#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DRON 15 - rozklad obciazenia czterech silnikow z logu pokladowego (tylko-odczyt).

    python tools\\fc_balans.py dok\\logi\\log_20260816_1756_lot3.bin
    python tools\\fc_balans.py <log.bin> --od 616 --do 623
    python tools\\fc_balans.py <log.bin> --ramie-x 247 --ramie-y 247

Po co to jest. Wyjscia RCOU w zawisie nie sa rowne i to jest normalne, ale ROZNICE
miedzy nimi mowia, co dokladnie jest krzywe. Narzedzie rozklada je na trzy niezalezne
skladowe, ktore naprawia sie zupelnie inaczej:

  tyl - przod   -> srodek ciezkosci przesuniety wzdluz osi podluznej   -> przesunac mase
  prawo - lewo  -> srodek ciezkosci przesuniety w bok                  -> przesunac mase
  CW - CCW      -> staly moment wokol osi pionowej                     -> osadzenie silnikow

Suma tych trzech skladowych podzielona na pol daje odchylke kazdego wyjscia od sredniej,
wiec od razu widac, ktory silnik pierwszy dobije do sufitu MOT_SPIN_MAX.

Wartosci PWM przeliczane sa na ciag przez krzywa MOT_THST_EXPO, bo zaleznosc
PWM -> ciag jest nieliniowa i liczenie procentow wprost z mikrosekund zawyza wynik.
Parametry MOT_* czytane sa z samego logu (rekordy PARM).

Okno pomiarowe: domyslnie caly czas w powietrzu. Do oceny osadzenia maszyny
najlepszy jest USTABILIZOWANY ZAWIS W CISZY, nisko nad ziemia - podac --od/--do,
bo wiatr na wysokosci dokłada wlasny moment i zafalszowuje odczyt.

