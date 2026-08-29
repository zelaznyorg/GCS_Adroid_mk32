#!/bin/sh
# Instalacja pulpitu GCS na malinie. Uruchamiać z katalogu `pulpit`:
#
#   sh rpi/instaluj.sh
#
# Co robi:
#   1. kopiuje kod do /opt/gcs/pulpit           (nadpisywane przy każdym wgraniu)
#   2. zakłada /etc/gcs/aplikacje.d              (NIE nadpisuje istniejących wpisów)
#   3. instaluje jednostkę użytkownika gcs-pulpit
#
# Czego NIE robi: nie przełącza sesji graficznej. Podmiana pulpitu na własny to
# osobny, świadomy krok — dopóki go nie ma, standardowy pulpit działa jak dotąd.
set -e

ZRODLO=$(cd "$(dirname "$0")/.." && pwd)
CEL=${CEL:-/opt/gcs/pulpit}
APLIKACJE=${APLIKACJE:-/etc/gcs/aplikacje.d}
NAGRANIA=${NAGRANIA:-/var/lib/gcs/nagrania}

powiedz() { echo "==> $*"; }
bledem() { echo "BŁĄD: $*" >&2; exit 1; }

[ "$(id -u)" != 0 ] || bledem "NIE uruchamiaj przez sudo — jednostka użytkownika
potrzebuje twojej sesji. Skrypt sam poprosi o sudo tam, gdzie trzeba."

python3 -c 'import gi; gi.require_version("Gtk","4.0"); from gi.repository import Gtk' 2>/dev/null \
  || bledem "brak GTK4 dla Pythona — sudo apt install gir1.2-gtk-4.0"

