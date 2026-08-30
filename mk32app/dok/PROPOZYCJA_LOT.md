# Propozycja — ekran LOT jako przyrząd pilota

**Data:** 2026-08-26, uzupełnione 2026-08-28 · **Stan:** **wariant 1 zamknięty — etapy 1–6
wdrożone**; etap 7 (HUD) odpadł wraz z wariantem 2
**Podstawa:** kod `ui/Kokpit.kt` i `domain/`, dialekt MAVLink ArduPilota 4.6.3,
`CLAUDE.md` (stan maszyny), [AUDYT_M3.md](AUDYT_M3.md)

Pytanie brzmiało: co zrobić, żeby LOT był funkcjonalny dla pilota, miał użyteczne funkcje,
nowoczesny militarny wygląd i pozwalał pilotować w każdych warunkach — oraz co przychodzi
z maszyny, czego nie pokazujemy.

Zacząłem od tego ostatniego, bo to jedyna część, którą da się rozstrzygnąć pomiarem,
a nie gustem. Wyszło z niej więcej, niż się spodziewałem.

---

## 1. Trzy fakty, od których wszystko dalej wynika

**Fakt 1 — jedyny wskaźnik paliwa na ekranie LOT jest podłączony do zepsutego czujnika.**
Cała informacja o zasilaniu na tym ekranie to słupek baterii i „24,1 V" na belce
([Belka.kt:146](../app/cockpit/src/main/java/pl/dron15/cockpit/ui/Belka.kt:146)),
oba liczone z `stan.napiecieV`. `CLAUDE.md` poz. 37: to wejście czyta **stabilizowaną szynę,
nie pakiet** — `BAT.Volt` stoi na 24,907…25,020 V (σ 0,0073 V) przy prądzie skaczącym 47→61 A.
Słupek będzie zielony i pełny przez cały lot, aż do wyczerpania pakietu.

**Fakt 2 — działający licznik paliwa jest dekodowany i wyrzucany.**
`zuzycieMah` i `pradA` są w `StanMaszyny`, są wypełniane z `BATTERY_STATUS` i `SYS_STATUS` —
i **nie mają ani jednego odwołania w całym katalogu `ui/`**. W locie 2 z 2026-08-16
zużycie wyniosło 4538 mAh w 4 minuty 52 sekundy. Ta liczba istnieje w aplikacji i nikt jej
nie widzi.

**Fakt 3 — maszyna spadła z 58 m, a pilot nie dostał żadnego sygnału.**
`CLAUDE.md` poz. 45: mikser nasycił się, wyjście `C1` siedziało na 1950 µs przez 89 % próbek,
gaz zbiorczy poszedł w dół mimo żądania 175 %. Wykryto to **z logu, tygodnie później**.
Wielkość, która to zapowiadała — rozrzut `RCOU` rosnący 95 → 168 → 173 → 309 µs — jest
w każdej sekundzie telemetrii, w komunikacie, którego nie dekodujemy.

Wniosek, który organizuje resztę tego dokumentu: **ten kokpit pokazuje dziś to, co pokazuje
każdy kokpit. Nie pokazuje nic z tego, co wie o tej konkretnej maszynie.**
Narzędzia analityczne (`fc_balans.py`, analizy lotów) potrafią policzyć jej słabe punkty
po locie. Propozycja sprowadza się do przeniesienia tego **przed** lot i **w** lot.

---

## 2. Co LOT pokazuje dziś

| Miejsce | Treść |
|---|---|
| belka górna | tryb, uzbrojenie, słupek baterii + napięcie, satelity, `ŁĄCZE xx Hz`, czas lotu, władza |
| kadr | obraz z ZR30 albo mapa |
| taśma kursu | kurs, znacznik domu, 400 × 20 dp |
| okrąg położenia | przechylenie i pochylenie, 132 dp |
| kolumna kamery | skala pochylenia głowicy, migawka, zoom |
| miniatura mapy | 190 × 126 dp |
| rząd liczb | wysokość, do domu, prędkość, wznoszenie, pozycja (dziesiętne + MGRS + GNSS) |
| komendy | RTL, LĄDUJ, PRZERWIJ |
| baner | jedno najważniejsze ostrzeżenie |

To jest komplet. Żadnej z wielkości wymienionych w §3 i §4 **nie ma tu w jakiejkolwiek postaci**.

---

## 3. Dane, które już mamy w pamięci i nie pokazujemy

Zero kosztu po stronie łącza — wystarczy je narysować.

