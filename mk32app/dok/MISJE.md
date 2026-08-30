# Planowanie misji i zadań

---

## 1. Protokół — ten sam, co w QGC i Mission Plannerze

Używamy standardowego protokołu misji MAVLink, więc misja zaplanowana w naszym kliencie
otwiera się w QGroundControl i odwrotnie. Żadnego własnego formatu.

Wartości zweryfikowane w definicjach MAVLink (dialekt `ardupilotmega`, pymavlink) — **FAKT**:

| Wiadomość | msgid | Rola |
|---|---|---|
| `MISSION_COUNT` | 44 | zapowiedź liczby punktów |
| `MISSION_REQUEST_INT` | 51 | maszyna prosi o punkt nr n |
| `MISSION_ITEM_INT` | 73 | punkt (współrzędne jako `int32`, bez utraty precyzji) |
| `MISSION_ACK` | 47 | potwierdzenie całości |
| `MISSION_REQUEST_LIST` | 43 | pobranie misji z maszyny |
| `MISSION_CLEAR_ALL` | 45 | kasowanie |
| `MISSION_CURRENT` | 42 | który punkt jest realizowany |
| `MISSION_ITEM_REACHED` | 46 | punkt osiągnięty |

**Zawsze `MISSION_ITEM_INT`, nigdy przestarzałe `MISSION_ITEM`** — to drugie przenosi
współrzędne jako `float` i gubi precyzję na poziomie metrów.

Trzy niezależne listy, rozróżniane polem `mission_type`:

| `mission_type` | Zawartość |
|---|---|
| 0 | trasa |
| 1 | geofence |
| 2 | punkty zbiórki (rally) |

---

## 2. Elementy trasy

| Komenda | Wartość | Zastosowanie |
|---|---|---|
| `NAV_WAYPOINT` | 16 | punkt trasy, z opcjonalnym postojem |
| `NAV_SPLINE_WAYPOINT` | 82 | przelot łagodnym łukiem, bez zatrzymania |
| `NAV_TAKEOFF` | 22 | start do zadanej wysokości |
| `NAV_LAND` | 21 | lądowanie |
| `NAV_RETURN_TO_LAUNCH` | 20 | powrót — zachowanie wg `RTL_*`, sekcja 5a `..\..\CLAUDE.md` |
| `NAV_LOITER_TIME` / `_TURNS` / `_UNLIM` | 19 / 18 / 17 | zawis czasowy, okrążenia, zawis bez końca |
| `DO_CHANGE_SPEED` | 178 | zmiana prędkości w locie |
| `CONDITION_DELAY` | 112 | odczekanie przed kolejnym poleceniem |
| `DO_JUMP` | 177 | pętla po fragmencie trasy |

### Zadania kamery w punktach trasy

| Komenda | Wartość | Co robi |
|---|---|---|
| `DO_SET_ROI_LOCATION` | 195 | głowica i nos celują w zadany punkt terenu — **na wielowirnikowcu obraca całą maszynę**, więc filtr władzy traktuje ROI jako komendę lotu |
| `DO_SET_ROI` | 201 | starszy odpowiednik, dla zgodności |
| `DO_MOUNT_CONTROL` | 205 | konkretny kąt głowicy |
| `DO_GIMBAL_MANAGER_PITCHYAW` | 1000 | nowszy sposób sterowania głowicą |
| `IMAGE_START_CAPTURE` | 2000 | zdjęcie lub seria |
| `VIDEO_START_CAPTURE` | 2500 | start nagrywania |
| `DO_DIGICAM_CONTROL` | 203 | wyzwolenie migawki, wariant starszy |

> **Uwaga specyficzna dla tej maszyny.** Zadania kamery w misji idą przez sterownik mounta
> w kontrolerze lotu, czyli **tą samą drogą, która była zepsuta** (poz. 28 `..\..\CLAUDE.md`).
> Sterowanie ręczne z aplikacji tego nie dotyczy, bo idzie po UDP prosto do ZR30.
> Wniosek praktyczny: **zanim zaplanujesz misję ze zdjęciami, sprawdź na ziemi, czy ROI
> naprawdę rusza głowicą.** Klient ma to sprawdzić w checkliście przedlotowej i ostrzec.

---

## 3. Geofence i punkty zbiórki

Geofence (`mission_type = 1`):

| Komenda | Wartość |
|---|---|
| `NAV_FENCE_POLYGON_VERTEX_INCLUSION` | 5001 |
| `NAV_FENCE_POLYGON_VERTEX_EXCLUSION` | 5002 |
| `NAV_FENCE_CIRCLE_INCLUSION` | 5003 |
| `NAV_FENCE_CIRCLE_EXCLUSION` | 5004 |

Punkty zbiórki (`mission_type = 2`): `NAV_RALLY_POINT` = 5100 — alternatywne miejsca
powrotu, bliższe niż punkt startu.

Na tej maszynie geofence siedzi na przełączniku **CH7** (`RC7_OPTION=11`, poz. 4
`..\..\CLAUDE.md`). Klient ma pokazywać, czy jest w danej chwili włączony — bo obrys
na mapie bez włączonego geofence to tylko rysunek.

---

## 4. Wzorce generowane automatycznie

Nie są osobnymi poleceniami MAVLinka — to nasze generatory, które produkują zwykłą
listę punktów.

