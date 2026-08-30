# DRON15 Cockpit — aplikacja Android na MK32

Natywny kokpit dla naziemnej jednostki SIYI MK32 z Androidem 9. Aplikacja jest
głównym interfejsem operatora i może pracować bez serwera podglądu RPi oraz bez
dostępu do internetu, jeżeli mapy znajdują się w pamięci urządzenia.

## Funkcje

- odbiór i dekodowanie telemetrii MAVLink 2;
- polecenia RTL, LAND, zmiana trybu i obsługa misji;
- odczyt parametrów, kontrolowany zapis wybranych parametrów i test PreArm;
- planowanie, wysyłanie i pobieranie misji;
- mapy online/offline, teren, profil trasy i kontrola prześwitu;
- obraz SIYI/RTSP z automatyczną ścieżką zapasową;
- bezpośrednie sterowanie głowicą ZR30 przez SIYI SDK;
- checklista przedlotowa, RC, ostrzeżenia oraz diagnostyka trzech łączy;
- jednokierunkowe udostępnianie telemetrii obserwatorom.

Aplikacja nie wysyła ARM/DISARM ani ręcznych komend drążków. Krytyczne akcje
ekranowe wymagają przytrzymania, a wynik polecenia jest wiązany z odpowiedzią ACK
kontrolera.

## Wymagania

- JDK 17;
- Android SDK 34 i Build Tools 34.0.0;
- Gradle 8.4;
- Android 9 / API 28 lub nowszy.

`local.properties` jest plikiem lokalnym i nie może trafić do repozytorium.

## Budowanie

```powershell
cd mk32app\app
C:\Gradle\gradle-8.4\bin\gradle.bat :cockpit:assembleDebug
```

APK powstaje w `cockpit/build/outputs/apk/debug/cockpit-debug.apk`.

## Testy

```powershell
C:\Gradle\gradle-8.4\bin\gradle.bat :cockpit:testDebugUnitTest
```

Aktualny zestaw zawiera 152 testy JVM obejmujące m.in. protokoły MAVLink i SIYI,
checklistę, mapy, pobieranie danych mapowych, RC, taśmę kursu, obliczanie zapasu
i ocenę zdrowia systemu.

## Instalacja na MK32

```powershell
C:\Android\platform-tools\adb.exe install -r cockpit\build\outputs\apk\debug\cockpit-debug.apk
```

Alternatywnie APK można przenieść na kartę lub pamięć USB i zainstalować z poziomu
Androida. Przed instalacją na stanowisku należy wykonać procedurę z
[`../dok/PIERWSZY_TEST_MK32.md`](../dok/PIERWSZY_TEST_MK32.md).

## Test bez drona

```powershell
python ..\narzedzia\symulator_telemetrii.py --scenariusz brak_kursu
C:\Android\platform-tools\adb.exe shell am start -n pl.dron15.cockpit/.MainActivity -e host ADRES_KOMPUTERA
```

Dostępne scenariusze symulują m.in. normalny lot, brak kursu, spadek liczby
satelitów, niewłaściwą ramę, niskie napięcie i utratę telemetrii.

## Układ kodu

```text
cockpit/src/main/java/pl/dron15/cockpit/
├── MainActivity.kt          spięcie łączy, cyklu życia i stanu interfejsu
├── net/mavlink/             ramkowanie, dekodowanie i komendy MAVLink
├── net/siyi/                sterowanie głowicą ZR30
├── domain/                  stan maszyny, reguły i ostrzeżenia
├── video/                   odbiór obrazu SIYI/RTSP
└── ui/                      ekrany Jetpack Compose
```

Szczegóły systemowe znajdują się w [`../dok/ARCHITEKTURA.md`](../dok/ARCHITEKTURA.md)
i [`../dok/INTERFEJSY.md`](../dok/INTERFEJSY.md).
