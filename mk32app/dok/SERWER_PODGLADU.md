# Serwer podglądu na RPi 5 — architektura

---

## MAPA — dodana 2026-08-29

Pokazuje **gdzie jest maszyna, skąd wystartowała i którędy ma lecieć**. Wchodzi
klawiszem MAPA na dolnym pasku albo z przełącznika w nagłówku dowolnego panelu.

### Kafelki ciągnie przeglądarka widza, nie stacja

To jest decyzja, nie szczegół implementacji. Stacja stoi w sieci, która nie musi
mieć internetu, a przez łącze radiowe nie ma czego przepychać. Widz siedzi zwykle
tam, gdzie internet jest — i to **jego** przeglądarka pobiera podkład wprost
z OpenStreetMap albo OpenTopoMap.

| | |
|---|---|
| ✅ | stacja nie wozi kafelków, nie potrzebuje internetu, nie jest wąskim gardłem |
| ⚠ | widz bez internetu **nie zobaczy podkładu** — zobaczy znaczniki na pustym tle |

Mapa nigdy nie udaje, że podkład jest: przy pierwszym nieudanym kafelku pisze wprost,
że tej przeglądarce brakuje internetu. Trzy stany, nie dwa — „ładuję", „jest",
„nie ma" (zasada 6 z [UI.md](UI.md)).

**Kokpit na MK32 działa odwrotnie** — tam kafelki leżą na karcie, bo aparatura w polu
internetu nie ma. To dwa różne stanowiska i dwie różne odpowiedzi na to samo pytanie.

### Skąd się biorą trasy

Pobranie trasy z maszyny wymagałoby **wysłania** do niej zapytania, a stacja nie
wysyła do maszyny niczego ([WLADZA.md](WLADZA.md)) i rozgałęźnik w aparaturze jest
jednokierunkowy. Zostają dwie drogi bierne — obie działają:

| Źródło | Kiedy się pojawia | Uwaga |
|---|---|---|
| **plik `.plan` albo `.waypoints`** w `/var/lib/dron15/trasy` | zawsze, także bez drona pod napięciem | droga zaplanowana w [GCS_RPI5.md](GCS_RPI5.md) |
| **podsłuch łącza** | gdy kokpit pobiera albo wysyła trasę | nic nie kosztuje, ale trzeba poczekać na transfer |

Mapa **zawsze pisze, skąd wzięła to, co rysuje**. Trasa na ekranie bez wskazania
źródła to najlepszy sposób, żeby polecieć według nieaktualnego planu.

Nazwa pliku z żądania jest sprawdzana wzorcem i przepuszczana przez `basename` —
`../../etc/passwd` dostaje 404 (sprawdzone).

### Punkt startu

Serwer do tej pory **nie znał** pozycji domu i dlatego na nakładce nie było ani
dystansu do startu, ani namiaru. Teraz dekodujemy `HOME_POSITION` (msgid 242).
Przesunięcia pól sprawdzone wprost w `pymavlink`, nie przepisane z dokumentacji:
MAVLink porządkuje pola na drucie **malejąco po rozmiarze**, a nie w kolejności
deklaracji, i to jest najczęstsze źródło przekłamanych odczytów.

### Ślad liczy widz, nie serwer

Ślad przelotu powstaje **w przeglądarce**, z tego, co i tak przychodzi telemetrią.
Serwer go nie liczy i nie wysyła — doklejanie historii do ramki lecącej 10 razy
na sekundę byłoby marnowaniem łącza. Cena: każdy widz ma własny ślad, od chwili
wejścia. Punkt dokładany dopiero po przesunięciu o 1,5 m, inaczej zawis daje
kilkanaście tysięcy punktów w jednym miejscu.

### Oddokowanie na drugi monitor

Klawisz **ODDOKUJ** przy mapie i na dolnym pasku przy obrazie otwiera osobne okno
(`?okno=mapa`, `?okno=obraz`), które da się przeciągnąć na drugi ekran. Kafelka
wewnątrz jednej karty przeglądarki poza jej krawędź przeciągnąć się nie da — stąd
osobne okno, a nie panel.

