"""Nagrywarka — zapis obrazu ze źródeł IP, każde niezależnie.

> ### ⛔ CVBS TEŻ JEST ŹRÓDŁEM IP — i to zmienia całą konstrukcję
>
> Pierwsza wersja nagrywała jedno źródło i zakładała, że obraz analogowy to osobny
> świat. **Nieprawda:** `pi5-uas-rtsp` udostępnia obraz CVBS jako strumień RTSP
> pod `rtsp://127.0.0.1:8554/uav` (640×480, 30 kl./s) — robi to dla ATAK‑a, ale
> słucha każdego.
>
> Dzięki temu **CVBS i kamera cyfrowa są tym samym rodzajem danych** i obsługuje je
> jeden mechanizm. Nie trzeba ani dotykać rejestratora Toma, ani budować drugiej
> ścieżki zapisu, ani nagrywać ekranu.

Źródeł może być dowolnie wiele — dron może mieć kamerę cyfrową i tor analogowy naraz,
a stacja przewiduje drugi slot kamery w sieci pokładowej (`192.168.144.26`).
Każde źródło ma **własny proces `ffmpeg` i własny katalog**, więc jedno padnięte
nie psuje pozostałych.

> ### Dlaczego `-c copy`, a nie przekodowanie
>
> Strumienie są już zakodowane (H.264). Kopiujemy **pakiety**, nie obraz — koszt
> bliski zeru. RPi 5 **nie ma sprzętowego kodera H.264** (poz. R4), więc każde
> przekodowanie zjadałoby rdzeń i psuło jakość bez żadnego zysku.
>
> **To jest też argument przeciw nagrywaniu ekranu:** ekran trzeba zakodować,
> a źródło wystarczy przepisać.

> ### Dlaczego `.mkv`, a nie `.mp4`
>
> Nagranie kończy się, kiedy operator naciśnie przycisk — albo gdy zniknie zasilanie.
> **`.mp4` urwany w pół zapisu jest nie do odtworzenia**, bo indeks trafia na koniec
> pliku. `.mkv` znosi to bez szkody: zostaje tyle, ile się nagrało.
"""

from __future__ import annotations

import json
import logging
import os
import re
import signal
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path

log = logging.getLogger("gcs.pulpit.nagrywanie")

KATALOG = Path(os.getenv("GCS_NAGRANIA", "/var/lib/gcs/nagrania"))
USTAWIENIA = Path.home() / ".config" / "gcs" / "zrodla-obrazu.json"
ZRODLA_STACJI = Path("/var/lib/dron15/zrodla.json")

# Ile miejsca musi zostać, żeby zaczynać. Karta systemowa jest jedyna (poz. R8),
# a zapchanie jej do zera potrafi zatrzymać całą malinę.
MINIMUM_WOLNEGO_MB = 1024
# Ile czekamy na pierwszy bajt obrazu, zanim uznamy, że źródła nie ma.
CZAS_NA_POLACZENIE_S = 8.0


@dataclass
class Zrodlo:
    id: str
    nazwa: str
    adres: str
    wlaczone: bool = True


def _domyslne_zrodla() -> list[Zrodlo]:
    """Dwa źródła, które ta maszyna ma naprawdę — adres głowicy z konfiguracji stacji."""
    adres_glowicy = "rtsp://192.168.144.25:8554/main.264"
    try:
        dane = json.loads(ZRODLA_STACJI.read_text(encoding="utf-8"))
        for zrodlo in dane.get("zrodla", []):
            if adres := zrodlo.get("rtspGlowny"):
                adres_glowicy = str(adres)
                break
    except (OSError, ValueError):
        log.info("Brak %s — biorę adres głowicy z wartości domyślnej", ZRODLA_STACJI)
    return [
        Zrodlo("zr30", "ZR30 — głowica (cyfrowa)", adres_glowicy),
        Zrodlo("cvbs", "CVBS — tor analogowy", "rtsp://127.0.0.1:8554/uav"),
    ]


