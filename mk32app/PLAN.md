# DRON15 Cockpit — plan aplikacji

**Wersja planu:** 3.1, 2026-08-20

| Wersja | Co się zmieniło |
|---|---|
| 1.0 | jedna aplikacja zamknięta na MK32 |
| 2.0 | architektura klient-serwer z serwerem **na** MK32, misje, retransmisja obrazu |
| **3.0** | **aplikacja samodzielna i kompletna**; serwer przeniesiony do **opcjonalnej stacji GCS** (RPi 5); władza nad maszyną zostaje na MK32 |
| **3.1** | **zakres stacji zamknięty**: podgląd + monitory HDMI + archiwum. Bez Androida, bez QGroundControla, bez edytora misji. Dostęp zdalny: **WireGuard na routerze** (decyzje 4 i 5, §10) |

> **Korekta wobec wersji 2.0.** Zapisałem tam zasadę „kokpit na MK32 jest klientem
> własnego serwera". Przy tej topologii to błąd — wiązałby aparaturę z komponentem,
> bez którego ma działać w pełni. Zasadę zastępuje: **aplikacja ma kompletny rdzeń
> własny, a udostępnianie jest modułem, który można wyłączyć.**

---

## 1. Zasada nadrzędna

> **Aplikacja na MK32 działa samodzielnie i ma pełną funkcjonalność.**
> Stacja GCS jest dodatkiem. **Władza nad maszyną należy do operatora na MK32**
> i przechodzi na stację wyłącznie przez świadome przekazanie.

Pełny opis: [dok/WLADZA.md](dok/WLADZA.md).

---

## 2. Dwa tryby

### SAMODZIELNY — domyślny, kompletny

```
   FC Cobra H743 ──radio SIYI──► MK32 ──► kokpit · misje · głowica · checklista · zapis
   ZR30 ──────────LAN──────────►
```

Obraz, telemetria, sterowanie głowicą, planowanie i wysyłka misji, checklista
przedlotowa, mapa offline, zapis `.tlog`. Bez stacji, bez internetu, bez niczego
poza aparaturą i dronem. **Nic nie jest wyłączone z powodu braku stacji.**

### SPAROWANY — z opcjonalną stacją GCS

```
   FC ──radio──► MK32 ──telemetria──► GCS (RPi 5) ──► monitory · przeglądarki · internet
                   ▲                        │
                   └── komendy tylko po przekazaniu władzy
   ZR30 ──LAN──► MK32
        └──LAN───────────────────────► GCS (własny strumień prosto z kamery)
```

Stacja dokłada to, czego 7 cali w słońcu nie zrobi dobrze: **duże monitory HDMI,
podgląd dla wielu osób przez przeglądarkę, archiwum**. Zakres zamknięty decyzją
z 2026-08-20 — **bez Androida, bez QGroundControla, bez planowania misji** (§10, decyzja 4).
Opis stacji: [dok/GCS_RPI5.md](dok/GCS_RPI5.md).

**Rozłączenie stacji w dowolnym momencie, także w locie, nie ma żadnego wpływu
na maszynę ani na kokpit.**

---

## 3. Dlaczego obraz może iść bokiem, a komendy nie

ZR30 wydaje **do czterech strumieni z tego samego adresu RTSP** i stacja siedzi w tej samej
sieci pokładowej — niech więc bierze obraz prosto z kamery. Nie obciąża MK32 i nie psuje się,
gdy aparatura ma inne zajęcie.

Z komendami jest odwrotnie. Gdyby stacja miała własny kanał do kontrolera lotu, zasada
„władza zostaje na MK32" byłaby **dobrym obyczajem**, nie własnością układu. Przy jednej
drodze — przez filtr w aplikacji na aparaturze — jest egzekwowalna.

To także upraszcza aplikację: **retransmisja obrazu w sieć jest zadaniem stacji**, więc
MK32 nie musi w ogóle przepakowywać strumienia — **w żadnym trybie**. Po decyzji 5
(stacja stoi przy operatorze, dostęp zdalny idzie tunelem do jej sieci) wariant
„MK32 wypycha obraz przez SRT" znika z planu, a wraz z nim **ryzyko porzuconego
`ffmpeg-kit` — całkowicie, nie tylko ze ścieżki krytycznej.**

---

## 4. Zakres aplikacji na MK32

**Robi:**
- obraz RTSP z ZR30, pełnoekranowo, z automatycznym wznawianiem
- telemetria MAVLink z jednostki naziemnej (UDP 19856), OSD na obrazie
- panel bezpieczeństwa: bateria, GNSS, EKF, dostępność RTL, jakość łącza
- sterowanie głowicą po UDP, **z pominięciem kontrolera lotu**
- komendy do FC: tryb, RTL, pauza misji — z potwierdzeniem przytrzymaniem
- **misje**: mapa offline, podgląd i edycja trasy, wysyłka i pobranie, geofence
- checklista przedlotowa liczona z parametrów
- zapis `.tlog` na kartę TF
- **udostępnianie** telemetrii innym klientom + przekazywanie władzy (moduł, wyłączalny)

**Nie robi:**
- uzbrajania z ekranu — zostaje na przełączniku CH9 (`RC9_OPTION=153`)
- zapisu parametrów — to `..\tools\fc_write_params.py`, z logiem
- kalibracji czujników — to Mission Planner
- obsługi dodatkowych monitorów i podglądu dla wielu widzów — **to funkcja stacji**

---

## 5. Misje

