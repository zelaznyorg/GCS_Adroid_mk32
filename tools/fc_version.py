#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - szybki odczyt wersji firmware z FC (tylko-odczyt, bez dumpu parametrow).

    python tools\\fc_version.py            # domyslnie COM9
    python tools\\fc_version.py COM7 115200

Co robi:
  1. laczy sie, czeka na HEARTBEAT
  2. prosi o AUTOPILOT_VERSION  -> wersja FW, git hash, board/vendor/product ID
  3. prosi o banner (MAV_CMD_DO_SEND_BANNER) -> tekstowa wersja + nazwa plyty
  4. czyta 4 parametry kontrolne (FORMAT_VERSION, INS/BARO DEVID)

NIC NIE ZAPISUJE DO FC.
"""

import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_version")

from pymavlink import mavutil

FW_TYPE = {0: "DEV", 64: "ALPHA", 128: "BETA", 192: "RC", 255: "OFFICIAL"}
MAV_TYPE = {2: "QUADROTOR", 13: "HEXAROTOR", 14: "OCTOROTOR"}


def log(m=""):
    print(m, flush=True)


def main():
    args = [a for a in sys.argv[1:] if a]
    port = args[0] if args else "COM9"
    baud = int(args[1]) if len(args) > 1 else 115200

    log("Laczenie: %s @ %d" % (port, baud))
    m = mavutil.mavlink_connection(port, baud=baud)

    hb = m.wait_heartbeat(timeout=20)
    if hb is None:
        log("BRAK HEARTBEAT - port zajety (Mission Planner?) albo zly baud.")
        sys.exit(1)
    log("HEARTBEAT: sysid=%d compid=%d typ=%s autopilot=%d" % (
        m.target_system, m.target_component,
        MAV_TYPE.get(hb.type, hb.type), hb.autopilot))

    # --- banner (statustexty z wersja) ---
    m.mav.command_long_send(m.target_system, m.target_component,
                            42428, 0, 0, 0, 0, 0, 0, 0, 0)  # MAV_CMD_DO_SEND_BANNER

    # --- AUTOPILOT_VERSION ---
    av = None
    for _ in range(4):
        m.mav.command_long_send(m.target_system, m.target_component,
                                mavutil.mavlink.MAV_CMD_REQUEST_MESSAGE,
                                0, 148, 0, 0, 0, 0, 0, 0)
        try:
            m.mav.autopilot_version_request_send(m.target_system, m.target_component)
        except Exception:
            pass
        av = m.recv_match(type="AUTOPILOT_VERSION", blocking=True, timeout=3)
        if av:
            break

    log("")
    if av:
        fv = av.flight_sw_version
        major, minor, patch, ftype = (fv >> 24) & 0xFF, (fv >> 16) & 0xFF, (fv >> 8) & 0xFF, fv & 0xFF
        log("AUTOPILOT_VERSION:")
        log("  wersja FW        : %d.%d.%d  (%s)" % (major, minor, patch,
                                                     FW_TYPE.get(ftype, "typ=%d" % ftype)))
        fcv = getattr(av, "flight_custom_version", None)
        if fcv:
            try:
                h = "".join("%02x" % b for b in bytes(fcv))
            except Exception:
                h = str(fcv)
            log("  git hash (FW)    : %s" % h)
        log("  board_version    : %s" % av.board_version)
        log("  vendor / product : 0x%04X / 0x%04X" % (
            getattr(av, "vendor_id", 0) or 0, getattr(av, "product_id", 0) or 0))
        log("  UID              : %s" % getattr(av, "uid", None))
        log("  capabilities     : 0x%X" % av.capabilities)
    else:
        log("BRAK AUTOPILOT_VERSION (FC nie odpowiedzial)")

    # --- banner / statustexty ---
    log("")
    log("Komunikaty tekstowe FC (banner):")
    t0, seen = time.time(), []
    while time.time() - t0 < 4:
        msg = m.recv_match(type="STATUSTEXT", blocking=True, timeout=1)
        if msg is None:
            continue
        txt = msg.text
        if isinstance(txt, bytes):
            txt = txt.decode("utf-8", "replace")
        txt = txt.rstrip("\x00")
        if txt not in seen:
            seen.append(txt)
            log("  > %s" % txt)
    if not seen:
        log("  (brak)")

    # --- kilka parametrow kontrolnych ---
    log("")
    log("Parametry kontrolne:")
    for name in ("FORMAT_VERSION", "INS_ACC_ID", "BARO1_DEVID", "SCHED_LOOP_RATE"):
        m.mav.param_request_read_send(m.target_system, m.target_component,
                                      name.encode("ascii"), -1)
        t0 = time.time()
        got = None
        while time.time() - t0 < 3:
            pv = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
            if pv is None:
                continue
            pid = pv.param_id
            if isinstance(pid, bytes):
                pid = pid.decode("ascii", "replace")
            if pid.rstrip("\x00") == name:
                got = pv.param_value
                break
        log("  %-16s = %s" % (name, got if got is not None else "(brak odpowiedzi)"))


if __name__ == "__main__":
    main()
