# DRON 15 — lista zadań

Jedno miejsce na to, co zostało do zrobienia. **Nie powtarza** dokumentów — odsyła do nich.
Uzasadnienia decyzji są w [PLAN.md](PLAN.md) §10 i w `dok/`, tu są tylko zadania.

**Ostatnia aktualizacja:** 2026-08-28

Znaczniki: 🔴 blokada · 🟠 ważne · 🟡 przydatne · ⬜ do zaplanowania · ✅ zrobione
Kolumna „sprzęt" mówi, czy zadanie wymaga drona pod napięciem.

---

## 1. Maszyna — przed następnym lotem

| # | Zadanie | Sprzęt | Skąd |
|---|---|---|---|
| 🔴 1.1 | **Świeży zrzut parametrów i nowy plik odniesienia.** `dok\ODNIESIENIE_QUAD_20260815.parm` ma `SERVO1..4_FUNCTION = 33,34,35,36`, a płyta po naprawie z 19:42 ma **34,36,33,35**. Wgranie obecnego pliku odniesienia **cofnęłoby naprawę salta** | tak | [PLAN.md](PLAN.md) §8a |
| 🔴 1.2 | **Kierunki obrotu silników** — nigdy nie zweryfikowane po korekcie `FRAME_CLASS`. Quad X wymaga: przód prawy CCW, tył lewy CCW, przód lewy CW, tył prawy CW. Zły kierunek daje ten sam objaw co złe mapowanie — wywrotkę przy oderwaniu | tak, bez śmigieł | `..\CLAUDE.md` §1 |
| 🔴 1.3 | **Test VTX ↔ GNSS** (poz. 36). Liczba satelitów przy nadajniku włączonym i wyłączonym. Przy `EK3_SRC1_YAW=2` zagłuszenie zabiera pozycję, kurs i RTL | tak | `..\CLAUDE.md` §6 poz. 36 |
| 🟠 1.4 | Poprawić w `..\CLAUDE.md` opis pliku odniesienia po wykonaniu 1.1 | nie | — |
| 🟡 1.5 | PID-y roll/pitch wciąż fabryczne; `MOT_THST_HOVER` wyuczony przy błędnym mikslerze | tak | `..\CLAUDE.md` §6 poz. 6 |

---

## 2. Serwer podglądu — da się zrobić od razu, bez drona