| Wielkość | Skąd | Dlaczego na LOT |
|---|---|---|
| **`zuzycieMah`** | `BATTERY_STATUS` | jedyny działający wskaźnik paliwa na tej maszynie |
| **`pradA`** | `SYS_STATUS` | bezpośrednia miara zapotrzebowania na ciąg; w locie 3 „pełny gaz" ciągnął tyle samo co zawis (50–60 A) i to był dowód nasycenia miksera |
| **`namiarNaDomSt`** | liczone | jest na taśmie jako znacznik i na ekranie KAMERA jako liczba; na LOT liczby brak |
| **`rssiRc`** | `RC_CHANNELS` | dziś 255 („S.Bus nie przenosi", poz. 16), ale po przejściu na `RSSI_TYPE=2` będzie realny |
| **`punktMisji`** | `MISSION_CURRENT` | w trybie AUTO pilot nie widzi, do którego punktu maszyna leci |
| **`wariancjaKursu`** | `EKF_STATUS_REPORT` | na maszynie bez kompasu to jest wskaźnik „czy RTL ma prawo zadziałać" |
| **`glowicaPitch` / `glowicaYaw`** | `GIMBAL_DEVICE_ATTITUDE_STATUS` | dokąd patrzy kamera względem kadłuba — przy zoomie 30× bez tego pilot się gubi |
| **`gazProc`** | `VFR_HUD` | **gaz zawisu to główny wskaźnik poz. 55** — rósł 0,605 → 0,638 → 0,672 → 0,703 przez cztery loty. Dekodowany, nieużywany |

Do tego **trzy pola, które czytamy z ramki i porzucamy w tej samej linii**
([SilnikStanu.kt:106](../app/cockpit/src/main/java/pl/dron15/cockpit/domain/SilnikStanu.kt:106)):

| Pole | Co się z nim dzieje | Do czego jest potrzebne |
|---|---|---|
| `vx`, `vy` z `GLOBAL_POSITION_INT` | zwijane do skalara `sqrt(vx²+vy²)`, **kierunek ginie** | wektor prędkości B1 — najważniejszy symbol HUD-u; sam kierunek toru wobec kursu |
| `alt` (MSL) z tej samej ramki | `o.i32()` bez przypisania | wysokość bezwzględna, bez której nie policzę AGL z kafli terenu (B4) |
| maski zdrowia z `SYS_STATUS` | `o.pomin(12)` | stan każdego czujnika z osobna (C1) |

---

## 4. Dane, które maszyna nadaje, a my ich nie pytamy

To wszystko jest w standardowych strumieniach ArduPilota. Trzeba dopisać dekodowanie
i podnieść odpowiednie `SRn_*` — **nie** zmieniać niczego w konfiguracji maszyny.

### 4.1 `SERVO_OUTPUT_RAW` (36) — najważniejsza pozycja na tej liście

Cztery wyjścia silników w mikrosekundach, czyli `RCOU` na żywo. Z tego liczy się:

| Przyrząd | Wzór | Co znaczy na tej maszynie |
|---|---|---|
| **ZAPAS CIĄGU** | `1000 + 1000·MOT_SPIN_MAX − max(M1..M4)` | lot 3: **66 µs** tuż przed spadkiem, **0 µs** w spadku. Lot 4: 52 µs. **Poniżej ~40 µs maszyna nie ma czym się bronić** |
| **ROZRZUT** | `max − min` | poz. 45: *„nie latać, dopóki rozrzut w zawisie nie zejdzie poniżej ~60 µs"*. Zmierzone: 95 / 168 / 173 / 309 µs |
| **tył − przód** | `(S1+S3) − (S2+S4)` | środek ciężkości. Dodatni w **każdym** oknie każdego lotu, +37…+111 µs |
| **prawo − lewo** | `(S1+S2) − (S3+S4)` | boczne wyważenie albo wiatr |
| **CW − CCW** | `(S4+S1) − (S2+S3)` | osadzenie silników, moment wokół pionu |

Przypisanie wg mapowania potwierdzonego lotem 2 (`CLAUDE.md`): `SERVO1` = Motor4 tył prawy,
`SERVO2` = Motor1 przód prawy, `SERVO3` = Motor2 tył lewy, `SERVO4` = Motor3 przód lewy.
Kierunki Quad X: CCW = przód prawy + tył lewy, CW = przód lewy + tył prawy.

To jest **dokładnie to, co liczy `tools\fc_balans.py` z pliku `.bin`** — tylko na żywo.
Strumień: `SRn_RAW_CTRL` (dziś `1`), wystarczy 5 Hz. Koszt pasma: ~4 % łącza 115 200.

**To jedna zmiana, która zamienia incydent z poz. 45 z niespodzianki w ostrzeżenie
z kilkudziesięciu sekund wyprzedzeniem.**

### 4.2 `VIBRATION` (241)

Wibracje w trzech osiach plus liczniki przycięcia akcelerometrów. `CLAUDE.md` śledzi
tę wielkość od pierwszych lotów: 1,4–2,5 m/s² normalnie, **17,2 m/s²** przy oscylacji
hamowania w locie 5. Próg ostrzegawczy ArduPilota to 30, przycięcie ≠ 0 to poważny alarm.
Strumień: `SRn_EXTRA3`.

### 4.3 `NAV_CONTROLLER_OUTPUT` (62)

`nav_bearing`, `target_bearing`, `wp_dist`, `alt_error`, `xtrack_error`.
W RTL i AUTO to jedyne źródło informacji **dokąd autopilot zmierza i o ile chybia**.
Poz. 51: kąt pochylenia przestrzeliwuje zadany o 30–50 % przy hamowaniu — `alt_error`
i `xtrack_error` pokazują to w locie, nie w logu. Strumień: `SRn_EXT_STAT`.

### 4.4 `SYS_STATUS` — pola, które już mamy w ręku i pomijamy

Dekodujemy tę wiadomość i **przeskakujemy pierwsze 12 bajtów** po napięcie
([SilnikStanu.kt:63](../app/cockpit/src/main/java/pl/dron15/cockpit/domain/SilnikStanu.kt:63)).
W tych 12 bajtach siedzą trzy maski: czujniki obecne, włączone i **zdrowe**.
Stamtąd wprost: GPS, baro, żyroskop, akcelerometr, odbiornik RC, bateria, silniki —
każdy z osobna, zdrowy albo nie. Zero nowego pasma, zero nowej wiadomości.
Dziś zamiast tego mamy `PreArm:` przechwycone z `STATUSTEXT` — czyli tekst zamiast stanu.

### 4.5 `HOME_POSITION` (242)

Dziś **zgadujemy dom sami**, w chwili przejścia na uzbrojone
([SilnikStanu.kt:44](../app/cockpit/src/main/java/pl/dron15/cockpit/domain/SilnikStanu.kt:44)).
Jeśli FC ma inny punkt domu — a ma, gdy ustawi go GCS albo gdy uzbrojenie nastąpiło przed
ustaleniem pozycji — nasza strzałka „DO DOMU" i dystans wskazują **nie tam, gdzie poleci RTL**.
Na maszynie, w której RTL jest podstawową procedurą ratunkową, to jest rozbieżność
do usunięcia, nie do tolerowania.

### 4.6 `FENCE_STATUS` (162)

Geofence jest na CH7, `FENCE_ALT_MAX = 120`. Dziś kokpit rysuje okrąg geofence na mapie
misji z parametru, ale **nie wie, czy nastąpiło naruszenie** ani którego ogrodzenia.

### 4.7 `BATTERY_STATUS` — pola pomijane

Bierzemy z niej `zuzycieMah`. Niesie też `battery_remaining` (%) i `time_remaining` —
liczone przez FC. Przy skalibrowanym `BATT_CAPACITY` to gotowy wskaźnik paliwa.
⚠ Dziś `BATT_CAPACITY = 3300` przy zużyciu 4538 mAh w jednym locie (poz. 40), więc **procent
będzie kłamał, dopóki pojemność i `BATT_AMP_PERVLT` nie zostaną skalibrowane wattomierzem**.
Do czasu kalibracji pokazywać **surowe mAh i A**, nie procent — liczba, której nie da się
zweryfikować, jest gorsza od liczby, której nie ma.

### 4.8 `WIND` (168) — z zastrzeżeniem

ArduPilot ma tę wiadomość, ale na wielowirnikowcu estymata wiatru wymaga ustawionych
`EK3_DRAG_BCOEF_X/Y` i `EK3_DRAG_MCOEF`. Na tej maszynie nie są ustawione, więc
**wiadomość przyjdzie i będzie bezwartościowa**.

**Zamiast tego proponuję wiatr liczony u nas, z tego, co już mamy:** w Loiter, gdy maszyna
trzyma pozycję, **średni kąt przechylenia jest miarą wiatru**. Poz. 45 robi dokładnie to
rozumowanie: *„a dodatnie staje się dopiero na 53–58 m, gdzie maszyna wisiała przechylona
−2,9° — to wiatr, nie geometria"*. Kierunek z azymutu wypadkowej przechylenia i pochylenia
obróconego o kurs, siła z `tan(kąt)` przeskalowanego. Zero nowych wiadomości, zero zmian w FC,
a wielkość, która **wprost zjada zapas ciągu z 4.1**.

---

## 5. Dane, po które trzeba by pójść do FC

Nie proponuję ich teraz — notuję, żeby nie zginęły.

| Co | Czego wymaga | Co dałoby |
|---|---|---|
| `ESC_TELEMETRY_1_TO_4` | `SERVO_DSHOT_ESC` i `SERVO_BLH_MASK` (dziś oba `0`) | obroty, **temperatura i prąd każdego ESC z osobna**; obroty domykają też poz. 43 (filtr harmoniczny sterowany RPM zamiast gazem) |
| `RANGEFINDER` | dalmierz, którego maszyna nie ma | wysokość nad gruntem przy lądowaniu |
| `RSSI` realne | `RSSI_TYPE = 2` + wolny kanał (poz. 16) | siła sygnału RC zamiast stałego 255 |

---

## 6. Propozycja przyrządów

Pogrupowane wg tego, co dają, nie wg tego, gdzie leżą.

### A. Zapas — czego nie ma, a jest najpotrzebniejsze

| # | Przyrząd | Źródło | Uzasadnienie |
|---|---|---|---|
| **A1** | **ZAPAS CIĄGU** — słupek poziomy, µs do sufitu, z progami 100 / 60 / 40 µs | `SERVO_OUTPUT_RAW` | poz. 45 i 55; jedyny przyrząd, który przewidziałby spadek z 58 m |
| **A2** | **ROZRZUT SILNIKÓW** — liczba + trzy składowe (tył−przód, prawo−lewo, CW−CCW) | j.w. | poz. 45 stawia próg 60 µs jako warunek dopuszczenia do lotu — dziś sprawdzalny tylko po locie |
| **A3** | **ENERGIA** — mAh zużyte, A bieżące, A średnie, minuty do JOKER i BINGO | `BATTERY_STATUS`, `SYS_STATUS` | jedyny działający wskaźnik paliwa (fakt 1 i 2) |
| **A4** | **WIATR** — strzałka i m/s z uśrednionego przechyłu w zawisie | liczone u nas | wiatr zjada zapas z A1; poz. 45 przypisuje mu 34 z 97 µs nadwyżki |

**JOKER i BINGO** — nazewnictwo lotnicze wojskowe i akurat tutaj wyjątkowo na miejscu:
- **JOKER** — moment, w którym trzeba ruszyć do domu, żeby wrócić z rezerwą.
  `czas_powrotu = dystans / prędkość_powrotu + wysokość / prędkość_opadania + margines`
- **BINGO** — moment, po którym powrót przestaje być możliwy; komenda RTL zapala się sama.

Obie liczby maszyna ma z czego policzyć **już dziś** (dystans, wysokość, `WPNAV_SPEED`,
`LAND_SPEED_HIGH`, zużycie mAh). Poz. 45 kończy się zdaniem *„Pilot nie miał żadnego
ostrzeżenia"* — to jest odpowiedź na tamto zdanie.

### B. Sterowanie i orientacja

| # | Przyrząd | Uzasadnienie |
|---|---|---|
| **B1** | **Wektor prędkości** („ptaszek") na kadrze — dokąd maszyna faktycznie leci, z `vx/vy/vz` | najbardziej użyteczny symbol każdego HUD-u; przy 30× zoomie i wietrze bocznym różnica między kursem a torem bywa kilkunastostopniowa |
| **B2** | **Drabinka pochylenia** przez cały kadr zamiast okręgu 132 dp — z symbolem maszyny i liniami co 5° | okrąg czyta się dobrze przy ±10°; poz. 51 notuje dojście do **+33,9°** |
| **B3** | **Taśma wysokości** przy prawej krawędzi ze wskaźnikiem wznoszenia | dziś wysokość jest liczbą w rzędzie na dole — najdalej od środka uwagi |
| **B4** | **AGL — wysokość nad terenem** z kafli Terrarium, które aplikacja już ma | `RTL_ALT` bywał 10 m (poz. 41); nad falistym terenem wysokość względem startu nie mówi nic o prześwicie. ⚠ wymaga rozstrzygnięcia układu odniesienia (elipsoida vs geoida, w Polsce ok. 30 m różnicy) |
| **B5** | **Cel automatu** — dokąd leci RTL/AUTO, ile do punktu, błąd toru | `NAV_CONTROLLER_OUTPUT`; dziś w AUTO pilot nie widzi nawet numeru punktu |

### C. Zdrowie maszyny

| # | Przyrząd | Uzasadnienie |
|---|---|---|
| **C1** | **Pasek czujników** — GPS, baro, IMU, RC, bateria, silniki; każdy zielony/bursztyn/czerwony | z masek `SYS_STATUS`, które już odbieramy; zastępuje zgadywanie z tekstu `PreArm:` |
| **C2** | **Wibracje** — trzy słupki + licznik przycięcia | `VIBRATION`; 17,2 m/s² w locie 5 to wielkość, którą warto zobaczyć od razu |
| **C3** | **Wiek każdej danej** — dziś rząd liczb ma wspólny „wiek", ale GNSS, EKF i bateria psują się osobno | zasada 6 z `UI.md`, doprowadzona do końca |
| **C4** | **Ogrodzenie** — odległość do granicy poziomej i do pułapu | `FENCE_STATUS` + `FENCE_RADIUS`/`FENCE_ALT_MAX` |

### D. Utrata łącza — stan, którego dziś nie ma

Dziś przy utracie telemetrii liczby po prostu zastygają, a baner mówi „UTRATA TELEMETRII".
Proponuję **tryb zliczania drogi**: ostatnia znana pozycja zostaje na mapie ze znacznikiem
i zegarem „od 14 s", ślad przechodzi w linię przerywaną z ekstrapolacją ostatniego wektora
prędkości, a wszystkie liczby, które przestały być prawdą, **gasną do konturu** zamiast
kłamać ostatnią wartością. To jest różnica między „nie wiem" a „wiem, że nie wiem" —
i na maszynie, na której RTL zależy od GNSS, decyduje o tym, czy pilot sięgnie po AltHold.

### E. Dźwięk — nie dodatek, tylko warunek

Audyt UI z 2026-08-19 zgłosił brak sygnalizacji dźwiękowej jako pozycję F10. **W kodzie
nadal nie ma ani `ToneGenerator`, ani `SoundPool`, ani `MediaPlayer`** — jedyne sprzężenie
zwrotne to wibracja przy naciśnięciu klawisza.

Pilot patrzy na maszynę, nie na ekran. Baner, którego nikt nie widzi, nie istnieje.
Proponuję cztery dźwięki, rozróżnialne bez patrzenia:

| Zdarzenie | Sygnał |
|---|---|
| zapas ciągu poniżej progu | narastające pikanie, tempo rośnie z zanikiem zapasu |
| JOKER / BINGO | dwa różne gongi, jednorazowe |
| utrata telemetrii lub kursu GNSS | ciągły niski ton |
| komenda przyjęta / odrzucona | krótki potwierdzający / opadający |

Ground unit MK32 ma głośnik i regulację głośności — to jest kanał, którego nie używamy wcale.

---

## 7. Wygląd — nowoczesny militarny

Dzisiejszy system (ścięte płyty, włosowe krawędzie, akcent na lewej krawędzi, Barlow
Condensed) jest **dobrą bazą i nie proponuję go wymieniać**. Militarnego charakteru
nie robi się nową paletą, tylko czterema rzeczami, z których każda jest funkcjonalna:

1. **Symbolika HUD zamiast widżetów.** Drabinka pochylenia, wektor prędkości, taśmy
   wysokości i prędkości z „bąbelkami" celu, celownik z podziałką kątową w kadrze kamery.
   Wojskowy wygląd bierze się stąd, że **wszystko jest narysowane cienką linią wprost
   na obrazie**, a nie umieszczone w kafelkach obok obrazu.
2. **Jedna waga linii, trzy poziomy jasności.** Dziś mamy płyty o różnym kryciu.
   Na HUD-ie obowiązuje: kontur 1 dp dla tła skal, 2 dp dla wartości bieżących,
   wypełnienie tylko dla alarmu. Nic półprzezroczystego nad obrazem
   — to zresztą domyka U1 z audytu.
3. **Cyfry monospaced, stała szerokość pola.** Liczba, która „skacze" przy zmianie
   z 9 na 10, jest nieczytelna kątem oka. `Kroje.liczba` już to robi — trzeba to
   rozciągnąć na wszystkie odczyty.
4. **Trzy palety, przełączane jednym klawiszem:**

| Paleta | Kiedy | Charakter |
|---|---|---|
| **DZIEŃ** | słońce | monochromatyczna, biel i czerń, kontur 2 dp, zero przezroczystości, alarm czerwienią |
| **NOC** | zmrok i noc | bursztyn na czerni, jasność zbita do ok. 30 % |
| **NVG** | gogle noktowizyjne | ciemna czerwień, **zero bieli i zero błękitu** — biały piksel zasypia gogle |

Dzisiejsze CIEMNY/JASNY zostaje jako podział bazowy; DZIEŃ/NOC/NVG to warstwa nad nim.

---

## 8. Pilotowanie w każdych warunkach

| Warunek | Co dziś zawodzi | Propozycja |
|---|---|---|
| **pełne słońce** | panele mają krycie 92 %, tekst przez nie prześwituje (U1); akcent błękitny ma niski kontrast na bieli | paleta DZIEŃ: pełne krycie, mono, kontur 2 dp |
| **noc** | biały tekst na czarnym oślepia i psuje adaptację wzroku | paleta NOC / NVG |
| **rękawice** | cele dotykowe podniesione do 64 dp (5a.4), ale **nietestowane w rękawicach** | test w polu; klawisze komend już 72 × 68 |
| **wiatr** | nie ma ani wiatru, ani zapasu ciągu, a to one razem decydują | A1 + A4, oba w jednym pasie |
| **pilot patrzy na maszynę, nie na ekran** | zero dźwięku | E |
| **utrata łącza** | liczby zastygają i wyglądają na prawdziwe | D |
| **utrata GNSS** | baner mówi „BRAK KURSU", ale nie mówi, co robić | baner z **procedurą**: „AltHold — dolna pozycja przełącznika trybów, sprowadź ręcznie", zgodnie z sekcją 5a `CLAUDE.md` |
| **zimno, deszcz** | — | bez zmian w oprogramowaniu |

---

## 9. Układ — dwa warianty do wyboru

Ekran roboczy: **950 × 594 dp** (1280 × 800 px, 7", gęstość liczona przez aplikację).
Po odjęciu belki 32 dp zostaje 950 × 562.

### Wariant 1 — dołożenie do istniejącego szkieletu

Makieta M3 zostaje nietknięta, nowe przyrządy wchodzą w wolne miejsca:

- **pas zapasu** — poziomy, 320 × 18 dp, tuż nad rzędem liczb, pośrodku:
  po lewej ZAPAS CIĄGU, po prawej ROZRZUT
- **blok ENERGIA** — wchodzi do rzędu liczb jako czwarty odczyt
  (mAh · A · JOKER), kosztem przesunięcia bloku pozycji
- **strzałka wiatru** — mała, w lewym górnym rogu kadru, nad kolumną komend
- **pasek czujników** — sześć kropek na belce górnej, obok satelitów
- **AGL** — druga linia w odczycie wysokości, mniejszym krojem

**Za:** tanie, odwracalne, nie kłóci się z niczym, co przekazał projektant.
**Przeciw:** ekran robi się gęstszy, a to nadal nie jest HUD — pilot wciąż zbiera
informacje z pięciu różnych miejsc.

### Wariant 2 — LOT jako HUD

Reorganizacja kadru: symbole na obrazie, nie obok obrazu.

```
┌ belka 32 ────────────────────────────────────────────────────────┐
│ taśma kursu ze strzałką wiatru i znacznikiem domu                │
│  ┌PRĘD┐                                                  ┌WYS ┐  │
│  │ 6.2│         ——— drabinka pochylenia ———              │ 58 │  │
│  │ ▓▓ │              ⊕  wektor prędkości                 │ ▓▓ │  │
│  │    │         ─────  symbol maszyny  ─────             │AGL │  │
│  └────┘                                                  └────┘  │
│ RTL LĄDUJ                                        kolumna kamery  │
│ PRZERWIJ                                                         │
│  ┌miniatura┐   ZAPAS ▓▓▓▓▓▓░░ 84 µs   ROZRZUT 121 µs   ┌ENERGIA┐ │
│  │  mapy   │   czujniki ●●●●●●         WIBR ▂▃▂         │4538mAh│ │
│  └─────────┘                                            │JOKER 3│ │
└──────────────────────────────────────────────────────────────────┘
```

**Za:** to jest przyrząd pilota, a nie panel telemetrii. Wszystko krytyczne w jednym
polu widzenia, wokół środka kadru.
**Przeciw:** **rozjazd z makietą M3**, którą projektant oddał dwa dni temu i którą właśnie
wdrożyliśmy. Wymaga jego zgody albo świadomej decyzji, że LOT idzie własną drogą.

---

## 9a. Co zostało wdrożone 2026-08-26

Etapy 1–3 z §10 — te, które działają w **obu** wariantach układu, więc nie przesądzają
decyzji z §11 pkt 1. Wariant 1 (dołożenie do szkieletu M3), bez ruszania makiety.

| Co | Gdzie |
|---|---|
| dekodowanie `SERVO_OUTPUT_RAW`, `VIBRATION`, `HOME_POSITION` | `net/mavlink/Mavlink.kt`, `domain/SilnikStanu.kt` |
| maski zdrowia z `SYS_STATUS` zamiast `pomin(12)` | `domain/SilnikStanu.kt` |
| wysokość MSL i **kierunek** toru z `vx/vy` | j.w. |
| **ZAPAS CIĄGU** i **ROZRZUT** z rozkładem na trzy składowe | `domain/Ciag.kt`, `ui/PasZapasu.kt` |
| **ENERGIA** z JOKER i BINGO | `domain/Energia.kt`, `ui/BlokEnergii.kt` |
| alarmy dźwiękowe | `diag/Dzwieki.kt`, `domain/Sygnaly.kt` |
| zamawianie strumieni bez zmiany parametrów maszyny | `Mavlink.zadanieInterwalu`, `LaczeMavlink.zamowPrzyrzady` |
| scenariusz `zanik_ciagu` odtwarzający lot 3 | `narzedzia/symulator_telemetrii.py` |
| 26 testów jednostkowych | `ZapasTest.kt` (razem 131, wszystkie przechodzą) |

Zrzuty: `dok/zrzuty/zapas_lot.png` (emulator), **`dok/zrzuty/zapas_mk32.png` (prawdziwy MK32)**.

### Co potwierdził pomiar

Symulator odtwarza narastanie z lotu 3 (średnia 1736 → 1787 µs, rozrzut 101 → 173 µs).
Przyrząd na tym przebiegu pokazuje **ZAPAS 77 µs na bursztynowo** i **ROZRZUT 173** —
czyli dokładnie tę liczbę, którą `CLAUDE.md` poz. 45 zapisała dla okna „zawis 58 m tuż przed",
i dokładnie ten kolor, którego wtedy zabrakło. Rozkład na składowe nazywa przyczynę
**„ciężki tył"**, zgodnie z tym, że `tył − przód` było dodatnie w każdym oknie każdego lotu.

Bez telemetrii oba przyrządy mówią **„BRAK DANYCH O SILNIKACH"** i **„BATT_CAPACITY
nie pobrane z maszyny"**, a nie zero i nie 100 %.

### Dwa błędy, które wyszły dopiero na ekranie

1. **`return@Row` z lambdy kompozycyjnej** wywrócił aplikację przy pierwszym uruchomieniu
   (`ArrayIndexOutOfBoundsException` w `SlotTable`) — ten sam błąd, który już raz
   naprawiano w `EkranMisji`. Zapisany teraz jako ostrzeżenie w `ui/PasZapasu.kt`.
2. **Pomyłka jednostek ×1000, dwa razy** (mAh↔A i A↔mAh/s) dała na ekranie
   **„JOKER 61097:33"**. Testy tego nie złapały, bo sprawdzały **relacje** — że JOKER
   wypada przed BINGO i rośnie z dystansem — a relacje między błędnymi liczbami też
   się zgadzają. Dopisany test na wartości bezwzględne.

### ⚠ Czego wdrożenie **nie** dowodzi

- **Dekodowanie jest sprawdzone, zamawianie strumienia nie.** Symulator nadaje
  `SERVO_OUTPUT_RAW` bezwarunkowo, więc nie wiadomo, czy `SET_MESSAGE_INTERVAL` w ogóle
  dojdzie przez port z `SERIAL6_OPTIONS = 4096` (poz. 35). **Jeśli nie dojdzie, przyrząd
  powie „brak danych" i trzeba będzie podnieść `SR1_RAW_CTRL` na maszynie** — to jest
  decyzja z §11 pkt 2, nadal otwarta.
- **Dźwięk nie był słyszany.** `ToneGenerator` jest wpięty i logika przetestowana,
  ale czy MK32 pozwala grać obok SIYI FPV — §11 pkt 6, nadal do sprawdzenia w polu.
- Progi zapasu (100 / 60 / 40 µs) nadal pochodzą z **czterech punktów pomiarowych**.

---

## 9b. Etapy 4 i 5 — wdrożone 2026-08-28

**Tom wybrał wariant 1**: dokładamy do szkieletu makiety M3, bez przebudowy kadru.
Etap 7 (HUD) odpada razem z wariantem 2.

| Co | Gdzie |
|---|---|
| stan czujników z masek `SYS_STATUS` | `domain/Czujniki.kt`, `ui/PasekCzujnikow.kt` |
| geofence: naruszenie + zapas do granicy | `domain/Ogrodzenie.kt`, `FENCE_STATUS` |
| wiatr z uśrednionego przechyłu w zawisie | `domain/Wiatr.kt`, `ui/PomiarWiatru.kt`, znacznik na taśmie kursu |
| wibracje | `VIBRATION` → pas zapasu, obok rozrzutu |
| cel automatu: dystans, błąd toru i wysokości | `NAV_CONTROLLER_OUTPUT`, `ui/BlokCelu.kt` |
| 22 testy jednostkowe | `ZdrowieTest.kt` (razem **152**, wszystkie przechodzą) |

Zrzut: `dok/zrzuty/etap45_lot.png`.

### Trzy decyzje projektowe, które wyszły dopiero z ekranu

**1. Pasek czujników rośnie dopiero przy usterce.** Dziewięć kwadratów zajmowało 130 dp
belki na stałe i „WIB" łamało się w pionie na „W / IB". Stan normalny to teraz jeden
zielony znacznik i liczba; lista rozwija się wyłącznie wtedy, gdy coś jest nie tak —
czyli w chwili, w której ma prawo zabrać miejsce czemu innemu. Wibracje przeniosły się
do pasa zapasu, gdzie i tak sąsiadują z rozrzutem silników.

**2. Przy usterce pokazujemy pełny skrót, nie pierwszą literę.** `BAR` i `BAT` dawały dwa
identyczne „B" i nie dało się odróżnić barometru od pomiaru pakietu. Żeby się zmieściły,
**czas lotu ustępuje im miejsca** — belka miała już tę regułę dla postaci zwięzłej,
teraz obowiązuje też przy usterce. Zegar jest też na DIAGNOSTYCE, usterki nie ma nigdzie indziej.

**3. Pas przyrządów jest przypięty do lewej krawędzi wolnego pasa, nie wyśrodkowany.**
Wyśrodkowany rozpychał się w obie strony i przy trzech blokach wchodził pod miniaturę mapy
z jednej strony i pod kolumnę kamery z drugiej. Bloki zwężone do 300 / 180 / 140 dp:
636 dp przy 666 dostępnych.

### Błąd, który znowu złapał dopiero ekran

**JOKER i BINGO skakały na 0:00.** Średni prąd liczyłem jako `zużycie / czas lotu`,
a licznik `zuzycieMah` liczy **od podłączenia pakietu**, nie od startu lotu — 2100 mAh
przy 13 s w powietrzu daje 581 A. Teraz średnia z licznika obowiązuje tylko wtedy, gdy
wychodzi w przedziale 1–200 A; poza nim wierzymy chwilowemu odczytowi. Dopisane dwa testy
na wartości bezwzględne.

To jest **trzeci** raz w tej pracy, gdy testy na relacjach przepuściły błąd, który widać
gołym okiem na ekranie. Relacje między błędnymi liczbami też się zgadzają.

### ⚠ Czego to nie dowodzi

- **Wiatr nie był widziany z prawdziwymi danymi.** Liczy się tylko przy uzbrojonej maszynie
  trzymającej pozycję, a symulator przełącza uzbrojenie co 15 s. Logika ma 8 testów,
  ale [WSPOLCZYNNIK] jest **dobrany, nie zmierzony** — do kalibracji jednym zawisem
  w znanym wietrze z wiatromierzem. Kierunek jest wiarygodny, liczba mówi rząd wielkości.
- **Bloku CEL nie widziałem po ostatniej zmianie szerokości** — wcześniej działał
  (pokazywał „płot 105 m"), a arytmetyka się zgadza, ale to rachunek, nie zrzut.
- Nic z tego **nie było na prawdziwym MK32**: aparatura była zajęta przez drugą sesję
  pracującą nad wideo.

---

## 9c. Etap 6 i czytelna pozycja — 2026-08-28

### Pięć palet zamiast dwóch

`CIEMNY` i `JASNY` zostają, dochodzą trzy warunki pracy — nie „skórki", tylko trzy różne
sytuacje w polu (`ui/Motyw.kt`, enum `Motyw`):

| Paleta | Kiedy | Zasada |
|---|---|---|
| **DZIEŃ** | pełne słońce | **zero przezroczystości**, czerń zamiast grafitu, grubsze krawędzie, ciemne nasycone akcenty. Domyka U1 z audytu — panel warstw przy kryciu 92 % przepuszczał rząd liczb |
| **NOC** | zmrok | bursztyn na czerni, jasność zbita do ok. jednej trzeciej; biel psuje adaptację wzroku na kilkanaście minut, a pilot patrzy na przemian na ekran i w niebo |
| **NVG** | gogle noktowizyjne | ciemna czerwień, **zero bieli i zero błękitu** — biały piksel zasypia wzmacniacz obrazu i gogle ściemniają całe pole |

⛔ **W NVG stany nie różnią się odcieniem, tylko jasnością** — wszystko leży w rodzinie
czerwieni. Dlatego znaczenie musi nieść także kształt: pasek czujników rysuje sprawny
czujnik konturem, a uszkodzony wypełnieniem. Ta decyzja z etapu 4 bierze się właśnie stąd.

Klawisz MOTYW na belce **cykluje** po pięciu, bo w polu nie ma czasu na otwieranie panelu;
pełna lista z opisami jest w WARSTWACH. Ustawienie przeżywa restart, ze zgodnością wstecz
dla zapisanego wcześniej boola `w_ciemny`.

### Okno pełnej pozycji

Zgłoszone przez Toma: *„informacja w MGRS jest za mała i jej nie widać"*. Miał rację —
MGRS dzielił linię ze stanem GNSS przy **9 sp**, czyli ok. 12 px na tej aparaturze.

Dotknięcie bloku pozycji otwiera `ui/OknoPozycji.kt`: **WGS84 dziesiętne, DMS i MGRS,
każde 24 sp w osobnej linii**, pod spodem wysokość, dystans i namiar do domu, stan GNSS
oraz pozycja samego domu. Który zapis jest potrzebny, zależy od rozmówcy — służbom zwykle
DMS albo MGRS, do QGC dziesiętne — więc wszystkie trzy są duże.

W samym rzędzie liczb MGRS dostał osobną linię i 11 sp, a stan GNSS zszedł niżej.

**Pułapka, na którą się nadziałem:** `Dialog` zakłada **własne okno i nie dziedziczy
`LocalDensity`** nadpisanego w `MainActivity`. A na tym nadpisaniu stoi cały układ
aplikacji — MK32 melduje gęstość 320, choć panel 7" 1280 × 800 ma realnie ok. 216 dpi.
Bez przekazania gęstości okno rysowało się w innej skali niż kokpit: 560 dp wychodziło
1120 px zamiast 755 i **zasłaniało kolumnę komend RTL i LĄDUJ**. Naprawione przez
`CompositionLocalProvider(LocalDensity provides …)` w środku dialogu; szerokość zeszła
do 470 dp, żeby komendy zostały odsłonięte także po naprawie.

To dotyczy **każdego** przyszłego dialogu w tej aplikacji, nie tylko tego jednego.

---

## 9d. Wiatr w okręgu i piktogramy zamiast podpisów — 2026-08-28

Dwie zmiany na prośbę Toma, obie o tym samym: **mniej szukania, mniej słów**.

### Wiatr wrócił z taśmy do okręgu

*„Chcę, aby wszystkie informacje były dostępne dla pilota centralnie i nie musiał
ich szukać"*. Wiatr siedział na taśmie kursu, czyli w drugim miejscu na ekranie.
Teraz **strzałka obiega ten sam okrąg, na którym pilot czyta położenie**, a prędkość
stoi tuż nad nim przy ikonie wiatru.

Kierunek jest podany **względem dziobu**, nie względem północy: strzałka u góry znaczy
wiatr w twarz, u dołu — w plecy, grot skierowany do środka, bo wiatr napiera na maszynę.
Tak się o wietrze myśli w locie — od tego zależy, w którą stronę zniesie i gdzie zniknie
zapas ciągu. Na taśmie kursu wiatru już nie ma; został tam znacznik domu.

### Piktogramy zamiast podpisów

*„Jak będziemy dodawać tłumaczenie, piktogramy same się bronią"* — i to jest właściwy
powód, mocniejszy niż samo miejsce. Rysunek nie ma języka.

| Było | Jest |
|---|---|
| `ZAPAS` | strzałka wspinająca się do sufitu |
| `ROZRZUT` | cztery wirniki w układzie X, jeden większy |
| `GAZ` | słupek wypełniony od dołu |
| `WIB` | fala o rosnącej amplitudzie |
| `ENERGIA` | bateria |
| `CEL` | celownik |
| `wysokość` `do domu` `prędkość` `wznoszenie` | strzałka od gruntu, dom, prędkościomierz, strzałki góra-dół |
| `pozycja maszyny` | pinezka |

Zostają słowa, które **nie są etykietami, tylko treścią**: `JOKER`, `BINGO`
(terminy lotnicze, krótkie i międzynarodowe), `ciężki tył` (diagnoza, nie podpis)
oraz stany na belce.

**Dwie ikony trzeba było przerysować po zobaczeniu ich na ekranie.** Przy 13 dp suwak gazu
czytał się jako znak `+`, a waga na klinie jako kreska. Zastąpione słupkiem i czterema
wirnikami — ta druga przy okazji mówi więcej, bo pokazuje wprost, o co chodzi:
jeden silnik pracuje mocniej niż reszta.

Symulator dostał scenariusz `wiatr` — zawis w miejscu z przechyłem 6° w prawo i 4° do
przodu, czyli wiatr z okolic 056°. Bez niego wiatru nie da się zobaczyć na biurku,
bo liczy się tylko przy uzbrojonej maszynie trzymającej pozycję.

---

## 9e. Główny przyrząd i ikony na belce — 2026-08-28

### Horyzont: wyrazistość konturem, nie wypełnieniem

Pierwsze podejście było błędne i Tom odrzucił je od razu: żeby przyrząd był wyraźny nad
jasnym kadrem, **wypełniłem tarczę** nieprzezroczystym tłem. Powstał matowy dysk 190 dp
na środku obrazu — dokładnie to, czego `UI.md` §7 zakazuje.

Właściwa odpowiedź to **podwójna kreska**: najpierw ciemniejsza i szersza, na niej
właściwa. Tak rysuje się napisy na wideo i tak działa każdy HUD. Kontrast kosztuje dwa
piksele, a nie całe koło — obraz widać przez cały przyrząd.

Po drodze druga korekta: pierwszy kontur miał krycie 88 % i 2,5 dp rozrostu, przez co
przyrząd wyglądał jak stary kompas obrysowany tuszem. Zeszło do 60 % i 1,2 dp.

Wygląd przerobiony na lżejszy: **152 dp zamiast 190**, obwódka tarczy to **cztery łuki
z przerwami** zamiast domkniętego koła, podziałka pierścienia co 30° zamiast co 15°,
horyzont **przerwany w środku**, a symbol maszyny to **skrzydełka**, nie krzyż.

### Co przyrząd niesie teraz

Położenie, pochylenie, przechył, **kurs** (pierścień plus liczba w pudełku), **dom**
(domek na pierścieniu) i **wiatr** (strzałka na krawędzi plus prędkość pod kołem).
Taśma kursu jest domyślnie zdjęta, bo powtarzałaby to samo; zostaje w warstwach.

### Belka na ikonach

Podpisy `SAT`, `ŁĄCZE` i `LOT` zastąpione piktogramami. Łącze dostało **słupki zasięgu
wypełniane kolorem stanu**:

| Stan | Wygląd |
|---|---|
| łącze sprawne | zielone słupki, im szybciej ramki, tym więcej |
| stawka spadła | pomarańczowe |
| brak łącza | puste kontury, przekreślone na czerwono, obok czas ciszy |

Progi dobrane pod **zmierzone 48 Hz na MK32**, nie pod 100 Hz z emulatora — inaczej
na prawdziwym sprzęcie nigdy nie byłoby zielono.

Kształt i barwa mówią to samo dwa razy. To nie nadmiar: w palecie NVG wszystko leży
w rodzinie czerwieni i wtedy zostaje sama liczba wypełnionych słupków.

**Tekstem zostają `ALTHOLD` i `UZBROJONY`** — to nie etykiety, tylko stany, a dwadzieścia
trybów ArduPilota nie ma sensownych piktogramów.

---

## 9f. Progi paliwowe na belce, władza zdjęta — 2026-08-28

### JOKER i BINGO wyszły z pasa na belkę

Były dwoma polami w bloku energii. Na belce mieści się jedno, i słusznie: pilot potrzebuje
**tylko najbliższego progu**. Dopóki JOKER przed nim, liczy się do JOKER; po jego
przekroczeniu sens ma już tylko BINGO, a pierwsza liczba jest szumem, bo decyzja zapadła.

**Na ekranie nie ma już tych nazw** — zastąpiły je piktogramy (Tom, 2026-08-28):

| Próg | Ikona | Znaczy |
|---|---|---|
| JOKER | dom z zegarem | czas ruszać do domu, żeby wrócić z rezerwą |
| BINGO | pusta bateria z wykrzyknikiem | powrót przestaje być możliwy |

Nazwy zostają w dokumentacji i w kodzie, bo tam są precyzyjne. Na ekranie rysunek mówi
to samo bez języka.

Pole **milczy przy niekalibrowanym `BATT_CAPACITY`** (poz. 9 i 40) — obie liczby byłyby
wtedy zmyślone. Surowe mAh i procent zostają w bloku energii, który zwęził się do 158 dp.

### Znacznik władzy zdjęty z belki

Tom uznał go za zbędny przy jednym operatorze. **Nie usunięty, tylko przeniesiony
do warstw** i domyślnie wyłączony: przy dwóch stacjach naziemnych (`dok/WLADZA.md`)
informacja o tym, kto steruje, wraca na wagę i szkoda byłoby jej nie mieć.

---

## 10. Kolejność, gdyby wchodziło etapami

| Etap | Zawartość | Dlaczego tu |
|---|---|---|
| ~~**1**~~ | ~~`SERVO_OUTPUT_RAW` + **A1 ZAPAS** + **A2 ROZRZUT**~~ | ✅ **wdrożone 2026-08-26** |
| ~~**2**~~ | ~~**A3 ENERGIA** z JOKER/BINGO~~ | ✅ **wdrożone 2026-08-26** |
| ~~**3**~~ | ~~**E dźwięk**~~ | ✅ **wdrożone 2026-08-26**, niesłyszane na sprzęcie |
| ~~**4**~~ | ~~**C1 czujniki** + `HOME_POSITION` + `FENCE_STATUS`~~ | ✅ **wdrożone 2026-08-28** |
| ~~**5**~~ | ~~**A4 wiatr**, **C2 wibracje**, **B5 cel automatu**~~ | ✅ **wdrożone 2026-08-28** |
| ~~**6**~~ | ~~**paleta DZIEŃ / NOC / NVG**~~ | ✅ **wdrożone 2026-08-28** |
| ~~**7**~~ | ~~**B1–B4 HUD**~~ | ⛔ **odpada** — to jest wariant 2, a wybrany został wariant 1 |

Etapy 1–3 to sedno. Gdyby miało wejść tylko tyle, ekran LOT i tak zmieni się z panelu
telemetrii w przyrząd, który potrafi ostrzec przed konkretnym, znanym sposobem, w jaki
ta maszyna zawodzi.

---

## 11. Co trzeba rozstrzygnąć, zanim cokolwiek zakoduję

1. ~~**Wariant 1 czy 2**~~ → **rozstrzygnięte 2026-08-28: wariant 1**, makieta M3 zostaje.
   Etap 7 (HUD) tym samym odpada.
2. **Czy podnosimy `SRn_RAW_CTRL` i `SRn_EXTRA3`** na łączu MK32.
   Bilans pasma wychodzi ok. 30 % zajętości przy 115 200 — jest miejsce, ale to zmiana
   parametrów maszyny, więc pytam.
3. **AGL — układ odniesienia.** Terrarium podaje wysokość nad geoidą, GNSS nad elipsoidą;
   w Polsce różnica to ok. 30 m. Zanim pokażę „prześwit 12 m", muszę wiedzieć,
   że to prawda. Do sprawdzenia pomiarem na znanym punkcie.
4. **JOKER i BINGO — jaki margines.** Proponuję JOKER przy 35 % pojemności, BINGO przy 20 %,
   ale przy niekalibrowanym `BATT_CAPACITY` (poz. 9 i 40) to liczby z sufitu.
   **Kalibracja wattomierzem jest warunkiem, żeby ten przyrząd nie kłamał.**
5. **Czy `MOT_SPIN_MAX` pobieramy z maszyny**, czy przyjmujemy 0,95 na sztywno.
   Zapas ciągu liczy się względem tego sufitu, więc wolałbym pobierać.
6. **Dźwięk — czy MK32 pozwala aplikacji grać** przy jednoczesnym działaniu SIYI FPV.
   Kod jest wpięty; zostaje odsłuchać na sprzęcie.
7. **Progi zapasu ciągu.** Proponuję 100 µs (uwaga) / 60 µs (ostrzeżenie) / 40 µs (blokada),
   wyprowadzone z lotów 3 i 4 — ale to są **cztery punkty pomiarowe**, nie statystyka.
   Do potwierdzenia na kolejnych lotach.
8. **Co z pakietem 8S** (poz. 56). Progi napięcia, `napiecieNaOgniwo` dzielone przez 6
   na sztywno i cała skala baterii są 6S-owe. Jeśli 8S wchodzi w tym roku, profil pakietu
   opłaca się zrobić **razem** z tą przebudową, a nie osobno.
