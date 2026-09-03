# Drony DJI w stacji DRON 15 — co się da, a czego nie

Rozpoznanie i pierwszy działający tor obrazu, 2026-08-31. Sprzęt Toma:
**Mavic 3 Pro** (konsumencki) i **Mavic 3T** (Enterprise).

> **Wniosek w jednym zdaniu:** obraz da się wpiąć z obu maszyn **już teraz** (RTMP,
> zrobione i sprawdzone), a telemetrię realnie tylko z **Mavic 3T** — przez wbudowaną
> w DJI Pilot 2 **Cloud API**. Mavic 3 Pro telemetrii nie odda, bo DJI nie wydało
> dla niego SDK.

---

## 1. Co DJI w ogóle udostępnia

| Droga | Co daje | Dla naszego sprzętu |
|---|---|---|
| **RTMP z aplikacji** (DJI Fly / Pilot 2) | **obraz** (opcjonalnie z wypaloną nakładką OSD) | ✅ **oba drony** |
| **Cloud API** (wbudowana w DJI Pilot 2) | **telemetria** po MQTT + obraz (RTMP/RTSP/GB28181/Agora) | ✅ tylko **Mavic 3T** (Enterprise) |
| **Mobile SDK v5** (własna aplikacja na Androida) | pełna telemetria i sterowanie | ✅ tylko **Mavic 3T**; ⛔ **Mavic 3 Pro nie jest wspierany** |
| **Payload SDK** | dostęp pokładowy | ⛔ wymaga ładunku na Enterprise, nie dotyczy |

⛔ **Mavic 3 Pro nie ma i nie będzie mieć MSDK.** DJI zapowiedziało brak SDK dla
Mavic 3 / Classic / Cine, a lista wspieranych MSDK v5 obejmuje wyłącznie serię
**Mavic 3 Enterprise**, M30, M300/M350, M4E, Mini 3/4 Pro i pokrewne.
Dla Mavic 3 Pro zostaje więc **sam obraz**.

⚠ **Pułapka, o którą łatwo się potknąć w polu:** od DJI Fly v1.16.0 transmisja na
żywo z DJI RC 2 / RC Pro / Smart Controller **wymaga podpiętego mikrofonu** —
bez niego aplikacja nie zacznie nadawać. Rozdzielczość: RC 2 i RC Pro 2 dają 720p,
kontrolery z telefonem (RC-N) do 1080p; bitrate 1–5 Mb/s.

---

## 2. ✅ Tor obrazu — zrobiony i zmierzony

Stacja przyjmuje teraz strumień **RTMP**. To ta sama droga dla obu dronów: w DJI Fly
(Mavic 3 Pro) albo DJI Pilot 2 (Mavic 3T) wpisuje się adres RTMP stacji.

```
  aparatura DJI ──RTMP :1935──► MediaMTX na stacji ──WebRTC──► przeglądarki widzów
                                       ▲
                             /api/mtx-auth decyduje,
                             kto może nadawać i kto oglądać
```

**Zmierzone na żywej stacji (obraz próbny z `ffmpeg`, tak jak zrobi to aparatura):**

| Sprawdzenie | Wynik |
|---|---|
| przyjęcie strumienia | **FAKT** — ścieżka `dji` `ready: true`, źródło `rtmpConn`, ścieżki `H264 + AAC`, 2,89 MB w 7 s |
| odbiór bez zaproszenia | **FAKT** — WHEP `401` |
| odbiór z żetonem stacji | **FAKT** — autoryzacja przechodzi (`400` na celowo pustym SDP, czyli dalej niż uwierzytelnianie) |
| nadawanie złym hasłem | **FAKT** — połączenie zerwane |
| nadawanie pod ścieżkę kamery `zr30` | **FAKT** — nic nie weszło: `zr30` nadal `rtspSource`, `bytesReceived: 0` |
| nadawanie pod nieznaną ścieżkę | **FAKT** — odrzucone |

### ⛔ Dlaczego to wymagało furtki, a nie zwykłego włączenia

Reszta systemu działa w jedną stronę: stacja **pobiera** obraz z kamery, a widz go
ogląda. `/api/mtx-auth` odrzucał więc **każdą** próbę publikowania — i słusznie.
DJI działa odwrotnie: obraz **wypycha** aparatura. Furtka jest wąska:

* **osobne hasło nadawania** (`server/nadawanie.mjs`, plik `/var/lib/dron15/nadawanie.txt`),
  nie żeton widza — widz nie zacznie nadawać, a wykradziony adres RTMP nie daje
  wglądu w nic innego;
* **tylko ścieżki z listy** (`dji`, `dji2`) — bez tego ktoś podstawiłby własny obraz
  pod ścieżkę kamery pokładowej;
* wejście RTMP **otwiera się samo tylko wtedy**, gdy w `zrodla.json` jest źródło
  nadawane — nie ma otwartego portu, gdy nie ma czego przyjmować.