Pełny opis: [dok/MISJE.md](dok/MISJE.md). Standardowy protokół MAVLink, więc trasy
wymieniają się z QGC i Mission Plannerem plikiem `.plan`.

**Planowanie misji w całości na MK32** — decyzja 4 z 2026-08-20. Podgląd trasy na mapie,
postęp misji, pauza, wznowienie, skok do punktu, dodawanie punktów, trzy wzorce
(orbita tutaj, siatka z obrysu, powrót).

**Stacja misji nie planuje.** Trasę przygotowaną wygodniej — myszą, na dużym ekranie —
robi się w QGroundControlu albo Mission Plannerze **na zwykłym komputerze** i przenosi
plikiem `.plan`. To ten sam standardowy protokół, więc nie wymaga ani jednej linijki
naszego kodu po stronie stacji.

**Każda trasa wymaga zatwierdzenia na MK32.** Wysyłka do maszyny to komenda, więc
podlega tej samej władzy co wszystko inne.

> **Ograniczenie tej maszyny:** tryb AUTO wymaga pozycji, a pozycja wymaga kursu, który
> pochodzi **wyłącznie z bazy GNSS** (`EK3_SRC1_YAW=2`, brak kompasu). Aplikacja blokuje
> wysłanie i uruchomienie misji przy niedostępnym kursie — bo FC i tak odmówi.

---

## 6. Interfejs

System projektowy **2.0**: [dok/UI.md](dok/UI.md). Audyt, z którego wynikł:
[dok/AUDYT_UI.md](dok/AUDYT_UI.md). Stara makieta HTML (`dok/makieta.html`) opisuje
wersję 1.0 — obowiązuje kod i zrzuty z emulatora.

Przemysłowo-wojskowy, pod obsługę w terenie: obraz jest tłem, mapa i obraz zamieniają się
miejscami, ostre naroża i linie włosowe zamiast plam koloru, cele dotykowe min. 64 dp,
kolor niesie znaczenie a nie dekorację, nic nieodwracalnego przez przypadek (przytrzymanie
zamiast dotknięcia), degradacja widoczna a nie ukryta — **każda wartość zna swój wiek**.

Sześć widoków: **LOT · MISJA · KAMERA · PRZED LOTEM · RC · DIAGNOSTYKA**.
USTAWIENIA wchodzą razem z modułem udostępniania (M5); do tego czasu adresy podaje się
przy uruchomieniu.

Zasada dopisana w 2026-08-19, po audycie: **ekran pokazuje, sprzęt wykonuje.** Funkcji
siedzącej na fizycznym przełączniku MK32 nie dublujemy przyciskiem ekranowym — stąd
panel RC ([dok/RC_PRZYPISANIA.md](dok/RC_PRZYPISANIA.md)), który mówi aplikacji, co jest
na kanałach.

Element dodany w wersji 3.0: **pas władzy** — dopóki steruje ktoś inny, pas stanu
zmienia kolor i pokazuje kto, z przyciskiem ODBIERZ zawsze pod ręką.

---

## 7. Stos technologiczny

| Warstwa | Wybór | Uzasadnienie |
|---|---|---|
| Aplikacja MK32 | **Kotlin**, minSdk 28 / target 33, Compose | Android 9 to twarde ograniczenie MK32 |
| Wideo na MK32 | **libVLC** | ZR30 domyślnie nadaje H.265; RTSP z HEVC w ExoPlayerze bywa zawodne |
| MAVLink | **`io.dronefleet.mavlink`** | czysta Java, MAVLink 2, dialekt ardupilotmega |
| Mapa na MK32 | **własny rysunek kafelków rastrowych** z karty TF (nie MapLibre) + dane wysokościowe Terrarium | teren bez internetu, bez dokładania bibliotek na Androida 9 — [dok/MAPY.md](dok/MAPY.md) |
| Udostępnianie | UDP 14550 / TCP 5760 + WebSocket JSON | QGC i Mission Planner podłączają się bez naszego kodu |
| Stacja GCS | **RPi 5** + MediaMTX + klient webowy (React) + Chromium w kiosku | podgląd i archiwum, **bez Androida** — patrz [dok/GCS_RPI5.md](dok/GCS_RPI5.md) |
| Dostęp zdalny | **WireGuard na routerze** (model NRK) | decyzja 5; działa z sieci ze stałym adresem, **nie z pola na 4G za CGNAT** — [dok/WIDEO.md](dok/WIDEO.md) |

---

## 8. Etapy

