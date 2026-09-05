# Dostęp i użytkownicy serwera podglądu

Kto może oglądać obraz ze stacji, skąd wie o tym serwer i co z tym robi administrator.
Realizacja decyzji 7–9 z [PLAN.md](../PLAN.md) §10, zatwierdzonych 2026-08-20.

**Data:** 2026-08-20
**Stan:** zaimplementowane i sprawdzone na biurku (§10)

> **Ta warstwa nie steruje maszyną.** Ani jeden przycisk w panelu administratora
> nie wysyła niczego do drona — także administrator stacji nie ma władzy nad lotem.
> Zostaje ona na MK32 ([WLADZA.md](WLADZA.md)).

---

## 1. Dwie warstwy, nie zamiana jednej na drugą

Do 2026-08-20 serwer nie miał logowania: barierą był sam WireGuard, kto w tunelu —
ten ogląda. Lista „kto teraz patrzy" i odcięcie pojedynczej osoby **wymagają tożsamości**,
więc logowanie musiało dojść. Nie zastępuje jednak tunelu:

| Warstwa | Co daje | Czego nie daje |
|---|---|---|
| **WireGuard** | kto w ogóle dosięgnie stacji | nie wie, kto to jest — widzi adres, nie człowieka |
| **Tożsamość w aplikacji** | imię przy widzu, odcięcie jednej osoby, dziennik | sama nie chroni — bez tunelu strona byłaby wystawiona |

**Logowanie nie jest powodem, żeby cokolwiek wystawiać do internetu.** Port 8095 zostaje
za tunelem tak jak dotąd ([SERWER_PODGLADU.md](SERWER_PODGLADU.md) §9).

---

## 2. Trzy role

| Rola | Może |
|---|---|
| **widz** | obraz, telemetria, lista pozostałych widzów |
| **operator** | to co widz; rola zarezerwowana pod przyszłe uprawnienia stanowiskowe |
| **admin** | to co operator + zaproszenia, odcinanie, ustawienia, dziennik, adres publiczny stacji, **archiwum i panel STACJA** (§4a) |

Przy dwóch–pięciu osobach więcej ról to biurokracja bez pokrycia.

Administrator **nie liczy się do limitu widzów** i **przechodzi przez tryb ciszy** —
inaczej pełna sala albo własny przełącznik odcinałyby od stacji osobę, która ma nią
zarządzać.

---

## 3. Wpuszczanie: zaproszenia, nie konta

Zakładanie kont z hasłami dla pięciu osób byłoby pracą bez zysku. Zamiast tego:

```
admin wpisuje imię  →  ZAPROŚ  →  link  →  wysyłasz go człowiekowi
                                     ↓
                        otwiera raz, przeglądarka zapamiętuje żeton
                                     ↓
                        przy kolejnych wejściach wchodzi bez pytania
```

### Krok po kroku — jak wydać kod

Panel **ADMIN** → sekcja **ZAPROSZENIA**:

| # | Co zrobić | Uwaga |
|---|---|---|
| 1 | wpisz **imię** | widoczne dla wszystkich na liście „kto ogląda" (decyzja 8) — to nie jest nazwa konta, tylko to, jak człowiek ma się przedstawiać |
| 2 | wybierz **rolę** | `widz` · `operator` · `admin`. Rola `operator` nie ma dziś własnych uprawnień (§2) |
| 3 | wybierz **ważność** | 1 godzina · 1 dzień · bezterminowo |
| 4 | zostaw albo zdejmij **jednorazowe** | domyślnie jednorazowe — link przestaje działać, gdy ktoś go użyje |
| 5 | **ZAPROŚ** | |
| 6 | wyślij **link, sam kod albo kod połączeniowy** | okienko podaje wszystkie trzy; kliknięcie w każde zaznacza całość do skopiowania |

**Trzy postacie, bo trzy różne sytuacje:**

