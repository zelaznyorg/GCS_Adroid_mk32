# Srodowisko testowe Android dla aplikacji DRON 15.
#
# Jeden skrypt, kilka polecen. Emulator udaje ekran MK32 (7", 1920x1200, 320 dpi, Android 9),
# zeby uklad interfejsu wygladal tak, jak bedzie wygladal na aparaturze.
#
#   .\srodowisko.ps1 sprawdz        czego brakuje w SDK
#   .\srodowisko.ps1 pobierz        sciaga emulator i obraz systemu (curl, z wznawianiem)
#   .\srodowisko.ps1 zainstaluj     rozpakowuje pobrane paczki do SDK
#   .\srodowisko.ps1 utworz         zaklada wirtualne urzadzenie MK32
#   .\srodowisko.ps1 start          uruchamia emulator
#   .\srodowisko.ps1 zbuduj         buduje APK (debug)
#   .\srodowisko.ps1 wgraj          instaluje i uruchamia z telemetria z symulatora
#   .\srodowisko.ps1 logi           logcat tylko z naszej aplikacji
#   .\srodowisko.ps1 zrzut          zrzut ekranu do dok\zrzuty\
#   .\srodowisko.ps1 stop           zamyka emulator
#   .\srodowisko.ps1 wszystko       zbuduj + start + wgraj + zrzut
#
# UWAGA O SIECI: emulator widzi komputer gospodarza pod adresem 10.0.2.2, nie 127.0.0.1.
# Dlatego aplikacja startuje z "-e host 10.0.2.2" i tam znajduje symulator telemetrii.

param(
    [Parameter(Position = 0)]
    [ValidateSet('sprawdz', 'pobierz', 'zainstaluj', 'utworz', 'start', 'zbuduj', 'wgraj', 'logi', 'zrzut', 'stop', 'wszystko')]
    [string]$Polecenie = 'sprawdz',

    [string]$Host_ = '10.0.2.2',      # gdzie aplikacja ma szukac telemetrii
    [string]$Avd = 'MK32'
)

$ErrorActionPreference = 'Stop'

$SDK = 'C:\Android'
$GRADLE = 'C:\Gradle\gradle-8.4\bin\gradle.bat'
$ADB = "$SDK\platform-tools\adb.exe"
$EMULATOR = "$SDK\emulator\emulator.exe"
$AVDMANAGER = "$SDK\cmdline-tools\latest\bin\avdmanager.bat"
$SDKMANAGER = "$SDK\cmdline-tools\latest\bin\sdkmanager.bat"
$OBRAZ = 'system-images;android-28;default;x86_64'   # Android 9 — tak jak MK32

$Projekt = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # ...\mk32app
$App = Join-Path $Projekt 'app'
$Apk = Join-Path $App 'cockpit\build\outputs\apk\debug\cockpit-debug.apk'
$Pakiet = 'pl.dron15.cockpit'

function Info($t) { Write-Host $t -ForegroundColor Cyan }
function Ok($t) { Write-Host "  OK   $t" -ForegroundColor Green }
function Brak($t) { Write-Host "  BRAK $t" -ForegroundColor Yellow }

