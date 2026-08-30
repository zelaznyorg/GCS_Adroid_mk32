# Interfejsy aplikacji DRON15 Cockpit

Wszystko, co aplikacja musi wiedzieć o świecie zewnętrznym. Każde twierdzenie ma status
wg konwencji z sekcji 8 `..\..\CLAUDE.md`.

Odniesienia do stron dotyczą **numeracji stron PDF** (nie nadruku w stopce) w plikach
`..\..\dok\_txt_MK32_User_Manual_v1_5_668100b6.txt` i
`..\..\dok\_txt_ZR30_User_Manual_v1_4_EN_0fefec4e.txt` (znaczniki `===== PAGE n =====`).

---

## 1. Telemetria MAVLink — UDP z jednostki naziemnej MK32

| Cecha | Wartość | Status |
|---|---|---|
| Adres | `192.168.144.12` (ground unit) | **FAKT** — instrukcja MK32, rozdz. 4.4, str. 89 |
| Port | **19856/UDP** | **FAKT** — j.w. |
| Rola | jednostka naziemna jest serwerem, GCS klientem | **FAKT** — instrukcja opisuje "Server Addresses" |
| Protokół | MAVLink 2, dialekt ardupilotmega | **FAKT** — FC `SERIAL6_PROTOCOL=2` |
| Warunek wstępny | w aplikacji **SIYI TX**: *Datalink → Connection = **UDP***, *Flight Controller = PX4/ArduPilot*, *Baud Rate = 115200* | **FAKT** — instrukcja 4.4.1 |
| Firmware jednostki naziemnej | v0.2.6 lub nowszy (wymóg podany dla Mission Plannera) | **FAKT** — instrukcja, str. 91 |

**HIPOTEZA do sprawdzenia w M0:** czy przy `Connection = UDP` telemetria nadal dociera do
aplikacji SIYI FPV. Jeśli nie — tym lepiej, potwierdza to decyzję o zastąpieniu SIYI FPV.

**HIPOTEZA do sprawdzenia w M0:** czy do portu 19856 może być podłączony więcej niż jeden
klient naraz (my + QGC na laptopie .30). Instrukcja MK32 odradza równoległe uruchamianie
SIYI FPV i QGC — powód nie jest podany.

### Co aplikacja wysyła

| Wiadomość | Kiedy | Uwaga |
|---|---|---|
| `HEARTBEAT` (typ `MAV_TYPE_GCS`) | 1 Hz, zawsze | tego **nie robi** SIYI FPV — poz. 34 |
| `PARAM_REQUEST_LIST` / `PARAM_REQUEST_READ` | przy połączeniu i na żądanie | do checklisty |
| `COMMAND_LONG` | tylko na akcję użytkownika | `MAV_CMD_DO_SET_MODE`, `MAV_CMD_NAV_RETURN_TO_LAUNCH`, `MAV_CMD_RUN_PREARM_CHECKS` |
| `REQUEST_DATA_STREAM` / `SET_MESSAGE_INTERVAL` | **domyślnie nigdy** | przy `SERIAL6_OPTIONS=4096` FC to ignoruje — patrz PLAN 4.4 |

### Co aplikacja odbiera i do czego

| Wiadomość | Użycie |
|---|---|
| `HEARTBEAT` | tryb lotu, uzbrojenie, watchdog łącza |
| `SYS_STATUS`, `BATTERY_STATUS` | napięcie, prąd, zużycie |
| `GPS_RAW_INT` | satelity, HDOP, fix, **`yaw` (65535 = brak kursu)** |
| `GLOBAL_POSITION_INT`, `VFR_HUD` | wysokość, prędkości, kurs |
| `ATTITUDE` | horyzont, a także **materiał do przekazania głowicy komendą `0x22`** (patrz 3.4) |
| `EKF_STATUS_REPORT` | flagi EKF, wariancja kursu → dostępność RTL |
| `STATUSTEXT` | komunikaty PreArm i ostrzeżenia |
| `PARAM_VALUE` | checklista przedlotowa |
| `COMMAND_ACK` | potwierdzenia komend |
| `RADIO_STATUS` | jakość łącza (jeśli MK32 to wystawia — **HIPOTEZA**, do sprawdzenia w M0) |

---