| Postać | Do czego |
|---|---|
| **link** `http://adres:8095/#z=KOD` | gość ma drogę do stacji i klika — wchodzi od razu |
| **sam kod** (24 znaki) | gdy link rozjechał się w komunikatorze, a gość ma już otwartą stronę stacji |
| **kod połączeniowy** `D15-…` | **dla aplikacji na telefonie** — niesie kod *i adres stacji*, więc aplikacja dodana na pulpit sama wie, dokąd się przełączyć. Opis: [TELEFON.md](TELEFON.md) §2a |

Kod połączeniowy nie jest adresem URL, więc komunikatory go nie łamią ani nie robią
z niego podglądu. Podlega temu samemu zastrzeżeniu co link — adres bierze z przeglądarki
administratora, nie z serwera (ramka niżej).

**Ważność liczy się od wydania, nie od pierwszego użycia.** Zaproszenie na godzinę wysłane
wieczorem jest rano martwe — dla kogoś, kto ma wejść „kiedyś", lepszy jest dzień albo
zaproszenie bezterminowe do ręcznego unieważnienia.

> ### ⚠ WAŻNOŚĆ DOTYCZY KODU, NIE DOSTĘPU
>
> Termin ogranicza **wymianę kodu na żeton**, a nie sam dostęp. Kto zdążył wejść przed
> upływem terminu, ten **ogląda dalej bez ograniczenia czasowego** — żeton nie ma daty
> ważności (`sprawdzZeton()` w `dostep.mjs` sprawdza tylko odcięcie i zgodność sekretu).
> Tak samo `UNIEWAŻNIJ`: zabija kod, nie trwający dostęp.
>
> Żeby komuś naprawdę zabrać wstęp, trzeba użyć **ODETNIJ** na liście „kto ogląda"
> albo w panelu administratora — to działa na żeton, a nie na zaproszenie (§6).
>
> „Zaproszenie na godzinę" znaczy więc „masz godzinę, żeby wejść", a nie „obejrzysz
> godzinę". To rozróżnienie łatwo przeoczyć przy wydawaniu kodu komuś obcemu.

Zanim wyślesz — przeczytaj ramkę o adresie, niżej w tej sekcji. To jedyne miejsce,
w którym łatwo wydać kod poprawny i link bezużyteczny.

**Monitory stacji to też widz.** Kiosk na HDMI (`rpi/kiosk.sh`) wchodzi przez zwykłe
zaproszenie, więc trzeba mu je wydać: **wielokrotne i bezterminowe**, bo każde okno
Chromium ma własny profil i wymienia kod osobno. Pokazuje się potem na liście „kto ogląda”
jak każdy inny widz — i tak samo się je odcina. Opis: [WDROZENIE_RPI.md](WDROZENIE_RPI.md) §4.

**Odzyskanie kodu wydanego wcześniej:** przycisk **POKAŻ LINK** przy zaproszeniu na liście.
**Odebranie dostępu:** `UNIEWAŻNIJ` (kod przestaje działać, kto już wszedł — ogląda dalej),
`ODETNIJ` przy widzu (zabiera stronę, telemetrię i **trwającą sesję obrazu**), `TRYB CISZY`
(wszyscy naraz, odwracalnie). Mechanika: §6.

Dzięki tym dwóm ograniczeniom link krążący po komunikatorach przestaje działać sam:
gdy ktoś go użyje (jeśli jednorazowy) albo gdy minie termin.

Postać linku: `http://<adres-stacji>:8095/#z=<kod>`. Kod znika z paska adresu
zaraz po wymianie, żeby nie został w historii przeglądarki.

