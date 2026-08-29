#!/usr/bin/env python3
"""Wpięcie mostu pokrętła w panel GC9A01 (PI5setup full).

Linie GPIO enkodera są zajmowane na wyłączność, więc pokrętło może mieć tylko
jednego właściciela. Zostaje nim panel — a ta łatka pozwala mu przekazywać
zdarzenia pulpitowi GCS. Panel dostaje szóstą stronę „PULPIT GCS”; klik na niej
oddaje pokrętło dużemu ekranowi, a przytrzymanie na pulpicie je odbiera.

Co zmienia:
  src/rotary_encoder.py  — dokłada zgłaszanie surowego stanu przycisku
                           (bez tego nie da się wykryć przytrzymania)
  src/control_panel.py   — most, szósta strona, przekazywanie zdarzeń

Wszystkie zmiany są oznaczone znacznikiem `# GCS`, a przed pierwszą zmianą
powstają kopie `*.przed-gcs`.

    sudo python3 zalataj_panel.py            # pokaż, co zrobi (nic nie zapisuje)
    sudo python3 zalataj_panel.py --zapisz
    sudo python3 zalataj_panel.py --cofnij
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

ZNACZNIK = "# GCS"
KOPIA = ".przed-gcs"

# (plik, opis, szukane, wstawiane)
ZMIANY: list[tuple[str, str, str, str]] = [
    (
        "rotary_encoder.py",
        "zgłaszanie surowego stanu przycisku",
        """        on_error: Callable[[], None] | None = None,
    ) -> None:
        self._on_rotate = on_rotate""",
        """        on_error: Callable[[], None] | None = None,
        on_switch: Callable[[bool], None] | None = None,  # GCS
    ) -> None:
        self._on_switch = on_switch  # GCS
        self._on_rotate = on_rotate""",
    ),
    (
        "rotary_encoder.py",
        "wywołanie on_switch przy zmianie stanu",
        """                    self._switch_stable = switch
                    if switch == GPIO.HIGH:""",
        """                    self._switch_stable = switch
                    if self._on_switch:  # GCS
                        self._on_switch(switch == GPIO.LOW)
                    if switch == GPIO.HIGH:""",
    ),
    (
        "control_panel.py",
        "import mostu",
        """from rotary_encoder import RotaryEncoder""",
        """from rotary_encoder import RotaryEncoder