Okno **nie dzieli stanu z rodzicem**: zestawia własne SSE albo własne WHEP. Wyszło
prościej i pewniej niż przesyłanie stanu przez `postMessage` — nie ma synchronizacji,
nie ma zamrożonego odczytu, gdy rodzic zamarznie, a zamknięcie jednego okna nie rusza
drugiego. Żeton siedzi we wspólnym `localStorage`, więc okno wchodzi bez pytania.

⚠ **Każde oddokowane okno to osobny widz** — liczy się do limitu widzów i widać je
na liście „kto ogląda". Przy limicie 6 i dwóch oddokowanych oknach jedna osoba zajmuje
trzy miejsca. Przy większej sali limit trzeba podnieść w panelu administratora.

### ✅ Sprawdzone na żywej maszynie 2026-08-29

| Co | Wynik |
|---|---|
| `HOME_POSITION` — dekodowanie | dom `50,3741229 / 19,1900085`, maszyna `50,3741248 / 19,1900047` — **różnica ~0,3 m**, czyli dokładnie tyle, ile powinno być przy dronie stojącym na punkcie startu |
| znacznik maszyny, obrót kursem | `rotate(89,9°)` przy kursie 90,1° z telemetrii |
| znacznik punktu startu | rysuje się |
| odczyt dystansu do domu | **DOM 2 m** |
| kafelki z internetu u widza | 24 z 24 |
| trasa z pliku `.plan` / `.waypoints` | oba formaty, z punktem domu; 2073 z 2073 pikseli w barwie motywu |
| wyjście poza katalog tras | 404 |
| okno oddokowane (mapa i obraz) | własny tytuł, sama treść bez HUD-u, wypełnia okno |

**Ślad przelotu — sprawdzony na symulatorze, na ziemi.** Przy dronie stojącym nie
powstaje ani jeden punkt (próg 1,5 m działa jak należy), więc żeby nie zostawiać tego
na lot, przejechaliśmy to lokalnie: `narzedzia/symulator_telemetrii.py` plus osobne
wystąpienie serwera na porcie 8099, bez dotykania stacji. Wynik: znacznik obraca się
z kursem (199,5° → 114,8°), a **ślad rośnie 850 → 1333 px w 22 s**.

**Zostaje niesprawdzona wyłącznie trasa z podsłuchu łącza** — wymaga, żeby kokpit
pobrał albo wysłał misję.

⚠ **Okno oddokowane nie przyjmuje kodu zaproszenia** — dziedziczy żeton po oknie
głównym przez wspólny `localStorage`. Otwarcie `?okno=mapa` w przeglądarce, która
nigdy nie była na stacji, kończy się komunikatem, a nie ekranem wejścia. Tak ma być,
ale warto wiedzieć: **najpierw wejść normalnie, dopiero potem oddokować**.

### ⛔ Dwie wady znalezione i naprawione przy tej próbie

**Leaflet nie wiedział, że pojemnik urósł.** Mapa zapamiętuje rozmiar przy tworzeniu
i sama go nie sprawdza, a tu pojemnik rośnie zawsze: panel się otwiera, okno bywa
przeciągane na inny monitor, użytkownik zmienia rozmiar. Objaw był mylący, bo mapa
działała: znacznik lądował **przy lewej krawędzi**, a kafelków dociągało się tyle,
ile zmieściłoby się w pierwotnym oknie — zmierzone **dwa zamiast dwudziestu czterech**.
Naprawa: `ResizeObserver` na pojemniku, nie nasłuch na `resize` okna — pojemnik
zmienia rozmiar także wtedy, gdy okno stoi w miejscu.

**Punkt startu zostawał na mapie po utracie łącza.** Pozycja znika sama (serwer
przestaje ją podawać po 10 s), ale dom trzymamy bez kontroli wieku — celowo, bo się
nie starzeje. Cena: przy martwym łączu mapa wyglądała na żywą, mając na sobie
wyłącznie znacznik sprzed awarii. Teraz przy martwym łączu dom jest **przygaszony**,
a mapa pisze wprost, że to odczyt sprzed utraty łącza.

### ⚠ Bez kokpitu na MK32 stacja nie ma telemetrii

To wynika wprost z tego, że **rozgałęźnikiem jest aparatura**: port 19856 otwiera
kokpit, więc gdy aplikacja nie działa, stacja nie ma od kogo brać danych. Zdarzyło
się to w trakcie prób — dron był w sieci, wszystkie węzły odpowiadały na ping,
a telemetrii nie było, bo kokpit był zatrzymany. **Obraz z ZR30 działa niezależnie**
(idzie prosto z kamery po RTSP), więc objaw jest niesymetryczny i przez to mylący:
obraz jest, danych nie ma.

