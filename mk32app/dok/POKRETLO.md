# Pokrętło stacji — obsługa strony bez myszy

Stacja obsługiwana jest **pokrętłem**, nie myszą: jest okrągły wyświetlacz GC9A01
i enkoder obrotowy, obsługiwane przez `pi5-control-panel` z `PI5setup full`.
Ten dokument opisuje, jak strona podglądu daje się nim obsłużyć w całości.

> ⚠ **Sprostowanie 2026-08-29.** Wcześniejsze wydanie tego opisu twierdziło, że
> „przy stacji nie ma myszy ani klawiatury". To było za mocne: `/proc/bus/input/devices`
> na malinie pokazuje podpiętą mysz **Logitech M350**. Klawiatury nie ma.
> Wniosek dla projektu zostaje ten sam — **pokrętło musi wystarczyć samo** — ale
> nie wolno na tej podstawie zakładać, że myszy nie ma nigdy.

> **Zasada, która rządzi całą tą funkcją:** pokrętło **nie odbiera niczego myszy
> ani dotykowi**. Porusza zwykłym ogniskiem przeglądarki i naciska zwykłym
> `click()`. Wszystko, co działa myszą, działa pokrętłem — i odwrotnie.

---

## 1. Skąd biorą się zdarzenia

Linie GPIO enkodera są zajmowane na wyłączność, więc pokrętło ma **jednego
właściciela**: panel GC9A01. Panel rozgłasza zdarzenia gniazdem UNIX każdemu,
kto się zgłosi (`/opt/pi5setup-full/src/gcs_most.py`). Jesteśmy jednym z takich
odbiorców — **nie przejmujemy pokrętła, prosimy o nie**.

```
  enkoder → panel GC9A01 (właściciel GPIO)
                 │  /run/gcs/pokretlo.sock — JSON po linii
                 ▼
           dron15-gcs  (server/pokretlo.mjs)
                 │  SSE /api/pokretlo — TYLKO do jednego odbiorcy
                 ▼
           przeglądarka (web/src/usePokretlo.js)
```

### Protokół — przepisany z ich modułu, nie wymyślony

| Kierunek | Wiadomość |
|---|---|
| panel → my | `{"typ":"obrot","kierunek":±1}` |
| | `{"typ":"klik"}` |
| | `{"typ":"wcisniety"}` / `{"typ":"puszczony"}` — surowy stan przycisku |
| | `{"typ":"polecenie","co":"nagrywanie"}` |
| | `{"typ":"ognisko","gdzie":"panel"\|"pulpit"}` |
| my → panel | `{"cmd":"ognisko","gdzie":"pulpit"\|"panel"}` |
| | `{"cmd":"stan","nagrywa":bool,"opis":"…"}` |
| | `{"cmd":"siec","lan":"…","wifi":"…","wan":"…"}` |

Przytrzymanie **mierzymy sami** — panel przysyła surowy stan przycisku właśnie po
to, żeby każdy odbiorca mógł mieć własny próg. U nas 600 ms.

Meldunek `stan` jest po to, żeby **okrągły ekran pokazywał prawdę o nagrywaniu**,
a nie własne domysły. Wysyłamy go przy zestawieniu mostu i co 30 s.

---

## 2. Jak się tym steruje

Model przepisany z ich panelu, **żeby pamięć ruchowa operatora przenosiła się bez
uczenia się drugiego zwyczaju**:

| Ruch | Co robi |
|---|---|
| **obrót** | przechodzi między pozycjami bieżącego widoku |
| **klik** | naciska to, na czym stoi ognisko |
| **klik na liście lub polu liczbowym** | **wchodzi** w nie — obrót zaczyna zmieniać wartość |
| **obrót w polu** | zmienia wartość, nie przesuwa ogniska |
| **klik w polu** | zatwierdza i wraca do przechodzenia |
| **przytrzymanie (0,6 s)** | cofa: wychodzi z pola albo zamyka panel |

Gdy obrót przestaje przechodzić, a zaczyna zmieniać wartość, na dole pojawia się
pasek `OBRÓT ZMIENIA WARTOŚĆ · KLIK ZATWIERDZA · PRZYTRZYMANIE COFA`. To jedyna
chwila, w której to samo pokrętło robi co innego, więc jedyna, która wymaga podpisu.

### Zakres wodzenia

