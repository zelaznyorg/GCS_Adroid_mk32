#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - automatyczne szukanie portu, na ktorym siedzi glowica ZR30.

    python tools\\fc_find_gimbal.py            # podglad planu, nic nie zapisuje
    python tools\\fc_find_gimbal.py --yes      # wykonuje przemiatanie

UWAGA: TO NARZEDZIE ZAPISUJE DO FC i RESTARTUJE go wielokrotnie.

Metoda (ta sama, ktora rozstrzygnela datalink MK32 = SERIAL6):
  dla kazdego kandydata po kolei:
    1. SERIALn_PROTOCOL=8 (Gimbal), BAUD=115; pozostali kandydaci -1
    2. restart FC
    3. odczekanie na inicjalizacje, wymuszenie MAV_CMD_RUN_PREARM_CHECKS
    4. sprawdzenie MNT1_DEVID oraz obecnosci "Mount: not healthy"

NIE RUSZA portow zajetych: SERIAL0 (USB), SERIAL3 (RCIN), SERIAL4 (GPS),
SERIAL6 (datalink MK32).
"""

import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_find_gimbal")

from pymavlink import mavutil

try:
    from serial.tools import list_ports
except ImportError:
    list_ports = None

PORT = "COM9"
BAUD = 115200
PORTY = [7, 1, 2, 5, 8]          # 7 pierwszy - tam wskazuja opisy na plytce
# 0 = normalnie, 8 = SwapTXRX (bit 3 wg AP_SerialManager.cpp @ Copter-4.6.3)
OPCJE = [0, 8]
KANDYDACI = [(p, o) for p in PORTY for o in OPCJE]
NIETYKALNE = {0, 3, 4, 6}        # USB, RCIN, GPS, datalink MK32 - NIE RUSZAC


def log(m=""):
    print(m, flush=True)


def polacz(timeout=60):
    """Czeka az port wroci po restarcie i zwraca polaczenie."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if list_ports:
            if PORT not in [p.device for p in list_ports.comports()]:
                time.sleep(0.5)
                continue
        try:
            m = mavutil.mavlink_connection(PORT, baud=BAUD)
            if m.wait_heartbeat(timeout=10) is not None:
                return m
            m.close()
        except Exception:
            time.sleep(0.5)
    return None


def ustaw(m, nazwa, wartosc):
    m.mav.param_set_send(m.target_system, m.target_component,
                         nazwa.encode("ascii"), float(wartosc),
                         mavutil.mavlink.MAV_PARAM_TYPE_REAL32)
    t0 = time.time()
    while time.time() - t0 < 4:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
        if msg is None:
            continue
        pid = msg.param_id
        if isinstance(pid, bytes):
            pid = pid.decode("ascii", "replace")
        if pid.rstrip("\x00") == nazwa:
            return msg.param_value
    return None


def czytaj(m, nazwa):
    m.mav.param_request_read_send(m.target_system, m.target_component,
                                  nazwa.encode("ascii"), -1)
    t0 = time.time()
    while time.time() - t0 < 4:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=1)
        if msg is None:
            continue
        pid = msg.param_id
        if isinstance(pid, bytes):
            pid = pid.decode("ascii", "replace")
        if pid.rstrip("\x00") == nazwa:
            return msg.param_value
    return None


def sprawdz_glowice(m, sekundy=20):
    """Zwraca (devid, czy_zdrowa, lista_tekstow)."""
    m.mav.command_long_send(m.target_system, m.target_component,
                            401, 0, 0, 0, 0, 0, 0, 0, 0)
    teksty, gimbal_msg = [], 0
    t0 = time.time()
    while time.time() - t0 < sekundy:
        msg = m.recv_match(blocking=True, timeout=1)
        if msg is None:
            continue
        t = msg.get_type()
        if t == "STATUSTEXT":
            x = msg.text
            if isinstance(x, bytes):
                x = x.decode("utf-8", "replace")
            x = x.rstrip("\x00")
            if x not in teksty:
                teksty.append(x)
        elif t in ("GIMBAL_DEVICE_ATTITUDE_STATUS", "GIMBAL_DEVICE_INFORMATION"):
            gimbal_msg += 1
    devid = czytaj(m, "MNT1_DEVID")
    niezdrowa = any("Mount" in t and "not healthy" in t for t in teksty)
    return devid, (not niezdrowa), teksty, gimbal_msg


