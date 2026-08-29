"""Pilot — sterowanie pokrętłem aplikacją, która jest na wierzchu.

Uruchomiona aplikacja (Chromium, ATAK, HUD) nie wie nic o pokrętle. Bez tego
operator może ją najwyżej włączyć i zamknąć, a w środku nie zrobi nic.

Pilot to wąski pasek na dole, **nakładka `layer-shell` bez ogniska** — dokładnie
jak klawiatura (`klawiatura.py`). Zamienia pokrętło na klawisze, które rozumie
każda aplikacja: strzałki, `ENTER`, `TAB`, `ESC`.

> ### Dlaczego wybór klawisza i osobne wciśnięcie, a nie „obrót = strzałka”
>
> Mając jedno pokrętło i jeden przycisk, trzeba wybrać, co znaczy obrót. Gdyby obrót
> był strzałką w dół, nie byłoby jak sięgnąć po `ENTER` czy `ESC`.
>
> Dlatego **obrót wybiera klawisz, a klik go wciska** — i zaznaczenie zostaje tam,
> gdzie było. Przewijanie listy w dół to jeden obrót na `↓`, a potem tyle kliknięć,
> ile trzeba. To jest szybsze, niż wygląda na papierze.
"""

from __future__ import annotations

import logging
import subprocess
from collections.abc import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Gtk4LayerShell", "1.0")
from gi.repository import Gtk  # noqa: E402
from gi.repository import Gtk4LayerShell as Warstwa  # noqa: E402

log = logging.getLogger("gcs.pulpit.pilot")

STYL = b"""
window.pilot  { background-color: #0e1411; }
.pilot-ramka  { padding: 10px 14px 12px 14px; }
.pilot-opis   { font-size: 11pt; color: #8f9a92; margin-bottom: 8px; }

button.pilot {
    background-image: none;
    background-color: #161d19;
    border: 3px solid #202a25;
    border-radius: 8px;
    box-shadow: none;
    min-width: 92px; min-height: 58px;
    font-size: 17pt; font-weight: 700; color: #f2f2e8;
    padding: 0 6px;
}
button.pilot.pilot-wybrany { border-color: #ffb000; background-color: #2a3830; }
button.pilot-maly    { font-size: 12pt; color: #b9c3bb; background-color: #1b2420; }
button.pilot-wyjscie { background-color: #2a1512; color: #ffb4a0; font-size: 12pt; }
button.pilot-wyjscie.pilot-wybrany {
    border-color: #ff8a70; background-color: #391b16;
}
button.pilot-mysz    { background-color: #1a2416; color: #c4e8a0; font-size: 12pt; }
button.pilot-mysz.pilot-wybrany { border-color: #a8e06f; background-color: #24331d; }
button.pilot-klaw    { background-color: #15202a; color: #a8d4ff; font-size: 12pt; }
button.pilot-klaw.pilot-wybrany { border-color: #6fb8ff; background-color: #1b2c3a; }
"""

# (etykieta, argumenty wtype, klasa stylu)
KLAWISZE: list[tuple[str, list[str], str]] = [
    ("↓", ["-k", "Down"], ""),
    ("↑", ["-k", "Up"], ""),
    ("←", ["-k", "Left"], ""),
    ("→", ["-k", "Right"], ""),
    ("ENTER", ["-k", "Return"], "pilot-maly"),
    ("ESC", ["-k", "Escape"], "pilot-maly"),
    ("TAB", ["-k", "Tab"], "pilot-maly"),
    ("⇧TAB", ["-M", "shift", "-k", "Tab", "-m", "shift"], "pilot-maly"),
    ("SPACJA", ["-k", "space"], "pilot-maly"),
    ("PgDn", ["-k", "Next"], "pilot-maly"),
    ("PgUp", ["-k", "Prior"], "pilot-maly"),
    ("⌫", ["-k", "BackSpace"], "pilot-maly"),
]


