# Tor wideo — od ZR30 do dowolnego miejsca

> **Podział obowiązków (wersja 3.0 planu).** Podgląd przez sieć i dodatkowe monitory
> to **funkcja stacji GCS**, nie aplikacji na MK32. Stacja bierze obraz **prosto z ZR30**,
> bo kamera wydaje do czterech strumieni naraz — aparatura nie musi nic przepakowywać.
> MK32 zajmuje się retransmisją **tylko w wariancie „stacja zdalna"**, gdy stacja nie ma
> dostępu do sieci pokładowej. Wtedy dotyczy go wszystko poniżej.

---

## 1. Decyzja, na której stoi wszystko: nie transkodujemy

Kamera ZR30 **sama jest koderem**. Komenda SDK `0x21` ustawia format strumienia
(instrukcja ZR30 v1.4, rozdz. 3.5.2 — **FAKT**):

| Pole | Wartości |
|---|---|
| `stream_type` | 0 = nagrywanie, 1 = główny, 2 = podgląd |
| `VideoEncType` | 1 = H.264, 2 = H.265 |
| `Resolution_L/H` | 1920×1080, 1280×720 |
| `VideoBitrate` | kbps |

Trzy strumienie są **niezależne**. Można więc mieć jednocześnie:

- **nagrywanie** 4K H.265 na karcie w kamerze — pełna jakość materiału,
- **główny** H.264 1280×720 @ 2 Mbps — do transmisji, przechodzi wszędzie bez zmian.

Ustawienie:
```bash
python narzedzia\siyi_gimbal.py setcodec --strumien glowny --kodek H264 --rozdzielczosc 1280x720 --bitrate 2000 --ruch
```
Odczyt stanu: `python narzedzia\siyi_gimbal.py codec --strumien glowny`

> **HIPOTEZA do sprawdzenia w M0:** instrukcja zaznacza, że `stream_type = 2` (podgląd)
> działa **tylko na ZT30 i ZT6**. Na ZR30 sterowalny jest prawdopodobnie wyłącznie strumień
> główny — a adres `/video2` i tak istnieje. Trzeba sprawdzić, co naprawdę odpowiada.
> To decyduje, czy kokpit i relay biorą ten sam strumień, czy dwa różne.

**Czemu to tak ważne.** Przekodowanie 4K H.265 → H.264 na Androidzie 9 z 4 GB RAM
zajęłoby procesor na stałe, dorzuciło 200–400 ms i grzało aparaturę przez cały lot.
Przepakowanie (`-c copy`) to przepisywanie nagłówków — koszt bliski zeru.

---

## 2. Cztery drogi obrazu

| Odbiorca | Droga | Opóźnienie | Uwaga |
|---|---|---|---|
| kokpit na MK32 | RTSP prosto z ZR30, **własny klient + MediaCodec** | 100–200 ms | nie przechodzi przez serwer — działa nawet gdy relay padnie. libVLC odpadło, patrz §2a |
| drugi monitor | wyjście HDMI aparatury | jak wyżej | zero pracy po naszej stronie |
| komputer w LAN | serwer RTSP na MK32 albo MPEG-TS po UDP | 150–300 ms | `restream.py udp --cel 192.168.1.50:5000` |
| **internet** | **SRT → serwer pośredniczący → WebRTC/HLS/RTSP** | 400–900 ms | patrz niżej |

ZR30 potrafi wydać **do czterech strumieni z tego samego adresu RTSP**
(instrukcja, rozdz. 2.3.3 — **FAKT**), więc kokpit i relay nie biją się o kamerę.

---

### 2a. Dlaczego kokpit nie używa już libVLC — pomiar 2026-08-28

Pierwsza wersja brała obraz przez libVLC. Na żywej maszynie obraz **zacinał się 8–19 razy
na minutę**, przy gładkim obrazie w fabrycznej aplikacji SIYI — czyli wina nie leżała
po stronie kamery ani radia. Przyczyna: libVLC pilnuje zegara RTP i przy spóźnieniu
ok. 90 ms wyrzuca klatkę albo przebudowuje bufor, a na tym łączu takie spóźnienia
zdarzają się stale. Nie da się tego wyłączyć — `--clock-jitter=0` tylko **przestaje
o tym meldować**, dalej gubiąc klatki.

Zastąpione własnym torem: `video/OdtwarzaczRtsp` (RTSP, RTP przeplatany po TCP,
rozpakowanie RFC 6184, kolejka wyrównująca) + `video/RysownikH264` (`SurfaceView`
i `MediaCodec`, klatka wyświetlana natychmiast po zdekodowaniu).

⚠ **Adres jest `rtsp://192.168.144.25:8554/main.264`**, nie `/video1` — ten drugi
odpowiada `404 Stream Not Found`. Instrukcja podaje oba, działa jeden.

