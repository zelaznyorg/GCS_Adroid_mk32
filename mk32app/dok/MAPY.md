# Mapy, teren i azymut — kokpit DRON15

Stan: **2026-08-26**. Dotyczy ekranów **LOT** i **MISJA**.

Do 2026-08-24 kokpit miał jeden, bezimienny podkład: cokolwiek leżało w
`/sdcard/dron15/kafelki/{z}/{x}/{y}.png`. Nie dało się go zmienić, nie było wiadomo, skąd
pochodzi, nie było danych o wysokości terenu, a więc i o tym, czy trasa mieści się nad ziemią.
Ten dokument opisuje, co jest teraz.

---

## 1. Co widzi operator

| Element | Gdzie | Po co |
|---|---|---|
| **Podkład** — hybryda / zdjęcia / topo / mapa / noc | LOT i MISJA | treść mapy pod śladem i trasą |
| **Cieniowanie rzeźby** | LOT i MISJA | rzeźba widoczna także na zdjęciu lotniczym |
| **Warstwice** co 5/10/20/50 m | LOT i MISJA | kierunek i stromość spadku — lot na azymut |
| **Pierścień azymutu** | LOT i MISJA | kierunek geograficzny, kreska co 10°, podpis co 30° |
| **Widok przestrzenny (3D)** | MISJA | czy trasa przejdzie nad grzbietem |
| **Profil terenu pod trasą** | MISJA | prześwit nad gruntem na całej długości trasy |
| **Prześwit przy każdym punkcie** | MISJA | `120 m` (zadana) i `+45 agl` (nad gruntem) |
| **Przybliżanie i oddalanie** | LOT, MISJA, 3D | szczypnięcie **albo** klawisze `−`/`+`, 50 m … 20 km |

Podkład zmienia się dwoma drogami: chipami **na samej mapie planowania** (górny prawy róg)
albo w panelu **WARSTWY EKRANU** przy prawej krawędzi. Ustawienie przeżywa restart.

### Hybryda jest obowiązkowa

Samo zdjęcie nie mówi, jak nazywa się droga, przy której stoi operator, ani gdzie kończy się
wieś. Sama mapa kreskowa nie pokazuje, czy pole jest zaorane, czy stoi na nim las. Dlatego
**hybryda (zdjęcie + nazwy + drogi) jest podkładem domyślnym i jedynym oznaczonym jako
wymagany**. Gdy jej kafelków brakuje na karcie, kokpit mówi o tym wprost — na mapie i w panelu
warstw — zamiast po cichu pokazać samą siatkę metryczną.

Podkład bez kompletu kafelków jest **wyszarzony i nieklikalny**, tak samo jak niedziałające
zakładki w pasku widoków: widać od razu, czego brakuje.

### Przybliżanie i oddalanie

Zasięg to **ile metrów mieści się w krótszym boku widoku**. Jedna liczba rządzi mapą płaską
i widokiem przestrzennym, więc przełączenie 2D ↔ 3D nie zmienia skali pod ręką operatora.

| Gdzie | Jak |
|---|---|
| ekran LOT | szczypnięcie na mapie **albo** `+` / `−` przy lewej krawędzi; `AUTO` wraca do zasięgu dobieranego do śladu |
| ekran MISJA (2D i 3D) | szczypnięcie **albo** `−` `400 m` `+` w rzędzie u dołu mapy |

**Klawisze, nie tylko szczypnięcie** — aparaturę trzyma się w polu dwiema rękami, często
w rękawicach, a ekran ma siedem cali. Klawisz da się nacisnąć kciukiem, nie puszczając drążków.

Klawisze chodzą po **drabinie** okrągłych wartości (50, 80, 120, 200, 300, 400, 600 m,
1, 1,5, 2,5, 4, 6, 10, 15, 20 km), a szczypnięcie zmienia zasięg płynnie. Po szczypnięciu
klawisz najpierw **wraca na drabinę**, a dopiero potem przesuwa się o szczebel — inaczej
operator kończyłby z 417 m zamiast 400.

