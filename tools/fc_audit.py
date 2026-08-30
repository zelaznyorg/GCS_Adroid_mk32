#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - przeglad konfiguracji wg podsystemow (tylko-odczyt, z pliku).

    python tools\\fc_audit.py dok\\ODNIESIENIE_QUAD_20260815.parm

Grupuje parametry tematycznie i oznacza:
  OK    wartosc zgodna z oczekiwaniem dla tej maszyny
  UWAGA wartosc dopuszczalna, ale warta swiadomej decyzji
  BLAD  wartosc sprzeczna z konfiguracja sprzetu
"""

import sys
import os

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_audit")


def wczytaj(p):
    d = {}
    for line in open(p, "r", encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line or line[0] in "#/":
            continue
        n, _, v = line.partition(",")
        try:
            d[n.strip().upper()] = float(v)
        except ValueError:
            pass
    return d


def main():
    if len(sys.argv) < 2 or not os.path.exists(sys.argv[1]):
        print(__doc__); sys.exit(2)
    p = wczytaj(sys.argv[1])

    def g(n):
        return p.get(n)

    def w(nazwa, wart, status, opis=""):
        znak = {"OK": "  OK  ", "UWAGA": " UWAGA", "BLAD": " BLAD "}[status]
        print("%s %-20s %-14s %s" % (znak, nazwa, wart, opis))

    print("=" * 78)
    print("DRON 15 - przeglad konfiguracji: %s" % os.path.basename(sys.argv[1]))
    print("=" * 78)

    print("\n--- RAMA I NAPED ---")
    fc, ft = g("FRAME_CLASS"), g("FRAME_TYPE")
    w("FRAME_CLASS", fc, "OK" if fc == 1 else "BLAD",
      "1=QUAD" if fc == 1 else "!!! 4=OCTAQUAD, a maszyna ma 4 silniki")
    w("FRAME_TYPE", ft, "OK" if ft == 1 else "UWAGA", "1=X")
    w("MOT_PWM_TYPE", g("MOT_PWM_TYPE"), "OK", "6=DShot600")
    silniki = [n for n in range(1, 17) if g("SERVO%d_FUNCTION" % n) in (33, 34, 35, 36)]
    reszta = [n for n in range(5, 17) if (g("SERVO%d_FUNCTION" % n) or 0) != 0]
    w("SERVO1-4 = Motor1-4", len(silniki), "OK" if len(silniki) == 4 else "BLAD")
    w("SERVO5-16 wolne", "tak" if not reszta else reszta,
      "OK" if not reszta else "BLAD", "" if not reszta else "pozostalosci po OctaQuad")
    w("MOT_THST_HOVER", g("MOT_THST_HOVER"), "UWAGA",
      "wyuczone przy blednym mikslerze - MOT_HOVER_LEARN=2 przeliczy w locie")
    w("MOT_SPIN_ARM", g("MOT_SPIN_ARM"), "UWAGA", "fabryczne, do sprawdzenia na ziemi")
    w("MOT_SPIN_MIN", g("MOT_SPIN_MIN"), "UWAGA", "fabryczne")

    print("\n--- PORTY SZEREGOWE ---")
    opis = {0: "USB / Mission Planner", 3: "RCIN (S.Bus z MK32)", 4: "GPS (UM982)",
            6: "MAVLink (datalink MK32)", 2: "Gimbal (ZR30, UART1 plyty)"}
    for i in range(9):
        pr = g("SERIAL%d_PROTOCOL" % i)
        if pr is None:
            continue
        nazwa = opis.get(i, "wolny")
        st = "OK" if (pr != -1 or nazwa == "wolny") else "UWAGA"
        w("SERIAL%d_PROTOCOL" % i, pr, st, nazwa)
    w("SERIAL6_OPTIONS", g("SERIAL6_OPTIONS"), "OK",
      "4096=Ignore Streamrate, blokuje degradacje stawek przez aplikacje MK32")

    print("\n--- WEJSCIE RC ---")
    w("RC_PROTOCOLS", g("RC_PROTOCOLS"), "OK", "1=autodetekcja")
    for c in (1, 2, 3, 4):
        mn, mx, tr = g("RC%d_MIN" % c), g("RC%d_MAX" % c), g("RC%d_TRIM" % c)
        ok = mn == 1045 and mx == 1945
        w("RC%d min/max/trim" % c, "%s/%s/%s" % (mn, mx, tr),
          "OK" if ok else "UWAGA", "skalibrowane pod MK32" if ok else "spoza zakresu MK32")
    w("RSSI_TYPE", g("RSSI_TYPE"), "UWAGA",
      "3=ReceiverProtocol, ale S.Bus nie niesie RSSI -> zawsze 0")

    print("\n--- FUNKCJE AUX ---")
    AUX = {4: "RTL", 11: "Fence", 153: "Arm/Disarm", 213: "MOUNT1_PITCH",
           214: "MOUNT1_YAW", 167: "Camera Zoom", 166: "Camera Record", 17: "AUTOTUNE"}
    uzyte = {}
    for c in range(5, 17):
        o = g("RC%d_OPTION" % c)
        if o and o != 0:
            uzyte.setdefault(o, []).append(c)
            w("RC%d_OPTION" % c, o, "OK", AUX.get(o, "?"))
    dup = {k: v for k, v in uzyte.items() if len(v) > 1}
    w("duplikaty AUX", "brak" if not dup else dup, "OK" if not dup else "BLAD")
    w("AUTOTUNE na przelaczniku", "brak" if 17 not in uzyte else uzyte[17],
      "UWAGA" if 17 not in uzyte else "OK", "wymagane przed strojeniem")

    print("\n--- TRYBY LOTU ---")
    M = {0: "Stabilize", 2: "AltHold", 3: "Auto", 4: "Guided", 5: "Loiter",
         6: "RTL", 16: "PosHold"}
    for s, poz in ((1, "dol"), (4, "srodek"), (6, "gora")):
        v = g("FLTMODE%d" % s)
        bez_gnss = v in (0, 2)
        w("FLTMODE%d (%s)" % (s, poz), v, "OK",
          "%s%s" % (M.get(v, v), "  <- dziala bez GNSS" if bez_gnss else ""))

    print("\n--- FAILSAFE ---")
    w("FS_THR_ENABLE", g("FS_THR_ENABLE"), "OK", "1=RTL przy utracie RC")
    fsv, rc3 = g("FS_THR_VALUE"), g("RC3_MIN")
    w("FS_THR_VALUE", fsv, "OK" if fsv < rc3 else "BLAD",
      "prog %s ponizej RC3_MIN=%s" % ("jest" if fsv < rc3 else "NIE JEST", rc3))
    w("FS_GCS_ENABLE", g("FS_GCS_ENABLE"), "UWAGA", "0=wylaczony (poz. 7)")
    cc = g("FS_CRASH_CHECK")
    w("FS_CRASH_CHECK", cc, "OK" if cc else "BLAD",
      "1=detekcja rozbicia wlaczona" if cc else "0=DETEKCJA ROZBICIA WYLACZONA (poz. 18)")
    w("FS_OPTIONS", g("FS_OPTIONS"), "UWAGA", "4=Continue if in Guided on RC failsafe")
    w("FS_EKF_ACTION", g("FS_EKF_ACTION"), "OK", "1=Land")
    w("ARMING_CHECK", g("ARMING_CHECK"), "OK", "1=wszystkie kontrole")

    print("\n--- BATERIA ---")
    w("BATT_MONITOR", g("BATT_MONITOR"), "OK", "4=napiecie i prad")
    w("BATT_CAPACITY", g("BATT_CAPACITY"), "UWAGA",
      "3300 mAh - wartosc domyslna, prawie na pewno nieprawdziwa (poz. 9)")
    w("BATT_LOW_VOLT", g("BATT_LOW_VOLT"), "OK", "6S: 3,70 V/ogniwo")
    w("BATT_CRT_VOLT", g("BATT_CRT_VOLT"), "OK", "6S: 3,50 V/ogniwo")
    w("BATT_VOLT_MULT", g("BATT_VOLT_MULT"), "OK", "skalibrowane")

    print("\n--- NAWIGACJA ---")
    w("GPS1_TYPE", g("GPS1_TYPE"), "OK", "25=Unicore moving baseline NMEA")
    w("GPS1_MB_TYPE", g("GPS1_MB_TYPE"), "OK")
    w("GPS1_MB_OFS_X", g("GPS1_MB_OFS_X"), "OK", "baza 0,40 m, master z przodu")
    w("EK3_SRC1_YAW", g("EK3_SRC1_YAW"), "UWAGA", "2=GPS - JEDYNE zrodlo kursu")
    w("EK3_SRC2_YAW", g("EK3_SRC2_YAW"), "UWAGA", "0=brak zrodla zapasowego")
    w("COMPASS_USE", g("COMPASS_USE"), "OK", "0=brak kompasu na pokladzie")
    w("COMPASS_ENABLE", g("COMPASS_ENABLE"), "UWAGA",
      "1, choc wszystkie COMPASS_DEV_ID sa zerowe - mozna wylaczyc dla porzadku")

    print("\n--- GEOFENCE ---")
    w("FENCE_ENABLE", g("FENCE_ENABLE"), "OK")
    w("FENCE_TYPE", g("FENCE_TYPE"), "OK", "3=wysokosc i promien")
    w("FENCE_ALT_MAX", g("FENCE_ALT_MAX"), "OK", "m")
    w("FENCE_RADIUS", g("FENCE_RADIUS"), "OK", "m")
    w("FENCE_ACTION", g("FENCE_ACTION"), "OK", "1=RTL")

    print("\n--- POWROT DO DOMU ---")
    w("RTL_ALT", g("RTL_ALT"), "OK", "cm -> %.0f m" % ((g("RTL_ALT") or 0) / 100))
    w("RTL_CLIMB_MIN", g("RTL_CLIMB_MIN"), "OK", "cm minimalnego wznoszenia")
    w("RTL_ALT_FINAL", g("RTL_ALT_FINAL"), "OK", "0=ladowanie automatyczne")
    w("RTL_SPEED", g("RTL_SPEED"), "OK", "0 -> WPNAV_SPEED=%s cm/s" % g("WPNAV_SPEED"))

    print("\n--- GLOWICA I KAMERA ---")
    w("MNT1_TYPE", g("MNT1_TYPE"), "OK", "8=SIYI")
    w("CAM1_TYPE", g("CAM1_TYPE"), "OK", "4=Mount/SIYI")
    w("MNT1_RC_RATE", g("MNT1_RC_RATE"), "UWAGA",
      "producent i wiki ArduPilota zalecaja 90 deg/s")
    w("MNT1_YAW_MIN/MAX", "%s / %s" % (g("MNT1_YAW_MIN"), g("MNT1_YAW_MAX")), "OK",
      "pelne 360 st, maksimum ArduPilota")
    w("MNT1_PITCH_MIN/MAX", "%s / %s" % (g("MNT1_PITCH_MIN"), g("MNT1_PITCH_MAX")), "OK")

    print("\n--- FILTRY I STROJENIE ---")
    w("SCHED_LOOP_RATE", g("SCHED_LOOP_RATE"), "OK", "400 Hz, fabryczne dla Coptera")
    w("INS_GYRO_FILTER", g("INS_GYRO_FILTER"), "OK", "20 Hz, fabryczne")
    w("INS_HNTCH_ENABLE", g("INS_HNTCH_ENABLE"), "UWAGA",
      "filtr wycinajacy WYLACZONY - ustawic PRZED Autotune")
    w("FFT_ENABLE", g("FFT_ENABLE"), "UWAGA", "wylaczone - przydatne do analizy zawisu")
    w("ATC_RAT_RLL_P", g("ATC_RAT_RLL_P"), "UWAGA", "fabryczne (poz. 6)")
    w("ATC_RAT_PIT_P", g("ATC_RAT_PIT_P"), "UWAGA", "fabryczne")
    w("ATC_RAT_YAW_P", g("ATC_RAT_YAW_P"), "UWAGA", "fabryczne")
    w("LOG_BITMASK", g("LOG_BITMASK"), "OK", "pelne logowanie - potrzebne do strojenia")

    print("\n--- KALIBRACJE ---")
    sc = [g("INS_ACCSCAL_%s" % a) for a in "XYZ"]
    w("INS_ACCSCAL X/Y/Z", "%.4f / %.4f / %.4f" % tuple(sc),
      "OK" if all(abs(s - 1.0) > 1e-4 for s in sc) else "BLAD",
      "sekwencja 6-pozycyjna przeszla" if all(abs(s - 1.0) > 1e-4 for s in sc)
      else "skale = 1,0 -> kalibracja NIEPELNA")
    tx, ty = g("AHRS_TRIM_X"), g("AHRS_TRIM_Y")
    w("AHRS_TRIM X/Y", "%.4f / %.4f" % (tx, ty), "OK",
      "%.2f st / %.2f st" % (tx * 57.2958, ty * 57.2958))
    w("INS_ACC_ID", g("INS_ACC_ID"), "OK", "ICM-42688")
    w("BARO1_DEVID", g("BARO1_DEVID"), "OK", "DPS280")

    print("\n--- DRAZEK GAZU ---")
    w("PILOT_THR_BHV", g("PILOT_THR_BHV"), "OK",
      "1=punkt odniesienia na srodku (drazek samocentrujacy)")
    w("RC3_TRIM", g("RC3_TRIM"), "UWAGA", "1495 = drazek samocentrujacy (poz. 24)")
    w("THR_DZ", g("THR_DZ"), "OK", "strefa martwa wokol srodka")
    w("PILOT_SPEED_UP", g("PILOT_SPEED_UP"), "OK", "cm/s wznoszenia w AltHold")
    w("PILOT_SPEED_DN", g("PILOT_SPEED_DN"), "OK", "cm/s opadania")
    ar = g("ARMING_RUDDER")
    w("ARMING_RUDDER", ar, "OK" if ar == 0 else "UWAGA",
      "0=wylaczone, uzbrajanie tylko przelacznikiem CH9" if ar == 0
      else "2=rozbrajanie drazkiem kierunku - ryzyko rozbrojenia w locie")


if __name__ == "__main__":
    main()
