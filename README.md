# DRON15 GCS

Dedykowany system stacji naziemnej dla platformy DRON15. Repozytorium obejmuje
kokpit Android na aparaturę SIYI MK32, opcjonalną stację podglądu na Raspberry Pi 5,
interfejs przeglądarkowy oraz narzędzia diagnostyczne i integracyjne.

> **Status:** rozwój i integracja ze sprzętem. Przed użyciem w locie każdą zmianę
> dotyczącą MAVLink, parametrów kontrolera, misji, RTL, kamery lub sieci należy
> sprawdzić bez śmigieł, a następnie zgodnie z procedurą testu stanowiskowego.

## Elementy systemu

| Element | Katalog | Rola |
|---|---|---|
| Kokpit MK32 | `mk32app/app/` | Natywna aplikacja Android: telemetria i komendy MAVLink, lot, misje, mapy, checklista, RC, diagnostyka, obraz i sterowanie ZR30 |
| Serwer podglądu | `mk32app/serwer/server/` | Odbiór telemetrii z MK32, API, archiwum i obsługa widzów |
| PWA | `mk32app/serwer/web/` | Podgląd obrazu i telemetrii w przeglądarce lub na telefonie |
| Wdrożenie RPi | `mk32app/serwer/rpi/` | Instalacja i usługi systemd dla Raspberry Pi 5, MediaMTX oraz tryb kioskowy |
| Narzędzia integracyjne | `mk32app/narzedzia/` | Symulator telemetrii, router MAVLink, obsługa SIYI, mapy i retransmisja |
| Narzędzia FC | `tools/` | Odczyt, diagnostyka, analiza logów i kontrolowany zapis parametrów ArduPilota |
| Dokumentacja | `mk32app/dok/` | Architektura, interfejsy, mapy, misje, wideo, bezpieczeństwo dostępu i wdrożenie |

## Co robi kokpit MK32

Aplikacja `pl.dron15.cockpit` działa na Androidzie 9 lub nowszym i jest głównym
interfejsem operatora. Korzysta z trzech niezależnych torów:

1. MAVLink/UDP — telemetria, parametry i polecenia kontrolera lotu.
2. SIYI SDK/UDP — bezpośrednie sterowanie głowicą ZR30.
3. SIYI/RTSP — obraz z automatycznym przejściem na tor zapasowy.

Kokpit odbiera m.in. stan lotu, GPS/GNSS, EKF, baterię, RC, silniki, misję,
geofence i komunikaty kontrolera. Może wysyłać kontrolowane polecenia, m.in. RTL,
LAND, zmianę trybu, obsługę misji, test PreArm oraz odczyt i zapis wybranych
parametrów. Krytyczne akcje w interfejsie wymagają przytrzymania. Uzbrajanie
i rozbrajanie pozostaje na fizycznym sterowaniu operatora.

Aktywne ekrany: **LOT**, **MISJA**, **KAMERA**, **PRZED LOTEM**, **RC**
i **DIAGNOSTYKA**.

## Stacja podglądu na Raspberry Pi

Stacja RPi jest elementem opcjonalnym. Odbiera udostępnioną telemetrię i obraz,
udostępnia je widzom, prowadzi archiwum oraz może uruchamiać stronę w trybie kioskowym
na monitorach. Standardowa ścieżka serwera podglądu nie wysyła komend do kontrolera
lotu ani do głowicy. Zarządzanie usługami RPi nie jest sterowaniem dronem.

Androidowy MK32 pozostaje jedynym klientem łącza kontrolera, a telemetria dla
obserwatorów jest rozgłaszana jednokierunkowo. Eksperymentalny router MAVLink
z filtrem władzy jest osobnym narzędziem i nie stanowi domyślnej ścieżki serwera.

## Budowanie i testy

### Android

Wymagane są JDK 17, Android SDK 34 i Gradle 8.4.

```powershell
cd mk32app\app
C:\Gradle\gradle-8.4\bin\gradle.bat :cockpit:testDebugUnitTest
C:\Gradle\gradle-8.4\bin\gradle.bat :cockpit:assembleDebug
```

APK powstaje w `mk32app/app/cockpit/build/outputs/apk/debug/`.

### Serwer i PWA

Wymagane są Node.js 22, npm, MediaMTX oraz — zależnie od trybu — ffmpeg.

```powershell
cd mk32app\serwer
npm ci
cd web
npm ci
npm run lint
npm run build
```

Konfigurację lokalną należy utworzyć z
`mk32app/serwer/zrodla.example.json` jako `mk32app/serwer/zrodla.json`.
Plik roboczy nie jest wersjonowany.

Wdrożenie na Raspberry Pi opisuje
[`mk32app/dok/WDROZENIE_RPI.md`](mk32app/dok/WDROZENIE_RPI.md).

## Dane, których nie publikujemy

Do repozytorium nie trafiają:

- klucze prywatne, certyfikaty lokalne, tokeny i pliki dostępu;
- konfiguracja konkretnego stanowiska i kopie parametrów kontrolera;
- surowe logi lotów, zrzuty pamięci i archiwa nagrań;
- pobrane kafelki map oraz dane wysokościowe;
- APK, katalogi `build`, `dist`, `node_modules` i inne wyniki generowania;
- kopie instrukcji producentów i robocze archiwa ZIP.

Reguły są zapisane w `.gitignore`, ale przed każdym commitem należy również
sprawdzić listę przygotowanych plików.

## Dokumentacja

- [`mk32app/dok/ARCHITEKTURA.md`](mk32app/dok/ARCHITEKTURA.md) — budowa i przepływy danych,
- [`mk32app/dok/INTERFEJSY.md`](mk32app/dok/INTERFEJSY.md) — porty i protokoły,
- [`mk32app/dok/MISJE.md`](mk32app/dok/MISJE.md) — obsługa misji,
- [`mk32app/dok/MAPY.md`](mk32app/dok/MAPY.md) — mapy i teren,
- [`mk32app/dok/WIDEO.md`](mk32app/dok/WIDEO.md) — tor obrazu,
- [`mk32app/dok/WLADZA.md`](mk32app/dok/WLADZA.md) — model uprawnień do sterowania,
- [`mk32app/dok/WDROZENIE_RPI.md`](mk32app/dok/WDROZENIE_RPI.md) — instalacja stacji podglądu,
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — obowiązkowy sposób pracy i wykonywania commitów,
- [`CHANGELOG.md`](CHANGELOG.md) — historia zmian widocznych i operacyjnych.

## Licencja

Repozytorium nie zawiera obecnie publicznej licencji. Do czasu dodania pliku
`LICENSE` wszystkie prawa pozostają przy właścicielu projektu.
