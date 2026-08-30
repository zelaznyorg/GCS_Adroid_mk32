# System operatorski DRON15

Ten katalog zawiera dwie współpracujące aplikacje oraz narzędzia integracyjne:

- `app/` — główny kokpit Android na SIYI MK32;
- `serwer/` — opcjonalny serwer podglądu i PWA wdrażane m.in. na Raspberry Pi 5;
- `narzedzia/` — symulator telemetrii, narzędzia SIYI/MAVLink, mapy i retransmisja;
- `dok/` — dokumentacja architektury, interfejsów i wdrożenia.

Nadrzędny opis projektu oraz zasady pracy znajdują się w
[`../README.md`](../README.md) i [`../CONTRIBUTING.md`](../CONTRIBUTING.md).

## Kokpit MK32

Kokpit jest natywną aplikacją Android dla SIYI MK32. Działa samodzielnie i nie
potrzebuje stacji RPi do sterowania maszyną. Łączy się z kontrolerem lotu przez
MAVLink/UDP, z głowicą ZR30 przez SIYI SDK/UDP i odbiera obraz przez tor SIYI/RTSP.

Aktywne ekrany:

- **LOT** — podstawowe przyrządy, ostrzeżenia i potwierdzane akcje RTL/LAND;
- **MISJA** — mapa, trasa, przesyłanie misji, pauza, wznowienie i skok do punktu;
- **KAMERA** — obraz oraz sterowanie ruchem, zoomem, ostrością i nagrywaniem ZR30;
- **PRZED LOTEM** — checklista, parametry i kontrola PreArm;
- **RC** — podgląd kanałów i przypisań aparatury;
- **DIAGNOSTYKA** — stan telemetrii, obrazu, głowicy, GNSS/EKF i komunikaty FC.

Aplikacja wysyła do kontrolera wybrane komendy MAVLink. Nie wysyła komendy
ARM/DISARM ani ręcznego sterowania drążkami — te funkcje pozostają na fizycznych
elementach aparatury.

## Stacja podglądu RPi

Serwer odbiera udostępnioną przez MK32 telemetrię i obraz z kamery, przekazuje je
do PWA, obsługuje widzów i archiwum oraz może uruchomić interfejs kioskowy na
monitorach. Domyślna ścieżka podglądu jest tylko do odczytu względem drona.

Szczegóły:

- [`serwer/README.md`](serwer/README.md),
- [`dok/GCS_RPI5.md`](dok/GCS_RPI5.md),
- [`dok/WDROZENIE_RPI.md`](dok/WDROZENIE_RPI.md),
- [`dok/SERWER_PODGLADU.md`](dok/SERWER_PODGLADU.md).

## Stan zweryfikowany lokalnie

Na komputerze deweloperskim przechodzą:

- budowanie debug APK;
- 152 testy jednostkowe JVM aplikacji Android;
- budowanie PWA.

Testy automatyczne nie zastępują próby na MK32, ZR30, kontrolerze lotu i docelowej
stacji RPi. Dokumentacja pomiarów sprzętowych jest datowana i wyraźnie oddzielona
od zachowania potwierdzonego wyłącznie testami.

## Najważniejsze dokumenty

- [`PLAN.md`](PLAN.md) — plan rozwoju i decyzje architektoniczne;
- [`TODO.md`](TODO.md) — lista otwartych prac;
- [`dok/ARCHITEKTURA.md`](dok/ARCHITEKTURA.md) — moduły i przepływy;
- [`dok/WLADZA.md`](dok/WLADZA.md) — model uprawnień;
- [`dok/INTERFEJSY.md`](dok/INTERFEJSY.md) — protokoły i porty;
- [`dok/MISJE.md`](dok/MISJE.md) — misje;
- [`dok/MAPY.md`](dok/MAPY.md) — mapy i teren;
- [`dok/WIDEO.md`](dok/WIDEO.md) — obraz;
- [`dok/LOGI_I_BLEDY.md`](dok/LOGI_I_BLEDY.md) — diagnostyka.
