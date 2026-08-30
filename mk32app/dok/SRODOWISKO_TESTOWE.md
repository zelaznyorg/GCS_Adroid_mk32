# Środowisko testowe Android

Po co: **żeby sprawdzać zmiany bez drona i bez aparatury.** Wgrywanie APK na MK32 przy
każdej poprawce jest wolne, a testowanie banerów na latającej maszynie — głupie.

Dotyczy tej aplikacji i każdej następnej budowanej w tym projekcie.

---

## 1. Trzy poziomy sprawdzania

| Poziom | Co weryfikuje | Ile trwa | Czego nie sprawdzi |
|---|---|---|---|
| **testy jednostkowe** (JVM) | protokoły, dekodowanie telemetrii, reguły banerów | sekundy | niczego z interfejsu |
| **emulator** (Android 9, 1920×1200) | układ ekranu, zachowanie w czasie, telemetria z symulatora | minuty | obrazu z kamery, realnego łącza radiowego |
| **MK32 przez ADB** | wszystko, z prawdziwym obrazem i łączem | minuty + kabel | zachowania w powietrzu |

Kolejność jest celowa: co da się sprawdzić niżej, tam się sprawdza.
Na aparaturę idzie tylko to, co przeszło poprzednie dwa poziomy.

---

## 2. Składniki

| Element | Wersja / ścieżka | Uwaga |
|---|---|---|
| Android SDK | `C:\Android` | platform-tools, build-tools 34, platforms android-34 |
| obraz systemu | `system-images;android-28;default;x86_64` | **API 28 = Android 9, tak jak MK32**. Wariant `default`, nie `google_apis` — usług Google nie używamy, a obraz jest o połowę mniejszy (538 MB zamiast 1051) |
| emulator | pakiet `emulator` z SDK | wymaga hipervisora (WHPX/Hyper-V) |
| Gradle | `C:\Gradle\gradle-8.4` | |
| JDK | 17 | |

Instalacja brakujących pakietów:
```powershell
C:\Android\cmdline-tools\latest\bin\sdkmanager.bat "emulator" "system-images;android-28;default;x86_64"
```

---

## 3. Jak się tego używa

Wszystko przez jeden skrypt: [`../narzedzia/android/srodowisko.ps1`](../narzedzia/android/srodowisko.ps1)

```powershell
cd mk32app\narzedzia\android
.\srodowisko.ps1 sprawdz      # czego brakuje
.\srodowisko.ps1 utworz       # zakłada urządzenie wirtualne MK32
.\srodowisko.ps1 wszystko     # zbuduj + start + wgraj + zrzut ekranu
.\srodowisko.ps1 logi         # logcat tylko z naszej aplikacji
```

Równolegle, w drugim oknie — udawana telemetria:
```powershell
python mk32app\narzedzia\symulator_telemetrii.py --scenariusz brak_kursu
```

### Urządzenie wirtualne odwzorowuje ekran MK32

**1280 × 800, 320 dpi**, orientacja pozioma, Android 9 — zmierzone na aparaturze
2026-08-25 przez ADB, nie wzięte z materiałów producenta.

```
wm size     ->  Physical size: 800x1280      (panel pionowy, w poziomie 1280x800)
wm density  ->  Physical density: 320
dumpsys display -> app 1184 x 800, real 1280 x 800, rotation 1
```

Co z tego wynika dla układu:

| | piksele | **dp przy 320 dpi** |
|---|---|---|
| panel | 1280 × 800 | **640 × 400** |
| obszar aplikacji z paskiem systemowym | 1184 × 800 | **592 × 400** |
| aplikacja w trybie pełnoekranowym | 1280 × 800 | 640 × 400 |

