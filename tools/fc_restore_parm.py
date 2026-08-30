#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - przywrocenie pelnej konfiguracji z pliku .parm

    python tools\\fc_restore_parm.py dok\\plik.parm            # dry-run
    python tools\\fc_restore_parm.py dok\\plik.parm --yes       # zapis
    python tools\\fc_restore_parm.py dok\\plik.parm --yes --reboot

Dziala inaczej niz fc_write_params.py: najpierw sciaga KOMPLET parametrow
jednym zapytaniem (szybko), porownuje z plikiem i zapisuje TYLKO roznice.
Przy 1300 parametrach to roznica miedzy kilkoma minutami a godzina.

ZAWSZE robi kopie zapasowa obecnego stanu do dok\\fc_backup_<data>.parm
- przywrocenie: python tools\\fc_restore_parm.py dok\\fc_backup_<data>.parm --yes

POMIJA liczniki i pola systemowe, ktore nie sa konfiguracja:
STAT_BOOTCNT, STAT_FLTTIME, STAT_RUNTIME, STAT_RESET, FORMAT_VERSION.

UWAGA: TO NARZEDZIE ZAPISUJE DO FC.
"""

import sys
import os
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_restore_parm")

from pymavlink import mavutil

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE) if os.path.basename(HERE).lower() == "tools" else HERE
DOK = os.path.join(PROJ, "dok")

POMIJANE = {"STAT_BOOTCNT", "STAT_FLTTIME", "STAT_RUNTIME", "STAT_RESET",
            "FORMAT_VERSION", "SYSID_SW_MREV"}


def log(m=""):
    print(m, flush=True)


def wczytaj_parm(sciezka):
    d = {}
    with open(sciezka, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line[0] in "#/":
                continue
            for sep in (",", "\t", " ", "="):
                if sep in line:
                    n, _, v = line.partition(sep)
                    n = n.strip().upper()
                    v = v.strip().split()[0] if v.strip() else ""
                    if n and v:
                        try:
                            d[n] = float(v)
                        except ValueError:
                            pass
                    break
    return d


def sciagnij(m, timeout=120):
    params, total = {}, None
    m.mav.param_request_list_send(m.target_system, m.target_component)
    t0 = last = time.time()
    while time.time() - t0 < timeout:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=2)
        if msg is None:
            if total and len(params) >= total:
                break
            if time.time() - last > 6:
                break
            continue
        last = time.time()
        total = msg.param_count
        n = msg.param_id
        if isinstance(n, bytes):
            n = n.decode("utf-8", "replace")
        params[n.rstrip("\x00")] = msg.param_value
        if total and len(params) >= total:
            break
    return params, total


def zapisz(m, n, v, timeout=3.0):
    m.mav.param_set_send(m.target_system, m.target_component,
                         n.encode("ascii"), float(v),
                         mavutil.mavlink.MAV_PARAM_TYPE_REAL32)
    t0 = time.time()
    while time.time() - t0 < timeout:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
        if msg is None:
            continue
        pid = msg.param_id
        if isinstance(pid, bytes):
            pid = pid.decode("ascii", "replace")
        if pid.rstrip("\x00") == n:
            return msg.param_value
    return None


def main():
    args = [a for a in sys.argv[1:] if a]
    plik = next((a for a in args if not a.startswith("--")), None)
    zapis = "--yes" in args
    reboot = "--reboot" in args
    port = "COM9"
    if "--port" in args:
        port = args[args.index("--port") + 1]

    if not plik or not os.path.exists(plik):
        print(__doc__)
        sys.exit(2)

    cel = wczytaj_parm(plik)
    log("=" * 70)
    log("DRON 15 - przywracanie konfiguracji z pliku")
    log("plik : %s  (%d parametrow)" % (plik, len(cel)))
    log("tryb : %s" % ("ZAPIS" if zapis else "PROBNY (dry-run)"))
    log("=" * 70)

    m = mavutil.mavlink_connection(port, baud=115200)
    if m.wait_heartbeat(timeout=20) is None:
        log("BRAK HEARTBEAT na %s" % port)
        sys.exit(1)

    log("\n[1] sciagam komplet parametrow z FC...")
    teraz, total = sciagnij(m)
    log("    pobrano %d (FC deklaruje %s)" % (len(teraz), total))

    stamp = time.strftime("%Y%m%d_%H%M%S")
    kopia = os.path.join(DOK, "fc_backup_%s.parm" % stamp)
    os.makedirs(DOK, exist_ok=True)
    with open(kopia, "w", encoding="utf-8") as f:
        for k in sorted(teraz):
            f.write("%s,%s\n" % (k, repr(round(teraz[k], 8))))
    log("    KOPIA ZAPASOWA: %s" % kopia)

    roznice, brakujace = [], []
    for n in sorted(cel):
        if n in POMIJANE:
            continue
        if n not in teraz:
            brakujace.append(n)
            continue
        if abs(teraz[n] - cel[n]) > 1e-6:
            roznice.append((n, teraz[n], cel[n]))

    log("\n[2] do zmiany: %d   pominietych licznikow: %d   nieobecnych w FC: %d"
        % (len(roznice), len(POMIJANE & set(cel)), len(brakujace)))
    if brakujace:
        log("    nieobecne: %s" % ", ".join(brakujace[:20]))

    if not zapis:
        log("\n--- PODGLAD (nic nie zapisano) ---")
        for n, a, b in roznice:
            log("  %-20s %-14s -> %s" % (n, round(a, 6), round(b, 6)))
        log("\nUruchom z --yes, zeby zapisac.")
        return

    log("\n[3] zapisuje...")
    ok = bledy = 0
    nieudane = []
    for i, (n, a, b) in enumerate(roznice, 1):
        got = zapisz(m, n, b)
        if got is not None and abs(got - b) < 1e-6:
            ok += 1
        else:
            bledy += 1
            nieudane.append((n, a, b, got))
        if i % 25 == 0:
            log("    %d / %d" % (i, len(roznice)))

    log("\n[4] wynik: zapisane %d, bledy %d" % (ok, bledy))
    if nieudane:
        log("    nieudane:")
        for n, a, b, got in nieudane:
            log("      %-20s %s -> %s  (FC zwrocil %s)" % (n, round(a, 6), b, got))

    if reboot:
        log("\n[5] restart FC...")
        m.mav.command_long_send(m.target_system, m.target_component,
                                mavutil.mavlink.MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN,
                                0, 1, 0, 0, 0, 0, 0, 0)

    log("\nCofniecie tej operacji:")
    log("  python tools\\fc_restore_parm.py %s --yes --reboot" % kopia)


if __name__ == "__main__":
    main()
