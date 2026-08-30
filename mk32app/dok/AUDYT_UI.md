# Audyt interfejsu — kokpit DRON15 na MK32

---

## ⛔ ZNALEZISKO Z UŻYTKOWANIA 2026-08-29 — PANEL BEZ WYJŚCIA

Zgłoszone po pierwszym dniu pracy na stacji: *„jak się wejdzie w sekcję admin, to już
nie można przejść nigdzie więcej, tak samo z innymi zakładkami, i trzeba restartować
całą stronę"* oraz *„ze strony nie można wysłać zaproszeń ani zarządzać podłączonymi
ludźmi"*.

**To jedna przyczyna, nie dwie.** W `App.css` było:

```css
.zaslona.panel { justify-content: center; overflow-y: auto; }
```

Przewijalny kontener z **wyśrodkowaną** zawartością **obcina górę**: treść wychodzi
poza początek obszaru przewijania, a tam scroll nie sięga. To znana pułapka flexboksa.

Panel administratora ma siedem sekcji. Przy wyśrodkowaniu poza zasięg wypadały
**dokładnie te górne**:

| Co było nieosiągalne | Skutek zgłoszony przez operatora |
|---|---|
| nagłówek z przyciskiem ZAMKNIJ | „nie można przejść nigdzie więcej, trzeba restartować stronę" |
| STEROWANIE DOSTĘPEM | brak trybu ciszy i limitu widzów |
| KTO OGLĄDA z przyciskiem ODETNIJ | „nie można zarządzać podłączonymi ludźmi" |
| ZAPROSZENIA | „nie można wysłać zaproszeń" |

Dolne sekcje (archiwum, rejestr, dziennik) były widoczne — i to właśnie myliło,
bo panel wyglądał na działający.

### Naprawa

1. **`justify-content: flex-start` + `margin-block: auto` na karcie.** Daje to samo
   wyśrodkowanie, gdy karta się mieści, a przy dłuższej — pełny dostęp do góry.
2. **Nagłówek przyklejony** (`position: sticky`): wyjście ma być widoczne **zawsze**,
   bez przewijania.
3. **Przełącznik paneli w nagłówku** (`NaglowekPanelu.jsx`): OGLĄDA · ADMIN · STACJA
   bez zamykania. Dolny pasek jest w tym czasie zasłonięty przez panel, więc bez tego
   każde przejście kosztowało dwa ruchy.
4. **Escape zamyka.**

### Zasada na przyszłość

**Panel, którego wyjście wymaga przewijania, jest panelem bez wyjścia.** Każdy nowy
ekran nakładkowy sprawdzać przy zawartości DŁUŻSZEJ NIŻ EKRAN — przy krótkiej ta wada
nie występuje wcale, więc na biurku nie ma jak jej zobaczyć.

**Data:** 2026-08-19 · **Przedmiot:** `app/cockpit/src/main/java/pl/dron15/cockpit/ui/*`
oraz zrzuty z emulatora `dok/zrzuty/` (1920 × 1200, 320 dpi).

Audyt użytkowy i funkcjonalny, wykonany przed przebudową. Każde znalezisko ma dowód:
plik albo zrzut ekranu. Znaleziska bez dowodu nie weszły do zestawienia.

> **Rozmiar roboczy, o którym trzeba pamiętać przy każdej decyzji:** 1920 × 1200 px przy
> 320 dpi to **960 × 600 dp**. Po odjęciu paska zakładek zostaje **876 × 600 dp** — tyle
> co połowa tabletu. Przy celu dotykowym 64 dp mieści się w poziomie **13 przycisków
> i ani jednego więcej**. Ten interfejs nie ma miejsca na ozdoby.

---

## 1. Ocena ogólna

| Obszar | Ocena | Uzasadnienie |
|---|---|---|
| Bezpieczeństwo (banery, checklista) | **dobry** | reguły z CLAUDE.md faktycznie policzone, 33 testy |
| Kompletność danych lotu | **niedostateczny** | brak mapy, kierunku do domu, czasu lotu, zużycia pakietu, stanu RC |
| Sterowanie z ekranu | **niedostateczny** | jedyna komenda lotu to RTL, bez potwierdzenia z maszyny |
| Zgodność z własnym `UI.md` | **częściowy** | 3 z 6 zasad wdrożone |
| Styl | **do przebudowy** | ciemny Material, nie kokpit |

---

## 2. Braki funkcjonalne

