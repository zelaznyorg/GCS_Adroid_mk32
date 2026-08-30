#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Udawana jednostka naziemna MK32 — do testowania aplikacji bez drona.

Zachowuje sie jak port 19856 w MK32: czeka, az klient sie odezwie (heartbeat GCS),
i od tej chwili nadaje telemetrie. Dzieki temu kokpit da sie sprawdzic na biurku,
razem z banerami, ktore w powietrzu chcemy zobaczyc jak najrzadziej.

Uzycie:
    python symulator_telemetrii.py                      # normalny lot
    python symulator_telemetrii.py --scenariusz brak_kursu
    python symulator_telemetrii.py --scenariusz zaglusz  # nagly spadek satelitow (poz. 36)
    python symulator_telemetrii.py --scenariusz zla_rama # FRAME_CLASS=4
    python symulator_telemetrii.py --scenariusz zanik_ciagu  # zapas ciagu znika jak w locie 3
    python symulator_telemetrii.py --scenariusz czujnik_padl # barometr i pakiet niezdrowe
    python symulator_telemetrii.py --scenariusz plot         # naruszenie geofence
    python symulator_telemetrii.py --scenariusz wiatr        # zawis przechylony pod wiatr
    python symulator_telemetrii.py --scenariusz paliwo       # pakiet sie konczy: WRACAJ, potem REZERWA
    python symulator_telemetrii.py --port 19856 --nasluch 0.0.0.0

