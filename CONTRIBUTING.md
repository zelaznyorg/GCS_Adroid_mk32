# Zasady pracy nad DRON15 GCS

Ten dokument obowiązuje przy każdym kolejnym commicie. System współpracuje z rzeczywistym
statkiem powietrznym, dlatego czytelność zmian i możliwość ich zweryfikowania są częścią
bezpieczeństwa, a nie tylko kwestią stylu.

## 1. Zasada jednego celu

Jeden commit ma realizować jeden spójny cel. Nie należy łączyć poprawki protokołu,
zmiany wyglądu, porządkowania nazw i aktualizacji zależności w jednym commicie.

Przed rozpoczęciem:

```powershell
git status
git pull --ff-only
git switch -c fix/krotki-opis
```

Dozwolone prefiksy gałęzi: `feat/`, `fix/`, `docs/`, `test/`, `refactor/`,
`build/`, `security/` i `chore/`.

## 2. Kolejność wykonania zmiany

1. Opisz problem i warunek uznania go za rozwiązany.
2. Ustal, którego elementu dotyczy zmiana: `android`, `serwer`, `web`, `rpi`,
   `narzedzia`, `fc` albo `dok`.
3. Wprowadź najmniejszą kompletną zmianę.
4. Dodaj lub popraw test, jeżeli zachowanie da się sprawdzić automatycznie.
5. Zaktualizuj dokumentację, gdy zmienia się protokół, konfiguracja, procedura,
   bezpieczeństwo, interfejs użytkownika albo zachowanie operatora.
6. Uruchom kontrole odpowiednie dla zmienionego elementu.
7. Obejrzyj dokładnie pliki przygotowane do commita.
8. Dopiero wtedy wykonaj commit i push.

## 3. Kontrole przed commitem

Android:

```powershell
cd mk32app\app
C:\Gradle\gradle-8.4\bin\gradle.bat :cockpit:testDebugUnitTest
C:\Gradle\gradle-8.4\bin\gradle.bat :cockpit:assembleDebug
```

PWA:

```powershell
cd mk32app\serwer\web
npm ci
npm run lint
npm run build
```

Narzędzia Python:

```powershell
python -m compileall -q tools mk32app\narzedzia
```

Przed zatwierdzeniem zawsze:

```powershell
git status --short
git diff --check
git diff --cached --stat
git diff --cached
```

Jeżeli zmiana dotyczy tylko jednego komponentu, wystarczą jego kontrole. W opisie
commita albo pull requestu trzeba zapisać, co uruchomiono i czego nie można było
sprawdzić bez sprzętu.

## 4. Format komunikatu commita

Stosujemy format:

```text
typ(obszar): krótkie polecenie po polsku
```

Typy:

- `feat` — nowa funkcja,
- `fix` — poprawka błędu,
- `docs` — wyłącznie dokumentacja,
- `test` — testy bez zmiany zachowania produkcyjnego,
- `refactor` — przebudowa bez zmiany funkcji,
- `perf` — wydajność,
- `build` — budowanie i zależności,
- `ci` — automatyczne kontrole,
- `security` — bezpieczeństwo,
- `chore` — pozostałe prace techniczne.

Obszary: `android`, `mavlink`, `misja`, `mapy`, `kamera`, `siyi`, `serwer`,
`web`, `rpi`, `fc`, `narzedzia` lub `dok`.

Przykłady:

```text
fix(mavlink): ponów żądanie brakującego punktu misji
feat(rpi): dodaj kontrolę temperatury w panelu stacji
docs(kamera): opisz przejście z SIYI na RTSP
```

Pierwsza linia ma mieć najwyżej około 72 znaków, bez kropki na końcu. Przy zmianie
nieoczywistej dodaj treść wyjaśniającą **dlaczego** została wykonana, ryzyko oraz
sposób sprawdzenia. Zmianę niezgodną wstecznie oznacz `BREAKING CHANGE:`.

## 5. Dokumentowanie kodu

- Nazwy w kodzie opisują zamiar; komentarz wyjaśnia przyczynę, ograniczenie lub
  nietypowe zachowanie, a nie powtarza instrukcji z następnej linii.
- Publiczne klasy i funkcje związane z protokołem, bezpieczeństwem, stanem lotu
  lub konfiguracją otrzymują KDoc/JSDoc/docstring.
- Przy ramkach MAVLink i SIYI podaj identyfikator wiadomości/polecenia, jednostki,
  zakres wartości, źródło definicji i warunek odrzucenia danych.
- Przy liczbach czasowych i progach zapisz jednostkę w nazwie (`timeoutMs`, `ciszaS`)
  albo bezpośrednio w dokumentacji.
- Obejścia sprzętowe muszą zawierać model/wersję, objaw, uzasadnienie i bezpieczny
  warunek usunięcia obejścia.
- Dokumentacja stanu rozróżnia: **sprawdzone automatycznie**, **sprawdzone na
  stanowisku**, **sprawdzone w locie** i **planowane**.
- Nie zapisujemy jako faktu zachowania sprzętu, którego nie zmierzono.

## 6. Zmiany związane z bezpieczeństwem lotu

Zmiany wysyłające komendy, zapisujące parametry, modyfikujące misję albo wpływające
na RTL/LAND wymagają:

1. testu jednostkowego lub testu protokołu;
2. sprawdzenia warunków blokujących i potwierdzenia operatora;
3. próby bez śmigieł;
4. zapisania wyniku testu stanowiskowego;
5. osobnej, zatwierdzonej procedury przed pierwszą próbą w locie.

Narzędzia zapisujące do kontrolera muszą domyślnie działać jako `dry-run` i wymagać
jawnej flagi, np. `--yes`, do wykonania zapisu. Nie wolno usuwać tych zabezpieczeń
dla wygody testu.

## 7. Dane zabronione w repozytorium

Nie commitujemy kluczy, tokenów, certyfikatów lokalnych, plików dostępu, danych
osobowych operatorów, publicznych adresów konkretnej stacji, kopii parametrów FC,
surowych logów lotów, nagrań, kafelków map, danych terenu, APK, katalogów budowania,
`node_modules` ani materiałów producenta, których licencja nie pozwala publikować.

Przed commitem sprawdź, czy lista nie zawiera m.in. `*.key`, `*.pem`, `*.parm`,
`*.bin`, `*.tlog`, `*.apk`, `local.properties`, `zrodla.json` lub `dostep.json`.
Sam `.gitignore` nie zastępuje kontroli `git diff --cached`.

## 8. Aktualizacja dokumentacji i changeloga

Zmiana zachowania widocznego dla operatora, sposobu instalacji, formatu danych,
portów, uprawnień lub procedury awaryjnej wymaga wpisu w sekcji `Unreleased`
w `CHANGELOG.md`.

Dokumentację poprawia się w tym samym commicie co kod. Historycznych pomiarów nie
przepisujemy: dodajemy datowaną korektę i wskazujemy, co zostało ponownie sprawdzone.

## 9. Push i przegląd

```powershell
git push -u origin nazwa-galezi
```

Gałąź `main` powinna przyjmować zmiany po przejściu automatycznych kontroli. Force-push
na `main` jest zabroniony po ustanowieniu właściwej historii repozytorium. Wyjątkiem
jest jednorazowa, jawnie zatwierdzona naprawa błędnego pierwszego commita.