NIC NIE ZAPISUJE DO FC. Czyta wylacznie plik .bin.
"""

import argparse
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from dziennik import zainstaluj

zainstaluj("fc_balans")

import numpy as np
from pymavlink import mavutil

# Przypisanie wyjsc do pozycji na ramie. Zrodlo: SERVOn_FUNCTION w logu,
# skonfrontowane z Motor Testem 2026-08-15. Kolejnosc funkcji w Quad X wg
# AP_MotorsMatrix.cpp @ Copter-4.6.3: Motor1 przod prawy CCW, Motor2 tyl lewy CCW,
# Motor3 przod lewy CW, Motor4 tyl prawy CW.
FUNKCJA_POZYCJA = {
    33: ("przod prawy", "CCW", -1, +1),   # Motor1;  x: -1 przod / +1 tyl,  y: -1 lewo / +1 prawo
    34: ("tyl lewy",    "CCW", +1, -1),   # Motor2
    35: ("przod lewy",  "CW",  -1, -1),   # Motor3
    36: ("tyl prawy",   "CW",  +1, +1),   # Motor4
}

DOMYSLNE = {
    "MOT_THST_EXPO": 0.65,
    "MOT_SPIN_MIN": 0.10,
    "MOT_SPIN_MAX": 0.95,
    "MOT_PWM_MIN": 1000.0,
    "MOT_PWM_MAX": 2000.0,
}


def wczytaj(sciezka):
    """Jedno przejscie po logu: RCOU, ATT, PARM oraz zdarzenia startu i ladowania."""
    mlog = mavutil.mavlink_connection(sciezka)
    rcou_t, rcou_v = [], []
    att_t, att_r, att_p = [], [], []
    parm = {}
    start = koniec = None
    while True:
        m = mlog.recv_match(type=["RCOU", "ATT", "PARM", "EV"])
        if m is None:
            break
        typ = m.get_type()
        if typ == "RCOU":
            rcou_t.append(m.TimeUS / 1e6)
            rcou_v.append([m.C1, m.C2, m.C3, m.C4])
        elif typ == "ATT":
            att_t.append(m.TimeUS / 1e6)
            att_r.append(m.Roll)
            att_p.append(m.Pitch)
        elif typ == "PARM":
            parm[m.Name] = m.Value
        elif typ == "EV":
            # 28 = NOT_LANDED (oderwanie), 18 = LAND_COMPLETE
            if m.Id == 28 and start is None:
                start = m.TimeUS / 1e6
            elif m.Id == 18:
                koniec = m.TimeUS / 1e6
    return (
        np.array(rcou_t), np.array(rcou_v, dtype=float),
        np.array(att_t), np.array(att_r), np.array(att_p),
        parm, start, koniec,
    )


def krzywa_ciagu(pwm, p):
    """PWM -> udzial ciagu 0..1 wg AP_MotorsMulticopter::thrust_to_actuator (odwrotnie)."""
    dol = p["MOT_PWM_MIN"] + p["MOT_SPIN_MIN"] * (p["MOT_PWM_MAX"] - p["MOT_PWM_MIN"])
    rozpietosc = (p["MOT_SPIN_MAX"] - p["MOT_SPIN_MIN"]) * (p["MOT_PWM_MAX"] - p["MOT_PWM_MIN"])
    a = np.clip((pwm - dol) / rozpietosc, 0.0, 1.0)
    e = p["MOT_THST_EXPO"]
    return a * (1.0 - e) + e * a * a


def main():
    ap = argparse.ArgumentParser(description="Rozklad obciazenia silnikow z logu .bin")
    ap.add_argument("log", help="plik .bin z FC")
    ap.add_argument("--od", type=float, default=None, help="poczatek okna [s od bootu]")
    ap.add_argument("--do", dest="do_", type=float, default=None, help="koniec okna [s od bootu]")
    ap.add_argument("--ramie-x", type=float, default=None,
                    help="odleglosc silnika od srodka wzdluz osi podluznej [mm] - wtedy wynik takze w mm")
    ap.add_argument("--ramie-y", type=float, default=None, help="to samo w poprzek [mm]")
    args = ap.parse_args()

    t, v, ta, ar, ap_, parm, start, koniec = wczytaj(args.log)
    if len(t) == 0:
        print("Brak rekordow RCOU w logu - nie ma czego liczyc.")
        return 1

    p = dict(DOMYSLNE)
    for k in p:
        if k in parm:
            p[k] = parm[k]

    # Ktore wyjscie jest ktorym silnikiem
    poz = []
    for i in range(1, 5):
        f = int(parm.get(f"SERVO{i}_FUNCTION", 32 + i))
        if f not in FUNKCJA_POZYCJA:
            print(f"SERVO{i}_FUNCTION = {f} - to nie jest wyjscie silnika Quad X. Przerywam.")
            return 1
        poz.append(FUNKCJA_POZYCJA[f])

    od = args.od if args.od is not None else (start + 5 if start else t[0])
    do_ = args.do_ if args.do_ is not None else (koniec - 5 if koniec else t[-1])
    okno = (t >= od) & (t <= do_)
    if okno.sum() < 20:
        print(f"Okno {od:.1f}-{do_:.1f} s zawiera {okno.sum()} probek - za malo.")
        return 1

    sr = v[okno].mean(axis=0)
    ciag = krzywa_ciagu(sr, p)
    calk = ciag.sum()

    print(f"\nPlik : {args.log}")
    print(f"Okno : {od:.1f} - {do_:.1f} s  ({okno.sum()} probek RCOU)")
    print(f"MOT_THST_EXPO={p['MOT_THST_EXPO']:.2f}  MOT_SPIN_MIN={p['MOT_SPIN_MIN']:.2f}  "
          f"MOT_SPIN_MAX={p['MOT_SPIN_MAX']:.2f}  (sufit = {p['MOT_PWM_MIN'] + p['MOT_SPIN_MAX'] * (p['MOT_PWM_MAX'] - p['MOT_PWM_MIN']):.0f} us)")
    if len(ta):
        ma = (ta >= od) & (ta <= do_)
        if ma.sum():
            print(f"Sredni kat w oknie: roll {ar[ma].mean():+.2f} deg, pitch {ap_[ma].mean():+.2f} deg"
                  "   <- jesli odbiega od zera, w oknie byl wiatr i wynik jest zafalszowany")

    print("\n  wyjscie  pozycja        obrot   srednie PWM   udzial ciagu   odchylka od sredniej")
    srednia_pwm = sr.mean()
    for i in range(4):
        nazwa, obrot, _, _ = poz[i]
        print(f"    C{i+1}     {nazwa:13s}  {obrot:4s}   {sr[i]:8.1f} us   {ciag[i]/calk*100:8.1f} %      {sr[i]-srednia_pwm:+7.1f} us")
    print(f"    srednia z czterech: {srednia_pwm:.1f} us    rozrzut (maks-min): {sr.max()-sr.min():.1f} us")

    # Rozklad na skladowe. Znak: dodatni = ta strona pracuje ciezej.
    def para(sel):
        return sum(sr[i] for i in range(4) if sel(poz[i])) / 2.0

    tyl = para(lambda q: q[2] > 0); przod = para(lambda q: q[2] < 0)
    prawo = para(lambda q: q[3] > 0); lewo = para(lambda q: q[3] < 0)
    cw = para(lambda q: q[1] == "CW"); ccw = para(lambda q: q[1] == "CCW")

    def para_c(sel):
        return sum(ciag[i] for i in range(4) if sel(poz[i]))

    cg_x = (para_c(lambda q: q[2] > 0) - para_c(lambda q: q[2] < 0)) / calk
    cg_y = (para_c(lambda q: q[3] > 0) - para_c(lambda q: q[3] < 0)) / calk

    print("\n  ROZKLAD NA TRZY NIEZALEZNE SKLADOWE")
    print(f"    tyl - przod : {tyl-przod:+7.1f} us   -> srodek ciezkosci {abs(cg_x)*100:.1f} % polramienia "
          f"{'DO TYLU' if cg_x > 0 else 'DO PRZODU'}"
          + (f"  = {abs(cg_x)*args.ramie_x:.0f} mm" if args.ramie_x else ""))
    print(f"    prawo - lewo: {prawo-lewo:+7.1f} us   -> srodek ciezkosci {abs(cg_y)*100:.1f} % polramienia "
          f"{'W PRAWO' if cg_y > 0 else 'W LEWO'}"
          + (f"  = {abs(cg_y)*args.ramie_y:.0f} mm" if args.ramie_y else ""))
    print(f"    CW  - CCW   : {cw-ccw:+7.1f} us   -> staly moment wokol osi pionowej "
          f"({'CW pracuja ciezej' if cw > ccw else 'CCW pracuja ciezej'}) -> osadzenie silnikow")

    print("\n  KTORY SILNIK PIERWSZY DOBIJE DO SUFITU")
    sufit = p["MOT_PWM_MIN"] + p["MOT_SPIN_MAX"] * (p["MOT_PWM_MAX"] - p["MOT_PWM_MIN"])
    naj = int(np.argmax(sr))
    print(f"    {poz[naj][0]} (C{naj+1}) - {sufit - sr[naj]:.0f} us zapasu do {sufit:.0f} us")

    print("\n  OCENA")
    for etykieta, wart, prog in (("tyl-przod", abs(tyl - przod), 30),
                                 ("prawo-lewo", abs(prawo - lewo), 30),
                                 ("CW-CCW", abs(cw - ccw), 30)):
        stan = "OK" if wart < prog else ("do poprawy" if wart < 2 * prog else "ZLE")
        print(f"    {etykieta:11s} {wart:6.1f} us   {stan}")
    print("    Cel: kazda skladowa ponizej 30 us, rozrzut calkowity ponizej 60 us.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
