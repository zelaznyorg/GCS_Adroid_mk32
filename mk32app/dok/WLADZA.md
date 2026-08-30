# Władza nad maszyną

Dokument opisuje jedyną rzecz w tym systemie, która nie podlega negocjacji:
**kto może wydać komendę dronowi.**

---

## 1. Zasada

> **Operator na MK32 ma pełną władzę nad maszyną.**
> Stacja GCS dostaje ją wyłącznie wtedy, gdy operator ją świadomie przekaże,
> i traci ją w chwili, gdy operator zechce — albo gdy przestanie się odzywać.

Z tego wynikają cztery reguły konstrukcyjne:

1. **Aplikacja na MK32 nie potrzebuje niczego do pracy.** Pełna funkcjonalność bez GCS,
   bez internetu, bez sieci poza pokładową. Stacja jest dodatkiem, nie warunkiem.
2. **Droga komend prowadzi wyłącznie przez MK32** — zarówno do kontrolera lotu,
   jak i do głowicy. Wszystko, co stacja wysyła, przechodzi przez filtr na aparaturze.
3. **Filtr stoi po stronie MK32.** Stacja nie może sobie nic przyznać, bo nie ona
   decyduje — decyduje kod działający na aparaturze operatora. Granice tej zasady
   opisuje sekcja 9; na wspólnej sieci wymaga ona uczciwego oprogramowania po obu stronach.
4. **Domyślnie stacja dostaje podgląd, planowanie i kamerę — ale nie lot.**
   Sterowanie maszyną wymaga świadomego przekazania (sekcja 7).

---

## 2. Dwa tryby pracy aplikacji

### Tryb SAMODZIELNY (domyślny)

```
   FC ──radio──► MK32 ──► kokpit, misje, głowica, checklista, zapis
   ZR30 ──LAN──►
```

Wszystko działa: obraz, telemetria, sterowanie głowicą, planowanie i wysyłka misji,
checklista przedlotowa, zapis `.tlog`, mapa offline. **Nic nie jest wyłączone
z powodu braku stacji.**

### Tryb SPAROWANY

```
   FC ──radio──► MK32 ──► kokpit (władza)
                   │
                   ├── telemetria ──► GCS (RPi 5) ──► monitory, przeglądarki, internet
                   └── komendy ◄──── tylko po przekazaniu władzy
   ZR30 ──LAN──► MK32
        └───LAN──────────────────► GCS (własny strumień z kamery)
```

Stacja dostaje wszystko do oglądania i planowania. Do sterowania — dopiero po przekazaniu.

**Parowanie nie zmienia niczego w trybie pracy MK32.** Rozłączenie stacji w dowolnym
momencie, także w locie, nie ma żadnego wpływu na maszynę ani na kokpit.

---

## 3. Dlaczego obraz może iść bokiem, a komendy nie

ZR30 wydaje **do czterech strumieni z tego samego adresu RTSP**, a stacja i tak siedzi
w sieci pokładowej. Niech więc bierze obraz prosto z kamery — nie obciąża MK32
i nie psuje się, gdy aparatura jest zajęta.

Z komendami jest odwrotnie: gdyby stacja miała własny kanał do kontrolera lotu,
**żaden zapis w dokumentacji nie powstrzymałby jej przed wydaniem komendy**. Zasada
byłaby wtedy tylko dobrym obyczajem. Przy jednej drodze przez MK32 staje się regułą,
którą egzekwuje kod — **z zastrzeżeniem z sekcji 9**: na wspólnej sieci pokładowej
stacja ma fizyczną możliwość ominięcia tej drogi, więc filtr jest tam umową plus czujką,
a nie barierą.

| Co | Skąd stacja to bierze | Dlaczego |
|---|---|---|
| obraz z kamery | wprost z ZR30 | odciąża MK32, kamera i tak wydaje 4 strumienie |
| telemetria | z MK32 (rozgałęzienie) | jedno łącze radiowe, jeden konsument po stronie ziemi |
| **komendy do FC** | **tylko przez MK32, po przekazaniu władzy** | jedyny sposób, żeby zasada była egzekwowalna |
| **komendy do głowicy** | **MAVLinkiem przez MK32, dostępne od razu** | patrz 7 — osobny tor uprawnień w tym samym filtrze |

