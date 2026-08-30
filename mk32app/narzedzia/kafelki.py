#!/usr/bin/env python3
"""Kafelki mapy i dane wysokosciowe dla kokpitu DRON15.

Kokpit rysuje podklad z kafelkow XYZ lezacych na karcie TF, **z podzialem na warstwy**:

    /sdcard/dron15/kafelki/{warstwa}/{z}/{x}/{y}.{png|jpg}
    /sdcard/dron15/teren/{z}/{x}/{y}.png            — dane wysokosciowe (Terrarium, zawsze PNG)

Warstwy skladaja sie na podklady widoczne w aplikacji (ui/Podklady.kt):

    hybryda = zdjecia + opisy + drogi     ← podklad OBOWIAZKOWY, domyslny
    zdjecia = samo zdjecie lotnicze
    topo    = mapa topograficzna z warstwicami (lot na azymut)
    mapa    = mapa kreskowa OSM
    noc     = ciemna mapa kreskowa

Uzycie:

    # komplet pod jeden rejon lotow: hybryda + topo + teren
    python kafelki.py --lat 52.1234 --lon 20.1234 --promien 3 --zoom 13-17

    # tylko dane wysokosciowe (widok 3D, warstwice, przeswit nad terenem)
    python kafelki.py --lat 52.1234 --lon 20.1234 --promien 5 --tylko-teren

    # wgranie na aparature (kafelki + teren)
    python kafelki.py --wgraj

    # co juz lezy w katalogu
    python kafelki.py --stan

    # przeniesienie starego, bezimiennego ukladu karty do warstwy "zdjecia"
    python kafelki.py --migruj

Rozmiar: rejon 3 km przy z13-17 to ok. 700 kafelkow na warstwe, czyli ok. 25 MB.
Hybryda to trzy warstwy, ale `opisy` i `drogi` sa lekkie (przezroczyste PNG, 1-4 kB).
Dane wysokosciowe pobieramy tylko na z12 — model ma ok. 30 m rozdzielczosci, wiec glebiej
nie ma czego szukac; caly rejon 5 km to zwykle 4 kafelki.

UWAGA o zrodlach. Serwer OpenStreetMap zabrania masowego pobierania. Dla wlasnego rejonu
lotow (kilkaset kafelkow, raz) miesci sie to w granicach rozsadku; przy wiekszych obszarach
uzyc wlasnego serwera kafelkow albo gotowej paczki (--zrodlo).
"""

import argparse
import json
import math
import os
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request

# Wspolny rejestr bledow lezy w tools\ (mk32app\dok\LOGI_I_BLEDY.md).
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[2] / "tools"))
from dziennik import zainstaluj  # noqa: E402

zainstaluj("kafelki")

# Uwaga praktyczna, sprawdzona 2026-08-19: serwer tile.openstreetmap.org odsyla obrazek
# "Access blocked" zamiast mapy, gdy uzna pobieranie za masowe — i robi to z kodem 200,
# wiec bledu nie widac, dopoki kafelek nie trafi na ekran.
#
# Kolejnosc {z}/{y}/{x} u Esri jest odwrotna niz w XYZ — dlatego kazde zrodlo ma wlasny wzor.
# Wszystkie adresy sprawdzone 2026-08-25 (HTTP 200). Esri odsyla zdjecia jako JPEG,
# nakladki i mapy kreskowe jako PNG — rozszerzenie ustala sie z tresci, nie z adresu.
WARSTWY = {
    "zdjecia": {
        "url": "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "opis": "zdjecia lotnicze Esri World Imagery",
    },
    "opisy": {
        "url": "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}",
        "opis": "nazwy miejscowosci i granice (przezroczysta nakladka hybrydy)",
    },
    "drogi": {
        "url": "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}",
        "opis": "drogi i koleje (przezroczysta nakladka hybrydy)",
    },
    "topo": {
        "url": "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        "opis": "mapa topograficzna z warstwicami — do lotu na azymut",
    },
    "mapa": {
        "url": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "opis": "mapa kreskowa OpenStreetMap",
    },
    "noc": {
        "url": "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "opis": "ciemna mapa kreskowa CARTO",
    },
}

