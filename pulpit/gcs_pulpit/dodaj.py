"""Ekran DODAJ APLIKACJĘ — budowanie kafelków z tego, co już jest zainstalowane.

Zamiast pisać plik JSON ręcznie, operator wybiera aplikację z listy tego, co
system naprawdę ma: pakiety Androida z Waydroida i programy maliny.

> ### Grupa jest wybierana PO wskazaniu aplikacji, nie wcześniej
>
> Pierwsza wersja miała „grupę docelową" ustawianą z góry, jedną pozycją na
> początku listy. W użyciu okazało się to pułapką: przy trzydziestu kilku
> pozycjach nikt nie pamięta, co jest ustawione u góry, i **aplikacja ląduje
> nie tam, gdzie trzeba** — u Toma Firefox wpadł do SYSTEM.
>
> Teraz kliknięcie aplikacji zadaje pytanie **gdzie**, z listą istniejących grup,
> pozycją „bez grupy" i „nową grupą". Jedno kliknięcie więcej, za to bez pomyłki.

> ### Gdzie lądują dodane kafelki
>
> W **`~/.config/gcs/aplikacje.d/`**, nie w `/etc`. Katalog użytkownika nie wymaga
> roota, więc dodawanie działa z pulpitu bez żadnych uprawnień. `/etc/gcs/aplikacje.d`
> zostaje na to, co przychodzi z instalatorem — te wpisy są tu widoczne, ale
> **nie do usunięcia z ekranu**, żeby jedno nieuważne kliknięcie nie skasowało
> konfiguracji wgranej z projektu.
"""

from __future__ import annotations

import configparser
import json
import logging
import re
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import GLib, Gtk  # noqa: E402

from . import katalog  # noqa: E402
from .widoki import przewin_do  # noqa: E402

log = logging.getLogger("gcs.pulpit.dodaj")

KATALOG_UZYTKOWNIKA = Path.home() / ".config" / "gcs" / "aplikacje.d"
BEZ_GRUPY = "(bez grupy)"

STYL = b"""
.dodaj-naglowek { font-size: 15pt; color: #97a29a; padding: 4px 0 12px 0; }
.dodaj-dzial    { font-size: 13pt; font-weight: 800; color: #ffb000;
                  padding: 14px 0 4px 4px; }
.dodaj-pytanie  { font-size: 19pt; font-weight: 800; color: #f2f2e8;
                  padding: 6px 0 14px 0; }

button.dodaj {
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
button.dodaj.dodaj-wybrany { border-color: #ffb000; background-color: #23302a; }
button.dodaj-jest     { background-color: #16301c; color: #7de08e; }
button.dodaj-stala    { color: #8f9a92; }
button.dodaj-wstecz   { background-color: #1b2420; color: #b9c3bb; font-size: 14pt; }
button.dodaj-grupa    { background-color: #1a1c2a; color: #b8c6ff; }
button.dodaj-nowa     { background-color: #17251c; color: #a9e6b8; }
"""


@dataclass
class Znaleziona:
    id_desktop: str
    nazwa: str
    android: bool


@dataclass
class Pozycja:
    etykieta: str
    akcja: Callable[[], None]
    styl: str = ""
    naglowek: bool = False


def _nazwa_z_desktop(sciezka: Path) -> str | None:
    parser = configparser.ConfigParser(interpolation=None, strict=False)
    try:
        parser.read(sciezka, encoding="utf-8")
        wpis = parser["Desktop Entry"]
    except (configparser.Error, KeyError, OSError, UnicodeDecodeError):
        return None
    if wpis.get("NoDisplay", "false").strip().lower() == "true":
        return None
    if wpis.get("Type", "Application").strip() != "Application":
        return None
    if wpis.get("Terminal", "false").strip().lower() == "true":
        return None
    if not wpis.get("Exec", "").strip():
        return None
    return (wpis.get("Name[pl]") or wpis.get("Name") or sciezka.stem).strip()


def znajdz_zainstalowane() -> list[Znaleziona]:
    widziane: dict[str, Znaleziona] = {}
    for folder in katalog.KATALOGI_DESKTOP:
        if not folder.is_dir():
            continue
        for sciezka in sorted(folder.glob("*.desktop")):
            identyfikator = sciezka.stem
            if identyfikator in widziane:
                continue
            nazwa = _nazwa_z_desktop(sciezka)
            if nazwa is None:
                continue
            widziane[identyfikator] = Znaleziona(
                id_desktop=identyfikator,
                nazwa=nazwa,
                android=identyfikator.startswith("waydroid."),
            )
    return sorted(widziane.values(), key=lambda z: (z.android, z.nazwa.lower()))