| Etap | Zakres | Kryterium odbioru |
|---|---|---|
| **M0** | rozpoznanie: łącza, kodek kamery, rozdzielczość ekranu, **czy port 19856 obsługuje wielu klientów naraz** | trzy łącza potwierdzone; wiadomo, czy `0x21` przyjmuje H.264 i czy stacja może podłączyć się do telemetrii równolegle z MK32 |
| **M1** | kokpit: wideo + telemetria + OSD | obraz poniżej 200 ms, komplet danych na ekranie |
| **M2** | głowica po UDP | pitch/yaw/zoom/foto/REC **przy `MNT1_TYPE=0`** — dowód niezależności od FC |
| **M3** | bezpieczeństwo: banery, wartownicy, checklista przedlotowa | werdykt zgodny z ręcznym `fc_read_params.py`; wartownik GNSS wyzwala się |
| **M4** | misje na MK32: mapa offline, trasa, wysyłka i pobranie, geofence | **misja z naszej aplikacji otwiera się w QGC bez zmian** |
| — | **★ aplikacja jest tu kompletna i samowystarczalna** | dalsze etapy niczego jej nie odbierają |
| **M5** | udostępnianie + **władza**: rozgałęzienie MAVLink, dwutorowy filtr komend, parowanie, przekazanie i odebranie, czujka obcych komend | QGC z laptopa odbiera; bez przekazania nie wyśle ani jednej komendy; odebranie lotu i kamery działa osobno i w każdych warunkach; komenda z pominięciem filtra daje baner |
| **M6** | stacja GCS na RPi 5: podgląd, monitory, archiwum | dwa monitory + przeglądarka; rozłączenie stacji nie rusza kokpitu |
| **M7** | dostęp zdalny przez **WireGuard na routerze** | strona, telemetria i obraz przez tunel; MTU tunelu potwierdzone pomiarem. *Edytor misji na stacji — skreślony decyzją 4* |
| **M8** | diagnostyka, wzorce misji, polerka | — |

**Kolejność jest celowa.** Najpierw kompletna aplikacja samodzielna, dopiero potem
udostępnianie. Odwrotna kolejność skończyłaby się aplikacją, która bez stacji nie działa
— czyli dokładnym przeciwieństwem założenia.

---

## 8a. Stan realizacji — 2026-08-19

| Etap | Stan |
|---|---|
| M0 rozpoznanie | ⏸ narzędzia gotowe, **niesprawdzone na sprzęcie** — wymaga drona pod napięciem |
| M1 kokpit | 🔶 **przebudowany po audycie**, sprawdzony w emulatorze; obraz z kamery nadal niesprawdzony |
| M2 głowica na osobnym ekranie | 🔶 **ekran KAMERA gotowy** (krzyżak, zoom, nastawy, wybór strumienia); nie rozmawiał z ZR30 |
| M3 bezpieczeństwo + checklista | ✅ **działa** — testy + przebieg w emulatorze na parametrach z pliku odniesienia |
| M4 misje | 🔶 **mapa i podgląd trasy są** (układ lokalny, ślad, dom, dystans i namiar); edytor i wysyłka misji — nie |
| M5 udostępnianie i władza | 🔶 prototyp w Pythonie działa (`mav_router.py`), w aplikacji nie ma |
| **M6 serwer podglądu (RPi 5)** | 🔶 **napisany, telemetria sprawdzona na symulatorze** — `serwer/`, opis w [dok/SERWER_PODGLADU.md](dok/SERWER_PODGLADU.md). Rdzeń przeniesiony z działającego systemu w `C:\Soft` (projekt NRK). **Tor obrazu niesprawdzony** — wymaga drona. *Waydroid skreślony decyzją 4 z 2026-08-20* |
| M7–M8 | ⬜ |
| — poza planem | ✅ **panel RC** ([dok/RC_PRZYPISANIA.md](dok/RC_PRZYPISANIA.md)) — okazał się warunkiem zasady „ekran nie dubluje przełącznika" |

Poza planem, bo okazało się konieczne: **środowisko testowe**
([dok/SRODOWISKO_TESTOWE.md](dok/SRODOWISKO_TESTOWE.md)) — trzy poziomy sprawdzania,
z których działają dwa.

### Przebudowa interfejsu — 2026-08-19

Audyt: [dok/AUDYT_UI.md](dok/AUDYT_UI.md) (10 braków funkcjonalnych, 13 usterek użytkowych).
System projektowy podniesiony do wersji 2.0: [dok/UI.md](dok/UI.md).

Co doszło do aplikacji:

| Rzecz | Dlaczego |
|---|---|
| **mapa** w układzie lokalnym: ślad, dom, dystans i namiar, siatka, podziałka | pilot nie widział, gdzie jest maszyna — przy kursie wyłącznie z GNSS to jedyna nawigacja ratunkowa |
| **zamiana mapy z obrazem** jednym dotknięciem, wspólna dla LOT i MISJA | zasada 2 systemu projektowego, dotąd niewdrożona |
| **dekodowanie `RC_CHANNELS`** i ekran RC | nie było widać ani jednej pozycji przełącznika |
| **`COMMAND_ACK`** i stan komendy na ekranie | RTL leciał „w ciemno" |
| **LĄDUJ** i **PRZERWIJ AUTOMAT** (→ LOITER/AltHold) | z ekranu nie dało się odwołać automatu |
| **ekran KAMERA**: krzyżak prędkościowy, zoom, nastawy pitch, wybór strumienia | `KlientSiyi` miał te funkcje, ekranu nie było |
| **wiek każdej wartości** (przygasa po 2 s, kreski po 10 s) | zasada 6, dotąd tylko dla pola „łącze" |
| **blokada przycisków bez pokrycia** z podanym powodem | RTL był aktywny także bez kursu GNSS, choć maszyna i tak by odmówiła |
| czas lotu, zużycie pakietu z paskiem, róża kursu ze strzałką na dom | brakowało podstaw do decyzji o powrocie |

Zrzuty: `dok/zrzuty/nowy_lot.png`, `nowy_misja.png`, `nowy_kamera.png`, `nowy_rc.png`,
`nowy_przed.png`, `nowy_diag.png`.

### Dziesiąta tura — ekran uruchamiania z logo producenta (2026-08-28)

Logo **AEROTHINK** przy starcie, kokpit wstaje pod spodem, zasłona schodzi po gotowości.

**Trzy fazy, bez czerni między nimi:**

