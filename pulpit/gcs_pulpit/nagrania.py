"""Odtwarzacz nagrań ze stacji — archiwum obrazu z głowicy ZR30.

⛔ **To NIE jest odtwarzacz nagrań z rejestratora CVBS.** Tamte mają już własny,
wbudowany w aplikację HUD (`lcd_recordings.py` + `cvbs/app/playback.py`), sterowany
z okrągłego panelu — i tego nie dublujemy.

Bez odtwarzacza zostawało **archiwum stacji podglądu**:
`/var/lib/dron15/archiwum/wideo/zr30`, segmenty `.mp4` z głowicy. Nagrywa je
MediaMTX, a obejrzeć ich nie dało się niczym na miejscu.

> ### Dlaczego `mpv`, a nie VLC czy `ffplay`
>
> Odtwarzacz musi dać się obsłużyć **pokrętłem**, a więc sterować z zewnątrz.
> `mpv` wystawia gniazdo sterujące (`--input-ipc-server`) i przyjmuje polecenia
> w JSON — pauza, przewijanie, zamknięcie i **odczyt pozycji**. VLC ma coś podobnego,
> ale bardziej po omacku, a `ffplay` nie ma nic.
>
> ⚠ Nagrania to **H.264**, a RPi 5 nie ma dla niego dekodera sprzętowego
> (poz. R4) — dekodowanie idzie programowo. Przy 720p to jest do udźwignięcia,
> ale nie jest darmowe.

Czasy trwania czyta `ffprobe` **w tle**, po jednym pliku: przy 160 nagraniach
liczenie ich z góry zatrzymałoby ekran na kilka sekund.
"""

from __future__ import annotations

import json
import logging
import os
import socket
import subprocess
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Gtk4LayerShell", "1.0")
from gi.repository import GLib, Gtk  # noqa: E402
from gi.repository import Gtk4LayerShell as Warstwa  # noqa: E402

from .widoki import przewin_do  # noqa: E402

log = logging.getLogger("gcs.pulpit.nagrania")

GNIAZDO_MPV = os.getenv("GCS_GNIAZDO_MPV", "/run/user/1000/gcs-mpv.sock")

# Katalogi z nagraniami stacji. Lista, bo archiwum może z czasem urosnąć
# o kolejne źródła obrazu (slot kamery 2 w sieci pokładowej).
def _zrodla_nagran() -> list[tuple[str, Path, tuple[str, ...]]]:
    """Katalog na źródło — nagrywarka zakłada je sama, więc lista rośnie z użyciem."""
    lista: list[tuple[str, Path, tuple[str, ...]]] = []
    korzen = Path("/var/lib/gcs/nagrania")
    if korzen.is_dir():
        for katalog in sorted(p for p in korzen.iterdir() if p.is_dir()):
            lista.append((f"NAGRANE: {katalog.name}", katalog, ("*.mkv",)))
    lista.append(
        ("ARCHIWUM STACJI — ZR30",
         Path("/var/lib/dron15/archiwum/wideo/zr30"), ("*.mp4", "*.mkv"))
    )
    return lista


ZRODLA: list[tuple[str, Path, tuple[str, ...]]] = _zrodla_nagran()

