# Audyt Panoramy — 2026-09-22

Przegląd serwera (`serwer/server/*.mjs`, 4527 linii), PWA (`serwer/web/src`), generatora
`mediamtx.yml`, jednostek systemd i sudoers. Aplikacji Android (`app/`) nie obejmuje.
Wszystkie usterki z tej listy są **naprawione w repozytorium**; stan wdrożenia na GSB —
na końcu.

## Usterki i poprawki

| # | Co było nie tak | Gdzie naprawione |
| --- | --- | --- |
| 1 | **Wyłączenie nagrywania nie wyłączało nagrywania.** Ścieżki dosyłamy do MediaMTX metodą PATCH (scalenie), a konfiguracja źródła pobieranego nie niosła klucza `record`, gdy archiwum było wyłączone — więc `record: yes` zostawało po staremu i stacja pisała na kartę do restartu usługi OBRAZ. Do tego panel przepisywał ścieżki tylko przy zmianie trybu wideo, a nie przy odhaczeniu całego archiwum. | `scripts/zrodla-lib.mjs` (`record: false` zawsze), `server/index.mjs` (warunek obejmuje `wlaczone`) |
| 2 | **Telemetria DJI nie docierała do HUD.** `useTelemetria` wołał `adresTelemetrii()` bez źródła, więc strumień SSE nigdy nie niósł `?zrodlo=`, a serwer zawsze wybierał MAVLink z DRON 15. Cały most MQTT był w tej drodze martwy. | `web/src/useTelemetria.js` (źródło przy zestawieniu), `server/index.mjs` + `server/obecnosc.mjs` (dostawca liczony przy każdej migawce, z meldunku obecności) |
| 3 | **Ukryte źródło dawało się oglądać.** `/api/mtx-auth` sprawdzał sam żeton — nie patrzył, czy ścieżka w ogóle istnieje i czy źródło jest widoczne. Identyfikatory powstają z nazwy, więc zgadnięcie było łatwe. | `server/index.mjs`, `scripts/zrodla-lib.mjs` (`zrodloSciezki`) |
| 4 | **`trust proxy: true` bez pośrednika.** `req.ip` brał się z nagłówka `X-Forwarded-For`, czyli od klienta — a na adresie stoją trzy decyzje: pętla zwrotna w `mtx-auth`, „tylko z ekranu stacji" przy zamykaniu podglądu i adresy w dzienniku dostępu. | `server/index.mjs` |
| 5 | **Dwa z ośmiu testów padały** na `TypeError` (atrapa gniazda bez `end()`), czyli ścieżka złego hasła do odbiornika zrzutu nie była realnie sprawdzana. | `tests/zrzut.test.mjs` |
| 6 | **`dostep.json` z sekretami żetonów zapisywany z prawami 0644**, choć `nadawanie.json` i `dji.json` miały 0600 od początku. | `server/dostep.mjs` |
| 7 | **Wyciek zegarów w archiwum:** `start()` woła panel po każdym zapisie ustawień, a każde wywołanie zakładało kolejny `setInterval` bez skasowania poprzedniego. | `server/archiwum.mjs` |
| 8 | **Pokrętło rozpoznawane po imieniu**, nie po żetonie — dwa urządzenia z tego samego zaproszenia podbierały je sobie, a porzucony strumień SSE zostawał otwarty. | `server/index.mjs` |
| 9 | **Nic nie kasowało żetonów.** Lista rosła z każdym wejściem, `ostatnioWidziany` nie trafiał na dysk, a mapa adresów żetonu nie była zwalniana po odcięciu. | `server/dostep.mjs` (`sprzatajZetony`), `server/index.mjs` |
| 10 | **Mozaika trzymała pętlę ponawiania** — pełnoekranowy odtwarzacz dostawał wymyśloną ścieżkę „brak" i pukał do MediaMTX co trzy sekundy. | `web/src/useWhep.js`, `web/src/App.jsx` |
| 11 | **SSE bez oglądania się na zaległości:** dziesięć migawek na sekundę wpisywanych wolnemu klientowi rosło w buforze serwera bez końca. | `server/index.mjs` (`PROG_ZALEGLOSCI_B`) |
| 12 | **Katalog danych liczony dwojako:** `nadawanie.mjs` i `dji.mjs` miały własne `process.env.DATA_DIR || "."`, reszta stacji liczy od katalogu kodu. | `server/nadawanie.mjs`, `server/dji.mjs` |
| 13 | **Spis archiwum przechodzony przy każdym odpytaniu panelu** (co kilka sekund, `statSync` na każdym pliku, a odcinki są dziesięciominutowe). | `server/archiwum.mjs` (spis ważny 10 s, sprzątanie bierze świeży) |

