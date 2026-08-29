# Mapa źródeł

Ten dokument wskazuje obowiązujące miejsca w repozytorium. Konfiguracja działającej
stacji jest celowo przechowywana poza Git.

## Kod aplikacji

| Ścieżka | Odpowiedzialność |
|---|---|
| `pulpit/gcs_pulpit/` | natywna aplikacja GTK4 |
| `pulpit/most/gcs_most.py` | most zdarzeń z panelu pokrętła |
| `pulpit/aplikacje.d/` | publiczne szablony kafelków |
| `pulpit/rpi/` | instalator, systemd, labwc i pomocniki systemowe |
| `narzedzia/` | diagnostyka i statyczna kontrola kodu |
| `tests/` | testy niewymagające GTK ani Raspberry Pi |

## Dokumentacja

| Plik | Zawartość |
|---|---|
| `README.md` | zakres, wymagania, instalacja i podstawowe sprawdzenie |
| `CONTRIBUTING.md` | obowiązujący proces commitów i dokumentowania |
| `CHANGELOG.md` | zmiany wydań i sekcja `Unreleased` |
| `dok/ARCHITEKTURA.md` | moduły, przepływ wejścia i granice uprawnień |
| `dok/BEZPIECZENSTWO.md` | procedura instalacji, prób i wycofania |
| `dok/UI_PULPIT.md` | szczegółowy projekt oraz dziennik decyzji UI |

## Konfiguracja na stacji

Pliki konkretnej maszyny nie należą do publicznego repozytorium:

| Lokalizacja | Przeznaczenie |
|---|---|
| `/etc/gcs/aplikacje.d/` | systemowe kafelki aplikacji |
| `~/.config/gcs/aplikacje.d/` | lokalne nadpisania, np. adres i żeton podglądu |
| `~/.config/gcs/router.json` | opcjonalny dostęp tylko-odczyt do routera |
| `/var/lib/gcs/nagrania/` | nagrania i stan rejestratora |
| `/run/gcs/pokretlo.sock` | ulotne gniazdo mostu pokrętła |

## Zasada źródła prawdy

- kod i publiczne wartości domyślne zmieniamy w repozytorium,
- sekrety oraz adresy konkretnego stanowiska zmieniamy tylko w konfiguracji lokalnej,
- wyniki prób zapisujemy w dokumentacji bez tokenów, kluczy i publicznych adresów IP,
- nie kopiujemy do tego repozytorium kodu osobnego serwera podglądu ani aplikacji
  sterującej dronem.