STYL = b"""
.nagr-naglowek { font-size: 15pt; color: #97a29a; padding: 4px 0 12px 0; }

button.nagr {
    background-image: none;
    background-color: #161d19;
    border: 3px solid #232e28;
    border-radius: 10px;
    box-shadow: none;
    outline: none;
    padding: 12px 20px;
    min-height: 54px;
    font-size: 15pt; font-weight: 700; color: #f2f2e8;
}
button.nagr.nagr-wybrany { border-color: #ffb000; background-color: #23302a; }
button.nagr-wstecz  { background-color: #1b2420; color: #b9c3bb; font-size: 14pt; }
button.nagr-zrodlo  { background-color: #1a1c2a; color: #b8c6ff; }
button.nagr-pusto   { color: #8f9a92; }

window.odtwarzacz { background-color: #0e1411; }
.odtw-ramka  { padding: 10px 14px 12px 14px; }
.odtw-tytul  { font-size: 15pt; font-weight: 800; color: #f2f2e8; }
.odtw-czas   { font-size: 20pt; font-weight: 800; color: #ffb000; margin-bottom: 8px; }

button.odtw {
    background-image: none; background-color: #161d19;
    border: 3px solid #202a25; border-radius: 8px; box-shadow: none; outline: none;
    min-width: 120px; min-height: 60px;
    font-size: 15pt; font-weight: 700; color: #f2f2e8; padding: 0 10px;
}
button.odtw.odtw-wybrany { border-color: #ffb000; background-color: #2a3830; }
button.odtw-stop { background-color: #2a1512; color: #ffb4a0; }
button.odtw-stop.odtw-wybrany { border-color: #ff8a70; background-color: #391b16; }
"""


@dataclass
class Nagranie:
    sciezka: Path
    rozmiar: int
    czas_pliku: float
    dlugosc: float | None = None


@dataclass
class Pozycja:
    etykieta: str
    akcja: Callable[[], None]
    styl: str = ""


def _ludzki_rozmiar(bajty: int) -> str:
    for jednostka, dzielnik in (("GB", 1 << 30), ("MB", 1 << 20), ("kB", 1 << 10)):
        if bajty >= dzielnik:
            return f"{bajty / dzielnik:.0f} {jednostka}"
    return f"{bajty} B"


def _ludzki_czas(sekundy: float | None) -> str:
    if sekundy is None:
        return "…"
    calk = int(sekundy)
    if calk >= 3600:
        return f"{calk // 3600}:{(calk % 3600) // 60:02d}:{calk % 60:02d}"
    return f"{calk // 60}:{calk % 60:02d}"


def dlugosc_pliku(sciezka: Path) -> float | None:
    try:
        wynik = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(sciezka)],
            capture_output=True, text=True, timeout=15,
        )
        return float(wynik.stdout.strip())
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


# ---- sterowanie mpv --------------------------------------------------------


def _mpv(polecenie: list) -> dict | None:
    """Jedno polecenie do mpv przez gniazdo sterujące. None, gdy nie odpowiada."""
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as gniazdo:
            gniazdo.settimeout(2.0)
            gniazdo.connect(GNIAZDO_MPV)
            gniazdo.sendall((json.dumps({"command": polecenie}) + "\n").encode("utf-8"))
            odpowiedz = gniazdo.makefile("rb").readline()
        return json.loads(odpowiedz.decode("utf-8")) if odpowiedz else None
    except (OSError, ValueError):
        return None