1. `res/drawable/tlo_startowe.xml` jako `windowBackground` — logo jest **od chwili
   otwarcia okna**. Bez tego przed pierwszą klatką Compose było **ponad sekundę czerni**.
2. `ui/EkranStartowy.kt` przejmuje rysowanie: to samo logo w tym samym rozmiarze,
   pasek postępu bez określonego końca i wersja aplikacji.
3. Zasłona schodzi łagodnie, gdy tor obrazu stoi.

**Pasek postępu i wersja wzmocnione** po uwadze Toma: pasek z 2 na 4 dp, wersja z 12 na
15 sp i wyróżniona kolorem akcentu. Pod paskiem doszedł **opis etapu** — „uruchamianie
toru obrazu”, potem „łączenie z maszyną”. Pasek mówi „cos się dzieje”, opis mówi **co**:
przy zawieszeniu od razu widać, na czym stanęło.

Rozmiary są **celowo zestrojone**: bitmapa 480 px, tło okna 240 dp × 2,0 (gęstość
systemowa) i Compose 356 dp × 1,348 (gęstość nadpisana przez aplikację) dają te same
480 px — logo nie skacze w chwili przejęcia.

⛔ **Zasłona nie ma prawa zablokować kokpitu.** Po 4,5 s schodzi niezależnie od gotowości:
kokpit steruje maszyną i musi być osiągalny także wtedy, gdy coś w inicjalizacji zawiodło.

⚠ **Logo dostało wariant na ciemne tło.** Oryginał jest projektowany na biel — napis
AEROTHINK i kontury śmigieł są czarne i na tle kokpitu znikały. Zamienione zostały
**wyłącznie piksele praktycznie czarne i achromatyczne**; czerwień drona i srebrny
gradient litery A są nietknięte. Pierwszy, łagodniejszy próg łapał też ciemniejszą część
gradientu i zostawiał tam ziarno. Oryginał: `grafika/LogoAerothink.png`.

---

### Dziewiąta tura — wiatr w okręgu, piktogramy (2026-08-28)

Dwie prośby Toma, obie o to samo: **mniej szukania i mniej słów**.

**Wiatr wrócił z taśmy kursu do okręgu położenia** — strzałka obiega to samo koło,
na którym pilot czyta położenie, kierunek podany **względem dziobu**, grot do środka,
bo wiatr napiera. Prędkość przy ikonie nad kołem.

**Podpisy tekstowe zastąpione piktogramami** w pasie przyrządów i rzędzie liczb —
osiem nowych ikon. Powód mocniejszy niż oszczędność miejsca: *„jak będziemy dodawać
tłumaczenie, piktogramy same się bronią"*. Zostają `JOKER`, `BINGO` i diagnozy w rodzaju
`ciężki tył`, bo to treść, nie etykiety.

Dwie ikony trzeba było przerysować **po zobaczeniu ich na ekranie**: przy 13 dp suwak gazu
czytał się jako `+`, a waga na klinie jako kreska.

---

### Ósma tura — palety i czytelna pozycja (2026-08-28)

Etap 6 zamyka wariant 1. **Pięć palet** zamiast dwóch: do `CIEMNY` i `JASNY` doszły
**DZIEŃ** (pełne słońce — zero przezroczystości, czerń zamiast grafitu), **NOC**
(bursztyn na czerni, zbita jasność) i **NVG** (ciemna czerwień, zero bieli i błękitu —
biały piksel zasypia gogle). Klawisz MOTYW cykluje, bo w polu nie ma czasu na panel.

W NVG stany różnią się **jasnością, nie odcieniem** — i to jest powód, dla którego pasek
czujników z etapu 4 rysuje sprawny czujnik konturem, a uszkodzony wypełnieniem.

Do tego zgłoszona przez Toma wada: MGRS w rzędzie liczb miał **9 sp** i był nie do odczytania.
Dotknięcie bloku pozycji otwiera teraz okno z **WGS84, DMS i MGRS po 24 sp** — bo to jedyna
rzecz na ekranie, którą przepisuje się komuś przez radio.

Przy okazji wyszła pułapka warta zapamiętania: **`Dialog` nie dziedziczy `LocalDensity`**
nadpisanego w `MainActivity`, a na tym nadpisaniu stoi cały układ tej aplikacji. Okno
rysowało się w innej skali niż kokpit i zasłaniało komendy RTL i LĄDUJ. Dotyczy każdego
przyszłego dialogu, nie tylko tego.

---

### Siódma tura — zdrowie, ogrodzenie i wiatr (2026-08-28)

**Tom wybrał wariant 1** — dokładamy do makiety M3, bez przebudowy kadru na HUD.
Etapy 4 i 5 z [dok/PROPOZYCJA_LOT.md](dok/PROPOZYCJA_LOT.md); etap 7 odpadł wraz z wariantem 2.

Weszły: **pasek czujników** z masek `SYS_STATUS` (te same 12 bajtów, które kokpit przez
pół roku przeskakiwał), **geofence** z naruszeniem i zapasem do granicy, **wiatr**
z uśrednionego przechyłu w zawisie, **wibracje** i **cel automatu**. 152 testy.

Trzy rzeczy wyszły dopiero z ekranu, nie z testów:

- pasek dziewięciu czujników zjadał 130 dp belki i łamał „WIB" na „W / IB" — teraz rośnie
  **dopiero przy usterce**, a wibracje przeniosły się do pasa zapasu;
