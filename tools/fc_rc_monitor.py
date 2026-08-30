#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - podglad wejscia RC z FC (tylko-odczyt).

    python tools\\fc_rc_monitor.py             # 15 s podgladu
    python tools\\fc_rc_monitor.py 60          # 60 s
    python tools\\fc_rc_monitor.py 60 --port COM9

Pokazuje: wykryty protokol RC, RSSI, wartosci 16 kanalow, bit "wejscie RC"
z SYS_STATUS oraz min/max kazdego kanalu przez caly czas obserwacji
(przydatne przy sprawdzaniu drazkow i przelacznikow).

NIC NIE ZAPISUJE DO FC.
"""

import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_rc_monitor")

from pymavlink import mavutil

RC_PROTO = {0: "PPM", 1: "IBUS", 2: "SBUS", 3: "SBUS_NI", 4: "DSM", 5: "SUMD",
            6: "SRXL", 7: "SRXL2", 8: "CRSF", 9: "ST24", 10: "FPORT",
            11: "FPORT2", 12: "FastSBUS", 13: "DroneCAN", 14: "Ghost",
            15: "MAVRadio", 255: "brak / nie wykryto"}


def main():
    argv = sys.argv[1:]
    port, baud, secs = "COM9", 115200, 15
    i = 0
    while i < len(argv):
        if argv[i] == "--port":
            i += 1; port = argv[i]
        elif argv[i] == "--baud":
            i += 1; baud = int(argv[i])
        else:
            secs = int(argv[i])
        i += 1

    m = mavutil.mavlink_connection(port, baud=baud)
    if m.wait_heartbeat(timeout=20) is None:
        print("BRAK HEARTBEAT na %s" % port)
        sys.exit(1)

    for sid in range(4):
        try:
            m.mav.request_data_stream_send(m.target_system, m.target_component, sid, 10, 1)
        except Exception:
            pass

    print("Podglad RC przez %d s.  Ruszaj drazkami i przelacznikami.\n" % secs)

    lo = [None] * 16
    hi = [None] * 16
    last = None
    proto = None
    rssi = None
    rc_bit = None
    frames = 0
    t0 = time.time()
    tprint = 0.0

    while time.time() - t0 < secs:
        msg = m.recv_match(type=["RC_CHANNELS", "SYS_STATUS", "STATUSTEXT"],
                           blocking=True, timeout=1)
        if msg is None:
            continue
        t = msg.get_type()
        if t == "SYS_STATUS":
            rc_bit = bool(msg.onboard_control_sensors_health & 0x1000)
            continue
        if t == "STATUSTEXT":
            txt = msg.text
            if isinstance(txt, bytes):
                txt = txt.decode("utf-8", "replace")
            print("  FC> %s" % txt.rstrip("\x00"))
            continue

        frames += 1
        vals = [getattr(msg, "chan%d_raw" % (c + 1), 0) for c in range(16)]
        last = vals
        rssi = msg.rssi
        for c, v in enumerate(vals):
            if v == 0:
                continue
            lo[c] = v if lo[c] is None else min(lo[c], v)
            hi[c] = v if hi[c] is None else max(hi[c], v)

        now = time.time()
        if now - tprint > 1.0:
            tprint = now
            print("  " + " ".join("%4d" % v for v in vals[:8])
                  + "  |  " + " ".join("%4d" % v for v in vals[8:16])
                  + "   rssi=%s" % rssi)

    # wykryty protokol
    m.mav.param_request_read_send(m.target_system, m.target_component,
                                  b"RC_PROTOCOLS", -1)
    t1 = time.time()
    while time.time() - t1 < 2:
        pv = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
        if pv is None:
            break

    print("\n" + "=" * 66)
    print("ramek RC_CHANNELS: %d  (%.1f Hz)" % (frames, frames / float(secs)))
    print("bit 'wejscie RC' w SYS_STATUS: %s" % (
        "SPRAWNE" if rc_bit else ("AWARIA/BRAK" if rc_bit is not None else "brak SYS_STATUS")))
    print("RSSI (0-255): %s" % rssi)
    if not frames:
        print("\nBRAK RAMEK RC. Sprawdz kolejno:")
        print("  - aparatura MK32 wlaczona i sparowana z air unitem")
        print("  - air unit zasilony (3-6S) i wypiety S.Bus wpiety w ten UART")
        print("  - SERIAL3_PROTOCOL=23 i restart FC po zmianie")
        return

    print("\nkanal   teraz    min    max   zakres")
    for c in range(16):
        if lo[c] is None:
            print("  CH%-2d      -      -      -   (cisza)" % (c + 1))
        else:
            print("  CH%-2d  %5d  %5d  %5d   %5d" % (
                c + 1, last[c], lo[c], hi[c], hi[c] - lo[c]))


if __name__ == "__main__":
    main()