> ### ⚠ LINK BIERZE ADRES Z PRZEGLĄDARKI ADMINA, NIE Z SERWERA
>
> Panel składa link z tego adresu, **pod którym administrator ma otwartą stronę** —
> inaczej się nie da, bo serwer nie wie, którą drogą gość będzie się łączył (LAN,
> tunel WireGuard, adres publiczny).
>
> Skutek praktyczny: panel otwarty na `http://localhost:8095` wyprodukuje link
> **oraz kod połączeniowy** działające **wyłącznie na tej jednej maszynie** — oba
> biorą adres z tego samego miejsca. Panel to wykrywa i mówi o tym
> wprost, w okienku z linkiem. Dwa wyjścia:
>
> - otworzyć panel pod adresem, którym łączy się gość (adresy są w sekcji
>   **ŁĄCZA I ADRESY** tego samego panelu), albo
> - wysłać **sam kod** — okienko podaje go osobno, do wklejenia na ekranie wejścia.
>
> To drugie działa zawsze i jest odporne na komunikatory, które łamią długie adresy.

**Kod da się pokazać ponownie** — przycisk `POKAŻ LINK` przy każdym ważnym zaproszeniu
(dodany 2026-08-23). Serwer trzyma kod do unieważnienia, więc zamknięcie okienka niczego
nie traci. Bez tego jedynym wyjściem byłoby wydanie drugiego zaproszenia tej samej osobie,
a pierwsze zostawałoby wiszące i ważne. Lista pokazuje też, czy zaproszenie **było już użyte**.

**Żeton** ma postać `id.sekret`. Sekret opuszcza serwer **raz** — w odpowiedzi na
wymianę kodu. Potem jest tylko porównywany, funkcją odporną na pomiar czasu.

### Pierwszy administrator

Serwer bez admina byłby zamknięty sam przed sobą — nie ma kto wydać pierwszego
zaproszenia. Gdy więc stacja nie ma jeszcze żadnego administratora, wypisuje kod
na konsolę przy starcie:

```
  ┌──────────────────────────────────────────────────────────────┐
  │  PIERWSZE WEJŚCIE ADMINISTRATORA                             │
  └──────────────────────────────────────────────────────────────┘
  kod: f49b918cf1e2a769b54e1cf8
  link: http://<adres-stacji>:8095/#z=f49b918cf1e2a769b54e1cf8
```

Zaproszenie jest wielokrotne (admin bywa potrzebny na kilku urządzeniach) i zostaje
ważne, dopóki nie unieważnisz go w panelu. Robi się to raz, po pierwszym wejściu.

Na stacji chodzącej pod systemd konsola startowa jest w dzienniku systemowym:

```bash
journalctl -u dron15-gcs | grep -A3 "PIERWSZE WEJŚCIE"
```

### Nie da się zatrzasnąć drzwi od środka

Kod administratora wypisuje się przy **każdym starcie**, w którym stacja nie ma
ani czynnego żetonu administratora, ani ważnego zaproszenia dla niego
(`zapewnijAdmina()` w `dostep.mjs`). To znaczy:

| Sytuacja | Co się dzieje przy starcie |
|---|---|
| wszedłeś i unieważniłeś zaproszenie startowe | **nic** — masz żeton, więc admin istnieje |
| straciłeś żeton (nowe urządzenie, wyczyszczona przeglądarka), zaproszenie nadal ważne | nic — wystarczy użyć starego kodu (`POKAŻ LINK`) |
| straciłeś żeton **i** nie ma ważnego zaproszenia administratora | **wypisuje nowy kod** |

Czyli z panelu nie da się wyjść na trwałe: w najgorszym razie zostaje
`sudo systemctl restart dron15-gcs` i odczytanie kodu z dziennika, jak wyżej.

Odwrotna strona tej wygody: **kto ma dostęp do konsoli stacji, ten ma administratora**,
niezależnie od zaproszeń. To jest świadome — stacja stoi przy operatorze, a dostęp
do jej konsoli i tak oznacza dostęp do wszystkiego (§7).

---

## 4. Panel administratora

Od 2026-09-04 panel ma **karty**, nie jedną długą ścianę sekcji. Każda karta odpowiada
na jedno pytanie, a jej nazwa jest tym pytaniem — na stacji, z pokrętłem zamiast myszy,
nie ma czasu na przewijanie i zgadywanie (uwaga Toma po pierwszym dniu z panelem).

