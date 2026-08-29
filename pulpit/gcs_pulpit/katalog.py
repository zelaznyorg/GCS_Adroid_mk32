"""Katalog aplikacji — jeden plik w katalogu to jeden kafelek.

Dodanie aplikacji ma nie wymagać zmiany kodu, budowania ani restartu. Wrzucasz
plik do `/etc/gcs/aplikacje.d/`, kafelek pojawia się od razu.

Najkrótszy możliwy wpis — wskazanie istniejącego skrótu `.desktop`:

    {"nazwa": "ATAK-CIV", "desktop": "waydroid.com.atakmap.app.civ"}

Pełny, gdy chcesz sterować wszystkim:

    {
      "nazwa": "KAMERA CVBS + HUD",
      "opis": "Podgląd wejścia analogowego z OSD",
      "grupa": "obraz",
      "uruchom": ["/usr/local/bin/hdmi-cvbs-camera"],
      "wymaga": {"plik": "/usr/local/bin/hdmi-cvbs-camera"},
      "pelny-ekran": true,
      "kolejnosc": 10
    }

Pola `uruchom` **albo** `desktop` — jedno z nich musi być. Reszta jest opcjonalna.
"""

from __future__ import annotations

import configparser
import json
import logging
import os
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

KATALOGI = [
    Path(os.getenv("GCS_APLIKACJE", "/etc/gcs/aplikacje.d")),
    Path.home() / ".config" / "gcs" / "aplikacje.d",
]
KATALOGI_DESKTOP = [
    Path.home() / ".local" / "share" / "applications",
    Path("/usr/local/share/applications"),
    Path("/usr/share/applications"),
]

# Znaczniki, które freedesktop wstawia do Exec= — dla nas są śmieciem.
_ZNACZNIKI_EXEC = re.compile(r"%[fFuUdDnNickvm]")

log = logging.getLogger("gcs.pulpit.katalog")


@dataclass
class Aplikacja:
    id: str
    nazwa: str
    polecenie: list[str]
    opis: str = ""
    grupa: str = ""
    ikona: str | None = None
    pelny_ekran: bool = False
    # "wlasne" = aplikacja sama obsługuje pokrętło, pilot jest zbędny i szkodliwy
    pokretlo: str = ""
    # Identyfikator okna dla `wlrctl` przy "pelny-ekran". Domyślnie zgadujemy
    # z polecenia; podaje się go wtedy, gdy zgadywanie nie trafia.
    okno: str = ""
    kolejnosc: int = 500
    wymaga: dict = field(default_factory=dict)
    zrodlo: Path | None = None

    # wypełniane przy odświeżaniu
    dostepna: bool = True
    powod: str = ""


# ---- czytanie skrótów .desktop -------------------------------------------


def _znajdz_desktop(nazwa: str) -> Path | None:
    # Skrót wolno wskazać także ścieżką — na tej malinie część leży na pulpicie.
    if "/" in nazwa:
        sciezka = Path(nazwa).expanduser()
        return sciezka if sciezka.is_file() else None
    plik = nazwa if nazwa.endswith(".desktop") else f"{nazwa}.desktop"
    for katalog in KATALOGI_DESKTOP:
        kandydat = katalog / plik
        if kandydat.is_file():
            return kandydat
    return None


def czytaj_desktop(nazwa: str) -> tuple[list[str], str, str | None] | None:
    """Zwraca (polecenie, opis, ikona) ze skrótu .desktop albo None."""
    sciezka = _znajdz_desktop(nazwa)
    if sciezka is None:
        log.warning("Nie znalazłem skrótu %s w %s", nazwa, KATALOGI_DESKTOP)
        return None
    parser = configparser.ConfigParser(interpolation=None, strict=False)
    try:
        parser.read(sciezka, encoding="utf-8")
        wpis = parser["Desktop Entry"]
    except (configparser.Error, KeyError):
        log.warning("Skrót %s jest nieczytelny", sciezka)
        return None

    exec_ = wpis.get("Exec", "").strip()
    if not exec_:
        return None
    exec_ = _ZNACZNIKI_EXEC.sub("", exec_).strip()
    # Cudzysłowy w Exec= są częste (--app="http://…") i shlex je poprawnie zdejmie.
    import shlex

    try:
        polecenie = shlex.split(exec_)
    except ValueError:
        polecenie = exec_.split()
    return polecenie, wpis.get("Comment", "").strip(), wpis.get("Icon") or None


