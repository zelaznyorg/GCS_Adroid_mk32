"""Stan sieci — LAN, Wi‑Fi i adres WAN, zbierany w tle.

Okrągły panel ma to pokazywać, ale sam nic nie liczy: **dane zbiera pulpit
i melduje je mostem**, tak samo jak stan nagrywania. Panel zostaje przy jednej
robocie — rysowaniu.

## Skąd bierze się adres WAN

Router (MikroTik na `192.168.88.1`) ma otwarty **wyłącznie SSH** — bez API, bez HTTP
i bez SNMP (sprawdzone skanem). Są więc dwie drogi i różnią się znaczeniem:

| Droga | Co pokazuje | Warunek |
|---|---|---|
| **z routera po SSH** | adres **na interfejsie WAN** | klucz publiczny maliny dopisany w RouterOS |
| **z zewnątrz** (`ifconfig.me`) | adres, **pod jakim świat nas widzi** | działający internet |

> ### ⚠ To NIE jest to samo i różnica ma znaczenie
>
> Gdy oba adresy są równe — mamy **publiczny adres** i WireGuard ma się gdzie postawić.
> Gdy się różnią — jesteśmy **za CGNAT** i połączenia przychodzące nie przejdą.
> To jest dokładnie ta kontrola, którą opisuje `ROUTER_MIKROTIK.md`.
>
> Dopóki klucz nie jest dopisany, pokazujemy adres widziany z zewnątrz i **mówimy
> wprost, że to on** — zamiast udawać, że znamy stan interfejsu.

Konfiguracja routera (opcjonalna), `~/.config/gcs/router.json`:

    {"host": "192.168.88.1", "uzytkownik": "admin", "klucz": "~/.ssh/id_ed25519"}
"""

from __future__ import annotations

import json
import logging
import subprocess
import threading
import time
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("gcs.pulpit.siec")

USTAWIENIA_ROUTERA = Path.home() / ".config" / "gcs" / "router.json"
ODSTEP_S = 30.0
# Adres widziany z zewnątrz sprawdzamy rzadziej — to ruch do internetu.
ODSTEP_WAN_S = 120.0

USLUGI_WAN = ("https://ifconfig.me/ip", "https://api.ipify.org")


@dataclass
class StanSieci:
    lan: str = "—"
    wifi: str = "—"
    wan: str = "—"
    wan_zrodlo: str = ""

    def jako_slownik(self) -> dict:
        return {
            "lan": self.lan,
            "wifi": self.wifi,
            "wan": self.wan,
            "wan_zrodlo": self.wan_zrodlo,
        }


def _nmcli(argumenty: list[str], czas: float = 6.0) -> str:
    try:
        return subprocess.run(
            ["nmcli", *argumenty], capture_output=True, text=True, timeout=czas
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def adres_lan() -> str:
    for linia in _nmcli(["-t", "-f", "IP4.ADDRESS", "device", "show", "eth0"]).splitlines():
        if ":" in linia:
            return linia.split(":", 1)[1].strip() or "brak adresu"
    return "brak"


def stan_wifi() -> str:
    if _nmcli(["-t", "-f", "WIFI", "general"]).strip() != "enabled":
        return "wyłączone"
    for linia in _nmcli(["-t", "-f", "DEVICE,STATE,CONNECTION", "device"]).splitlines():
        czesci = linia.split(":")
        if len(czesci) >= 3 and czesci[0] == "wlan0":
            if czesci[1] == "connected":
                moc = ""
                for wiersz in _nmcli(
                    ["-t", "-f", "IN-USE,SSID,SIGNAL", "device", "wifi", "list"], 10
                ).splitlines():
                    pola = wiersz.split(":")
                    if len(pola) >= 3 and pola[0].strip() == "*":
                        moc = f"  {pola[2]}%"
                        break
                return f"{czesci[2] or 'połączone'}{moc}"
            return "bez połączenia"
    return "brak karty"


def _wan_z_routera() -> str | None:
    """Adres na interfejsie WAN — jedyna odpowiedź, która mówi o stanie łącza."""
    try:
        dane = json.loads(USTAWIENIA_ROUTERA.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    host = str(dane.get("host") or "192.168.88.1")
    uzytkownik = str(dane.get("uzytkownik") or "admin")
    klucz = str(dane.get("klucz") or "")
    polecenie = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
                 "-o", "StrictHostKeyChecking=accept-new"]
    if klucz:
        polecenie += ["-i", str(Path(klucz).expanduser())]
    polecenie += [f"{uzytkownik}@{host}", "/ip address print terse"]
    try:
        wynik = subprocess.run(polecenie, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        return None
    if wynik.returncode != 0:
        log.info("Router nie odpowiedział: %s", wynik.stderr.strip()[:120])
        return None
    for linia in wynik.stdout.splitlines():
        for pole in linia.split():
            if pole.startswith("address="):
                adres = pole.split("=", 1)[1].split("/")[0]
                # Adresy sieci domowej i pętli zwrotnej to nie WAN.
                if not adres.startswith(("192.168.88.", "127.")):
                    return adres
    return None


def _wan_z_zewnatrz() -> str | None:
    for usluga in USLUGI_WAN:
        try:
            wynik = subprocess.run(
                ["curl", "-s", "--max-time", "8", usluga],
                capture_output=True, text=True, timeout=12,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        adres = wynik.stdout.strip()
        if adres and len(adres) <= 45 and all(c.isalnum() or c in ".:" for c in adres):
            return adres
    return None


class ObserwatorSieci:
    """Odpytuje w tle i podaje ostatni znany stan. Nigdy nie blokuje interfejsu."""

    def __init__(self) -> None:
        self.stan = StanSieci()
        self._ostatni_wan = 0.0
        self._stop = threading.Event()
        self._watek = threading.Thread(target=self._petla, name="gcs-siec", daemon=True)
        self._watek.start()

    def _petla(self) -> None:
        while not self._stop.is_set():
            try:
                self.stan.lan = adres_lan()
                self.stan.wifi = stan_wifi()
                if time.monotonic() - self._ostatni_wan > ODSTEP_WAN_S or self.stan.wan == "—":
                    self._ostatni_wan = time.monotonic()
                    if (adres := _wan_z_routera()) is not None:
                        self.stan.wan, self.stan.wan_zrodlo = adres, "z routera"
                    elif (adres := _wan_z_zewnatrz()) is not None:
                        self.stan.wan, self.stan.wan_zrodlo = adres, "z zewnątrz"
                    else:
                        self.stan.wan, self.stan.wan_zrodlo = "brak", ""
            except Exception:
                log.exception("Błąd odczytu stanu sieci")
            self._stop.wait(ODSTEP_S)

    def stop(self) -> None:
        self._stop.set()