from gcs_most import MostPokretla, PANEL, PULPIT  # GCS""",
    ),
    (
        "control_panel.py",
        "trzy dodatkowe strony panelu",
        """PAGE_COUNT = 5
VRX_PAGE = 2""",
        """PAGE_COUNT = 8  # GCS: trzy dodatkowe strony — PULPIT, NAGRYWANIE, SIEĆ
PULPIT_PAGE = 5  # GCS
NAGRYWANIE_PAGE = 6  # GCS
SIEC_PAGE = 7  # GCS
# GCS: zakladki strony SIEC — 240x240 nie pomiesci wszystkiego naraz,
# wiec najpierw wybor, potem szczegol.
SIEC_POZYCJE = (("WAN", "wan"), ("WiFi", "wifi"), ("LAN", "lan"))
SIEC_WYGASNIECIE_S = 30
VRX_PAGE = 2""",
    ),
    (
        "control_panel.py",
        "uruchomienie mostu i podpięcie przycisku",
        """            self.encoder = RotaryEncoder(self.on_rotate, self.on_click, self.request_stop)""",
        """            self.most = self._uruchom_most()  # GCS
            self._siec_menu = None  # GCS: indeks w menu albo None
            self._siec_widok = None  # GCS: otwarta zakladka albo None
            self._siec_ostatnia = 0.0  # GCS
            self._gcs_wcisniete_od = 0.0  # GCS: wyjscie awaryjne
            self.encoder = RotaryEncoder(
                self.on_rotate, self.on_click, self.request_stop, self.on_switch
            )""",
    ),
    (
        "control_panel.py",
        "przekazywanie zdarzeń zamiast obsługi lokalnej",
        """    def on_rotate(self, direction: int) -> None:
        self.events.put(("rotate", direction))

    def on_click(self) -> None:
        self.events.put(("click", 1))""",
        """    def on_rotate(self, direction: int) -> None:
        if self._przekaz("obrot", kierunek=direction):  # GCS
            return
        self.events.put(("rotate", direction))

    def on_click(self) -> None:
        if self._przekaz("klik"):  # GCS
            return
        self.events.put(("click", 1))

    # --- GCS: most pokrętła ------------------------------------------------
    def _uruchom_most(self):
        \"\"\"Awaria mostu nie może zabrać panelu — wtedy po prostu go nie ma.\"\"\"
        try:
            most = MostPokretla()
            most.start()
            return most
        except Exception:
            log.exception("Nie udało się uruchomić mostu pokrętła")
            return None

    def on_switch(self, wcisniety: bool) -> None:
        \"\"\"Surowy stan przycisku — pulpit sam mierzy z niego przytrzymanie.

        ⛔ WYJSCIE AWARYJNE. Gdy pokretlo trzyma klient, panel przekazuje mu
        WSZYSTKO — wiec klient, ktory przestal odpowiadac, zabija cale sterowanie:
        nie dziala ani on, ani strony panelu. Zdarzylo sie to naprawde.

        Dlatego **dlugie przytrzymanie ponad 1,5 s zawsze odbiera pokretlo**
        i robi to TU, u wlasciciela GPIO. Tego nie da sie ani przechwycic,
        ani zablokowac, bo nie idzie przez zaden most.
        \"\"\"
        most = getattr(self, "most", None)
        teraz = time.monotonic()
        if wcisniety:
            self._gcs_wcisniete_od = teraz
        else:
            trzymane = teraz - (self._gcs_wcisniete_od or teraz)
            self._gcs_wcisniete_od = 0.0
            if trzymane >= 1.5 and most is not None and most.ognisko != PANEL:
                log.warning(
                    "Dlugie przytrzymanie (%.1f s) — pokretlo wraca do panelu", trzymane
                )
                most.ustaw_ognisko(PANEL)
                return
        if most is not None and most.ognisko == PULPIT:
            most.rozglos("wcisniety" if wcisniety else "puszczony")

    def _przekaz(self, typ: str, **pola: object) -> bool:
        most = getattr(self, "most", None)
        if most is None or most.ognisko != PULPIT:
            return False
        most.rozglos(typ, **pola)
        return True

    def _oddaj_pokretlo_pulpitowi(self) -> None:
        most = getattr(self, "most", None)
        if most is not None:
            most.ustaw_ognisko(PULPIT)

    def _przelacz_nagrywanie(self) -> None:
        \"\"\"Panel sam nic nie nagrywa — prosi o to pulpit, bo tam żyje nagrywarka.\"\"\"
        most = getattr(self, "most", None)
        if most is None:
            return
        if not most.ma_odbiorcow:
            log.warning("Nagrywanie: pulpit nie odpowiada")
            return
        # Adresowane do pulpitu — inaczej nagrywanie ruszyłoby u każdego klienta.
        most.rozglos_do("pulpit", "polecenie", co="nagrywanie")

    @staticmethod
    def _skroc(tekst: str, limit: int = 20) -> str:
        return tekst if len(tekst) <= limit else tekst[: limit - 1] + "\u2026"

    def _siec_page(self) -> Image.Image:
        # Menu i zakladki gasna same — inaczej panel zostalby w nich na zawsze,
        # a operator zobaczylby przy nastepnym podejsciu nieaktualny ekran.
        if (self._siec_menu is not None or self._siec_widok is not None) and (
            time.monotonic() - self._siec_ostatnia > SIEC_WYGASNIECIE_S
        ):
            self._siec_menu = None
            self._siec_widok = None

        most = getattr(self, "most", None)
        if most is None or not most.ma_odbiorcow:
            image, draw = self._base("SIEC", SIEC_PAGE)
            self._center(draw, "PULPIT NIE DZIALA", 104, self.font_medium, DIM)
            self._center(draw, "NIE MA KTO ZMIERZYC", 140, self.font_small, DIM)
            return image

        if self._siec_widok is not None:
            return self._siec_szczegol_page(most.stan_sieci)
        if self._siec_menu is not None:
            return self._siec_menu_page()

        image, draw = self._base("SIEC", SIEC_PAGE)
        self._center(draw, "WAN \u00b7 WiFi \u00b7 LAN", 104, self.font_medium, WHITE)
        self._center(draw, "KLIK: MENU", 150, self.font_small, AMBER)
        return image

    def _siec_menu_page(self) -> Image.Image:
        image, draw = self._base("SIEC", SIEC_PAGE)
        gora = 78
        for indeks, (etykieta, _) in enumerate(SIEC_POZYCJE):
            wybrana = indeks == self._siec_menu
            self._center(
                draw, ("> " + etykieta) if wybrana else etykieta,
                gora + indeks * 30, self.font_medium,
                AMBER if wybrana else DIM,
            )
        self._center(draw, "KLIK: OTWORZ", 186, self.font_small, DIM)
        return image

    def _siec_szczegol_page(self, stan: dict) -> Image.Image:
        tytuly = {"wan": "WAN", "wifi": "WiFi", "lan": "LAN"}
        image, draw = self._base(tytuly.get(self._siec_widok, "SIEC"), SIEC_PAGE)
        wartosc = str(stan.get(self._siec_widok) or "-")
        if self._siec_widok == "wan":
            self._center(draw, self._skroc(wartosc), 108, self.font_medium, AMBER)
            zrodlo = str(stan.get("wan_zrodlo") or "")
            if zrodlo:
                self._center(draw, zrodlo, 140, self.font_small, DIM)
        else:
            self._center(draw, self._skroc(wartosc), 112, self.font_medium, WHITE)
        self._center(draw, "OBROT: KOLEJNA", 186, self.font_small, DIM)
        self._center(draw, "KLIK: WSTECZ", 206, self.font_small, DIM)
        return image

    def _nagrywanie_page(self) -> Image.Image:
        image, draw = self._base("NAGRYWANIE", NAGRYWANIE_PAGE)
        most = getattr(self, "most", None)
        if most is None or not most.ma_odbiorcow:
            self._center(draw, "PULPIT NIE DZIAŁA", 104, self.font_medium, DIM)
            self._center(draw, "NIE MA CZEGO PYTAĆ", 140, self.font_small, DIM)
            return image
        stan = most.stan_nagrywania
        if stan.get("nagrywa"):
            self._center(draw, "● NAGRYWA", 92, self.font_medium, RED)
            self._center(draw, str(stan.get("opis") or ""), 126, self.font_small, WHITE)
            self._center(draw, "KLIK: ZATRZYMAJ", 166, self.font_small, AMBER)
        else:
            self._center(draw, "GOTOWE", 100, self.font_medium, GREEN)
            self._center(draw, "KLIK: NAGRYWAJ", 150, self.font_small, AMBER)
        return image

    def _pulpit_page(self) -> Image.Image:
        image, draw = self._base("PULPIT GCS", PULPIT_PAGE)
        most = getattr(self, "most", None)
        if most is None:
            self._center(draw, "MOST NIE DZIAŁA", 108, self.font_medium, RED)
            self._center(draw, "SPRAWDŹ DZIENNIK", 142, self.font_small, DIM)
        elif most.ognisko == PULPIT:
            self._center(draw, "POKRĘTŁO ODDANE", 92, self.font_small, GREEN)
            self._center(draw, str(most.wlasciciel), 118, self.font_medium, GREEN)
            self._center(draw, "PRZYTRZYMAJ 1,5 s", 158, self.font_small, AMBER)
            self._center(draw, "= ODBIERZ", 178, self.font_small, AMBER)
        elif most.ma_odbiorcow:
            self._center(draw, "PULPIT CZEKA", 104, self.font_medium, WHITE)
            self._center(draw, "KLIK: PRZEKAŻ", 150, self.font_small, AMBER)
        else:
            self._center(draw, "PULPIT NIE DZIAŁA", 104, self.font_medium, DIM)
            self._center(draw, "NIKT NIE SŁUCHA", 140, self.font_small, DIM)
        return image
    # --- koniec GCS --------------------------------------------------------""",
    ),
    (
        "control_panel.py",
        "obrót w menu sieci",
        """                else:
                    self.page = reversed_axis_index(self.page, value, PAGE_COUNT)""",
        """                elif self._siec_widok is not None:  # GCS
                    # W otwartej zakladce obrot przeskakuje wprost do sasiedniej —
                    # bez wracania do menu, bo porownuje sie je jedno po drugim.
                    self._siec_ostatnia = time.monotonic()
                    klucze = [klucz for _, klucz in SIEC_POZYCJE]
                    self._siec_widok = SIEC_POZYCJE[
                        reversed_axis_index(
                            klucze.index(self._siec_widok), value, len(SIEC_POZYCJE)
                        )
                    ][1]
                elif self._siec_menu is not None:  # GCS
                    self._siec_ostatnia = time.monotonic()
                    self._siec_menu = reversed_axis_index(
                        self._siec_menu, value, len(SIEC_POZYCJE)
                    )
                else:
                    self.page = reversed_axis_index(self.page, value, PAGE_COUNT)""",
    ),
    (
        "control_panel.py",
        "klik na stronie PULPIT GCS",
        """                else:
                    if self.page == 1:
                        self._open_recordings_menu()""",
        """                else:
                    if self._siec_widok is not None:  # GCS
                        self._siec_widok = None
                        self._siec_ostatnia = time.monotonic()
                    elif self._siec_menu is not None:  # GCS
                        self._siec_widok = SIEC_POZYCJE[self._siec_menu][1]
                        self._siec_menu = None
                        self._siec_ostatnia = time.monotonic()
                    elif self.page == SIEC_PAGE:  # GCS
                        self._siec_menu = 0
                        self._siec_ostatnia = time.monotonic()
                    elif self.page == PULPIT_PAGE:  # GCS
                        self._oddaj_pokretlo_pulpitowi()
                    elif self.page == NAGRYWANIE_PAGE:  # GCS
                        self._przelacz_nagrywanie()
                    elif self.page == 1:
                        self._open_recordings_menu()""",
    ),
    (
        "control_panel.py",
        "rysowanie strony PULPIT GCS",
        """            elif self.page == 3:
                image = self._temperature_page()""",
        """            elif self.page == 3:
                image = self._temperature_page()
            elif self.page == PULPIT_PAGE:  # GCS
                image = self._pulpit_page()
            elif self.page == NAGRYWANIE_PAGE:  # GCS
                image = self._nagrywanie_page()
            elif self.page == SIEC_PAGE:  # GCS
                image = self._siec_page()""",
    ),
    (
        "control_panel.py",
        "zamykanie mostu",
        """        self._closed = True
        self.stop_event.set()
        try:
            if self.vrx is not None:""",
        """        self._closed = True
        self.stop_event.set()
        try:  # GCS
            most = getattr(self, "most", None)
            if most is not None:
                most.close()
        except Exception:
            log.exception("Błąd zamykania mostu pokrętła")
        try:
            if self.vrx is not None:""",
    ),
]


