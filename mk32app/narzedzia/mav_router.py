#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Rozgalezienie MAVLinka — prototyp serwera telemetrii z architektury klient-serwer.

Problem: jednostka naziemna MK32 wystawia telemetrie na JEDNYM porcie UDP i tylko jeden
program moze ja wygodnie trzymac. Chcemy naraz: kokpit na MK32, QGroundControl na laptopie,
przegladarke u kogos innego i zapis do pliku.

Rozwiazanie: jeden proces trzyma lacze w gore, a w dol obsluguje wielu klientow.
Docelowo ten sam mechanizm bedzie w aplikacji na MK32 (klasa MavlinkHub).
Ten skrypt to jego dzialajacy pierwowzor do testow z laptopa.

                        +-- UDP 14550 --> QGroundControl / Mission Planner
  MK32 :19856 --> [HUB] +-- TCP 5760  --> kolejni klienci (takze przez VPN)
                        +-- plik .tlog

WLADZA: domyslnie NIKT poza operatorem MK32 nie moze nadawac w gore. Przekazanie
sterowania stacji GCS jest decyzja swiadoma, odwracalna w kazdej chwili i wygasajaca
sama, gdy stacja zamilknie. Filtr jest tutaj, czyli po stronie MK32 — stacja nie moze
sobie sama nic przyznac.

Uzycie:
    python mav_router.py                                   # tylko podglad, nikt nie steruje
    python mav_router.py --sterowanie 192.168.144.30       # od razu oddane wskazanej stacji
    python mav_router.py --wladza-port 8099                # przekazywanie w czasie pracy
                                                           # (kamera osobno od lotu)
    python mav_router.py --tlog lot.tlog
    python mav_router.py --gora 127.0.0.1:14555            # inne zrodlo, np. symulator

Sterowanie wladza w czasie pracy (gdy podano --wladza-port):
    curl http://127.0.0.1:8099/stan
    curl http://127.0.0.1:8099/przekaz?klient=192.168.144.30
    curl http://127.0.0.1:8099/odbierz
    curl http://127.0.0.1:8099/odbierz-kamere     # sama kamera, bez ruszania lotu
    curl http://127.0.0.1:8099/przekaz-kamere

