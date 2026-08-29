#!/usr/bin/env python3
"""Podgląd zdarzeń pokrętła — sprawdzenie warstwy wejścia bez żadnego UI.

Uruchamiać na malinie, po wgraniu łatki do panelu:

    python3 sluchaj_pokretla.py

Potem na okrągłym wyświetlaczu przekręcić na stronę **PULPIT GCS** i kliknąć.
Od tej chwili każdy obrót i klik ma się tu wypisywać. Przytrzymanie pokrętła
ponad pół sekundy oddaje je z powrotem panelowi.

To narzędzie tylko czyta i wypisuje. Niczego nie uruchamia i nic nie zmienia.
"""

from __future__ import annotations

import logging
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pulpit"))

from gcs_pulpit.wejscie import PANEL, PULPIT, Pokretlo  # noqa: E402

zaznaczenie = 0
start = time.monotonic()


def chwila() -> str:
    return f"{time.monotonic() - start:7.2f}s"


def na_obrot(kierunek: int) -> None:
    global zaznaczenie
    zaznaczenie += kierunek
    strzalka = "w prawo" if kierunek > 0 else "w lewo "
    print(f"{chwila()}  OBRÓT {strzalka}   pozycja {zaznaczenie:+d}")


def na_klik() -> None:
    print(f"{chwila()}  KLIK          (zatwierdzenie pozycji {zaznaczenie:+d})")


def na_przytrzymanie() -> None:
    print(f"{chwila()}  PRZYTRZYMANIE — oddaję pokrętło panelowi")
    pokretlo.oddaj_panelowi()


def na_ognisko(gdzie: str) -> None:
    if gdzie == PULPIT:
        print(f"{chwila()}  >>> POKRĘTŁO JEST MOJE — kręć i klikaj")
    else:
        print(f"{chwila()}  <<< pokrętło wróciło do panelu")


def na_lacze(zywe: bool) -> None:
    if zywe:
        print(f"{chwila()}  łącze z mostem nawiązane")
    else:
        print(f"{chwila()}  łącze z mostem zerwane — ponawiam")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    gniazdo = os.getenv("GCS_GNIAZDO_POKRETLA", "/run/gcs/pokretlo.sock")
    print(f"Słucham mostu: {gniazdo}")
    print("Na okrągłym ekranie: strona PULPIT GCS, potem klik.  Ctrl+C kończy.\n")
    pokretlo = Pokretlo(
        na_obrot=na_obrot,
        na_klik=na_klik,
        na_przytrzymanie=na_przytrzymanie,
        na_ognisko=na_ognisko,
        na_lacze=na_lacze,
    )
    pokretlo.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nKoniec.")
        pokretlo.stop()
