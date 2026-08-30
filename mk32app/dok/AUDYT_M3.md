# Audyt aplikacji kokpitu MK32 — wydanie M3

**Data:** 2026-08-26 · **Przedmiot:** `mk32app/app/cockpit` w całości
(12 189 linii Kotlina w 42 plikach źródłowych, 105 testów jednostkowych)
**Wersja aplikacji:** `0.1-M1`, kokpit po przebudowie na makietę `Kokpit M3.dc.html`

Audyt kodu, zachowania w emulatorze i zgodności ze stanem maszyny opisanym w `CLAUDE.md`.
Każde znalezisko ma dowód: `plik:linia`, zrzut ekranu albo wynik pomiaru.
**Hipotezy bez potwierdzenia nie weszły do zestawienia** — trzy podejrzenia o nieaktualne
lambdy sprawdziłem na emulatorze; dwa się potwierdziły i są niżej, trzecie opisuję
jako ten sam wzorzec, ale bez sceny, która by go wywołała.

Poprzedni audyt: `AUDYT_UI.md` (2026-08-19, przed przebudową). Braki F1–F10 stamtąd
są zamknięte — mapa, ślad, czas lotu, `RC_CHANNELS`, `COMMAND_ACK`, panel przypisań RC
i sterowanie głowicą istnieją. Ten audyt dotyczy tego, co powstało po tamtej dacie.

---

## 1. Ocena ogólna

| Obszar | Ocena | Uzasadnienie |
|---|---|---|
| Dekodowanie MAVLink | **dobry** | wszystkie offsety pól sprawdzone z dialektem, zgodne co do bajtu |
| Współrzędne (UTM/MGRS/DMS) | **dobry** | pięć punktów kontrolnych zgodnych z niezależną implementacją, ale **zero testów** |
| Model misji i edycja punktów | **niedostateczny** | B1 — kasuje punkty trasy przy edycji wysokości |
| Checklista przedlotowa | **niedostateczny** | B2 — żąda archiwalnego mapowania silników i każe je pilotowi przywrócić |
| Ostrzeżenia bezpieczeństwa | **częściowy** | progi napięcia dla 6S przy pakiecie 8S w budowie; detektor zagłuszania działa tylko na dwóch ekranach |
| Warstwy ekranu | **niedostateczny** | B3 — włączenie jednej warstwy cofa poprzednią |
| Odporność łącza | **częściowy** | brak filtrowania nadawcy MAVLink |
| Rozmiar i wydajność | **do poprawy** | 96 MB APK, 80 % to dwie kopie libVLC |
| Pokrycie testami | **częściowy** | 766 linii logiki krytycznej bez ani jednego testu |

---

## 2. Blokady

### B1 — Edycja wysokości punktu **kasuje resztę trasy**. Potwierdzone na emulatorze.

**Objaw.** W MISJA → PLANUJ dołożyłem dwa punkty (panel: `2 pkt · 161 m`,
`audyt_misja.png`). Naciśnięcie `+` przy punkcie 1 podniosło jego wysokość z 30 na 35 m
**i skasowało punkt 2** — panel pokazuje `1 pkt · 0 m` (`audyt_plus1.png`).
Kolejne naciśnięcia nie robią już nic (`audyt_plus3.png`).

**Przyczyna.** [EkranMisji.kt:522](app/cockpit/src/main/java/pl/dron15/cockpit/ui/EkranMisji.kt:522):

```kotlin
.pointerInput(znak) { detectTapGestures(onTap = { akcja() }) }
```

Kluczem jest sam znak (`+`, `−`, `✕`), który nigdy się nie zmienia, więc blok gestu
nie restartuje się i trzyma `akcja` z **pierwszej** kompozycji. Ta lambda domyka się nad
`misja`, które jest **parametrem** `EkranMisji` — czyli nad stanem trasy sprzed edycji.
Każde naciśnięcie przebudowuje misję z tamtego, jednopunktowego stanu.