# Podklady w aplikacji -> warstwy, ktore trzeba miec na karcie.
PODKLADY = {
    "hybryda": ["zdjecia", "opisy", "drogi"],
    "zdjecia": ["zdjecia"],
    "topo": ["topo"],
    "mapa": ["mapa"],
    "noc": ["noc"],
}

# Domyslnie bierzemy hybryde (obowiazkowa) i topo (azymut). Reszta na zyczenie.
DOMYSLNE_PODKLADY = "hybryda,topo"

# Dane wysokosciowe: PNG-i Terrarium, h = (R*256 + G + B/256) - 32768.
# Sprawdzone 2026-08-25: kafelek 12/2264/1366 daje 165-191 m n.p.m. w rejonie Radomska.
ZRODLA_TERENU = {
    "terrarium": "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",
    "terrarium-alt": "https://elevation-tiles-prod.s3.amazonaws.com/terrarium/{z}/{x}/{y}.png",
}

# Model ma ok. 30 m rozdzielczosci; z12 to ok. 24 m/px na 52 stopniu. Glebiej to sama waga pliku.
ZOOM_TERENU = 12

# Znane wielkosci obrazka "Access blocked" z OSM — taki kafelek jest bezuzyteczny,
# wiec lepiej go nie zapisac, niz odkryc go dopiero w powietrzu.
MINIMALNY_ROZMIAR = 400

NAGLOWEK = {"User-Agent": "DRON15-cockpit/0.2 (offline map cache for one flight area)"}

# Esri odsyla zdjecia lotnicze jako JPEG, mimo ze adres konczy sie na kafelku bez rozszerzenia.
# Zapisujemy z rozszerzeniem zgodnym z trescia — Android i tak rozpoznaje format po naglowku,
# ale plik nazwany "png" z JPEG-iem w srodku myli kazdego, kto zajrzy na karte.
ROZSZERZENIA = (
    (b"\x89PNG\r\n\x1a\n", "png"),
    (b"\xff\xd8\xff", "jpg"),
    (b"RIFF", "webp"),
)


def rozszerzenie(dane):
    for naglowek, ext in ROZSZERZENIA:
        if dane.startswith(naglowek):
            return ext
    return None

KORZEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
KATALOG_KAFELKOW = os.path.join(KORZEN, "kafelki")
KATALOG_TERENU = os.path.join(KORZEN, "teren")
CEL_BAZA = "/sdcard/dron15"
CEL_KAFELKI = CEL_BAZA + "/kafelki"
CEL_TEREN = CEL_BAZA + "/teren"
MANIFEST = "manifest.json"


def kafelek(lat, lon, z):
    """Wspolrzedne kafelka XYZ dla punktu — wzor jak w Kafelki.kt po stronie aplikacji."""
    n = 2.0 ** z
    x = int((lon + 180.0) / 360.0 * n)
    s = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(s)) / math.pi) / 2.0 * n)
    return x, y


def zakres_zoomow(tekst):
    if "-" in tekst:
        a, b = tekst.split("-")
        return list(range(int(a), int(b) + 1))
    return [int(tekst)]


def plan_kafelkow(lat, lon, promien_km, zoomy):
    """Prostokat kafelkow wokol punktu, dla kazdego zadanego powiekszenia."""
    # 1 stopien szerokosci to ok. 111,32 km; dlugosc skraca sie z cosinusem szerokosci
    dlat = promien_km / 111.32
    dlon = promien_km / (111.32 * math.cos(math.radians(lat)))
    plan = []
    for z in zoomy:
        x1, y1 = kafelek(lat + dlat, lon - dlon, z)
        x2, y2 = kafelek(lat - dlat, lon + dlon, z)
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                plan.append((z, x, y))
    return plan


def sciagnij(url, przerwa):
    """Zwraca bajty albo None. None znaczy 'nie zapisuj' — nie 'przerwij'."""
    zadanie = urllib.request.Request(url, headers=NAGLOWEK)
    with urllib.request.urlopen(zadanie, timeout=25) as odp:
        dane = odp.read()
    if len(dane) < MINIMALNY_ROZMIAR or b"Access blocked" in dane[:2000]:
        return None
    time.sleep(przerwa)          # nie zalewamy serwera
    return dane


