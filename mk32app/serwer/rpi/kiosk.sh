#!/bin/sh
# Podgląd na monitorach HDMI stacji — Chromium w trybie kiosku.
# Zadanie 2.2 z TODO.md.
#
#   KOD=<kod> sh rpi/kiosk.sh                 uruchom teraz, na pierwszym planie
#   KOD=<kod> sh rpi/kiosk.sh --zainstaluj    wpnij jako usługę sesji graficznej (bez sudo!)
#   sh rpi/kiosk.sh --odinstaluj
#
# ⛔ KIOSK TEŻ POTRZEBUJE ZAPROSZENIA — inaczej pokaże ekran wejścia, a nie obraz.
#
# Strona wymaga żetonu, a obraz przez WHEP wymaga go osobno (MediaMTX pyta serwer
# o zgodę przed każdym odtworzeniem — dok/DOSTEP_I_UZYTKOWNICY.md §6). Świeży profil
# Chromium ma pustą pamięć, więc bez kodu na monitorach stacji stanie ekran
# „KOD ZAPROSZENIA" i nikt go nie wpisze, bo przy stacji nie ma klawiatury.
#
# Wydaj w panelu ADMIN zaproszenie **wielokrotne i bezterminowe** (imię np.
# „monitory stacji", rola widz) i podaj jego kod w KOD. Wielokrotne jest konieczne:
# każde okno ma własny profil, więc kod zostanie wymieniony tyle razy, ile monitorów.
#
# Kod wymienia się na żeton przy pierwszym uruchomieniu i zostaje w profilu, więc
# przy kolejnych startach nie jest już potrzebny. Odebranie monitorom obrazu:
# ODETNIJ przy „monitory stacji" w panelu administratora.
#
# ⚠ WYMAGA SESJI GRAFICZNEJ, więc jednostka jest UŻYTKOWNIKA (`systemctl --user`),
#   a nie systemową. Instalowanie tego spod sudo nie zadziała — kiosk trafiłby
#   do sesji roota, której na pulpicie nie ma.
#
# ⚠ STAN FLAG SPRZĘTOWEGO DEKODOWANIA: NIEZWERYFIKOWANY NA ŻYWYM SPRZĘCIE.
#   RPi 5 ma sprzętowy dekoder HEVC i NIE ma dekodera H.264 (dok/GCS_RPI5.md §2),
#   więc na monitorach chcemy H.265. Poniższe flagi to najlepszy znany zestaw,
#   ale to, czy Chromium na tym konkretnym wydaniu Raspberry Pi OS naprawdę zejdzie
#   na sprzęt, rozstrzyga wyłącznie pomiar: `sh rpi/sprawdz.sh --kiosk` i podgląd
#   chrome://media-internals podczas odtwarzania. Do czasu pomiaru traktować
#   jako HIPOTEZĘ (konwencja z ..\..\CLAUDE.md §8).
set -e