Górny szczebel to **20 km**, bo dopiero przy takim zasięgu widać ukształtowanie terenu w skali,
w której cokolwiek znaczy: pojedyncze wzniesienie ma kilkaset metrów i przy zasięgu 400 m
jest płaskie jak stół.

W widoku przestrzennym zoom zmienia **wielkość pokazywanego kwadratu terenu**, a nie odległość
kamery. Odsunięcie kamery od tego samego kwadratu nie pokazuje ani metra więcej krajobrazu.

Mapa lotu ma **podziałkę z podpisem** w lewym dolnym rogu — przy zoomie płynnym sam odcinek
bez liczby nie mówi nic.

---

## 1a. Mapa z internetu

Do 2026-08-25 kokpit czytał **wyłącznie** kafelki leżące na karcie. Teraz **dociąga brakujące
z sieci** i zapisuje je u siebie, więc od tej chwili działają też bez sieci.

> **To nie zastępuje pobrania przed wyjazdem.** W polu aparatura MK32 siedzi zwykle w sieci
> pokładowej drona i **internetu tam nie ma**. Mapa dociągnie się w domu, w aucie z telefonem
> jako punktem dostępu albo wszędzie tam, gdzie MK32 widzi sieć — ale nie na łące.
> Obejrzenie rejonu przy sieci jest więc równocześnie przygotowaniem go na lot bez sieci.

| Rzecz | Jak jest |
|---|---|
| przełącznik | panel **WARSTWY EKRANU** → „Mapa z internetu"; domyślnie **włączone**, przeżywa restart |
| gdzie ląduje | `Android/data/pl.dron15.cockpit/files/kafelki/{warstwa}/{z}/{x}/{y}` — katalog własny aplikacji, bez dodatkowych uprawnień |
| ile naraz | cztery pobrania równolegle, odstęp 80 ms — regulaminy serwerów zabraniają masowego ściągania |
| powtórki | ten sam kafelek nie jest zamawiany dwa razy; po nieudanej próbie odczekanie 60 s |
| śmieci | obrazek „Access blocked" ma kilkaset bajtów i kod 200 — odsiewany po rozmiarze i po nagłówku formatu |
| przerwane pobranie | zapis przez plik tymczasowy i `rename`, żeby obcięty kafelek nie został na karcie na zawsze |
| dane wysokościowe | tak samo, ale **tylko PNG** — patrz §3 |

Przy włączonym internecie **żaden podkład nie jest wyszarzony**: brak kafelków na karcie
znaczy tylko tyle, że dociągną się przy pierwszym pokazaniu.

### ⛔ Warunek, o którym nikt nie pomyśli: zegar aparatury

Wszystkie serwery kafelków chodzą po **HTTPS**, a HTTPS sprawdza, czy certyfikat serwera jest
ważny **w dacie, którą pokazuje urządzenie**. MK32 przychodzi z fabryki z zegarem ustawionym
na **2023 rok** i z **wyłączonym czasem automatycznym** (`auto_time = 0`, strefa `Asia/Shanghai`).
Dla takiej aparatury każdy dzisiejszy certyfikat jest „jeszcze nieważny".

Zmierzone na MK32 2026-08-26 — sieć bez zarzutu, TLS martwy:

```
zegar aparatury:  Mon Oct  2 23:27:20 CST 2023
certyfikat ważny: od Wed Jul 16 2025
    → CertificateNotYetValidException, połączenie zrywane

curl https://tile.openstreetmap.org/...   kod=000    (nie doszło)
curl http://s3.amazonaws.com/...          kod=403    (doszło — serwer odpowiedział)
```

**To nie dotyczy wyłącznie map.** Zły zegar psuje każde połączenie HTTPS z aparatury i myli
daty w logach aplikacji oraz w nazwach zapisywanych plików.

Naprawa jest po stronie Androida, nie aplikacji — kokpit **nie obchodzi** sprawdzania
certyfikatów, bo to znaczyłoby przyjmowanie dowolnego cudzego serwera jako prawdziwego:

