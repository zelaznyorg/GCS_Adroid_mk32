# Pierwszy test na aparaturze — 2026-08-25

Pierwsze uruchomienie aplikacji na **prawdziwym MK32**. Do tej pory wszystko, co widzieliśmy,
pochodziło z emulatora ustawionego na 1920 × 1200. Pomiar to obalił.

Urządzenie: `MK32`, `msm8953_64`, serial `8756ccce`, Android 9 (API 28), 3,6 GB RAM,
`arm64-v8a`. Zrzuty: `zrzuty/mk32_realny_*.png`, `zrzuty/mk32_gestosc240.png`.

---

## 1. Co zadziałało

| Rzecz | Wynik |
|---|---|
| Instalacja APK (149 MB przez USB-C) | ✅ 12 s, `Success` |
| Start aplikacji | ✅ bez awarii, `mResumedActivity: pl.dron15.cockpit/.MainActivity` |
| Telemetria MAVLink z symulatora przez WiFi | ✅ **71–76 Hz**, tryb, uzbrojenie, napięcie, satelity, wysokość, prędkość, ślad na mapie |
| Baner „BRAK OBRAZU Z KAMERY" | ✅ pojawił się poprawnie — kamery nie ma w tej sieci |
| Uprawnienie do karty | ⚠ pyta przy pierwszym starcie, dialog **zasłania kokpit** do czasu odpowiedzi |
| `AndroidRuntime:E` | ✅ pusto — nic się nie wywróciło |

**Telemetria przeszła całą drogę na docelowym sprzęcie.** To zamyka część zadania 5.1
z [TODO.md](../TODO.md): łącze MAVLink działa na aparaturze, choć źródłem był symulator,
a nie kontroler lotu.

---

## 2. ⛔ Ekran jest 2,25 raza mniejszy, niż zakładał projekt

```
wm size          ->  Physical size: 800x1280      (w poziomie 1280x800)
wm density       ->  Physical density: 320
dumpsys display  ->  app 1184 x 800, real 1280 x 800, rotation 1
```

| | piksele | **dp przy 320 dpi** | powierzchnia |
|---|---|---|---|
| emulator do 2026-08-25 | 1920 × 1200 | 960 × 600 | 100 % |
| **rzeczywisty MK32** | **1280 × 800** | **640 × 400** | **44 %** |
| obszar z paskiem systemowym | 1184 × 800 | 592 × 400 | 41 % |

Wszystkie zrzuty z 19 i 24 sierpnia (`m3_*.png`, `nowy_*.png`, `wariantD_*.png`) mają
1920 × 1200 px — pokazują układ, którego na aparaturze nie ma.

Emulator poprawiony na 1280 × 800 (`narzedzia/android/srodowisko.ps1` i `config.ini`).

---

## 3. ⛔ Skutek: menu widoków jest poza ekranem

Belka górna (`ui/Belka.kt`, linie 128–137) układa elementy tak:

```
[ tryb | uzbrojenie | bateria | SAT | ŁĄCZE | LOT ]  Spacer(weight 1f)
        [ WŁADZA ] [ MOTYW 28dp ] [ WARSTWY 30dp ] [ MENU WIDOKÓW 180dp ]
```

Lewa grupa zajmuje ok. 465 dp, prawa ok. 400 dp — razem **ponad 860 dp przy dostępnych 640**.
`Spacer` dostaje zero, a nadmiar wychodzi poza prawą krawędź i zostaje obcięty.

**Na aparaturze nie da się przełączyć widoku.** Aplikacja startuje na ekranie LOT i tam zostaje;
MISJA, KAMERA, PRZED LOTEM, DIAGNOSTYKA i RC są nieosiągalne. Razem z menu przepadają
przełącznik motywu i przełącznik warstw.

Sprawdzone: dotknięcie przy prawej krawędzi (x = 1270) nie otwiera niczego —
`zrzuty/mk32_proba_menu.png`.

### Dowód eksperymentem

Po `wm density 240` (czyli 853 dp szerokości) **wszystko się pojawia**:

| Element | 320 dpi (rzeczywiste) | 240 dpi (próba) |
|---|---|---|
| menu widoków „LOT ⌄" | **niewidoczne** | widoczne |
| przełącznik motywu, warstwy | **niewidoczne** | widoczne |
| znacznik władzy | „STERUJESZ" — ucięte | „STERUJESZ TY" |
| róża kursu | „GNS" — ucięte | „GNSS" |
| podpis wznoszenia | „WZNOS…" | „WZNOSZENIE" |
| **pozycja maszyny z MGRS** | **całe pole niewidoczne** | „52,12369 N · 34U DC 39937 75158 · GNSS 3D" |

Gęstość przywrócona do 320 (`wm density reset`).

---

## 4. Co z tym zrobić — trzy drogi

