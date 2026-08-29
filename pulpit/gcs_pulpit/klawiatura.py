"""Klawiatura ekranowa sterowana pokrętłem, w stylu pulpitu GCS.

Ekran nie jest dotykowy, a `squeekboard` jest zrobiony pod palec — więc gdziekolwiek
trzeba wpisać tekst, operator jest bezradny. To jest odpowiedź na ten przypadek.

## Jak to w ogóle może działać

Klawiatura jest **nakładką `layer-shell` z `KeyboardMode.NONE`** — leży nad wszystkim,
ale **nie zabiera ogniska klawiatury**. Dzięki temu okno pod spodem (Chromium, ATAK,
nasze własne pole) pozostaje aktywne i to ono dostaje znaki.

Sterowanie nie idzie przez ognisko okna, tylko **przez pokrętło po gnieździe UNIX**,
więc brak ogniska niczego nie psuje. Te dwie rzeczy razem sprawiają, że nakładka
działa tam, gdzie zwykłe okno by przeszkadzało.

Sprawdzone na `GSB` 2026-08-29: `wtype` wpisał `zażółć gęślą jaźń 123` do pola
w oknie pod nakładką, z polskimi znakami, bez utraty ogniska.

⚠ **Wymaga `LD_PRELOAD=libgtk4-layer-shell.so.0`** — inaczej biblioteka ląduje
w dowiązaniach po `libwayland` i nakładka po cichu staje się zwykłym oknem
(ostrzeżenie „GTK4 Layer Shell may have been linked after libwayland”).

## Dlaczego tekst zbiera się najpierw u nas

Znaki **nie** lecą do celu po jednym. Powstają w podglądzie na górze klawiatury,
a do okna trafiają dopiero po zatwierdzeniu. Przy pisaniu pokrętłem literówka jest
nieunikniona, a poprawianie jej w cudzym polu wymagałoby strzałek, których nie ma.
"""

from __future__ import annotations

import logging
import os
import subprocess
from collections.abc import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Gtk4LayerShell", "1.0")
from gi.repository import Gtk, Pango  # noqa: E402
from gi.repository import Gtk4LayerShell as Warstwa  # noqa: E402

log = logging.getLogger("gcs.pulpit.klawiatura")

STYL = b"""
window.klawiatura        { background-color: #0e1411; }
.klaw-ramka              { padding: 14px 18px 18px 18px; }

.klaw-podglad {
    font-size: 20pt; font-weight: 700; color: #f2f2e8;
    background-color: #161d19; border: 2px solid #232e28; border-radius: 8px;
    padding: 12px 18px; margin-bottom: 12px;
}
.klaw-podglad-pusty { color: #6f7a72; font-weight: 400; }
.klaw-opis { font-size: 11pt; color: #8f9a92; margin-bottom: 10px; }

button.klaw {
    background-image: none;
    background-color: #161d19;
    border: 3px solid #202a25;
    border-radius: 8px;
    box-shadow: none;
    min-width: 74px; min-height: 62px;
    font-size: 19pt; font-weight: 700; color: #f2f2e8;
    padding: 0;
}
button.klaw.klaw-wybrany { border-color: #ffb000; background-color: #2a3830; }
button.klaw-funkcja   { background-color: #1b2420; color: #b9c3bb; font-size: 13pt; }
button.klaw-wyslij    { background-color: #16301c; color: #7de08e; font-size: 13pt; }
button.klaw-wyslij.klaw-wybrany { border-color: #7de08e; background-color: #1d3f25; }
button.klaw-anuluj    { background-color: #2a1512; color: #ffb4a0; font-size: 13pt; }
button.klaw-anuluj.klaw-wybrany { border-color: #ff8a70; background-color: #391b16; }
button.klaw-wlaczony  { background-color: #3a2f10; color: #ffd070; }
"""

MALE = [
    list("qwertyuiop"),
    list("asdfghjkl"),
    list("zxcvbnm"),
]
CYFRY = [
    list("1234567890"),
    ["-", "_", ".", ",", ":", ";", "/", "\\", "@", "#"],
    ["!", "?", "'", '"', "(", ")", "+", "=", "*", "&"],
    ["%", "$", "<", ">", "|", "~", "^", "`", "[", "]"],
]
POLSKIE = [list("ąćęłńóśźż")]