| # | Znalezisko | Skutek dla pilota | Waga |
|---|---|---|---|
| **F1** | **Nie ma mapy.** Zakładka MISJA jest wyszarzona (`Zakladka.MISJA(gotowa = false)` w `ui/Aplikacja.kt`) | pilot nie wie, gdzie jest maszyna względem siebie i względem domu; poza zasięgiem wzroku jest ślepy | **blokada** |
| **F2** | Brak kierunku i odległości do punktu startu, brak śladu trasy. Pozycja jest w stanie (`szerokosc`, `dlugosc`), nikt jej nie pokazuje | na maszynie bez kompasu, z kursem wyłącznie z GNSS, kierunek do domu jest **jedyną** nawigacją ratunkową | **blokada** |
| **F3** | Brak czasu lotu, zużycia pakietu i szacowanego czasu pozostałego. `zuzycieMah` jest dekodowane i nigdzie nie pokazane | decyzja o powrocie podejmowana „na oko" | wysoka |
| **F4** | **`RC_CHANNELS` nie jest dekodowane.** `Mavlink.RC_CHANNELS` ma wpis w `CRC_EXTRA`, ale `SilnikStanu.zastosuj()` nie ma dla niego gałęzi | pilot nie widzi ani jednej pozycji przełącznika; nie ma jak sprawdzić, czy aparatura naprawdę steruje | wysoka |
| **F5** | Jedyna komenda lotu z ekranu to RTL. Nie ma LĄDUJ, nie ma **przerwania** RTL ani AUTO, nie ma zmiany trybu | automat raz uruchomiony jest z ekranu nieodwoływalny; zostaje fizyczny przełącznik trybów | wysoka |
| **F6** | **Brak potwierdzenia komendy.** `COMMAND_ACK` ma `crcExtra`, ale nie jest dekodowany | RTL leci „w ciemno"; przy słabym łączu pilot nie odróżni komendy przyjętej od zgubionej | wysoka |
| **F7** | Głowica z ekranu tylko: centrum, foto, REC. Brak pitch/yaw/zoom, choć `KlientSiyi` ma `obroc()`, `ustawKat()`, `zoom()` — kod jest, ekranu nie ma | przy 30× zoomie brak precyzyjnego celowania z ekranu | średnia |
| **F8** | **Brak panelu przypisań RC.** Nigdzie nie widać, że RTL siedzi na CH6, fence na CH7, uzbrojenie na CH9, zoom na CH16 | ekran dubluje sprzęt, nie mówiąc o tym; po każdej zmianie `RCn_OPTION` wiedza żyje tylko w `CLAUDE.md` | wysoka |
| **F9** | Brak wyboru strumienia wideo (`/video1` ↔ `/video2`) i jakości | jedyne lekarstwo, gdy dekoder Androida 9 nie wyrobi z 4K H.265, jest niedostępne z ekranu | średnia |
| **F10** | Brak sygnalizacji dźwiękowej i wibracji przy blokadzie, choć `UI.md` §7 rezerwuje dźwięk właśnie dla blokad i utraty łącza | baner na ekranie, na który pilot nie patrzy, nie istnieje | wysoka |

---

## 3. Usterki użytkowe

