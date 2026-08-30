// Minimalny parser MAVLink 2 — TYLKO ODCZYT.
//
// Dlaczego własny, a nie biblioteka: serwer podglądu ma nic nie robić poza
// przepisywaniem danych, a gotowe pakiety MAVLink dla Node ciągną za sobą
// generatory dialektów i setki kilobajtów. Tu potrzebujemy siedmiu wiadomości.
//
// ZAKRES: dekodujemy wyłącznie to, co widać na ekranie podglądu. Nic nie nadajemy —
// serwer podglądu nie wysyła komend (dok/WLADZA.md). To nie jest GCS.
//
// UWAGA — nie sprawdzamy CRC. Wymagałoby to tablicy CRC_EXTRA dla każdej wiadomości,
// a ramkowanie po STX + długości jest wystarczająco pewne na łączu UDP w tunelu.
// Skutkiem błędu byłaby pojedyncza przekłamana wartość, nie awaria — a każda
// wartość i tak ma swój wiek (patrz `wiek_s` w stanie).

export const STX_V1 = 0xfe;
export const STX_V2 = 0xfd;

export const MSG = {
  HEARTBEAT: 0,
  SYS_STATUS: 1,
  GPS_RAW_INT: 24,
  ATTITUDE: 30,
  GLOBAL_POSITION_INT: 33,
  VFR_HUD: 74,
  EKF_STATUS_REPORT: 193,
  STATUSTEXT: 253,
  // Dołożone dla mapy. Przesunięcia pól sprawdzone wprost w `pymavlink`
  // (`ordered_fieldnames` + rozmiary typów), nie przepisane z dokumentacji —
  // MAVLink porządkuje pola na drucie MALEJĄCO PO ROZMIARZE, a nie w kolejności
  // deklaracji, i to jest najczęstsze źródło przekłamanych odczytów.
  MISSION_CURRENT: 42,
  MISSION_COUNT: 44,
  MISSION_ITEM_INT: 73,
  HOME_POSITION: 242,
};

/**
 * Polecenia misji, które rysujemy jako punkty trasy. Reszta (zmiany prędkości,
 * sterowanie głowicą, warunki) nie ma współrzędnych i na mapie nie istnieje.
 * Wartości z `MAV_CMD` — MAVLink common.
 */
export const PUNKTY_TRASY = new Set([
  16,  // NAV_WAYPOINT
  17,  // NAV_LOITER_UNLIM
  18,  // NAV_LOITER_TURNS
  19,  // NAV_LOITER_TIME
  20,  // NAV_RETURN_TO_LAUNCH   (bez współrzędnych, ale kończy trasę)
  21,  // NAV_LAND
  22,  // NAV_TAKEOFF
  82,  // NAV_SPLINE_WAYPOINT
]);

// Tryby lotu ArduCopter (custom_mode w HEARTBEAT).
// Zweryfikowane wobec ArduCopter/mode.h @ Copter-4.6.3 — konwencja z CLAUDE.md §8.
export const TRYBY_COPTER = {
  0: "STABILIZE",
  1: "ACRO",
  2: "ALTHOLD",
  3: "AUTO",
  4: "GUIDED",
  5: "LOITER",
  6: "RTL",
  7: "CIRCLE",
  9: "LAND",
  11: "DRIFT",
  13: "SPORT",
  14: "FLIP",
  15: "AUTOTUNE",
  16: "POSHOLD",
  17: "BRAKE",
  18: "THROW",
  19: "AVOID_ADSB",
  20: "GUIDED_NOGPS",
  21: "SMART_RTL",
  22: "FLOWHOLD",
  23: "FOLLOW",
  24: "ZIGZAG",
  25: "SYSTEMID",
  26: "AUTOROTATE",
  27: "AUTO_RTL",
};

export const FIX_GPS = {
  0: "brak",
  1: "brak",
  2: "2D",
  3: "3D",
  4: "DGPS",
  5: "RTK float",
  6: "RTK fix",
};

const POZIOM_STATUSTEXT = {
  0: "krytyczny", 1: "krytyczny", 2: "krytyczny", 3: "blad",
  4: "ostrzezenie", 5: "info", 6: "info", 7: "debug",
};

// MAVLink 2 ucina końcowe zera ładunku ("payload truncation"), więc przed
// odczytem pól trzeba dopełnić bufor do długości oczekiwanej przez daną wiadomość.
function dopelnij(buf, dlugosc) {
  if (buf.length >= dlugosc) return buf;
  const out = Buffer.alloc(dlugosc);
  buf.copy(out);
  return out;
}