```
Ustawienia → System → Data i godzina → Automatyczna data i godzina: WŁ.
                                     → strefa: Europe/Warsaw
```

albo, przy podłączonej aparaturze:

```bash
adb shell settings put global auto_time 1
```

Dopóki zegar jest zły, mapa **mówi o tym wprost** zamiast pokazywać pustą siatkę:

> „MAPA" nie dociąga się z sieci — zegar aparatury pokazuje 2023-10-02, więc każdy certyfikat
> HTTPS jest dla niej nieważny. Ustaw datę i strefę czasu w Androidzie. · na kartę:
> `/sdcard/dron15/kafelki/mapa`

Rozpoznawanie przyczyny siedzi w `zdiagnozujPobieranie()` ([ZrodlaKafelkow.kt](../app/cockpit/src/main/java/pl/dron15/cockpit/ui/ZrodlaKafelkow.kt))
i odróżnia trzy przypadki, które z pustego ekranu wyglądają identycznie: **zły zegar**,
**brak sieci** i **milczący serwer**. Kafelki i model terenu mają to samo rozpoznanie.

---

## 2. Podkłady i warstwy

Podkład to zestaw **warstw**, czyli katalogów kafelków. Nakładki (`opisy`, `drogi`) to
przezroczyste PNG-i kładzione na wierzchu bazy.

| Podkład | Warstwy | Do czego |
|---|---|---|
| **HYBRYDA** ⭐ | `zdjecia` + `opisy` + `drogi` | podkład podstawowy, obowiązkowy |
| ZDJĘCIA | `zdjecia` | maksimum szczegółu terenu, bez napisów |
| TOPO | `topo` | warstwice i rzeźba — **lot na azymut** |
| MAPA | `mapa` | mapa kreskowa, czytelna w pełnym słońcu |
| NOC | `noc` | ciemna mapa kreskowa, po zmroku |

Definicje: [`ui/Podklady.kt`](../app/cockpit/src/main/java/pl/dron15/cockpit/ui/Podklady.kt).

### Źródła (sprawdzone 2026-08-25, HTTP 200)

| Warstwa | Serwis | Format |
|---|---|---|
| `zdjecia` | Esri World Imagery | JPEG |
| `opisy` | Esri World Boundaries and Places | PNG z przezroczystością |
| `drogi` | Esri World Transportation | PNG z przezroczystością |
| `topo` | OpenTopoMap | PNG |
| `mapa` | OpenStreetMap standard | PNG |
| `noc` | CARTO dark | PNG |

Kolejność w adresie Esri to **`{z}/{y}/{x}`**, nie `{z}/{x}/{y}` — narzędzie trzyma dla każdej
warstwy własny wzór adresu i to jest jedyne miejsce, gdzie ta różnica występuje.

> **Warunki użycia.** Kafelki pobieramy do własnego użytku, na jeden rejon lotów, raz.
> OpenStreetMap zabrania masowego pobierania — przy większych obszarach użyć własnego serwera
> kafelków (`--zrodlo warstwa=URL`) albo gotowej paczki. Przy publikacji zrzutów ekranu
> obowiązują wymagania atrybucyjne poszczególnych serwisów.

---

## 3. Dane wysokościowe

Źródłem są kafelki **Terrarium** (`elevation-tiles-prod`): zwykłe PNG-i XYZ, w których
wysokość siedzi w barwie piksela.

```
h [m n.p.m.] = (R · 256 + G + B / 256) − 32768
```

Wybrane świadomie, bo mieszczą się w **tej samej rurze, co podkład mapy**: jedno narzędzie
pobiera, jeden katalog na karcie, żadnej nowej biblioteki i żadnego serwera w polu.

| Cecha | Wartość |
|---|---|
| Rozdzielczość źródła | ok. 30 m (SRTM / ASTER / EU-DEM zależnie od rejonu) |
| Pobierany poziom | **z12** — ok. 24 m/px na 52° szerokości |
| Waga | rejon 5 km to zwykle 1–4 kafelki, ok. 100 kB każdy |
| Format | **wyłącznie PNG** — patrz niżej |