Gdy otwarty jest panel, pokrętło **zostaje w nim**. Inaczej obrót wyprowadzałby
ognisko na przyciski schowane pod zasłoną i operator traciłby orientację, nie
widząc, gdzie stoi. Zmierzone: ekran główny — 7 pozycji (dolny pasek), otwarty
panel administratora — 30 pozycji, wszystkie w karcie, dolny pasek wykluczony.

Przełącznik paneli i `ZAMKNIJ` stoją **na początku** listy, więc wyjście jest
zawsze o kilka kliknięć, nie o trzydzieści.

---

## 3. Przekazanie pokrętła

**Na panelu:** strona `PULPIT GCS` (piąta z ośmiu) → naciśnięcie oddaje ognisko
stronie. Okrągły ekran pokazuje wtedy `STERUJE PULPITEM`.

**Na stronie:** klawisz `POKRĘTŁO` w dolnym pasku. Wybór jest zapamiętywany
w przeglądarce.

**Na stanowisku stacji: samo, z adresu.** `rpi/podglad.sh` dokleja do adresu
`pokretlo=1` i strona bierze pokrętło od razu po wczytaniu.

> ### ⛔ Dlaczego sama pamięć przeglądarki nie wystarczyła
>
> Pierwsza wersja miała **wyłącznie** klawisz i pamięć w przeglądarce. Opis brzmiał
> „stanowisko ma pokrętło czynne od razu po otwarciu strony, bez klikania myszą" —
> i był nieprawdziwy, bo zapamiętać można tylko to, co ktoś wcześniej kliknął
> **myszą**. Świeży profil Chromium nie ma czego pamiętać.
>
> **Zmierzone na stacji 2026-08-29:** most żył, panel wysłał 155 zdarzeń,
> `ognisko` stało na `pulpit` — a `trzyma` było **puste**, więc strona nie
> dostawała ani jednego obrotu i **cała obsługa była nieosiągalna**: ani mapy,
> ani paneli, ani zamknięcia. Żeby włączyć obsługę bez myszy, trzeba było
> najpierw kliknąć myszą.
>
> Znacznik `pokretlo=1` działa i w zapytaniu, i w kotwicy. Nie włączamy go
> wszystkim z automatu: pokrętło jest jedno, stoi przy stacji, a serwer oddaje
> je dokładnie jednemu klientowi — widz, który dostałby je przypadkiem,
> odebrałby je stanowisku.
>
> ⚠ **Pułapka przy sprawdzaniu:** własny `curl` na `/api/pokretlo` **podbiera
> pokrętło stronie** (jeden odbiorca!). Przez to klawisz pokazywał `BRAK`
> i przez chwilę wyglądało, że poprawka nie działa. Stan czytać z
> `/api/pokretlo/stan`, nie zestawiając drugiego strumienia.

⚠ **Pokrętło trzyma dokładnie jeden odbiorca.** Jest jedno i stoi fizycznie przy
stacji; rozsyłanie jego obrotów wszystkim widzom przestawiałoby ekrany ludziom,
którzy go nie dotykają. Drugi chętny dostaje `409` z imieniem tego, kto trzyma.

> ### ⛔ Ognisko nie może utknąć poza panelem
>
> To ich zasada bezpieczeństwa i przejmujemy ją bez zmian: **przy maszynie nie ma
> klawiatury** (mysz bywa, klawiatury nie ma), więc pokrętło uwięzione w martwym pulpicie znaczy panel nie do
> obsłużenia. Zamknięcie strumienia SSE — także przez zerwane łącze czy zamkniętą
> kartę — oddaje ognisko panelowi jawnie. Ich most robi to samo, gdy odejdzie
> ostatni klient. Dwie niezależne siatki na ten sam upadek.

---

## 4. ⛔ Dlaczego nie polegamy na `:focus`

Pierwsza wersja rysowała obwódkę selektorem `:focus`. **To nie działa na
stanowisku z pokrętłem** i wyszło dopiero przy próbie: gdy okno przeglądarki nie
ma ogniska systemowego (`document.hasFocus() === false`), `:focus` przestaje
pasować i obwódka znika — choć `document.activeElement` wskazuje poprawny element.

Na stanowisku z pokrętłem znaczyłoby to **ognisko przesuwane niewidocznie**,
a odzyskanie go wymagałoby myszy — czyli tego, czego cała ta funkcja ma nie wymagać.

Dlatego pokrętło zakłada własną klasę `.ognisko-pokretla`, niezależną od stanu
okna. Sprawdzone: obwódka widoczna przy `document.hasFocus() === false`.

---