def juz_jest(sciezka, y):
    """Kafelek moze lezec pod dowolnym z obslugiwanych rozszerzen (tak samo czyta go kokpit)."""
    for _, ext in ROZSZERZENIA:
        plik = os.path.join(sciezka, "%d.%s" % (y, ext))
        if os.path.isfile(plik) and os.path.getsize(plik) > 0:
            return True
    return False


def pobierz_warstwe(nazwa, wzor, plan, katalog, przerwa, tylko_png=False):
    """
    `tylko_png` dla danych wysokosciowych: wysokosc siedzi w dokladnej barwie piksela,
    wiec kompresja stratna (JPEG) zamienilaby model terenu na szum. Lepiej nie zapisac
    takiego kafelka, niz policzyc z niego przeswit nad zboczem.
    """
    nowe = pominiete = bledy = 0
    puste = zle_format = 0
    for i, (z, x, y) in enumerate(plan, 1):
        sciezka = os.path.join(katalog, str(z), str(x))
        if juz_jest(sciezka, y):
            pominiete += 1
            continue
        os.makedirs(sciezka, exist_ok=True)
        try:
            dane = sciagnij(wzor.format(z=z, x=x, y=y), przerwa)
            if dane is None:
                puste += 1
                continue
            ext = rozszerzenie(dane)
            if ext is None:
                puste += 1
                continue
            if tylko_png and ext != "png":
                zle_format += 1
                continue
            with open(os.path.join(sciezka, "%d.%s" % (y, ext)), "wb") as f:
                f.write(dane)
            nowe += 1
        except Exception as e:
            bledy += 1
            if bledy <= 3:
                print("    blad %d/%d/%d: %s" % (z, x, y, e))
        if i % 100 == 0:
            print("    %d / %d" % (i, len(plan)))
    if puste:
        print("    %d kafelkow serwer odeslal jako zastepcze — sprawdz --zrodlo" % puste)
    if zle_format:
        print("    %d kafelkow terenu przyszlo w formacie stratnym — ODRZUCONE "
              "(wysokosc siedzi w barwie piksela)" % zle_format)
    print("  %-8s nowe: %-5d juz byly: %-5d bledy: %d" % (nazwa, nowe, pominiete, bledy))
    return nowe, pominiete, bledy


def pobierz(args):
    zoomy = zakres_zoomow(args.zoom)
    plan = plan_kafelkow(args.lat, args.lon, args.promien, zoomy)

    warstwy = []
    if not args.tylko_teren:
        for podklad in [p.strip() for p in args.podklady.split(",") if p.strip()]:
            if podklad not in PODKLADY:
                print("Nieznany podklad: %s (znane: %s)" % (podklad, ", ".join(PODKLADY)))
                return 1
            for w in PODKLADY[podklad]:
                if w not in warstwy:
                    warstwy.append(w)

    razem = len(plan) * len(warstwy)
    print("Rejon: %.5f %.5f, promien %.1f km, zoom %s" % (args.lat, args.lon, args.promien, args.zoom))
    if warstwy:
        print("Warstwy: %s" % ", ".join(warstwy))
        print("Do pobrania: %d kafelkow na warstwe, %d razem" % (len(plan), razem))
    if razem > args.limit:
        print("To wiecej niz limit %d. Zmniejsz promien albo zoom, "
              "albo podnies --limit swiadomie." % args.limit)
        return 1

    for nazwa in warstwy:
        wzor = WARSTWY[nazwa]["url"]
        pobierz_warstwe(nazwa, wzor, plan, os.path.join(os.path.abspath(args.katalog), nazwa),
                        args.przerwa)

    if not args.bez_terenu:
        pobierz_teren(args)

    zapisz_manifest(args, warstwy)
    print("Katalog kafelkow: %s" % os.path.abspath(args.katalog))
    print("Katalog terenu:   %s" % os.path.abspath(args.katalog_terenu))
    return 0


