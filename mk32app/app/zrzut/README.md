# DRON 15 — zrzut ekranu aparatury (APK na kontroler DJI)

Aplikacja na Androida, która **przechwytuje ekran kontrolera DJI i wysyła obraz na
stację naziemną**. Osobny moduł, nie część kokpitu MK32: inne urządzenie, inne
zadanie, inny cykl życia.

| | |
|---|---|
| Moduł Gradle | `:zrzut` (`app/settings.gradle.kts`) |
| Pakiet | `pl.dron15.zrzut` |
| Android | minSdk 26 — kontrolery DJI to Android 9–11 |
| Rozmiar APK | ok. 5,7 MB (z biblioteką Material 3) |
| Zależności | wyłącznie `com.google.android.material` |
| Odbiornik po stronie stacji | `serwer/server/zrzut.mjs`, TCP **5601** |

---

## 1. Po co to jest

**Mavic 3 Pro nie odda telemetrii żadną drogą** — DJI nie wydało dla niego SDK, a Cloud
API działa tylko z serią Enterprise (`dok/DJI.md` §1). Natywna transmisja RTMP daje sam
obraz i od DJI Fly 1.16 wymaga podpiętego mikrofonu.

Zrzut ekranu obchodzi obie te sprawy naraz: bierze **to, co widzi operator** — obraz
razem z całą nakładką OSD, czyli wysokością, prędkością i baterią wypaloną w obrazie.

⛔ **To nie zastępuje Cloud API dla Mavic 3T.** Tam telemetria idzie liczbami i trafia
na mapę stacji; tutaj jest tylko pikselami. Dla Mavic 3 Pro jest to jednak jedyna droga.

### Aplikacja obsługuje OBIE drogi obrazu

Operator wpisuje **dwa pola — adres stacji i hasło urządzenia — i to wszystko**.
Z tych samych dwóch wartości aplikacja składa też gotowy adres RTMP dla DJI Pilot 2:

| Droga | Ścieżka na stacji | Co daje |
|---|---|---|
| zrzut ekranu (START) | `dji` | obraz **z nakładką OSD** |
| natywny RTMP z Pilota 2 | `dji2` | **czysty obraz** z kamery |

Ścieżki są różne celowo — stacja dopuszcza obie (`SCIEZKI_NADAWANIA`), więc oba obrazy
mogą iść **równocześnie** i pokazać się jako dwa osobne źródła.

⚠ **Równocześnie znaczy podwójne pasmo w górę z jednej aparatury**, która jednocześnie
prowadzi lot. Na słabym łączu zepsują się oba naraz, a nie jeden ustąpi drugiemu.

⛔ **Adres RTMP zawiera hasło urządzenia** i po naciśnięciu KOPIUJ zostaje w schowku
aparatury, dopóki nie skopiuje się czegoś innego.

### Gdy zrzut zawodzi, aplikacja sama proponuje drugą drogę

Karta podpowiedzi wychodzi w dwóch przypadkach i mówi, **co** poszło źle:

| Objaw | Rozpoznanie |
|---|---|
| obraz wychodzi pusty | przepływność poniżej **20 kb/s przez 6 s** — ten sam próg, co na stacji. To **podejrzenie, nie dowód**: nieruchomy ciemny ekran daje podobny wynik |
| łącze zrywa się | **3 ponowienia** lub więcej |

Klawisz **PRZEŁĄCZ NA PILOTA 2** kopiuje adres i otwiera aplikację DJI jednym
naciśnięciem. ⚠ Nazwy pakietów DJI są zgadywane z listy (`DrogaPilota.PAKIETY`);
gdy żadnej nie ma, aplikacja mówi wprost „otwórz ręcznie, adres masz w schowku"
zamiast udawać, że coś zrobiła.

---

## 2. Jak to działa

```
ekran aparatury ──MediaProjection──► VirtualDisplay ──► Surface kodera
   ──MediaCodec H.264──► TCP :5601 ──► serwer/zrzut.mjs ──ffmpeg -c copy──► RTMP :1935
   ──► MediaMTX ──WebRTC──► przeglądarki widzów
```

Obraz trafia **wprost na powierzchnię wejściową kodera**, więc nie przechodzi przez
pamięć aplikacji ani przez procesor: rysuje układ graficzny, koduje koder sprzętowy.
To jedyny wariant, który ma szansę nadążyć obok pracującego DJI Pilot 2.

### Dlaczego surowy H.264 po TCP, a nie RTMP z Androida

RTMP znaczyłby własny handshake, chunkowanie i muxer FLV — kilkaset linii protokołu na
urządzeniu, którego nie da się wygodnie podejrzeć. Aparatura wysyła to, co wypluwa
`MediaCodec` (strumień Annex-B), a resztę robi `ffmpeg` na stacji, gdzie jest konsola
i dziennik. `-c copy` znaczy **bez przekodowania**.

Protokół: jedna linia JSON zakończona `\n` (`haslo`, `szer`, `wys`, `fps`), potem już
tylko klatki.

---

## 3. ⛔ Pauza, nie zatrzymanie — to jest sedno całej konstrukcji

