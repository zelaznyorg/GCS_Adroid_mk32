# Interfejs — system projektowy

**Wersja 2.1, 2026-08-19; wariant D wdrożony 2026-08-23.** 2.0 powstała po audycie
([AUDYT_UI.md](AUDYT_UI.md)). 2.1 to poprawka po **uwagach operatora**: było za dużo
informacji, kroje za duże, tafle nieprzezroczyste, a mapa nie miała podkładu.
Co się zmieniło — sekcja 8.

> ### ⚠ UKŁAD OBOWIĄZUJĄCY OD 2026-08-24: **[PRZEKAZANIE_M3.md](PRZEKAZANIE_M3.md)**
>
> Sekcje 3 i 9 tego dokumentu opisują **dwa poprzednie układy** i zostają jako materiał
> porównawczy. Aplikacja stoi na przekazaniu M3: wybór ekranu w belce zamiast pasa
> zakładek, przezroczysty okrąg położenia zamiast karty horyzontu, miniatura mapy na
> krawędzi spodu, jeden okrągły klawisz migawki zamiast pary FOTO + REC, planowanie misji
> na mapie, ścięte naroża.
>
> **Co z tego dokumentu obowiązuje dalej:** siedem zasad z §1, barwy i kroje z §2 (poza
> narożami i celami dotykowymi — patrz niżej), reguła wieku danych, przytrzymanie komend
> ruszających maszyną, brak gestów krawędziowych, kolejność ważności banerów oraz cała §7
> („czego nie robimy").

*(archiwum)* **Układ z sekcji 9 — wariant D** obowiązywał 2026-08-23 i 2026-08-24.
Sekcja 3 opisuje układ z paskiem zakładek zabierającym 72 dp szerokości — zastąpiony
2026-08-23 i zostawiony jako materiał porównawczy, bo wymiary stref i reguła „jeśli pilot
nie podejmie na tej podstawie decyzji w ciągu sekundy, to nie należy do HUD-u" obowiązują
dalej. **Przy czytaniu §3 pamiętać, że opisuje stan poprzedni.**

Dotyczy aplikacji na MK32 (samodzielnej i kompletnej) oraz klientów stacji GCS.
Jeden język, trzy układy.

---

## 0. Rozmiar, od którego wszystko zależy

MK32: **1920 × 1200 px, 320 dpi → 960 × 600 dp.** Po odjęciu paska zakładek zostaje
**876 × 600 dp**. To jest budżet. Każdy element trzeba w nim policzyć, a nie „dopasować
później" — przy celu dotykowym 64 dp w poziomie mieści się trzynaście przycisków.

Wszystkie wymiary w tym dokumencie są w **dp/sp**, nigdy w pikselach. Wersja 1.0 podawała
piksele i przez to rozjechała się z kodem dwukrotnie.

---

## 1. Siedem zasad

1. **Obraz jest tłem, nie kafelkiem.** Widok z kamery zajmuje całą powierzchnię, dane leżą
   na nim w półprzezroczystych taflach.
2. **Mapa i obraz zamieniają się miejscami.** Jedno dotknięcie miniatury przenosi ją na
   pełny ekran, a poprzednie tło idzie w miniaturę. Nigdy dwie połówki — zawsze jedno duże
   i jedno małe. Zamiana jest **wspólna dla ekranów LOT i MISJA**: to ta sama para, oglądana
   z dwóch stron.
3. **Trzy poziomy hierarchii i nic więcej na wierzchu.** *co widzę* (obraz) — *gdzie jestem*
   (mapa, kurs, wysokość) — *czy jest bezpiecznie* (pas stanu). Reszta chowa się za zakładkami.
   Surowe komunikaty z kontrolera lotu **nie należą do kokpitu** — należą do DIAGNOSTYKI.
4. **Kolor znaczy, nie zdobi.** Interfejs jest niemal monochromatyczny. Bursztyn wyłącznie
   przy ostrzeżeniu, czerwień wyłącznie przy blokadzie, cyan wyłącznie jako zaznaczenie.
   Uzbrojenie **nie jest** blokadą i nie ma prawa być czerwone.
5. **Nic nie jest nieodwracalne przez przypadek.** Komendy ruszające maszyną wymagają
   **przytrzymania** z widocznym paskiem postępu. Puszczenie przed końcem anuluje.
6. **Degradacja jest widoczna, nie ukryta.** Każda wartość zna swój wiek. Powyżej 2 s
   przygasa i dostaje znacznik („3 s"), powyżej 10 s zamienia się w kreski. Zamrożona liczba
   jest gorsza niż brak liczby.
7. **Ekran pokazuje, sprzęt wykonuje.** Funkcji, która siedzi na fizycznym przełączniku
   MK32, **nie dublujemy przyciskiem ekranowym** — pokazujemy tylko jej stan i to, gdzie
   jest. Ekran wykonuje to, czego sprzęt nie ma. Rozwinięcie: [RC_PRZYPISANIA.md](RC_PRZYPISANIA.md).

---

## 2. Zmienne

### Kolor

| Rola | Wartość | Użycie |
|---|---|---|
| tło | `#060809` | pod obrazem i mapą |
| tafla | `#04070A` @ **40 %** | panele na obrazie — obraz ma być przez nie widoczny |
| tafla mocna | `#04070A` @ 65 % | pas górny |
| tafla pełna | `#070A0C` @ 90 % | banery, dialogi — nieprzezroczyste |
| klawisz | biel @ 8 % / 24 % pod palcem | przyciski są szybą, nie płytką |
| linia | `#7E96A8` @ 20 % | krawędzie, znaczniki narożne |
| linia słaba | `#7E96A8` @ 10 % | podziały wewnątrz tafli |
| tekst | `#DFE7EC` | wartości |
| tekst drugi | `#8A9AA6` | etykiety, jednostki |
| tekst wygasły | `#59686F` | wartość starsza niż 2 s |
| akcent | `#35C7E8` | zaznaczenie, aktywna zakładka, ślad na mapie |
| dobrze | `#35D07A` | stan poprawny |
| ostrzeżenie | `#F5A623` | wymaga uwagi, lot możliwy |
| blokada | `#FF3B30` | nie startować / przerwij |

Zmiana wobec 1.0: akcent z niebieskiego `#4DA3FF` na **cyan `#35C7E8`**. Powód w audycie
§5 — niebieski pierwszy traci kontrast na ekranie w słońcu.

**Nigdy sam kolor.** Każdy stan ma też znak (`✔ ⚠ ⛔ ○ ●`) i słowo.

### Typografia

Jeden krój bezszeryfowy. **Cyfry o stałej szerokości obowiązkowo** — w Compose przez
`fontFeatureSettings = "tnum"`, w przeglądarce przez `font-variant-numeric: tabular-nums`.
Bez tego wartości skaczą w poziomie przy każdej zmianie.

| Poziom | Rozmiar | Użycie |
|---|---|---|
| ogromna | 26 sp | **jedna** wartość na ekran — wysokość |
| duża | 20 sp | trzy pozostałe odczyty HUD |
| średnia | 16 sp | wartości na ekranach roboczych |
| pas stanu | 15 sp | tryb, pakiet, GNSS, łącze, czas |
| baner | 18 sp, pogrubiony | ostrzeżenia krytyczne |
| tekst | 13 sp | listy, opisy |
| etykieta | 9 sp, wersaliki, rozstrzelenie 0,11 em | podpisy pod wartościami i pod ikonami |

Rozmiary z wersji 2.0 (34 / 26 / 20) były wzięte z ekranu biurkowego. Na aparaturze
trzymanej 40 cm od oczu liczba 34 sp to plakat: zajmuje miejsce, którego nie ma,
i nie czyta się ani o milisekundę szybciej.

### Kształt i odstęp

Skok 4 dp. **Naroża ścięte pod 45°, 6–20 dp zależnie od elementu** — znaczniki, klawisze,
panele, karty. Zamiast pełnych ramek wystarcza linia włosowa: ścięcie samo w sobie jest
znacznikiem, więc **znaczniki narożne z wersji 2.1 zniknęły** (robiły dwa rysunki w tym
samym miejscu). Wypełnienie tafli 12 dp.

Cele dotykowe **min. 64 dp** w każdym wymiarze, odstęp między nimi min. 8 dp.
Przyciski o skutkach nieodwracalnych mają **min. 12 dp odstępu** od wszystkiego innego
i inną formę (kontur w kolorze funkcji) — mają nie sąsiadować z niegroźnymi.

> **Trzy wyjątki od 64 dp, wprowadzone przekazaniem M3** ([PRZEKAZANIE_M3.md](PRZEKAZANIE_M3.md) §7),
> rozstrzygnięte przez Toma 2026-08-24:
>
> | Element | Wymiar | Dlaczego wolno |
> |---|---|---|
> | komendy RTL i LĄDUJ | 44 × 40 dp | chronione **przytrzymaniem 1200 ms** — chybienie niczego nie uruchamia, a kadr ma zostać pusty |
> | pozycja menu widoków | 38 dp | sześć pozycji po 64 dp to 384 dp, czyli dwie trzecie ekranu na wybór ekranu |
> | znacznik punktu trasy | 24 dp | wybiera punkt na mapie; pomyłka kosztuje jedno dotknięcie |
>
> Zakładki ekranu KAMERA celowo podniesiono do **48 dp** — to minimum Material 3
> i najniższa wartość, przy której zakładka nadal jest celem, a nie napisem.
>
> **Reguła nadrzędna zostaje bez zmian: wszystko, co rusza maszyną, ma przytrzymanie.**
> Wyjątek dotyczy rozmiaru, nie ochrony.

**Cień jest zabroniony jako wypukłość, dozwolony jako sygnał.** Jedyne użycie: **poświata
na klawiszu RTL** — element pierwotny musi się wyróżniać wśród pozostałych (§7 przekazania).
Nic innego cienia nie nosi.

---

## 3. Siatka ekranu MK32

```
 ┌───────────────────────────────────────────────────────────────┬──────┐
 │ ALTHOLD ●ROZBROJONY ▭24.1V ⛭18 ⌇73Hz ⏱0:14           40 dp   │      │
 ├───────────────────────────────────────────────────────────────┤      │
 │ 061 ┄┄┄┄┄┄┄┄┄ N ┄┄┄┄┄┄▲┄┄┄┄┄┄ NE ┄┄┄┄┄┄┄┄┄  GNSS      24 dp   │ ZAK- │
 ├───────────────────────────────────────────────────────────────┤ ŁADKI│
 │                                                               │ 72dp │
 │              OBRAZ albo MAPA — czysty, bez paneli             │      │
 │                       (baner tylko gdy trzeba)                │      │
 │                                                               │      │
 ├──────────────┬────────────────────────┬───────────────────────┤      │
 │ MINIATURA    │ WYS  DOM  PRĘD  WZN    │  RTL LĄDUJ  FOTO REC  │      │
 │ 240 × 136    │ (cztery liczby)        │  (piktogramy 76 dp)   │      │
 └──────────────┴────────────────────────┴───────────────────────┴──────┘
```

| Strefa | Wymiar | Zawartość |
|---|---|---|
| pas górny | 888 × 40 | tryb · uzbrojenie · napięcie · satelity · łącze · czas lotu |
| taśma kursu | 888 × 24 | kurs GNSS, litery świata, **zielony znacznik kierunku na dom** |
| obraz / mapa | reszta | czyste tło; baner tylko wtedy, gdy jest co powiedzieć |
| miniatura | 240 × 136 | to, co **nie jest** tłem; dotknięcie zamienia |
| odczyty | ok. 330 × 56 | wysokość, dystans do domu, prędkość, wznoszenie |
| dok akcji | 4 × 76 dp | RTL · LĄDUJ (albo PRZERWIJ) · FOTO · REC |
| zakładki | 72 × 600 | sześć widoków, piktogram + podpis 9 sp |

**Dok akcji jest w prawym dolnym rogu**, bo tam sięga kciuk przy trzymaniu aparatury
oburącz. Odczyty są po lewej, przy miniaturze — wzrok wraca w jedno miejsce.

**Czego na ekranie lotu nie ma i nie będzie:** prądu, zużycia mAh, procentu gazu, kątów
głowicy, stanu trzech łączy, listy komunikatów z FC. To wszystko żyje w zakładkach
DIAGNOSTYKA i KAMERA. Zasada: **jeśli pilot nie podejmie na tej podstawie decyzji
w ciągu najbliższej sekundy, to nie należy do HUD-u.**

**Żadnych gestów krawędziowych** — kciuki trzymają aparaturę. Jedyny gest to przeciągnięcie
**wewnątrz** krzyżaka głowicy.

## 4. Elementy

**Tafla.** Prostokąt z linią włosową i znacznikami narożnymi. Nagłówek: etykieta 11 sp
wersalikami. W środku wartości. Bez cieni, bez gradientów.

**Wartość z wiekiem.** Liczba + jednostka + etykieta. Gdy dane starsze niż 2 s: kolor
schodzi do „tekst wygasły" i obok pojawia się wiek. Powyżej 10 s zamiast liczby są kreski
`———`. Dotyczy **każdej** wartości pochodzącej z telemetrii, nie tylko pola „łącze".

**Pas stanu.** Zawsze widoczny, u góry, pola o stałych wagach (nie zawijają się i nie
uciekają poza ekran). Każde pole: wartość + etykieta pod spodem.

**Baner krytyczny.** Pełna szerokość obszaru obrazu, **tło nieprzezroczyste**, znak + tekst
+ szczegół. Widoczny tylko jeden, najważniejszy. Kolejność ważności ustala MK32 i rozsyła
gotową, żeby stacja pokazywała dokładnie to samo.

**Przycisk.** Kontur + wypełnienie 7 %. Trzy stany: zwykły, **wciśnięty** (wypełnienie
rośnie natychmiast — to jedyna informacja zwrotna, jaką pilot dostanie), **niedostępny**
(kontur przygaszony i **podpis z powodem**, np. „brak kursu GNSS", nigdy samo wyszarzenie).

**Przycisk z przytrzymaniem.** Pasek postępu wypełnia przycisk od lewej przez 800 ms
(1200 ms dla komend uzbrojonej maszyny). Puszczenie przed końcem anuluje i pasek znika.
Dotyczy: RTL, LĄDUJ, przerwania automatu, startu misji, przejęcia sterowania.

**Potwierdzenie komendy.** Po wysłaniu przycisk pokazuje `…` do czasu `COMMAND_ACK`.
Odpowiedź: `✔ przyjęta` (2 s) albo `⛔ odrzucona: <powód>` (5 s). Brak odpowiedzi przez
3 s to `⚠ bez potwierdzenia` — i to też jest informacja.

**Taśma kursu.** Zamiast róży — 24 dp zamiast 148 i czyta się jak w każdym HUD-zie.
Podziałka co 5°, litery świata, **zielony znacznik kierunku na dom**, po lewej bieżąca
wartość, po prawej podpis źródła `GNSS`. Gdy kursu nie ma, wartość gaśnie na `---`.
Podpis źródła jest częścią elementu: na tej maszynie kurs pochodzi z jednego źródła.

**Mapa.** Podkład z **kafelków rastrowych z karty TF** (`/sdcard/dron15/kafelki/{z}/{x}/{y}.png`,
układ XYZ jak w OSM) plus warstwa własna: ślad trasy, dom, maszyna jako trójkąt zwrócony
wg kursu, przerywana linia „maszyna → dom", podziałka. Kafelki przygotowuje się raz na
komputerze (`narzedzia/kafelki.py`) — domyślnie zdjęcia lotnicze, bo operatorowi mówią
więcej niż mapa drogowa. Brak kafelków nie blokuje niczego: zostaje siatka metryczna.

Świadomie **bez MapLibre** — instrukcja MK32 odradza obciążanie aparatury, a jeden rejon
lotów mieści się w kilkuset kafelkach.

**Miniatura.** To, co nie jest tłem. Dotknięcie zamienia. Podpis mówi, co zobaczysz po
dotknięciu (`MAPA`/`OBRAZ`), a nie co widać — bo to i tak widać.

**Pas władzy.** Dopóki steruje operator MK32, pas stanu wygląda normalnie i mówi
„STERUJESZ TY". Z chwilą przekazania **cały pas zmienia kolor na bursztynowy**, pokazuje
kto steruje i ile czasu zostało, a przycisk **ODBIERZ STEROWANIE** jest widoczny bez
wchodzenia w menu.

**Kamera osobno od lotu.** Na ekranie KAMERA stale widnieje podpis, kto nią rusza,
i przycisk **ODBIERZ KAMERĘ** — jedno dotknięcie, bez potwierdzania. Dwa zakresy władzy
mają dwa niezależne wskaźniki i dwa niezależne przyciski.

**Baner obcego nadawcy.** Gdy maszyna potwierdza komendę, której nie wydał ani operator,
ani stacja przez nasz filtr — **czerwony baner na całą szerokość**. Jedyny baner, który
nie znika sam.

**Krzyżak głowicy.** Kwadratowe pole; przeciągnięcie od środka zadaje **prędkość** obrotu
(`CMD 0x07`), puszczenie zatrzymuje (`0x07` z zerami — bez tego głowica ucieka). Dotknięcie
środka centruje. Obok: zoom, presety pitch (0° / −45° / −90°), tryb Lock/Follow/FPV.

**Wskaźnik kanałów RC.** Szesnaście pasków z pozycją drążka lub przełącznika, aktualizowanych
z `RC_CHANNELS`. Kanały z przypisaną funkcją mają jej nazwę. To jedyne miejsce, w którym widać,
że aparatura naprawdę steruje.

---

## 5. Ruch

Animacje wyłącznie funkcjonalne, do 150 ms: pojawienie się tafli, wypełnienie paska
przytrzymania, zamiana mapy z obrazem. **Żadnych animowanych liczb** — wartość ma się
zmienić od razu, bo interpolacja to kłamstwo o stanie maszyny.

Wyjątek: baner krytyczny dostaje jedno mrugnięcie, żeby złapać wzrok.

---

## 6. Sześć widoków

| Widok | Do czego | Stan |
|---|---|---|
| **LOT** | obraz jako tło, listwy przyrządów, róża, miniatura mapy, dok akcji | ✅ |
| **MISJA** | mapa jako tło, lista punktów, postęp, pauza i skok do punktu; miniatura obrazu | 🔶 mapa i podgląd trasy są, edytor w M4 |
| **KAMERA** | krzyżak głowicy, zoom, presety, foto, REC, wybór strumienia | ✅ |
| **PRZED LOTEM** | checklista licząca się z parametrów | ✅ |
| **RC** | **przypisania kanałów i przełączników**, żywe pozycje, wykrywanie konfliktów | ✅ |
| **DIAGNOSTYKA** | stan trzech łączy, częstotliwości, komunikaty z FC, klienci, historia władzy | ✅ |

USTAWIENIA (adresy, udostępnianie, progi) wchodzą razem z modułem udostępniania w M5;
do tego czasu adresy podaje się przy uruchomieniu (`-e host …`).

---

## 7. Czego nie robimy

- **sztucznego horyzontu na pół ekranu** — przy kamerze na stabilizowanej głowicy obraz
  i tak jest wypoziomowany; horyzont zajmuje miejsce i niczego nie dodaje. Przechylenie
  i pochylenie maszyny są dostępne w DIAGNOSTYCE
- **wykresów w kokpicie** — historia należy do widoku DIAGNOSTYKA
- **ikon bez podpisów** — w polu, pod stresem, ikona bez słowa jest zagadką
- **przycisków dublujących fizyczne przełączniki** — zasada 7
- **uzbrajania z ekranu** — zostaje na CH9 (`RC9_OPTION=153`), niezależnie od wygody
- **dźwięków dekoracyjnych** — sygnał dźwiękowy zarezerwowany dla blokad i utraty łącza

---

## 8. Co zmieniła wersja 2.1 — uwagi operatora

Pierwsza przebudowa naprawiła braki funkcjonalne, ale poszła za daleko w drugą stronę:
zamiast dwóch paneli danych zrobiły się cztery, a wszystko było za duże i nieprzezroczyste.
Uwagi z 2026-08-19 i odpowiedzi:

| Uwaga | Co zrobiono |
|---|---|
| „brakuje map" | podkład z kafelków rastrowych z karty + narzędzie `kafelki.py` do ich przygotowania; mapa działa offline, domyślnie na zdjęciach lotniczych |
| „przyciski są nieprzezroczyste" | tafle 40 %, klawisze 8 % bieli z konturem; pod palcem 24 % — obraz widać przez cały interfejs |
| „czcionki za duże" | ogromna 34 → 26 sp, duża 26 → 20, pas stanu 20 → 15, etykiety 11 → 9 |
| „część funkcji może być na piktogramach" | wszystkie czynności i zakładki na piktogramach rysowanych wektorowo (`ui/Ikony.kt`), podpis 9 sp zostaje jako podpowiedź |
| „za dużo informacji" | z ekranu lotu zdjęte: prąd, mAh, gaz, kąty głowicy, cztery diody łączy, komunikaty z FC, róża 148 dp. Zostały **cztery liczby** i sześć pól w pasie górnym |

Reguła, która z tego wynika i obowiązuje na przyszłość:

> **Jeśli pilot nie podejmie na tej podstawie decyzji w ciągu najbliższej sekundy,
> to nie należy do HUD-u.**

---

## 9. Wariant D — stacja indywidualna

> ### ✅ WDROŻONY 2026-08-23 — to jest stan aplikacji, nie projekt
>
> Do 2026-08-23 sekcja 9 była wyłącznie opisem; kod stał na siatce z §3 (pasek zakładek
> 72 dp po prawej, pas górny 40 dp, tafle danych u dołu). Teraz wariant D jest w aplikacji.
>
> **Co za czym poszło w kodzie:**
>
> | Element §9 | Gdzie |
> |---|---|
> | wymiary całego wariantu | `ui/Motyw.kt` — `Wymiary` |
> | pełna szyba, karty narożnika, rząd liczb, klawisze | `ui/Kokpit.kt` |
> | pływająca kolumna zakładek 64 × 34 × 6 | `ui/Aplikacja.kt` — `KolumnaZakladek` |
> | pas górny 28 dp na przejściu tonalnym, słupek baterii, pole władzy | `ui/Elementy.kt` — `PasGorny` |
> | taśma kursu 460 dp / 60° | `ui/Elementy.kt` — `TasmaKursu` |
> | pasek telemetrii 960 × 78 bez tafli | `ui/Elementy.kt` — `PasekTelemetrii` |
> | **karta mapy z klawiszem ROZWIŃ** i **karta horyzontu** | `ui/Karty.kt` (nowy plik) |
>
> Zrzuty z emulatora MK32 (1920 × 1200, 320 dpi, telemetria z symulatora):
> `dok/zrzuty/wariantD_lot.png`, `wariantD_misja.png`, `wariantD_kamera.png`,
> `wariantD_przed.png`, `wariantD_rc.png`, `wariantD_diag.png`.
>
> **Cztery rozstrzygnięcia, których §9 nie przesądzała:**
>
> 1. **MISJA dostała tę samą chrom co LOT.** §9 opisuje jedną szybę, a MISJA jest tą samą
>    parą „mapa ⇄ obraz" oglądaną z drugiej strony (zasada 2) — trzymanie jej na starej
>    siatce rozjechałoby oba ekrany. KAMERA też zostaje pełną szybą.
> 2. **Ekrany robocze (PRZED, RC, DIAGNOSTYKA) mają wcięcie 72 dp od lewej.** Mają
>    nieprzezroczyste tło, więc pływająca kolumna leżałaby na treści.
> 3. **Podziałka mapy podniesiona o wysokość paska telemetrii.** Przy mapie na pełnej
>    szybie wchodziła pod wysokość i dystans (`ui/Mapa.kt`, `wcieciePodolu`).
> 4. **Pole władzy pokazuje na razie zawsze „STERUJESZ TY".** Moduł udostępniania (M5)
>    nie istnieje, więc steruje wyłącznie operator MK32 — i tak właśnie ekran ma to mówić.
>    `PasGorny(steruje = …)` jest gotowy na resztę: podana nazwa przestawia **cały pas
>    na bursztyn**, zgodnie z opisem „pasa władzy" w §4.
>
> **Jedno odstępstwo od §2 wchodzi razem z tym wariantem:** zakładki mają **34 dp
> wysokości przy celu dotykowym 64 dp**. Wynika to wprost z wymiaru „64 × 34 × 6" w tabeli
> niżej. Zakładki są jedynym elementem, który tę regułę łamie, i jedynym, w którym pomyłka
> niczego nie uruchamia — klawisze komend zostają przy 64 dp i przy przytrzymaniu.
> **Do sprawdzenia na aparaturze w rękawiczkach**, bo emulator tego nie rozstrzygnie.

MK32 nie jest tabletem współdzielonym z nikim: to **osobista stacja naziemna jednej
maszyny**. Wariant D wyciąga z tego wniosek — obraz zajmuje całą szybę, a interfejs
pływa nad nim w kartach, zamiast odbierać mu pasek szerokości.

| Element | Wymiar | Zmiana wobec siatki z §3 |
|---|---|---|
| obraz | **960 × 600** | pełna szyba; pasek zakładek 72 dp znika |
| zakładki | 64 × 34 × 6 | pływająca kolumna po lewej, pod pasem górnym |
| pas górny | 960 × 28 | cieńszy, na przejściu tonalnym, bez linii; dochodzi słupek baterii i pole władzy |
| taśma kursu | 460 × 20 | krótsza, zakres 60° zamiast 100°, wyłącznie nad obrazem |
| karta mapy | 212 × 150 | prawy górny narożnik, z klawiszem **ROZWIŃ** |
| karta horyzontu | 212 × 150 | pod kartą mapy; przechył ±30°, pochylenie ±10°, wartości w narożniku |
| pasek telemetrii | 960 × 78 | jeden rząd liczb u dołu na przejściu tonalnym, bez tafli |
| klawisze | 64 × 64 | FOTO · REC · LĄDUJ · RTL; dwa ostatnie na przytrzymanie, jak w §4 |

**Czego w wariancie D nie ma i nie będzie:**

- **klawiszy głowicy i zoomu** — siedzą na aparaturze (pokrętła i przyciski S1/S2,
  patrz [RC_PRZYPISANIA.md](RC_PRZYPISANIA.md)), więc zasada 7 obowiązuje bez wyjątku.
  Na ekranie zostaje wyłącznie **stan** głowicy: kąty, zoom, nagrywanie
- **uzbrajania z ekranu** — nadal CH9
- **wykresów i komunikatów z FC** — nadal DIAGNOSTYKA

Bez zmian wobec 2.1: barwy i kroje z §2, reguła wieku danych (§4, zasada 6), przytrzymanie
komend ruszających maszyną, brak gestów krawędziowych, kolejność ważności banerów.
