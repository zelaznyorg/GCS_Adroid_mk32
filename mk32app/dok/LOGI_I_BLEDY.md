# Logi i obsługa błędów

Jak trzy części tego projektu — kokpit na MK32, serwer podglądu i narzędzia w Pythonie —
zapisują to, co poszło nie tak, i gdzie tego szukać po powrocie z pola.

**Data:** 2026-08-20
**Stan:** zaimplementowane i sprawdzone (§7)

---

## 1. Zasada: dwa różne strumienie, nigdy zmieszane

To jest najważniejsza rzecz w całym dokumencie, bo pomylenie ich kosztuje czas przy każdej
usterce:

| Strumień | Dla kogo | Co zawiera | Gdzie |
|---|---|---|---|
| **komunikaty** | dla pilota / operatora | krótko, po polsku, „co się dzieje" | ekran kokpitu, dziennik dostępu w panelu admina |
| **rejestr** | dla nas | ze stosem wywołań, „dlaczego nie zadziałało" | plik na dysku, ekran DIAGNOSTYKA, panel admina |

Pilot w powietrzu nie ma czytać stosów wywołań. My, szukając usterki, nie mamy zgadywać
z komunikatu „Lacze przerwane", co dokładnie się stało.

W kodzie odpowiadają temu osobne wywołania — i nie wolno ich zastępować jednym:

| Warstwa | Dla pilota | Dla nas |
|---|---|---|
| kokpit MK32 | `SilnikStanu.dopiszKomunikat(...)` | `Dziennik.blad/ostrzezenie/info(...)` |
| serwer podglądu | `dostep.zapiszZdarzenie(...)` | `rejestr.blad/ostrzezenie/info(...)` |
| narzędzia Python | `print(...)` na konsolę | `dziennik.pisz(...)` + pułapka na wyjątki |

---

## 2. Co robimy z wyjątkiem, którego nikt nie złapał

Odpowiedź nie jest wszędzie taka sama, bo stawka nie jest wszędzie taka sama.

| Gdzie | Zachowanie | Dlaczego |
|---|---|---|
| **serwer podglądu** (`uncaughtException`) | zapisz → posprzątaj → **zejdź z pola**, kod 1 | po nieprzechwyconym wyjątku proces jest w stanie **nieokreślonym**; serwer, który udaje, że działa, jest gorszy od wyłączonego, bo nikt tego nie zauważy |
| **serwer podglądu** (`unhandledRejection`) | zapisz → **pracuj dalej** | to zwykle jedno zgubione `await`; reszta programu jest zdrowa |
| **kokpit MK32** | zapisz → **oddaj systemowi**, proces ginie normalnie | aplikacja pokazująca po awarii dane, którym nie wolno wierzyć, jest przy locie groźniejsza niż zamknięte okno |
| **korutyny łączy** | zapisz → **zabierz to jedno łącze** | wymóg z [ARCHITEKTURA.md](ARCHITEKTURA.md): awaria obrazu nie może zabrać telemetrii |
| **narzędzia Python** | zapisz → zdanie po polsku → kod 1 | narzędzie ma powiedzieć, **co zrobić**, a nie pokazać ścianę tekstu |

Skoro serwer schodzi z pola celowo, **podnoszenie należy do warstwy wyżej**:

```bash
sh start.sh --pilnuj
```

Dozorca podnosi serwer po awarii z rosnącym odstępem (5 s, 10 s, … do minuty), a wyjście
kodem 0 — czyli zatrzymanie świadome — kończy pętlę. Na RPi docelowo lepszy jest systemd;
`--pilnuj` istnieje po to, żeby nie czekać z tym do wdrożenia.

---

## 3. Kokpit na MK32

Moduł: `app/cockpit/src/main/java/pl/dron15/cockpit/diag/Dziennik.kt`
Pułapka: `KokpitApp.kt` — klasa `Application`, wstaje **przed** pierwszym ekranem.

