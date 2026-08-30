#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Klient SIYI Gimbal SDK po UDP — ZR30 pod 192.168.144.25:37260.

Po co: dowód, ze glowica da sie sterowac Z POMINIECIEM kontrolera lotu.
W projekcie DRON 15 tor powrotny ZR30 -> FC byl zrodlem klopotow (poz. 28 w CLAUDE.md),
a ta sciezka w ogole go nie uzywa. To samo bedzie robila aplikacja na MK32.

Uruchamiac z laptopa (192.168.144.30) wpietego w siec pokladowa, albo z MK32 przez termux/adb.

Uzycie:
    python siyi_gimbal.py --selftest            # sprawdza CRC bez sprzetu
    python siyi_gimbal.py version               # wersje firmware
    python siyi_gimbal.py attitude              # orientacja glowicy
    python siyi_gimbal.py config                # tryb pracy, stan nagrywania
    python siyi_gimbal.py zoom in|out|stop
    python siyi_gimbal.py abszoom 4.5
    python siyi_gimbal.py center
    python siyi_gimbal.py angle --yaw 0 --pitch -30
    python siyi_gimbal.py rotate --yaw 30 --pitch 0 --time 1.0
    python siyi_gimbal.py codec --strumien glowny
    python siyi_gimbal.py setcodec --kodek H264 --rozdzielczosc 1280x720 --bitrate 2000 --ruch

Komendy ruchu wymagaja flagi --ruch (zabezpieczenie przed przypadkowym poruszeniem glowicy).