Android pyta o zgodę na przechwytywanie ekranu **przy każdym nowym uruchomieniu**
i nie da się tego zapamiętać (celowe zabezpieczenie systemu). Gdyby STOP zwalniał
przechwytywanie, każde ponowne włączenie w powietrzu oznaczałoby okienko systemowe
do odklikania — **pilotowi, który trzyma drążki**.

Dlatego zgodę bierze się **raz, przed lotem**, a START i STOP przełączają tylko wysyłanie:

| Stan | zgoda | koder i obraz wirtualny | gniazdo do stacji |
|---|---|---|---|
| nadaje | trzymana | pracują | otwarte |
| **pauza** | **trzymana** | zwolnione (zero obciążenia) | zamknięte |
| koniec | zwolniona | zwolnione | zamknięte |

Wznowienie tworzy nowy obraz wirtualny z **tej samej** zgody — bez pytania.

### ⛔ „Włączone" to nie to samo, co „obraz dociera"

Gdy sieć padnie, usługa **dalej chce nadawać** i ponawia próbę co 1→15 s. Stan
rozróżnia więc dwie rzeczy: `nadaje` (operator włączył) i `plynie` (klatki naprawdę
idą). Bez tego rozróżnienia karta świeciłaby zielenią przy zerwanym łączu, a zielone
znaczy w tej aplikacji **wyłącznie** „obraz idzie".

| Co widać | Kiedy |
|---|---|
| **NADAJE** (zielone) | łącze stoi, klatki idą |
| **ŁĄCZY SIĘ** (pomarańczowe) | operator włączył, ale stacja nie odpowiada |
| **WSTRZYMANE** | pauza, zgoda trzymana |

Z tego samego powodu aplikacja **chowa się po starcie dopiero wtedy, gdy obraz
naprawdę poszedł** — inaczej pilot odchodziłby od aparatury przekonany, że stacja
ma obraz.

---

## 4. Trzy drogi do tego samego przełącznika

Pilot w locie patrzy na DJI Pilot 2 i nie będzie wracał do naszej aplikacji, więc
obraz wstrzymuje się:

1. **kafelkiem w szybkich ustawieniach** (`KafelekZrzutu`) — jedno przeciągnięcie
   paska i jedno dotknięcie z dowolnej aplikacji. Droga główna w locie;
2. **z powiadomienia** — niesie stan (`NADAJE — obraz idzie na stację · 548 kb/s · 38 s`)
   i klawisze WSTRZYMAJ / ZAKOŃCZ. ⚠ Klawisze widać po rozwinięciu, więc jest o jeden
   gest dalej niż kafelek;
3. z ekranu aplikacji — przed lotem.

Stan trzyma jeden obiekt (`Stan.kt`), żeby wszystkie trzy pokazywały to samo.

⚠ **Kafelek trzeba raz dodać ręcznie** na Androidzie starszym niż 13: pasek szybkich
ustawień → ołówek → przeciągnąć „Zrzut ekranu". Na 13+ robi to klawisz w aplikacji.
⛔ Kafelek **nie potrafi wziąć zgody** — systemowego okna nie da się pokazać z szybkich
ustawień — więc bez zgody otwiera aplikację, zamiast udawać, że coś zrobił.

---

## 5. Aplikacja schodzi z ekranu, ale nie z drogi

Ekran aparatury należy do Pilota 2. Stąd klawisz **UKRYJ** i przełącznik
**„Chowaj aplikację po starcie"** (domyślnie włączony): po naciśnięciu START operator
widzi przez chwilę zieloną kartę i ekran **sam schodzi mu z drogi**.

Technicznie `moveTaskToBack`, nie zamknięcie — zamknięcie aktywności zabiłoby usługę
razem ze zgodą na przechwytywanie.

> ### ⛔ Dlaczego NIE ma pływającego klawisza nad Pilotem
>
> Przechwytujemy **cały ekran**, więc każda nasza nakładka trafiłaby do obrazu
> wysyłanego na stację — na nagraniu z lotu wisiałby nasz przycisk.

---

## 6. Wygląd — Material 3 (Material You)

Układ **dwukolumnowy**, bo kontroler jest szeroki i niski (7″ w poziomie): lewa kolumna
to stan i działanie, prawa — ustawienia dotykane raz przed lotem.

| Decyzja | Powód |
|---|---|
| karta stanu jest największym elementem | ma być czytelna z odległości wyciągniętej ręki |
| **kolor niesie stan**: zielony „obraz idzie", pomarańczowy „wstrzymane" | rozpoznanie kątem oka, w słońcu |
| klawisz główny zmienia rolę: zielone wypełnienie zaprasza, pomarańczowa obwódka jest wyjściem | kolor nigdy nie kłamie o tym, co się stanie |
| jeden wybór jakości (`LEKKA` / `ZWYKŁA` / `OSTRA`) zamiast trzech liczb | klatki, skala i pasmo nie są niezależne — więcej klatek bez pasma daje kaszę |
| ustawienia gasną i blokują się w trakcie nadawania | zmiana w locie znaczyłaby zerwanie łącza w najgorszym momencie |
| **klawisze są przypięte, stan się przewija** | gdy wyskoczy karta podpowiedzi, treść rośnie ponad wysokość ekranu — a klawisz główny musi zostać tam, gdzie palec go szuka |
| komunikat po naciśnięciu trzyma się 4 s | odświeżanie stanu co pół sekundy wpisywało z powrotem „ponawiam za N s" i odpowiedź na własne naciśnięcie znikała, zanim dało się ją przeczytać |
| `SPRAWDŹ ŁĄCZE` | sprawdzenie na ziemi jest tanie; odkrycie w powietrzu, że adres zły, kosztuje lot |