KATALOG=$(cd "$(dirname "$0")/.." && pwd)
ADRES=${ADRES:-http://localhost:8095}
PROFILE=${PROFILE:-$HOME/.local/share/dron15-kiosk}
# Kod zaproszenia dla monitorów. Pusty = kiosk stanie na ekranie wejścia.
KOD=${KOD:-}

# Które ekrany obsadzić. Format: "SZEROKOŚĆxWYSOKOŚĆ+X+Y" po spacji, jeden na monitor.
# Domyślnie dwa monitory 1080p obok siebie — układ z dok/GCS_RPI5.md §2.
# Jeden monitor:  EKRANY="1920x1080+0+0" sh rpi/kiosk.sh
EKRANY=${EKRANY:-"1920x1080+0+0 1920x1080+1920+0"}

CHROMIUM=$(command -v chromium-browser || command -v chromium || true)
[ -n "$CHROMIUM" ] || { echo "BŁĄD: nie ma chromium. sudo apt install chromium-browser" >&2; exit 1; }

# ---- instalacja jako usługa sesji ------------------------------------------

if [ "$1" = "--zainstaluj" ]; then
  [ "$(id -u)" != 0 ] || { echo "BŁĄD: NIE uruchamiaj tego przez sudo (patrz nagłówek)." >&2; exit 1; }
  [ -n "$KOD" ] || echo "UWAGA: bez KOD=<kod> kiosk pokaże ekran wejścia zamiast obrazu (patrz nagłówek)."
  mkdir -p "$HOME/.config/systemd/user"
  sed -e "s#@KATALOG@#$KATALOG#g" -e "s#@ADRES@#$ADRES#g" -e "s#@KOD@#$KOD#g" \
    "$KATALOG/rpi/dron15-kiosk.service" > "$HOME/.config/systemd/user/dron15-kiosk.service"
  systemctl --user daemon-reload
  systemctl --user enable --now dron15-kiosk
  # Bez lingera kiosk wstaje dopiero po zalogowaniu się człowieka. Stacja ma
  # pokazywać obraz od włączenia prądu, więc włączamy — wymaga jednorazowo sudo.
  echo "Aby kiosk wstawał bez logowania:  sudo loginctl enable-linger $(id -un)"
  systemctl --user --no-pager --lines=0 status dron15-kiosk || true
  exit 0
fi

if [ "$1" = "--odinstaluj" ]; then
  systemctl --user disable --now dron15-kiosk 2>/dev/null || true
  rm -f "$HOME/.config/systemd/user/dron15-kiosk.service"
  systemctl --user daemon-reload
  echo "kiosk usunięty"
  exit 0
fi

# ---- uruchomienie ----------------------------------------------------------

# Czekamy na serwer. Przy starcie maszyny kiosk bywa gotowy przed nim i pokazałby
# wtedy stronę błędu, która sama się nie odświeży.
PROBA=0
while [ "$PROBA" -lt 60 ]; do
  if curl -sf -o /dev/null "$ADRES/api/zrodla" 2>/dev/null; then break; fi
  # 401 (brak zaproszenia) też znaczy, że serwer żyje — to jest poprawna odpowiedź.
  # Zmienna nazywa się STATUS, a nie KOD: KOD niesie kod zaproszenia dla monitorów
  # i nadpisanie go tutaj zabrałoby kioskowi obraz.
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$ADRES/api/zrodla" 2>/dev/null || echo 000)
  [ "$STATUS" = "401" ] && break
  PROBA=$((PROBA + 1))
  sleep 2
done
[ "$PROBA" -lt 60 ] || echo "UWAGA: serwer pod $ADRES nie odpowiedział przez 2 minuty — uruchamiam mimo to."

FLAGI="--kiosk --noerrdialogs --disable-infobars --disable-session-crashed-bubble
--no-first-run --disable-features=TranslateUI --autoplay-policy=no-user-gesture-required
--enable-features=VaapiVideoDecodeLinuxGL,AcceleratedVideoDecodeLinuxGL
--ignore-gpu-blocklist --enable-gpu-rasterization
--disable-background-timer-throttling --disable-renderer-backgrounding"

# Kod doklejamy do adresu tylko wtedy, gdy jest. Strona wymienia go na żeton,
# czyści z paska adresu i zapamiętuje w profilu — przy kolejnych startach kiosk
# wchodzi już bez niego. Zostawienie kodu w adresie nic nie psuje: wielokrotne
# zaproszenie znosi powtórne użycie, a jednorazowe i tak by tu nie wystarczyło
# (każdy monitor ma własny profil, więc kod idzie w ruch tyle razy, ile okien).
CEL="$ADRES"
[ -n "$KOD" ] && CEL="$ADRES/#z=$KOD"

I=0
for GEO in $EKRANY; do
  SZER=$(echo "$GEO" | sed 's/x.*//')
  WYS=$(echo "$GEO"  | sed 's/.*x\([0-9]*\)+.*/\1/')
  X=$(echo "$GEO"    | sed 's/.*+\([0-9]*\)+.*/\1/')
  Y=$(echo "$GEO"    | sed 's/.*+//')
  # Osobny profil na okno — bez tego drugie uruchomienie Chromium trafia do
  # pierwszej instancji i otwiera KARTĘ zamiast okna na drugim monitorze.
  "$CHROMIUM" $FLAGI \
    --user-data-dir="$PROFILE/$I" \
    --window-position="$X,$Y" \
    --window-size="$SZER,$WYS" \
    "$CEL" &
  I=$((I + 1))
done

wait
