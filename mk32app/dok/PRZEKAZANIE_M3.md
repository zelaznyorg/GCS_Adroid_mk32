# Przekazanie do kodu — kokpit M3

**Wersja 1.0, 2026-08-24.** Dotyczy `dron15/mk32app/app/cockpit`.
Makieta: `../Aplikacja mobilna dla DRON/Kokpit M3.dc.html` (kokpit) i `Kokpit MK32 M3.dc.html`
(przegląd). Nawigacja: `../Aplikacja mobilna dla DRON/NAWIGACJA.md`.
Zasady bazowe: [UI.md](UI.md) — odstępstwa spisane w §7.

Wymiary w **dp**, ramka **960 × 600 dp**. Makieta jest rysowana 1:1 w tych jednostkach,
więc liczby z niej można przenosić wprost.

---

> ## ✅ WDROŻONE 2026-08-24 — stan kodu, nie plan
>
> Zrealizowane kroki 1–6 i 8 z §10. Krok 7 (adresy i POI) czeka na decyzję z §5.
>
> ### Czego użyto jako źródła
>
> **Makieta jest** — `mk32app\Aplikacja mobilna dla DRON\Kokpit M3.dc.html` razem
> z `NAWIGACJA.md` i zestawem tokenów `_ds/industry`. Pierwsze wdrożenie powstało z samego
> tekstu tego dokumentu, **drugie przeszło makietę linia po linii**. Poniżej to, co druga
> tura zmieniła — bo tekst tych rzeczy nie przesądzał, a rysunek tak.
>
> #### Płyta — sygnatura, której nie było w tekście
>
> W makiecie **każdy** element chromu przechodzi przez pomocnik `plyta(sc, tło, kolor)`:
>
> ```
> clip-path: polygon(SC 0, 100% 0, 100% calc(100% − SC), calc(100% − SC) 100%, 0 100%, 0 SC)
> border-left: 2px solid <kolor>
> box-shadow: inset −1px 0 0 outline-var, inset 0 1px 0 outline-var, inset 0 −1px 0 outline-var
> ```
>
> Czyli: **ścięte dwa przeciwległe naroża (lewy górny i prawy dolny), krawędź akcentu 2 dp
> wyłącznie po lewej, włos na pozostałych trzech.** Pierwsze wdrożenie ścinało cztery naroża
> i rysowało ramkę dookoła — to gubi kierunek, po którym element się rozpoznaje. Poprawione
> w `ui/Elementy.kt` (`Modifier.plyta`), używane wszędzie.
>
> #### Pozostałe różnice, które wyszły dopiero z makiety
>
> | Rzecz | Z tekstu | Z makiety |
> |---|---|---|
> | pola belki | piktogramy | **podpis słowem** 11 sp + wartość 13 sp (`SAT 18`, `ŁĄCZE 73 Hz`, `LOT 0:14`) |
> | pole władzy | kropka i napis | ramka z podpisem `WŁADZA` 9 sp i wartością 14 sp |
> | słupek baterii | z czubkiem, barwa wg napięcia | 40 × 11 dp bez czubka, wypełnienie `ok` |
> | RTL | czerwień z poświatą | **akcent** — czerwień znaczy blokadę, a RTL nią nie jest |
> | komendy | wyśrodkowane w pionie | `left 12, top 68`, jedna pod drugą |
> | wskaźnik położenia | jednobarwne wypełnienie | **niebo i ziemia** z tokenów `--niebo`/`--ziemia`, drabinka ±5° i ±2,5°, skala przechyłu przechylająca się o **połowę** wartości |
> | odczyty koła | po bokach, w połowie wysokości | **w dolnych narożnikach**, z jedną cyfrą po przecinku |
> | pion kamery | skala, pod nią migawka | **migawka na środku skali**, rama z włosów, kreski od prawej, znacznik od lewej, odczyt 46 dp poza kolumną |
> | miniatura mapy | uchwyt nad kartą | uchwyt **w karcie**, `MAPA · OFFLINE` po lewej i `WYSUŃ`/`SCHOWAJ` po prawej |
> | rząd liczb | pełna szerokość | kończy się na krawędzi kolumny (`KOL + 30`), bloki po 96 dp, pasek 2 dp, etykieta **pod** paskiem |
> | taśma kursu | ścięte rogi, środek ramki | **ostre czubki**, środek liczony od **obrazu** (456 / 504 / 420 dp) |
> | warstwy ekranu | pływająca karta | **panel przy prawej krawędzi, 252 dp, od belki do spodu**, przełączniki 44 × 24 |
> | menu widoków | ramka 2 dp dookoła aktywnej | krawędź akcentu **po lewej** |
> | PRZED / RC / DIAGNOSTYKA | pełny ekran | **płyty wpisane w kadr** (`top 64`, `bottom 12`), obraz widoczny pod spodem |
> | zakładki KAMERY | GŁOWICA · OBIEKTYW · STRUMIEŃ · AI | GŁOWICA · OBIEKTYW · **AI** · STRUMIEŃ |
> | szuflada kamery | krawędź po lewej | **krawędź u góry** — wyjeżdża z góry i to ta krawędź o tym mówi |
> | znacznik punktu trasy | podpis wysokości pod spodem | podpis **po prawej stronie** znacznika |
>
> #### Trzy rzeczy, w których świadomie odszedłem od makiety
>
> 1. **Barlow Condensed → zwykły krój z rozstrzeleniem.** Aparatura nie ma tego kroju,
>    a dokładanie pliku czcionki do APK kłóci się z §7 planu (rozmiar APK). `Kroje.zgeszczona`
>    daje ten sam gęsty, techniczny napis. **Skutek uboczny: podpisy klawiszy komend zeszły
>    z 13 na 11 sp**, bo „LĄDUJ" w zwykłym kroju nie mieści się w 44 dp.
> 2. **Kolumna kamery idzie 48 dp w górę, gdy miniatura jest wysunięta.** W makiecie stopka
>    pionu (kierunek + zoom) i uchwyt miniatury nachodzą na siebie o kilkanaście dp.
> 3. **Podpowiedź „dotknij mapę" nad rzędem chipów zasięgu**, nie pod nim — w makiecie
>    obie rzeczy siedzą w lewym dolnym rogu i zachodzą na siebie.
>
> ### Gdzie co jest
>
> | Element | Plik |
> |---|---|
> | tokeny, dwa motywy, ścięcia jako `Shape`, wymiary | `ui/Motyw.kt` |
> | belka górna, menu widoków, nakładka warstw | `ui/Belka.kt` |
> | ekran LOT i cztery stałe krawędzi | `ui/Kokpit.kt` |
> | okrąg położenia, miniatura mapy z uchwytem | `ui/Karty.kt` |
> | pion pochylenia kamery i klawisz migawki | `ui/PionKamery.kt` |
> | taśma kursu, rząd liczb, klawisze komend | `ui/Elementy.kt` |
> | współrzędne: dziesiętne / MGRS / DMS, w obie strony | `domain/Wspolrzedne.kt` |
> | model misji i plik `.plan` | `domain/Misja.kt`, `domain/MagazynMisji.kt` |
> | protokół misji MAVLink | `net/mavlink/TransferMisji.kt`, `Mavlink.kt` |
> | mapa planowania | `ui/MapaMisji.kt` |
> | ekran MISJA | `ui/EkranMisji.kt` |
> | ekran KAMERA i szuflada | `ui/EkranKamery.kt` |
> | komendy SIYI z tabeli §6 | `net/siyi/KlientSiyi.kt` |
>
> Zrzuty z emulatora MK32 (1920 × 1200, 320 dpi, telemetria z symulatora):
> `dok/zrzuty/m3_lot.png`, `m3_lot_jasny.png`, `m3_misja.png`, `m3_kamera.png`,
> `m3_kamera_szuflada.png`, `m3_menu.png`, `m3_warstwy.png`, `m3_przed.png`, `m3_rc.png`,
> `m3_diag.png`.
>
> ### Sześć decyzji, których ten dokument nie przesądzał
>
> 1. **`SharedPreferences` zamiast `DataStore`** (§4, warstwy ekranu). Wymóg brzmi
>    „przeżywa restart", a nie „konkretna biblioteka". Ani `androidx.datastore`, ani żadnej
>    biblioteki nawigacji **nie ma w lokalnej pamięci podręcznej Gradle**, a projekt buduje
>    się `--offline`; `SharedPreferences` już w tym pliku był i robi to samo.
> 2. **Menu widoków zamiast `DestinationsNavHost`** (§1). Zmiana widoczna dla operatora —
>    wybór ekranu w belce — jest wdrożona. `NAWIGACJA.md` przeczytane: opisuje Compose
>    Destinations 1.10.2 + KSP i **cztery rzeczy, których menu samo nie daje** — przejścia
>    150 ms, zachowanie stanu ekranu przy powrocie, przycisk wstecz i zwinięcie siedemnastu
>    lambd w `dependenciesContainerBuilder`. Biblioteki nie dołożono, bo nie ma jej
>    w lokalnej pamięci Gradle, a `NAWIGACJA.md` §3 sam stawia rozmiar APK jako warunek
>    odbioru. **To osobne zadanie, nie część tego wdrożenia** — patrz TODO 5a.9.
> 3. **PRZERWIJ zostaje na LOT** (§4 wymienia tylko RTL i LĄDUJ). W trybie automatycznym
>    LĄDUJ zamienia się w PRZERWIJ — usunięcie tego klawisza cofnęłoby naprawę znaleziska
>    F5 z [AUDYT_UI.md](AUDYT_UI.md) („automat raz uruchomiony był z ekranu nieodwoływalny").
> 4. **Mapa planowania przyjmuje przeciągnięcie i ma wybór zasięgu** (§5 tego nie opisuje).
>    Bez przesuwania widoku planer sięga tylko tam, co widać wokół domu.
> 5. **Tryb LEĆ pobiera misję z maszyny przy wejściu.** §5 mówi „podgląd wykonywanej misji",
>    a nie ma czego podglądać, dopóki się jej nie ściągnie.
> 6. **Wnętrza przyrządów dostały tło z tokenów `instr*`** — okrąg położenia i pion kamery.
>    §4 mówi „przezroczysty, bez tafli", ale §8 zakłada te tokeny wprost („bez nich karta
>    mapy i okrąg położenia zostawały ciemnymi dziurami w motywie jasnym"). Bez tła ciemny
>    tusz motywu jasnego znikał na ciemnym kadrze — sprawdzone na zrzucie.
>
> ### Czego nie sprawdzono
>
> - **Żadna z komend SIYI dopisanych w §6 nie rozmawiała z głowicą.** Komendy działające
>   wcześniej były weryfikowane przez `narzedzia/siyi_gimbal.py` wobec przykładów
>   producenta; nowe pochodzą z instrukcji i z tego dokumentu. Układ ładunku może wymagać
>   korekty przy pierwszym podłączeniu ZR30.
> - **Wysyłka i pobranie misji nie rozmawiały z kontrolerem lotu.** Protokół jest napisany
>   wg dok/MISJE.md §1, ale symulator telemetrii nie obsługuje wiadomości misji.
> - **Cele dotykowe poniżej 64 dp** (komendy 44 × 40, menu 38, znaczniki punktów 24)
>   — do sprawdzenia na aparaturze, w rękawiczkach.

---

## 1. Co się zmieniło wobec dzisiejszego kodu

| Rzecz | Przed zmianą | Po zmianie |
|---|---|---|
| Wybór ekranu | pas zakładek na kadrze, `when (zakladka)` | menu w belce górnej |
| Sztuczny horyzont | karta z tłem | okrąg **przezroczysty**, bez tafli, wyśrodkowany w poziomie |
| Mapa | karta w narożniku | miniatura na krawędzi spodu, chowana w dół, dotknięcie zamienia z kadrem |
| RTL / LĄDUJ | pas klawiszy 104 dp | 44 × 40 dp, pion przy lewej krawędzi, przezierne |
| FOTO / REC | dwa klawisze 64 dp | jeden **okrągły** klawisz migawki w osi pionu kamery |
| Klawisz MAPA/OBRAZ | osobny klawisz | **usunięty** — funkcję przejmuje miniatura |
| MISJA | lista punktów | planowanie **na mapie** + wyszukiwanie (współrzędne / adres / POI) |
| KAMERA | dok z zakładkami | zakładki tekstem na kadrze, ustawienia wyjeżdżają z góry |
| Współrzędne maszyny | brak | blok w rzędzie liczb na LOT |
| Naroża | 2 dp | **ścięte 45°** (chamfer 6–20 dp zależnie od elementu) |

---

## 2. Warstwy ekranu — kolejność malowania

Od spodu. Każda warstwa ma stałe miejsce; nic nie leży na sobie.

1. **Kadr** — obraz z kamery albo mapa, pełna powierzchnia (zasada 1 z UI.md).
2. **Belka górna** — 32 dp, przejście tonalne na pełnym kryciu przez pierwsze 27 dp.
3. **Taśma kursu** — 400 × 20 dp, wyśrodkowana **nad kadrem** (nie nad ramką).
4. **Przyrządy** — okrąg położenia, pion kamery, miniatura mapy.
5. **Komendy** — RTL i LĄDUJ.
6. **Rząd liczb** — 64 dp u spodu, tylko na LOT.
7. **Panele robocze** — MISJA, PRZED LOTEM, RC, DIAGNOSTYKA.
8. **Nakładki** — menu widoków, warstwy ekranu, szuflada ustawień kamery, potwierdzenia.

Reguła, która rozwiązała większość kolizji w makiecie: **każdy element liczy swoją krawędź
od zajętego sąsiada, nie od ramki.** Cztery stałe w jednym miejscu, wszyscy je czytają
(w kodzie: `Krawedzie` w `ui/Kokpit.kt`).

---

## 3. Belka górna (32 dp)

Od lewej: znacznik trybu lotu (ścięty, krawędź akcentu), stan uzbrojenia, napięcie z
paskiem, satelity, częstotliwość łącza, czas lotu. Po prawej: znacznik władzy, motyw,
warstwy ekranu, **menu widoków**.

**Menu widoków** — klawisz z nazwą bieżącego ekranu i strzałką; rozwija listę 180 dp
z sześcioma pozycjami (ikona + nazwa), aktywna ma krawędź akcentu 2 dp.
Wysokość pozycji **38 dp** — odstępstwo, patrz §7.

---

## 4. Ekran LOT

### Okrąg położenia — 132 dp, przezroczysty

Bez tafli i bez tła. Zawiera: linię horyzontu, drabinkę pochylenia (±5°, ±10°),
skalę przechyłu na obwodzie ze wskaźnikiem u góry, symbol maszyny w środku, odczyty
przechyłu i pochylenia po bokach okręgu.

- Zakres: przechył ±30°, pochylenie ±10° → **5,4 dp na stopień**
- Położenie: wyśrodkowany w poziomie, **82 dp nad spodem** (nad rzędem liczb)
- Rysowanie: `Canvas` w `Karty.kt` — obracana grupa, nie osobne widoki

### Pion pochylenia kamery — 58 × 300 dp, prawa krawędź, wyśrodkowany w pionie

- Skala −90…+25° (zakres ZR30), kreski co 15°, dłuższe co 45°
- Żywy znacznik + odczyt stopni **po lewej stronie skali**
- Pod skalą, w jednym kontenerze z `gap`: podpis kierunku (▼ DÓŁ / ▲ GÓRA / POZIOM)
  i krotność zoomu. **Nie dwie osobne kotwice** — to była przyczyna nakładania.

### Klawisz migawki — okrągły, 58 dp, w osi pionu

| Gest | Skutek |
|---|---|
| krótkie dotknięcie, tryb WIDEO | start / stop nagrywania |
| krótkie dotknięcie, tryb FOTO | wyzwolenie migawki |
| przytrzymanie **700 ms** | zmiana trybu, potwierdzenie „MIGAWKA — WIDEO/FOTO" |

Ikona na klawiszu pokazuje wybrany tryb (aparat albo koło nagrywania). Przy nagrywaniu
obwódka i ikona czerwienieją.

### Komendy — RTL nad LĄDUJ, lewa krawędź

44 × 40 dp, krycie ~75 %, ścięte naroża, krawędź akcentu 2 dp.
**Przytrzymanie 1200 ms** z paskiem postępu i potwierdzeniem `COMMAND_ACK`
(„RTL — PRZYJĘTA"). Odstępstwo od celu 64 dp — §7.

### Miniatura mapy — 190 × 126 dp, prawa krawędź spodu

- Uchwyt 20 dp z ikoną i napisem **WYSUŃ / SCHOWAJ**; przejście 150 ms
- Schowana zostawia na kadrze tylko uchwyt
- **Dotknięcie mapy zamienia ją z kadrem** (zasada 2 z UI.md)
- Strona kolumny (lewa / prawa) wybierana w warstwach ekranu

### Rząd liczb — 64 dp

Wysokość (26 sp), do domu, prędkość, wznoszenie — każda z paskiem; wznoszenie liczone
od środka skali. Po prawej **POZYCJA MASZYNY**: współrzędne dziesiętne (15 sp),
pod nimi MGRS i stan GNSS.

### Taśma kursu — 400 × 20 dp

Ścięte końce, kurs po lewej, znacznik północy, **domek** z odczytem azymutu do punktu
startu (nie trójkąt).

### Warstwy ekranu (nakładka 252 dp)

Przełączniki: taśma kursu, miniatura mapy, okrąg położenia, rząd liczb, dok akcji.
Plus wybór krawędzi kolumny i motywu. Ustawienie **przeżywa restart**.

---

## 5. Ekran MISJA — planowanie na mapie

Mapa wypełnia kadr do panelu listy (288 dp z prawej). Rząd liczb i przyrządy LOT-u
**nie renderują się** na tym ekranie.

### Tryby (segmented button)

| Tryb | Co robi | Akcje |
|---|---|---|
| PLANUJ | nowa trasa od zera | WYCZYŚĆ · ZAPISZ .plan · **WYŚLIJ** (przytrzymanie) |
| LEĆ | podgląd wykonywanej misji, edycja zablokowana | PAUZA · **SKOK** · **PRZERWIJ** (przytrzymanie) |
| EDYTUJ | zapisany plik z karty | POBIERZ · ZAPISZ .plan · **WYŚLIJ ZMIANY** (przytrzymanie) |

### Nanoszenie punktów

- **Dotknięcie mapy** dokłada `WAYPOINT` na końcu trasy, przed `RETURN_TO_LAUNCH`
- Punkt to ścięty znacznik 24 dp z numerem, obok podpis wysokości
- Wybrany punkt na wypełnieniu akcentu; trasa rysowana odcinkami
- Na mapie: dom (ikona domku), pozycja maszyny (trójkąt), okrąg geofence (linia przerywana)

### Wyszukiwanie — panel 336 dp, lewy górny

| Tryb | Format | Uwaga |
|---|---|---|
| WSPÓŁRZĘDNE | `52.23412 N 21.00871 E`, MGRS `34U EC 12345 67890`, stopnie-minuty-sekundy | parsowanie lokalne, bez sieci |
| ADRES | miejscowość, ulica, numer | **wymaga danych offline** |
| POI | wieża, most, hałda… | **wymaga danych offline** |

Wynik pokazuje nazwę, współrzędne i odległość; **DODAJ** wstawia punkt do trasy.

> **DO DECYZJI PRZED KODOWANIEM.** Aparatura nie ma sieci, więc adresy i POI muszą pochodzić
> z danych na karcie. Dwie drogi: (a) lokalny indeks (wyciąg z OSM dla rejonu, plik
> SQLite/FTS na `/sdcard/dron15`), (b) import punktów z pliku przygotowanego na stacji
> (CSV/GeoJSON). Makieta nie rozstrzyga — wyniki w niej są zaślepką.
> Współrzędne działają bez żadnych danych i mogą wejść pierwsze.
>
> **Stan po wdrożeniu:** współrzędne działają, obie pozostałe zakładki **mówią wprost,
> czego brakuje i skąd to wziąć**, zamiast pokazywać pustą listę.

### Lista punktów — panel 288 dp

Wiersz ma **dwie linie**: numer + typ + wysokość, pod nimi **pełne współrzędne** i klawisze
− / + / ✕ (28 dp). Nie skracać formatu współrzędnych — kolumna `1fr` w jednej linii zwijała
się do 89 dp i ucinała odczyt.

Nagłówek: stan misji (kolor zależny od trybu) i podsumowanie „N pkt · M m".

---

## 6. Ekran KAMERA — stanowisko obsługi

Pełny obraz z HUD-em; **nic z komend lotu** (zasada: nie powielać LOT-u).

### HUD

- Lewy górny: krotność zoomu, pod nią odczyt kątów głowicy (obrót · pochylenie · przechył)
- Wskaźnik nagrywania pod nimi
- Celownik w środku; **przeciągnięcie** zadaje prędkość obrotu (`0x07`), puszczenie wysyła
  zera — bez tego głowica ucieka
- **Dotknięcie bez przeciągnięcia** stawia ramkę ostrości w punkcie (`0x04` z x,y)
- Pas orientacji u spodu (52 dp): wysokość, kurs, do domu, namiar na dom — **wartości,
  których nie ma w belce**

### Zakładki i szuflada

Cztery zakładki tekstem na kadrze (prawy górny, **48 dp**). Dotknięcie wysuwa szufladę
z góry, ponowne — chowa. Kadr domyślnie czysty.

Szuflada: `left 12 / right 82 / top 94`, wysokość do 322 dp — mija pas odczytów HUD
i pion kamery. Odczyt kątów schodzi z kadru na czas otwarcia (te same wartości są w środku).

### Komendy SIYI — stan wdrożenia

**✔** działało wcześniej i jest sprawdzone; **⊙** dopisane 2026-08-24, **niesprawdzone
na sprzęcie**.

| Zakładka | Funkcja | CMD | Stan |
|---|---|---|---|
| GŁOWICA | prędkość obrotu | `0x07` | ✔ |
| GŁOWICA | kąt bezwzględny (obrót ±270°, pochylenie −90…+25°) | `0x0E` | ✔ |
| GŁOWICA | centrowanie | `0x08` | ✔ |
| GŁOWICA | tryb LOCK / FOLLOW / FPV | `0x0C` funkcje 3–5 | ⊙ |
| GŁOWICA | położenie i prędkości osi | `0x0D`, `0x25` | `0x0D` ✔ / `0x25` ⊙ |
| GŁOWICA | kierunek montażu | `0x0A` | ✔ |
| OBIEKTYW | zoom ciągły (przytrzymanie) | `0x05` | ✔ |
| OBIEKTYW | zoom bezwzględny 1–30× | `0x0F` | ⊙ |
| OBIEKTYW | maksimum i odczyt zoomu | `0x16`, `0x18` | ⊙ |
| OBIEKTYW | ostrość auto raz / daleko / blisko | `0x04`, `0x06` | ⊙ |
| OBIEKTYW | HDR | `0x0C` funkcja 1 | producent: **nieobsługiwane** |
| STRUMIEŃ | kodek, rozdzielczość, bitrate | `0x20`, `0x21` | ⊙ |
| STRUMIEŃ | rozdzielczość nagrania 4K / 2K / 1080p | `0x21` | ⊙ |
| — | zdjęcie | `0x0C` funkcja 0 | ✔ |
| — | nagrywanie | `0x0C` funkcja 2 | ✔ |

### AI — cała zakładka wyłączona, widoczna

Rozpoznawanie, śledzenie, AI follow i ramka celu **nie są funkcją ZR30** — wymagają
dokupienia modułu **SIYI AI Tracking**, którego na tej maszynie nie ma. Zakładka pokazuje
je z podanym powodem, zamiast ukrywać.

> **HIPOTEZA do sprawdzenia na sprzęcie.** Instrukcja opisuje sterowanie modułem przez
> aplikację SIYI FPV, nie przez protokół `0x..`. Nawet po dokupieniu modułu nie wiadomo,
> czy da się to prowadzić z naszego kodu. Sprawdzić **przed** planowaniem tej funkcji.

### Czego na ZR30 nie ma

- **Strumień pomocniczy** — tylko ZT30 i ZT6 (dlatego przełącznik MAIN/SUB został usunięty)
- **Wyjście HDMI / CVBS** — tylko ZT6 i A8 mini

---

## 7. Odstępstwa od UI.md — do zatwierdzenia

Makieta łamie trzy zapisy §2. Jeśli kierunek zostaje, `UI.md` trzeba poprawić, żeby
dokument nie rozjechał się z kodem — inaczej następny audyt zgłosi to jako regresję.

| Zapis w UI.md | Makieta | Uzasadnienie |
|---|---|---|
| naroża 2 dp | ścięte 45°, 6–20 dp | prośba operatora o wygląd „futurystyczny", nie ciosany |
| zero cieni | poświata na RTL | sygnał, nie wypukłość — element pierwotny musi się wyróżniać |
| cel dotykowy 64 dp | komendy 44 × 40 dp, menu 38 dp | kadr musi zostać pusty; zakładki kamery podniesione do 48 dp (minimum M3) |

**Rozstrzygnięcie (Tom, 2026-08-24): komendy zostają 44 × 40 dp jak w makiecie.**
Uzasadnienie: obie są chronione przytrzymaniem 1200 ms, więc chybienie niczego nie
uruchamia. `UI.md` §2 poprawione, żeby dokument nie rozjechał się z kodem.

---

## 8. Kolory — tokeny motywu

Dwa motywy, ten sam zestaw nazw. W kodzie: `ui/Motyw.kt`, obiekty `Palety` i `Barwy`.

| Token | Ciemny | Jasny | Rola M3 |
|---|---|---|---|
| `surf` | `#0B0F11` | `#E6EBED` | surface |
| `surf-c` | `#0A1014` @ 42 % | `#F4F7F8` @ 88 % | surface-container (tafla) |
| `surf-c-hi` | @ 68 % | @ 94 % | tafla klawisza |
| `surf-c-max` | `#070B0D` @ 92 % | `#F9FBFC` @ 96 % | nakładki, dialogi |
| `on-surf` | `#E3EAEE` | `#101619` | on-surface |
| `on-surf-var` | `#93A4AE` | `#3E4C55` | on-surface-variant |
| `outline` / `outline-var` | `#7E96A8` @ 30 / 14 % | `#182C3A` @ 34 / 15 % | outline |
| `prim` | `#4ED8F2` | `#00616F` | primary — **tylko zaznaczenie** |
| `ok` | `#35D07A` | `#0E6B3C` | stan poprawny |
| `uwaga` | `#F5A623` | `#8A4B00` | **tylko ostrzeżenie** |
| `err` | `#FF6B62` | `#A5231C` | **tylko blokada** |
| `instr` / `instr-line` / `instr-ink` | ciemne wnętrze przyrządu | jasne wnętrze | wnętrza kart i mapy |
| `scrim` | `#060809` @ 82 % | `#EEF2F4` @ 93 % | przejścia tonalne belki i rzędu liczb |

Wnętrza przyrządów mają **własne tokeny** — bez nich karta mapy i okrąg położenia zostawały
ciemnymi dziurami w motywie jasnym.

> **Wartości `instr*` są wzięte z tego opisu, nie z makiety** (której nie było):
> ciemny `#0A1014` @ 72 % / `#7E96A8` @ 22 % / `#B9C7D0`, jasny `#DCE4E8` @ 92 % /
> `#182C3A` @ 20 % / `#2A3942`. Do porównania, gdy makieta się znajdzie.

---

## 9. Ruch

| Co | Czas | Krzywa |
|---|---|---|
| przejście między ekranami (na obrazie) | 150 ms | przenikanie, `.2 0 0 1` |
| przejście między ekranami (panele) | 150 ms | przenikanie + 6 dp od strony menu |
| zamiana mapy z kadrem | 150 ms | przenikanie — nie podmiana |
| wysuwanie miniatury i szuflady | 150 ms | `.2 0 0 1` |
| pasek przytrzymania | liniowo | z czasu wciśnięcia |

**Postęp przytrzymania liczy się z czasu wciśnięcia, nie z akumulatora w stanie.**
W makiecie akumulator plus `onPointerLeave` gubił gest przy każdym zabłąkanym zdarzeniu —
to ścieżka bezpieczeństwa z §5 UI.md.

> **W kodzie:** postęp to `(teraz − początek) / czas`, a gest trzyma `tryAwaitRelease()`,
> więc zdarzenia po drodze nie mają na niego wpływu.

---

## 10. Kolejność wdrożenia

1. **Tokeny i motyw** — `Motyw.kt`: dwa schematy, role przyrządów, ścięcia jako `Shape` ✅
2. **Belka + menu widoków** — zdjęcie pasa zakładek ✅
3. **LOT** — okrąg położenia na `Canvas`, pion kamery, migawka, komendy, miniatura mapy ✅
4. **Współrzędne** — parser i formaty (dziesiętne / MGRS / DMS), blok w rzędzie liczb ✅
5. **MISJA** — mapa planu, nanoszenie punktów, lista, wyszukiwanie po współrzędnych ✅
6. **KAMERA** — zakładki i szuflada, potem brakujące komendy `0x..` z tabeli §6 ✅ (kod)
7. **Adresy i POI** — dopiero po decyzji z §5 ⬜
8. **Warstwy ekranu** — trwałość ustawień ✅

Krok 6 wymaga sprzętu na biurku — połowa komend nie ma sensu bez podłączonej głowicy.
