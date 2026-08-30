# Wdrożenie stacji na Raspberry Pi 5 — instrukcja krok po kroku

Zakres stacji i uzasadnienia decyzji: [GCS_RPI5.md](GCS_RPI5.md).
Architektura serwera: [SERWER_PODGLADU.md](SERWER_PODGLADU.md).
Ten plik jest **procedurą**, nie wywodem — mówi, co wpisać i co ma się pokazać.

> ### ✅ WDROŻONE 2026-08-28 — STACJA DZIAŁA NA ŻYWEJ MALINIE
>
> Maszyna: **`GSB`, `192.168.88.30`** (statycznie, od 2026-08-28), Raspberry Pi 5 Model B Rev 1.1 / 8 GB,
> Debian 13 (trixie), jądro 6.18.34, `throttled: 0x0`, 39 GB wolne.
>
> | Sprawdzone na miejscu | Wynik |
> |---|---|
> | `dron15-mediamtx`, `dron15-gcs` | **active / enabled**, porty 8095 · 8889 · 9997 · 8189 nasłuchują |
> | MediaMTX | **v1.19.0** — wersja wymagana przez `authMethod: http` i ubijanie sesji |
> | **telemetria z rozgałęźnika MK32** | **43 196 ramek, 0 błędów**, łącze żywe, 0,6 s od heartbeatu |
> | dane z maszyny | `ALTHOLD`, rozbrojony, 24,97 V, **28 satelitów, HDOP 0,50**, zero ostrzeżeń |
> | archiwum `.tlog` | nagrywa: **43 198 ramek, 2,0 MB** w `/var/lib/dron15/archiwum/tlog/` |
> | **obraz z ZR30 przez router** | `stream is available and online, 1 track (H264)` |
> | nagranie obrazu | **h264 1280×720, 30 kl./s, 9,7 s, 1,96 MB** — sprawdzone `ffprobe` |
> | panel STACJA | sudoers założony, `admin` w grupach `adm` i `systemd-journal` |
>
> Kiosk **świadomie pominięty** — malina ma jeden monitor HDMI zajęty przez pulpit
> i inny system (niżej). Podgląd idzie do przeglądarek.
>
> ### ⚠ TA MALINA NIE JEST DEDYKOWANĄ STACJĄ
>
> Chodzi na niej równolegle **`PI5setup full`** — własny system Tomka:
> `pi5-uas-rtsp` (obraz CVBS po RTSP dla ATAK-CIV, **słucha na 8554**),
> `pi5-camera-recorder` (nagrywanie CVBS na kartę SD), `pi5-control-panel`
> (GC9A01 + enkoder) i **Waydroid**.
>
> Współistnienie jest bezkolizyjne, bo nasz MediaMTX ma `rtsp: no` i nie dotyka 8554,
> a obciążenie stacji to ułamek możliwości maszyny (0,14 przy czterech rdzeniach,
> 7,4 GB wolnej pamięci). Ale trzeba o tym pamiętać przy każdej zmianie:
>
> - ⛔ **archiwum nie może iść na `/media/fpv-recordings`** — to karta ich rejestratora (3,8 GB),
> - ⛔ **kiosk przykryłby pulpit** na jedynym podłączonym HDMI,
> - zapis „stacja nie ma Androida" z [GCS_RPI5.md](GCS_RPI5.md) dotyczył czystej maszyny
>   i **na tej malinie nie obowiązuje** — Waydroid tu jest i zostaje.
>
> ### Dwie rzeczy naprawione przy wdrożeniu
>
> ⛔ **Binarka i katalog konfiguracji miały tę samą nazwę.** `mediamtx/` trzyma generowany
> `mediamtx.yml`, więc plik `mediamtx` nie mógł stać obok. Gorzej: `[ -x ./mediamtx ]`
> na **katalogu zwraca prawdę**, więc kontrola instalatora przepuszczała brak binarki,
> a wywracał się dopiero systemd przy próbie uruchomienia katalogu. **Binarka siedzi
> teraz w `bin/mediamtx`**, a kontrole sprawdzają `-f` i `-x`.
>
> ⚠ **`ERR [API] path already exists` przy każdej zmianie ustawień archiwum.** `upsertPath()`
> próbował najpierw dodać ścieżkę, a ta prawie zawsze już istnieje (powstaje z `mediamtx.yml`).
> Czerwony wpis znaczący „wszystko w porządku" to ślad, przez który potem szuka się usterki
> godzinę — kolejność odwrócona na **PATCH przed POST**. Sprawdzone: cztery zmiany ustawień
> pod rząd, zero nowych wpisów.
>
> ### ✅ RESTART PRZEŻYTY — SPRAWDZONE PRZYPADKIEM 2026-08-28 17:55
>
> Malina zrestartowała się w trakcie prac. Wyszło z tego darmowe potwierdzenie tego,
> czego nie chcieliśmy wymuszać: **adres statyczny `192.168.88.30` wrócił sam**,
> a `dron15-mediamtx` i `dron15-gcs` wstały bez pomocy (`enabled` działa).
> Stan dostępu też przeżył — `dostep.json` leży w `/var/lib/dron15`.
>
> ### ⚠ DZIENNIK JEST ULOTNY — KOD ADMINISTRATORA ZNIKA PRZY RESTARCIE
>
> Po restarcie `journalctl -u dron15-gcs | grep "kod:"` **nie zwraca nic**: journald na
> tej malinie trzyma dziennik w `/run`, więc każdy restart go czyści. Procedura
> „kod wyciągniesz z journala" (§4) **działa tylko do pierwszego restartu**.
>
> To **nie jest** zatrzaśnięcie się na zewnątrz — kod żyje dalej w stanie dostępu
> i da się go odczytać:
>
> ```bash
> sudo python3 -c "
> import json
> d = json.load(open('/var/lib/dron15/dostep.json'))
> for z in d['zaproszenia']:
>     if z['rola'] == 'admin' and not z.get('uniewaznione'): print(z['kod']); break"
> ```
>
> Nowego kodu serwer **nie wypisze**, bo ważne zaproszenie administratora istnieje
> (`zapewnijAdmina()` sprawdza właśnie to). Warianty na przyszłość: trwały dziennik
> (`sudo mkdir -p /var/log/journal && sudo systemctl restart systemd-journald`)
> albo po prostu zapisanie sobie linku zapraszającego przy pierwszym wejściu.
>
> ### ⛔ OTWARTA WADA: NAGRANIE OBRAZU GUBI OK. 14 % MATERIAŁU
>
> Zmierzone 2026-08-28 na zamkniętym oknie: przy nagrywaniu przez **60 s** powstały
> **trzy pliki o łącznej długości 51,9 s**. Brakuje **8,1 s, czyli 14 %**.
>
> W dzienniku MediaMTX widać przyczynę bezpośrednią:
>
> ```
> ERR [path zr30] [recorder] detected drift between recording duration and absolute time, resetting
> ```
>
> Recorder porównuje długość nagrania z zegarem ściennym, a te dwie wielkości się
> rozjeżdżają. Po ok. 23 s różnica przekracza próg, nagranie jest zamykane i zaczynane
> od nowa — i **w tej przerwie materiał przepada**. Stąd pliki po 22–24 s zamiast
> ustawionych 10-minutowych segmentów.
>
> **Przyczyna źródłowa — niezgodność tempa klatek.** Kamera deklaruje **30 kl./s**
> (`CMD 0x20`, pole `klatki`), a oddaje ok. **25–28**. To samo widać niezależnie
> w kokpicie na MK32 (`tor SIYI: 25,0 kl/s`) i w `..\..\CLAUDE.md` poz. 57
> („Tempo 25 kl./s jest własnością kamery"). Timeline nagrania budowany przy założeniu
> 30 kl./s narasta szybciej niż czas rzeczywisty i dryf jest nieuchronny.
>
> **Co sprawdzone i NIE pomogło:** zmiana `recordFormat` z `fmp4` na `mpegts`.
> Trzy resety na 100 s, segmenty nadal po ~30 s. Czyli **to nie kwestia kontenera**.
>
> **Czego jeszcze nie sprawdzono** (w kolejności od najmniej ryzykownego):
>
> 1. czy nowsza wersja MediaMTX ma większą tolerancję dryfu albo jej wyłącznik,
> 2. czy `sourceOnDemandCloseAfter` i przełączanie trybu archiwum nie dokładają resetów
>    — pomiar robiono przy ścieżce trzymanej otwartej, ale nie w pełnej izolacji,
> 3. ⛔ **ustawienie kamerze prawdziwego tempa przez `CMD 0x21`** — to najpewniej
>    usunęłoby przyczynę, ale źle zbudowana komenda 0x21 zawiesza ZR30 na głucho
>    i ratuje tylko cykl zasilania (poz. 57). **Nie robić przy dronie z podpiętym
>    pakietem i śmigłami.**
>
> ⚠ **Telemetrii `.tlog` to nie dotyczy** — tam nie ma żadnego dryfu ani resetów,
> zapis jest ciągły. Wada dotyczy wyłącznie nagrań obrazu.
>
> ### Obserwacja warta zapamiętania
>
> ```
> [path zr30] RTP packets are too big (8180 > 1440), remuxing them into smaller ones
> ```
>
> ZR30 wysyła pakiety RTP grubo ponad typowe MTU. MediaMTX je **przepakowuje na mniejsze**
> — i dobrze, bo WebRTC inaczej by ich nie przepuścił. To jedyne miejsce, w którym robi
> coś więcej niż czysty remux, ale nadal **nie dekoduje ani nie koduje** obrazu.
>
> ---
>
> ### ⚠ STAN NA 2026-08-23 (przed wdrożeniem, dla porównania)
>
> Konwencja z `..\..\CLAUDE.md` §8 nakazuje oznaczać twierdzenia. Tu jest podział:
>
> | Rzecz | Status |
> |---|---|
> | serwer, strona, telemetria, panel admina | **FAKT** — działa, próby na Windows |
> | archiwum `.tlog` | **FAKT** — nagrane i odczytane przez `pymavlink` (§6.4) |
> | sprzątanie archiwum po czasie i po zajętości | **FAKT** — sprawdzone |
> | binarka MediaMTX arm64 ma potrzebne funkcje | **FAKT** — `authMethod`, `authHTTPAddress`, `webrtcsessions/kick`, `recordDeleteAfter` obecne w pliku |
> | panel STACJA — odczyty, lista dozwolonych poleceń, degradacja bez systemd | **FAKT** — sprawdzone na Windows (§4a) |
> | jednostki systemd, kiosk, sudoers, dziennik systemowy, skrypty `rpi/*` | **NIEZWERYFIKOWANE** — napisane, nieuruchomione |
> | sprzętowe dekodowanie H.265 w Chromium na RPi 5 | **HIPOTEZA** — flagi dobrane z rozeznania, pomiar dopiero przed nami (§7) |
>
> Pierwsze uruchomienie na malinie należy więc traktować jak próbę, nie jak
> odtworzenie znanej procedury. Sekcja §8 mówi, gdzie szukać, gdy nie zadziała.

---

## 0. Weryfikacja na żywej sieci — 2026-08-28

Sprawdzone z laptopa wpiętego w sieć pokładową (`192.168.144.161`), przy **dronie
pod napięciem, ze śmigłami** i **działającym kokpitem na MK32**. Wszystko tylko-odczyt:
ani jednej komendy do maszyny, ani jednego zapisu do kamery.

| Węzeł | Adres | Stan |
|---|---|---|
| air unit MK32 | `.11` | odpowiada, http otwarty |
| jednostka naziemna | `.12` | odpowiada; TCP 5760 zamknięty, telemetria idzie UDP 19856 |
| Android MK32 | `.20` | odpowiada, `adb` 5555 otwarty; **kokpit 0.1-M1 na wierzchu**, wgrany 14:50 |
| ZR30 | `.25` | odpowiada, RTSP 8554 otwarty |
| **stacja RPi** | — | patrz niżej: **stoi w LAN-ie, nie w sieci pokładowej** |

> ### ⚡ KOREKTA 2026-08-28 — STACJA NIE JEST W SIECI POKŁADOWEJ
>
> Plan zakładał adres `192.168.144.30` w sieci drona. **Malina trafiła gdzie indziej
> i to jest lepsze rozwiązanie**: siedzi w LAN-ie pod `192.168.88.30`, a do sprzętu
> pokładowego dociera **przez router `192.168.88.1`** — jeden skok.
>
> ```
>   LAN 192.168.88.0/24                    sieć pokładowa 192.168.144.0/24
>   ├── .30   stacja RPi 5      ──►  .1  ──►  .20  MK32 (telemetria)
>   ├── .199  laptop                          .25  ZR30 (obraz)
>   └── widzowie                              .12  jednostka naziemna
> ```
>
> **Zmierzone przez router, przy dronie pod napięciem:**
>
> | Tor | Bezpośrednio z sieci pokładowej | Przez router |
> |---|---|---|
> | obraz RTSP `/main.264` | 1279 kb/s | **1344 kb/s** |
> | telemetria z rozgałęźnika MK32 | 105 wiad./s | **106 wiad./s** |
>
> Trasowanie nie kosztuje nic mierzalnego, a daje trzy rzeczy: malina **nie potrzebuje
> drugiej karty sieciowej**, widzowie sięgają do niej ze zwykłego LAN-u zamiast wchodzić
> do sieci drona, i **sieć pokładowa zostaje nietknięta** — stacja jej nie zaśmieca
> ani nie zajmuje w niej adresu.
>
> ⚠ **`zrodla.json` zostaje bez zmian** — adresy `192.168.144.25` i `192.168.144.20`
> są z maliny osiągalne. Zmienia się wyłącznie to, **pod jakim adresem szukać stacji**:
> `http://192.168.88.30:8095`, nie `.144.30`.
>
> ### ✅ ADRES USTAWIONY NA STAŁE 2026-08-28: `192.168.88.30`
>
> Malina dostała pierwotnie `.198` z DHCP. Zmienione na **statyczne `192.168.88.30/24`**,
> brama i DNS `192.168.88.1`, przez NetworkManager (`Wired connection 1`, `ipv4.method
> manual`) — bo stacja pod zmiennym adresem to linki zapraszające, które przestają
> działać po restarcie routera.
>
> **Jak to zrobić, nie tracąc zdalnego dostępu.** Zmiana adresu urywa sesję SSH, która
> ją wykonuje, więc dwie rzeczy trzeba zrobić inaczej niż odruchowo:
>
> 1. **Siatka bezpieczeństwa najpierw.** `systemd-run --on-active=300` uzbraja powrót
>    na DHCP za pięć minut. Gdyby nowy adres okazał się nieosiągalny, malina wraca sama
>    — bez chodzenia do niej z klawiaturą. Rozbroić dopiero **po** potwierdzeniu kontaktu.
> 2. **Samą zmianę uruchomić ODŁĄCZONĄ od sesji** (`systemd-run --unit=…`), inaczej ginie
>    w połowie: `nmcli con up` zrywa połączenie, które właśnie wykonuje polecenie.
>
> ⚠ **`.30` może leżeć w puli DHCP routera** (MikroTik domyślnie rozdaje od `.10` w górę).
> Adres statyczny w puli działa, dopóki router nie przydzieli go komuś innemu. Docelowo
> **wyłączyć `.30` z puli albo zrobić rezerwację** na `192.168.88.1`.

### Co przeszło

✅ **Kamera oddaje RTSP równocześnie z torem SIYI kokpitu.** To był warunek konieczny
całej stacji. Zmierzone przy kokpicie trzymającym port 37256: `SETUP`+`PLAY`
na `/main.264` dało **937 kB w 6 s (~1279 kb/s)**. Ograniczenie „jeden klient"
dotyczy portu 37256, nie RTSP.

✅ **Adres `.30` wolny**, sieć osiągalna, wszystkie węzły żywe.

### Co było zepsute i zostało poprawione

⛔ **`zrodla.json` wskazywał na nieistniejące ścieżki.** `/video1` i `/video2` dają
**404** — stacja nie pokazałaby nic. Jedyna działająca to `/main.264`. Poprawione.

⛔ **Strumień pomocniczy nie istnieje** (kamera zgłasza 0×0 / 0 kb/s). `rtspPomocniczy`
usunięty z konfiguracji, żeby w interfejsie nie było przełącznika prowadzącego donikąd.

⛔ **Główny strumień to H.264 720p, nie H.265 1080p.** Monitory będą dekodować
programowo — RPi 5 nie ma bloku H.264. Przy 720p to dużo taniej niż zakładano,
ale **do zmierzenia**. Opis: [GCS_RPI5.md](GCS_RPI5.md) §2.

### ✅ TELEMETRIA — ROZSTRZYGNIĘTE: ROZGAŁĘŹNIKIEM JEST APARATURA

**Jednostka naziemna obsługuje jednego klienta MAVLink** (`..\..\CLAUDE.md` poz. 57),
a kokpit trzyma go teraz: w jego dzienniku widać `[telemetria] nasluch 192.168.144.12:19856`.

Stacja skonfigurowana na ten sam adres **podbierałaby telemetrię aparaturze** — czyli
zabierała ją operatorowi, żeby pokazać widzom.

**Decyzja Toma (2026-08-28): rozgałęźnikiem jest MK32, nie stacja.** Aparatura zostaje
jedynym klientem jednostki naziemnej i rozdaje kopie w dół — stacji i komu jeszcze trzeba.

```
  jednostka naziemna .12:19856
           │  (jeden klient — kokpit)
           ▼
     KOKPIT MK32 .20:19856  ──┬──►  stacja RPi
                              ├──►  laptop z narzędziami
                              └──►  kolejny odbiorca
```

Ten kierunek jest właściwy także dlatego, że **nie odwraca porządku z [WLADZA.md](WLADZA.md)**:
gdyby rozgałęziała stacja, telemetria operatora szłaby przez maszynę, która wedle
założeń ma być tylko podglądem.

**Zbudowane w aplikacji MK32:** `net/mavlink/RozglosTelemetrii.kt`.

⛔ **Ruch idzie wyłącznie w dół.** Datagram od odbiorcy służy tylko do poznania jego
adresu — **treść jest wyrzucana bez oglądania**. Nie ma ścieżki, którą cokolwiek z dołu
mogłoby dojść do kontrolera lotu. Nawet poprawna ramka `COMMAND_LONG` z rozbrojeniem
skończy w koszu.

Odbiorca zgłasza się sam, dokładnie tak jak stacja pyta jednostkę naziemną: pusty
datagram pod `host:port` otwiera drogę powrotną (`server/telemetria.mjs`, `_zaczepka()`).
Dzięki temu **po stronie stacji nie trzeba było zmieniać ani linijki kodu** — wystarczyła
zmiana adresu w `zrodla.json` z `.12` na `.20`. Kto zamilknie na 15 s, wypada z listy sam.

Rozgałęźnik jest **dodatkiem, nie warunkiem**: gdy port 19856 na aparaturze okaże się
zajęty, kokpit melduje to w dzienniku i pracuje dalej bez zmian.

#### Zmierzone na żywym sprzęcie 2026-08-28, po wgraniu na MK32

Laptop udawał stację (`192.168.144.161` → `.20:19856`), przy dronie pod napięciem:

| Co sprawdzone | Wynik |
|---|---|
| rozgałęźnik wstaje | `[rozglos] rozgałęźnik telemetrii na porcie 19856` |
| odbiorca się zgłasza | `nowy odbiorca telemetrii: 192.168.144.161:60826` |
| **strumień w dół** | **1261 wiadomości w 12 s (105/s, 49 kB)** — `RC_CHANNELS`, `ATTITUDE`, `GLOBAL_POSITION_INT`, `BATTERY_STATUS`, `VFR_HUD`, `SYS_STATUS`… |
| **kokpit NIE traci telemetrii** | **zero `Lacze przerwane` w dzienniku przez cały czas odbioru** |
| wygasanie odbiorcy | po ciszy: `odbiorca 192.168.144.161 zamilkł — wypisany` |
| **droga w górę zamknięta** | wysłany w górę `PARAM_REQUEST_READ(FRAME_CLASS)` → **zero `PARAM_VALUE` w odpowiedzi**; maszyna go nie zobaczyła |

Ostatni wiersz jest testem właściwym dla tej konstrukcji: `PARAM_REQUEST_READ` jest
nieszkodliwy, ale maszyna **musi** na niego odpowiedzieć, jeśli go dostanie. Brak
odpowiedzi przy działającym strumieniu w dół dowodzi, że komenda nie wyszła
poza rozgałęźnik.

⚠ **Adresy rozdaje DHCP** — stacja potrzebuje adresu statycznego poza pulą albo
rezerwacji, inaczej po restarcie zmieni adres razem z linkami zapraszającymi.

---

## 1. Co przygotować

| Element | Wartość | Skąd |
|---|---|---|
| Raspberry Pi 5, 8 GB | z aktywnym chłodzeniem | [GCS_RPI5.md](GCS_RPI5.md) §2 |
| zasilacz | **oficjalny 27 W USB-C** | niedomiar objawia się losowymi zawieszeniami, nie komunikatem |
| dysk | NVMe przez HAT albo karta A2 | nagrania obrazu zabijają zwykłe karty |
| system | Raspberry Pi OS **64-bit** (Bookworm) | 32-bit odpada — binarka MediaMTX jest arm64 |
| binarka MediaMTX | `C:\Soft\nas-arm\mediamtx_arm64` | projekt NRK; wysyła ją `wgraj.ps1` |
| dostęp SSH z Windows | klucz w `~/.ssh/authorized_keys` na malinie | bez tego skrypt trzy razy zapyta o hasło |

Na malinie, raz:

```bash
sudo apt update && sudo apt install -y nodejs npm chromium-browser v4l-utils
```

Sprawdź wersję: `node -v` musi dać **18 lub więcej** (archiwum liczy wolne miejsce
przez `statfs`, dodane w Node 18.15).

---

## 2. Wgranie z Windows

Z katalogu `mk32app\serwer`:

```powershell
.\rpi\wgraj.ps1 -Malina dron15.local -Uzytkownik pi -Instaluj
```

Parametr nazywa się **`-Malina`, nie `-Host`** — `$Host` jest w PowerShellu zmienną
tylko-do-odczytu i parametr o tej nazwie wywraca skrypt przy starcie.

Co się dzieje: pakowanie (bez `node_modules`, bez archiwum, bez `dostep.json`),
wysyłka do `/opt/dron15`, jednorazowa wysyłka binarki MediaMTX, a przy `-Instaluj`
uruchomienie `rpi/instaluj.sh` przez `sudo`.

Kolejne wgrania kodu — bez powtarzania instalacji:

```powershell
.\rpi\wgraj.ps1 -Malina dron15.local -Restart
```

---

## 3. Co robi `instaluj.sh`

1. sprawdza architekturę, obecność Node 18+ i wykonywalnej binarki `mediamtx`,
2. `npm install --omit=dev` oraz budowę strony (`web/`),
3. zakłada **katalog danych `/var/lib/dron15`** i kopiuje tam `zrodla.json`,
4. instaluje i włącza dwie jednostki systemd,
5. nadaje panelowi STACJA wąskie prawo do restartu usług i czytania dziennika (§4a).

> ### Dlaczego dane leżą poza katalogiem projektu
>
> `/opt/dron15` jest **nadpisywane** przy każdym wgraniu kodu. Gdyby leżały tam
> zaproszenia, ustawienia i nagrania, aktualizacja kasowałaby je za każdym razem.
> Po instalacji **edytuje się `/var/lib/dron15/zrodla.json`**, nie ten w projekcie.

| Co | Gdzie |
|---|---|
| kod | `/opt/dron15` |
| konfiguracja źródeł | `/var/lib/dron15/zrodla.json` |
| użytkownicy i zaproszenia | `/var/lib/dron15/dostep.json` |
| nagrania | `/var/lib/dron15/archiwum/{tlog,wideo}` |
| rejestr techniczny | `/var/lib/dron15/logi/serwer.log` + `journalctl` |

---

## 4. Usługi

| Jednostka | Co robi | Restart |
|---|---|---|
| `dron15-mediamtx` | obraz: remux RTSP → WebRTC | `always` |
| `dron15-gcs` | strona, API, telemetria, archiwum `.tlog` | **`on-failure`** |
| `dron15-kiosk` | Chromium na monitorach | `always`, jednostka **użytkownika** |

```bash
systemctl status dron15-gcs
journalctl -u dron15-gcs -f
sudo systemctl restart dron15-mediamtx dron15-gcs
```

> **`on-failure`, nie `always`, dla serwera — to nie jest przeoczenie.**
> Po nieprzechwyconym wyjątku serwer schodzi z pola celowo, kodem 1
> ([LOGI_I_BLEDY.md](LOGI_I_BLEDY.md) §2) — wtedy systemd go podnosi. Wyjście
> kodem 0 znaczy „zatrzymany świadomie" i wznawiania nie wymaga. Ta sama zasada,
> którą realizował dozorca w `start.sh --pilnuj`, tylko oddana systemd
> (zadanie 4a.4 z [TODO.md](../TODO.md)).

**Kiosk instaluje się osobno i BEZ `sudo`**, bo potrzebuje sesji graficznej:

```bash
KOD=<kod-dla-monitorow> sh rpi/kiosk.sh --zainstaluj
sudo loginctl enable-linger $(id -un)     # żeby wstawał bez logowania człowieka
```

Jeden monitor zamiast dwóch:

```bash
EKRANY="1920x1080+0+0" KOD=<kod> sh rpi/kiosk.sh
```

> ### ⛔ KIOSK TEŻ POTRZEBUJE ZAPROSZENIA
>
> Strona wymaga żetonu, a obraz przez WHEP wymaga go **osobno** — MediaMTX pyta serwer
> o zgodę przed każdym odtworzeniem ([DOSTEP_I_UZYTKOWNICY.md](DOSTEP_I_UZYTKOWNICY.md) §6).
> Świeży profil Chromium ma pustą pamięć, więc **bez kodu na monitorach stacji stanie
> ekran „KOD ZAPROSZENIA"** — i nikt go nie wpisze, bo przy stacji nie ma klawiatury.
>
> Wydaj w panelu ADMIN zaproszenie **wielokrotne i bezterminowe** (imię np.
> „monitory stacji", rola `widz`) i podaj jego kod w `KOD`.
>
> **Wielokrotne jest konieczne**, nie zalecane: każde okno ma własny profil Chromium,
> więc przy dwóch monitorach kod idzie w ruch dwa razy. Jednorazowe zadziałałoby
> na jednym ekranie i zawiodło na drugim.
>
> Kod wymienia się na żeton przy pierwszym uruchomieniu, znika z paska adresu i zostaje
> w profilu — przy kolejnych startach nie jest już potrzebny. Monitory pokazują się
> potem w panelu jako zwykły widz i tak samo się je odcina: **ODETNIJ** przy
> „monitory stacji".
>
> Sprawdzone 2026-08-23 w przeglądarce: profil z wyczyszczoną pamięcią i adresem
> `.../#z=<kod>` ląduje wprost na nakładce, jako „monitory stacji".

> **Skutek uboczny wart odnotowania:** kiosk jest widzem, więc **dopóki monitory
> są włączone, strumień z ZR30 leci bez przerwy**. Tryb archiwum `przy-widzach`
> zachowuje się wtedy jak `zawsze` — jeden slot kamery i pasmo radiowe są zajęte
> stale (§6.2). Jeśli to ma być inaczej, kiosk trzeba zatrzymywać między lotami:
> `systemctl --user stop dron15-kiosk`.

**Pierwsze wejście administratora** — kod wypisuje się na konsolę przy pierwszym
starcie i trafia do journala:

```bash
journalctl -u dron15-gcs | grep -A3 "PIERWSZE WEJŚCIE"
```

Wejdź tym kodem, a potem wydawaj imienne zaproszenia z panelu **ADMIN → ZAPROSZENIA**.
Procedura krok po kroku, razem z pułapką „link z adresem localhost":
[DOSTEP_I_UZYTKOWNICY.md](DOSTEP_I_UZYTKOWNICY.md) §3.

> **Otwórz panel pod adresem, którym będą się łączyć goście** (np. `http://192.168.144.30:8095`),
> a nie przez `localhost` na samej stacji. Link zapraszający składa się z adresu
> w pasku przeglądarki administratora, więc otwarty lokalnie wyprodukuje link
> działający tylko na tej maszynie. Panel to wykrywa i ostrzega, a obok linku
> podaje sam kod — ten działa zawsze.

---

## 4a. Panel STACJA — obsługa bez ssh

Dodany 2026-08-23. Przycisk **STACJA** na dole strony, rola `admin`.
To jest `rpi/sprawdz.sh` w przeglądarce: stan i restart usług, dławienie zasilania,
temperatura, obciążenie, pamięć, dysk, adresy i MTU interfejsów, nasłuch portów,
wersje oprogramowania, dekoder HEVC, dziennik systemowy per usługa.

**Skrypt zostaje i nie jest zbędny** — jest jedyną drogą wtedy, gdy serwer nie wstaje,
a wtedy panelu też nie ma.

| Panel | Odpowiada na pytanie |
|---|---|
| **ADMIN** | kto ma wstęp — zaproszenia, widzowie, odcinanie, archiwum |
| **STACJA** | czy sprzęt działa — usługi, zasilanie, sieć, dziennik |

### Co panel podnosi na wierzch sam

Dwie rzeczy dostają czerwoną ramkę u góry, zanim spojrzysz na resztę:

- **dławienie zasilania** (`vcgencmd get_throttled` ≠ `0x0`) — bo objawia się losowymi
  zawieszeniami stacji i wygląda dokładnie jak usterka oprogramowania,
- **API MediaMTX odpowiadające spoza `127.0.0.1`** — bo wtedy każdy w sieci może
  przestawiać ścieżki obrazu.

Trzecia rzecz jest przy interfejsach: **MTU tunelu powyżej 1420** dostaje ostrzeżenie,
bo to najczęstsza przyczyna objawu „strona i telemetria działają, obraz nie startuje" (§7.2).

### Restart usług — dlaczego dwa kliknięcia

Pierwsze kliknięcie uzbraja (`NA PEWNO?`), drugie wykonuje; uzbrojenie mija samo
po pięciu sekundach. Restart widzą wszyscy widzowie, więc jeden odruch dłoni to za mało.

Restart **SERWERA** rozłącza także sam panel — serwer odpowiada najpierw, schodzi
z pola po chwili, a strona wraca sama, gdy systemd go podniesie.

Restart **MONITORÓW** bywa nieosiągalny: kiosk to jednostka użytkownika, żyjąca w sesji
graficznej, do której usługa systemowa nie zawsze ma dostęp. Panel powie wtedy wprost,
co wpisać z konsoli stacji.

### Uprawnienia — wąskie z rozmysłu

`rpi/instaluj.sh` nadaje je dwiema drogami:

| Do czego | Jak | Co otwiera |
|---|---|---|
| czytanie dziennika | grupa `adm` / `systemd-journal` | odczyt dziennika, **bez** podnoszenia uprawnień |
| restart usług | `/etc/sudoers.d/dron15-panel` | **wyłącznie** `systemctl restart dron15-mediamtx` i `dron15-gcs`, pełnymi ścieżkami |

Wzorzec sudoers przechodzi `visudo -c` **przed** założeniem — uszkodzony plik
w `sudoers.d` potrafi zablokować `sudo` na całej maszynie. Gdy kontrola nie przejdzie,
instalator odpuszcza i mówi o tym; panel działa dalej, tylko bez przycisku RESTART.

W pliku sudoers **nie ma gwiazdki**. Powód i pełne uzasadnienie:
[DOSTEP_I_UZYTKOWNICY.md](DOSTEP_I_UZYTKOWNICY.md) §4a.

> **Skutek uboczny, o którym trzeba wiedzieć:** jednostka `dron15-gcs` **nie ma**
> `NoNewPrivileges=yes`. `sudo` jest programem setuid i pod tą opcją nie zadziałałby
> w ogóle — przycisk RESTART byłby martwy bez żadnego czytelnego komunikatu.
> `dron15-mediamtx` tę opcję zachowuje, bo niczego nie podnosi.

**Przynależność do grupy `adm` działa od następnego startu usługi.** Jeśli po instalacji
dziennik systemowy w panelu jest pusty: `sudo systemctl restart dron15-gcs`.

> ⛔ **Granica bez wyjątków.** Panel obsługuje stację, nie maszynę latającą. Nie prowadzi
> stąd żadna ścieżka do kontrolera lotu ani do głowicy. Nazwa usługi z żądania nigdy nie
> trafia do polecenia wprost — musi być na zamkniętej liście w `server/stacja.mjs`.
> Sprawdzone: restart usługi `dron15-gcs; rm -rf /` kończy się odpowiedzią
> „Nieznana usługa".

---

## 5. Konfiguracja

`/var/lib/dron15/zrodla.json`:

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

Po zmianie: `sudo systemctl restart dron15-mediamtx dron15-gcs`.

Stacja wpina się w sieć pokładową `192.168.144.0/24`; adres zalecany przez SIYI
dla komputera GCS to **`.30`** (`..\..\CLAUDE.md` §4).

---

## 6. Archiwum

Zadanie 2.4 z [TODO.md](../TODO.md). Dwa strumienie danych, zapisywane zupełnie inaczej:

| Co | Kto zapisuje | Kiedy |
|---|---|---|
| telemetria `.tlog` | nasz serwer (`server/archiwum.mjs`) | **zawsze**, gdy ramki przychodzą |
| obraz `.mp4` | MediaMTX, wprost z remuksu | zależnie od trybu (niżej) |

### 6.1 Dlaczego tak, a nie jednym mechanizmem

Ramki MAVLink przez stację przechodzą i tak, więc ich zapis nic nie kosztuje.
Obraz natomiast **przechodzi tylko wtedy, gdy ktoś go ogląda** — MediaMTX ściąga
strumień na żądanie (`sourceOnDemand`). Gdyby obraz zapisywał nasz serwer, musiałby
go dekodować i cała oszczędność z remuksu by przepadła.

### 6.2 Trzy tryby nagrywania obrazu

Przełącznik w panelu administratora, sekcja **ARCHIWUM**.

| Tryb | Co zapisuje | Cena |
|---|---|---|
| `nie` | nic | — |
| **`przy-widzach`** (domyślny) | obraz, gdy ktoś patrzy | **lot, którego nikt nie oglądał, nie ma nagrania** |
| `zawsze` | wszystko | strumień z kamery leci bez przerwy: obciąża łącze radiowe i zajmuje jeden z czterech slotów ZR30 także wtedy, gdy nikt nie patrzy |

Domyślnie `przy-widzach`, bo **pasmo radiowe jest tu zasobem rzadszym niż miejsce
na dysku**. Przed lotem, który koniecznie ma mieć nagranie — przestawić na `zawsze`
i pamiętać, żeby wrócić.

Nagrywany jest **wyłącznie strumień główny**. Pomocniczy istnieje jako droga odwrotu
dla przeglądarek, które nie odtworzą H.265 ([SERWER_PODGLADU.md](SERWER_PODGLADU.md) §4);
zapisywanie obu podwoiłoby zużycie dysku dla drugiej, gorszej kopii tego samego lotu.

### 6.3 Kasowanie starych nagrań — dwa niezależne limity

| Limit | Kto pilnuje |
|---|---|
| **czas** (`trzymajDni`) | MediaMTX dla obrazu (`recordDeleteAfter`), nasz serwer dla `.tlog` |
| **zajętość** (`limitGb`) | wyłącznie nasz serwer, co kwadrans i na żądanie |

Limit zajętości musi być nasz, bo MediaMTX nie umie patrzeć na wolne miejsce —
a na karcie w RPi to jest właśnie ten limit, który kończy się pierwszy.
**Plik nagrywany w tej chwili jest nietykalny**: bieżący lot jest ważniejszy niż wczorajszy.

Przycisk **SPRZĄTAJ TERAZ** w panelu robi to od razu — przed wyjazdem w teren
warto wiedzieć, ile miejsca jest naprawdę.

### 6.4 Format `.tlog` — sprawdzony, nie założony

Ciąg wpisów: `[8 bajtów, uint64 big-endian, mikrosekundy UTC][surowa ramka MAVLink]`.
Ten sam format, co w Mission Plannerze i QGroundControl (MAVProxy `mavutil.py`,
klasa `mavlogfile`), więc nagrania ze stacji otwiera się tym samym narzędziem
co logi z Mission Plannera.

Zapisujemy **każdą** odebraną ramkę, także tę, której serwer nie dekoduje —
archiwum ma być wierne, nie wybiórcze. Próba z 2026-08-23 to potwierdziła: w pliku
znalazły się `BATTERY_STATUS` i `RC_CHANNELS`, których serwer nie rozumie.

```
865 wiadomości w 11,9 s · HEARTBEAT, SYS_STATUS, GPS_RAW_INT, ATTITUDE,
GLOBAL_POSITION_INT, VFR_HUD, EKF_STATUS_REPORT, BATTERY_STATUS, RC_CHANNELS,
PARAM_VALUE · znaczniki czasu rosnące, odczytane przez pymavlink
```

Nowy plik powstaje przy pierwszej ramce po ciszy; **cisza dłuższa niż 60 s zamyka
nagranie**. Krótsza to zwykłe potknięcie łącza i nie ma sensu ciąć na tym lotu.

---

## 7. Co trzeba zmierzyć na miejscu

To są zadania z [TODO.md](../TODO.md), których z Windows rozstrzygnąć się nie da.

### 7.1 Czy Chromium dekoduje H.265 sprzętowo (zadanie 2.2)

RPi 5 **stracił blok H.264** i ma wyłącznie sprzętowy dekoder HEVC
([GCS_RPI5.md](GCS_RPI5.md) §2) — dlatego na monitorach chcemy H.265.

```bash
sh rpi/sprawdz.sh              # czy dekoder w ogóle jest w systemie
sh rpi/sprawdz.sh --kiosk      # 20-sekundowy pomiar obciążenia, GDY OBRAZ IDZIE
```

> **Obecność dekodera w systemie NIE dowodzi, że Chromium z niego korzysta.**
> To rozstrzyga wyłącznie `chrome://media-internals` podczas odtwarzania: pole
> `Decoder` ma pokazać dekoder sprzętowy, a nie `FFmpegVideoDecoder`.
> Flagi w `rpi/kiosk.sh` są dobrane z rozeznania i **nie zostały zmierzone**.

Odniesienie do porównania: MediaMTX ma zjadać **blisko zera** niezależnie od kodeka
(przepakowuje pakiety, nie dekoduje). Procesor zużywa dekodowanie w przeglądarce.

### 7.2 MTU tunelu WireGuard (zadanie 2.3)

**Najbardziej mylący objaw w całym projekcie: strona i telemetria działają, a obraz
się nie startuje.** Prawie zawsze to MTU, nie serwer. WireGuard zwykle 1420;
pod LTE albo PPPoE bywa potrzebne 1280. `rpi/sprawdz.sh` wypisuje MTU wszystkich
interfejsów i ostrzega przy `wg0` powyżej 1420.

### 7.3 Reszta

- **2.1** — czy H.265 przez WHEP działa u realnych widzów (Chrome, Safari, telefon)
- **4.4** — ubicie trwającej sesji obrazu; wymaga przeglądarki i żywego źródła
- **3.2** — cała droga przez tunel z telefonu: strona, telemetria, obraz

---

## 8. Gdy nie działa

| Objaw | Gdzie patrzeć |
|---|---|
| serwer nie wstaje | `journalctl -u dron15-gcs -n 50` — najczęściej zajęty port 8095 |
| strona jest, obrazu nie ma | `systemctl status dron15-mediamtx`, potem `rpi/sprawdz.sh` (porty) |
| obraz nie idzie **przez tunel**, reszta tak | **MTU** — §7.2 |
| losowe zawieszenia stacji | `vcgencmd get_throttled` ≠ `0x0` → zasilanie, nie oprogramowanie |
| kiosk nie wstaje po restarcie | `loginctl enable-linger`, `systemctl --user status dron15-kiosk` |
| brak nagrań obrazu | tryb `przy-widzach` i nikt nie oglądał — §6.2 |
| RESTART w panelu odmawia | brak `/etc/sudoers.d/dron15-panel` — §4a |
| dziennik systemowy w panelu pusty | konto usługi jeszcze nie ma grupy `adm`: `sudo systemctl restart dron15-gcs` |
| „archiwum wyłączone" w panelu | katalog `/var/lib/dron15/archiwum` nie do zapisu; podgląd działa dalej, celowo |

Pełny przegląd jednym poleceniem:

```bash
sh rpi/sprawdz.sh
```

---

## 9. Czego stacja nadal nie robi

Zakres zamknięty 2026-08-20, decyzje 4 i 5 ([PLAN.md](../PLAN.md) §10):
**nie wysyła komend do maszyny, nie planuje misji, nie ma Androida ani QGroundControla.**
Władza zostaje na MK32 ([WLADZA.md](WLADZA.md)). Panel administratora zarządza
**dostępem**, nie dronem — ani jeden przycisk na stacji nie idzie do kontrolera lotu.
