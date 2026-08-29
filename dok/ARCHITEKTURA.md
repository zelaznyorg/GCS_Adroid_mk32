# Architektura GCS Pulpit

## Przepływ wejścia

```text
enkoder GPIO
    │
    ▼
zewnętrzny panel GC9A01
    │  /run/gcs/pokretlo.sock — JSON po jednej wiadomości w linii
    ▼
most/gcs_most.py
    │
    ├── pulpit GTK4
    ├── pasek pilota
    └── aplikacja obsługująca pokrętło samodzielnie
```

GPIO ma jednego właściciela. Pulpit nie próbuje otwierać tych samych linii; odbiera
zdarzenia przez most. Po rozłączeniu ognisko musi wrócić do panelu.

## Moduły Python

| Moduł | Odpowiedzialność |
|---|---|
| `okno.py` | aplikacja GTK, ekran główny i koordynacja widoków |
| `katalog.py` | odczyt kafelków JSON i skrótów `.desktop` |
| `wejscie.py` | klient gniazda pokrętła i model ogniska |
| `pilot.py` | sterowanie aplikacją zewnętrzną klawiszami |
| `mysz.py` | wskaźnik sterowany pokrętłem |
| `klawiatura.py` | nakładka znakowa, która nie odbiera ogniska |
| `siec.py` | ekran operacji NetworkManagera |
| `siec_stan.py` | odczyt stanu LAN, Wi-Fi i WAN w tle |
| `nagrywanie.py` | proces nagrywania i trwały stan źródeł |
| `nagrania.py` | katalog oraz odtwarzacz nagrań |
| `okna.py` | wykrywanie i pełny ekran okien Wayland |
| `tlo.py` | warstwa tła sesji GCS |

GTK może być zmieniane wyłącznie z głównego wątku. Praca sieciowa, procesy i
odpytywanie systemu idą w tle, a wynik wraca przez `GLib.idle_add`.

## Konfiguracja aplikacji

Pulpit czyta kolejno:

1. `/etc/gcs/aplikacje.d/*.json`,
2. `~/.config/gcs/aplikacje.d/*.json`.

Identyfikatorem jest nazwa pliku bez rozszerzenia. Późniejszy wpis o tym samym ID
nadpisuje wcześniejszy, dlatego dane konkretnej stacji mogą pozostać poza repo.

Pole `uruchom` jest tablicą argumentów procesu. Kod nie uruchamia powłoki. Pole
`desktop` wskazuje istniejący skrót freedesktop. Warunki `wymaga` gaszą niedostępny
kafelek zamiast uruchamiać polecenie, które musi się nie udać.

## Granica uprawnień

Pulpit działa jako zwykły użytkownik. Jedynym uprzywilejowanym wejściem sieciowym
jest `/usr/local/sbin/gcs-siec`, wskazany dokładnie w sudoers. Pomocnik przyjmuje
zamkniętą listę operacji i wywołuje `nmcli` bez powłoki.

Instalator używa `sudo` do instalowania plików, ale uruchamiana aplikacja nie
powinna otrzymywać ogólnego prawa do `nmcli`, `systemctl` ani powłoki roota.

## Integracja systemowa

- `gcs-pulpit.service` — usługa użytkownika związana z sesją graficzną,
- `gcs.desktop` i `gcs-sesja` — osobna sesja Wayland/labwc,
- `gcs-ui` — odwracalne przełączenie sesji autologowania,
- `gcs-otoczenie` — ukrycie i przywrócenie standardowego pulpitu,
- `labwc/autostart` — minimalny autostart sesji GCS.

## Zasada rozszerzania

1. Logikę niezależną od GTK umieszczaj w małym, testowalnym module.
2. Operacje systemowe wywołuj tablicą argumentów, nigdy przez `shell=True`.
3. Nowe prawo `sudo` wymaga osobnego pomocnika lub zamkniętej komendy i przeglądu.
4. Długie operacje nie mogą blokować wątku GTK.
5. Każdy nowy tryb pełnoekranowy musi mieć sprawdzoną drogę powrotu.