| Droga | Na czym polega | Koszt | Uwaga |
|---|---|---|---|
| **A. układ adaptacyjny** | belka i ekrany liczone od dostępnej szerokości: zwijanie pól, skróty podpisów, menu jako ikona przy ciasnocie | duży, ale jednorazowy | **jedyna droga, która nie zakłada rozdzielczości** |
| **B. gęstość systemu na 240** | `wm density 240` na aparaturze — 853 × 533 dp | minutowy | zmienia **wszystkie** aplikacje na aparaturze, w tym SIYI FPV i TX; do uzgodnienia z Tomem |
| **C. przeprojektowanie pod 640 × 400 dp** | makiety od nowa, z prawdziwym rozmiarem | duży | ma sens tylko razem z audytem, który i tak trwa |

Zalecenie: **A jako cel, B jako obejście na czas prac.** Wariant B pozwala testować
pozostałe ekrany już teraz, zanim układ zostanie przerobiony.

---

## 4a. Naprawa — wariant A, wykonana 2026-08-25

Wybrany wariant: **układ adaptacyjny**. Trzy zmiany, wszystkie sprawdzone na aparaturze.

### Belka górna — grupa prawa mierzy się pierwsza

`ui/Belka.kt` — zamiast `Spacer(weight(1f))`, który przy ciasnocie dostaje zero i pozwala
lewym polom wypchnąć sterowanie poza ekran, **wagę dostaje grupa lewa**. W Compose elementy
bez wagi mierzą się jako pierwsze, więc menu, przełączniki i znacznik władzy zawsze
się mieszczą, a pola stanu dostają resztę i są przycinane (`clipToBounds`).

Do tego postać zwięzła poniżej `Wymiary.BelkaProgZwiezly = 780 dp`:

| Element | pełna | zwięzła |
|---|---|---|
| uzbrojenie | ROZBROJONY | ROZBR. |
| SAT, ŁĄCZE | z podpisem | sama wartość z jednostką |
| czas lotu | jest | ustępuje pierwszy — jest też na DIAGNOSTYCE |
| znacznik stanu | BRAK KURSU GNSS | BEZ KURSU |
| władza | „władza STERUJESZ TY" | STERUJESZ |
| menu | PRZED LOTEM, DIAGNOSTYKA | PRZED, DIAG |

### Panele robocze — bez rezerwy na kolumnę przyrządów

`ui/Aplikacja.kt`, `PanelRoboczy`: odstępy z makiety rezerwowały `Krawedzie.PrzyKolumnie`
(≈ 220 dp) na kolumnę przyrządów, której panele PRZED LOTEM, RC i DIAGNOSTYKA w ogóle
nie rysują. Przy 640 dp zjadało to ponad jedną trzecią szerokości. Poniżej progu
odstęp schodzi do 14 dp, a górny z 64 na 42 dp.

Skutek na DIAGNOSTYCE: adresy mieszczą się w jednej linii, `EKF 0x033F` przestało być
ucięte do `0x0`, podpisy kafelków nie łamią się w połowie słowa.

### Wiersz ostrzeżeń na DIAGNOSTYCE

`width(520.dp)` na sam tekst nie zostawiał miejsca na szczegół — zastąpione wagami.

### Przy okazji: polskie znaki w regułach

`assets/preflight_rules.json` był pisany bez ogonków, więc pilot czytał „Mapowanie wyjsc
z Motor Testu". Poprawione; test `ChecklistaTest` dopasowany do nowego brzmienia reguły.

### Wynik

| | przed | po |
|---|---|---|
| menu widoków | **poza ekranem** | działa, sześć pozycji |
| przełączniki motywu i warstw | poza ekranem | widoczne |
| pozostałe pięć ekranów | **nieosiągalne** | osiągalne |
| DIAGNOSTYKA: szerokość panelu | ~65 % | pełna |
| testy jednostkowe | 45 | **45, zero błędów** |

Zrzuty po naprawie: `zrzuty/mk32_belka_adaptacyjna.png`, `mk32_menu_otwarte.png`,
`mk32b_diag.png`, `mk32c_przed.png`.

**Checklista wyłapała na aparaturze realny problem 1.1 z TODO:** mapowanie wyjść
`33 · 34 · 35 · 36` wobec oczekiwanych `34 · 36 · 33 · 35`, werdykt NIE STARTOWAĆ.

---

## 4b. Poprawka makiety 1.1 — wdrożona 2026-08-25

