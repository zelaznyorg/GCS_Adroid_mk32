# Zasady pracy nad GCS Pulpit

Historia repozytorium ma pozwalać ustalić: co zmieniono, dlaczego, na jakiej
platformie to sprawdzono i jak bezpiecznie wrócić do poprzedniego stanu.

## 1. Gałąź i zakres

Po pierwszym commicie nie pracujemy bezpośrednio na `main`. Nazwa gałęzi:

```text
feat/nazwa-funkcji
fix/krotki-opis
docs/temat
refactor/obszar
test/obszar
chore/obszar
```

Jeden commit realizuje jeden cel. Kod, test regresji i dokumentacja tej samej
zmiany należą do jednego commita. Niezwiązane formatowanie — do osobnego.

## 2. Komunikaty commitów

Format Conventional Commits:

```text
typ(zakres): krótki opis w trybie rozkazującym
```

Typy: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`.

Typowe zakresy: `pulpit`, `pokretlo`, `sieć`, `nagrania`, `katalog`, `sesja`,
`instalator`, `systemd`, `docs`.

Przykłady:

```text
fix(sieć): obsłuż połączenie z ukrytym SSID
feat(katalog): odśwież kafelki po zmianie pliku
test(pokretlo): sprawdź oddanie ogniska po rozłączeniu
docs(instalator): opisz powrót do sesji Raspberry Pi
```

Nie używamy komunikatów `update`, `poprawki`, `działa`, `final` ani `wip` na
gałęzi głównej.

Jeżeli przyczyna nie mieści się w tytule, treść commita zawiera:

```text
fix(sieć): obsłuż połączenie z ukrytym SSID

Interfejs wywoływał connect-hidden, lecz pomocnik uprzywilejowany znał tylko
connect. Dodano jawne przekazanie hidden=yes do nmcli.

Test: python3 -m unittest discover -s tests -v
Sprzęt: niewymagany
```

## 3. Dokładna procedura następnego commita

1. Pobierz aktualny `main` i utwórz gałąź:

   ```bash
   git switch main
   git pull --ff-only
   git switch -c fix/krotki-opis
   ```

2. Dla błędu zapisz oczekiwane zachowanie i, jeśli to możliwe, najpierw dodaj
   test odtwarzający problem.

3. Wprowadź jedną spójną zmianę. Nie dotykaj przy okazji obcych modułów.

4. Uruchom kontrole:

   ```bash
   python3 -m unittest discover -s tests -v
   python3 narzedzia/kontrola_nazw.py pulpit/gcs_pulpit/*.py pulpit/most/*.py pulpit/rpi/*.py
   python3 narzedzia/kontrola_nazw.py pulpit/rpi/gcs-siec
   sh -n pulpit/rpi/instaluj.sh pulpit/rpi/gcs-otoczenie pulpit/rpi/gcs-sesja pulpit/rpi/gcs-ui
   git diff --check
   ```

5. Przy zmianie GTK, Wayland, systemd, GPIO, sieci albo nagrywania wykonaj próbę
   na Raspberry Pi i zapisz wynik według `dok/BEZPIECZENSTWO.md`.

6. Uzupełnij `CHANGELOG.md` i odpowiedni dokument, jeżeli zmienił się kontrakt,
   konfiguracja, zależność lub procedura instalacji.

7. Dodaj wyłącznie konkretne pliki i przejrzyj staging:

   ```bash
   git status --short
   git add sciezka/do/kodu.py tests/test_modul.py CHANGELOG.md
   git diff --staged --check
   git diff --staged
   ```

8. Utwórz commit i wyślij gałąź:

   ```bash
   git commit -m "fix(sieć): obsłuż połączenie z ukrytym SSID"
   git push -u origin fix/krotki-opis
   ```

9. Otwórz pull request. Scalaj po przejściu CI i kontroli ręcznej. Drobne commity
   typu „fix review” można scalić przez **Squash and merge**.

## 4. Wymagane testy

| Zmiana | Minimum |
|---|---|
| czysta logika Python | test `unittest` bez GTK i sprzętu |
| parser JSON / katalog aplikacji | dane poprawne, uszkodzone i brakujące pola |
| most pokrętła | obrót, klik, rozłączenie i oddanie ogniska |
| operacja sieciowa | test argumentów bez powłoki + próba na NetworkManagerze |
| GTK / nawigacja | test ręczny myszą i pokrętłem na docelowej rozdzielczości |
| nagrywanie | brak źródła, zerwanie, zatrzymanie i poprawność pliku |
| systemd / labwc | instalacja, restart, awaria i droga powrotu |

Brak automatycznego testu nie zwalnia z udokumentowania próby ręcznej.

## 5. Dokumentowanie kodu

Docstring jest wymagany dla publicznych klas, funkcji tworzących kontrakt między
modułami oraz operacji mających skutki systemowe. Powinien określać:

- wejście, wynik i możliwe wyjątki,
- jednostki oraz dozwolone zakresy,
- wątek wykonania, jeżeli kod dotyka GTK,
- pliki, gniazda, GPIO albo procesy, które funkcja zmienia,
- zachowanie po awarii i sposób wycofania,
- czy operacja wymaga uprawnień administratora.

Komentarz wyjaśnia **dlaczego**, ograniczenia i pułapki; nie przepisuje składni.

W dokumentacji pomiarowej używamy oznaczeń:

- **FAKT** — wynik kodu, urządzenia albo powtarzalnego pomiaru,
- **DEKLARACJA** — informacja producenta,
- **INTERPRETACJA** — wniosek z faktów,
- **HIPOTEZA** — rzecz jeszcze niesprawdzona.

## 6. Kiedy aktualizować dokumentację

- `README.md` — wymagania, uruchomienie, zakres lub status,
- `dok/ARCHITEKTURA.md` — moduły, przepływ danych i odpowiedzialność,
- `dok/BEZPIECZENSTWO.md` — uprawnienia, procedury, droga powrotu,
- `dok/UI_PULPIT.md` — zachowanie widoczne i wynik próby sprzętowej,
- `CHANGELOG.md` — każda zmiana widoczna, instalacyjna lub operacyjna.

## 7. Czego nie commitujemy

- haseł Wi-Fi, tokenów dostępu, kluczy SSH i certyfikatów prywatnych,
- `CLAUDE.md` ze stanem konkretnego stanowiska,
- nagrań, logów, zrzutów zawierających dane operacyjne,
- plików `*.local.json`, `.env`, `router.json`, kopii i plików tymczasowych,
- `__pycache__`, środowisk wirtualnych i artefaktów buildów.

Sekret znaleziony w historii trzeba unieważnić; usunięcie go w następnym commicie
nie usuwa go z poprzedniego.

## 8. Warunki przyjęcia pull requestu

- CI przechodzi,
- zakres i powód są jasne,
- istnieje test albo zapis próby ręcznej,
- znana jest droga wycofania,
- dokumentacja i changelog są aktualne,
- nie ma sekretów ani danych konkretnego stanowiska.