**Skutek.** Pilot planuje trasę, koryguje wysokość jednego punktu i po cichu traci resztę.
Licznik punktów się zmienia, ale nic tego nie sygnalizuje. Trasa wysłana do maszyny
po takiej edycji **nie jest trasą, którą pilot zaplanował**.

**Naprawa.** `val akcjaTeraz by rememberUpdatedState(akcja)` i klucz `Unit` — dokładnie tak,
jak zrobiono w `KlawiszKomendy`, `Chip`, `PrzyciskPrzytrzymaj` i `Przelacznik`.
Ostrzeżenie o tej pułapce jest już zapisane w [Elementy.kt:632](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Elementy.kt:632)
— po prostu nie zostało zastosowane wszędzie.

---

### B2 — Checklista przedlotowa każe przywrócić archiwalne mapowanie silników.

[preflight_rules.json:11](app/cockpit/src/main/assets/preflight_rules.json:11), reguła `silniki`,
poziom **blokada**:

```json
{ "param": "SERVO1_FUNCTION", "rowne": 34 }, { "param": "SERVO2_FUNCTION", "rowne": 36 },
{ "param": "SERVO3_FUNCTION", "rowne": 33 }, { "param": "SERVO4_FUNCTION", "rowne": 35 }
```

To jest **mapowanie z etapu 20, wgrane 2026-08-15 19:42**, które `CLAUDE.md` opisuje
dosłownie jako *„(archiwum) … nieaktualne"*. Obowiązujące — potwierdzone stabilnym lotem 2
z 2026-08-16, błąd kąta σ < 1,2° — jest inne:

| Wyjście | Checklista żąda | Obowiązuje wg `CLAUDE.md` |
|---|---|---|
| `SERVO1_FUNCTION` | 34 | **36** |
| `SERVO2_FUNCTION` | 36 | **33** |
| `SERVO3_FUNCTION` | 33 | **34** |
| `SERVO4_FUNCTION` | 35 | 35 |

**Skutek.** Na poprawnie skonfigurowanej maszynie checklista zapali czerwoną **blokadę**,
a jej komunikat brzmi *„Kolejność inna niż ustalona Motor Testem: 1 tył lewy, 2 tył prawy,
3 przód prawy, 4 przód lewy"* — czyli **instruuje pilota, żeby przywrócił mapowanie
oznaczone w `CLAUDE.md` jako archiwalne**, w miejsce tego, które przeleciało stabilnie.

Nie twierdzę, że akurat wariant z etapu 20 kiedykolwiek latał i spadł — nie wiadomo, czy
w ogóle wzbił się w powietrze. Twierdzę mniej i wystarczająco dużo: **złe przypisanie wyjść
do pozycji na ramie jest udokumentowaną przyczyną salta przy oderwaniu 2026-08-15**
(`dok\logi\log_006.bin`, `CLAUDE.md` sekcja 1), a checklista popycha pilota dokładnie
w stronę zmiany tego przypisania — na maszynie, która ma je już poprawne i potwierdzone lotem.

Narzędzie bezpieczeństwa, które myli się w tę stronę, jest gorsze niż jego brak.

**Przyczyna strukturalna.** Plik ma `"_wersja": "1.0 / 2026-08-18"`, a korekta mapowania
weszła do `CLAUDE.md` później. Nic nie pilnuje, żeby te dwa dokumenty się zgadzały.

---

### B3 — Włączenie jednej warstwy ekranu cofa poprzednią. Potwierdzone na emulatorze.

**Objaw.** W panelu WARSTWY EKRANU dotknąłem wiersza „Rząd liczb" — rząd liczb zniknął
(`audyt_warstwy1.png`). Potem dotknąłem wiersza „Taśma kursu" — taśma zniknęła,
**ale rząd liczb wrócił**, razem z przełącznikiem w pozycji włączonej
(`audyt_warstwy2.png`, widać to też na `audyt_stan.png`).