---

## 4. Przekazanie — jak wygląda

```
   STACJA GCS                         MK32
   ──────────                         ────
   "Proszę o sterowanie"   ────────►  baner: KTO prosi, PO CO, na JAK DŁUGO
                                             │
                                      operator PRZYTRZYMUJE „PRZEKAŻ" 2 s
                                             │
   sterowanie aktywne      ◄────────  przekazane, licznik czasu biegnie
                                             │
                                      w pasie stanu, przez cały czas:
                                      „STERUJE: GCS biuro — ODBIERZ"
```

Cechy, których nie wolno uprościć:

- **Przekazanie wymaga przytrzymania**, nie dotknięcia. Odebranie — jednego dotknięcia.
  Droga do oddania władzy ma być trudniejsza niż droga do jej odzyskania.
- **Prośba wygasa po 30 s bez reakcji.** Baner znika, nic się nie dzieje.
- **Przekazanie jest ograniczone czasem** (domyślnie 10 min, do ustawienia). Po upływie
  wraca samo, z ostrzeżeniem 60 s wcześniej.
- **Widoczność ciągła.** Dopóki steruje ktoś inny, pas stanu MK32 świeci innym kolorem
  i pokazuje kto. Nie da się o tym zapomnieć.

---

## 5. Odebranie — kiedy wraca samo

Władza wraca do MK32 **natychmiast**, bez pytania stacji, gdy:

| Zdarzenie | Uzasadnienie |
|---|---|
| operator dotknął „ODBIERZ" | decyzja człowieka, bez opóźnień |
| **ruch drążkami na aparaturze** | fizyczna intencja pilota jest ważniejsza niż cokolwiek |
| stacja milczy dłużej niż **3 s** | nie da się sterować czymś, czego się nie widzi |
| dowolny failsafe (RC, bateria, geofence) | sytuacja awaryjna należy do pilota |
| zmiana trybu przełącznikiem na aparaturze | pilot przejął ręcznie |
| upłynął czas przekazania | patrz 4 |
| utrata kursu GNSS | maszyna traci pozycję — patrz sekcja 5 `..\..\CLAUDE.md` |

**Kamera ma osobny licznik.** Jej odebranie nie następuje automatycznie przy failsafe
— pilot leci maszyną, operator kamery patrzy, i to bywa akurat wtedy najbardziej potrzebne.
Odbiera się ją wyłącznie ręcznie albo gdy stacja zamilknie.

Powrót władzy **nie robi nic z maszyną**: nie zmienia trybu, nie wywołuje RTL.
Zmienia wyłącznie to, kto może wysyłać komendy. Failsafe GCS (`FS_GCS_ENABLE`) zostaje
wyłączony, żeby zerwanie łącza ze stacją nie sprowadzało drona.

---

## 6. Czego nie da się przekazać nigdy

| Czynność | Dlaczego zostaje na MK32 |
|---|---|
| **uzbrojenie i rozbrojenie** | jest na przełączniku CH9 (`RC9_OPTION=153`) — sprzętowo, nie programowo |
| **zapis parametrów** | zasada projektu: parametry zmienia tylko `..\..\tools\fc_write_params.py`, z logiem |
| **przejęcie z pominięciem MK32** | brak takiej drogi w naszym oprogramowaniu; o granicach tego stwierdzenia — sekcja 9 |
| **wyłączenie kontroli przedlotowej** | checklista jest po stronie MK32 i tam zostaje |

Operator na MK32 zachowuje też **zawsze dostępne RTL** — niezależnie od tego,
kto ma w danej chwili sterowanie.

---

## 7. Głowica — ROZSTRZYGNIĘTE 2026-08-18

**Stacja steruje kamerą od razu, bez przekazywania władzy nad lotem.
Operator na MK32 może jej to odebrać jednym przyciskiem.**

Uzasadnienie: głowica ma osobne łącze, z pominięciem kontrolera lotu, więc operator
kamery w biurze może pracować, nie odbierając pilotowi sterowania maszyną. To dwie
niezależne rzeczy i nie ma powodu ich wiązać.

