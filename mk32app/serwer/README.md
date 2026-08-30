# Serwer podglądu DRON15

Odbiera obraz z głowicy ZR30 i telemetrię z MK32, rozdaje je przeglądarkom w sieci.
Etap **M6** z [../PLAN.md](../PLAN.md). Architektura i uzasadnienia decyzji:
[../dok/SERWER_PODGLADU.md](../dok/SERWER_PODGLADU.md).

> **Ten serwer nie steruje maszyną.** Nie wysyła komend ani do kontrolera lotu, ani do
> głowicy — słucha i pokazuje. Władza zostaje na MK32 ([../dok/WLADZA.md](../dok/WLADZA.md)).

---

## Co się z czego składa

```
zrodla.json           ← lokalna konfiguracja utworzona z zrodla.example.json
   │
   ├─► mediamtx/mediamtx.yml   (generowany — nie edytować ręcznie)
   └─► web/public/zrodla.json  (generowany, bez adresów RTSP)

MediaMTX   :8889  obraz — remux RTSP → WebRTC, zero transkodowania
serwer     :8095  strona + API + strumień telemetrii (SSE) + archiwum
archiwum/         tlog/  telemetria (nasz zapis)   wideo/  obraz (zapis MediaMTX)
```

Obciążenie procesora jest bliskie zeru, bo **nic tu nie dekoduje ani nie koduje obrazu**.
MediaMTX przepisuje pakiety, a dekodowaniem zajmuje się przeglądarka widza.

---

## Konfiguracja lokalna

Plik `zrodla.json` zawiera adresy konkretnego stanowiska i nie jest wersjonowany.
Przed pierwszym uruchomieniem utwórz go z bezpiecznego wzoru:

```bash
cp zrodla.example.json zrodla.json
```

Następnie wpisz adres kamery i źródła telemetrii właściwy dla stanowiska. Nie dodawaj
do pliku tokenów ani danych operatorów. Prywatne klucze i lokalne certyfikaty także
nie mogą trafić do repozytorium.

## Uruchomienie

### Raspberry Pi 5 (docelowo) — przez `rpi/`

Pełna procedura: **[../dok/WDROZENIE_RPI.md](../dok/WDROZENIE_RPI.md)**.
Z Windows, z tego katalogu:

```powershell
.\rpi\wgraj.ps1 -Malina dron15.local -Uzytkownik pi -Instaluj
```

Parametr nazywa się `-Malina`, **nie** `-Host` — `$Host` jest w PowerShellu zmienną
tylko-do-odczytu i parametr o tej nazwie wywraca skrypt przy starcie.

Na malinie robotę wykonuje `rpi/instaluj.sh`: zależności, budowa strony, katalog danych
`/var/lib/dron15` i dwie jednostki systemd. Kiosk na monitorach instaluje się osobno
i **bez `sudo`** (potrzebuje sesji graficznej): `sh rpi/kiosk.sh --zainstaluj`.
Przegląd stacji: `sh rpi/sprawdz.sh`.

| Plik w `rpi/` | Do czego |
|---|---|
| `wgraj.ps1` | wysyłka z Windows przez SSH — bez `node_modules` i bez danych stacji |
| `instaluj.sh` | instalacja na malinie, **przez `sudo`** |
| `dron15-mediamtx.service` | obraz; `Restart=always` |
| `dron15-gcs.service` | strona, API, telemetria, archiwum; **`Restart=on-failure`** |
| `dron15-kiosk.service` + `kiosk.sh` | Chromium na monitorach; jednostka **użytkownika** |
| `sprawdz.sh` | przegląd: zasilanie, dekoder HEVC, porty, MTU, zajętość archiwum |
| `dron15-panel.sudoers` | wąskie prawo do restartu usług z panelu STACJA |

> **`on-failure`, nie `always`, dla serwera.** Wyjście kodem 0 znaczy „zatrzymany
> świadomie" i wznawiania nie wymaga; kodem 1 — nieprzechwycony wyjątek, wtedy
> podnosimy ([../dok/LOGI_I_BLEDY.md](../dok/LOGI_I_BLEDY.md) §2). Ta sama zasada,
> którą realizował dozorca `start.sh --pilnuj`, tylko oddana systemd.

Ręcznie, bez systemd (do prób), nadal działa `sh start.sh`. Binarka arm64 leży
w `C:\Soft\nas-arm\mediamtx_arm64` (z projektu NRK) — `wgraj.ps1` wysyła ją sam,
jednorazowo, bo ma 62 MB i nie ma sensu przepychać jej przy każdym wgraniu kodu.