> Dlaczego w `Application`, a nie w `MainActivity`: awaria potrafi zdarzyć się przy starcie,
> zanim pojawi się jakikolwiek ekran. Pułapka zarejestrowana w aktywności nie zdążyłaby
> jej złapać i po powrocie z pola nie byłoby czego czytać.

**Gdzie ląduje plik:**

```
Android/data/pl.dron15.cockpit/files/logi/kokpit-RRRR-MM-DD.log
```

To katalog własny aplikacji — nie wymaga żadnych uprawnień, a mimo to widać go z komputera
po podpięciu MK32 kablem. Trzymamy **siedem dni**, starsze kasujemy przy starcie: karta
w aparaturze jest mała.

**Poziomy:** `BLAD`, `OSTRZ`, `INFO`, `SZCZEG`. Szczegóły zapisujemy tylko w wersji debug —
inaczej strumień telemetrii zasypałby plik.

**Na ekranie DIAGNOSTYKA** są teraz dwie listy obok siebie: po lewej to, co powiedziała
**maszyna**, po prawej to, co zepsuło się **w aplikacji**, z godziną, obszarem i skróconym
stosem.

**Ślad po awarii przeżywa restart.** Aplikacja zapisuje `ostatnia_awaria.txt` i przy
następnym wejściu na DIAGNOSTYKĘ pokazuje czerwony pas: *poprzednie uruchomienie skończyło
się awarią*. Znika po dotknięciu. Bez tego pilot, któremu aplikacja zamknęła się w polu,
nie miałby jak się dowiedzieć, że w ogóle coś się stało.

**Logcat zostaje** — przy podpiętym kablu to najszybsza droga:

```bash
"C:/Android/platform-tools/adb.exe" logcat -s DRON15
```

Ściągnięcie pliku z aparatury:

```bash
"C:/Android/platform-tools/adb.exe" pull /sdcard/Android/data/pl.dron15.cockpit/files/logi
```

---

## 4. Serwer podglądu

Moduł: `serwer/server/rejestr.mjs`

```
logi/serwer.log      rejestr techniczny — poziomy, obszary, stosy; rotowany co 5 MB, 3 archiwa
logi/konsola.log     surowe wyjście procesu — łapie też to, co pada zanim rejestr wstanie
logi/mediamtx.log    wyjście MediaMTX
```

Format jednej linii, do czytania **gregiem, nie parserem**:

```
2026-08-20T10:45:23.300Z INFO   [start       ] nasłuch http://0.0.0.0:8097
2026-08-20T10:45:23.901Z OSTRZ  [api         ] nieznana trasa GET /api/nie-ma-takiej
2026-08-20T10:45:54.209Z BLAD   [krytyczny   ] nieprzechwycony wyjątek — kończę pracę | {"blad":"...","stos":"..."}
```

**Obszary:** `start`, `api`, `telemetria`, `mediamtx`, `obecnosc`, `dostep`, `krytyczny`,
`obietnica`. Filtrowanie: `grep "\[mediamtx" logi/serwer.log`.

**Poziom podnosi się zmienną:** `POZIOM=szczegol sh start.sh`.

**W panelu administratora** jest sekcja **REJESTR TECHNICZNY** z filtrem poziomu — te same
wpisy, bez logowania się na stację po SSH.

Trzy drogi, którymi łapiemy błędy w Express, bo w Node nie ma jednej:

| Skąd błąd | Kto łapie |
|---|---|
| trasa `async` | `wrap()` — loguje ze stosem i oddaje 500 |
| trasa synchroniczna | warstwa błędu Express (`app.use` z czterema argumentami) |
| timer, gniazdo, cokolwiek poza trasą | pułapki procesu w `rejestr.mjs` |

Dołożone przy okazji: **nieznana trasa `/api/*` daje 404 z JSON-em**, a nie stronę HTML.
Bez tego literówka w adresie kończy się komunikatem „Unexpected token <" w konsoli
przeglądarki, a szukanie takiej pomyłki potrafi zająć godzinę.

