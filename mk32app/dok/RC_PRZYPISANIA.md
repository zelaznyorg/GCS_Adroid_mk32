# Panel RC — przypisania kanałów i przełączników

Ekran **RC** w kokpicie. Powstał z zasady 7 systemu projektowego ([UI.md](UI.md)):
*ekran pokazuje, sprzęt wykonuje*. Żeby aplikacja mogła się do niej stosować, musi
wiedzieć, co siedzi na kanałach — a dziś ta wiedza istnieje wyłącznie w `..\..\CLAUDE.md`,
w formie zdania po polsku.

---

## 1. Po co to jest

Trzy zadania, w tej kolejności ważności:

1. **Pokazać, że aparatura steruje.** Szesnaście żywych pasków z `RC_CHANNELS`. Bez tego
   pilot nie ma jak odróżnić „przełącznik nie działa" od „funkcja nie jest przypisana".
   Do wersji 2.0 aplikacja w ogóle nie dekodowała tej wiadomości.
2. **Powiedzieć, co robi który przełącznik.** Odczyt `RCn_OPTION` prosto z maszyny, opisany
   po ludzku, a nie liczbą. Plus `FLTMODE_CH` i `FLTMODE1..6` — czyli co naprawdę daje
   przełącznik trybów.
3. **Wyłączyć z ekranu to, co robi kciuk.** Funkcja obecna na przełączniku dostaje na ekranie
   **wskaźnik stanu zamiast przycisku**. Deklaracja jest po stronie aplikacji, jednym
   przełącznikiem przy każdej pozycji.

---

## 2. Czego ten panel **nie** robi

**Nie zapisuje parametrów do kontrolera lotu.** Zasada z `PLAN.md` §9 obowiązuje bez
wyjątku: misje i geofence tak, parametry nie. Od zapisu jest `..\..\tools\fc_write_params.py`,
z logiem i z podglądem różnic.

Panel może natomiast **wystawić gotowy plan zmian** w formacie `NAZWA=WARTOŚĆ` na kartę TF
(`/sdcard/dron15/plan_rc_RRRRMMDD.txt`) — dokładnie tym formatem, którego oczekują
`plany\plan_*.txt`. Zmiana trafia do maszyny świadomie, z komputera, z zapisem w logu.

---

## 3. Skąd się biorą dane

| Dane | Źródło | Uwaga |
|---|---|---|
| położenie drążków i przełączników | `RC_CHANNELS` (msgid 65) | 16 kanałów + `chancount` + `rssi` |
| funkcja kanału | parametry `RC1_OPTION … RC16_OPTION` | pobierane imiennie, jak reguły checklisty |
| kanał trybów lotu | `FLTMODE_CH` (domyślnie 5) | |
| tryby na pozycjach | `FLTMODE1 … FLTMODE6` | numery trybów jak w `SilnikStanu.TRYBY` |
| zakresy | `RC1_MIN/MAX/TRIM … ` | do wykrycia niedopasowania kalibracji |
| zachowanie gazu | `PILOT_THR_BHV` | drążek samocentrujący — poz. 24/24b `CLAUDE.md` |
| organ fizyczny (drążek / SA–SF / pokrętło / przycisk) | **deklaracja w aplikacji** | tego nie da się odczytać z maszyny |

Ostatni wiersz jest tu najważniejszy: kontroler lotu wie, że kanał 6 wyzwala RTL, ale
**nie wie, że kanał 6 to przełącznik SB po lewej stronie aparatury**. To wie tylko człowiek
i dlatego jest deklarowane w panelu, a nie zgadywane.

Domyślne przypisanie organów wg mapy z sekcji 2.2 `..\..\CLAUDE.md`:

| Kanały | Organ |
|---|---|
| 1–4 | drążki (roll, pitch, gaz, kierunek) |
| 5–10 | przełączniki trzypozycyjne SA–SF |
| 11–14 | pokrętła LD1, RD1, LD2, RD2 |
| 15–16 | przyciski S1, S2 |

---

## 4. Odczyt pozycji przełącznika

ArduPilot dzieli zakres kanału na trzy pozycje (`RC_Channel::AuxSwitchPos`):

| PWM | Pozycja |
|---|---|
| < 1200 µs | **DÓŁ** (LOW) |
| 1200–1800 µs | **ŚRODEK** (MIDDLE) |
| > 1800 µs | **GÓRA** (HIGH) |

**Konsekwencja dla tej maszyny, wprost z `CLAUDE.md` poz. 17:** MK32 wystawia zakres
**1045–1945 µs**, więc górna pozycja (1945) mieści się z zapasem, a dolna (1045) też —
ale margines wynosi 155 µs, nie 200. Przełącznik trzypozycyjny działa; dla kanałów
proporcjonalnych (pokrętła) skrajności są nieosiągalne w ok. 5 %.

Progi wyboru trybu lotu są inne (sześć okien po ok. 205 µs), stąd znany fakt, że
z trzypozycyjnego przełącznika osiągalne są **wyłącznie sloty 1, 4 i 6**.

