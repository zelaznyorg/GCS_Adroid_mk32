# Podgląd na telefonie

Aplikacja na telefon dla **widza**: obraz z ZR30, telemetria i ostrzeżenia ze stacji
podglądu. **Nie steruje maszyną** — ani jednego przycisku wysyłającego cokolwiek do
drona, tak samo jak reszta serwera podglądu ([WLADZA.md](WLADZA.md)).

Zakres ustalony 2026-08-23. Dane idą **przez stację**, nie wprost z sieci pokładowej —
telefon jest zwykłym widzem, z zaproszeniem, rolą i możliwością odcięcia
([DOSTEP_I_UZYTKOWNICY.md](DOSTEP_I_UZYTKOWNICY.md)).

---

## 1. Dlaczego to nie jest osobna aplikacja natywna

Serwer podglądu już wydaje obraz przez WHEP i telemetrię przez SSE, a przeglądarka
telefonu umie oba. Napisanie drugiego klienta w Kotlinie znaczyłoby: drugi dekoder
H.265, druga implementacja WebRTC, drugi zestaw ostrzeżeń do utrzymania w zgodzie
z serwerem — i **drugie miejsce, w którym coś może pokazać nieaktualną liczbę**.

Zamiast tego ta sama strona dostaje układ na wąski ekran i staje się instalowalna:
ikona na pulpicie, własne okno bez paska adresu, powłoka aplikacji zapamiętana
na telefonie. Dla użytkownika to jest aplikacja. Dla nas to jeden klient, nie dwa.

> **Kokpit na MK32 to co innego.** Tamten steruje, jest samodzielny i nie potrzebuje
> stacji ([PLAN.md](../PLAN.md) §1). Telefon nie wchodzi mu w drogę.

---

## 2. Jak to zainstalować na telefonie

Wejść przeglądarką pod adres stacji z linkiem zaproszenia, a potem:

| System | Co zrobić |
|---|---|
| **Android / Chrome** | ⋮ → *Dodaj do ekranu głównego* albo *Zainstaluj aplikację* |
| **iPhone / Safari** | ikona udostępniania → *Do ekranu początkowego* |

Podpowiedź *Zainstaluj aplikację* pojawia się **tylko po HTTPS** — patrz §4.
Po zwykłym `http://` zostaje ręczne *Dodaj do ekranu głównego*: na iPhonie i tak
daje okno na pełnym ekranie (Safari czyta `apple-mobile-web-app-capable`), na
Androidzie skrót otworzy się w przeglądarce z paskiem adresu.

Zaproszenie i żeton siedzą w pamięci przeglądarki, więc apka z pulpitu wchodzi
od razu na obraz. Wyczyszczenie danych strony = trzeba użyć linku ponownie.

---

## 2a. Kod autoryzacji — jak telefon trafia do stacji

**Problem, który to rozwiązuje.** Do 2026-08-23 kod zaproszenia mówił wyłącznie
*kim jesteś*, nigdy *gdzie jest stacja*. Nie przeszkadzało to, dopóki stronę serwowała
sama stacja: żeby zobaczyć ekran wejścia, trzeba było już do niej dosięgnąć, więc adres
był z definicji znany. Na telefonie to się rozjeżdża — **aplikacja dodana na pulpit
jest przypięta do adresu, spod którego ją zainstalowano**, a adres stacji się zmienia:
raz LAN, raz tunel WireGuard, raz adres publiczny spod CGNAT przepisywany ręcznie
([SERWER_PODGLADU.md](SERWER_PODGLADU.md) §6.5). Ikona na pulpicie zostawała martwa,
a sam kod nie miał jak pomóc.

Od 2026-08-23 klient ma pojęcie **„która stacja"**: adres jest osobnym, zapamiętywanym
stanem, a **żeton jest przypisany do adresu**, nie do przeglądarki.

### Trzy drogi wejścia

| Droga | Co dostaje widz | Kiedy |
|---|---|---|
| **kod połączeniowy** `D15-…` | jeden ciąg: **kod + adres stacji** | aplikacja już na pulpicie, stacja pod nowym adresem |
| link `http://adres:8095/#z=KOD` | jak dotąd — wchodzi od razu | widz ma drogę do stacji i klika w link |
| zwykły kod + pole adresu | kod osobno, adres wpisywany ręcznie | gdy link rozjechał się w komunikatorze |

