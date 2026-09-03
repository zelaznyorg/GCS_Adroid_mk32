#!/bin/sh
# Przegląd stacji na Raspberry Pi 5 — co działa, co się grzeje, co jest podłączone.
# Tylko odczyt: ten skrypt niczego nie zmienia i niczego nie restartuje.
#
#   sh rpi/sprawdz.sh            przegląd
#   sh rpi/sprawdz.sh --kiosk    dodatkowo 20-sekundowy pomiar obciążenia
#                                (do zadania 2.2 z TODO.md — uruchamiać, GDY OBRAZ IDZIE)
#
# Odpowiada na trzy pytania, które w terenie zadaje się najczęściej:
#   1. czy stacja żyje i czy nie dławi jej zasilanie,
#   2. czy dekoder HEVC jest tam, gdzie ma być (dok/GCS_RPI5.md §2),
#   3. czy MTU tunelu nie zabije obrazu przy telemetrii, która wygląda zdrowo
#      (zadanie 2.3, najbardziej mylący objaw w całym projekcie).

DANE=${DANE:-/var/lib/panorama}

tytul() { echo; echo "── $* ──────────────────────────────────────────" | cut -c1-70; }
jest()  { command -v "$1" >/dev/null 2>&1; }

tytul "MASZYNA"
[ -r /proc/device-tree/model ] && echo "model:    $(tr -d '\0' < /proc/device-tree/model)"
echo "system:   $(. /etc/os-release 2>/dev/null; echo "$PRETTY_NAME")"
echo "jądro:    $(uname -r) $(uname -m)"
# RPi OS na Pi 5 chodzi ze stronami 16 kB. Warto wiedzieć — część binarek
# zbudowanych pod 4 kB potrafi się o to potknąć.
jest getconf && echo "strony:   $(getconf PAGESIZE) B"
echo "pamięć:   $(free -h 2>/dev/null | awk '/^Mem:/{print $3" z "$2" zajęte"}')"
echo "czas pracy: $(uptime -p 2>/dev/null)"

tytul "ZASILANIE I TEMPERATURA"
if jest vcgencmd; then
  echo "temperatura: $(vcgencmd measure_temp 2>/dev/null | sed 's/temp=//')"
  T=$(vcgencmd get_throttled 2>/dev/null | sed 's/throttled=//')
  echo "throttled:   $T"
  case "$T" in
    0x0) echo "             → czysto" ;;
    *)   echo "             → NIEZEROWE. Bit 0 = niedomiar napięcia TERAZ, bit 16 = był wcześniej."
         echo "               Niedomiar zasilania objawia się losowymi zawieszeniami stacji"
         echo "               i myli, bo wygląda jak błąd oprogramowania (dok/GCS_RPI5.md §2)." ;;
  esac
else
  echo "brak vcgencmd — nie sprawdzę dławienia ani temperatury"
fi

tytul "DEKODER OBRAZU"
# RPi 5: VideoCore VII ma sprzętowy dekoder HEVC i NIE MA H.264 (dok/GCS_RPI5.md §2).
# Dlatego na monitorach stacji chcemy H.265, a nie odwrotnie.
if jest v4l2-ctl; then
  v4l2-ctl --list-devices 2>/dev/null | sed 's/^/  /'
  for D in /dev/video*; do
    [ -e "$D" ] || continue
    if v4l2-ctl -d "$D" --list-formats 2>/dev/null | grep -qi "HEVC"; then
      echo "  HEVC obsługiwany przez $D  ← tego szukamy"
    fi
  done
else
  echo "brak v4l2-ctl — doinstaluj:  sudo apt install v4l-utils"
  ls /dev/video* 2>/dev/null | sed 's/^/  /' || echo "  brak urządzeń /dev/video*"
fi
echo
echo "UWAGA: obecność dekodera w systemie NIE dowodzi, że Chromium z niego korzysta."
echo "       To rozstrzyga wyłącznie chrome://media-internals podczas odtwarzania"
echo "       (pole \"Decoder\": sprzętowy vs FFmpegVideoDecoder) — zadanie 2.2 z TODO.md."

tytul "OPROGRAMOWANIE"
jest node && echo "node:      $(node -v)" || echo "node:      BRAK"
KATALOG=$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)
if [ -f "$KATALOG/bin/mediamtx" ] && [ -x "$KATALOG/bin/mediamtx" ]; then
  echo "mediamtx:  $("$KATALOG/bin/mediamtx" --version 2>/dev/null || echo 'jest, wersji nie podał')"
else
  echo "mediamtx:  BRAK wykonywalnej binarki w $KATALOG/bin"
