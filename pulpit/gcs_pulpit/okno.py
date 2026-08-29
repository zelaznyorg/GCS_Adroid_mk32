"""Ekran główny GCS — kafelki aplikacji, sterowane pokrętłem.

Zasady wynikają ze sprzętu, nie z gustu: ekran nie jest dotykowy, a jedynym
pewnym wejściem jest pokrętło (obrót, klik, przytrzymanie).

  * wszystko jest siatką — obrót przesuwa zaznaczenie o jedną pozycję
  * zaznaczenie ma być widać z dwóch metrów: gruba obwódka, nie odcień
  * ⛔ żadnego najeżdżania myszą, menu rozwijanych ani przeciągania
  * mysz działa jako skrót tam, gdzie akurat jest — ale nic od niej nie zależy

⛔ **Z każdej uruchomionej aplikacji musi być powrót samym pokrętłem.** Bez tego
operator zostaje uwięziony w pełnoekranowym oknie bez klawiatury. Stąd dwie rzeczy:
przytrzymanie wywołuje pulpit na wierzch, a dopóki coś chodzi, pierwszym kafelkiem
jest ZAMKNIJ.
"""

from __future__ import annotations

import logging
import os
import signal
import socket
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

# Brak magistrali dostępności na tej malinie zasypuje dziennik ostrzeżeniami.
os.environ.setdefault("GTK_A11Y", "none")

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Gdk", "4.0")
from gi.repository import Gdk, Gio, GLib, Gtk, Pango  # noqa: E402

from . import katalog  # noqa: E402
from . import okna  # noqa: E402
from . import tlo  # noqa: E402
from .klawiatura import Klawiatura  # noqa: E402
from .klawiatura import STYL as STYL_KLAWIATURY  # noqa: E402
from .pilot import STYL as STYL_PILOTA  # noqa: E402
from .mysz import STYL as STYL_MYSZY  # noqa: E402
from .mysz import Mysz  # noqa: E402
from .nagrania import STYL as STYL_NAGRAN  # noqa: E402
from .nagrania import Nagrania, Odtwarzacz, uruchom_mpv  # noqa: E402
from .nagrywanie import Nagrywarka  # noqa: E402
from .nagrywarka import STYL as STYL_NAGRYWARKI  # noqa: E402
from .nagrywarka import EkranNagrywarki  # noqa: E402
from .pilot import Pilot  # noqa: E402
from .siec import STYL as STYL_SIECI  # noqa: E402
from .dodaj import STYL as STYL_DODAWANIA  # noqa: E402
from .dodaj import Dodaj  # noqa: E402
from .siec import Siec  # noqa: E402
from .siec_stan import ObserwatorSieci  # noqa: E402
from .widoki import przewin_do  # noqa: E402
from .wejscie import PANEL, PULPIT, Pokretlo  # noqa: E402

log = logging.getLogger("gcs.pulpit.okno")

STYL = b"""
/* DWIE klasy, nie jedna. Reguly typu (`kafelek-wstecz` i podobne) stoja
   nizej w pliku i przy rownej szczegolowosci wygrywaly - obwodka
   zaznaczenia znikala akurat na kafelku WSTECZ, wiec wygladalo to
   jak brak mozliwosci przejscia na niego. */

window, .tlo { background-color: #0b0f0d; color: #e8e8de; }

.pasek        { padding: 16px 30px; background-color: #121815;
                border-bottom: 1px solid #1e2823; }
.pasek-tytul  { font-size: 22pt; font-weight: 800; color: #ffb000; }
.pasek-info   { font-size: 14pt; color: #8f9a92; }
.pasek-zegar  { font-size: 24pt; font-weight: 700; color: #e8e8de; }

.znacznik        { font-size: 13pt; font-weight: 800; padding: 6px 16px;
                   border-radius: 4px; }
.znacznik-pulpit { color: #08120a; background-color: #50dc64; }
.znacznik-panel  { color: #8f9a92; background-color: #1c2420; }
.znacznik-brak   { color: #150705; background-color: #ff462d; }

button.kafelek {
    background-image: none;
    background-color: #161d19;
    border: 4px solid #232e28;
    border-radius: 12px;
    box-shadow: none;
    padding: 26px;
    min-width: 300px;
    min-height: 170px;
    outline: none;
}
button.kafelek:hover  { background-color: #1c2721; }
button.kafelek:active { background-color: #22302a; }

/* UWAGA: DWIE klasy, nie jedna. Reguly typu (`kafelek-wstecz` i podobne) stoja
   nizej w pliku i przy rownej szczegolowosci wygrywaly - obwodka zaznaczenia
   znikala akurat na kafelku WSTECZ, wiec wygladalo to jak brak mozliwosci
   przejscia na niego. */
button.kafelek.kafelek-wybrany { border-color: #ffb000; background-color: #23302a; }
button.kafelek-nieczynny { background-color: #12100f; border-color: #2a1e1a; }
button.kafelek-zamknij   { background-color: #2a1512; border-color: #5e2a20; }
button.kafelek-grupa { background-color: #1d1a26; border-color: #362f47; }
button.kafelek-grupa.kafelek-wybrany {
    border-color: #c9a8ff; background-color: #2a2438;
}
button.kafelek-grupa .kafelek-nazwa { color: #d9c6ff; }
button.kafelek-wstecz { background-color: #1b2420; border-color: #2b352f; }
button.kafelek-wstecz .kafelek-nazwa { color: #b9c3bb; }
button.kafelek-dodaj { background-color: #17251c; border-color: #274232; }
button.kafelek-dodaj.kafelek-wybrany {
    border-color: #7de08e; background-color: #1e3226;
}
button.kafelek-dodaj .kafelek-nazwa { color: #a9e6b8; }
button.kafelek-nagrania { background-color: #251a20; border-color: #43293a; }
button.kafelek-nagrania.kafelek-wybrany {
    border-color: #ff9ec7; background-color: #33222c;
}
button.kafelek-nagrania .kafelek-nazwa { color: #ffc4dc; }
button.kafelek-siec { background-color: #1a1c2a; border-color: #2b3050; }
button.kafelek-siec.kafelek-wybrany {
    border-color: #8fa8ff; background-color: #232840;
}
button.kafelek-siec .kafelek-nazwa { color: #b8c6ff; }
button.kafelek-pilot { background-color: #1a2416; border-color: #2f4326; }
button.kafelek-pilot.kafelek-wybrany {
    border-color: #a8e06f; background-color: #24331d;
}
button.kafelek-pilot .kafelek-nazwa { color: #c4e8a0; }
button.kafelek-klawiatura { background-color: #15202a; border-color: #223648; }
button.kafelek-klawiatura.kafelek-wybrany {
    border-color: #6fb8ff; background-color: #1b2c3a;
}
button.kafelek-klawiatura .kafelek-nazwa { color: #a8d4ff; }
button.kafelek-zamknij.kafelek-wybrany {
    border-color: #ff8a70; background-color: #391b16;
}

.kafelek-nazwa { font-size: 21pt; font-weight: 800; color: #f2f2e8; }
.kafelek-opis  { font-size: 12pt; color: #97a29a; }
.kafelek-powod { font-size: 12pt; font-weight: 700; color: #ff8a70; }
button.kafelek-nieczynny .kafelek-nazwa { color: #9a948d; }
button.kafelek-zamknij .kafelek-nazwa   { color: #ffb4a0; }

.stopka { padding: 8px 20px; background-color: #121815; font-size: 13pt;
          color: #8f9a92; border-top: 1px solid #1e2823; }
button.belka-ikona {
    background-image: none; background-color: #1b2420; border: 2px solid #26302a;
    border-radius: 8px; box-shadow: none; min-width: 56px; min-height: 42px;
    font-size: 17pt; color: #b9c3bb; padding: 0;
}
button.belka-ikona:hover { background-color: #23302a; color: #f2f2e8; }
button.belka-rec { min-width: 110px; font-size: 13pt; font-weight: 800;
                   color: #ff8a70; }
button.belka-rec-pali { background-color: #7a1c12; color: #ffffff;
                        border-color: #ff4a2d; }
button.kafelek-rec { background-color: #2a1512; border-color: #5e2a20; }
button.kafelek-rec.kafelek-wybrany {
    border-color: #ff8a70; background-color: #391b16;
}
button.kafelek-rec .kafelek-nazwa { color: #ffb4a0; }
button.kafelek-rec-pali { background-color: #7a1c12; border-color: #ff4a2d; }
button.kafelek-rec-pali .kafelek-nazwa { color: #ffffff; }
.pusto  { font-size: 17pt; color: #8f9a92; padding: 70px; }
"""


