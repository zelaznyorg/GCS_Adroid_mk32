#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - ZAPIS parametrow do FC.  UWAGA: to jedyne narzedzie w projekcie,
ktore modyfikuje kontroler.  Domyslnie dziala w trybie PROBNYM (dry-run).

    # podglad: co by sie zmienilo, nic nie zapisuje
    python tools\\fc_write_params.py BARO_PROBE_EXT=66 BARO_EXT_BUS=0

    # faktyczny zapis
    python tools\\fc_write_params.py --yes BARO_PROBE_EXT=66 BARO_EXT_BUS=0

    # z pliku (linie NAZWA=WARTOSC, # = komentarz)
    python tools\\fc_write_params.py --yes --file plan_lacze_mk32.txt

    # restart FC po zapisie
    python tools\\fc_write_params.py --yes --file plan.txt --reboot

Zasady:
  * przed zapisem odczytuje i loguje wartosc BIEZACA (mozliwosc cofniecia)
  * po zapisie odczytuje ponownie i weryfikuje
  * parametr nieistniejacy w FC -> BLAD, nie jest tworzony
  * pelny log przed/po -> dok\\fc_write_<data>.log
"""

import sys
import os
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_write_params")

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

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE) if os.path.basename(HERE).lower() == "tools" else HERE
DOK = os.path.join(PROJ, "dok")

LINES = []


def log(msg=""):
    print(msg, flush=True)
    LINES.append(msg)


def read_param(m, name, timeout=4.0):
    """Zwraca wartosc parametru albo None gdy FC nie odpowiada / brak parametru."""
    m.mav.param_request_read_send(m.target_system, m.target_component,
                                  name.encode("ascii"), -1)
    t0 = time.time()
    while time.time() - t0 < timeout:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
        if msg is None:
            continue
        pid = msg.param_id
        if isinstance(pid, bytes):
            pid = pid.decode("ascii", "replace")
        if pid.rstrip("\x00") == name:
            return msg.param_value
    return None


def write_param(m, name, value, timeout=4.0):
    """Zapisuje i zwraca wartosc potwierdzona przez FC (albo None)."""
    m.mav.param_set_send(m.target_system, m.target_component,
                         name.encode("ascii"), float(value),
                         mavutil.mavlink.MAV_PARAM_TYPE_REAL32)
    t0 = time.time()
    while time.time() - t0 < timeout:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
        if msg is None:
            continue
        pid = msg.param_id
        if isinstance(pid, bytes):
            pid = pid.decode("ascii", "replace")
        if pid.rstrip("\x00") == name:
            return msg.param_value
    return None


def parse_pairs(items):
    out = []
    for it in items:
        if "=" not in it:
            print("BLAD: oczekiwano NAZWA=WARTOSC, dostalem: %s" % it)
            sys.exit(2)
        n, _, v = it.partition("=")
        out.append((n.strip().upper(), float(v.strip())))
    return out


def load_file(path):
    items = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                items.append(line)
    return parse_pairs(items)


def przedstaw_sie(m, port, prob=12):
    """Przy laczu UDP trzeba odezwac sie PIERWSZEMU.

    Jednostka naziemna MK32 jest serwerem UDP: nadaje wylacznie do tego, kto sie do niej
    odezwal. `wait_heartbeat()` na swiezym `udpout:` czeka wiec na gniezdzie, ktore nic
    jeszcze nie wyslalo — w Windows konczy sie to OSError 10022 (zmierzone 2026-08-26).
    Wysylamy najpierw heartbeat stacji naziemnej i dopiero potem czekamy.

    UWAGA: jednostka naziemna obsluguje TYLKO JEDNEGO klienta. Zanim uzyjesz tego
    narzedzia po UDP, zatrzymaj kokpit na aparaturze — inaczej odbierzesz mu telemetrie.
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
    do_write = False
    do_reboot = False
    pairs = []
    raw = []

    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--port":
            i += 1; port = argv[i]
        elif a == "--baud":
            i += 1; baud = int(argv[i])
        elif a in ("--yes", "-y"):
            do_write = True
        elif a == "--reboot":
            do_reboot = True
        elif a == "--file":
            i += 1; pairs.extend(load_file(argv[i]))
        else:
            raw.append(a)
        i += 1
    pairs.extend(parse_pairs(raw))

    if not pairs and not do_reboot:
        print(__doc__)
        sys.exit(2)

    tryb = "ZAPIS" if do_write else "PROBNY (dry-run, nic nie zapisuje)"
    log("=" * 70)
    log("DRON 15 - fc_write_params   tryb: %s" % tryb)
    log("czas: %s   port: %s @ %d" % (time.strftime("%Y-%m-%d %H:%M:%S"), port, baud))
    log("=" * 70)

    m = mavutil.mavlink_connection(port, baud=baud)
    if przedstaw_sie(m, port) is None:
        log("BRAK HEARTBEAT - port zajety albo zly baud.")
        sys.exit(1)

    log("")
    log("%-20s %-14s %-14s %s" % ("PARAMETR", "BYLO", "MA BYC", "WYNIK"))
    log("-" * 70)

    errors = 0
    changed = 0
    for name, target in pairs:
        cur = read_param(m, name)
        if cur is None:
            log("%-20s %-14s %-14s BLAD: brak parametru w FC" % (name, "?", target))
            errors += 1
            continue
        if abs(cur - target) < 1e-6:
            log("%-20s %-14s %-14s bez zmian" % (name, repr(round(cur, 6)), target))
            continue
        if not do_write:
            log("%-20s %-14s %-14s -> DO ZAPISU" % (name, repr(round(cur, 6)), target))
            changed += 1
            continue
        got = write_param(m, name, target)
        if got is None:
            log("%-20s %-14s %-14s BLAD: brak potwierdzenia" % (name, repr(round(cur, 6)), target))
            errors += 1
        elif abs(got - target) > 1e-6:
            log("%-20s %-14s %-14s BLAD: FC zwrocil %s" % (
                name, repr(round(cur, 6)), target, repr(round(got, 6))))
            errors += 1
        else:
            log("%-20s %-14s %-14s OK" % (name, repr(round(cur, 6)), target))
            changed += 1

    log("-" * 70)
    log("zmienione: %d   bledy: %d" % (changed, errors))

    if do_reboot:
        if not do_write:
            log("\n(tryb probny - restart pominiety)")
        else:
            log("\nRestart FC...")
            m.mav.command_long_send(m.target_system, m.target_component,
                                    mavutil.mavlink.MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN,
                                    0, 1, 0, 0, 0, 0, 0, 0)
            log("wyslano MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN")
            log("port USB znika i wraca po ~5-10 s - odczekaj przed kolejnym poleceniem")

    if do_write:
        os.makedirs(DOK, exist_ok=True)
        path = os.path.join(DOK, "fc_write_%s.log" % time.strftime("%Y%m%d_%H%M%S"))
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(LINES) + "\n")
        print("\nlog: %s" % path)

    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