> ⛔ **Dane wysokościowe nie mogą przejść przez kompresję stratną.** Wysokość jest zakodowana
> w dokładnej wartości trzech kanałów barwy; JPEG zamieniłby model terenu na szum o amplitudzie
> kilkudziesięciu metrów. Narzędzie **odrzuca** kafelek terenu, który przyszedł w formacie
> stratnym, a aplikacja czyta je z `inScaled = false` i wymuszonym `ARGB_8888`, żeby Android
> niczego nie przeskalował pod gęstość ekranu ani nie skwantował barwy.

### Czego ten model nie wie

To **numeryczny model terenu**, czyli goła ziemia. **Nie ma w nim drzew, masztów, linii
energetycznych ani budynków.** Prześwit liczony w kokpicie jest prześwitem *nad gruntem*
i nie zwalnia z patrzenia, co na tym gruncie stoi. Próg ostrzegawczy 30 m wzięty jest właśnie
stąd: tyle mniej więcej ma dojrzałe drzewo plus zapas, a model o drzewie nie wie.

### Jak liczy się prześwit

Misja ArduPilota niesie wysokości **względem punktu startu**, a model terenu — nad poziomem
morza. Punktem styku jest wysokość terenu **w miejscu startu**, wzięta z tego samego modelu:

```
wysokość lotu n.p.m.  = teren(dom) + wysokość zadana w misji
prześwit              = wysokość lotu n.p.m. − teren(punkt)
```

Dzięki temu prześwit jest różnicą dwóch liczb z jednego źródła i **błąd bezwzględny modelu
(rzędu kilku metrów) się skraca** — zostaje błąd względny, znacznie mniejszy. Wysokość
barometryczna z maszyny nie wchodzi w ten rachunek: przed lotem jej nie ma, a po starcie
liczy się od tego samego punktu startu, więc niczego by nie dołożyła.

Barwy prześwitu, wspólne dla znaczników, profilu i widoku 3D:

| Prześwit | Barwa | Znaczenie |
|---|---|---|
| ≥ 30 m | zielony | zapas jest |
| 0–30 m | pomarańczowy | poniżej progu ostrzegawczego |
| ≤ 0 m | **czerwony** | trasa wchodzi w zbocze |
| brak danych | szary, `— agl` | model nie pokrywa tego punktu |

---

## 4. Widok przestrzenny terenu (3D)

Chip **3D** na ekranie MISJA. Przeciągnięcie obraca i pochyla, szczypnięcie oddala.
Chip **PION ×1/×2/×3** przesadza pion — bez przesady rzeźba niskiego terenu, po jakim ta
maszyna lata, jest na ekranie niewidoczna, a to ona jest treścią tego widoku.

Co widać: siatkę terenu pokrytą **tym samym podkładem, co mapa płaska**, trasę na wysokości
lotu i **maszty do gruntu** przy każdym punkcie. Maszt jest tu najważniejszy — to on pokazuje
prześwit, którego na mapie płaskiej nie widać.

### Dlaczego nie MapLibre i nie biblioteka 3D

MK32 to Android 9 (API 28). Sprzętowo przyśpieszone `Canvas.drawVertices`, na którym stoją
zwykle takie widoki, **działa dopiero od API 29** — na tej aparaturze byłoby pustym
wywołaniem. Używamy `drawBitmapMesh`, obsługiwanego od API 18: jedna siatka 65 × 65 węzłów,
jedna tekstura 384 × 384, jedno wywołanie rysujące.

Cieniowanie rzeźby wchodzi **w teksturę**, a nie osobną warstwą, bo `drawBitmapMesh` nie
przyjmuje barw wierzchołków przy rysowaniu sprzętowym.

### Ograniczenie, o którym trzeba wiedzieć