| Karta | Pytanie | Zawartość |
|---|---|---|
| **ZAPROSZENIA** | kogo wpuścić | 1. wydaj (dla kogo, rola z opisem, ważność, jednorazowe) → 2. gotowy link, kod i kod połączeniowy → 3. lista ważnych: POKAŻ LINK, UNIEWAŻNIJ; na dole adresy stacji w LAN |
| **DOSTĘP** | kto ogląda i co może | tryb ciszy, limit widzów, źródło domyślne; kto ogląda teraz (imię, rola, strumień, adres, od kiedy, ODETNIJ); dziennik dostępu |
| **NOWE ŹRÓDŁO** | jak podłączyć drona albo kamerę | 1. dron DJI (nadaje) albo kamera IP (stacja pobiera) → 2. nazwa (+ adres RTSP) → 3. GOTOWE: adres RTMP dla Pilota 2 i hasło dla aplikacji Horyzont, do przepisania od razu |
| **ŹRÓDŁA** | co stacja pokazuje | stan (NADAJE / CZEKA / GOTOWE), nazwa, WIDOCZNE/UKRYTE, POKAŻ HASŁO, NOWE HASŁO, USUŃ (dwa kliknięcia); klawisz + NOWE ŹRÓDŁO |
| **ARCHIWUM** | co nagrywamy | tryb nagrywania obrazu, limity, zajętość dysku, ostatnie nagrania, SPRZĄTAJ TERAZ |
| **DIAGNOSTYKA** | czy działa i co się zepsuło | stan MediaMTX i telemetrii, sesje obrazu, endpoint WireGuarda, rejestr techniczny ze stosami |

Dodawanie źródła jest **osobno od listy** celowo: dodawanie to czynność z końcem, którym
jest hasło do wpisania w aparaturze; lista to stan. Zaproszenia są osobno od DOSTĘPU
z tego samego powodu: wydanie kodu to czynność, kto ogląda — stan.

Ostatnio otwarta karta jest pamiętana w przeglądarce (`dron15.admin.karta`). Każda karta
pobiera własne dane i odświeża je tylko, gdy jest otwarta.

**Adres publiczny stacji przeniesiono tu z dolnego paska** (był tam od decyzji 6).
To adres bramy do sieci — widzowi do niczego nie jest potrzebny, a wiedza o nim
jest warta więcej niż wygoda.

---

## 4-bis. Stacja RPi jest STANOWISKIEM ADMINISTRATORA

Ustalone 2026-08-29 po pierwszym dniu użytkowania. **Stacja na malinie nie jest
kolejnym widzem — to miejsce, z którego rozdaje się dostęp.** Praktycznie znaczy to:

- kod administratora żyje **tam** (`/var/lib/dron15/dostep.json`) i stamtąd wydaje się
  zaproszenia dla pozostałych stanowisk,
- pozostali (laptop, telefon, goście) dostają rolę **widz** — nigdy admina,
- panel ADMIN otwiera się **pod adresem stacji**, nie przez `localhost`, bo link
  zapraszający składa się z adresu w pasku przeglądarki (§3).

⚠ **Kiosk odłożony, świadomie.** Pierwotnie stacja miała pracować jako kiosk na monitorze.
Na tej malinie chodzi równolegle inne oprogramowanie (`PI5setup full` — patrz
[WDROZENIE_RPI.md](WDROZENIE_RPI.md) §0), z którym trzeba współpracować, a monitor jest
jeden. Zostajemy przy stronie w przeglądarce.

---

## 4a. Panel STACJA — osobny przycisk, osobne pytanie

Dodany 2026-08-23. Rozróżnienie jest celowe i warto je trzymać:

| Panel | Odpowiada na pytanie |
|---|---|
| **ADMIN** | **kto ma wstęp** — zaproszenia, widzowie, odcinanie, archiwum |
| **STACJA** | **czy sprzęt działa** — usługi, zasilanie, sieć, dziennik systemowy |