Paleta jest **ta sama, co na stacji** — operator ogląda oba ekrany tego samego dnia.

> ### ⛔ Material odebrał barwom znaczenie i trzeba mu je było odebrać z powrotem
>
> Po włączeniu Material 3 klawisz `SPRAWDŹ ŁĄCZE` zrobił się pomarańczowy: komponenty
> tonalne biorą tło z `colorSecondaryContainer`, a tam siedział kolor wstrzymania.
> Barwy drugorzędne motywu są teraz **neutralne**, a stan malujemy jawnie w kodzie.
> Dotyczy to też Material You: na Androidzie 12+ paleta idzie z tapety, ale **zielony
> i pomarańczowy stanu zostają nasze**.
>
> ⚠ Kontrolery DJI to Android 9–11, więc Material You się tam **nie włączy** —
> obowiązuje paleta z `res/values/colors.xml`.

---

## 7. Pliki

| Plik | Co robi |
|---|---|
| `GlownaAktywnosc.kt` | ekran przed lotem: ustawienia, próba łącza, zgoda, start |
| `DrogaPilota.kt` | druga droga: adres RTMP, schowek, otwarcie aplikacji DJI |
| `UslugaZrzutu.kt` | przechwytywanie, kodowanie, wysyłka; pauza/wznowienie; powiadomienie |
| `NadajnikTcp.kt` | gniazdo do stacji, nagłówek, wysyłka klatek |
| `KafelekZrzutu.kt` | kafelek szybkich ustawień |
| `Stan.kt` | wspólny stan dla ekranu, kafelka i powiadomienia |
| `res/values/themes.xml` | motyw Material 3 i style |
| `res/layout/glowna.xml` | układ dwukolumnowy |

---

## 8. Budowanie i wgrywanie

```bash
gradle :zrzut:assembleDebug
adb install -r app/zrzut/build/outputs/apk/debug/zrzut-debug.apk
```

Na aparaturze: wpisać adres stacji (`192.168.88.30:5601`) i **hasło urządzenia**
z panelu ADMIN (`GET /api/zrzut` podaje adres i hasło gotowe do przepisania),
nacisnąć START i potwierdzić zgodę systemu. Na stacji wybrać źródło
**DJI — nadawany**.

---

## 9. Co sprawdzone, a co nie

| Rzecz | Stan |
|---|---|
| budowanie APK | **FAKT** |
| instalacja, zgoda systemu, przechwytywanie | **FAKT** — emulator API 28 |
| kodowanie i wysyłka | **FAKT** — `Nadaje 960×592 @ 15 kl./s`, odebrane 5,5 MB / 406 klatek |
| klatki wyjęte ze strumienia pokazują żywy ekran | **FAKT** |
| pauza i wznowienie **bez pytania o zgodę** | **FAKT** — log: `Wstrzymane (operator)` → `Nadaje` po 5 s |
| chowanie po starcie, obraz leci dalej | **FAKT** — 12 s w tle bez dotknięcia, dane płyną |
| adres RTMP składa się z dwóch pól | **FAKT** — `rtmp://10.0.2.2:1935/dji2?user=dji&pass=…` |
| kopiowanie adresu do schowka | **FAKT** — potwierdzenie na ekranie |
| podpowiedź po 3 ponowieniach + klawisz | **FAKT** — emulator bez stacji na drugim końcu |
| **ŁĄCZY SIĘ** zamiast zielonego **NADAJE** przy martwym łączu | **FAKT** |
| wykrycie czerni po przepływności | ⛔ **NIESPRAWDZONE** — brak ekranu, który DJI naprawdę zasłania |
| otwarcie aplikacji DJI z klawisza | ⛔ **NIESPRAWDZONE** — na emulatorze jej nie ma; ścieżka „nie znalazłem" **sprawdzona** |
| cały łańcuch do MediaMTX | **FAKT** — `[path dji] stream is available and online, 1 track (H264)` |
| **działanie na prawdziwym kontrolerze DJI** | ⛔ **NIESPRAWDZONE** |
| **czy DJI nie blokuje zrzutu (`FLAG_SECURE`)** | ⛔ **NIEROZSTRZYGNIĘTE** — wtedy obraz będzie czarny i nie da się tego obejść z aplikacji |

Stacja wykrywa czerń sama, po przepływności (`serwer/server/zrzut.mjs`) — to jednak
**podejrzenie, nie dowód**; rozstrzyga spojrzenie na podgląd.

Szersze tło i strona stacji: **`dok/DJI.md`**.