Widok **nie sortuje trójkątów po głębi** — `drawBitmapMesh` rysuje je rzędami. Przy pochyleniu
poniżej ok. 25° i bardzo stromym zboczu bliższy grzbiet potrafi się „przebić" przez dalszy.
Na terenie, po jakim ta maszyna lata, tego nie widać; gdyby przeszkadzało — podnieść pochylenie.

Bez kafelków podkładu widok nadal działa: teren maluje się wtedy skalą barwną wysokości
(zieleń dolin → brąz → biel grzbietów).

---

## 5. Lot na azymut

**Wszystkie azymuty w kokpicie są geograficzne, nie magnetyczne.** To nie jest wybór stylu:
ta maszyna nie ma kompasu (`COMPASS_USE=0`) i bierze kurs z bazy GNSS (`EK3_SRC1_YAW=2`),
czyli względem północy geograficznej — patrz `../../CLAUDE.md`, sekcja 5.

> ⚠ Operator odczytujący kierunek z **busoli** w terenie musi doliczyć deklinację
> (w Polsce ok. +6° E). Kokpit tego nie robi, bo nie zna miejsca ani daty pomiaru busolą.

Narzędzia do lotu na kierunek:

- **Pierścień azymutu** wokół punktu startu — kreska co 10°, podpis co 30°, wyróżnione strony
  świata. Włącza się chipem **AZYMUT** albo w panelu warstw.
- **Podkład TOPO** — warstwice i rzeźba z gotowej mapy topograficznej.
- **Warstwice liczone z modelu terenu** — kładą się na **dowolnym** podkładzie, także na
  zdjęciu lotniczym. Operator widzi wtedy naraz to, co jest na ziemi, i to, jak ta ziemia
  się układa — czego żaden pojedynczy gotowy podkład nie daje. Co piąta warstwica gruba
  i podpisana, jak na mapie papierowej.
- **Cieniowanie rzeźby** — światło z 315°, 45° nad widnokręgiem. Umowa kartograficzna, przy
  której oko czyta doliny jako wklęsłe; odwrócenie kierunku daje złudzenie odwrotne, dlatego
  kierunek nie jest ustawialny.

---

## 6. Karta pamięci

```
/sdcard/dron15/kafelki/{warstwa}/{z}/{x}/{y}.{png|jpg}
/sdcard/dron15/teren/{z}/{x}/{y}.png
```

Kokpit szuka też w `getExternalFilesDir("kafelki")` i w pamięci wewnętrznej aplikacji —
pierwszy istniejący katalog wygrywa.

> **Zgodność wstecz.** Stary układ **bez** nazwy warstwy (`kafelki/{z}/{x}/{y}.png`) nadal
> działa — kokpit czyta go jako warstwę `zdjecia`. Karta przygotowana przed 2026-08-25 nie
> gaśnie. Do porządku: `python narzedzia\kafelki.py --migruj`.

### Pobranie

```bash
python narzedzia\kafelki.py --lat 52.1234 --lon 20.1234 --promien 3 --zoom 13-17
```

Domyślnie bierze **hybrydę + topo + dane wysokościowe**. Dalej:

```bash
python narzedzia\kafelki.py --stan            # co już leży w katalogach
python narzedzia\kafelki.py --wgraj           # kafelki i teren na MK32 przez ADB
python narzedzia\kafelki.py --migruj          # stary układ karty → warstwa "zdjecia"
```

| Przełącznik | Do czego |
|---|---|
| `--podklady hybryda,topo,mapa` | które podkłady pobrać |
| `--tylko-teren` | same dane wysokościowe |
| `--bez-terenu` | sam podkład |
| `--zrodlo topo=https://…/{z}/{x}/{y}.png` | podmiana adresu jednej warstwy |
| `--zrodlo-teren terrarium-alt` | zapasowy adres danych wysokościowych |
| `--zoom-terenu 13` | gęstszy model (rzadko potrzebny) |

**Wielkości.** Rejon 3 km przy z13–17 to ok. 700 kafelków na warstwę, ok. 25 MB. Hybryda to
trzy warstwy, ale `opisy` i `drogi` ważą po 1–4 kB. Dane wysokościowe dla tego samego rejonu
to zwykle jeden kafelek.