**Przyczyna.** [Belka.kt:627](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Belka.kt:627) —
ten sam wzorzec co B1: `pointerInput(nazwa, wlaczona)` z lambdą domykającą się
nad parametrem `warstwy`. Druga zmiana wychodzi ze stanu sprzed pierwszej.

**Niespójność, która to maskuje:** dotknięcie **samego przełącznika** działa poprawnie,
bo `Przelacznik` ma `rememberUpdatedState` ([Elementy.kt:910](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Elementy.kt:910)).
Ten sam wiersz zachowuje się więc różnie zależnie od tego, w które miejsce pilot trafi palcem.

**Skutek.** Ustawienie kadru jest zapisywane między lotami. Pilot, który zdejmuje z ekranu
dwa elementy przed startem, poleci z jednym z nich nadal na kadrze i z zapisanym
niepoprawnym ustawieniem.

---

### B4 — Ten sam wzorzec w czterech dalszych miejscach, jeszcze bez sceny wywołującej.

Pełna lista miejsc gestu bez `rememberUpdatedState`:

| Miejsce | Klucz | Ryzyko |
|---|---|---|
| [EkranMisji.kt:522](app/cockpit/src/main/java/pl/dron15/cockpit/ui/EkranMisji.kt:522) `MalyKlawisz` | `znak` | **B1, potwierdzone** |
| [Belka.kt:627](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Belka.kt:627) `WierszWarstwy` | `nazwa, wlaczona` | **B3, potwierdzone** |
| [Elementy.kt:712](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Elementy.kt:712) `PrzyciskAkcji` | `etykieta, dostepny` | **DODAJ** w panelu szukania domyka się nad `misja` przez `dolozPunkt` ([EkranMisji.kt:205](app/cockpit/src/main/java/pl/dron15/cockpit/ui/EkranMisji.kt:205)) — drugie dodanie ze współrzędnych nadpisze pierwsze |
| [Elementy.kt:869](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Elementy.kt:869) `PrzyciskIkona` | `etykieta, dostepny` | dziś wszystkie akcje są stabilne; pęknie przy pierwszej, która nie będzie |
| [EkranMisji.kt:470](app/cockpit/src/main/java/pl/dron15/cockpit/ui/EkranMisji.kt:470) wiersz punktu | `numer, wybrany` | `wybrany` zmienia się przy wyborze, więc klucz zwykle restartuje — samo się leczy, ale przypadkiem |
| [Belka.kt:421](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Belka.kt:421) wiersz menu widoków | `e` | akcja stabilna, dziś nieszkodliwe |

Cztery bliźniacze komponenty (`KlawiszKomendy`, `Chip`, `PrzyciskPrzytrzymaj`, `Przelacznik`)
mają `rememberUpdatedState`, sześć nie. To nie jest przeoczenie w jednym miejscu, tylko
**brak reguły** — dlatego naprawa punktowa B1 i B3 nie wystarczy.

---

## 3. Bezpieczeństwo — rozjazd z rzeczywistą maszyną

### S1 — Progi napięcia są dla 6S LiPo; maszyna przechodzi na 8S Li-ion.

[Ostrzezenia.kt:20-21](app/cockpit/src/main/java/pl/dron15/cockpit/domain/Ostrzezenia.kt:20)
i `preflight_rules.json` sekcja `telemetria`:

| Próg | W kodzie | Wg `CLAUDE.md` poz. 56 (8S5P Li-ion) |
|---|---|---|
| górny (limit ZR30 / air unit) | 25,2 V | pakiet pełny to **33,6 V** |
| `BATT_LOW_VOLT` | 22,2 V | **26,4 V** |
| `BATT_CRT_VOLT` | — | **24,8 V** |

