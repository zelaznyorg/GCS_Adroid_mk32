#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - podsluch surowych bajtow na wskazanym porcie FC (przelotka szeregowa).

    python tools\\fc_serial_sniff.py 7 --yes     # podsluch SERIAL7

Po co: rozstrzyga, czy urzadzenie na danym UART cokolwiek nadaje - bez
interpretowania protokolu.  Odpowiada na pytanie "kabel czy konfiguracja".

Jak dziala:
  1. zwalnia port (SERIALn_PROTOCOL=-1) i restartuje FC, zeby zaden sterownik
     nie zjadal bajtow
  2. wlacza przelotke ArduPilota: SERIAL_PASS1=0 (USB), SERIAL_PASS2=n
     SERIAL_PASSTIMO=15 - przelotka SAMA sie wylaczy po 15 s ciszy na USB,
     wiec nie da sie zablokowac lacza na stale
  3. czyta COM9 jako surowy port szeregowy i szuka sygnatur
  4. przywraca poprzednia konfiguracje

Sygnatury:
  55 66  -> SIYI SDK (STX=0x6655, mlodszy bajt z przodu; instrukcja ZR30 str. 46)
  FD     -> MAVLink v2      FE -> MAVLink v1

UWAGA: ZAPISUJE DO FC i RESTARTUJE go dwa razy.
"""

import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_serial_sniff")

from pymavlink import mavutil
import serial
from serial.tools import list_ports

COM = "COM9"
BAUD = 115200


def log(m=""):
    print(m, flush=True)


def polacz(timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if COM not in [p.device for p in list_ports.comports()]:
            time.sleep(0.5)
            continue
        try:
            m = mavutil.mavlink_connection(COM, baud=BAUD)
            if m.wait_heartbeat(timeout=10) is not None:
                return m
            m.close()
        except Exception:
            time.sleep(0.5)
    return None


def ustaw(m, nazwa, wartosc):
    m.mav.param_set_send(m.target_system, m.target_component,
                         nazwa.encode("ascii"), float(wartosc),
                         mavutil.mavlink.MAV_PARAM_TYPE_REAL32)
    t0 = time.time()
    while time.time() - t0 < 4:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
        if msg is None:
            continue
        pid = msg.param_id
        if isinstance(pid, bytes):
            pid = pid.decode("ascii", "replace")
        if pid.rstrip("\x00") == nazwa:
            return msg.param_value
    return None


def main():
    if "--yes" not in sys.argv or len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    nr = int([a for a in sys.argv[1:] if a.isdigit()][0])

    log("=" * 66)
    log("DRON 15 - podsluch surowych bajtow na SERIAL%d" % nr)
    log("=" * 66)

    m = polacz()
    if m is None:
        log("BRAK POLACZENIA"); sys.exit(1)

    stary = None
    for _ in range(3):
        m.mav.param_request_read_send(m.target_system, m.target_component,
                                      ("SERIAL%d_PROTOCOL" % nr).encode(), -1)
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=3)
        if msg:
            stary = msg.param_value
            break
    log("SERIAL%d_PROTOCOL przed testem: %s" % (nr, stary))

    log("\n[1] zwalniam port i restartuje FC...")
    ustaw(m, "SERIAL%d_PROTOCOL" % nr, -1)
    m.mav.command_long_send(m.target_system, m.target_component,
                            mavutil.mavlink.MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN,
                            0, 1, 0, 0, 0, 0, 0, 0)
    try:
        m.close()
    except Exception:
        pass
    time.sleep(7)

    m = polacz()
    if m is None:
        log("FC nie wrocil"); sys.exit(1)

    log("[2] wlaczam przelotke: SERIAL_PASS1=0, SERIAL_PASS2=%d, timeout 15 s" % nr)
    ustaw(m, "SERIAL_PASSTIMO", 15)
    ustaw(m, "SERIAL_PASS1", 0)
    ustaw(m, "SERIAL_PASS2", nr)
    try:
        m.close()
    except Exception:
        pass
    time.sleep(1)

    log("[3] czytam surowe bajty z %s przez 12 s...\n" % COM)
    dane = b""
    try:
        s = serial.Serial(COM, BAUD, timeout=0.5)
        t0 = time.time()
        while time.time() - t0 < 12:
            dane += s.read(4096)
        s.close()
    except Exception as e:
        log("blad odczytu surowego: %s" % e)

    log("odebrano %d bajtow" % len(dane))
    siyi = dane.count(b"\x55\x66")
    mav2 = dane.count(b"\xfd")
    mav1 = dane.count(b"\xfe")
    log("  sygnatura SIYI  (55 66) : %d" % siyi)
    log("  MAVLink v2      (FD)    : %d" % mav2)
    log("  MAVLink v1      (FE)    : %d" % mav1)
    if dane:
        log("\n  pierwsze 96 bajtow:")
        log("  " + " ".join("%02X" % b for b in dane[:96]))

    log("\n[4] przywracam konfiguracje...")
    time.sleep(17)          # niech przelotka wygasnie sama
    m = polacz()
    if m is None:
        log("UWAGA: nie moge wrocic - przelotka moze byc aktywna, odczekaj i sprobuj ponownie")
        sys.exit(1)
    ustaw(m, "SERIAL_PASS2", -1)
    ustaw(m, "SERIAL_PASSTIMO", 15)
    if stary is not None:
        ustaw(m, "SERIAL%d_PROTOCOL" % nr, stary)
    log("    SERIAL_PASS2=-1, SERIAL%d_PROTOCOL=%s" % (nr, stary))

    log("\n" + "=" * 66)
    if siyi:
        log("WYNIK: GLOWICA NADAJE. Rozpoznano %d ramek SIYI." % siyi)
        log("Kabel i tor elektryczny sa DOBRE - problem jest po stronie")
        log("protokolu albo ustawien sterownika, nie okablowania.")
    elif len(dane) > 0 and (mav2 or mav1):
        log("WYNIK: widze tylko ruch MAVLink z samego FC - przelotka")
        log("prawdopodobnie nie zdazyla sie zalaczyc albo juz wygasla.")
        log("Powtorz test.")
    elif len(dane) > 0:
        log("WYNIK: przychodza jakies bajty, ale bez sygnatury SIYI.")
        log("Cos nadaje - sprawdzic predkosc transmisji i tryb protokolu w glowicy.")
    else:
        log("WYNIK: CISZA ABSOLUTNA - zero bajtow.")
        log("Tor elektryczny jest przerwany.  Wrocic do lutownicy:")
        log("sprawdzic, czy przewody trafily na wlasciwe pola (T7/R7, nie")
        log("sasiednie AIRSPD/GND/5V) i czy masa jest wspolna.")


if __name__ == "__main__":
    main()