# ---- 0. kontrola nazw PRZED instalacją ------------------------------------
# ⛔ Nie jest to formalność. 2026-08-29 literówka tej klasy (`PANEL` użyty, ale
# niezaimportowany) przeszła przez instalację, bo składnia była poprawna — i
# zabiła panel dopiero przy geście ratunkowym, czyli w najgorszym możliwym
# momencie. Sprawdzamy, zanim cokolwiek trafi na maszynę.
KONTROLA="$ZRODLO/../narzedzia/kontrola_nazw.py"
if [ -f "$KONTROLA" ]; then
  powiedz "kontrola nazw"
  python3 "$KONTROLA" "$ZRODLO"/gcs_pulpit/*.py "$ZRODLO"/most/*.py "$ZRODLO"/rpi/*.py \
    || bledem "kod ma nierozwiązane nazwy — instalacja wstrzymana"
  python3 "$KONTROLA" "$ZRODLO"/rpi/gcs-siec \
    || bledem "pomocnik sieciowy ma nierozwiązane nazwy — instalacja wstrzymana"
fi

# ---- 1. kod ---------------------------------------------------------------
powiedz "kod do $CEL"
sudo mkdir -p "$CEL"
sudo rm -rf "$CEL/gcs_pulpit"
sudo cp -r "$ZRODLO/gcs_pulpit" "$CEL/gcs_pulpit"
sudo find "$CEL" -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

# Dokument wskazany przez jednostkę systemd ma istnieć także po instalacji.
sudo mkdir -p "$(dirname "$CEL")/dok"
sudo install -m 0644 "$ZRODLO/../dok/UI_PULPIT.md" "$(dirname "$CEL")/dok/UI_PULPIT.md"

# ---- 2. katalog aplikacji -------------------------------------------------
powiedz "aplikacje w $APLIKACJE"
sudo mkdir -p "$APLIKACJE"
# Kafelki dodawane z pulpitu lądują u użytkownika — bez roota i bez sudo.
mkdir -p "$HOME/.config/gcs/aplikacje.d"

# Katalog na nagrania z przycisku. Właścicielem jest użytkownik pulpitu,
# żeby nagrywanie nie potrzebowało żadnych uprawnień.
powiedz "katalog nagrań $NAGRANIA"
sudo mkdir -p "$NAGRANIA"
sudo chown "$(id -un)":"$(id -gn)" "$NAGRANIA"
for plik in "$ZRODLO"/aplikacje.d/*.json; do
    [ -f "$plik" ] || continue
    nazwa=$(basename "$plik")
    if [ -f "$APLIKACJE/$nazwa" ]; then
        echo "    zostawiam $nazwa (już jest — twoje zmiany są bezpieczne)"
    else
        sudo install -m 0644 "$plik" "$APLIKACJE/$nazwa"
        echo "    dodane $nazwa"
    fi
done

# ---- 3. pomocnik sieciowy -------------------------------------------------
powiedz "pomocnik sieciowy /usr/local/sbin/gcs-siec"
sudo install -o root -g root -m 0755 "$ZRODLO/rpi/gcs-siec" /usr/local/sbin/gcs-siec

# ⛔ Uszkodzony plik w sudoers.d potrafi zablokować `sudo` na całej maszynie,
# dlatego wzorzec przechodzi `visudo -c` ZANIM trafi na miejsce.
TYMCZASOWY=$(mktemp)
sed "s|@UZYTKOWNIK@|$(id -un)|" "$ZRODLO/rpi/gcs-siec.sudoers" > "$TYMCZASOWY"
if sudo visudo -c -f "$TYMCZASOWY" >/dev/null 2>&1; then
    sudo install -o root -g root -m 0440 "$TYMCZASOWY" /etc/sudoers.d/gcs-siec
    echo "    prawo do zarządzania siecią nadane"
else
    echo "    UWAGA: wzorzec sudoers nie przeszedł kontroli — pomijam."
    echo "    Ekran SIEĆ będzie tylko pokazywał stan, bez włączania Wi-Fi."
fi
rm -f "$TYMCZASOWY"

# ---- 3a. otoczenie: pasek i pulpit maliny ---------------------------------
powiedz "skrypt otoczenia /usr/local/bin/gcs-otoczenie"
sudo install -m 0755 "$ZRODLO/rpi/gcs-otoczenie" /usr/local/bin/gcs-otoczenie

# ---- 3b. panel ma wstawać sam ---------------------------------------------
# Panel jest jedynym właścicielem GPIO pokrętła — kiedy ginie, pokrętło umiera
# całkowicie, a przy stanowisku nie ma myszy, żeby to naprawić. Fabryczne
# `Restart=on-failure` nie wystarcza: awaria 2026-08-29 zakończyła proces kodem
# 0, więc systemd uznał ją za poprawne zakończenie i usługi NIE wskrzesił.
if systemctl list-unit-files pi5-control-panel.service >/dev/null 2>&1; then
  powiedz "panel wstaje sam po awarii"
  sudo mkdir -p /etc/systemd/system/pi5-control-panel.service.d
  sudo install -m 0644 "$ZRODLO/rpi/pi5-control-panel.gcs-restart.conf" \
    /etc/systemd/system/pi5-control-panel.service.d/10-gcs-restart.conf
  sudo systemctl daemon-reload
fi

# ---- 3c. sesja graficzna GCS ----------------------------------------------
# Instalujemy ją, ale NIE przełączamy. Podmiana ekranu logowania to decyzja,
# nie skutek uboczny instalacji — robi ją osobno `sudo gcs-ui nasze`.
powiedz "sesja graficzna GCS"
sudo install -m 0755 "$ZRODLO/rpi/gcs-sesja" /usr/local/bin/gcs-sesja
sudo install -m 0755 "$ZRODLO/rpi/gcs-ui"    /usr/local/bin/gcs-ui
sudo install -m 0644 "$ZRODLO/rpi/gcs.desktop" /usr/share/wayland-sessions/gcs.desktop
sudo mkdir -p /etc/gcs/labwc
sudo install -m 0644 "$ZRODLO/rpi/labwc/autostart" /etc/gcs/labwc/autostart
# rc.xml i environment bierzemy z maliny, zamiast pisać własne: tam siedzą
# motyw, skróty klawiszowe i ustawienia wejścia, które mają zostać jak były.
for plik in rc.xml environment; do
  if [ ! -f "/etc/gcs/labwc/$plik" ] && [ -f "/etc/xdg/labwc/$plik" ]; then
    sudo cp "/etc/xdg/labwc/$plik" "/etc/gcs/labwc/$plik"
    echo "    $plik przeniesiony z /etc/xdg/labwc (bez zmian)"
  fi
done

# ---- 4. jednostka użytkownika --------------------------------------------
powiedz "jednostka gcs-pulpit"
mkdir -p "$HOME/.config/systemd/user"
install -m 0644 "$ZRODLO/rpi/gcs-pulpit.service" "$HOME/.config/systemd/user/gcs-pulpit.service"
systemctl --user daemon-reload

cat <<'KONIEC'

Gotowe. Dalej:

  systemctl --user start gcs-pulpit      # uruchom teraz
  systemctl --user enable gcs-pulpit     # i przy każdym starcie sesji
  journalctl --user -u gcs-pulpit -f     # co mówi

Do prac nad wyglądem wygodniej w oknie niż na pełnym ekranie:

  systemctl --user set-environment GCS_PELNY_EKRAN=0

Podmiana ekranu przy starcie maszyny (osobna, swiadoma decyzja):

  sudo gcs-ui nasze  && sudo systemctl restart lightdm   # wstaje pulpit GCS
  sudo gcs-ui malina && sudo systemctl restart lightdm   # wraca pulpit maliny
  sudo gcs-ui stan                                       # co jest ustawione

Druga komenda to droga powrotu przez SSH — dziala takze wtedy, gdy ekran
w ogole nie wstal, bo sshd nie zalezy od sesji graficznej.

Dodanie aplikacji: jeden plik JSON w /etc/gcs/aplikacje.d — kafelek pojawia się
od razu, bez restartu.
KONIEC