Osobno, z tego samego dnia, usterka pokrętła opisana w [POKRETLO.md §3](POKRETLO.md):
kafelek pulpitu bez `"pokretlo": "wlasne"` + milczenie mostu czytane jako zgoda.

## Testy

`node --test tests/*.test.mjs` — **20 przypadków, wszystkie przechodzą**. Doszły:

- `tests/pokretlo.test.mjs` — potwierdzanie przejęcia ogniska, ponowienia, odmowa,
- `tests/zrodla.test.mjs` — `record: false` przy wyłączonym nagrywaniu, ścieżka główna
  kontra pomocnicza, przypisanie ścieżki do źródła,
- `tests/dostep.test.mjs` — sprzątanie żetonów, odcięcie, trwałość „ostatnio widziany".

## Co zostaje świadomie

- Port 5601 (odbiornik zrzutu ekranu) wystawiony do internetu, hasło jawne w protokole —
  decyzja z [PANORAMA_INTERNET_DJI.md](PANORAMA_INTERNET_DJI.md);
- broker MQTT 1883 bez TLS, tożsamością jest jeden klucz stacji;
- żeton w adresie strumienia SSE (EventSource nie umie nagłówków) —
  [DOSTEP_I_UZYTKOWNICY.md §7](DOSTEP_I_UZYTKOWNICY.md).

## Zależności

`npm audit` pokazywał trzy wpisy o średniej wadze i wszystkie prowadziły do jednej
paczki: **`qs`**, parsera ciągu zapytania, który Express wciąga przez siebie i przez
`body-parser` ([GHSA-x5fp-wj9c-mxmx](https://github.com/advisories/GHSA-x5fp-wj9c-mxmx),
[GHSA-4mjr-xmp4-gh2g](https://github.com/advisories/GHSA-4mjr-xmp4-gh2g)). Obie klasy to
wyczerpanie zasobów procesu — bez wycieku danych i bez wykonania kodu — ale **do
parsowania dochodzi przed uwierzytelnieniem**: `wezZeton()` sięga po `req.query.zeton`,
więc wystarczy dosięgnąć portu 8095 (czyli być w LAN albo w tunelu; 8095 nie jest
wystawiony do internetu). Na stacji znaczyłoby to przewrócony proces i kilka sekund
bez obrazu — w locie to nie jest drobiazg.

⛔ **`npm audit fix` był tu bezsilny i mylił.** Nie zmieniał ani jednej paczki:
`express@4.22.2` i `body-parser@1.20.6` trzymają `qs ~6.15.1`, a w całej linii 6.15.x
nie ma wydania z łatą — poprawka siedzi dopiero w `qs@6.16.0`. Wyjście mieści się
w zadeklarowanym `^4.21.2`, więc `package.json` zostaje nietknięty, rusza się sam lock:

```bash
npm update express body-parser   # 4.22.3 + 1.20.8, oba na qs ~6.16.0
```

Po tym `npm ci` daje jedną kopię `qs@6.16.0` w drzewie i `found 0 vulnerabilities`.

## Stan wdrożenia

Wszystko wgrane na GSB (`rpi/wgraj.ps1 -Restart`) i sprawdzone na miejscu:

- MediaMTX ma `record: false` na czterech ścieżkach, zgodnie z `archiwum: nie` —
  wcześniej wymagało to restartu usługi OBRAZ;
- `/var/lib/panorama/dostep.json` ma prawa `0600` (na Windows `chmod` nic nie robi,
  więc potwierdzić dało się to dopiero na malinie);
- sprzątanie przy starcie usunęło 8 żetonów;
- `express 4.22.3`, `body-parser 1.20.8`, `qs 6.16.0`, `npm audit` czysty;
- obie usługi wstały bez wyjątku, most pokrętła zestawiony.

⚠ Trzech rzeczy nie da się potwierdzić zdalnie i czekają na próbę przy stanowisku:
telemetria na kafelku DJI (potrzebny nadający dron), odmowa obrazu ze źródła ukrytego
dla żetonu widza i samo przejęcie pokrętła z kafelka pulpitu.
