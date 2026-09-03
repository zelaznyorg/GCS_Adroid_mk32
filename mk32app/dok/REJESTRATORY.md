# Rejestratory na stacji — kto nagrywa co i skąd bierze obraz

Stan po 2026-09-03. Na jednej malinie (`192.168.88.30`) działają **trzy** rzeczy,
które potrafią zapisywać obraz. Do dziś dwie z nich nagrywały **to samo, dwa razy,
ciągnąc kamerę dwoma osobnymi strumieniami przez łącze radiowe**. Ten dokument
opisuje podział ról, który to kończy, i to, co zostało zmierzone.

---

## 1. Trzy rejestratory — czym się różnią

| | **NAGRYWARKA** (pulpit `gcs_pulpit`) | **archiwum stacji** DRON 15 | `pi5-camera-recorder` (`PI5setup full`) |
|---|---|---|---|
| kafelek | **⏺ NAGRYWARKA** — wbudowany w pulpit, nie w `aplikacje.d/` | panel ADMIN → ARCHIWUM | `KAMERA CVBS + HUD` |
| co nagrywa | **źródła IP z listy** układanej z ekranu | strumienie MediaMTX + telemetrię `.tlog` | **wejście analogowe** z karty przechwytującej |
| silnik | osobny `ffmpeg -c copy` na źródło | MediaMTX `record` | własny |
| zapis | `/var/lib/gcs/nagrania/<id>/<data>.mkv` | `/var/lib/dron15/archiwum` | **karta SD `/media/fpv-recordings`** (3,8 GB) |
| sterowanie | kafelek, `⏺ REC` na belce, **strona na panelu GC9A01** | tryb `nie / przy-widzach / zawsze` | przycisk w HUD |
| lista źródeł | `~/.config/gcs/zrodla-obrazu.json` | `/var/lib/dron15/zrodla.json` | — |

⛔ `/media/fpv-recordings` należy do rejestratora Toma — **nie piszemy tam**.
⛔ Plików `PI5setup full` (`/opt/pi5setup-full`, `/etc/pi5setup-full`) **nie ruszamy**.

> ### Błędne założenie, które kosztowało pół dnia
>
> Przez długi czas dokumentacja i rozmowa zakładały, że „nagrywarka" to rejestrator
> **analogowy** i że DJI ani ZR30 „nie ma czym do niej wpiąć". To było nieprawdą:
> NAGRYWARKA pulpitu jest **rejestratorem IP**, ZR30 była na jej liście od początku,
> a obraz analogowy trafia do niej jako **strumień IP** z `pi5-uas-rtsp`
> (`rtsp://127.0.0.1:8554/uav`, H.264 640×480, 30 kl./s, 2 Mb/s). Kafelka nie było
> widać w `aplikacje.d/`, bo jest **wbudowany w kod pulpitu**
> (`/opt/gcs/pulpit/gcs_pulpit/nagrywarka.py`, `nagrywanie.py`, `nagrania.py`).

---

## 2. Podział ról — decyzja z 2026-09-03

**NAGRYWARKA jest jedynym rejestratorem obrazu. Stacja jest rozdzielnią strumieni.**

```
ZR30 ──radio──► MediaMTX (stacja) ──┬──► WebRTC :8889 ──► widzowie (żeton)
DJI  ──RTMP:1935──►                 └──► RTSP 127.0.0.1:8555 ──► NAGRYWARKA (ffmpeg)
CVBS ──pi5-uas-rtsp :8554 ───────────────────────────────────► NAGRYWARKA
```

| Co | Skutek |
|---|---|
| MediaMTX wystawia **RTSP na pętli zwrotnej `127.0.0.1:8555`**, sam TCP | każdy lokalny odbiorca bierze kopię ze stacji; kamera pobierana **raz** |
| NAGRYWARKA ma ZR30 pod `rtsp://127.0.0.1:8555/zr30` zamiast wprost z `.144.25` | koniec drugiego strumienia przez radio i drugiego z 4 slotów ZR30 |
| NAGRYWARKA dostała `dji` i `dji2` (`wlaczone: false`) | obraz z DJI nagrywa się tym samym mechanizmem, gdy operator go włączy |
| archiwum stacji: **`wideo: nie`** | `.tlog` zostaje; obraz pisze wyłącznie nagrywarka |

Port **8555**, nie 8554 — ten trzyma `pi5-uas-rtsp`.

