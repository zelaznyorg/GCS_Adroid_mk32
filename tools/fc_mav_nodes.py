#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - kto gada w sieci MAVLink (tylko-odczyt).

    python tools\\fc_mav_nodes.py            # 15 s
    python tools\\fc_mav_nodes.py 30

Sluchamy biernie na USB i wypisujemy KAZDE zrodlo (sysid, compid), ktore
sie odezwalo.  ArduPilot domyslnie przekazuje MAVLink miedzy laczami, wiec:

  * FC to zwykle sysid=1 compid=1
  * glowica / gimbal          -> compid 154 lub 67..
  * GCS (Mission Planner, QGC na MK32) -> compid 190..195, sysid 255 lub inny

JESLI widac GCS o sysid innym niz nasze polaczenie USB, znaczy to ze
odbior na tamtym porcie DZIALA.  Brak takiego zrodla przy podlaczonej
aparaturze wskazuje na martwy tor RX (kabel, baud, zly port).

NIC NIE ZAPISUJE DO FC.
"""

import sys
import time
import collections

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_mav_nodes")

from pymavlink import mavutil

COMP = {1: "autopilot", 25: "IMU?", 26: "GPS", 67: "gimbal/mount",
        100: "kamera", 154: "gimbal (MAV_COMP_ID_GIMBAL)",
        169: "kamera 2", 190: "GCS (MissionPlanner)", 191: "GCS 2",
        192: "GCS 3", 193: "GCS 4", 194: "GCS 5", 195: "GCS 6",
        240: "user", 250: "peryferium", 251: "log"}

MAV_TYPE_GCS = 6


def main():
    argv = [a for a in sys.argv[1:] if a]
    secs = int(argv[0]) if argv else 15
    port = argv[1] if len(argv) > 1 else "COM9"

    m = mavutil.mavlink_connection(port, baud=115200)
    if m.wait_heartbeat(timeout=20) is None:
        print("BRAK HEARTBEAT na %s" % port)
        sys.exit(1)

    print("slucham %d s, bez zadania czegokolwiek...\n" % secs)
    zrodla = collections.defaultdict(collections.Counter)
    typy = {}
    t0 = time.time()
    while time.time() - t0 < secs:
        msg = m.recv_match(blocking=True, timeout=1)
        if msg is None:
            continue
        h = msg.get_header()
        klucz = (h.srcSystem, h.srcComponent)
        zrodla[klucz][msg.get_type()] += 1
        if msg.get_type() == "HEARTBEAT":
            typy[klucz] = (msg.type, msg.autopilot)

    print("%-16s %-30s %8s  %s" % ("SYSID/COMPID", "rola", "komun.", "przyklady"))
    print("-" * 90)
    for (s, c), lic in sorted(zrodla.items()):
        rola = COMP.get(c, "compid %d" % c)
        if klucz in typy and typy.get((s, c), (None,))[0] == MAV_TYPE_GCS:
            rola += " [GCS]"
        top = ", ".join(t for t, _ in lic.most_common(3))
        print("%-16s %-30s %8d  %s" % ("%d / %d" % (s, c), rola, sum(lic.values()), top))

    print()
    gcs = [(s, c) for (s, c) in zrodla if 190 <= c <= 195 or typy.get((s, c), (0,))[0] == MAV_TYPE_GCS]
    if gcs:
        print("Wykryte stacje naziemne: %s" % ", ".join("sys%d/comp%d" % g for g in gcs))
        print("-> odbior MAVLink z tamtej strony DZIALA")
    else:
        print("NIE WYKRYTO zadnej stacji naziemnej poza tym polaczeniem.")
        print("-> jesli aparatura jest wlaczona i aplikacja uruchomiona,")
        print("   tor RX na tamtym porcie jest martwy (kabel / baud / zly UART)")


if __name__ == "__main__":
    main()