| # | Znalezisko | Dowód | Waga |
|---|---|---|---|
| **U1** | Pas stanu: pola bez wag, tekst łamie się w pionie („dostępn/y"), ostatnie pole poza ekranem | `ekran_20260819_075157.png`; `Kokpit.kt` — `Row` z `Pole()` bez `weight` | wysoka |
| **U2** | Baner nachodzi na prawą kolumnę i zasłania stan głowicy | ten sam zrzut | wysoka |
| **U3** | Kolumny checklisty mają sztywne szerokości 220/200 dp; dłuższa treść łamie się w dwa wiersze i rozpycha rząd | `ekran_20260819_080434.png`; `EkranChecklisty.kt`, `Modifier.width(220.dp)` | średnia |
| **U4** | Ostatni wiersz checklisty jest ucięty bez znaku, że lista ma ciąg dalszy — brak paska przewijania i cienia krawędzi | `ekran_20260819_080434.png` | średnia |
| **U5** | **UZBROJONY pokazany czerwienią** — łamie zasadę 4 („czerwień wyłącznie przy blokadzie"). Uzbrojenie to stan normalny, nie awaria | `Kokpit.kt`, `PasStanu` | średnia |
| **U6** | Zasada 6 („degradacja widoczna") wdrożona wyłącznie dla pola „telemetria". Wysokość, napięcie, satelity pokazują ostatnią wartość bez znacznika wieku, gdy łącze padnie | `Kokpit.kt` — `telemetriaZywa()` użyte raz | **wysoka** |
| **U7** | Zakładki: 6 × ok. 100 dp wysokości, bez ikon, etykieta 12 sp. Nieaktywne różnią się **wyłącznie** przezroczystością tekstu — znowu „sam kolor" | `Aplikacja.kt`, `PasekZakladek` | średnia |
| **U8** | Wszystkie przyciski to `Box` + `detectTapGestures`: bez reakcji na dotknięcie, bez haptyki, bez stanu „wyłączony", bez semantyki dostępności | `Kokpit.kt`, `EkranChecklisty.kt` | wysoka |
| **U9** | Wiersz akcji miesza porządki: RTL (nieodwracalna komenda lotu) stoi obok FOTO (bez konsekwencji), w tym samym rozmiarze, 12 dp od siebie | `Kokpit.kt` | **wysoka** |
| **U10** | Przycisk RTL jest aktywny także wtedy, gdy telemetria nie żyje albo `rtlDostepny == false`. Pole obliczeniowe istnieje i nie jest użyte do zablokowania przycisku | `StanMaszyny.rtlDostepny`, nieużyte w `Kokpit.kt` | **wysoka** |
| **U11** | Kafelek „komunikaty z FC" zjada 150 dp wysokości ekranu LOT na surowy tekst z kontrolera. To materiał na DIAGNOSTYKĘ | `Kokpit.kt` | średnia |
| **U12** | Prawy dolny róg — najlepiej dostępny kciukiem przy trzymaniu oburącz — jest pusty, a dane siedzą po lewej | `ekran_20260819_075157.png` | średnia |
| **U13** | Brak wskaźnika kursu (róża, taśma). Kurs jest w stanie (`kursSt`, `kursGnssSt`) i nie ma go na ekranie, choć makieta `dok/makieta.html` przewiduje HSI | zrzut vs makieta | wysoka |

---

## 4. Rozjazdy z własnym systemem projektowym

| Zasada `UI.md` | Stan |
|---|---|
| 1 · obraz jest tłem | ✅ wdrożona |
| 2 · mapa i obraz zamieniają się miejscami | ❌ mapy nie ma wcale |
| 3 · trzy poziomy hierarchii | 🔶 czwarty poziom (komunikaty z FC) wleciał na ekran LOT |
| 4 · kolor znaczy, nie zdobi | 🔶 czerwień użyta na stan uzbrojenia (U5) |
| 5 · nic nieodwracalnego przez przypadek | 🔶 tylko RTL; „pierścień postępu" z opisu jest w kodzie napisem procentowym |
| 6 · degradacja widoczna | ❌ patrz U6 |

**Rozjazd jednostek.** `UI.md` §2 podaje rozmiary w **pikselach** (40 / 28 / 26 px), kod
liczy w **sp** (34 / 24 / 24 sp = 68 / 48 / 48 px przy 320 dpi). Dokumentacja i kod mówią
o czym innym, różnica jest dwukrotna. Do ujednolicenia w `sp` — jednostka fizyczna,
a nie pikselowa, jest tu jedyną sensowną.

**Brak `tabular-nums`.** `UI.md` wymaga cyfr o stałej szerokości; Compose potrzebuje do tego
`fontFeatureSettings = "tnum"`, którego nigdzie nie ma. Napis „11.1 → 9.8" przeskakuje
w poziomie przy każdej zmianie.

---

## 5. Styl — dlaczego wymaga przebudowy

Obecny wygląd to **ciemny Material**: promień 12 dp, wypełnione plamy koloru, akcent
`#4DA3FF`. Nie jest brzydki, ale nie jest kokpitem — wygląda jak ekran ustawień aplikacji.

Trzy powody rzeczowe, nie estetyczne:

1. **Zaokrąglenia 12 dp przy taflach szerokich na 160 dp** zjadają róg, w którym i tak
   brakuje miejsca, i rozmywają siatkę. Przyrządy czyta się szybciej, gdy krawędzie są
   ostre i wyrównane do wspólnej linii.
2. **Niebieski akcent najgorzej znosi słońce.** Przy ekranie w pełnym świetle spada
   nasycenie i niebieski na czarnym zlewa się pierwszy. Chłodny cyan i czysta biel
   utrzymują kontrast dłużej.
3. **Plamy wypełnione kolorem rywalizują z obrazem z kamery**, który ma być tłem. Kontury
   i linie włosowe wygrywają: dane są czytelne, a obrazu nie brakuje.

Kierunek: **przemysłowo-wojskowy** — ostre naroża, znaczniki narożne zamiast pełnych ramek,
linie włosowe, wersaliki z rozstrzeleniem, cyfry o stałej szerokości, kolor wyłącznie jako
znaczenie. Pełny opis w [UI.md](UI.md).

---

## 6. Podział pracy: ekran a fizyczne przełączniki MK32

Rzecz przeoczona w pierwszym podejściu, a rozstrzygająca dla układu ekranu.
MK32 ma **16 kanałów**: 4 drążki, 6 przełączników trzypozycyjnych (SA–SF),
4 pokrętła (LD1/RD1/LD2/RD2), 2 przyciski (S1/S2) — sekcja 2.2 `CLAUDE.md`.

Stan na maszynie (sekcja 6 poz. 5 `CLAUDE.md`): `RC6_OPTION=4` RTL, `RC7_OPTION=11` Fence,
`RC9_OPTION=153` ARM/DISARM, `RC10_OPTION=214` Mount Yaw, `RC15_OPTION=213` Mount Pitch,
`RC16_OPTION=167` zoom.

**Wniosek: ekran nie powinien dublować tego, co robi kciuk bez patrzenia.** Przycisk
ekranowy dla funkcji siedzącej na przełączniku jest gorszy pod każdym względem — wymaga
wzroku, jest wolniejszy i tworzy drugie źródło tego samego stanu.

Stąd zasada, której dotąd nie było:

> **Ekran pokazuje. Sprzęt wykonuje. Ekran wykonuje tylko to, czego sprzęt nie ma.**

Żeby dało się jej pilnować, aplikacja musi **wiedzieć**, co siedzi na kanałach — i stąd
bierze się panel przypisań RC ([RC_PRZYPISANIA.md](RC_PRZYPISANIA.md)), a nie z chęci
dołożenia jeszcze jednego ekranu.

---

## 7. Kolejność napraw

| Krok | Zakres | Znaleziska | Stan |
|---|---|---|---|
| 1 | listwy i pas stanu na wagach, wiek danych na każdym kafelku, blokada przycisków bez pokrycia | U1, U2, U6, U8, U10 | ✅ zrobione |
| 2 | mapa jako pełnoprawny element: ślad, dom, kierunek i odległość, zamiana z obrazem | F1, F2, U13, zasada 2 | ✅ zrobione |
| 3 | dok akcji rozdzielony na LOT i KAMERĘ, komendy kontekstowe, potwierdzenie z `COMMAND_ACK` | F5, F6, U9 | ✅ zrobione |
| 4 | dekodowanie `RC_CHANNELS` + panel przypisań | F4, F8 | ✅ zrobione |
| 5 | ekran KAMERA: krzyżak, zoom, wybór strumienia | F7, F9 | ✅ zrobione |
| 6 | dźwięk i wibracja przy blokadzie | F10 | ⬜ wymaga decyzji o głośności na aparaturze |
| 7 | porządki w checkliście i diagnostyce | U3, U4, U11 | 🔶 U11 zrobione (kafelek zdjęty z LOT) |

Mapa jest **własnym rysunkiem w układzie lokalnym** (ślad, dom, maszyna, obwiednia lotu),
bez kafelków terenu — kafelki offline z MapLibre wchodzą w etapie M4 i zajmą to samo miejsce
w układzie, bez zmiany reszty ekranu.

---

## 8. Druga tura — uwagi operatora, 2026-08-19

Pierwsza przebudowa naprawiła braki (F1–F10), ale przy okazji **przeładowała ekran lotu**.
Uwagi operatora i co z nich wynikło:

| # | Uwaga | Diagnoza | Naprawa |
|---|---|---|---|
| **O1** | „brakuje map" | mapa była własnym rysunkiem bez podkładu terenu — dla operatora to nie jest mapa | kafelki rastrowe z karty TF + `narzedzia/kafelki.py`; domyślnie **zdjęcia lotnicze** |
| **O2** | „przyciski są nieprzezroczyste" | tafle 78 %, przyciski wypełnione — obraz z kamery był zasłonięty tam, gdzie leżał interfejs | tafle 40 %, klawisze 8 % bieli, pod palcem 24 % |
| **O3** | „czcionki za duże" | rozmiary dobrane na monitorze, nie na 7 calach | 34 → 26, 26 → 20, 20 → 15, 11 → 9 sp |
| **O4** | „część funkcji może być na piktogramach" | każda czynność miała słowo, przez co dok akcji zjadał 400 dp | piktogramy wektorowe (`ui/Ikony.kt`), podpis 9 sp jako podpowiedź |
| **O5** | **„za dużo informacji"** | dwie listwy przyrządów, róża 148 dp, cztery diody łączy, komunikaty z FC — na ekranie, na który patrzy się w locie | z HUD-u zdjęte: prąd, mAh, gaz, kąty głowicy, diody łączy, róża; **zostały cztery liczby** |

**Miara, którą warto zapamiętać.** Ekran lotu przed drugą turą pokazywał **19 wartości
liczbowych**; po niej pokazuje **10** (sześć w pasie górnym, cztery w odczytach). Powierzchnia
zajęta przez interfejs spadła z ok. 38 % do ok. 17 % ekranu, a to, co zostało, jest
przezroczyste.

Reguła, która z tego wynika:

> **Jeśli pilot nie podejmie na tej podstawie decyzji w ciągu najbliższej sekundy,
> to nie należy do HUD-u.**

Zrzuty po drugiej turze: `dok/zrzuty/hud_lot.png`, `hud_misja.png`, `hud_kamera.png`,
`hud_rc.png`.