def main():
    zapis = "--yes" in sys.argv
    log("=" * 68)
    log("DRON 15 - szukanie portu glowicy ZR30   tryb: %s" % (
        "ZAPIS + RESTARTY" if zapis else "PROBNY (nic nie zapisuje)"))
    log("=" * 68)
    log("kandydaci: %d prob (%s) x opcje %s" % (
        len(KANDYDACI), ", ".join("SERIAL%d" % p for p in PORTY),
        "0=normalnie, 8=SwapTXRX"))
    log("nietykalne: %s" % ", ".join("SERIAL%d" % k for k in sorted(NIETYKALNE)))
    if not zapis:
        log("\nUruchom z --yes, zeby wykonac.")
        return

    wynik = None
    for kand, opcja in KANDYDACI:
        log("\n" + "-" * 68)
        log(">>> PROBA: glowica na SERIAL%d, linie %s" % (
            kand, "zamienione (SwapTXRX)" if opcja else "normalnie"))
        m = polacz()
        if m is None:
            log("    BRAK POLACZENIA - przerywam")
            return

        for k in PORTY:
            if k in NIETYKALNE:
                continue
            cel = 8 if k == kand else -1
            got = ustaw(m, "SERIAL%d_PROTOCOL" % k, cel)
            ustaw(m, "SERIAL%d_OPTIONS" % k, opcja if k == kand else 0)
            if k == kand:
                ustaw(m, "SERIAL%d_BAUD" % k, 115)
            log("    SERIAL%d_PROTOCOL -> %-4s OPTIONS -> %s%s" % (
                k, cel, opcja if k == kand else 0,
                "" if got == cel else "  (BLAD, zwrocono %s)" % got))

        log("    restart...")
        m.mav.command_long_send(m.target_system, m.target_component,
                                mavutil.mavlink.MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN,
                                0, 1, 0, 0, 0, 0, 0, 0)
        try:
            m.close()
        except Exception:
            pass
        time.sleep(6)

        m = polacz()
        if m is None:
            log("    FC nie wrocil - przerywam")
            return
        log("    FC wrocil, czekam na inicjalizacje glowicy...")
        time.sleep(8)

        devid, zdrowa, teksty, gmsg = sprawdz_glowice(m)
        log("    MNT1_DEVID = %s   zdrowa = %s   ramek gimbala = %d" % (
            devid, zdrowa, gmsg))
        for t in teksty:
            if "Mount" in t or "Gimbal" in t or "SIYI" in t:
                log("      FC> %s" % t)
        try:
            m.close()
        except Exception:
            pass

        if (devid not in (None, 0.0)) or zdrowa or gmsg > 0:
            log("\n    *** ZNALEZIONE: glowica odpowiada na SERIAL%d (OPTIONS=%d) ***"
                % (kand, opcja))
            wynik = (kand, opcja)
            break

    log("\n" + "=" * 68)
    if wynik:
        log("WYNIK: glowica ZR30 na SERIAL%d, OPTIONS=%d%s" % (
            wynik[0], wynik[1],
            "  (linie TX/RX zamienione programowo)" if wynik[1] else ""))
        log("Zostawiam tak ustawione.")
        log("PAMIETAJ: wg instrukcji ZR30 str. 84 ustawic SRn_EXTRA1=50 na kanale")
        log("odpowiadajacym temu portowi - strumien orientacji z FC do glowicy.")
    else:
        log("WYNIK: zaden kandydat nie odpowiedzial, w zadnym wariancie linii.")
        log("Numeracja portu i zamiana TX/RX sa wykluczone.")
        log("Zostaje: czy odnoga UART kabla faktycznie dochodzi do plyty,")
        log("wspolna masa (GND), oraz tryb protokolu ustawiony w samej glowicy.")


if __name__ == "__main__":
    main()
