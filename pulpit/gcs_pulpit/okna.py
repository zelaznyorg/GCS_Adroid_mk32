"""Rozciąganie okien aplikacji zewnętrznych na cały ekran.

⛔ Powód, 2026-08-29: kafelki miały pole `"pelny-ekran": true`, ale **kod nigdy
go nie używał**. Firefox wstawał w małym oknie na środku, a dookoła widać było
wszystko, co leży niżej. Na stanowisku naziemnym aplikacja ma zająć ekran.

Robi to `wlrctl toplevel fullscreen` (protokół foreign-toplevel-management,
labwc go wystawia — sprawdzone). Okno nie pojawia się natychmiast po starcie
procesu, więc pilnujemy go przez kilkanaście sekund i działamy, gdy się zjawi.

✅ Zmierzone 2026-08-29: polecenie **ustawia** stan, nie przełącza — drugie
wywołanie na oknie już rozciągniętym niczego nie zepsuło. Dzięki temu wskazówkę
można spokojnie dać także aplikacji, która sama wchodzi na pełny ekran
(DRON 15 startuje z `--start-fullscreen`). Wysyłamy je i tak raz, bo drugi raz
nie ma co poprawiać.
"""

from __future__ import annotations

import logging
import subprocess

from gi.repository import GLib

log = logging.getLogger("gcs.pulpit.okna")

CZEKAJ_S = 1
PROBY = 20  # ok. 20 s — Waydroid i przeglądarki potrafią wstawać wolno


def lista() -> dict[str, str]:
    """Zwraca {app_id: tytuł} widocznych okien."""
    try:
        wynik = subprocess.run(
            ["wlrctl", "toplevel", "list"],
            capture_output=True, text=True, timeout=5, check=False,
        )
    except (OSError, subprocess.SubprocessError) as blad:
        log.warning("wlrctl nie odpowiada: %s", blad)
        return {}
    okna: dict[str, str] = {}
    for linia in wynik.stdout.splitlines():
        app_id, _, tytul = linia.partition(":")
        if app_id.strip():
            okna[app_id.strip()] = tytul.strip()
    return okna


def _dopasuj(wskazowka: str, okna: dict[str, str]) -> str | None:
    """Najpierw trafienie dokładne, potem zawieranie.

    Zawieranie jest tu potrzebne, nie wygodne: Waydroid uruchamia się
    poleceniem `waydroid`, a okno melduje się jako `waydroid.com.atakmap…`.
    """
    if wskazowka in okna:
        return wskazowka
    for app_id in okna:
        if wskazowka in app_id.lower() or app_id.lower() in wskazowka:
            return app_id
    return None


def rozciagnij(wskazowka: str, nazwa: str = "") -> None:
    """Rozciąga okno na pełny ekran, gdy tylko się pojawi. Nie blokuje."""
    if not wskazowka:
        return
    licznik = {"proby": 0}

    def sprawdz() -> bool:
        licznik["proby"] += 1
        app_id = _dopasuj(wskazowka.lower(), lista())
        if app_id is None:
            if licznik["proby"] >= PROBY:
                log.info("%s: okno „%s” się nie pojawiło — zostawiam", nazwa, wskazowka)
                return False
            return True  # próbujemy dalej
        try:
            subprocess.run(
                ["wlrctl", "toplevel", "fullscreen", f"app_id:{app_id}"],
                capture_output=True, timeout=5, check=False,
            )
            log.info("%s: okno %s na pełny ekran", nazwa, app_id)
        except (OSError, subprocess.SubprocessError) as blad:
            log.warning("%s: nie udało się rozciągnąć okna — %s", nazwa, blad)
        return False  # raz wystarczy — stan jest ustawiany, nie przełączany

    GLib.timeout_add_seconds(CZEKAJ_S, sprawdz)