## 2. Wideo — RTSP z ZR30

| Cecha | Wartość | Status |
|---|---|---|
| Strumień główny | `rtsp://192.168.144.25:8554/video1` | **FAKT** — instrukcja ZR30, str. 108 |
| Podgląd | `rtsp://192.168.144.25:8554/video2` | **FAKT** — j.w. |
| Kodek | H.265, do 4K@25, 12 Mbps | **DEKLARACJA** producenta (karta ZR30) |
| Liczba równoległych strumieni | do 4 z tego samego adresu | **FAKT** — instrukcja ZR30, rozdz. 2.3.3 |
| Zmiana kodeka/rozdzielczości | komenda SDK `0x21` | **FAKT** — instrukcja ZR30, rozdz. 3.5.2 |

**Uwaga o adresacji:** ZR30 należy do starszej rodziny SIYI — ścieżki `/video1` i `/video2`,
**nie** `/video0` (sekcja 4 `..\..\CLAUDE.md`).

**HIPOTEZA do sprawdzenia w M1:** czy dekoder MK32 (Android 9) radzi sobie z H.265 4K
sprzętowo. Jeśli nie — zejść na `/video2` i/lub przestawić kodek komendą `0x21`.

Parametry libVLC na start (do strojenia w M1):
`--rtsp-tcp`, `--network-caching=150`, `--clock-jitter=0`, `--clock-synchro=0`, `--no-audio`.

---

## 3. Sterowanie głowicą — SIYI Gimbal SDK

### 3.1 Łącze

| Cecha | Wartość | Status |
|---|---|---|
| UDP | `192.168.144.25:37260` | **FAKT** — instrukcja ZR30, rozdz. 3.5.3, str. 65 |
| TCP | `192.168.144.25:37260`, heartbeat `556601010000000000598B` | **FAKT** — j.w. |
| Wybór | **UDP** | prostsze, bezstanowe, wystarczające |

### 3.2 Ramka

```
0x55 0x66 | CTRL(1) | DATA_LEN(2, LE) | SEQ(2, LE) | CMD_ID(1) | DATA(n) | CRC16(2, LE)
```

`CTRL`: 0 = wymaga ACK, 1 = pakiet ACK. `CRC16` liczony z **całego** pakietu przed sumą.

**CRC16 = CRC-16/XMODEM** (wielomian `0x1021`, init `0x0000`, bez odbicia, bez XOR-out),
zapisany **młodszym bajtem naprzód**.

> **Status: FAKT — zweryfikowane obliczeniowo 2026-08-18** na czterech przykładach
> z instrukcji: `5566010000000040`→`819c`, `...0019`→`5d57`, `...0016`→`b2a6`,
> `...0018`→`7c47`. Wszystkie cztery zgadzają się co do bajtu.
> Implementacja odniesienia: `..\narzedzia\siyi_gimbal.py`.

### 3.3 Komendy używane przez aplikację

| CMD | Nazwa | Dane wysyłane | Odpowiedź |
|---|---|---|---|
| `0x01` | wersja firmware | — | wersje kamery/gimbala/zoomu |
| `0x05` | zoom ręczny | `int8`: 1 = do wewnątrz, 0 = stop, −1 = na zewnątrz | `uint16` krotność ×10 |
| `0x0F` | zoom bezwzględny | `uint8` część całkowita (1–30), `uint8` część dziesiętna | — |
| `0x07` | obrót | `int8 turn_yaw`, `int8 turn_pitch`, oba −100…100, **0 = stop przy puszczeniu** | `sta` |
| `0x08` | centrum | `uint8 = 1` | `sta` |
| `0x0E` | kąt bezwzględny | `int16 yaw`, `int16 pitch` — **stopnie ×10** | bieżące yaw/pitch/roll |
| `0x0D` | orientacja | — | yaw, pitch, roll + prędkości, wszystko **÷10** |
| `0x0A` | konfiguracja | — | m.in. `record_sta`, `gimbal_motion_mode` (0 Lock / 1 Follow / 2 FPV) |
| `0x0C` | foto i nagrywanie | `uint8`: 0 = zdjęcie, 2 = start/stop REC, 3 = Lock, 4 = Follow | — |
| `0x0B` | informacja zwrotna | — | sukces / brak karty TF / HDR |
| `0x20` | odczyt kodeka | `uint8` typ strumienia | kodek, rozdzielczość, bitrate, klatki |
| `0x21` | **ustawienie kodeka** | typ, kodek (1 = H.264, 2 = H.265), szer., wys., kbps | `sta` |
| `0x25` | żądanie strumienia danych | typ + częstotliwość | głowica sama wysyła orientację |

