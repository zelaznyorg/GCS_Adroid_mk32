#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - dekoder DEVID ArduPilota (INS_*_ID, BARO*_DEVID, COMPASS_DEV_ID*).

    python tools\\decode_devid.py                       # dekoduje plik bazowy
    python tools\\decode_devid.py dok\\fc_dump_X\\params.parm
    python tools\\decode_devid.py 3408162               # pojedyncza liczba

Format DEVID wg libraries/AP_HAL/Device.h (union DeviceId / DeviceStructure):
    bus_type : 3 bity   (bity 0-2)
    bus      : 5 bitow  (bity 3-7)
    address  : 8 bitow  (bity 8-15)
    devtype  : 8 bitow  (bity 16-23)

Tablice devtype pobrane ze zrodel ArduPilot (master):
    libraries/AP_InertialSensor/AP_InertialSensor_Backend.h
    libraries/AP_Baro/AP_Baro_Backend.h
    libraries/AP_Compass/AP_Compass_Backend.h
"""

import sys
import os

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("decode_devid")

BUS_TYPE = {0: "UNKNOWN", 1: "I2C", 2: "SPI", 3: "DroneCAN", 4: "SITL",
            5: "MSP", 6: "SERIAL", 7: "WSPI"}

INS_DEVTYPE = {
    0x27: "ICM20789", 0x28: "ICM20689", 0x29: "BMI055", 0x2B: "BMI088",
    0x2C: "ICM20948", 0x2D: "ICM20648", 0x2E: "ICM20649", 0x2F: "ICM20602",
    0x30: "ICM20601", 0x31: "ADIS1647X", 0x33: "ICM40609", 0x34: "ICM42688",
    0x35: "ICM42605", 0x36: "ICM40605", 0x37: "IIM42652", 0x39: "BMI085",
    0x3A: "ICM42670", 0x3B: "ICM45686", 0x3C: "SCHA63T", 0x3D: "IIM42653",
    0x3E: "LSM6DSV16X", 0x3F: "ASM330", 0x40: "ADIS16607",
    0x41: "ZEROONE_FPGA_SCH16T", 0x42: "LSM6DSV32X", 0x43: "LSM6DSK320X",
}

BARO_DEVTYPE = {
    0x01: "SITL", 0x02: "BMP085", 0x03: "BMP280", 0x04: "BMP388",
    0x05: "DPS280", 0x06: "DPS310", 0x07: "FBM320", 0x08: "ICM20789",
    0x09: "KELLERLD", 0x0A: "LPS2XH", 0x0B: "MS5611", 0x0C: "SPL06",
    0x0D: "DroneCAN", 0x0E: "MSP", 0x0F: "ICP101XX", 0x10: "ICP201XX",
    0x11: "MS5607", 0x12: "MS5837_30BA", 0x13: "MS5637", 0x14: "BMP390",
    0x15: "BMP581", 0x16: "SPA06", 0x17: "AUAV", 0x18: "MS5837_02BA",
}

COMPASS_DEVTYPE = {
    0x01: "HMC5883_OLD", 0x07: "HMC5883", 0x02: "LSM303D", 0x04: "AK8963",
    0x05: "BMM150", 0x06: "LSM9DS1", 0x08: "LIS3MDL", 0x09: "AK09916",
    0x0A: "IST8310", 0x0B: "ICM20948", 0x0C: "MMC3416", 0x0E: "QMC5883L",
    0x0F: "MAG3110", 0x10: "SITL", 0x11: "AK09918", 0x12: "AK09915",
    0x13: "QMC5883P", 0x14: "BMM350", 0x15: "IST8308",
}


def decode(devid, tabela=None):
    devid = int(devid)
    if devid == 0:
        return {"devid": 0, "opis": "brak / nieprzypisany"}
    bus_type = devid & 0x07
    bus = (devid >> 3) & 0x1F
    address = (devid >> 8) & 0xFF
    devtype = (devid >> 16) & 0xFF
    nazwa = (tabela or {}).get(devtype)
    return {
        "devid": devid,
        "hex": "0x%06X" % devid,
        "bus_type": BUS_TYPE.get(bus_type, bus_type),
        "bus": bus,
        "address": "0x%02X" % address,
        "devtype": "0x%02X" % devtype,
        "czujnik": nazwa or "NIEZNANY (0x%02X)" % devtype,
    }


def tabela_dla(nazwa_param):
    n = nazwa_param.upper()
    if n.startswith("INS_"):
        return INS_DEVTYPE
    if n.startswith("BARO"):
        return BARO_DEVTYPE
    if n.startswith("COMPASS"):
        return COMPASS_DEVTYPE
    return None


def read_parm(path):
    out = {}
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line[0] in "#/":
                continue
            for sep in ("\t", ",", " "):
                if sep in line:
                    name, _, val = line.partition(sep)
                    try:
                        out[name.strip().upper()] = float(val.strip().split()[0])
                    except (ValueError, IndexError):
                        pass
                    break
    return out


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else None

    if arg and arg.isdigit():
        for etykieta, tab in (("IMU", INS_DEVTYPE), ("BARO", BARO_DEVTYPE),
                              ("KOMPAS", COMPASS_DEVTYPE)):
            d = decode(arg, tab)
            print("%-7s %s" % (etykieta + ":", d))
        return

    here = os.path.dirname(os.path.abspath(__file__))
    proj = os.path.dirname(here) if os.path.basename(here).lower() == "tools" else here
    path = arg or os.path.join(proj, "dok", "ardupilot_params_20260811_gimbal_ok.parm")

    if not os.path.exists(path):
        print("Brak pliku: %s" % path)
        sys.exit(1)

    p = read_parm(path)
    print("Plik: %s   (%d parametrow)\n" % (path, len(p)))

    interesujace = [k for k in sorted(p)
                    if k.endswith("_ID") and k.startswith("INS_")
                    or k.endswith("_DEVID") or k.startswith("COMPASS_DEV_ID")]

    print("%-18s %-11s %-9s %-5s %-7s %-8s %s" % (
        "PARAMETR", "DEVID", "hex", "bus", "typ", "addr", "CZUJNIK"))
    print("-" * 84)
    for k in interesujace:
        d = decode(p[k], tabela_dla(k))
        if d.get("devid") == 0:
            print("%-18s %-11s %s" % (k, "0", "-- brak --"))
        else:
            print("%-18s %-11d %-9s %-5s %-7s %-8s %s" % (
                k, d["devid"], d["hex"], d["bus"], d["bus_type"],
                d["address"], d["czujnik"]))


if __name__ == "__main__":
    main()
