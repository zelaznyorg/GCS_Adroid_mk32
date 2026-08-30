# Architektura

Uzupełnienie [PLAN.md](../PLAN.md). Tu leży kontrakt: budowa aplikacji, porty,
protokół parowania, zachowanie przy awariach.

---

## 1. Budowa aplikacji na MK32

```
                      ┌──────────────────────────────────────────┐
   FC ──radio──►      │  ADAPTERY            RDZEŃ               │
   (UDP 19856)        │  MavlinkLink ──┐                         │
                      │                ├──► VehicleState         │
   ZR30 ──LAN──►      │  SiyiClient ───┤    SafetyEvaluator      │──► UI (6 widoków)
   (RTSP + UDP 37260) │  VideoSource ──┘    PreflightChecker     │
                      │                     MissionService       │
                      │                     GnssWatchdog         │
                      └──────────────┬───────────────────────────┘
                                     │
                      ┌──────────────▼───────────────────────────┐
                      │  MODUŁ UDOSTĘPNIANIA  (wyłączalny)       │
                      │  MavlinkHub · StateApi · AuthorityGate   │──► stacja GCS, QGC
                      └──────────────────────────────────────────┘
```

**Rdzeń nie wie o istnieniu modułu udostępniania.** Zależność idzie tylko w jedną stronę.
Sprawdzian, który ma przechodzić przez cały czas życia projektu: **kompilacja bez modułu
udostępniania daje działającą aplikację** z pełnym kokpitem, misjami i checklistą.

| Warstwa | Składniki |
|---|---|
| **Adaptery** | `MavlinkLink` (UDP 19856), `SiyiClient` (UDP 37260), `VideoSource` (libVLC), `TlogWriter` |
| **Rdzeń** | `VehicleState` (jeden `StateFlow`), `SafetyEvaluator`, `PreflightChecker`, `MissionService`, `GnssWatchdog` |
| **UI** | `CockpitScreen`, `MissionScreen`, `CameraScreen`, `PreflightScreen`, `DiagnosticsScreen`, `SettingsScreen` |
| **Udostępnianie** | `MavlinkHub` (rozgałęzienie), `StateApi` (WebSocket), **`AuthorityGate`** (dwutorowy filtr komend, per ramka), `ObcyNadawcaDetektor`, `SiyiProxy` (wariant zapasowy) |

---

## 2. Porty

Aktywne **tylko przy włączonym module udostępniania**. W trybie samodzielnym aplikacja
nie nasłuchuje na niczym.

| Port | Protokół | Kto korzysta |
|---|---|---|
| — | UDP → `192.168.144.12:19856` | `MavlinkLink` w górę — jedyne połączenie do maszyny |
| — | UDP → `192.168.144.25:37260` | `SiyiClient` — głowica, z pominięciem FC |
| 14550 | UDP | QGC, Mission Planner, stacja |
| 5760 | TCP | stacja, klienci przez VPN |
| 8081 | WebSocket | stacja i klienci lekcy (stan + komendy) |

Stacja GCS bierze obraz **prosto z ZR30** (`rtsp://192.168.144.25:8554/video1`),
nie przez MK32 — uzasadnienie w [WLADZA.md](WLADZA.md), sekcja 3.

Porty stacji i serwera pośredniczącego: [GCS_RPI5.md](GCS_RPI5.md), [WIDEO.md](WIDEO.md).

---

## 3. Protokół parowania — WebSocket

Kanał między MK32 a stacją. QGC i Mission Planner go nie potrzebują — idą prosto
na 14550/5760 i dostają zwykły MAVLink.

### 3.1 MK32 → stacja: `stan`, 10 Hz