| Zakres | Stan początkowy | Kto zmienia |
|---|---|---|
| **kamera** (pitch, yaw, zoom, foto, REC) | **stacja ma od razu** | MK32 odbiera jednym dotknięciem |
| **lot** (tryby, RTL, misje) | MK32 | MK32 przekazuje przytrzymaniem 2 s |

Na ekranie MK32 są więc **dwa niezależne przyciski odbierania**: `ODBIERZ KAMERĘ`
i `ODBIERZ STEROWANIE`. Odebranie kamery nie rusza władzy nad lotem i odwrotnie.

### Którędy stacja steruje kamerą — MAVLinkiem

Głowicą da się sterować **zwykłym MAVLinkiem** (`DO_GIMBAL_MANAGER_PITCHYAW`,
`SET_CAMERA_ZOOM`, `IMAGE_START_CAPTURE`, `VIDEO_START_CAPTURE` — pełna lista
w [INTERFEJSY.md](INTERFEJSY.md), sekcja 3c). To upraszcza całą konstrukcję:

- **jeden protokół i jeden filtr** — komendy kamery jadą tą samą drogą co komendy lotu,
  przez `AuthorityGate` na MK32, tylko innym torem uprawnień
- **żadnego nowego kodu po stronie stacji** — QGroundControl i Mission Planner dostają
  sterowanie kamerą za darmo
- **odebranie kamery jest decyzją, nie prośbą**, bo filtr stoi w drodze

Podział, który z tego wychodzi:

| Kto | Czym steruje głowicą | Dlaczego |
|---|---|---|
| **kokpit na MK32** | **UDP prosto do ZR30** | zero kosztu pasma, brak zależności od FC, działa nawet przy `MNT1_TYPE=0` |
| **stacja GCS** | **MAVLink przez filtr na MK32** | jeden filtr, zero nowego kodu, egzekwowalne odebranie |
| **misje (ROI, zdjęcia w punktach)** | MAVLink | innej drogi nie ma |

Cena drogi MAVLinkowej: przechodzi przez łącze szeregowe FC↔ZR30 — **to samo, które
było zepsute i potrafiło zamilknąć w locie** (poz. 28 `..\..\CLAUDE.md`). Wynika z tego
przyjemna właściwość: **gdy to łącze znowu padnie, stacja straci kamerę, a MK32 nie**,
bo operator ma własną drogę po LAN-ie.

`SiyiProxy` (przekazywanie komend SIYI z kanału parowania) zostaje jako **wariant
zapasowy**, na wypadek gdyby droga MAVLinkowa okazała się w praktyce zbyt wolna
albo znowu zawodna. Nie jest potrzebny do działania.

**Obraz** stacja bierze prosto z kamery: strumień jest tylko do oglądania,
nie da się nim niczego poruszyć.

### ROI należy do toru LOTU, nie kamery

`DO_SET_ROI` na wielowirnikowcu **obraca całą maszynę** w stronę celu, nie tylko głowicę.
Filtr klasyfikuje go więc jako komendę lotu i bez przekazanej władzy nie przepuszcza.
To nie jest formalność — inaczej operator kamery mógłby zawracać drona.

### Czego stacja nie dostaje razem z kamerą

- zmiany kodeka i przepływności (`0x21`) w trakcie lotu — to wpływa na obraz u pilota
- nagrywania na kartę w kamerze, gdy pilot je zablokuje przed lotem

---

## 8. Stan prototypu

`..\narzedzia\mav_router.py` realizuje ten mechanizm i **jest przetestowany**:

| Próba | Wynik |
|---|---|
| stacja bez władzy wysyła komendy | 0 przeszło w górę, zapis w logu |
| po `/przekaz?klient=IP` | 3 z 3 przeszły |
| po `/odbierz` | znowu 0, licznik stoi |
| cisza stacji ponad 3 s | władza wróciła sama, wpis w logu |
| **komenda z pominięciem filtra** | **wykryta i zgłoszona** — patrz sekcja 9 |
| **zoom + RTL w jednym strumieniu TCP** | **zoom przeszedł, RTL zablokowany** — filtr działa per ramka |
| po `/odbierz-kamere` | ani zoom, ani zdjęcie nie przechodzą |
| po `/przekaz` i `/przekaz-kamere` | oba tory przechodzą |