@dataclass
class Kafelek:
    nazwa: str
    opis: str
    akcja: Callable[[], None]
    dostepny: bool = True
    powod: str = ""
    styl: str = ""


class Pulpit(Gtk.ApplicationWindow):
    KOLUMNY = 3

    def __init__(self, aplikacja: Gtk.Application) -> None:
        super().__init__(application=aplikacja, title="GCS")
        self.set_default_size(1280, 720)
        self.add_css_class("tlo")

        self._aplikacje: list[katalog.Aplikacja] = []
        self._uruchomione: dict[str, subprocess.Popen] = {}
        self._kafelki: list[Kafelek] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0
        # Otwarta grupa kafelków; None znaczy ekran główny.
        self._grupa: str | None = None
        # Jedna nakładka naraz: klawiatura albo pilot. Pokrętło idzie do niej,
        # a nie do kafelków, dopóki jest otwarta.
        self._nakladka: Klawiatura | Pilot | Mysz | None = None
        self._ognisko = PANEL
        self._most_zywy = False

        self._zbuduj()
        self._odswiez_katalog()
        self._obserwuj_katalogi()

        GLib.timeout_add_seconds(1, self._tyknij_zegar)
        GLib.timeout_add_seconds(1, self._odswiez_nagrywanie)
        GLib.timeout_add_seconds(3, self._sprzatnij_uruchomione)
        GLib.timeout_add_seconds(15, self._przelicz_dostepnosc)

        self._pokretlo = Pokretlo(
            na_obrot=lambda k: GLib.idle_add(self._na_obrot, k),
            na_klik=lambda: GLib.idle_add(self._na_klik),
            na_przytrzymanie=lambda: GLib.idle_add(self._na_przytrzymanie),
            na_ognisko=lambda g: GLib.idle_add(self._na_ognisko, g),
            na_lacze=lambda z: GLib.idle_add(self._na_lacze, z),
            na_polecenie=lambda co: GLib.idle_add(self._na_polecenie, co),
        )
        self._pokretlo.start()

    # ---- budowa okna -----------------------------------------------------

    def _zbuduj(self) -> None:
        pion = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.set_child(pion)

        pasek = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=18)
        pasek.add_css_class("pasek")
        tytul = Gtk.Label(label="GCS", xalign=0)
        tytul.add_css_class("pasek-tytul")
        adres = Gtk.Label(label=self._adres(), xalign=0)
        adres.add_css_class("pasek-info")
        self._znacznik = Gtk.Label(label="POKRĘTŁO: PANEL")
        self._znacznik.add_css_class("znacznik")
        self._znacznik.add_css_class("znacznik-panel")
        self._zegar = Gtk.Label(label="")
        self._zegar.add_css_class("pasek-zegar")

        pasek.append(tytul)
        pasek.append(adres)
        rozpychacz = Gtk.Box()
        rozpychacz.set_hexpand(True)
        pasek.append(rozpychacz)
        pasek.append(self._znacznik)
        pasek.append(self._zegar)
        pion.append(pasek)

        self._siatka = Gtk.Grid(
            row_spacing=18, column_spacing=18, margin_top=26,
            margin_bottom=26, margin_start=26, margin_end=26,
        )
        self._siatka.set_halign(Gtk.Align.CENTER)
        self._siatka.set_valign(Gtk.Align.CENTER)
        self._przewijak = Gtk.ScrolledWindow()
        self._przewijak.set_vexpand(True)
        self._przewijak.set_child(self._siatka)

        # Dwa widoki w jednym oknie: kafelki i SIEĆ. Pokrętło trafia do tego,
        # który jest na wierzchu — patrz `_widok_aktywny`.
        self._stos = Gtk.Stack()
        self._stos.set_vexpand(True)
        self._stos.add_named(self._przewijak, "kafelki")
        pion.append(self._stos)
        self._siec: Siec | None = None
        self._dodaj: Dodaj | None = None
        self._nagrania: Nagrania | None = None
        self._mpv = None
        # Nagrywanie z przycisku — nasz odpowiednik rejestratora CVBS,
        # ktory u Toma startuje wylacznie przyciskiem na GPIO 21.
        self._nagrywarka = Nagrywarka()
        # Stan sieci zbierany w tle — panel GC9A01 dostaje go mostem.
        self._siec_stan = ObserwatorSieci()
        self._ekran_nagrywarki = None

        # Belka dolna: podpowiedź po lewej, narzędzia po prawej jako ikony.
        # Klawiatura i mysz są potrzebne rzadko, a kafelki zabierały im miejsce
        # w siatce — tu są zawsze pod ręką i nic nie zasłaniają.
        belka = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        belka.add_css_class("stopka")
        self._stopka = Gtk.Label(label="", xalign=0)
        self._stopka.set_hexpand(True)
        self._stopka.set_ellipsize(Pango.EllipsizeMode.END)
        belka.append(self._stopka)
        # Nagrywanie ma byc pod jednym nacisnieciem, wiec siedzi na belce,
        # a nie w zadnym menu. Czerwone, gdy pisze — widac z drugiego konca stolu.
        self._przycisk_rec = Gtk.Button(label="⏺ REC")
        self._przycisk_rec.add_css_class("belka-ikona")
        self._przycisk_rec.add_css_class("belka-rec")
        self._przycisk_rec.set_can_focus(False)
        self._przycisk_rec.set_focus_on_click(False)
        self._przycisk_rec.set_tooltip_text("Nagrywanie obrazu z glowicy")
        self._przycisk_rec.connect("clicked", lambda *_: self._przelacz_nagrywanie())
        belka.append(self._przycisk_rec)

        for znak, podpowiedz, akcja in (
            ("⌨", "Klawiatura ekranowa", lambda: self._otworz_klawiature()),
            ("🖱", "Mysz sterowana pokrętłem", lambda: self._otworz_mysz()),
        ):
            przycisk = Gtk.Button(label=znak)
            przycisk.add_css_class("belka-ikona")
            przycisk.set_can_focus(False)
            przycisk.set_focus_on_click(False)
            przycisk.set_tooltip_text(podpowiedz)
            przycisk.connect("clicked", lambda *_, a=akcja: a())
            belka.append(przycisk)
        pion.append(belka)

        # Klawiatura jako droga zapasowa — przydaje się przy pracy zdalnej.
        klawisze = Gtk.EventControllerKey()
        # Faza przechwytywania: przyciski nie przyjmuja juz ogniska, wiec bez tego
        # zdarzenia klawiszy nie mialyby do kogo trafic.
        klawisze.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        klawisze.connect("key-pressed", self._na_klawisz)
        self.add_controller(klawisze)

        self._tyknij_zegar()

    @staticmethod
    def _adres() -> str:
        nazwa = socket.gethostname()
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("192.168.88.1", 1))
                return f"{nazwa} · {s.getsockname()[0]}"
        except OSError:
            return nazwa

    # ---- katalog ---------------------------------------------------------

    def _obserwuj_katalogi(self) -> None:
        """Wrzucenie pliku ma dokładać kafelek od razu, bez restartu."""
        self._obserwatorzy = []
        for sciezka in katalog.KATALOGI:
            if not sciezka.is_dir():
                continue
            try:
                monitor = Gio.File.new_for_path(str(sciezka)).monitor_directory(
                    Gio.FileMonitorFlags.NONE, None
                )
            except GLib.Error:
                log.warning("Nie mogę obserwować %s", sciezka)
                continue
            monitor.connect("changed", lambda *_: self._odswiez_katalog())
            self._obserwatorzy.append(monitor)
            log.info("Obserwuję %s", sciezka)

    def _odswiez_katalog(self) -> bool:
        self._aplikacje = katalog.wczytaj()
        self._przeloz_kafelki()
        return False

    def _przelicz_dostepnosc(self) -> bool:
        for aplikacja in self._aplikacje:
            aplikacja.dostepna, aplikacja.powod = katalog.sprawdz(aplikacja)
        self._przeloz_kafelki()
        return True

    def _przeloz_kafelki(self) -> None:
        zaznaczony = (
            self._kafelki[self._wybrany].nazwa
            if 0 <= self._wybrany < len(self._kafelki)
            else None
        )
        self._kafelki = self._zbierz_kafelki()
        self._wybrany = 0
        if zaznaczony is not None:
            for i, kafelek in enumerate(self._kafelki):
                if kafelek.nazwa == zaznaczony:
                    self._wybrany = i
                    break
        self._przebuduj_siatke()
        self._odmaluj_zaznaczenie()
        self._opisz_stopke()

    def _wbudowane(self) -> list[tuple[str, str, object, str, str]]:
        """(nazwa, opis, akcja, styl, grupa) — pozycje własne pulpitu."""
        return [
            ("🌐 SIEĆ", "Wi-Fi, sieci ukryte, łączność z dronem",
             self._otworz_siec, "kafelek-siec", "SYSTEM"),
            ("⌨ KLAWIATURA", "Pisanie pokrętłem do okna pod spodem",
             lambda: self._otworz_klawiature(), "kafelek-klawiatura", "SYSTEM"),
            ("➕ DODAJ APLIKACJĘ", "Zbuduj kafelek z tego, co zainstalowane",
             self._otworz_dodaj, "kafelek-dodaj", "SYSTEM"),
            # Bez grupy: to jest narzędzie robocze, nie ustawienie systemu.
            ("🎞 NAGRANIA", "Archiwum obrazu z głowicy ZR30",
             self._otworz_nagrania, "kafelek-nagrania", ""),
            ("⏺ NAGRYWARKA", "Źródła IP, konfiguracja i zapis",
             self._otworz_nagrywarke, "kafelek-rec", ""),
        ]

    def grupy(self) -> list[str]:
        """Nazwy grup, które faktycznie istnieją — do wyboru przy dodawaniu."""
        nazwy = {a.grupa.strip().upper() for a in self._aplikacje if a.grupa.strip()}
        nazwy.update(g for *_, g in self._wbudowane() if g)
        return sorted(nazwy)

    def _zbierz_kafelki(self) -> list[Kafelek]:
        kafelki: list[Kafelek] = []

        if self._grupa is not None:
            kafelki.append(
                Kafelek(
                    nazwa="◀ WSTECZ",
                    opis=f"Powrót z grupy {self._grupa}",
                    akcja=self._zamknij_grupe,
                    styl="kafelek-wstecz",
                )
            )
            for nazwa, opis, akcja, styl, grupa in self._wbudowane():
                if grupa == self._grupa:
                    kafelki.append(Kafelek(nazwa=nazwa, opis=opis, akcja=akcja, styl=styl))
            for aplikacja in self._aplikacje:
                if aplikacja.grupa.strip().upper() == self._grupa:
                    kafelki.append(self._kafelek_aplikacji(aplikacja))
            return kafelki

        # Dopóki cokolwiek chodzi, zamknięcie musi być pierwsze pod ręką —
        # to jedyna droga wyjścia, gdy aplikacja zajmuje cały ekran.
        for nazwa in list(self._uruchomione):
            kafelki.append(
                Kafelek(
                    nazwa=f"✕ ZAMKNIJ {nazwa}",
                    opis="Zatrzymuje uruchomioną aplikację i wraca tutaj",
                    akcja=lambda n=nazwa: self._zamknij(n),
                    styl="kafelek-zamknij",
                )
            )
        if self._uruchomione:
            kafelki.append(
                Kafelek(
                    nazwa="🕹 STERUJ APLIKACJĄ",
                    opis="Pokrętło jako strzałki, ENTER, TAB i ESC",
                    akcja=self._pilot_dla_pierwszej,
                    styl="kafelek-pilot",
                )
            )

        # Aplikacje bez grupy stoją wprost na pulpicie; reszta chowa się w grupach.
        for aplikacja in self._aplikacje:
            if not aplikacja.grupa.strip():
                kafelki.append(self._kafelek_aplikacji(aplikacja))
        for nazwa, opis, akcja, styl, grupa in self._wbudowane():
            if not grupa:
                if styl == "kafelek-rec" and self._nagrywarka.nagrywa:
                    opis = f"NAGRYWA {self._nagrywarka.opis_zbiorczy()}"
                    styl = "kafelek-rec kafelek-rec-pali"
                kafelki.append(Kafelek(nazwa=nazwa, opis=opis, akcja=akcja, styl=styl))

        for grupa in self.grupy():
            ile = sum(1 for a in self._aplikacje if a.grupa.strip().upper() == grupa)
            ile += sum(1 for *_, g in self._wbudowane() if g == grupa)
            kafelki.append(
                Kafelek(
                    nazwa=f"📁 {grupa}",
                    opis=f"{ile} pozycji",
                    akcja=lambda g=grupa: self._otworz_grupe(g),
                    styl="kafelek-grupa",
                )
            )
        return kafelki

    def _kafelek_aplikacji(self, aplikacja: katalog.Aplikacja) -> Kafelek:
        return Kafelek(
            nazwa=aplikacja.nazwa,
            opis=aplikacja.opis,
            akcja=lambda a=aplikacja: self._uruchom(a),
            dostepny=aplikacja.dostepna,
            powod=aplikacja.powod,
        )

    def _otworz_grupe(self, grupa: str) -> None:
        self._grupa = grupa
        self._wybrany = 0
        self._przeloz_kafelki()

    def _zamknij_grupe(self) -> None:
        self._grupa = None
        self._wybrany = 0
        self._przeloz_kafelki()

    def _przebuduj_siatke(self) -> None:
        while (dziecko := self._siatka.get_first_child()) is not None:
            self._siatka.remove(dziecko)
        self._widgety = []

        if not self._kafelki:
            pusto = Gtk.Label(
                label="Brak aplikacji.\nWrzuć plik do /etc/gcs/aplikacje.d/",
                justify=Gtk.Justification.CENTER,
            )
            pusto.add_css_class("pusto")
            self._siatka.attach(pusto, 0, 0, 1, 1)
            return

        for i, kafelek in enumerate(self._kafelki):
            widget = self._zbuduj_widget(i, kafelek)
            self._siatka.attach(widget, i % self.KOLUMNY, i // self.KOLUMNY, 1, 1)
            self._widgety.append(widget)

    def _zbuduj_widget(self, indeks: int, kafelek: Kafelek) -> Gtk.Widget:
        przycisk = Gtk.Button()
        przycisk.add_css_class("kafelek")
        # ⛔ Nasze przyciski NIE MOGĄ przyjmować ogniska GTK. Zaznaczenie
        # prowadzimy sami, a gdy przycisk ma ognisko, GTK aktywuje go
        # dodatkowo klawiszem — zatwierdzenie wykonywało się wtedy DWA RAZY
        # i wchodzenie w grupę kończyło się otwarciem sąsiedniej pozycji.
        przycisk.set_can_focus(False)
        przycisk.set_focus_on_click(False)
        if kafelek.styl:
            przycisk.add_css_class(kafelek.styl)
        if not kafelek.dostepny:
            przycisk.add_css_class("kafelek-nieczynny")
        przycisk.connect("clicked", lambda *_: self._wykonaj(indeks))

        pion = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        nazwa = Gtk.Label(label=kafelek.nazwa, xalign=0)
        nazwa.add_css_class("kafelek-nazwa")
        nazwa.set_wrap(True)
        pion.append(nazwa)

        if kafelek.opis:
            opis = Gtk.Label(label=kafelek.opis, xalign=0)
            opis.add_css_class("kafelek-opis")
            opis.set_wrap(True)
            pion.append(opis)

        if not kafelek.dostepny:
            powod = Gtk.Label(label=f"⛔ {kafelek.powod}", xalign=0)
            powod.add_css_class("kafelek-powod")
            powod.set_wrap(True)
            pion.append(powod)

        przycisk.set_child(pion)
        return przycisk

    def _odmaluj_zaznaczenie(self) -> None:
        for i, widget in enumerate(self._widgety):
            if i == self._wybrany:
                widget.add_css_class("kafelek-wybrany")
            else:
                widget.remove_css_class("kafelek-wybrany")
        # Siatka też potrafi urosnąć ponad ekran — widok idzie za zaznaczeniem.
        if 0 <= self._wybrany < len(self._widgety):
            przewin_do(self._przewijak, self._siatka, self._widgety[self._wybrany])

    # ---- pokrętło --------------------------------------------------------

    def _na_obrot(self, kierunek: int) -> bool:
        if self._nakladka is not None:
            self._nakladka.obrot(kierunek)
            return False
        if self._widok_aktywny() is not None:
            self._widok_aktywny().obrot(kierunek)
            return False
        if self._zaslonięty():
            self._przejmij_zaslonięty()
            return False
        if self._kafelki:
            self._wybrany = (self._wybrany + kierunek) % len(self._kafelki)
            self._odmaluj_zaznaczenie()
        return False

    def _na_klik(self) -> bool:
        if self._nakladka is not None:
            self._nakladka.klik()
            return False
        if self._widok_aktywny() is not None:
            self._widok_aktywny().klik()
            return False
        if self._zaslonięty():
            self._przejmij_zaslonięty()
            return False
        self._wykonaj(self._wybrany)
        return False

    def _na_przytrzymanie(self) -> bool:
        """Przytrzymanie zawsze znaczy „o krok wstecz", nigdy nic innego.

        ⛔ Kolejność ma znaczenie i była kiedyś źródłem zacięcia: wewnątrz grupy
        przytrzymanie oddawało pokrętło panelowi zamiast cofnąć, więc operator
        zostawał w grupie bez wyjścia. Teraz wychodzenie idzie warstwami —
        nakładka, potem widok, potem grupa, i dopiero z gołego pulpitu pokrętło
        wraca do panelu.
        """
        if self._nakladka is not None:
            self._nakladka.przytrzymanie()
            return False
        if self._widok_aktywny() is not None:
            self._widok_aktywny().przytrzymanie()
            return False
        if self._grupa is not None:
            self._zamknij_grupe()
            return False
        if not self.is_active():
            self.present()
            self._powiedz(
                "Pulpit na wierzchu. Przytrzymaj jeszcze raz, aby oddać pokrętło panelowi."
            )
        else:
            self._pokretlo.oddaj_panelowi()
        return False

    def _zaslonięty(self) -> bool:
        """Czy pulpit jest pod czymś — mimo że formalnie „widoczny".

        ⛔ To był realny błąd, nie teoria: aplikacja DRON 15 wchodzi na pełny ekran
        i przykrywa pulpit. Pokrętło szło dalej do kafelków, więc obrót przesuwał
        zaznaczenie, a klik **uruchamiał kolejne aplikacje po omacku** — operator
        widział tylko to, że coś się dzieje.
        """
        return self._nakladka is None and not self.is_active()

    def _przejmij_zaslonięty(self) -> None:
        """Zasłonięty pulpit wychodzi na wierzch — i nic poza tym.

        ⛔ Kusiło, żeby od razu otwierać pilota. Byłby to jednak błąd przy
        aplikacjach, które **same obsługują pokrętło** (stacja DRON 15): pasek
        pilota przykryłby ich własne przyciski, a zdarzenia i tak są im potrzebne.
        Pierwsze pokręcenie tylko pokazuje pulpit; pilota wybiera się świadomie
        kafelkiem STERUJ APLIKACJĄ.
        """
        log.info("Pulpit był zasłonięty — pokazuję go zamiast działać po omacku")
        self.set_visible(True)
        self.present()
        self._powiedz_trwale("Pulpit był zasłonięty — kręć dalej")

    def _na_ognisko(self, gdzie: str) -> bool:
        self._ognisko = gdzie
        for klasa in ("znacznik-pulpit", "znacznik-panel", "znacznik-brak"):
            self._znacznik.remove_css_class(klasa)
        if gdzie == PULPIT:
            self._znacznik.set_text("POKRĘTŁO: PULPIT")
            self._znacznik.add_css_class("znacznik-pulpit")
            self.present()
        else:
            self._znacznik.set_text("POKRĘTŁO: PANEL")
            self._znacznik.add_css_class("znacznik-panel")
            # ⛔ Bez pokrętła każda nasza nakładka jest martwa — nie dostaje ani
            # obrotu, ani kliku. Zostawiona na ekranie tylko zasłaniałaby aplikację,
            # która właśnie przejęła sterowanie.
            if self._nakladka is not None:
                log.info("Straciliśmy pokrętło — zamykam nakładkę")
                self._zamknij_nakladke()
        self._opisz_stopke()
        return False

    def _na_polecenie(self, co: str) -> bool:
        """Polecenie z okrągłego panelu. Działa też wtedy, gdy pokrętło jest przy nim."""
        if co == "nagrywanie":
            self._przelacz_nagrywanie()
        else:
            log.warning("Nieznane polecenie z panelu: %s", co)
        return False

    def _na_lacze(self, zywe: bool) -> bool:
        self._most_zywy = zywe
        if not zywe:
            for klasa in ("znacznik-pulpit", "znacznik-panel"):
                self._znacznik.remove_css_class(klasa)
            self._znacznik.set_text("BRAK POKRĘTŁA")
            self._znacznik.add_css_class("znacznik-brak")
        self._opisz_stopke()
        return False

    # ---- działanie -------------------------------------------------------

    def _wykonaj(self, indeks: int) -> None:
        if not (0 <= indeks < len(self._kafelki)):
            return
        self._wybrany = indeks
        self._odmaluj_zaznaczenie()
        kafelek = self._kafelki[indeks]
        if not kafelek.dostepny:
            self._powiedz(f"{kafelek.nazwa}: {kafelek.powod}")
            return
        kafelek.akcja()

    def _uruchom(self, aplikacja: katalog.Aplikacja) -> None:
        if aplikacja.nazwa in self._uruchomione:
            self._powiedz(f"{aplikacja.nazwa} już działa")
            return
        # ⛔ LD_PRELOAD jest nasze, do nakladki klawiatury. Wpychanie
        # gtk4-layer-shell do Chromium czy Waydroida nie ma sensu i moze zaszkodzic.
        srodowisko = os.environ.copy()
        srodowisko.pop("LD_PRELOAD", None)
        try:
            proces = subprocess.Popen(
                aplikacja.polecenie,
                start_new_session=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                env=srodowisko,
            )
        except OSError as blad:
            log.warning("Nie udało się uruchomić %s: %s", aplikacja.nazwa, blad)
            self._powiedz(f"{aplikacja.nazwa}: nie ruszyła — {blad}")
            return
        self._uruchomione[aplikacja.nazwa] = proces
        log.info("Uruchomione: %s (%s)", aplikacja.nazwa, " ".join(aplikacja.polecenie))
        if aplikacja.pelny_ekran:
            # ⛔ Do 2026-08-29 ta flaga nie robiła NIC. Firefox wstawał w małym
            # oknie, a dookoła widać było wszystko, co leży niżej.
            okna.rozciagnij(aplikacja.okno or self._zgadnij_okno(aplikacja), aplikacja.nazwa)
        self._powiedz(f"Uruchamiam: {aplikacja.nazwa}")
        self._przeloz_kafelki()
        if aplikacja.pokretlo == "wlasne":
            # Aplikacja obsługuje pokrętło SAMA (stacja DRON 15 jest klientem tego
            # samego mostu), więc pilot jest tu szkodliwy: przykryłby jej przyciski.
            #
            # ⛔ NIE przekazujemy jej pokrętła z góry. Sprawdzone w jej kodzie
            # (`server/index.mjs`, `GET /api/pokretlo`): stacja bierze ognisko
            # **dopiero, gdy przeglądarka otworzy strumień**, a zdarzenia trafiają
            # do pola `trzymajacy`. Serwer z ogniskiem, ale bez strony, wyrzuca je
            # do kosza — i wtedy nie działa NIC, bo panel też już ich nie widzi.
            #
            # Zamiast tego dajemy operatorowi to, czego naprawdę potrzebuje:
            # wskaźnik, żeby mógł nacisnąć w aplikacji jej własny przycisk POKRĘTŁO.
            self._zamknij_nakladke()
            self._schowaj_pulpit()
            mysz = Mysz(
                na_pilota=lambda: self._otworz_pilota(aplikacja.nazwa),
                na_pulpit=self._wroc_na_pulpit,
                na_klawiature=lambda: self._otworz_klawiature(),
            )
            self._nakladka = mysz
            mysz.present()
            self._powiedz_trwale(
                f"{aplikacja.nazwa} obsługuje pokrętło sama — kursorem naciśnij "
                f"w niej POKRĘTŁO, a przejmie sterowanie"
            )
            return
        # ⛔ Pilot otwiera się OD RAZU, nie po trzech sekundach. Ta przerwa była
        # dziurą, w której pokrętło sterowało jeszcze kafelkami, a aplikacja już
        # wchodziła na wierzch — czyli dokładnie sytuacją, w której klik uruchamiał
        # coś, czego nikt nie chciał. Klawisze wysłane, zanim aplikacja wstanie,
        # po prostu przepadają i nikomu nie szkodzą.
        self._otworz_pilota(aplikacja.nazwa)

    @staticmethod
    def _zgadnij_okno(aplikacja: katalog.Aplikacja) -> str:
        """Nazwa programu bywa dobrym app_id — a gdy nie jest, pomaga
        dopasowanie po zawieraniu (Waydroid melduje `waydroid.com.…`)."""
        if not aplikacja.polecenie:
            return ""
        return os.path.basename(aplikacja.polecenie[0]).removesuffix(".sh")

    def _zamknij(self, nazwa: str) -> None:
        proces = self._uruchomione.get(nazwa)
        if proces is None:
            return
        try:
            # Aplikacje startują w nowej sesji, więc gasimy całą grupę —
            # inaczej Chromium zostawia po sobie procesy potomne.
            os.killpg(os.getpgid(proces.pid), signal.SIGTERM)
        except OSError:
            try:
                proces.terminate()
            except OSError:
                pass
        log.info("Zamykam: %s", nazwa)
        self._powiedz(f"Zamykam: {nazwa}")
        self.present()

    # ---- nakładki: klawiatura i pilot ------------------------------------

    def _zamknij_nakladke(self) -> None:
        nakladka = self._nakladka
        self._nakladka = None
        if nakladka is not None:
            nakladka.destroy()

    def _wroc_na_pulpit(self) -> None:
        """⛔ Jedyne miejsce, w którym pulpit wraca na ekran. Wcześniej brakowało
        tego kroku po zamknięciu klawiatury i wyglądało to jak zniknięcie GCS."""
        self._zamknij_nakladke()
        self.set_visible(True)
        self.present()
        self._opisz_stopke()

    def _schowaj_pulpit(self) -> None:
        """Pulpit musi zejść z drogi, bo inaczej to ON jest oknem aktywnym
        i klawisze trafiają do niego zamiast do aplikacji."""
        self.set_visible(False)

    def _otworz_klawiature(self, *, wroc_do_pilota: str = "") -> None:
        self._zamknij_nakladke()
        self._schowaj_pulpit()

        def po_zamknieciu() -> None:
            self._nakladka = None
            if wroc_do_pilota:
                self._otworz_pilota(wroc_do_pilota)
            else:
                self._wroc_na_pulpit()

        klawiatura = Klawiatura(
            opis="Obrót wybiera, klik wciska, przytrzymanie anuluje. "
            "Tekst idzie do okna pod spodem dopiero po WYŚLIJ.",
            na_zamkniecie=po_zamknieciu,
        )
        self._nakladka = klawiatura
        klawiatura.present()
        log.info("Klawiatura otwarta")

    def _otworz_pilota(self, nazwa: str = "") -> None:
        self._zamknij_nakladke()
        self._schowaj_pulpit()

        zamknij = None
        if nazwa and nazwa in self._uruchomione:
            def zamknij() -> None:  # noqa: F811
                self._zamknij(nazwa)
                self._wroc_na_pulpit()

        pilot = Pilot(
            nazwa_aplikacji=nazwa,
            na_pulpit=self._wroc_na_pulpit,
            na_klawiature=lambda: self._otworz_klawiature(wroc_do_pilota=nazwa),
            na_mysz=lambda: self._otworz_mysz(nazwa),
            na_zamkniecie_aplikacji=zamknij,
        )
        self._nakladka = pilot
        pilot.present()
        log.info("Pilot otwarty%s", f" dla {nazwa}" if nazwa else "")

    # ---- ekran SIEĆ ------------------------------------------------------

    def _widok_aktywny(self):
        """Który widok w stosie ma teraz pokrętło. None = siatka kafelków."""
        nazwa = self._stos.get_visible_child_name()
        if nazwa == "siec":
            return self._siec
        if nazwa == "dodaj":
            return self._dodaj
        if nazwa == "nagrania":
            return self._nagrania
        if nazwa == "nagrywarka":
            return self._ekran_nagrywarki
        return None

    def _otworz_nagrywarke(self) -> None:
        if self._ekran_nagrywarki is None:
            self._ekran_nagrywarki = EkranNagrywarki(
                nagrywarka=self._nagrywarka,
                na_wstecz=self._zamknij_nagrywarke,
                na_tekst=self._zapytaj_o_tekst,
                na_komunikat=self._powiedz_trwale,
                na_zmiane=self._przeloz_kafelki,
            )
            self._stos.add_named(self._ekran_nagrywarki, "nagrywarka")
        else:
            self._ekran_nagrywarki.odswiez()
        self._stos.set_visible_child_name("nagrywarka")

    def _zamknij_nagrywarke(self) -> None:
        self._stos.set_visible_child_name("kafelki")
        self._opisz_stopke()

    # ---- nagrania --------------------------------------------------------

    def _otworz_nagrania(self) -> None:
        if self._nagrania is None:
            self._nagrania = Nagrania(
                na_wstecz=self._zamknij_nagrania,
                na_komunikat=self._powiedz_trwale,
                na_odtwarzanie=self._odtworz_nagranie,
            )
            self._stos.add_named(self._nagrania, "nagrania")
        else:
            self._nagrania.odswiez()
        self._stos.set_visible_child_name("nagrania")

    def _zamknij_nagrania(self) -> None:
        self._stos.set_visible_child_name("kafelki")
        self._opisz_stopke()

    def _odtworz_nagranie(self, sciezka: str) -> None:
        """Pulpit schodzi z drogi, mpv gra na pełnym ekranie, a nakładka
        odtwarzacza zostaje na wierzchu — pokrętło steruje przez gniazdo mpv."""
        self._zamknij_nakladke()
        self._mpv = uruchom_mpv(sciezka)
        if self._mpv is None:
            self._powiedz_trwale("Nie udało się uruchomić mpv")
            return
        self._schowaj_pulpit()
        odtwarzacz = Odtwarzacz(
            tytul=sciezka.rsplit("/", 1)[-1],
            na_koniec=self._koniec_odtwarzania,
        )
        self._nakladka = odtwarzacz
        odtwarzacz.present()

    def _koniec_odtwarzania(self) -> None:
        if self._mpv is not None:
            try:
                self._mpv.terminate()
            except OSError:
                pass
            self._mpv = None
        # Nagrywanie z przycisku — nasz odpowiednik rejestratora CVBS,
        # ktory u Toma startuje wylacznie przyciskiem na GPIO 21.
        self._nagrywarka = Nagrywarka()
        # Stan sieci zbierany w tle — panel GC9A01 dostaje go mostem.
        self._siec_stan = ObserwatorSieci()
        self._ekran_nagrywarki = None
        self._zamknij_nakladke()
        self.set_visible(True)
        self.present()
        self._stos.set_visible_child_name("nagrania")
        self._opisz_stopke()

    def _otworz_dodaj(self) -> None:
        if self._dodaj is None:
            self._dodaj = Dodaj(
                na_wstecz=self._zamknij_dodaj,
                na_tekst=self._zapytaj_o_tekst,
                na_komunikat=self._powiedz_trwale,
                na_zmiane=self._odswiez_katalog,
                grupy=self.grupy,
            )
            self._stos.add_named(self._dodaj, "dodaj")
        else:
            self._dodaj.odswiez()
        self._stos.set_visible_child_name("dodaj")

    def _zamknij_dodaj(self) -> None:
        self._stos.set_visible_child_name("kafelki")
        self._opisz_stopke()

    def _otworz_siec(self) -> None:
        if self._siec is None:
            self._siec = Siec(
                na_wstecz=self._zamknij_siec,
                na_tekst=self._zapytaj_o_tekst,
                na_komunikat=self._powiedz_trwale,
                wan=lambda: self._siec_stan.stan,
            )
            self._stos.add_named(self._siec, "siec")
        else:
            self._siec.odswiez()
        self._stos.set_visible_child_name("siec")

    def _zamknij_siec(self) -> None:
        self._stos.set_visible_child_name("kafelki")
        self._opisz_stopke()

    def _zapytaj_o_tekst(self, opis: str, oddaj: Callable[[str], None]) -> None:
        """Tekst zbiera nasza klawiatura — do własnego pola, nie przez `wtype`.

        ⚠ Odpowiedź może otworzyć KOLEJNĄ klawiaturę (nazwa sieci → hasło).
        Dlatego wynik oddajemy dopiero po zamknięciu tej, a sprzątanie sprawdza
        tożsamość okna — inaczej zamykana klawiatura skasowałaby swoją następczynię.
        """
        self._zamknij_nakladke()
        odpowiedz: list[str] = []

        def po_zamknieciu(ta=None) -> None:
            if self._nakladka is ta:
                self._nakladka = None
            if odpowiedz:
                GLib.idle_add(lambda: (oddaj(odpowiedz[0]), False)[1])

        klawiatura = Klawiatura(
            opis=f"{opis} — obrót wybiera, klik wciska, WYŚLIJ zatwierdza",
            na_gotowe=odpowiedz.append,
            na_zamkniecie=lambda: po_zamknieciu(klawiatura),
        )
        self._nakladka = klawiatura
        klawiatura.present()

    def _powiedz_trwale(self, tekst: str) -> None:
        self._stopka.set_text(tekst)

    def _otworz_mysz(self, nazwa: str = "") -> None:
        """Dla aplikacji, których klawiszami obsłużyć się nie da — a takich jest
        większość zrobionych pod dotyk i mysz."""
        self._zamknij_nakladke()
        self._schowaj_pulpit()
        mysz = Mysz(
            na_pilota=lambda: self._otworz_pilota(nazwa),
            na_pulpit=self._wroc_na_pulpit,
            na_klawiature=lambda: self._otworz_klawiature(wroc_do_pilota=nazwa),
        )
        self._nakladka = mysz
        mysz.present()
        log.info("Mysz otwarta%s", f" dla {nazwa}" if nazwa else "")

    def _pilot_dla_pierwszej(self) -> None:
        nazwa = next(iter(self._uruchomione), "")
        self._otworz_pilota(nazwa)

    def _sprzatnij_uruchomione(self) -> bool:
        """Aplikacja zamknięta własnym krzyżykiem też ma znikać z listy."""
        odeszly = [n for n, p in self._uruchomione.items() if p.poll() is not None]
        for nazwa in odeszly:
            del self._uruchomione[nazwa]
            log.info("Zakończyła się: %s", nazwa)
        if odeszly:
            self._przeloz_kafelki()
        return True

    def _powiedz(self, tekst: str) -> None:
        self._stopka.set_text(tekst)
        GLib.timeout_add_seconds(6, self._opisz_stopke)

    def _opisz_stopke(self) -> bool:
        if not self._most_zywy:
            tekst = "Most pokrętła nie odpowiada — sprawdź usługę pi5-control-panel"
        elif self._ognisko == PULPIT:
            tekst = (
                "Obrót: wybór  ·  Klik: uruchom  ·  "
                "Przytrzymaj: pulpit na wierzch, drugi raz: oddaj pokrętło"
            )
        else:
            tekst = (
                "Pokrętło jest przy panelu — na okrągłym ekranie wybierz "
                "PULPIT GCS i kliknij"
            )
        self._stopka.set_text(tekst)
        return False

    def _przelacz_nagrywanie(self) -> None:
        komunikat = self._nagrywarka.przelacz_wszystkie()
        self._powiedz(komunikat)
        self._odswiez_nagrywanie()
        # Kafelek NAGRYWAJ zmienia opis, a lista nagrań moze miec nowa pozycje.
        self._przeloz_kafelki()

    def _odswiez_nagrywanie(self) -> bool:
        # Rejestrator sam melduje, gdy kamera nie odpowiada albo ffmpeg padl.
        if komunikat := self._nagrywarka.sprawdz():
            self._powiedz(komunikat)
            self._przeloz_kafelki()
            if self._ekran_nagrywarki is not None:
                self._ekran_nagrywarki.odswiez()
        elif (
            self._ekran_nagrywarki is not None
            and self._stos.get_visible_child_name() == "nagrywarka"
        ):
            # Czasy i rozmiary na ekranie maja isc do przodu same.
            self._ekran_nagrywarki.odswiez()
        nagrywa = self._nagrywarka.nagrywa
        if nagrywa:
            self._przycisk_rec.set_label(f"⏺ {self._nagrywarka.opis_zbiorczy()}")
            self._przycisk_rec.add_css_class("belka-rec-pali")
        else:
            self._przycisk_rec.set_label("⏺ REC")
            self._przycisk_rec.remove_css_class("belka-rec-pali")
        # Okrągły panel pokazuje stan nagrywania i sieci — musi go od nas dostać.
        self._pokretlo.zglos_stan(nagrywa, self._nagrywarka.opis_zbiorczy())
        self._pokretlo.zglos_siec(self._siec_stan.stan.jako_slownik())
        return True

    def _tyknij_zegar(self) -> bool:
        self._zegar.set_text(datetime.now().strftime("%H:%M"))
        return True

    def _na_klawisz(self, _kontroler, klawisz, _kod, _stan) -> bool:
        if klawisz in (Gdk.KEY_Right, Gdk.KEY_Down):
            self._na_obrot(1)
        elif klawisz in (Gdk.KEY_Left, Gdk.KEY_Up):
            self._na_obrot(-1)
        elif klawisz in (Gdk.KEY_Return, Gdk.KEY_KP_Enter, Gdk.KEY_space):
            self._na_klik()
        elif klawisz == Gdk.KEY_Escape:
            self._na_przytrzymanie()
        else:
            return False
        return True


class Aplikacja(Gtk.Application):
    def __init__(self) -> None:
        super().__init__(application_id="pl.gcs.pulpit")
        self._okno: Pulpit | None = None
        self._tlo = None

    def do_activate(self) -> None:
        if self._okno is None:
            self._wczytaj_styl()
            # ⛔ Tło idzie PRZED pulpitem. Kiedy aplikacja zewnętrzna nie zajmuje
            # całego ekranu, spod niej widać to, co leży niżej — a bez naszej
            # warstwy był to pulpit maliny z ikonami i pasek zadań.
            self._tlo = tlo.wlacz()
            self._okno = Pulpit(self)
        self._okno.present()
        if os.getenv("GCS_PELNY_EKRAN", "1") != "0":
            self._okno.fullscreen()

    @staticmethod
    def _wczytaj_styl() -> None:
        ustawienia = Gtk.Settings.get_default()
        if ustawienia is not None:
            # Motyw systemowy jest jasny; bez tego część widgetów zostaje biała.
            ustawienia.set_property("gtk-application-prefer-dark-theme", True)
        dostawca = Gtk.CssProvider()
        # GTK 4.12 wprowadziło load_from_string; starsze mają tylko load_from_data.
        calosc = STYL + STYL_KLAWIATURY + STYL_PILOTA + STYL_MYSZY + STYL_SIECI + STYL_DODAWANIA + STYL_NAGRAN + STYL_NAGRYWARKI
        if hasattr(dostawca, "load_from_string"):
            dostawca.load_from_string(calosc.decode("utf-8"))
        else:
            dostawca.load_from_data(calosc)
        Gtk.StyleContext.add_provider_for_display(
            Gdk.Display.get_default(), dostawca, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )
