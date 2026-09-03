# Wgranie serwera podglądu z Windows na Raspberry Pi 5.
#
#   .\rpi\wgraj.ps1 -Malina dron15.local
#   .\rpi\wgraj.ps1 -Malina 192.168.144.30 -Uzytkownik tomas -Instaluj
#
# Uwaga na nazwę parametru: -Malina, nie -Host. $Host to w PowerShellu zmienna
# tylko-do-odczytu (obiekt konsoli) i parametr o tej nazwie wywraca skrypt.
#
# Wymaga OpenSSH z Windows (ssh, scp, tar — wszystkie są w Windows 10/11 domyślnie)
# oraz klucza wgranego na malinę (ssh-copy-id albo ręcznie w ~/.ssh/authorized_keys),
# inaczej skrypt trzy razy zapyta o hasło.
#
# Co wysyła: kod, konfigurację i binarkę MediaMTX arm64.
# Czego NIE wysyła: node_modules (budują się na miejscu — mają natywne zależności
# pod arm64), archiwum, logów ani dostep.json. Dane stacji leżą w /var/lib/dron15
# i mają PRZEŻYĆ aktualizację kodu — dlatego wgrywanie ich zabija sens tego podziału.

[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][Alias("Host")][string]$Malina,
  [string]$Uzytkownik = "pi",
  [string]$Katalog = "/opt/dron15",
  [string]$Mediamtx = "C:\Soft\nas-arm\mediamtx_arm64",
  [switch]$Instaluj,
  [switch]$Restart
)

$ErrorActionPreference = "Stop"
$Zrodlo = Split-Path -Parent $PSScriptRoot
$Cel = "$Uzytkownik@$Malina"

function Krok($tekst) { Write-Host "==> $tekst" -ForegroundColor Cyan }

foreach ($n in @("ssh", "scp", "tar")) {
  if (-not (Get-Command $n -ErrorAction SilentlyContinue)) {
    throw "Brak polecenia '$n'. Włącz klienta OpenSSH w Ustawieniach Windows (Opcjonalne funkcje)."
  }
}

Krok "sprawdzam połączenie z $Cel"
$echo = ssh -o BatchMode=yes -o ConnectTimeout=8 $Cel "uname -m; id -un"
if ($LASTEXITCODE -ne 0) { throw "Nie mogę się połączyć z $Cel bez hasła. Wgraj klucz publiczny na malinę." }
Write-Host "    $($echo -join ' / ')"

# ---- paczka ----------------------------------------------------------------

$Paczka = Join-Path $env:TEMP "dron15-serwer.tar.gz"
Krok "pakuję $Zrodlo"
# --exclude musi poprzedzać ścieżkę, inaczej bsdtar go zignoruje.
tar --exclude="node_modules" --exclude="web/node_modules" --exclude="archiwum" `
    --exclude="logi" --exclude="dostep.json" --exclude=".git" `
    -czf $Paczka -C $Zrodlo .
if ($LASTEXITCODE -ne 0) { throw "Pakowanie nie powiodło się." }
$mb = [math]::Round((Get-Item $Paczka).Length / 1MB, 1)
Write-Host "    $mb MB"

Krok "wysyłam"
ssh $Cel "sudo mkdir -p '$Katalog' && sudo chown $Uzytkownik '$Katalog'"
scp -q $Paczka "${Cel}:/tmp/dron15-serwer.tar.gz"
if ($LASTEXITCODE -ne 0) { throw "Wysyłka nie powiodła się." }
# --warning=no-timestamp: malina nie ma zegara na baterii i po zimnym starcie, zanim
# NTP zsynchronizuje, chodzi godziny do tyłu. tar ostrzega wtedy o plikach „z przyszłości”
# na stderr — a PowerShell 5.1 pod $ErrorActionPreference=Stop każe za to całym
# wgrywaniem, choć rozpakowanie się udało (2026-09-03: przerwane przed restartem usług).
ssh $Cel "tar --warning=no-timestamp -xzf /tmp/dron15-serwer.tar.gz -C '$Katalog' && rm /tmp/dron15-serwer.tar.gz"
Remove-Item $Paczka -Force

# ---- binarka MediaMTX ------------------------------------------------------
#
# 62 MB, więc wysyłamy ją tylko wtedy, gdy na malinie jej nie ma. Aktualizacja
# kodu nie musi za każdym razem przepychać przez sieć tego samego pliku.

# Binarka idzie do bin/, bo obok stoi katalog `mediamtx/` z generowaną konfiguracją
# i plik o tej samej nazwie nie może tam istnieć. Test na `-f`, nie `-x`: `-x` na
# katalogu zwraca prawdę i przepuściłby brak binarki dalej.
$maBinarke = ssh $Cel "test -f '$Katalog/bin/mediamtx' && echo tak || echo nie"
if ($maBinarke.Trim() -eq "nie") {
  if (-not (Test-Path $Mediamtx)) {
    Write-Warning "Nie ma $Mediamtx — wgraj binarkę MediaMTX arm64 ręcznie jako $Katalog/bin/mediamtx"
  } else {
    Krok "wysyłam binarkę MediaMTX arm64 (jednorazowo)"
    ssh $Cel "mkdir -p '$Katalog/bin'"
    scp -q $Mediamtx "${Cel}:$Katalog/bin/mediamtx"
    ssh $Cel "chmod +x '$Katalog/bin/mediamtx'"
  }
} else {
  Write-Host "    binarka MediaMTX już jest — pomijam"
}

ssh $Cel "chmod +x '$Katalog/rpi/'*.sh '$Katalog/start.sh' '$Katalog/stop.sh' 2>/dev/null || true"

# ---- instalacja / restart --------------------------------------------------

if ($Instaluj) {
  Krok "uruchamiam instalację na malinie"
  ssh -t $Cel "sudo sh '$Katalog/rpi/instaluj.sh'"
} elseif ($Restart) {
  Krok "przebudowuję stronę i restartuję usługi"
  ssh $Cel "cd '$Katalog' && npm install --omit=dev && cd web && npm install && npm run build"
  ssh -t $Cel "sudo systemctl restart dron15-mediamtx dron15-gcs"
  ssh $Cel "systemctl --no-pager --lines=0 status dron15-gcs"
} else {
  Write-Host ""
  Write-Host "Pliki na miejscu. Dalej, na malinie:" -ForegroundColor Yellow
  Write-Host "    sudo sh $Katalog/rpi/instaluj.sh      (pierwszy raz)"
  Write-Host "    lub .\rpi\wgraj.ps1 -Malina $Malina -Restart   (kolejne wgrania)"
}
