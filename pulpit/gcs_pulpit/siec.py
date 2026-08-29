"""Ekran SIEĆ — zarządzanie łączem bez konsoli.

Wi‑Fi na tej malinie jest **wyłączone w NetworkManagerze** (`WIFI: disabled`),
a sprzęt sprawny. Włączenie go wymagało dotąd `nmcli` z konsoli — czyli klawiatury,
której przy stanowisku nie ma. To jest pierwszy przypadek użycia tego ekranu.

> ### ⛔ Dlaczego przez pomocnika, a nie wprost przez `nmcli`
>
> NetworkManager przyznaje prawo do włączenia radia i połączenia tylko **aktywnej
> sesji lokalnej**. Pulpit chodzi jako jednostka użytkownika i dostaje odmowę
> (`Not authorized to perform this operation`) — sprawdzone.
>
> Dlatego zapis idzie przez `/usr/local/sbin/gcs-siec`: jeden plik z zamkniętą listą
> operacji, wskazany w sudoers **bez gwiazdki**. Odczyt stanu nie wymaga niczego
> i leci zwykłym `nmcli`.

Wszystko, co trwa — skan, łączenie — dzieje się w wątku. Ekran sterowany pokrętłem
nie może zamarzać, bo operator nie ma czym go odblokować.
"""

from __future__ import annotations

import logging
import subprocess
import threading
from collections.abc import Callable
from dataclasses import dataclass

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import GLib, Gtk  # noqa: E402

from .widoki import przewin_do  # noqa: E402

log = logging.getLogger("gcs.pulpit.siec")

POMOCNIK = "/usr/local/sbin/gcs-siec"

STYL = b"""
.siec-naglowek { font-size: 15pt; color: #97a29a; padding: 4px 0 14px 0; }
.siec-naglowek-mocny { font-size: 17pt; font-weight: 700; color: #e8e8de; }

button.siec {
    background-image: none;
    background-color: #161d19;
    border: 3px solid #232e28;
    border-radius: 10px;
    box-shadow: none;
    padding: 14px 20px;
    min-height: 58px;
    font-size: 16pt; font-weight: 700; color: #f2f2e8;
}
button.siec { outline: none; }
button.siec.siec-wybrany { border-color: #ffb000; background-color: #23302a; }
button.siec-wstecz   { background-color: #1b2420; color: #b9c3bb; font-size: 14pt; }
button.siec-wlaczone { background-color: #16301c; color: #7de08e; }
button.siec-siec     { font-size: 15pt; }
button.siec-polaczona { border-color: #50dc64; }
button.siec-ukryta   { background-color: #1a1c2a; color: #b8c6ff; font-size: 14pt; }
.siec-uwaga { font-size: 13pt; color: #ffb000; padding-top: 10px; }
"""


@dataclass
class Pozycja:
    etykieta: str
    akcja: Callable[[], None]
    styl: str = ""


def _nmcli(argumenty: list[str], czas: float = 20.0) -> str:
    try:
        wynik = subprocess.run(
            ["nmcli", *argumenty], capture_output=True, text=True, timeout=czas
        )
        return wynik.stdout
    except (OSError, subprocess.SubprocessError):
        log.exception("nmcli nie odpowiedział")
        return ""