```bash
python narzedzia\mav_router.py --wladza-port 8099
curl http://127.0.0.1:8099/stan
curl http://127.0.0.1:8099/przekaz?klient=192.168.1.50   # oddanie lotu
curl http://127.0.0.1:8099/odbierz                       # odebranie lotu
curl http://127.0.0.1:8099/odbierz-kamere                # sama kamera
curl http://127.0.0.1:8099/przekaz-kamere
```

Klasyfikacja komend jest w `mav_router.py` w słownikach `KAMERA_CMD`, `KAMERA_MSGID`
i `ROI_CMD` — jedno miejsce do uzupełnienia, gdy dojdzie nowa funkcja.

W aplikacji na MK32 ten sam mechanizm dostanie przyciski na ekranie zamiast adresów HTTP.
Logika zostaje bez zmian.

---

## 9. Granice egzekwowalności — rzecz do rozstrzygnięcia

Poprzednia wersja tego dokumentu twierdziła, że filtr po stronie MK32 **uniemożliwia**
stacji wydanie komendy. To prawda tylko wtedy, gdy stacja nie ma innej drogi do maszyny.
A w domyślnym wariancie ją ma:

> Jednostka naziemna MK32 wystawia telemetrię na `192.168.144.12:19856`, **w sieci
> pokładowej**. Stacja wpięta w tę samą sieć może połączyć się z tym portem bezpośrednio,
> z pominięciem naszej aplikacji. Tak samo może sięgnąć do głowicy pod `192.168.144.25:37260`.

Czyli: **na wspólnej sieci filtr jest umową, którą honoruje nasze oprogramowanie,
a nie barierą fizyczną.** Trzy możliwe odpowiedzi:

| Wariant | Jak działa | Egzekwowalność | Koszt |
|---|---|---|---|
| **A. wspólna sieć + czujka** | stacja w sieci pokładowej, nasze oprogramowanie honoruje filtr, MK32 **wykrywa i alarmuje**, gdy ktoś go omija | umowna + wykrywanie | zero |
| **B. rozdzielenie sieci** | stacja **nie wchodzi** do sieci pokładowej, łączy się wyłącznie z MK32 (hotspot WiFi albo druga karta sieciowa) | **pełna** | MK32 musi przekazywać obraz, czyli go przepakowywać |
| **C. wspólna sieć bez zabezpieczeń** | jak A, ale bez czujki | żadna | zero |

**Rekomendacja: A na start, B gdy stacja trafi w obce ręce.** Dopóki stacją zarządza ta
sama osoba co dronem, wariant A wystarcza: nikt nie musi się przed sobą bronić, a czujka
wyłapie pomyłkę — na przykład zapomniany QGroundControl w tle, który wysyła własne komendy.
Wariant B jest jedyną odpowiedzią, gdy stacja stoi u kogoś, komu ufa się ograniczenie.

### Czujka obcych komend — działa w prototypie

`COMMAND_ACK` przychodzące z maszyny, gdy przez nasz filtr nie przeszła żadna komenda,
znaczy, że komendę wydał ktoś inny. Prototyp to wykrywa i wypisuje ostrzeżenie; w aplikacji
ma to być **baner na cały ekran**, bo to znaczy, że maszyną steruje ktoś, kogo nie widzimy.

```
[!!] OBCE ZRODLO KOMEND: potwierdzenie MAV_CMD 20, a przez ten filtr
     nic nie przeszlo. Ktos wydaje komendy z pominieciem MK32.
```

**Do sprawdzenia w M0:** czy port 19856 w ogóle obsługuje więcej niż jednego klienta naraz.
Jeśli obsługuje tylko jednego, wariant A jest bezpieczniejszy, niż się wydaje — stacja
odbierałaby MK32 telemetrię i byłoby to natychmiast widać. Jeśli obsługuje wielu, czujka
jest jedynym sygnałem.