function Sprawdz {
    Info "Skladniki srodowiska"
    if (Test-Path $ADB) { Ok "adb" } else { Brak "platform-tools" }
    if (Test-Path $EMULATOR) { Ok "emulator" } else { Brak "emulator  -> $SDKMANAGER `"emulator`"" }
    if (Test-Path "$SDK\system-images\android-28") { Ok "obraz Android 9" }
    else { Brak "obraz systemu -> $SDKMANAGER `"$OBRAZ`"" }
    if (Test-Path $GRADLE) { Ok "gradle" } else { Brak "gradle" }

    if (Test-Path $EMULATOR) {
        $avdy = & $EMULATOR -list-avds 2>$null
        if ($avdy -contains $Avd) { Ok "urzadzenie wirtualne $Avd" }
        else { Brak "urzadzenie $Avd -> .\srodowisko.ps1 utworz" }
    } else { Brak "urzadzenie $Avd (najpierw emulator)" }

    Info "`nHipervisor (bez niego emulator ledwo zipie)"
    $hv = (Get-ComputerInfo -Property HyperVisorPresent).HyperVisorPresent
    if ($hv) { Ok "obecny" } else { Brak "brak — wlacz Hyper-V albo Windows Hypervisor Platform" }
}

# sdkmanager potrafi zawiesic pobieranie w polowie i stac tak godzinami — sprawdzone
# 2026-08-18. Dlatego paczki sciagamy curlem: widac postep, da sie wznowic, da sie
# sprawdzic sume kontrolna.
$Paczki = @(
    @{ nazwa = 'emulator'; plik = 'emulator-windows_x64-15917651.zip'
        url = 'https://dl.google.com/android/repository/emulator-windows_x64-15917651.zip'
        sha1 = '54fa750822ff462d57e04fc8e98e60f08df2bb61'; cel = $SDK
    },
    @{ nazwa = 'obraz Android 9'; plik = 'x86_64-28_r04.zip'
        url = 'https://dl.google.com/android/repository/sys-img/android/x86_64-28_r04.zip'
        sha1 = ''; cel = "$SDK\system-images\android-28\default"
    }
)

function Pobierz {
    $katalog = "$SDK\.pobrane"
    New-Item -ItemType Directory -Force -Path $katalog | Out-Null
    foreach ($p in $Paczki) {
        $cel = Join-Path $katalog $p.plik
        Info "Pobieram $($p.nazwa)"
        & curl.exe -# -C - -o $cel $p.url
        if ($LASTEXITCODE -ne 0) { throw "pobieranie $($p.nazwa) nieudane" }
        Ok "$($p.plik)"
    }
}

function Zainstaluj {
    $katalog = "$SDK\.pobrane"
    foreach ($p in $Paczki) {
        $zip = Join-Path $katalog $p.plik
        if (-not (Test-Path $zip)) { throw "Brak $zip — najpierw: .\srodowisko.ps1 pobierz" }
        if ($p.sha1) {
            $suma = (Get-FileHash $zip -Algorithm SHA1).Hash.ToLower()
            if ($suma -ne $p.sha1) { throw "$($p.plik): suma kontrolna sie nie zgadza (plik niepelny?)" }
            Ok "suma kontrolna $($p.plik)"
        }
        Info "Rozpakowuje $($p.nazwa) do $($p.cel)"
        New-Item -ItemType Directory -Force -Path $p.cel | Out-Null
        Expand-Archive -Path $zip -DestinationPath $p.cel -Force
    }
    Ok "skladniki na miejscu"
    Sprawdz
}

function Utworz {
    if (-not (Test-Path "$SDK\system-images\android-28")) {
        throw "Najpierw pobierz obraz: $SDKMANAGER `"$OBRAZ`""
    }
    Info "Zakladam urzadzenie wirtualne $Avd"
    "no" | & $AVDMANAGER create avd -n $Avd -k $OBRAZ --force --abi default/x86_64

    # Ekran jak w MK32 - ZMIERZONE NA APARATURZE 2026-08-25 przez adb:
    #   wm size    -> 800x1280 (panel pionowy, w poziomie 1280x800)
    #   wm density -> 320
    # Czyli aplikacja dostaje 640 x 400 dp, a przy widocznym pasku systemowym 592 x 400 dp.
    # Wczesniej stalo tu 1920x1200 (960x600 dp) - zalozenie z marketingu, nigdy niesprawdzone.
    $cfg = "$env:USERPROFILE\.android\avd\$Avd.avd\config.ini"
    $ustawienia = @{
        'hw.lcd.width'            = '1280'
        'hw.lcd.height'           = '800'
        'hw.lcd.density'          = '320'
        'hw.ramSize'              = '3072'
        'hw.gpu.enabled'          = 'yes'
        'hw.gpu.mode'             = 'auto'
        'hw.keyboard'             = 'yes'
        'hw.initialOrientation'   = 'landscape'
        'disk.dataPartition.size' = '4G'
        'showDeviceFrame'         = 'no'
        'skin.name'               = '1280x800'
        'skin.path'               = '_no_skin'
    }
    $tresc = Get-Content $cfg | Where-Object { $_ -notmatch '^(hw\.lcd\.|hw\.ramSize|hw\.gpu\.|hw\.keyboard|hw\.initialOrientation|disk\.dataPartition\.size|showDeviceFrame|skin\.)' }
    $tresc += $ustawienia.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
    $tresc | Set-Content $cfg -Encoding ascii
    Ok "urzadzenie $Avd gotowe (1280x800, 320 dpi, Android 9 - jak aparatura)"
}

function Start-Emulator {
    if (Czy-Dziala) { Ok "emulator juz dziala"; return }
    Info "Uruchamiam emulator $Avd"
    Start-Process -FilePath $EMULATOR -ArgumentList @(
        '-avd', $Avd, '-gpu', 'auto', '-no-snapshot-load',
        '-netdelay', 'none', '-netspeed', 'full'
    ) -WindowStyle Normal
    Info "Czekam na system (to trwa 1-3 minuty przy pierwszym starcie)..."
    & $ADB wait-for-device | Out-Null
    do {
        Start-Sleep -Seconds 3
        $gotowy = (& $ADB shell getprop sys.boot_completed 2>$null).Trim()
    } while ($gotowy -ne '1')
    Ok "system wstal"
}

function Czy-Dziala {
    if (-not (Test-Path $ADB)) { return $false }
    $lista = & $ADB devices
    return ($lista -match 'emulator-\d+\s+device').Count -gt 0
}

function Zbuduj {
    Info "Buduje APK (debug)"
    Push-Location $App
    try {
        & $GRADLE --no-daemon :cockpit:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "build nieudany" }
    } finally { Pop-Location }
    Ok "APK: $Apk"
}

function Wgraj {
    if (-not (Test-Path $Apk)) { throw "Brak APK — najpierw: .\srodowisko.ps1 zbuduj" }
    if (-not (Czy-Dziala)) { throw "Emulator nie dziala — najpierw: .\srodowisko.ps1 start" }
    Info "Instaluje"
    & $ADB install -r $Apk
    Info "Uruchamiam z telemetria spod $Host_"
    & $ADB shell am start -n "$Pakiet/.MainActivity" -e host $Host_ | Out-Null
    Ok "uruchomione"
    Write-Host ""
    Write-Host "Pamietaj o symulatorze na gospodarzu:" -ForegroundColor Yellow
    Write-Host "  python $Projekt\narzedzia\symulator_telemetrii.py --scenariusz lot"
}

function Logi {
    & $ADB logcat -c
    Info "logcat (Ctrl+C przerywa). Filtr: nasza aplikacja + libVLC + bledy"
    & $ADB logcat LaczeMavlink:V KlientSiyi:V OdtwarzaczVlc:V VLC:W AndroidRuntime:E "$Pakiet`:V" "*:E"
}

function Zrzut {
    $katalog = Join-Path $Projekt 'dok\zrzuty'
    New-Item -ItemType Directory -Force -Path $katalog | Out-Null
    $plik = Join-Path $katalog ("ekran_" + (Get-Date -Format 'yyyyMMdd_HHmmss') + ".png")
    # Uwaga: `adb exec-out ... > plik` w PowerShellu psuje plik binarny (potok idzie tekstem).
    # Dlatego zrzut robimy na urzadzeniu i sciagamy go przez adb pull.
    & $ADB shell screencap -p /sdcard/zrzut.png
    & $ADB pull /sdcard/zrzut.png $plik | Out-Null
    & $ADB shell rm /sdcard/zrzut.png
    Ok "zrzut: $plik"
}

function Stop-Emulator {
    if (Czy-Dziala) { & $ADB emu kill; Ok "emulator zatrzymany" } else { Info "emulator nie dziala" }
}

switch ($Polecenie) {
    'sprawdz' { Sprawdz }
    'pobierz' { Pobierz }
    'zainstaluj' { Zainstaluj }
    'utworz' { Utworz }
    'start' { Start-Emulator }
    'zbuduj' { Zbuduj }
    'wgraj' { Wgraj }
    'logi' { Logi }
    'zrzut' { Zrzut }
    'stop' { Stop-Emulator }
    'wszystko' { Zbuduj; Start-Emulator; Wgraj; Start-Sleep -Seconds 8; Zrzut }
}
