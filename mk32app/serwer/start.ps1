# Uruchomienie serwera podglądu DRON15 na Windows — do prób na biurku.
# Wersja docelowa (RPi 5) startuje przez start.sh.
#
#   .\start.ps1
#   .\start.ps1 -Port 8095
#
# MediaMTX bierzemy z C:\Soft\mediamtx\mediamtx.exe (projekt NRK), żeby nie
# trzymać drugiej kopii binarki. Ścieżkę można nadpisać parametrem.

param(
  [int]$Port = 8095,
  [string]$MediaMtx = "C:\Soft\mediamtx\mediamtx.exe"
)

$ErrorActionPreference = "Stop"
$Katalog = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Katalog
New-Item -ItemType Directory -Force -Path "logi" | Out-Null

# Konfiguracja MediaMTX powstaje ze zrodla.json — zawsze przed startem.
node scripts/gen-config.mjs
if (-not $?) { throw "Nie udało się wygenerować konfiguracji." }

if (Test-Path $MediaMtx) {
  Start-Process -FilePath $MediaMtx -ArgumentList "mediamtx\mediamtx.yml" `
    -RedirectStandardOutput "logi\mediamtx.log" -RedirectStandardError "logi\mediamtx.err.log" `
    -WindowStyle Hidden
  Write-Output "MediaMTX wystartował ($MediaMtx)"
} else {
  Write-Warning "Nie znaleziono $MediaMtx — obraz nie będzie działał, telemetria owszem."
}

$env:PORT = $Port
Write-Output "Serwer: http://localhost:$Port"
node server/index.mjs
