#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - odczyt wskazanej listy parametrow z FC (tylko-odczyt).

    python tools\\fc_read_params.py SERIAL1_PROTOCOL SERIAL3_PROTOCOL
    python tools\\fc_read_params.py --group rc        # gotowa grupa
    python tools\\fc_read_params.py --group serial
    python tools\\fc_read_params.py --port COM9 --group serial rc

Grupy: serial, rc, fltmode, fs
Parametr nieistniejacy w FC raportowany jest jako (BRAK).
NIC NIE ZAPISUJE DO FC.
"""

import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_read_params")

from pymavlink import mavutil

# --- obejscie bledu pymavlink (wersja 3.14 / pythoncore) -----------------------
# Po UDP przychodza wiadomosci z polem instancji (BATTERY_STATUS, ESC_TELEMETRY).
# pymavlink probuje wtedy zapisac messages[typ]._instances[nr], a to pole bywa
# None dla egzemplarza zapisanego wczesniej -> TypeError i wywrocone narzedzie.
# Objaw: "TypeError: 'NoneType' object does not support item assignment"
# w mavutil.add_message. Nie dotyczy pracy po USB, tylko po sieci.
# Zamiast tego zapisujemy po prostu ostatnia wiadomosc danego typu.
_add_message_oryg = mavutil.add_message


def _add_message_odporny(messages, mtype, msg):
    try:
        _add_message_oryg(messages, mtype, msg)
    except TypeError:
        messages[mtype] = msg


mavutil.add_message = _add_message_odporny
# -------------------------------------------------------------------------------

GROUPS = {
    "serial": (
        [f"SERIAL{i}_{s}" for i in range(9) for s in ("PROTOCOL", "BAUD", "OPTIONS")]
        + ["BRD_SER1_RTSCTS", "BRD_SER2_RTSCTS", "BRD_SER3_RTSCTS",
           "BRD_SER4_RTSCTS", "BRD_SER5_RTSCTS"]
    ),
    "rc": (
        ["RC_PROTOCOLS", "RC_OPTIONS", "RSSI_TYPE", "RSSI_CHANNEL"]
        + [f"RC{c}_{s}" for c in range(1, 17)
           for s in ("MIN", "MAX", "TRIM", "REVERSED", "OPTION", "DZ")]
    ),
    "fltmode": ["FLTMODE_CH"] + [f"FLTMODE{i}" for i in range(1, 7)] + ["INITIAL_MODE"],
    "fs": ["FS_GCS_ENABLE", "FS_GCS_TIMEOUT", "FS_THR_ENABLE", "FS_THR_VALUE",
           "FS_OPTIONS", "FS_EKF_ACTION", "FS_EKF_THRESH", "FS_CRASH_CHECK",
           "SYSID_MYGCS", "SYSID_THISMAV", "THR_FAILSAFE"],
}

# kod SERIALn_BAUD -> rzeczywisty baud
BAUD_CODE = {1: 1200, 2: 2400, 4: 4800, 9: 9600, 19: 19200, 38: 38400,
             57: 57600, 111: 111100, 115: 115200, 230: 230400, 256: 256000,
             460: 460800, 500: 500000, 921: 921600, 1500: 1500000}


def przedstaw_sie(m, port, prob=12):
    """Przy laczu UDP trzeba odezwac sie PIERWSZEMU — patrz fc_write_params.przedstaw_sie.

    Jednostka naziemna MK32 jest serwerem UDP i obsluguje TYLKO JEDNEGO klienta.
    Zatrzymaj kokpit na aparaturze, zanim uzyjesz tego narzedzia po sieci.
    """
    if not str(port).lower().startswith("udp"):
        return m.wait_heartbeat(timeout=20)
    for _ in range(prob):
        try:
            m.mav.heartbeat_send(6, 8, 0, 0, 3)      # GCS, autopilot = INVALID
        except Exception:
            pass
        hb = m.recv_match(type="HEARTBEAT", blocking=True, timeout=1)
        if hb is not None:
            m.target_system = hb.get_srcSystem()
            m.target_component = hb.get_srcComponent()
            return hb
    return None


def main():
    argv = sys.argv[1:]
    port, baud = "COM9", 115200
    names, groups = [], []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--port":
            i += 1
            port = argv[i]
        elif a == "--baud":
            i += 1
            baud = int(argv[i])
        elif a == "--group":
            i += 1
            while i < len(argv) and not argv[i].startswith("--"):
                groups.append(argv[i])
                i += 1
            continue
        else:
            names.append(a.upper())
        i += 1

    for g in groups:
        if g not in GROUPS:
            print("Nieznana grupa: %s (dostepne: %s)" % (g, ", ".join(GROUPS)))
            sys.exit(2)
        names.extend(GROUPS[g])

    # deduplikacja z zachowaniem kolejnosci
    seen = set()
    names = [n for n in names if not (n in seen or seen.add(n))]
    if not names:
        print(__doc__)
        sys.exit(2)

    m = mavutil.mavlink_connection(port, baud=baud)
    hb = przedstaw_sie(m, port)
    if hb is None:
        print("BRAK HEARTBEAT na %s @ %d" % (port, baud))
        sys.exit(1)

    got = {}
    # partiami po 25, zeby nie zapchac bufora
    for start in range(0, len(names), 25):
        chunk = names[start:start + 25]
        for _ in range(3):
            missing = [n for n in chunk if n not in got]
            if not missing:
                break
            for n in missing:
                m.mav.param_request_read_send(m.target_system, m.target_component,
                                              n.encode("ascii"), -1)
            t0 = time.time()
            while time.time() - t0 < 4 and any(n not in got for n in chunk):
                msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
                if msg is None:
                    continue
                pid = msg.param_id
                if isinstance(pid, bytes):
                    pid = pid.decode("ascii", "replace")
                pid = pid.rstrip("\x00")
                if pid in chunk:
                    got[pid] = msg.param_value

    print("# port=%s baud=%d  odczytano %d/%d" % (port, baud, len(got), len(names)))
    for n in names:
        if n not in got:
            print("%-20s (BRAK)" % n)
            continue
        v = got[n]
        extra = ""
        if n.endswith("_BAUD") and int(v) in BAUD_CODE:
            extra = "   -> %d bps" % BAUD_CODE[int(v)]
        print("%-20s %-12s%s" % (n, repr(round(v, 6)), extra))


if __name__ == "__main__":
    main()