def wczytaj(sciezka: Path) -> str:
    return sciezka.read_text(encoding="utf-8")


def zalataj(katalog: Path, zapisz: bool) -> int:
    pliki = {nazwa for nazwa, _, _, _ in ZMIANY}
    tresc = {}
    for nazwa in pliki:
        sciezka = katalog / nazwa
        if not sciezka.is_file():
            print(f"BŁĄD: nie ma {sciezka}", file=sys.stderr)
            return 1
        tresc[nazwa] = wczytaj(sciezka)

    juz = [n for n, t in tresc.items() if ZNACZNIK in t]
    if juz:
        print(f"Łatka już jest w: {', '.join(sorted(juz))}. Nic nie robię.")
        print("Aby wgrać od nowa: --cofnij, potem --zapisz.")
        return 0

    for nazwa, opis, szukane, wstawiane in ZMIANY:
        if tresc[nazwa].count(szukane) != 1:
            print(
                f"BŁĄD: w {nazwa} nie znalazłem dokładnie jednego miejsca na "
                f"zmianę „{opis}”. Plik jest inny, niż zakładała łatka — "
                f"przerywam, żeby go nie uszkodzić.",
                file=sys.stderr,
            )
            return 1
        tresc[nazwa] = tresc[nazwa].replace(szukane, wstawiane, 1)
        print(f"  {nazwa}: {opis}")

    if not zapisz:
        print("\nTo była próba na sucho. Dodaj --zapisz, żeby wgrać.")
        return 0

    for nazwa in sorted(pliki):
        sciezka = katalog / nazwa
        kopia = sciezka.with_suffix(sciezka.suffix + KOPIA)
        if not kopia.exists():
            shutil.copy2(sciezka, kopia)
            print(f"  kopia: {kopia}")
        sciezka.write_text(tresc[nazwa], encoding="utf-8")
    print("\nWgrane. Teraz: sudo systemctl restart pi5-control-panel")
    return 0


def cofnij(katalog: Path) -> int:
    wrocilo = 0
    for nazwa in sorted({n for n, _, _, _ in ZMIANY}):
        sciezka = katalog / nazwa
        kopia = sciezka.with_suffix(sciezka.suffix + KOPIA)
        if kopia.is_file():
            shutil.copy2(kopia, sciezka)
            kopia.unlink()
            print(f"  przywrócone: {nazwa}")
            wrocilo += 1
    if not wrocilo:
        print("Nie ma czego cofać — brak kopii *.przed-gcs.")
        return 1
    print("\nCofnięte. Teraz: sudo systemctl restart pi5-control-panel")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--katalog", default="/opt/pi5setup-full/src")
    p.add_argument("--zapisz", action="store_true", help="faktycznie zapisz zmiany")
    p.add_argument("--cofnij", action="store_true", help="przywróć kopie *.przed-gcs")
    a = p.parse_args()
    katalog = Path(a.katalog)
    if a.cofnij:
        return cofnij(katalog)
    return zalataj(katalog, a.zapisz)


if __name__ == "__main__":
    raise SystemExit(main())