```json
{
  "typ": "stan",
  "czas": 1755500000.123,
  "wladza": { "lot": "MK32", "kamera": "stacja", "od_sekund": null, "do_konca_s": null },
  "lot": { "tryb": "ALTHOLD", "uzbrojony": false, "wysokosc_m": 12.4,
           "wznoszenie_ms": 0.3, "predkosc_ms": 2.1, "gaz_proc": 48 },
  "polozenie": { "lat": 52.123456, "lon": 20.123456, "kurs_deg": 47.0,
                 "kurs_zrodlo": "gnss", "home_m": 132.5 },
  "gnss": { "satelity": 18, "hdop": 0.7, "fix": "3D", "kurs_dostepny": true },
  "ekf": { "flagi": "0x033F", "wariancja_kursu": 0.02, "pozycja_ok": true },
  "bateria": { "napiecie_v": 24.1, "na_ogniwo_v": 4.02, "prad_a": 12.3 },
  "glowica": { "pitch": -34.0, "yaw": 12.0, "zoom": 4.5, "nagrywa": false },
  "misja": { "aktywna": false, "punkt": 0, "punktow": 0 },
  "lacza": { "telemetria_hz": 11.3, "sekund_od_heartbeatu": 0.2, "wideo_klatki_s": 25 },
  "ostrzezenia": [ { "poziom": "blokada", "id": "kurs_gnss",
                     "tekst": "BRAK KURSU GNSS — RTL I MISJA NIEDOSTĘPNE" } ]
}
```

`ostrzezenia` liczy **MK32**, nie klient. Dzięki temu operator na stacji widzi dokładnie
ten sam alarm, co pilot, i nowy klient nie może o nim zapomnieć.

### 3.2 Pozostałe typy

| `typ` | Kierunek | Treść |
|---|---|---|
| `komunikat` | → stacja | `STATUSTEXT` z FC |
| `misja` | ↔ | pełna trasa, geofence, punkty zbiórki |
| `wladza` | → stacja | zmiana: kto steruje, ile czasu zostało, powód odebrania |
| `prosba_o_sterowanie` | stacja → MK32 | kto prosi, po co, na jak długo |
| `kamera` | stacja → MK32 | tylko dla wariantu zapasowego `SiyiProxy`; normalnie stacja steruje kamerą zwykłym MAVLinkiem |
| `komenda` | stacja → MK32 | `rtl`, `tryb`, `glowica_kat`, `zoom`, `misja_wyslij`, … |
| `ack` | → stacja | `ok` / `odrzucone` / `timeout` + powód |

### 3.3 Filtr `AuthorityGate`

Każda komenda ze stacji przechodzi przez jedno miejsce w kodzie:

Filtr rozstrzyga **per ramka MAVLink**, nie per pakiet — w jednym strumieniu TCP potrafią
sąsiadować komenda kamery i komenda lotu, a każda ma inne uprawnienie.

```
ramka od stacji
   ├─ msgid 287/288 albo COMMAND_* z MAV_CMD kamery  → tor KAMERA (domyślnie wolno)
   ├─ COMMAND_* z ROI (195/197/201)                  → tor LOT — obraca maszynę!
   ├─ inne komendy (SET_MODE, MISSION_*, PARAM_SET…) → tor LOT (trzeba władzy)
   ├─ uzbrojenie                                     → NIGDY — jest na CH9
   ├─ zapis parametru                                → NIGDY — fc_write_params.py
   └─ odczyty, heartbeat                             → przepuszczane zawsze
```

Klasyfikacja: pole `command` leży w ładunku `COMMAND_LONG` i `COMMAND_INT` pod tym samym
przesunięciem 28 bajtów (sprawdzone w `ordered_fieldnames` pymavlinka).
Zbiory `KAMERA_CMD`, `KAMERA_MSGID`, `ROI_CMD` w `mav_router.py`.

**Dwa niezależne stany**, bo takie zapadły ustalenia (decyzja 1 w PLAN):

| Stan | Wartość początkowa | Zmienia |
|---|---|---|
| `wladzaNadLotem` | `MK32` | przekazanie: przytrzymanie 2 s; odebranie: dotknięcie |
| `kameraDlaStacji` | **`true`** | odebranie: dotknięcie; przywrócenie: dotknięcie |

Odrzucenie **zawsze** wraca do stacji z powodem i **zawsze** trafia do logu zdarzeń.
Cichy brak reakcji byłby gorszy niż odmowa.

### Dwie drogi do głowicy

| Kto | Droga | Dlaczego |
|---|---|---|
| kokpit MK32 | `SiyiClient` — UDP prosto do ZR30 | zero pasma na radiu, niezależne od FC |
| stacja | **MAVLink przez `AuthorityGate`** | jeden filtr, zero nowego kodu, QGC działa bez zmian |
| misje | MAVLink | ROI i zadania kamery inaczej się nie da |