---

## 5. Kody funkcji AUX

Zgodnie z konwencją z sekcji 8 `..\..\CLAUDE.md` każda wartość ma status. **FAKT** oznacza
wartość zweryfikowaną w `RC_Channel.h` @ Copter-4.6.3 przy okazji prac nad tą maszyną.

| Kod | Funkcja | Status |
|---|---|---|
| 0 | brak przypisania | FAKT |
| 4 | RTL | **FAKT** |
| 11 | Geofence | **FAKT** |
| 27 | Chowanie głowicy (RETRACT_MOUNT1) | **FAKT** |
| 153 | ARM / DISARM | **FAKT** |
| 163 | Blokada głowicy (MOUNT_LOCK) | **FAKT** |
| 167 | Zoom kamery | **FAKT** |
| 213 | Głowica — pochylenie (MOUNT1_PITCH) | **FAKT** |
| 214 | Głowica — obrót (MOUNT1_YAW) | **FAKT** |
| 166 | Nagrywanie wideo | DEKLARACJA — instrukcja ZR30 str. 83 |
| 168 | Ostrość ręczna | DEKLARACJA — j.w. |
| 169 | Autofokus | DEKLARACJA — j.w. |

Kodów spoza tej listy panel **nie nazywa zgadując**. Pokazuje `OPCJA <n> — nierozpoznana`
i to jest uczciwsza odpowiedź niż wymyślona nazwa. Rozszerzanie tablicy: sprawdzić
w `RC_Channel.h` używanej wersji firmware i dopisać ze statusem.

---

## 6. Co panel wykrywa sam

| Kontrola | Dlaczego akurat ta |
|---|---|
| **duplikat funkcji na dwóch kanałach** | maszyna odmawia uzbrojenia komunikatem `Arm: Duplicate Aux Switch Options`; zdarzyło się realnie (CH12 i CH16 na zoomie — `CLAUDE.md`, etap końcowy 2026-08-15) |
| **brak przypisania RTL** | jedyna droga powrotu przy utracie orientacji operatora |
| **brak przypisania ARM/DISARM** | uzbrojenia świadomie nie ma na ekranie; jeśli nie ma go też na przełączniku, maszyny nie da się uzbroić |
| **kanał martwy** (0 µs przy żywej telemetrii) | air unit bez zasilania albo zerwane łącze RC |
| **kanał poza zakresem `RCn_MIN..MAX`** | ślad po kalibracji zrobionej innym nadajnikiem |
| **`PILOT_THR_BHV` bez bitu 0 przy samocentrującym gazie** | poz. 24b `CLAUDE.md` — bez tego środek drążka znaczy pół mocy, a nie zawis |
| **funkcja na przełączniku, a mimo to przycisk na ekranie** | naruszenie zasady 7; panel proponuje ukrycie przycisku |

---

## 7. Układ ekranu

```
 ┌──────────────────────────────────────────────────────┬──────┐
 │ RC · APARATURA          16 kanałów · 47,7 Hz · RSSI —│      │
 ├──────────────────────────────────────────────────────┤ ZAK- │
 │ CH  ORGAN     ┃━━━━━━━━━━━━━━━━━━━┃ 1495  ŚRODEK     │ ŁADKI│
 │ 06  SB        ┃━━━━━━━━━━━━━━━━━━━┃ 1945  GÓRA       │      │
 │     RTL                                   [na ekranie]│      │
 │ …                                                     │      │
 ├──────────────────────────────────────────────────────┤      │
 │ TRYBY: CH5 · 1 AltHold · 4 Loiter · 6 Auto           │      │
 │ ⚠ 1 konflikt · ✔ RTL na CH6 · ✔ ARM na CH9           │      │
 └──────────────────────────────────────────────────────┴──────┘
```

Wiersz kanału: numer · zadeklarowany organ · pasek położenia · wartość µs · pozycja
(DÓŁ/ŚRODEK/GÓRA albo procent dla drążków) · funkcja z FC · przełącznik „obsługiwane
sprzętowo — nie pokazuj przycisku".

Wiersze z konfliktem dostają czerwony znacznik i są podniesione na górę listy — tak jak
w checkliście, żeby nie trzeba było ich szukać.

---

## 8. Stan wdrożenia

| Element | Stan |
|---|---|
| dekodowanie `RC_CHANNELS` | ✅ `SilnikStanu` |
| odczyt `RCn_OPTION`, `FLTMODE*`, zakresów | ✅ przy starcie i na żądanie |
| nazwy funkcji (tablica ze statusami) | ✅ `domain/Rc.kt` |
| pozycje DÓŁ/ŚRODEK/GÓRA | ✅ |
| wykrywanie konfliktów | ✅ duplikaty, brak RTL/ARM, kanał martwy |
| deklaracja organów i ukrywanie przycisków | ✅ zapis w `SharedPreferences` |
| eksport planu zmian na kartę TF | ⬜ M8 |