| Wzorzec | Parametry | Zastosowanie |
|---|---|---|
| **siatka pomiarowa** | obrys obszaru, wysokość, pokrycie wzdłuż i w poprzek, kierunek linii | inwentaryzacja, ortofoto |
| **korytarz** | linia łamana, szerokość, liczba przelotów | linie energetyczne, drogi, rzeki |
| **orbita** | środek, promień, wysokość, liczba okrążeń, ROI w środku | oblot obiektu z kamerą na cel |
| **pionowa fasada** | linia bazowa, wysokość od–do, krok | ściany, maszty, kominy |

Generator ma liczyć i pokazywać **przed wysłaniem**: długość trasy, czas przy
`WPNAV_SPEED = 6 m/s`, liczbę zdjęć i szacunek zużycia pakietu. Przy 6S i zawisie
46,6 % gazu ostrzeżenie ma się pojawiać, gdy trasa wymaga więcej niż 70 % pojemności.

---

## 5. Ograniczenia tej maszyny — twarde

> ### Misja bez kursu GNSS nie wystartuje
> Tryb AUTO wymaga estymaty pozycji, pozycja wymaga kursu, a kurs pochodzi **wyłącznie
> z bazy GNSS** (`EK3_SRC1_YAW=2`, brak kompasu — sekcja 5 `..\..\CLAUDE.md`).
> Klient **blokuje wysłanie i uruchomienie misji**, dopóki `gnss.kurs_dostepny = false`.
> To nie jest ostrożność — to stan, w którym FC i tak odmówi wejścia w tryb.

Pozostałe:

- **AUTO jest na przełączniku trybów**, w pozycji trzeciej (`FLTMODE6`). Przełącznik ma
  tylko trzy osiągalne pozycje: AltHold / Loiter / Auto (poz. 4). Klient ma pokazywać,
  w której pozycji jest przełącznik, bo wysłanie misji nie uruchamia jej samo.
- **RTL wraca na 50 m** (`RTL_ALT=5000`) — wysokości w misji planować z tym w tle.
- **Zasięg łącza** MK32 to do 15 km bez przeszkód; klient ma rysować na mapie okrąg
  odległości od operatora i ostrzegać, gdy trasa go przekracza.
- **Failsafe RC** to RTL (`FS_THR_ENABLE=1`), więc wyjście poza zasięg w trakcie misji
  przerwie ją i zawróci maszynę.

---

## 6. Przepływ pracy

```
   STACJA GCS (duży ekran)                MK32 (7 cali, w terenie, WŁADZA)
   ───────────────────────                ────────────────────────────────
   rysowanie trasy na mapie               własny edytor: dodaj punkt, wyślij, pobierz
   wzorce, ROI, zadania kamery            podgląd trasy, postęp, punkt bieżący
   symulacja czasu i pakietu              pauza / wznowienie / skok do punktu
   biblioteka misji, archiwum             trzy wzorce: orbita, siatka, powrót
                    │                                    │
                    └──── propozycja trasy ─────────────►│
                                                         │  zatwierdzenie operatora
                                                         ▼
                                              MissionService na MK32
                                                         │
                                              wysyłka do FC + weryfikacja
```

**MK32 nie potrzebuje stacji, żeby latać misje.** Stacja przyspiesza planowanie,
nie warunkuje go.

**Planowanie na dużym ekranie, wykonanie na aparaturze.** Rysowanie precyzyjnej trasy
na 7 calach w słońcu jest karą, a nie funkcją — dlatego pełny edytor mieszka na stacji GCS.
Ale **MK32 ma własny, uproszczony edytor i działa bez stacji**: dodanie punktu, wysyłka,
pobranie, trzy wzorce.

**Misja przygotowana na stacji wymaga zatwierdzenia na MK32.** Wysyłka do maszyny jest
komendą, więc podlega tej samej władzy co wszystko inne — albo stacja ma przekazane
sterowanie, albo trasa czeka na zatwierdzenie na aparaturze
(patrz [WLADZA.md](WLADZA.md)).

Biblioteka misji leży na MK32 w formacie `.plan` (ten sam, co QGC), więc trasy przenoszą
się między narzędziami zwykłym plikiem.

---

## 7. Zasady wysyłki

1. **Pobierz przed edycją.** Zawsze ściągnąć misję z maszyny i pokazać różnice, zanim
   cokolwiek nadpiszemy — inaczej dwóch operatorów skasuje sobie pracę.
2. **Wysyłka to komenda pilota.** Ze stacji przechodzi tylko przy przekazanej władzy;
   inaczej trasa czeka na zatwierdzenie na MK32 (patrz [WLADZA.md](WLADZA.md)).
3. **Weryfikacja po wysłaniu.** Po `MISSION_ACK` pobrać misję z powrotem i porównać
   punkt po punkcie. To dokładnie ta sama lekcja, co poz. 22 w `..\..\CLAUDE.md` —
   przy parametrach pięć wartości przepadło po cichu. Przy misji zakładamy to samo.
4. **Nigdy nie wysyłać misji w locie bez ostrzeżenia** — jeśli maszyna jest w trybie AUTO,
   klient ma zażądać potwierdzenia i pokazać, który punkt jest realizowany.
