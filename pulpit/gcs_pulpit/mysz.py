"""Mysz sterowana pokrętłem — dla aplikacji, których klawiszami obsłużyć się nie da.

## Dlaczego to musiało powstać

Pilot (`pilot.py`) wysyła `TAB`, strzałki i `ENTER` i to wystarcza aplikacjom, które
umieją nawigację klawiaturą. **Strona DRON 15 do nich nie należy** — sprawdzone
na żywo: `F11` przełączył Chromium na pełny ekran (czyli klawisze docierają bez
zarzutu), ale trzy `TAB`-y nie zaznaczyły niczego widocznego. Aplikacja zrobiona
pod dotyk i mysz nie stanie się obsługiwalna klawiaturą dlatego, że tego chcemy.

Zostaje wskaźnik. `wlrctl` (protokół `zwlr_virtual_pointer_v1`) rusza kursorem
i klika — bez roota, bez `uinput`, przez zwykłe gniazdo Waylanda.

## Trzy tryby, bo gestów są dwa

Pokrętło daje obrót i klik, a potrzebne są trzy rzeczy: ruch w poziomie, ruch
w pionie i kliknięcie. Dlatego **obrót zawsze rusza kursorem, a klik przełącza,
czym rusza** — po kole `POZIOM → PION → KLIK`. W trybie `KLIK` klik naciska
przycisk myszy i tam zostaje, więc dwuklik to po prostu dwa kliknięcia.

Przytrzymanie wychodzi.

## Ruch jest zbierany i wysyłany paczkami

Każdy `wlrctl` to osobny proces. Przy 20 zaskokach na sekundę byłoby ich 20 —
dlatego przesunięcia sumują się i lecą raz na 60 ms. Przy okazji ruch jest
płynniejszy, bo kursor nie skacze po jednym kroku.
"""

from __future__ import annotations

import logging
import subprocess
import time
from collections.abc import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Gtk4LayerShell", "1.0")
from gi.repository import GLib, Gtk  # noqa: E402
from gi.repository import Gtk4LayerShell as Warstwa  # noqa: E402

log = logging.getLogger("gcs.pulpit.mysz")

STYL = b"""
window.mysz   { background-color: #0e1411; }
.mysz-ramka   { padding: 10px 14px 12px 14px; }
.mysz-opis    { font-size: 11pt; color: #8f9a92; margin-bottom: 8px; }
.mysz-stan    { font-size: 17pt; font-weight: 800; color: #ffb000; margin-bottom: 8px; }

button.mysz {
    background-image: none;
    background-color: #161d19;
    border: 3px solid #202a25;
    border-radius: 8px;
    box-shadow: none;
    min-width: 150px; min-height: 62px;
    font-size: 15pt; font-weight: 700; color: #f2f2e8;
    padding: 0 10px;
}
button.mysz.mysz-wybrany { border-color: #ffb000; background-color: #2a3830; }
button.mysz-klik    { background-color: #16301c; color: #7de08e; }
button.mysz-klik.mysz-wybrany { border-color: #7de08e; background-color: #1d3f25; }
button.mysz-wyjscie { background-color: #2a1512; color: #ffb4a0; font-size: 12pt; }
button.mysz-wyjscie.mysz-wybrany { border-color: #ff8a70; background-color: #391b16; }
button.mysz-klaw    { background-color: #15202a; color: #a8d4ff; font-size: 12pt; }
button.mysz-klaw.mysz-wybrany { border-color: #6fb8ff; background-color: #1b2c3a; }
"""

POZIOM = "poziom"
PION = "pion"
KLIK = "klik"
KOLEJNOSC = (POZIOM, PION, KLIK)

KROK_PX = 14
KROK_SZYBKI_PX = 70
KROK_BARDZO_SZYBKI_PX = 170
PROG_SZYBKI_S = 0.09
PROG_BARDZO_SZYBKI_S = 0.05
PACZKA_MS = 60


