param([string]$Router = '192.168.88.1', [string]$Uzytkownik = 'admin')
$ErrorActionPreference = 'Stop'
# RouterOS po SSH traktuje nowe linie jako osobne polecenia terminala.
# Caly blok z lokalnymi zmiennymi musi dotrzec jako jedno polecenie.
$wiersze = Get-Content (Join-Path $PSScriptRoot 'mikrotik-panorama-apk.rsc')
$reguly = ($wiersze | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') } |
    ForEach-Object { $_.Trim() }) -join ' '
$wynik = & ssh -o BatchMode=yes -o ConnectTimeout=8 "${Uzytkownik}@${Router}" $reguly
$kod = $LASTEXITCODE
$wynik | Write-Output
# RouterOS moze zwrocic kod 0 rowniez po bledzie skladni. Wymagamy znacznika.
if ($kod -ne 0 -or -not ($wynik -match '^PANORAMA_APK_RULES_OK\s*$')) {
    throw 'RouterOS nie potwierdzil wykonania skryptu. Sprawdz reguly przed ponowieniem.'
}