class Pilot(Gtk.Window):
    """Pasek na dole. Obrót wybiera klawisz, klik go wciska, przytrzymanie wychodzi."""

    def __init__(
        self,
        *,
        nazwa_aplikacji: str = "",
        na_pulpit: Callable[[], None],
        na_klawiature: Callable[[], None],
        na_mysz: Callable[[], None] | None = None,
        na_zamkniecie_aplikacji: Callable[[], None] | None = None,
    ) -> None:
        super().__init__()
        self.add_css_class("pilot")
        self._nazwa = nazwa_aplikacji
        self._na_pulpit = na_pulpit
        self._na_klawiature = na_klawiature
        self._na_mysz = na_mysz
        self._na_zamkniecie_aplikacji = na_zamkniecie_aplikacji

        self._pozycje: list[tuple[str, Callable[[], None], str]] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0

        self._jako_nakladka()
        self._zbuduj()

    def _jako_nakladka(self) -> None:
        Warstwa.init_for_window(self)
        Warstwa.set_layer(self, Warstwa.Layer.OVERLAY)
        for krawedz in (Warstwa.Edge.BOTTOM, Warstwa.Edge.LEFT, Warstwa.Edge.RIGHT):
            Warstwa.set_anchor(self, krawedz, True)
        # ⛔ NONE: pilot nie może przejąć ogniska, bo wtedy klawisze poszłyby
        # do niego samego zamiast do aplikacji pod spodem.
        Warstwa.set_keyboard_mode(self, Warstwa.KeyboardMode.NONE)
        Warstwa.auto_exclusive_zone_enable(self)

    def _zbuduj(self) -> None:
        ramka = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        ramka.add_css_class("pilot-ramka")
        self.set_child(ramka)

        opis = "Obrót: wybór klawisza · Klik: wciśnij · Przytrzymaj: wróć na pulpit"
        if self._nazwa:
            opis = f"STERUJESZ: {self._nazwa}   ·   {opis}"
        etykieta = Gtk.Label(label=opis, xalign=0)
        etykieta.add_css_class("pilot-opis")
        ramka.append(etykieta)

        siatka = Gtk.Grid(row_spacing=8, column_spacing=8)
        siatka.set_halign(Gtk.Align.CENTER)
        ramka.append(siatka)

        for etykieta_klawisza, argumenty, styl in KLAWISZE:
            self._pozycje.append(
                (etykieta_klawisza, lambda a=argumenty: self._wcisnij(a), styl)
            )
        if self._na_mysz is not None:
            self._pozycje.append(("🖱 MYSZ", self._na_mysz, "pilot-mysz"))
        self._pozycje.append(("⌨ KLAWIATURA", self._na_klawiature, "pilot-klaw"))
        if self._na_zamkniecie_aplikacji is not None:
            self._pozycje.append(
                ("✕ ZAMKNIJ APLIKACJĘ", self._na_zamkniecie_aplikacji, "pilot-wyjscie")
            )
        self._pozycje.append(("◀ PULPIT", self._na_pulpit, "pilot-wyjscie"))

        kolumna = 0
        wiersz = 0
        for indeks, (etykieta_pozycji, _, styl) in enumerate(self._pozycje):
            szerokosc = 3 if len(etykieta_pozycji) > 6 else 1
            if kolumna + szerokosc > 14:
                kolumna = 0
                wiersz += 1
            przycisk = Gtk.Button(label=etykieta_pozycji)
            przycisk.add_css_class("pilot")
            # ⛔ Nasze przyciski NIE MOGĄ przyjmować ogniska GTK. Zaznaczenie
            # prowadzimy sami, a gdy przycisk ma ognisko, GTK aktywuje go
            # dodatkowo klawiszem — zatwierdzenie wykonywało się wtedy DWA RAZY
            # i wchodzenie w grupę kończyło się otwarciem sąsiedniej pozycji.
            przycisk.set_can_focus(False)
            przycisk.set_focus_on_click(False)
            if styl:
                przycisk.add_css_class(styl)
            przycisk.connect("clicked", lambda *_, i=indeks: self.wykonaj(i))
            siatka.attach(przycisk, kolumna, wiersz, szerokosc, 1)
            self._widgety.append(przycisk)
            kolumna += szerokosc

        self._odmaluj()

    def _odmaluj(self) -> None:
        for i, widget in enumerate(self._widgety):
            if i == self._wybrany:
                widget.add_css_class("pilot-wybrany")
            else:
                widget.remove_css_class("pilot-wybrany")

    # ---- pokrętło --------------------------------------------------------

    def obrot(self, kierunek: int) -> None:
        if self._pozycje:
            self._wybrany = (self._wybrany + kierunek) % len(self._pozycje)
            self._odmaluj()

    def klik(self) -> None:
        self.wykonaj(self._wybrany)

    def przytrzymanie(self) -> None:
        self._na_pulpit()

    # ---- działanie -------------------------------------------------------

    def wykonaj(self, indeks: int) -> None:
        if not (0 <= indeks < len(self._pozycje)):
            return
        # ⚠ Zaznaczenie NIE wraca na początek — przewijanie listy to jeden wybór
        # klawisza i wiele kliknięć.
        self._wybrany = indeks
        self._odmaluj()
        self._pozycje[indeks][1]()

    @staticmethod
    def _wcisnij(argumenty: list[str]) -> None:
        try:
            subprocess.run(["wtype", *argumenty], check=False, timeout=5)
        except (OSError, subprocess.SubprocessError):
            log.exception("wtype nie zadziałał")