## 5. Co sprawdzone, a co nie

| Rzecz | Stan |
|---|---|
| most z panelem (`/run/gcs/pokretlo.sock`) | **FAKT** — `most z panelem GC9A01 zestawiony`, panel: `Nowy odbiorca pokrętła (razem 2)` |
| przekazanie ogniska | **FAKT** — `administrator bierze pokrętło` → `ognisko: pulpit`, panel potwierdza `Ognisko pokrętła: pulpit` |
| zakres wodzenia (ekran / panel) | **FAKT** — 7 i 30 pozycji, dolny pasek wykluczony w panelu |
| widoczność ogniska bez ogniska okna | **FAKT** — obwódka `solid 2px` przy `hasFocus() === false` |
| ponawianie mostu po zerwaniu | **kod jest**, nieprzejechane |
| **obrót, klik i przytrzymanie z prawdziwego enkodera** | ⛔ **NIESPRAWDZONE** — wymaga ręki na pokrętle |

Ostatniego wiersza nie da się sprawdzić zdalnie: klienci mostu mogą **odbierać**
zdarzenia i prosić o ognisko, ale nie mogą ich **wstrzykiwać** — obroty rozgłasza
wyłącznie panel, z prawdziwego GPIO. To jest właściwe zabezpieczenie i nie ma
powodu go obchodzić na potrzeby próby.

---

## 6. Pliki

| Plik | Co robi |
|---|---|
| `serwer/server/pokretlo.mjs` | klient gniazda, ponawianie, arbitraż jednego odbiorcy |
| `serwer/server/index.mjs` | `GET /api/pokretlo` (SSE), `GET /api/pokretlo/stan`, meldunek `stan` co 30 s |
| `serwer/web/src/usePokretlo.js` | model obsługi: przechodzenie, wchodzenie w pola, cofanie |
| `serwer/web/src/App.css` | `.ognisko-pokretla`, `.pasek-pokretla` |
| *(ich)* `/opt/pi5setup-full/src/gcs_most.py` | most po stronie panelu — **nie nasz, nie ruszać** |

---

## 7. Pełny ekran — strona sama go nie założy

Strona **nie może** wejść w pełny ekran z własnej woli. `requestFullscreen()`
wymaga prawdziwego gestu użytkownika, a zdarzenia z pokrętła przychodzą
strumieniem SSE — dla przeglądarki to dane z sieci, nie dotknięcie człowieka.
Wywołanie jest odbijane po cichu, bez błędu w konsoli. Klawisz `EKRAN` na stronie
zostaje i działa wszędzie tam, gdzie jest mysz albo dotyk.

Pełny ekran zakłada więc **przeglądarka przy starcie**, w `rpi/podglad.sh`.
Kafelek pulpitu GCS woła ten sam skrypt, żeby flagi okna były w jednym miejscu:

```json
"uruchom": ["/opt/dron15/rpi/podglad.sh", "http://192.168.88.30:8095/#z=<kod>"]
```

⚠ **`"pelny-ekran": true` w katalogu pulpitu nic nie robi.** Wpis jest czytany
(`katalog.py:163`), ale nigdzie nieużywany przy uruchamianiu — sprawdzone w ich
źródłach. To deklaracja bez skutku, więc pełny ekran musi wejść do polecenia.

### ⛔ Trzy pułapki, każda kosztowała próbę

| Objaw | Przyczyna |
|---|---|
| `setsid: failed to execute … No such file or directory`, choć plik jest i ma `+x` | **CRLF w linii `#!`** — jądro szuka interpretera `/bin/sh\r`. Wprowadzone przez edycję pliku Pythonem na Windows (`write_text` tłumaczy końce linii). Zapisywać z `newline=""` |
| Chromium kończy się natychmiast, `Missing X server or $DISPLAY` | sesja to **Wayland (labwc)**, a Chromium bez wskazania platformy wybiera X11. **`--ozone-platform-hint=auto` NIE wystarcza**: podpowiedź patrzy na `XDG_SESSION_TYPE`, której nie ma przy uruchomieniu po ssh ani z usługi. Skrypt wykrywa gniazdo Waylanda i podaje `--ozone-platform=wayland` wprost |
| dymek „przetłumaczyć stronę?" zasłania prawy górny róg HUD-a | **`--disable-features=Translate,TranslateUI`, `--lang` ani `--accept-lang` tego nie gaszą** na Chromium 149 — zmierzone, dymek wracał przy każdym świeżym profilu. Gasi to dopiero `translate.enabled=false` w `Preferences` profilu, zakładane przez skrypt przed pierwszym startem |

