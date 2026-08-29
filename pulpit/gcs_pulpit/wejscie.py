"""Pokrętło po stronie pulpitu — klient mostu z panelu GC9A01.

Zamienia surowy strumień z mostu na trzy zdarzenia, które rozumie interfejs:

    obrot(kierunek)   -1 albo +1, przesunięcie zaznaczenia
    klik()            zatwierdzenie
    przytrzymanie()   powrót — z aplikacji na pulpit, z pulpitu do panelu

> ### ⚠ DLACZEGO KLIK MA ZWŁOKĘ
>
> Sterownik enkodera zgłasza klik **w chwili wciśnięcia**, nie puszczenia
> (`rotary_encoder.py`, gałąź `elif self._armed`). Gdybyśmy działali od razu,
> przytrzymanie najpierw uruchomiłoby zaznaczoną pozycję, a dopiero potem
> wróciło na pulpit — czyli zrobiłoby obie rzeczy naraz.
>
> Dlatego klik czeka na rozstrzygnięcie: puszczone przed progiem to **klik**,
> trzymane dłużej to **przytrzymanie**, a klik przepada. Kosztuje to `PROG_S`
> zwłoki przy każdym zatwierdzeniu i jest to świadoma zamiana — bez niej nie ma
> powrotu, a przy maszynie nie ma klawiatury.
"""

from __future__ import annotations

import json
import logging
import os
import socket
import threading
import time
from collections.abc import Callable

SCIEZKA_GNIAZDA = os.getenv("GCS_GNIAZDO_POKRETLA", "/run/gcs/pokretlo.sock")
PROG_PRZYTRZYMANIA_S = float(os.getenv("GCS_PROG_PRZYTRZYMANIA", "0.5"))

# ⚠ Surowy kierunek z enkodera jest ODWROTNY do naturalnego. Panel GC9A01 sam to
# prostuje — jego funkcja nazywa się wprost `reversed_axis_index` i liczy
# `-direction` (`panel_settings.py`). Pulpit brał kierunek surowy i dlatego
# zaznaczenie szło w drugą stronę niż ręka. Prostujemy tak samo, w jednym miejscu:
# przez to przechodzą kafelki, klawiatura i pilot.
# GCS_POKRETLO_ODWROTNE=0 wyłącza prostowanie, gdyby enkoder wymieniono na inny.
ODWROTNE = os.getenv("GCS_POKRETLO_ODWROTNE", "1").strip().lower() not in (
    "0", "false", "nie", "off"
)
PRZERWA_PONOWIENIA_S = 2.0

PANEL = "panel"
PULPIT = "pulpit"

log = logging.getLogger("gcs.pulpit.wejscie")