Zawartość: stan i restart trzech usług, dławienie zasilania, temperatura, obciążenie,
pamięć, dysk, adresy i MTU interfejsów, nasłuch portów, wersje oprogramowania,
obecność dekodera HEVC, dziennik systemowy per usługa. To ten sam zestaw odczytów,
co `rpi/sprawdz.sh` — tyle że bez wchodzenia po ssh. Skrypt zostaje, bo jest jedyną
drogą wtedy, gdy serwer **nie wstaje**, a wtedy panelu też nie ma.

> ### ⛔ Granica jest ta sama co wszędzie
>
> Panel obsługuje **stację**, nie maszynę latającą. Nie prowadzi stąd żadna ścieżka
> do kontrolera lotu ani do głowicy. Restart usługi podglądu zabiera obraz widzom
> i nic poza tym. Władza zostaje na MK32 ([WLADZA.md](WLADZA.md)).

### Co administrator dostaje na samej maszynie — i dlaczego akurat tyle

Restart usługi wymaga uprawnień, których serwer podglądu normalnie nie ma. Nadaje mu
je `rpi/instaluj.sh`, dwiema drogami, obiema wąskimi:

| Do czego | Jak | Co to otwiera |
|---|---|---|
| czytanie dziennika | grupa `adm` / `systemd-journal` | odczyt dziennika systemowego, **bez** podnoszenia uprawnień |
| restart usług | `/etc/sudoers.d/dron15-panel` | **wyłącznie** `systemctl restart dron15-mediamtx` i `dron15-gcs`, pełnymi ścieżkami |

W pliku sudoers **nie ma gwiazdki** i to nie jest przesada. `systemctl restart dron15-*`
wystarczyłoby, ale każda gwiazdka znaczy, że jedna dziura w warstwie wyżej (błąd
w sprawdzaniu nazwy usługi, przechwycony żeton administratora) zamienia panel w powłokę
z prawami roota. Przy dwóch pełnych ścieżkach najgorsze, co da się tą drogą zrobić,
to zrestartować usługę, którą i tak wolno restartować z panelu.

Czego tam celowo **nie ma**: `reboot` i `poweroff` (przez tunel nieodwracalne bez wizyty
na miejscu), `stop` i `disable` (panel ma podnosić, nie gasić).

**Konsekwencja dla jednostki `dron15-gcs`: nie ma w niej `NoNewPrivileges=yes`**, bo
`sudo` jest programem setuid i pod tą opcją nie zadziałałby w ogóle — przycisk RESTART
byłby martwy bez żadnego czytelnego komunikatu. `dron15-mediamtx` tę opcję zachowuje,
bo niczego nie podnosi.

Nazwa usługi z żądania **nigdy nie trafia do polecenia wprost** — musi się najpierw
znaleźć na zamkniętej liście w `server/stacja.mjs`. Sprawdzone: żądanie restartu
usługi `dron15-gcs; rm -rf /` kończy się odpowiedzią „Nieznana usługa".

Bez tych uprawnień panel **działa dalej**: pokazuje wszystko, a przy próbie restartu
mówi wprost, czego brakuje i skąd to wziąć.

---

## 5. Obecność — kto teraz ogląda

Widz widzi **imiona** (decyzja 8): przy kilku znajomych osobach anonimowość niczego
nie chroni, a lista bez imion niewiele mówi. Adresów IP w tym widoku nie ma —
te widzi wyłącznie administrator.

**Mechanizm jest darmowy.** Sygnałem obecności jest **otwarte połączenie SSE
z telemetrią** — to samo, które każdy widz i tak trzyma. Zamknięta karta = padnięte
połączenie = zniknięcie z listy. Nie dokładamy odpytywania ani drugiego kanału.

Jedyne, czego z samego połączenia nie widać, to **co dana osoba ogląda** — więc
przeglądarka melduje to osobno, identyfikatorem połączenia, który serwer odsyła
w pierwszej wiadomości SSE.

