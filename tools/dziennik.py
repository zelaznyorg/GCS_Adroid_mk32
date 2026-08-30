#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - wspolny rejestr bledow dla narzedzi.

Konwencje calego projektu: mk32app\\dok\\LOGI_I_BLEDY.md

Po co to jest. Narzedzia w tools\\ rozmawiaja z kontrolerem lotu przez COM9. Gdy cos
pojdzie nie tak - odpiety kabel, zajety port, przerwana sesja MAVLink - Python wypisuje
slad stosu, ktory znika razem z zamknietym oknem konsoli. Po powrocie z pola nie ma
czego czytac. Ten modul zapisuje to samo do pliku i podaje czlowiekowi zdanie po polsku
zamiast surowego wyjatku.

Uzycie - dwie linie na poczatku skryptu, zaraz po imports:

    from dziennik import zainstaluj
    zainstaluj("fc_read_params")

Od tego momentu:
  - kazdy nieprzechwycony wyjatek trafia do dok\\logi\\narzedzia.log ze stosem,
  - na konsoli zostaje krotki komunikat zamiast sciany tekstu,
  - Ctrl+C konczy cicho, bo przerwanie z klawiatury nie jest awaria.

ZASADA: nic w tym module nie moze wywrocic narzedzia. Log, ktory psuje odczyt
parametrow przed lotem, jest gorszy niz brak loga - stad try/except wszedzie.
"""

import atexit
import datetime
import os
import sys
import traceback

_KATALOG = None
_PLIK = None
_NAZWA = "narzedzie"
_ZAINSTALOWANY = False

# Ile trzymamy, zanim zaczniemy obcinac od poczatku. Plik ma byc do czytania,
# nie do archiwizacji - historia zmian siedzi w dok\fc_write_*.log.
_MAX_BAJTOW = 2 * 1024 * 1024


def _katalog_logow():
    """dok\\logi obok katalogu projektu; gdy sie nie da - katalog biezacy."""
    global _KATALOG
    if _KATALOG:
        return _KATALOG
    try:
        korzen = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        kandydat = os.path.join(korzen, "dok", "logi")
        os.makedirs(kandydat, exist_ok=True)
        _KATALOG = kandydat
    except Exception:
        _KATALOG = os.getcwd()
    return _KATALOG


def _sciezka():
    global _PLIK
    if _PLIK is None:
        _PLIK = os.path.join(_katalog_logow(), "narzedzia.log")
    return _PLIK


def _znacznik():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _przytnij():
    """Obcina plik od poczatku, gdy urosl ponad limit. Nie rotujemy - jeden plik wystarcza."""
    try:
        p = _sciezka()
        if os.path.exists(p) and os.path.getsize(p) > _MAX_BAJTOW:
            with open(p, "rb") as f:
                f.seek(-_MAX_BAJTOW // 2, os.SEEK_END)
                ogon = f.read()
            with open(p, "wb") as f:
                f.write(b"... (poczatek pliku obciety) ...\n")
                f.write(ogon)
    except Exception:
        pass


def pisz(poziom, wiadomosc, stos=None):
    """Jeden wpis. Nigdy nie rzuca."""
    try:
        _przytnij()
        linia = "%s %-6s [%-16s] %s\n" % (_znacznik(), poziom, _NAZWA, wiadomosc)
        if stos:
            linia += "".join("    " + w for w in stos.splitlines(True)) + "\n"
        with open(_sciezka(), "a", encoding="utf-8", errors="replace") as f:
            f.write(linia)
    except Exception:
        pass


def info(wiadomosc):
    pisz("INFO", wiadomosc)


def ostrzezenie(wiadomosc):
    pisz("OSTRZ", wiadomosc)


def blad(wiadomosc, wyjatek=None):
    pisz("BLAD", wiadomosc, traceback.format_exc() if wyjatek else None)


def _po_polsku(typ, wartosc):
    """
    Najczestsze awarie tych narzedzi maja rozpoznawalne przyczyny. Zdanie, ktore mowi
    CO ZROBIC, jest warte wiecej niz nazwa klasy wyjatku.
    """
    tekst = str(wartosc)
    nazwa = typ.__name__

    if "PermissionError" in nazwa or "Access is denied" in tekst:
        return "Port zajety albo brak uprawnien. Zamknij Mission Planner i sprobuj ponownie."
    if "SerialException" in nazwa or "could not open port" in tekst.lower():
        return "Nie moge otworzyc portu. Sprawdz, czy FC jest podpiety i czy to na pewno COM9."
    if nazwa == "FileNotFoundError":
        return "Nie ma takiego pliku: %s" % tekst
    if nazwa == "ModuleNotFoundError":
        return "Brakuje biblioteki: %s. Zainstaluj ja przez pip." % tekst
    if nazwa == "TimeoutError" or "timed out" in tekst.lower():
        return "Kontroler nie odpowiedzial w czasie. Sprobuj ponownie albo zrestartuj FC."
    return "%s: %s" % (nazwa, tekst)


def _pulapka(typ, wartosc, slad):
    # Ctrl+C to decyzja czlowieka, nie awaria - konczymy cicho.
    if issubclass(typ, KeyboardInterrupt):
        print("\nPrzerwane.", file=sys.stderr)
        sys.exit(130)

    opis = _po_polsku(typ, wartosc)
    stos = "".join(traceback.format_exception(typ, wartosc, slad))
    pisz("AWARIA", opis, stos)

    print("", file=sys.stderr)
    print("BLAD: %s" % opis, file=sys.stderr)
    print("Szczegoly zapisane w: %s" % _sciezka(), file=sys.stderr)
    sys.exit(1)


def zainstaluj(nazwa, opisz_start=True):
    """
    Wywolac raz, na poczatku skryptu. `nazwa` trafia do kazdego wpisu, zeby po tygodniu
    dalo sie odroznic, ktore narzedzie zostawilo slad.
    """
    global _NAZWA, _ZAINSTALOWANY
    _NAZWA = nazwa
    if _ZAINSTALOWANY:
        return
    _ZAINSTALOWANY = True
    try:
        sys.excepthook = _pulapka
        if opisz_start:
            info("start: %s" % " ".join(sys.argv))
        atexit.register(lambda: info("koniec"))
    except Exception:
        # Narzedzie ma dzialac takze wtedy, gdy rejestr nie wstal.
        pass


def sciezka_logu():
    return _sciezka()