**Kod połączeniowy świadomie NIE jest adresem URL.** Komunikatory robią z linków
podglądy i łamią je w połowie; ciąg `D15-…` przechodzi przez nie bez zmian. Postać:
`D15-` + base64url z `adres|kod`. Wydaje go panel **ADMIN → ZAPROSZENIA**, obok linku
i samego kodu — z tym samym zastrzeżeniem co link: adres bierze się z tego, **pod jakim
adresem administrator ma otwarty panel**, więc panel na `localhost` wyprodukuje kod
działający tylko na tej maszynie. Panel mówi o tym wprost.

### Powrót bez kodu

Żeton siedzi pod kluczem adresu, więc **powrót do stacji, w której już byłeś, nie wymaga
kodu drugi raz**. Ekran wejścia pokazuje listę „stacje, w których już byłeś" —
dotknięcie adresu wchodzi od razu, `ZAPOMNIJ` kasuje adres razem z żetonem.
Pamiętamy 8 ostatnich; przy zmianie adresu tunelu lista nie puchnie w nieskończoność.

### Co to znaczy dla serwera

Wywołanie na adres inny niż strona jest **zapytaniem międzyźródłowym**, więc serwer
odsyła nagłówki CORS dla `/api/*` (`server/index.mjs`). MediaMTX miał je już wcześniej
(`webrtcAllowOrigins: ['*']` w generowanym `mediamtx.yml`).

> ### ⚠ CORS TO NIE JEST OTWARCIE SERWERA NA ŚWIAT
>
> Nagłówki CORS nikogo nie wpuszczają — mówią tylko przeglądarce, że wolno jej pokazać
> odpowiedź skryptowi z innego źródła. **Barierą pozostaje WireGuard**
> ([SERWER_PODGLADU.md](SERWER_PODGLADU.md) §9): kto nie ma drogi sieciowej do stacji,
> nie dostanie niczego niezależnie od tych nagłówków, a kto ma — i tak potrzebuje
> ważnego żetonu. **Portu 8095 nadal nie wystawiać do internetu.**
>
> Odbijamy źródło zamiast `*`, a `Access-Control-Allow-Credentials` świadomie
> **nie** wysyłamy: ciasteczek nie używamy, żeton jedzie nagłówkiem `Authorization`,
> więc odbicie dowolnego źródła nie otwiera drogi do sesji przeglądarki widza.

### Ograniczenie, które zostaje

Kod połączeniowy podaje adres, ale **nie zestawia tunelu**. Jeśli stacja jest osiągalna
tylko przez WireGuard, telefon musi mieć ten tunel podniesiony — inaczej dostanie
„Stacja nie odpowiada". To jest ta sama granica co dotąd i nie da się jej przesunąć
kodem: adres bez drogi sieciowej jest bezużyteczny.

---

## 3. Wygląd — wariant D, ten sam co w kokpicie

**Od 2026-08-23 podgląd mówi tym samym językiem wizualnym co aparatura.** Do tej pory
klient webowy stał na starym systemie 2.0 (tafle z pełnymi ramkami w rogach, własna
paleta), a kokpit MK32 przeszedł na wariant D (dok/UI.md §9). Dwa ekrany tego samego
systemu wyglądały jak dwa różne produkty.

Pięć reguł, które przyszły razem z wariantem D:

1. **obraz dostaje całą szybę**, interfejs pływa nad nim — nie zabiera mu paska szerokości,
2. **pas górny i pasek telemetrii leżą na przejściu tonalnym**, bez linii odcinającej,
3. **tafle mają znaczniki narożne** (10 px kreski w rogu) zamiast pełnych ramek,
4. **jedna wartość na ekran jest ogromna** (26 px — wysokość), reszta duża (20 px);
   cztery równe liczby to tablica przyrządów, a nie HUD,
5. **kolor wyłącznie ze znaczeniem** — bursztyn to uwaga, czerwień blokada, cyan maszyna;
   nigdy sam kolor, zawsze też znak i słowo.

Barwy i wymiary są **przepisane z `ui/Motyw.kt` co do liczby** do `serwer/web/src/Motyw.css`.
Gdy zmienia się jeden, trzeba zmienić drugi — inaczej telefon i aparatura zaczną się rozjeżdżać.

### Co ten ekran ma, a czego nie ma

