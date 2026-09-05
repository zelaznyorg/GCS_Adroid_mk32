#!/bin/sh
# Podgląd na ekranie stacji — Chromium OD RAZU NA PEŁNYM EKRANIE.
#
#   sh rpi/podglad.sh                  uruchom teraz
#   sh rpi/podglad.sh <adres>          uruchom pod wskazanym adresem
#   sh rpi/podglad.sh --skrot          połóż skrót na pulpicie i w menu
#
# Kafelek pulpitu GCS (`/etc/gcs/aplikacje.d/30-panorama.json`) woła ten sam skrypt
# z adresem jako argumentem — żeby flagi okna były w JEDNYM miejscu, a nie w dwóch
# rozjeżdżających się kopiach.
#
# ### ⛔ Dlaczego pełny ekran musi zrobić PRZEGLĄDARKA, a nie strona
#
# Strona nie może sama wejść w pełny ekran. `requestFullscreen()` wymaga
# **prawdziwego gestu użytkownika**, a zdarzenia z pokrętła przychodzą do nas
# strumieniem SSE — dla przeglądarki to zwykłe dane z sieci, nie dotknięcie
# człowieka. Wywołanie odbite jest po cichu, bez błędu w konsoli.
#
# Dlatego pełny ekran zakłada się flagą przy starcie okna. Klawisz EKRAN na
# stronie zostaje i działa wszędzie tam, gdzie jest mysz albo dotyk.
#
# ### Dlaczego --start-fullscreen, a nie --kiosk
#
# `--kiosk` blokuje wyjście z okna. **Przy stacji nie ma klawiatury**, więc
# z takiego okna nie dałoby się wyjść niczym — pokrętło steruje stroną, nie
# oknem przeglądarki. `--start-fullscreen` daje ten sam obraz na starcie,
# ale zostawia oknu zwykłe zarządzanie: gdy ktoś podepnie klawiaturę, F11
# i Alt+F4 działają.
#
# ### ⛔ Wybór platformy okien: WYKRYWANY, nie podpowiadany
#
# Sesja stacji to Wayland (labwc). Chromium bez wskazania platformy wybiera X11,
# nie znajduje `$DISPLAY` i **wychodzi, zanim powstanie okno** — bez żadnego
# objawu poza `Missing X server or $DISPLAY` w logu.
#
# `--ozone-platform-hint=auto` tego NIE załatwia w każdym przypadku: podpowiedź
# patrzy na `XDG_SESSION_TYPE`, a ta zmienna istnieje tylko w pełnej sesji
# graficznej. Uruchomienie po ssh albo z usługi ma `WAYLAND_DISPLAY`, ale nie ma
# `XDG_SESSION_TYPE` — i Chromium mimo podpowiedzi szło w X11 (zmierzone).
# Dlatego platformę wskazujemy wprost, gdy widać gniazdo Waylanda.
#
# ⚠ Na maszynie stacji obok nas pracuje inne oprogramowanie (`PI5setup full`),
#   więc to jest SKRÓT do uruchomienia ręcznego, a nie usługa zabierająca ekran
#   przy każdym starcie. Wersja usługowa na dwa monitory: `rpi/kiosk.sh`.
set -e

KATALOG=$(cd "$(dirname "$0")/.." && pwd)
ADRES=${ADRES:-http://localhost:8095}
# Kod zaproszenia. Pusty = strona pokaże ekran wejścia. Przy stacji nie ma
# klawiatury, więc kod musi być wpisany TUTAJ, raz — potem żeton siedzi w profilu.
KOD=${KOD:-}
PROFIL=${PROFIL:-$HOME/.local/share/panorama-podglad}

CHROMIUM=$(command -v chromium-browser || command -v chromium || true)
[ -n "$CHROMIUM" ] || { echo "BŁĄD: nie ma chromium." >&2; exit 1; }

CEL="$ADRES"
[ -n "$KOD" ] && CEL="$ADRES/#z=$KOD"
# Adres wolno podać wprost — tak robi kafelek pulpitu.
case "$1" in http://*|https://*) CEL="$1"; shift ;; esac

# ### Stanowisko prosi o pokrętło ADRESEM
#
# Strona włącza obsługę pokrętła po znaczniku `pokretlo=1`. Bez niego czekałaby,
# aż ktoś kliknie klawisz POKRĘTŁO — **myszą**, czyli tym, czego cała ta funkcja
# ma nie wymagać. Zmierzone na stacji: bez znacznika most żył i liczył zdarzenia,
# a strona nie trzymała pokrętła, więc nic w niej nie reagowało.
case "$CEL" in
  *pokretlo=1*) ;;
  *#*) CEL="$CEL&pokretlo=1" ;;
  *)   CEL="$CEL#pokretlo=1" ;;
esac

# ---- skrót na pulpicie -----------------------------------------------------

if [ "$1" = "--skrot" ]; then
  [ "$(id -u)" != 0 ] || { echo "BŁĄD: NIE przez sudo — skrót ma trafić do pulpitu człowieka." >&2; exit 1; }
  for GDZIE in "$HOME/Desktop" "$HOME/Pulpit" "$HOME/.local/share/applications"; do
    [ -d "$GDZIE" ] || continue
    PLIK="$GDZIE/panorama-podglad.desktop"
    # ⛔ Skrót MUSI nieść pełny adres z kodem. Wcześniej `Exec` był samym skryptem,
    # więc podwójne kliknięcie szło na `localhost` bez zaproszenia i bez znacznika
    # pokrętła — strona stawała na ekranie wejścia, z wyłączoną obsługą pokrętła.
    # ⛔ Skrót MUSI nieść pełny adres z kodem. Wcześniej `Exec` był samym skryptem,
    # więc podwójne kliknięcie szło na `localhost` bez zaproszenia i bez znacznika
    # pokrętła — strona stawała na ekranie wejścia, z wyłączoną obsługą pokrętła.
    #
    # ⚠ Piszemy wprost, bez `sed`: adres niesie `#` (separator) ORAZ `&`, które
    # w zamienniku `sed` znaczy „całe dopasowanie" — z `&pokretlo=1` robiło się
    # `@CEL@pokretlo=1`. Zmierzone, nie teoretyczne.
    cat > "$PLIK" <<WPIS
