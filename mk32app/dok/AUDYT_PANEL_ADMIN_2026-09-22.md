# Audyt panelu ADMIN — 2026-09-22

Przegląd interfejsu administratora Panoramy pod kątem dwóch czynności, które
w polu wykonuje się najczęściej i pod presją: **wpuszczenie kogoś** i **podłączenie
źródła**. Zakres: `serwer/web/src/Admin.jsx`, `admin/*`, `Wejscie.jsx`,
`NaglowekPanelu.jsx`, `Mapa.jsx` oraz wspierające je punkty API.

Poprawki z części „zrobione" są w repozytorium i zbudowane; na stację jeszcze nie
wgrane.

## Co trzyma się dobrze

Karta = jedno pytanie, a jej nazwa jest tym pytaniem — to się broni i nie ruszałem
tego podziału. Dane do aparatury dostały osobny, pełnoszeroki blok z dużym
monospace (`DaneAparatury.jsx`), bo przepisuje się je ręcznie z ekranu; hasła
pokazują się dopiero na żądanie; nagłówek panelu jest przyklejony do góry i niesie
przejście do pozostałych paneli — wszystko trzy wnioski z użytkowania, nie
z projektu. Klawisze mają rozmiar pod pokrętło, a nie pod mysz.

## Dostęp — co poprawiono

| # | Objaw w polu | Przyczyna | Co zrobiono |
| --- | --- | --- | --- |
| A1 | Zaproszenie wydane z ekranu stacji nie wpuszczało nikogo poza tą maszyną | link budował się z `window.location.origin`, a panel na stanowisku ma w pasku `127.0.0.1`; panel ostrzegał, ale poprawić się tego nie dało | **adres jest teraz wyborem** — lista adresów stacji przy zaproszeniu, domyślnie pierwszy sieciowy, nie pętla zwrotna |
| A2 | Wpuszczenie gościa z telefonem = przepisanie 24 znaków z ekranu, w rękawicach, przy słońcu | kod istniał tylko jako tekst | **kod QR**: na ekranie, powiększany na pół ekranu, do zgrania na pendrive i do wysłania dalej |
| A3 | Te same trzy pola ustawiane w kółko dla trzech powtarzalnych sytuacji | brak zestawów | **GOŚĆ NA JEDEN LOT / KTOŚ Z ZESPOŁU / EKRAN STACJI** — jeden klawisz ustawia rolę, ważność i jednorazowość; pola zostają widoczne |
| A4 | „do 21:03" nie mówi, czy to za pięć minut, czy jutro | ważność pokazywana jako godzina bezwzględna | **„jeszcze 43 min"**, godzina w podpowiedzi |
| A5 | Rola `admin` wydawała się jak każda inna | brak ostrzeżenia | wybór roli `admin` mówi wprost, co oddaje; kod QR admina ma własne ostrzeżenie |
| A6 | „POKAŻ LINK" dawał tekst, a QR trzeba było wydać od nowa | — | ten sam klawisz otwiera pełny blok z QR (**POKAŻ QR**) |

### Kod QR — trzy drogi, bo trzy sytuacje

* **POWIĘKSZ** — gość stoi obok: kod na pół ekranu skanuje się z dwóch metrów.
* **NA PENDRIVE** — gościa nie ma: PNG plus `.txt` z linkiem lądują na nośniku
  zamontowanym przez pulpit w `/media/gcs` (`server/nosniki.mjs`).
* **WYŚLIJ / KOPIUJ ADRES** — panel otwarty na telefonie albo laptopie: arkusz
  udostępniania systemu (`navigator.share`) albo schowek.