Dwa tory: komendy kamery (zoom, pitch/yaw glowicy, zdjecie, nagrywanie) maja wlasne
uprawnienie i domyslnie sa dozwolone; komendy lotu wymagaja przekazanej wladzy.
ROI nalezy do toru LOTU, bo na wielowirnikowcu obraca cala maszyne.
"""

import argparse
import collections
import json
import selectors
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

# Wspolny rejestr bledow lezy w tools\ (mk32app\dok\LOGI_I_BLEDY.md).
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[2] / "tools"))
from dziennik import zainstaluj  # noqa: E402

zainstaluj("mav_router")

try:
    from probe_endpoints import skanuj_ramki, skanuj_ramki_z_ladunkiem, heartbeat_gcs, NAZWY
except ImportError:
    print("Brak probe_endpoints.py obok tego pliku.")
    sys.exit(2)

GORA_DOMYSLNA = "192.168.144.12:19856"
KRESKA = "-" * 72

# Wiadomosci, ktore moga poruszyc maszyna. Sluza do raportowania, co przechodzi w gore.
KOMENDY = {11: "SET_MODE", 39: "MISSION_ITEM", 44: "MISSION_COUNT", 45: "MISSION_CLEAR_ALL",
           73: "MISSION_ITEM_INT", 75: "COMMAND_INT", 76: "COMMAND_LONG", 23: "PARAM_SET",
           82: "SET_ATTITUDE_TARGET", 84: "SET_POSITION_TARGET_LOCAL_NED",
           86: "SET_POSITION_TARGET_GLOBAL_INT", 70: "RC_CHANNELS_OVERRIDE",
           287: "GIMBAL_MANAGER_SET_PITCHYAW", 288: "GIMBAL_MANAGER_SET_MANUAL_CONTROL"}

# --- dwa tory uprawnien -------------------------------------------------------
# Kamera i lot to osobne zakresy wladzy (patrz dok\WLADZA.md sekcja 7).
# Wartosci MAV_CMD zweryfikowane w dialekcie ardupilotmega (pymavlink).
KAMERA_CMD = {
    205: "DO_MOUNT_CONTROL", 1000: "DO_GIMBAL_MANAGER_PITCHYAW",
    1001: "DO_GIMBAL_MANAGER_CONFIGURE", 530: "SET_CAMERA_MODE",
    531: "SET_CAMERA_ZOOM", 532: "SET_CAMERA_FOCUS",
    2000: "IMAGE_START_CAPTURE", 2001: "IMAGE_STOP_CAPTURE",
    2500: "VIDEO_START_CAPTURE", 2501: "VIDEO_STOP_CAPTURE",
    203: "DO_DIGICAM_CONTROL", 521: "REQUEST_CAMERA_INFORMATION",
}
KAMERA_MSGID = {287, 288}

# UWAGA: ROI NIE nalezy do toru kamery. Na wielowirnikowcu DO_SET_ROI obraca cala
# maszyne w strone celu, wiec jest komenda lotu.
ROI_CMD = {195: "DO_SET_ROI_LOCATION", 197: "DO_SET_ROI_NONE", 201: "DO_SET_ROI"}

# Pole 'command' lezy w ladunku COMMAND_LONG i COMMAND_INT pod tym samym przesunieciem
# (7 albo 4 wartosci po 4 bajty + x/y/z) — sprawdzone w ordered_fieldnames pymavlinka.
PRZESUNIECIE_CMD = 28


def rozpoznaj_tor(msgid, ladunek):
    """Zwraca ('kamera'|'lot'|'obojetne', opis)."""
    if msgid in KAMERA_MSGID:
        return "kamera", KOMENDY.get(msgid, "msgid %d" % msgid)
    if msgid in (75, 76) and len(ladunek) >= PRZESUNIECIE_CMD + 2:
        cmd = struct.unpack("<H", ladunek[PRZESUNIECIE_CMD:PRZESUNIECIE_CMD + 2])[0]
        if cmd in KAMERA_CMD:
            return "kamera", KAMERA_CMD[cmd]
        if cmd in ROI_CMD:
            return "lot", ROI_CMD[cmd] + " (obraca maszyne)"
        return "lot", "MAV_CMD %d" % cmd
    if msgid in KOMENDY:
        return "lot", KOMENDY[msgid]
    return "obojetne", ""


def uruchom_sterownik_wladzy(hub, port, tylko_lokalnie=True):
    """Maly interfejs HTTP, ktorym operator przekazuje i odbiera sterowanie.
    W docelowej aplikacji na MK32 zastapia go dwa przyciski na ekranie."""

    class Uchwyt(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def _odpowiedz(self, tresc, kod=200):
            dane = json.dumps(tresc, ensure_ascii=False).encode("utf-8")
            self.send_response(kod)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(dane)))
            self.end_headers()
            self.wfile.write(dane)

        def do_GET(self):
            adres = urlparse(self.path)
            pytanie = parse_qs(adres.query)
            if adres.path == "/stan":
                self._odpowiedz(hub.stan_wladzy())
            elif adres.path == "/przekaz":
                komu = (pytanie.get("klient") or [""])[0]
                if not komu:
                    self._odpowiedz({"blad": "podaj ?klient=ADRES_IP"}, 400)
                else:
                    self._odpowiedz(hub.przekaz_wladze(komu))
            elif adres.path == "/odbierz":
                self._odpowiedz(hub.odbierz_wladze())
            elif adres.path == "/odbierz-kamere":
                self._odpowiedz(hub.odbierz_kamere())
            elif adres.path == "/przekaz-kamere":
                self._odpowiedz(hub.przekaz_kamere((pytanie.get("klient") or ["wszyscy"])[0]))
            else:
                self._odpowiedz({"blad": "uzyj /stan, /przekaz?klient=IP, /odbierz,"
                                         " /odbierz-kamere, /przekaz-kamere"}, 404)

    host = "127.0.0.1" if tylko_lokalnie else "0.0.0.0"
    serwer = HTTPServer((host, port), Uchwyt)
    watek = threading.Thread(target=serwer.serve_forever, daemon=True)
    watek.start()
    print("Wladza: http://%s:%d/stan | /przekaz?klient=IP | /odbierz"
          " | /odbierz-kamere | /przekaz-kamere" % (host, port))
    return serwer


class Klient:
    def __init__(self, opis, adres, wysylka, steruje):
        self.opis = opis
        self.adres = adres
        self.wysylka = wysylka          # funkcja przyjmujaca bytes
        self.steruje = steruje
        self.w_dol = 0
        self.w_gore = 0
        self.odrzucone = 0
        self.ostatni = time.time()


class Hub:
    def __init__(self, gora, port_udp, port_tcp, sterowanie, tlog, wladza_timeout=3.0):
        host, port = gora.split(":")
        self.gora = (host, int(port))
        self.sterowanie = sterowanie
        self.sel = selectors.DefaultSelector()

        self.up = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.up.bind(("0.0.0.0", 0))
        self.up.setblocking(False)
        self.sel.register(self.up, selectors.EVENT_READ, self._z_gory)

        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.udp.bind(("0.0.0.0", port_udp))
        self.udp.setblocking(False)
        self.sel.register(self.udp, selectors.EVENT_READ, self._z_udp)

        self.tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.tcp.bind(("0.0.0.0", port_tcp))
        self.tcp.listen(8)
        self.tcp.setblocking(False)
        self.sel.register(self.tcp, selectors.EVENT_READ, self._nowe_tcp)

        self.klienci = {}
        self.zamek = threading.Lock()
        self.wladza = sterowanie
        self.wladza_od = time.time()
        self.wladza_ostatni_ruch = time.time()
        self.wladza_timeout = wladza_timeout
        self.ostatnia_nasza_komenda = 0.0
        self.obce_komendy = 0
        # Kamera: stacja ma ja od razu (decyzja z 2026-08-18). Lot: patrz self.wladza.
        self.kamera = "wszyscy"
        self.statystyki = collections.Counter()
        self.bajty_w_dol = 0
        self.seq = 0
        self.ostatni_hb = 0.0
        self.start = time.time()
        self.tlog = open(tlog, "wb") if tlog else None
        self.port_udp, self.port_tcp = port_udp, port_tcp

    # ------------------------------------------------------------ uprawnienia

    def czy_steruje(self, ip):
        """Czy adres ma prawo nadawac w gore. Stan zmienia sie w czasie pracy —
        operator MK32 przekazuje i odbiera wladze, kiedy chce."""
        with self.zamek:
            if self.wladza == "wszyscy":
                return True
            if self.wladza == "nikt":
                return False
            return ip in self.wladza

    def czy_kamera(self, ip):
        with self.zamek:
            if self.kamera == "wszyscy":
                return True
            if self.kamera == "nikt":
                return False
            return ip in self.kamera

    def przekaz_kamere(self, komu="wszyscy"):
        with self.zamek:
            self.kamera = komu if komu == "wszyscy" else [komu]
        print("[KAMERA] przekazana: %s" % komu)
        return {"kamera": komu}

    def odbierz_kamere(self):
        with self.zamek:
            self.kamera = "nikt"
        print("[KAMERA] ODEBRANA — glowica wraca do operatora MK32")
        return {"kamera": "nikt"}

    def przekaz_wladze(self, komu):
        with self.zamek:
            self.wladza = [komu]
            self.wladza_od = time.time()
            self.wladza_ostatni_ruch = time.time()
        self._przelicz_uprawnienia()
        print("[WLADZA] przekazana: %s" % komu)
        return {"wladza": komu}

    def odbierz_wladze(self, powod="decyzja operatora MK32"):
        with self.zamek:
            byla = self.wladza
            self.wladza = "nikt"
        if byla != "nikt":
            self._przelicz_uprawnienia()
            print("[WLADZA] ODEBRANA (%s) — sterowanie wraca do MK32" % powod)
        return {"wladza": "nikt", "powod": powod}

    def _przelicz_uprawnienia(self):
        """Po zmianie wladzy trzeba przestawic juz podlaczonych klientow."""
        for k in self.klienci.values():
            k.steruje = self.czy_steruje(k.adres[0])

    def stan_wladzy(self):
        with self.zamek:
            kto = self.wladza if isinstance(self.wladza, str) else self.wladza[0]
            od = None if self.wladza == "nikt" else round(time.time() - self.wladza_od, 1)
        with self.zamek:
            kam = self.kamera if isinstance(self.kamera, str) else self.kamera[0]
        return {"lot": kto, "kamera": kam, "od_sekund": od,
                "klientow": len(self.klienci),
                "uwaga": "operator MK32 moze odebrac lot i kamere osobno, w kazdej chwili"}

    # ------------------------------------------------------------ kierunek w dol

    def _z_gory(self, gniazdo):
        try:
            dane, _ = gniazdo.recvfrom(8192)
        except OSError:
            return
        self.bajty_w_dol += len(dane)
        for msgid, _, _, ladunek in skanuj_ramki_z_ladunkiem(dane)[0]:
            self.statystyki[msgid] += 1
            if msgid == 77:
                self._sprawdz_obce_zrodlo(ladunek)
        if self.tlog:
            self.tlog.write(struct.pack(">Q", int(time.time() * 1e6)) + dane)
        martwi = []
        for klucz, k in self.klienci.items():
            try:
                k.wysylka(dane)
                k.w_dol += 1
            except OSError:
                martwi.append(klucz)
        for klucz in martwi:
            self._usun(klucz)

    # ------------------------------------------------------------ kierunek w gore

    def _w_gore(self, k, dane):
        """Filtr dziala na POJEDYNCZYCH RAMKACH, nie na calym datagramie — inaczej
        jedna komenda lotu w strumieniu blokowalaby caly ruch kamery i odwrotnie."""
        przepuszczone, komenda_poszla = [], False
        for msgid, ladunek, surowe in self._ramki_surowe(dane):
            tor, opis = rozpoznaj_tor(msgid, ladunek)
            wolno = (k.steruje if tor == "lot" else
                     self.czy_kamera(k.adres[0]) if tor == "kamera" else True)
            if not wolno:
                k.odrzucone += 1
                if k.odrzucone in (1, 5, 25, 100):
                    print("[!] ODRZUCONO od %s: %s — brak uprawnien do toru '%s'"
                          % (k.opis, opis, tor))
                continue
            przepuszczone.append(surowe)
            if tor != "obojetne":
                komenda_poszla = True
        ladunek_wyjsciowy = b"".join(przepuszczone)
        if not ladunek_wyjsciowy:
            return
        self.up.sendto(ladunek_wyjsciowy, self.gora)
        k.w_gore += 1
        self.wladza_ostatni_ruch = time.time()
        if komenda_poszla:
            self.ostatnia_nasza_komenda = time.time()

    @staticmethod
    def _ramki_surowe(dane):
        """Wyplywa (msgid, ladunek, surowe_bajty) dla kolejnych ramek — kazda ze swojego
        miejsca w buforze. Rozroznianie po msgid nie wystarcza: dwie rozne komendy
        COMMAND_LONG maja ten sam msgid 76."""
        i = 0
        while i < len(dane):
            if dane[i] == 0xFD and len(dane) - i >= 12:
                dlugosc = dane[i + 1]
                calosc = 12 + dlugosc
                if len(dane) - i < calosc:
                    return
                msgid = dane[i + 7] | (dane[i + 8] << 8) | (dane[i + 9] << 16)
                yield msgid, dane[i + 10:i + 10 + dlugosc], dane[i:i + calosc]
                i += calosc
            elif dane[i] == 0xFE and len(dane) - i >= 8:
                dlugosc = dane[i + 1]
                calosc = 8 + dlugosc
                if len(dane) - i < calosc:
                    return
                yield dane[i + 5], dane[i + 6:i + 6 + dlugosc], dane[i:i + calosc]
                i += calosc
            else:
                i += 1

    def _z_udp(self, gniazdo):
        try:
            dane, adres = gniazdo.recvfrom(8192)
        except OSError:
            return
        klucz = ("udp", adres)
        k = self.klienci.get(klucz)
        if k is None:
            k = Klient("UDP %s:%d" % adres, adres,
                       lambda b, a=adres: self.udp.sendto(b, a), self.czy_steruje(adres[0]))
            self.klienci[klucz] = k
            print("[+] klient %s — sterowanie: %s" % (k.opis, "TAK" if k.steruje else "nie"))
        k.ostatni = time.time()
        self._w_gore(k, dane)

    def _nowe_tcp(self, gniazdo):
        polaczenie, adres = gniazdo.accept()
        polaczenie.setblocking(False)
        polaczenie.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        klucz = ("tcp", adres)
        k = Klient("TCP %s:%d" % adres, adres, polaczenie.sendall, self.czy_steruje(adres[0]))
        self.klienci[klucz] = k
        self.sel.register(polaczenie, selectors.EVENT_READ,
                          lambda g, kl=klucz: self._z_tcp(g, kl))
        print("[+] klient %s — sterowanie: %s" % (k.opis, "TAK" if k.steruje else "nie"))

    def _z_tcp(self, gniazdo, klucz):
        try:
            dane = gniazdo.recv(8192)
        except OSError:
            dane = b""
        if not dane:
            self.sel.unregister(gniazdo)
            gniazdo.close()
            self._usun(klucz)
            return
        k = self.klienci.get(klucz)
        if k:
            k.ostatni = time.time()
            self._w_gore(k, dane)

    def _usun(self, klucz):
        k = self.klienci.pop(klucz, None)
        if k:
            print("[-] odszedl %s" % k.opis)

    # ------------------------------------------------------------ petla

    def pracuj(self, raport_co):
        print(KRESKA)
        print("HUB MAVLink")
        print("  w gore : UDP %s:%d" % self.gora)
        print("  w dol  : UDP 0.0.0.0:%d, TCP 0.0.0.0:%d" % (self.port_udp, self.port_tcp))
        st = self.stan_wladzy()
        print("  wladza lot: %s   kamera: %s" % (st["lot"], st["kamera"]))
        print("  powrot wladzy po ciszy stacji: %.0f s" % self.wladza_timeout)
        print(KRESKA)
        print("W QGroundControl / Mission Plannerze dodaj polaczenie UDP na port %d" % self.port_udp)
        print("albo TCP na port %d, wskazujac adres tego komputera.\n" % self.port_tcp)

        ostatni_raport = time.time()
        try:
            while True:
                teraz = time.time()
                if teraz - self.ostatni_hb >= 1.0:
                    self.up.sendto(heartbeat_gcs(self.seq), self.gora)
                    self.seq += 1
                    self.ostatni_hb = teraz
                for klucz, _ in self.sel.select(timeout=0.2):
                    klucz.data(klucz.fileobj)
                # sprzatanie martwych klientow UDP
                for klucz in [k for k, v in self.klienci.items()
                              if k[0] == "udp" and teraz - v.ostatni > 15]:
                    self._usun(klucz)
                self._sprawdz_czy_stacja_zyje(teraz)
                if teraz - ostatni_raport >= raport_co:
                    self._raport()
                    ostatni_raport = teraz
        except KeyboardInterrupt:
            print("\nkoniec")
        finally:
            if self.tlog:
                self.tlog.close()

    def _sprawdz_obce_zrodlo(self, ladunek):
        """COMMAND_ACK bez naszej komendy znaczy, ze komende wydal ktos inny —
        z pominieciem tego filtra. Nie da sie temu zapobiec na wspolnej sieci,
        ale da sie to zobaczyc i zglosic. Patrz dok/WLADZA.md, sekcja 9."""
        if len(ladunek) < 2:
            return
        komenda = struct.unpack("<H", ladunek[:2])[0]
        if time.time() - self.ostatnia_nasza_komenda < 5.0:
            return
        self.obce_komendy += 1
        if self.obce_komendy in (1, 5, 20, 100):
            print("[!!] OBCE ZRODLO KOMEND: potwierdzenie MAV_CMD %d, a przez ten filtr"
                  " nic nie przeszlo. Ktos wydaje komendy z pominieciem MK32." % komenda)

    def _sprawdz_czy_stacja_zyje(self, teraz):
        """Wladza wygasa sama, gdy stacja przestaje sie odzywac. Bez tego zerwane
        lacze zostawiloby sterowanie u kogos, kto go juz nie ma."""
        if self.wladza in ("nikt", "wszyscy") or self.wladza_timeout <= 0:
            return
        zywi = [k for k in self.klienci.values()
                if k.adres[0] in self.wladza and teraz - k.ostatni <= self.wladza_timeout]
        if not zywi:
            self.odbierz_wladze("stacja zamilkla na ponad %.0f s" % self.wladza_timeout)

    def _raport(self):
        czas = time.time() - self.start
        razem = sum(self.statystyki.values())
        print("%s  ramek %d (%.1f/s), %.1f kB/s w dol, klientow %d, wladza: %s"
              % (time.strftime("%H:%M:%S"), razem, razem / czas,
                 self.bajty_w_dol / 1024.0 / czas, len(self.klienci),
                 "lot=%s kamera=%s" % (self.stan_wladzy()["lot"], self.stan_wladzy()["kamera"])))
        if razem == 0:
            print("     brak danych z gory — sprawdz SIYI TX -> Datalink -> Connection = UDP")
        if self.obce_komendy:
            print("     [!!] obce komendy do maszyny: %d — ktos omija ten filtr" % self.obce_komendy)
        for k in self.klienci.values():
            print("     %-24s w dol %6d   w gore %5d   odrzucone %5d"
                  % (k.opis, k.w_dol, k.w_gore, k.odrzucone))


def main():
    p = argparse.ArgumentParser(description="Rozgalezienie MAVLinka dla wielu klientow")
    p.add_argument("--gora", default=GORA_DOMYSLNA, help="zrodlo telemetrii host:port")
    p.add_argument("--port-udp", type=int, default=14550)
    p.add_argument("--port-tcp", type=int, default=5760)
    p.add_argument("--sterowanie", default="nikt",
                   help="stan poczatkowy wladzy: 'nikt' (domyslnie), 'wszyscy' albo adresy IP")
    p.add_argument("--wladza-port", type=int,
                   help="port sterownika przekazywania wladzy (HTTP, tylko 127.0.0.1)")
    p.add_argument("--wladza-timeout", type=float, default=3.0,
                   help="po ilu sekundach ciszy stacji wladza wraca do MK32 (0 = nigdy)")
    p.add_argument("--tlog", help="zapis surowego strumienia do pliku")
    p.add_argument("--raport-co", type=float, default=5.0)
    a = p.parse_args()

    sterowanie = a.sterowanie
    if sterowanie not in ("nikt", "wszyscy"):
        sterowanie = [s.strip() for s in sterowanie.split(",") if s.strip()]

    hub = Hub(a.gora, a.port_udp, a.port_tcp, sterowanie, a.tlog, a.wladza_timeout)
    if a.wladza_port:
        uruchom_sterownik_wladzy(hub, a.wladza_port)
    hub.pracuj(a.raport_co)
    return 0


if __name__ == "__main__":
    sys.exit(main())
