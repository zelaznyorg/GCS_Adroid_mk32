# GCS Pulpit dla Raspberry Pi

Natywny, pełnoekranowy pulpit stacji naziemnej na Raspberry Pi. Zastępuje
standardowe ikony i pasek zadań jednym interfejsem obsługiwanym enkoderem,
uruchamia aplikacje terenowe, pokazuje stan stacji, zarządza siecią oraz obsługuje
nagrywanie i odtwarzanie obrazu.

> **Status:** rozwój i integracja sprzętowa. Projekt jest przeznaczony dla
> Raspberry Pi 5 z Wayland/labwc i wymaga sprawdzenia na konkretnym stanowisku
> przed ustawieniem go jako domyślnej sesji.

## Zakres

- katalog aplikacji oparty na plikach JSON,
- obsługa bez myszy i klawiatury przez enkoder,
- pasek pilota dla aplikacji, które nie znają pokrętła,
- ekran sieci oparty na NetworkManagerze,
- klawiatura ekranowa sterowana pokrętłem,
- nagrywanie źródeł RTSP i przegląd nagrań,
- osobna, odwracalna sesja labwc,
- instalator systemd i ograniczony pomocnik sieciowy `sudo`.

Projekt **nie wysyła komend do kontrolera lotu**. Może uruchamiać zewnętrzne
aplikacje, ale sam pulpit pozostaje warstwą obsługi stacji.

## Wymagania

Platforma odniesienia:

- Raspberry Pi OS / Debian 64-bit,
- Python 3.11 lub nowszy,
- GTK 4 + PyGObject,
- `gtk4-layer-shell`, Wayland i labwc,
- NetworkManager (`nmcli`), systemd,
- `wtype` i `wlrctl`,
- opcjonalnie `ffmpeg`, `ffprobe` i `mpv` dla nagrań.

## Instalacja

Instalator kopiuje kod, szablony aplikacji, usługę użytkownika oraz wąski
pomocnik sieciowy. Nie przełącza sam domyślnej sesji graficznej.

```bash
chmod +x pulpit/rpi/instaluj.sh
./pulpit/rpi/instaluj.sh
systemctl --user enable --now gcs-pulpit
```

Po sprawdzeniu działania i dostępu SSH:

```bash
sudo gcs-ui nasze
sudo systemctl restart lightdm
```

Powrót do standardowego pulpitu:

```bash
sudo gcs-ui malina
sudo systemctl restart lightdm
```

Szczegółową procedurę i uzasadnienia zawiera
[`dok/UI_PULPIT.md`](dok/UI_PULPIT.md).

## Konfiguracja aplikacji

Jeden plik JSON w `/etc/gcs/aplikacje.d/` oznacza jeden kafelek. Przykłady są w
[`pulpit/aplikacje.d`](pulpit/aplikacje.d).

Konfiguracji zawierającej token, prywatny adres usługi albo dane operatora nie
commitujemy. Lokalny wariant można umieścić w:

```text
~/.config/gcs/aplikacje.d/30-dron15.json
```

Plik użytkownika o tej samej nazwie nadpisuje szablon systemowy. Publiczny
szablon DRON15 otwiera `http://127.0.0.1:8095/` bez żetonu dostępu.

## Uruchomienie deweloperskie

Na Raspberry Pi z aktywną sesją Wayland:

```bash
cd pulpit
GCS_PELNY_EKRAN=0 python3 -m gcs_pulpit
```

Kontrole niewymagające GTK ani sprzętu:

```bash
python3 -m unittest discover -s tests -v
python3 narzedzia/kontrola_nazw.py pulpit/gcs_pulpit/*.py pulpit/most/*.py pulpit/rpi/*.py
python3 narzedzia/kontrola_nazw.py pulpit/rpi/gcs-siec
```

Te kontrole wykonuje również GitHub Actions.

## Architektura i bezpieczeństwo

- [`dok/ARCHITEKTURA.md`](dok/ARCHITEKTURA.md) — moduły, przepływ zdarzeń i pliki systemowe,
- [`dok/BEZPIECZENSTWO.md`](dok/BEZPIECZENSTWO.md) — instalacja, uprawnienia i droga powrotu,
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — dokładny sposób wykonywania kolejnych commitów,
- [`CHANGELOG.md`](CHANGELOG.md) — zmiany widoczne i operacyjne.

## Licencja

Brak publicznej licencji. Do czasu dodania pliku `LICENSE` wszystkie prawa
pozostają przy właścicielu projektu.