| Element wariantu D | W podglądzie | Dlaczego |
|---|---|---|
| pas górny 28 px | **jest** | tryb, uzbrojenie, słupek baterii, GNSS, stan łącza |
| taśma kursu 460 × 20, zakres 60° | **jest** | z liczbą kursu w taflce przed taśmą |
| karta horyzontu 212 × 150 | **jest** | z `postawa.roll_deg` / `pitch_deg` |
| pasek telemetrii 78 px | **jest** | WYS ogromna, PRĘDKOŚĆ i WZN duże |
| banery ostrzeżeń | **jest** | liczy je serwer, nie klient — każdy widz widzi to samo |
| **pole władzy** | **inne** | mówi **PODGLĄD**, nie „STERUJESZ TY" — ten klient nie steruje |
| **klawisze FOTO · REC · LĄDUJ · RTL** | **NIE MA i nie będzie** | dok/WLADZA.md; w ich miejscu stoją klawisze widza |
| karta mapy | nie ma | podgląd nie wozi kafelków — te siedzą na karcie MK32 |
| DOM (dystans do startu), czas lotu, namiar na dom | nie ma | serwer nie zna pozycji domu ani chwili uzbrojenia (ARCHITEKTURA.md §3.1) |

Ostatni wiersz jest ważny metodycznie: **tych wartości nie da się wyliczyć uczciwie**
z tego, co przychodzi po SSE. Liczenie czasu lotu od chwili wejścia widza dałoby liczbę
wyglądającą na prawdziwą i nieprawdziwą — a to dokładnie ten błąd, przed którym broni
zasada 6. Wracają dopiero wtedy, gdy serwer zacznie je wysyłać.

### Reguła wieku danych

Jeden wiek dla całej nakładki, liczony z `lacze.sekund_od_heartbeatu` — tak samo jak
`stan.wiekTelemetriiS()` w kokpicie. Powyżej **2 s** wszystkie liczby przygasają
(`--wygasly`), powyżej **10 s** serwer przysyła już `null` i pokazujemy kreski.
Drugiego progu klient nie liczy: robi to `_swieze()` w `server/telemetria.mjs`.

### Domyślny strumień na telefonie

Telefon startuje na **strumieniu pomocniczym ZR30 (`/video2`)**, biurko na głównym.
Sub-strumień jest lżejszy dla łącza LTE i zgodniejszy z dekoderami telefonów, a główny
zostaje w H.265 (decyzja z 2026-08-20, [SERWER_PODGLADU.md](SERWER_PODGLADU.md) §4).
Rozpoznanie jest jednorazowe, przy pierwszym renderze — obrót telefonu nie ma prawa
przełączyć widzowi obrazu pod palcami. Przełącznik jest w klawiszu **GŁÓWNY / POMOC.**

---

## 3a. Co zmienia się na wąskim ekranie

Obraz z ZR30 jest 16:9. Wpisany w telefon trzymany pionowo zajmuje ok. 30 %
wysokości, a reszta ekranu i tak byłaby czernią, na której trzeba położyć dane.
**Gramatyka wariantu D zostaje bez zmian** — te same barwy, te same tafle ze znacznikami
narożnymi, ta sama hierarchia liczb. Zmienia się jedno: nakładka schodzi z obrazu
i staje się biegiem strony.

```
 pion (telefon w ręce)              poziom / tryb kinowy / biurko
 ┌─────────────────────┐            ┌──────────────────────────────────┐
 │ALTHOLD ●UZBR  PODGL.│            │ALTHOLD ●UZBR 24.1V ⌁18   PODGLĄD │
 │                     │            │      272 ├──┴──┬──┴──┤   ┌──────┐ │
 │     OBRAZ  16:9     │            │                          │ MAPA │ │
 │                     │            │      ⚠ BATERIA NISKA     ├──────┤ │
 ├─────────────────────┤            │                          │HORYZ.│ │
 │ 272 ├──┴──┬──┴──┤   │  ← kurs    │        O B R A Z         └──────┘ │
 │  ⚠ BATERIA NISKA    │            │                                   │
 │            ┌──────┐ │            │                                   │
 │            │HORYZ.│ │            │ WYS   PRĘDK  WZN     [klawisze]   │
 │            └──────┘ │            │12.0m  1.1m/s +0.3m/s              │
 │                     │            └──────────────────────────────────┘
 │ WYS   PRĘDK   WZN   │
 │12.0m  1.1m/s +0.3   │              (karty MAPA nie ma w podglądzie —
 │ obraz ● ekran ●     │               podgląd nie wozi kafelków)
 │[klaw][klaw][klaw]   │
 │[klaw][klaw]         │
 └─────────────────────┘
```

