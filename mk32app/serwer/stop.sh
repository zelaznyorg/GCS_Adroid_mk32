#!/bin/sh
# Zatrzymuje serwer podglądu DRON15.
#
# Przy trybie --pilnuj w logi/serwer.pid siedzi PID DOZORCY, nie samego serwera.
# Zabicie samego dozorcy zostawiłoby działającego node'a, więc sprzątamy też po nim.
KATALOG=$(cd "$(dirname "$0")" && pwd)
cd "$KATALOG"

for nazwa in serwer mediamtx; do
  PLIK="logi/$nazwa.pid"
  if [ -f "$PLIK" ]; then
    PID=$(cat "$PLIK")
    if kill "$PID" 2>/dev/null; then
      echo "zatrzymano $nazwa (pid $PID)"
    else
      echo "$nazwa (pid $PID) już nie działał"
    fi
    rm -f "$PLIK"
  else
    echo "brak $PLIK — $nazwa prawdopodobnie nie działa"
  fi
done

# Serwer uruchomiony przez dozorcę ma własny PID, którego nigdzie nie zapisaliśmy.
if command -v pkill >/dev/null 2>&1; then
  if pkill -f "server/index.mjs" 2>/dev/null; then
    echo "zatrzymano serwer uruchomiony przez dozorcę"
  fi
fi