def wczytaj_zrodla() -> list[Zrodlo]:
    try:
        dane = json.loads(USTAWIENIA.read_text(encoding="utf-8"))
        zrodla = [
            Zrodlo(
                id=str(z["id"]),
                nazwa=str(z.get("nazwa") or z["id"]),
                adres=str(z["adres"]),
                wlaczone=bool(z.get("wlaczone", True)),
            )
            for z in dane.get("zrodla", [])
            if z.get("id") and z.get("adres")
        ]
        if zrodla:
            return zrodla
    except (OSError, ValueError, KeyError):
        pass
    zrodla = _domyslne_zrodla()
    zapisz_zrodla(zrodla)
    return zrodla


def zapisz_zrodla(zrodla: list[Zrodlo]) -> None:
    try:
        USTAWIENIA.parent.mkdir(parents=True, exist_ok=True)
        USTAWIENIA.write_text(
            json.dumps(
                {"zrodla": [z.__dict__ for z in zrodla]}, ensure_ascii=False, indent=2
            )
            + "\n",
            encoding="utf-8",
        )
    except OSError:
        log.exception("Nie udało się zapisać %s", USTAWIENIA)


def bezpieczny_id(tekst: str) -> str:
    oczyszczony = re.sub(r"[^a-zA-Z0-9._-]", "-", tekst.strip().lower()).strip("-")
    return oczyszczony or f"zrodlo-{int(time.time())}"


def wolne_mb(katalog: Path) -> int:
    try:
        stan = os.statvfs(katalog)
        return int(stan.f_bavail * stan.f_frsize / (1 << 20))
    except OSError:
        return 0


