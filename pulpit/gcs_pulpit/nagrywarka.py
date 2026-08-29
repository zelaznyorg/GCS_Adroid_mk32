"""Ekran NAGRYWARKA — źródła obrazu i sterowanie zapisem.

Nagrywarka nie jest przywiązana do jednej kamery. Dron może mieć **tor cyfrowy
i analogowy naraz**, a stacja przewiduje drugi slot kamery w sieci pokładowej —
więc źródła są **listą, którą operator sam układa**.

⚠ **Obraz analogowy też jest tu źródłem IP.** `pi5-uas-rtsp` udostępnia CVBS pod
`rtsp://127.0.0.1:8554/uav` (robi to dla ATAK‑a), więc trafia do tej samej listy
co kamera cyfrowa i nagrywa się tym samym mechanizmem.

Każde źródło ma **własny wiersz i własny zapis**: klik startuje albo zatrzymuje
tylko jego. Przycisk `⏺ REC` na belce obejmuje wszystkie **włączone** naraz.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from .nagrywanie import Nagrywarka, Zrodlo, bezpieczny_id, zapisz_zrodla  # noqa: E402
from .widoki import przewin_do  # noqa: E402

log = logging.getLogger("gcs.pulpit.nagrywarka")

STYL = b"""
.nagrw-naglowek { font-size: 15pt; color: #97a29a; padding: 4px 0 12px 0; }
.nagrw-dzial { font-size: 13pt; font-weight: 800; color: #ffb000;
               padding: 12px 0 4px 4px; }

button.nagrw {
    background-image: none; background-color: #161d19;
    border: 3px solid #232e28; border-radius: 10px; box-shadow: none; outline: none;
    padding: 12px 20px; min-height: 56px;
    font-size: 15pt; font-weight: 700; color: #f2f2e8;
}
button.nagrw.nagrw-wybrany { border-color: #ffb000; background-color: #23302a; }
button.nagrw-wstecz  { background-color: #1b2420; color: #b9c3bb; font-size: 14pt; }
button.nagrw-pisze   { background-color: #7a1c12; border-color: #ff4a2d; color: #ffffff; }
button.nagrw-wylaczone { color: #8f9a92; }
button.nagrw-wszystkie { background-color: #2a1512; color: #ffb4a0; }
button.nagrw-dodaj   { background-color: #17251c; color: #a9e6b8; }
"""


@dataclass
class Pozycja:
    etykieta: str
    akcja: Callable[[], None]
    styl: str = ""
    naglowek: bool = False


class EkranNagrywarki(Gtk.Box):
    def __init__(
        self,
        *,
        nagrywarka: Nagrywarka,
        na_wstecz: Callable[[], None],
        na_tekst: Callable[[str, Callable[[str], None]], None],
        na_komunikat: Callable[[str], None],
        na_zmiane: Callable[[], None],
    ) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.set_margin_top(20)
        self.set_margin_bottom(20)
        self.set_margin_start(40)
        self.set_margin_end(40)

        self._nagrywarka = nagrywarka
        self._na_wstecz = na_wstecz
        self._na_tekst = na_tekst
        self._na_komunikat = na_komunikat
        self._na_zmiane = na_zmiane

        self._pozycje: list[Pozycja] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0
        self._nowa_nazwa = ""

        self._naglowek = Gtk.Label(label="", xalign=0)
        self._naglowek.add_css_class("nagrw-naglowek")
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
        zrodla = self._nagrywarka.zrodla
        pozycje: list[Pozycja] = [
            Pozycja("◀ WSTECZ  (albo przytrzymaj pokrętło)", self._na_wstecz, "nagrw-wstecz")
        ]
        if self._nagrywarka.nagrywa:
            pozycje.append(
                Pozycja(
                    "⏹ ZATRZYMAJ WSZYSTKIE",
                    self._zatrzymaj_wszystkie,
                    "nagrw-wszystkie nagrw-pisze",
                )
            )
        else:
            pozycje.append(
                Pozycja(
                    "⏺ NAGRYWAJ WSZYSTKIE WŁĄCZONE",
                    self._nagrywaj_wszystkie,
                    "nagrw-wszystkie",
                )
            )

        pozycje.append(Pozycja("— ŹRÓDŁA — klik startuje albo zatrzymuje", lambda: None, naglowek=True))
        for zrodlo in zrodla:
            rejestrator = self._nagrywarka.rejestrator(zrodlo.id)
            stan = rejestrator.opis() if rejestrator else "?"
            pisze = bool(rejestrator and rejestrator.nagrywa)
            znak = "●" if pisze else ("○" if zrodlo.wlaczone else "·")
            styl = "nagrw-pisze" if pisze else ("" if zrodlo.wlaczone else "nagrw-wylaczone")
            pozycje.append(
                Pozycja(
                    f"{znak}  {zrodlo.nazwa}   ·   {stan}",
                    lambda z=zrodlo: self._przelacz_zrodlo(z),
                    styl,
                )
            )

        pozycje.append(Pozycja("— USTAWIENIA ŹRÓDEŁ —", lambda: None, naglowek=True))
        for zrodlo in zrodla:
            stan = "włączone do REC" if zrodlo.wlaczone else "pomijane przez REC"
            pozycje.append(
                Pozycja(
                    f"{zrodlo.nazwa}   ·   {zrodlo.adres}   ·   {stan}  ▸ przełącz",
                    lambda z=zrodlo: self._przelacz_wlaczone(z),
                    "" if zrodlo.wlaczone else "nagrw-wylaczone",
                )
            )
            pozycje.append(
                Pozycja(
                    f"      ▸ usuń źródło {zrodlo.nazwa}",
                    lambda z=zrodlo: self._usun(z),
                    "nagrw-wylaczone",
                )
            )
        pozycje.append(Pozycja("➕ DODAJ ŹRÓDŁO IP", self._dodaj, "nagrw-dodaj"))

        self._pozycje = pozycje
        self._wybrany = min(self._wybrany, len(pozycje) - 1)
        self._przemaluj()

    def _przemaluj(self) -> None:
        while (dziecko := self._lista.get_first_child()) is not None:
            self._lista.remove(dziecko)
        self._widgety = []
        for indeks, pozycja in enumerate(self._pozycje):
            if pozycja.naglowek:
                etykieta = Gtk.Label(label=pozycja.etykieta, xalign=0)
                etykieta.add_css_class("nagrw-dzial")
                self._lista.append(etykieta)
                self._widgety.append(etykieta)
                continue
            przycisk = Gtk.Button(label=pozycja.etykieta)
            przycisk.add_css_class("nagrw")
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
                widget.add_css_class("nagrw-wybrany")
            else:
                widget.remove_css_class("nagrw-wybrany")
        if 0 <= self._wybrany < len(self._widgety):
            przewin_do(self._przewijak, self._lista, self._widgety[self._wybrany])
        self._opisz_naglowek()

    def _opisz_naglowek(self) -> None:
        zrodla = self._nagrywarka.zrodla
        wlaczone = sum(1 for z in zrodla if z.wlaczone)
        self._naglowek.set_text(
            f"Źródeł: {len(zrodla)}   ·   włączonych do REC: {wlaczone}"
            f"   ·   nagrywa teraz: {self._nagrywarka.ile_nagrywa}"
        )

    # ---- pokrętło --------------------------------------------------------

    def obrot(self, kierunek: int) -> None:
        if not self._pozycje:
            return
        indeks = self._wybrany
        for _ in range(len(self._pozycje)):
            indeks = (indeks + kierunek) % len(self._pozycje)
            if not self._pozycje[indeks].naglowek:
                break
        self._wybrany = indeks
        self._zaznacz()

    def klik(self) -> None:
        self.wykonaj(self._wybrany)

    def przytrzymanie(self) -> None:
        self._na_wstecz()

    def wykonaj(self, indeks: int) -> None:
        if 0 <= indeks < len(self._pozycje) and not self._pozycje[indeks].naglowek:
            self._wybrany = indeks
            self._zaznacz()
            self._pozycje[indeks].akcja()

    # ---- działania -------------------------------------------------------

    def _nagrywaj_wszystkie(self) -> None:
        self._na_komunikat(self._nagrywarka.przelacz_wszystkie())
        self._po_zmianie()

    def _zatrzymaj_wszystkie(self) -> None:
        self._na_komunikat(self._nagrywarka.zatrzymaj_wszystkie())
        self._po_zmianie()

    def _przelacz_zrodlo(self, zrodlo: Zrodlo) -> None:
        rejestrator = self._nagrywarka.rejestrator(zrodlo.id)
        if rejestrator is None:
            return
        komunikat = rejestrator.zatrzymaj() if rejestrator.nagrywa else rejestrator.zacznij()
        self._na_komunikat(komunikat)
        self._po_zmianie()

    def _przelacz_wlaczone(self, zrodlo: Zrodlo) -> None:
        zrodla = self._nagrywarka.zrodla
        for z in zrodla:
            if z.id == zrodlo.id:
                z.wlaczone = not z.wlaczone
        zapisz_zrodla(zrodla)
        self._nagrywarka.przeladuj()
        self._po_zmianie()

    def _usun(self, zrodlo: Zrodlo) -> None:
        rejestrator = self._nagrywarka.rejestrator(zrodlo.id)
        if rejestrator is not None and rejestrator.nagrywa:
            self._na_komunikat(f"{zrodlo.nazwa}: najpierw zatrzymaj nagrywanie")
            return
        zrodla = [z for z in self._nagrywarka.zrodla if z.id != zrodlo.id]
        zapisz_zrodla(zrodla)
        self._nagrywarka.przeladuj()
        self._na_komunikat(f"Usunięte źródło: {zrodlo.nazwa}")
        self._po_zmianie()

    def _dodaj(self) -> None:
        self._na_tekst("Nazwa źródła (np. Kamera 2)", self._dodaj_nazwa)

    def _dodaj_nazwa(self, nazwa: str) -> None:
        nazwa = nazwa.strip()
        if not nazwa:
            self._na_komunikat("Pusta nazwa — nic nie dodałem")
            return
        self._nowa_nazwa = nazwa
        self._na_tekst("Adres RTSP (rtsp://adres:port/sciezka)", self._dodaj_adres)

    def _dodaj_adres(self, adres: str) -> None:
        adres = adres.strip()
        if not adres.startswith(("rtsp://", "rtsps://", "http://", "https://")):
            self._na_komunikat("Adres musi zaczynać się od rtsp:// albo http://")
            return
        zrodla = self._nagrywarka.zrodla
        identyfikator = bezpieczny_id(self._nowa_nazwa)
        if any(z.id == identyfikator for z in zrodla):
            identyfikator = f"{identyfikator}-{len(zrodla) + 1}"
        zrodla.append(Zrodlo(identyfikator, self._nowa_nazwa, adres))
        zapisz_zrodla(zrodla)
        self._nagrywarka.przeladuj()
        self._na_komunikat(f"Dodane źródło: {self._nowa_nazwa}")
        self._po_zmianie()

    def _po_zmianie(self) -> None:
        self.odswiez()
        self._na_zmiane()