class Siec(Gtk.Box):
    """Widok wstawiany w okno pulpitu. Sterowanie: obrót, klik, przytrzymanie."""

    def __init__(
        self,
        *,
        na_wstecz: Callable[[], None],
        na_tekst: Callable[[str, Callable[[str], None]], None],
        na_komunikat: Callable[[str], None],
        wan: Callable[[], object] | None = None,
    ) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        self.set_margin_top(24)
        self.set_margin_bottom(24)
        self.set_margin_start(40)
        self.set_margin_end(40)

        self._na_wstecz = na_wstecz
        self._na_tekst = na_tekst
        self._na_komunikat = na_komunikat
        self._wan = wan

        self._pozycje: list[Pozycja] = []
        self._widgety: list[Gtk.Widget] = []
        self._wybrany = 0
        self._zajete = False

        self._naglowek = Gtk.Label(label="", xalign=0)
        self._naglowek.add_css_class("siec-naglowek")
        self._naglowek.set_wrap(True)
        self.append(self._naglowek)

        self._lista = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self._przewijak = Gtk.ScrolledWindow()
        self._przewijak.set_vexpand(True)
        self._przewijak.set_child(self._lista)
        self.append(self._przewijak)

        self.odswiez()

    # ---- odczyt stanu ----------------------------------------------------

    @staticmethod
    def _radio_wlaczone() -> bool:
        return _nmcli(["-t", "-f", "WIFI", "general"], 5).strip() == "enabled"

    @staticmethod
    def _adres(urzadzenie: str) -> str:
        for linia in _nmcli(["-t", "-f", "IP4.ADDRESS", "device", "show", urzadzenie], 5).splitlines():
            if ":" in linia:
                return linia.split(":", 1)[1]
        return ""

    @staticmethod
    def _sieci() -> list[tuple[str, str, int, bool]]:
        """(ssid, zabezpieczenie, sygnał, czy połączona)"""
        wynik: list[tuple[str, str, int, bool]] = []
        widziane: set[str] = set()
        surowe = _nmcli(
            ["-t", "-f", "IN-USE,SSID,SIGNAL,SECURITY", "device", "wifi", "list"], 25
        )
        for linia in surowe.splitlines():
            czesci = linia.split(":")
            if len(czesci) < 4:
                continue
            uzywana, ssid, sygnal, zabezpieczenie = (
                czesci[0], czesci[1], czesci[2], ":".join(czesci[3:])
            )
            if not ssid or ssid in widziane:
                continue
            widziane.add(ssid)
            try:
                moc = int(sygnal)
            except ValueError:
                moc = 0
            wynik.append((ssid, zabezpieczenie.strip(), moc, uzywana.strip() == "*"))
        wynik.sort(key=lambda s: (-int(s[3]), -s[2]))
        return wynik

    @staticmethod
    def _slupki(moc: int) -> str:
        poziom = min(4, max(0, (moc + 24) // 25))
        return "▁▃▅▇"[:poziom].ljust(4, "·")

    # ---- budowa listy ----------------------------------------------------

    def odswiez(self, *, skanuj: bool = False) -> None:
        if self._zajete:
            return
        self._zajete = True
        self._na_komunikat("Odczytuję stan sieci…")

        def robota() -> None:
            radio = self._radio_wlaczone()
            eth = self._adres("eth0")
            sieci = self._sieci() if radio else []
            GLib.idle_add(self._zbuduj, radio, eth, sieci)

        threading.Thread(target=robota, name="gcs-siec", daemon=True).start()

    def _zbuduj(self, radio: bool, eth: str, sieci: list) -> bool:
        self._zajete = False
        self._eth = eth
        self._radio = radio
        self._opisz_naglowek()

        pozycje: list[Pozycja] = [
            Pozycja("◀ WSTECZ", self._na_wstecz, "siec-wstecz"),
            Pozycja(
                "WI-FI: WYŁĄCZ" if radio else "WI-FI: WŁĄCZ",
                lambda: self._przelacz_radio(not radio),
                "siec-wlaczone" if radio else "",
            ),
        ]
        if radio:
            pozycje.append(Pozycja("⟳ ODŚWIEŻ LISTĘ SIECI", lambda: self.odswiez(skanuj=True)))
            for ssid, zabezpieczenie, moc, polaczona in sieci:
                klodka = "🔒" if zabezpieczenie else "  "
                znak = "✓ " if polaczona else "  "
                pozycje.append(
                    Pozycja(
                        f"{znak}{self._slupki(moc)}  {klodka}  {ssid}",
                        lambda s=ssid, z=zabezpieczenie: self._polacz(s, z),
                        "siec-siec" + (" siec-polaczona" if polaczona else ""),
                    )
                )
            if not sieci:
                pozycje.append(
                    Pozycja("(nie widać żadnej sieci — spróbuj odświeżyć)", lambda: None)
                )
            # Sieć ukryta nie rozgłasza nazwy, więc nigdy nie pojawi się wyżej.
            # Bez tej pozycji nie da się do niej wejść w ogóle.
            pozycje.append(
                Pozycja("+ DODAJ SIEĆ UKRYTĄ", self._siec_ukryta, "siec-ukryta")
            )
        pozycje.append(Pozycja("SPRAWDŹ ŁĄCZNOŚĆ Z DRONEM", self._sprawdz_drona))

        self._pozycje = pozycje
        self._wybrany = min(self._wybrany, len(pozycje) - 1)
        self._przemaluj()
        self._na_komunikat(
            "Wi-Fi wyłączone — pierwsza pozycja włącza radio" if not radio
            else "Obrót: wybór · Klik: wykonaj · Przytrzymaj: powrót"
        )
        return False

    def _opisz_naglowek(self) -> None:
        """Licznik pozycji, bo przy długiej liście nie widać, gdzie się jest."""
        ile = len(self._pozycje)
        gdzie = f"   ·   pozycja {self._wybrany + 1} z {ile}" if ile else ""
        self._naglowek.set_markup(
            f"<span size='large'><b>Ethernet:</b> "
            f"{GLib.markup_escape_text(getattr(self, '_eth', '') or 'brak adresu')}"
            f"   ·   <b>Wi-Fi:</b> "
            f"{'włączone' if getattr(self, '_radio', False) else 'wyłączone'}"
            f"{GLib.markup_escape_text(self._opis_wan())}"
            f"{GLib.markup_escape_text(gdzie)}</span>"
        )

    def _opis_wan(self) -> str:
        """Adres, pod jakim widać nas z zewnątrz — albo z routera, gdy da się zapytać."""
        if self._wan is None:
            return ""
        stan = self._wan()
        adres = getattr(stan, "wan", "") or ""
        if not adres or adres == "—":
            return ""
        zrodlo = getattr(stan, "wan_zrodlo", "") or ""
        return f"   ·   WAN: {adres}" + (f" ({zrodlo})" if zrodlo else "")

    def _przemaluj(self) -> None:
        while (dziecko := self._lista.get_first_child()) is not None:
            self._lista.remove(dziecko)
        self._widgety = []
        for indeks, pozycja in enumerate(self._pozycje):
            przycisk = Gtk.Button(label=pozycja.etykieta)
            przycisk.add_css_class("siec")
            # ⛔ Nasze przyciski NIE MOGĄ przyjmować ogniska GTK. Zaznaczenie
            # prowadzimy sami, a gdy przycisk ma ognisko, GTK aktywuje go
            # dodatkowo klawiszem — zatwierdzenie wykonywało się wtedy DWA RAZY
            # i wchodzenie w grupę kończyło się otwarciem sąsiedniej pozycji.
            przycisk.set_can_focus(False)
            przycisk.set_focus_on_click(False)
            for klasa in pozycja.styl.split():
                przycisk.add_css_class(klasa)
            przycisk.set_halign(Gtk.Align.FILL)
            przycisk.connect("clicked", lambda *_, i=indeks: self.wykonaj(i))
            self._lista.append(przycisk)
            self._widgety.append(przycisk)
        self._zaznacz()

    def _zaznacz(self) -> None:
        for i, widget in enumerate(self._widgety):
            if i == self._wybrany:
                widget.add_css_class("siec-wybrany")
            else:
                widget.remove_css_class("siec-wybrany")
        if 0 <= self._wybrany < len(self._widgety):
            przewin_do(self._przewijak, self._lista, self._widgety[self._wybrany])
        self._opisz_naglowek()

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

    def _pomocnik(self, argumenty: list[str], haslo: str | None = None) -> tuple[bool, str]:
        try:
            wynik = subprocess.run(
                ["sudo", "-n", POMOCNIK, *argumenty],
                input=(haslo + "\n") if haslo is not None else None,
                capture_output=True,
                text=True,
                timeout=90,
            )
        except (OSError, subprocess.SubprocessError) as blad:
            return False, str(blad)
        if wynik.returncode != 0:
            linie = (wynik.stderr or wynik.stdout).strip().splitlines()
            return False, linie[-1] if linie else "nie udało się"
        return True, ""

    def _przelacz_radio(self, wlacz: bool) -> None:
        self._na_komunikat("Włączam Wi-Fi…" if wlacz else "Wyłączam Wi-Fi…")

        def robota() -> None:
            ok, blad = self._pomocnik(["wifi-on" if wlacz else "wifi-off"])
            if ok and wlacz:
                # Świeżo włączone radio potrzebuje chwili, zanim zobaczy cokolwiek.
                self._pomocnik(["rescan"])
            GLib.idle_add(self._po_dzialaniu, ok, blad)

        threading.Thread(target=robota, daemon=True).start()

    def _polacz(self, ssid: str, zabezpieczenie: str) -> None:
        if not zabezpieczenie:
            self._polacz_z_haslem(ssid, "")
            return
        self._na_tekst(
            f"Hasło do sieci {ssid}",
            lambda haslo: self._polacz_z_haslem(ssid, haslo),
        )

    def _siec_ukryta(self) -> None:
        """Dwa pytania po kolei: najpierw nazwa, potem hasło."""
        self._na_tekst("Nazwa sieci ukrytej (SSID)", self._ukryta_ma_nazwe)

    def _ukryta_ma_nazwe(self, ssid: str) -> None:
        ssid = ssid.strip()
        if not ssid:
            self._na_komunikat("Pusta nazwa — nic nie robię")
            return
        self._na_tekst(
            f"Hasło do sieci {ssid} (puste = sieć otwarta)",
            lambda haslo: self._polacz_z_haslem(ssid, haslo, ukryta=True),
        )

    def _polacz_z_haslem(self, ssid: str, haslo: str, *, ukryta: bool = False) -> None:
        self._na_komunikat(f"Łączę z {ssid}…")
        polecenie = "connect-hidden" if ukryta else "connect"

        def robota() -> None:
            ok, blad = self._pomocnik([polecenie, ssid], haslo)
            GLib.idle_add(self._po_dzialaniu, ok, blad or f"Połączono z {ssid}")

        threading.Thread(target=robota, daemon=True).start()

    def _po_dzialaniu(self, ok: bool, wiadomosc: str) -> bool:
        self._na_komunikat(wiadomosc if not ok else (wiadomosc or "Gotowe"))
        GLib.timeout_add_seconds(2, lambda: (self.odswiez(), False)[1])
        return False

    def _sprawdz_drona(self) -> None:
        self._na_komunikat("Sprawdzam sieć pokładową…")

        def robota() -> None:
            wyniki = []
            for adres, opis in (
                ("192.168.144.20", "MK32 (telemetria)"),
                ("192.168.144.25", "ZR30 (obraz)"),
            ):
                try:
                    kod = subprocess.run(
                        ["ping", "-c1", "-W2", adres], capture_output=True, timeout=6
                    ).returncode
                except (OSError, subprocess.SubprocessError):
                    kod = 1
                wyniki.append(f"{opis}: {'odpowiada' if kod == 0 else 'CISZA'}")
            GLib.idle_add(lambda: (self._na_komunikat("  ·  ".join(wyniki)), False)[1])

        threading.Thread(target=robota, daemon=True).start()
