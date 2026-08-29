#!/usr/bin/env python3
"""Szuka nazw uzytych, ale nigdzie nie zdefiniowanych.

⛔ Powod powstania, 2026-08-29: obsluga dlugiego przytrzymania pokretla
uzywala stalej `PANEL`, ktorej latka nie zaimportowala. Skladnia byla
poprawna, modul wstawal, panel dzialal — a `NameError` wychodzil dopiero
w chwili wykonania gestu. Zabijalo to proces bedacy JEDYNYM wlascicielem
GPIO, czyli cale pokretlo. Najgorsze, ze byl to gest ratunkowy: rzecz,
ktora miala odratowac stanowisko, kladla je na amen.

Ta klasa bledu dotyczy kazdej rzadko wykonywanej galezi — menu sieci,
przelaczenia nagrywania, wyjscia awaryjnego. Test przechodzi caly modul,
wiec widzi ja bez uruchamiania i bez klikania.

    python3 narzedzia/kontrola_nazw.py plik.py [plik.py ...]

Zwraca 1, gdy cokolwiek jest nierozwiazane — nadaje sie do instalatora.
"""

from __future__ import annotations

import ast
import builtins
import sys
from pathlib import Path


def nieznane_nazwy(sciezka: str) -> dict[str, int]:
    drzewo = ast.parse(Path(sciezka).read_text(encoding="utf-8"), sciezka)

    # Zbieramy wszystko, co gdziekolwiek w module powstaje. Celowo bez analizy
    # zasiegow: chcemy wylapac nazwy nieznane NIGDZIE, a nie uzyte poza swoim
    # zasiegiem. Falszywy alarm bylby tu gorszy niz przeoczenie.
    znane = set(dir(builtins))
    znane.update(
        {
            "__annotations__",
            "__cached__",
            "__doc__",
            "__file__",
            "__loader__",
            "__name__",
            "__package__",
            "__spec__",
        }
    )
    for w in ast.walk(drzewo):
        if isinstance(w, (ast.Import, ast.ImportFrom)):
            for a in w.names:
                znane.add((a.asname or a.name).split(".")[0])
        elif isinstance(w, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            znane.add(w.name)
        elif isinstance(w, ast.Name) and isinstance(w.ctx, ast.Store):
            znane.add(w.id)
        elif isinstance(w, ast.arg):
            znane.add(w.arg)
        elif isinstance(w, ast.ExceptHandler) and w.name:
            znane.add(w.name)
        elif isinstance(w, (ast.Global, ast.Nonlocal)):
            znane.update(w.names)

    braki: dict[str, int] = {}
    for w in ast.walk(drzewo):
        if isinstance(w, ast.Name) and isinstance(w.ctx, ast.Load) and w.id not in znane:
            braki.setdefault(w.id, w.lineno)
    return braki


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    zle = 0
    for sciezka in argv[1:]:
        try:
            braki = nieznane_nazwy(sciezka)
        except SyntaxError as blad:
            print(f"  BLAD  {sciezka}:{blad.lineno}  blad skladni: {blad.msg}")
            zle = 1
            continue
        if braki:
            zle = 1
            for nazwa, linia in sorted(braki.items(), key=lambda x: x[1]):
                print(f"  BLAD  {sciezka}:{linia}  nazwa nieznana: {nazwa}")
        else:
            print(f"  ok    {sciezka}")
    return zle


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