Uwaga na przyszłość: strumień telemetrii zestawiamy **raz** i nie zrywamy go przy
zmianie źródła. Zerwanie i zestawienie na nowo liczyłoby się jako wyjście i wejście
widza, a dziennik zapełniłby się zdarzeniami bez treści.

---

## 6. Odcinanie — trzy rzeczy, nie jedna

Odcięty widz musi stracić **wszystkie trzy** kanały, bo każdy działa niezależnie:

| Co | Jak zabierane |
|---|---|
| strona i API | żeton oznaczony jako odcięty → każde wywołanie dostaje 401 |
| telemetria | otwarte połączenie SSE zrywane z zewnątrz, ze zdarzeniem `odciety` |
| **obraz** | **sesja WebRTC ubijana w MediaMTX** |

Trzeci punkt był ryzykiem całego projektu i dlatego rozpoznaliśmy go **przed** pisaniem
kodu. Obraz nie idzie przez nasz serwer — wydaje go MediaMTX, obok. Bez wpięcia się
w jego uwierzytelnianie „ODETNIJ" zabierałoby stronę i telemetrię, ale **nie strumień,
który ktoś już trzyma otwarty**.

**Rozwiązanie — MediaMTX v1.19.0 ma jedno i drugie (FAKT, sprawdzone):**

```yaml
authMethod: http
authHTTPAddress: http://127.0.0.1:8095/api/mtx-auth
authHTTPExclude: [{action: api}, {action: metrics}, {action: pprof}]
```

MediaMTX pyta nasz serwer przed **każdym** odtworzeniem. Przeglądarka przedstawia się
przez HTTP Basic: **użytkownik = id żetonu, hasło = sekret**. Trwające sesje kończy się
przez `POST /v3/webrtcsessions/kick/{id}`; sesję kojarzymy z żetonem po adresie,
który MediaMTX podaje przy pytaniu o zgodę.

`authHTTPExclude` jest konieczne: bez niego nasz własny serwer nie mógłby korzystać
z API kontrolnego MediaMTX, bo musiałby pytać sam siebie o zgodę.

**Tryb ciszy działa tą samą drogą i od razu**, nie od następnego wejścia — inaczej
byłby deklaracją, a nie przełącznikiem.

---

## 7. Świadome ograniczenia

Rzeczy, o których lepiej wiedzieć teraz niż odkryć je później:

1. **Żeton bywa w adresie.** `EventSource` nie umie ustawiać nagłówków, więc do SSE
   przekazujemy żeton parametrem. Konsekwencja: może trafić do logów dostępu.
   Wewnątrz tunelu przyjmujemy to świadomie.
2. **Żeton leży w `localStorage`.** Kto ma dostęp do odblokowanego urządzenia widza,
   ma dostęp do podglądu. Odpowiedzią jest ODETNIJ, nie szyfrowanie.
2a. **Żeton jest przypisany do adresu stacji.** Od 2026-08-23 klient pamięta kilka
   stacji naraz i do każdej osobny żeton ([TELEFON.md](TELEFON.md) §2a). Zaletą jest
   powrót bez kodu; ceną — że `ZAPOMNIJ` na ekranie wejścia kasuje żeton tylko
   po stronie widza. Po stronie stacji żeton żyje dalej, aż do `ODETNIJ` (§6).
2b. **CORS na `/api/*` nie jest barierą i jej nie zastępuje.** Nagłówki mówią
   przeglądarce, że wolno pokazać odpowiedź skryptowi z innego źródła — nikogo nie
   wpuszczają. Barierą pozostaje WireGuard, a portu 8095 nadal nie wystawiamy
   do internetu.
3. **Brak TLS.** Strona chodzi po `http` wewnątrz tunelu. Gdyby kiedyś miała wyjść
   poza tunel, potrzebne są certyfikaty **i** przemyślenie punktów 1–2 od nowa.