| # | Zadanie | Skąd |
|---|---|---|
| 🟠 2.1 | **Czy H.265 przez WHEP działa u realnych widzów** — podmienić `source` w `mediamtx.yml` na dowolne źródło H.265 i otworzyć stronę w Chrome, Safari i na telefonie | [SERWER_PODGLADU.md](dok/SERWER_PODGLADU.md) §10 poz. 1 |
| 🟠 2.2 | **Chromium w kiosku na dwóch monitorach 1080p** — czy dekodowanie H.265 idzie sprzętowo i ile bierze procesora | tamże, poz. 7 |
| 🟠 2.3 | **MTU tunelu WireGuard.** Objaw mylący: strona i telemetria działają, obraz się sypie. Sprawdzić przed wyjazdem | tamże §6.3 |
| ✅ 2.4 | **Archiwum zbudowane 2026-08-23.** Telemetria `.tlog` zapisywana zawsze (format Mission Plannera, odczytany przez `pymavlink` — 865 wiadomości w próbie), obraz przez MediaMTX w trzech trybach (`nie` / `przy-widzach` / `zawsze`), sprzątanie po czasie **i po zajętości dysku**, panel ARCHIWUM w administratorze | [WDROZENIE_RPI.md](dok/WDROZENIE_RPI.md) §6 |
| ✅ 2.5 | **Wdrożone 2026-08-28 na `GSB` (192.168.88.198).** Obie usługi active/enabled, telemetria 43 196 ramek bez błędu, obraz z ZR30 przez router, nagranie h264 720p sprawdzone `ffprobe`. Naprawione przy okazji: kolizja nazw `mediamtx` (binarka → `bin/`) i fałszywy `ERR` z `upsertPath` | [WDROZENIE_RPI.md](dok/WDROZENIE_RPI.md) §0 |
| ⬜ 2.5b | *(archiwum)* pierwotne zadanie: **Uruchomić zestaw wdrożeniowy na żywej malinie.** `serwer/rpi/` ma instalator, dwie jednostki systemd, kiosk i przegląd stacji — **nic z tego nie chodziło jeszcze na RPi**. Pierwsze uruchomienie traktować jak próbę | [WDROZENIE_RPI.md](dok/WDROZENIE_RPI.md) §2–§4 |
| ✅ 2.7 | **Panel STACJA zbudowany 2026-08-23** — usługi z restartem, zasilanie, temperatura, sieć z MTU, porty, wersje, dekoder HEVC, dziennik systemowy. Odpowiednik `rpi/sprawdz.sh` w przeglądarce. Uprawnienia wąskie: grupa `adm` do dziennika, dwa wpisy w sudoers do restartu | [WDROZENIE_RPI.md](dok/WDROZENIE_RPI.md) §4a |
| ✅ 2.9 | **Kiosk dostał zaproszenie (2026-08-23).** Wcześniej `rpi/kiosk.sh` otwierał stronę bez kodu, więc na monitorach stacji stanąłby ekran wejścia zamiast obrazu — strona i strumień WHEP wymagają żetonu osobno. Teraz `KOD=<kod>` doklejany do adresu; zaproszenie ma być **wielokrotne**, bo każde okno ma własny profil Chromium | [WDROZENIE_RPI.md](dok/WDROZENIE_RPI.md) §4 |
| 🟠 2.8 | **Sprawdzić panel STACJA na malinie w przeglądarce** — sudoers i grupa `adm` założone przez instalator, ale samego panelu nikt jeszcze nie otworzył. Dawniej: **Sprawdzić panel STACJA na malinie** — na Windows przetestowane odczyty i zamknięta lista poleceń, ale systemd, sudoers i `journalctl` nie chodziły ani razu. Razem z 2.5 | tamże |
| ✅ 2.6a | **Adres stacji ustawiony na stałe 2026-08-28: `192.168.88.30`** (NetworkManager, `ipv4.method manual`, brama i DNS `.1`). Zmiana zrobiona z siatką bezpieczeństwa — powrót na DHCP po 5 min, rozbrojony po potwierdzeniu kontaktu | [WDROZENIE_RPI.md](dok/WDROZENIE_RPI.md) §0 |
| 🟠 2.6b | **Wyłączyć `.30` z puli DHCP routera albo zrobić rezerwację** na `192.168.88.1`. Adres statyczny wewnątrz puli działa, dopóki router nie przydzieli go komuś innemu | tamże |
| 🟡 2.11 | **Dziennik systemowy jest ulotny** — po restarcie maliny kod administratora znika z `journalctl`. Odzyskiwalny z `/var/lib/dron15/dostep.json`. Do rozważenia trwały dziennik (`/var/log/journal`) albo zapisanie linku zapraszającego | [WDROZENIE_RPI.md](dok/WDROZENIE_RPI.md) §0 |
| 🟠 2.12 | **Panel bez wyjścia — naprawione, NIEWGRANE.** Po dniu użytkowania: wejście w ADMIN blokowało nawigację, nie dało się wysłać zaproszeń ani odciąć widza. Przyczyna: `justify-content: center` przy `overflow-y: auto` obcinało górę panelu. Poprawione (`flex-start`, przyklejony nagłówek, przełącznik paneli, Escape) i zbudowane — **czeka na wgranie, gdy malina wróci w zasięg** | [AUDYT_UI.md](dok/AUDYT_UI.md) |
| ✅ 2.13 | **Mapa 2026-08-29:** pozycja maszyny ze śladem, punkt startu (`HOME_POSITION`), trasy z plików `.plan`/`.waypoints` **i** z podsłuchu łącza, dwa podkłady. **Kafelki ciągnie przeglądarka widza**, nie stacja. Oddokowanie mapy i obrazu na drugi monitor osobnym oknem | [SERWER_PODGLADU.md](dok/SERWER_PODGLADU.md) |
| ✅ 2.14 | **Mapa sprawdzona na żywej maszynie 2026-08-29:** `HOME_POSITION` (dom 0,3 m od maszyny), znacznik obrócony kursem, DOM 2 m, 24 kafelki. Naprawione przy okazji: Leaflet nie przeliczał rozmiaru pojemnika (2 kafelki zamiast 24, znacznik przy krawędzi) i punkt startu zostawał bez zastrzeżenia po utracie łącza. **Ślad sprawdzony na symulatorze** (850 → 1333 px w 22 s, znacznik obraca się z kursem). Zostaje wyłącznie trasa z podsłuchu łącza — wymaga transferu misji | [SERWER_PODGLADU.md](dok/SERWER_PODGLADU.md) |
| ⬜ 2.14b | *(archiwum)* **Mapy nie widziano z żywą maszyną** — dron był wyłączony. Niesprawdzone: znacznik pozycji, obrót kursem, ślad przelotu, punkt startu z `HOME_POSITION` i trasa złapana z łącza. Parsery plików i rysowanie sprawdzone na danych zastępczych | tamże |
| ✅ 2.15 | **Serwer oddawał HTTP 200 na nieistniejący pakiet** — reguła „wszystko inne to strona" wysyłała HTML tam, gdzie przeglądarka czekała na JavaScript (`Unexpected token <`). Teraz `/assets/*` daje 404, `index.html` chodzi z `no-store`, a pakiety z `immutable` | — |
| ✅ 2.16 | **Obsługa pokrętłem stacji 2026-08-29.** Most do panelu GC9A01 (`/run/gcs/pokretlo.sock`), obrót przechodzi, klik naciska i wchodzi w pola, przytrzymanie cofa. **Nic nie odebrane myszy** — pokrętło rusza zwykłym ogniskiem. Sprawdzone: most, przekazanie ogniska, zakres wodzenia, widoczność obwódki bez ogniska okna | [POKRETLO.md](dok/POKRETLO.md) |
| 🟠 2.17 | **Przekręcić pokrętłem na stacji** — obrót, klik i przytrzymanie z prawdziwego enkodera. Nie da się sprawdzić zdalnie: klienci mostu mogą odbierać zdarzenia, ale nie mogą ich wstrzykiwać (i dobrze) | tamże §5 |
| ✅ 2.18 | **Strona wstaje na pełnym ekranie** — `rpi/podglad.sh` + kafelek pulpitu GCS. Sprawdzone zrzutem z ekranu stacji: 1920×1080 bez ramki, dymek tłumaczenia zgaszony | dok/POKRETLO.md §7 |
| 🟠 2.19 | **Uruchomić podgląd z kafelka pulpitu**, ręką operatora. Próby robiłem tym samym poleceniem, ale spod ssh — uruchomienia z samego pulpitu nikt jeszcze nie przejechał | tamże |
| ✅ 2.20 | **Obsługa bez myszy naprawiona w trzech miejscach:** strona bierze pokrętło sama (`pokretlo=1`), hook czeka na żeton zamiast poddawać się przy montażu, a strzałki pilota wodzą ogniskiem. Skrót z pulpitu niesie wreszcie adres z kodem | dok/POKRETLO.md §8–9 |
| ✅ 2.22 | **Pokrętło nie wyłącza już samo siebie** — omija własny klawisz; długie przytrzymanie (≥2 s) zostawione panelowi, żeby oddanie pokrętła nie zamykało panelu | dok/POKRETLO.md §10 |
| ✅ 2.23 | **Klawisz ZAMKNIJ na stacji** — okno pełnoekranowe bez klawiatury nie miało wyjścia. Dwustopniowo (przeglądarka, potem serwer), tylko z ekranu stacji (`403` z zewnątrz) | dok/POKRETLO.md §11 |
| ✅ 2.24 | **Stacja ma rolę administratora** — własne zaproszenie `stacja RPi`, więc z jej ekranu da się wydawać zaproszenia i zmieniać ustawienia | tamże |
| ✅ 2.25 | **Wejście RTMP dla dronów DJI** — obraz z Mavic 3 Pro i 3T wpina się w istniejący tor. Osobne hasło nadawania, tylko ścieżki `dji`/`dji2`, port otwiera się tylko gdy jest źródło nadawane | dok/DJI.md §2 |
| ✅ 2.26 | **Telemetria DJI zbudowana** — broker MQTT w serwerze (`aedes`), przejściówka Cloud API → nasz `stan()`, strona `/dji.html` dla Pilot 2. Sprawdzone udawanym Pilotem przez prawdziwe API stacji | dok/DJI.md §3a |
| 🔴 2.27 | ⛔ **Licencja Cloud API od DJI** — `appId`, `appKey`, `license` z konta deweloperskiego. Bez nich Pilot 2 nie załaduje modułu chmurowego i telemetria DJI nie ruszy. Tylko właściciel konta może je uzyskać | dok/DJI.md §3a |
| 🟠 2.28 | Pola licencji DJI w panelu ADMIN — dziś ustawia się je tylko przez API | tamże |
| ✅ 2.29 | **APK zrzutu ekranu aparatury DJI** — ⚠ **od 2026-09-03 w SmartGCS `dji/`** (przeniesiony, nie forkowany; tu zostaje odbiornik `serwer/server/zrzut.mjs`); wcześniej moduł `app/zrzut`, MediaProjection → H.264 → TCP → ffmpeg → RTMP. Buduje się; odbiór sprawdzony lokalnie (hasło, jeden nadawca, przekazanie do ffmpeg) | dok/DJI.md §7 |
| 🔴 2.30 | ⛔ **Sprawdzić, czy DJI nie blokuje zrzutu (`FLAG_SECURE`)** — jeśli tak, obraz będzie czarny i nie da się tego obejść. Stacja zgłasza podejrzenie po przepływności; rozstrzyga spojrzenie na podgląd | tamże |
| 🟠 2.31 | Wgrać **Horyzont** (`horyzont-debug.apk`, SmartGCS `dji/`) na kontroler DJI i przejechać całą drogę — stacja była wyłączona przy budowaniu | tamże |
| ✅ 2.32 | **Obsługa w locie: pauza zamiast zatrzymania** — zgoda brana raz, start/stop przełącza samo wysyłanie. Kafelek szybkich ustawień + powiadomienie + ekran; ponawianie łącza; ustawienia zablokowane w trakcie nadawania | dok/DJI.md §7 |
| ✅ 2.33 | **Interfejs przeniesiony na Material Design 3 (Material You)** — karty, pola z obwódką, wybór segmentowany, motyw ciemny, barwy z tapety na Androidzie 12+. APK 0,84 → 5,7 MB | dok/DJI.md §7 |
| ✅ 2.34 | **Aplikacja schodzi z ekranu** — klawisz UKRYJ i chowanie po starcie (domyślnie włączone). Obraz leci dalej; sprawdzone: 12 s w tle bez dotknięcia, dane płyną | dok/DJI.md §7 |
| ✅ 2.35 | **Obie drogi obrazu w jednej aplikacji** — z dwóch pól (adres + hasło) składa się gotowy adres RTMP dla Pilota 2 (`dji2`), z klawiszami KOPIUJ i OTWÓRZ PILOTA. Zrzut zostaje na ścieżce `dji`, więc oba obrazy mogą iść naraz | dok/DJI.md §8 |
| ✅ 2.36 | **Podpowiedź drugiej drogi, gdy pierwsza zawodzi** — obraz pusty (<20 kb/s przez 6 s) albo 3 ponowienia łącza. Miara czerni liczona na aparaturze, nie tylko na stacji | tamże |
| ✅ 2.37 | ⛔ **Naprawione: karta stanu kłamała barwą** — zielone `NADAJE` przy martwym łączu. Rozdzielone `nadaje` (operator włączył) i `plynie` (klatki idą); chowanie po starcie czeka teraz na pierwsze klatki | tamże |
| ✅ 2.38 | ⛔ **Naprawione: klawisz główny wypadał poza ekran** przy karcie podpowiedzi. Lewa kolumna: stan przewijany, klawisze przypięte | tamże |
| 🟠 2.40 | **Edycja źródeł w panelu ADMIN stacji** — dodawanie, zmiana i usuwanie z przestawieniem MediaMTX na żywo. Maszyneria już jest (`server/mediamtx.mjs`, `writeZrodla`); brakuje trzech punktów API i formularza. ⏸ **Wstrzymane** — najpierw rozstrzygnąć podział ról między stacją a NAGRYWARKĄ pulpitu (2.42) | dok/DJI.md §8 |
| ✅ 2.41 | ~~Sprawdzić ścieżkę RTSP analogu~~ → **`rtsp://192.168.88.30:8554/uav`** (H.264 640×480 30 kl./s, 2 Mb/s, GStreamer), potwierdzone DESCRIBE 2026-09-03. ⚠ **NAGRYWARKA pulpitu już to źródło ma i nagrywa** (`~/.config/gcs/zrodla-obrazu.json`, id `cvbs`) — wpinanie go do stacji byłoby trzecią kopią tego samego obrazu | `gcs_pulpit/nagrywarka.py` |
| ✅ 2.42 | **Podział ról rejestratorów — ZROBIONE 2026-09-03.** NAGRYWARKA pulpitu = jedyny rejestrator obrazu, stacja = rozdzielnia: MediaMTX wystawia RTSP na `127.0.0.1:8555` (sam TCP), `mtx-auth` przepuszcza RTSP z pętli bez żetonu (warunek na protokół), nagrywarka ma ZR30 przez stację (`rtsp://127.0.0.1:8555/zr30`) + `dji`/`dji2` poza REC, archiwum stacji `wideo: nie`. Cały tor DJI zmierzony na pętli (wzorzec RTMP → RTSP → `-c copy` kod 0). ⛔ Niesprawdzone: REC w nagrywarce z prawdziwą aparaturą | dok/REJESTRATORY.md |
| 🔴 2.43 | ⛔ **Zegar stacji bez źródła czasu** — 18 h spóźnienia, RTC 1970, brak internetu w `.88`, router nie odpowiada na NTP. Ustawiony ręcznie z laptopa = plaster do następnego wyłączenia. Trwałe: NTP na MikroTiku albo moduł RTC z baterią | dok/REJESTRATORY.md §5 |
| ✅ 2.44 | **NAGRYWARKA wydzielona do osobnej aplikacji — w SmartGCS** (`RPI-nagrywarka`, gałąź `feat/nagrywarka-osobno`, commity `f7138ac` + docs): `nagrywarka/` (silnik bez GTK + widoki + CLI + instalator), `wspolne/gcs_wspolne`. Wgrane na GSB 2026-09-03 17:56 etapami, pulpit wstał (`NRestarts=0`), `proba cvbs` nagrała 8 s. ⚠ Ekranu NAGRYWARKA nie otwierano zdalnie; REC na `dji` wymaga aparatury. Gałąź **niewypchnięta** | SmartGCS `nagrywarka/README.md` |
| ✅ 2.45 | `rpi/wgraj.ps1`: `tar --warning=no-timestamp` — przy spóźnionym zegarze ostrzeżenie na stderr przerywało wgrywanie w połowie rozpakowywania (stan mieszany w `/opt/dron15`) | dok/REJESTRATORY.md §5 |
| ✅ 2.46 | Usunięty martwy `web/public/zrodla.json` + `generateWebConfig()` — kopiowany do `dist` tylko przy budowaniu, na stacji stary od 29.08, czytany przez nikogo, a wystawiał listę źródeł bez żetonu | dok/REJESTRATORY.md §6 |
| ✅ 2.47 | **WGRANE NA GSB 2026-09-03 19:00** (decyzja Toma: przed próbami). Stacja przemianowana z „DRON 15 — podgląd" na PANORAMA** (gałąź `feat/panorama`, commit `459522b`): usługi `panorama-*`, `/opt/panorama`, `/var/lib/panorama`, sudoers, kafelek, marka na stronie/PWA; klucze localStorage i odwołania do maszyny DRON 15 celowo bez zmian. `instaluj.sh` ma idempotentny krok przenosin. **Na GSB wgrać PO próbach 2026-09-04** — żeby regresję MK32 mierzyć na znanym stanie. SmartGCS gotowe: nagrywarka czyta obie ścieżki danych, kafelek `30-panorama.json` (`6fdd723`) | serwer/README.md |
| ✅ 2.48 | **WGRANE NA GSB 2026-09-03 19:00.** Źródła z panelu ADMIN + hasło na źródło + mozaika** (gałąź `feat/panorama`): dodawanie/ukrywanie/usuwanie źródeł na żywo (API MediaMTX), każdy dron DJI z własnym hasłem (`nadawanie.json`; odbiornik zrzutu poznaje drona po haśle), lista widza bez ukrytych, ekran główny: 1 źródło = pełny ekran, 2–6 = mozaika kafelków, limit 6. 42 kroki sprawdzone lokalnie na żywym serwerze i MediaMTX. **Na GSB wgrać po próbach 2026-09-04** razem z 2.47. Niesprawdzone: mozaika na malinie (wydajność 6 kafelków), APK z hasłem drona | serwer/README.md |
| ✅ 2.49 | **Kafelek Panoramy nie dawał ADMIN (2026-09-03/04) — trzy przyczyny naraz, zmierzone na GSB.** (a) `instaluj.sh` położył kafelek z kodem **widza** („monitory stacji”) zamiast admina — profil Chromium okna zapamiętał żeton widza; (b) strona dawała pierwszeństwo zapamiętanemu żetonowi nad kodem `#z=` z adresu, więc poprawiony kafelek nic nie zmieniał (naprawione `818dd89`: inny kod w adresie przyjmuje zaproszenie od nowa; żeton pamięta swój kod); (c) po naprawie okna nikt nie otworzył — dziennik pulpitu: jedno uruchomienie 15:50, przed poprawką. Rozstrzygnięte 21:18 uruchomieniem kafelka po ssh i zrzutem ekranu `grim`: wejście **stacja RPi / admin**, ADMIN i STACJA na dole po prawej. Przy okazji: pulpit pisał „okno »podglad« się nie pojawiło”, bo app_id Chromium to `chrome-192.168.88.30__-Default` — kafelek dostał `"okno": "chrome-"`; stare skróty `dron15-podglad.desktop` na pulpicie maliny przepięte na `/opt/panorama`. ⚠ Kod w kafelku MUSI być zaproszeniem **wielokrotnym** (jednorazowe zużyje się przy pierwszym oknie i każde następne wejdzie bez żetonu) | serwer/README.md, dok/PROBY_20260904.md A6 |
| 🟠 2.39 | Potwierdzić na aparaturze **nazwy pakietów DJI** (`dji.v5.pilot`…) — klawisz OTWÓRZ PILOTA sprawdzony wyłącznie w ścieżce „nie znalazłem" | tamże |
| — 2.21 | *(nie jest usterką)* Brak obrazu i telemetrii przy próbach 2026-08-29 wieczorem: cała sieć 192.168.144.x milczała, bo **drona po prostu nie było**. MediaMTX `ready: false` i WHEP 400 to skutek braku źródła, nie wada strony. Zapisane, żeby nikt nie ścigał tego jako awarii | — |
| 🔴 2.10 | ⛔ **Nagranie obrazu gubi ok. 14 % materiału.** Zmierzone: 51,9 s z 60 s okna, trzy pliki zamiast jednego. MediaMTX resetuje nagranie co ~23 s (`detected drift between recording duration and absolute time`), bo kamera deklaruje 30 kl./s, a oddaje 25–28. Zmiana kontenera na `mpegts` **nie pomaga**. `.tlog` nietknięty | [WDROZENIE_RPI.md](dok/WDROZENIE_RPI.md) §0 |
| 🟡 2.6 | Zdecydować, czy nagrania mają iść na **NVMe**, i wtedy przestawić `ARCHIWUM_DIR` w jednostkach systemd. Domyślnie wszystko ląduje w `/var/lib/dron15` | tamże §3 |