**Dane stacji leżą poza katalogiem projektu** (`/var/lib/dron15`), bo `/opt/dron15`
jest nadpisywane przy każdym wgraniu. Po instalacji edytuje się
`/var/lib/dron15/zrodla.json`, nie ten tutaj.

### Windows (próby na biurku)

```powershell
.\start.ps1
```

Używa `C:\Soft\mediamtx\mediamtx.exe`, żeby nie trzymać drugiej kopii binarki.

### Bez drona — na symulatorze

```bash
python ..\narzedzia\symulator_telemetrii.py --nasluch 127.0.0.1 --port 19856
```

W `zrodla.json` ustaw wtedy `telemetria.host` na `127.0.0.1`. Scenariusze symulatora
(`--scenariusz brak_kursu`, `zaglusz`, `niskie_napiecie`) sprawdzają, czy ostrzeżenia
naprawdę się pokazują.

## Gdy coś nie działa

```
logi/serwer.log      rejestr techniczny — poziomy, obszary, stosy wywołań
logi/konsola.log     surowe wyjście procesu
logi/mediamtx.log    wyjście MediaMTX
```

`POZIOM=szczegol sh start.sh` podnosi gadatliwość. `sh start.sh --pilnuj` podnosi serwer
po awarii. Te same wpisy widać w panelu administratora, sekcja REJESTR TECHNICZNY.
Opis: [../dok/LOGI_I_BLEDY.md](../dok/LOGI_I_BLEDY.md).

## Użytkownicy i dostęp

Serwer wymaga zaproszenia. Przy pierwszym starcie wypisuje na konsolę kod administratora —
otwórz podany link, wejdź, a potem wydawaj imienne zaproszenia z panelu
**ADMIN → ZAPROSZENIA** (imię, rola, ważność, jednorazowość → `ZAPROŚ`).
Procedura krok po kroku: [../dok/DOSTEP_I_UZYTKOWNICY.md](../dok/DOSTEP_I_UZYTKOWNICY.md) §3.

> **Otwórz panel pod adresem, którym łączą się goście**, nie przez `localhost`.
> Link zapraszający składa się z adresu w pasku przeglądarki administratora, więc
> otwarty lokalnie wyprodukuje link działający tylko na tej maszynie. Panel to wykrywa
> i ostrzega, a obok linku podaje **sam kod** — ten działa zawsze, niezależnie od adresu.
> `POKAŻ LINK` przy zaproszeniu odzyskuje kod wydany wcześniej.

Obraz jest chroniony tą samą tożsamością: MediaMTX pyta ten serwer o zgodę przed każdym
odtworzeniem (`authMethod: http` w generowanym `mediamtx.yml`), a odcięcie widza kończy
także jego trwającą sesję WebRTC.

| Zmienna | Domyślnie | Do czego |
|---|---|---|
| `ADMIN_IMIE` | `administrator` | imię pierwszego zaproszenia |
| `DATA_DIR` | katalog serwera | gdzie leży `dostep.json` i `zrodla.json` |

## Adres dostępu — sekcja ŁĄCZA I ADRESY w panelu admina

Dostęp zdalny stoi na WireGuardzie bez serwera koordynującego, więc klient musi wiedzieć,
jaki adres ma dziś router stacji. Panel administratora to pokazuje: endpoint `ADRES:51820`
i adresy w sieci lokalnej. To samo daje `GET /api/adresy` (wymaga roli admina).

| Zmienna | Domyślnie | Do czego |
|---|---|---|
| `ADRES_PUBLICZNY` | — | adres na sztywno; **wyłącza odpytywanie na zewnątrz** |
| `ADRES_PUBLICZNY_URL` | `https://api.ipify.org` | skąd pytamy; `off` wyłącza |
| `ADRES_PUBLICZNY_ODSWIEZ_MS` | 300000 | co ile odświeżać |
| `WG_PORT` | 51820 | port WireGuarda w endpoincie |

> Pokazywany jest adres, spod którego stacja **wychodzi** w świat. Gdy sama siedzi
> za komercyjnym VPN-em, zobaczysz adres wyjścia tego VPN-a — a połączenia przychodzące
> i tak nie zadziałają. Szczegóły: [../dok/SERWER_PODGLADU.md](../dok/SERWER_PODGLADU.md) §6.5.