⚠ Adres RTMP niesie hasło jawnie — taki jest protokół. Wpisany w aparaturę zostaje
w niej, więc **po utracie sprzętu hasło wymienić**: `POST /api/nadawanie/nowe-haslo`
(tylko administrator). Adres do wpisania: `GET /api/nadawanie` (też tylko administrator).

### Jak wpiąć drona

1. W panelu ADMIN stacji odczytać adres nadawania (`rtmp://<stacja>:1935/dji?user=dji&pass=…`).
2. **Mavic 3 Pro:** DJI Fly → *Transmisja* → *Platformy transmisji* → **RTMP** → wkleić adres.
   ⚠ podpiąć mikrofon, inaczej aplikacja nie ruszy.
3. **Mavic 3T:** DJI Pilot 2 → transmisja na żywo → RTMP → ten sam adres.
4. Na stronie wybrać źródło **DJI — nadawany**.

---

## 3. Telemetria — co jest możliwe, a czego nie ma

Nasz model telemetrii jest **niezależny od źródła** (`server/telemetria.mjs`, `stan()`:
`lacze`, `lot`, `polozenie`…), więc interfejs, mapa i HUD nie wymagają zmian —
potrzebna jest wyłącznie przejściówka wypełniająca ten sam kształt.

### Mavic 3T — realna droga: Cloud API

DJI Pilot 2 ma **wbudowaną** obsługę Cloud API: publikuje telemetrię (OSD) po **MQTT**
na wskazany serwer i potrafi puszczać obraz po RTMP/RTSP/GB28181/Agora. **Nie trzeba
pisać aplikacji na Androida** — trzeba postawić po naszej stronie broker MQTT
i kilka punktów HTTPS.

Koszt: broker (np. Mosquitto na stacji) + przejściówka MQTT → nasz `stan()`.
Zysk: wysokość, prędkość, położenie, bateria, tryb lotu i punkt startu na naszej mapie.

### Mavic 3 Pro — telemetrii nie będzie

Brak SDK i brak Cloud API dla wersji konsumenckiej. Zostaje **wypalona nakładka OSD**
w obrazie (czytelna dla człowieka, bezużyteczna dla mapy) albo odczyt logów **po locie**.
Nie ma drogi na żywo — to ograniczenie DJI, nie nasze.

### ⛔ Czego robić nie będziemy

Sterowania dronem DJI ze stacji. Ta sama zasada, co przy DRON 15: stacja **patrzy,
nie rozkazuje** (`dok/SERWER_PODGLADU.md`). Cloud API ma tryb sterowania (DRC),
ale go nie włączamy.

---

## 3a. ✅ Telemetria z Mavic 3T — zbudowana i sprawdzona

Stacja ma teraz **własnego brokera MQTT** i przejściówkę Cloud API → nasz model stanu.

```
  Mavic 3T ──► aparatura z DJI Pilot 2 ──MQTT :1883──► server/dji.mjs ──► stan() ──► HUD i mapa
                        ▲
              strona /dji.html ustawia połączenie
              (JSBridge: licencja → moduł „thing")
```

### Dlaczego broker jest w naszym serwerze, a nie osobną usługą

Mosquitto byłby drugą usługą, drugim plikiem konfiguracji i drugim miejscem na hasła —
a i tak potrzebowalibyśmy klienta MQTT, żeby te wiadomości odebrać. Broker wbudowany
(`aedes`) daje jedną usługę, jedno poświadczenie i dostęp do wiadomości bez pośrednika.

### Przełożenie pól (z dokumentacji DJI `m3-series/properties`)

| DJI | u nas | uwaga |
|---|---|---|
| `elevation` | `lot.wysokosc_m` | **względem punktu startu** — tak liczy nasz HUD |
| `height` | `dji.wysokosc_bezwzgledna_m` | bezwzględna, trzymana osobno, żeby nie mieszać |
| `horizontal_speed` / `vertical_speed` | `lot.predkosc_ms` / `lot.wznoszenie_ms` | |
| `latitude` / `longitude` | `polozenie.lat` / `.lon` | |
| `attitude_head` / `_roll` / `_pitch` | `polozenie.kurs_deg`, `postawa.*` | |
| `position_state.gps_number` | `gnss.satelity` | |
| `battery.capacity_percent` | `bateria.procent` | napięcia DJI nie podaje |
| `mode_code` | `lot.tryb` | 19 stanów, przełożone na polskie nazwy |

⚠ **`lot.uzbrojony` przy DJI to INTERPRETACJA, nie odczyt.** DJI nie ma pojęcia
„uzbrojony"; wystawiamy „w powietrzu" wyliczone z `mode_code` (poza gotowością,
przygotowaniem, aktualizacją i brakiem połączenia). Nie mylić tego z uzbrojeniem
ArduPilota.

⚠ **Punkt startu jest domysłem.** Przyjmujemy pierwszą pozycję zmierzoną tuż nad
ziemią. DJI podaje dom osobnym komunikatem, którego jeszcze nie obsługujemy — więc
znacznik na mapie jest przybliżony i nie wolno go mylić z punktem powrotu ustawionym
w aparaturze.