4. **To nie jest ochrona przed kimś, kto już jest w sieci pokładowej.** Taka osoba
   sięgnie do MediaMTX i do głowicy z pominięciem naszego serwera. Odpowiedzią jest
   rozdzielenie sieci ([SERWER_PODGLADU.md](SERWER_PODGLADU.md) §8), nie logowanie.
5. **Limit widzów to nie ozdoba.** Każdy widz to osobny strumień — pasmo jest skończone.
   Po przekroczeniu limitu kolejny dostaje jasny komunikat, nie zawieszenie.
6. **Żeton nie wygasa.** Termin ważności ogranicza wymianę kodu, nie sam dostęp (§3).
   Kto raz wszedł, ogląda do odcięcia. Świadome: przy pięciu znajomych osobach
   wygaszanie sesji przeszkadzałoby częściej, niż pomagało — a ODETNIJ jest natychmiastowe.
7. **Konsola stacji to administrator.** Restart usługi bez czynnego administratora
   wypisuje nowy kod (§3). Zaletą jest brak możliwości zatrzaśnięcia się na zewnątrz,
   ceną — że dostęp do konsoli równa się dostępowi do panelu.

---

## 8. Pliki i API

| Plik | Rola |
|---|---|
| `serwer/server/dostep.mjs` | zaproszenia, żetony, ustawienia, dziennik; stan w `dostep.json` |
| `serwer/server/obecnosc.mjs` | rejestr obecności oparty o połączenia SSE |
| `serwer/server/index.mjs` | trasy, uwierzytelnianie, `/api/mtx-auth` |
| `serwer/web/src/sesja.js` | żeton po stronie przeglądarki, trzy sposoby przedstawiania się |
| `serwer/web/src/Wejscie.jsx` | ekran dla kogoś bez ważnego zaproszenia |
| `serwer/web/src/Widzowie.jsx` | lista „kto ogląda" dla widza |
| `serwer/web/src/Admin.jsx` | panel administratora — dostęp i archiwum |
| `serwer/server/archiwum.mjs` | zapis `.tlog`, sprzątanie nagrań po czasie i zajętości |
| `serwer/server/stacja.mjs` | odczyty maszyny i **zamknięta lista** dozwolonych poleceń |
| `serwer/web/src/Stacja.jsx` | panel stacji — usługi, zasilanie, sieć, dziennik systemowy |
| `serwer/rpi/dron15-panel.sudoers` | wzorzec uprawnień do restartu usług (§4a) |

Stan trzymamy w jednym pliku JSON (`DATA_DIR/dostep.json`), zapisywanym przez plik
tymczasowy — przerwane zasilanie RPi nie zostawi obciętego JSON-a. Przy tej skali baza
danych byłaby pracą bez pokrycia, a plik da się obejrzeć i poprawić edytorem w terenie.

### Trasy

| Metoda | Ścieżka | Kto |
|---|---|---|
| POST | `/api/zaproszenie` | każdy (wymiana kodu na żeton) |
| GET | `/api/ja` | żeton |
| GET | `/api/zrodla`, `/api/status`, `/api/stan` | widz |
| GET | `/api/telemetria` (SSE) | widz |
| GET | `/api/widzowie` | widz |
| POST | `/api/obecnosc` | widz |
| POST | `/api/mtx-auth` | **tylko 127.0.0.1** — pyta MediaMTX |
| GET | `/api/adresy` | admin |
| GET | `/api/admin/stan`, `/api/admin/dziennik` | admin |
| POST | `/api/admin/zaproszenie`, `/api/admin/odetnij`, `/api/admin/ustawienia` | admin |
| DELETE | `/api/admin/zaproszenie/:id` | admin |
| GET | `/api/admin/archiwum` | admin |
| POST | `/api/admin/archiwum`, `/api/admin/archiwum/sprzataj` | admin |
| GET | `/api/admin/stacja`, `/api/admin/stacja/dziennik` | admin |
| POST | `/api/admin/stacja/restart` | admin — **tylko usługi z zamkniętej listy** |