/**
 * Wyszukuje ramki MAVLink w buforze.
 * Zwraca { ramki: [{ msgid, sysid, compid, payload }], reszta: Buffer }.
 * `reszta` to niedokończona ramka do sklejenia z następną porcją danych.
 */
/**
 * Rozkłada bufor na ramki. Każda ramka niesie też `surowa` — WIDOK (nie kopię)
 * na własne bajty w buforze wejściowym. Potrzebne do zapisu `.tlog`, który
 * przechowuje ramki w postaci nietkniętej. Widok żyje tak długo, jak bufor
 * wejściowy — kto chce go zatrzymać na dłużej, musi zrobić kopię.
 */
export function parsujRamki(buf) {
  const ramki = [];
  let i = 0;

  while (i < buf.length) {
    const stx = buf[i];

    if (stx === STX_V2) {
      if (i + 10 > buf.length) break; // za mało na nagłówek
      const len = buf[i + 1];
      const incompat = buf[i + 2];
      const podpis = incompat & 0x01 ? 13 : 0;
      const calosc = 10 + len + 2 + podpis;
      if (i + calosc > buf.length) break; // ramka jeszcze nie doszła w całości

      ramki.push({
        sysid: buf[i + 5],
        compid: buf[i + 6],
        msgid: buf[i + 7] | (buf[i + 8] << 8) | (buf[i + 9] << 16),
        payload: buf.subarray(i + 10, i + 10 + len),
        surowa: buf.subarray(i, i + calosc),
      });
      i += calosc;
      continue;
    }

    if (stx === STX_V1) {
      if (i + 6 > buf.length) break;
      const len = buf[i + 1];
      const calosc = 6 + len + 2;
      if (i + calosc > buf.length) break;

      ramki.push({
        sysid: buf[i + 3],
        compid: buf[i + 4],
        msgid: buf[i + 5],
        payload: buf.subarray(i + 6, i + 6 + len),
        surowa: buf.subarray(i, i + calosc),
      });
      i += calosc;
      continue;
    }

    i++; // śmieć — szukamy dalej
  }

  return { ramki, reszta: buf.subarray(i) };
}

/**
 * Dekoduje ramkę do obiektu z polami, które nas interesują.
 * Zwraca null dla wiadomości, których nie obsługujemy.
 */