- `BAR` i `BAT` dawały dwa nierozróżnialne „B" — przy usterce idzie pełny skrót,
  a **czas lotu ustępuje mu miejsca**;
- **JOKER i BINGO skakały na 0:00**, bo średni prąd liczyłem jako `zużycie / czas lotu`,
  a licznik mAh liczy od podłączenia pakietu, nie od startu — 2100 mAh przy 13 s to 581 A.

To trzeci raz, gdy testy na relacjach przepuściły błąd widoczny gołym okiem. Relacje
między błędnymi liczbami też się zgadzają — stąd nowe testy na wartości bezwzględne.

⚠ Wszystko sprawdzone **tylko na emulatorze**: aparatura była zajęta przez równoległą
sesję pracującą nad torem wideo. `Wiatr.WSPOLCZYNNIK` jest **dobrany, nie zmierzony**.

---

### Szósta tura — przyrządy zapasu (2026-08-26)

Propozycja: [dok/PROPOZYCJA_LOT.md](dok/PROPOZYCJA_LOT.md). Wdrożone etapy 1–3.

Punkt wyjścia był taki, że kokpit **pokazywał to, co pokazuje każdy kokpit, i nic z tego,
co wie o tej konkretnej maszynie**. Trzy fakty to ustawiły:

- jedyny wskaźnik paliwa na ekranie LOT wisiał na czujniku, o którym `CLAUDE.md` poz. 37
  mówi, że jest martwy;
- działający licznik — `zuzycieMah` — był dekodowany od początku i **nie miał ani jednego
  odwołania w katalogu `ui/`**. Tak samo `pradA` i `gazProc`;
- maszyna spadła z 58 m, bo nasycił się mikser, a wielkość, która to zapowiadała, szła
  w telemetrii przez cały czas w wiadomości, której nie dekodowaliśmy.

Weszły: **ZAPAS CIĄGU** i **ROZRZUT** z `SERVO_OUTPUT_RAW` (z rozkładem na trzy składowe,
jak `fc_balans.py`, tylko na żywo), **ENERGIA** z **JOKER** i **BINGO**, oraz **alarmy
dźwiękowe** — audyt UI zgłaszał ich brak jako F10 jeszcze 2026-08-19.

Sprawdzone na symulatorze odtwarzającym lot 3 i **na prawdziwym MK32**
(`dok/zrzuty/zapas_mk32.png`): przy przebiegu z poz. 45 przyrząd pokazuje **ROZRZUT 173 µs**
— tę samą liczbę, którą zapisano dla okna „zawis 58 m tuż przed" — i zapala bursztyn
przy zapasie 77 µs.

Dwa błędy wyszły dopiero na ekranie, nie w testach: `return@Row` z lambdy kompozycyjnej
(ta sama pułapka, co w `EkranMisji`) i **pomyłka jednostek ×1000 w dwóch miejscach naraz**,
która dała „JOKER 61097:33". Testy jej nie złapały, bo sprawdzały relacje między liczbami,
a relacje między błędnymi liczbami też się zgadzają. Dopisany test na wartości bezwzględne.

⚠ **Dekodowanie jest potwierdzone, zamawianie strumienia nie** — symulator nadaje
bezwarunkowo, a na maszynie `SERIAL6_OPTIONS = 4096` może zignorować `SET_MESSAGE_INTERVAL`.
To pierwsze zadanie w [TODO.md](TODO.md) §5c.

Przy okazji rozstrzygnięte 5b.9: podłączony MK32 melduje
`ro.product.cpu.abilist = arm64-v8a,armeabi-v7a,armeabi` — **aparatura jest 64-bitowa**,
więc 36,8 MB `armeabi-v7a` w APK wydania to balast.

---

### Piąta tura — audyt (2026-08-26)

Pełny raport: [dok/AUDYT_M3.md](dok/AUDYT_M3.md). Zadania: [TODO.md](TODO.md) §5b.

Audyt całego modułu `app/cockpit` — 12 189 linii, 105 testów, sesja na emulatorze
z logcatem, rozbiór APK, niezależna kontrola przeliczeń współrzędnych.
Trzy blokady potwierdzone objawem, nie tylko lekturą kodu:

| | Znalezisko | Skąd wiadomo |
|---|---|---|
| **B2** | Checklista przedlotowa żąda **archiwalnego** mapowania silników (34/36/33/35) zamiast potwierdzonego lotem (36/33/34/35) i każe je pilotowi przywrócić — a złe przypisanie wyjść to udokumentowana przyczyna salta z 2026-08-15 | porównanie `preflight_rules.json` z `CLAUDE.md` sekcja 1 |
| **B1** | Podniesienie wysokości punktu trasy **kasuje pozostałe punkty** — `2 pkt · 161 m` → `1 pkt · 0 m` | zrzuty `audyt_misja.png`, `audyt_plus1.png` |
| **B3** | Włączenie drugiej warstwy ekranu **cofa pierwszą** | zrzuty `audyt_warstwy1.png`, `audyt_warstwy2.png` |

B1 i B3 to jedna przyczyna w sześciu miejscach: `Modifier.pointerInput(klucze)` bez
`rememberUpdatedState`. Ostrzeżenie o tej pułapce jest w `Elementy.kt:632` od poprzedniej
tury — cztery komponenty je stosują, sześć nie. **Brak reguły, nie przeoczenie.**

Dwie liczby warte zapamiętania:

