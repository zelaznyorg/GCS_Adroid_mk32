#!/bin/sh
# Instalacja serwera podglądu Panorama na Raspberry Pi 5 (Raspberry Pi OS 64-bit).
# Uruchamiać NA MALINIE, z katalogu projektu:  sudo sh rpi/instaluj.sh
#
# Co robi:
#   1. sprawdza, czy to naprawdę arm64 i czy jest node
#   2. instaluje zależności i buduje stronę
#   3. zakłada katalog danych (/var/lib/panorama) i przenosi tam zrodla.json
#   4. instaluje jednostki systemd i włącza dwie z nich
#   5. nadaje panelowi STACJA wąskie prawo do restartu usług i czytania dziennika
#
# Czego NIE robi: nie stawia WireGuarda (to router, nie stacja — dok/SERWER_PODGLADU.md §6)
# i nie włącza kiosku sam (kiosk potrzebuje sesji graficznej — patrz §4 niżej).
#
# Zamyka zadanie 4a.4 z TODO.md: `start.sh --pilnuj` był rozwiązaniem tymczasowym.
set -e

KATALOG=$(cd "$(dirname "$0")/.." && pwd)
DANE=${DANE:-/var/lib/panorama}
# Usługi mają chodzić na koncie użytkownika, a nie roota: nic tu nie potrzebuje
# uprawnień administratora, a porty są wysokie (8095, 8889, 8189, 9997).
UZYTKOWNIK=${UZYTKOWNIK:-${SUDO_USER:-$(id -un)}}

powiedz() { echo "==> $*"; }
bledem() { echo "BŁĄD: $*" >&2; exit 1; }

# ---- 1. sprawdzenia wstępne ------------------------------------------------

[ "$(id -u)" = 0 ] || bledem "uruchom przez sudo — instalacja dotyka /etc/systemd i $DANE"

ARCH=$(uname -m)
[ "$ARCH" = "aarch64" ] || echo "UWAGA: architektura $ARCH, a binarka MediaMTX jest arm64."

# Kontrola musi sprawdzać, czy to PLIK. `[ -x katalog ]` zwraca prawdę, a obok leży
# katalog `mediamtx/` z generowaną konfiguracją — stąd binarka siedzi w bin/.
if [ ! -f "$KATALOG/bin/mediamtx" ] || [ ! -x "$KATALOG/bin/mediamtx" ]; then
  bledem "brak wykonywalnego $KATALOG/bin/mediamtx
      Skopiuj binarkę arm64 (C:\\Soft\\nas-arm\\mediamtx_arm64 z projektu NRK) i nadaj:
      mkdir -p bin && chmod +x bin/mediamtx"
fi

NODE=$(command -v node || true)
[ -n "$NODE" ] || bledem "nie ma node. Zainstaluj Node.js 18+ (nodejs z apt na Bookworm wystarczy)."
WERSJA=$(node -p "process.versions.node.split('.')[0]")
[ "$WERSJA" -ge 18 ] || bledem "Node.js $WERSJA jest za stary — potrzebny 18+ (statfs w archiwum)."
powiedz "node $(node -v) w $NODE"

id "$UZYTKOWNIK" >/dev/null 2>&1 || bledem "nie ma użytkownika $UZYTKOWNIK"
powiedz "usługi na koncie: $UZYTKOWNIK"

# ---- 2. zależności i strona ------------------------------------------------

powiedz "instaluję zależności serwera"
su "$UZYTKOWNIK" -c "cd '$KATALOG' && npm install --omit=dev"

powiedz "buduję stronę"
su "$UZYTKOWNIK" -c "cd '$KATALOG/web' && npm install && npm run build"

# ---- 3. katalog danych -----------------------------------------------------
#
# Dane trzymamy POZA katalogiem projektu, żeby aktualizacja kodu (nadpisanie
# katalogu przez rpi/wgraj.ps1) nie kasowała zaproszeń, ustawień ani nagrań.