class Pokretlo:
    """Wątek w tle. Wywołania zwrotne lecą z tego wątku — patrz `przez_glowny`."""

    def __init__(
        self,
        *,
        na_obrot: Callable[[int], None],
        na_klik: Callable[[], None],
        na_przytrzymanie: Callable[[], None],
        na_ognisko: Callable[[str], None] | None = None,
        na_lacze: Callable[[bool], None] | None = None,
        na_polecenie: Callable[[str], None] | None = None,
        sciezka: str = SCIEZKA_GNIAZDA,
        prog_s: float = PROG_PRZYTRZYMANIA_S,
        odwrotne: bool = ODWROTNE,
    ) -> None:
        self._na_obrot = na_obrot
        self._na_klik = na_klik
        self._na_przytrzymanie = na_przytrzymanie
        self._na_ognisko = na_ognisko
        self._na_lacze = na_lacze
        self._na_polecenie = na_polecenie
        self._sciezka = sciezka
        self._prog_s = prog_s
        self._odwrotne = odwrotne

        self._stop = threading.Event()
        self._watek: threading.Thread | None = None
        self._gniazdo: socket.socket | None = None
        self._plik = None
        self._ognisko = PANEL

        # rozstrzyganie klik / przytrzymanie
        self._klik_czeka = False
        self._wcisniete_od: float | None = None
        self._zegar: threading.Timer | None = None

    # ---- cykl życia ------------------------------------------------------

    def start(self) -> None:
        self._watek = threading.Thread(target=self._petla, name="gcs-pokretlo", daemon=True)
        self._watek.start()

    def stop(self) -> None:
        self._stop.set()
        self._anuluj_zegar()
        self._rozlacz()

    @property
    def ognisko(self) -> str:
        return self._ognisko

    def zglos_stan(self, nagrywa: bool, opis: str) -> None:
        """Melduje panelowi, co się dzieje z nagrywaniem — okrągły ekran to pokazuje."""
        self._wyslij({"cmd": "stan", "nagrywa": bool(nagrywa), "opis": opis})

    def zglos_siec(self, stan: dict) -> None:
        """Melduje panelowi stan sieci — okrągły ekran tylko go wyświetla."""
        wiadomosc = {"cmd": "siec"}
        wiadomosc.update(stan)
        self._wyslij(wiadomosc)

    def przekaz_sasiadowi(self) -> None:
        """Oddaje pokrętło innemu klientowi mostu — aplikacji, która obsługuje je sama."""
        self._wyslij({"cmd": "ognisko", "gdzie": "inny"})

    def oddaj_panelowi(self) -> None:
        """Pulpit rezygnuje z pokrętła — panel odzyskuje własne strony."""
        self._wyslij({"cmd": "ognisko", "gdzie": PANEL})

    # ---- łącze -----------------------------------------------------------

    def _petla(self) -> None:
        while not self._stop.is_set():
            if not self._polacz():
                self._stop.wait(PRZERWA_PONOWIENIA_S)
                continue
            try:
                self._czytaj()
            except OSError:
                pass
            finally:
                self._rozlacz()
                if self._na_lacze:
                    self._na_lacze(False)
            if not self._stop.is_set():
                self._stop.wait(PRZERWA_PONOWIENIA_S)

    def _polacz(self) -> bool:
        try:
            gniazdo = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            gniazdo.connect(self._sciezka)
        except OSError as blad:
            log.debug("Most pokrętła niedostępny (%s): %s", self._sciezka, blad)
            return False
        self._gniazdo = gniazdo
        self._plik = gniazdo.makefile("rwb")
        # Most kieruje zdarzenia do JEDNEGO klienta — musi wiedzieć, kim jesteśmy.
        self._wyslij({"cmd": "jestem", "nazwa": PULPIT})
        log.info("Podłączony do mostu pokrętła")
        if self._na_lacze:
            self._na_lacze(True)
        return True

    def _rozlacz(self) -> None:
        for obiekt in (self._plik, self._gniazdo):
            try:
                if obiekt is not None:
                    obiekt.close()
            except Exception:
                pass
        self._plik = None
        self._gniazdo = None

    def _wyslij(self, wiadomosc: dict) -> None:
        plik = self._plik
        if plik is None:
            # Meldunki stanu lecą co sekundę — nie zasypujemy nimi dziennika.
            if wiadomosc.get("cmd") not in ("stan", "siec"):
                log.warning("Brak łącza z mostem — polecenie przepadło: %s", wiadomosc)
            return
        try:
            plik.write((json.dumps(wiadomosc) + "\n").encode("utf-8"))
            plik.flush()
        except OSError:
            log.warning("Nie udało się wysłać polecenia do mostu")

    def _czytaj(self) -> None:
        assert self._plik is not None
        for linia in self._plik:
            if self._stop.is_set():
                return
            try:
                wiadomosc = json.loads(linia.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                continue
            self._obsluz(wiadomosc)

    # ---- zamiana surowych zdarzeń na sensowne ----------------------------

    def _obsluz(self, wiadomosc: dict) -> None:
        typ = wiadomosc.get("typ")
        if typ == "obrot":
            kierunek = 1 if wiadomosc.get("kierunek", 1) > 0 else -1
            if self._odwrotne:
                kierunek = -kierunek
            self._na_obrot(kierunek)
        elif typ == "wcisniety":
            self._wcisniete_od = time.monotonic()
        elif typ == "klik":
            # Klik przychodzi w chwili wciśnięcia — czekamy na rozstrzygnięcie.
            self._klik_czeka = True
            self._anuluj_zegar()
            self._zegar = threading.Timer(self._prog_s, self._prog_minal)
            self._zegar.daemon = True
            self._zegar.start()
        elif typ == "puszczony":
            self._anuluj_zegar()
            self._wcisniete_od = None
            if self._klik_czeka:
                self._klik_czeka = False
                self._na_klik()
        elif typ == "polecenie":
            # Polecenie z panelu — działa NIEZALEŻNIE od tego, kto ma pokrętło.
            if self._na_polecenie:
                self._na_polecenie(str(wiadomosc.get("co") or ""))
        elif typ == "ognisko":
            gdzie = wiadomosc.get("gdzie", PANEL)
            self._ognisko = gdzie
            # Przy oddaniu pokrętła nie zostawiamy niedokończonego kliku.
            self._anuluj_zegar()
            self._klik_czeka = False
            if self._na_ognisko:
                self._na_ognisko(gdzie)

    def _prog_minal(self) -> None:
        if not self._klik_czeka:
            return
        self._klik_czeka = False
        self._na_przytrzymanie()

    def _anuluj_zegar(self) -> None:
        if self._zegar is not None:
            self._zegar.cancel()
            self._zegar = None