**Zakresy ZR30 (FAKT, instrukcja str. 52–53):** yaw −270…270°, pitch −90…+25°.
Zgadza się z `MNT1_PITCH_MIN/MAX` na FC.

### 3.4 Sztuczka wartościowa dla tej maszyny — `0x22`

`CMD 0x22` = **przekazanie głowicy orientacji kontrolera lotu** (6 × `float`, radiany:
roll, pitch, yaw + prędkości kątowe). Producent zaleca podawanie tych danych, żeby
poprawić stabilizację przy gwałtownych manewrach — na FC odpowiada temu `SRn_EXTRA1=50`
(poz. 12 `..\..\CLAUDE.md`).

**Aplikacja może to zrobić sama:** odbiera `ATTITUDE` z MAVLinka i przekazuje je do ZR30
po UDP komendą `0x22`. Ścieżka omija szeregowe łącze FC↔głowica, które w tym projekcie
było źródłem kłopotów (poz. 28).

**HIPOTEZA:** nazwa komendy mówi "to Gimbal **UART** Port", więc możliwe, że po UDP jest
ignorowana. Do sprawdzenia w M4 — koszt sprawdzenia jest zerowy, zysk spory.

### 3.5 Kodowanie strumieni — podstawa retransmisji

Trzy strumienie ZR30 są niezależne (**FAKT**, instrukcja rozdz. 3.5.2):
`0` nagrywanie, `1` główny, `2` podgląd.

Ustawienie strumienia głównego na **H.264 1280×720** pozwala przesyłać obraz dalej
**bez transkodowania**, przy zachowaniu 4K H.265 na karcie w kamerze.
Szczegóły i konsekwencje: [WIDEO.md](WIDEO.md).

> **HIPOTEZA:** instrukcja zastrzega, że `stream_type = 2` działa tylko na ZT30 i ZT6.
> Na ZR30 sterowalny jest zapewne wyłącznie strumień główny — do sprawdzenia w M0
> poleceniem `python narzedzia\siyi_gimbal.py codec --strumien podglad`.

---

## 3c. Kamera i głowica **po MAVLinku** — druga droga

Głowicą da się sterować nie tylko po UDP, ale też zwykłym MAVLinkiem, bo ArduPilot
wystawia menedżera głowicy i kamerę (na tej maszynie `MNT1_TYPE=8`, `CAM1_TYPE=4`).
Wartości zweryfikowane w dialekcie `ardupilotmega` (pymavlink) — **FAKT**:

| Komenda / wiadomość | Wartość | Do czego |
|---|---|---|
| `MAV_CMD_DO_GIMBAL_MANAGER_PITCHYAW` | 1000 | kąt albo prędkość głowicy |
| `GIMBAL_MANAGER_SET_PITCHYAW` | msgid 287 | to samo, strumieniowo |
| `GIMBAL_MANAGER_SET_MANUAL_CONTROL` | msgid 288 | sterowanie ciągłe, jak drążkiem |
| `MAV_CMD_DO_MOUNT_CONTROL` | 205 | starszy sposób ustawienia kąta |
| `MAV_CMD_SET_CAMERA_ZOOM` | 531 | zoom |
| `MAV_CMD_SET_CAMERA_FOCUS` | 532 | ostrość |
| `MAV_CMD_IMAGE_START_CAPTURE` / `_STOP_` | 2000 / 2001 | zdjęcie, seria |
| `MAV_CMD_VIDEO_START_CAPTURE` / `_STOP_` | 2500 / 2501 | nagrywanie |
| `CAMERA_INFORMATION` / `CAMERA_SETTINGS` / `CAMERA_CAPTURE_STATUS` | msgid 259 / 260 / 262 | **odczyt** stanu kamery |
| `GIMBAL_MANAGER_STATUS` / `GIMBAL_DEVICE_ATTITUDE_STATUS` | msgid 281 / 285 | **odczyt** orientacji głowicy |