⛔ **Kod QR jest kluczem.** Kto go sfotografuje, ten wejdzie. Dlatego: pokazuje się
dopiero po wydaniu albo po naciśnięciu POKAŻ QR, przy roli `admin` mówi wprost,
co oddaje, a zgranie na nośnik zostawia wpis w dzienniku dostępu („zgrano kod na
nośnik X"). Zapis idzie **wyłącznie** pod ścieżkę, którą system melduje jako
zamontowany nośnik, a nazwa pliku jest przepisywana na bezpieczny zestaw znaków.

⚠ Kodu **połączeniowego** (`D15-…`) świadomie nie zamieniamy na QR: to nie jest
adres, więc aparat telefonu pokazałby tylko tekst. QR robimy z linku, który po
zeskanowaniu otwiera stronę i wpuszcza.

## Źródła — co poprawiono

| # | Objaw | Co zrobiono |
| --- | --- | --- |
| S1 | Adres RTSP to jedyne miejsce w panelu, gdzie trzeba wklepać ciąg znaków bez pomyłki — pokrętłem, literę po literze | **wzorce**: TOR ANALOGOWY CVBS i GŁOWICA ZR30 wypełniają nazwę i adres; pola zostają edytowalne |
| S2 | Zły adres = nieaktywny klawisz DODAJ i cisza | podpowiedź mówi, czego brakuje, i przypomina, że dron nadający do stacji to inny rodzaj |
| S3 | „czy ten dron naprawdę nadaje" rozstrzygała dioda w tabeli | **PODEJRZYJ** — zamyka panel i pokazuje to źródło na pełnym ekranie (dla ukrytych nieaktywne, z wyjaśnieniem) |

## Propozycje, których nie wykonano

1. **Odcięcie i zamknięcie drzwi jednym ruchem.** Dziś odcięcie żetonu jest
   w karcie DOSTĘP, a unieważnienie kodu w ZAPROSZENIACH — przy kodzie wielokrotnym
   odcięty wraca tym samym kodem. Panel to opisuje, ale opis nie jest zabezpieczeniem.
   Proponuję przy odcinaniu pytać „unieważnić też zaproszenie, z którego przyszedł?".
2. **Kto ma wstęp, choć nie ogląda.** Serwer zna listę żetonów (`/api/admin/stan`),
   panel pokazuje wyłącznie tych, którzy patrzą teraz. Lista „wpuszczonych" z datą
   ostatniego wejścia dałaby odpowiedź na pytanie, które pada przed wyjazdem:
   *kto w ogóle ma dostęp do tej stacji?*
3. **Dwa kliknięcia na NOWE HASŁO.** Jeden klik unieważnia adres wpisany
   w aparaturze — powinien uzbrajać się jak USUŃ.
4. **QR do danych aparatury — świadomie NIE.** Kusi, ale: kod niósłby hasło źródła
   na ekranie stacji, a Pilot 2 i tak nie ma skanera. Właściwe miejsce na skaner to
   nasz APK Horyzont — wtedy QR z adresem RTMP zdejmie przepisywanie 24 znaków po
   stronie aparatury. Do rozważenia razem z pracami nad Horyzontem.
5. **Mapa bez internetu.** Kafelki ciągnie przeglądarka widza (świadoma decyzja,
   `Mapa.jsx`), więc na stanowisku bez internetu widać same znaczniki na pustym tle.
   Mapa mówi o tym wprost i to jest uczciwe, ale operator w terenie i tak zostaje
   bez podkładu. Kierunek: podkład z pendrive'a albo z karty stacji (MBTiles
   serwowane lokalnie) — osobna praca, nie poprawka panelu.
6. **Jedno miejsce „co jest włączone".** Stan kiosku, monitorów, archiwum i usług
   jest rozrzucony między kartę ARCHIWUM, DIAGNOSTYKĘ i panel STACJA. Przy trzech
   ekranach na stanowisku brakuje jednej kartki „co teraz chodzi".

## Co doszło w kodzie

```
serwer/server/nosniki.mjs          nośniki wymienne: lista i zapis (nowy)
serwer/server/index.mjs            /api/admin/nosniki, /api/admin/nosniki/zapisz
serwer/web/src/admin/qr.js         rysowanie kodu QR na płótnie (nowy)
serwer/web/src/admin/KodQr.jsx     komponent: podgląd, powiększenie, pendrive, wysyłka (nowy)
serwer/web/src/admin/Zaproszenia.jsx  wybór adresu, zestawy, QR, „jeszcze N min"
serwer/web/src/admin/NoweZrodlo.jsx   wzorce adresów RTSP i podpowiedź przy błędzie
serwer/web/src/admin/Zrodla.jsx       PODEJRZYJ
serwer/web/src/{App,Admin}.jsx        przekazanie podglądu źródła
serwer/tests/nosniki.test.mjs      testy nośników (nowy)
```

Zależność: `qrcode-generator` (MIT, **bez zależności przechodnich**). Pakiet strony
urósł z 430 kB do 459 kB (gzip 131 → 142 kB) — cena za kod QR rysowany na miejscu,
bez internetu i bez usługi zewnętrznej.

## Stan sprawdzenia

* `node --test tests/*.test.mjs` — **25 przypadków, wszystkie przechodzą**
  (doszło 5 o nośnikach: co uznajemy za pendrive, przepisywanie nazwy pliku,
  przyjmowanie wyłącznie PNG, odmowa zapisu poza nośnikiem).
* eslint bez błędów, `vite build` przechodzi.
* Punkty API sprawdzone na żywym serwerze: `/api/admin/nosniki` bez żetonu daje 401,
  z żetonem admina oddaje pustą listę (maszyna deweloperska), a próba zapisu na
  nieistniejący nośnik kończy się czytelnym 400.

⚠ Na stanowisku trzeba jeszcze sprawdzić trzy rzeczy, których stąd nie widać:
czy telefon skanuje kod z ekranu stacji przy świetle dziennym, czy zapis na
pendrive'a zamontowanego przez pulpit przechodzi prawami konta usługi, i czy
klawisze nowego bloku dają się obsłużyć pokrętłem bez gubienia ogniska.