def pobierz_teren(args):
    """Dane wysokosciowe — jeden poziom, bo model i tak ma ok. 30 m."""
    plan = plan_kafelkow(args.lat, args.lon, args.promien, [args.zoom_terenu])
    wzor = ZRODLA_TERENU.get(args.zrodlo_terenu, args.zrodlo_terenu)
    print("Teren (z%d): %d kafelkow" % (args.zoom_terenu, len(plan)))
    nowe, pominiete, bledy = pobierz_warstwe(
        "teren", wzor, plan, os.path.abspath(args.katalog_terenu), args.przerwa,
        tylko_png=True)
    if bledy and not nowe and not pominiete:
        print("  ZADEN kafelek terenu sie nie pobral — sprobuj --zrodlo-teren terrarium-alt")
    return 0


def zapisz_manifest(args, warstwy):
    """
    Co i skad lezy na karcie. Aplikacja tego nie czyta — czyta go czlowiek, ktory za pol
    roku bedzie chcial wiedziec, czy podklad obejmuje nowy rejon lotow i skad pochodzi.
    """
    sciezka = os.path.join(os.path.abspath(args.katalog), MANIFEST)
    dane = {}
    if os.path.isfile(sciezka):
        try:
            with open(sciezka, encoding="utf-8") as f:
                dane = json.load(f)
        except Exception:
            dane = {}
    rejony = dane.get("rejony", [])
    rejony.append({
        "lat": args.lat,
        "lon": args.lon,
        "promien_km": args.promien,
        "zoom": args.zoom,
        "warstwy": warstwy,
        "teren": (not args.bez_terenu),
        "pobrano": time.strftime("%Y-%m-%d %H:%M"),
    })
    dane["rejony"] = rejony
    dane["zrodla"] = {n: WARSTWY[n]["url"] for n in warstwy}
    if not args.bez_terenu:
        dane["zrodlo_terenu"] = ZRODLA_TERENU.get(args.zrodlo_terenu, args.zrodlo_terenu)
    dane["uwaga"] = ("Kafelki pobrane do wlasnego uzytku w jednym rejonie lotow. "
                     "Warunki uzycia naleza do wystawcy kazdej warstwy.")
    os.makedirs(os.path.dirname(sciezka), exist_ok=True)
    with open(sciezka, "w", encoding="utf-8") as f:
        json.dump(dane, f, ensure_ascii=False, indent=2)


def stan(args):
    """Co juz lezy w katalogach — zanim wyjedzie sie w teren, nie po."""
    def opisz(korzen, tytul):
        korzen = os.path.abspath(korzen)
        print("%s (%s)" % (tytul, korzen))
        if not os.path.isdir(korzen):
            print("  brak katalogu")
            return
        for nazwa in sorted(os.listdir(korzen)):
            sciezka = os.path.join(korzen, nazwa)
            if not os.path.isdir(sciezka):
                continue
            ile = 0
            waga = 0
            poziomy = []
            for z in sorted(os.listdir(sciezka)):
                if not z.isdigit():
                    continue
                poziomy.append(int(z))
                for katalog, _, pliki in os.walk(os.path.join(sciezka, z)):
                    for p in pliki:
                        ile += 1
                        waga += os.path.getsize(os.path.join(katalog, p))
            if ile:
                print("  %-10s %5d kafelkow, %6.1f MB, poziomy %s"
                      % (nazwa, ile, waga / 1e6, "-".join(str(p) for p in
                                                          (min(poziomy), max(poziomy)))))

    opisz(args.katalog, "KAFELKI MAPY")
    print()
    korzen_terenu = os.path.abspath(args.katalog_terenu)
    if os.path.isdir(korzen_terenu):
        ile = waga = 0
        for katalog, _, pliki in os.walk(korzen_terenu):
            for p in pliki:
                ile += 1
                waga += os.path.getsize(os.path.join(katalog, p))
        print("TEREN (%s)\n  %d kafelkow, %.1f MB" % (korzen_terenu, ile, waga / 1e6))
    else:
        print("TEREN (%s)\n  brak — widok 3D, warstwice i przeswit nie policza sie" % korzen_terenu)

    print()
    brak = [p for p, ws in PODKLADY.items()
            if not all(os.path.isdir(os.path.join(os.path.abspath(args.katalog), w)) for w in ws)]
    if brak:
        print("Podklady bez kompletu warstw: %s" % ", ".join(brak))
        if "hybryda" in brak:
            print("  UWAGA: brakuje podkladu OBOWIAZKOWEGO (hybryda)")
    else:
        print("Wszystkie podklady maja komplet warstw.")
    return 0