Protokol: instrukcja ZR30 v1.4, rozdz. 3.5. CRC-16/XMODEM, mlodszy bajt naprzod.
"""

import argparse
import socket
import struct
import sys
import time

# Wspolny rejestr bledow lezy w tools\ (mk32app\dok\LOGI_I_BLEDY.md).
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[2] / "tools"))
from dziennik import zainstaluj  # noqa: E402

zainstaluj("siyi_gimbal")

HOST_DOMYSLNY = "192.168.144.25"
PORT_DOMYSLNY = 37260

STX = b"\x55\x66"


# ---------------------------------------------------------------- ramka

def crc16_xmodem(data: bytes) -> int:
    crc = 0x0000
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def zbuduj(cmd_id: int, dane: bytes = b"", seq: int = 0, ctrl: int = 1) -> bytes:
    """ctrl=1 znaczy 'potrzebuje ACK' w przykladach producenta."""
    rdzen = STX + bytes([ctrl]) + struct.pack("<H", len(dane)) + struct.pack("<H", seq) + bytes([cmd_id]) + dane
    return rdzen + struct.pack("<H", crc16_xmodem(rdzen))


def rozbierz(ramka: bytes):
    """Zwraca (cmd_id, dane) albo rzuca ValueError."""
    if len(ramka) < 10 or ramka[0:2] != STX:
        raise ValueError("to nie jest ramka SIYI: " + ramka[:12].hex())
    dlugosc = struct.unpack("<H", ramka[3:5])[0]
    koniec = 8 + dlugosc
    if len(ramka) < koniec + 2:
        raise ValueError("ramka urwana")
    oczekiwane = struct.unpack("<H", ramka[koniec:koniec + 2])[0]
    policzone = crc16_xmodem(ramka[:koniec])
    if oczekiwane != policzone:
        raise ValueError("bledne CRC: w ramce %04x, policzone %04x" % (oczekiwane, policzone))
    return ramka[7], ramka[8:koniec]


# ---------------------------------------------------------------- transport

class Glowica:
    def __init__(self, host=HOST_DOMYSLNY, port=PORT_DOMYSLNY, timeout=1.5):
        self.adres = (host, port)
        self.gniazdo = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.gniazdo.settimeout(timeout)
        self.timeout = timeout
        self.seq = 0

    def wyslij(self, cmd_id, dane=b"", czekaj_na_odpowiedz=True):
        # ⚠ Odpowiedz musi byc odpowiedzia NA TO pytanie. Do 2026-08-26 brano pierwszy
        # datagram, jaki przyszedl — a gdy kokpit odpytuje glowice rownolegle (0x0D co
        # 200 ms), trafialy tu cudze ramki i rozbieralismy je jak wlasne. Objaw: trzy
        # odczyty 0x0A pod rzad dawaly 'Follow', 'Lock' i znowu 'Follow', z hdr=255
        # i montaz=0, czyli wartosciami, ktorych ten protokol nie zna.
        self.seq = (self.seq + 1) & 0xFFFF
        ramka = zbuduj(cmd_id, dane, self.seq)
        self.gniazdo.sendto(ramka, self.adres)
        if not czekaj_na_odpowiedz:
            return None
        koniec = time.monotonic() + self.timeout
        while time.monotonic() < koniec:
            self.gniazdo.settimeout(max(0.05, koniec - time.monotonic()))
            try:
                odp, _ = self.gniazdo.recvfrom(1024)
            except socket.timeout:
                return None
            wynik = rozbierz(odp)
            if wynik is None:
                continue
            if wynik[0] == cmd_id:          # tylko odpowiedz na TO pytanie
                return wynik
        return None


# ---------------------------------------------------------------- komendy

def wersja(g):
    o = g.wyslij(0x01)
    if not o:
        return None
    _, d = o
    if len(d) < 12:
        return d.hex()
    kod, kamera, gimbal = struct.unpack("<III", d[:12])
    return {"zoom_fw": hex(kod), "kamera_fw": hex(kamera), "gimbal_fw": hex(gimbal)}


def orientacja(g):
    o = g.wyslij(0x0D)
    if not o:
        return None
    _, d = o
    if len(d) < 12:
        return d.hex()
    yaw, pitch, roll, vy, vp, vr = struct.unpack("<hhhhhh", d[:12])
    return {"yaw": yaw / 10.0, "pitch": pitch / 10.0, "roll": roll / 10.0,
            "yaw_v": vy / 10.0, "pitch_v": vp / 10.0, "roll_v": vr / 10.0}


TRYBY = {0: "Lock", 1: "Follow", 2: "FPV"}
NAGRYWANIE = {0: "wylaczone", 1: "TRWA", 2: "brak karty TF", 3: "blad zapisu na TF"}


def konfiguracja(g):
    o = g.wyslij(0x0A)
    if not o:
        return None
    _, d = o
    if len(d) < 7:
        return d.hex()
    return {"hdr": d[1], "nagrywanie": NAGRYWANIE.get(d[3], d[3]),
            "tryb_ruchu": TRYBY.get(d[4], d[4]), "montaz": d[5]}


def zoom(g, kierunek):
    mapa = {"in": 1, "stop": 0, "out": -1}
    o = g.wyslij(0x05, struct.pack("<b", mapa[kierunek]))
    if o and len(o[1]) >= 2:
        return {"krotnosc": struct.unpack("<H", o[1][:2])[0] / 10.0}
    return None


def abszoom(g, krotnosc):
    calosc = int(krotnosc)
    ulamek = int(round((krotnosc - calosc) * 10))
    return g.wyslij(0x0F, bytes([calosc, ulamek]))


def centruj(g):
    return g.wyslij(0x08, bytes([1]))


def kat(g, yaw, pitch):
    o = g.wyslij(0x0E, struct.pack("<hh", int(round(yaw * 10)), int(round(pitch * 10))))
    if o and len(o[1]) >= 6:
        y, p, r = struct.unpack("<hhh", o[1][:6])
        return {"yaw": y / 10.0, "pitch": p / 10.0, "roll": r / 10.0}
    return None


def obroc(g, yaw, pitch, czas):
    g.wyslij(0x07, struct.pack("<bb", int(yaw), int(pitch)), czekaj_na_odpowiedz=False)
    time.sleep(czas)
    return g.wyslij(0x07, struct.pack("<bb", 0, 0))


KODEKI = {1: "H264", 2: "H265"}
STRUMIENIE = {"nagrywanie": 0, "glowny": 1, "podglad": 2}


def kodek(g, strumien="glowny"):
    """CMD 0x20 — odczyt parametrow kodowania wskazanego strumienia."""
    o = g.wyslij(0x20, bytes([STRUMIENIE[strumien]]))
    if not o:
        return None
    _, d = o
    if len(d) < 9:
        return d.hex()
    typ, enc = d[0], d[1]
    szer, wys, bitrate = struct.unpack("<HHH", d[2:8])
    return {"strumien": typ, "kodek": KODEKI.get(enc, enc), "rozdzielczosc": "%dx%d" % (szer, wys),
            "bitrate_kbps": bitrate, "klatki": d[8]}


def ustaw_kodek(g, strumien, enc, szer, wys, bitrate):
    """CMD 0x21 — ustawienie kodeka. Kluczowe dla retransmisji: H264 720p da sie
    przepakowac bez transkodowania i wysylac dalej praktycznie kazda droga."""
    dane = bytes([STRUMIENIE[strumien], enc]) + struct.pack("<HHH", szer, wys, bitrate) + bytes([0])
    o = g.wyslij(0x21, dane)
    if o and len(o[1]) >= 2:
        return {"strumien": o[1][0], "wynik": "OK" if o[1][1] == 1 else "ODRZUCONE"}
    return None


def foto(g):
    return g.wyslij(0x0C, bytes([0]), czekaj_na_odpowiedz=False)


def nagrywanie(g):
    return g.wyslij(0x0C, bytes([2]), czekaj_na_odpowiedz=False)


# ---------------------------------------------------------------- selftest

PRZYKLADY = [
    ("5566010000000040", "819c", "Request Hardware ID"),
    ("5566010000000019", "5d57", "Request Working Mode"),
    ("5566010000000016", "b2a6", "Request Max Zoom"),
    ("5566010000000018", "7c47", "Request Zoom Value"),
]


def selftest():
    """Sprawdza implementacje CRC wobec przykladow z instrukcji ZR30 v1.4."""
    ok = True
    for ramka_hex, crc_hex, opis in PRZYKLADY:
        policzone = crc16_xmodem(bytes.fromhex(ramka_hex))
        oczekiwane = int(crc_hex[0:2], 16) | (int(crc_hex[2:4], 16) << 8)
        zgoda = policzone == oczekiwane
        ok = ok and zgoda
        print("%-4s %s  policzone=%04x  z instrukcji=%04x  (%s)" %
              ("OK" if zgoda else "BLAD", ramka_hex, policzone, oczekiwane, opis))
    print("\nWynik: %s" % ("wszystkie zgodne" if ok else "SA ROZBIEZNOSCI"))
    return 0 if ok else 1


# ---------------------------------------------------------------- CLI

def main():
    p = argparse.ArgumentParser(description="Klient SIYI Gimbal SDK (ZR30) po UDP")
    p.add_argument("komenda", nargs="?", default="version",
                   choices=["version", "attitude", "config", "zoom", "abszoom",
                            "center", "angle", "rotate", "photo", "rec",
                            "codec", "setcodec"])
    p.add_argument("wartosc", nargs="?", help="dla zoom: in|out|stop; dla abszoom: krotnosc np. 4.5")
    p.add_argument("--host", default=HOST_DOMYSLNY)
    p.add_argument("--port", type=int, default=PORT_DOMYSLNY)
    p.add_argument("--yaw", type=float, default=0.0)
    p.add_argument("--pitch", type=float, default=0.0)
    p.add_argument("--time", type=float, default=1.0, help="czas obrotu dla 'rotate' [s]")
    p.add_argument("--strumien", default="glowny", choices=["nagrywanie", "glowny", "podglad"])
    p.add_argument("--kodek", default="H264", choices=["H264", "H265"])
    p.add_argument("--rozdzielczosc", default="1280x720")
    p.add_argument("--bitrate", type=int, default=2000, help="kbps")
    p.add_argument("--ruch", action="store_true", help="zgoda na komendy poruszajace glowica")
    p.add_argument("--selftest", action="store_true", help="sprawdz CRC bez sprzetu i wyjdz")
    a = p.parse_args()

    if a.selftest:
        return selftest()

    ruchowe = {"zoom", "abszoom", "center", "angle", "rotate", "photo", "rec", "setcodec"}
    if a.komenda in ruchowe and not a.ruch:
        print("Komenda '%s' porusza glowica albo wyzwala nagrywanie." % a.komenda)
        print("Dodaj --ruch, jesli tego chcesz. Upewnij sie, ze glowica ma wolna przestrzen.")
        return 2

    g = Glowica(a.host, a.port)
    print("-> %s:%d" % (a.host, a.port))

    if a.komenda == "version":
        w = wersja(g)
    elif a.komenda == "attitude":
        w = orientacja(g)
    elif a.komenda == "config":
        w = konfiguracja(g)
    elif a.komenda == "zoom":
        w = zoom(g, a.wartosc or "stop")
    elif a.komenda == "abszoom":
        w = abszoom(g, float(a.wartosc or "1"))
    elif a.komenda == "center":
        w = centruj(g)
    elif a.komenda == "angle":
        w = kat(g, a.yaw, a.pitch)
    elif a.komenda == "rotate":
        w = obroc(g, a.yaw, a.pitch, a.time)
    elif a.komenda == "photo":
        w = foto(g)
    elif a.komenda == "rec":
        w = nagrywanie(g)
    elif a.komenda == "codec":
        w = kodek(g, a.strumien)
    elif a.komenda == "setcodec":
        szer, wys = (int(x) for x in a.rozdzielczosc.lower().split("x"))
        w = ustaw_kodek(g, a.strumien, 1 if a.kodek == "H264" else 2, szer, wys, a.bitrate)
    else:
        w = None

    if w is None:
        print("Brak odpowiedzi w zadanym czasie.")
        print("Sprawdz: zasilanie glowicy, kabel LAN, adres 192.168.144.25, ping.")
        return 1
    print(w)
    return 0


if __name__ == "__main__":
    sys.exit(main())