Po podłączeniu 8S kokpit od pierwszej sekundy pokaże ostrzeżenie
**„NAPIĘCIE NA GRANICY ZR30 I AIR UNITU"** i nie zdejmie go przez cały lot,
a próg dolny 22,2 V nie zadziała nigdy. Baner, który świeci zawsze, przestaje być banerem.

Progi są zaszyte jako `const` w kodzie i jako liczby w regułach — **nie ma pojęcia profilu
pakietu**. Przy zaplanowanej zmianie zasilania to trzeba przestawić w dwóch miejscach naraz.

### S2 — Ostrzeżenie o niskim napięciu nie może zadziałać, a wygląda jakby chroniło.

`CLAUDE.md` poz. 37: wejście pomiaru czyta stabilizowaną szynę, nie pakiet —
`BAT.Volt` stoi na 24,907…25,020 V (σ 0,0073 V) przy prądzie skaczącym 47→61 A.
Reguła `napiecie_dolne` (blokada, `co_najmniej 22.2`) **zawsze przejdzie**,
a `napiecie_gorne` (`najwyzej 25.2`) będzie migać, bo pomiar leży 0,1 V pod progiem.

Kokpit pokazuje więc zielony ptaszek przy pozycji „Dolny próg napięcia" na maszynie,
która **nie ma żadnego zabezpieczenia przed rozładowaniem**.

### S3 — Brak reguł na dwa czynne blokery z `CLAUDE.md`.

Checklista nie sprawdza niczego z tego:

| Bloker | Stan wg `CLAUDE.md` | W checkliście |
|---|---|---|
| `BATT_LOW_MAH`, `BATT_CRT_MAH` = 0, `BATT_CAPACITY` = 3300 | poz. 45 — *„failsafe baterii nadal całkowicie nieaktywny… Zero ochrony"* | **brak** |
| `MOT_THST_HOVER` rośnie z lotu na lot (0,611 → 0,6875) | poz. 55 — *„ZAPAS CIĄGU ZNIKNĄŁ"* | **brak** |

Przy martwym pomiarze napięcia `BATT_LOW_MAH` jest **jedyną realną ochroną pakietu**
i akurat jej nikt nie pilnuje. Jedna reguła `blokada` na `BATT_LOW_MAH > 0` kosztuje
trzy linie JSON-a.

### S4 — `RTL_ALT`: checklista blokuje na nierozstrzygniętym sporze.

[preflight_rules.json:18](app/cockpit/src/main/assets/preflight_rules.json:18) wymaga
`RTL_ALT = 5000` jako **blokadę**. Na płycie jest `1000`, a `CLAUDE.md` poz. 41 notuje,
że *„decyzja niepodjęta"*. Czyli druga blokada, która zapali się na maszynie w stanie,
w jakim ona faktycznie jest.

Dwie z ośmiu blokad checklisty (B2 i ta) fałszywie alarmują na poprawnie
skonfigurowanej maszynie. Pilot, który raz zobaczy, że blokady kłamią, przestanie
je czytać — i wtedy przegapi tę prawdziwą.

### S5 — Detektor zagłuszania GNSS działa tylko wtedy, gdy pilot patrzy na właściwą zakładkę.

[Ostrzezenia.kt:113](app/cockpit/src/main/java/pl/dron15/cockpit/domain/Ostrzezenia.kt:113)
`wykryjSpadekSatelitow` dopisuje próbki do **wspólnego, modułowego** `ArrayDeque`,
a `ocen()` jest wołane wyłącznie z kompozycji: [Kokpit.kt:81](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Kokpit.kt:81)
i [EkranDiagnostyki.kt:104](app/cockpit/src/main/java/pl/dron15/cockpit/ui/EkranDiagnostyki.kt:104).

Skutki:

- na ekranie MISJA, KAMERA, RC czy PRZED LOTEM **nie wpada ani jedna próbka** —
  szereg czasowy ma dziurę dokładnie wtedy, gdy pilot planuje trasę i najmniej patrzy na kadr;