> ### ⛔ KOREKTA 2026-08-25 — emulator był ustawiony na 1920 × 1200
>
> Do 2026-08-25 urządzenie wirtualne miało **1920 × 1200 przy 320 dpi**, czyli **960 × 600 dp**.
> Wartość pochodziła z materiałów producenta („7-inch high definition", „dual full HD video")
> i była w tym dokumencie oznaczona jako *do potwierdzenia* — pomiar jej nie potwierdził.
>
> **Rzeczywisty ekran daje 2,25 raza mniejszą powierzchnię w dp.** Wszystkie zrzuty
> z 19 i 24 sierpnia (`dok/zrzuty/m3_*.png`, `nowy_*.png`, `wariantD_*.png`) mają
> 1920 × 1200 px — pokazują układ, którego na aparaturze nie będzie.
>
> „Full HD" w materiałach SIYI dotyczy **strumienia wideo**, nie panelu. Panel to typowe
> 7-calowe 1280 × 800. Przy tej przekątnej rzeczywiste zagęszczenie wynosi ok. 215 dpi,
> a system melduje 320 — czyli interfejs jest **powiększany**, co dodatkowo zabiera miejsce.
>
> Wniosek metodyczny: parametr ekranu bierze się z `adb shell wm size`, nie z karty katalogowej.

---

## 4. Dwie pułapki, które trzeba znać

### Sieć: emulator widzi gospodarza pod `10.0.2.2`

`127.0.0.1` wewnątrz emulatora to sam emulator. Symulator telemetrii działa na komputerze,
więc aplikację uruchamia się tak:

```powershell
adb shell am start -n pl.dron15.cockpit/.MainActivity -e host 10.0.2.2
```

Skrypt robi to sam. Na prawdziwym MK32 adresu się nie podaje — domyślny to `192.168.144.12`.

### Architektura: emulator jest x86_64, MK32 jest ARM

libVLC wnosi biblioteki natywne osobno dla każdej architektury. Wydanie na aparaturę
ma tylko ARM (inaczej APK rośnie do ~190 MB), ale wtedy **w emulatorze nie ładują się
biblioteki i obraz nie rusza**.

Rozwiązane w `app/cockpit/build.gradle.kts`:

```kotlin
debug   -> arm64-v8a, armeabi-v7a, x86_64     // żeby działało w emulatorze
release -> arm64-v8a, armeabi-v7a             // żeby APK na MK32 był mniejszy
```

---

## 5. Czego emulator nie sprawdzi

**Obrazu z kamery.** ZR30 leży w sieci pokładowej, do której emulator nie ma dostępu.
Aplikacja jest na to przygotowana — brak obrazu nie zabiera telemetrii (dok/ARCHITEKTURA.md),
i to akurat **da się w emulatorze sprawdzić**: baner „BRAK OBRAZU Z KAMERY" ma się pojawić.

Jeśli trzeba przetestować sam odtwarzacz, można podstawić strumień z komputera:

```powershell
# wymaga ffmpeg (obecnie NIE zainstalowany) oraz MediaMTX z narzedzia/relay/
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=25 -c:v libx264 -f rtsp rtsp://127.0.0.1:8554/test
.\srodowisko.ps1 wgraj -Host_ 10.0.2.2      # a w aplikacji: -e rtsp rtsp://10.0.2.2:8554/test
```

**Łącza radiowego, opóźnień, zachowania przy słabym zasięgu.** To zostaje na MK32.

**Zachowania w powietrzu.** Oczywiste, ale warto zapisać: zielone testy nie są zgodą na lot.

---

## 6. Praca z prawdziwym MK32

Aparatura podpięta kablem Type-C w trybie przesyłu plików wystawia ADB:

```powershell
C:\Android\platform-tools\adb.exe devices
C:\Android\platform-tools\adb.exe install -r cockpit-debug.apk
C:\Android\platform-tools\adb.exe logcat LaczeMavlink:V "*:E"
```

Gdyby port Type-C był zajęty przez tryb aktualizacji, zostaje ADB po sieci:
```powershell
adb tcpip 5555
adb connect 192.168.144.20:5555
```

**Uwaga:** instrukcja MK32 (rozdz. 7.2) ostrzega przed instalowaniem zbędnych aplikacji
na aparaturze. Środowisko testowe ma sprawiać, że na MK32 ląduje tylko to, co gotowe.