class Dodaj(Gtk.Box):
    """Widok w oknie pulpitu. Sterowanie: obrót, klik, przytrzymanie."""

    def __init__(
        self,
        *,
        na_wstecz: Callable[[], None],
        na_tekst: Callable[[str, Callable[[str], None]], None],
        na_komunikat: Callable[[str], None],
        na_zmiane: Callable[[], None],
        grupy: Callable[[], list[str]],
    ) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.set_margin_top(20)
        self.set_margin_bottom(20)
        self.set_margin_start(40)
        self.set_margin_end(40)

        self._na_wstecz = na_wstecz
        self._na_tekst = na_tekst
        self._na_komunikat = na_komunikat
        self._na_zmiane = na_zmiane
        self._grupy = grupy

        self._pytanie: Znaleziona | None = None
        self._dodanych = 0
        self._pozycje: list[Pozycja] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0

        self._naglowek = Gtk.Label(label="", xalign=0)
        self._naglowek.add_css_class("dodaj-naglowek")
        self._naglowek.set_wrap(True)
        self.append(self._naglowek)

        self._lista = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self._przewijak = Gtk.ScrolledWindow()
        self._przewijak.set_vexpand(True)
        self._przewijak.set_child(self._lista)
        self.append(self._przewijak)

        self.odswiez()

    # ---- budowa listy ----------------------------------------------------

    def odswiez(self) -> None:
        self._pozycje = (
            self._pozycje_wyboru_grupy() if self._pytanie else self._pozycje_aplikacji()
        )
        self._wybrany = min(self._wybrany, max(0, len(self._pozycje) - 1))
        self._przemaluj()

    def _pozycje_aplikacji(self) -> list[Pozycja]:
        dodane = {a.id: a for a in katalog.wczytaj()}
        self._dodanych = len(dodane)
        # Wpis dodany z pulpitu ma plik u użytkownika — tylko taki wolno usunąć.
        wlasne = {p.stem for p in KATALOG_UZYTKOWNIKA.glob("*.json")}

        pozycje: list[Pozycja] = [
            Pozycja("◀ WSTECZ  (albo przytrzymaj pokrętło)", self._na_wstecz, "dodaj-wstecz")
        ]
        znalezione = znajdz_zainstalowane()
        for android, tytul in ((True, "— ANDROID (Waydroid) —"), (False, "— MALINA —")):
            wybrane = [z for z in znalezione if z.android == android]
            if not wybrane:
                continue
            pozycje.append(Pozycja(tytul, lambda: None, naglowek=True))
            for znaleziona in wybrane:
                identyfikator = self._identyfikator(znaleziona)
                jest = identyfikator in dodane
                if jest and identyfikator in wlasne:
                    gdzie = (dodane[identyfikator].grupa or "").strip().upper()
                    opis = f" [{gdzie}]" if gdzie else ""
                    pozycje.append(
                        Pozycja(
                            f"✓ {znaleziona.nazwa}{opis}   ▸ usuń z pulpitu",
                            lambda i=identyfikator, n=znaleziona.nazwa: self._usun(i, n),
                            "dodaj-jest",
                        )
                    )
                elif jest:
                    pozycje.append(
                        Pozycja(
                            f"✓ {znaleziona.nazwa}   (z instalatora — nie do usunięcia)",
                            lambda n=znaleziona.nazwa: self._na_komunikat(
                                f"{n}: wpis pochodzi z /etc — skasuj plik ręcznie"
                            ),
                            "dodaj-jest dodaj-stala",
                        )
                    )
                else:
                    pozycje.append(
                        Pozycja(
                            f"+ {znaleziona.nazwa}",
                            lambda z=znaleziona: self._zapytaj_gdzie(z),
                            "",
                        )
                    )
        return pozycje

    def _pozycje_wyboru_grupy(self) -> list[Pozycja]:
        assert self._pytanie is not None
        pozycje = [
            Pozycja("◀ ANULUJ  (albo przytrzymaj pokrętło)", self._anuluj, "dodaj-wstecz"),
            Pozycja(
                f"{BEZ_GRUPY} — wprost na pulpicie",
                lambda: self._dodaj(BEZ_GRUPY),
                "dodaj-grupa",
            ),
        ]
        for grupa in self._grupy():
            pozycje.append(
                Pozycja(f"📁 {grupa}", lambda g=grupa: self._dodaj(g), "dodaj-grupa")
            )
        pozycje.append(Pozycja("➕ NOWA GRUPA…", self._nowa_grupa, "dodaj-nowa"))
        return pozycje

    @staticmethod
    def _identyfikator(znaleziona: Znaleziona) -> str:
        """Nazwa pliku JSON = identyfikator kafelka. Bez znaków psujących ścieżki."""
        return "90-" + re.sub(r"[^a-zA-Z0-9._-]", "_", znaleziona.id_desktop)

    def _przemaluj(self) -> None:
        while (dziecko := self._lista.get_first_child()) is not None:
            self._lista.remove(dziecko)
        self._widgety = []
        for indeks, pozycja in enumerate(self._pozycje):
            if pozycja.naglowek:
                etykieta = Gtk.Label(label=pozycja.etykieta, xalign=0)
                etykieta.add_css_class("dodaj-dzial")
                self._lista.append(etykieta)
                self._widgety.append(etykieta)
                continue
            przycisk = Gtk.Button(label=pozycja.etykieta)
            przycisk.add_css_class("dodaj")
            # ⛔ Nasze przyciski NIE MOGĄ przyjmować ogniska GTK. Zaznaczenie
            # prowadzimy sami, a gdy przycisk ma ognisko, GTK aktywuje go
            # dodatkowo klawiszem — zatwierdzenie wykonywało się wtedy DWA RAZY
            # i wchodzenie w grupę kończyło się otwarciem sąsiedniej pozycji.
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
                widget.add_css_class("dodaj-wybrany")
            else:
                widget.remove_css_class("dodaj-wybrany")
        if 0 <= self._wybrany < len(self._widgety):
            przewin_do(self._przewijak, self._lista, self._widgety[self._wybrany])
        self._opisz_naglowek()

    def _opisz_naglowek(self) -> None:
        if self._pytanie is not None:
            self._naglowek.set_markup(
                "<span size='x-large'><b>Gdzie dodać: "
                f"{GLib.markup_escape_text(self._pytanie.nazwa)}</b></span>"
            )
            self._naglowek.add_css_class("dodaj-pytanie")
            return
        self._naglowek.remove_css_class("dodaj-pytanie")
        ile = sum(1 for p in self._pozycje if not p.naglowek)
        numer = sum(1 for p in self._pozycje[: self._wybrany + 1] if not p.naglowek)
        self._naglowek.set_text(
            f"Na pulpicie: {self._dodanych} pozycji     Wybrane: {numer} z {ile}"
        )

    # ---- pokrętło --------------------------------------------------------

    def obrot(self, kierunek: int) -> None:
        if not self._pozycje:
            return
        # Nagłówki działów są tylko podpisami — pokrętło ma je przeskakiwać.
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
        """Z pytania o grupę wraca do listy, z listy — na pulpit."""
        if self._pytanie is not None:
            self._anuluj()
        else:
            self._na_wstecz()

    def wykonaj(self, indeks: int) -> None:
        if 0 <= indeks < len(self._pozycje) and not self._pozycje[indeks].naglowek:
            self._wybrany = indeks
            self._zaznacz()
            self._pozycje[indeks].akcja()

    # ---- działania -------------------------------------------------------

    def _zapytaj_gdzie(self, znaleziona: Znaleziona) -> None:
        self._pytanie = znaleziona
        self._wybrany = 1  # od razu na „bez grupy" — najczęstszy wybór
        self.odswiez()

    def _anuluj(self) -> None:
        self._pytanie = None
        self._wybrany = 0
        self.odswiez()
        self._na_komunikat("Anulowane — nic nie dodałem")

    def _nowa_grupa(self) -> None:
        self._na_tekst("Nazwa nowej grupy", self._nowa_grupa_gotowa)

    def _nowa_grupa_gotowa(self, nazwa: str) -> None:
        nazwa = nazwa.strip().upper()
        if not nazwa:
            self._na_komunikat("Pusta nazwa — nic nie dodałem")
            return
        self._dodaj(nazwa)

    def _dodaj(self, grupa: str) -> None:
        znaleziona = self._pytanie
        if znaleziona is None:
            return
        KATALOG_UZYTKOWNIKA.mkdir(parents=True, exist_ok=True)
        wpis: dict[str, object] = {
            "nazwa": znaleziona.nazwa,
            "desktop": znaleziona.id_desktop,
            "pelny-ekran": True,
        }
        if grupa != BEZ_GRUPY:
            wpis["grupa"] = grupa
        if znaleziona.android:
            # Kafelek Androida bez działającego Waydroida ma się gasić, nie próbować.
            wpis["wymaga"] = {"usluga": "waydroid-container"}

        sciezka = KATALOG_UZYTKOWNIKA / f"{self._identyfikator(znaleziona)}.json"
        try:
            sciezka.write_text(
                json.dumps(wpis, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
        except OSError as blad:
            self._na_komunikat(f"Nie udało się dodać: {blad}")
            return

        log.info("Dodany kafelek %s → %s (grupa %s)", znaleziona.nazwa, sciezka, grupa)
        gdzie = "wprost na pulpicie" if grupa == BEZ_GRUPY else f"do grupy {grupa}"
        self._na_komunikat(
            f"Dodane: {znaleziona.nazwa} {gdzie}. Przytrzymaj pokrętło, aby wrócić."
        )
        self._pytanie = None
        # Zaznaczenie wraca na WSTECZ — po dodaniu najczęściej chce się wyjść,
        # a przewijanie przez trzydzieści pozycji do góry byłoby karą.
        self._wybrany = 0
        self._na_zmiane()
        self.odswiez()

    def _usun(self, identyfikator: str, nazwa: str) -> None:
        sciezka = KATALOG_UZYTKOWNIKA / f"{identyfikator}.json"
        try:
            sciezka.unlink()
        except OSError as blad:
            self._na_komunikat(f"Nie udało się usunąć: {blad}")
            return
        log.info("Usunięty kafelek %s", nazwa)
        self._na_komunikat(f"Usunięte z pulpitu: {nazwa}")
        self._na_zmiane()
        self.odswiez()
