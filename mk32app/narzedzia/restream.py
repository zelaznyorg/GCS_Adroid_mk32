#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Retransmisja obrazu z ZR30 w dowolne miejsce sieci — prototyp modulu VideoRelay.

Zalozenie, na ktorym stoi calosc: NIE TRANSKODUJEMY. Kamera ZR30 sama potrafi kodowac
H.264 720p (komenda SDK 0x21, patrz siyi_gimbal.py setcodec), wiec MK32 ma tylko
przepakowac gotowy strumien. Transkodowanie 4K H.265 na Androidzie 9 z 4 GB RAM
zjadloby procesor i dorzucilo kilkaset ms opoznienia.

    ZR30 --RTSP--> [ przepakowanie -c copy ] --SRT/RTSP/UDP--> serwer posredniczacy
                                                                    |
                                              przegladarka, drugi monitor, biuro

Dlaczego SRT do wyjscia w swiat: karta SIM w MK32 dostaje adres za CGNAT operatora,
wiec NIKT nie polaczy sie do MK32 od zewnatrz. Polaczenie musi wychodzic z MK32
na serwer publiczny, a SRT znosi straty pakietow 4G duzo lepiej niz RTSP czy RTMP.

Uzycie:
    python restream.py sprawdz
    python restream.py srt  --cel 203.0.113.10:8890 --haslo tajne
    python restream.py rtsp --cel 203.0.113.10:8554/dron15
    python restream.py udp  --cel 192.168.1.50:5000        # drugi monitor w tej samej sieci
    python restream.py plik --cel nagranie.mkv
    python restream.py srt  --cel ... --pokaz-tylko-polecenie
"""

import argparse
import json
import shutil
import subprocess
import sys

# Wspolny rejestr bledow lezy w tools\ (mk32app\dok\LOGI_I_BLEDY.md).
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[2] / "tools"))
from dziennik import zainstaluj  # noqa: E402

zainstaluj("restream")

ZRODLO_DOMYSLNE = "rtsp://192.168.144.25:8554/video1"
KRESKA = "-" * 72

# Flagi wejscia dobrane pod male opoznienie. Te same trafia do ffmpeg-kit na Androidzie.
WEJSCIE = [
    "-rtsp_transport", "tcp",
    "-fflags", "nobuffer",
    "-flags", "low_delay",
    "-avioflags", "direct",
    "-probesize", "500000",
    "-analyzeduration", "1000000",
]


def sprawdz_narzedzia():
    braki = [n for n in ("ffmpeg", "ffprobe") if shutil.which(n) is None]
    if braki:
        print("Brak w PATH: %s" % ", ".join(braki))
        print("Windows: winget install Gyan.FFmpeg")
        print("Na MK32 rownowaznikiem bedzie biblioteka ffmpeg wkompilowana w aplikacje.")
        return False
    return True


def sprawdz_zrodlo(zrodlo):
    print(KRESKA)
    print("ZRODLO: %s" % zrodlo)
    print(KRESKA)
    if not sprawdz_narzedzia():
        return None
    polecenie = ["ffprobe", "-v", "error", "-rtsp_transport", "tcp",
                 "-select_streams", "v:0", "-show_entries",
                 "stream=codec_name,width,height,avg_frame_rate,bit_rate",
                 "-of", "json", zrodlo]
    try:
        wynik = subprocess.run(polecenie, capture_output=True, text=True, timeout=15)
    except subprocess.TimeoutExpired:
        print("ffprobe nie doczekal sie odpowiedzi. Sprawdz zasilanie ZR30 i siec.")
        return None
    if wynik.returncode != 0:
        print("ffprobe zwrocil blad:\n%s" % wynik.stderr.strip())
        return None
    try:
        strumien = json.loads(wynik.stdout)["streams"][0]
    except (KeyError, IndexError, ValueError):
        print("Nie udalo sie odczytac opisu strumienia.")
        return None

    kodek = strumien.get("codec_name", "?")
    print("kodek        : %s" % kodek)
    print("rozdzielczosc: %sx%s" % (strumien.get("width"), strumien.get("height")))
    print("klatki       : %s" % strumien.get("avg_frame_rate"))
    print("bitrate      : %s" % strumien.get("bit_rate", "nie podano"))
    print()
    if kodek == "hevc":
        print("UWAGA: H.265.")
        print("  - do SRT i UDP nadaje sie bez zmian (MPEG-TS niesie H.265)")
        print("  - do RTMP i do wiekszosci przegladarek NIE nadaje sie")
        print("  - zamiast transkodowac, przestaw kamere:")
        print("    python siyi_gimbal.py setcodec --kodek H264 --rozdzielczosc 1280x720 \\")
        print("           --bitrate 2000 --strumien glowny --ruch")
    else:
        print("H.264 — przechodzi wszedzie bez transkodowania.")
    return strumien


def zbuduj_polecenie(tryb, zrodlo, cel, haslo, opoznienie, transkoduj):
    kodowanie = ["-c", "copy"]
    if transkoduj:
        kodowanie = ["-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                     "-b:v", "2000k", "-g", "50", "-pix_fmt", "yuv420p"]

    if tryb == "srt":
        adres = "srt://%s?mode=caller&transtype=live&latency=%d" % (cel, opoznienie * 1000)
        if haslo:
            adres += "&passphrase=%s&pbkeylen=16" % haslo
        wyjscie = ["-f", "mpegts", adres]
    elif tryb == "rtsp":
        wyjscie = ["-f", "rtsp", "-rtsp_transport", "tcp",
                   cel if cel.startswith("rtsp://") else "rtsp://%s" % cel]
    elif tryb == "udp":
        wyjscie = ["-f", "mpegts", "udp://%s?pkt_size=1316" % cel]
    elif tryb == "plik":
        wyjscie = ["-f", "matroska", cel]
    else:
        raise ValueError(tryb)

    return ["ffmpeg", "-hide_banner", "-loglevel", "warning", "-stats"] + \
           WEJSCIE + ["-i", zrodlo, "-an"] + kodowanie + wyjscie


def main():
    p = argparse.ArgumentParser(description="Retransmisja obrazu z ZR30")
    p.add_argument("tryb", choices=["sprawdz", "srt", "rtsp", "udp", "plik"])
    p.add_argument("--zrodlo", default=ZRODLO_DOMYSLNE)
    p.add_argument("--cel", help="host:port albo sciezka pliku")
    p.add_argument("--haslo", help="passphrase SRT — bez tego strumien leci otwarty")
    p.add_argument("--opoznienie", type=int, default=200,
                   help="bufor SRT w ms; 4G potrafi wymagac 300-500")
    p.add_argument("--transkoduj", action="store_true",
                   help="ostatecznosc: przekodowanie na H.264 kosztem procesora i opoznienia")
    p.add_argument("--pokaz-tylko-polecenie", action="store_true")
    a = p.parse_args()

    if a.tryb == "sprawdz":
        return 0 if sprawdz_zrodlo(a.zrodlo) else 1

    if not a.cel:
        print("Podaj --cel")
        return 2
    if a.tryb == "srt" and not a.haslo:
        print("[!] SRT bez --haslo: obraz poleci otwartym tekstem przez internet.\n")

    polecenie = zbuduj_polecenie(a.tryb, a.zrodlo, a.cel, a.haslo, a.opoznienie, a.transkoduj)
    print(KRESKA)
    print(" ".join(polecenie))
    print(KRESKA)
    if a.pokaz_tylko_polecenie:
        return 0
    if not sprawdz_narzedzia():
        return 1
    try:
        return subprocess.call(polecenie)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