- **APK wydania 96 MB**, z czego **77 MB to dwie kopie `libvlc.so`** (arm64 + armeabi-v7a)
  przy `isMinifyEnabled = false`. Po sprawdzeniu ABI aparatury i włączeniu R8 → ok. 50 MB.
  To domyka warunek odbioru postawiony w §7 przy Compose Destinations.
- **766 linii bez ani jednego testu**: `Wspolrzedne`, `Misja`, `MagazynMisji`, `TransferMisji`
  — czyli przeliczanie pozycji, model trasy, zapis `.plan` i wysyłka misji do maszyny.
  B1 siedzi dokładnie w nietestowanym `Misja`.

Co audyt potwierdził jako **dobre**: dekodowanie MAVLink co do bajtu, przeliczenia
UTM/MGRS zgodne z niezależną implementacją na pięciu punktach kontrolnych, obsługa libVLC
w całości poza wątkiem głównym, brak nawrotu awarii `Stack.pop`, memoizacja liczenia terenu.

---

### Czwarta tura — przekazanie M3 (2026-08-24)

Projektant oddał **przekazanie do kodu**: [dok/PRZEKAZANIE_M3.md](dok/PRZEKAZANIE_M3.md).
Wdrożone kroki 1–6 i 8 z §10 tego dokumentu; krok 7 (adresy i POI) czeka na decyzję.

To nie była korekta wyglądu, tylko **zmiana tego, czym aplikacja jest na trzech ekranach**:

| Rzecz | Było | Jest |
|---|---|---|
| wybór ekranu | pas zakładek na kadrze | menu w belce górnej, 180 dp |
| horyzont | karta 212 × 150 z tłem | **przezroczysty okrąg 132 dp** wyśrodkowany w poziomie |
| mapa na LOT | karta w narożniku | miniatura 190 × 126 na krawędzi spodu, chowana do uchwytu |
| komendy | pas klawiszy 104 dp | 44 × 40 dp, pion przy krawędzi, przytrzymanie 1200 ms |
| FOTO + REC | dwa klawisze 64 dp | **jeden okrągły klawisz migawki**, przytrzymanie zmienia tryb |
| **MISJA** | lista punktów do oglądania | **planowanie na mapie**: dotknięcie dokłada punkt, `.plan`, wysyłka do maszyny |
| **KAMERA** | dok z zakładkami | zakładki na kadrze, szuflada z góry, pas orientacji |
| współrzędne | brak | dziesiętne + **MGRS** + DMS, parser w obie strony |
| motyw | jeden, ciemny | **dwa, przełączane w belce**, ustawienie przeżywa restart |

Dwie rzeczy dołożone przy okazji, bo bez nich reszta nie działa:

- **Protokół misji MAVLink** (`net/mavlink/TransferMisji.kt`) — `MISSION_COUNT` →
  `MISSION_REQUEST_INT` → `MISSION_ITEM_INT` → `MISSION_ACK`, w obie strony, z dozorcą czasu
  na każdy krok. Punkt 0 to zawsze pozycja domu, tak jak w QGC.
- **Konwersja współrzędnych** (`domain/Wspolrzedne.kt`) — UTM/MGRS wg wzorów szeregowych,
  liczone lokalnie, bo aparatura nie ma sieci.

Sprawdzone w emulatorze MK32 na telemetrii z symulatora, oba motywy, wszystkie sześć
ekranów: `dok/zrzuty/m3_*.png`. 33 testy jednostkowe przechodzą.