- po powrocie na LOT okno 10 s czyści historię, więc detektor **startuje od zera** —
  spadek satelitów, który nastąpił w trakcie planowania, nie zostanie wykryty nigdy;
- funkcja mutuje stan współdzielony **w trakcie kompozycji**, co Compose wprost odradza:
  kompozycja może się powtórzyć albo zostać pominięta, a wtedy tempo próbkowania zależy
  od przerysowań ekranu, nie od tempa ramek GNSS.

To jest detektor postawiony pod poz. 36 (*„PODEJRZENIE ZAGŁUSZANIA GNSS PRZEZ VTX"*) —
czyli pod zjawisko, które odbiera pozycję, kurs i RTL naraz. Powinien liczyć w silniku
stanu, przy odbiorze `GPS_RAW_INT`, nie w warstwie widoku.

### S6 — Brak filtrowania nadawcy MAVLink.

[LaczeMavlink.kt](app/cockpit/src/main/java/pl/dron15/cockpit/net/mavlink/LaczeMavlink.kt)
tworzy `DatagramSocket()` bez `connect()` i nie sprawdza adresu źródłowego pakietu,
a [SilnikStanu.kt:30](app/cockpit/src/main/java/pl/dron15/cockpit/domain/SilnikStanu.kt:30)
nie patrzy na `sysid`/`compid`. **Dowolny HEARTBEAT w sieci 192.168.144.0/24** przestawia
stan uzbrojenia, ustala punkt domu, zeruje ślad trasy i restartuje zegar lotu.

To nie jest scenariusz teoretyczny: w tej samej sieci siedzi ground unit MK32, air unit,
głowica i — zgodnie z `dok\GCS_RPI5.md` — planowany drugi GCS. Dwa MAVLinkowe węzły
w jednej podsieci to stan docelowy, nie awaria.

**Naprawa:** przyjmować `sysid` pierwszego widzianego autopilota i odrzucać resztę,
albo `connect()` na adres air unitu.

---

## 4. Usterki użytkowe

| # | Znalezisko | Dowód | Waga |
|---|---|---|---|
| **U1** | **Panel warstw prześwituje.** `surf-c-max` ma krycie 92 %, więc rząd liczb i pozycja maszyny czytają się **przez** panel; „POZYCJA MASZYNY / 52.12300 N 20.12393 E / 34U DC…" widać pod kaflami PODKŁAD MAPY. Wierne makiecie (`rgba(7,11,13,.92)`), ale makieta nie stała na słońcu | `audyt_stan.png`, [Motyw.kt:73](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Motyw.kt:73), [Belka.kt:454](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Belka.kt:454) | wysoka |
| **U2** | Opisy warstw ucięte w połowie słowa: „dotknięcie zamienia z", „dociąga brakujące kafelki i", „wysokość · dom · prędkość ·" — `maxLines = 1` bez wielokropka | `audyt_stan.png` | średnia |
| **U3** | Lista misji podaje „2 pkt · **161 m**", a pasek profilu pod tą samą trasą „**273 m**" — dwie różne długości obok siebie, bez wyjaśnienia, która jest która (pozioma vs. z profilem terenu) | `audyt_misja.png` | wysoka |
| **U4** | Widok 3D blokuje edycję punktów. Aplikacja **mówi o tym** — „WIDOK PRZESTRZENNY — PUNKTY DOKŁADA SIĘ NA MAPIE PŁASKIEJ" — ale to jest dokładnie ta podpowiedź, którą zasłania sterowanie zasięgiem z U5 | [EkranMisji.kt:148](app/cockpit/src/main/java/pl/dron15/cockpit/ui/EkranMisji.kt:148), `mapy_misja_3d.png` | średnia |
| **U5** | Panel kafli warstw zasłania około jednej trzeciej mapy; sterowanie zasięgiem i wysokością nachodzi na podpowiedź mapy | `mapy_panel_warstw.png` | średnia |
| **U6** | Znacznik domu na taśmie kursu nachodzi na litery stron świata | `audyt_lot.png` | niska |
| **U7** | „LOT 0:13" świeci obok „ROZBROJONY". Logika jest poprawna (to czas ostatniego lotu, zamrożony przy rozbrojeniu, [SilnikStanu.kt:53](app/cockpit/src/main/java/pl/dron15/cockpit/domain/SilnikStanu.kt:53)), ale etykieta nie odróżnia zegara idącego od zatrzymanego | `audyt_stan.png` | niska |
| **U8** | **Brak autozapisu planowanej misji.** `MagazynMisji` zapisuje wyłącznie na jawne ZAPISZ. Proces ubity pod presją pamięci — realne przy 55 MB kafli plus libVLC plus SIYI FPV obok — kasuje trasę bez śladu | [MagazynMisji.kt](app/cockpit/src/main/java/pl/dron15/cockpit/domain/MagazynMisji.kt) | średnia |

---

## 5. Wydajność i rozmiar

### W1 — APK 96 MB, z czego 80 % to dwie kopie libVLC.

Zmierzone na świeżo zbudowanym `cockpit-release-unsigned.apk` (100 939 343 B):

| Składnik | Rozmiar w archiwum |
|---|---|
| `lib/arm64-v8a/libvlc.so` | **40,3 MB** |
| `lib/armeabi-v7a/libvlc.so` | **36,8 MB** |
| dex | 17,0 MB |
| `libc++_shared.so` × 2 | 1,4 MB |
| reszta (res, assets, jni) | 0,7 MB |

Dwa ruchy, oba tanie:

1. **Sprawdzić ABI aparatury i wyrzucić drugą.** Jeśli MK32 jest 64-bitowy — a przy 4 GB RAM
   niemal na pewno jest — `armeabi-v7a` to 36,8 MB balastu. Sprawdzenie na sprzęcie:

```bash
adb shell getprop ro.product.cpu.abilist
```

2. **Włączyć R8.** [build.gradle.kts:25](app/cockpit/build.gradle.kts:25) ma
   `isMinifyEnabled = false`, więc 17 MB dexu zawiera cały runtime Compose bez obcinania.
   Typowo schodzi to o połowę.

Razem: **96 MB → ok. 50 MB.** Komentarz w `build.gradle.kts` mówi wprost, że producent
odradza obciążanie aparatury — a połowa tego obciążenia jest zbędna.

### W2 — Zacinanie przy pracy na mapie.

Logcat z sesji audytowej: `Choreographer: Skipped 42 frames`, `Skipped 41`, `Skipped 31`,
a `OpenGLRenderer: Davey!` zgłasza klatki po **700–1200 ms** ciągiem podczas przewijania
i powiększania mapy w MISJI.

⚠ **Pomiar z emulatora z programowym GPU — na sprzęcie będzie lepiej i tej liczby nie wolno
przenosić wprost.** Ale kierunek jest wiarygodny, a jeden konkretny winowajca jest w kodzie:
[Kafelki.kt](app/cockpit/src/main/java/pl/dron15/cockpit/ui/Kafelki.kt) — `zbadajKarte(korzenie)`
robi `listFiles()` po karcie TF **w konstruktorze magazynu**, czyli na wątku głównym.
Ta sama lekcja została już raz odrobiona przy libVLC ([OdtwarzaczVlc.kt:24](app/cockpit/src/main/java/pl/dron15/cockpit/video/OdtwarzaczVlc.kt:24)
opisuje 2,5 s zamrożenia i 149 zgubionych klatek) — tu jej nie zastosowano.

---

## 6. Testy

105 testów, wszystkie przechodzą. Rozkład:

| Plik | Testów | Co pokrywa |
|---|---|---|
| `MapyTest` | 46 | teren, cieniowanie, warstwice, profil, azymut, rzut 3D, kafelki, zasięg |
| `ProtokolyTest` | 20 | ramkowanie MAVLink, SIYI, ostrzeżenia |
| `ChecklistaTest` | 16 | silnik reguł checklisty |
| `RcTest` | 12 | dekodowanie kanałów, pozycje przełączników |
| `PobieranieMapTest` | 6 | diagnoza błędów pobierania |
| `TasmaKursuTest` | 5 | litery stron świata |

**Bez ani jednego testu — 766 linii:**

| Moduł | Linii | Dlaczego to boli |
|---|---|---|
| [Wspolrzedne.kt](app/cockpit/src/main/java/pl/dron15/cockpit/domain/Wspolrzedne.kt) | 274 | UTM/MGRS/DMS liczone ręcznie z szeregów USGS; zła cyfra w MGRS to zły meldunek pozycji |
| [TransferMisji.kt](app/cockpit/src/main/java/pl/dron15/cockpit/net/mavlink/TransferMisji.kt) | 221 | protokół wysyłki trasy **do maszyny** |
| [Misja.kt](app/cockpit/src/main/java/pl/dron15/cockpit/domain/Misja.kt) | 213 | model trasy — dokładnie to, co psuje B1 |
| [MagazynMisji.kt](app/cockpit/src/main/java/pl/dron15/cockpit/domain/MagazynMisji.kt) | 58 | zapis i odczyt `.plan` |

**Test, którego brak zabolał najbardziej:** B1 to trzy linijki w `MisjaTest` —
dołóż dwa punkty, podnieś wysokość pierwszego, sprawdź, że nadal są dwa.

Sprawdziłem `Wspolrzedne` doraźnie, niezależną implementacją UTM napisaną od zera
z Snydera (USGS PP 1395) — **wszystkie pięć punktów kontrolnych zgodne co do metra**,
a `MGRS → lat/lon → MGRS` wraca z błędem 0,3 m:

| Wejście | `Wspolrzedne.mgrs` | Referencja |
|---|---|---|
| 52,123 N 20,12393 E | `34U DC 40023 75081` | `34U DC 40023 75081` |
| 52,2297 N 21,0122 E | `34U EC 00833 86587` | `34U EC 00833 86587` |
| 0 N 0 E | `31N AA 66021 00000` | `31N AA 66021 00000` (podręcznikowe 166 021 m) |
| 33,8688 S 151,2093 E | `56H LH 34369 50948` | `56H LH 34369 50948` |
| 64,1466 N 21,9426 W | `27W VM 54138 13690` | `27W VM 54138 13690` |

Moduł jest poprawny. Nic go jednak nie broni przed następną zmianą.

---

## 7. Co jest zrobione dobrze

Żeby audyt nie był listą samych zarzutów — to sprawdziłem i to trzyma:

- **Dekodowanie MAVLink.** Offsety wszystkich czternastu obsługiwanych komunikatów
  zgadzają się z dialektem co do bajtu. Ręcznie pisany parser bez biblioteki — i bez błędu.
- **Odtwarzacz wideo.** Cała praca libVLC zepchnięta na własny wątek, ponowienia
  z narastającym odczekaniem 1→2→4→8→15 s, licznik zerowany na `Playing`,
  `zapewnijOdtwarzanie()` nie zrywa działającego strumienia przy powrocie z innej zakładki.
  Wzorcowe, z opisem *dlaczego* w komentarzu.
- **Dekodowanie kafli Terrarium** `(R·256 + G + B/256) − 32768` — poprawne, przetestowane.
- **Ciężkie liczenie terenu** (cieniowanie, warstwice, profil) siedzi w `remember(klucze)`
  i nie przelicza się przy każdym przerysowaniu.
- **Zero wczesnych `return@` z lambd kompozycyjnych** — awaria `Stack.pop` z poprzedniej
  tury nie ma nawrotu; sprawdzone w całym drzewie `ui/`.
- **`FLAG_KEEP_SCREEN_ON`** ustawione, łącza żyją na `lifecycleScope` i nie padają przy
  przejściu w tło — po przełączeniu na SIYI FPV telemetria leci dalej.
- **Diagnostyka pobierania kafli** tłumaczy `CertificateNotYetValidException` na
  „zegar aparatury pokazuje…" zamiast wyrzucać wyjątek pilotowi.

---

## 8. Kolejność napraw

| # | Co | Koszt | Dlaczego w tej kolejności |
|---|---|---|---|
| 1 | **B2** — poprawić `SERVO*_FUNCTION` w `preflight_rules.json` na 36/33/34/35 | 4 linie | jedyne znalezisko, które aktywnie popycha pilota ku wypadkowi |
| 2 | **B1 + B3 + B4** — `rememberUpdatedState` we wszystkich sześciu miejscach gestu | ~20 linii | ciche kasowanie danych trasy |
| 3 | **testy** `MisjaTest`, `WspolrzedneTest`, `MagazynMisjiTest` | ~150 linii | inaczej pkt 2 wróci przy następnej zmianie |
| 4 | **S3** — reguły na `BATT_LOW_MAH` i `BATT_CRT_MAH` | 6 linii JSON | jedyna realna ochrona pakietu przy martwym pomiarze napięcia |
| 5 | **S4** — rozstrzygnąć `RTL_ALT` i uzgodnić z checklistą | decyzja | fałszywa blokada uczy ignorowania blokad |
| 6 | **S5** — przenieść detektor spadku satelitów do `SilnikStanu` | ~30 linii | detektor, który zależy od otwartej zakładki, nie jest detektorem |
| 7 | **W1** — ABI + R8 | 2 linie po sprawdzeniu ABI na sprzęcie | 96 MB → ok. 50 MB |
| 8 | **S6** — filtrowanie `sysid` | ~10 linii | zanim w podsieci pojawi się drugi GCS |
| 9 | **U1, U2, U3** — krycie panelu, wielokropki, ujednolicenie długości trasy | drobne | czytelność w słońcu |
| 10 | **S1** — profil pakietu zamiast zaszytych progów 6S | ~50 linii | **zanim** pakiet 8S trafi na maszynę, nie po |

---

## 9. Metoda i ograniczenia

**Czym sprawdzano:** przegląd całego drzewa `app/cockpit`, uruchomienie 105 testów,
sesja na emulatorze z otwartym `logcat`, zrzuty ekranu przy każdym potwierdzonym objawie,
niezależna implementacja UTM w Pythonie do kontroli współrzędnych, rozbiór APK po składnikach.

**Czego **nie** sprawdzono i dlaczego:**

- **Zachowania na prawdziwym MK32.** Wszystko poza analizą kodu robione na emulatorze
  x86_64. Liczby wydajnościowe (W2) i cele dotykowe poniżej 64 dp wymagają sprzętu —
  to samo notuje `TODO.md` 5a.4.
- **Rozmowy z prawdziwym sprzętem.** Komendy SIYI nigdy nie dotarły do głowicy,
  a wysyłka misji nigdy do kontrolera lotu (`TODO.md` 5a.1 i 5a.2). Kod protokołów
  przejrzałem, ale **poprawność na drucie jest niepotwierdzona** i nie mogę o niej
  nic orzec w tym audycie.
- **`DRON 15 Telefon.dc.html`** — makieta na telefon nadal bez pokrycia (`TODO.md` 5a.10).

**Rozbieżność do zanotowania:** `dok/SRODOWISKO_TESTOWE.md` opisuje ekran testowy jako
1920 × 1200 przy 320 dpi, a uruchomione AVD melduje **1280 × 800 @ 320**. Zrzuty w tym
audycie mają 1280 × 800. Dokument albo AVD wymaga poprawienia — inaczej następny audyt
znowu porówna dwie różne rzeczy.