### Barwy warstw idą przez opcje Leafletu, nie przez CSS

Mapa rysuje wektory na **kanwie** (`preferCanvas`), bo ślad potrafi mieć tysiące
punktów i w SVG by się zadławił. Kanwa nie zna arkuszy stylów — `className` na
warstwie nic tam nie robi. Kosztowało to jedno nieudane podejście: kanwa miała treść,
trasa się rysowała, ale w domyślnym niebieskim Leafletu. Barwy czytamy więc ze
zmiennych motywu w JavaScripcie i podajemy jako opcje.


Realizacja etapu **M6** z [PLAN.md](../PLAN.md). Dokument opisuje serwer, który odbiera
obraz z ZR30 i rozdaje go przeglądarkom w sieci, oraz sposób dostępu do niego przez VPN.

**Data:** 2026-08-20
**Stan:** projekt zatwierdzony, kod w budowie
**Zakres zamknięty 2026-08-20:** podgląd w przeglądarkach + monitory HDMI + archiwum.
**Bez Androida i bez planowania misji** — [PLAN.md](../PLAN.md) §10, decyzja 4.
**Zasada nadrzędna bez zmian:** władza nad maszyną zostaje na MK32 ([WLADZA.md](WLADZA.md)).
Serwer podglądu **nie wysyła komend** — widzowie wyłącznie oglądają.

---

## 1. Decyzja główna: nie piszemy tego od zera

W `C:\Soft` działa gotowy system podglądu kamer (projekt NRK): MediaMTX w trybie
RTSP-pull, backend Node/Express, klient React z własnym klientem WHEP, generator
konfiguracji i opisane wdrożenie na ARM. **Przenosimy go, zamiast pisać drugi raz.**

| Element źródłowy | Rola | Zmiana dla DRON15 |
|---|---|---|
| `web/src/whep.js` | klient WebRTC (WHEP) | **bez zmian** — najtrudniejszy element, sprawdzony |
| `web/src/{App,CameraTile,FullscreenView}.jsx` | siatka kafelków, pełny ekran | kosmetyka: 1 źródło zamiast 15 |
| `server/index.mjs` | API `/api/*`, serwowanie frontendu | szkielet zostaje, dochodzi telemetria |
| `server/mediamtx.mjs` | klient API MediaMTX | bez zmian |
| `scripts/cameras-lib.mjs` | generator `mediamtx.yml` | zmiana nazw pojęć na źródła drona |
| `server/onvif.mjs` | wykrywanie kamer ONVIF | **usuwamy** — SIYI nie ma ONVIF |
| `nas-arm/mediamtx_arm64` | binarka pod ARM64 | **gotowa do RPi 5** |
| `DEPLOY-NAS.md` | wdrożenie natywne na ARM | wzorzec dla `start.sh` na RPi |

Dochodzi jedno, czego w NRK nie było: **telemetria MAVLink na WebSocket**
(format już zdefiniowany w [ARCHITEKTURA.md](ARCHITEKTURA.md) §3.1).

---

## 2. Topologia

```
                            ┌──────────── RPi 5 / Raspberry Pi OS ────────────┐
  ZR30 ──/video1 H.265──────┼──► MediaMTX (remux) ──► WHEP :8889 ──┐          │
       └─/video2 (zapas)────┼──►                                   │          │
                            │                                      ├─► :8095  │
  MK32 ──MAVLink UDP────────┼──► serwer Node ──► WebSocket ────────┘  strona  │
                            │                                                 │
                            │    Chromium --kiosk ──► 1-2 monitory HDMI       │
                            └───────────────┬─────────────────────────────────┘
                                            │ WireGuard
                        ┌───────────────────┼───────────────────┐
                     telefon             laptop               MK32
```

Klucz: **MediaMTX nic nie dekoduje ani nie koduje** — przepisuje pakiety RTP.
Koszt procesora jest bliski zeru i nie zależy od kodeka ani liczby widzów.

---

## 3. Dlaczego RPi 5 nie ma tu nic do liczenia