fi
if jest chromium-browser; then echo "chromium:  $(chromium-browser --version 2>/dev/null)"
elif jest chromium;        then echo "chromium:  $(chromium --version 2>/dev/null)"
else echo "chromium:  BRAK — kiosk nie ruszy"; fi

tytul "USŁUGI"
for U in panorama-mediamtx panorama-gcs; do
  printf "%-18s %s\n" "$U" "$(systemctl is-active "$U" 2>/dev/null) / $(systemctl is-enabled "$U" 2>/dev/null)"
done
printf "%-18s %s\n" "panorama-kiosk" "$(systemctl --user is-active panorama-kiosk 2>/dev/null || echo 'brak (jednostka użytkownika)')"

tytul "PORTY"
# 9997 MA nasłuchiwać tylko na 127.0.0.1 — to API kontrolne MediaMTX.
# Gdyby wisiało na 0.0.0.0, każdy w sieci mógłby przestawiać ścieżki obrazu.
if jest ss; then
  ss -lntup 2>/dev/null | grep -E ":(8095|8889|8189|9997)\b" | sed 's/^/  /' || echo "  nic nie nasłuchuje"
  ss -lnt 2>/dev/null | grep -q "0.0.0.0:9997" && \
    echo "  ⛔ 9997 wystawione na świat — API MediaMTX ma być TYLKO na 127.0.0.1"
else
  echo "brak ss — doinstaluj: sudo apt install iproute2"
fi

tytul "SIEĆ I MTU"
# Objaw mylący, zadanie 2.3: strona i telemetria działają, obraz się nie startuje.
# Prawie zawsze to MTU tunelu, nie serwer.
ip -brief address 2>/dev/null | sed 's/^/  /'
echo
for I in $(ls /sys/class/net 2>/dev/null); do
  [ "$I" = "lo" ] && continue
  printf "  %-8s MTU %s\n" "$I" "$(cat "/sys/class/net/$I/mtu" 2>/dev/null)"
done
if [ -d /sys/class/net/wg0 ]; then
  M=$(cat /sys/class/net/wg0/mtu)
  [ "$M" -gt 1420 ] && echo "  ⚠ wg0 MTU $M — pod LTE albo PPPoE bywa potrzebne 1280 (README, sekcja Porty)"
fi

tytul "ARCHIWUM"
if [ -d "$DANE/archiwum" ]; then
  echo "katalog: $DANE/archiwum"
  du -sh "$DANE/archiwum/tlog" "$DANE/archiwum/wideo" 2>/dev/null | sed 's/^/  /'
  echo "  nagrań telemetrii: $(find "$DANE/archiwum/tlog" -name '*.tlog' 2>/dev/null | wc -l)"
  echo "  nagrań obrazu:     $(find "$DANE/archiwum/wideo" -type f 2>/dev/null | wc -l)"
  df -h "$DANE" 2>/dev/null | tail -1 | awk '{print "  dysk: "$3" z "$2" zajęte, wolne "$4" ("$5")"}'
else
  echo "brak $DANE/archiwum — stacja jeszcze nie startowała albo DANE wskazuje gdzie indziej"
fi

if [ "$1" = "--kiosk" ]; then
  tytul "OBCIĄŻENIE — 20 s"
  echo "Mierzy sens tylko wtedy, gdy OBRAZ NAPRAWDĘ IDZIE na monitory."
  echo "Odniesienie: MediaMTX przepakowuje pakiety (remux), więc jego udział ma być"
  echo "bliski zeru NIEZALEŻNIE od kodeka. Procesor zjada dekodowanie w przeglądarce"
  echo "— i to jest liczba, którą chcemy poznać (zadanie 2.2)."
  echo
  I=0
  while [ "$I" -lt 20 ]; do
    OBC=$(awk '{print $1}' /proc/loadavg)
    TMP=$(vcgencmd measure_temp 2>/dev/null | sed 's/temp=//')
    CHR=$(ps -eo pcpu,comm 2>/dev/null | awk '/chromium/ {s+=$1} END {printf "%.0f", s}')
    MTX=$(ps -eo pcpu,comm 2>/dev/null | awk '/mediamtx/ {s+=$1} END {printf "%.0f", s}')
    printf "  obciążenie %-5s  temp %-8s  chromium %3s%%  mediamtx %3s%%\n" "$OBC" "$TMP" "${CHR:-0}" "${MTX:-0}"
    I=$((I + 1))
    sleep 1
  done
fi

echo
echo "Koniec przeglądu. Logi:  journalctl -u panorama-gcs -n 50"