class Rejestrator:
    """Zapis jednego źródła. Start i stop tym samym poleceniem."""

    def __init__(self, zrodlo: Zrodlo) -> None:
        self.zrodlo = zrodlo
        self._proces: subprocess.Popen | None = None
        self._plik: Path | None = None
        self._od: float = 0.0
        self._potwierdzone = False
        self._ostatni_blad = ""
        # Postęp czytany wprost z ffmpeg — patrz `potwierdzone`.
        self._bufor = b""
        self._out_ms = 0

    # ---- stan ------------------------------------------------------------

    @property
    def nagrywa(self) -> bool:
        if self._proces is None:
            return False
        if self._proces.poll() is not None:
            log.warning(
                "%s: ffmpeg zakończył się sam (kod %s)",
                self.zrodlo.id, self._proces.returncode,
            )
            self._ostatni_blad = f"{self.zrodlo.nazwa}: zapis przerwany"
            self._proces = None
            return False
        return True

    def _czytaj_postep(self) -> None:
        """`ffmpeg -progress` wypisuje `out_time_ms=…` co pół sekundy.

        ⛔ Rozmiar pliku do tego NIE służy: ffmpeg buforuje zapis, więc przy
        cichym źródle plik przez kilkanaście sekund ma zero bajtów, choć nagranie
        idzie. Pierwsza wersja właśnie dlatego ucinała poprawne nagrania.
        """
        if self._proces is None or self._proces.stdout is None:
            return
        try:
            kawalek = self._proces.stdout.read(8192)
        except (OSError, ValueError):
            return
        if not kawalek:
            return
        self._bufor = (self._bufor + kawalek)[-8192:]
        for linia in self._bufor.split(b"\n"):
            if linia.startswith(b"out_time_ms="):
                try:
                    self._out_ms = max(self._out_ms, int(linia.split(b"=", 1)[1]))
                except ValueError:
                    pass

    @property
    def potwierdzone(self) -> bool:
        """Czy nagranie naprawdę idzie — mierzone postępem, nie nadzieją."""
        if not self._potwierdzone:
            self._czytaj_postep()
            self._potwierdzone = self._out_ms > 0
        return self._potwierdzone

    def opis(self) -> str:
        if not self.nagrywa:
            return "gotowe"
        if not self.potwierdzone:
            return "ŁĄCZĘ…"
        self._czytaj_postep()
        sekundy = self._out_ms // 1_000_000
        rozmiar = ""
        if self._plik is not None and self._plik.exists():
            megabajty = self._plik.stat().st_size / (1 << 20)
            if megabajty >= 0.1:
                rozmiar = f"  ·  {megabajty:.0f} MB"
        return f"nagrywa {sekundy // 60}:{sekundy % 60:02d}{rozmiar}"

    # ---- działanie -------------------------------------------------------

    def zacznij(self) -> str:
        if self.nagrywa:
            return f"{self.zrodlo.nazwa}: już nagrywam"
        katalog = KATALOG / self.zrodlo.id
        try:
            katalog.mkdir(parents=True, exist_ok=True)
        except OSError as blad:
            return f"{self.zrodlo.nazwa}: brak katalogu ({blad})"

        wolne = wolne_mb(katalog)
        if wolne < MINIMUM_WOLNEGO_MB:
            return f"Za mało miejsca: {wolne} MB (potrzeba {MINIMUM_WOLNEGO_MB})"

        plik = katalog / (time.strftime("%Y-%m-%d_%H-%M-%S") + ".mkv")
        srodowisko = os.environ.copy()
        srodowisko.pop("LD_PRELOAD", None)
        polecenie = [
            "ffmpeg", "-hide_banner", "-loglevel", "warning",
            # TCP, bo po drodze bywa router; UDP gubi pakiety i psuje zapis.
            "-rtsp_transport", "tcp",
            # ⛔ To musi być `-timeout`, NIE `-rw_timeout`. Demukser RTSP w ffmpeg 7
            # nie zna tej drugiej opcji i kończy się od razu komunikatem
            # „Option rw_timeout not found" — czyli każde nagranie padało w starcie.
            "-timeout", "5000000",
            "-i", self.zrodlo.adres,
            "-c", "copy",
            # Postęp na stdout — jedyny pewny dowód, że coś naprawdę leci.
            "-progress", "pipe:1", "-nostats",
            str(plik),
        ]
        try:
            self._proces = subprocess.Popen(
                polecenie,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
                env=srodowisko,
            )
        except OSError as blad:
            self._ostatni_blad = str(blad)
            return f"{self.zrodlo.nazwa}: nie ruszyło ({blad})"

        if self._proces.stdout is not None:
            # Bez tego odczyt postępu zablokowałby cały interfejs.
            os.set_blocking(self._proces.stdout.fileno(), False)
        self._plik = plik
        self._od = time.monotonic()
        self._potwierdzone = False
        self._bufor = b""
        self._out_ms = 0
        self._ostatni_blad = ""
        log.info("%s: NAGRYWANIE START -> %s", self.zrodlo.id, plik)
        return f"{self.zrodlo.nazwa}: łączę…"

    def sprawdz(self) -> str:
        """Wołane co sekundę. Zwraca komunikat tylko wtedy, gdy coś się zmieniło."""
        if self._proces is None:
            return ""
        if not self.nagrywa:
            blad, self._ostatni_blad = self._ostatni_blad, ""
            return blad
        if self.potwierdzone:
            return ""
        if time.monotonic() - self._od > CZAS_NA_POLACZENIE_S:
            self.zatrzymaj()
            return f"{self.zrodlo.nazwa}: źródło nie odpowiada — przerwane"
        return ""

    def zatrzymaj(self) -> str:
        if self._proces is None:
            return ""
        plik = self._plik
        try:
            # `q` to prośba o normalne domknięcie pliku; SIGINT jest drugą próbą,
            # a zabicie ostatnią — wtedy `.mkv` i tak się otworzy.
            if self._proces.stdin:
                self._proces.stdin.write(b"q")
                self._proces.stdin.flush()
            self._proces.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired):
            for sygnal in (signal.SIGINT, signal.SIGKILL):
                try:
                    os.killpg(os.getpgid(self._proces.pid), sygnal)
                    self._proces.wait(timeout=4)
                    break
                except (OSError, subprocess.TimeoutExpired):
                    continue
        self._proces = None
        potwierdzone, self._potwierdzone = self._potwierdzone, False
        czas = self._out_ms // 1_000_000 or int(time.monotonic() - self._od)
        log.info("%s: NAGRYWANIE STOP -> %s (%d s)", self.zrodlo.id, plik, czas)

        if potwierdzone and plik is not None and plik.exists():
            return (
                f"{self.zrodlo.nazwa}: zapisane {plik.name}  ·  "
                f"{czas // 60}:{czas % 60:02d}  ·  "
                f"{plik.stat().st_size / (1 << 20):.0f} MB"
            )
        # Pusty plik po nieudanej próbie tylko zaśmieca listę nagrań.
        if plik is not None:
            try:
                if plik.exists() and plik.stat().st_size == 0:
                    plik.unlink()
            except OSError:
                pass
        return f"{self.zrodlo.nazwa}: nagranie nie powstało"