Przy każdym pobraniu narzędzie dopisuje `kafelki/manifest.json`: rejon, promień, poziomy,
warstwy, adresy źródeł i datę. **Aplikacja tego pliku nie czyta** — czyta go człowiek, który
za pół roku będzie chciał wiedzieć, czy podkład obejmuje nowy rejon lotów i skąd pochodzi.

### ⛔ `--wgraj` idzie archiwum, nie katalogiem

`adb push KATALOG/. /sdcard/...` **nie nadaje się** do kafelków. Zmierzone na MK32
2026-08-26: 671 plików, awaria po **9 min 29 s** z `libc++abi: terminating due to uncaught
exception of type std::bad_alloc` i **zero plików na karcie**. Narzut na plik zjada pamięć
samego `adb`, więc im lepiej pobrany rejon, tym pewniejsza porażka.

`--wgraj` pakuje więc wszystko w jeden `.tar`, wysyła go jednym strzałem i rozpakowuje
`/system/bin/tar` na aparaturze, po czym kasuje archiwum. **Te same dane przechodzą w 0,3 s.**
Gdy rozpakowanie się nie uda, archiwum **zostaje** na karcie — żeby dało się dokończyć ręcznie:

```bash
adb shell "cd /sdcard/dron15 && tar -xf /sdcard/dron15_kafelki.tar && rm /sdcard/dron15_kafelki.tar"
```

> ⚠ **Git Bash w Windows psuje ścieżki Androida** — `/sdcard/...` zamienia się w
> `C:/Program Files/Git/sdcard/...`, a `adb` melduje wtedy `remote secure_mkdirs failed`,
> choć wina jest po stronie powłoki. Ustawić `MSYS_NO_PATHCONV=1` albo użyć PowerShella.
> Gdy podpięta jest więcej niż jedna maszyna (np. emulator środowiska testowego),
> wskazać aparaturę: `ANDROID_SERIAL=8756ccce python narzedzia\kafelki.py --wgraj`.

---

## 7. Wydajność

Aparatura ma 7 cali i Androida 9, a mapa dzieli ekran z obrazem z kamery — dlatego:

| Rzecz | Rozwiązanie | Koszt |
|---|---|---|
| kafelki | wczytywane w tle, brakujący zwraca `null` i ląduje w kolejce | rysunek nigdy nie czeka na dysk |
| pamięć podręczna kafelków | 220 kafelków | ok. 55 MB |
| model terenu | kafelek rozpakowany **raz** do tablicy metrów, 16 w pamięci | 4 MB |
| cieniowanie | siatka 65 × 65, jeden obrazek rozciągany przy rysowaniu | ok. 25 tys. działań, < 1 ms |
| warstwice | jedna ścieżka na wszystkie odcinki danego rodzaju | 2 wywołania rysujące |
| widok 3D | jedno `drawBitmapMesh` | 8192 trójkątów |

Nakładki terenu są **wyłączone domyślnie** i nie liczą się w miniaturze mapy — 190 × 126 dp
to za mało, żeby warstwice cokolwiek powiedziały.

---

## 8. Co jest sprawdzone, a co nie

**Sprawdzone:**

- 35 testów jednostkowych na JVM w `MapyTest.kt` (80 w całym module): dekodowanie Terrarium wobec opisu formatu,
  numeracja kafelków XYZ wobec numeracji OpenStreetMap, interpolacja w siatce, kierunek
  światła w cieniowaniu, położenie warstwic, rachunek prześwitu (w tym kolizja z terenem
  i luki w modelu), azymut, rzut perspektywiczny.
- Wszystkie sześć adresów źródłowych i adres danych wysokościowych — odpowiedź serwera
  sprawdzona 2026-08-25.
- Pobranie, układ katalogów, wykrycie formatu, manifest i migracja starego układu —
  przejechane na prawdziwym rejonie (51,26 N / 19,05 E).
