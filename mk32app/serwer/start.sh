#!/bin/sh
# Uruchomienie serwera podglądu DRON15 na Raspberry Pi 5 (Raspberry Pi OS 64-bit).
#
# Wzorowane na DEPLOY-NAS.md z projektu NRK — ten sam układ sprawdził się na ARM.
# Dwa procesy: MediaMTX (obraz) i Node (strona, API, telemetria). Żaden nie transkoduje.
#
#   sh start.sh              — start
#   sh start.sh --pilnuj     — start z podnoszeniem serwera po awarii
#   sh stop.sh               — zatrzymanie
#   tail -f logi/serwer.log
#
# Logi (dok/LOGI_I_BLEDY.md):
#   logi/serwer.log     rejestr techniczny — poziomy, obszary, stosy wywołań, rotowany
#   logi/konsola.log    surowe wyjście procesu — łapie też to, co pada przed rejestrem
#   logi/mediamtx.log   wyjście MediaMTX
set -e

KATALOG=$(cd "$(dirname "$0")" && pwd)
cd "$KATALOG"
mkdir -p logi

PILNUJ=nie
[ "$1" = "--pilnuj" ] && PILNUJ=tak

# --- MediaMTX ------------------------------------------------------------------
# Binarka arm64 leży w C:\Soft\nas-arm\mediamtx_arm64 (projekt NRK) — skopiuj ją tutaj
# jako ./bin/mediamtx i nadaj prawo wykonywania: chmod +x bin/mediamtx
#
# ⚠ BINARKA SIEDZI W bin/, NIE W KATALOGU GŁÓWNYM. Powód jest prozaiczny i kosztował
# jedno nieudane wdrożenie: katalog `mediamtx/` trzyma GENEROWANY mediamtx.yml, więc
# plik o tej samej nazwie obok niego istnieć nie może. Gorzej — `[ -x ./mediamtx ]`
# na katalogu zwraca PRAWDĘ (katalogi są przeszukiwalne), więc kontrola przechodziła,
# a dopiero start wywracał się przy próbie uruchomienia katalogu.
if [ ! -f ./bin/mediamtx ] || [ ! -x ./bin/mediamtx ]; then
  echo "BŁĄD: brak wykonywalnego ./bin/mediamtx"
  echo "      mkdir -p bin, skopiuj binarkę arm64 jako bin/mediamtx, chmod +x bin/mediamtx"
  exit 1
fi

# --- Node ----------------------------------------------------------------------
NODE_BIN=${NODE_BIN:-$(command -v node || true)}
if [ -z "$NODE_BIN" ]; then
  echo "BŁĄD: nie znaleziono node. Zainstaluj Node.js 18+ albo podaj NODE_BIN=..."
  exit 1
fi
echo "Używam node: $NODE_BIN"

# Generujemy mediamtx.yml ze zrodla.json PRZED startem MediaMTX.
"$NODE_BIN" scripts/gen-config.mjs

./bin/mediamtx mediamtx/mediamtx.yml >> logi/mediamtx.log 2>&1 &
echo $! > logi/mediamtx.pid

# Po nieprzechwyconym wyjątku serwer schodzi z pola celowo — proces jest wtedy
# w stanie nieokreślonym i dalsza praca oznaczałaby serwer, który UDAJE, że działa
# (dok/LOGI_I_BLEDY.md §2). Podnoszenie należy więc do warstwy wyżej: tutaj albo
# do systemd. Wyjście 0 znaczy "zatrzymany świadomie" i pętli nie wznawia.
if [ "$PILNUJ" = tak ]; then
  (
    PROBA=0
    while true; do
      PORT=${PORT:-8095} "$NODE_BIN" server/index.mjs >> logi/konsola.log 2>&1
      KOD=$?
      [ "$KOD" -eq 0 ] && break
      PROBA=$((PROBA + 1))
      echo "$(date -Is) serwer padł (kod $KOD), podnoszę — próba $PROBA" >> logi/konsola.log
      # Odstęp rośnie do minuty: przy usterce trwałej nie zapychamy karty logami.
      if [ "$PROBA" -lt 6 ]; then
        sleep $((PROBA * 5))
      else
        sleep 60
      fi
    done
  ) &
  echo $! > logi/serwer.pid
  echo "Serwer pod nadzorem (pid dozorcy $(cat logi/serwer.pid))"
else
  PORT=${PORT:-8095} "$NODE_BIN" server/index.mjs >> logi/konsola.log 2>&1 &
  echo $! > logi/serwer.pid
fi

echo "MediaMTX pid $(cat logi/mediamtx.pid), serwer pid $(cat logi/serwer.pid)"
echo "Strona: http://$(hostname -I 2>/dev/null | awk '{print $1}'):${PORT:-8095}"
echo "Logi:   logi/serwer.log (rejestr), logi/konsola.log, logi/mediamtx.log"