---

## 5. Narzędzia w Pythonie

Moduł: `tools/dziennik.py`, plik: `dok/logi/narzedzia.log` (obcinany po 2 MB).

> W tym samym katalogu leżą **logi lotu z FC** (`log_006.bin` i podobne). To celowo
> jedno miejsce: szukając „logów" człowiek zagląda raz. Rozróżnia je rozszerzenie —
> `.bin` to zapis z maszyny, `.log` to zapis z narzędzia.

Wpięty we **wszystkie 16 narzędzi w `tools/` i 6 w `mk32app/narzedzia/`** — dwiema liniami
zaraz po importach:

```python
from dziennik import zainstaluj

zainstaluj("fc_read_params")
```

Co to zmienia. Zamiast ściany tekstu, która znika razem z zamkniętym oknem konsoli:

```
BLAD: Nie moge otworzyc portu. Sprawdz, czy FC jest podpiety i czy to na pewno COM9.
Szczegoly zapisane w: C:\Soft\gcs-DRON\dron15\dok\logi\narzedzia.log
```

Pełny stos idzie do pliku. **Rozpoznawane przypadki** — te, które faktycznie zdarzały się
w tym projekcie — dostają zdanie mówiące, **co zrobić**:

| Objaw | Komunikat |
|---|---|
| port zajęty | „Zamknij Mission Planner i spróbuj ponownie." |
| nie ma portu | „Sprawdź, czy FC jest podpięty i czy to na pewno COM9." |
| brak odpowiedzi | „Kontroler nie odpowiedział w czasie." |
| brak biblioteki | „Brakuje biblioteki: … Zainstaluj ją przez pip." |

**Ctrl+C nie jest awarią** — kończy cicho, kodem 130, bez wpisu o błędzie.

---

## 6. Czego celowo nie ma

- **Wysyłania logów gdziekolwiek.** Zostają na urządzeniu. Stacja nie ma wyjścia
  w internet poza tunelem, a log z lotu to nie jest rzecz do wysyłania w świat.
- **Osobnego formatu maszynowego (JSON Lines).** Przy tej skali plik ma się dać
  przeczytać `tailem` i przefiltrować `grepem`; parser byłby pracą bez pokrycia.
- **Logowania każdego żądania HTTP.** Strumień SSE zasypałby plik. Notujemy błędy,
  ostrzeżenia i zdarzenia dostępu — nie ruch.
- **Zbierania logów z MK32 na stację.** Kokpit ma być samodzielny; dokładanie zależności
  od stacji po to, żeby mieć logi w jednym miejscu, przeczyłoby zasadzie z
  [PLAN.md](../PLAN.md) §1.

---

## 7. Co sprawdzone

**Serwer** (2026-08-20, na biurku):

| Co | Wynik |
|---|---|
| plik `logi/serwer.log` powstaje, wpisy startowe | tak |
| nieznana trasa `/api/*` | 404 z JSON-em + wpis `OSTRZ` |
| `unhandledRejection` | wpis `BLAD`, **proces pracuje dalej** |
| `uncaughtException` | wpis `BLAD`, sprzątanie wykonane, **wyjście kodem 1** |
| podgląd rejestru przez `/api/admin/logi` z filtrem poziomu | działa |

**Kokpit:** kompiluje się, **45 testów jednostkowych przechodzi**, APK się buduje.

**Narzędzia:** wszystkie 23 pliki (22 narzędzia + sam moduł) kompilują się;
`fc_version.py COM99` — czyli port,
którego nie ma — daje komunikat po polsku i pełny stos w pliku.

**Niesprawdzone:** zachowanie pułapki awaryjnej na prawdziwym MK32 (jak wszystko w tym
projekcie — patrz [PLAN.md](../PLAN.md) §8a) oraz dozorca `--pilnuj` na RPi.