class Klawiatura(Gtk.Window):
    """Nakładka na dole ekranu. `na_gotowe` przejmuje tekst zamiast `wtype`."""

    def __init__(
        self,
        *,
        na_gotowe: Callable[[str], None] | None = None,
        na_zamkniecie: Callable[[], None] | None = None,
        opis: str = "",
        tekst: str = "",
    ) -> None:
        super().__init__()
        self.add_css_class("klawiatura")
        self._na_gotowe = na_gotowe
        self._na_zamkniecie = na_zamkniecie
        self._opis = opis
        self._bufor = tekst

        self._duze = False
        self._warstwa = "litery"  # litery | cyfry | polskie
        self._klawisze: list[tuple[str, str, str]] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0

        self._jako_nakladka()
        self._zbuduj()
        self._przeloz()

    # ---- nakładka --------------------------------------------------------

    def _jako_nakladka(self) -> None:
        Warstwa.init_for_window(self)
        Warstwa.set_layer(self, Warstwa.Layer.OVERLAY)
        for krawedz in (Warstwa.Edge.BOTTOM, Warstwa.Edge.LEFT, Warstwa.Edge.RIGHT):
            Warstwa.set_anchor(self, krawedz, True)
        # ⛔ NONE, nie ON_DEMAND: nakładka nie może przejąć ogniska, bo wtedy
        # znaki poszłyby do niej samej zamiast do okna pod spodem.
        Warstwa.set_keyboard_mode(self, Warstwa.KeyboardMode.NONE)
        Warstwa.auto_exclusive_zone_enable(self)

    # ---- budowa ----------------------------------------------------------

    def _zbuduj(self) -> None:
        ramka = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        ramka.add_css_class("klaw-ramka")
        self.set_child(ramka)

        if self._opis:
            etykieta = Gtk.Label(label=self._opis, xalign=0)
            etykieta.add_css_class("klaw-opis")
            ramka.append(etykieta)

        self._podglad = Gtk.Label(label="", xalign=0)
        self._podglad.add_css_class("klaw-podglad")
        self._podglad.set_ellipsize(Pango.EllipsizeMode.START)
        ramka.append(self._podglad)

        self._siatka = Gtk.Grid(row_spacing=8, column_spacing=8)
        self._siatka.set_halign(Gtk.Align.CENTER)
        ramka.append(self._siatka)

    def _uklad(self) -> list[list[str]]:
        if self._warstwa == "cyfry":
            return CYFRY
        rzedy = POLSKIE if self._warstwa == "polskie" else MALE
        if self._duze:
            return [[z.upper() for z in rzad] for rzad in rzedy]
        return rzedy

    def _zbierz_klawisze(self) -> list[tuple[str, str, str]]:
        """(etykieta, rodzaj, wartość)"""
        klawisze: list[tuple[str, str, str]] = []
        for rzad in self._uklad():
            for znak in rzad:
                klawisze.append((znak, "znak", znak))

        klawisze.append(("⇧ DUŻE" if not self._duze else "⇧ małe", "shift", ""))
        klawisze.append(("123" if self._warstwa != "cyfry" else "ABC", "cyfry", ""))
        klawisze.append(("ĄĘ" if self._warstwa != "polskie" else "ABC", "polskie", ""))
        klawisze.append(("SPACJA", "znak", " "))
        klawisze.append(("⌫ CZYŚĆ", "kasuj", ""))
        klawisze.append(("✓ WYŚLIJ", "wyslij", ""))
        klawisze.append(("⏎ WYŚLIJ+ENTER", "wyslij-enter", ""))
        klawisze.append(("✕ ANULUJ", "anuluj", ""))
        return klawisze

    def _przeloz(self) -> None:
        self._klawisze = self._zbierz_klawisze()
        self._wybrany = min(self._wybrany, len(self._klawisze) - 1)
        while (dziecko := self._siatka.get_first_child()) is not None:
            self._siatka.remove(dziecko)
        self._widgety = []

        kolumna = 0
        wiersz = 0
        for rzad in self._uklad():
            kolumna = 0
            for znak in rzad:
                self._dodaj(znak, wiersz, kolumna, 1)
                kolumna += 1
            wiersz += 1

        # Rząd funkcyjny: klawisze z dłuższym opisem dostają więcej miejsca,
        # a przy przepełnieniu wiersza schodzą do następnego.
        wiersz += 1
        kolumna = 0
        for indeks in range(len(self._widgety), len(self._klawisze)):
            etykieta, rodzaj, _ = self._klawisze[indeks]
            szerokosc = 3 if len(etykieta) > 6 else 2
            self._dodaj(etykieta, wiersz, kolumna, szerokosc, rodzaj)
            kolumna += szerokosc
            if kolumna >= 12:
                kolumna = 0
                wiersz += 1

        self._odmaluj()
        self._odswiez_podglad()

    def _dodaj(
        self, etykieta: str, wiersz: int, kolumna: int, szerokosc: int, rodzaj: str = "znak"
    ) -> None:
        indeks = len(self._widgety)
        przycisk = Gtk.Button(label=etykieta)
        przycisk.add_css_class("klaw")
        # ⛔ Nasze przyciski NIE MOGĄ przyjmować ogniska GTK. Zaznaczenie
        # prowadzimy sami, a gdy przycisk ma ognisko, GTK aktywuje go
        # dodatkowo klawiszem — zatwierdzenie wykonywało się wtedy DWA RAZY
        # i wchodzenie w grupę kończyło się otwarciem sąsiedniej pozycji.
        przycisk.set_can_focus(False)
        przycisk.set_focus_on_click(False)
        if rodzaj in ("shift", "cyfry", "polskie", "kasuj"):
            przycisk.add_css_class("klaw-funkcja")
            if (rodzaj == "shift" and self._duze) or (
                rodzaj in ("cyfry", "polskie") and self._warstwa == rodzaj
            ):
                przycisk.add_css_class("klaw-wlaczony")
        elif rodzaj in ("wyslij", "wyslij-enter"):
            przycisk.add_css_class("klaw-wyslij")
        elif rodzaj == "anuluj":
            przycisk.add_css_class("klaw-anuluj")
        przycisk.connect("clicked", lambda *_: self.wykonaj(indeks))
        self._siatka.attach(przycisk, kolumna, wiersz, szerokosc, 1)
        self._widgety.append(przycisk)

    def _odmaluj(self) -> None:
        for i, widget in enumerate(self._widgety):
            if i == self._wybrany:
                widget.add_css_class("klaw-wybrany")
            else:
                widget.remove_css_class("klaw-wybrany")

    def _odswiez_podglad(self) -> None:
        if self._bufor:
            self._podglad.set_text(self._bufor + "▌")
            self._podglad.remove_css_class("klaw-podglad-pusty")
        else:
            self._podglad.set_text("(pusto — kręć i klikaj)")
            self._podglad.add_css_class("klaw-podglad-pusty")

    # ---- pokrętło --------------------------------------------------------

    def obrot(self, kierunek: int) -> None:
        if self._klawisze:
            self._wybrany = (self._wybrany + kierunek) % len(self._klawisze)
            self._odmaluj()

    def klik(self) -> None:
        self.wykonaj(self._wybrany)

    def przytrzymanie(self) -> None:
        """Przytrzymanie zamyka klawiaturę bez wysyłania — jak ANULUJ."""
        self._zamknij()

    # ---- działanie -------------------------------------------------------

    def wykonaj(self, indeks: int) -> None:
        if not (0 <= indeks < len(self._klawisze)):
            return
        self._wybrany = indeks
        self._odmaluj()
        _, rodzaj, wartosc = self._klawisze[indeks]

        if rodzaj == "znak":
            self._bufor += wartosc
            self._odswiez_podglad()
        elif rodzaj == "kasuj":
            self._bufor = self._bufor[:-1]
            self._odswiez_podglad()
        elif rodzaj == "shift":
            self._duze = not self._duze
            self._przeloz()
        elif rodzaj in ("cyfry", "polskie"):
            self._warstwa = "litery" if self._warstwa == rodzaj else rodzaj
            self._przeloz()
        elif rodzaj == "wyslij":
            self._wyslij(enter=False)
        elif rodzaj == "wyslij-enter":
            self._wyslij(enter=True)
        elif rodzaj == "anuluj":
            self._zamknij()

    def _wyslij(self, *, enter: bool) -> None:
        tekst = self._bufor
        if self._na_gotowe is not None:
            self._na_gotowe(tekst)
            self._zamknij()
            return
        if tekst:
            self._wtype([tekst])
        if enter:
            self._wtype(["-k", "Return"])
        log.info("Wysłane do okna pod spodem: %d znaków%s", len(tekst), ", ENTER" if enter else "")
        self._zamknij()

    @staticmethod
    def _wtype(argumenty: list[str]) -> None:
        try:
            subprocess.run(["wtype", *argumenty], check=False, timeout=10)
        except (OSError, subprocess.SubprocessError):
            log.exception("wtype nie zadziałał — czy pakiet jest zainstalowany?")

    def _zamknij(self) -> None:
        if self._na_zamkniecie is not None:
            self._na_zamkniecie()
        self.destroy()


def dostepna() -> tuple[bool, str]:
    """Sprawdza, czy klawiatura ma czym pisać i na czym leżeć."""
    if not any(
        os.path.exists(os.path.join(k, "wtype"))
        for k in os.getenv("PATH", "").split(os.pathsep)
    ):
        return False, "brak wtype"
    if not os.getenv("LD_PRELOAD", "").find("gtk4-layer-shell") >= 0:
        # Nie błąd: bez LD_PRELOAD nakładka zadziała jak zwykłe okno i zabierze
        # ognisko. Warto to wiedzieć, ale nie warto z tego powodu odmawiać.
        log.warning(
            "LD_PRELOAD bez libgtk4-layer-shell — klawiatura może zabrać ognisko"
        )
    return True, ""