---

## 3. Dostęp zdalny

**Stan:** endpoint WireGuarda podawany ręcznie — stacja pokazuje adres, operator przepisuje.
Zrobione 2026-08-20 (decyzja 6): [SERWER_PODGLADU.md](dok/SERWER_PODGLADU.md) §6.5.

| # | Zadanie | Skąd |
|---|---|---|
| ✅ 3.1 | Panel **DOSTĘP** na stronie + `GET /api/adresy` z endpointem `ADRES:51820` | — |
| 🟡 3.2 | Sprawdzić całą drogę przez tunel od telefonu: strona, telemetria, obraz | §6.2 |
| ⬜ 3.3 | **Serwer koordynujący — odłożony, nie odrzucony.** Wraca do gry dopiero wtedy, gdy stacja ma być osiągalna z pola na 4G. Wtedy bierzemy gotowe (NetBird albo headscale na VPS), nie piszemy własnego: poprawna wersja wymaga hole punchingu, przekaźnika przy symetrycznym NAT i kluczy z terminem ważności | §6.6 |

---

## 4. Wielu użytkowników, panel administratora

Zatwierdzone i **zbudowane 2026-08-20**: zaproszenia linkiem, trzy role, lista „kto ogląda"
z imionami, panel administratora, odcinanie na trzech warstwach, limit widzów, tryb ciszy,
dziennik. Opis i wyniki prób: [dok/DOSTEP_I_UZYTKOWNICY.md](dok/DOSTEP_I_UZYTKOWNICY.md).