# ---- 3a. przenosiny ze starej nazwy (DRON 15 -> Panorama, 2026-09-03) ---------
#
# Stacja nazywała się „DRON 15 — podgląd" i tak nazywały się jej usługi, katalogi
# i kafelek. Nazwa maszyny nie pasowała do stacji, która pokazuje wiele źródeł.
# Ten krok jest IDEMPOTENTNY: robi coś tylko wtedy, gdy stara nazwa jeszcze istnieje,
# a nowa nie — więc świeża instalacja i kolejne wgrania przechodzą przez niego pusto.
STARE_DANE=/var/lib/dron15
if [ -d "$STARE_DANE" ] && [ ! -d "$DANE" ]; then
  powiedz "przenoszę dane stacji: $STARE_DANE -> $DANE"
  # Zaproszenia, hasło nadawania i źródła to kilka KB — kopia zapasowa obok, na wszelki
  # wypadek. Archiwum (setki MB) jedzie samym `mv`, bez kopii: to ta sama partycja.
  tar -czf "/var/backups/panorama-dane-przed-przenosinami-$(date +%Y%m%d_%H%M%S).tgz"       -C "$STARE_DANE" --exclude=archiwum --exclude=logi . 2>/dev/null || true
  mv "$STARE_DANE" "$DANE"
fi
for STARA in dron15-gcs dron15-mediamtx; do
  if [ -f "/etc/systemd/system/$STARA.service" ]; then
    powiedz "wyłączam starą jednostkę $STARA"
    systemctl disable --now "$STARA" 2>/dev/null || true
    rm -f "/etc/systemd/system/$STARA.service"
  fi
done
[ -f /etc/sudoers.d/dron15-panel ] && { powiedz "usuwam stary /etc/sudoers.d/dron15-panel"; rm -f /etc/sudoers.d/dron15-panel; }
# Kafelek pulpitu GCS: stary wpis zastępujemy nowym (ten sam numer 30 = to samo miejsce).
# ⛔ Kafelek niesie ADRES Z KODEM ZAPROSZENIA — i to musi zostać adres tej stacji,
# nie ten z repozytorium. 2026-09-03 wzorzec z repo miał kod widza „monitory stacji";
# po przenosinach okno Panoramy z pulpitu weszło jako widz i panel ADMIN „zniknął".
# Dlatego nowy kafelek dostaje polecenie uruchomienia ze STAREGO, a z repo tylko
# nazwę, opis, wymagania i ikonę. Wzorzec w repo ma zamiast kodu napis KOD-ZAPROSZENIA-ADMINA.
if [ -f /etc/gcs/aplikacje.d/30-dron15.json ]; then
  powiedz "kafelek pulpitu: 30-dron15.json -> 30-panorama.json (adres i kod zaproszenia ze starego)"
  python3 - "$KATALOG/rpi/gcs-pulpit-30-panorama.json" /etc/gcs/aplikacje.d/30-dron15.json > /tmp/30-panorama.json <<'PY'
import json, sys
nowy = json.load(open(sys.argv[1])); stary = json.load(open(sys.argv[2]))
adres = next((u for u in stary.get("uruchom", []) if u.startswith("http")), None)
if adres:
    nowy["uruchom"] = [adres if u.startswith("http") else u for u in nowy["uruchom"]]
print(json.dumps(nowy, ensure_ascii=False, indent=2))
PY
  install -m 0644 /tmp/30-panorama.json /etc/gcs/aplikacje.d/30-panorama.json
  rm -f /tmp/30-panorama.json /etc/gcs/aplikacje.d/30-dron15.json
fi
if grep -q "KOD-ZAPROSZENIA-ADMINA" /etc/gcs/aplikacje.d/30-panorama.json 2>/dev/null; then
  echo "UWAGA: kafelek 30-panorama.json ma napis KOD-ZAPROSZENIA-ADMINA zamiast kodu —"
  echo "       wpisz kod zaproszenia admina (panel ADMIN → ZAPROSZENIA) w /etc/gcs/aplikacje.d/30-panorama.json"
fi
systemctl daemon-reload

powiedz "katalog danych: $DANE"
mkdir -p "$DANE/archiwum/tlog" "$DANE/archiwum/wideo" "$DANE/logi"
if [ ! -f "$DANE/zrodla.json" ]; then
  cp "$KATALOG/zrodla.json" "$DANE/zrodla.json"
  powiedz "skopiowano zrodla.json do $DANE — od teraz edytuj TAMTEN"
