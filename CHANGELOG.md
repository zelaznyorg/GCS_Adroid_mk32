# Historia zmian

Format opiera się na [Keep a Changelog](https://keepachangelog.com/pl/1.1.0/).

## [Unreleased]

### Dodano

- miejsce na opis zmian przygotowywanych do następnego wydania.

## [0.1.0] - 2026-08-29

### Dodano

- pierwszy publiczny import pulpitu GCS dla Raspberry Pi,
- natywny interfejs GTK4 obsługiwany pokrętłem,
- katalog aplikacji, klawiaturę ekranową, obsługę sieci i nagrań,
- integrację z panelem GC9A01 przez gniazdo Unix,
- instalator, sesję labwc, usługę użytkownika i bezpieczną drogę powrotu,
- test pomocnika sieciowego i automatyczne kontrole GitHub Actions,
- zasady commitowania, dokumentowania i prób sprzętowych.

### Naprawiono

- połączenie z ukrytą siecią Wi-Fi przekazuje teraz `hidden=yes` do `nmcli`,
- dokumentacja wskazana przez usługę jest kopiowana podczas instalacji.

### Bezpieczeństwo

- usunięto żeton dostępu ze wzorcowej konfiguracji kafelka DRON15,
- wykluczono z publicznego repozytorium pamięć konkretnego stanowiska i sekrety.