| # | Zadanie | Stan |
|---|---|---|
| ✅ 4.1 | Projekt zatwierdzony: linki zapraszające, imiona widoczne, źródło domyślne od admina z możliwością zmiany (decyzje 7–9) | — |
| ✅ 4.2 | Spisane do `dok/DOSTEP_I_UZYTKOWNICY.md` | — |
| ✅ 4.3 | **Ryzyko rozpoznane i zamknięte:** MediaMTX v1.19.0 ma `authMethod: http` **oraz** `/v3/webrtcsessions/kick/`. Sprawdzone na żywo: WHEP bez poświadczeń → 401, z poprawnym żetonem → przechodzi | — |
| 🟠 4.4 | **Ubicie trwającej sesji obrazu — jedyna rzecz niesprawdzona.** Kod jest, endpoint potwierdzony, ale przy próbie nie było prawdziwej sesji WebRTC do ubicia. Wymaga przeglądarki i działającego źródła obrazu (razem z 2.1) | ⬜ |
| 🟡 4.5 | Rola `operator` istnieje, ale nie ma jeszcze własnych uprawnień — do wypełnienia albo usunięcia | ⬜ |

---

## 4a. Logi i obsługa błędów

Zbudowane 2026-08-20 dla wszystkich trzech części projektu.
Opis: [dok/LOGI_I_BLEDY.md](dok/LOGI_I_BLEDY.md).