> **FAKT.** VideoCore VII w Raspberry Pi 5 **nie ma bloku H.264** — ani kodera,
> ani dekodera. Został wyłącznie **sprzętowy dekoder HEVC (4Kp60)**.

Konsekwencje, odwrotne do intuicji:

| Operacja | Koszt na RPi 5 |
|---|---|
| remux RTSP → WebRTC (nasz przypadek) | ~0, kodek bez znaczenia |
| dekodowanie **H.265** na monitor HDMI | tanie, sprzętowo |
| dekodowanie **H.264** na monitor HDMI | programowo, ~1 rdzeń na 1080p |
| transkodowanie czegokolwiek | programowy x264 — **wykluczone** |

To obala zapis z [GCS_RPI5.md](GCS_RPI5.md) §2, który traktował H.264 jako wariant
lżejszy dla stacji. Dla **przeglądarek** H.264 jest bezpieczniejszy, dla **monitorów
stacji** — droższy. Te dwie rzeczy trzeba rozdzielić i dlatego kamera ma dwa strumienie.

---

## 4. Kodek: H.265 zostaje, plan B jest przygotowany

Decyzja Toma z 2026-08-20: strumień główny zostaje w **H.265**, bez przestawiania
komendą `0x21`.

Stan obsługi HEVC w WebRTC (**FAKT**, sierpień 2026):

| Przeglądarka | H.265 w WebRTC |
|---|---|
| Chrome / Chromium **136+** | tak, natywnie, bez flag |
| Safari **18+** | tak |
| Android Chrome | tak, gdy układ ma sprzętowy dekoder |
| **Firefox** | **nie** |

Przy założeniu 2–5 widzów na telefonach i laptopach z Chrome/Safari — **działa**.
Firefox jest jedynym wykluczonym i to trzeba wiedzieć zawczasu.

**Plan B, gdyby okazało się to zawodne** — bez transkodowania, bez przebudowy:

```
/video1  H.265        → monitory HDMI stacji (dekoder sprzętowy Pi 5)
/video2  H.264 720p   → przeglądarki (WebRTC, zgodność powszechna)
```

Koszt przejścia: jedna linijka w `zrodla.json`. Struktura serwera przewiduje parę
strumieni od początku (`glowny` / `pomocniczy`), więc nic więcej nie trzeba ruszać.

> **HIPOTEZA do sprawdzenia w M0.** Czy ZR30 pozwala sterować strumieniem podglądu
> komendą `0x21` — instrukcja przypisuje `stream_type = 2` modelom ZT30/ZT6, choć
> adres `/video2` istnieje także tutaj. Odczyt:
> `python narzedzia\siyi_gimbal.py codec --strumien podglad`

---

## 5. Oszczędność pasma radiowego: `sourceOnDemand`

Konfiguracja przeniesiona z NRK zawiera mechanizm, który dla drona jest cenniejszy
niż dla kamer stacjonarnych:

```yaml
sourceOnDemand: yes
sourceOnDemandCloseAfter: 30s
```

MediaMTX łączy się do kamery **dopiero, gdy ktoś ogląda**, i rozłącza po 30 s od
odejścia ostatniego widza. Dla nas znaczy to:

- gdy nikt nie patrzy, przez łącze radiowe **nie idzie nic**,
- nie zajmujemy slotu ZR30 (kamera wydaje maksymalnie **4 strumienie** — FAKT,
  instrukcja rozdz. 2.3.3), więc kokpit MK32 zawsze ma swój,
- N widzów = **jeden** strumień z kamery, niezależnie od N.

---

## 6. Dostęp przez VPN — WireGuard

**DECYZJA 2026-08-20 (PLAN.md §10, decyzja 5):** dostęp do aplikacji wyłącznie przez VPN,
tym samym mechanizmem co w NRK — **serwer WireGuard na routerze**, klienci z aplikacją
WireGuard. Bez Tailscale'a, bez własnego VPS-a.