---

> **Uwaga przy testach:** `DATA_DIR` przenosi tylko *odczyt* `zrodla.json`.
> Wygenerowany `mediamtx/mediamtx.yml` zawsze trafia do katalogu projektu, więc test
> z podmienioną konfiguracją nadpisze plik produkcyjny. Po testach uruchom ponownie
> `node scripts/gen-config.mjs` z właściwym `zrodla.json`.

---

## Konfiguracja — `zrodla.json`

```json
{
  "zrodla": [
    {
      "id": "zr30",
      "nazwa": "ZR30 — głowica",
      "rtspGlowny": "rtsp://192.168.144.25:8554/video1",
      "rtspPomocniczy": "rtsp://192.168.144.25:8554/video2"
    }
  ],
  "telemetria": { "host": "192.168.144.12", "port": 19856 },
  "archiwum": {
    "wlaczone": true,
    "katalog": "archiwum",
    "wideo": "przy-widzach",
    "trzymajDni": 30,
    "limitGb": 50
  }
}
```

Po zmianie: `node scripts/gen-config.mjs` i restart.

**Po co dwa strumienie.** ZR30 wydaje `/video1` i `/video2` niezależnie i do czterech
strumieni naraz. Główny zostaje w H.265 (decyzja z 2026-08-20), a pomocniczy jest drogą
odwrotu, gdyby H.265 okazał się nie do odtworzenia u części widzów — przełącznik jest
na dole strony. Szczegóły: [SERWER_PODGLADU.md §4](../dok/SERWER_PODGLADU.md).

---

## Archiwum

Pełny opis i uzasadnienia: [../dok/WDROZENIE_RPI.md](../dok/WDROZENIE_RPI.md) §6.
W skrócie — dwa strumienie zapisywane zupełnie inaczej:

| Co | Kto zapisuje | Kiedy |
|---|---|---|
| telemetria `.tlog` | ten serwer (`server/archiwum.mjs`) | **zawsze**, gdy ramki przychodzą |
| obraz `.mp4` | MediaMTX, wprost z remuksu | zależnie od trybu |

Ramki MAVLink przez stację przechodzą i tak, więc ich zapis nic nie kosztuje. Obraz
przechodzi **tylko wtedy, gdy ktoś go ogląda** — gdyby zapisywał go ten serwer,
musiałby go dekodować i cała oszczędność z remuksu by przepadła.

| `wideo` | Co zapisuje | Cena |
|---|---|---|
| `nie` | nic | — |
| **`przy-widzach`** | obraz, gdy ktoś patrzy | **lot, którego nikt nie oglądał, nie ma nagrania** |
| `zawsze` | wszystko | strumień leci bez przerwy: obciąża łącze radiowe i zajmuje slot ZR30 |

Przełącznik jest w panelu administratora (sekcja ARCHIWUM) i działa **na żywo** —
przestawia ścieżki przez API MediaMTX, bez restartu, żeby nie zabierać obrazu widzom.

Nagrywany jest **wyłącznie strumień główny**; pomocniczy to droga odwrotu dla
przeglądarek bez H.265, a nie druga kopia do archiwum.

Kasowanie: po czasie (`trzymajDni`) **i po zajętości** (`limitGb`). Drugie musi być
nasze, bo MediaMTX nie umie patrzeć na wolne miejsce — a na karcie w RPi to właśnie
ten limit kończy się pierwszy. **Plik nagrywany w tej chwili jest nietykalny.**

`.tlog` ma format Mission Plannera i QGroundControl
(`[8 B uint64 BE, mikrosekundy][surowa ramka]`), więc otwiera się `pymavlink`-iem
tak samo jak logi z MP. Zapisujemy **każdą** odebraną ramkę, także tę, której serwer
nie dekoduje. Nowy plik powstaje przy pierwszej ramce po ciszy; cisza dłuższa
niż 60 s nagranie zamyka.

| Zmienna | Domyślnie | Do czego |
|---|---|---|
| `ARCHIWUM_DIR` | `DATA_DIR/archiwum` | gdzie lądują nagrania; na RPi wskazuje NVMe |
| `LOGI_DIR` | `logi/` w katalogu serwera | rejestr techniczny |
| `ARCHIWUM_CISZA_S` | 60 | po ilu sekundach ciszy zamknąć `.tlog` |

---

## Panel STACJA