Droga MAVLinkowa przechodzi przez sterownik mounta w FC, czyli przez łącze szeregowe
FC↔ZR30 z historią awarii (poz. 28 `..\..\CLAUDE.md`). Skutek uboczny jest korzystny:
**jeśli to łącze znowu padnie, kamerę traci stacja, a nie operator.**

`SiyiProxy` — przekazywanie komend SIYI z kanału parowania — zostaje jako wariant
zapasowy, gdyby droga MAVLinkowa okazała się za wolna albo znów zawodna.

**Obraz to inna sprawa** — stacja bierze go prosto z kamery, bo strumieniem nie da się
niczego poruszyć, a MK32 nie musi go wtedy przepakowywać.

### `ObcyNadawcaDetektor`

`COMMAND_ACK` z maszyny, gdy przez `AuthorityGate` nie przeszła żadna komenda, znaczy,
że komendę wydał ktoś spoza naszej drogi. Wtedy: **baner na cały ekran** i wpis do logu.
Granice tego mechanizmu i warianty sieciowe: [WLADZA.md](WLADZA.md), sekcja 9.
Prototyp: `..\narzedzia\mav_router.py`, przetestowany.

---

## 4. Zachowanie przy awariach

| Co pada | Co się dzieje |
|---|---|
| RTSP z ZR30 | ostatnia klatka przygaszona + licznik prób; **telemetria działa** |
| telemetria z FC | baner „UTRATA TELEMETRII"; obraz i głowica **działają** (inna droga) |
| **stacja GCS** | **władza nad lotem i kamera wracają do MK32 po 3 s ciszy**; kokpit bez zmian; maszyna bez zmian |
| moduł udostępniania | kokpit nietknięty — nie ma od niego zależności |
| 4G / internet | dotyczy wyłącznie widowni; lot toczy się normalnie |

Failsafe GCS (`FS_GCS_ENABLE`) zostaje **wyłączony**: zerwanie łącza ze stacją albo
z przeglądarką nie może sprowadzać drona. Aplikacja nadaje heartbeat, więc technicznie
dałoby się go włączyć — ale to osobna, świadoma decyzja.

---

## 5. Prototypy, które już działają

| Narzędzie | Odpowiada modułowi | Stan |
|---|---|---|
| [`mav_router.py`](../narzedzia/mav_router.py) | `MavlinkHub` + `AuthorityGate` + `ObcyNadawcaDetektor` | **działa** — rozgałęzienie UDP+TCP, przekazywanie i odbieranie władzy z automatycznym powrotem po ciszy stacji, wykrywanie komend wydanych z pominięciem filtra |
| [`siyi_gimbal.py`](../narzedzia/siyi_gimbal.py) | `SiyiClient` | **działa** — ramkowanie potwierdzone przykładami producenta, sterowanie i ustawianie kodeka |
| [`restream.py`](../narzedzia/restream.py) | retransmisja (stacja / wariant zdalny) | buduje i uruchamia łańcuch ffmpeg |
| [`probe_endpoints.py`](../narzedzia/probe_endpoints.py) | — | etap M0 |
| [`relay/mediamtx.yml`](../narzedzia/relay/mediamtx.yml) | stacja / serwer pośredniczący | konfiguracja gotowa |

Próba przekazania władzy, przeprowadzona 2026-08-18:

```
stan początkowy   wladza: nikt
bez władzy        0 komend przeszło w górę
/przekaz          3 z 3 przeszły
/odbierz          znowu 0, licznik stoi
cisza 3 s         władza wróciła sama
```

Próba czujki obcych komend, 2026-08-18: maszyna potwierdza `MAV_CMD 20`, przez filtr
nie przeszło nic → alarm zgłoszony przy pierwszym, piątym i dwudziestym wystąpieniu,
licznik w raporcie okresowym.

Próba filtra dwutorowego, 2026-08-18 — jeden strumień TCP, dwie komendy obok siebie:

```
stan: lot=nikt, kamera=wszyscy
ZOOM(531) + RTL(20)        → do maszyny doszło [531]        RTL odrzucony
/odbierz-kamere            → do maszyny doszło []           oba tory zamknięte
/przekaz + /przekaz-kamere → do maszyny doszło [531, 20]    oba tory otwarte
```