Próg przełączenia: `max-width: 820px` **i** orientacja pionowa. Obrót telefonu
w poziom wraca do nakładki sam, bez żadnego przycisku.

**Pas górny zostaje na obrazie także w pionie** — ma 28 px i to on niesie tryb
i uzbrojenie. Przy 390 px szerokości nie mieści wszystkiego, więc **lewa grupa
przewija się palcem i ma wygaszenie przy krawędzi**, a prawa (pole władzy) jest
nieściśliwa. Ucięte ma czytać się jako „jest tego więcej", nie jako usterka.
Diody stanu przeglądarki zeszły do paska telemetrii — w pasie wypychały „UZBROJONY",
czyli akurat to, co musi być widoczne zawsze.

Poza tym:

- **cele dotykowe 64 px** w pionie (było 40) — [UI.md](UI.md) §2, obsługa w rękawicach,
- **marginesy bezpieczne** (`env(safe-area-inset-*)`) — bez nich dolny rząd przycisków
  wchodzi pod kreskę nawigacji telefonu i połowa dotknięć trafia w system,
- **`user-scalable=no`** — szczypnięcie dwoma palcami powiększało stronę zamiast obrazu
  i nie było jak wrócić,
- **PEŁNY EKRAN** — pełny ekran plus obrót w poziom jednym dotknięciem. Przycisku nie ma
  na iPhonie, bo iOS nie daje pełnego ekranu zwykłym elementom; tam wystarczy obrócić telefon.

---

## 4. ⚠ Ograniczenie: stacja chodzi po `http://`

To nie jest przeoczenie, tylko konsekwencja decyzji z
[SERWER_PODGLADU.md](SERWER_PODGLADU.md) §9 — bariera stoi na WireGuardzie, nie w TLS.
Przeglądarka odmawia jednak części funkcji poza HTTPS i localhostem:

| Funkcja | Po `http://` | Po `https://` |
|---|---|---|
| obraz, telemetria, cały układ | **działa** | działa |
| dodanie na pulpit (ręczne) | działa | działa |
| podpowiedź „Zainstaluj aplikację" (Android) | **nie ma** | jest |
| własne okno bez paska adresu (Android) | **nie ma** | jest |
| własne okno na iPhonie | działa | działa |
| powłoka zapamiętana offline (service worker) | **nie wstaje** | wstaje |
| ekran nie gaśnie (Screen Wake Lock) | **niedostępne** | działa |

Aplikacja **wie o tym i to pokazuje** zamiast milczeć: dioda `EKRAN` w pasku dolnym
świeci na czerwono z wyjaśnieniem, a rejestracja service workera jest wprost odcięta
warunkiem `window.isSecureContext` w `main.jsx` — inaczej w konsoli telefonu leżałby
w polu błąd wyglądający na awarię, którą nie jest.

**Obejście na dziś:** ustawić w telefonie dłuższy czas wygaszania ekranu.

**Domknięcie:** TLS na stacji. Wymaga dwóch rzeczy naraz — certyfikatu dla strony
(port 8095) **i** TLS na MediaMTX (port 8889), bo strona po `https://` nie zawoła
`http://…:8889` ([SERWER_PODGLADU.md](SERWER_PODGLADU.md) §6.4). W repozytorium leżą
już `serwer/auto.crt` i `serwer/auto.key`, dziś nieużywane. Certyfikat własnoręczny
załatwia MediaMTX, ale **service workera nie** — Chrome odmawia rejestracji przy
niezaufanym certyfikacie, dopóki nie wgra się go do magazynu telefonu.
Zadanie: [TODO.md](../TODO.md) poz. 6.1.

---

## 5. Czego service worker NIE robi

Trzyma **wyłącznie powłokę**: HTML, JS, CSS, ikony, manifest. Ani telemetria, ani
obraz, ani `/api/*` nie są buforowane i nie mogą być.