Ostatnia pułapka nie jest kosmetyką: dymek stoi dokładnie na wskaźniku łącza
i horyzoncie, a **pokrętłem nie da się go odklikać** — pokrętło steruje stroną,
nie ramką przeglądarki.

### Dlaczego `--start-fullscreen`, a nie `--kiosk`

`--kiosk` blokuje wyjście z okna, a klawiatury przy stacji nie ma — z takiego okna
nie dałoby się wyjść niczym. `--start-fullscreen` daje ten sam obraz na starcie
i zostawia oknu zwykłe zarządzanie. Powrót na pulpit GCS: **przytrzymanie
pokrętła** (ich pasek: „Przytrzymaj: pulpit na wierzch").

### Sprawdzone zrzutem z ekranu stacji

| Rzecz | Stan |
|---|---|
| okno zajmuje pełne 1920×1080, bez ramki i paska | **FAKT** — `grim`, zrzut `d79374cb…` |
| kafelek `DRON 15 — podgląd` odświeżył opis bez restartu pulpitu | **FAKT** — katalog czytany na żywo |
| dymek tłumaczenia zniknął przy świeżym profilu | **FAKT** — zrzut z dymkiem `97447aea…`, bez dymka `d79374cb…` |
| uruchomienie **z kafelka**, ręką operatora | ⛔ **NIESPRAWDZONE** — próby robiłem tym samym poleceniem, ale spod ssh |

---

## 8. Druga droga: klawisze pilota pulpitu

Wasz `gcs_pulpit/pilot.py` zamienia pokrętło na **zwykłe klawisze** i wysyła je
przez `wlrctl` do okna na wierzchu: `↑ ↓ ← →`, `ENTER`, `TAB`, `⇧TAB`, `ESC`,
`PgUp`/`PgDn`. Ich model to *„obrót WYBIERA klawisz, klik go WCISKA"* — operator
ustawia `↓` i klika tyle razy, ile trzeba.

**Ta droga działa niezależnie od mostu.** Pilot nie pyta nikogo o pozwolenie, więc
jest drogą odwrotu, gdy pokrętło trzyma ktoś inny albo panel wziął ognisko dla
siebie. Obie drogi wodzą po **tej samej liście** pozycji (`ogniskowanie.js`),
więc nie da się ich rozjechać.

> ### ⛔ Strzałki nie robiły nic — to była główna przyczyna „pokrętłem nic nie działa"
>
> Zmierzone wstrzyknięciem klawiszy: `TAB`, `ENTER` i `ESC` działały, a `↓` i `→`
> **nie ruszały niczego** — przeglądarka sama nie wodzi nimi ogniska. Skoro strzałka
> jest w ich modelu podstawowym ruchem, strona była pilotem nie do obsłużenia.
>
> Potwierdza to nagłówek ich `mysz.py`: *„Strona DRON 15 do nich nie należy —
> `F11` przełączył Chromium na pełny ekran, ale trzy `TAB`-y nie zaznaczyły niczego
> widocznego"*. **Mysz sterowana pokrętłem powstała jako obejście naszej wady.**
> Dziś `TAB` zaznacza widocznie (`solid 2px` + poświata), a strzałki wodzą ogniskiem.

W polu tekstowym, liczbowym i na liście rozwijanej strzałki zostają przeglądarce —
inaczej nie dałoby się wpisać wartości ani wybrać pozycji.

---

## 9. Trzy usterki, które trzymały to razem zepsute

Wszystkie trzy dawały ten sam objaw — „pokrętłem nic nie da się zrobić" — i każda
z osobna wystarczyła, żeby stanowisko było nie do obsłużenia.

| # | Usterka | Jak wyszła |
|---|---|---|
| 1 | **Strona brała pokrętło dopiero po kliknięciu myszą.** Wybór był pamiętany w przeglądarce, ale zapamiętać można tylko to, co ktoś raz kliknął. Świeży profil nie ma czego pamiętać | most żył i doliczył 155 zdarzeń, a `trzyma` było puste |
| 2 | **Hook czytał żeton raz, przy montażu.** Przy wejściu z kodem żeton pojawia się dopiero po jego wymianie — efekt kończył pustą ręką i **nigdy nie ponawiał** | `trzyma: null` przy poprawnym żetonie w pamięci; ten sam żeton wstawiony ręcznie dawał `200 OK` |
| 3 | **Strzałki nic nie robiły** — patrz §8 | wstrzyknięcie `↓` nie ruszyło ogniska |

Do tego dwie rzeczy myliły przy samym sprawdzaniu:

- ⚠ **Wskaźnik kłamał.** „Połączone" ustawiane było wyłącznie w `onopen`, które
  potrafi nie zadziałać mimo żywego strumienia — strona pokazywała `BRAK`, gdy
  serwer notował `bierze pokrętło`. Teraz liczy się też pierwsza wiadomość.
- ⚠ **Własny `curl` na `/api/pokretlo` podbiera pokrętło stronie** (odbiorca jest
  jeden). Stan czytać z `/api/pokretlo/stan`, nie zestawiając drugiego strumienia.

### Skrót z pulpitu szedł donikąd

`Exec` był samym skryptem, bez adresu — podwójne kliknięcie otwierało `localhost`
**bez zaproszenia i bez znacznika pokrętła**. Stąd „jak się uruchamia, to nie ma
włączonego trybu pokrętło". Skrót jest teraz generowany wprost, bez `sed`:
adres niesie `#` (separator `sed`) oraz `&`, które w zamienniku `sed` znaczy
„całe dopasowanie" — z `&pokretlo=1` robiło się `@CEL@pokretlo=1`.

### Sprawdzone wstrzykiwaniem klawiszy przez DevTools

| Rzecz | Stan |
|---|---|
| `↓ ↑ ← →` wodzą ogniskiem, obwódka widoczna | **FAKT** |
| `ENTER` otwiera panel; mapa wczytuje kafelki (15) | **FAKT** |
| w otwartym panelu wodzenie **zostaje w panelu** | **FAKT** |
| `ESC` zamyka panel | **FAKT** |
| pokrętło brane samo na **czystym profilu** | **FAKT** — `trzyma: "monitory stacji"`, `ognisko: pulpit`, wskaźnik `POKRĘTŁO` |
| **obrót prawdziwego enkodera** | ⛔ **NIESPRAWDZONE** — nie da się wstrzyknąć, obroty rozgłasza wyłącznie panel z GPIO |

Diagnostyka na miejscu (przy stacji nie ma klawiatury, więc konsola jest poza
zasięgiem): `GCS_DEVTOOLS=1 /opt/dron15/rpi/podglad.sh` wystawia port 9222.

---

## 10. Co pokazały logi ze stacji (2026-08-29, wieczór)

Panel prowadzi własny dziennik i on rozstrzygnął sprawę:

```
22:42:55 WARNING Dlugie przytrzymanie (2.8 s) — pokretlo wraca do panelu
22:43:13 Ognisko pokrętła: klient-1      ← strona wzięła
22:43:34 Ognisko pokrętła: panel         ← strona ODDAŁA, bez ostrzeżenia panelu
22:43:35 Ognisko pokrętła: pulpit
```

Przy 22:43:34 **nie ma** ostrzeżenia o przytrzymaniu — więc to nie operator odebrał
pokrętło, tylko strona sama zwolniła strumień. A zwalnia go tylko wtedy, gdy zostanie
naciśnięty klawisz `POKRĘTŁO`.

> ### ⛔ Pokrętło wyłączało samo siebie
>
> Klawisz `POKRĘTŁO` stał w dolnym pasku — **drugi od lewej**, czyli w liście, po
> której pokrętło wodzi. Jeden klik nie w tę pozycję i pokrętło się rozłączało,
> a przy stacji nie ma myszy, żeby je z powrotem włączyć.
>
> Pokrętło omija go teraz (`data-bez-pokretla`). Sprawdzone na żywej stronie:
>
> | Droga | Lista pozycji |
> |---|---|
> | **pokrętło** | `MAPA · ODDOKUJ · OGLĄDA · EKRAN` |
> | klawisze pilota, mysz | `MAPA · POKRĘTŁO · ODDOKUJ · OGLĄDA · EKRAN` |
>
> Oddawanie pokrętła należy do panelu — ma na to długie przytrzymanie i robi to
> niezawodnie. Klawisz zostaje dla myszy, dotyku i pilota, bo tamtymi drogami
> wyłączenie jest świadome i odwracalne.

### Dwa progi przytrzymania, żeby jeden ruch nie robił dwóch rzeczy

| Czas | Kto reaguje | Co się dzieje |
|---|---|---|
| ≥ 0,6 s | **strona** | cofnięcie: wyjście z pola albo zamknięcie panelu |
| ≥ 2,0 s | **panel** | strona milczy — to przytrzymanie „oddaj pokrętło" |

Bez górnego progu oddanie pokrętła zamykałoby przy okazji otwarty panel.

---

## 11. Wyjście z aplikacji i uprawnienia stacji

### Klawisz ZAMKNIJ

Okno wstaje pełnoekranowe, bez ramki, a klawiatury przy stacji nie ma — nie ma więc
ani krzyżyka, ani `Alt+F4`. W dolnym pasku jest `ZAMKNIJ`, **osiągalny pokrętłem**.

Działa dwustopniowo, bo `window.close()` przeglądarka odrzuca dla okien, których
sama nie otworzyła skryptem: najpierw prosimy przeglądarkę, po 300 ms serwer zamyka
proces okna (`pkill` celujący **wyłącznie** w profil `dron15-podglad`).

⛔ **Tylko z ekranu stacji.** Serwer porównuje adres nadawcy z własnymi kartami
sieciowymi (`zSamejStacji`) — widz z telefonu dostaje `403` i nie zgasi nikomu ekranu.
Sprawdzone: z laptopa `403`, ze stacji okno zamknięte (9 procesów → 0).

> ### ⛔ Dlaczego to było pilne, a nie kosmetyczne
>
> Ich most rozsyła zdarzenia **wyłącznie właścicielowi pokrętła** (`gcs_most.py`,
> `rozglos`). Dopóki pokrętło trzyma nasza strona, pulpit GCS nie dostaje **ani
> jednego** zdarzenia — więc jego przytrzymanie nie wywoła go na wierzch, a kafelek
> `✕ ZAMKNIJ` jest nieosiągalny. Wcześniej wyjściem był klawisz `POKRĘTŁO` (zwalniał
> strumień), ale odciąłem go od pokrętła w §10 — i tym samym zamknąłem operatora
> w aplikacji. Klawisz `ZAMKNIJ` przywraca wyjście wprost.
>
> Dodatkowo długie przytrzymanie **przekazuje pokrętło dalej** — serwer wysyła
> `{"cmd":"ognisko","gdzie":"inny"}`, czyli ich własne „przekaż sąsiadowi", więc
> pulpit odzyskuje sterowanie i wraca do niego kafelek `✕ ZAMKNIJ`.

### Stacja jest stanowiskiem administratora

`ADMIN` i `STACJA` widzi tylko rola `admin` (`NaglowekPanelu.jsx`, `minRola`).
Stacja wchodziła zaproszeniem **widza** („monitory stacji"), więc zaproszeń nie dało
się z niej wydawać. Ma teraz własne zaproszenie **`stacja RPi`, rola `admin`,
wielokrotne** — wielokrotne dlatego, że profil kiosku bywa kasowany, a kod
jednorazowy przepadłby po pierwszym czyszczeniu.

Po zmianie dolny pasek: `MAPA · ZAMKNIJ · POKRĘTŁO · ODDOKUJ · OGLĄDA · ADMIN ·
STACJA · EKRAN`, a w panelu administratora są `ZAPROŚ`, `POKAŻ LINK`, `UNIEWAŻNIJ`,
tryb ciszy, ustawienia nagrywania i sprzątanie.

⚠ **To jest decyzja o uprawnieniach, nie o wygodzie:** kto ma dostęp do ekranu
stacji, ten wydaje i unieważnia zaproszenia. Tak było ustalone (stacja RPi =
stanowisko administratora), ale warto o tym pamiętać przy wynoszeniu maszyny w pole.

> ### ⛔ Pułapka: dane dostępu leżą w `/var/lib/dron15`, nie w katalogu programu
>
> Usługa ma `Environment=DATA_DIR=/var/lib/dron15`. Skrypt uruchomiony bez tej
> zmiennej tworzy **własny, martwy magazyn** w `/opt/dron15/dostep.json` — zaproszenie
> powstaje, `zaproszenia()` je pokazuje, a serwer odpowiada „Nieznany kod zaproszenia".
> Zdarzyło się to przy tej właśnie zmianie; stary plik został usunięty.
>
> ```
> DATA_DIR=/var/lib/dron15 node <skrypt>
> ```
>
> ⚠ Stan dostępu jest **wczytywany raz i trzymany w pamięci** (`wczytaj()`), więc po
> zmianie pliku z zewnątrz usługę trzeba przeładować.