- Wartości z kafelka `12/2264/1366` rozpakowane niezależnie od aplikacji: 165–191 m n.p.m.,
  co zgadza się z rzeźbą tego rejonu.

**Sprawdzone w emulatorze MK32** (Android 9 x86_64, 1280 × 800, kafelki i teren wgrane
na `/sdcard/dron15`, telemetria z symulatora):

| Zrzut | Co pokazuje |
|---|---|
| [`zrzuty/mapy_lot_hybryda.png`](zrzuty/mapy_lot_hybryda.png) | ekran LOT, miniatura na podkładzie hybrydowym |
| [`zrzuty/mapy_panel_warstw.png`](zrzuty/mapy_panel_warstw.png) | panel warstw: wybór podkładu, brakujące wyszarzone |
| [`zrzuty/mapy_misja_profil.png`](zrzuty/mapy_misja_profil.png) | MISJA: trasa, prześwit przy punktach, pas profilu |
| [`zrzuty/mapy_misja_3d.png`](zrzuty/mapy_misja_3d.png) | MISJA: widok przestrzenny z masztami do gruntu |
| [`zrzuty/mapy_misja_azymut.png`](zrzuty/mapy_misja_azymut.png) | MISJA: pierścień azymutu wokół domu |


- podkład **hybrydowy składa się poprawnie** — zdjęcie + nazwa drogi + linie dróg;
- podkłady bez kafelków (`mapa`, `noc`) są wyszarzone i nieklikalne, panel warstw wypisuje,
  czego brakuje;
- **widok przestrzenny rysuje się i obraca** — `drawBitmapMesh` na API 28 działa, teren jest
  pokryty tym samym zdjęciem, co mapa płaska, maszty punktów sięgają gruntu;
- **prześwit liczy się z prawdziwych danych**: teren 81–83 m n.p.m. w rejonie symulatora,
  punkty na 30 m nad startem dały `+28…+30 agl`, a pas profilu — `min. prześwit +28 m`
  w barwie ostrzegawczej (poniżej progu 30 m). Liczby zgadzają się między znacznikiem,
  profilem i masztem w 3D;
- **pierścień azymutu**: kreski, podpisy co 30°, wyróżnione strony świata, środek na domu;
- ustawienia (podkład, warstwice, widok) **przeżywają restart aplikacji**;
- zero wyjątków w `logcat` przez cały przebieg.

Dwie kolizje układu wyszły dopiero w emulatorze i zostały poprawione: rząd chipów chował
`2D`/`3D` pod panelem wyszukiwania, a sterowanie widoku przestrzennego nachodziło na rząd
zasięgu.

**Niesprawdzone:**

- ⚠ **Nic z tego nie chodziło na prawdziwej aparaturze MK32** — emulator to nie ta sama
  płyta ani nie ten sam układ graficzny. `drawBitmapMesh` w emulatorze działa; na sprzęcie
  do potwierdzenia.
- Płynność przy równoczesnym obrazie z kamery (w emulatorze obrazu nie było) i przy pełnej
  karcie liczącej kilka tysięcy kafelków.
- Czy `elevation-tiles-prod` będzie dostępne za rok — stąd `--zrodlo-teren` i możliwość
  podania własnego adresu.

---

## 9. Do rozważenia

- **NMT 1 m z GUGiK** zamiast Terrarium dla rejonów w Polsce: model z lotniczego skaningu
  laserowego, trzydziestokrotnie gęstszy, dostępny bezpłatnie. Wymagałby narzędzia, które
  przeliczy go na kafelki Terrarium — aplikacja nie musiałaby się zmieniać wcale.
- **Numeryczny model pokrycia terenu** (z drzewami i budynkami) zamiast modelu gołej ziemi.
  Zdjąłby główne zastrzeżenie z sekcji 3.
- **PMTiles** zamiast luźnych plików — jeden plik na warstwę zamiast tysięcy. Pytanie otwarte
  od `PLAN.md` §12; przy dzisiejszych rozmiarach nie boli.