> ### ⚠ Cena tej decyzji — przyjęta świadomie
>
> Serwer WireGuard na routerze wymaga, żeby ten router był osiągalny z zewnątrz,
> czyli miał **stały, publiczny adres**. To jest spełnione w sieci domowej albo biurowej.
>
> **Nie jest spełnione w polu.** [WIDEO.md](WIDEO.md) §61: karta SIM dostaje adres
> **za CGNAT operatora**, więc do stacji stojącej przy operatorze na 4G **nikt się
> z zewnątrz nie połączy** — połączenie musi wychodzić od niej, a WireGuard na routerze
> tak nie działa.
>
> **Co z tego wynika praktycznie:** dostęp zdalny działa, gdy stacja jest w bazie.
> W terenie ogląda się **na jej własnych monitorach i w hotspocie** (§8), nie przez
> internet. Przy stacji „przy operatorze" (decyzja z §10 PLAN.md) to jest spójne —
> tam i tak pracuje się przy niej, nie zdalnie.
>
> Gdyby dostęp z pola okazał się kiedyś potrzebny, odwrotem jest **Tailscale**
> (przebija CGNAT sam) albo **VPS pośredniczący** — oba opisane w [WIDEO.md](WIDEO.md).
> Zmiana dotyczy wyłącznie warstwy tunelu; serwer podglądu nie wie o niej nic.

### 6.1 Co to upraszcza

Tunel daje **płaską sieć IP** — obie strony widzą się po adresach `10.x`. Dzięki temu
kandydaci ICE typu *host* wystarczają i **nie potrzebujemy ani STUN, ani TURN**.
Komentarz w `whep.js` mówiący, że w sieci lokalnej kandydaci host wystarczają,
pozostaje prawdziwy także w tunelu.

### 6.2 Porty do przepuszczenia w tunelu

| Port | Protokół | Rola |
|---|---|---|
| **8095** | TCP | strona + API + WebSocket telemetrii |
| **8889** | TCP | MediaMTX — sygnalizacja WHEP |
| **8189** | UDP | MediaMTX — media (ICE) |
| 9997 | TCP | API MediaMTX — **tylko lokalnie**, nie wystawiać |

`webrtcLocalUDPAddress: :8189` z konfiguracji NRK jest tu istotną zaletą: media idą
przez **jeden stały port**, a nie losowy zakres, więc reguła w tunelu jest jedna.

### 6.3 Pułapka: MTU

> **Do sprawdzenia przed pierwszym użyciem w terenie.**
> WireGuard ma narzut ok. 60 B, więc domyślne MTU tunelu to zwykle 1420. WebRTC
> trzyma pakiety w okolicy 1200 B i zwykle się mieści — ale pod LTE (MTU ~1430)
> lub PPPoE (1492) tunel może wymagać zejścia do **1280**.
>
> **Objaw jest mylący:** telemetria i strona działają bez zarzutu, a obraz sypie się
> albo nie startuje. Kto tego nie wie, szuka błędu w WebRTC zamiast w MTU.

### 6.4 Adresowanie — rozwiązane po stronie klienta webowego

`App.jsx` wyprowadza adres serwera z adresu, spod którego załadowano stronę:

```js
const WHEP_BASE = `${window.location.protocol}//${window.location.hostname}:8889`;
```

Wpisujesz w przeglądarce adres z tunelu, z LAN-u albo publiczny — **reszta dobiera się
sama**. Żadnych profili, żadnej konfiguracji po stronie widza.

Pole adresu pozostaje potrzebne w dwóch miejscach:

| Gdzie | Dlaczego |
|---|---|
| **MK32 i aplikacje natywne** | nie ładują strony, więc adres muszą dostać jawnie — pole z pamięcią ostatnich wpisów i automatycznym próbowaniem |
| **wariant HTTPS** | strona po `https://` nie zawoła `http://…:8889`; wtedy MediaMTX potrzebuje TLS na 8889 (w NRK są już `auto.crt` / `auto.key`) |

### 6.5 Endpoint podawany ręcznie — decyzja 6, 2026-08-20

Dostęp zdalny nie ma serwera koordynującego. **Stacja pokazuje swój aktualny adres
publiczny, a operator przepisuje go do klienta WireGuard.** To jest DDNS obsługiwany
oczami — i przy stacji stojącej za routerem z publicznym adresem w zupełności wystarcza.

Gdzie się to widzi: **panel administratora, sekcja ŁĄCZA I ADRESY** — endpoint
w postaci `ADRES:51820`, dużą czcionką, do zaznaczenia jednym kliknięciem, a pod spodem
adresy w sieci lokalnej i wiek odczytu.