---

## 9. Pierwsze uruchomienie

```bash
cd serwer
npm install --omit=dev
cd web && npm install && npm run build && cd ..
sh start.sh
```

Kod pierwszego administratora pojawi się na konsoli. Otwórz link, wejdź, unieważnij
to zaproszenie w panelu i wydaj imienne dla siebie i pozostałych.

---

## 10. Co sprawdzone, a co nie

**Sprawdzone na biurku 2026-08-20**, bez drona, z prawdziwym MediaMTX v1.19.0:

| Co | Wynik |
|---|---|
| wywołanie bez żetonu | 401 |
| wymiana kodu na żeton, rola z zaproszenia | działa |
| kod jednorazowy użyty drugi raz | odmowa |
| widz sięgający po panel admina | 403 |
| obecność dwóch widzów, imiona i strumienie | działa |
| `mtx-auth`: poprawny żeton / złe hasło / próba publikowania | 200 / 401 / 401 |
| **WHEP przez prawdziwy MediaMTX bez poświadczeń** | **401 — MediaMTX pyta nas i słucha odpowiedzi** |
| **WHEP z poprawnym żetonem** | **przechodzi uwierzytelnianie** |
| ODETNIJ: strona, telemetria (zdarzenie `odciety`), powrót | 401 / zerwane / 401 |
| limit widzów | drugi widz dostaje 429 z powodem |
| tryb ciszy, przepustka administratora | działa |
| dziennik | notuje wejścia, odcięcia, odmowy, zaproszenia |

**Dołożone 2026-08-23** (archiwum i panel STACJA), na Windows z symulatorem telemetrii:

| Co | Wynik |
|---|---|
| `.tlog`: zapis i odczyt przez `pymavlink` | 865 wiadomości, znaczniki rosnące |
| sprzątanie archiwum po czasie i po zajętości | kasuje najstarsze, bieżącego nagrania nie rusza |
| `/api/admin/archiwum` bez żetonu / z żetonem admina | 401 / 200 |
| zmiana trybu nagrywania — czy `zrodla.json` zachowuje resztę | źródła i telemetria nietknięte |
| nieznany tryb nagrywania (`czasem`) | 400 z listą dozwolonych |
| `/api/admin/stacja` bez żetonu / z żetonem admina | 401 / 200 |
| **restart usługi `dron15-gcs; rm -rf /`** | **400 „Nieznana usługa” — lista zamknięta działa** |
| panel na systemie bez systemd (Windows) | pokazuje, co się da; restart mówi dlaczego nie |
| **wydanie zaproszenia w przeglądarce** | imię, rola, ważność, jednorazowość → link + kod, wpis w dzienniku |
| **POKAŻ LINK na wydanym wcześniej zaproszeniu** | odzyskuje ten sam kod, bez wydawania nowego |
| ostrzeżenie o linku z `localhost` | pokazuje się, gdy panel otwarty lokalnie |
| **wejście świeżego profilu przez `#z=<kod>`** | ląduje wprost na nakładce jako „monitory stacji”, kod znika z adresu, żeton zostaje w profilu |

**Niesprawdzone:** cała warstwa systemd, sudoers i dziennik systemowy — na żywej
malinie nic z tego jeszcze nie chodziło ([WDROZENIE_RPI.md](WDROZENIE_RPI.md), ramka na górze).

**Niesprawdzone:**

- **ubicie trwającej sesji obrazu** — kod jest, endpoint MediaMTX potwierdzony,
  ale przy próbie nie było ani jednej prawdziwej sesji WebRTC do ubicia.
  Do sprawdzenia z przeglądarką i działającym źródłem obrazu.
- **cała rzecz z prawdziwą kamerą** — jak wszystko w tym projekcie, patrz
  [PLAN.md](../PLAN.md) §8a.
