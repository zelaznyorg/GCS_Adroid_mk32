# Stacja naziemna GCS na Raspberry Pi 5

Opcjonalny element systemu. **Aplikacja na MK32 działa bez niej w pełnym zakresie**
— stacja dokłada to, czego 7-calowy ekran w słońcu nie zrobi dobrze: **duże monitory,
udostępnianie podglądu wielu osobom i archiwum**.

Władza nad maszyną pozostaje na MK32 — patrz [WLADZA.md](WLADZA.md).

> ### Zakres zamknięty 2026-08-20 — [PLAN.md](../PLAN.md) §10, decyzje 4 i 5
>
> **Stacja jest serwerem podglądu z monitorami, nie drugim stanowiskiem operatorskim.**
>
> | Robi | Nie robi |
> |---|---|
> | odbiera obraz z ZR30 i rozdaje przeglądarkom | **nie ma Androida ani Waydroida** |
> | wyświetla na 1–2 monitorach HDMI | **nie uruchamia QGroundControla ani Mission Plannera** |
> | podaje telemetrię po WebSocket | **nie planuje misji** |
> | archiwizuje wideo i `.tlog` | **nie wysyła komend do maszyny** |
>
> Dostęp z zewnątrz: **WireGuard na routerze**, czyli gdy stacja jest w sieci ze stałym
> adresem. W polu za CGNAT dostęp zdalny nie działa i to jest przyjęte —
> [SERWER_PODGLADU.md](SERWER_PODGLADU.md) §6.

---

## 1. Co robi stacja

| Funkcja | Uwaga |
|---|---|
| **podgląd na dużych monitorach** | RPi 5 ma dwa wyjścia micro-HDMI; Chromium w kiosku, strumień H.265 dekodowany **sprzętowo** |
| **udostępnianie obrazu w sieci** | jeden odbiór z kamery, wielu widzów; przeglądarka bez instalowania czegokolwiek |
| **dostęp zdalny** | **WireGuard na routerze** — z sieci ze stałym adresem; nie z pola za CGNAT |
| **archiwum** | nagrania wideo i `.tlog` na dysku stacji, nie na karcie w aparaturze |

**Kamerą stacja steruje od razu** — pitch, yaw, zoom, zdjęcie, nagrywanie — bo głowica
ma osobną drogę i nie dotyka to władzy nad lotem. Operator MK32 może jej to odebrać
jednym dotknięciem.

Czego stacja **nie** robi: nie steruje lotem (dopóki operator MK32 świadomie nie przekaże
jej władzy), **nie planuje misji, nie uruchamia Androida ani standardowych GCS** —
te ostatnie chodzą na zwykłym komputerze, a trasy przenosi się plikiem `.plan`.

---

## 2. Sprzęt

| Element | Wybór | Dlaczego |
|---|---|---|
| komputer | **Raspberry Pi 5, 8 GB** | 4 GB wystarczy do samego przekazywania obrazu, 8 GB daje zapas na przeglądarkę i GCS |
| chłodzenie | aktywne (oryginalny wentylator albo obudowa z radiatorem) | przy ciągłym strumieniu i przeglądarce RPi 5 się grzeje |
| zasilanie | oficjalny zasilacz 27 W USB-C | niedomiar zasilania objawia się losowymi zawieszeniami |
| dysk | NVMe przez HAT albo szybka karta A2 | nagrania wideo zabijają zwykłe karty |
| sieć | Ethernet do MK32 / do sieci pokładowej | pewniejsze niż WiFi w terenie |
| internet (opcja) | z sieci, w której stoi stacja | dostęp zdalny **wymaga stałego adresu** — z 4G za CGNAT nie zadziała, [SERWER_PODGLADU.md](SERWER_PODGLADU.md) §6 |
| monitory | 1–2 × HDMI | to jest funkcja samej stacji, nie aplikacji |

