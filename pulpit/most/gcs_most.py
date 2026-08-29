"""Most pokrętła — rozgłaszanie zdarzeń enkodera do innych programów.

Linie GPIO enkodera są zajmowane na wyłączność (`rpi-lgpio` nad `lgpio`), więc
pokrętło może mieć tylko jednego właściciela. Właścicielem zostaje panel GC9A01,
a ten moduł pozwala mu **przesyłać** zdarzenia komukolwiek, kto się zgłosi.

Gniazdo strumieniowe UNIX, po jednej wiadomości JSON w linii, w obie strony:

    panel  → klient   {"typ": "obrot",     "kierunek": 1}
                      {"typ": "polecenie", "co": "nagrywanie"}
                      {"typ": "klik"}
                      {"typ": "wcisniety"}          — surowy stan przycisku,
                      {"typ": "puszczony"}            po to, żeby klient sam
                                                      wykrył przytrzymanie
                      {"typ": "ognisko",   "gdzie": "pulpit"}
    klient → panel    {"cmd": "jestem",    "nazwa": "pulpit"}   — opcjonalne
                      {"cmd": "ognisko",   "gdzie": "inny"}     — przekaż sąsiadowi
                      {"cmd": "ognisko",   "gdzie": "panel"}
                      {"cmd": "stan", "nagrywa": true, "opis": "0:11 · 2 źr."}
                      {"cmd": "siec", "lan": "…", "wifi": "…", "wan": "…"}

Meldunek `stan` jest po to, żeby **okrągły ekran pokazywał prawdę o nagrywaniu**,
a nie własne domysły: nagrywarka żyje w pulpicie i tylko on wie, co się dzieje.

⛔ ZDARZENIA POKRĘTŁA IDĄ DO **JEDNEGO** KLIENTA, NIE DO WSZYSTKICH.

To była realna usterka, nie ostrożność: gdy do mostu podłączyła się druga
aplikacja (stacja DRON 15 z własną obsługą pokrętła), obrót i klik trafiały
**równocześnie** do niej i do pulpitu. Operator kręcił w aplikacji, a przykryty
pulpit pod spodem przesuwał zaznaczenie i **uruchamiał kolejne programy**.

Dlatego most wie, kto ma pokrętło, i wysyła zdarzenia tylko jemu. Każdy klient
dostaje przy tym **własną prawdę**: właściciel słyszy `ognisko: pulpit`,
pozostali `ognisko: panel`. Klient nie musi o tym nic wiedzieć — protokół
się nie zmienił.

⛔ ZASADA BEZPIECZEŃSTWA: ognisko nie może utknąć poza panelem.
Gdy odejdzie ostatni klient, ognisko wraca do panelu samo. Bez tego padnięcie
pulpitu zostawiłoby martwe pokrętło, a przy maszynie nie ma klawiatury.
"""

from __future__ import annotations

import grp
import json
import logging
import os
import socket
import threading
from collections.abc import Callable

SCIEZKA_GNIAZDA = os.getenv("GCS_GNIAZDO_POKRETLA", "/run/gcs/pokretlo.sock")
GRUPA_GNIAZDA = os.getenv("GCS_GRUPA_GNIAZDA", "video")

PANEL = "panel"
PULPIT = "pulpit"

log = logging.getLogger("gcs.most")


class _Klient:
    def __init__(self, gniazdo: socket.socket, nazwa: str = "") -> None:
        self.nazwa = nazwa
        self.gniazdo = gniazdo
        self.plik = gniazdo.makefile("rwb")
        self.blokada = threading.Lock()

    def wyslij(self, wiadomosc: dict) -> None:
        dane = (json.dumps(wiadomosc, ensure_ascii=False) + "\n").encode("utf-8")
        with self.blokada:
            self.plik.write(dane)
            self.plik.flush()

    def zamknij(self) -> None:
        for zamykaj in (self.plik.close, self.gniazdo.close):
            try:
                zamykaj()
            except Exception:
                pass