export function dekoduj({ msgid, payload }) {
  switch (msgid) {
    case MSG.HEARTBEAT: {
      const p = dopelnij(payload, 9);
      const base_mode = p.readUInt8(6);
      return {
        typ: "heartbeat",
        tryb: TRYBY_COPTER[p.readUInt32LE(0)] ?? `MODE_${p.readUInt32LE(0)}`,
        uzbrojony: Boolean(base_mode & 0x80), // MAV_MODE_FLAG_SAFETY_ARMED
        typPojazdu: p.readUInt8(4),
      };
    }

    case MSG.SYS_STATUS: {
      const p = dopelnij(payload, 31);
      const prad = p.readInt16LE(16); // cA, -1 = brak pomiaru
      const pozostalo = p.readInt8(30); // %, -1 = brak
      return {
        typ: "sys_status",
        napiecie_v: p.readUInt16LE(14) / 1000,
        prad_a: prad < 0 ? null : prad / 100,
        bateria_proc: pozostalo < 0 ? null : pozostalo,
        obciazenie_proc: p.readUInt16LE(12) / 10,
      };
    }

    case MSG.GPS_RAW_INT: {
      const p = dopelnij(payload, 52);
      // yaw == 0 znaczy "brak informacji", 65535 to sentinel MAVLink "niedostępne".
      // Na tej maszynie kurs pochodzi WYŁĄCZNIE z bazy GNSS (EK3_SRC1_YAW=2,
      // brak kompasu), więc jego brak zabiera pozycję, RTL i tryby poza AltHold.
      const yaw = p.readUInt16LE(50);
      const kursDostepny = yaw !== 0 && yaw !== 65535;
      return {
        typ: "gps",
        fix: FIX_GPS[p.readUInt8(28)] ?? "?",
        fix_nr: p.readUInt8(28),
        satelity: p.readUInt8(29),
        hdop: p.readUInt16LE(20) === 65535 ? null : p.readUInt16LE(20) / 100,
        kurs_gnss_deg: kursDostepny ? yaw / 100 : null,
        kurs_dostepny: kursDostepny,
      };
    }

    case MSG.ATTITUDE: {
      const p = dopelnij(payload, 28);
      const st = (r) => (r * 180) / Math.PI;
      return {
        typ: "attitude",
        roll_deg: st(p.readFloatLE(4)),
        pitch_deg: st(p.readFloatLE(8)),
        yaw_deg: (st(p.readFloatLE(12)) + 360) % 360,
      };
    }

    case MSG.GLOBAL_POSITION_INT: {
      const p = dopelnij(payload, 28);
      const hdg = p.readUInt16LE(26);
      return {
        typ: "pozycja",
        lat: p.readInt32LE(4) / 1e7,
        lon: p.readInt32LE(8) / 1e7,
        wysokosc_m: p.readInt32LE(16) / 1000, // relative_alt — nad punktem startu
        kurs_deg: hdg === 65535 ? null : hdg / 100,
      };
    }

    case MSG.VFR_HUD: {
      const p = dopelnij(payload, 20);
      return {
        typ: "hud",
        predkosc_ms: p.readFloatLE(4), // groundspeed
        wznoszenie_ms: p.readFloatLE(12),
        gaz_proc: p.readUInt16LE(18),
      };
    }

    case MSG.EKF_STATUS_REPORT: {
      const p = dopelnij(payload, 22);
      const flagi = p.readUInt16LE(20);
      return {
        typ: "ekf",
        flagi: "0x" + flagi.toString(16).toUpperCase().padStart(4, "0"),
        wariancja_kursu: p.readFloatLE(12), // compass_variance — dotyczy kursu, nie kompasu
        // EKF_PRED_POS_HORIZ_ABS (0x100) + EKF_POS_HORIZ_ABS (0x020)
        pozycja_ok: Boolean(flagi & 0x020),
      };
    }

    /**
     * Pozycja domu — punkt, do którego wraca RTL. Serwer podglądu do tej pory
     * jej NIE ZNAŁ (ARCHITEKTURA.md §3.1) i dlatego na nakładce nie było ani
     * dystansu do startu, ani namiaru na dom. Teraz zna, bo maszyna sama ją nadaje.
     */
    case MSG.HOME_POSITION: {
      const p = dopelnij(payload, 52);
      const lat = p.readInt32LE(0) / 1e7;
      const lon = p.readInt32LE(4) / 1e7;
      // Wartości spoza zakresu znaczą „jeszcze nie ustalona" — lepiej nie mieć
      // domu niż mieć go na Wyspie Zerowej.
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || (lat === 0 && lon === 0)) return null;
      return {
        typ: "dom",
        dom_lat: lat,
        dom_lon: lon,
        dom_wysokosc_m: p.readInt32LE(8) / 1000,
      };
    }

    /**
     * Punkt misji. Wyłapujemy je BIERNIE — kiedy kokpit na MK32 pobiera albo
     * wysyła trasę, jej punkty przechodzą przez rozgałęźnik i my je widzimy.
     * Sami o nic nie pytamy: stacja nie wysyła do maszyny niczego (dok/WLADZA.md),
     * a rozgałęźnik i tak jest jednokierunkowy.
     *
     * Cena tego rozwiązania: trasa pojawia się na mapie dopiero wtedy, gdy ktoś
     * ją przez łącze przepuści. Trasy wczytane z pliku `.plan` są niezależne.
     */
    case MSG.MISSION_ITEM_INT: {
      const p = dopelnij(payload, 37);
      const command = p.readUInt16LE(30);
      return {
        typ: "punkt_misji",
        punkt: {
          seq: p.readUInt16LE(28),
          command,
          lat: p.readInt32LE(16) / 1e7,
          lon: p.readInt32LE(20) / 1e7,
          wysokosc_m: p.readFloatLE(24),
          frame: p.readUInt8(34),
          nawigacyjny: PUNKTY_TRASY.has(command),
        },
      };
    }

    case MSG.MISSION_COUNT: {
      const p = dopelnij(payload, 4);
      return { typ: "liczba_punktow", punktow: p.readUInt16LE(0) };
    }

    case MSG.MISSION_CURRENT: {
      const p = dopelnij(payload, 2);
      return { typ: "biezacy_punkt", biezacy_punkt: p.readUInt16LE(0) };
    }

    case MSG.STATUSTEXT: {
      const p = dopelnij(payload, 51);
      const surowy = p.subarray(1, 51);
      const koniec = surowy.indexOf(0);
      return {
        typ: "komunikat",
        poziom: POZIOM_STATUSTEXT[p.readUInt8(0)] ?? "info",
        tekst: surowy.subarray(0, koniec === -1 ? 50 : koniec).toString("utf8").trim(),
      };
    }

    default:
      return null;
  }
}