### Zmierzone na żywej stacji (udawany Pilot 2, prawdziwy protokół)

| Sprawdzenie | Wynik |
|---|---|
| broker przyjmuje połączenie z hasłem urządzenia | **FAKT** — `Pilot 2 połączony` |
| meldunek `sys/product/{sn}/status` rejestruje statek | **FAKT** — `zgłosił się statek powietrzny M3T-…` |
| telemetria przez prawdziwe API (`/api/stan?zrodlo=dji`) | **FAKT** — tryb RĘCZNY, 47 m, 7,5 m/s, 19 satelitów, 63 %, kurs 137° |
| przełączanie źródeł obok siebie | **FAKT** — `zrodlo=dji` daje DJI, `zrodlo=zr30` DRON 15 |
| gaśnięcie po ciszy | **FAKT** — po 6 s bez wiadomości wartości wracają na `null` |
| konfiguracja bez klucza / ze złym kluczem | **FAKT** — `401` |
| **prawdziwa aparatura DJI** | ⛔ **NIESPRAWDZONE** — wymaga licencji DJI (niżej) |

### ⛔ Warunek konieczny: licencja Cloud API od DJI

`platformVerifyLicense(appId, appKey, license)` musi przejść, inaczej **Pilot 2 nie
załaduje modułu chmurowego** i żadna poprawność po naszej stronie tego nie obejdzie.
Te trzy wartości pochodzą z konta deweloperskiego DJI (aplikacja typu „Cloud API")
i tylko właściciel konta może je uzyskać.

Po ich zdobyciu:

```bash
curl -X POST -H "Content-Type: application/json"   -d '{"appId":"…","appKey":"…","licencja":"…"}'   "http://192.168.88.30:8095/api/dji/ustawienia?zeton=<żeton administratora>"
```

Potem w aparaturze: **DJI Pilot 2 → Cloud Service → Open Platforms** → adres
`http://192.168.88.30:8095/dji.html?k=<hasło urządzenia>` (podaje go
`GET /api/dji/ustawienia` jako `adresDlaPilota`). Strona sama przeprowadzi
weryfikację licencji i podłączy moduł chmurowy, wypisując na ekranie, na którym
kroku ewentualnie stanęła — przy stacji i w polu nie ma konsoli przeglądarki.

⚠ Adres brokera **musi mieć przedrostek `tcp://`** — Pilot 2 bez niego nie ładuje
modułu. Adres podajemy z serwera, więc nie da się tego pomylić ręcznie.

---

## 3b. Stan wpięcia — co gdzie stoi

| Ogniwo | Napisane | Na stacji | Sprawdzone |
|---|---|---|---|
| wejście RTMP w MediaMTX (`:1935`, ścieżki `dji`/`dji2`) | ✅ | ✅ | ✅ na stacji |
| furtka nadawania (`nadawanie.mjs`, `/api/mtx-auth`) | ✅ | ✅ | ✅ na stacji |
| źródło **DJI — nadawany** na stronie | ✅ | ✅ | ✅ |
| most MQTT Cloud API (`dji.mjs`, `:1883`) + `/dji.html` | ✅ | ✅ | ✅ udawanym Pilotem |
| wybór dostawcy telemetrii po źródle | ✅ | ✅ | ✅ |
| **odbiór zrzutu z APK (`zrzut.mjs`, `:5601`)** | ✅ | ⛔ **NIE** | ✅ u siebie, cały łańcuch |

⛔ **Jedno ogniwo nie jest wgrane na stację**: `server/zrzut.mjs` i jego wpięcie
w `index.mjs` powstały, gdy malina była wyłączona. Wgranie to zwykłe `rpi/wgraj.ps1`.

### Cały łańcuch przejechany u siebie (2026-09-03)

Postawiłem MediaMTX lokalnie z **konfiguracją z naszego generatora** i puściłem obraz
tak, jak zrobi to aparatura:

```
nadawca ──H.264 po TCP:5601──► zrzut.mjs ──ffmpeg -c copy──► RTMP:1935 ──► MediaMTX
```

| Sprawdzenie | Wynik |
|---|---|
| przyjęcie i uwierzytelnienie | **FAKT** — złe hasło rozłączone, drugi nadawca odrzucony |
| przepakowanie do RTMP | **FAKT** — log MediaMTX: `[path dji] stream is available and online, 1 track (H264)` |
| stan ścieżki w trakcie nadawania | **FAKT** — `ready: true`, źródło `rtmpConn`, `["H264"]`, 2,69 MB |
| droga do widza (WHEP) | **FAKT** — punkt odpowiada (`400` na celowo pustym SDP, czyli dalej niż routing) |

⚠ **Pułapka pomiarowa, w którą wpadłem:** pierwsze pytanie o `ready` wysłałem **po**
zakończeniu strumienia i dostałem `false` — wyglądało to na awarię, a było moim
błędem kolejności. Rozstrzygnął log MediaMTX, w którym publikacja była zapisana.

## 4. Co dalej

| Krok | Stan |
|---|---|
| obraz z obu dronów (RTMP) | ✅ **zrobione i sprawdzone** |
| ustalić, jakie są aparatury (RC) — od tego zależy Cloud API | ⛔ **do potwierdzenia** |
| broker MQTT + przejściówka Cloud API → `stan()` (tylko M3T) | ✅ **zrobione i sprawdzone** (§3a) |
| **licencja Cloud API z konta DJI** — bez niej Pilot 2 nie ruszy | ⛔ **na Tomie** |
| pola licencji w panelu ADMIN (dziś tylko przez API) | 🟠 |
| punkt startu i trasa DJI na naszej mapie | 🟠 po telemetrii |
| próba w powietrzu | 🟠 |

⚠ **Nagrywanie obrazu z DJI jest wyłączone** (`record: no` na ścieżkach `dji`), tak
samo jak było ustalone dla ZR30. Otwarta pozostaje wada 2.10 — nagranie gubi ok. 14 %.

---

## 5. Pułapka warta zapamiętania: dane ruchome są w `/var/lib/dron15`

Usługi mają `Environment=DATA_DIR=/var/lib/dron15`, więc **`zrodla.json`,
`dostep.json` i `nadawanie.txt` żyją tam**, a nie w katalogu programu. Edycja pliku
w `/opt/dron15` nic nie zmienia — i nie daje żadnego komunikatu o błędzie, tylko
cichy brak skutku. Zdarzyło się to dwa razy w jednej sesji: raz przy zaproszeniu
administratora („Nieznany kod zaproszenia" mimo poprawnie utworzonego wpisu),
raz przy dodawaniu źródła DJI („1 źródeł: zr30" mimo dopisanego drugiego).

⚠ `mediamtx.yml` jest **generowany** przy każdym starcie usługi z `zrodla.json` —
ręczne poprawki w nim znikają. Zmieniać `scripts/zrodla-lib.mjs`.

---

## 7. APK na aparaturę — zrzut ekranu kontrolera

> ### ⚠ KOD APK PRZENIESIONY DO SmartGCS (2026-09-03)
>
> Decyzja Toma: *„to coś innego niż MK32, nie robić dodatkowego forka"*. Źródła
> aplikacji są teraz w repozytorium **SmartGCS**, katalog **`dji/`** (samodzielny
> projekt Gradle, moduł `:zrzut`, własny README z tabelą „co sprawdzone"). Historia
> wcześniejszych zmian zostaje tu (`f9fa772`, `30c627c`). W tym repozytorium zostaje
> **strona stacji**: odbiornik `server/zrzut.mjs`, furtka `nadawanie.mjs`, broker
> `dji.mjs`, strona `web/public/dji.html` — bo to część serwera podglądu.
> Opis niżej dotyczy działania aplikacji i pozostaje aktualny.

Pomysł Toma: zamiast walczyć z tym, czego DJI nie udostępnia, **wziąć to, co widzi
operator** — obraz z ekranu aparatury, razem z całą nakładką OSD.

### Kiedy to jest lepsze od natywnej transmisji

| | natywny RTMP z DJI Fly / Pilot 2 | nasz APK (zrzut ekranu) |
|---|---|---|
| jakość i opóźnienie | **lepsze** — koder sprzętowy w torze wideo | gorsze o jedną przemianę |
| obciążenie aparatury | żadne dodatkowe | koder pracuje obok Pilota |
| wymaga mikrofonu (DJI Fly ≥1.16) | **tak** | nie |
| niesie nakładkę OSD | nie | **tak — z telemetrią widoczną w obrazie** |
| działa dla Mavic 3 Pro | tak (sam obraz) | **tak, razem z odczytami** |

⛔ **To nie zastępuje Cloud API dla Mavic 3T** — tam telemetria idzie liczbami i trafia
na mapę. Zrzut ekranu daje ją tylko jako piksele. Dla **Mavic 3 Pro** jest to jednak
jedyna droga, żeby na stacji zobaczyć wysokość, prędkość i baterię.

### Jak to jest zbudowane

```
  ekran aparatury ──MediaProjection──► VirtualDisplay ──► Surface kodera
   ──MediaCodec H.264──► TCP :5601 ──► server/zrzut.mjs ──ffmpeg -c copy──► RTMP :1935 ──► MediaMTX
```

Obraz trafia **wprost na powierzchnię wejściową kodera**, więc nie przechodzi przez
pamięć aplikacji ani przez procesor: rysuje układ graficzny, koduje koder sprzętowy.
To jedyny wariant, który ma szansę nadążyć obok działającego DJI Pilot 2.

**Dlaczego surowy H.264 po TCP, a nie RTMP z Androida:** RTMP znaczyłby własny
handshake, chunkowanie i muxer FLV — kilkaset linii protokołu na urządzeniu, którego
nie da się wygodnie podejrzeć. Aparatura wysyła to, co wypluwa `MediaCodec`, a resztę
robi `ffmpeg` na stacji, gdzie jest konsola i dziennik. `-c copy` znaczy **bez
przekodowania** — procesor stacji obrazu nie dotyka.

Protokół: jedna linia JSON (`hasło`, rozmiar, tempo) zakończona `\n`, potem już tylko
klatki. Hasło jest to samo, co przy nadawaniu obrazu i przy Cloud API — jedno hasło
urządzenia, jedno miejsce do wymiany.

### ⛔ Ryzyko, które rozstrzyga się dopiero na sprzęcie

Jeśli DJI oznacza podgląd wideo jako **`FLAG_SECURE`**, `MediaProjection` odda czarny
prostokąt i **nie da się tego obejść z aplikacji**. Stacja sama to zgłosi: czerń koduje
się prawie darmo, więc spadek przepływności poniżej 20 kb/s stawia w dzienniku
ostrzeżenie. ⚠ To podejrzenie, nie dowód — nieruchomy ciemny ekran wygląda tak samo;
rozstrzyga spojrzenie na podgląd.

Druga niewiadoma: czy kontroler pozwoli **zainstalować obcy APK**. DJI przewiduje
aplikacje trzecich stron dla Pilota 2, ale to zależy od modelu i wersji oprogramowania.

### Sprawdzone u siebie (stacja była wyłączona)

| Rzecz | Stan |
|---|---|
| APK się buduje | **FAKT** — `zrzut-debug.apk`, 809 kB, `BUILD SUCCESSFUL` |
| złe hasło urządzenia | **FAKT** — stacja rozłącza |
| drugi nadawca naraz | **FAKT** — odrzucony (przeplot klatek z dwóch źródeł byłby nie do oglądania) |
| przekazanie strumienia do `ffmpeg` | **FAKT** — rozpoznany `h264 … 1280x720, 30 fps`, 2728 kB przeszło |
| sprzątanie po rozłączeniu | **FAKT** — `nadaje = false`, ffmpeg zamknięty |
| **cała droga w emulatorze Androida** | **FAKT** — APK wgrany, zgoda systemu, `Nadaje 1280x800 @ 15 kl./s`, odebrane **5,5 MB / 406 klatek**, klatki wyjęte ze strumienia pokazują żywy ekran |
| ostatni odcinek (RTMP → MediaMTX) | ⛔ **NIESPRAWDZONE** — stacja offline |
| zrzut na prawdziwej aparaturze | ⛔ **NIESPRAWDZONE** — wymaga kontrolera w ręku |

> ### ⛔ Błąd wyłapany próbą, nie rozumowaniem
>
> Pierwsza wersja wykrywała czarny obraz filtrem `-vf blackdetect` przy `-c copy`.
> **Filtra nie da się połączyć z kopiowaniem strumienia** — ffmpeg wysypywał się na
> starcie (wyjście −22) i cały tor nie ruszał. Gdybym tego nie przejechał, wyszłoby
> to dopiero na aparaturze, w polu.
>
> Zastąpione liczeniem przepływności: zero dekodowania, zero kosztu procesora — co na
> RPi 5 ma znaczenie, bo ta maszyna **nie ma sprzętowego dekodera H.264**.

### ✅ Sprawdzone w emulatorze Androida (2026-09-03)

Cała droga poza samą aparaturą została przejechana na emulatorze API 28:
APK się instaluje, prosi o zgodę, przechwytuje ekran, koduje i wysyła; odbiornik
przyjmuje strumień, `ffmpeg` go rozpoznaje (`h264 … 1280x800`), a z zapisanych
danych dają się wyjąć klatki pokazujące **żywy ekran urządzenia**.

> ### ⛔ Emulator nie wstawał — i przyczyna była podwójna
>
> 1. **Migawka `default_boot`.** Flaga `-no-snapshot-save` migawkę **wczytuje**,
>    tylko jej nie zapisuje. Uszkodzona migawka wieszała rozruch tak, że `adb`
>    widział urządzenie, ale `adb shell` już nie odpowiadał. Trzeba `-no-snapshot`.
> 2. **Brak `opengl32sw.dll`** w tej instalacji SDK. Przy `-gpu swiftshader_indirect`
>    emulator wybierał programowe OpenGL, nie mógł go załadować i **zamrażał maszynę**.
>    Rozstrzygający pomiar: `qemu` zużywał **0 s procesora przez 5 s** — to nie był
>    wolny rozruch, tylko zatrzymanie. Działa `-gpu host`.
>
> ```
> emulator -avd MK32 -no-snapshot -no-audio -no-boot-anim -gpu host
> ```

### ⛔ Ergonomia w locie: pauza, nie zatrzymanie

Android pyta o zgodę na przechwytywanie ekranu **przy każdym nowym uruchomieniu**
i nie da się tego zapamiętać — jest to celowe zabezpieczenie systemu. Gdyby STOP
zwalniał przechwytywanie, każde ponowne włączenie w powietrzu oznaczałoby okienko
systemowe do odklikania **pilotowi trzymającemu drążki**. To przesądziło o budowie:

| Stan | zgoda | koder i obraz wirtualny | gniazdo |
|---|---|---|---|
| nadaje | trzymana | pracują | otwarte |
| **pauza** | **trzymana** | zwolnione (zero obciążenia) | zamknięte |
| koniec | zwolniona | zwolnione | zamknięte |

Zgodę bierze się **raz, przed lotem**; start i stop przełączają tylko wysyłanie.
Zmierzone w emulatorze: `Wstrzymane (operator)` → `Nadaje 960×592` po 5 s,
**bez okna zgody**.

### Trzy drogi do tego samego przełącznika

Pilot nie będzie wracał do aplikacji w locie, więc obraz wstrzymuje się:

1. **kafelkiem w szybkich ustawieniach** — jedno przeciągnięcie paska i jedno
   dotknięcie z dowolnej aplikacji, także znad Pilota 2. To jest droga główna;
2. **z powiadomienia** — niesie stan (`NADAJE — obraz idzie na stację · 2628 kb/s · 36 s`)
   i klawisze WSTRZYMAJ / ZAKOŃCZ; ⚠ klawisze widać dopiero po rozwinięciu, więc
   jest o jeden gest dalej niż kafelek;
3. z ekranu aplikacji — przed lotem.

Stan trzymany jest w jednym miejscu (`Stan.kt`), żeby wszystkie trzy pokazywały to samo.

⚠ **Kafelek trzeba raz dodać ręcznie** na Androidzie starszym niż 13: pasek szybkich
ustawień → ołówek → przeciągnąć „Zrzut ekranu". Na 13+ robi to klawisz DODAJ KAFELEK
w aplikacji. ⛔ Kafelek **nie potrafi wziąć zgody** (systemowego okna nie da się
pokazać z szybkich ustawień), więc gdy zgody nie ma, otwiera aplikację zamiast
udawać, że coś zrobił.

### Aplikacja schodzi z ekranu, ale nie z drogi

Ekran aparatury należy do DJI Pilot 2. Nasza aplikacja ma więc **zejść z widoku
zaraz po starcie**, nie zasłaniając obrazu z drona:

* **`UKRYJ`** — usuwa aplikację z ekranu, **nie zatrzymując nadawania**;
* **`Chowaj aplikację po starcie`** (domyślnie włączone) — po naciśnięciu START pilot
  widzi przez chwilę zieloną kartę (potwierdzenie, że obraz poszedł) i **ekran sam
  schodzi mu z drogi**. Jedno naciśnięcie zamiast dwóch, w chwili, gdy uwaga jest już
  przy maszynie.

Technicznie to `moveTaskToBack`, nie zamknięcie: zamknięcie aktywności zabiłoby usługę
razem ze **zgodą na przechwytywanie**, a wtedy wznowienie w locie znów wymagałoby
okienka systemowego. Ukrycie zostawia wszystko żywe i wraca do tego, co było pod
spodem — czyli zwykle do Pilota.

> ### ⛔ Dlaczego NIE ma pływającego klawisza nad Pilotem
>
> Przechwytujemy **cały ekran**, więc każda nasza nakładka trafiłaby do obrazu
> wysyłanego na stację — na nagraniu z lotu wisiałby nasz przycisk. Dlatego
> sterowanie w locie zostaje tam, gdzie obrazu nie zasłania: **kafelek szybkich
> ustawień** i **powiadomienie**.
>
> **Zmierzone w emulatorze:** po starcie aplikacja schodzi sama (`mCurrentFocus`
> wraca na launcher), a odbiornik przez 12 s **bez ani jednego dotknięcia** dostaje
> kolejne dane (720 → 782 kB). Powiadomienie w tym czasie pokazuje
> `NADAJE — obraz idzie na stację · 548 kb/s · 38 s`.
>
> ⚠ Pułapka przy sprawdzaniu, nie w aplikacji: stukanie w **stałe współrzędne** po
> schowaniu okna trafia w to, co akurat jest pod spodem — moje próbne dotknięcia
> nacisnęły WSTRZYMAJ i ZAKOŃCZ, co wyglądało jak zerwany strumień. Log usługi
> (`Wstrzymane (operator)`, `Zakończone (operator)`) rozstrzygnął, że to nie wada.

### Reszta ergonomii — skąd te decyzje

* **Zerwane łącze nie kończy przechwytywania.** W locie sieć potrafi mrugnąć;
  aplikacja ponawia w miejscu z rosnącą przerwą (1 → 15 s) i liczy ponowienia.
  Gdyby odpuszczała, pilot musiałby wracać po zgodę.
* **Ustawienia zablokowane w trakcie nadawania** — przypadkowe dotknięcie pola
  w locie nie zmieni adresu ani przepływności.
* **Stan kolorem, nie napisem**: zielony znaczy „obraz idzie", pomarańczowy
  „wstrzymane". Czytelne w słońcu i kątem oka.
* **Jeden wielki klawisz** zamiast dwóch małych — w rękawicach nie da się pomylić.
* **Skala obrazu** (domyślnie 75 %) — mniej pasma i mniej pracy kodera obok Pilota 2;
  w locie liczy się bardziej niż ostrość. Zmierzone: 1280×800 → 960×592.
* **SPRAWDŹ ŁĄCZE** — próba połączenia bez przechwytywania. Sprawdzenie na ziemi jest
  tanie; odkrycie w powietrzu, że adres jest zły, kosztuje lot.

### Wygląd — decyzje i powody

Ekran jest **dwukolumnowy**, bo kontroler jest szeroki i niski (7″ w poziomie):
lewa kolumna to stan i działanie, prawa — ustawienia dotykane raz przed lotem.
Jedna długa kolumna marnowała połowę ekranu i spychała ustawienia pod krawędź.

| Decyzja | Powód |
|---|---|
| **Karta stanu jest największym elementem** — jedno słowo (`NADAJE` / `WSTRZYMANE`), obok przepływność i czas | ma być czytelna z odległości wyciągniętej ręki, bez czytania zdań |
| **Kolor niesie stan**: zielony = obraz idzie, pomarańczowy = wstrzymane | rozpoznanie kątem oka, w słońcu; nic innego w aplikacji nie jest zielone |
| **Klawisz główny zmienia rolę**: zielone wypełnienie zaprasza do startu, pomarańczowa obwódka jest wyjściem | kolor nigdy nie kłamie o tym, co się stanie po dotknięciu |
| **Jeden wybór jakości** (`LEKKA` / `ZWYKŁA` / `OSTRA`) zamiast trzech liczb | klatki, skala i przepływność nie są niezależne — więcej klatek bez pasma daje kaszę. Godzenie tego można wykonać raz, tutaj. Wartości i tak widać pod spodem |
| **Wybór segmentowany zamiast pól liczbowych** | w rękawicach dotknięcie pola wyboru jest pewniejsze, a klawiatura ekranowa zasłania pół ekranu |
| **Ustawienia gasną i blokują się w trakcie nadawania** | zmiana adresu albo jakości w locie znaczyłaby zerwanie i odtworzenie łącza w najgorszym momencie |
| **Odzew na dotknięcie** (fala pod palcem) | w rękawicach trudno poznać, czy dotknięcie zostało przyjęte |
| **Pełny ekran z motywu okna** | pasek systemowy zabierał 24 dp, których kolumnie ustawień brakowało |
| **Material Design 3 (Material You)** | decyzja Toma z 2026-09-03. Karty, pola z obwódką i segmentowany wybór to gotowe komponenty z porządnymi stanami dotknięcia i typografią; na Androidzie 12+ dochodzą barwy z tapety systemu. ⚠ Koszt: **APK urósł z 0,84 MB do 5,7 MB** — świadomy, bo aplikacja pracuje obok DJI Pilot 2 |

> ### ⛔ Material odebrał barwom znaczenie — i trzeba było mu je odebrać z powrotem
>
> Po włączeniu Material 3 klawisz `SPRAWDŹ ŁĄCZE` zrobił się **pomarańczowy**:
> komponenty tonalne biorą tło z `colorSecondaryContainer`, a tam siedział kolor
> wstrzymania. W tej aplikacji pomarańczowy ma znaczyć „obraz stoi" i nic więcej,
> więc barwy drugorzędne motywu są teraz **neutralne**, a stan malujemy jawnie
> w kodzie. Ta sama zasada dotyczy Material You: na Androidzie 12+ paleta idzie
> z tapety, ale **zielony i pomarańczowy stanu zostają nasze** — inaczej barwa
> przestałaby cokolwiek znaczyć.
>
> ⚠ Drobiazg, który kosztował dwa podejścia: napisy na wyborze segmentowanym
> wychodziły ucięte (`LEK…`), bo styl klawisza Material ustawia zarówno
> `paddingStart/End`, jak i `paddingLeft/Right` — nadpisanie samych pierwszych
> nic nie daje.

Paleta jest ta sama, co na stacji — operator ogląda oba ekrany tego samego dnia
i nie powinien mieć wrażenia, że to dwa różne systemy.

⚠ **Dwie rzeczy wyszły dopiero na ekranie, nie w kodzie:** napis `OSZCZĘDNA` łamał się
w pigułce na pół (stąd krótkie `LEKKA`), a flaga `systemUiVisibility` chowała pasek
systemowy, ale zostawiała po nim **szary pas** — pełny ekran musi iść z motywu okna.

### Co trzeba zrobić przy sprzęcie

1. Wgrać APK na kontroler (`adb install` albo przez pamięć masową).
2. W aplikacji wpisać adres stacji (`192.168.88.30:5601`) i **hasło urządzenia**
   z panelu ADMIN (`GET /api/zrzut` podaje adres i hasło gotowe do przepisania).
3. START → Android zapyta o zgodę na przechwytywanie ekranu (**pyta za każdym razem,
   tego nie da się zapamiętać** — tak działa Android i jest to celowe).
4. Na stacji wybrać źródło **DJI — nadawany**.

---

## 8. ✅ Jedna aplikacja obsługuje OBIE drogi obrazu (2026-09-03)

Do tej pory zrzut ekranu i natywny RTMP z Pilota 2 były dwoma osobnymi pomysłami:
pierwszy miał aplikację, drugi — akapit w dokumentacji i długi adres do przepisania
z palca. **Teraz operator wpisuje dwa pola i ma obie drogi.**

### Adres RTMP składa się sam

Z tych samych dwóch wartości, które i tak trzeba podać zrzutowi:

```
adres stacji: 192.168.88.30:5601        hasło: <z panelu ADMIN>
                    ↓
rtmp://192.168.88.30:1935/dji2?user=dji&pass=<hasło>
```

Klawisze **KOPIUJ ADRES** i **OTWÓRZ PILOTA** stoją obok wypisanego adresu. Nie ma
czego zapamiętywać ani przepisywać — a literówka w tym ciągu daje nadawanie, które
milczy bez powodu.

| Droga | Ścieżka MediaMTX | Co daje |
|---|---|---|
| zrzut ekranu | `dji` | obraz **z nakładką OSD** — dla Mavic 3 Pro jedyna telemetria, jaka istnieje |
| natywny RTMP | `dji2` | **czysty obraz** z kamery, prosto ze strumienia drona |

Ścieżki są **różne celowo**: stacja dopuszcza obie (`SCIEZKI_NADAWANIA`), więc oba
obrazy mogą iść równocześnie i pokazać się jako dwa źródła. ⚠ Równocześnie znaczy
**podwójne pasmo w górę z jednej aparatury**, która przy tym prowadzi lot — na słabym
łączu zepsują się oba naraz. Zalecenie: ustawić obie, ale w locie trzymać włączoną jedną.

⛔ **Adres niesie hasło urządzenia** i po skopiowaniu zostaje w schowku aparatury.

### Aplikacja sama proponuje drugą drogę, gdy pierwsza zawodzi

| Objaw | Rozpoznanie | Co widzi operator |
|---|---|---|
| obraz pusty | poniżej **20 kb/s przez 6 s** — ten sam próg, co w `server/zrzut.mjs` | karta z czerwoną obwódką i klawiszem **PRZEŁĄCZ NA PILOTA 2** |
| łącze zrywa się | **3 ponowienia** lub więcej | karta z pomarańczową obwódką i tym samym klawiszem |

Miara czerni jest liczona **u źródła**, nie tylko na stacji — w polu nikt nie zagląda
do dziennika serwera. ⚠ To **podejrzenie, nie dowód**: nieruchomy ciemny ekran daje
podobny wynik. Dlatego skutkiem jest podpowiedź, a nie przełączenie czegokolwiek
za operatora.

### ⛔ Przy okazji wyszły dwa błędy, których nie widać było w kodzie

**1. Karta stanu kłamała barwą.** Przy zerwanym łączu aplikacja świeciła zielonym
`NADAJE` i pokazywała `0 kb/s` — bo `nadaje` znaczyło „operator włączył", a nie
„obraz dociera". Pilot mógł odejść od aparatury przekonany, że stacja ma obraz.
Stan rozróżnia teraz `nadaje` i `plynie`; przy martwym łączu karta jest pomarańczowa
i mówi **ŁĄCZY SIĘ**, powiadomienie — **„obraz NIE idzie"**, a kafelek — „łączy się…".
Z tego samego powodu **chowanie po starcie czeka na pierwsze klatki**, nie na samo
naciśnięcie.

**2. Klawisz główny wypadał poza ekran**, gdy pojawiała się karta podpowiedzi —
czyli dokładnie wtedy, kiedy był najbardziej potrzebny. Lewa kolumna ma teraz
**przewijany stan i przypięte klawisze**.

Obie rzeczy wyszły dopiero ze zrzutów ekranu z emulatora; w kodzie wyglądały poprawnie.

### Sprawdzone w emulatorze (2026-09-03)

| Rzecz | Stan |
|---|---|
| adres RTMP składany z dwóch pól | **FAKT** — `rtmp://10.0.2.2:1935/dji2?user=dji&pass=…` |
| kopiowanie do schowka | **FAKT** |
| podpowiedź po 3 ponowieniach + klawisz | **FAKT** |
| **ŁĄCZY SIĘ** zamiast zielonego **NADAJE** przy martwym łączu | **FAKT** |
| komunikat po naciśnięciu przestał ginąć | **FAKT** — „Nie znalazłem aplikacji DJI…" przeżyło cykle odświeżania |
| otwarcie aplikacji DJI | ⛔ **NIESPRAWDZONE** — na emulatorze jej nie ma; sprawdzona wyłącznie ścieżka „nie znalazłem" |
| wykrycie czerni | ⛔ **NIESPRAWDZONE** — brak ekranu, który DJI naprawdę zasłania |

Nazwy pakietów DJI (`dji.v5.pilot`, `dji.pilot`, `dji.go.v5`, `dji.go.v4`) są
**zgadywane** — do potwierdzenia na aparaturze. ⚠ Wymagają wpisu `<queries>`
w manifeście, bo Android 11+ ukrywa cudze pakiety.