⚠ **Wartości `VideoEncType` z tabeli wyżej są prawdziwe i aplikacja miała je błędnie**
(0/1 zamiast 1/2), a ładunek `CMD 0x21` musi mieć **9 bajtów** — brakujący `reserve`
zawiesił kamerę na głucho do cyklu zasilania. Klawisze rozdzielczości w kokpicie
zostały z tego powodu wyłączone.

⚠ **Kamera ignoruje żądany bitrate strumienia głównego** — odpowiada „sukces" i zostaje
przy 1570 kb/s (sprawdzone dla 2000 i 1000). Kodek i rozdzielczość przyjmuje.

Pełny przebieg dochodzenia, z tabelą wariantów i trzema błędami rozpakowywania:
[SESJA_20260828.md](SESJA_20260828.md) §7.

---

## 3. Dlaczego przez serwer pośredniczący

Karta SIM w MK32 dostaje adres **za CGNAT operatora**. Oznacza to, że połączenie
przychodzące z internetu do aparatury jest niemożliwe — nie ma czego przekierować.
Ruch musi **wychodzić** z MK32 do maszyny o stałym adresie.

```
  MK32 (4G, za CGNAT) ──SRT push──► VPS z publicznym IP ──► WebRTC │ HLS │ RTSP
                                     MediaMTX + nagrywanie          ▼
                                                          przeglądarka, telefon, biuro
```

**Czemu SRT, a nie RTMP czy RTSP:**

- RTMP w standardzie nosi tylko H.264 — H.265 odpada, a i tak nie ma retransmisji
- RTSP po TCP na sieci komórkowej zacina się przy każdym zgubionym pakiecie
- **SRT** ma bufor czasowy i retransmisję pakietów — jest zaprojektowany dokładnie do
  przesyłania obrazu przez łącza, które gubią dane; nosi MPEG-TS, więc i H.264, i H.265
- do tego szyfrowanie hasłem jednym parametrem

Bufor SRT (`--opoznienie`) to wymiana odporności za opóźnienie: 200 ms na dobrym LTE,
400–500 ms na słabym zasięgu.

**Alternatywa dla sieci prywatnej:** Tailscale/WireGuard. Wtedy VPS nie jest konieczny
do samego obrazu, bo MK32 i odbiorca widzą się bezpośrednio. VPS nadal ma sens, gdy
widzów jest wielu — jedno wysłanie z 4G zamiast N.

---

## 4. Budżet pasma

Łącze 4G w terenie bywa niesymetryczne, a liczy się **wysyłanie**.

| Ustawienie | Pasmo w górę | Kiedy |
|---|---|---|
| 1280×720 H.264 2 Mbps | ~2,3 Mbps z narzutem | domyślne |
| 1280×720 H.264 1 Mbps | ~1,2 Mbps | słaby zasięg |
| 1920×1080 H.264 4 Mbps | ~4,5 Mbps | tylko przy pewnym zasięgu |

Serwer ma obserwować `srt_rtt_ms` i `srt_straty_proc` (są w API stanu) i **schodzić
z przepływnością sam**, komendą `0x21` do kamery — bo to kamera koduje, więc zmiana
jakości nie kosztuje MK32 nic.

---

## 5. Narzędzia

```bash
# co naprawdę nadaje kamera
python narzedzia\restream.py sprawdz

# wysyłka w świat
python narzedzia\restream.py srt --cel ADRES_VPS:8890 --haslo TAJNE

# drugi monitor w tej samej sieci
python narzedzia\restream.py udp --cel 192.168.1.50:5000

# samo polecenie, bez uruchamiania — do przeniesienia na Androida
python narzedzia\restream.py srt --cel ... --pokaz-tylko-polecenie
```

Flagi wejścia w `restream.py` (`-fflags nobuffer -flags low_delay -avioflags direct`)
są dobrane pod minimalne opóźnienie i **mają trafić bez zmian do wersji na Androidzie** —
to ten sam ffmpeg, tylko wkompilowany w aplikację.

---

## 6. Otwarta decyzja: czym przepakować na Androidzie

`ffmpeg-kit`, dotąd standardowy sposób na ffmpeg w aplikacji Android, został przez autora
**porzucony**. Gotowe pakiety działają, ale poprawek nie będzie. Trzy wyjścia:

1. **zostać przy `ffmpeg-kit`** — najszybciej, ryzyko długu technicznego
2. **własna kompilacja ffmpeg** — pełna kontrola, kosztowna w utrzymaniu
3. **napisać przepakowanie w Kotlinie** — skoro nie dekodujemy, robota sprowadza się do
   odbioru RTP, złożenia jednostek NAL i zapakowania w MPEG-TS. Mniej kodu, niż się wydaje,
   i zero zależności natywnych

Do rozstrzygnięcia w **M3**, po zmierzeniu, jak zachowuje się łącze 4G w terenie.