class Mysz(Gtk.Window):
    def __init__(
        self,
        *,
        na_pilota: Callable[[], None],
        na_pulpit: Callable[[], None],
        na_klawiature: Callable[[], None],
    ) -> None:
        super().__init__()
        self.add_css_class("mysz")
        self._na_pilota = na_pilota
        self._na_pulpit = na_pulpit
        self._na_klawiature = na_klawiature

        self._tryb = POZIOM
        self._ostatni_obrot = 0.0
        self._dx = 0
        self._dy = 0
        self._zegar: int | None = None
        self._pozycje: list[tuple[str, Callable[[], None], str]] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0

        self._jako_nakladka()
        self._zbuduj()
        self._odswiez_stan()

    def _jako_nakladka(self) -> None:
        Warstwa.init_for_window(self)
        Warstwa.set_layer(self, Warstwa.Layer.OVERLAY)
        for krawedz in (Warstwa.Edge.BOTTOM, Warstwa.Edge.LEFT, Warstwa.Edge.RIGHT):
            Warstwa.set_anchor(self, krawedz, True)
        Warstwa.set_keyboard_mode(self, Warstwa.KeyboardMode.NONE)
        Warstwa.auto_exclusive_zone_enable(self)

    def _zbuduj(self) -> None:
        ramka = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        ramka.add_css_class("mysz-ramka")
        self.set_child(ramka)

        opis = Gtk.Label(
            label="Obrót: rusza kursorem  ·  Klik: przełącza tryb  ·  Przytrzymaj: wyjście",
            xalign=0,
        )
        opis.add_css_class("mysz-opis")
        ramka.append(opis)

        self._stan = Gtk.Label(label="", xalign=0)
        self._stan.add_css_class("mysz-stan")
        ramka.append(self._stan)

        siatka = Gtk.Grid(row_spacing=8, column_spacing=8)
        siatka.set_halign(Gtk.Align.CENTER)
        ramka.append(siatka)

        self._pozycje = [
            ("↔ POZIOM", lambda: self._ustaw_tryb(POZIOM), ""),
            ("↕ PION", lambda: self._ustaw_tryb(PION), ""),
            ("● LEWY KLIK", self._klik_myszy, "mysz-klik"),
            ("⌨ KLAWIATURA", self._na_klawiature, "mysz-klaw"),
            ("◀ PILOT", self._na_pilota, "mysz-wyjscie"),
            ("◀ PULPIT", self._na_pulpit, "mysz-wyjscie"),
        ]
        for indeks, (etykieta, _, styl) in enumerate(self._pozycje):
            przycisk = Gtk.Button(label=etykieta)
            przycisk.add_css_class("mysz")
            # ⛔ Nasze przyciski NIE MOGĄ przyjmować ogniska GTK. Zaznaczenie
            # prowadzimy sami, a gdy przycisk ma ognisko, GTK aktywuje go
            # dodatkowo klawiszem — zatwierdzenie wykonywało się wtedy DWA RAZY
            # i wchodzenie w grupę kończyło się otwarciem sąsiedniej pozycji.
            przycisk.set_can_focus(False)
            przycisk.set_focus_on_click(False)
            if styl:
                przycisk.add_css_class(styl)
            przycisk.connect("clicked", lambda *_, i=indeks: self._wykonaj_pozycje(i))
            siatka.attach(przycisk, indeks % 6, indeks // 6, 1, 1)
            self._widgety.append(przycisk)

    # ---- stan ------------------------------------------------------------

    def _ustaw_tryb(self, tryb: str) -> None:
        self._tryb = tryb
        self._odswiez_stan()

    def _odswiez_stan(self) -> None:
        opisy = {
            POZIOM: "KURSOR: ◀ ▶ w poziomie",
            PION: "KURSOR: ▲ ▼ w pionie",
            KLIK: "KLIK — obrót wraca do ruchu w poziomie",
        }
        self._stan.set_text(opisy[self._tryb])
        self._wybrany = KOLEJNOSC.index(self._tryb)
        for i, widget in enumerate(self._widgety):
            if i == self._wybrany:
                widget.add_css_class("mysz-wybrany")
            else:
                widget.remove_css_class("mysz-wybrany")

    # ---- pokrętło --------------------------------------------------------

    def obrot(self, kierunek: int) -> None:
        if self._tryb == KLIK:
            self._ustaw_tryb(POZIOM)
            return
        teraz = time.monotonic()
        odstep = teraz - self._ostatni_obrot
        self._ostatni_obrot = teraz
        if odstep < PROG_BARDZO_SZYBKI_S:
            krok = KROK_BARDZO_SZYBKI_PX
        elif odstep < PROG_SZYBKI_S:
            krok = KROK_SZYBKI_PX
        else:
            krok = KROK_PX
        if self._tryb == POZIOM:
            self._dx += kierunek * krok
        else:
            self._dy += kierunek * krok
        if self._zegar is None:
            self._zegar = GLib.timeout_add(PACZKA_MS, self._wyslij_ruch)

    def klik(self) -> None:
        """Klik przełącza tryb po kole; w trybie KLIK naciska przycisk myszy."""
        if self._tryb == KLIK:
            self._klik_myszy()
            return
        nastepny = KOLEJNOSC[(KOLEJNOSC.index(self._tryb) + 1) % len(KOLEJNOSC)]
        self._ustaw_tryb(nastepny)

    def przytrzymanie(self) -> None:
        self._na_pilota()

    # ---- działanie -------------------------------------------------------

    def _wykonaj_pozycje(self, indeks: int) -> None:
        """Wejście myszą — pokrętło korzysta z `klik()` i `obrot()`."""
        if 0 <= indeks < len(self._pozycje):
            self._pozycje[indeks][1]()

    def _wyslij_ruch(self) -> bool:
        self._zegar = None
        dx, dy = self._dx, self._dy
        self._dx = self._dy = 0
        if dx or dy:
            self._wlrctl(["pointer", "move", str(dx), str(dy)])
        return False

    def _klik_myszy(self) -> None:
        self._wyslij_ruch()
        self._wlrctl(["pointer", "click", "left"])
        self._ustaw_tryb(KLIK)

    @staticmethod
    def _wlrctl(argumenty: list[str]) -> None:
        try:
            subprocess.run(["wlrctl", *argumenty], check=False, timeout=5)
        except (OSError, subprocess.SubprocessError):
            log.exception("wlrctl nie zadziałał — czy pakiet jest zainstalowany?")
