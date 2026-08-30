#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Etap M0 — weryfikacja trzech laczy, zanim powstanie pierwsza linia Kotlina.

Sprawdza po kolei:
  1. TELEMETRIA  UDP 192.168.144.12:19856  — czy leci MAVLink, jakie wiadomosci, z jaka czestotliwoscia
  2. WIDEO       RTSP 192.168.144.25:8554  — czy serwer odpowiada na OPTIONS i DESCRIBE
  3. GLOWICA     UDP 192.168.144.25:37260  — czy ZR30 odpowiada na komende 0x01 (wersja firmware)

Uruchamiac z komputera wpietego w siec pokladowa (zalecany adres 192.168.144.30)
albo bezposrednio na MK32.

WARUNEK dla punktu 1: w aplikacji SIYI TX na MK32 musi byc ustawione
  Datalink -> Connection = UDP, Flight Controller = PX4/ArduPilot, Baud Rate = 115200

Nic nie zapisuje i nie porusza sprzetem. Do telemetrii wysyla wylacznie heartbeat GCS
— bez tego serwer UDP w jednostce naziemnej nie wie, dokad odsylac dane.

Uzycie:
    python probe_endpoints.py                 # wszystkie trzy, 10 s nasluchu
    python probe_endpoints.py --sekundy 30
    python probe_endpoints.py --tylko mavlink
