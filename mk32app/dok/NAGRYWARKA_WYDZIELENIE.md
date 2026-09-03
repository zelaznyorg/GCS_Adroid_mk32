# Wydzielenie NAGRYWARKI do osobnej aplikacji — analiza i plan

Decyzja Toma (2026-09-03): *„wydzielić osobną aplikację w źródłach do osobnego
katalogu w Smart GCS, aby w przyszłości móc ją rozbudowywać"*. Ten dokument opisuje,
**co dziś jest nagrywarką, gdzie jest zszyta z pulpitem i jak ją odciąć**, żeby
cięcie było jednym ruchem, a nie serią niespodzianek.

> ### ⛔ KOREKTA (ten sam dzień, kilka godzin później) — kod JEST w repozytorium
>
> Pierwsza wersja tego dokumentu twierdziła, że pulpit „nie jest w żadnym repozytorium".
> **Nieprawda.** Pulpit żyje w **SmartGCS** (`zelaznyorg/SmartGCS`), linia `main`,
> kopia robocza `C:\Soft\gcs-DRON\RPI` — z `pulpit/`, `pogoda/`, `stacja/`, `dok/`,
> `tests/`. Na malinie `~/gcs-src` to **wdrożenie** z tego repo (`narzedzia/wdroz.sh`:
> *„malina dostaje jedynie finalne wdrożenia"*), bez `.git`. Malina była nawet
> **starsza** od repo — brakowało jej `fix(audyt)` z 2026-09-03 09:56.
> Szukałem kodu tylko na maszynie i zrobiłem zdublowaną „migawkę" w klonie
> `C:\Soft\gcs-DRON\GSB` (stara linia PI5setup, bez pulpitu) — cofnięta.
>
> **Wydzielenie robione jest w SmartGCS**, worktree `C:\Soft\gcs-DRON\RPI-nagrywarka`,
> gałąź `feat/nagrywarka-osobno` od `fix/audyt-kodu` (a38ea5f). Krok 1 („migawka")
> **odpada** — historia już jest. Układ katalogów niżej: `nagrywarka/`, `wspolne/`
> obok `pulpit/` w korzeniu SmartGCS (nie `mk32app/`).

Źródła: SmartGCS `pulpit/gcs_pulpit/` — 5001 linii Pythona w 18 plikach.

---

## 1. Co jest nagrywarką — trzy pliki i czterdzieści linii kleju

| Plik | Linie | Co robi | Zależy od GTK? | Zależy od pulpitu? |
|---|---|---|---|---|
| `nagrywanie.py` | 438 | **silnik**: `Zrodlo`, lista źródeł (`~/.config/gcs/zrodla-obrazu.json`), `Rejestrator` (jeden `ffmpeg -c copy` na źródło), `Nagrywarka` (wszystkie naraz) | **nie** — czysty Python, `subprocess` | **nie** — jedyne odwołanie to `ZRODLA_STACJI = /var/lib/dron15/zrodla.json` jako źródło adresu ZR30 przy pierwszym uruchomieniu |
| `nagrywarka.py` | 275 | **ekran ⏺ NAGRYWARKA**: lista źródeł, klik startuje/zatrzymuje, dodaj/usuń/przełącz | tak | `widoki.przewin_do`, `widoki.przycisk_listy` + trzy wywołania zwrotne z pulpitu (`na_tekst`, `na_komunikat`, `na_zmiane`) |
| `nagrania.py` | 466 | **przeglądarka nagrań + odtwarzacz `mpv`** (nakładka layer-shell, sterowanie pokrętłem przez gniazdo mpv, długości przez `ffprobe` w tle) | tak, + `Gtk4LayerShell` | `widoki`; ścieżki na sztywno: `/var/lib/gcs/nagrania/<id>/` i `/var/lib/dron15/archiwum/wideo/zr30` |

Klej w `okno.py` (1191 linii, z czego nagrywania dotyczy ok. 40):

| Miejsce | Co |
|---|---|
| l. 44–46 | importy `Nagrania`, `Odtwarzacz`, `uruchom_mpv`, `Nagrywarka`, `EkranNagrywarki` |
| l. 201 | `self._nagrywarka = Nagrywarka()` — **jedna instancja na życie pulpitu**, celowo (§ „⛔ NIE tworzyć tu na nowo nagrywarki", l. 938) |
| l. 114 → `_odswiez_nagrywanie` (l. 1091) | **zegar 1 s**: `sprawdz()` (opróżnia rury ffmpega — bez tego ffmpeg blokuje się po 165 s, zmierzone), odmalowanie belki i ekranu, **meldunek stanu do panelu GC9A01** `self._pokretlo.zglos_stan(nagrywa, opis)` |
| l. 215–229 | przycisk `⏺ REC` na belce → `_przelacz_nagrywanie()` → `przelacz_wszystkie()` |
| l. 345–348, 409–411 | kafelki **NAGRANIA** i **NAGRYWARKA**; kafelek REC zmienia opis, gdy pisze |
| l. 649–659 `_na_polecenie` | polecenia z okrągłego panelu: `"nagrywanie"` (REC) i `"nagrania"` (otwórz listę na dużym ekranie) |
| l. 873–911 | ekrany w `Gtk.Stack` (`"nagrywarka"`, `"nagrania"`), otwieranie i zamykanie |
| l. 917–948 | uruchomienie `mpv` i nakładka odtwarzacza |

Wspólne z resztą pulpitu: `widoki.py` (99 linii — `przewin_do`, `przycisk_listy`),
`ikony.py` (role ikon), klasy CSS `belka-rec*`, `kafelek-rec*`, `nagrw-*` w `styl.css`.

**Wniosek:** silnik jest już dziś **niezależną biblioteką bez GTK**. Cięcie nie
wymaga przepisywania — wymaga przeniesienia trzech plików, wskazania pulpitowi
nowego miejsca importu i zebrania siedmiu punktów styku w jeden mały interfejs.

---

## 2. Granica cięcia — co proponuję

```
mk32app/
├── nagrywarka/                      ← NOWE: osobna aplikacja, własny README, własne testy
│   ├── nagrywarka/
│   │   ├── __init__.py
│   │   ├── zrodla.py                ← Zrodlo, wczytaj/zapisz/domyślne   (z nagrywanie.py)
│   │   ├── rejestrator.py           ← Rejestrator, Nagrywarka            (z nagrywanie.py)
│   │   ├── spis.py                  ← lista nagrań, długości ffprobe     (z nagrania.py, bez GTK)
│   │   └── __main__.py              ← CLI: `zrodla`, `start`, `stop`, `stan` — próby BEZ pulpitu
│   └── nagrywarka_gtk/
│       ├── ekran.py                 ← EkranNagrywarki                    (nagrywarka.py)
│       ├── nagrania.py              ← przeglądarka                       (nagrania.py)
│       └── odtwarzacz.py            ← Odtwarzacz + mpv                   (nagrania.py)
└── pulpit/                          ← MIGAWKA gcs_pulpit + rpi/ + dok/ (dziś tylko na malinie)
    └── gcs_pulpit/okno.py           ← importuje `nagrywarka`, `nagrywarka_gtk`; klej zostaje
```

### Dlaczego dwa pakiety, a nie jeden

`nagrywarka` (silnik) ma **zero GTK** i da się go uruchomić z konsoli, w teście,
albo — kiedyś — jako osobną usługę. `nagrywarka_gtk` to widoki dla pulpitu. Ktoś,
kto chce rozbudować nagrywanie (harmonogram, limit miejsca, nagrywanie z panelu
bez pulpitu), dotyka pierwszego i nie musi wiedzieć nic o GTK ani o pokrętle.

### Co zostaje w pulpicie, celowo

Belka `⏺ REC`, kafelki, zegar 1 s i meldunek do panelu GC9A01 — to jest **pulpit
używający nagrywarki**, nie nagrywarka. Zostają w `okno.py`, tylko wołają nowy pakiet.

### ⚠ `widoki` i `ikony` — jedyny prawdziwy węzeł

`nagrywarka_gtk` potrzebuje `przewin_do` i `przycisk_listy` z pulpitu. Trzy drogi:

| Droga | Ocena |
|---|---|
| `nagrywarka_gtk` importuje `gcs_pulpit.widoki` | ⛔ odwrotna zależność — nagrywarka nie może istnieć bez pulpitu |
| skopiować `widoki.py` do `nagrywarka_gtk` | ⚠ dwie kopie tego samego, rozjadą się |
| **wspólny pakiet `gcs_wspolne/` (`widoki.py`, `ikony.py`)**, importowany przez oba | ✅ **zalecane** — 220 linii, bez własnego stanu |

### Ścieżki na sztywno → jedno miejsce

`/var/lib/gcs/nagrania`, `~/.config/gcs/zrodla-obrazu.json`, `/var/lib/dron15/zrodla.json`,
`/var/lib/dron15/archiwum/wideo/zr30`, gniazdo mpv `/run/user/1000/gcs-mpv.sock` —
dziś rozsiane po trzech plikach. W `nagrywarka/ustawienia.py`, ze zmiennymi
środowiska (`GCS_NAGRANIA` już istnieje).

---

## 3. Jak to wdrożyć na stacji bez zmiany usługi

`gcs-pulpit.service` uruchamia `python3 -m gcs_pulpit` z `WorkingDirectory=/opt/gcs/pulpit`
— przy `-m` katalog roboczy jest na `sys.path`, więc **pakiety leżące obok
`gcs_pulpit/` importują się bez żadnej konfiguracji**. `instaluj.sh` kopiuje dziś
`gcs_pulpit/` do `/opt/gcs/pulpit/`; dokłada się dwie linie dla `nagrywarka/`,
`nagrywarka_gtk/` i `gcs_wspolne/`. Usługa, sudoers, panel GC9A01 — **bez zmian**.

---

## 4. Kolejność — trzy commity, każdy odwracalny

| Krok | Co | Ryzyko |
|---|---|---|
| **1. migawka** | `mk32app/pulpit/` = kopia 1:1 z maliny (`gcs_pulpit`, `rpi`, `dok`). **Zero zmian w kodzie.** Od tej chwili pulpit ma historię | żadne — to tylko zapis stanu |
| **2. cięcie** | przenieść trzy pliki do `mk32app/nagrywarka/`, wydzielić `gcs_wspolne`, poprawić importy w `okno.py`, `ustawienia.py` na ścieżki, CLI w `__main__.py`, README | średnie: importy i CSS. Sprawdzalne **bez maliny**: `python -m nagrywarka zrodla` na laptopie, `python -m compileall`, testy silnika z udawanym `ffmpeg` |
| **3. wgranie** | `instaluj.sh` + restart `gcs-pulpit`; próba: REC na `cvbs`, ekran NAGRYWARKA, panel GC9A01 | ⚠ wymaga maliny i chwili, gdy nic nie nagrywa |

---

## 5. Czego NIE robić w tym kroku

- ⛔ **Nie zmieniać zachowania.** Zegar 1 s, opróżnianie rur, `-timeout` zamiast
  `-rw_timeout`, `q` przed `SIGINT` — każda z tych rzeczy ma za sobą zmierzoną
  usterkę (`nagrywanie.py`, `UI_PULPIT.md` §17.6). Przenosimy, nie poprawiamy.
- ⛔ **Nie robić z tego od razu osobnej usługi (demona).** Kusi — nagranie
  przeżyłoby restart pulpitu — ale znaczy własny protokół, własny plik stanu i drugi
  proces do pilnowania. Podział na pakiety bez GTK **umożliwia** to później, jednym
  krokiem; dziś nie ma takiej potrzeby.
- ⛔ **Nie dotykać `pi5-camera-recorder`** ani niczego w `PI5setup full`.

---

## 6. Rozstrzygnięte przez Toma (2026-09-03)

1. Miejsce: **repozytorium SmartGCS**, linia główna (kopia `RPI`), nie `dron15`
   i nie klon `GSB`. Katalogi `nagrywarka/` i `wspolne/` w korzeniu, obok `pulpit/`.
2. Zakres cięcia: **przenosiny bez zmiany zachowania** — trzy pliki w całości
   (`nagrywanie.py`, `nagrywarka.py` → `ekran.py`, `nagrania.py`), nie drobniejszy
   podział z §2. Ten podział (zrodla/rejestrator/spis) zostaje jako możliwy krok
   następny, gdy będzie po co.
3. Wynik i lista rzeczy niesprawdzonych: SmartGCS `nagrywarka/README.md` oraz
   `dok/UI_PULPIT.md` §17.11.
