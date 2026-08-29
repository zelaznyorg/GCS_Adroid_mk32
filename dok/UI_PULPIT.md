# Własny pulpit GCS na Raspberry Pi

Zastąpienie standardowego pulpitu Raspberry Pi OS własnym ekranem głównym: dostęp
do aplikacji Toma, zarządzanie siecią, nowoczesny wygląd — **obsługiwany jednym pokrętłem**.

- **Decyzje 2026-08-29:** podmieniamy **pulpit na HDMI**, technologia **natywny Python**
- **Platforma odniesienia:** Raspberry Pi 5, jeden ekran HDMI, Wayland/labwc
- **Stan:** implementacja działa; przed użyciem na nowej stacji wymagane są instalacja,
  test pokrętła i potwierdzenie drogi powrotu do standardowego pulpitu.

## Wymagania od Toma (2026-08-29)

1. **Dodawanie aplikacji bez kombinowania** — nowa pozycja ma się pojawiać sama
2. **Zarządzanie siecią przyjazne** — bez `nmcli` z konsoli
3. **Nowoczesny, funkcjonalny, intuicyjny wygląd**
4. ⛔ **Ekran HDMI nie jest dotykowy. Sterowanie: POKRĘTŁO** — to samo, które dziś
   obsługuje okrągły wyświetlacz i nagrywanie

---

## 1. Sterowanie — najtwardsze ograniczenie projektu

### 1.1 Co fizycznie jest — FAKT, odczytane z `/opt/pi5setup-full/src/panel_config.py`

| Element | Szczegóły |
|---|---|
| **Enkoder KY-040** | GPIO **22** (CLK) · **23** (DT) · **24** (SW), 3,3 V, `ENCODER_STEPS_PER_DETENT=4` |
| **Przycisk nagrywania** | osobny, **GPIO 21**, monostabilny — **to jest drugie wejście** |
| **Okrągły wyświetlacz** | GC9A01 **240×240**, SPI0/CE0, DC 25, RST 27, podświetlenie 18 |
| **Mysz** | ⚠ **`Logitech M350` jest podłączona** (`/proc/bus/input/devices`) |

> ⚠ **Dwie rozbieżności do potwierdzenia:**
> 1. Mówisz o **2,8″**, a konfiguracja opisuje **Waveshare 1,28″ Round Touch LCD,
>    GC9A01 240×240**. Panel na pewno działa na tym sterowniku — więc albo opis
>    w kodzie jest nieaktualny, albo chodzi o inny egzemplarz.
> 2. **Mysz jest w systemie.** Projektuję pod samo pokrętło (mysz może zniknąć albo
>    nie być pod ręką w polu), ale jeśli zostaje na stałe, wpisywanie haseł Wi‑Fi
>    robi się dużo prostsze — §4.2.

### 1.2 Sterownik enkodera daje dziś tylko dwa zdarzenia

`rotary_encoder.py`: **obrót** (`on_rotate ±1`) i **klik** (`on_click`, po puszczeniu).
**Przytrzymania nie ma.** W nowym UI dopiszemy je we własnej warstwie wejścia — to kilka
linijek, a bez niego nie ma jak wrócić.

### 1.3 ⛔ Pokrętło może mieć tylko JEDNEGO właściciela

`RPi.GPIO` na tej malinie to nakładka **`python3-rpi-lgpio` 0.6** na `lgpio`.
Linie GPIO są zajmowane **na wyłączność** — drugi proces dostanie błąd, nie zdarzenia.
Dziś właścicielem jest **`pi5-control-panel` (jako root)**.

**Wniosek bez wyjścia bocznym drzwiami: nie da się zbudować sterowanego pokrętłem
pulpitu, nie dotykając `control_panel.py`.** Do wyboru są dwie drogi:

| Droga | Na czym polega | Koszt |
|---|---|---|
| **A — panel rozgłasza** *(zalecana)* | `control_panel.py` zostaje właścicielem GPIO i **przesyła każde zdarzenie** na gniazdo `/run/gcs/pokretlo.sock`. Pulpit słucha | ~30 linii w kodzie Toma, w repo `GSB` |
| B — osobny demon wejścia | nowy `gcs-wejscie` przejmuje GPIO, panel **i** pulpit są odbiorcami | czystsze docelowo, ale **przebudowa panelu**, nie dopisek |

### 1.4 Kto ma pokrętło w danej chwili — „ognisko"

Jeden knob, dwa ekrany. Rozstrzyga to **okrągły wyświetlacz**, bo i tak zawsze pokazuje,
co się dzieje:

```
   pokrętło ──► panel GC9A01 (właściciel GPIO)
                    │
                    ├── strony panelu (nagrywanie, nośnik, VRX, temperatura, ustawienia)
                    │      └── obrót i klik zostają na okrągłym ekranie
                    │
                    └── strona "PULPIT GCS"  ← nowa
                           └── obrót i klik idą na HDMI, okrągły pokazuje wybraną pozycję
```

Powrót z aplikacji: **przytrzymanie ≥ 0,6 s = PULPIT**. To musi działać zawsze,
także gdy na wierzchu siedzi ATAK albo Chromium — inaczej bez klawiatury nie ma wyjścia.

**Zapasowe wejście: przycisk nagrywania (GPIO 21).** Zostaje przy nagrywaniu, ale
jest gotowym „awaryjnym powrotem", gdyby przytrzymanie okazało się w polu niewygodne.

---

## 2. Co zastajemy na pulpicie — FAKT

```
lightdm  (autologin admin, sesja "rpd-labwc")
   └── /usr/bin/labwc-pi  →  exec labwc -m        ← "-m" = TRYB SCALANIA
          ├── /etc/xdg/labwc/autostart            ← systemowy, z pakietu
          │     ├── lwrespawn pcmanfm-pi          ← ikony i tapeta
          │     ├── lwrespawn wf-panel-pi         ← pasek zadań
          │     ├── kanshi
          │     └── lxsession-xdg-autostart
          └── ~/.config/labwc/autostart           ← Twój, z blokami PI5SETUP
```

**`labwc -m` scala oba pliki.** Dlatego bloki PI5SETUP działają, a standardowy pasek
i ikony **i tak wstają** — z pliku użytkownika nie da się ich „odznaczyć", a plik
systemowy należy do pakietu i wróci przy `apt upgrade`.

### 2.1 Podmiana odwracalna jedną linijką

⛔ **Nie ruszamy `/etc/xdg/labwc/autostart`** · ⛔ **nie wyłączamy labwc** — Waydroid,
a w nim **ATAK‑CIV**, bez niego nie ruszy. Podmieniamy **powłokę, nie kompozytor**.

| Element | Co robi |
|---|---|
| `/usr/local/bin/labwc-gcs` | jak `labwc-pi`, ale `labwc` **bez `-m`** — pasek i ikony nie wstają |
| `~/.config/labwc-gcs/autostart` | uruchamia nasz pulpit + `kanshi` + wybrane bloki PI5SETUP |
| `/usr/share/wayland-sessions/gcs.desktop` | nowa pozycja sesji |
| `/etc/lightdm/lightdm.conf` | `autologin-session=gcs` — **jedyna zmiana w pliku systemowym** |

**Cofnięcie:** `autologin-session=rpd-labwc` + restart. Standardowy pulpit wraca,
bo nigdy go nie usuwaliśmy.

⚠ **Warunek wstępny: droga wejścia bez ekranu.** Dostęp SSH do stacji musi być
sprawdzony **przed** przełączeniem sesji, nie po.

---

## 3. Dodawanie aplikacji bez kombinowania

**Zasada: jeden plik = jeden kafelek. Bez restartu, bez edycji kodu, bez budowania.**

```
/etc/gcs/aplikacje.d/
├── 10-kamera-hud.json
├── 20-atak.json
└── 30-dron15.json
```

```json
{
  "nazwa": "ATAK-CIV",
  "opis": "Mapa taktyczna z podglądem z UAS Tool",
  "grupa": "mapy",
  "ikona": "atak.png",
  "uruchom": ["waydroid", "app", "launch", "com.atakmap.app.civ"],
  "wymaga": { "usluga": "waydroid-container" },
  "pelny-ekran": true
}
```

Trzy rzeczy, które to daje:

- **katalog jest obserwowany** (`inotify`) — wrzucenie pliku dokłada kafelek **od razu**
- **istniejące `.desktop` importują się same** — wszystko z `~/.local/share/applications`
  i `/usr/share/applications` można wskazać jednym polem `desktop: "waydroid.com.atakmap.app.civ"`,
  bez przepisywania polecenia