⚠ **Dlaczego `dji`/`dji2` są domyślnie poza REC:** ścieżka nadawana bez nadawcy daje
`404` i ffmpeg nagrywarki melduje „zapis przerwany". Gdyby były włączone, każde
`⏺ REC` bez drona DJI w powietrzu kończyłoby się fałszywym alarmem. Włącza się je
w NAGRYWARCE jednym dotknięciem („▸ przełącz") na czas lotu DJI.

⚠ **Dlaczego archiwum na `nie`, a nie `przy-widzach`:** dla MediaMTX ffmpeg
nagrywarki jest *widzem*. W trybie `przy-widzach` każde `⏺ REC` uruchamiałoby
u nas drugie nagranie tego samego strumienia — dokładnie ta duplikacja, którą
chcieliśmy usunąć. Zmierzone przy próbie (§4): 20 s wzorca testowego → plik
`archiwum/wideo/dji/2026-09-03_15-54-32.mp4`, 555 kB.

---

## 3. Uwierzytelnianie — dlaczego RTSP z pętli nie potrzebuje żetonu

`server/index.mjs`, `/api/mtx-auth`: **odczyt po protokole `rtsp` z `127.0.0.1`
jest dozwolony bez żetonu widza.** Warunek jest na **protokół**, nie na sam adres:
przeglądarka pulpitu też pyta z `127.0.0.1`, ale po WebRTC — i ona ma się nadal
legitymować, bo tak działa „odetnij widza".

Bezpieczne, bo nasłuch RTSP jest przypięty do pętli zwrotnej w generowanym
`mediamtx.yml` — z sieci tą drogą nikt nie wejdzie. Panel STACJA sprawdza to
osobno: każdy port „TYLKO lokalnie" (9997, 8555), który odpowie pod adresem
zewnętrznym, dostaje czerwoną sekcję `⛔ PORT … WYSTAWIONY NA SIEĆ`.

---

## 4. Zmierzone na stacji, 2026-09-03

Po wgraniu i restarcie (`dron15-mediamtx`, `dron15-gcs`):

| Sprawdzenie | Wynik |
|---|---|
| `ss -ltnp` | `127.0.0.1:8555` — **tylko pętla**; `*:5601` (zrzut ekranu), `*:1935`, `*:1883` |
| MediaMTX | `[RTSP] started with listeners on 127.0.0.1:8555 (TCP/RTSP)`, 3 źródła |
| `DESCRIBE /dji`, `/dji2` | `404 Not Found` od `gortsplib` = ścieżka jest, nadawcy nie ma — **poprawnie** |
| `DESCRIBE /zr30` | `400`, w logu `dial tcp 192.168.144.25:8554: no route to host` — dron wyłączony, **poprawnie** |
| `DESCRIBE /nie-ma-takiej` | `path 'nie-ma-takiej' is not configured` |
| odmowy w dzienniku stacji | **zero** — reguła z §3 przepuściła RTSP z pętli |

**Cały tor DJI, na pętli zwrotnej, bez aparatury:** `ffmpeg` nadaje 20 s wzorca
`testsrc` po RTMP pod `dji` z hasłem urządzenia → stacja: `źródło nadaje pod ścieżkę
dji` → MediaMTX: `stream is available and online, 1 track (H264)` → `ffprobe
rtsp://127.0.0.1:8555/dji` widzi `h264 640×360 15 kl./s` → `ffmpeg -c copy -f null`
czyta 3 s i kończy **kodem 0**. To jest dokładnie ścieżka, którą pójdzie ffmpeg
NAGRYWARKI.

Pulpit przeładowany (`systemctl --user restart gcs-pulpit`) — wstał czysto, nowa
lista źródeł wczytana przy starcie (`wczytaj_zrodla()` czyta plik raz, w konstruktorze
`Nagrywarka`).

⛔ **Niesprawdzone:** naciśnięcie `⏺ REC` w samej NAGRYWARCE na źródle `dji`
przy nadającej aparaturze — wymaga kontrolera DJI albo świadomej zgody na zapis
próbnego pliku do `/var/lib/gcs/nagrania`.

---

## 5. Zegar stacji — ⛔ 18 godzin spóźnienia, bez źródła czasu

Zastane 2026-09-03: `Local time: 2026-09-02 21:01`, `RTC time: 1970-01-01`,
`System clock synchronized: no`. Malina **nie ma zegara na baterii**, a w sieci `.88`
**nie ma żadnego źródła czasu**: brak trasy do internetu (`ping 1.1.1.1` — 100 %
strat), brak DNS, router `192.168.88.1` **nie odpowiada na NTP** (sprawdzone
zapytaniem UDP/123).

Skutek: tą datą podpisują się nagrania (`.mkv`, `.mp4`), `.tlog`, kasowanie po
`trzymajDni` i cały dziennik. Nagranie z lotu nie zejdzie się w czasie z logiem
z kontrolera lotu.

**Zrobione:** zegar ustawiony ręcznie z laptopa (`date -u -s`). **To jest plaster** —
po następnym odłączeniu zasilania malina znów zacznie od 1970.

**Do zrobienia (TODO 2.43):** trwałe źródło czasu — NTP na routerze MikroTik
(`/system ntp server set enabled=yes`) albo moduł RTC z baterią na I²C.

⚠ **Pułapka przy wgrywaniu:** przy spóźnionym zegarze `tar` na malinie ostrzega
o plikach „z przyszłości" na stderr, a PowerShell 5.1 pod `$ErrorActionPreference =
"Stop"` przerywa za to cały skrypt — **w połowie rozpakowywania**. 2026-09-03 zostawiło
to `/opt/dron15` w stanie mieszanym (nowy `index.mjs`, stara biblioteka, brak
`zrzut.mjs`); usługi żyły dalej tylko dlatego, że nie zostały zrestartowane.
`rpi/wgraj.ps1` ma teraz `tar --warning=no-timestamp`.

---

## 6. Martwy plik, którego już nie ma: `web/public/zrodla.json`

Generowany przy każdym starcie, ale strona serwowana jest z `web/dist`, do którego
Vite kopiuje `public/` **wyłącznie w chwili budowania**. Na stacji plik na dysku
był z 2 września i znał `dji`; ten w przeglądarce — z 29 sierpnia, bez `dji`.
Nikt go nie czytał (panel bierze `/api/zrodla` na żywo, z żetonem), więc był
wyłącznie **nieaktualnym, niechronionym spisem nazw źródeł**. Usunięty razem
z `generateWebConfig()`.

---

## 7. Co dalej — wydzielenie NAGRYWARKI jako osobnej aplikacji (TODO 2.44)

Kod nagrywarki żyje dziś **tylko na malinie**, w `~/gcs-src/pulpit/gcs_pulpit/`
(brak repozytorium git, brak zdalnego), i jest zszyty z pulpitem przez `okno.py`
(belka `⏺ REC`, kafelki, protokół panelu GC9A01). Decyzja Toma: **wydzielić ją do
osobnego katalogu w Smart GCS**, żeby dało się ją rozwijać niezależnie. Zakres,
sprzężenia i plan — do opisania po zdjęciu źródeł z maliny.
