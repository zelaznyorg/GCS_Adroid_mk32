#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - lista logow na karcie FC, opcjonalnie pobranie (tylko-odczyt).

    python tools\\fc_logs.py                 # lista logow
    python tools\\fc_logs.py --pobierz 12    # pobiera log nr 12 do dok\\logi\\

Pobieranie przez USB @115200 jest wolne - okolo 10 kB/s, wiec log 5 MB
to ponad 8 minut.  Przy wiekszych plikach szybciej wyjac karte microSD.

NIC NIE ZAPISUJE DO FC.
"""

import sys
import os
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_logs")

from pymavlink import mavutil

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE) if os.path.basename(HERE).lower() == "tools" else HERE
LOGI = os.path.join(PROJ, "dok", "logi")


def main():
    argv = sys.argv[1:]
    port = "COM9"
    pobierz = None
    i = 0
    while i < len(argv):
        if argv[i] == "--pobierz":
            i += 1
            pobierz = int(argv[i])
        elif argv[i] == "--port":
            i += 1
            port = argv[i]
        i += 1

    m = mavutil.mavlink_connection(port, baud=115200)
    if m.wait_heartbeat(timeout=20) is None:
        print("BRAK HEARTBEAT"); sys.exit(1)

    print("pobieram liste logow...\n")
    m.mav.log_request_list_send(m.target_system, m.target_component, 0, 0xFFFF)

    wpisy = {}
    t0 = time.time()
    ostatni = time.time()
    while time.time() - t0 < 25:
        msg = m.recv_match(type="LOG_ENTRY", blocking=True, timeout=2)
        if msg is None:
            if wpisy and time.time() - ostatni > 4:
                break
            continue
        ostatni = time.time()
        if msg.size > 0 or msg.id not in wpisy:
            wpisy[msg.id] = (msg.size, msg.time_utc, msg.num_logs)
        if msg.num_logs and len(wpisy) >= msg.num_logs:
            break

    if not wpisy:
        print("Brak logow na karcie albo FC nie odpowiada na LOG_REQUEST_LIST.")
        print("Sprawdz, czy karta microSD jest wlozona i czy LOG_BACKEND_TYPE=1.")
        return

    print("%-6s %12s  %s" % ("nr", "rozmiar", "data (UTC)"))
    print("-" * 48)
    for nr in sorted(wpisy):
        rozm, utc, _ = wpisy[nr]
        data = time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(utc)) if utc else "brak"
        print("%-6d %9.2f MB  %s" % (nr, rozm / 1048576.0, data))
    print("\nrazem %d logow" % len(wpisy))

    najnowszy = max(wpisy)
    print("\nnajnowszy: nr %d, %.2f MB" % (najnowszy, wpisy[najnowszy][0] / 1048576.0))
    szac = wpisy[najnowszy][0] / 10240.0 / 60.0
    print("pobranie przez USB zajmie orientacyjnie %.0f min" % szac)

    if pobierz is None:
        print("\nZeby pobrac:  python tools\\fc_logs.py --pobierz %d" % najnowszy)
        return

    if pobierz not in wpisy:
        print("\nNie ma logu nr %d" % pobierz); sys.exit(1)

    os.makedirs(LOGI, exist_ok=True)
    sciezka = os.path.join(LOGI, "log_%03d.bin" % pobierz)
    rozmiar = wpisy[pobierz][0]
    print("\npobieram log %d (%.2f MB) -> %s" % (pobierz, rozmiar / 1048576.0, sciezka))

    dane = bytearray(rozmiar)
    maska = bytearray(rozmiar)
    ofs = 0
    t0 = time.time()
    ostatni_druk = 0
    while ofs < rozmiar:
        m.mav.log_request_data_send(m.target_system, m.target_component,
                                    pobierz, ofs, 90 * 1024)
        koniec = time.time()
        while time.time() - koniec < 3:
            msg = m.recv_match(type="LOG_DATA", blocking=True, timeout=1)
            if msg is None:
                break
            o, n = msg.ofs, msg.count
            dane[o:o + n] = bytes(msg.data[:n])
            maska[o:o + n] = b"\x01" * n
            koniec = time.time()
            if time.time() - ostatni_druk > 5:
                ostatni_druk = time.time()
                got = sum(maska)
                print("  %.1f%%  (%.2f MB / %.2f MB)" % (
                    100.0 * got / rozmiar, got / 1048576.0, rozmiar / 1048576.0))
        brak = maska.find(b"\x00", ofs)
        ofs = brak if brak >= 0 else rozmiar

    with open(sciezka, "wb") as f:
        f.write(bytes(dane))
    print("\ngotowe w %.0f s: %s" % (time.time() - t0, sciezka))


if __name__ == "__main__":
    main()