class MostPokretla:
    """Serwer gniazda. Tworzy go panel; klientem jest pulpit i cokolwiek innego."""

    def __init__(
        self,
        sciezka: str = SCIEZKA_GNIAZDA,
        *,
        na_zmiane_ogniska: Callable[[str], None] | None = None,
    ) -> None:
        self._sciezka = sciezka
        self._na_zmiane_ogniska = na_zmiane_ogniska
        self._klienci: list[_Klient] = []
        self._licznik = 0
        self._blokada = threading.Lock()
        # Kto ma pokrętło. None znaczy panel.
        self._wlasciciel: _Klient | None = None
        # Ostatni meldunek z pulpitu — panel go tylko wyświetla, sam nic nie liczy.
        self.stan_nagrywania: dict = {"nagrywa": False, "opis": ""}
        self.stan_sieci: dict = {"lan": "—", "wifi": "—", "wan": "—", "wan_zrodlo": ""}
        self._stop = threading.Event()
        self._serwer: socket.socket | None = None
        self._watek: threading.Thread | None = None

    # ---- uruchamianie ----------------------------------------------------

    def start(self) -> None:
        katalog = os.path.dirname(self._sciezka)
        os.makedirs(katalog, mode=0o755, exist_ok=True)
        try:
            os.unlink(self._sciezka)
        except FileNotFoundError:
            pass

        self._serwer = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._serwer.bind(self._sciezka)
        self._serwer.listen(8)
        self._serwer.settimeout(0.5)
        self._nadaj_prawa()

        self._watek = threading.Thread(target=self._przyjmuj, name="gcs-most", daemon=True)
        self._watek.start()
        log.info("Most pokrętła nasłuchuje na %s", self._sciezka)

    def _nadaj_prawa(self) -> None:
        """Panel chodzi jako root, pulpit jako zwykły użytkownik — stąd grupa."""
        try:
            gid = grp.getgrnam(GRUPA_GNIAZDA).gr_gid
            os.chown(self._sciezka, 0, gid)
            os.chmod(self._sciezka, 0o660)
        except Exception:
            log.warning(
                "Nie udało się nadać gniazdu grupy %s — pulpit może nie mieć dostępu",
                GRUPA_GNIAZDA,
                exc_info=True,
            )

    def _przyjmuj(self) -> None:
        assert self._serwer is not None
        while not self._stop.is_set():
            try:
                gniazdo, _ = self._serwer.accept()
            except socket.timeout:
                continue
            except OSError:
                if not self._stop.is_set():
                    log.exception("Most pokrętła przestał przyjmować połączenia")
                return
            with self._blokada:
                self._licznik += 1
                klient = _Klient(gniazdo, f"klient-{self._licznik}")
                self._klienci.append(klient)
            log.info("Nowy odbiorca pokrętła: %s (razem %d)", klient.nazwa, len(self._klienci))
            klient.wyslij({"typ": "ognisko", "gdzie": self._widziane_ognisko(klient)})
            threading.Thread(
                target=self._czytaj, args=(klient,), name="gcs-most-klient", daemon=True
            ).start()

    def _czytaj(self, klient: _Klient) -> None:
        try:
            for linia in klient.plik:
                if self._stop.is_set():
                    break
                try:
                    wiadomosc = json.loads(linia.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    continue
                if wiadomosc.get("cmd") == "jestem":
                    klient.nazwa = str(wiadomosc.get("nazwa") or klient.nazwa)[:32]
                    log.info("Odbiorca przedstawił się: %s", klient.nazwa)
                elif wiadomosc.get("cmd") == "siec":
                    self.stan_sieci = {
                        klucz: str(wiadomosc.get(klucz) or "—")
                        for klucz in ("lan", "wifi", "wan", "wan_zrodlo")
                    }
                elif wiadomosc.get("cmd") == "stan":
                    self.stan_nagrywania = {
                        "nagrywa": bool(wiadomosc.get("nagrywa")),
                        "opis": str(wiadomosc.get("opis") or ""),
                    }
                elif wiadomosc.get("cmd") == "ognisko":
                    gdzie = wiadomosc.get("gdzie")
                    if gdzie == PANEL:
                        # ⛔ Rezygnować wolno TYLKO ze swojego ogniska. Inaczej
                        # klient, który pokrętła nie miał, zabierałby je temu,
                        # który właśnie z niego korzysta — i pokrętło umierało.
                        if klient is self._wlasciciel:
                            self.ustaw_ognisko(PANEL)
                        else:
                            log.debug(
                                "%s oddaje pokrętło, którego nie ma — pomijam",
                                klient.nazwa,
                            )
                    elif gdzie == PULPIT:
                        # Prosi konkretne połączenie i ono właśnie dostaje pokrętło.
                        self.ustaw_ognisko(PULPIT, klient)
                    elif gdzie == "inny":
                        # Jawne przekazanie sąsiadowi. Potrzebne, gdy pulpit
                        # uruchamia aplikację obsługującą pokrętło samodzielnie:
                        # ona nie ma jak poprosić, bo przy stanowisku nie ma myszy.
                        self._przekaz_innemu(klient)
        except OSError:
            pass
        finally:
            self._odlacz(klient)

    def _odlacz(self, klient: _Klient) -> None:
        with self._blokada:
            if klient in self._klienci:
                self._klienci.remove(klient)
            zostalo = len(self._klienci)
        klient.zamknij()
        log.info("Odbiorca %s odszedł (zostało %d)", klient.nazwa, zostalo)
        if self._wlasciciel is klient:
            log.warning("Odszedł właściciel pokrętła — ognisko wraca do panelu")
            self.ustaw_ognisko(PANEL)

    # ---- ognisko ---------------------------------------------------------

    @property
    def ognisko(self) -> str:
        """Dla panelu: czy pokrętło jest u niego, czy oddane."""
        return PANEL if self._wlasciciel is None else PULPIT

    @property
    def wlasciciel(self) -> str:
        return self._wlasciciel.nazwa if self._wlasciciel is not None else PANEL

    def _widziane_ognisko(self, klient: _Klient) -> str:
        """Każdy klient dostaje własną prawdę: ma pokrętło albo nie."""
        return PULPIT if klient is self._wlasciciel else PANEL

    @property
    def ma_odbiorcow(self) -> bool:
        with self._blokada:
            return bool(self._klienci)

    def ustaw_ognisko(self, gdzie: str, klient: "_Klient | None" = None) -> bool:
        """Zwraca False, gdy przekazanie ogniska nie ma sensu — brak odbiorcy."""
        if gdzie == PANEL:
            nowy = None
        else:
            nowy = klient if klient is not None else self._domyslny_odbiorca()
            if nowy is None:
                log.warning("Odmowa przekazania ogniska: nikt nie słucha pokrętła")
                return False
        if nowy is self._wlasciciel:
            return True
        self._wlasciciel = nowy
        log.info("Ognisko pokrętła: %s", self.wlasciciel)
        self._powiadom_o_ognisku()
        if self._na_zmiane_ogniska:
            try:
                self._na_zmiane_ogniska(self.ognisko)
            except Exception:
                log.exception("Błąd w powiadomieniu o zmianie ogniska")
        return True

    def _przekaz_innemu(self, nadawca: "_Klient") -> None:
        """⛔ Wolno tylko wtedy, gdy nadawca ma pokrętło albo nie ma go nikt —
        inaczej dowolny klient mógłby je zabrać temu, kto właśnie z niego korzysta."""
        if self._wlasciciel not in (None, nadawca):
            log.debug("%s chce przekazać cudze pokrętło — pomijam", nadawca.nazwa)
            return
        with self._blokada:
            cel = next((k for k in self._klienci if k is not nadawca), None)
        if cel is None:
            log.info("Nie ma komu przekazać pokrętła — zostaje przy panelu")
            self.ustaw_ognisko(PANEL)
            return
        self.ustaw_ognisko(PULPIT, cel)

    def _domyslny_odbiorca(self) -> "_Klient | None":
        """Panel oddaje pokrętło pulpitowi; gdy go nie ma — pierwszemu z brzegu."""
        with self._blokada:
            for klient in self._klienci:
                if klient.nazwa == PULPIT:
                    return klient
            return self._klienci[0] if self._klienci else None

    def _powiadom_o_ognisku(self) -> None:
        with self._blokada:
            odbiorcy = list(self._klienci)
        for klient in odbiorcy:
            try:
                klient.wyslij({"typ": "ognisko", "gdzie": self._widziane_ognisko(klient)})
            except OSError:
                self._odlacz(klient)

    # ---- rozgłaszanie ----------------------------------------------------

    def rozglos(self, typ: str, **pola: object) -> None:
        """Zdarzenie pokrętła — WYŁĄCZNIE do właściciela.

        ⛔ Wysyłanie tego wszystkim było przyczyną „klikam w aplikacji, a pulpit
        uruchamia inne programy".
        """
        wlasciciel = self._wlasciciel
        if wlasciciel is None:
            return
        wiadomosc: dict[str, object] = {"typ": typ}
        wiadomosc.update(pola)
        try:
            wlasciciel.wyslij(wiadomosc)
        except OSError:
            self._odlacz(wlasciciel)

    def rozglos_do(self, nazwa: str, typ: str, **pola: object) -> None:
        """Wiadomość do wskazanego klienta — używane przez polecenia z panelu."""
        wiadomosc: dict[str, object] = {"typ": typ}
        wiadomosc.update(pola)
        with self._blokada:
            odbiorcy = [k for k in self._klienci if k.nazwa == nazwa]
        for klient in odbiorcy:
            try:
                klient.wyslij(wiadomosc)
            except OSError:
                self._odlacz(klient)

    def close(self) -> None:
        self._stop.set()
        if self._serwer is not None:
            try:
                self._serwer.close()
            except Exception:
                pass
        with self._blokada:
            odbiorcy = list(self._klienci)
            self._klienci.clear()
        for klient in odbiorcy:
            klient.zamknij()
        try:
            os.unlink(self._sciezka)
        except FileNotFoundError:
            pass
        except Exception:
            pass