Scenariusze odpowiadaja banerom z domain/Ostrzezenia.kt — sluza do sprawdzenia,
czy aplikacja naprawde je pokazuje.
"""

import argparse
import io
import math
import os
import socket
import struct
import sys
import time

# Wspolny rejestr bledow lezy w tools\ (mk32app\dok\LOGI_I_BLEDY.md).
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[2] / "tools"))
from dziennik import zainstaluj  # noqa: E402

zainstaluj("symulator_telemetrii")

try:
    from pymavlink.dialects.v20 import ardupilotmega as mav
except ImportError:
    print("Potrzebny pymavlink:  pip install pymavlink")
    sys.exit(2)

SCENARIUSZE = ("lot", "brak_kursu", "zaglusz", "zla_rama", "niskie_napiecie", "cisza",
               "zanik_ciagu", "czujnik_padl", "plot", "wiatr", "paliwo")

# Maski SYS_STATUS tej maszyny. Magnetometru NIE MA celowo: COMPASS_USE=0, kurs idzie
# z bazy GNSS (CLAUDE.md sekcja 5). Pasek czujnikow ma go pomijac, a nie swiecic na czerwono.
CZUJNIKI_OBECNE = (0x00000001 | 0x00000002 | 0x00000008 | 0x00000020 |
                   0x00008000 | 0x00010000 | 0x00100000 | 0x00200000 | 0x02000000)

# Plik odniesienia z prawdziwej maszyny — symulator odpowiada na pytania o parametry
# jego wartosciami, wiec checklista w aplikacji jest sprawdzana na tym, co naprawde
# stoi w dronie, a nie na liczbach wymyslonych pod test.
PLIK_ODNIESIENIA = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "..", "dok", "ODNIESIENIE_QUAD_20260815.parm")


def wczytaj_parametry():
    wynik = {}
    try:
        with io.open(PLIK_ODNIESIENIA, encoding="utf-8", errors="replace") as f:
            for linia in f:
                linia = linia.strip()
                if not linia or linia.startswith("#"):
                    continue
                czesci = linia.replace("	", " ").replace(",", " ").split()
                if len(czesci) >= 2:
                    try:
                        wynik[czesci[0].upper()] = float(czesci[1])
                    except ValueError:
                        pass
    except OSError as e:
        print("Nie udalo sie wczytac %s: %s" % (PLIK_ODNIESIENIA, e))
    return wynik


class Nadajnik:
    """Pakuje wiadomosci MAVLink 2 bez otwierania wlasnego gniazda."""

    def __init__(self, sysid=1, compid=1):
        self.link = mav.MAVLink(None, srcSystem=sysid, srcComponent=compid)

    def __getattr__(self, nazwa):
        koder = getattr(self.link, nazwa)

        def pakuj(*a, **kw):
            return koder(*a, **kw).pack(self.link)

        return pakuj


def odpowiedz_na_pytania(g, klient, dane, n, parametry):
    """PARAM_REQUEST_READ (msgid 20) -> PARAM_VALUE. Bez tego checklista w aplikacji
    zostaje na 'brak danych' i nie da sie jej sprawdzic."""
    i = 0
    while i < len(dane):
        if dane[i] != 0xFD or len(dane) - i < 12:
            i += 1
            continue
        dl = dane[i + 1]
        msgid = dane[i + 7] | (dane[i + 8] << 8) | (dane[i + 9] << 16)
        ladunek = dane[i + 10:i + 10 + dl]
        if msgid == 76 and len(ladunek) >= 30:
            # COMMAND_LONG -> COMMAND_ACK. Bez tego przycisk w kokpicie nigdy nie dostanie
            # potwierdzenia i zawsze konczy na "bez potwierdzenia".
            komenda = ladunek[28] | (ladunek[29] << 8)
            # RTL bez kursu GNSS maszyna odrzuca — udajemy to samo, zeby dalo sie zobaczyc
            wynik = 0
            try:
                g.sendto(n.command_ack_encode(komenda, wynik), klient)
                print("COMMAND_LONG %d -> ACK %d" % (komenda, wynik))
            except (OSError, TypeError):
                pass
        if msgid == 20 and len(ladunek) >= 20:
            nazwa = ladunek[4:20].decode("ascii", "replace").rstrip(chr(0) + " ").upper()
            wartosc = parametry.get(nazwa)
            if wartosc is not None:
                try:
                    g.sendto(n.param_value_encode(nazwa.encode("ascii"), wartosc, 9,
                                                  len(parametry), 0), klient)
                except OSError:
                    pass
        i += 12 + dl


LAT0 = 52.1234567
LON0 = 20.1234567

# Szesc ogniw 6S po ok. 4,02 V — reszta pol 0xFFFF oznacza "ogniwo nieobecne".
kanaly_ogniw = [4017] * 6 + [0xFFFF] * 4


def main():
    p = argparse.ArgumentParser(description="Udawana telemetria dla kokpitu DRON15")
    p.add_argument("--port", type=int, default=19856)
    p.add_argument("--nasluch", default="0.0.0.0")
    p.add_argument("--scenariusz", default="lot", choices=SCENARIUSZE)
    p.add_argument("--hz", type=float, default=10.0, help="czestotliwosc wysylki")
    a = p.parse_args()

    g = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    g.bind((a.nasluch, a.port))
    g.settimeout(0.05)
    n = Nadajnik()

    print("Udawany MK32 na %s:%d, scenariusz: %s" % (a.nasluch, a.port, a.scenariusz))
    print("Czekam, az kokpit sie odezwie (wysyla heartbeat co sekunde)...")
    print("W aplikacji: adb shell am start -n pl.dron15.cockpit/.MainActivity -e host ADRES_TEGO_KOMPUTERA")

    parametry = wczytaj_parametry()
    if a.scenariusz == "zla_rama":
        parametry["FRAME_CLASS"] = 4.0          # regresja, ktora zdarzyla sie trzy razy
    print("Parametry z pliku odniesienia: %d" % len(parametry))

    klient = None
    t0 = time.time()
    ostatni = 0.0
    okres = 1.0 / a.hz
    poinformowano = False

    while True:
        try:
            dane, adres = g.recvfrom(2048)
            if adres != klient:
                klient = adres
                print("Klient: %s:%d" % klient)
            odpowiedz_na_pytania(g, klient, dane, n, parametry)
        except socket.timeout:
            pass
        except ConnectionResetError:
            # Windows: gdy klient zniknie, kolejne recvfrom dostaje ICMP "port
            # unreachable" jako WinError 10054. To nie awaria — klient moze wrocic,
            # wiec zapominamy o nim i czekamy dalej, zamiast konczyc prace.
            klient = None
            continue

        teraz = time.time()
        if klient is None or teraz - ostatni < okres:
            time.sleep(0.005)
            continue
        ostatni = teraz
        t = teraz - t0

        if a.scenariusz == "cisza":
            if not poinformowano:
                print("Scenariusz 'cisza': nic nie nadaje — kokpit ma pokazac UTRATE TELEMETRII")
                poinformowano = True
            continue

        # --- lot po okregu, zeby cos sie na ekranie ruszalo
        kurs = (t * 12) % 360
        wysokosc = 12.0 + 3 * math.sin(t / 4)
        predkosc = 2.0 + math.sin(t / 3)
        if a.scenariusz == "wiatr":
            # Zawis w miejscu: kurs staly, predkosc ponizej progu Wiatr.MAKS_PREDKOSC_MS,
            # maszyna przechylona pod wiatr. Inaczej wiatru nie da sie zobaczyc.
            # 5 stopni, nie 0: w MAVLink GPS_RAW_INT.yaw = 0 znaczy NIEDOSTEPNY,
            # wiec zero kursu wygladaloby jak awaria bazy GNSS.
            kurs = 5.0
            predkosc = 0.4

        # Pozycja jedzie po okregu o promieniu 60 m wokol punktu startu — inaczej mapa
        # i slad nie mialyby czego pokazac.
        promien = 60.0
        katr = math.radians(kurs)
        lat = LAT0 + (promien * math.cos(katr)) / 111320.0
        lon = LON0 + (promien * math.sin(katr)) / (111320.0 * math.cos(math.radians(LAT0)))

        # --- zuzycie pakietu. W scenariuszu "paliwo" rosnie szybko, zeby dalo sie
        # zobaczyc oba progi (WRACAJ, potem REZERWA) w kilkadziesiat sekund zamiast
        # w kilka minut. W pozostalych stoi, bo nie o to w nich chodzi.
        if a.scenariusz == "paliwo":
            zuzycie_mah = int(min(3250, 40 + t * 14))
        else:
            zuzycie_mah = 2100

        # --- maski czujnikow; w scenariuszu "czujnik_padl" barometr co jakis czas siada
        czujniki_obecne = CZUJNIKI_OBECNE
        czujniki_wlaczone = CZUJNIKI_OBECNE
        czujniki_zdrowe = CZUJNIKI_OBECNE
        if a.scenariusz == "czujnik_padl" and (int(t) % 20) >= 10:
            czujniki_zdrowe &= ~0x00000008        # barometr niezdrowy
            czujniki_zdrowe &= ~0x02000000        # i pomiar pakietu razem z nim

        satelity = 18
        if a.scenariusz == "zaglusz":
            # co 20 s symulacja zagluszenia: satelity spadaja z 18 do 2
            satelity = 2 if (int(t) % 20) >= 10 else 18
        kurs_gnss = 0xFFFF if a.scenariusz == "brak_kursu" else int(kurs * 100)
        napiecie = 21500 if a.scenariusz == "niskie_napiecie" else 24100

        # --- wyjscia silnikow. Kolejnosc jak na plycie: S1 tyl prawy, S2 przod prawy,
        # S3 tyl lewy, S4 przod lewy (mapowanie potwierdzone lotem 2 z 2026-08-16).
        if a.scenariusz == "zanik_ciagu":
            # Odtworzenie lotu 3: srednia rosnie 1736 -> 1787, a najwyzsze wyjscie
            # dochodzi do sufitu 1950 i tam siada. Pelny cykl w 60 s.
            postep = min(1.0, (t % 90.0) / 60.0)
            srednia = 1736 + 51 * postep
            nadwyzka = 38 + 135 * postep          # rozrzut 101 -> 173 us jak w logu
        else:
            srednia = 1700 + 20 * math.sin(t / 5)
            nadwyzka = 40
        wyjscia = [
            int(min(1950, srednia + nadwyzka / 2)),   # S1 tyl prawy — najbardziej obciazony
            int(srednia - nadwyzka / 2),              # S2 przod prawy
            int(srednia + nadwyzka / 6),              # S3 tyl lewy
            int(srednia - nadwyzka / 6),              # S4 przod lewy
        ]

        paczki = [
            # Tryb: normalnie ALTHOLD (2). W scenariuszu "plot" co jakis czas RTL (6),
            # zeby dalo sie zobaczyc blok CEL — pokazuje sie tylko w trybach automatycznych.
            n.heartbeat_encode(
                2, 3, 209 if (a.scenariusz == "wiatr" or int(t) % 30 > 15) else 81,
                6 if (a.scenariusz == "plot" and (int(t) % 24) >= 8) else 2, 4),
            # 209 = 81 + 128, czyli bit uzbrojenia; 81 = rozbrojony
            n.sys_status_encode(czujniki_obecne, czujniki_wlaczone, czujniki_zdrowe,
                                300, napiecie, 1230, 0, 0, 0, 0, 0, 0, 71),
            n.gps_raw_int_encode(int(t * 1e6), 3, int(lat * 1e7), int(lon * 1e7), 120000,
                                 70, 90, int(predkosc * 100), int(kurs * 100),
                                 satelity, 0, 0, 0, 0, 0, kurs_gnss),
            n.attitude_encode(
                int(t * 1000),
                # W scenariuszu "wiatr" staly przechyl 6 stopni w prawo i 4 do przodu:
                # to odpowiada wiatrowi z prawej i z przodu, czyli z okolic 056 stopni.
                math.radians(6.0) if a.scenariusz == "wiatr" else 0.02 * math.sin(t),
                math.radians(-4.0) if a.scenariusz == "wiatr" else 0.03 * math.cos(t),
                math.radians(kurs), 0.0, 0.0, 0.0),
            n.global_position_int_encode(int(t * 1000), int(lat * 1e7), int(lon * 1e7),
                                         132000, int(wysokosc * 1000),
                                         int(predkosc * 100), 0, int(-30 * math.cos(t / 4)),
                                         int(kurs * 100)),
            n.vfr_hud_encode(0.0, predkosc, int(kurs), 48, wysokosc, 0.3),
            n.ekf_status_report_encode(0x033F, 0.1, 0.2, 0.1, 0.02, 0.0, 0.0),
            # Zrodlo zapasu ciagu i rozrzutu — domain/Ciag.kt, dok/PROPOZYCJA_LOT.md §4.1
            n.servo_output_raw_encode(int(t * 1e6), 0, *wyjscia, 0, 0, 0, 0),
            n.vibration_encode(int(t * 1e6),
                               1.8 + 0.4 * math.sin(t), 2.1 + 0.3 * math.cos(t), 2.4,
                               0, 0, 0),
            n.home_position_encode(int(LAT0 * 1e7), int(LON0 * 1e7), 120000,
                                   0.0, 0.0, 0.0, [1.0, 0.0, 0.0, 0.0], 0.0, 0.0, 0.0),
            # Geofence: w scenariuszu "plot" co jakis czas naruszenie pulapu.
            # Cel automatu — bez tego blok CEL pokazuje sam myslnik.
            # UWAGA: pymavlink przyjmuje pola w kolejnosci z XML, nie z drutu —
            # nav_roll, nav_pitch, nav_bearing, target_bearing, wp_dist, alt_error,
            # aspd_error, xtrack_error. Dekoder w Kotlinie czyta kolejnosc drutowa
            # (piec floatow, potem dwa i16 i u16) i to jest poprawne dla samych bajtow.
            n.nav_controller_output_encode(
                0.0, 0.0,
                int(kurs), int((kurs + 15) % 360),
                int(120 + 60 * abs(math.sin(t / 9))),
                float(-2.5 + 5 * math.sin(t / 6)), 0.0, float(3 * math.sin(t / 4))),
            n.fence_status_encode(
                0, 3 if a.scenariusz == "plot" else 0,
                1 if (a.scenariusz == "plot" and (int(t) % 24) >= 12) else 0,
                2 if a.scenariusz == "plot" else 0, 0),
            n.battery_status_encode(0, 0, 0, 3500, kanaly_ogniw, 1250, zuzycie_mah, -1, 62),
            # Aparatura: drazki ruszaja sie, przelaczniki stoja. Zakres 1045-1945 us jak w MK32.
            n.rc_channels_encode(
                int(t * 1000), 16,
                1495 + int(400 * math.sin(t / 2)),      # CH1 roll
                1495 + int(400 * math.cos(t / 3)),      # CH2 pitch
                1495,                                    # CH3 gaz — samocentrujacy
                1495 + int(200 * math.sin(t / 5)),      # CH4 kierunek
                1045, 1945, 1495, 1045, 1045, 1495,     # CH5-10 przelaczniki
                1495, 1495, 1495, 1495,                 # CH11-14 pokretla
                1045, 1045,                             # CH15-16 przyciski
                0, 0, 255),
        ]

        # co 5 s komunikat tekstowy, zeby bylo widac liste na dole ekranu
        if int(t) % 5 == 0 and t - int(t) < okres:
            paczki.append(n.statustext_encode(6, b"ArduCopter V4.6.3 (3fc7011a)"))

        # parametry do checklisty — FRAME_CLASS jest tu najwazniejszy
        if int(t) % 3 == 0 and t - int(t) < okres:
            klasa = 4.0 if a.scenariusz == "zla_rama" else 1.0
            paczki.append(n.param_value_encode(b"FRAME_CLASS", klasa, 9, 1306, 0))
            # Pojemnosc: w scenariuszu "zanik_ciagu" podajemy skalibrowana (pakiet 8S5P
            # z poz. 56), zeby dalo sie zobaczyc JOKER i BINGO. W pozostalych zostaje
            # dzisiejsze 3300, na ktorym blok energii ma **odmowic** liczenia procentow.
            pojemnosc = 22500.0 if a.scenariusz == "zanik_ciagu" else 3300.0
            paczki.append(n.param_value_encode(b"BATT_CAPACITY", pojemnosc, 9, 1306, 1))
            paczki.append(n.param_value_encode(b"MOT_SPIN_MAX", 0.95, 9, 1306, 2))
            # Geofence wg CLAUDE.md: promien 300 m, pulap 120 m.
            paczki.append(n.param_value_encode(b"FENCE_ENABLE", 1.0, 9, 1306, 7))
            paczki.append(n.param_value_encode(b"FENCE_RADIUS", 300.0, 9, 1306, 8))
            paczki.append(n.param_value_encode(b"FENCE_ALT_MAX", 120.0, 9, 1306, 9))
            for i, funkcja in enumerate((36.0, 33.0, 34.0, 35.0), start=1):
                paczki.append(n.param_value_encode(
                    ("SERVO%d_FUNCTION" % i).encode(), funkcja, 9, 1306, 2 + i))

        for paczka in paczki:
            try:
                g.sendto(paczka, klient)
            except OSError as e:
                print("blad wysylki: %s" % e)
                klient = None
                break


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nkoniec")