**Druga tura tego samego dnia — makieta.** Pliki znalazły się w `mk32app\Aplikacja mobilna
dla DRON\`; przeszedłem `Kokpit M3.dc.html` linia po linii. Najważniejsza poprawka dotyczy
**formy, nie układu**: w makiecie każdy element chromu ma **ścięte dwa przeciwległe naroża
i krawędź akcentu 2 dp wyłącznie po lewej** (pomocnik `plyta()`), a nie ścięcia dookoła
i ramkę. Poza tym wróciły: podpisy słowne w belce zamiast piktogramów, akcent zamiast
czerwieni na RTL, niebo i ziemia we wskaźniku położenia, migawka **na środku** pionu kamery,
warstwy ekranu jako panel przy krawędzi i panele robocze **wpisane w kadr** zamiast
pełnoekranowych. Pełna lista różnic: [dok/PRZEKAZANIE_M3.md](dok/PRZEKAZANIE_M3.md).

> **Czego nie sprawdzono i to jest istotne:** żadna z dopisanych komend SIYI nie rozmawiała
> z głowicą, a wysyłka misji nie rozmawiała z kontrolerem lotu — symulator nie obsługuje
> wiadomości misji. Nawigacja z `NAWIGACJA.md` (Compose Destinations) **nie jest wdrożona** —
> menu widoków działa, ale bez przejść, stanu ekranu i przycisku wstecz.

### Trzecia tura — wariant D (2026-08-23)

Sekcja 9 z [dok/UI.md](dok/UI.md) — **„stacja indywidualna"** — była od 19 sierpnia
opisem bez pokrycia w kodzie. Wdrożona 2026-08-23.

Punkt wyjścia: MK32 nie jest tabletem współdzielonym z nikim. Skoro tak, pasek zakładek
zabierający **72 dp szerokości na wszystkich sześciu ekranach naraz** nie ma uzasadnienia.
Obraz dostaje pełną szybę 960 × 600, a interfejs pływa nad nim.

| Rzecz | Dlaczego |
|---|---|
| zakładki z paska 72 × 600 na **pływającą kolumnę 64 × 34 × 6** | 72 dp szerokości obrazu odzyskane na każdym ekranie |
| pas górny 40 → **28 dp, na przejściu tonalnym, bez linii** | pasek przestał odcinać górę kadru; doszły **słupek baterii** i **pole władzy** |
| taśma kursu 888 → **460 dp, zakres 100 → 60°** | krótsza taśma na tej samej liczbie stopni na piksel byłaby zgrubna; węższy zakres przywraca podziałce rozdzielczość |
| miniatura i tafle danych → **karta mapy z ROZWIŃ** i **nowa karta horyzontu** w prawym górnym narożniku | narożnik nie zabiera ani środka kadru, ani rzędu liczb |
| dolne tafle → **jeden rząd liczb 960 × 78 bez tafli** | te same cztery wartości, zero ramek |
| klawisze 76 → **64 dp**, kolejność FOTO · REC · LĄDUJ · RTL | dwa ostatnie nadal na przytrzymanie i z 12 dp odstępu od niegroźnych |

Karta horyzontu to **nie** jest sztuczny horyzont, którego §7 zakazuje: 212 × 150 dp,
przechył ±30°, pochylenie ±10°. Obraz z kamery na stabilizowanej głowicy jest wypoziomowany
i nie mówi nic o położeniu maszyny — karta odpowiada na jedno pytanie, czy maszyna leci
prosto, i przy wąskim zakresie robi to z rozdzielczością, jakiej pełny ekran by nie dał.

Sprawdzone w emulatorze MK32 na telemetrii z symulatora, wszystkie sześć ekranów:
`dok/zrzuty/wariantD_*.png`. 33 testy jednostkowe przechodzą. **Na sprzęcie niesprawdzone
— dotyczy to zwłaszcza zakładek 34 dp, bo cel dotykowy z §2 to 64 dp.**

### Druga tura — uwagi operatora (2026-08-19)

Po pierwszej przebudowie ekran lotu był kompletny, ale przeładowany. Poprawki:
**mapa dostała podkład** (kafelki rastrowe z karty TF, `narzedzia/kafelki.py`, domyślnie
zdjęcia lotnicze), **tafle i przyciski zrobiły się przezroczyste**, **kroje zeszły o jeden
poziom** (34 → 26 sp na głównej wartości), **czynności przeszły na piktogramy** rysowane
wektorowo, a z HUD-u zniknęły prąd, mAh, gaz, kąty głowicy, diody łączy i róża kursu —
zastąpiona taśmą 24 dp. Ekran lotu pokazuje dziś **10 wartości zamiast 19**.

Szczegóły: [dok/AUDYT_UI.md](dok/AUDYT_UI.md) §8, [dok/UI.md](dok/UI.md) §8.

> **Uwaga o wiarygodności tego zestawienia.** Wszystko powyżej zostało obejrzane
> w emulatorze, na telemetrii z symulatora. **Żadne z trzech łączy nie rozmawiało jeszcze
> z prawdziwym sprzętem.**

> ### ⛔ Znalezisko z pierwszego przebiegu checklisty — dotyczy maszyny, nie aplikacji
>
> Checklista uruchomiona na `dok\ODNIESIENIE_QUAD_20260815.parm` zgłosiła **blokadę**:
> `SERVO1..4_FUNCTION = 33, 34, 35, 36`, a po korekcie z Motor Testu ma być **34, 36, 33, 35**.
>
> Sprawdzone w logach: `dokc_write_20260815_194224.log` zapisał poprawne mapowanie
> **o 19:42**, a plik odniesienia to zrzut **z 17:30** — dwie godziny wcześniej.
> Ten sam stary układ mają zrzuty z 17:44 i 18:09.
>
> **Wgranie pliku opisanego w CLAUDE.md jako „jedyny poprawny" cofnęłoby mapowanie
> silników do stanu sprzed naprawy salta.** Do rozstrzygnięcia z Tomem: zrobić świeży
> zrzut z maszyny i zastąpić nim plik odniesienia.

---

## 9. Zasady pracy nad kodem

- **Rdzeń aplikacji nie ma zależności od modułu udostępniania.** Wyłączenie parowania
  ma nie zmieniać niczego w kokpicie. To jest sprawdzalne: kompilacja bez tego modułu
  musi dawać działającą aplikację.
- **Filtr komend stoi po stronie MK32.** Stacja nie może sobie przyznać uprawnień.
- **Aplikacja nie zapisuje parametrów FC.** Misje i geofence tak, parametry nie.
- Komendy ruszające maszyną — **potwierdzenie przytrzymaniem**.
- Wartości enum ArduPilota weryfikować w źródłach 4.6.3 (konwencja z sekcji 8 `..\CLAUDE.md`).
- Twierdzenia o protokołach oznaczać **FAKT** / **HIPOTEZA** — `dok\INTERFEJSY.md`.
- Testy na biurku **bez śmigieł** do etapu M4 włącznie.

---

## 10. Decyzje

### Podjęte

| # | Decyzja | Data |
|---|---|---|
| 1 | **Stacja steruje kamerą od razu, bez przekazywania władzy nad lotem.** Operator MK32 odbiera jej kamerę jednym dotknięciem, niezależnie od władzy nad lotem | 2026-08-18 |
| 2 | **Stacja steruje kamerą zwykłym MAVLinkiem**, nie osobnym protokołem — jeden filtr, dwa tory uprawnień, zero nowego kodu po stronie stacji. Kokpit MK32 zostaje przy UDP prosto do ZR30 (niezależność od FC). `SiyiProxy` tylko jako wariant zapasowy | 2026-08-18 |
| 3 | **ROI to komenda lotu, nie kamery** — na wielowirnikowcu obraca całą maszynę | 2026-08-18 |
| **4** | **Zakres stacji: podgląd + monitory HDMI + archiwum.** Bez Waydroida i Androida, bez QGroundControla na stacji, bez edytora misji. Powód: Waydroid wymusza `kernel8.img`, czyli strony 4 KB i spowolnienie całego systemu, przy niepewnym losie sprzętowego dekodera HEVC — a jedyne, co dawał, to aplikacje, które równie dobrze chodzą na zwykłym komputerze | **2026-08-20** |
| **5** | **Dostęp zdalny: WireGuard na routerze**, tak jak w NRK. Bez Tailscale'a i bez własnego VPS-a. **Świadoma cena: stacja jest osiągalna z zewnątrz tylko wtedy, gdy siedzi w sieci ze stałym, publicznym adresem.** W polu, na 4G za CGNAT, dostęp zdalny nie zadziała — i to jest przyjęte, bo tam pracuje się przy stacji, nie zdalnie | **2026-08-20** |
| **6** | **Endpoint WireGuarda podawany ręcznie.** Stacja wyświetla swój aktualny adres publiczny (przycisk DOSTĘP na stronie), operator przepisuje go do klienta. Bez serwera koordynującego — rozważony i odłożony, bo poprawna wersja wymaga hole punchingu, przekaźnika na wypadek symetrycznego NAT i kluczy z terminem ważności; gdyby był potrzebny, bierzemy gotowy NetBird albo headscale, nie piszemy własnego. [SERWER_PODGLADU.md](dok/SERWER_PODGLADU.md) §6.5–6.6 | **2026-08-20** |
| **7** | **Wpuszczanie linkiem zapraszającym, nie kontami z hasłami.** Admin wpisuje imię, dostaje link; zaproszenie ma rolę, termin i może być jednorazowe. Powód: przy kilku osobach konta to praca bez zysku, a link daje każdemu własny żeton — więc odcięcie jednej osoby nie rusza reszty | **2026-08-20** |
| **8** | **Widz widzi imiona pozostałych widzów**, nie samą liczbę. Przy kilku znajomych osobach anonimowość niczego nie chroni, a lista bez imion niewiele mówi. Adresy IP zostają wyłącznie w panelu admina | **2026-08-20** |
| **9** | **Admin narzuca źródło domyślne, każdy może je u siebie zmienić.** Narzucanie widoku komuś, kto ogląda na telefonie w słońcu, częściej przeszkadza niż pomaga | **2026-08-20** |

Rozwinięcie: [dok/WLADZA.md](dok/WLADZA.md), sekcja 7 oraz
[dok/INTERFEJSY.md](dok/INTERFEJSY.md), sekcja 3c.

### Do potwierdzenia

1. **Wariant sieciowy stacji — A czy B?** Na wspólnej sieci pokładowej stacja może sięgnąć
   do portu `19856` i do głowicy z pominięciem naszego filtra; wtedy filtr jest umową plus
   czujką, a nie barierą. **Nowa okoliczność po decyzji 4:** stacja z własnym hotspotem
   dla widzów realizuje wariant B **za darmo** — widz nie jest w sieci pokładowej, a obraz
   i tak idzie przez stację ([SERWER_PODGLADU.md](dok/SERWER_PODGLADU.md) §8).
   Szczegóły w [dok/WLADZA.md](dok/WLADZA.md), sekcja 9
2. **Ile trwa przekazanie władzy, zanim wróci samo?** (rekomendacja: 10 minut,
   z ostrzeżeniem 60 s wcześniej)
3. **Czy kokpit zastępuje SIYI FPV całkowicie?** (rekomendacja: tak — dwa programy biją się
   o `SR1_*` i o dekoder)
4. ~~**Mapa offline: skąd kafelki?**~~ — **rozstrzygnięte 2026-08-25**: raster XYZ na karcie,
   z podziałem na warstwy (`kafelki/{warstwa}/{z}/{x}/{y}`), pięć podkładów do wyboru,
   hybryda obowiązkowa. Dane wysokościowe **Terrarium** w tej samej rurze
   (`teren/{z}/{x}/{y}.png`) — stąd cieniowanie, warstwice, widok 3D i prześwit nad terenem.
   Opis: [dok/MAPY.md](dok/MAPY.md). **PMTiles zostaje pytaniem otwartym**, ale przy
   dzisiejszych rozmiarach (25 MB na rejon) nie boli

### Rozstrzygnięte 2026-08-20

| Było pytaniem | Odpowiedź |
|---|---|
| Czy stacja bierze obraz prosto z kamery, czy przez MK32? | **prosto z kamery** — nie obciąża aparatury, nie zajmuje slotu (ZR30 wydaje 4 strumienie) |
| Wariant stacji: przy operatorze czy zdalna? | **przy operatorze**; zdalny jest wyłącznie *dostępem* do niej przez tunel, nie osobną topologią |
| Czy stacja ma Androida (Waydroid, QGC)? | **nie** — decyzja 4 |
| Czy stacja planuje misje? | **nie** — decyzja 4; planowanie na MK32 albo w QGC na zwykłym komputerze, wymiana plikiem `.plan` |