[Desktop Entry]
Type=Application
Version=1.0
Name=Panorama — podgląd
Comment=Obraz, telemetria i mapa ze stacji. Pełny ekran, obsługa pokrętłem.
Exec=$KATALOG/rpi/podglad.sh "$CEL"
Icon=video-display
Terminal=false
Categories=AudioVideo;Video;Network;
StartupNotify=true
WPIS
    chmod +x "$PLIK"
    # Bez tego pulpit pokazuje skrót jako "niezaufany" i nie da się go kliknąć.
    command -v gio >/dev/null && gio set "$PLIK" metadata::trusted true 2>/dev/null || true
    echo "skrót: $PLIK"
  done
  exit 0
fi

# ---- uruchomienie ----------------------------------------------------------

# ### ⛔ Dymek "przetłumaczyć stronę?" zasłania róg HUD-a
#
# Zmierzone na zrzucie z ekranu stacji: dymek tłumaczenia Chromium wchodzi na
# prawy górny róg, czyli dokładnie tam, gdzie stoi wskaźnik łącza i horyzont.
# **Flagi `--disable-features=Translate,TranslateUI`, `--lang` i `--accept-lang`
# tego NIE gaszą na tym wydaniu** (Chromium 149, sprawdzone — dymek wracał przy
# każdym świeżym profilu). Gasi to dopiero ustawienie w samym profilu, więc
# zakładamy je przed pierwszym uruchomieniem.
#
# Przy stacji nie da się tego dymka odklikać pokrętłem — pokrętło steruje stroną,
# nie ramką przeglądarki. Dlatego to nie kosmetyka.
if [ ! -e "$PROFIL/Default/Preferences" ]; then
  mkdir -p "$PROFIL/Default"
  cat > "$PROFIL/Default/Preferences" <<'USTAWIENIA'
{"translate":{"enabled":false},"translate_blocked_languages":["pl","en"],
 "intl":{"accept_languages":"pl-PL,pl"},
 "credentials_enable_service":false,"profile":{"password_manager_enabled":false}}
USTAWIENIA
fi

# Serwer bywa jeszcze niegotowy zaraz po włączeniu maszyny; strona błędu sama
# by się nie odświeżyła. 401 też znaczy "serwer żyje" — to poprawna odpowiedź
# na pytanie bez żetonu.
PROBA=0
while [ "$PROBA" -lt 30 ]; do
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$ADRES/api/zrodla" 2>/dev/null || echo 000)
  case "$STATUS" in 200|401) break ;; esac
  PROBA=$((PROBA + 1))
  sleep 1
done

PLATFORMA="--ozone-platform-hint=auto"
if [ -n "$WAYLAND_DISPLAY" ] && [ -S "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/$WAYLAND_DISPLAY" ]; then
  PLATFORMA="--ozone-platform=wayland"
fi

# Diagnostyka strony na stacji. Bez tego jedyną drogą do konsoli przeglądarki
# jest klawiatura, której tam nie ma:  GCS_DEVTOOLS=1 sh rpi/podglad.sh
DIAGNOSTYKA=""
[ -n "$GCS_DEVTOOLS" ] && DIAGNOSTYKA="--remote-debugging-port=${GCS_DEVTOOLS_PORT:-9222}"

# ### ⛔ Jedna instancja na profil — inaczej kafelek „się zamyka"
#
# Chromium z zajętym `--user-data-dir` NIE otwiera drugiej instancji: oddaje adres
# tej, która już działa, i KOŃCZY SIĘ w pół sekundy. Pulpit widzi koniec procesu,
# uznaje, że aplikacja się zamknęła, i wraca na wierzch — a okno Panoramy stoi
# pod nim, z każdym kliknięciem o jedno okno więcej. Zmierzone 2026-09-05: instancja
# uruchomiona ręcznie po ssh, potem cztery kliknięcia w kafelek, każde „Zakończyła
# się" po <1 s, 6 okien w `wlrctl toplevel list`. Dlatego stara instancja z tym
# profilem idzie precz, zanim wystartuje nowa — kafelek znaczy „otwórz na nowo".
if pkill -f -- "--user-data-dir=$PROFIL" 2>/dev/null; then
  sleep 1
fi

exec "$CHROMIUM" $PLATFORMA $DIAGNOSTYKA --start-fullscreen --password-store=basic --window-size=1920,1080 --window-position=0,0 --user-data-dir="$PROFIL" --noerrdialogs --disable-infobars --disable-session-crashed-bubble --no-first-run --no-default-browser-check --lang=pl --accept-lang=pl-PL,pl --disable-features=TranslateUI,Translate --autoplay-policy=no-user-gesture-required --enable-features=VaapiVideoDecodeLinuxGL,AcceleratedVideoDecodeLinuxGL --ignore-gpu-blocklist --enable-gpu-rasterization --disable-background-timer-throttling --disable-renderer-backgrounding --app="$CEL"