---

## 7. Debugowanie

| Objaw | Gdzie szukać |
|---|---|
| aplikacja wstaje i gaśnie | `adb logcat AndroidRuntime:E` — wyjątek ze stosem |
| pusty ekran, brak danych | `adb logcat LaczeMavlink:V` — czy leci heartbeat i czy coś wraca |
| obraz nie rusza | `adb logcat VLC:W` — najczęściej brak biblioteki dla tej architektury |
| błędne wartości na ekranie | najpierw test jednostkowy z ramką wzorcową, nie emulator |

Punkty przerwania: projekt otwiera się w Android Studio (`mk32app/app`), bo to zwykły
projekt Gradle. Emulator założony skryptem jest widoczny jako urządzenie.

---

## 8. Stan

| Element | Stan |
|---|---|
| testy jednostkowe (**45**) | ✅ przechodzą, 0 błędów — doszły `RcTest` (aparatura, mapa, potwierdzenia komend) |
| skrypt środowiska | ✅ działa: `sprawdz`, `utworz`, `start`, `wgraj`, `zrzut` |
| symulator telemetrii | ✅ 6 scenariuszy, odpowiadanie na pytania o parametry, **`RC_CHANNELS`, `BATTERY_STATUS`, `COMMAND_ACK` i lot po okręgu** (żeby mapa i panel RC miały co pokazywać) |
| APK debug z x86_64 | ✅ 148 MB, trzy architektury |
| pakiet `emulator` 37.1.11 | ✅ zainstalowany |
| obraz Android 9 (API 28, x86_64) | ✅ zainstalowany |
| urządzenie wirtualne MK32 | ✅ 1920×1200, 320 dpi, poziomo |
| **aplikacja w emulatorze** | ✅ **działa — sześć ekranów sprawdzonych po przebudowie interfejsu 2026-08-19** |