def migruj(args):
    """
    Stary uklad karty (`kafelki/{z}/{x}/{y}`) przenosi do `kafelki/zdjecia/{z}/...`.

    Kokpit czyta stary uklad tak czy owak — traktuje go jako warstwe `zdjecia`. Migracja
    jest po to, zeby po dolozeniu drugiej warstwy nie siedziec z polowa karty w jednym
    ukladzie, a polowa w drugim; to sie mysli przy nastepnym pobieraniu, nie w locie.
    """
    korzen = os.path.abspath(args.katalog)
    poziomy = [n for n in os.listdir(korzen)
               if n.isdigit() and os.path.isdir(os.path.join(korzen, n))] \
        if os.path.isdir(korzen) else []
    if not poziomy:
        print("Nic do przeniesienia — %s jest juz w ukladzie warstwowym." % korzen)
        return 0
    cel = os.path.join(korzen, "zdjecia")
    os.makedirs(cel, exist_ok=True)
    print("Przenosze %d poziomow do %s" % (len(poziomy), cel))
    for z in sorted(poziomy, key=int):
        zrodlo = os.path.join(korzen, z)
        docelowy = os.path.join(cel, z)
        if os.path.exists(docelowy):
            print("  z%s: katalog docelowy juz istnieje — pomijam" % z)
            continue
        os.rename(zrodlo, docelowy)
        print("  z%s -> zdjecia/%s" % (z, z))
    print("Gotowe. Sprawdz: python kafelki.py --stan")
    return 0


# Sciezka na aparaturze, pod ktora ladujace archiwum czeka na rozpakowanie.
ARCHIWUM_NA_KARCIE = "/sdcard/dron15_kafelki.tar"


def _policz_pliki(katalog):
    return sum(len(p) for _, _, p in os.walk(katalog))


def wgraj(args):
    """Wysyla kafelki i teren na aparature **jednym archiwum**.

    ⛔ Nie uzywac `adb push KATALOG/. CEL`. Przy kilkuset drobnych plikach adb przewraca sie
    na `libc++abi: std::bad_alloc` po kilku minutach i **nie zostawia na karcie ani jednego
    pliku** — narzut na plik zjada pamiec samego adb. Zmierzone 2026-08-26: 671 plikow,
    awaria po 9 min 29 s, zero plikow na miejscu.

    Aparatura ma `/system/bin/tar`, wiec wlasciwa droga to jeden plik: spakowac, wyslac,
    rozpakowac na urzadzeniu, skasowac archiwum. Te same dane przechodza w **0,3 s**.
    """
    adb = args.adb

    zrodla = []
    for zrodlo, nazwa in ((args.katalog, "kafelki"), (args.katalog_terenu, "teren")):
        katalog = os.path.abspath(zrodlo)
        if not os.path.isdir(katalog):
            print("Pomijam %s — katalogu nie ma." % katalog)
            continue
        zrodla.append((katalog, nazwa))

    if not zrodla:
        print("Nie ma czego wyslac.")
        return 1

    razem = sum(_policz_pliki(k) for k, _ in zrodla)
    print("Pakuje %d plikow z %d katalogow..." % (razem, len(zrodla)))

    uchwyt, lokalne = tempfile.mkstemp(prefix="dron15_kafelki_", suffix=".tar")
    os.close(uchwyt)
    try:
        with tarfile.open(lokalne, "w") as paczka:
            for katalog, nazwa in zrodla:
                paczka.add(katalog, arcname=nazwa)
        wielkosc = os.path.getsize(lokalne) / (1024.0 * 1024.0)
        print("Archiwum %.1f MB — wysylam..." % wielkosc)

        # Kolejnosc ma znaczenie: katalog docelowy musi istniec, zanim tar zacznie pisac.
        subprocess.run([adb, "shell", "mkdir", "-p", CEL_BAZA], check=False)

        wynik = subprocess.run([adb, "push", lokalne, ARCHIWUM_NA_KARCIE])
        if wynik.returncode != 0:
            print("adb push nie powiodl sie — czy aparatura jest podpieta?")
            return wynik.returncode

        print("Rozpakowuje na aparaturze...")
        polecenie = "cd %s && tar -xf %s && rm -f %s && echo ROZPAKOWANE" % (
            CEL_BAZA, ARCHIWUM_NA_KARCIE, ARCHIWUM_NA_KARCIE)
        wynik = subprocess.run([adb, "shell", polecenie],
                               capture_output=True, text=True)
        wyjscie = (wynik.stdout or "") + (wynik.stderr or "")
        if "ROZPAKOWANE" not in wyjscie:
            print("Rozpakowanie nie powiodlo sie:")
            print(wyjscie.strip() or "(brak odpowiedzi)")
            # Archiwum zostaje na karcie, zeby dalo sie rozpakowac recznie.
            print("Archiwum lezy na aparaturze: %s" % ARCHIWUM_NA_KARCIE)
            return wynik.returncode or 1
    finally:
        try:
            os.remove(lokalne)
        except OSError:
            pass

    print("Gotowe: %d plikow. Kokpit zobaczy podklad po ponownym wejsciu na mape." % razem)
    return 0