Powód jest ten sam co zasada 6 z [UI.md](UI.md): *zamrożona liczba jest gorsza niż
brak liczby*. Service worker podający wysokość sprzed dwóch minut z pamięci podręcznej
byłby dokładnie tym błędem — z tą różnicą, że **niewidocznym**, bo strona wyglądałaby
na żywą. Odcięcie jest wprost w `public/sw.js`, w funkcji `omijamy()`.

Nawigacja idzie **najpierw do sieci**, pamięć jest planem awaryjnym. Odwrotnie byłoby
szybciej, ale telefon trzymałby starą wersję aplikacji do czasu wyczyszczenia danych.
Serwer wydaje `sw.js` i manifest z `Cache-Control: no-cache` (`server/index.mjs`) —
zbuforowany service worker potrafi się okopać na tygodnie i żadne odświeżenie strony
tego nie ruszy, bo to właśnie on odpowiada na zapytania.

---

## 6. Pliki

| Plik | Co robi |
|---|---|
| `serwer/web/public/manifest.webmanifest` | nazwa, ikony, tryb okna, kolory |
| `serwer/web/public/sw.js` | powłoka offline — patrz §5 |
| `serwer/web/public/ikona-*.png`, `favicon.svg` | ikony; generuje `narzedzia/ikony_pwa.py` |
| `serwer/web/index.html` | manifest, `theme-color`, znaczniki iOS, viewport |
| `serwer/web/src/main.jsx` | rejestracja service workera pod warunkiem `isSecureContext` |
| `serwer/web/src/useEkran.js` | blokada wygaszania + tryb kinowy |
| `serwer/web/src/polaczenie.js` | **która stacja i czyj żeton** — adres, pamięć stacji, kodek kodu połączeniowego |
| `serwer/web/src/sesja.js` | wywołania API pod adres stacji, wymiana kodu na żeton |
| `serwer/web/src/Wejscie.jsx` | ekran wejścia: kod + adres + lista znanych stacji |
| `serwer/web/src/Motyw.css` | **barwy, wymiary, kroje, tafla** — przepisane z `ui/Motyw.kt` |
| `serwer/web/src/Hud.css` | rozkład nakładki wariantu D + jej wersja na telefon w pionie |
| `serwer/web/src/Osd.jsx` | składa nakładkę (= `ui/Kokpit.kt`) |
| `serwer/web/src/PasGorny.jsx` | pas górny 28 px (= `PasGorny` z `ui/Elementy.kt`) |
| `serwer/web/src/TasmaKursu.jsx` | taśma kursu 460 × 20 (= `TasmaKursu`) |
| `serwer/web/src/KartaHoryzontu.jsx` | karta horyzontu 212 × 150 (= `ui/Karty.kt`) |
| `serwer/web/src/PasekTelemetrii.jsx` | dolny rząd liczb (= `PasekTelemetrii`) |
| `serwer/web/src/Banery.jsx` | banery ostrzeżeń (= `Baner`) |
| `serwer/web/src/Ikony.jsx` | piktogramy klawiszy (= `ui/Ikony.kt`) |
| `serwer/web/src/App.css` | rama ekranu, zasłona i panele — **już nie nakładka** |
| `serwer/server/index.mjs` | nagłówki `no-cache` dla `sw.js` i manifestu |

Ikony rysuje się ponownie po zmianie kolorów lub sylwetki:

```
python narzedzia\ikony_pwa.py
cd serwer\web && npm run build
```

Sylwetka na ikonie to **Quad X, cztery ramiona** — zgodnie z korektą `FRAME_CLASS`
z 2026-08-15 (`..\..\CLAUDE.md` §1), a nie osiem z wcześniejszej dokumentacji.

---

## 7. Co sprawdzono, a czego nie

Sprawdzone 2026-08-23 pomiarem geometrii w przeglądarce, na symulatorze telemetrii
(`--scenariusz niskie_napiecie`, żeby zobaczyć też pasek ostrzeżenia):

| Rozmiar | Wynik |
|---|---|
| 1280 × 800 (biurko) | pas górny 28, taśma **dokładnie 460 × 20**, karta 212 × 150 w narożniku, pasek telemetrii 78 — zgodnie z §9 |
| 390 × 844 (telefon, pion) | obraz 390 × 219, taśma i karta pod nim, pasek 253 px, **bez przewijania**, nic nie ucięte |
| 800 × 500 (okno wąskie) | pole władzy „PODGLĄD" zostaje widoczne, ustępuje środek pasa |