> **Zmiana wobec pierwotnej wersji (2026-08-20, po decyzji 7):** przycisk DOSTĘP stał
> przez chwilę na dolnym pasku, widoczny dla każdego. Wraz z wprowadzeniem ról przeniesiono
> go do panelu admina — to adres bramy do sieci, widzowi do niczego niepotrzebny,
> a wiedza o nim jest warta więcej niż wygoda. [DOSTEP_I_UZYTKOWNICY.md](DOSTEP_I_UZYTKOWNICY.md) §4.

| Zmienna | Domyślnie | Do czego |
|---|---|---|
| `ADRES_PUBLICZNY` | — | adres wpisany na sztywno; **wyłącza odpytywanie na zewnątrz** |
| `ADRES_PUBLICZNY_URL` | `https://api.ipify.org` | skąd pytamy o adres; `off` wyłącza |
| `ADRES_PUBLICZNY_ODSWIEZ_MS` | 300000 | co ile odświeżać (5 min) |
| `WG_PORT` | 51820 | port nasłuchu WireGuarda w endpoincie |

Odczyt jest buforowany, a ostatni znany adres pokazuje się razem z **wiekiem**, więc
widać, kiedy może być nieaktualny. Nieudane odpytanie nie jest awarią serwera —
to informacja dla operatora.

> **Pułapka, na którą warto uważać.** Zapytanie pokazuje adres, spod którego stacja
> **wychodzi** w świat. Jeśli sama siedzi za komercyjnym VPN-em (NordVPN i podobne),
> zobaczysz adres wyjścia tego VPN-a, a połączenia przychodzące i tak nie zadziałają.
> Adres z ekranu ma sens tylko wtedy, gdy ruch wychodzi wprost przez router,
> na którym stoi WireGuard.

### 6.6 Dlaczego nie serwer koordynujący — rozważone i odłożone

Naturalne rozwinięcie: stacja melduje się na znanym serwerze, klient loguje się tam,
dostaje endpoint i łączy się **bezpośrednio**. To jest poprawny wzorzec — dokładnie tak
działa Tailscale, NetBird i headscale. Odłożony, bo napisanie go samemu to osobny projekt,
a trzy rzeczy w nim nie są oczywiste:

1. **Sam publiczny adres nie wystarcza za CGNAT.** Trzeba adresu i portu **zaobserwowanego
   przez serwer** na tym samym sockecie, na którym siedzi WireGuard, plus **jednoczesnego**
   strzału z obu stron (*UDP hole punching*). Numer portu wybiera NAT operatora, nie my.
2. **Przy symetrycznym NAT punching zawodzi** — dla każdego celu przydzielany jest inny port.
   Wtedy potrzebny jest **przekaźnik**, czyli serwer musi umieć też przenosić ruch.
3. **Zamknięcie tunelu samo z siebie niczego nie odbiera.** Klient trzyma klucze
   w konfiguracji i wróci bez pytania kogokolwiek o zgodę. Warunek „bez autoryzacji
   nie ma wejścia" wymaga **kluczy z terminem ważności** i stacji dodającej peera dopiero
   po potwierdzeniu — czyli warstwy sterującej nad WireGuardem.

Gdyby to kiedyś było potrzebne, bierzemy **gotowe**: NetBird albo headscale na tanim VPS-ie.
Nie piszemy tego sami.

---

## 7. Android na RPi 5 — SKREŚLONY 2026-08-20

> ### ⛔ Waydroid wypada z planu
>
> Wcześniej zapisano tu: „Raspberry Pi OS jako podstawa, Android w kontenerze Waydroid",
> żeby dało się uruchomić QGroundControla (ma pakiet na Androida; oficjalnego builda
> na Linuksa ARM64 nie ma). **Decyzja 4 z 2026-08-20 to skreśla.**
>
> **Powód — koszt był nieproporcjonalny do zysku.** Waydroid na Pi 5 wymaga
> `kernel=kernel8.img` w `config.txt`, co wymusza **strony pamięci 4 KB zamiast
> domyślnych 16 KB i spowalnia cały system**, a przy tym pozostawiało otwarte pytanie,
> czy sprzętowy dekoder HEVC — jedyny, jaki ten układ ma (§3) — przeżyje tę zmianę.
> Ryzykowaliśmy więc **rdzeń stacji dla funkcji pobocznej**.
>
> **Czym zastąpione:** QGroundControl i Mission Planner uruchamia się na **zwykłym
> komputerze** — tam mają natywne buildy, mysz, klawiaturę i duży ekran. Trasy wymienia
> się plikiem `.plan`, bo to standardowy format MAVLink. Stacja robi to, do czego
> jest naprawdę potrzebna: **odbiera obraz, rozdaje go i wyświetla na monitorach.**