- **`wymaga`** gasi kafelek i mówi dlaczego („Waydroid nie działa"), zamiast uruchamiać
  coś, co i tak nie wstanie

Kafelki na start — wszystkie znalezione na maszynie:

| Kafelek | Uruchamia | Skąd wiadomo, że istnieje |
|---|---|---|
| **KAMERA CVBS + HUD** | `/usr/local/bin/hdmi-cvbs-camera` | `~/Desktop/Kamera-CVBS-HUD.desktop` |
| **ATAK‑CIV** | Waydroid `com.atakmap.app.civ` | `waydroid.com.atakmap.app.civ.desktop` |
| **DRON 15 — podgląd** | Chromium na `:8095` | `~/Desktop/dron15-podglad.desktop` |
| **NAGRANIA** | przegląd `/media/fpv-recordings` | `pi5-camera-recorder`, `lcd_recordings.py` |
| **ANDROID** | pełny Waydroid | 15 pozycji `waydroid.*.desktop` |

---

## 4. Zarządzanie siecią

### 4.1 Co zastajemy — FAKT

| Rzecz | Stan |
|---|---|
| NetworkManager | **działa**, `eth0` połączony jako `Wired connection 1` |
| **Wi‑Fi** | ⛔ **wyłączone w NetworkManagerze** (`WIFI: disabled`), `wlan0` `unavailable` |
| adres | `192.168.88.30/24` statycznie, brama `192.168.88.1` |

Czyli Wi‑Fi jest sprawne, tylko zgaszone — **i to jest pierwszy przypadek użycia**
tego ekranu: włączyć radio i połączyć się, bez konsoli.

### 4.2 Ekran SIEĆ

Wszystko przez NetworkManagera (jego D‑Bus), więc ustawienia przeżywają restart
i nie kłócą się z tym, co już jest.

| Co | Obsługa pokrętłem |
|---|---|
| stan: adres, brama, łącze, kto podłączony | tylko odczyt |
| **Wi‑Fi włącz / wyłącz** | klik na przełączniku |
| **lista sieci** z mocą sygnału | obrót po liście, klik = połącz |
| **hasło** | ⚠ patrz niżej |
| Ethernet: DHCP ↔ stały adres | wybór z listy, cyfry pokrętłem |
| **dostęp do sieci pokładowej** | podgląd, czy `192.168.144.20` i `.25` odpowiadają |

> ### ⚠ HASŁO WI‑FI TO JEDYNE MIEJSCE, GDZIE SAMO POKRĘTŁO BOLI
>
> Wpisanie 20 znaków kołem znaków to kilkadziesiąt obrotów. Trzy wyjścia, do wyboru:
>
> | Sposób | Ocena |
> |---|---|
> | **koło znaków** na ekranie | zawsze działa, wolne — **zrobimy je tak czy tak, jako podstawę** |
> | **mysz `Logitech M350`**, która już jest podłączona | najwygodniejsze, jeśli zostaje na stanowisku |
> | **strona z telefonu** (kod QR na ekranie) | najszybsze w polu, wymaga drugiej sieci albo kabla |
>
> Zaczynamy od koła znaków, bo ono nie zależy od niczego. Resztę dokładamy, jeśli zechcesz.

---

## 5. Wygląd i obsługa

**GTK4 + libadwaita, PyGObject.** `libgtk-4-1` i `libadwaita` **już są na malinie** —
brakuje samego typelibu: `sudo apt install gir1.2-gtk-4.0`. Odwrót: **GTK3**, działa
od ręki bez instalowania czegokolwiek; różnica dotyczy wyglądu, nie możliwości.

Zasady, które wynikają z pokrętła, nie z gustu:

- ⛔ **żadnego najeżdżania myszą, żadnych menu rozwijanych, żadnego przeciągania**
- **wszystko jest listą albo siatką** — obrót przesuwa zaznaczenie po jednej pozycji
- **zaznaczenie musi być widać z dwóch metrów**: gruba obwódka, nie zmiana odcienia
- **pierwszą pozycją każdej listy jest `◀ WSTECZ`** — żeby powrót nie zależał wyłącznie
  od przytrzymania
- **liczba pozycji w widoku ograniczona** — 6–8 kafelków, inaczej kręcenie trwa wieczność
- ciemne tło (praca w polu i w nocy), duża typografia, stan zamiast ozdób

### 5.1 Ekrany

| Ekran | Zawartość |
|---|---|
| **PULPIT** | siatka kafelków aplikacji |
| **STAN** | DVR i wolne miejsce, strumień dla ATAK, usługi stacji, telemetria (ramek/s), temperatura i dławienie, wolne miejsce na obu kartach |
| **SIEĆ** | §4.2 |
| **SYSTEM** | restart usług, restart i wyłączenie maliny, wersje |

### 5.2 Czego ten ekran NIE robi

- ⛔ **nic, co idzie do kontrolera lotu** — granica z `WLADZA.md` obowiązuje bez wyjątków
- ⛔ nie zastępuje panelu GC9A01 ani HUD‑a — to osobne UI i zostają
- ⛔ nie usuwa standardowego pulpitu, tylko przestaje go uruchamiać

---

## 6. Kolejność budowy

| # | Krok | Dlaczego w tej kolejności |
|---|---|---|
| 1 | warstwa wejścia: rozgłaszanie z panelu + przytrzymanie | **bez tego nic nie da się obsłużyć**; sprawdzalne w konsoli, bez UI |
| 2 | pulpit z kafelkami z `/etc/gcs/aplikacje.d` | pierwsza rzecz widoczna na ekranie |
| 3 | własna sesja labwc, wciąż **nie** jako domyślna | uruchamiana ręcznie, standardowy pulpit nietknięty |
| 4 | ekran STAN | czyta to, co już leży w `/run/pi5setup-full/*.json` |
| 5 | ekran SIEĆ | najwięcej pracy: koło znaków |
| 6 | przełączenie `autologin-session` | **dopiero gdy reszta działa** |

---

## 7. ✅ KROK 1 WYKONANY — warstwa wejścia działa (2026-08-29)

**Zmienione na malinie `GSB`. Wszystko odwracalne, kopie zapasowe leżą obok plików.**

| Co | Gdzie | Cofnięcie |
|---|---|---|
| **most pokrętła** | `/opt/pi5setup-full/src/gcs_most.py` (nowy plik) | `sudo rm` |
| **łatka panelu** | `control_panel.py` i `rotary_encoder.py`, 9 miejsc, każde ze znacznikiem `# GCS` | `sudo python3 zalataj_panel.py --cofnij` — kopie `*.przed-gcs` |
| **katalog `/run/gcs`** | drop-in `pi5-control-panel.service.d/10-gcs-most.conf`, `RuntimeDirectory=… gcs` | `sudo rm` pliku + `daemon-reload` |

⚠ **Drop-in jest konieczny, nie kosmetyczny:** usługa panelu ma `ProtectSystem=strict`,
więc bez wpisania `/run/gcs` w `RuntimeDirectory` gniazdo nie miałoby gdzie powstać.

⚠ **`apt upgrade` pakietu z panelem nadpisze łatkę.** Zmiany trzeba wnieść do repo
`GSB` (`github.com/NooqPL/GSB`) — plik `pulpit\rpi\zalataj_panel.py` jest ich pełnym opisem.

### Co zostało sprawdzone

| Próba | Wynik |
|---|---|
| panel wstaje z łatką | ✅ `active`, `Most pokrętła nasłuchuje na /run/gcs/pokretlo.sock` |
| prawa gniazda | ✅ `srw-rw---- root:video` — `admin` jest w grupie `video` |
| klient się podłącza | ✅ `Nowy odbiorca pokrętła (razem 1)`, od razu dostaje bieżące ognisko |
| przekazanie ogniska w obie strony | ✅ `panel → pulpit → panel` |
| **odejście klienta przy ognisku na pulpicie** | ✅ ognisko wraca do panelu samo — pokrętło nie zostaje martwe |

### ⛔ Czego NIE sprawdzono — wymaga ręki przy pokrętle

Obrót, klik i przytrzymanie **nie zostały przepuszczone przez fizyczny enkoder**.
Sprawdzenie zajmuje minutę:

```bash
python3 ~/gcs-src/narzedzia/sluchaj_pokretla.py
```

Potem na okrągłym ekranie **przekręcić na stronę `PULPIT GCS`** (jest teraz szósta,
kropek na dole jest sześć) i **kliknąć**. Od tej chwili obrót i klik mają się wypisywać
w konsoli, a przytrzymanie ponad pół sekundy oddaje pokrętło panelowi.

---

---

## 8. ✅ KROK 2 WYKONANY — pulpit z kafelkami działa (2026-08-29)

Aplikacja **`gcs_pulpit`** (Python + GTK4) chodzi na malinie jako jednostka
użytkownika `gcs-pulpit`, w oknie (`GCS_PELNY_EKRAN=0`) — standardowy pulpit
jest **nietknięty**, sesja graficzna niezmieniona.

| Co | Gdzie |
|---|---|
| kod | `/opt/gcs/pulpit/gcs_pulpit/` |
| aplikacje | `/etc/gcs/aplikacje.d/*.json` — **cztery wpisy** |
| jednostka | `~/.config/systemd/user/gcs-pulpit.service` |
| instalator | `pulpit/rpi/instaluj.sh` — **nie nadpisuje** istniejących wpisów aplikacji |

### Sprawdzone

| Próba | Wynik |
|---|---|
| wczytanie katalogu | ✅ cztery kafelki, wszystkie dostępne |
| skrót `.desktop` z `~/.local/share/applications` | ✅ ATAK‑CIV → `waydroid app launch com.atakmap.app.civ` |
| skrót `.desktop` wskazany ścieżką | ✅ DRON 15 → polecenie Chromium z kodem zaproszenia, cudzysłowy zdjęte |
| podłączenie do mostu pokrętła | ✅ panel melduje `Nowy odbiorca pokrętła (razem 1)` |
| wygląd | ✅ zrzut ekranu, ciemny, zaznaczenie widoczne |

⚠ **Pierwsze podejście wyszło białe** — GTK maluje przyciski własnym tłem z motywu
i moje reguły przegrywały specyficznością. Potrzebne było `button.kafelek`,
`background-image: none` i wymuszenie ciemnego motywu.

### Czego jeszcze nie ma

- **ikony** — kafelki są na razie samym tekstem
- **ekran STAN i SIEĆ** — kroki 4 i 5
- **pełny ekran i podmiana sesji** — kroki 3 i 6, celowo na końcu
- ⛔ **niesprawdzone w ruchu:** obrót, klik i uruchomienie aplikacji **z pokrętła**
  na tym ekranie. Warstwa wejścia działa (§7), pulpit jest podłączony — ale
  cała droga „pokrętło → kafelek → aplikacja” nie została jeszcze przejechana

---

---

## 9. Pierwsza próba w polu — trzy rzeczy naraz (2026-08-29)

Tom uruchomił z kafelka **DRON 15 — podgląd**. Wyszło okno z żądaniem hasła
i klawiaturą ekranową, której pokrętłem nie da się obsłużyć.

### 9.1 ⛔ To nie stacja pytała o hasło — to pęk kluczy GNOME

Zrzut ekranu rozstrzygnął w sekundę: **`Unlock Keyring — An application wants
access to the keyring "Default Keyring", but it is locked`**. Kod zaproszenia był
cały czas poprawny; jego wartość została usunięta z publicznej dokumentacji.

**Przyczyna:** sesja wchodzi **autologowaniem**, więc pęk kluczy logowania nigdy nie
zostaje odblokowany hasłem użytkownika. Chromium sięga po niego, żeby schować ciasteczka
— i staje. To **nieuchronny skutek autologowania**, nie usterka stacji ani kokpitu.

**Lek — dwie flagi w opisie aplikacji:**

| Flaga | Po co |
|---|---|
| `--password-store=basic` | Chromium omija pęk kluczy; **żadnego okna z hasłem** |
| `--ozone-platform-hint=auto` | bez tego Chromium uruchomione spoza sesji szuka X11 i kończy się `Missing X server or $DISPLAY` |

✅ **Sprawdzone po poprawce:** strona wchodzi wprost, zalogowana jako „monitory stacji",
bez jednego pytania. (Pokazuje `BRAK TELEMETRII` — to osobna sprawa, poz. R9.)

⚠ **Ta sama pułapka czeka każdą aplikację sięgającą po pęk kluczy.** Trwalsze
rozwiązanie to pęk z pustym hasłem, ale flaga załatwia sprawę bez ruszania systemu.

### 9.2 ⛔ Klawiatura ekranowa `squeekboard` jest bezużyteczna przy pokrętle

Wyskoczyła sama, bo pojawiło się pole tekstowe. Jest zrobiona pod dotyk, a ekran
dotykowy nie jest. **Dopóki gdzieś trzeba wpisać tekst, pokrętło jest bezradne.**

Podział, który z tego wynika:

| Gdzie trzeba pisać | Rozwiązanie |
|---|---|
| **nasze ekrany** (hasło Wi‑Fi, adres IP) | **koło znaków w naszym oknie** — pełna kontrola, bez żadnych sztuczek |
| **cudze okna** (Chromium, ATAK) | wymaga wirtualnej klawiatury przez `zwp_virtual_keyboard_v1` + nakładki `layer-shell`, która **nie zabiera ogniska**. To osobne, większe zadanie |
| **ten konkretny przypadek** | ✅ **zniknął** — flaga usunęła potrzebę pisania |

**Wniosek roboczy: unikać pisania, zamiast budować klawiaturę.** Do każdej aplikacji
wchodzić tak, żeby nie pytała — kodem w adresie, flagą, wcześniej zapisanym stanem.

### 9.3 ✅ Poprawione przy okazji: powrót z aplikacji samym pokrętłem

Poprzednia wersja miała lukę: uruchomiona pełnoekranowa aplikacja przykrywała pulpit,
a **przytrzymanie od razu oddawało pokrętło panelowi** — czyli operator zostawał
z aplikacją, z której nie ma jak wyjść. Teraz:

| Gest | Skutek |
|---|---|
| przytrzymanie, gdy na wierzchu jest aplikacja | **pulpit na wierzch**, pokrętło zostaje |
| przytrzymanie, gdy pulpit już jest na wierzchu | oddanie pokrętła panelowi |
| kafelek **`✕ ZAMKNIJ …`** | pojawia się **pierwszy**, dopóki cokolwiek chodzi; gasi całą grupę procesów (Chromium zostawia potomków) |

Aplikacja zamknięta własnym krzyżykiem znika z listy sama — sprawdzane co 3 s.

---

---

## 10. ✅ KLAWIATURA STEROWANA POKRĘTŁEM (2026-08-29)

Na żądanie Toma: **pisanie pokrętłem musi być możliwe na wszelki wypadek,
a klawiatura ma być w stylu naszej aplikacji.** Zbudowane: `gcs_pulpit/klawiatura.py`.

### 10.1 Dlaczego to w ogóle może działać

Dwie rzeczy złożone razem — żadna osobno by nie wystarczyła:

| Składnik | Co daje |
|---|---|
| nakładka **`layer-shell` z `KeyboardMode.NONE`** | leży nad wszystkim, ale **nie zabiera ogniska** — okno pod spodem zostaje aktywne |
| sterowanie **pokrętłem po gnieździe UNIX** | nie potrzebujemy ogniska, żeby odbierać obrót i klik |
| **`wtype`** (`zwp_virtual_keyboard_v1`) | wpisuje znaki do okna, które **ma** ognisko |

**Sprawdzone pomiarem, nie założone:** `wtype` wpisał `zażółć gęślą jaźń 123` do pola
w oknie pod nakładką — z polskimi znakami, bez utraty ogniska.

⚠ **Wymaga `LD_PRELOAD=/usr/lib/aarch64-linux-gnu/libgtk4-layer-shell.so.0`.**
Bez tego biblioteka ląduje w dowiązaniach po `libwayland` i nakładka **po cichu
staje się zwykłym oknem** — zabiera ognisko i pisze sama do siebie. Wpisane
w jednostkę `gcs-pulpit.service`.

⛔ **`LD_PRELOAD` jest zdejmowane przy uruchamianiu aplikacji** — wpychanie
gtk4‑layer‑shell do Chromium czy Waydroida nie ma sensu i może zaszkodzić.

### 10.2 Jak się jej używa

Kafelek **`⌨ KLAWIATURA`** na pulpicie. Pulpit **chowa się** na czas pisania —
inaczej to on byłby oknem aktywnym i znaki trafiłyby do niego.

| Gest | Skutek |
|---|---|
| obrót | zaznaczenie kolejnego klawisza |
| klik | wciśnięcie |
| przytrzymanie | anuluje i zamyka |

Warstwy: **litery**, **⇧ DUŻE**, **123** (cyfry i znaki, cztery rzędy),
**ĄĘ** (`ą ć ę ł ń ó ś ź ż`). Klawisze funkcyjne: `SPACJA`, `⌫ CZYŚĆ`,
`✓ WYŚLIJ`, `⏎ WYŚLIJ+ENTER`, `✕ ANULUJ`.

> ### Tekst zbiera się najpierw u nas, a nie leci znak po znaku
>
> Litery lądują w podglądzie na górze klawiatury, a do okna docelowego idą dopiero
> po `WYŚLIJ`. Przy pisaniu pokrętłem literówka jest nieunikniona, a poprawianie jej
> w cudzym polu wymagałoby strzałek, których nie ma. Tak można sprawdzić całość
> przed wysłaniem — przy hasłach Wi‑Fi to jest różnica między wygodą a zgadywanką.

### 10.3 Sprawdzone

| Próba | Wynik |
|---|---|
| nakładka nie zabiera ogniska | ✅ tekst trafił do okna pod spodem |
| przełączanie warstw litery → 123 → ĄĘ | ✅ bufor `gcs15ł` |
| wysyłka przez `wtype` | ✅ w polu dokładnie `gcs15ł` |
| polskie znaki | ✅ `zażółć gęślą jaźń` |
| wygląd w stylu pulpitu | ✅ zrzut ekranu |

⛔ **Niesprawdzone w ruchu:** obsługa **fizycznym pokrętłem** (próba szła przez
wywołania w kodzie) oraz kafelek `⌨ KLAWIATURA` na żywym pulpicie.

### 10.4 ⛔ `squeekboard` wyłączony

Systemowa klawiatura dotykowa wyskakiwała sama przy każdym polu tekstowym
i **zasłaniała naszą**. Wyłączona po stronie użytkownika, bez ruszania plików systemowych:

```
~/.config/autostart/squeekboard.desktop   →   Hidden=true
```

**Cofnięcie: usunąć ten plik.** Ekran nie jest dotykowy, więc squeekboard i tak
nie miał czym być obsługiwany.

---

---

## 11. Dwie usterki z próby w polu (2026-08-29)

### 11.1 ⛔ Po `WYŚLIJ` znikał cały pulpit — błąd, nie zamierzenie

Klawiatura chowa pulpit (`set_visible(False)`), żeby to nie **on** był oknem aktywnym.
Po jej zamknięciu **nikt go nie pokazywał z powrotem** — wyglądało to jak zamknięcie GCS,
choć usługa cały czas chodziła.

Poprawione: **jedno miejsce, `_wroc_na_pulpit()`**, przez które przechodzi każdy powrót
z nakładki — klawiatury i pilota, po zatwierdzeniu i po anulowaniu.

### 11.2 ⛔ Uruchomioną aplikacją nie dało się sterować — brak, nie usterka

Pokrętło obsługiwało kafelki i klawiaturę, ale **nie samą aplikację**. Chromium czy ATAK
nie wiedzą o pokrętle, więc po uruchomieniu dało się je tylko zamknąć.

Zbudowane: **`gcs_pulpit/pilot.py`** — pasek na dole, ta sama nakładka bez ogniska,
zamieniający pokrętło na klawisze, które rozumie każda aplikacja:

```
↓  ↑  ←  →  ENTER  ESC  TAB  ⇧TAB  SPACJA  PgDn  PgUp  ⌫
⌨ KLAWIATURA        ✕ ZAMKNIJ APLIKACJĘ        ◀ PULPIT
```

> ### Dlaczego wybór klawisza, a nie „obrót = strzałka w dół"
>
> Mając jedno pokrętło i jeden przycisk trzeba zdecydować, co znaczy obrót. Gdyby był
> strzałką, nie byłoby jak sięgnąć po `ENTER` ani `ESC`.
>
> Dlatego **obrót wybiera klawisz, klik go wciska, a zaznaczenie zostaje na miejscu.**
> Przewinięcie listy to jeden obrót na `↓` i tyle kliknięć, ile trzeba.

**Pilot otwiera się sam 3 sekundy po uruchomieniu aplikacji** — tyle potrzeba, żeby
wstała i wzięła ognisko; wcześniejsze klawisze poszłyby w próżnię. Przytrzymanie
wraca na pulpit. Dopóki coś chodzi, na pulpicie jest też kafelek `🕹 STERUJ APLIKACJĄ`.

### 11.3 Sprawdzone

| Próba | Wynik |
|---|---|
| pilot wciska klawisze w oknie pod spodem | ✅ dwa `⌫` zmieniły `abcdef` na `abcd` |
| układ i wygląd paska | ✅ zrzut ekranu |
| powrót pulpitu po zamknięciu nakładki | ✅ pulpit wraca |

⛔ **Niesprawdzone fizycznym pokrętłem:** samoczynne wejście w pilota po starcie
aplikacji, przejście `pilot → klawiatura → pilot` i `✕ ZAMKNIJ APLIKACJĘ`.

---

### 11.4 ⛔ Kierunek pokrętła był odwrotny — i przyczyna była w cudzym kodzie

Zaznaczenie szło w stronę przeciwną do ręki. **Surowy kierunek z enkodera jest
odwrotny do naturalnego**, a panel GC9A01 sam to prostuje — jego funkcja nazywa się
wprost **`reversed_axis_index`** i liczy `-direction` (`panel_settings.py`).

Nasz pulpit brał kierunek surowy, prosto z mostu, i dlatego zachowywał się odwrotnie
niż wszystko inne na tej maszynie. Wniosek ogólny: **most przekazuje surowe zdarzenia
sprzętu, nie zdarzenia gotowe do użycia** — każdy odbiorca musi wiedzieć, co z nimi zrobić.

Prostowanie wstawione **w jednym miejscu** (`wejscie.py`), więc obowiązuje kafelki,
klawiaturę i pilota naraz. Wyłącznik na wypadek wymiany enkodera:
`GCS_POKRETLO_ODWROTNE=0`.

---

---

## 12. Mysz sterowana pokrętłem — i dlaczego musiała powstać (2026-08-29)

### 12.1 Pilot nie wystarczył, ale przyczyna była inna, niż się wydawało

Tom zgłosił, że uruchomionej aplikacji nadal nie da się obsłużyć. **Pierwszy odruch
— „klawisze nie docierają" — był błędny.** Rozstrzygnął pomiar: wysłany `F11`
przełączył Chromium na pełny ekran. Czyli tor `pilot → wtype → okno` działa bez zarzutu.

Trzy `TAB`-y nie zaznaczyły natomiast **niczego widocznego**. Wniosek:

> ⛔ **Strona DRON 15 nie jest obsługiwalna klawiaturą.** Aplikacja zrobiona pod dotyk
> i mysz nie stanie się taka dlatego, że my potrzebujemy. Klawisze docierają — nie ma
> ich co odebrać.

Zostaje wskaźnik. **`wlrctl`** (protokół `zwlr_virtual_pointer_v1`) rusza kursorem
i klika — bez roota, bez `uinput`, przez zwykłe gniazdo Waylanda.

### 12.2 Trzy tryby, bo gestów są dwa

Pokrętło daje obrót i klik, a potrzebne są trzy rzeczy: ruch w poziomie, ruch w pionie
i kliknięcie. Dlatego **obrót zawsze rusza kursorem, a klik przełącza, czym rusza**:
`POZIOM → PION → KLIK`. W trybie `KLIK` klik naciska przycisk myszy i tam zostaje,
więc dwuklik to po prostu dwa kliknięcia. Przytrzymanie wychodzi.

Ruch **sumuje się i leci paczkami co 60 ms** — każde `wlrctl` to osobny proces,
a przy 20 zaskokach na sekundę byłoby ich 20. Przy okazji kursor płynie zamiast skakać.
Krok rośnie z prędkością kręcenia: 14 → 70 → 170 px.

### 12.3 Sprawdzone

| Próba | Wynik |
|---|---|
| `wlrctl pointer move` | ✅ kursor przesunął się, widoczny na zrzucie `grim -c` |
| przełączanie trybów klikiem | ✅ `POZIOM → PION` |
| ruch w obu osiach | ✅ różnica między zrzutami |
| wygląd paska | ✅ zrzut ekranu |

⛔ **Niesprawdzone fizycznym pokrętłem.**

---

## 13. ⛔ OKRĄGŁY EKRAN JAKO GŁADZIK — POMYSŁ DOBRY, ALE DOTYK NIE JEST PODŁĄCZONY

Tom zaproponował użycie okrągłego wyświetlacza jako gładzika. **Kierunek jest słuszny** —
palec przesuwający kursor wprost to zupełnie inna wygoda niż przełączanie osi pokrętłem.

**Rozstrzyga własna dokumentacja połączeń Toma**, `GSB/docs/PI5_PINOUT_POLACZEN.pdf`
(2026‑08‑26). Wyświetlacz ma tam wypisane **siedem sygnałów i ani jednego dotykowego**:

| Sygnał | GPIO | Pin |
|---|---|---|
| VCC / GND | 3,3 V / GND | 1 / 9 |
| DIN/MOSI · CLK/SCL · CS · DC · RST · BL | 10 · 11 · 8 · 25 · 27 · 18 | 19 · 23 · 24 · 22 · 13 · 12 |

⛔ **Brak `SDA`, `SCL`, `INT` i `RST` dotyku.** Piny 3 i 5 — czyli `GPIO2/GPIO3`,
jedyna sprzętowa magistrala I2C na złączu — są w tabeli oznaczone jako **niewykorzystane**.

**Potwierdzone pomiarem, nie samą dokumentacją:**

| Sprawdzenie | Wynik |
|---|---|
| urządzenia wejściowe jądra | tylko HDMI, przycisk zasilania, mysz Logitech — **żadnego dotyku** |
| ślad dotyku w repo `GSB` | **zero** — ekran jest używany wyłącznie do rysowania |
| jedyne urządzenie na `i2c-1` | `0x2a`, odpowiada na odczyty |
| **60 s próbkowania `0x2a` przy dotykaniu ekranu** | ⛔ **zero zmian** |

### ✅ POTWIERDZONE PRZEZ TOMA (2026-08-29): MODUŁ MA DOTYK, PIN JEST FIZYCZNIE ODPIĘTY

Pomiar i dokumentacja mówiły „nie ma połączenia" — Tom potwierdził przyczynę:
**warstwa dotykowa w module jest, ale jej wyprowadzenie nie zostało podłączone.**
To zamyka diagnostykę: **nie ma tu nic do naprawienia w oprogramowaniu.**

⚠ **Zadanie sprzętowe po stronie Toma.** Do czasu podłączenia obowiązuje mysz
z pokrętła (§12).

### Co trzeba zrobić, żeby pomysł ruszył

1. ~~Ustalić, czy moduł ma dotyk~~ — ✅ **ma**, potwierdzone przez Toma.
   ⚠ Zostaje ustalić **który to moduł**: dokumentacja i `panel_config.py` mówią
   **GC9A01 1,28″**, Tom o 2,8″ — od tego zależy kontroler i jego adres.
2. **Dołożyć cztery przewody:** `SDA→GPIO2 (pin 3)`, `SCL→GPIO3 (pin 5)`,
   `INT→wolny GPIO`, `RST→wolny GPIO`. ⚠ Przewody przekładać **przy wyłączonym Pi** —
   to zasada z jego własnej dokumentacji.
3. **Napisać odczyt kontrolera** (na module 1,28″ zwykle CST816S pod `0x15`) i przełożyć
   współrzędne palca na `wlrctl pointer move`. Kod jest krótki, bo tor wskaźnika
   już działa i jest sprawdzony.

**Do tego czasu obowiązuje mysz z pokrętła (§12)** — mniej wygodna, ale bez lutownicy.

---

---

## 14. ✅ EKRAN SIEĆ — zbudowany (2026-08-29)

Drugie z czterech wymagań Toma. `gcs_pulpit/siec.py`, kafelek **`🌐 SIEĆ`**.

| Pozycja | Co robi |
|---|---|
| nagłówek | adres Ethernetu i stan Wi‑Fi |
| `WI-FI: WŁĄCZ / WYŁĄCZ` | przełącza radio |
| `⟳ ODŚWIEŻ LISTĘ SIECI` | ponowny skan |
| lista sieci | siła sygnału słupkami, 🔒 przy zabezpieczonych, ✓ przy połączonej |
| `+ DODAJ SIEĆ UKRYTĄ` | dwa pytania po kolei: **nazwa**, potem **hasło** |
| `SPRAWDŹ ŁĄCZNOŚĆ Z DRONEM` | ping `192.168.144.20` i `.25` przez router |

> ### Sieć ukryta wymagała osobnej drogi
>
> Taka sieć **nie rozgłasza nazwy**, więc nigdy nie pojawi się na liście skanowania —
> bez tej pozycji nie da się do niej wejść w ogóle. NetworkManager musi dostać wprost
> polecenie jej poszukania (`hidden yes`), stąd osobne polecenie pomocnika
> **`connect-hidden`**.
>
> ⚠ **Pułapka, którą trzeba było obejść:** pytanie o nazwę i pytanie o hasło to
> **dwie klawiatury po kolei**. Pierwsza, zamykając się, kasowała referencję do drugiej
> — sprzątanie sprawdza teraz tożsamość okna, a odpowiedź jest oddawana dopiero
> po jego zamknięciu.

**Hasło zbiera nasza klawiatura** (§10) — i tu jest jej najlepsze zastosowanie:
tekst trafia **do naszego pola**, nie przez `wtype` do cudzego okna. Można obejrzeć
całość przed zatwierdzeniem.

### 14.1 ⛔ `nmcli` odmawia pulpitowi — i to nie jest usterka

NetworkManager przyznaje prawo do włączenia radia i połączenia tylko **aktywnej
sesji lokalnej**. Pulpit chodzi jako jednostka użytkownika, więc dostaje to samo,
co połączenie po SSH:

```
Error: failed to set Wi-Fi radio: Not authorized to perform this operation
```

**Rozwiązane tym samym wzorcem, co panel STACJA w stacji DRON 15:**

| Element | Rola |
|---|---|
| `/usr/local/sbin/gcs-siec` | jeden plik, **zamknięta lista operacji**: `wifi-on`, `wifi-off`, `rescan`, `connect`, `forget` |
| `/etc/sudoers.d/gcs-siec` | wskazuje **ten plik**, ⛔ **bez gwiazdki i bez `nmcli` wprost** |
| instalator | wzorzec przechodzi `visudo -c` **zanim** trafi na miejsce; przy niepowodzeniu odpuszcza i mówi o tym |

Hasło idzie do pomocnika **wejściem standardowym**, więc nie występuje w poleceniu
`sudo` ani w dzienniku pulpitu. Nazwa sieci jest sprawdzana (długość, znaki
sterujące) i przekazywana przez `argv`, nigdy przez powłokę.

⚠ **Znane ograniczenie:** udokumentowany interfejs `nmcli device wifi connect`
przyjmuje hasło jako argument. Pomocnik musi przekazać je w tej postaci do
krótkotrwałego procesu `nmcli`, dlatego lokalny użytkownik uprawniony do podglądu
obcych procesów może je wtedy zobaczyć. Usunięcie tego ograniczenia wymaga
osobnego projektu profilu/secret-agenta i próby na docelowym Raspberry Pi.

### 14.2 Sprawdzone

| Próba | Wynik |
|---|---|
| odmowa bez uprawnień | ✅ potwierdzona przed zbudowaniem pomocnika |
| `sudo -n gcs-siec wifi-on` | ✅ **radio włączone**, `wlan0: disconnected` |
| odczyt listy sieci | ✅ dwie sieci z siłą sygnału i informacją o zabezpieczeniu |
| pozycja sieci ukrytej | ✅ na liście, `connect-hidden` w pomocniku |
| **odporność pomocnika na wstrzyknięcie** | ✅ `gcs-siec "radio; rm -rf /"` → `BŁĄD: nieznane polecenie` |
| wygląd ekranu | ✅ zrzut ekranu |

⚠ **Wi‑Fi zostało włączone i takie zostaje** — było zgaszone, a teraz da się je
zgasić z ekranu. `wlan0` jest `disconnected`, więc nic się samo nie łączy i trasa
domyślna przez `eth0` pozostaje nietknięta.

⛔ **Niesprawdzone:** połączenie z siecią z hasłem wpisanym pokrętłem.

### 14.3 ⚠ Pułapka, która zjadła jeden przebieg instalatora

Skrypty zapisywane z Windows dostały **końce linii `CRLF`** i `instaluj.sh`
wywrócił się na `set: Illegal option -`. Wszystkie pliki w tym katalogu są teraz
znormalizowane do `LF` — przy każdej edycji z Windows pilnować tego samego.

---

---

## 15. ✅ GRUPY, BELKA I DODAWANIE APLIKACJI (2026-08-29)

Trzy zmiany na życzenie Toma, wszystkie wgrane.

### 15.1 Klawiatura zeszła z siatki na belkę

Kafelek klawiatury zajmował miejsce w siatce, a potrzebny jest rzadko. Belka dolna
ma teraz **ikony `⌨` i `🖱`** — klawiatura i mysz są zawsze pod ręką i nic nie
zasłaniają. Klawiatura jest **dodatkowo** w grupie SYSTEM, bo tam szuka się jej
świadomie.

⚠ **Brakowało czcionki emoji** — `📁` i `🖱` wychodziły jako puste prostokąty.
Doinstalowane `fonts-noto-color-emoji`.

### 15.2 Grupy kafelków

Kafelek z polem **`grupa`** nie stoi już wprost na pulpicie — chowa się w kafelku
grupy, który pokazuje jej nazwę i liczbę pozycji. Wejście klikiem, powrót przez
`◀ WSTECZ` albo przytrzymanie.

Ekran główny wygląda teraz tak:

| Kafelek | Skąd |
|---|---|
| KAMERA CVBS + HUD · ATAK-CIV · DRON 15 | aplikacje bez grupy |
| **📁 SYSTEM (4 pozycji)** | SIEĆ, KLAWIATURA, DODAJ APLIKACJĘ, ANDROID |

Grupa powstaje **z samego użycia** — nie ma osobnej listy grup do utrzymywania.
Wpisanie nowej nazwy przy dodawaniu aplikacji tworzy ją, usunięcie ostatniego
kafelka sprawia, że znika.

### 15.3 Ekran `➕ DODAJ APLIKACJĘ`

Zamiast pisać plik JSON ręcznie — lista tego, co system **naprawdę ma**.
Zmierzone na `GSB`: **36 aplikacji**, podzielone na **ANDROID (Waydroid)**
i **MALINĘ**.

| Element | Zachowanie |
|---|---|
| `GRUPA DOCELOWA: … ▸ zmień` | przechodzi po istniejących grupach, `(bez grupy)` i `NOWA GRUPA…` |
| `+ Nazwa` | dokłada kafelek do wybranej grupy |
| `✓ Nazwa ▸ usuń z pulpitu` | zabiera kafelek |
| `✓ Nazwa (z instalatora)` | ⛔ **nie do usunięcia z ekranu** |

Pozycje z Androida dostają automatycznie `wymaga: {"usluga": "waydroid-container"}`,
więc kafelek **gaśnie**, gdy Waydroid nie chodzi, zamiast próbować i zawodzić.

> ### Gdzie lądują dodane kafelki — i dlaczego nie w `/etc`
>
> W **`~/.config/gcs/aplikacje.d/`**. Katalog użytkownika nie wymaga roota, więc
> dodawanie działa z pulpitu bez żadnych uprawnień i bez `sudo`. `/etc/gcs/aplikacje.d`
> zostaje na to, co przychodzi z instalatorem — te wpisy są na liście widoczne,
> ale **nie do skasowania jednym kliknięciem**.
>
> Oba katalogi są obserwowane, więc kafelek pojawia się **natychmiast**.

### 15.4 Sprawdzone

| Próba | Wynik |
|---|---|
| grupy na ekranie głównym | ✅ trzy aplikacje + `📁 SYSTEM (4 pozycji)` |
| belka z ikonami | ✅ zrzut ekranu |
| wykrywanie zainstalowanych | ✅ **36 pozycji**, podział Android / malina |
| wygląd ekranu dodawania | ✅ zrzut ekranu |

⛔ **Niesprawdzone pokrętłem:** wejście w grupę i powrót, dodanie i usunięcie
kafelka, założenie nowej grupy przez klawiaturę.

---

### 15.5 ⛔ Lista nie szła za zaznaczeniem — poprawione

Przy 38 pozycjach zaznaczenie schodziło pod dolny brzeg i **nie było widać,
co się wybiera**. `Gtk.ScrolledWindow` sam tego nie robi: przesuwa się za
**ogniskiem klawiatury**, a my ognisk nie używamy — zaznaczenie jest własną klasą CSS.

Dopisane `gcs_pulpit/widoki.py` — jedna funkcja `przewin_do()`, wpięta w **trzy
miejsca naraz**: siatkę kafelków, ekran SIEĆ i ekran DODAJ APLIKACJĘ. Liczenie idzie
przez `idle_add`, bo tuż po przebudowie listy widgety nie mają jeszcze przydzielonego
miejsca i `compute_bounds` zwróciłoby nieprawdę.

Do tego **licznik pozycji w nagłówku** (`Wybrane: 26 z 38`) — przy długiej liście
sama widoczność zaznaczenia nie mówi, ile jeszcze zostało.

| Próba | Wynik |
|---|---|
| 25 zaskoków w dół na liście 38 pozycji | ✅ suwak 1646 z 3510, zaznaczone `+ Thonny` widoczne |
| licznik w nagłówku | ✅ `Wybrane: 26 z 38` |

---

### 15.6 ⛔ Trzy usterki z pierwszego użycia — poprawione

Tom dodał Firefoksa i wyszły trzy rzeczy naraz.

#### a) Aplikacja trafiła nie tam, gdzie trzeba

„Grupa docelowa" była ustawiana **z góry**, jedną pozycją na początku listy.
Przy trzydziestu kilku pozycjach nikt nie pamięta, co jest ustawione u góry —
Firefox wpadł do SYSTEM.

**Odwrócona kolejność:** kliknięcie aplikacji zadaje teraz pytanie **`Gdzie dodać: …`**
z listą istniejących grup, pozycją `(bez grupy) — wprost na pulpicie` i `➕ NOWA GRUPA…`.
Zaznaczenie startuje na „bez grupy", bo to najczęstszy wybór. Jedno kliknięcie więcej,
za to bez pomyłki. Lista pokazuje też, **w której grupie** co już jest: `✓ Firefox [SYSTEM]`.

#### b) ⛔ Z grupy nie dało się wyjść przytrzymaniem — najpoważniejsza z trzech

Wewnątrz grupy przytrzymanie **oddawało pokrętło panelowi** zamiast cofnąć.
Operator zostawał w grupie z jedną drogą wyjścia — dokręceniem do kafelka `◀ WSTECZ`.

**Przytrzymanie znaczy teraz zawsze „o krok wstecz", i wychodzi warstwami:**

```
nakładka (klawiatura, pilot, mysz)  →  widok (SIEĆ, DODAJ)  →  grupa  →  pulpit
                                                                        ↓
                                                          dopiero stąd: oddanie panelowi
```

#### c) Wyjście po dodaniu było nieoczywiste

Po dodaniu zaznaczenie zostawało w środku długiej listy. Teraz **wraca na `◀ WSTECZ`**,
a komunikat mówi wprost: *„Dodane: Firefox wprost na pulpicie. Przytrzymaj pokrętło,
aby wrócić."* Sam kafelek `WSTECZ` nosi też podpowiedź `(albo przytrzymaj pokrętło)`.

⚠ **Firefoksa przeniesiono z SYSTEM na pulpit główny** — kafelek pojawił się od razu,
bez restartu, co przy okazji potwierdziło działanie obserwatora katalogów.

| Próba | Wynik |
|---|---|
| pytanie o grupę przy dodawaniu | ✅ `ANULUJ` · `(bez grupy)` · `📁 SYSTEM` · `📁 MAPY` · `➕ NOWA GRUPA…` |
| przeniesienie Firefoksa na pulpit | ✅ kafelek na ekranie głównym, bez restartu |

⛔ **Niesprawdzone pokrętłem:** wyjście z grupy przytrzymaniem i pełna droga
dodawania z założeniem nowej grupy.

---

### 15.7 ⛔ „Nie da się przejść na WSTECZ" — zaznaczenie tam było, tylko niewidoczne

Zgłoszenie brzmiało jak błąd nawigacji. Okazało się błędem stylu.

**Przyczyna: szczegółowość CSS.** Reguła zaznaczenia i reguła typu kafelka miały
**tę samą szczegółowość** (element + jedna klasa), a `button.kafelek-wstecz` stało
w pliku **niżej** niż `button.kafelek-wybrany` — więc wygrywało i nadpisywało kolor
obwódki. Zaznaczenie przechodziło na WSTECZ poprawnie, po prostu **nie było go widać**.

Poprawka: reguła zaznaczenia dostaje **dwie klasy** (`button.kafelek.kafelek-wybrany`),
więc wygrywa niezależnie od kolejności. To samo w pięciu pozostałych modułach —
`siec-wstecz` i `dodaj-wstecz` miały dokładnie tę samą wadę, tylko jeszcze nikt na nią
nie trafił.

### 15.8 Druga usterka, znaleziona przy odtwarzaniu: podwójne zatwierdzenie

Przy próbie odtworzenia wyszło coś innego: **`ENTER` na kafelku grupy otwierał
klawiaturę** zamiast wejść do grupy.

Nasze przyciski przyjmowały **ognisko GTK**. Zaznaczenie prowadzimy sami, ale GTK
niezależnie od nas aktywowało klawiszem przycisk z ogniskiem — zatwierdzenie szło
**dwa razy**: raz nasze, raz GTK, i drugie trafiało w sąsiednią pozycję.

| Poprawka | Po co |
|---|---|
| `set_can_focus(False)` na wszystkich naszych przyciskach | GTK przestaje je aktywować samo |
| kontroler klawiatury w **fazie przechwytywania** | bez ogniska zdarzenia nie miałyby do kogo trafić |

⚠ **To dotyczyło tylko klawiatury fizycznej, nie pokrętła** — tor pokrętła wywołuje
naszą obsługę raz i nigdy nie miał tego problemu.

### 15.9 Sposób odtwarzania, który warto zapamiętać

Pulpit przyjmuje **te same akcje ze strzałek i `ENTER`**, co z pokrętła, a `wtype`
potrafi te klawisze wysłać. Dzięki temu całą nawigację da się przejechać zdalnie,
bez ręki przy enkoderze — tak właśnie znaleziono obie powyższe usterki.

⚠ **Pułapka:** jeśli na wierzchu wisi otwarta nakładka (klawiatura, pilot, mysz),
pulpit jest schowany i klawisze idą w próżnię. Przed próbą zrestartować `gcs-pulpit`.

| Próba | Wynik |
|---|---|
| wejście w grupę `ENTER`-em | ✅ otwiera grupę, nie klawiaturę |
| obrót do WSTECZ w grupie | ✅ **obwódka widoczna** |
| `ENTER` na WSTECZ | ✅ powrót na ekran główny |

---

---

## 16. ✅ ODTWARZACZ NAGRAŃ ZE STACJI (2026-08-29)

⛔ **Nie jest to odtwarzacz nagrań z rejestratora CVBS** — tamte mają już swój,
wbudowany w aplikację HUD (`lcd_recordings.py` + `cvbs/app/playback.py`), sterowany
z okrągłego panelu. Tego nie dublujemy.

Bez odtwarzacza zostało **archiwum stacji podglądu**: `/var/lib/dron15/archiwum/wideo/zr30`.
Nagrywa je MediaMTX, a obejrzeć ich nie dało się na miejscu niczym.

### 16.1 Jak zrobione

`gcs_pulpit/nagrania.py`, kafelek **`🎞 NAGRANIA`** wprost na pulpicie.

| Warstwa | Rozwiązanie |
|---|---|
| lista | data, długość i rozmiar, najnowsze na górze; nagłówek z sumą i licznikiem |
| odtwarzanie | **`mpv --fullscreen`** |
| sterowanie | pasek-nakładka nad obrazem: `⏯ PAUZA`, `⏪/⏩ 10 s`, `⏮/⏭ 1 min`, `✕ ZAMKNIJ` |

> ### Dlaczego `mpv`, a nie VLC czy `ffplay`
>
> Odtwarzacz musi dać się obsłużyć **pokrętłem**, czyli sterować z zewnątrz.
> `mpv` wystawia gniazdo (`--input-ipc-server`) i przyjmuje polecenia w JSON —
> pauza, przewijanie, zamknięcie i **odczyt pozycji**, dzięki któremu pasek pokazuje
> `0:03 / 0:18`. VLC ma coś podobnego, ale po omacku; `ffplay` nie ma nic.

**Czasy trwania liczy `ffprobe` w tle.** 126 wywołań z góry zatrzymałoby ekran na
kilka sekund — lista pojawia się od razu z datą i rozmiarem, długości dopisują się same.

**Koniec nagrania zamyka odtwarzacz sam.** Odpytywanie pozycji przestaje odpowiadać,
gdy `mpv` znika — to jest sygnał do sprzątnięcia nakładki i powrotu na listę.
Działa tak samo, gdy ktoś zamknie `mpv` z zewnątrz.

### 16.2 Sprawdzone na żywych nagraniach

| Próba | Wynik |
|---|---|
| lista archiwum | ✅ **126 nagrań, 521 MB, łącznie 46:22** |
| długości w tle | ✅ uzupełniły się same po kilku sekundach |
| odtwarzanie | ✅ obraz z ZR30 na pełnym ekranie, pasek pokazuje `0:03 / 0:18` |
| powrót po końcu nagrania | ✅ nakładka znikła, pulpit wrócił na listę |

⚠ **Lista potwierdza wadę R1 naocznie:** segmenty mają **18–25 sekund** zamiast
ustawionych dziesięciu minut. To ten sam dryf recordera, który opisuje `CLAUDE.md` R1.

⛔ **Niesprawdzone pokrętłem:** pauza i przewijanie z paska (sterowanie mpv
sprawdzone bezpośrednio przez gniazdo, ale nie ręką na enkoderze).

---

---

## 17. ✅ NAGRYWANIE Z PRZYCISKU (2026-08-29)

Tom: „CVBS obsługuje również nagranie na kartę SD i my chcemy tak samo mieć
możliwość uruchamiania record z przycisku".

### 17.1 Rejestrator CVBS zostaje nietknięty — i to nie jest wybór, tylko fakt

`pi5-camera-recorder` startuje **wyłącznie przyciskiem na GPIO 21**. Sprawdzone
w jego kodzie: `CAMERA_BUTTON_GPIO`, `DebouncedMonostableButton`, a plik stanu
melduje wprost `Oczekiwanie na przycisk`. **Nie ma tam żadnego wejścia programowego**,
więc przycisk w naszym UI wymagałby dopisania go do jego kodu — czego nie robimy
bez potrzeby.

### 17.2 Nasza nagrywarka — ta sama zasada, nasz strumień

`gcs_pulpit/nagrywanie.py`. **Jedno naciśnięcie startuje, drugie zatrzymuje.**

| Gdzie | Co |
|---|---|
| **belka dolna** | `⏺ REC` — zawsze pod ręką; **czerwienieje i liczy czas**, gdy pisze |
| kafelek | `⏺ NAGRYWAJ`, w trakcie zmienia się w `⏹ ZATRZYMAJ NAGRYWANIE` |
| zapis | `/var/lib/gcs/nagrania/<data>.mkv` |
| pojawia się w | ekranie NAGRANIA, źródło **NAGRANE Z PRZYCISKU** |

> ### Dlaczego `-c copy`, a nie przekodowanie
>
> Strumień jest już zakodowany (H.264 720p). Kopiujemy **pakiety**, nie obraz —
> koszt bliski zeru. RPi 5 **nie ma sprzętowego kodera H.264** (poz. R4), więc
> przekodowanie zjadłoby rdzeń i pogorszyło jakość bez żadnego zysku.

> ### Dlaczego `.mkv`, a nie `.mp4`
>
> Nagranie kończy się wtedy, kiedy operator naciśnie przycisk — albo gdy zniknie
> zasilanie. **`.mp4` urwany w pół zapisu jest nie do odtworzenia**, bo indeks trafia
> na koniec pliku. `.mkv` znosi to bez szkody.

⚠ **To osobny odbiorca RTSP** — kamera obsługuje wielu naraz (zmierzone), więc
nagrywanie nie zabiera obrazu ani stacji, ani aparaturze.

### 17.3 ⛔ Pierwsza wersja KŁAMAŁA — i dobrze, że wyszło od razu

Próba wypadła przy **wyłączonym dronie**. `ffmpeg` nie miał się z czym połączyć,
ale **proces żył**, więc przycisk pokazywał `0:01, 0:02, 0:03…` — a plik nie powstawał.
Operator zobaczyłby „nagrywam" i dowiedziałby się prawdy dopiero po locie.

**Poprawione trzema rzeczami:**

| Poprawka | Skutek |
|---|---|
| `-rw_timeout 5 s` | `ffmpeg` nie wisi w nieskończoność |
| stan **potwierdzony pierwszym bajtem w pliku** | do tego czasu przycisk pokazuje `ŁĄCZĘ…`, nie czas |
| próg 8 s | po nim: `Kamera nie odpowiada — nagrywanie przerwane` |

Pusty plik po nieudanej próbie jest **kasowany** — żeby nie zaśmiecał listy nagrań.

### 17.4 Sprawdzone i niesprawdzone

| Próba | Wynik |
|---|---|
| zachowanie przy wyłączonym dronie | ✅ `ŁĄCZĘ…` → po 8 s uczciwy komunikat, zero pustych plików |
| katalog i prawa | ✅ `/var/lib/gcs/nagrania`, właściciel `admin`, bez `sudo` |
| kontrola wolnego miejsca | ✅ próg 1 GB przed startem (karta systemowa jest jedyna, poz. R8) |
| wygląd przycisku i kafelka | ✅ zrzut ekranu |
| **nagranie przy włączonej kamerze** | ⛔ **NIESPRAWDZONE** — dron był wyłączony, `192.168.144.25` nie odpowiadało na ping |

---

### 17.5 ⛔ PRZEBUDOWA: nagrywarka jest WIELOŹRÓDŁOWA

Uwaga Toma: *„nagrywarka ma być do źródeł z IP, czyli cyfrowych kamer, więc nie
możesz zamykać się na jeden typ danych"*. Słusznie — pierwsza wersja miała jeden
adres na sztywno.

> ### ✅ ODKRYCIE, KTÓRE ROZSTRZYGA SPRAWĘ CVBS
>
> **Obraz analogowy już jest strumieniem IP.** `pi5-uas-rtsp` udostępnia go pod
> **`rtsp://127.0.0.1:8554/uav`** (640×480, 30 kl./s, H.264 2000 kb/s) — robi to
> dla ATAK‑a, ale słucha każdego.
>
> Czyli **CVBS i kamera cyfrowa to ten sam rodzaj danych.** Nie trzeba dwóch
> mechanizmów, nie trzeba dotykać rejestratora Toma i **nie trzeba nagrywać ekranu**.

Nagrywarka trzyma **listę źródeł** w `~/.config/gcs/zrodla-obrazu.json`, zasianą
dwoma, które ta maszyna naprawdę ma. Każde ma **własny proces `ffmpeg` i własny
katalog** (`/var/lib/gcs/nagrania/<id>/`), więc jedno padnięte nie psuje pozostałych.

Ekran **`⏺ NAGRYWARKA`**:

```
◀ WSTECZ
⏺ NAGRYWAJ WSZYSTKIE WŁĄCZONE      (albo ⏹ ZATRZYMAJ WSZYSTKIE)
— ŹRÓDŁA — klik startuje albo zatrzymuje
○ ZR30 — głowica (cyfrowa)   ·  gotowe
○ CVBS — tor analogowy       ·  gotowe
— USTAWIENIA ŹRÓDEŁ —
ZR30 · rtsp://…/main.264 · włączone do REC ▸ przełącz
      ▸ usuń źródło ZR30
➕ DODAJ ŹRÓDŁO IP
```

Przycisk `⏺ REC` na belce obejmuje **wszystkie włączone naraz** — dron z dwoma
torami nagrywa się jednym naciśnięciem.

### 17.6 ⛔ Dwie własne usterki znalezione pomiarem

**(a) `-rw_timeout` nie istnieje dla RTSP.** ffmpeg 7 odpowiada
`Option rw_timeout not found` i kończy się natychmiast — czyli **każde nagranie
padało w starcie**. Właściwa opcja to **`-timeout`**. Rozstrzygnięte trzema
przebiegami: bez opcji → nagrywa, z `-rw_timeout` → błąd, z `-timeout` → nagrywa.

**(b) Rozmiar pliku nie nadaje się na dowód, że nagranie idzie.** `ffmpeg` buforuje
zapis, więc przy cichym źródle plik ma **zero bajtów przez kilkanaście sekund** —
i mój próg 8 s ucinał poprawne nagrania. Teraz potwierdzeniem jest **postęp samego
ffmpeg** (`-progress pipe:1`, pole `out_time_ms`), czytany nieblokująco. Przy okazji
licznik pokazuje **czas nagrania**, a nie czas od naciśnięcia przycisku.

### 17.7 Sprawdzone na żywym sprzęcie

| Próba | Wynik |
|---|---|
| **nagranie CVBS** | ✅ **11 sekund zapisane**, `2026-08-29_19-41-54.mkv` |
| ZR30 przy wyłączonym dronie | ✅ uczciwie: `zapis przerwany`, zero pustych plików |
| oba źródła jednym przyciskiem | ✅ startują razem, licznik zbiorczy `0:11 · 1 źr.` |
| ekran nagrywarki | ✅ zrzut ekranu |

⛔ **Niesprawdzone:** nagranie z ZR30 (dron wyłączony) i dodanie źródła przez klawiaturę.

### 17.8 ⚠ DO ROZSTRZYGNIĘCIA — okrągły panel i nagrywanie ekranu

Tom postawił trzy warianty. **Zalecenie: nagrywać źródła, nie ekran**, a na okrągły
panel dodać stronę `NAGRYWANIE`.

| Wariant | Ocena |
|---|---|
| **strona `NAGRYWANIE` na panelu GC9A01** | ✅ **zalecane** — dokładka do łatki, która już tam jest; klik startuje wszystkie źródła, ekran pokazuje czas i liczbę piszących |
| wybór źródła na panelu | ⚠ możliwe, ale przy 240×240 pikselach lista adresów jest nieczytelna — wybór zostawić na dużym ekranie |
| **nagrywanie ekranu po REC** | ⛔ **odradzam** — ekran trzeba **zakodować**, a RPi 5 nie ma kodera H.264 (poz. R4). Zjadłoby to rdzeń, dało gorszy obraz niż oryginał i utrwaliło nakładki UI na nagraniu. Źródło wystarczy **przepisać** — koszt bliski zeru |

---

### 17.9 ✅ STRONA `NAGRYWANIE` NA OKRĄGŁYM PANELU (2026-08-29)

Panel GC9A01 ma teraz **siódmą stronę**. Klik startuje albo zatrzymuje **wszystkie
włączone źródła**, a ekran pokazuje czas i liczbę piszących.

> ### Panel sam nic nie nagrywa — i tak ma zostać
>
> Nagrywarka żyje w pulpicie i **tylko on wie, co się dzieje**. Panel wysyła prośbę
> (`{"typ":"polecenie","co":"nagrywanie"}`), a pulpit **co sekundę melduje stan**
> (`{"cmd":"stan","nagrywa":true,"opis":"0:06 · 1 źr."}`). Dzięki temu okrągły ekran
> pokazuje prawdę, a nie własne domysły — gdyby liczył sam, rozjechałby się przy
> pierwszym źródle, które odmówiło.

⚠ **Działa niezależnie od ogniska pokrętła.** Nie trzeba przekazywać knoba pulpitowi —
nagrywanie włącza się wprost z panelu, tak jak przycisk CVBS.

⚠ **Gdy pulpit nie działa, strona mówi to wprost** (`PULPIT NIE DZIAŁA / NIE MA CZEGO
PYTAĆ`) zamiast udawać gotowość.

**Łatka panelu jest ta sama co poprzednio**, tylko szersza — `zalataj_panel.py`
nadal cofa wszystko jednym `--cofnij`, a kopie `*.przed-gcs` powstają od nowa.

### 17.10 Sprawdzone całą drogą przez interfejs

Uruchomione **klawiszami w działającym pulpicie**, nie wywołaniem w kodzie:

| Krok | Wynik |
|---|---|
| wejście w NAGRYWARKĘ i `NAGRYWAJ WSZYSTKIE` | ✅ |
| wiersz CVBS | ✅ **czerwony, `nagrywa 0:06`** |
| wiersz ZR30 (dron wyłączony) | ✅ został `gotowe` — nie udaje, że pisze |
| nagłówek i belka | ✅ `nagrywa teraz: 1`, przycisk `⏺ 0:06 · 1 źr.` na czerwono |
| zatrzymanie | ✅ plik **26 417 B** zamknięty poprawnie |
| panel po łatce | ✅ `active`, zero błędów, `PAGE_COUNT = 7` |

⛔ **Niesprawdzone:** sama strona `NAGRYWANIE` na okrągłym ekranie — wymaga ręki
na pokrętle. Kod i łącze są sprawdzone z obu stron, ale kliknięcia nikt nie zrobił.

---

---

## 18. ✅ STRONA `SIEC` NA OKRĄGŁYM PANELU (2026-08-29)

Panel ma teraz **ósmą stronę**: LAN, Wi‑Fi i adres WAN. Tak samo jak przy nagrywaniu —
**panel tylko rysuje, dane zbiera pulpit** i melduje je mostem (`{"cmd":"siec",…}`).

| Wiersz | Skąd |
|---|---|
| **LAN** | adres `eth0` z NetworkManagera |
| **WiFi** | radio włączone/wyłączone, a przy połączeniu SSID i moc sygnału |
| **WAN** | adres zewnętrzny + **podpis, skąd pochodzi** |

Ten sam adres WAN dopisał się też do nagłówka ekranu SIEĆ na dużym monitorze.

### 18.1 ⚠ Adres WAN ma DWA znaczenia i nie wolno ich mylić

Router (MikroTik `192.168.88.1`) ma otwarty **wyłącznie SSH** — bez API, bez HTTP
i bez SNMP (sprawdzone skanem portów). Stąd dwie drogi:

| Droga | Co pokazuje | Warunek |
|---|---|---|
| **z routera** | adres **na interfejsie WAN** | klucz maliny dopisany w RouterOS |
| **z zewnątrz** | adres, **pod jakim widzi nas świat** | działający internet |

> ### Dlaczego to nie jest ta sama liczba
>
> Równe → mamy **publiczny adres** i WireGuard ma się gdzie postawić.
> Różne → jesteśmy **za CGNAT** i połączenia przychodzące nie przejdą.
> To jest dokładnie kontrola opisana w `ROUTER_MIKROTIK.md`.
>
> Dopóki klucza nie ma, pokazujemy adres widziany z zewnątrz i **podpisujemy go
> „z zewnątrz"** — zamiast udawać, że znamy stan interfejsu.

### 18.2 Co zrobić, żeby dostać adres wprost z routera

Malina **nie ma jeszcze własnego klucza SSH** (`~/.ssh/` zawiera tylko
`authorized_keys`). Kolejność:

```bash
ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519          # na malinie
cat ~/.ssh/id_ed25519.pub                                  # skopiować do RouterOS
```

W RouterOS: wgrać plik i `/user ssh-keys import public-key-file=... user=admin`.
Potem na malinie `~/.config/gcs/router.json`:

```json
{"host": "192.168.88.1", "uzytkownik": "admin", "klucz": "~/.ssh/id_ed25519"}
```

Od tej chwili strona sama weźmie adres z interfejsu WAN i podpisze go
**„z routera"**. ⚠ Konfiguracja jest **opcjonalna** — bez niej wszystko działa,
tylko z drugiego źródła.

### 18.3 Sprawdzone

| Próba | Wynik |
|---|---|
| odczyt na żywo | ✅ adres LAN, stan Wi-Fi i adres WAN zostały pokazane poprawnie |
| panel po łatce | ✅ `active`, **zero błędów**, `PAGE_COUNT = 8` |
| WAN w nagłówku dużego ekranu | ✅ zrzut ekranu |
| odpytywanie w tle | ✅ sieć co 30 s, adres zewnętrzny co 2 min — interfejs nigdy nie czeka |

⛔ **Niesprawdzone:** sama strona `SIEC` na okrągłym ekranie (wymaga pokrętła)
oraz odczyt z routera (brak klucza).

---

### 18.4 Strona `SIEC` to teraz MENU, nie tablica (2026-08-29)

Pierwsza wersja upychała LAN, WiFi i WAN na jednym ekranie 240×240. Przy trzech
adresach naraz nie da się tego zrobić czytelnie — litery robią się mniejsze od tego,
co warto przeczytać z odległości ręki.

**Teraz jest tak, jak prosił Tom:**

```
strona SIEC          klik      MENU              klik      SZCZEGÓŁ
„WAN · WiFi · LAN"   ────►     > WAN             ────►     WAN
„KLIK: MENU"                     WiFi                      <adres WAN>
                                 LAN                       z zewnątrz
                                                           „KLIK: WSTECZ"
```

Obrót w menu wybiera zakładkę, klik ją otwiera, kolejny klik wraca.
**Kierunek obrotu jest liczony tą samą funkcją co strony panelu**
(`reversed_axis_index`), więc menu kręci się zgodnie z resztą urządzenia.

> ### ⚠ Menu gaśnie samo po 30 sekundach
>
> Bez tego panel zostałby w zakładce na zawsze, a operator przy następnym podejściu
> zobaczyłby **nieaktualny ekran zamiast bieżącego stanu**. To ta sama zasada,
> którą stosują menu ustawień i nagrań w panelu Toma.

Długie wartości (nazwa sieci Wi‑Fi) są skracane wielokropkiem — 240 pikseli nie
przyjmie więcej, a ucięcie w pół słowa jest gorsze od wielokropka.

| Próba | Wynik |
|---|---|
| łatka po przebudowie | ✅ **22 znaczniki `# GCS`**, panel `active` |
| obrót i klik w menu | ✅ wpięte w łańcuch zdarzeń panelu przed zmianą strony |
| dziennik panelu | ✅ **zero błędów** w próbce 20 s |

⛔ **Niesprawdzone:** samo menu na okrągłym ekranie — rysowanie tych stron zaczyna
się dopiero po przekręceniu na nie pokrętłem.

---

### 18.5 W otwartej zakładce obrót przeskakuje do sąsiedniej

Życzenie Toma: po wejściu w `WAN` samo przekręcenie ma przejść do `WiFi` i `LAN`,
bez cofania się do menu. Adresy porównuje się jeden po drugim, więc droga
„zakladka → menu → zakladka" była zbędnym krążeniem.

```
   MENU  ─klik─►  WAN  ─obrót─►  WiFi  ─obrót─►  LAN
                    └──────── klik: wstecz ────────┘
```

⚠ **Zakładki nie zawijają się na krańcach** — z `LAN` obrót dalej nic nie zmienia.
To nie jest przeoczenie: `bounded_index` w panelu Toma **celowo nie zawija**
(*„Przesuń o jedną pozycję bez zawijania na krańcach listy"*), tak samo zachowują
się jego strony, ustawienia i menu VRX. Zawijanie tylko tutaj byłoby jedynym
miejscem na tym wyświetlaczu, które działa inaczej niż reszta.

Podpowiedź na dole zakładki mówi teraz obie rzeczy: `OBROT: KOLEJNA` i `KLIK: WSTECZ`.

| Próba | Wynik |
|---|---|
| łatka po zmianie | ✅ panel `active`, zero błędów w dzienniku |
| przeskok wpięty przed menu i przed zmianą strony | ✅ kolejność w łańcuchu zdarzeń sprawdzona w kodzie |

---

---

## 19. ⛔ „KLIKAM W DRON 15, A PULPIT ODPALA INNE APLIKACJE" — usterka w moim moście

Tom podpiął **stację DRON 15 do tego samego mostu** (`/opt/dron15/server/pokretlo.mjs`).
Od tej chwili pokrętło zaczęło żyć własnym życiem: obrót w aplikacji przesuwał
zaznaczenie na **przykrytym** pulpicie, a klik **uruchamiał kolejne programy**.

### 19.1 Przyczyna — most rozsyłał zdarzenia DO WSZYSTKICH

`rozglos()` wysyłał każdy obrót i klik **każdemu klientowi**. Przy jednym odbiorcy
to nie przeszkadzało. Przy dwóch — obaj reagowali na to samo pokręcenie.

**Naprawione: zdarzenia pokrętła idą do JEDNEGO klienta.**

| Element | Jak działa teraz |
|---|---|
| **kto ma pokrętło** | most trzyma wskaźnik na **konkretne połączenie**, nie na słowo „pulpit" |
| **`{"cmd":"jestem","nazwa":…}`** | klient mówi, kim jest; nasz pulpit przedstawia się jako `pulpit` |
| **własna prawda dla każdego** | właściciel słyszy `ognisko: pulpit`, pozostałi `ognisko: panel` |
| **polecenia z panelu** | adresowane (`rozglos_do("pulpit", …)`) — inaczej nagrywanie ruszyłoby u każdego |

⚠ **Protokół się nie zmienił.** Klient Toma nie musi nic wiedzieć o nazwach —
dostaje `ognisko: panel`, gdy pokrętła nie ma, i `pulpit`, gdy je dostał.
Potwierdzone w dzienniku: `klient-1` przedstawił się jako `pulpit`,
`klient-2` (stacja) wziął ognisko — i tylko on dostaje zdarzenia.

### 19.2 Pilot przykrywał przyciski aplikacji, która pokrętło obsługuje sama

Stacja DRON 15 ma **własny przycisk `POKRĘTŁO`** i własną nawigację. Nasz pasek
pilota był tam podwójnie szkodliwy: zasłaniał jej przyciski i przechwytywał
zdarzenia, których ona potrzebuje.

Wpis aplikacji dostał pole **`"pokretlo": "wlasne"`**. Przy nim pulpit:

- **nie otwiera pilota**,
- **schodzi z drogi** (chowa się),
- **oddaje pokrętło**, żeby aplikacja mogła je wziąć.

### 19.3 Dwie rzeczy przy okazji

**(a) Pulpit chodził w oknie.** `GCS_PELNY_EKRAN=0` zostało w środowisku usługi
po moim debugowaniu i **nadpisywało** ustawienie jednostki. Zdjęte — pulpit jest
znowu pełnoekranowy.

**(b) Pilot otwiera się od razu, nie po 3 sekundach.** Ta przerwa była dziurą:
aplikacja już wchodziła na wierzch, a pokrętło sterowało jeszcze kafelkami.

**(c) Zasłonięty pulpit nie działa po omacku.** Gdy dostanie obrót albo klik,
będąc pod czymś, **tylko wychodzi na wierzch**. Pilota wybiera się świadomie
kafelkiem — inaczej wróciłby problem z 19.2.

### 19.4 Sprawdzone

| Próba | Wynik |
|---|---|
| dwaj klienci na moście | ✅ `klient-1` → `pulpit`, `klient-2` → stacja |
| ognisko po stronie stacji | ✅ `Ognisko pokrętła: klient-2` — pulpit zdarzeń nie dostaje |
| DRON 15 z kafelka | ✅ **bez pilota**, własne przyciski aplikacji odsłonięte |
| pulpit, panel, stacja po zmianach | ✅ wszystkie `active` |

⛔ **Niesprawdzone pokrętłem:** czy obrót w DRON 15 rzeczywiście przestał ruszać
pulpitem. Kod tego pilnuje i dziennik potwierdza właściciela, ale ręką nikt tego
nie przejechał.

---

### 19.5 ⛔ „Nie działa pokrętło po odpaleniu" — druga połowa tej samej usterki

Po naprawie z 19.1 pokrętło po uruchomieniu DRON 15 **umierało**. Przyczyna była
moja i wynikała z półśrodka: przy aplikacji z `pokretlo: wlasne` pulpit wołał
`oddaj_panelowi()`, a to **zabierało pokrętło stacji**, która właśnie je miała.

Dwie poprawki, obie o znaczeniu ogólnym:

**(a) Rezygnować wolno tylko ze SWOJEGO ogniska.** `{"cmd":"ognisko","gdzie":"panel"}`
działa teraz wyłącznie wtedy, gdy nadawca faktycznie trzyma pokrętło. Wcześniej
dowolny klient mógł je zdjąć komuś innemu — i dokładnie to robiłem.

**(b) Nowe `{"cmd":"ognisko","gdzie":"inny"}` — jawne przekazanie sąsiadowi.**
Aplikacja, która obsługuje pokrętło sama, **nie ma jak o nie poprosić**: prosi
o nie jej strona w przeglądarce, a przy stanowisku nie ma myszy. Więc to pulpit
przekazuje je jej w chwili uruchomienia.

⚠ Przekazanie jest dozwolone tylko wtedy, gdy nadawca ma pokrętło albo nie ma go
nikt — żaden klient nie zabierze go temu, kto właśnie z niego korzysta.

| Próba | Wynik |
|---|---|
| uruchomienie DRON 15 z kafelka | ✅ `Ognisko pokrętła: klient-2`, a stacja meldowała `ognisko: pulpit` |
| pulpit po przekazaniu | ✅ nie dostaje zdarzeń, więc nic nie uruchamia |

⚠ **Powrót idzie przez panel, nie przez przytrzymanie.** Skoro pulpit nie ma
pokrętła, nie dostanie też przytrzymania — pokrętło odbiera się stroną
**PULPIT GCS** na okrągłym ekranie. Komunikat na dole pulpitu mówi to wprost.

---

### 19.6 ⛔⛔ CAŁE STEROWANIE ZAMARŁO — i to był błąd konstrukcyjny, nie potknięcie

Po przekazaniu pokrętła stacji **przestało odpowiadać wszystko**: aplikacja,
pulpit i **strony samego panelu**.

**Przyczyna leży w założeniu, które przyjąłem bez zastanowienia:** gdy pokrętło
trzyma klient, panel przekazuje mu **każdy obrót i każdy klik** i sam nie reaguje
na nic. Dopóki klientem był pulpit, który zawsze odpowiadał, nikt tego nie zauważył.
Wystarczył jeden klient, który zdarzeń nie obsłużył — i **nie było żadnej drogi
powrotu**, bo nawet strona `PULPIT GCS` nie miała jak przyjąć kliknięcia.

> ### ⛔ WYJŚCIE AWARYJNE: PRZYTRZYMANIE 1,5 s ZAWSZE ODBIERA POKRĘTŁO
>
> Obsługiwane **u właściciela GPIO**, w `on_switch` panelu — **przed** jakimkolwiek
> przekazaniem. Nie idzie przez most, więc żaden klient nie może tego ani
> przechwycić, ani zablokować, ani zagłodzić.
>
> Progi są rozdzielone świadomie: pulpit liczy przytrzymanie od **0,5 s**
> (powrót z aplikacji), panel odbiera pokrętło od **1,5 s** — więc zwykłe
> przytrzymanie nadal dochodzi do klienta.

Strona `PULPIT GCS` pokazuje teraz dodatkowo **kto trzyma pokrętło**
(`pulpit`, `klient-2`…) i przypomina o geście ratunkowym.

**Wniosek na przyszłość, szerszy niż ta usterka:** przy jednym urządzeniu wejściowym
**każde przekazanie sterowania musi mieć drogę odwrotu niezależną od tego, komu się
je przekazało**. Inaczej pierwszy niereagujący odbiorca unieruchamia całe stanowisko.

| Próba | Wynik |
|---|---|
| panel po łatce | ✅ `active`, zero błędów |
| pulpit i stacja | ✅ `active` |

⛔ **Niesprawdzone:** sam gest ratunkowy — wymaga pokrętła. To jest teraz
**najważniejsza rzecz do przejechania ręką.**

---

### 19.7 ⛔ Dlaczego przekazanie pokrętła stacji NIE MOGŁO zadziałać

Odpowiedź jest w kodzie stacji, nie w domysłach — `server/index.mjs`,
`GET /api/pokretlo`:

```js
pokretlo.trzymajacy = { imie: kto.imie, wyslij };
pokretlo.wezOgnisko();      // ognisko bierze się DOPIERO tutaj
```

Stacja bierze pokrętło **dopiero, gdy przeglądarka otworzy strumień**, a zdarzenia
trafiają do pola `trzymajacy`. **Serwer z ogniskiem, ale bez otwartej strony,
wyrzuca je do kosza.**

Moje „przekaż sąsiadowi" dawało więc ognisko komuś, kto nie miał go komu przekazać —
i **to** było przyczyną zamarcia z 19.6, nie sam mechanizm własności.

> ### Wniosek: nie wolno przekazywać sterowania „w ciemno"
>
> Odbiorca może mieć własne warunki, o których nadawca nic nie wie. Przekazanie
> bez potwierdzenia, że druga strona **jest gotowa je przyjąć**, to wyrzucenie
> sterowania za burtę.

**Co robi pulpit teraz przy aplikacji z `pokretlo: wlasne`:**

| Krok | Dlaczego |
|---|---|
| chowa się | żeby nie zasłaniać aplikacji |
| **nie rusza ogniska** | pokrętło zostaje tam, gdzie było — panel nadal działa |
| **otwiera pasek MYSZY** | bo operatorowi potrzebny jest **wskaźnik**, żeby nacisnąć w aplikacji jej własny przycisk `POKRĘTŁO` |
| zamyka swoje nakładki, gdy traci ognisko | bez pokrętła i tak są martwe, a zasłaniałyby aplikację, która właśnie przejęła sterowanie |

Sprawdzone zrzutem: po uruchomieniu DRON 15 widać **jej własne przyciski**
(`MAPA`, `POKRĘTŁO`, `ODDOKUJ`, `OGLĄDA`, `EKRAN`) **i pasek myszy pod nimi**.

⛔ **Niesprawdzone pokrętłem:** przejechanie kursorem do przycisku `POKRĘTŁO`
i przejęcie sterowania przez aplikację.

---

## 20. ⛔ „UMIERA" — wyjście awaryjne samo zabijało pokrętło (2026-08-29)

Zgłoszenie brzmiało jednym słowem: **„umiera"**. Dziennik pokazał, co to znaczy.

```
File "/opt/pi5setup-full/src/control_panel.py", line 218, in on_switch
    if trzymane >= 1.5 and most is not None and most.ognisko != PANEL:
NameError: name 'PANEL' is not defined
```

**To była moja obsługa długiego przytrzymania — gest ratunkowy.** Łatka
importowała z `gcs_most` tylko `MostPokretla` i `PULPIT`; `PANEL` nie było.
Rzecz, która miała odratować stanowisko, kładła je na amen: wyjątek leciał
w wątku enkodera, a panel jest **jedynym właścicielem GPIO pokrętła**.

### Dlaczego to nie wyszło wcześniej

Składnia była poprawna, moduł wstawał, panel działał godzinami. `NameError`
w Pythonie powstaje **przy wykonaniu**, nie przy imporcie — a ta linia wykonuje
się wyłącznie przy przytrzymaniu ponad 1,5 s. Innymi słowy: kod czekał na
dokładnie ten moment, w którym operator go najbardziej potrzebuje.

### Druga przyczyna — dlaczego awaria była TRWAŁA, a nie dwusekundowa

| Fakt | Skutek |
|---|---|
| proces zakończył się **kodem 0** | dla systemd to poprawne zakończenie |
| usługa miała `Restart=on-failure` | **nie wskrzesił jej** |
| panel to jedyny właściciel GPIO | pokrętło martwe: ani panel, ani pulpit, ani stacja |
| przy stanowisku nie ma myszy | **nie ma czym tego naprawić** |

To jest właściwa lekcja z tego zgłoszenia. Sam błąd był literówką; nieodwracalne
zrobiła go polityka restartu.

### Naprawione

1. **`PANEL` dopisany do importu** w `zalataj_panel.py` (łatka odwracalna, jak reszta).
2. **`narzedzia/kontrola_nazw.py`** — przechodzi cały moduł drzewem AST i wypisuje
   nazwy użyte, a nigdzie niezdefiniowane. Widzi rzadkie gałęzie bez uruchamiania
   i bez klikania: menu sieci, przełączenie nagrywania, wyjście awaryjne. Wpięte
   w `instaluj.sh` jako **etap 0** — instalacja staje, zanim cokolwiek trafi na maszynę.
   Przebieg po naprawie: **wszystkie 18 plików czyste.**
3. **`Restart=always`** dla panelu — dokładka `10-gcs-restart.conf`, plik usługi
   PI5setup zostaje nietknięty.

### Dowód, że wstaje sam

```
$ sudo systemctl kill -s SIGKILL pi5-control-panel
po zabiciu: active
INFO control-panel: Nowy odbiorca pokrętła: klient-1 (razem 1)
INFO control-panel: Odbiorca przedstawił się: pulpit
INFO control-panel: Nowy odbiorca pokrętła: klient-2 (razem 2)
```

Zabity najtwardszym sygnałem, jaki jest — wrócił w 6 s, a pulpit i stacja
podłączyły się z powrotem same, bez udziału człowieka.

### ⚠ Czego to nadal nie dowodzi

Że **samo przytrzymanie działa**. Wiadomo tylko tyle, że nie wywraca już panelu.
Gest wymaga ręki na pokrętle i pozostaje **do sprawdzenia przy stanowisku** —
razem z pozostałymi punktami z sekcji 21.

## 21. ✅ SPOD APLIKACJI NIE MOŻE WYGLĄDAĆ MALINA (2026-08-29)

Zgłoszenie Toma: *„jak odpali się firefox to widać pasek maliny oraz pulpit,
a tak nie może być i przy innych aplikacjach zewnętrznych też nie może być;
może być oczywiście jakaś tapeta, ale nie reszta"*.

### Co dokładnie było widać — ustalone, nie zgadnięte

Nasz pulpit **chował się poprawnie** (`_otworz_pilota` → `_schowaj_pulpit`).
Widoczne były dwa procesy Raspberry Pi OS:

| Proces | Co rysuje |
|---|---|
| `wf-panel-pi` | pasek zadań u góry |
| `pcmanfm --desktop` | pulpit maliny wraz z ikonami z `~/Desktop` (8 pozycji) |

Oba startują z `/etc/xdg/labwc/autostart` **pod `lwrespawn`**, czyli wstają same
po zabiciu. Samo `pkill` nic tu nie daje — najpierw musi zginąć opiekun.

### Trzy zmiany

**1. `rpi/gcs-otoczenie ukryj|przywroc`** — gasi opiekuna, potem proces.
Wpięte w jednostkę: `ExecStartPost=… ukryj`, `ExecStopPost=… przywroc`.
⛔ **Żaden plik systemowy nie jest zmieniany.** Zatrzymanie `gcs-pulpit` oddaje
malinę w stanie fabrycznym — to jest cała droga powrotu.

**2. `gcs_pulpit/tlo.py` — własna warstwa tła.** Skoro pulpit maliny gaśnie,
tapetę musi położyć ktoś inny. Na tej malinie **nie ma `swaybg` ani `wbg`, ani
`mpvpaper`** (sprawdzone), a `gtk4-layer-shell` już mamy pod klawiaturę — koszt
to jedno okno na warstwie `BACKGROUND` i zero nowych pakietów. Bierze tapetę
Toma z PI5setup (`GCS_TAPETA` podmienia bez zmiany kodu), a przy braku pliku
rysuje własne tło, żeby nigdy nie było dziury na kompozytor.

**3. Flaga `"pelny-ekran"` wreszcie coś robi.** Przy okazji wyszło, że pole
istniało w `katalog.py` od początku i **kod nigdy go nie używał** — Firefox
wstawał w małym oknie na środku ekranu. Teraz `gcs_pulpit/okna.py` czeka, aż
okno się pojawi (do 20 s — Waydroid wstaje wolno), i rozciąga je przez
`wlrctl toplevel fullscreen`.

Dopasowanie app_id: najpierw dokładne, potem po zawieraniu — bo Waydroid
uruchamia się poleceniem `waydroid`, a okno melduje się jako
`waydroid.com.atakmap…`. Kiedy zgadywanie nie trafia, w kafelku podaje się
`"okno"` wprost; DRON 15 dostał `"okno": "chrome-"`, bo Chromium w trybie
`--app` melduje się nazwą zaczynającą się od `chrome-`.

✅ **Zmierzone: `wlrctl toplevel fullscreen` USTAWIA stan, nie przełącza.**
Drugie wywołanie na oknie już rozciągniętym niczego nie zepsuło. To była realna
obawa — DRON 15 startuje z `--start-fullscreen`, więc przełącznik zdjąłby mu
pełny ekran.

### Dowód

Kafelek Firefoksa kliknięty **wskaźnikiem, czyli tą samą drogą, którą idzie
pokrętło** (`wlrctl pointer`), nie uruchomieniem z konsoli:

```
$ wlrctl toplevel list
firefox: Restore Session — Mozilla Firefox      <- pełny ekran
chrome-ADRES__-Default: DRON15 — podgląd
                                                <- pl.gcs.pulpit ZNIKNĄŁ
$ ps -eo comm | grep -cE "wf-panel|pcmanfm"
0
```

Na ekranie: Firefox na pełnym ekranie, na dole pasek pilota, ani śladu paska
zadań i pulpitu maliny.

### ⚠ Czego to nie zmienia

Sesja `lightdm` nadal loguje się w standardowy pulpit Raspberry Pi OS —
przełączenie na własną sesję `gcs` zostaje świadomie na koniec. Do tego czasu
pasek i pulpit maliny **wracają za każdym razem, gdy zatrzymasz `gcs-pulpit`**,
i to jest zamierzone: dopóki podmiana sesji nie jest zrobiona, musi istnieć
droga powrotu do zwykłego systemu.

## 22. ✅ WŁASNA SESJA GRAFICZNA — GCS WSTAJE PRZY STARCIE MASZYNY (2026-08-29)

Ostatni odłożony krok z sekcji 1: `lightdm` loguje teraz w pulpit GCS, nie w pulpit
Raspberry Pi OS. Do tego droga powrotu przez SSH i konsola tekstowa w grupie SYSTEM.

### Sesja: `labwc -C`, nie zabijanie procesów

```
labwc-pi:   labwc -m                    <- scala /etc/xdg + ~/.config
gcs-sesja:  labwc -C /etc/gcs/labwc     <- czyta WYŁĄCZNIE nasz katalog
```

To jest cała sztuczka. `/etc/xdg/labwc/autostart` z paskiem `wf-panel-pi` i pulpitem
`pcmanfm --desktop` **w ogóle się nie uruchamia** — nie trzeba go gasić, on nie wstaje.
`gcs-otoczenie` zostaje do pracy nad wyglądem w sesji maliny.

`rc.xml` i `environment` instalator kopiuje z `/etc/xdg/labwc` zamiast pisać własne:
motyw, skróty klawiszowe i ustawienia wejścia mają zostać takie, jak były.

| Plik | Rola |
|---|---|
| `/usr/local/bin/gcs-sesja` | uruchamia kompozytor (wzorowane na `labwc-pi`) |
| `/usr/share/wayland-sessions/gcs.desktop` | sesja widoczna dla `lightdm` |
| `/etc/gcs/labwc/autostart` | układ ekranów, kanshi, start pulpitu |
| `/usr/local/bin/gcs-ui` | przełącznik `nasze` / `malina` / `stan` |

### ⛔ DROGA POWROTU PRZEZ SSH

```bash
sudo gcs-ui malina && sudo systemctl restart lightdm
```

`sshd` nie zależy od sesji graficznej, więc to działa także wtedy, gdy na ekranie
nie ma nic. Restart `lightdm` zrywa sesję na ekranie, ale **nie zrywa SSH**.
Powrót na nasze: `sudo gcs-ui nasze && sudo systemctl restart lightdm`.

Poprzednia sesja jest **zapamiętywana przy pierwszym przełączeniu**
(`/etc/gcs/sesja-poprzednia`), a nie zgadywana. Drugie przełączenie tej pamięci
nie nadpisuje — inaczej droga powrotu prowadziłaby do nas samych.

### ⛔ Dwie dziury w drodze powrotu, obie znalezione pomiarem

**1. Pulpit GCS przeżywał wymianę sesji.** Po `gcs-ui malina` + restart `lightdm`
sesja maliny wstała, a **nasz pulpit dalej zasłaniał ekran**. Powód: to usługa
*użytkownika*, żyje w `user@1000.service`, którego restart `lightdm` nie dotyka.
Naprawione w dwóch miejscach: `gcs-sesja` nie robi już `exec` tylko czeka na koniec
kompozytora i gasi pulpit, a `gcs-ui malina` gasi go **od razu**, nie licząc na restart.

**2. Pasek maliny nie wracał, a `pcmanfm` meldował „Desktop manager is not active".**
Wyścig: sesja maliny uruchamia pasek i pulpit sama, a nasz `ExecStopPost=… przywroc`
robił to drugi raz. `ExecStopPost` usunięty — restart `lightdm` i tak daje świeży,
kompletny pulpit maliny.

### Konsola tekstowa w grupie SYSTEM

Kafelek `KONSOLA` (`lxterminal`, pełny ekran). Pisze się w niej **klawiaturą
pokrętła** — przycisk KLAWIATURA na pasku pilota. Czcionka podniesiona z
`Monospace 10` na `14`, bo domyślna jest na tym ekranie nieczytelna; kopia
oryginału: `~/.config/lxterminal/lxterminal.conf.przed-gcs`.

### Sprawdzone na żywej maszynie — pełny restart, nie sam `lightdm`

```
$ ps -eo args | grep labwc
/usr/bin/labwc -C /etc/gcs/labwc
$ ps -eo comm | grep -cE "wf-panel|pcmanfm"
0
$ systemctl --user is-active gcs-pulpit
active
$ wlrctl toplevel list
pl.gcs.pulpit: GCS
INFO control-panel: Odbiorca przedstawił się: pulpit
```

Po `systemctl reboot` maszyna sama wchodzi w pulpit GCS, pokrętło wraca na most,
panel i stacja DRON 15 wstają jak dotąd. Droga powrotu sprawdzona osobno: pulpit
maliny wrócił kompletny — ikony, tapeta, pasek zadań i zasobnik.

⚠ Pulpit GCS zostaje `disabled` w systemd (uruchamia go nasz autostart, nie
`graphical-session.target`) — dzięki temu w sesji maliny **nie wstaje sam z siebie**.

## 23. Otwarte pytania

| # | Pytanie |
|---|---|
| U1 | Zgoda na dopisek w `control_panel.py` (repo `GSB`)? Bez tego pokrętło nie obsłuży pulpitu — §1.3 |
| U2 | Czy mysz zostaje na stanowisku na stałe? Rozstrzyga wygodę wpisywania haseł — §4.2 |
| U3 | Okrągły wyświetlacz: 1,28″ czy 2,8″? Konfiguracja mówi co innego niż Ty — §1.1 |
| U4 | Rozdzielczość i przekątna monitora HDMI — od tego zależy rozmiar kafelków |
| U5 | Czy panel GC9A01 ma dostać stronę „PULPIT GCS", czy wolisz inny sposób przekazywania ogniska — §1.4 |