# ---- wczytanie katalogu ---------------------------------------------------


def _z_pliku(sciezka: Path) -> Aplikacja | None:
    try:
        dane = json.loads(sciezka.read_text(encoding="utf-8"))
    except (OSError, ValueError) as blad:
        log.warning("Pomijam %s — %s", sciezka.name, blad)
        return None

    nazwa = str(dane.get("nazwa") or "").strip()
    if not nazwa:
        log.warning("Pomijam %s — brak pola „nazwa”", sciezka.name)
        return None

    polecenie = dane.get("uruchom")
    opis = str(dane.get("opis") or "")
    ikona = dane.get("ikona")

    if not polecenie and dane.get("desktop"):
        odczyt = czytaj_desktop(str(dane["desktop"]))
        if odczyt is None:
            log.warning("Pomijam %s — skrót %s nieosiągalny", sciezka.name, dane["desktop"])
            return None
        polecenie, opis_desktop, ikona_desktop = odczyt
        opis = opis or opis_desktop
        ikona = ikona or ikona_desktop

    if not polecenie:
        log.warning("Pomijam %s — ani „uruchom”, ani „desktop”", sciezka.name)
        return None
    if isinstance(polecenie, str):
        import shlex

        polecenie = shlex.split(polecenie)

    # Prefiks w nazwie pliku („10-kamera.json”) wyznacza kolejność, jeśli nie podano.
    domyslna_kolejnosc = 500
    if (m := re.match(r"^(\d+)", sciezka.stem)) is not None:
        domyslna_kolejnosc = int(m.group(1))

    return Aplikacja(
        id=sciezka.stem,
        nazwa=nazwa,
        polecenie=[str(c) for c in polecenie],
        opis=opis,
        grupa=str(dane.get("grupa") or ""),
        ikona=ikona,
        pelny_ekran=bool(dane.get("pelny-ekran", False)),
        pokretlo=str(dane.get("pokretlo") or ""),
        okno=str(dane.get("okno") or ""),
        kolejnosc=int(dane.get("kolejnosc", domyslna_kolejnosc)),
        wymaga=dict(dane.get("wymaga") or {}),
        zrodlo=sciezka,
    )


def wczytaj(katalogi: list[Path] | None = None) -> list[Aplikacja]:
    """Wczytuje wszystkie wpisy. Późniejszy katalog nadpisuje wcześniejszy po `id`."""
    wynik: dict[str, Aplikacja] = {}
    for katalog in katalogi if katalogi is not None else KATALOGI:
        if not katalog.is_dir():
            continue
        for sciezka in sorted(katalog.glob("*.json")):
            aplikacja = _z_pliku(sciezka)
            if aplikacja is not None:
                wynik[aplikacja.id] = aplikacja
    lista = sorted(wynik.values(), key=lambda a: (a.kolejnosc, a.nazwa))
    for aplikacja in lista:
        aplikacja.dostepna, aplikacja.powod = sprawdz(aplikacja)
    return lista


# ---- warunki --------------------------------------------------------------


def _usluga_dziala(nazwa: str) -> bool:
    try:
        wynik = subprocess.run(
            ["systemctl", "is-active", "--quiet", nazwa], timeout=5, check=False
        )
        return wynik.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def sprawdz(aplikacja: Aplikacja) -> tuple[bool, str]:
    """Kafelek nieczynny z powodem jest uczciwszy niż uruchamianie czegoś,
    co i tak nie wstanie."""
    wymaga = aplikacja.wymaga

    if (plik := wymaga.get("plik")) and not Path(plik).exists():
        return False, f"brak {plik}"

    if usluga := wymaga.get("usluga"):
        if not _usluga_dziala(str(usluga)):
            return False, f"usługa {usluga} nie działa"

    if (polecenie := wymaga.get("polecenie")) and shutil.which(str(polecenie)) is None:
        return False, f"brak polecenia {polecenie}"

    if aplikacja.polecenie:
        program = aplikacja.polecenie[0]
        if "/" in program:
            if not Path(program).exists():
                return False, f"brak {program}"
        elif shutil.which(program) is None:
            return False, f"brak polecenia {program}"

    return True, ""