> ### ⛔ KOREKTA 2026-08-20 — powyższa hipoteza była ODWRÓCONA
>
> Zapisano tu, że RPi 5 „nie ma sprzętowego dekodera HEVC takiego jak RPi 4", i że
> ratunkiem jest zejście na H.264. **Jest dokładnie na odwrót.**
>
> **FAKT:** VideoCore VII w RPi 5 **stracił blok H.264** — nie ma ani kodera, ani
> dekodera tego formatu. Został wyłącznie **sprzętowy dekoder HEVC (4Kp60)**.
>
> | Operacja na RPi 5 | Koszt |
> |---|---|
> | **H.265** na monitor HDMI | tanio, sprzętowo |
> | **H.264** na monitor HDMI | programowo, ~1 rdzeń na 1080p |
> | jakiekolwiek transkodowanie | programowy x264 — **wykluczone** |
>
> Skutek dla projektu: dla **monitorów stacji** lepszy jest H.265, dla **przeglądarek**
> H.264. Dlatego nie wybieramy jednego — używamy obu strumieni ZR30 naraz.
>
> **Rzecz ważniejsza od kodeka:** serwer i tak niczego nie dekoduje. MediaMTX
> przepakowuje pakiety (remux), więc jego koszt jest bliski zeru **niezależnie od
> formatu**. Kodek decyduje wyłącznie o tym, co zrozumie odbiorca.
>
> Pełny wywód: [SERWER_PODGLADU.md](SERWER_PODGLADU.md) §3 i §4.
>
> ### ⛔ KOREKTA 2026-08-28 — TA KAMERA NIE DAJE H.265
>
> Powyższe rozważanie („dla monitorów H.265, dla przeglądarek H.264”) zakładało,
> że ZR30 wydaje oba. **Nie wydaje.** Odczyt z kamery (`CMD 0x20`): strumień główny
> to **H.264 1280×720 @1570 kb/s**, a pomocniczy **nie istnieje** (0×0, 0 kb/s).
> Jedyna działająca ścieżka RTSP to `/main.264`; `/video1` i `/video2` dają 404.
> Pełny zapis pomiaru: `..\..\CLAUDE.md` §4.
>
> Skutek: **monitory stacji będą dekodować H.264 programowo**, bo RPi 5 nie ma
> dla niego bloku sprzętowego. Przy 720p to znacznie mniej niż zakładany rdzeń
> na 1080p, ale **liczbę trzeba zmierzyć** (`sh rpi/sprawdz.sh --kiosk`), a nie założyć.
>
> Wyjściem byłoby przestawienie kamery na H.265 przez `CMD 0x21` — ⛔ **nie robić
> bez potrzeby**: źle zbudowana komenda 0x21 zawiesza ZR30 na głucho i ratuje
> tylko cykl zasilania (poz. 57), a dron stoi z podpiętym pakietem i śmigłami.

---

## 3. Oprogramowanie

```
Raspberry Pi OS (64-bit)          ← system bazowy, strony 16 KB, dekoder HEVC sprzętowo
├── dron15-mediamtx.service   obraz: remux RTSP → WebRTC/WHEP, zero transkodowania
├── dron15-gcs.service        nasz serwer: strona, API, telemetria (SSE), archiwum
└── dron15-kiosk.service      Chromium na monitorach (jednostka UŻYTKOWNIKA)
```

**Waydroida i standardowych GCS tu nie ma** — decyzja 4 z 2026-08-20. Powód i czym
zastąpione: [SERWER_PODGLADU.md](SERWER_PODGLADU.md) §7.

> ### 📋 PEŁNA PROCEDURA WDROŻENIA: [WDROZENIE_RPI.md](WDROZENIE_RPI.md)
>
> Ten rozdział mówi, **z czego stacja się składa**. Tamten — **co wpisać i co ma się
> pokazać**: wgranie z Windows (`serwer\rpi\wgraj.ps1`), instalator na malinie
> (`rpi/instaluj.sh`), jednostki systemd, kiosk, archiwum i przegląd stacji
> (`rpi/sprawdz.sh`).
>
> **Stan na 2026-08-23: zestaw jest napisany, ale nie chodził jeszcze na żywej
> malinie.** Podział na to, co sprawdzone, a co nie — [WDROZENIE_RPI.md](WDROZENIE_RPI.md), ramka na górze.

### 3.1 Dane osobno od kodu

| Co | Gdzie |
|---|---|
| kod | `/opt/dron15` — **nadpisywany przy każdym wgraniu** |
| konfiguracja, użytkownicy, nagrania, logi | `/var/lib/dron15` — przeżywa aktualizację |

Po instalacji edytuje się **`/var/lib/dron15/zrodla.json`**, nie ten w katalogu projektu.

### 3.2 Kiosk

Instaluje się osobno i **bez `sudo`** — potrzebuje sesji graficznej, której systemowa
jednostka nie widzi:

```bash
KOD=<kod-dla-monitorow> sh rpi/kiosk.sh --zainstaluj
sudo loginctl enable-linger $(id -un)     # żeby wstawał bez logowania człowieka
```

**Kiosk jest zwykłym widzem i potrzebuje zaproszenia** — wielokrotnego i bezterminowego,
bo każde okno ma własny profil Chromium. Bez kodu na monitorach stanie ekran wejścia.
Szczegóły: [WDROZENIE_RPI.md](WDROZENIE_RPI.md) §4.

