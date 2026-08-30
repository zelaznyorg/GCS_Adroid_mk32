#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - analiza logu .bin z FC (tylko-odczyt).

    python tools\\log_analiza.py dok\\logi\\log_006.bin

Szuka momentu uzbrojenia i tego, co dzialo sie potem:
zdarzenia, tryby, wyjscia silnikow, zadane kontra rzeczywiste polozenie,
wibracje.  Nastawione na diagnoze nieudanego startu.
"""

import sys
import os

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("log_analiza")

from pymavlink import mavutil


def main():
    if len(sys.argv) < 2 or not os.path.exists(sys.argv[1]):
        print(__doc__); sys.exit(2)

    m = mavutil.mavlink_connection(sys.argv[1])

    zdarzenia = []      # (t, opis)
    rcou = []           # (t, c1..c4)
    att = []            # (t, droll, roll, dpitch, pitch, dyaw, yaw)
    vibe = []
    tryby = []
    uzbrojenie = None
    licznik = {}

    while True:
        msg = m.recv_match()
        if msg is None:
            break
        t = msg.get_type()
        licznik[t] = licznik.get(t, 0) + 1
        try:
            czas = getattr(msg, "TimeUS", 0) / 1e6
        except Exception:
            czas = 0

        if t == "MSG":
            zdarzenia.append((czas, msg.Message))
        elif t == "EV":
            OP = {10: "UZBROJONY", 11: "ROZBROJONY", 15: "auto uzbrojenie",
                  18: "land complete", 28: "no dane", 25: "set home",
                  29: "utrata lacza RC", 30: "powrot lacza RC"}
            zdarzenia.append((czas, "EV %s: %s" % (msg.Id, OP.get(msg.Id, ""))))
            if msg.Id == 10:
                uzbrojenie = czas
        elif t == "ERR":
            zdarzenia.append((czas, "ERR podsystem=%s kod=%s" % (msg.Subsys, msg.ECode)))
        elif t == "MODE":
            tryby.append((czas, msg.Mode, getattr(msg, "ModeNum", "")))
        elif t == "RCOU":
            rcou.append((czas, msg.C1, msg.C2, msg.C3, msg.C4))
        elif t == "ATT":
            att.append((czas, msg.DesRoll, msg.Roll, msg.DesPitch, msg.Pitch,
                        msg.DesYaw, msg.Yaw))
        elif t == "VIBE":
            vibe.append((czas, msg.VibeX, msg.VibeY, msg.VibeZ))

    print("=" * 76)
    print("LOG: %s   dlugosc %.0f s" % (os.path.basename(sys.argv[1]),
                                        att[-1][0] - att[0][0] if att else 0))
    print("=" * 76)

    print("\n--- ZMIANY TRYBU ---")
    for c, tr, nr in tryby:
        print("  %8.1f s   %s (%s)" % (c, tr, nr))

    print("\n--- ZDARZENIA I KOMUNIKATY ---")
    for c, op in zdarzenia[-40:]:
        print("  %8.1f s   %s" % (c, op))

    if uzbrojenie is None:
        print("\n>>> W tym logu NIE BYLO UZBROJENIA - maszyna nie ruszyla silnikow.")
        return

    print("\n>>> UZBROJENIE w %.1f s" % uzbrojenie)

    print("\n--- WYJSCIA SILNIKOW po uzbrojeniu (co 0,5 s) ---")
    print("  %8s  %6s %6s %6s %6s   %s" % ("czas", "M1", "M2", "M3", "M4", "uwagi"))
    ost = 0
    for c, a, b, cc, d in rcou:
        if c < uzbrojenie - 0.5 or c - ost < 0.5:
            continue
        ost = c
        rozrzut = max(a, b, cc, d) - min(a, b, cc, d)
        uwaga = ""
        if rozrzut > 400:
            uwaga = "<<< OGROMNY ROZRZUT %d us" % rozrzut
        elif rozrzut > 200:
            uwaga = "<< duzy rozrzut %d us" % rozrzut
        print("  %8.1f  %6d %6d %6d %6d   %s" % (c, a, b, cc, d, uwaga))
        if c > uzbrojenie + 12:
            break

    print("\n--- POLOZENIE: zadane kontra rzeczywiste (co 0,5 s) ---")
    print("  %8s  %14s  %14s   %s" % ("czas", "ROLL zad/rzecz", "PITCH zad/rzecz", "blad"))
    ost = 0
    for c, dr, r, dp, pp, dy, y in att:
        if c < uzbrojenie - 0.5 or c - ost < 0.5:
            continue
        ost = c
        br, bp = r - dr, pp - dp
        flaga = ""
        if abs(bp) > 25 or abs(br) > 25:
            flaga = "<<< UCIEKA"
        elif abs(bp) > 10 or abs(br) > 10:
            flaga = "<< rozjazd"
        print("  %8.1f  %6.1f /%6.1f  %6.1f /%6.1f   R%+6.1f P%+6.1f %s" % (
            c, dr, r, dp, pp, br, bp, flaga))
        if c > uzbrojenie + 12:
            break

    if vibe:
        v = [x for x in vibe if x[0] >= uzbrojenie]
        if v:
            mx = max(max(a[1], a[2], a[3]) for a in v)
            print("\n--- WIBRACJE ---")
            print("  maksimum po uzbrojeniu: %.1f m/s2  (limit ok. 30)" % mx)


if __name__ == "__main__":
    main()
