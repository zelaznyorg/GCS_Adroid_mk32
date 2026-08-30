#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - polaczenie z kontrolerem lotu (Cobra H743 / ArduPilot) po COM.

Uruchamiac NA WINDOWS, w katalogu projektu:
    python tools\\fc_connect.py                 # autodetekcja portu
    python tools\\fc_connect.py COM7            # port wskazany recznie
    python tools\\fc_connect.py COM7 115200     # port + baud

Co robi:
  1. wykrywa port COM kontrolera (VID/PID ArduPilot/STM32)
  2. laczy sie, czeka na HEARTBEAT
  3. pobiera wersje FW, typ plyty, ID sprzetu
  4. sciaga KOMPLET parametrow -> dok\\fc_dump_<data>\\params.parm
  5. zbiera 8 s telemetrii (IMU, baro, GPS, bateria, EKF, RC, gimbal)
  6. porownuje parametry z baza dok\\ardupilot_params_20260811_gimbal_ok.parm
  7. zapisuje raport TXT + JSON

NIC NIE ZAPISUJE DO FC. Tylko odczyt.
"""

import sys
import os
import json
import time
import glob

from dziennik import zainstaluj

zainstaluj("fc_connect")

# --- konsola Windows: wymus UTF-8, zeby polskie znaki nie wywalaly skryptu ---
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from pymavlink import mavutil
except ImportError:
    print("BLAD: brak pymavlink.  Zainstaluj:  pip install pymavlink pyserial")
    sys.exit(2)

try:
    from serial.tools import list_ports
except ImportError:
    list_ports = None


HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE) if os.path.basename(HERE).lower() == "tools" else HERE
DOK = os.path.join(PROJ, "dok")
BASELINE = os.path.join(DOK, "ardupilot_params_20260811_gimbal_ok.parm")

# VID znane dla ArduPilot / STM32 CDC
KNOWN_VIDS = {0x1209, 0x2DAE, 0x0483, 0x26AC, 0x3162}

MAV_TYPE = {1: "FIXED_WING", 2: "QUADROTOR", 13: "HEXAROTOR", 14: "OCTOROTOR",
            15: "TRICOPTER", 10: "GROUND_ROVER"}
MAV_STATE = {0: "UNINIT", 1: "BOOT", 2: "CALIBRATING", 3: "STANDBY",
             4: "ACTIVE", 5: "CRITICAL", 6: "EMERGENCY", 7: "POWEROFF",
             8: "FLIGHT_TERMINATION"}
GPS_FIX = {0: "brak GPS", 1: "brak fixa", 2: "2D", 3: "3D", 4: "DGPS",
           5: "RTK float", 6: "RTK fixed", 7: "static", 8: "PPP"}

# bity SYS_STATUS onboard sensors (najwazniejsze dla nas)
SENSOR_BITS = [
    (0x01, "zyroskop 3D"),
    (0x02, "akcelerometr 3D"),
    (0x04, "magnetometr 3D"),
    (0x08, "cisnienie absolutne"),
    (0x20, "GPS"),
    (0x400, "stabilizacja 3-osiowa"),
    (0x1000, "wejscie RC"),
    (0x20000, "bateria"),
    (0x100000, "predkosc katowa"),
    (0x2000000, "predefiniowany AHRS"),
    (0x4000000, "terrain"),
    (0x20000000, "bateria - poziom"),
]


def log(msg=""):
    print(msg, flush=True)


def find_port(explicit=None):
    """Zwraca (port, opis, lista_wszystkich)."""
    ports = list(list_ports.comports()) if list_ports else []
    listing = [{"device": p.device, "desc": p.description,
                "vid": p.vid, "pid": p.pid, "hwid": p.hwid} for p in ports]

    if explicit:
        return explicit, "wskazany recznie", listing

    # 1) po VID
    for p in ports:
        if p.vid in KNOWN_VIDS:
            return p.device, p.description, listing
    # 2) po opisie
    for p in ports:
        d = (p.description or "").lower()
        if any(k in d for k in ("ardupilot", "px4", "pixhawk", "usb serial device",
                                "stm32", "cube", "mavlink")):
            return p.device, p.description, listing
    # 3) jedyny port
    if len(ports) == 1:
        return ports[0].device, ports[0].description, listing
    return None, None, listing


def read_parm_file(path):
    """Parser pliku .parm Mission Plannera (NAZWA<sep>WARTOSC)."""
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line[0] in "#/":
                continue
            for sep in ("\t", ",", " "):
                if sep in line:
                    name, _, val = line.partition(sep)
                    name = name.strip().upper()
                    val = val.strip().split()[0] if val.strip() else ""
                    if name and val:
                        try:
                            out[name] = float(val)
                        except ValueError:
                            pass
                    break
    return out


def fetch_params(m, timeout=120):
    """Sciaga komplet parametrow z retry na brakujace indeksy."""
    params, total = {}, None
    m.mav.param_request_list_send(m.target_system, m.target_component)
    t0 = last = time.time()
    got_idx = set()

    while time.time() - t0 < timeout:
        msg = m.recv_match(type="PARAM_VALUE", blocking=True, timeout=2)
        if msg is None:
            if total and len(params) >= total:
                break
            if time.time() - last > 6:
                # ponow zapytanie o brakujace indeksy
                if total:
                    missing = [i for i in range(total) if i not in got_idx]
                    if not missing:
                        break
                    log("    ...ponawiam %d brakujacych parametrow" % len(missing))
                    for i in missing[:60]:
                        m.mav.param_request_read_send(
                            m.target_system, m.target_component, b"", i)
                    last = time.time()
                else:
                    m.mav.param_request_list_send(m.target_system, m.target_component)
                    last = time.time()
            continue

        last = time.time()
        total = msg.param_count
        name = msg.param_id
        if isinstance(name, bytes):
            name = name.decode("utf-8", "replace")
        name = name.rstrip("\x00")
        params[name] = msg.param_value
        got_idx.add(msg.param_index)
        if len(params) % 100 == 0:
            log("    %d / %s" % (len(params), total))
        if total and len(params) >= total:
            break

    return params, total


def collect_telemetry(m, seconds=8):
    """Zbiera po jednej ostatniej instancji kazdego interesujacego komunikatu."""
    wanted = ["SYS_STATUS", "GPS_RAW_INT", "GPS2_RAW", "RAW_IMU", "SCALED_IMU2",
              "SCALED_IMU3", "SCALED_PRESSURE", "SCALED_PRESSURE2",
              "SCALED_PRESSURE3", "BATTERY_STATUS", "EKF_STATUS_REPORT",
              "RC_CHANNELS", "VFR_HUD", "ATTITUDE", "HEARTBEAT",
              "MOUNT_STATUS", "GIMBAL_DEVICE_ATTITUDE_STATUS",
              "POWER_STATUS", "MEMINFO", "HWSTATUS", "VIBRATION"]
    snap, texts = {}, []
    t0 = time.time()
    while time.time() - t0 < seconds:
        msg = m.recv_match(blocking=True, timeout=1)
        if msg is None:
            continue
        t = msg.get_type()
        if t == "STATUSTEXT":
            txt = msg.text
            if isinstance(txt, bytes):
                txt = txt.decode("utf-8", "replace")
            txt = txt.rstrip("\x00")
            if txt not in texts:
                texts.append(txt)
        elif t in wanted:
            snap[t] = msg.to_dict()
    return snap, texts


def decode_sensors(present, enabled, health):
    rows = []
    for bit, nazwa in SENSOR_BITS:
        if present & bit:
            rows.append({
                "czujnik": nazwa,
                "obecny": True,
                "wlaczony": bool(enabled & bit),
                "sprawny": bool(health & bit),
            })
    return rows


def main():
    args = [a for a in sys.argv[1:] if a]
    port = args[0] if args else None
    baud = int(args[1]) if len(args) > 1 else 115200

    log("=" * 72)
    log("DRON 15 - polaczenie z FC (tryb tylko-odczyt)")
    log("=" * 72)

    port, desc, listing = find_port(port)
    log("\n[1] Porty COM w systemie:")
    if not listing:
        log("    (pyserial nie widzi zadnych portow)")
    for p in listing:
        vid = ("VID:%04X PID:%04X" % (p["vid"], p["pid"])) if p["vid"] else p["hwid"]
        log("    %-8s %s   %s" % (p["device"], p["desc"], vid))

    if not port:
        log("\nBLAD: nie wykryto portu kontrolera.")
        log("Podaj recznie, np.:  python tools\\fc_connect.py COM7")
        log("Sprawdz tez, czy Mission Planner / QGroundControl NIE trzyma portu.")
        sys.exit(1)

    log("\n[2] Laczenie: %s @ %d  (%s)" % (port, baud, desc))
    try:
        m = mavutil.mavlink_connection(port, baud=baud)
    except Exception as e:
        log("    BLAD otwarcia portu: %s" % e)
        log("    Najczestsza przyczyna: port zajety przez Mission Planner/QGC.")
        sys.exit(1)

    log("    czekam na HEARTBEAT (max 20 s)...")
    hb = m.wait_heartbeat(timeout=20)
    if hb is None:
        log("    BRAK HEARTBEAT. Sprawdz baud (USB-C: dowolny; telemetria: 57600).")
        sys.exit(1)

    log("    OK  sysid=%d compid=%d  typ=%s  stan=%s" % (
        m.target_system, m.target_component,
        MAV_TYPE.get(hb.type, hb.type), MAV_STATE.get(hb.system_status, hb.system_status)))

    # --- wersja FW / plyta ---
    log("\n[3] Wersja firmware i plyty")
    ver = {}
    try:
        m.mav.command_long_send(m.target_system, m.target_component,
                                mavutil.mavlink.MAV_CMD_REQUEST_MESSAGE,
                                0, 148, 0, 0, 0, 0, 0, 0)
    except Exception:
        pass
    try:
        m.mav.autopilot_version_request_send(m.target_system, m.target_component)
    except Exception:
        pass
    av = m.recv_match(type="AUTOPILOT_VERSION", blocking=True, timeout=6)
    if av:
        fv = av.flight_sw_version
        ver = {
            "wersja_fw": "%d.%d.%d" % ((fv >> 24) & 0xFF, (fv >> 16) & 0xFF, (fv >> 8) & 0xFF),
            "board_version": av.board_version,
            "vendor_id": getattr(av, "vendor_id", None),
            "product_id": getattr(av, "product_id", None),
            "uid": getattr(av, "uid", None),
            "capabilities": av.capabilities,
        }
        log("    ArduPilot %s   board_version=%s   vendor=0x%04X product=0x%04X" % (
            ver["wersja_fw"], av.board_version,
            ver["vendor_id"] or 0, ver["product_id"] or 0))
    else:
        log("    (brak AUTOPILOT_VERSION - wersje odczytamy z parametrow)")

    # --- parametry ---
    log("\n[4] Pobieranie parametrow (to trwa ~30-90 s)")
    params, total = fetch_params(m)
    log("    pobrano %d parametrow (FC deklaruje %s)" % (len(params), total))

    # --- telemetria ---
    log("\n[5] Snapshot telemetrii (8 s)")
    for sid in range(4):
        try:
            m.mav.request_data_stream_send(m.target_system, m.target_component,
                                           sid, 4, 1)
        except Exception:
            pass
    snap, texts = collect_telemetry(m, 8)
    log("    komunikaty: %s" % (", ".join(sorted(snap.keys())) or "brak"))

    # --- interpretacja on-line ---
    log("\n[6] Stan pokladu")
    sensors = []
    if "SYS_STATUS" in snap:
        s = snap["SYS_STATUS"]
        sensors = decode_sensors(s["onboard_control_sensors_present"],
                                 s["onboard_control_sensors_enabled"],
                                 s["onboard_control_sensors_health"])
        for r in sensors:
            flag = "OK " if r["sprawny"] else "AWARIA"
            wl = "wl." if r["wlaczony"] else "wyl."
            log("    %-24s %-6s %s" % (r["czujnik"], wl, flag))
        log("    napiecie: %.2f V   prad: %.1f A   obciazenie CPU: %.1f %%" % (
            s["voltage_battery"] / 1000.0,
            (s["current_battery"] / 100.0) if s["current_battery"] > 0 else 0.0,
            s["load"] / 10.0))
    if "GPS_RAW_INT" in snap:
        g = snap["GPS_RAW_INT"]
        log("    GPS: %s, satelity=%d, HDOP=%.2f" % (
            GPS_FIX.get(g["fix_type"], g["fix_type"]), g["satellites_visible"],
            g["eph"] / 100.0 if g["eph"] < 65535 else -1))
    if "VIBRATION" in snap:
        v = snap["VIBRATION"]
        log("    wibracje X/Y/Z: %.1f / %.1f / %.1f   clip: %d/%d/%d" % (
            v["vibration_x"], v["vibration_y"], v["vibration_z"],
            v["clipping_0"], v["clipping_1"], v["clipping_2"]))
    if texts:
        log("    komunikaty FC:")
        for t in texts[:25]:
            log("      > %s" % t)

    # --- diff wobec bazy ---
    log("\n[7] Roznice wobec bazy 2026-08-11")
    base = read_parm_file(BASELINE)
    diff = {"zmienione": {}, "nowe": [], "znikniete": []}
    if base:
        for k, v in params.items():
            if k in base:
                if abs(base[k] - v) > 1e-6:
                    diff["zmienione"][k] = {"baza": base[k], "teraz": v}
            else:
                diff["nowe"].append(k)
        diff["znikniete"] = sorted(set(base) - set(params))
        log("    baza: %d param.  teraz: %d param." % (len(base), len(params)))
        log("    zmienione: %d | nowe: %d | zniknelo: %d" % (
            len(diff["zmienione"]), len(diff["nowe"]), len(diff["znikniete"])))
        for k in sorted(diff["zmienione"])[:40]:
            d = diff["zmienione"][k]
            log("      %-22s %-14s -> %s" % (k, d["baza"], d["teraz"]))
        if len(diff["zmienione"]) > 40:
            log("      ... i %d wiecej (pelna lista w raporcie)" % (len(diff["zmienione"]) - 40))
    else:
        log("    (brak pliku bazowego %s - pomijam)" % BASELINE)

    # --- zapis ---
    stamp = time.strftime("%Y%m%d_%H%M")
    outdir = os.path.join(DOK, "fc_dump_%s" % stamp)
    os.makedirs(outdir, exist_ok=True)

    parm_path = os.path.join(outdir, "params.parm")
    with open(parm_path, "w", encoding="utf-8") as f:
        for k in sorted(params):
            f.write("%s,%s\n" % (k, repr(round(params[k], 8))))

    state = {
        "czas": time.strftime("%Y-%m-%d %H:%M:%S"),
        "port": port, "baud": baud, "opis_portu": desc,
        "porty_com": listing,
        "heartbeat": {"sysid": m.target_system, "compid": m.target_component,
                      "typ": MAV_TYPE.get(hb.type, hb.type),
                      "stan": MAV_STATE.get(hb.system_status, hb.system_status),
                      "tryb_bazowy": hb.base_mode, "tryb_wlasny": hb.custom_mode},
        "wersja": ver,
        "liczba_parametrow": len(params),
        "parametry_deklarowane": total,
        "czujniki": sensors,
        "telemetria": snap,
        "komunikaty": texts,
        "diff_wobec_bazy": diff,
    }
    json_path = os.path.join(outdir, "state.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2, ensure_ascii=False, default=str)

    log("\n[8] Zapisano:")
    log("    %s" % parm_path)
    log("    %s" % json_path)
    log("\nGOTOWE. Wklej sciezke katalogu do rozmowy z Claude:")
    log("    %s" % outdir)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        log("\nprzerwane przez uzytkownika")
    except Exception as e:
        import traceback
        log("\nBLAD: %s" % e)
        traceback.print_exc()
        sys.exit(1)
