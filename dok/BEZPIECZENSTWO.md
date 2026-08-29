# Bezpieczeństwo instalacji i prób

## Granica projektu

Pulpit zarządza stacją Raspberry Pi i uruchamia aplikacje. Sam nie wysyła komend
do kontrolera lotu. Dodanie takiej ścieżki wymaga osobnej decyzji architektonicznej,
analizy ryzyka i testów właściwych dla systemu sterowania.

## Przed przełączeniem sesji

1. Sprawdź dostęp SSH z drugiego urządzenia.
2. Uruchom pulpit w oknie: `GCS_PELNY_EKRAN=0 python3 -m gcs_pulpit`.
3. Sprawdź obrót, klik i długie przytrzymanie pokrętła.
4. Sprawdź polecenie `sudo gcs-ui malina`.
5. Dopiero potem ustaw sesję GCS jako domyślną.

Droga powrotu:

```bash
sudo gcs-ui malina
sudo systemctl restart lightdm
```

## Uprawnienia

- nie dodawaj ogólnego `NOPASSWD` dla `nmcli`, `systemctl`, Pythona ani powłoki,
- każdy uprzywilejowany pomocnik musi walidować operację i argumenty,
- hasła nie mogą trafiać do polecenia `sudo`, logów ani plików repozytorium,
- przed instalacją pliku sudoers wykonaj `visudo -c -f`,
- konfigurację konkretnej stacji trzymaj w `/etc/gcs` albo katalogu użytkownika.

Znane ograniczenie wersji 0.1.0: `nmcli device wifi connect` przyjmuje hasło jako
argument, więc może być ono chwilowo widoczne w lokalnej liście procesów. Zmiana
tego mechanizmu wymaga testu na docelowym NetworkManagerze; nie zastępuj go
powłoką ani plikiem tymczasowym bez jednoznacznej kontroli uprawnień i usuwania.

## Próba sprzętowa

Zapis próby:

```text
Data i operator:
Commit:
Model RPi i system:
Ekran/kompozytor:
Urządzenia wejściowe:
Warunki początkowe:
Kroki:
Oczekiwany wynik:
Rzeczywisty wynik:
Logi:
Droga wycofania sprawdzona: tak/nie
Czego próba nie potwierdza:
```

## Zmiany wymagające próby na Raspberry Pi

- obsługa GTK, layer-shell, Wayland i pełnego ekranu,
- most pokrętła i przytrzymanie awaryjne,
- NetworkManager i pomocnik sudo,
- nagrywanie, mpv i obsługa nośników,
- systemd, LightDM, labwc i autostart,
- instalator albo zmiana ścieżek systemowych.

## Sekrety

Publiczne szablony nie mogą zawierać tokenów, haseł, kluczy ani prawdziwego
adresu publicznego stanowiska. Wykryty sekret należy unieważnić — usunięcie go
z bieżącego pliku nie usuwa wcześniejszych kopii ani historii Git.