fi
chown -R "$UZYTKOWNIK": "$DANE"

# ---- 4. jednostki systemd --------------------------------------------------

powiedz "instaluję jednostki systemd"
for j in panorama-mediamtx panorama-gcs; do
  sed -e "s#@KATALOG@#$KATALOG#g" \
      -e "s#@DANE@#$DANE#g" \
      -e "s#@UZYTKOWNIK@#$UZYTKOWNIK#g" \
      -e "s#@UID@#$(id -u "$UZYTKOWNIK")#g" \
      -e "s#@NODE@#$NODE#g" \
      "$KATALOG/rpi/$j.service" > "/etc/systemd/system/$j.service"
done

systemctl daemon-reload
systemctl enable panorama-mediamtx panorama-gcs

# ---- 5. uprawnienia panelu STACJA -------------------------------------------
#
# Panel w przeglądarce pokazuje stan usług i pozwala je zrestartować
# (web/src/Stacja.jsx). Potrzebuje do tego dwóch rzeczy, obu wąskich:
#
#   czytanie dziennika  →  grupa `adm` (bez podnoszenia uprawnień)
#   restart usług       →  trzy wpisy w sudoers, każdy z nazwą na sztywno
#
# Bez tego panel działa dalej — pokaże stan, a przy restarcie powie, czego brakuje.

powiedz "dopisuję $UZYTKOWNIK do grup adm i systemd-journal (czytanie dziennika)"
for G in adm systemd-journal; do
  getent group "$G" >/dev/null 2>&1 && usermod -aG "$G" "$UZYTKOWNIK" || true
done

powiedz "zakładam /etc/sudoers.d/panorama-panel"
TYMCZASOWY=$(mktemp)
sed "s#@UZYTKOWNIK@#$UZYTKOWNIK#g" "$KATALOG/rpi/panorama-panel.sudoers" > "$TYMCZASOWY"
# Uszkodzony plik w sudoers.d potrafi zablokować sudo na całej maszynie,
# więc sprawdzamy go PRZED założeniem i przy błędzie po prostu odpuszczamy.
if visudo -cf "$TYMCZASOWY" >/dev/null 2>&1; then
  install -m 0440 -o root -g root "$TYMCZASOWY" /etc/sudoers.d/panorama-panel
  powiedz "restart usług z panelu: włączony"
else
  echo "UWAGA: wzorzec sudoers nie przeszedł kontroli — pomijam."
  echo "       Panel STACJA pokaże stan, ale przycisk RESTART nie zadziała."
fi
rm -f "$TYMCZASOWY"

# ---- 6. start ---------------------------------------------------------------

systemctl restart panorama-mediamtx
systemctl restart panorama-gcs

sleep 2
systemctl --no-pager --lines=0 status panorama-mediamtx panorama-gcs || true

echo
powiedz "gotowe"
echo "    strona:      http://$(hostname -I 2>/dev/null | awk '{print $1}'):8095"
echo "    kod admina:  journalctl -u panorama-gcs | grep -A2 'PIERWSZE WEJŚCIE'"
echo "    logi:        journalctl -u panorama-gcs -f    oraz  $DANE/logi/serwer.log"
echo "    sprawdzenie: sh rpi/sprawdz.sh"
echo
echo "    PANEL STACJA w przeglądarce: przycisk STACJA na dole strony (rola admin)."
echo "    Pokazuje usługi, zasilanie, sieć i dziennik — zamiast wchodzenia po ssh."
echo "    Uwaga: przynależność do grupy adm działa od NASTĘPNEGO startu usługi,"
echo "    więc jeśli dziennik jest pusty, wykonaj:  sudo systemctl restart panorama-gcs"
echo
echo "    KIOSK NA MONITORACH instaluje się osobno, na koncie użytkownika:"
echo "        sh rpi/kiosk.sh --zainstaluj"
echo "    (potrzebuje sesji graficznej, więc nie da się tego zrobić spod sudo)"