Telemetria dochodzi i wyświetla się w obu układach. Sprawdzone stany:

| Stan | Co widać |
|---|---|
| lot normalny | `ALTHOLD ● UZBROJONY 24.1 V ⌁18 hdop 0.70`, WYS/PRĘDKOŚĆ/WZN, horyzont `prz +1° poch -1°` |
| niskie napięcie | baner `⚠ BATERIA NISKA 21.5 V` pod taśmą, napięcie w pasie na czerwono, bez kolizji z kartą |
| **zerwana telemetria** | baner `⛔ BRAK TELEMETRII`, wszystkie liczby na `——` i wygaszone, pas na kreskach, łącze `CISZA`, karta horyzontu na `———` |

Manifest i `sw.js` serwer wydaje z poprawnym typem zawartości i `Cache-Control: no-cache`.

**Droga „kod → połączenie" sprawdzona na dwóch serwerach naraz** (stacja na `:8095`,
aplikacja podana z osobnego adresu `:8199`, czyli dokładnie przypadek telefonu
z aplikacją na pulpicie):

| Przypadek | Wynik |
|---|---|
| kod połączeniowy `D15-…` wklejony w aplikacji pod obcym adresem | pole adresu znika, wchodzi, telemetria płynie z `:8095` |
| przeładowanie aplikacji | wraca bez kodu, żeton zapamiętany pod adresem stacji |
| dotknięcie znanej stacji na liście | wchodzi od razu, bez kodu |
| zwykły kod + adres wpisany jako `localhost:8095` (bez protokołu) | adres znormalizowany, wchodzi |
| stacja pod martwym adresem | „Stacja nie odpowiada pod zapamiętanym adresem" + lista znanych stacji |
| **klasyczna droga** — link `#z=KOD` prosto ze stacji | bez zmian: wchodzi, kod znika z paska adresu, brak znacznika obcej stacji |

Po drodze wyszły **dwa błędy tej samej rodziny** i oba są naprawione: `useTelemetria`
zestawiał SSE raz przy montowaniu, więc (1) po przełączeniu stacji pytał stary serwer,
a (2) przy wejściu ze stacji macierzystej trzymał połączenie zestawione **przed**
wpisaniem kodu, czyli bez żetonu. Objaw obu był ten sam i mylący: `BRAK SERWERA`
mimo udanego wejścia. Efekt zależy teraz od **adresu i żetonu**, a stan telemetrii
jest znakowany stacją, z której pochodzi.

**Czego NIE sprawdzono — wymaga prawdziwego telefonu:**

| # | Sprawa | Dlaczego nie tutaj |
|---|---|---|
| 1 | Rejestracja service workera | wbudowana przeglądarka odmawia (`unknown error when fetching the script`), mimo że sam plik pobiera się poprawnie (200, `application/javascript`). Składnia sprawdzona `node --check` |
| 2 | Tryb pełnego ekranu i obrót w poziom | ta sama przeglądarka odmawia: `Permissions check failed`. Kod przewiduje odmowę i nie zostawia po niej połowicznego stanu |
| 3 | Blokada wygaszania ekranu | patrz §4 — po `http://` niedostępna z założenia |
| 4 | Instalacja z pulpitu, ikona maskowalna | wymaga Androida i iPhone'a |
| 5 | Obsługa w rękawicach przy celu 64 px | to samo zastrzeżenie co dla kokpitu, [TODO.md](../TODO.md) poz. 5.5 |
| 6 | **Cała droga: obraz H.265 przez WHEP na telefonie** | to jest [TODO.md](../TODO.md) poz. 2.1 i nie zmienia się przez tę pracę — obrazu z kamery nie było jeszcze ani razu |
| 7 | **Stan pośredni reguły wieku** (2–10 s: liczby przygasają, ale jeszcze są) | trudny do złapania na symulatorze — po zerwaniu łącza serwer zeruje pola szybciej, niż da się zrobić pomiar. Sama reguła jest jednolinijkowa (`odHeartbeatu > 2`) i klasa `.stara` wchodzi poprawnie |
| 8 | **Wygląd wobec makiety `DRON 15 Telefon.dc.html`** | pliku nie ma na tej maszynie ani na Dysku — implementacja stoi na wariancie D z `UI.md` §9 i na zrzutach kokpitu. Do uzgodnienia szczegółów, gdy plik będzie dostępny |