Domyślnie obsadza dwa monitory 1080p obok siebie, każdy własnym oknem Chromium
(osobny profil na okno — bez tego drugie uruchomienie otwiera kartę w pierwszej
instancji zamiast okna na drugim ekranie). Jeden monitor: `EKRANY="1920x1080+0+0"`.

**Flagi sprzętowego dekodowania są HIPOTEZĄ**, nie pomiarem — rozstrzyga
`chrome://media-internals` podczas odtwarzania (zadanie 2.2 z [TODO.md](../TODO.md)).

### 3.3 Obsługa bez ssh — panel STACJA

Przycisk **STACJA** na dole strony (rola `admin`) pokazuje to samo, co `rpi/sprawdz.sh`:
stan i restart usług, dławienie zasilania, temperaturę, obciążenie, dysk, MTU interfejsów,
nasłuch portów, wersje, dekoder HEVC i dziennik systemowy. Skrypt zostaje — jest jedyną
drogą wtedy, gdy serwer nie wstaje. Opis i uprawnienia:
[WDROZENIE_RPI.md](WDROZENIE_RPI.md) §4a.

### 3.4 Archiwum

Telemetria `.tlog` — zawsze, w formacie Mission Plannera. Obraz — przez MediaMTX,
w jednym z trzech trybów, domyślnie **tylko gdy ktoś ogląda**. Kasowanie starych
nagrań po czasie **i po zajętości dysku**. Opis i uzasadnienia:
[WDROZENIE_RPI.md](WDROZENIE_RPI.md) §6.

---

## 4. Połączenie z MK32

| Co | Skąd | Dlaczego tak |
|---|---|---|
| **obraz** | wprost z ZR30 (`rtsp://192.168.144.25:8554/video1`) | kamera wydaje do 4 strumieni; MK32 nie musi nic robić |
| **telemetria** | z MK32, port TCP 5760 albo UDP 14550 | jedno łącze radiowe, rozgałęzione po stronie ziemi |
| **komendy lotu** | z MK32, wyłącznie po przekazaniu władzy | patrz [WLADZA.md](WLADZA.md) |
| **komendy kamery** | z MK32, **dostępne od razu** | stacja ma kamerę domyślnie; operator MK32 może ją odebrać jednym dotknięciem |

Stacja wpina się w sieć pokładową `192.168.144.0/24`. Adres zalecany przez SIYI dla
komputera GCS to **`.30`** (sekcja 4 `..\..\CLAUDE.md`).

> **Uwaga o egzekwowalności.** W tej sieci stacja widzi też port telemetrii jednostki
> naziemnej (`.12:19856`) i głowicę (`.25:37260`), więc technicznie mogłaby ominąć filtr
> władzy w aplikacji. Dopóki stacją i dronem zarządza ta sama osoba, wystarcza wariant
> z czujką obcych komend. Wariant z pełnym odcięciem opisuje
> [WLADZA.md](WLADZA.md), sekcja 9 — kosztuje przekazywanie obrazu przez MK32.

**Uwaga o zasilaniu:** stacja nie może wisieć na tym samym zasilaniu, co aparatura.
MK32 ma pracować z własnej baterii przez ~10 h; RPi 5 pod obciążeniem bierze
kilkanaście watów i rozładowałby ją w godzinę.

---

## 5. Wariant „stacja przy operatorze" i „stacja zdalna"

| Wariant | Gdzie stoi RPi | Łącze do MK32 | Uwagi |
|---|---|---|---|
| **przy operatorze** | w tym samym miejscu | Ethernet | najprostszy; monitory pod ręką, zero opóźnień |
| ~~zdalna~~ | — | — | **skreślony 2026-08-20** |

> **Po decyzji 5 „stacja zdalna" przestała być osobnym wariantem.** Stacja stoi przy
> operatorze, w sieci pokładowej, i to ona zbiera obraz. „Zdalnie" znaczy dziś wyłącznie
> **dostęp do niej przez tunel WireGuard**, gdy jest w bazie — a nie inna topologia.
>
> Skutek uboczny wart odnotowania: **MK32 nigdy nie przepakowuje obrazu.** Wariant,
> w którym aparatura wypycha strumień przez SRT, znika z planu razem z zależnością
> od porzuconego `ffmpeg-kit` ([PLAN.md](../PLAN.md) §3).

---

## 6. Kolejność wdrożenia

Stacja jest **poza ścieżką krytyczną**. Kolejność w [PLAN.md](../PLAN.md):
najpierw kompletna aplikacja samodzielna (M1–M4), potem udostępnianie i władza (M5),
potem stacja (M6), a na końcu dostęp zdalny przez WireGuard (M7).

Wcześniejsze wzięcie się za stację skończyłoby się aplikacją, która bez niej nie działa
— czyli dokładnym odwróceniem tego, co ma być.