class Nagrywarka:
    """Wszystkie źródła naraz. Każde ma własny proces i własny katalog."""

    def __init__(self) -> None:
        self._rejestratory: dict[str, Rejestrator] = {}
        self.przeladuj()

    def przeladuj(self) -> None:
        zrodla = wczytaj_zrodla()
        nowe: dict[str, Rejestrator] = {}
        for zrodlo in zrodla:
            istniejacy = self._rejestratory.get(zrodlo.id)
            if istniejacy is not None:
                istniejacy.zrodlo = zrodlo
                nowe[zrodlo.id] = istniejacy
            else:
                nowe[zrodlo.id] = Rejestrator(zrodlo)
        # Źródła usunięte z listy trzeba najpierw zatrzymać, inaczej ffmpeg
        # zostałby sierotą piszącą w tle do końca świata.
        for identyfikator, rejestrator in self._rejestratory.items():
            if identyfikator not in nowe and rejestrator.nagrywa:
                rejestrator.zatrzymaj()
        self._rejestratory = nowe

    @property
    def zrodla(self) -> list[Zrodlo]:
        return [r.zrodlo for r in self._rejestratory.values()]

    def rejestrator(self, identyfikator: str) -> Rejestrator | None:
        return self._rejestratory.get(identyfikator)

    @property
    def nagrywa(self) -> bool:
        return any(r.nagrywa for r in self._rejestratory.values())

    @property
    def ile_nagrywa(self) -> int:
        return sum(1 for r in self._rejestratory.values() if r.nagrywa)

    def opis_zbiorczy(self) -> str:
        pracujace = [r for r in self._rejestratory.values() if r.nagrywa]
        if not pracujace:
            return ""
        if any(not r.potwierdzone for r in pracujace):
            return "ŁĄCZĘ…"
        # Czas najdłużej pracującego — on wyznacza długość całego nagrania.
        najdluzsze = min(pracujace, key=lambda r: r._od)
        czas = najdluzsze.opis().replace("nagrywa ", "").split("  ·  ")[0]
        return f"{czas}  ·  {len(pracujace)} źr."

    def przelacz_wszystkie(self) -> str:
        """Przycisk REC: skoro cokolwiek pisze — zatrzymaj; jak nie — zacznij."""
        if self.nagrywa:
            return self.zatrzymaj_wszystkie()
        wlaczone = [r for r in self._rejestratory.values() if r.zrodlo.wlaczone]
        if not wlaczone:
            return "Żadne źródło nie jest włączone — otwórz NAGRYWARKĘ"
        return "  |  ".join(r.zacznij() for r in wlaczone)

    def zatrzymaj_wszystkie(self) -> str:
        komunikaty = [
            r.zatrzymaj() for r in self._rejestratory.values() if r.nagrywa
        ]
        return "  |  ".join(k for k in komunikaty if k) or "Nic nie nagrywam"

    def sprawdz(self) -> str:
        komunikaty = [r.sprawdz() for r in self._rejestratory.values()]
        return "  |  ".join(k for k in komunikaty if k)

    def zamknij(self) -> None:
        self.zatrzymaj_wszystkie()