class Odtwarzacz(Gtk.Window):
    """Pasek sterowania nad odtwarzanym obrazem — nakładka bez ogniska."""

    def __init__(self, *, tytul: str, na_koniec: Callable[[], None]) -> None:
        super().__init__()
        self.add_css_class("odtwarzacz")
        self._na_koniec = na_koniec
        self._pozycje: list[Pozycja] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0

        self._jako_nakladka()
        self._zbuduj(tytul)
        GLib.timeout_add(500, self._odswiez_czas)

    def _jako_nakladka(self) -> None:
        Warstwa.init_for_window(self)
        Warstwa.set_layer(self, Warstwa.Layer.OVERLAY)
        for krawedz in (Warstwa.Edge.BOTTOM, Warstwa.Edge.LEFT, Warstwa.Edge.RIGHT):
            Warstwa.set_anchor(self, krawedz, True)
        Warstwa.set_keyboard_mode(self, Warstwa.KeyboardMode.NONE)
        Warstwa.auto_exclusive_zone_enable(self)

    def _zbuduj(self, tytul: str) -> None:
        ramka = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        ramka.add_css_class("odtw-ramka")
        self.set_child(ramka)

        etykieta = Gtk.Label(label=tytul, xalign=0)
        etykieta.add_css_class("odtw-tytul")
        etykieta.set_ellipsize(3)
        ramka.append(etykieta)

        self._czas = Gtk.Label(label="…", xalign=0)
        self._czas.add_css_class("odtw-czas")
        ramka.append(self._czas)

        siatka = Gtk.Grid(row_spacing=8, column_spacing=8)
        siatka.set_halign(Gtk.Align.CENTER)
        ramka.append(siatka)

        self._pozycje = [
            Pozycja("⏯ PAUZA", lambda: _mpv(["cycle", "pause"])),
            Pozycja("⏪ 10 s", lambda: _mpv(["seek", -10, "relative"])),
            Pozycja("⏩ 10 s", lambda: _mpv(["seek", 10, "relative"])),
            Pozycja("⏮ 1 min", lambda: _mpv(["seek", -60, "relative"])),
            Pozycja("⏭ 1 min", lambda: _mpv(["seek", 60, "relative"])),
            Pozycja("✕ ZAMKNIJ", self.zakoncz, "odtw-stop"),
        ]
        for indeks, pozycja in enumerate(self._pozycje):
            przycisk = Gtk.Button(label=pozycja.etykieta)
            przycisk.add_css_class("odtw")
            przycisk.set_can_focus(False)
            przycisk.set_focus_on_click(False)
            if pozycja.styl:
                przycisk.add_css_class(pozycja.styl)
            przycisk.connect("clicked", lambda *_, i=indeks: self.wykonaj(i))
            siatka.attach(przycisk, indeks, 0, 1, 1)
            self._widgety.append(przycisk)
        self._zaznacz()

    def _zaznacz(self) -> None:
        for i, widget in enumerate(self._widgety):
            if i == self._wybrany:
                widget.add_css_class("odtw-wybrany")
            else:
                widget.remove_css_class("odtw-wybrany")

    def _odswiez_czas(self) -> bool:
        pozycja = _mpv(["get_property", "time-pos"])
        calosc = _mpv(["get_property", "duration"])
        if pozycja is None:
            # mpv zamknięte z zewnątrz (np. krzyżykiem) — sprzątamy po sobie.
            self.zakoncz()
            return False
        teraz = pozycja.get("data")
        koniec = (calosc or {}).get("data")
        self._czas.set_text(f"{_ludzki_czas(teraz)} / {_ludzki_czas(koniec)}")
        return True

    # ---- pokrętło --------------------------------------------------------

    def obrot(self, kierunek: int) -> None:
        self._wybrany = (self._wybrany + kierunek) % len(self._pozycje)
        self._zaznacz()

    def klik(self) -> None:
        self.wykonaj(self._wybrany)

    def przytrzymanie(self) -> None:
        self.zakoncz()

    def wykonaj(self, indeks: int) -> None:
        if 0 <= indeks < len(self._pozycje):
            self._wybrany = indeks
            self._zaznacz()
            self._pozycje[indeks].akcja()

    def zakoncz(self) -> None:
        _mpv(["quit"])
        self._na_koniec()