Projektant dosłał makietę przeliczoną na 640 × 400 dp (`Aplikacja mobilna dla DRON\`,
pliki z 09:14). Wdrożone w kolejności „najpierw to, co widać jako zepsute na aparaturze".

| # | Rzecz | Było | Jest |
|---|---|---|---|
| 1 | **taśma kursu** | środek na stałej `504 dp` z ramki 960 → sięgała 704 dp, ucięte „GNS" | środek i szerokość liczone z rzeczywistej szerokości ekranu; 300 dp wg makiety |
| 2 | **miniatura mapy** | 190 × 126, prawy dolny narożnik | **152 × 104, lewy dolny**, nad rzędem liczb |
| 3 | **wybór krawędzi** | przełącznik LEWA/PRAWA w warstwach | **usunięty** — prawa krawędź należy do kolumny kamery, nie ma czego wybierać |
| 4 | **rząd liczb** | kończył się na 220 dp rezerwy | pełna szerokość; **wrócił blok POZYCJA MASZYNY z MGRS**, „WZNOSZENIE" bez ucięcia |
| 5 | **komendy** | dwa klawisze, drugi podmieniany zależnie od trybu | **trzy stałe**: RTL, LĄDUJ, PRZERWIJ — nieaktywny z powodem „ręczny" |
| 6 | pozycja menu | 38 dp | 44 dp |

### Trzy kolizje wyłapane dopiero na sprzęcie

Żadnej nie dało się zobaczyć w makiecie — wyszły z uruchomienia:

- **stopka kolumny kamery** („POZIOM 1,0×") zwisa 42 dp pod skalą i lądowała na bloku
  współrzędnych. Kolumna rezerwuje teraz `RzadLiczb + PionStopkaZwis`.
- **uchwyt miniatury**: po zwężeniu do 152 dp „SCHOWAJ" ucinało się do „SCH".
  Podpis skrócony z „MAPA · OFFLINE" do „MAPA".
- **powód blokady** na klawiszu 44 dp: „nie w automacie" ucinało się do „nie w aut" → „ręczny".

### Odstępstwo od makiety — świadome

Projektant chciał trzech klawiszy komend zawsze widocznych i **ma rację**, ale z innego
powodu niż podał: klawisz, który zmienia znaczenie pod kciukiem zależnie od trybu lotu,
łamie pamięć ruchową. Zamiast chować PRZERWIJ poza automatem, jest **zablokowany
z podanym powodem** — zgodnie z zasadą z audytu „blokuj bez pokrycia, nie chowaj".

### Stan

45 testów jednostkowych, zero błędów. Zrzut po komplecie zmian:
`zrzuty/mk32_lot_final.png` (1280 × 800, prosto z aparatury).

**Nie sprawdzone przy 640 dp:** ekrany MISJA i KAMERA (zakładki 48 dp, szuflada ustawień),
ścięcia naroży i tokeny `instr*` z §8 przekazania.

---

## 4c. ⛔ Prawdziwa przyczyna — aparatura kłamie o swojej gęstości (2026-08-25)

Tom, patrząc na żywy ekran: *„napisy, ikony, taśma kursu i wszystkie czcionki są o połowę
za duże, wszystko jest wielkie, nieproporcjonalne i przysłania ekran"*. Miał rację,
a przyczyna leży poniżej całej dotychczasowej dyskusji o makiecie.

```
panel 1280 × 800 px  ->  przekątna 1509 px
przy 7 calach (SIYI) ->  216 dpi RZECZYWISTE
Android melduje      ->  320  (dumpsys: 317,5 × 318,7 dpi)
```

| Gęstość | 1 dp fizycznie | Ekran w dp |
|---|---|---|
| **320 — deklarowana przez MK32** | **0,236 mm** | 640 × 400 |
| wzorzec Androida | 0,159 mm | — |
| **213 — wynikająca z panelu** | **0,157 mm** | **962 × 601** |

**Wszystko było rysowane 1,48 raza za duże.** Nie dlatego, że ktoś źle dobrał rozmiary,
tylko dlatego, że aparatura melduje gęstość, której nie ma. To wyjaśnia jednocześnie,
czemu ekran wydawał się ciasny: przy zawyżonej gęstości kadr kurczy się do 640 dp.

### Naprawa — własna gęstość, bez ruszania systemu

`MainActivity` liczy gęstość z przekątnej panelu i owija drzewo Compose
`CompositionLocalProvider(LocalDensity provides Density(...))`. Systemowej gęstości
**nie zmieniamy** — `wm density` dotknęłoby też SIYI FPV i SIYI TX.

Przekątną da się nadpisać przy uruchomieniu: `-e cale 7.0`.

### Konsekwencja: pierwotna makieta 960 × 600 była poprawna

Po naprawie kadr ma **962 × 601 dp** — czyli dokładnie tyle, ile zakładała makieta M3
sprzed poprawki. **Poprawka 1.1 na 640 × 400 leczyła objaw**, nie przyczynę: przeliczała
projekt pod zawyżoną gęstość, zamiast ją zakwestionować.

Co z tego zostaje w kodzie:

| Z poprawki 1.1 | Los |
|---|---|
| miniatura w lewym dolnym narożniku | ✅ zostaje — prawa krawędź naprawdę należy do kolumny kamery |
| usunięty wybór krawędzi | ✅ zostaje — z tego samego powodu |
| trzy stałe klawisze komend | ✅ zostaje — klawisz zmieniający znaczenie łamie pamięć ruchową |
| rząd liczb na pełnej szerokości | ✅ zostaje |
| taśma 300 dp, miniatura 152 × 104 | ↩ **cofnięte do 400 dp i 190 × 126** — przy 962 dp wymiary z M3 są właściwe |
| belka zwięzła poniżej 780 dp | ✅ zostaje, ale **nie załącza się** na aparaturze — chroni węższe ekrany |

Praca nad układem adaptacyjnym nie poszła na marne: to ona sprawia, że aplikacja przeżyje
zarówno 640, jak i 962 dp — a przy okazji pokazała, że 640 dp było wartością **zastaną,
nie zadaną**.

---

## 4d. Mapy na aparaturze — sprawdzone 2026-08-26

Pierwsze uruchomienie map, terenu i widoku przestrzennego na sprzęcie. **Wszystko działa**,
a przy okazji wyszły dwie usterki, których nie dało się zobaczyć na emulatorze.

### Kafelki: 674 pliki, jedno archiwum zamiast 674 wysyłek

`adb push kafelki/` na katalogu z 671 drobnymi plikami **przewracał się po 9,5 minuty**
z `libc++abi: terminating due to uncaught exception of type std::bad_alloc` i nie zostawiał
na karcie **ani jednego pliku**. Narzut na plik przy tej liczbie przewraca samo `adb`.

Aparatura ma `/system/bin/tar`, więc właściwa droga to jedno archiwum:

```
tar -cf dron15_mapy.tar kafelki teren
adb push dron15_mapy.tar /sdcard/dron15_mapy.tar
adb shell "cd /sdcard/dron15 && tar -xf /sdcard/dron15_mapy.tar && rm /sdcard/dron15_mapy.tar"
```

**0,3 s zamiast 9,5 minuty i awarii.** 5,6 MB, 674 pliki na miejscu.

> ⚠ Przy Git Bash w Windows ścieżki Androida trzeba podawać z `MSYS_NO_PATHCONV=1`
> albo z PowerShella — inaczej `/sdcard/...` zamienia się w `C:/Program Files/Git/sdcard/...`
> i `adb` melduje `remote secure_mkdirs failed`, choć wina leży po stronie powłoki.
> Tak samo `adb exec-out screencap -p > plik.png` w PowerShellu **psuje binaria** (dokłada BOM);
> zrzut robić na urządzeniu i ściągać przez `adb pull`.

### Co potwierdzone wzrokiem

| Rzecz | Stan |
|---|---|
| Podkład hybrydowy na LOT i MISJA | ✅ zdjęcie, drogi i nazwy (widoczna „Jasionna") |
| Ślad maszyny, znacznik domu, trasa | ✅ |
| Chipy podkładów | ✅ `MAPA` i `NOC` **wyszarzone**, bo nie mają kafelków — dokładnie jak w `MAPY.md` |
| Prześwit przy punktach | ✅ `30 m` / `+28 agl`, pomarańczowy poniżej progu 30 m |
| Profil trasy | ✅ `554 m · min. prześwit +28 m · teren 81–83 m` |
| Widok 3D | ✅ siatka terenu pokryta zdjęciem, trasa, maszty do gruntu |
| Wysyłka trasy | ✅ `WYŚLIJ` odblokowany dopiero z punktami, na przytrzymanie 1,2 s |

Zrzuty: `zrzuty/mapy_mk32_lot.png`, `mapy_mk32_misja.png`, `mapy_mk32_3d.png`.

### ⛔ Usterka 1 — wartownik wieku telemetrii trafiał na belkę

Bez ani jednego heartbeatu belka pokazywała
**`ŁĄCZE 34028234663852886000000000000000000000 s`**, a baner to samo w zdaniu.
To `Float.MAX_VALUE` z `StanMaszyny.wiekTelemetriiS()` — wartownik znaczący „nigdy",
wpuszczony wprost do `format`.

W domenie wartownik jest poprawny (`telemetriaZywa` ma dzięki niemu wyjść fałszem), więc
naprawa poszła w prezentację: `telemetriaByla` i `opisCiszy()` w `StanMaszyny`, a wszystkie
trzy miejsca formatujące (belka, baner, diagnostyka) pytają najpierw o nie.

**Rozróżnienie jest operacyjne, nie kosmetyczne.** „Nigdy" znaczy, że łącze nie stanęło ani
razu — szukaj kabla, portu, zasilania air unitu. „12 s" znaczy, że stało i padło — szukaj
zasięgu. Belka mówi teraz `ŁĄCZE —`, a baner *„nie przyszedł ani jeden heartbeat —
sprawdź air unit i port"*.

### ⛔ Usterka 2 — podziałka taśmy kursu przekreślała litery stron świata

Na ekranie czytało się **`É` zamiast `E` i `$` zamiast `S`**. Kreska co 30° biegnie przez
**całą** wysokość taśmy, a litery leżą przy jej górnej krawędzi — więc N, E, S i W
(wszystkie wielokrotności 30°) dostawały linię dokładnie przez środek znaku.

Naprawa: **gdzie stoi litera, tam ona jest podziałką.** Warunek widoczności litery i warunek
pominięcia kreski to ta sama funkcja `literaZamiastKreski()`, więc przy krawędzi taśmy,
gdzie litery już nie ma, kreska zostaje i w podziałce nie robi się dziura.

Obie usterki mają testy (`ProtokolyTest`, `TasmaKursuTest`) — łącznie **94 testy, zero błędów**
(86 przed dzisiaj, 8 dołożonych: 3 na wartownika i 5 na podziałkę).

### ⚠ Kafelki pokrywają rejon próbny, nie miejsce lotów

`kafelki/manifest.json` mówi `52.1234567 / 20.1234567`, promień 1,5 km — to **dokładnie
`LAT0`/`LON0` z `narzedzia/symulator_telemetrii.py`**. Do sprawdzenia aplikacji to jest
właściwy wybór (mapa i symulator pokazują to samo miejsce), ale **przed lotem trzeba pobrać
rejon rzeczywisty**, bo poza tym kwadratem mapa będzie pusta.

---

## 4e. ⛔ Zegar aparatury stoi na 2023 — HTTPS nie działa wcale (2026-08-26)

Sprawdzenie **pobierania kafelków z sieci**, dołożonego tego samego dnia. Warstwy `MAPA`
i `NOC` przestały być wyszarzone, aparatura ma internet po Wi-Fi (`192.168.1.83`, ping do
`8.8.8.8` w 28 ms) — a mimo to **nie ściągnął się ani jeden kafelek** i mapa pokazywała
pustą siatkę bez słowa wyjaśnienia.

W logu:

```
CertificateNotYetValidException: Certificate not valid until Wed Jul 16 2025
(compared to Mon Oct 02 23:26:21 GMT+08:00 2023)
```

| | |
|---|---|
| zegar aparatury | **Mon Oct 2 23:27:20 CST 2023** |
| strefa | `Asia/Shanghai` (fabryczna) |
| `auto_time` | **0** — czas automatyczny wyłączony |
| `ntp_server` | `null` |
| zegar komputera | śr. sie 26 09:29 2026 |

Potwierdzone niezależnie od aplikacji, `curl` z samej aparatury:

| Próba | Wynik |
|---|---|
| `https://tile.openstreetmap.org/...` | **`kod=000`** — połączenie nie doszło |
| `http://s3.amazonaws.com/...` | `kod=403`, 243 bajty — **serwer odpowiedział** |

Czyli **sieć jest sprawna, a psuje ją wyłącznie zegar**: certyfikaty wystawione po 2023 roku
są dla aparatury „jeszcze nieważne".

### To nie jest sprawa map

Zły zegar zabiera każde połączenie HTTPS z aparatury oraz przestawia daty w logach
aplikacji i w nazwach zapisywanych plików. **Nie zmieniałem tego ustawienia sam** — dotyczy
całego Androida, więc także SIYI FPV, SIYI TX i wszystkiego, co ta aparatura zapisze.
Naprawa: *Ustawienia → System → Data i godzina → Automatyczna data i godzina* + strefa
`Europe/Warsaw`, albo `adb shell settings put global auto_time 1`.

### ⛔ Usterka aplikacji: rozpoznawalna awaria pokazana jako nic

Kokpit **obiecywał więcej, niż umiał**: `maPodklad()` zwracał prawdę już przy samej włączonej
sieci, więc podkład wyglądał na dostępny, komunikat o braku się nie pokazywał, a operator
dostawał pustą siatkę i żadnej wskazówki. Tak samo model terenu — `maDane` mówiło „tak"
i kończyło się wiecznym „wczytuję teren…".

Naprawione: `zdiagnozujPobieranie()` czyta **łańcuch przyczyn** wyjątku (przyczyna leży dwa
poziomy pod `SSLHandshakeException`) i odróżnia trzy przypadki, które z pustego ekranu
wyglądają tak samo — **zły zegar**, **brak sieci**, **milczący serwer**. Mapa i profil terenu
pokazują to zdanie zamiast milczeć. Certyfikatów **nie obchodzimy** — to znaczyłoby przyjęcie
dowolnego cudzego serwera za prawdziwy.

Zrzut: `zrzuty/mapy2_zegar.png`. Testy: `PobieranieMapTest.kt` (6). Razem **105 testów, zero błędów**.

### Przy okazji sprawdzone

| Rzecz | Stan |
|---|---|
| Drabina zasięgu `− 400 m +` | ✅ dwa naciśnięcia `−`: 400 m → 600 m → 1 km, podziałka nadąża |
| Podkład z karty (HYBRYDA) | ✅ rysuje się bez sieci, komunikat o usterce słusznie znika |
| Tryb 3D po restarcie | ✅ zapamiętany |

⚠ **Do poprawy przy okazji:** podpowiedź `DOTKNIJ MAPĘ, ŻEBY DOŁOŻYĆ PUNKT` jest pisana
barwą wygaszoną i na jasnym zdjęciu lotniczym staje się praktycznie niewidoczna.

---

## 4f. ⛔ Zwłoka interfejsu — zmierzona i w większości usunięta (2026-08-26)

Zgłoszenie Toma: *„ciężko się przełącza przyciski, jest duża zwłoka na ekranie"*.
Zmierzone `dumpsys gfxinfo` i `Choreographer`, nie oceniane na oko.

### Co się działo

| Objaw | Pomiar |
|---|---|
| **Każde wejście na ekran LOT zamrażało kokpit** | `Skipped 149 frames` ≈ **2,5 s** |
| Zimny start | dwa zamrożenia, `44` + `143` klatki ≈ **3,1 s** |
| Klatka w spoczynku (LOT) | p50 **38 ms**, 98 % klatek po terminie |

Rozstrzygające było porównanie: **RC ↔ DIAGNOSTYKA — zero zamrożeń, LOT ↔ DIAGNOSTYKA —
149 klatek.** Różnicą między tymi ekranami jest obraz z kamery.

### Przyczyna 1 — libVLC na wątku głównym

`AndroidView.factory` wykonuje się **w kompozycji, czyli na wątku głównym**, a siedziało
w nim uruchomienie strumienia RTSP. Przy nieosiągalnej kamerze `play()` blokował na sekundy.
Do tego pętla ponawiania wołała `stop()` + `play()` **natychmiast i bez końca**.

**W locie to jest wada bezpieczeństwa, nie wygody.** Gdy łącze z głowicą pada w powietrzu —
a wiadomo z `CLAUDE.md` poz. 28, że potrafi — kokpit zamierałby cyklicznie na ponad dwie
sekundy, w tym przyrządy i klawisze komend.

Naprawa: wszystkie polecenia do libVLC idą na własny wątek, ponawianie dostało **narastające
odczekanie 1 → 2 → 4 → 8 → 15 s**, a wątek główny co najwyżej podpina widok.

### Przyczyna 2 — strumień budowany od zera przy każdym powrocie

`factory` wołało `graj()` bezwarunkowo, więc powrót z MISJI **zrywał działający strumień
i stawiał połączenie RTSP na nowo** — kilka sekund czarnego kadru przy sprawnej kamerze.
Teraz widok pyta `zapewnijOdtwarzanie()`, a zejście z ekranu tylko odpina obraz,
nie zatrzymując go.

### Hipoteza, która okazała się fałszywa

Podejrzewałem konstruktor `LibVLC` (ładuje biblioteki natywne) o zamrożenie przy starcie
i przeniosłem go poza wątek główny. Pomiar to obalił: **buduje się w 96 ms.** Zmiana zostaje,
bo jest słuszna, ale przyczyną nie była. Właściwą pokazała oś czasu: **pierwsza kompozycja
całego drzewa trwa ok. 1,5 s** — to koszt startu Compose na tym procesorze.

### Wynik

| Miara | Przed | Po |
|---|---|---|
| **Przełączanie ekranów** | zamrożenie **2,5 s za każdym razem** | **zero zamrożeń** |
| Zimny start | `44` + `143` klatki | **jedno, `79` klatek** |
| Klatka na LOT (release) | p50 32 ms | **p50 26 ms** |

Strażnik regresji: podpięcie obrazu mierzy się samo i **melduje w dzienniku**, gdy przekroczy
100 ms na wątku głównym.

### ⚠ Co zostało: całe drzewo przelicza się przy każdej paczce telemetrii

`StanMaszyny` wędruje w dół jako jedna wartość, więc **każda zmiana czegokolwiek unieważnia
wszystko**. W spoczynku daje to ok. 8 przeliczeń na sekundę po **26–28 ms** — nadal powyżej
budżetu 16,7 ms, na każdym ekranie z osobna (nawet DIAGNOSTYKA, czyli zwykła lista).

Rozkład czasu klatki (`framestats`, release, DIAGNOSTYKA): **pomiar/układ 6,8 ms + GPU 7,9 ms**,
samo rysowanie 0,2 ms. Czyli koszt siedzi w kompozycji i w składaniu warstw, nie w treści.

To jest przebudowa, nie poprawka — dlatego osobne zadanie 5.10, nie zrobione tutaj.

### ⚠ Na aparaturze warto trzymać wariant `release`

Wszystkie dotychczasowe wgrania to były **kompilacje `debug`**: flaga `DEBUGGABLE`, brak R8,
biblioteki x86_64 pod emulator. Kosztuje to ok. **15–20 % czasu klatki** i **55 MB** objętości
(155 MB wobec 101 MB). Projekt nie ma jednak konfiguracji podpisu, więc `assembleRelease`
daje APK niepodpisany. Do testów podpisałem go ręcznie kluczem debug:

```bash
zipalign -p -f 4 cockpit-release-unsigned.apk wyrownany.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out cockpit-release.apk wyrownany.apk
```

**Na aparaturze stoi teraz ten właśnie APK.** Do rozstrzygnięcia (zadanie 5.11), czy
projekt ma dostać własny klucz — patrz TODO.

---

## 4g. Audyt ergonomii wszystkich zakładek (2026-08-26)

Zgłoszenie Toma: *„przyciski RTL, LĄDUJ i ręczny są za małe, nie mieszczą się pod palcem"*
oraz *„przejść przez pozostałe zakładki i sprawdzić, gdzie jest kolizja interfejsu"*.
Przejrzane po kolei na żywej aparaturze: LOT, MISJA, KAMERA, PRZED LOTEM, RC, DIAGNOSTYKA.

### Miara, którą się posłużyłem

Na tej aparaturze **1 dp = 0,157 mm** (po naprawie gęstości z §4c), więc rozmiary dają się
przeliczyć na milimetry i porównać z palcem, a nie z wyczuciem:

| | dp | mm | ocena |
|---|---|---|---|
| opuszek palca | — | **10–14** | miara odniesienia |
| minimum Androida | 48 | 7,6 | dolna granica |
| **`Wymiary.CelDotyku`** — token **już zadeklarowany w projekcie** | **64** | **10,0** | *nie używał go nikt* |
| klawisze komend przed poprawką | 44 × 40 | 6,9 × 6,3 | ⛔ |
| chipy mapy przed poprawką | ~76 × 28 | ~12 × **4,4** | ⛔ |
| klawisze zasięgu przed poprawką | 44 × 30 | 6,9 × **4,7** | ⛔ |

Najciekawsze w tym audycie: **projekt od początku deklarował `CelDotyku = 64.dp` i nie
używał tego tokenu ani razu.** Zamiar był zapisany, tylko nigdy nie wszedł do kodu.

### Co zostało zmienione

| Element | Przed | Po |
|---|---|---|
| **RTL / LĄDUJ / PRZERWIJ** | 44 × 40 dp | **72 × 68 dp** (11,3 × 10,7 mm), ikona 22 dp, napis 13 sp |
| Chipy mapy (podkłady, 2D/3D, warstwice…) | 28–34 dp wysokości | **64 dp** — [Chip] podnosi każdą podaną wartość, więc żadne wywołanie nie zejdzie niżej |
| Klawisze zasięgu `+`/`−` | 44 × 30 dp | **64 × 64 dp** |
| Zakładki KAMERY | 48 dp wys., „AI" ~40 dp szer. | **64 dp** w obu wymiarach |
| Pole „obsługa" w tabeli RC | 32 dp wys. | **44 dp** |

### ⛔ Kolizja 1 — RC: dwa objawy, jedna przyczyna

Ekran RC pokazywał **dwa kanały z szesnastu** przy pustej połowie panelu, a obok tekst
łamany **po jednym znaku na wiersz**. Wyglądało to na dwie niezależne usterki.

Przyczyna jest jedna. W pasie trybów objaśnienie stało w jednym rzędzie z chipami trybów,
z `weight(1f)`. Chipy nie mają wagi, więc mierzą się **pierwsze** i zabierały całą szerokość
— na tekst zostawało ok. 26 dp, czyli jeden znak. Napis rozciągał się na ~24 wiersze,
**cały pas rósł do ok. 310 dp wysokości**, a że lista kanałów dzieli z nim wysokość przez
`weight(1f)`, dostawała 100 dp zamiast 370.

To ten sam błąd kolejności pomiaru, który w §4a wyrzucił menu poza ekran. Warto go znać:
**w `Row` dzieci bez wagi mierzą się przed ważonymi i mogą im zostawić zero.**

Naprawa: objaśnienie w osobnym wierszu, gdzie ma pełną szerokość i nie konkuruje o pomiar.
Po niej widać **siedem kanałów** i tekst czyta się normalnie.

### ⛔ Kolizja 2 — panele robocze oddawały 30 % szerokości pod nic

`PanelRoboczy` (PRZED LOTEM, RC, DIAGNOSTYKA) rezerwował **68 dp z lewej i 220 dp z prawej**
na **kolumnę przyrządów, której te ekrany w ogóle nie rysują**. Panel miał 686 z 962 dp.

Skutkiem nie był sam zmarnowany kadr:
- w RC kolumny wiersza (78+150+300+150 dp) przestawały się mieścić, więc kolumna funkcji
  dostawała zero szerokości, a nagłówek „obsługa" był ucięty;
- w PRZED LOTEM długie opisy zawijały się do dwóch wierszy i **drugi wiersz wchodził
  na kolumnę wartości** („RTL 50 m, minimalne wznoszenie 5 m", „Datalink MK32…").

Po zdjęciu rezerwy obie kolizje zniknęły same — opisy mieszczą się w jednym wierszu.

### ⛔ Kolizja 3 — MISJA: panel wyszukiwania na stałe zasłaniał róg mapy

Panel WSPÓŁRZĘDNE / ADRES / POI zajmował **336 × 200 dp lewego górnego rogu mapy** i nie
dało się go schować. Tam leży teren, na którym planuje się trasę, i tam nie da się postawić
punktu. Szukanie jest czynnością okazjonalną, oglądanie mapy — ciągłą.

Jest teraz **zwinięty domyślnie** do jednego klawisza „SZUKAJ ▾".

Przy okazji: po powiększeniu chipów rząd pięciu podkładów urósł do 396 dp i **sięgnął pod
ten panel**, ucinając „HYBRYDA" do „RYDA". Chipy poszły w dwa rzędy po trzy (244 dp), które
mijają panel z zapasem.

### ⛔ Kolizja 4 — taśma kursu: znacznik domu na literze strony świata

Przy kursie ok. 350° domek z namiarem i litera „N" rysowały się w tym samym miejscu
20-dpowego pasa i żadnego z nich nie dało się odczytać. Namiar do domu jest przy kursie
wyłącznie z GNSS **jedyną nawigacją ratunkową**, więc to litera ustępuje — tak samo jak
wcześniej ustąpiła kreska podziałki (§4d).

### Co sprawdzone i czyste

| Ekran | Stan |
|---|---|
| **LOT** | ✅ klawisze pod palec, taśma czysta, miniatura z podkładem, rząd liczb pełny |
| **MISJA** | ✅ panel zwijany, chipy w dwóch rzędach, sterowniki pod palec |
| **KAMERA** | ✅ zakładki 64 dp; ⚠ reszty nie da się ocenić bez obrazu z ZR30 |
| **PRZED LOTEM** | ✅ kolizja opisu z wartością zniknęła, 10 pozycji naraz |
| **RC** | ✅ 7 kanałów, kolumna funkcji widoczna, objaśnienie czytelne |
| **DIAGNOSTYKA** | ✅ pełna szerokość, kafelki i dziennik bez kolizji |

### ⛔ Kolizja 5 — belka górna: klawisz wyboru ekranu miał 3,5 mm

Zgłoszenie Toma: *„dalej nie zawsze kliknięcie zadziała"* przy wyborze LOT / KAMERA.

Przyczyna była strukturalna, nie w obsłudze zdarzeń: **cała belka miała `32 dp = 5,0 mm`
wysokości**, więc wszystko w niej było za małe z definicji.

| Element belki | Przed | mm | Po |
|---|---|---|---|
| wysokość belki | 32 dp | 5,0 | **56 dp** |
| **klawisz wyboru ekranu** | **22 dp** | **3,5** | **52 × 104 dp** |
| klawisz motywu | 28 dp | 4,4 | **52 dp** |
| klawisz warstw | 30 dp | 4,7 | **52 dp** |
| pozycje rozwiniętej listy | 44 dp | 6,9 | **64 dp** |

**22 dp to jedna czwarta opuszka palca** — przy takim celu chybienie nie jest usterką, tylko
statystyką. Belka jest **nakładką na kadr**, nie zajmuje miejsca w układzie, więc jej
powiększenie kosztuje wyłącznie zasłonięcie 24 dp nieba u góry.

⚠ Klawisze belki mają 52 dp, a nie 64 jak reszta aplikacji. To świadome odstępstwo: pasek
stanu, który się głównie **czyta**, zabrałby przy 64 dp jedenaście procent wysokości ekranu.
52 dp = 8,2 mm, czyli powyżej minimum Androida (48 dp).

### Druga, cichsza przyczyna gubionych dotknięć

`pointerInput(opis, aktywny)` i `pointerInput(ekran, otwarte)` — **zmiana klucza przerywa
detektor gestów i buduje go od nowa**, gubiąc dotknięcie, które akurat trwa. A `otwarte`
zmienia się w reakcji na to samo dotknięcie, `aktywny` przy każdym otwarciu panelu warstw,
i całe drzewo przelicza się kilka razy na sekundę od telemetrii (§4f).

Klucz zmieniony na `Unit`, akcja podawana przez `rememberUpdatedState` — detektor żyje przez
całe życie klawisza i nie ma jak zgubić gestu.

### Sprawdzenie

Sześć dotknięć w **różne punkty** klawisza — cztery rogi i dwa razy środek. Menu przełącza
się przy każdym, więc po parzystej liczbie kończy zamknięte. Tak było: **wszystkie sześć
zadziałało**, łącznie z rogami. Zrzut: `zrzuty/erg_dotyk.png`.

### ⚠ Zostawione świadomie

- **Podpowiedź `DOTKNIJ MAPĘ, ŻEBY DOŁOŻYĆ PUNKT`** jest pisana barwą wygaszoną i na jasnym
  zdjęciu lotniczym robi się prawie niewidoczna. Wymaga obwódki albo podkładki — zadanie 5.12.
- **Ostatni wiersz dziennika na DIAGNOSTYCE** ucina się w połowie znaku zamiast na granicy
  wiersza. Kosmetyka.
- **Ekran KAMERA bez obrazu** to w praktyce pusty kadr — jego ergonomii nie da się uczciwie
  ocenić, dopóki ZR30 nie nada (zadanie 5.2).

Zrzuty: `zrzuty/erg_lot.png`, `erg_misja.png`, `erg_kamera.png`, `erg_przed.png`,
`erg_rc.png`, `erg_diag.png`.

---

## 5. Czego ten test nadal nie sprawdził

- **obrazu z ZR30** — aparatura nie była w sieci pokładowej (`192.168.144.12` nie odpowiadał,
  air unit nie podniesiony)
- **telemetrii z prawdziwego kontrolera lotu** — źródłem był symulator na laptopie
- **SDK aparatury po `/dev/ttyHS0`** — port istnieje i ma prawa `crwxrwxrwx`,
  czyli aplikacja może go otworzyć bez roota; nie próbowaliśmy jeszcze nic wysłać
- **map w rejonie rzeczywistym** — kafelki pokrywają wyłącznie kwadrat próbny (§4d)
- **ekranów KAMERA, PRZED LOTEM, RC i DIAGNOSTYKA** przy poprawionej gęstości —
  LOT i MISJA sprawdzone 2026-08-26, reszta nie