def main():
    p = argparse.ArgumentParser(
        description="Kafelki mapy i dane wysokosciowe dla kokpitu DRON15",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Warstwy: " + "\n         ".join(
            "%-8s %s" % (n, w["opis"]) for n, w in WARSTWY.items()),
    )
    p.add_argument("--lat", type=float, help="szerokosc srodka rejonu")
    p.add_argument("--lon", type=float, help="dlugosc srodka rejonu")
    p.add_argument("--promien", type=float, default=3.0, help="promien w km (domyslnie 3)")
    p.add_argument("--zoom", default="13-17", help="zakres powiekszen mapy, np. 13-17")
    p.add_argument("--podklady", default=DOMYSLNE_PODKLADY,
                   help="ktore podklady pobrac, po przecinku: " + ", ".join(PODKLADY))
    p.add_argument("--zrodlo", action="append", metavar="WARSTWA=URL",
                   help="podmiana adresu jednej warstwy, np. topo=https://.../{z}/{x}/{y}.png")
    p.add_argument("--bez-terenu", action="store_true",
                   help="nie pobieraj danych wysokosciowych")
    p.add_argument("--tylko-teren", action="store_true",
                   help="pobierz wylacznie dane wysokosciowe")
    p.add_argument("--zoom-terenu", type=int, default=ZOOM_TERENU,
                   help="powiekszenie danych wysokosciowych (domyslnie %d)" % ZOOM_TERENU)
    p.add_argument("--zrodlo-teren", dest="zrodlo_terenu", default="terrarium",
                   help="terrarium | terrarium-alt | wlasny wzor URL")
    p.add_argument("--katalog", default=KATALOG_KAFELKOW)
    p.add_argument("--katalog-terenu", dest="katalog_terenu", default=KATALOG_TERENU)
    p.add_argument("--limit", type=int, default=12000, help="zabezpieczenie przed pomylka")
    p.add_argument("--przerwa", type=float, default=0.12, help="odstep miedzy pobraniami [s]")
    p.add_argument("--wgraj", action="store_true", help="wyslij katalogi na MK32 przez ADB")
    p.add_argument("--stan", action="store_true", help="pokaz, co juz lezy w katalogach")
    p.add_argument("--migruj", action="store_true",
                   help="przenies stary uklad kafelki/{z}/... do kafelki/zdjecia/{z}/...")
    p.add_argument("--adb", default=r"C:\Android\platform-tools\adb.exe")
    a = p.parse_args()

    for podmiana in (a.zrodlo or []):
        if "=" not in podmiana:
            p.error("--zrodlo oczekuje postaci WARSTWA=URL")
        nazwa, url = podmiana.split("=", 1)
        if nazwa not in WARSTWY:
            p.error("nieznana warstwa: %s" % nazwa)
        WARSTWY[nazwa]["url"] = url

    if a.migruj:
        return migruj(a)
    if a.wgraj:
        return wgraj(a)
    if a.stan:
        return stan(a)
    if a.lat is None or a.lon is None:
        p.error("podaj --lat i --lon albo uzyj --wgraj / --stan")
    if a.tylko_teren:
        a.bez_terenu = False
    return pobierz(a)


if __name__ == "__main__":
    sys.exit(main())