**Co stacja pokazuje na własnych monitorach HDMI:** ten sam klient webowy co widzowie,
w Chromium w trybie kiosku. RPi 5 ma dwa wyjścia micro-HDMI, a strumień główny jest
w H.265 — czyli w jedynym formacie, który ten układ dekoduje **sprzętowo** (§3).
Dwa monitory 1080p to dla niego zadanie bez wysiłku.

---

## 8. Rozdzielenie sieci — efekt uboczny wart odnotowania

Jeśli RPi 5 postawi **własny hotspot Wi-Fi** dla widzów, a w sieć pokładową wejdzie
Ethernetem, to widzowie **nie mają dostępu do `192.168.144.0/24`**.

To realizuje **wariant B** z [WLADZA.md](WLADZA.md) §9 — pełną egzekwowalność zasady
„władza zostaje na MK32" — **bez** kosztu, który tam opisano (przekazywania obrazu
przez MK32). Widz nie ma jak sięgnąć do portu `19856` ani do głowicy, bo nie jest
w tej samej sieci.

Dodatkowa korzyść: adres serwera w hotspocie jest **stały** (np. `10.42.0.1`),
więc w terenie bez infrastruktury nikt nie musi niczego szukać.

---

## 9. Bezpieczeństwo

Stack NRK **nie miał logowania** — `DEPLOY-NAS.md` mówi wprost, żeby używać go
w sieci lokalnej lub przez VPN. Bariera stała na WireGuardzie, nie w aplikacji.

**Od 2026-08-20 doszła druga warstwa: tożsamość** — zaproszenia, role, odcinanie widzów,
dziennik. Pełny opis: [DOSTEP_I_UZYTKOWNICY.md](DOSTEP_I_UZYTKOWNICY.md).
**Nie zastępuje ona tunelu, tylko go uzupełnia.**

Świadome konsekwencje:

- **nadal nie wystawiać portu 8095 do internetu** — logowanie nie jest do tego pretekstem;
  strona chodzi po `http` i żeton bywa w adresie (tamże §7),
- kto jest w tunelu, ten dostanie stronę — ale obrazu i telemetrii nie zobaczy
  bez ważnego zaproszenia,
- serwer podglądu **nie przyjmuje komend**, więc nawet nieuprawniony widz nic nie zrobi
  maszynie; najgorsze, co może, to oglądać. Dotyczy to również **administratora stacji**:
  jego panel zarządza dostępem, nie dronem.

---

## 10. Co pozostaje otwarte

| # | Sprawa | Jak rozstrzygnąć |
|---|---|---|
| 1 | Czy H.265 przez WHEP działa u realnych widzów | podmienić `source` w istniejącym `mediamtx.yml` na źródło H.265 i otworzyć stronę — **nie wymaga drona** |
| 2 | Co ZR30 nadaje na `/video2` i czy `0x21` to zmienia | `siyi_gimbal.py codec --strumien podglad` — wymaga drona |
| 3 | Ile pasma zostaje w radiu MK32 przy 2 strumieniach | pomiar M0 — decyduje, czy kokpit ma brać obraz od stacji |
| 4 | MTU tunelu WireGuard | test obrazu przez tunel przed wyjazdem |
| ~~5~~ | ~~Czy Waydroid wstaje na Pi 5~~ | **nieaktualne** — Waydroid skreślony decyzją 4, §7 |
| 6 | Czy MK32 ma być klientem tego serwera | dziś kokpit jest samodzielny ([PLAN.md](../PLAN.md) §1); podłączenie go do stacji to zmiana zasady, nie drobiazg |
| 7 | Czy Chromium w kiosku na dwóch monitorach 1080p wyrabia z dekodowaniem H.265 sprzętowo | **nie wymaga drona** — do zrobienia od razu, tym samym testem co pozycja 1 |
