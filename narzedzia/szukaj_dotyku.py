#!/usr/bin/env python3
"""Szukanie kontrolera dotyku na magistralach I2C — po podłączeniu przewodów.

Warstwa dotykowa w okrągłym wyświetlaczu **jest**, ale jej wyprowadzenie nie było
podłączone (potwierdzone przez Toma 2026-08-29). Po dołożeniu `SDA`, `SCL`, `INT`
i `RST` to narzędzie sprawdza jednym poleceniem, czy kontroler się odzywa i czy
reaguje na palec.

    sudo python3 szukaj_dotyku.py            # przegląd + 30 s nasłuchu
    sudo python3 szukaj_dotyku.py --czas 60

Tylko odczyt. Nie zapisuje do żadnego rejestru — kontrolery dotyku bywają czułe
na przypadkowy zapis, a przy dronie na stole nie ma miejsca na niespodzianki.
"""

from __future__ import annotations

import argparse
import glob
import sys
import time

try:
    from smbus2 import SMBus, i2c_msg
except ImportError:
    print("Brak smbus2. Zainstaluj: sudo apt install python3-smbus2", file=sys.stderr)
    raise SystemExit(1)

# Adresy kontrolerów dotyku spotykanych w okrągłych modułach LCD.
ZNANE = {
    0x15: "CST816S / CST816T / CST820 — typowy dla Waveshare 1,28″ GC9A01",
    0x1A: "CST328",
    0x2E: "CHSC5816",
    0x38: "FT6206 / FT6236 / FT5x06",
    0x14: "GT911 (adres podstawowy)",
    0x5D: "GT911 (adres zapasowy)",
    0x5A: "MPR121 / CST226SE",
    0x48: "ADS1115 — to NIE dotyk, tylko przetwornik VRX",
}

# Rejestr z identyfikatorem układu, jeśli rodzina go ma.
ID_UKLADU = {0x15: 0xA7, 0x1A: 0xA7, 0x38: 0xA3}


def magistrale() -> list[int]:
    return sorted(int(s.rsplit("-", 1)[1]) for s in glob.glob("/dev/i2c-*"))


def odpowiada(bus: SMBus, adres: int) -> bool:
    """Sam odczyt jednego bajtu — bez zapisu, bez budzenia układu."""
    try:
        bus.i2c_rdwr(i2c_msg.read(adres, 1))
        return True
    except OSError:
        pass
    try:
        bus.read_byte_data(adres, 0x00)
        return True
    except OSError:
        return False


def czytaj_blok(bus: SMBus, adres: int, ile: int = 16) -> bytes | None:
    try:
        zapis = i2c_msg.write(adres, [0x00])
        odczyt = i2c_msg.read(adres, ile)
        bus.i2c_rdwr(zapis, odczyt)
        return bytes(odczyt)
    except OSError:
        return None


def przeglad() -> list[tuple[int, int]]:
    znalezione: list[tuple[int, int]] = []
    for numer in magistrale():
        try:
            with SMBus(numer) as bus:
                for adres in range(0x08, 0x78):
                    if not odpowiada(bus, adres):
                        continue
                    opis = ZNANE.get(adres, "nieznany układ")
                    znak = "◀ KANDYDAT NA DOTYK" if adres in ZNANE and adres != 0x48 else ""
                    print(f"  i2c-{numer}  0x{adres:02x}  {opis} {znak}")
                    if (rejestr := ID_UKLADU.get(adres)) is not None:
                        try:
                            print(f"            ID układu (0x{rejestr:02x}) = "
                                  f"0x{bus.read_byte_data(adres, rejestr):02x}")
                        except OSError:
                            pass
                    znalezione.append((numer, adres))
        except OSError as blad:
            print(f"  i2c-{numer}: nie da się otworzyć ({blad})")
    return znalezione


def nasluch(kandydaci: list[tuple[int, int]], czas: float) -> None:
    print(f"\nDOTYKAJ TERAZ okrągłego ekranu przez {czas:.0f} s.")
    print("Każda zmiana rejestrów zostanie wypisana.\n")
    stan: dict[tuple[int, int], bytes | None] = {}
    otwarte = {}
    for numer, adres in kandydaci:
        if numer not in otwarte:
            otwarte[numer] = SMBus(numer)
        stan[(numer, adres)] = czytaj_blok(otwarte[numer], adres)

    zmian = 0
    koniec = time.time() + czas
    try:
        while time.time() < koniec:
            for (numer, adres), poprzedni in list(stan.items()):
                teraz = czytaj_blok(otwarte[numer], adres)
                if teraz is not None and teraz != poprzedni:
                    zmian += 1
                    print(f"{time.strftime('%H:%M:%S')}  i2c-{numer} 0x{adres:02x}  "
                          f"{teraz.hex(' ')}")
                    stan[(numer, adres)] = teraz
            time.sleep(0.02)
    finally:
        for bus in otwarte.values():
            bus.close()

    print()
    if zmian:
        print(f"✅ {zmian} zmian — kontroler REAGUJE. To jest dotyk.")
        print("   Kolejny krok: odczyt współrzędnych i przełożenie na wskaźnik.")
    else:
        print("⛔ Zero zmian. Albo przewody nie dochodzą, albo to nie ten układ,")
        print("   albo kontroler śpi i potrzebuje linii INT/RST.")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--czas", type=float, default=30.0, help="ile sekund nasłuchiwać")
    a = p.parse_args()

    print("Magistrale I2C:", ", ".join(f"i2c-{n}" for n in magistrale()) or "brak")
    print("\nUrządzenia, które odpowiadają:")
    znalezione = przeglad()
    if not znalezione:
        print("  (żadnych)")
        return 1

    kandydaci = [(n, a_) for n, a_ in znalezione if a_ in ZNANE and a_ != 0x48]
    if not kandydaci:
        print("\n⚠ Żaden adres nie pasuje do znanych kontrolerów dotyku.")
        print("  Nasłuchuję wszystkiego, co odpowiada — może to układ spoza listy.")
        kandydaci = znalezione

    nasluch(kandydaci, a.czas)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