Zrzuty ekranu z pierwszego przebiegu: `dok\zrzuty\`.

### Co wykrył pierwszy przebieg

Emulator zrobił dokładnie to, po co powstał — w pierwszych minutach wyszły cztery usterki
układu, niewidoczne dla testów jednostkowych:

| Usterka | Przyczyna |
|---|---|
| pas stanu ucinał ostatnie pole | podwójne wcięcie na pasek zakładek — 92 dp liczone dwa razy |
| baner zasłaniał prawą kolumnę | baner leżał na wierzchu zamiast w układzie |
| kafelek komunikatów zgnieciony do zera | pięć przycisków po 132 dp zjadało szerokość |
| ostatnia wartość w kolumnach ucięta | kroje za duże na dostępną wysokość |

Piąta usterka wyszła na ekranie DIAGNOSTYKA: identyczne komunikaty z FC zapychały listę.
Teraz powtórzenia zwijają się w licznik `(×N)`.

### Co wykrył drugi przebieg — po przebudowie interfejsu 2026-08-19

| Usterka | Przyczyna |
|---|---|
| rysunek mapy wychodził poza swój kafelek na cały ekran | Compose **nie przycina** rysowania do granic widoku; trzeba `Modifier.clipToBounds()` |
| ślad trasy uciekał poza kadr | zasięg mapy liczony tylko z bieżącego dystansu, bez uwzględnienia przebytej trasy |
| pola pasu stanu urywały się („ROZBROJ…", „GOTO…") | wagi dobrane na oko; przy 960 dp szerokości każde pole ma ok. 90 dp i nie mieści dłuższego słowa |
| podpisy grup przycisków ucięte dolną krawędzią | podpis pod przyciskiem, a dok akcji stoi na samym dole ekranu — podpis przeniesiony nad grupę |

### Co wykrył trzeci przebieg — mapa z kafelków 2026-08-19

| Usterka | Przyczyna |
|---|---|
| kafelki z OSM to obrazki „Access blocked" | serwer OpenStreetMap odsyła zastępczy obrazek **z kodem 200**, więc błędu nie widać aż do ekranu; `kafelki.py` sprawdza teraz rozmiar i treść, a domyślnym źródłem są zdjęcia Esri |
| mapa rysowała samą siatkę mimo pełnej karty | prosiła o poziom `z18`, a pobrane były 14–17; magazyn zna teraz dostępne poziomy i rozciąga kafelek z najbliższego |
| katalog `/sdcard` niewidoczny dla aplikacji | `targetSdk 33` wymaga `READ_EXTERNAL_STORAGE` w czasie działania nawet na Androidzie 9; w emulatorze szybciej `adb shell pm grant …` |

**Pułapka do zapamiętania:** `adb install -r` na działającej aplikacji **ubija proces**,
a `adb shell input tap` potrafi czekać kilka sekund na odpowiedź (emulator renderuje
programowo). Zrzuty robić dopiero po ponownym `am start` i z odstępem 8–10 s po każdym
dotknięciu, inaczej łapie się poprzedni ekran albo pulpit systemu.

### Trzy pułapki instalacji SDK — zapisane, żeby nie tracić na nie czasu drugi raz

**1. `sdkmanager` zawiesza pobieranie.** Stanął na 13 % i tkwił tak 25 minut, choć
`dl.google.com` odpowiadał normalnie. To samo spotkało zwykłego `curl` bez zabezpieczeń.
Lekarstwo: `--speed-time 20 --speed-limit 20000` (przerwij, gdy transfer padnie)
plus `-C -` i pętla ponawiająca. Emulator zszedł po **sześciu** wznowieniach.

**2. Ręcznie rozpakowany pakiet jest niewidoczny dla `avdmanager`.** Obraz systemu
ma `package.xml` w archiwum i rejestruje się sam, ale emulator ma tylko `source.properties`
i `avdmanager` odpowiada „emulator package must be installed". Lekarstwo: skopiować
`package.xml` z innego pakietu generycznego (`platform-tools`) i podmienić `path`,
`revision` oraz `display-name`.

**3. Obraz `default` zamiast `google_apis`.** 538 MB zamiast 1051 MB, a usług Google
i tak nie używamy.

### Uwaga o PowerShellu 5.1

Skrypty `.ps1` z polskimi znakami **muszą** być zapisane jako UTF-8 **z BOM**. Bez BOM
Windows PowerShell 5.1 czyta plik jako ANSI i myślnik w tekście rozwala składnię —
zdarzyło się przy pisaniu tego skryptu.

## ⛔ Emulator na tym laptopie widzi **prawdziwą maszynę**

Ustalone 2026-08-28, po kilkunastu minutach analizowania cudzych danych w przekonaniu,
że to symulator.

Emulator routuje ruch przez host. Gdy laptop jest podłączony do sieci pokładowej —
a przy pracy z MK32 jest — **domyślny adres `192.168.144.12` z emulatora działa**.
Aplikacja uruchomiona bez `-e host` łączy się wtedy z żywym dronem, nie z symulatorem
na `10.0.2.2`.

Objaw, który mnie zmylił: dane wyglądały sensownie, tylko „nie takie jak w scenariuszu" —
28 satelitów zamiast 18, 25,0 V zamiast 24,1 i pozycja o 200 km dalej. Łatwo to wziąć
za błąd symulatora.

**Rozpoznanie jest teraz w logu.** Od 2026-08-28 `LaczeMavlink.start` wypisuje:

```
[telemetria] nasluch 10.0.2.2:19857
```

Przed każdą analizą zrzutu sprawdzić tę linię:

```bash
adb -s emulator-5554 logcat -d | grep "nasluch"
```

⚠ Drugi ślad w logu, który o tym mówi: `[wideo] tor SIYI: brak obrazu z 192.168.144.25`
— skoro emulator w ogóle próbuje sięgnąć do sieci pokładowej, to ją widzi.

⚠ **Konsekwencja dla audytu S6** (brak filtrowania nadawcy MAVLink): dopóki kokpit
przyjmuje ramki od kogokolwiek, taki zbieg adresów jest tym groźniejszy.