"""

import argparse
import collections
import socket
import struct
import sys
import time

# Wspolny rejestr bledow lezy w tools\ (mk32app\dok\LOGI_I_BLEDY.md).
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[2] / "tools"))
from dziennik import zainstaluj  # noqa: E402

zainstaluj("probe_endpoints")

GROUND_UNIT = "192.168.144.12"
PORT_TELEMETRII = 19856
KAMERA = "192.168.144.25"
PORT_RTSP = 8554
PORT_SDK = 37260

KRESKA = "-" * 72


# ---------------------------------------------------------------- MAVLink

NAZWY = {
    0: "HEARTBEAT", 1: "SYS_STATUS", 2: "SYSTEM_TIME", 22: "PARAM_VALUE",
    24: "GPS_RAW_INT", 27: "RAW_IMU", 29: "SCALED_PRESSURE", 30: "ATTITUDE",
    32: "LOCAL_POSITION_NED", 33: "GLOBAL_POSITION_INT", 35: "RC_CHANNELS_RAW",
    36: "SERVO_OUTPUT_RAW", 42: "MISSION_CURRENT", 62: "NAV_CONTROLLER_OUTPUT",
    65: "RC_CHANNELS", 74: "VFR_HUD", 77: "COMMAND_ACK", 111: "TIMESYNC",
    116: "SCALED_IMU2", 125: "POWER_STATUS", 129: "SCALED_IMU3", 136: "TERRAIN_REPORT",
    147: "BATTERY_STATUS", 152: "MEMINFO", 163: "AHRS", 165: "HWSTATUS",
    178: "AHRS2", 182: "AHRS3", 193: "EKF_STATUS_REPORT", 241: "VIBRATION",
    242: "HOME_POSITION", 253: "STATUSTEXT", 109: "RADIO_STATUS",
}


def crc_akumuluj(bajt, crc):
    tmp = bajt ^ (crc & 0xFF)
    tmp = (tmp ^ (tmp << 4)) & 0xFF
    return ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF


def mav_crc(dane, crc_extra):
    crc = 0xFFFF
    for b in dane:
        crc = crc_akumuluj(b, crc)
    return crc_akumuluj(crc_extra, crc)


def heartbeat_gcs(seq):
    """MAVLink2 HEARTBEAT: typ 6 = GCS, autopilot 8 = INVALID. CRC_EXTRA dla HEARTBEAT = 50."""
    ladunek = struct.pack("<IBBBBB", 0, 6, 8, 0, 4, 3)
    naglowek = struct.pack("<BBBBBBB", len(ladunek), 0, 0, seq & 0xFF, 255, 190, 0) + b"\x00\x00"
    # naglowek: len, incompat, compat, seq, sysid, compid, msgid(3 bajty LE) = 0
    rdzen = naglowek + ladunek
    return b"\xFD" + rdzen + struct.pack("<H", mav_crc(rdzen, 50))


def skanuj_ramki_z_ladunkiem(bufor):
    """Zwraca liste (msgid, sysid, compid, ladunek) i reszte bufora.
    Bez sprawdzania CRC — to sonda, nie dekoder produkcyjny."""
    znalezione = []
    i = 0
    while i < len(bufor):
        b = bufor[i]
        if b == 0xFD and len(bufor) - i >= 12:
            dlugosc = bufor[i + 1]
            calosc = 12 + dlugosc
            if len(bufor) - i < calosc:
                break
            msgid = bufor[i + 7] | (bufor[i + 8] << 8) | (bufor[i + 9] << 16)
            znalezione.append((msgid, bufor[i + 5], bufor[i + 6], bufor[i + 10:i + 10 + dlugosc]))
            i += calosc
        elif b == 0xFE and len(bufor) - i >= 8:
            dlugosc = bufor[i + 1]
            calosc = 8 + dlugosc
            if len(bufor) - i < calosc:
                break
            znalezione.append((bufor[i + 5], bufor[i + 3], bufor[i + 4], bufor[i + 6:i + 6 + dlugosc]))
            i += calosc
        else:
            i += 1
    return znalezione, bufor[i:]


def skanuj_ramki(bufor):
    """Wariant bez ladunku — zgodny z wczesniejszym uzyciem."""
    ramki, reszta = skanuj_ramki_z_ladunkiem(bufor)
    return [(m, s, c) for m, s, c, _ in ramki], reszta


def probuj_mavlink(sekundy):
    print(KRESKA)
    print("1. TELEMETRIA — UDP %s:%d, nasluch %d s" % (GROUND_UNIT, PORT_TELEMETRII, sekundy))
    print(KRESKA)
    g = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    g.settimeout(0.5)
    try:
        g.bind(("0.0.0.0", 0))
    except OSError as e:
        print("Nie udalo sie otworzyc gniazda: %s" % e)
        return False

    licznik = collections.Counter()
    nadawcy = collections.Counter()
    bajty = 0
    bufor = b""
    seq = 0
    start = time.time()
    ostatni_hb = 0.0

    while time.time() - start < sekundy:
        teraz = time.time()
        if teraz - ostatni_hb >= 1.0:
            try:
                g.sendto(heartbeat_gcs(seq), (GROUND_UNIT, PORT_TELEMETRII))
                seq += 1
            except OSError as e:
                print("Blad wysylania heartbeatu: %s" % e)
            ostatni_hb = teraz
        try:
            paczka, _ = g.recvfrom(4096)
        except socket.timeout:
            continue
        bajty += len(paczka)
        bufor += paczka
        ramki, bufor = skanuj_ramki(bufor)
        for msgid, sysid, _ in ramki:
            licznik[msgid] += 1
            nadawcy[sysid] += 1

    czas = time.time() - start
    if not licznik:
        print("BRAK DANYCH.")
        print("Sprawdz kolejno:")
        print("  - SIYI TX -> Datalink -> Connection = UDP, FC = PX4/ArduPilot, 115200")
        print("  - czy komputer jest w sieci 192.168.144.0/24 (ping %s)" % GROUND_UNIT)
        print("  - czy air unit ma zasilanie (na samym USB w FC nie ma lacza radiowego)")
        print("  - czy nie dziala rownolegle SIYI FPV albo QGC, ktore trzymaja polaczenie")
        g.close()
        return False

    print("Odebrano %d ramek, %.1f kB, %.1f kB/s" % (sum(licznik.values()), bajty / 1024.0, bajty / 1024.0 / czas))
    print("Nadawcy (sysid): %s" % dict(nadawcy))
    print()
    print("%-24s %8s %9s" % ("wiadomosc", "sztuk", "Hz"))
    for msgid, ile in licznik.most_common():
        print("%-24s %8d %9.1f" % (NAZWY.get(msgid, "msgid %d" % msgid), ile, ile / czas))
    print()
    print("WNIOSEK: lacze telemetryczne dziala. Czestotliwosci wynikaja z SR1_* na FC")
    print("         (przy SERIAL6_OPTIONS=4096 zadania stawek z GCS sa ignorowane).")
    g.close()
    return True


# ---------------------------------------------------------------- RTSP

def probuj_rtsp(sciezka="video1"):
    print(KRESKA)
    print("2. WIDEO — RTSP %s:%d/%s" % (KAMERA, PORT_RTSP, sciezka))
    print(KRESKA)
    url = "rtsp://%s:%d/%s" % (KAMERA, PORT_RTSP, sciezka)
    try:
        s = socket.create_connection((KAMERA, PORT_RTSP), timeout=3)
    except OSError as e:
        print("Brak polaczenia TCP: %s" % e)
        print("Sprawdz zasilanie ZR30 i kabel LAN (ping %s)." % KAMERA)
        return False
    s.settimeout(3)
    try:
        for nr, metoda in ((1, "OPTIONS"), (2, "DESCRIBE")):
            zadanie = "%s %s RTSP/1.0\r\nCSeq: %d\r\nUser-Agent: dron15-probe\r\n" % (metoda, url, nr)
            if metoda == "DESCRIBE":
                zadanie += "Accept: application/sdp\r\n"
            s.sendall((zadanie + "\r\n").encode())
            odp = s.recv(8192).decode("utf-8", "replace")
            pierwsza = odp.split("\r\n")[0]
            print("%-9s -> %s" % (metoda, pierwsza))
            if metoda == "DESCRIBE":
                for linia in odp.split("\r\n"):
                    if linia.startswith(("m=", "a=rtpmap", "a=fmtp", "a=control")):
                        print("            %s" % linia)
    except OSError as e:
        print("Blad wymiany RTSP: %s" % e)
        return False
    finally:
        s.close()
    print()
    print("WNIOSEK: sprawdz linie 'a=rtpmap' — H265 znaczy, ze do odtwarzania potrzebny")
    print("         jest libVLC; ExoPlayer z RTSP i HEVC bywa zawodny.")
    return True


# ---------------------------------------------------------------- glowica

def probuj_glowice():
    print(KRESKA)
    print("3. GLOWICA — UDP %s:%d (SIYI Gimbal SDK)" % (KAMERA, PORT_SDK))
    print(KRESKA)
    try:
        from siyi_gimbal import Glowica, wersja, orientacja, konfiguracja
    except ImportError:
        print("Brak siyi_gimbal.py obok tego pliku.")
        return False
    g = Glowica(KAMERA, PORT_SDK)
    w = wersja(g)
    if w is None:
        print("Brak odpowiedzi na 0x01.")
        print("Sprawdz zasilanie ZR30 i kabel LAN. Uwaga: to lacze NIE przechodzi przez FC,")
        print("wiec stan MNT1_TYPE i SERIAL2 nie ma tu zadnego znaczenia.")
        return False
    print("wersja firmware : %s" % w)
    print("orientacja      : %s" % orientacja(g))
    print("konfiguracja    : %s" % konfiguracja(g))
    print()
    print("WNIOSEK: glowica jest sterowalna z pominieciem kontrolera lotu.")
    print("         To jest fundament ekranu KAMERA w aplikacji (poz. 28 w CLAUDE.md).")
    return True


# ---------------------------------------------------------------- main

def main():
    p = argparse.ArgumentParser(description="M0 — weryfikacja laczy dla aplikacji na MK32")
    p.add_argument("--sekundy", type=int, default=10, help="czas nasluchu telemetrii")
    p.add_argument("--tylko", choices=["mavlink", "rtsp", "gimbal"], help="uruchom jedna probe")
    p.add_argument("--sciezka", default="video1", help="sciezka RTSP: video1 (4K) albo video2 (podglad)")
    a = p.parse_args()

    print("DRON15 Cockpit — sonda laczy, %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    wyniki = {}
    if a.tylko in (None, "mavlink"):
        wyniki["telemetria"] = probuj_mavlink(a.sekundy)
        print()
    if a.tylko in (None, "rtsp"):
        wyniki["wideo"] = probuj_rtsp(a.sciezka)
        print()
    if a.tylko in (None, "gimbal"):
        wyniki["glowica"] = probuj_glowice()
        print()

    print(KRESKA)
    print("PODSUMOWANIE")
    for nazwa, ok in wyniki.items():
        print("  %-12s %s" % (nazwa, "OK" if ok else "BRAK"))
    print(KRESKA)
    return 0 if all(wyniki.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