class Nagrania(Gtk.Box):
    """Lista nagrań. Sterowanie: obrót, klik, przytrzymanie."""

    def __init__(
        self,
        *,
        na_wstecz: Callable[[], None],
        na_komunikat: Callable[[str], None],
        na_odtwarzanie: Callable[[str], None],
    ) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.set_margin_top(20)
        self.set_margin_bottom(20)
        self.set_margin_start(40)
        self.set_margin_end(40)

        self._na_wstecz = na_wstecz
        self._na_komunikat = na_komunikat
        self._na_odtwarzanie = na_odtwarzanie

        self._zrodlo = 0
        self._nagrania: list[Nagranie] = []
        self._pozycje: list[Pozycja] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0
        self._dlugosci: dict[Path, float] = {}

        self._naglowek = Gtk.Label(label="", xalign=0)
        self._naglowek.add_css_class("nagr-naglowek")
        self._naglowek.set_wrap(True)
        self.append(self._naglowek)

        self._lista = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self._przewijak = Gtk.ScrolledWindow()
        self._przewijak.set_vexpand(True)
        self._przewijak.set_child(self._lista)
        self.append(self._przewijak)

        self.odswiez()

    # ---- lista -----------------------------------------------------------

    def odswiez(self) -> None:
        global ZRODLA
        ZRODLA = _zrodla_nagran()
        self._zrodlo = min(self._zrodlo, len(ZRODLA) - 1)
        nazwa, katalog, wzorce = ZRODLA[self._zrodlo]
        znalezione: list[Nagranie] = []
        if katalog.is_dir():
            for wzorzec in wzorce:
                for sciezka in katalog.glob(wzorzec):
                    # Plik zaczynający się kropką to nagranie w toku — pomijamy.
                    if sciezka.name.startswith("."):
                        continue
                    try:
                        stan = sciezka.stat()
                    except OSError:
                        continue
                    znalezione.append(
                        Nagranie(
                            sciezka=sciezka,
                            rozmiar=stan.st_size,
                            czas_pliku=stan.st_mtime,
                            dlugosc=self._dlugosci.get(sciezka),
                        )
                    )
        znalezione.sort(key=lambda n: n.czas_pliku, reverse=True)
        self._nagrania = znalezione

        pozycje = [
            Pozycja("◀ WSTECZ  (albo przytrzymaj pokrętło)", self._na_wstecz, "nagr-wstecz")
        ]
        if len(ZRODLA) > 1:
            pozycje.append(
                Pozycja(f"ŹRÓDŁO: {nazwa}  ▸ zmień", self._zmien_zrodlo, "nagr-zrodlo")
            )
        if not znalezione:
            gdzie = katalog if katalog.is_dir() else f"{katalog} — katalog nie istnieje"
            pozycje.append(Pozycja(f"(brak nagrań w {gdzie})", lambda: None, "nagr-pusto"))
            pozycje.append(
                Pozycja(
                    "Nagrania z rejestratora CVBS ogląda się w aplikacji HUD",
                    lambda: None,
                    "nagr-pusto",
                )
            )
        for nagranie in znalezione:
            pozycje.append(
                Pozycja(self._opis(nagranie), lambda n=nagranie: self._odtworz(n))
            )
        self._pozycje = pozycje
        self._wybrany = min(self._wybrany, len(pozycje) - 1)
        self._przemaluj()
        self._policz_dlugosci()

    @staticmethod
    def _opis(nagranie: Nagranie) -> str:
        kiedy = time.strftime("%Y-%m-%d %H:%M", time.localtime(nagranie.czas_pliku))
        return (
            f"{kiedy}   ·   {_ludzki_czas(nagranie.dlugosc)}"
            f"   ·   {_ludzki_rozmiar(nagranie.rozmiar)}"
        )

    def _policz_dlugosci(self) -> None:
        """W tle — 160 wywołań `ffprobe` z góry zatrzymałoby ekran na kilka sekund."""
        brakujace = [n.sciezka for n in self._nagrania if n.dlugosc is None]
        if not brakujace:
            return

        def robota() -> None:
            for sciezka in brakujace:
                dlugosc = dlugosc_pliku(sciezka)
                if dlugosc is not None:
                    self._dlugosci[sciezka] = dlugosc
            GLib.idle_add(self._uzupelnij_dlugosci)

        threading.Thread(target=robota, name="gcs-dlugosci", daemon=True).start()

    def _uzupelnij_dlugosci(self) -> bool:
        for nagranie in self._nagrania:
            nagranie.dlugosc = self._dlugosci.get(nagranie.sciezka, nagranie.dlugosc)
        przesuniecie = len(self._pozycje) - len(self._nagrania)
        for indeks, nagranie in enumerate(self._nagrania):
            miejsce = przesuniecie + indeks
            if 0 <= miejsce < len(self._widgety):
                self._widgety[miejsce].set_label(self._opis(nagranie))
        self._opisz_naglowek()
        return False

    def _przemaluj(self) -> None:
        while (dziecko := self._lista.get_first_child()) is not None:
            self._lista.remove(dziecko)
        self._widgety = []
        for indeks, pozycja in enumerate(self._pozycje):
            przycisk = Gtk.Button(label=pozycja.etykieta)
            przycisk.add_css_class("nagr")
            przycisk.set_can_focus(False)
            przycisk.set_focus_on_click(False)
            for klasa in pozycja.styl.split():
                przycisk.add_css_class(klasa)
            przycisk.connect("clicked", lambda *_, i=indeks: self.wykonaj(i))
            self._lista.append(przycisk)
            self._widgety.append(przycisk)
        self._zaznacz()

    def _zaznacz(self) -> None:
        for i, widget in enumerate(self._widgety):
            if i == self._wybrany:
                widget.add_css_class("nagr-wybrany")
            else:
                widget.remove_css_class("nagr-wybrany")
        if 0 <= self._wybrany < len(self._widgety):
            przewin_do(self._przewijak, self._lista, self._widgety[self._wybrany])
        self._opisz_naglowek()

    def _opisz_naglowek(self) -> None:
        nazwa, _, _ = ZRODLA[self._zrodlo]
        laczna = sum(n.dlugosc or 0 for n in self._nagrania)
        miejsce = sum(n.rozmiar for n in self._nagrania)
        numer = max(0, self._wybrany - (len(self._pozycje) - len(self._nagrania)) + 1)
        self._naglowek.set_text(
            f"{nazwa}   ·   {len(self._nagrania)} nagrań   ·   "
            f"łącznie {_ludzki_czas(laczna)}   ·   {_ludzki_rozmiar(miejsce)}"
            + (f"   ·   wybrane {numer} z {len(self._nagrania)}" if numer else "")
        )

    # ---- pokrętło --------------------------------------------------------

    def obrot(self, kierunek: int) -> None:
        if self._pozycje:
            self._wybrany = (self._wybrany + kierunek) % len(self._pozycje)
            self._zaznacz()

    def klik(self) -> None:
        self.wykonaj(self._wybrany)

    def przytrzymanie(self) -> None:
        self._na_wstecz()

    def wykonaj(self, indeks: int) -> None:
        if 0 <= indeks < len(self._pozycje):
            self._wybrany = indeks
            self._zaznacz()
            self._pozycje[indeks].akcja()

    # ---- działania -------------------------------------------------------

    def _zmien_zrodlo(self) -> None:
        self._zrodlo = (self._zrodlo + 1) % len(ZRODLA)
        self._wybrany = 1
        self.odswiez()

    def _odtworz(self, nagranie: Nagranie) -> None:
        self._na_komunikat(f"Odtwarzam: {nagranie.sciezka.name}")
        self._na_odtwarzanie(str(nagranie.sciezka))


def uruchom_mpv(sciezka: str) -> subprocess.Popen | None:
    """Odpala mpv na pełnym ekranie z gniazdem sterującym dla pokrętła."""
    try:
        os.unlink(GNIAZDO_MPV)
    except FileNotFoundError:
        pass
    except OSError:
        pass
    srodowisko = os.environ.copy()
    # LD_PRELOAD jest nasze, do nakładek — mpv go nie potrzebuje.
    srodowisko.pop("LD_PRELOAD", None)
    try:
        return subprocess.Popen(
            [
                "mpv",
                "--fullscreen",
                "--no-terminal",
                "--osd-level=1",
                f"--input-ipc-server={GNIAZDO_MPV}",
                sciezka,
            ],
            start_new_session=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=srodowisko,
        )
    except OSError:
        log.exception("Nie udało się uruchomić mpv")
        return None