Przycisk **STACJA** na dole strony (rola `admin`). To jest `rpi/sprawdz.sh`
w przeglądarce: stan i restart usług, dławienie zasilania, temperatura, obciążenie,
pamięć, dysk, adresy i MTU interfejsów, nasłuch portów, wersje, dekoder HEVC
i dziennik systemowy per usługa. Opis: [../dok/WDROZENIE_RPI.md](../dok/WDROZENIE_RPI.md) §4a.

Rozróżnienie między dwoma panelami jest celowe:

| Panel | Odpowiada na pytanie |
|---|---|
| **ADMIN** | kto ma wstęp — zaproszenia, widzowie, odcinanie, archiwum |
| **STACJA** | czy sprzęt działa — usługi, zasilanie, sieć, dziennik |

> **Skrypt `rpi/sprawdz.sh` zostaje.** Jest jedyną drogą wtedy, gdy serwer nie wstaje
> — a wtedy panelu też nie ma.

Restart usług wymaga wpisu w `/etc/sudoers.d/dron15-panel` (zakłada go `rpi/instaluj.sh`),
a dziennik — członkostwa w grupie `adm`. Bez tego panel działa dalej: pokazuje stan,
a przy restarcie mówi wprost, czego brakuje.

`server/stacja.mjs` uruchamia **wyłącznie** polecenia z zamkniętej listy, przez
`execFile` z tablicą argumentów. Nazwa usługi z żądania nigdy nie trafia do polecenia
wprost — sprawdzone: `dron15-gcs; rm -rf /` dostaje odpowiedź „Nieznana usługa”.

---

## API

| Ścieżka | Co zwraca |
|---|---|
| `GET /api/zrodla` | lista źródeł obrazu (bez adresów RTSP) |
| `GET /api/stan` | migawka stanu maszyny |
| `GET /api/telemetria` | **strumień SSE**, 10 Hz — tego używa strona |
| `GET /api/status` | czy MediaMTX żyje, stan ścieżek, stan łącza |
| `GET /api/adresy` | adresy IP serwera — dla MK32 i aplikacji natywnych |
| `GET /api/admin/archiwum` | stan archiwum: tryb, zajętość, wolne miejsce, ostatnie pliki |
| `POST /api/admin/archiwum` | zmiana trybu nagrywania i limitów — przestawia ścieżki MediaMTX **na żywo** |
| `POST /api/admin/archiwum/sprzataj` | kasowanie ponad limit na żądanie |
| `GET /api/admin/stacja` | stan maszyny: usługi, zasilanie, sieć, porty, wersje, dekoder |
| `GET /api/admin/stacja/dziennik` | dziennik systemowy jednej usługi (`journalctl`) |
| `POST /api/admin/stacja/restart` | restart usługi — **tylko z zamkniętej listy** |

Format stanu odpowiada [../dok/ARCHITEKTURA.md](../dok/ARCHITEKTURA.md) §3.1.

**Ostrzeżenia liczy serwer, nie klient.** Dzięki temu każdy widz widzi to samo i nowy
klient nie może o nich zapomnieć.

---

## Porty

| Port | Protokół | Rola | W tunelu WireGuard |
|---|---|---|---|
| 8095 | TCP | strona, API, telemetria | **tak** |
| 8889 | TCP | MediaMTX — sygnalizacja WHEP | **tak** |
| 8189 | UDP | MediaMTX — media | **tak** |
| 9997 | TCP | API MediaMTX | **nie** — tylko lokalnie |

Adresu serwera nie konfiguruje się w przeglądarce: strona wyprowadza go z własnego
adresu, więc ten sam build działa w LAN, w tunelu i pod adresem publicznym.

> **Jeśli obraz nie startuje przez tunel, a telemetria działa — sprawdź MTU.**
> To najczęstsza przyczyna i myli, bo wszystko poza obrazem wygląda zdrowo.
> WireGuard zwykle 1420; pod LTE lub PPPoE bywa potrzebne 1280.

---

## Skąd to pochodzi

Rdzeń przeniesiony z działającego systemu podglądu kamer w `C:\Soft` (projekt NRK):
klient WHEP, komponenty React, klient API MediaMTX i generator konfiguracji.
Dołożone tutaj: odbiór telemetrii MAVLink (`server/mavlink.mjs`, `server/telemetria.mjs`),
nakładka OSD i strumień SSE. Usunięte: ONVIF — SIYI go nie ma.