**To znaczy, że stan głowicy i kamery da się pobrać bez odpytywania ZR30 po UDP** —
`GIMBAL_DEVICE_ATTITUDE_STATUS` na tej maszynie przychodzi (potwierdzone 2026-08-15,
poz. 28 `..\..\CLAUDE.md`).

### Czym to się różni od drogi UDP

| | MAVLink (przez FC) | SIYI SDK po UDP |
|---|---|---|
| trasa | GCS → MK32 → radio → FC → **UART → ZR30** | LAN → ZR30 |
| zależy od sterownika mounta w FC | **tak** | nie |
| działa przy `MNT1_TYPE=0` | nie | **tak** |
| koszt pasma na łączu 115 200 | jest | żaden |
| opóźnienie | radio + planista FC | sieć lokalna |
| ROI, zadania kamery w misji | **jedyna droga** | nie dotyczy |
| ustawienie kodeka (`0x21`), zoom bezwzględny | nie | **tak** |

> **Uwaga dla tej maszyny.** Droga MAVLinkowa przechodzi przez łącze szeregowe FC↔ZR30,
> czyli **dokładnie to, które było zepsute** i które potrafiło zamilknąć po kilkunastu
> minutach od startu (poz. 28). Od 2026-08-15 działa na `SERIAL2`, ale historia każe
> traktować je jako słabsze ogniwo niż LAN.

---

## 3a. Protokół misji MAVLink

Osobny dokument: [MISJE.md](MISJE.md). Wartości `msgid` i `MAV_CMD` zweryfikowane
w definicjach dialektu `ardupilotmega` (pymavlink) — **FAKT**.

---

## 3b. Porty serwera na MK32

Pełna mapa: [ARCHITEKTURA.md](ARCHITEKTURA.md), sekcja 1.
W skrócie: 14550/UDP i 5760/TCP dla klientów MAVLink, 8080 HTTP i 8081 WebSocket
dla klientów lekkich, 8554 RTSP dla podglądu w sieci lokalnej, SRT wychodzący
na serwer pośredniczący.

---

## 4. Jednostka naziemna MK32 — SDK (opcjonalnie, poza M0–M6)

Ta sama ramka `0x5566` co w głowicy (instrukcja MK32, rozdz. 4.8).
Ciekawe komendy: `0x40` ID sprzętu, `0x16` ustawienia systemowe — w tym
**`Rc_bat`, poziom baterii aparatury ×10 V**, oraz ustawiona prędkość transmisji telemetrii.

**HIPOTEZA:** instrukcja nie podaje adresu ani portu dla SDK jednostki naziemnej.
Do ustalenia eksperymentalnie (prawdopodobnie `192.168.144.12`), jeśli w ogóle zechcemy
pokazywać stan baterii aparatury.

---

## 5. Środowisko na MK32

| Cecha | Wartość | Status |
|---|---|---|
| System | Android 9.0, 4 GB RAM, 64 GB ROM | **FAKT** — instrukcja MK32, str. 22 |
| Ekran | 7", "high definition", rozdzielczość **niepodana w instrukcji** | do zmierzenia: `adb shell wm size` |
| Instalacja aplikacji | karta TF, pendrive USB-A albo Type-C w trybie File Transfer | **FAKT** — instrukcja, rozdz. 7.2 |
| Ostrzeżenie producenta | *"avoid installing useless apps to avoid possible system overwhelming during flight"* | **FAKT** — rozdz. 7.2 |
| Porty | LAN (Ethernet), USB-A, Type-C, TF, HDMI out, slot SIM | **FAKT** — str. 23 |

**Wniosek z ostrzeżenia producenta:** aplikacja ma być lekka i ma **zastępować**
SIYI FPV, a nie dokładać się do niego.

---

## 6. Adresacja sieci pokładowej

Za sekcją 4 `..\..\CLAUDE.md`:

| IP | Węzeł |
|---|---|
| .11 | MK32 air unit |
| .12 | MK32 ground unit — **źródło telemetrii UDP 19856** |
| .20 | MK32 — system Android (**tu działa nasza aplikacja**) |
| .25 | **ZR30** — RTSP 8554, SDK UDP 37260 |
| .30 | laptop / QGC Windows |
