#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - AKTYWNA sonda glowicy ZR30 przez przelotke szeregowa FC.

    python tools\\fc_gimbal_probe.py --yes            # domyslnie SERIAL7,1,2,5,8
    python tools\\fc_gimbal_probe.py 7 --yes          # tylko SERIAL7

Czym rozni sie od fc_serial_sniff.py: ten NIE slucha biernie, tylko SAM
WYSYLA zapytania protokolu SIYI i czeka na odpowiedz.  Protokol SIYI jest
typu zadanie-odpowiedz, wiec bierny podsluch niczego nie dowodzi - glowica
milczy dopoki sie jej nie zapyta.

Ramki (CRC16/XMODEM, zweryfikowany na przykladzie z instrukcji ZR30 str. 46):
    CMD 0x01  wersja firmware   55 66 01 00 00 00 00 01 64 C4
    CMD 0x02  hardware ID       55 66 01 00 00 00 00 02 07 F4
    CMD 0x0D  orientacja        55 66 01 00 00 00 00 0D E8 05

UWAGA: ZAPISUJE DO FC i RESTARTUJE go raz na kazdy testowany port.
"""

import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_gimbal_probe")

from pymavlink import mavutil
import serial
from serial.tools import list_ports

COM, BAUD = "COM9", 115200
DOMYSLNE = [7, 1, 2, 5, 8]
NIETYKALNE = {0, 3, 4, 6}

SONDY = [
    ("wersja firmware", bytes.fromhex("556601000000000164C4")),
    ("hardware ID",     bytes.fromhex("55660100000000020 7F4".replace(" ", ""))),
    ("orientacja",      bytes.fromhex("5566010000000000DE805".replace(" ", "")[:20])),
]
# zbudowane niezaleznie, zeby uniknac literowek:
def _crc16_xmodem(d):
    c = 0
    for b in d:
        c ^= b << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x1021) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
    return c


def ramka(cmd_id, data=b"", seq=0, ctrl=0x01):
    body = (b"\x55\x66" + bytes([ctrl]) + len(data).to_bytes(2, "little") +
            seq.to_bytes(2, "little") + bytes([cmd_id]) + data)
    return body + _crc16_xmodem(body).to_bytes(2, "little")


SONDY = [("wersja firmware", ramka(0x01)),
         ("hardware ID", ramka(0x02)),
         ("orientacja", ramka(0x0D))]


def log(m=""):
    print(m, flush=True)


def polacz(timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if COM not in [p.device for p in list_ports.comports()]:
            time.sleep(0.5); continue
        try:
            m = mavutil.mavlink_connection(COM, baud=BAUD)
            if m.wait_heartbeat(timeout=10) is not None:
                return m
            m.close()
        except Exception:
            time.sleep(0.5)
    return None


def ustaw(m, n, v):
    m.mav.param_set_send(m.target_system, m.target_component,
                         n.encode("ascii"), float(v),
                         mavutil.mavlink.MAV_PARAM_TYPE_REAL32)
    t0 = time.time()
    while time.time() - t0 < 4:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
        if msg is None:
            continue
        p = msg.param_id
        if isinstance(p, bytes):
            p = p.decode("ascii", "replace")
        if p.rstrip("\x00") == n:
            return msg.param_value
    return None


def main():
    if "--yes" not in sys.argv:
        print(__doc__); sys.exit(2)
    reczne = [int(a) for a in sys.argv[1:] if a.isdigit()]
    porty = reczne or DOMYSLNE

    log("=" * 66)
    log("DRON 15 - aktywna sonda glowicy ZR30")
    log("porty: %s" % ", ".join("SERIAL%d" % p for p in porty))
    log("=" * 66)
    for opis, r in SONDY:
        log("  sonda %-16s %s" % (opis, r.hex(" ").upper()))

    wynik = None
    for nr in porty:
        if nr in NIETYKALNE:
            log("\nSERIAL%d pomijam - port zajety przez dzialajace urzadzenie" % nr)
            continue
        log("\n" + "-" * 66)
        log(">>> SERIAL%d" % nr)

        m = polacz()
        if m is None:
            log("    BRAK POLACZENIA"); sys.exit(1)
        for k in porty:
            if k not in NIETYKALNE:
                ustaw(m, "SERIAL%d_PROTOCOL" % k, -1)
                ustaw(m, "SERIAL%d_BAUD" % k, 115)
        ustaw(m, "SERIAL_PASSTIMO", 30)
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
            log("    FC nie wrocil"); sys.exit(1)
        ustaw(m, "SERIAL_PASS1", 0)
        ustaw(m, "SERIAL_PASS2", nr)
        try:
            m.close()
        except Exception:
            pass
        time.sleep(1)

        odp = b""
        try:
            s = serial.Serial(COM, BAUD, timeout=0.2)
            s.reset_input_buffer()
            t0 = time.time()
            i = 0
            while time.time() - t0 < 10:
                opis, r = SONDY[i % len(SONDY)]
                s.write(r)
                s.flush()
                i += 1
                t1 = time.time()
                while time.time() - t1 < 0.4:
                    odp += s.read(512)
            s.close()
        except Exception as e:
            log("    blad portu surowego: %s" % e)

        # odsiewamy echo wlasnych sond
        czyste = odp
        for _, r in SONDY:
            czyste = czyste.replace(r, b"")
        siyi = czyste.count(b"\x55\x66")
        log("    odebrano %d bajtow (po odsianiu echa: %d)" % (len(odp), len(czyste)))
        log("    ramek SIYI w odpowiedzi: %d" % siyi)
        if czyste:
            log("    pierwsze 64: %s" % " ".join("%02X" % b for b in czyste[:64]))
        if siyi:
            log("\n    *** GLOWICA ODPOWIEDZIALA NA SERIAL%d ***" % nr)
            wynik = nr
            try:
                m2 = polacz()
                if m2:
                    ustaw(m2, "SERIAL_PASS2", -1)
                    m2.close()
            except Exception:
                pass
            break

        time.sleep(31)      # niech przelotka wygasnie
        m = polacz()
        if m:
            ustaw(m, "SERIAL_PASS2", -1)
            try:
                m.close()
            except Exception:
                pass

    log("\n" + "=" * 66)
    m = polacz()
    if m:
        ustaw(m, "SERIAL_PASS2", -1)
        ustaw(m, "SERIAL7_PROTOCOL", 8)
        ustaw(m, "SERIAL7_BAUD", 115)
        for k in (1, 2, 5, 8):
            ustaw(m, "SERIAL%d_PROTOCOL" % k, -1)
        ustaw(m, "SERIAL6_PROTOCOL", 2)
        ustaw(m, "SERIAL6_BAUD", 115)
        log("konfiguracja przywrocona: SERIAL6=MAVLink, SERIAL7=Gimbal, reszta -1")
        try:
            m.close()
        except Exception:
            pass

    if wynik:
        log("\nWYNIK: glowica odpowiada na SERIAL%d - tor elektryczny DZIALA." % wynik)
    else:
        log("\nWYNIK: glowica nie odpowiedziala na zadnym porcie.")
        log("Wyslano prawidlowe ramki SIYI i nie wrocilo nic.")
        log("To juz mocna przeslanka, ze tor elektryczny jest przerwany:")
        log("przewody na niewlasciwych polach albo brak wspolnej masy.")


if __name__ == "__main__":
    main()