| # | Zadanie | Stan |
|---|---|---|
| ✅ 4a.1 | Kokpit: `diag/Dziennik.kt`, pułapka w `KokpitApp`, log na kartę, ślad po awarii przeżywający restart, rejestr na ekranie DIAGNOSTYKA | — |
| ✅ 4a.2 | Serwer: `server/rejestr.mjs`, pułapki procesu, warstwa błędu Express, 404 dla `/api/*`, podgląd w panelu admina, dozorca `start.sh --pilnuj` | — |
| ✅ 4a.3 | Narzędzia: `tools/dziennik.py` wpięty w 22 skrypty, komunikaty po polsku zamiast surowych wyjątków | — |
| ✅ 4a.4 | **Jednostki systemd napisane 2026-08-23:** `dron15-mediamtx` (Restart=always), `dron15-gcs` (**Restart=on-failure** — wyjście kodem 0 znaczy „zatrzymany świadomie" i wznawiania nie wymaga, zgodnie z [LOGI_I_BLEDY.md](dok/LOGI_I_BLEDY.md) §2), `dron15-kiosk` jako jednostka użytkownika. `--pilnuj` zostaje wyłącznie do prób. **Do sprawdzenia na malinie — zadanie 2.5** | ⬜ próba |
| 🟡 4a.5 | Sprawdzić pułapkę awaryjną na prawdziwym MK32 (wymaga aparatury) | ⬜ |

---

## 5. Aplikacja MK32 — etapy z PLAN.md

Pełny stan: [PLAN.md](PLAN.md) §8a. Tu tylko to, co blokuje.

| # | Zadanie | Sprzęt |
|---|---|---|
| 🔴 5.1 | **M0 — żadne z trzech łączy nie rozmawiało z prawdziwym sprzętem.** Telemetria z symulatora, obrazu z kamery nie było ani razu | tak |
| 🟠 5.2 | M2 — ekran KAMERA nigdy nie rozmawiał z ZR30 | tak |
| 🟠 5.3 | M4 — edytor i wysyłka misji; jest tylko mapa i podgląd trasy | częściowo |
| ✅ 5.5 | **Mapy dostały providera (2026-08-25).** Pięć podkładów (hybryda obowiązkowa, zdjęcia, topo, mapa, noc) na LOT i MISJI, dane wysokościowe Terrarium, cieniowanie rzeźby, warstwice, pierścień azymutu, **widok 3D terenu** i **profil trasy z prześwitem nad gruntem**. 46 testów w `MapyTest.kt`. Opis: [dok/MAPY.md](dok/MAPY.md) | nie |
| ✅ 5.6 | ~~Sprawdzić mapy na aparaturze~~ → **sprawdzone 2026-08-26 na MK32.** Działa podkład hybrydowy na LOT i MISJI, ślad, trasa, prześwit przy punktach, profil trasy i **widok 3D (`drawBitmapMesh`)**. Chipy bez kafelków wyszarzone zgodnie z opisem. Przy okazji dwie usterki znalezione i naprawione (wartownik wieku telemetrii na belce, podziałka przekreślająca litery stron świata) — [dok/PIERWSZY_TEST_MK32.md](dok/PIERWSZY_TEST_MK32.md) §4d. **Nie sprawdzone: płynność mapy przy równoczesnym obrazie z kamery** — obrazu nie było | częściowo |
| ✅ 5.9 | **Zoom i mapa z internetu (2026-08-26).** Przybliżanie i oddalanie szczypnięciem **albo klawiszami `−`/`+`** (drabina 50 m – 20 km) na LOT, MISJI i w widoku 3D — w 3D zoom zmienia wielkość pokazywanego terenu, nie odległość kamery. **Mapa i dane wysokościowe dociągają się z internetu** i zostają na aparaturze na później; przełącznik w panelu warstw, domyślnie włączony. Sprawdzone w emulatorze: 111 kafelków pobranych z sieci, rzeźba Beskidu w 3D. Opis: [dok/MAPY.md](dok/MAPY.md) §1a | nie |
| 🟠 5.10 | **Wgrać na aparaturę wydanie z zoomem i pobieraniem z sieci** — na MK32 stoi wydanie sprzed 5.9 | tak |
| 🔴 5.9 | ⛔ **USTAWIĆ ZEGAR APARATURY.** MK32 stoi na **2 X 2023**, strefa `Asia/Shanghai`, czas automatyczny wyłączony. Skutek: **każde połączenie HTTPS z aparatury pada** (certyfikaty są dla niej „jeszcze nieważne") — nie pobierze się żaden kafelek mapy — a daty w logach aplikacji i w nazwach zapisywanych plików są sprzed trzech lat. Naprawa: *Ustawienia → System → Data i godzina → automatyczna* + strefa `Europe/Warsaw`, albo `adb shell settings put global auto_time 1`. Dotyczy całego Androida, więc też SIYI FPV i TX — dlatego nie zmieniam tego bez decyzji. Pomiar: [dok/PIERWSZY_TEST_MK32.md](dok/PIERWSZY_TEST_MK32.md) §4e | tak |
| 🟠 5.10 | **Całe drzewo interfejsu przelicza się przy każdej paczce telemetrii.** `StanMaszyny` idzie w dół jako jedna wartość, więc zmiana czegokolwiek unieważnia wszystko: ok. 8 przeliczeń na sekundę po 26–28 ms, na każdym ekranie (nawet na zwykłej liście DIAGNOSTYKI). Budżet klatki to 16,7 ms. Rozkład: pomiar/układ 6,8 ms + GPU 7,9 ms, rysowanie 0,2 ms. Kierunek: podawać stan jako `State<T>` albo lambdy zamiast wartości, żeby unieważniał się tylko ten fragment, który naprawdę się zmienił; osobno sprawdzić przezroczystości i cienie (GPU). Pomiar: [dok/PIERWSZY_TEST_MK32.md](dok/PIERWSZY_TEST_MK32.md) §4f | nie |
| 🟡 5.11 | **Decyzja o kluczu podpisu.** Projekt nie ma `signingConfig`, więc `assembleRelease` daje APK niepodpisany, a wariant `debug` jest o 15–20 % wolniejszy i o 55 MB większy. Na aparaturze stoi dziś release podpisany **kluczem debug** — do zastąpienia własnym kluczem projektu, jeśli aplikacja ma być rozdawana | nie |
| 🟠 5.8 | **Płynność mapy i 3D przy równoczesnym obrazie z ZR30.** Jedyna część 5.6, której nie dało się sprawdzić bez air unitu. Mierzyć razem z 5.1 | tak |
| 🟠 5.7 | **Pobrać kafelki i teren pod PRAWDZIWY rejon lotów.** Na karcie aparatury leży od 2026-08-26 komplet 674 plików (hybryda, topo, teren), ale **wyłącznie dla rejonu symulatora** (52,1235 N / 20,1235 E) — poza tym kwadratem mapa będzie pusta. Przed wyjazdem: `python narzedzia\kafelki.py --lat .. --lon .. --promien 3` i `--wgraj`. Kontrola: `--stan`. ⚠ **`--wgraj` musi iść przez archiwum, nie przez `adb push` na katalogu** — przy 671 plikach `adb` przewraca się na `std::bad_alloc` po 9 minutach i nie zostawia niczego (§4d) | nie |
| 🟡 5.4 | M5 — udostępnianie i władza: prototyp `mav_router.py` działa, w aplikacji nie ma. **Pole władzy w pasie górnym już jest** (`PasGorny(steruje = …)`) — pokazuje „STERUJESZ TY" i czeka na dane | nie |

---

## 5a. Przekazanie M3 — co zostało po wdrożeniu

Wdrożone 2026-08-24: [dok/PRZEKAZANIE_M3.md](dok/PRZEKAZANIE_M3.md), kroki 1–6 i 8.

| # | Zadanie | Sprzęt |
|---|---|---|
| 🔴 5a.1 | **Komendy SIYI dopisane w §6 nie rozmawiały z głowicą** — `0x0F`, `0x16`, `0x18`, `0x04`, `0x06`, `0x20`, `0x21`, `0x25` i tryb LOCK/FOLLOW/FPV. Układ ładunku wzięty z instrukcji, nie z pomiaru. Sprawdzić `narzedzia/siyi_gimbal.py` z podłączonym ZR30 na biurku, poprawić `net/siyi/KlientSiyi.kt` | tak |
| 🔴 5a.2 | **Wysyłka i pobranie misji nie rozmawiały z FC.** Symulator nie obsługuje wiadomości misji. Sprawdzić na maszynie: WYŚLIJ → czy trasa pojawia się w Mission Plannerze; POBIERZ → czy wraca ta sama | tak |
| ✅ 5a.3 | ~~Makiety nie ma w repozytorium~~ → **znalezione 2026-08-24** w `mk32app\Aplikacja mobilna dla DRON\`. Przeszedłem makietę linia po linii; różnice i poprawki spisane w [dok/PRZEKAZANIE_M3.md](dok/PRZEKAZANIE_M3.md), sekcja „Czego użyto jako źródła" | nie |
| 🟠 5a.9 | **Nawigacja wg [NAWIGACJA.md](Aplikacja%20mobilna%20dla%20DRON/NAWIGACJA.md)** — Compose Destinations + KSP. Menu widoków działa, ale bez biblioteki nie ma: przejść 150 ms, zachowania stanu ekranu przy powrocie, przycisku wstecz i zwinięcia siedemnastu lambd w `dependenciesContainerBuilder`. Warunek odbioru z §3 tego pliku: **zmierzyć rozmiar APK po dołożeniu** — plan §7 stawia go jako twarde ograniczenie | nie |
| 🟡 5a.10 | **Makieta `DRON 15 Telefon.dc.html`** leży w tym samym katalogu i nie jest jeszcze niczym pokryta — osobna aplikacja mobilna, poza zakresem kokpitu MK32. Do rozstrzygnięcia, czy w ogóle wchodzi w plan | nie |
| ✅ 5a.4 | ~~Cele dotykowe poniżej 64 dp~~ → **naprawione 2026-08-26 po zgłoszeniu z ręki.** Klawisze komend 44 × 40 → **72 × 68 dp** (11,3 × 10,7 mm), chipy mapy 28–34 → **64 dp**, zakładki kamery i klawisze zasięgu też 64. `Chip` podnosi teraz każdą podaną wysokość do `Wymiary.CelDotyku`, więc żadne wywołanie nie zejdzie niżej. Ciekawostka: token `CelDotyku = 64.dp` **był w projekcie od początku i nie używał go nikt**. Zostaje: znaczniki punktów trasy (24 dp) i sprawdzenie **w rękawiczkach**. Pomiar: [dok/PIERWSZY_TEST_MK32.md](dok/PIERWSZY_TEST_MK32.md) §4g | tak |
| 🟡 5.12 | **Podpowiedzi na mapie nieczytelne na jasnym zdjęciu.** `DOTKNIJ MAPĘ, ŻEBY DOŁOŻYĆ PUNKT` i podobne są pisane barwą wygaszoną wprost na kadrze — na zdjęciu lotniczym w słońcu znikają. Dać obwódkę albo półprzezroczystą podkładkę | nie |
| 🟠 5a.5 | **Adresy i POI — decyzja o źródle danych offline** (§5 przekazania): lokalny indeks z OSM na karcie albo import punktów przygotowany na stacji. Do czasu decyzji obie zakładki mówią, czego brakuje | nie |
| 🟡 5a.6 | **Jasny motyw na ciemnym kadrze.** Elementy bez podkładki dostały tła z tokenów `instr*`, ale przy nocnym obrazie z kamery jasny motyw i tak będzie gorszy. Sprawdzić w polu, czy nie warto przełączać motywu automatycznie po jasności kadru | tak |
| 🟡 5a.7 | **Sprawdzić zapis `.plan` na karcie MK32** — `/sdcard/dron15/misje`. W emulatorze katalog powstaje, na aparaturze zależy od uprawnień | tak |
| 🟡 5a.8 | Zakładka AI: **hipoteza do rozstrzygnięcia** — czy modułem SIYI AI Tracking da się w ogóle sterować z naszego kodu, czy tylko z aplikacji SIYI FPV. Sprawdzić **przed** planowaniem tej funkcji | tak |

---

## 5b. Audyt 2026-08-26 — znaleziska

Pełny raport: [dok/AUDYT_M3.md](dok/AUDYT_M3.md). Kolejność napraw jak w §8 tamtego pliku.

| # | Zadanie | Sprzęt |
|---|---|---|
| 🔴 5b.1 | **Checklista każe przywrócić archiwalne mapowanie silników.** `preflight_rules.json` reguła `silniki` żąda `SERVO1..4_FUNCTION = 34/36/33/35` — to archiwalne mapowanie z 2026-08-15, oznaczone w `CLAUDE.md` jako **nieaktualne**. Obowiązuje **36/33/34/35** (lot 2 z 2026-08-16). Reguła jest poziomu `blokada`, więc na poprawnej maszynie zapali czerwień i poinstruuje pilota, żeby zmienił przypisanie wyjść — a złe przypisanie to udokumentowana przyczyna salta z 2026-08-15 | nie |
| 🔴 5b.2 | **Edycja wysokości punktu kasuje resztę trasy.** Potwierdzone na emulatorze: `2 pkt · 161 m` → `+` przy punkcie 1 → `1 pkt · 0 m`. Przyczyna: `EkranMisji.kt:522` `pointerInput(znak)` bez `rememberUpdatedState` | nie |
| 🔴 5b.3 | **Włączenie jednej warstwy ekranu cofa poprzednią.** Potwierdzone: `Belka.kt:627`, ten sam wzorzec. Dotknięcie samego przełącznika działa poprawnie — ten sam wiersz zachowuje się różnie zależnie od miejsca dotknięcia | nie |
| 🔴 5b.4 | **Uporządkować `rememberUpdatedState` we wszystkich sześciu miejscach gestu** (`Elementy.kt:712` i `:869`, `EkranMisji.kt:470` i `:522`, `Belka.kt:421` i `:627`). Cztery bliźniacze komponenty już to mają — brak reguły, nie pojedyncze przeoczenie | nie |
| 🔴 5b.5 | **Testy dla `Misja`, `Wspolrzedne`, `MagazynMisji`, `TransferMisji`** — 766 linii bez ani jednego testu. 5b.2 to trzy linijki w `MisjaTest`. `Wspolrzedne` sprawdziłem doraźnie niezależną implementacją UTM i wychodzi poprawnie, ale nic go nie broni przed następną zmianą | nie |
| 🟠 5b.6 | **Brak reguł na `BATT_LOW_MAH` i `BATT_CRT_MAH`** — przy martwym pomiarze napięcia (`CLAUDE.md` poz. 37) to jedyna realna ochrona pakietu, a checklista jej nie sprawdza. Sześć linii JSON-a | nie |
| 🟠 5b.7 | **`RTL_ALT`: checklista blokuje na nierozstrzygniętym sporze.** Reguła żąda `5000`, na płycie jest `1000`, `CLAUDE.md` poz. 41 notuje „decyzja niepodjęta". Druga fałszywa blokada obok 5b.1 — pilot, który raz zobaczy, że blokady kłamią, przestanie je czytać | nie |
| 🟠 5b.8 | **Detektor zagłuszania GNSS liczy tylko na ekranach LOT i DIAGNOSTYKA.** `Ostrzezenia.wykryjSpadekSatelitow` mutuje wspólny bufor z kompozycji; na MISJI nie wpada ani jedna próbka, a okno 10 s czyści historię przy powrocie. Przenieść do `SilnikStanu`, do obsługi `GPS_RAW_INT` | nie |
| 🟠 5b.9 | **APK 96 MB, 80 % to dwie kopie libVLC** (arm64 40,3 MB + armeabi-v7a 36,8 MB), dex 17 MB przy `isMinifyEnabled = false`. **ABI sprawdzone 2026-08-26 na podłączonym MK32: `arm64-v8a,armeabi-v7a,armeabi` — aparatura jest 64-bitowa, więc `armeabi-v7a` (36,8 MB) to czysty balast.** Zostaje wyrzucić ją z `release` i włączyć R8 → ok. 50 MB. Domyka warunek odbioru z 5a.9 | nie |
| 🟠 5b.10 | **Brak filtrowania nadawcy MAVLink** — `LaczeMavlink` nie sprawdza adresu źródłowego, `SilnikStanu` nie patrzy na `sysid`/`compid`. Dowolny HEARTBEAT w podsieci przestawia uzbrojenie, dom, ślad i zegar lotu. Istotne przed dołożeniem drugiego GCS (`dok/GCS_RPI5.md`) | nie |
| 🟡 5b.11 | **Panel warstw prześwituje** (krycie 92 % wg makiety) — pozycja maszyny czyta się przez kafle PODKŁAD MAPY. Opisy warstw ucięte w połowie słowa (`maxLines = 1` bez wielokropka) | nie |
| 🟡 5b.12 | **Dwie różne długości trasy obok siebie**: lista misji „161 m", pasek profilu „273 m", bez wyjaśnienia, która jest pozioma, a która z profilem terenu | nie |
| 🟡 5b.13 | **Progi napięcia zaszyte pod 6S LiPo** w `Ostrzezenia.kt` i w regułach. Przy pakiecie 8S (`CLAUDE.md` poz. 56) ostrzeżenie „NAPIĘCIE NA GRANICY ZR30" zapali się od pierwszej sekundy i nie zgaśnie. Wprowadzić profil pakietu **zanim** 8S trafi na maszynę | nie |
| 🟡 5b.14 | **Brak autozapisu planowanej misji** — `MagazynMisji` zapisuje tylko na jawne ZAPISZ; ubicie procesu kasuje trasę bez śladu | nie |
| 🟡 5b.15 | **`zbadajKarte` robi `listFiles()` po karcie TF w konstruktorze magazynu kafli**, czyli na wątku głównym. Ta sama lekcja odrobiona już przy libVLC. Zmierzyć na sprzęcie — pomiar z emulatora (klatki 700–1200 ms) nie przenosi się wprost | tak |
| 🟡 5b.16 | **`dok/SRODOWISKO_TESTOWE.md` mówi 1920 × 1200, a AVD melduje 1280 × 800 @ 320.** Uzgodnić, inaczej następny audyt znowu porówna dwie różne rzeczy | nie |

---

## 5c. Przyrządy zapasu — wdrożone 2026-08-26

Propozycja: [dok/PROPOZYCJA_LOT.md](dok/PROPOZYCJA_LOT.md). Weszły **etapy 1–3**, czyli te,
które nie przesądzają wyboru układu (wariant 1 vs 2). Zrzut z prawdziwej aparatury:
`dok/zrzuty/zapas_mk32.png`.

| # | Zadanie | Sprzęt |
|---|---|---|
| 🔴 5c.1 | **Sprawdzić, czy maszyna honoruje `SET_MESSAGE_INTERVAL`.** Strona nadawcza jest już potwierdzona: symulator loguje `COMMAND_LONG 511 -> ACK 0`, czyli aplikacja wysyła komendę poprawnie zaramkowaną i dostaje na nią potwierdzenie. **Nieznane zostaje jedno — czy ArduPilot za portem z `SERIAL6_OPTIONS = 4096` („Ignore Streamrate", poz. 35) na nią zareaguje.** Jeśli nie, pas zapasu powie „BRAK DANYCH" i trzeba podnieść `SR1_RAW_CTRL` na FC. Sprawdzenie: podłączyć maszynę i zobaczyć, czy pas pokazuje liczby. **To warunek, żeby etap 1 miał sens w powietrzu.** | tak |
| 🟠 5c.2 | **Odsłuchać alarmy na MK32** — czy `ToneGenerator` gra przy jednocześnie działającym SIYI FPV. Logika przetestowana, dźwięk niesłyszany | tak |
| 🟠 5c.3 | **Skalibrować `BATT_CAPACITY` i `BATT_AMP_PERVLT` wattomierzem.** Do tego czasu blok ENERGIA świadomie **odmawia** pokazania procentów, JOKER i BINGO — pokazuje same mAh i ampery. Bez kalibracji te dwie liczby są z sufitu (poz. 9 i 40) | tak |
| 🟠 5c.4 | **Potwierdzić progi zapasu ciągu** (100 / 60 / 40 µs). Wyprowadzone z **czterech** punktów pomiarowych z lotów 3 i 4 — to za mało na statystykę. Zebrać z kolejnych lotów | tak |
| 🟡 5c.5 | **`MOT_SPIN_MAX` dopisane do pobieranych parametrów**, ale gdy nie wróci, sufit liczy się z domyślnych 0,95. Sprawdzić, czy dochodzi | tak |
| ✅ 5c.6 | ~~Reszta propozycji czeka na decyzję~~ → **2026-08-28 wybrany wariant 1**; etapy 4 i 5 wdrożone (§5d), etap 7 (HUD) odpada wraz z wariantem 2 | nie |

---

## 5d. Etapy 4 i 5 — wdrożone 2026-08-28

Wariant 1: dokładamy do makiety M3. Szczegóły: [dok/PROPOZYCJA_LOT.md](dok/PROPOZYCJA_LOT.md) §9b.
Weszły: pasek czujników z masek `SYS_STATUS`, geofence (`FENCE_STATUS` + zapas do granicy),
wiatr z przechyłu, wibracje, cel automatu (`NAV_CONTROLLER_OUTPUT`). 152 testy przechodzą.

| # | Zadanie | Sprzęt |
|---|---|---|
| 🔴 5d.1 | **Skalibrować `Wiatr.WSPOLCZYNNIK`** — jest **dobrany, nie zmierzony**. Jeden zawis w znanym wietrze, odczyt kąta z kokpitu, porównanie z wiatromierzem. Do tego czasu strzałka kierunku jest wiarygodna, a metry na sekundę mówią rząd wielkości | tak |
| 🟠 5d.2 | **Zobaczyć etapy 4 i 5 na prawdziwym MK32.** Sprawdzone tylko na emulatorze — aparatura była zajęta przez sesję pracującą nad wideo. Szczególnie belka: pasek czujników rozwija się przy usterce i wypycha czas lotu, a na 640 dp może być ciaśniej niż na 950 | tak |
| ✅ 5d.3 | ~~Sprawdzić blok CEL po zwężeniu bloków~~ → **potwierdzone 2026-08-28**: „CEL 134 m · 016° · Δh +1 · tor −2 m · płot 110 m", mieści się | nie |
| 🟠 5d.4 | **Czy `FENCE_STATUS` i `NAV_CONTROLLER_OUTPUT` dochodzą z maszyny** — ten sam warunek co 5c.1: zamawiamy je przez `SET_MESSAGE_INTERVAL`, a port ma `SERIAL6_OPTIONS = 4096` | tak |
| ✅ 5d.5 | ~~Etap 6 — palety DZIEŃ / NOC / NVG~~ → **wdrożone 2026-08-28**, pięć palet w `ui/Motyw.kt`, klawisz MOTYW cykluje. **Wariant 1 zamknięty** | nie |
| ✅ 5d.7 | ~~MGRS w rzędzie liczb za mały~~ → **naprawione 2026-08-28**: dotknięcie bloku pozycji otwiera `OknoPozycji` z WGS84, DMS i MGRS po 24 sp; w rzędzie MGRS dostał osobną linię i 11 sp | nie |
| ✅ 5d.11 | ~~Wiatr na taśmie kursu, czyli w drugim miejscu~~ → **2026-08-28 wkomponowany w okrąg położenia**: strzałka na pierścieniu względem dziobu, prędkość przy ikonie nad kołem | nie |
| ✅ 5d.12 | ~~Podpisy tekstowe w pasie i rzędzie liczb~~ → **zastąpione piktogramami 2026-08-28**; osiem nowych ikon w `ui/Ikony.kt`. Zostają `JOKER`, `BINGO` i diagnozy | nie |
| ✅ 5d.14 | ~~Sztuczny horyzont gubi sie na jasnym tle~~ → **naprawione 2026-08-28 konturem, nie wypelnieniem**. Pierwsza proba wypelniala tarcze i zostala odrzucona: przyrzad zaslanial kadr. Teraz podwojna kreska, 152 dp, luki z przerwami, skrzydelka zamiast krzyza | nie |
| ✅ 5d.15 | ~~Kurs i dom w osobnych miejscach~~ → **przeniesione na okrag polozenia**; tasma kursu domyslnie zdjeta | nie |
| ✅ 5d.16 | ~~Podpisy na gornej belce~~ → **ikony**; lacze jako slupki zasiegu wypelniane kolorem: zielony sprawne, pomaranczowy spadek, czerwony przekreslony brak | nie |
| ✅ 5d.18 | ~~JOKER i BINGO zajmuja dwa pola w pasie~~ → **przeniesione na belke 2026-08-28 jako jedno pole z najblizszym progiem**; nazwy zastapione piktogramami (dom z zegarem, pusta bateria z wykrzyknikiem) | nie |
| ✅ 5d.19 | ~~Znacznik wladzy na belce~~ → **zdjety domyslnie**, przeniesiony do warstw. Wraca na wagę przy dwoch stacjach naziemnych (dok/WLADZA.md) | nie |
| ✅ 5d.20 | ~~Nie da się odróżnić symulatora od żywej maszyny na zrzucie~~ → **`LaczeMavlink` loguje adres przy starcie** (2026-08-28). Emulator na laptopie podpiętym do sieci pokładowej łączy się z prawdziwym dronem pod domyślnym adresem — opis w dok/SRODOWISKO_TESTOWE.md | nie |
| ✅ 5d.21 | ~~Brak ekranu uruchamiania~~ → **logo AEROTHINK od chwili otwarcia okna** (2026-08-28): tło okna + `ui/EkranStartowy.kt`, zasłona schodzi po gotowości toru obrazu, twardy sufit 4,5 s | nie |
| ✅ 5d.24 | ~~Ciężko trafić w blok pozycji, żeby otworzyć okno współrzędnych~~ → **naprawione 2026-08-28**. Przyczyna: `padding` stał **przed** `pointerInput`, a modyfikatory działają od zewnątrz — margines był więc odejmowany od obszaru dotyku i cel wychodził **mniejszy niż sam napis**. Teraz gest jest pierwszy, blok wypełnia całą wysokość rzędu (64 dp) i ma 10 dp zapasu po bokach; doszła też ramka i podświetlenie pod palcem | nie |
| ✅ 5d.23 | ~~Pasek postępu i wersja ledwo widoczne~~ → **wzmocnione 2026-08-28**: pasek 4 dp, wersja 15 sp w kolorze akcentu, pod paskiem **nazwa etapu** ładowania | nie |
| 🟡 5d.22 | **Logo ma wariant na ciemne tło** — czarne elementy oryginału zamienione na jasne, bo ginęły na tle kokpitu. Do potwierdzenia, czy taka wersja znaku jest akceptowalna dla producenta; alternatywa to białe tło ekranu startowego | nie |
| 🟡 5d.17 | **Sprawdzic progi ikony lacza na sprzecie** — dobrane pod zmierzone 48 Hz na MK32, ale to jeden pomiar | tak |
| 🟡 5d.13 | **Przejrzeć resztę interfejsu pod kątem piktogramów** — MISJA, KAMERA, PRZED LOTEM i DIAGNOSTYKA nadal mają podpisy słowne. Przy tłumaczeniu to one urosną najbardziej | nie |
| 🟠 5d.8 | **Sprawdzić palety w polu** — DZIEŃ w pełnym słońcu i NVG w goglach. Na emulatorze widać tylko, że są spójne; czy naprawdę są czytelne, rozstrzyga wyłącznie teren | tak |
| 🟡 5d.9 | **W palecie NOC „STERUJESZ TY" i słupek baterii zostają jaskrawozielone** — wygląda na kolor podany z palca zamiast z tokenu `Barwy.Dobrze`. Znaleźć i przełożyć na token | nie |
| 🟡 5d.10 | **`Dialog` nie dziedziczy `LocalDensity`** — naprawione w `OknoPozycji`, ale dotyczy każdego przyszłego dialogu. Rozważyć własny `OknoKokpitu`, który robi to raz | nie |
| 🟡 5d.6 | **Zapas geofence liczy się od punktu domu**, więc gdy dom jest zgadnięty a nie wzięty z `HOME_POSITION`, liczba jest oznaczona znakiem zapytania. Sprawdzić, czy maszyna faktycznie przysyła `HOME_POSITION` | tak |

---

## 5e. Pierwsze uruchomienie przy żywym dronie — co zostało otwarte

**Zbudowane i sprawdzone 2026-08-26…28:** sklejanie strumienia MAVLink, CRC dla wersji 1,
wiązanie gniazd z siecią (pokładową dla drona, Wi-Fi dla map), własny tor RTSP zamiast
libVLC, zapis parametrów z checklisty, MOUNT_LOCK, poprawione mapowanie silników w regułach.
Przebieg i pomiary: [dok/SESJA_20260828.md](dok/SESJA_20260828.md).

| # | Zadanie | Sprzęt | Skąd |
|---|---|---|---|
| 🟠 5e.1 | **Równość obrazu — rysownik przebudowany, zostaje ocena okiem.** Klatki idą teraz na równej siatce dosuniętej do odświeżeń ekranu (`releaseOutputBuffer` ze znacznikiem czasu), z buforem przed dekoderem. Zmierzone `dumpsys SurfaceFlinger --latency`: klatki zmarnowane (odstęp 1 odświeżenia) **12 → 0–2**, serie `1 1` **zniknęły**, odstępy równe 2 odświeżenia **60 → 101–112**. Zostają pojedyncze zastoje 150–200 ms przy klatkach kluczowych. ⚠ Rozrzut próbek tej samej wersji sięga 3,2–4,8 %, więc dalsze strojenie tą miarą nie ma sensu | tak | [dok/SESJA_20260828.md](dok/SESJA_20260828.md) §7a |
| 🟠 5e.2 | **Sprawdzić przed lotem, który fizyczny przełącznik to CH8** (`RC8_OPTION=163`, MOUNT_LOCK). Trzecia funkcja przeniesiona na inny kanał w tym projekcie | tak | `..\CLAUDE.md` poz. 58 |
| 🟡 5e.3 | **Tryb FPV głowicy jest nieosiągalny**, dopóki `MNT1_TYPE=8` — ArduPilot ma tylko blokadę kursu i nadpisuje tryb co kilka sekund. `MNT1_TYPE=0` odrzucone (zabiera pokrętła i misje). Do rozważenia: przełączanie `MNT1_TYPE` z kokpitu na czas zdjęć | tak | tamże |
| ✅ 5e.4 | **Tor SIYI na TCP 37256 — zrobiony i domyślny.** Pakiet podtrzymania rozłożony: dwa CRC-32 (`0x04C11DB7`, MSB-first, rejestr 0, bez negacji) po bajtach 0..11 i 0..15. Kod: `video/SumaSiyi.kt`, `video/OdtwarzaczSiyi.kt`. Powrót: `-e wideo rtsp` | tak | [dok/SESJA_20260828.md](dok/SESJA_20260828.md) §7 |
| ✅ 5e.8 | **Samoczynne zejście na RTSP** — `video/TorZZapasem.kt`: gdy tor natywny przez 12 s nie da klatki, kokpit sam przechodzi na RTSP, jednym rysownikiem (widok się nie zmienia) i melduje to pilotowi. Dzięki temu tor SIYI mógł zostać **domyślny** | tak | tamże §7b |
| ✅ 5e.10 | **Panel STRUMIEŃ przebudowany** — przełącznik drogi obrazu SIYI/RTSP i restart kamery lub głowicy (`CMD 0x80`, dwustopniowe potwierdzenie, restart kamery nie rusza głowicą). Usunięte martwe klawisze kodeka i trzy akapity nieaktualnych objaśnień | tak | tamże §7c |
| ✅ 5e.11 | **Powitanie włączające strumień natywny** — po cyklu zasilania kamera milczy, dopóki nie dostanie pięciu ramek, które wysyła fabryczna aplikacja. Odtworzone w `video/SumaSiyi.kt`; tor SIYI działa teraz bez uruchamiania czegokolwiek producenta | tak | tamże §7b |
| ✅ 5e.12 | **Przełączanie toru zamrażało obraz** — odstawiany tor gasił dekoder należący już do nowego. Naprawione (zatrzymanie tylko we własnym pokoleniu, zamykanie gniazda, przełączanie poza wątkiem ekranu, wyciszenie serii naciśnięć) | tak | tamże §7b1 |
| ⚠ 5e.9 | **Przed lotem zamknąć SIYI FPV.** Kamera obsługuje na 37256 jednego klienta, a fabryczna aplikacja trzyma go **także w tle** — objawem jest przyjęte połączenie i cisza. Rozważyć, czy kokpit ma to wykrywać i mówić wprost | tak | tamże §7b |
| ✅ 5e.7 | **Sesja RTSP wygasała co 60 s** — stąd mrugające `BRAK OBRAZU Z KAMERY` co 71 s. Zapasowy tor RTSP dostał `OPTIONS` z nagłówkiem `Session` co 20 s | tak | tamże |
| 🟡 5e.5 | **Kamera ignoruje żądany bitrate strumienia głównego** (odpowiada „sukces", zostaje 1570 kb/s). Sprawdzić na nowszym firmware kamery albo pogodzić się z tym | tak | tamże §6 |
| 🟡 5e.6 | **`RTL_ALT = 1000` (10 m)** — jedyna prawdziwa niezgodność wykryta przez checklistę. Decyzja 10 czy 50 m nadal niepodjęta | nie | `..\CLAUDE.md` poz. 41 |

---

## 6. Podgląd na telefonie

**Zbudowane 2026-08-23:** układ pionowy, instalacja z pulpitu, tryb kinowy, blokada
wygaszania ekranu, ikony. Opis i wyniki pomiarów: [dok/TELEFON.md](dok/TELEFON.md).

| # | Zadanie | Sprzęt | Skąd |
|---|---|---|---|
| 🟠 6.1 | **TLS na stacji — odblokowuje trzy rzeczy naraz.** Bez HTTPS nie ma podpowiedzi „Zainstaluj aplikację" na Androidzie, nie wstaje service worker i nie działa blokada wygaszania ekranu. Wymaga certyfikatu na porcie 8095 **i** TLS na MediaMTX 8889 — strona po `https://` nie zawoła `http://…:8889`. `auto.crt`/`auto.key` już leżą w `serwer/`, nieużywane | nie | [dok/TELEFON.md](dok/TELEFON.md) §4 |
| 🟠 6.2 | **Sprawdzić na prawdziwym telefonie:** instalacja z pulpitu, ikona maskowalna, rejestracja service workera, tryb pełnego ekranu z obrotem. Wbudowana przeglądarka odmówiła obu ostatnich, więc kod jest napisany, ale nieprzejechany | telefon | tamże §7 |
| 🟡 6.3 | Cele dotykowe 64 px w pionie — sprawdzić **w rękawicach**, tak samo jak poz. 5.5 | telefon | [dok/UI.md](dok/UI.md) §2 |
| 🟡 6.4 | Ekran wejścia (`Wejscie.jsx`) i panele OGLĄDA/ADMIN/STACJA nie mają jeszcze układu pod wąski ekran — na telefonie działają, ale są ciasne. **Zostały też na starym systemie 2.0**, podczas gdy nakładka przeszła na wariant D | nie | [dok/TELEFON.md](dok/TELEFON.md) §3 |
| 🟠 6.5 | **Uzgodnić wygląd z makietą `DRON 15 Telefon.dc.html`.** Pliku nie ma na tej maszynie ani na Dysku Google — implementacja wariantu D stoi na `UI.md` §9 i na zrzutach kokpitu. Do sprawdzenia różnic, gdy plik będzie dostępny | nie | tamże §7 poz. 8 |
| ✅ 6.7 | **Kod autoryzacji prowadzi telefon do stacji** — kod połączeniowy `D15-…` niesie kod i adres, żeton przypisany do adresu, lista znanych stacji, CORS na `/api/*`. Sprawdzone na dwóch serwerach naraz | nie | [dok/TELEFON.md](dok/TELEFON.md) §2a |
| 🟠 6.8 | **Kod połączeniowy podaje adres, ale nie zestawia tunelu.** Gdy stacja jest osiągalna tylko przez WireGuard, telefon musi mieć tunel podniesiony sam. Do rozważenia razem z poz. 3.3 (serwer koordynujący) | nie | tamże §2a |
| 🟡 6.6 | `Motyw.css` i `ui/Motyw.kt` trzymają te same liczby w dwóch miejscach. Rozjadą się przy pierwszej zmianie, której nikt nie przeniesie — rozważyć generowanie jednego z drugiego | nie | tamże §3 |
