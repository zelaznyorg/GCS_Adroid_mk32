#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - pomiar RZECZYWISTEJ czestotliwosci odswiezania drazkow (tylko-odczyt).

    python tools\\fc_rc_rate.py            # 20 s
    python tools\\fc_rc_rate.py 30

W trakcie pomiaru RUSZAJ JEDNYM DRAZKIEM PLYNNIE tam i z powrotem.

Rozroznia dwie rzeczy, ktore latwo pomylic:
  * ile ramek MAVLink przychodzi   -> limit strumienia SR0_RC_CHAN
  * jak czesto zmienia sie TRESC   -> prawdziwe tempo wejscia RC (S.Bus)

Jesli ramek jest duzo, a zmian malo - waskim gardlem jest sam S.Bus albo
aparatura, nie telemetria.  Jesli zmian tyle co ramek - waskim gardlem
jest strumien i trzeba podniesc SR0_RC_CHAN / SR1_RC_CHAN.

NIC NIE ZAPISUJE DO FC.
"""

import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_rc_rate")

from pymavlink import mavutil


def main():
    argv = [a for a in sys.argv[1:] if a]
    secs = int(argv[0]) if argv else 20
    port = argv[1] if len(argv) > 1 else "COM9"

    m = mavutil.mavlink_connection(port, baud=115200)
    if m.wait_heartbeat(timeout=20) is None:
        print("BRAK HEARTBEAT na %s" % port)
        sys.exit(1)

    # zadaj maksimum, zeby zmierzyc sufit a nie ustawienie
    m.mav.command_long_send(m.target_system, m.target_component,
                            mavutil.mavlink.MAV_CMD_SET_MESSAGE_INTERVAL,
                            0, 65, 10000, 0, 0, 0, 0, 0)   # 100 Hz

    print("Mierze %d s.  RUSZAJ JEDNYM DRAZKIEM plynnie tam i z powrotem.\n" % secs)

    ramek = 0
    zmiany = []          # znaczniki czasu zmian tresci
    prev = None
    kanaly = None
    t0 = time.time()
    while time.time() - t0 < secs:
        msg = m.recv_match(type="RC_CHANNELS", blocking=True, timeout=1)
        if msg is None:
            continue
        ramek += 1
        kanaly = msg.chancount
        cur = tuple(getattr(msg, "chan%d_raw" % c, 0) for c in range(1, 9))
        if prev is not None and cur != prev:
            zmiany.append(time.time())
        prev = cur

    # przywroc rozsadna stawke na USB
    m.mav.command_long_send(m.target_system, m.target_component,
                            mavutil.mavlink.MAV_CMD_SET_MESSAGE_INTERVAL,
                            0, 65, 100000, 0, 0, 0, 0, 0)

    print("=" * 60)
    print("ramek MAVLink      : %d   -> %.1f Hz" % (ramek, ramek / float(secs)))
    print("zmian tresci       : %d   -> %.1f Hz" % (len(zmiany), len(zmiany) / float(secs)))
    print("kanalow (chancount): %s" % kanaly)

    if len(zmiany) < 5:
        print("\nZa malo zmian - drazek prawdopodobnie sie nie ruszal.")
        print("Powtorz i ruszaj drazkiem PRZEZ CALY czas pomiaru.")
        return

    surowe = [(zmiany[i] - zmiany[i - 1]) * 1000.0 for i in range(1, len(zmiany))]
    # ramki MAVLink przychodza paczkami, wiec czesc odstepow jest bliska zeru
    # i nie niesie informacji o tempie wejscia RC - odsiewamy je
    okresy = sorted(d for d in surowe if d >= 1.0)
    odsiane = len(surowe) - len(okresy)

    print("\nodstepy miedzy zmianami [ms]  (odsiano %d < 1 ms - paczki ramek)" % odsiane)
    if okresy:
        n = len(okresy)
        print("  mediana %.1f   p10 %.1f   p90 %.1f   max %.1f" % (
            okresy[n // 2], okresy[n // 10], okresy[(9 * n) // 10], okresy[-1]))

    # najpewniejszy estymator: liczba zmian na czas trwania pomiaru
    print("\n  -> rzeczywiste tempo wejscia RC: %.1f Hz" % (len(zmiany) / float(secs)))

    print("\nWNIOSEK:")
    if ramek and len(zmiany) / float(ramek) > 0.8:
        print("  Prawie kazda ramka niesie nowa wartosc -> waskim gardlem jest")
        print("  STRUMIEN telemetrii.  Podnies SR0_RC_CHAN (USB) / SR1_RC_CHAN (MK32).")
    else:
        print("  Duzo ramek powtarza te sama tresc -> telemetria nadaza, a limituje")
        print("  SAM S.BUS albo aparatura.  Sprawdz ustawienia wyjscia S.Bus w MK32.")


if __name__ == "__main__":
    main()
