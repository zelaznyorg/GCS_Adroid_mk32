#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - co blokuje uzbrojenie (tylko-odczyt, NIE uzbraja).

    python tools\\fc_prearm.py            # 40 s nasluchu
    python tools\\fc_prearm.py 60

Wysyla MAV_CMD_RUN_PREARM_CHECKS (401) - to POLECENIE URUCHOMIENIA KONTROLI,
nie uzbrojenia.  Silniki nie ruszaja.  Potem slucha STATUSTEXT i skladа
obraz z SYS_STATUS, EKF_STATUS_REPORT i GPS_RAW_INT.

NIC NIE ZAPISUJE DO FC.  NIE WYSYLA KOMENDY ARM.
"""

import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_prearm")

from pymavlink import mavutil

SENSORY = [
    (0x01, "zyroskop 3D"), (0x02, "akcelerometr 3D"), (0x04, "magnetometr 3D"),
    (0x08, "cisnienie absolutne"), (0x20, "GPS"), (0x400, "stabilizacja 3-osiowa"),
    (0x1000, "wejscie RC"), (0x20000, "bateria"), (0x100000, "predkosc katowa"),
    (0x2000000, "AHRS"), (0x4000000, "terrain"), (0x8000000, "silniki odwrocone"),
    (0x10000000, "geofence"), (0x20000000, "bateria - poziom"),
]

EKF = [
    (1, "attitude"), (2, "predkosc pozioma"), (4, "predkosc pionowa"),
    (8, "pozycja pozioma wzgledna"), (16, "pozycja pozioma bezwzgledna"),
    (32, "pozycja pionowa bezwzgledna"), (64, "wysokosc nad terenem"),
    (128, "TRYB STALEJ POZYCJI (brak nawigacji)"), (256, "prognoza poz. wzgl."),
    (512, "prognoza poz. bezwzgl."), (1024, "NIEZAINICJALIZOWANY"),
]

GPS_FIX = {0: "brak GPS", 1: "brak fixa", 2: "2D", 3: "3D", 4: "DGPS",
           5: "RTK float", 6: "RTK fixed"}


def main():
    secs = int(sys.argv[1]) if len(sys.argv) > 1 else 40
    port = sys.argv[2] if len(sys.argv) > 2 else "COM9"

    m = mavutil.mavlink_connection(port, baud=115200)
    if m.wait_heartbeat(timeout=20) is None:
        print("BRAK HEARTBEAT na %s" % port)
        sys.exit(1)

    for sid in range(4):
        try:
            m.mav.request_data_stream_send(m.target_system, m.target_component, sid, 4, 1)
        except Exception:
            pass

    # 401 = MAV_CMD_RUN_PREARM_CHECKS - uruchamia kontrole, NIE uzbraja
    m.mav.command_long_send(m.target_system, m.target_component,
                            401, 0, 0, 0, 0, 0, 0, 0, 0)

    print("slucham %d s (kontrole PreArm zglaszane sa cyklicznie)...\n" % secs)
    teksty, snap = [], {}
    t0 = time.time()
    while time.time() - t0 < secs:
        msg = m.recv_match(blocking=True, timeout=1)
        if msg is None:
            continue
        t = msg.get_type()
        if t == "STATUSTEXT":
            txt = msg.text
            if isinstance(txt, bytes):
                txt = txt.decode("utf-8", "replace")
            txt = txt.rstrip("\x00")
            if txt not in teksty:
                teksty.append(txt)
                print("  FC> %s" % txt)
        elif t in ("SYS_STATUS", "EKF_STATUS_REPORT", "GPS_RAW_INT", "HEARTBEAT"):
            snap[t] = msg

    print("\n" + "=" * 62)
    blokady = [t for t in teksty if "PreArm" in t or "Arm" in t]
    print("KOMUNIKATY BLOKUJACE (%d):" % len(blokady))
    for t in blokady:
        print("   ! %s" % t)
    if not blokady:
        print("   brak - kontrole PreArm przechodza")

    if "SYS_STATUS" in snap:
        s = snap["SYS_STATUS"]
        print("\nCZUJNIKI (obecny / wlaczony / sprawny):")
        for bit, nazwa in SENSORY:
            if s.onboard_control_sensors_present & bit:
                zdr = bool(s.onboard_control_sensors_health & bit)
                wl = bool(s.onboard_control_sensors_enabled & bit)
                print("   %-26s %-8s %s" % (nazwa, "wl." if wl else "wyl.",
                                            "OK" if zdr else "AWARIA"))
        print("   napiecie %.2f V   CPU %.1f %%" % (
            s.voltage_battery / 1000.0, s.load / 10.0))

    if "GPS_RAW_INT" in snap:
        g = snap["GPS_RAW_INT"]
        print("\nGPS: %s, satelity=%d, HDOP=%.2f" % (
            GPS_FIX.get(g.fix_type, g.fix_type), g.satellites_visible,
            g.eph / 100.0 if g.eph < 65535 else -1))

    if "EKF_STATUS_REPORT" in snap:
        e = snap["EKF_STATUS_REPORT"]
        print("\nEKF3 flagi (0x%04X):" % e.flags)
        for bit, nazwa in EKF:
            if e.flags & bit:
                print("   + %s" % nazwa)
        print("   wariancje: kurs %.2f  pozycja %.2f  wysokosc %.2f  predkosc %.2f" % (
            e.compass_variance, e.pos_horiz_variance, e.terrain_alt_variance,
            e.velocity_variance))


if __name__ == "__main__":
    main()
