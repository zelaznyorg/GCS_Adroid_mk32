// Telemetria z dronów DJI — broker MQTT i przejściówka na nasz model stanu.
//
// ### Skąd to się bierze
//
// DJI Pilot 2 (aparatura Enterprise: RC Pro Enterprise / RC Plus) ma **wbudowaną**
// Cloud API: po wejściu na naszą stronę H5 sam łączy się z naszym brokerem MQTT
// i publikuje telemetrię. Nie trzeba pisać aplikacji na Androida — trzeba mieć
// brokera i rozumieć protokół.
//
//   Mavic 3T ──► aparatura z DJI Pilot 2 ──MQTT──► ten moduł ──► stan() ──► HUD i mapa
//
// ### Dlaczego broker jest W NAS, a nie osobną usługą
//
// Mosquitto byłby drugą usługą, drugim plikiem konfiguracji i drugim miejscem na
// hasła — a i tak potrzebowalibyśmy klienta MQTT, żeby te wiadomości odebrać.
// Broker wbudowany daje jedną usługę, jedno miejsce na poświadczenia i dostęp do
// wiadomości bez pośrednika.
//
// ### ⛔ Czego ten moduł NIE robi
//
// Nie wysyła do drona niczego, co by nim ruszało. Cloud API ma tryb sterowania
// (DRC, `thing/product/{sn}/drc/down`) i świadomie go nie dotykamy — obowiązuje ta
// sama zasada, co przy DRON 15: stacja patrzy, nie rozkazuje.
//
// Wyjątkiem są odpowiedzi wymagane przez protokół (`status_reply`), bez których
// Pilot 2 nie uzna połączenia za nawiązane. To potwierdzenia odbioru, nie polecenia.
import { createServer } from "node:net";
// ⚠ Eksport NAZWANY, nie domyślny: fabryka `createBroker` wisi na `Aedes`,
// a `import Aedes from "aedes"` daje klasę, która przy `new` rzuca odsyłaczem
// do migracji (aedes 1.x). Kosztowało to jedną nieudaną próbę.
import { Aedes } from "aedes";
import * as rejestr from "./rejestr.mjs";

export const PORT_MQTT = Number(process.env.DJI_MQTT_PORT) || 1883;

/** Po tylu sekundach bez wiadomości uznajemy łącze za martwe (jak w telemetrii MAVLink). */
const CISZA_LACZA_S = 5;

/**
 * Tryby lotu Mavic 3 — `mode_code` z dokumentacji DJI (m3-series/properties).
 * Po polsku, bo trafiają wprost na ekran operatora.
 */
const TRYBY = {
  0: "GOTOWOŚĆ",
  1: "PRZYGOTOWANIE",
  2: "GOTOWY DO STARTU",
  3: "RĘCZNY",
  4: "START AUTOMATYCZNY",
  5: "TRASA",
  6: "PANORAMA",
  7: "ŚLEDZENIE",
  8: "OMIJANIE ADS-B",
  9: "POWRÓT",
  10: "LĄDOWANIE",
  11: "LĄDOWANIE AWARYJNE",
  12: "LĄDOWANIE NA TRZECH",
  13: "AKTUALIZACJA",
  14: "BRAK POŁĄCZENIA",
  15: "APAS",
  16: "DRĄŻKI WIRTUALNE",
  17: "STEROWANIE ZDALNE",
  18: "RTK",
};

/** Tryby, w których maszyna nie jest w powietrzu. */
const NA_ZIEMI = [0, 1, 2, 13, 14];

export class MostDji {
  /**
   * @param {() => string} hasloUrzadzenia — poświadczenie, którym łączy się Pilot 2.
   *   Bierzemy je z tego samego miejsca co hasło nadawania obrazu: jedno hasło
   *   urządzenia, jedno miejsce do wymiany po utracie sprzętu.
   */
  constructor(hasloUrzadzenia) {
    this.hasloUrzadzenia = hasloUrzadzenia;
    this.broker = null;
    this.serwer = null;
    this.pola = {};
    this.ostatniaWiadomosc = 0;
    this.wiadomosci = 0;
    this.urzadzenia = new Map(); // sn -> { typ, ostatnio }
  }

  // aedes 1.x tworzy brokera fabryką asynchroniczną (`createBroker`), a nie
  // konstruktorem — stary `new Aedes()` rzuca wyjątkiem z odsyłaczem do migracji.
  async start() {
    this.broker = await Aedes.createBroker({
      authenticate: (klient, uzytkownik, haslo, gotowe) => {
        const podane = haslo ? haslo.toString() : "";
        if (podane && podane === this.hasloUrzadzenia()) return gotowe(null, true);
        rejestr.ostrzezenie("dji", "odrzucone połączenie MQTT — złe hasło urządzenia", {
          uzytkownik: String(uzytkownik || "?"),
        });
        const b = new Error("Złe poświadczenia");
        b.returnCode = 4;
        return gotowe(b, false);
      },
    });

    this.broker.on("client", (k) => rejestr.info("dji", `Pilot 2 połączony (${k.id})`));
    this.broker.on("clientDisconnect", (k) => rejestr.info("dji", `Pilot 2 rozłączony (${k.id})`));
    this.broker.on("publish", (pakiet, klient) => {
      if (!klient) return; // wiadomości nadane przez nas samych
      this.przyjmij(String(pakiet.topic), pakiet.payload);
    });

    this.serwer = createServer(this.broker.handle);
    this.serwer.listen(PORT_MQTT, () =>
      rejestr.info("dji", `broker MQTT nasłuchuje na :${PORT_MQTT} (Cloud API)`)
    );
    this.serwer.on("error", (e) =>
      rejestr.ostrzezenie("dji", "broker MQTT nie wstał", { blad: e.message })
    );
  }

  stop() {
    this.serwer?.close();
    this.broker?.close();
  }

  przyjmij(temat, ladunek) {
    let w;
    try {
      w = JSON.parse(ladunek.toString("utf8"));
    } catch {
      return; // nie nasza wiadomość albo uszkodzona — cisza jest lepsza niż hałas
    }
    this.wiadomosci += 1;

    // thing/product/{sn}/osd — telemetria nadawana z częstotliwością
    const osd = /^thing\/product\/([^/]+)\/osd$/.exec(temat);
    if (osd) {
      this.ostatniaWiadomosc = Date.now();
      this.zapamietaj(osd[1], w.data || {});
      return;
    }

    // sys/product/{sn}/status — urządzenie melduje się i podaje topologię.
    // ⚠ Bez odpowiedzi Pilot 2 NIE uzna połączenia za nawiązane. To jedyna
    // wiadomość, którą musimy odesłać, i jest wyłącznie potwierdzeniem odbioru.
    const status = /^sys\/product\/([^/]+)\/status$/.exec(temat);
    if (status) {
      const brama = status[1];
      this.ostatniaWiadomosc = Date.now();
      for (const u of w.data?.sub_devices || []) {
        this.urzadzenia.set(u.sn, { typ: u.type, ostatnio: Date.now() });
        rejestr.info("dji", `zgłosił się statek powietrzny ${u.sn} (typ ${u.type})`);
      }
      this.odpowiedz(`sys/product/${brama}/status_reply`, {
        tid: w.tid,
        bid: w.bid,
        timestamp: Date.now(),
        data: { result: 0, output: { status: "normal" } },
      });
      return;
    }

    // Pozostałe (state, events) na razie tylko podtrzymują łącze.
    if (/^thing\/product\/[^/]+\/(state|events)$/.test(temat)) {
      this.ostatniaWiadomosc = Date.now();
    }
  }

  odpowiedz(temat, wiadomosc) {
    this.broker?.publish(
      { topic: temat, payload: Buffer.from(JSON.stringify(wiadomosc)), qos: 0, retain: false },
      () => {}
    );
  }

  /** Przepisanie pól DJI na nasze. Nazwy z dokumentacji `m3-series/properties`. */
  zapamietaj(sn, d) {
    const p = this.pola;
    p.sn = sn;
    if (typeof d.latitude === "number") p.lat = d.latitude;
    if (typeof d.longitude === "number") p.lon = d.longitude;
    // `elevation` to wysokość WZGLĘDEM PUNKTU STARTU — tak liczy ją nasz HUD.
    // `height` jest bezwzględna i trzymamy ją osobno, żeby nie mieszać dwóch rzeczy.
    if (typeof d.elevation === "number") p.wysokosc_m = d.elevation;
    if (typeof d.height === "number") p.wysokosc_bezwzgledna_m = d.height;
    if (typeof d.vertical_speed === "number") p.wznoszenie_ms = d.vertical_speed;
    if (typeof d.horizontal_speed === "number") p.predkosc_ms = d.horizontal_speed;
    if (typeof d.attitude_head === "number") p.kurs_deg = d.attitude_head;
    if (typeof d.attitude_roll === "number") p.roll_deg = d.attitude_roll;
    if (typeof d.attitude_pitch === "number") p.pitch_deg = d.attitude_pitch;
    if (typeof d.home_distance === "number") p.odleglosc_od_domu_m = d.home_distance;
    if (typeof d.mode_code === "number") {
      p.mode_code = d.mode_code;
      p.tryb = TRYBY[d.mode_code] ?? `TRYB ${d.mode_code}`;
      // DJI nie ma pojęcia „uzbrojony". Za lot uznajemy każdy tryb poza gotowością
      // i brakiem połączenia — to jest INTERPRETACJA, nie odczyt, i tak to opisujemy.
      p.w_locie = !NA_ZIEMI.includes(d.mode_code);
    }
    if (d.position_state) {
      if (typeof d.position_state.gps_number === "number") p.satelity = d.position_state.gps_number;
      if (typeof d.position_state.rtk_number === "number") p.rtk_satelity = d.position_state.rtk_number;
      if (typeof d.position_state.is_fixed === "number") p.fix = d.position_state.is_fixed;
    }
    if (d.battery) {
      if (typeof d.battery.capacity_percent === "number") p.bateria_proc = d.battery.capacity_percent;
      if (typeof d.battery.remain_flight_time === "number") p.pozostaly_lot_s = d.battery.remain_flight_time;
      if (typeof d.battery.return_home_power === "number") p.prog_powrotu_proc = d.battery.return_home_power;
    }
    if (typeof d.total_flight_time === "number") p.czas_lotu_s = d.total_flight_time;

    // Punkt startu: przyjmujemy pierwszą pozycję zmierzoną tuż nad ziemią.
    // ⚠ To jest domysł, nie odczyt — DJI podaje dom osobnym komunikatem, którego
    // na razie nie obsługujemy. Lepszy przybliżony dom niż pusty znacznik na mapie,
    // ale nie wolno go mylić z punktem powrotu ustawionym w aparaturze.
    if (p.dom_lat == null && p.lat != null && p.wysokosc_m != null && p.wysokosc_m < 1) {
      p.dom_lat = p.lat;
      p.dom_lon = p.lon;
    }
  }

  /**
   * Ten sam kształt, co `telemetria.mjs`. Dzięki temu strona, HUD i mapa nie wiedzą,
   * czy patrzą na ArduPilota, czy na DJI — i nie muszą wiedzieć.
   */
  stan() {
    const teraz = Date.now();
    const od = this.ostatniaWiadomosc ? (teraz - this.ostatniaWiadomosc) / 1000 : null;
    const zywe = od !== null && od < CISZA_LACZA_S;
    const p = this.pola;
    const w = (k) => (zywe ? (p[k] ?? null) : null);

    return {
      typ: "stan",
      zrodlo_telemetrii: "dji",
      czas: teraz / 1000,
      lacze: { zywe, sekund_od_heartbeatu: od, ramek: this.wiadomosci, bledow: 0 },
      lot: {
        tryb: w("tryb"),
        // ⚠ NIE jest to odczyt „uzbrojony" jak w ArduPilocie — DJI takiego pojęcia
        // nie ma. To tryb lotu przełożony na „w powietrzu".
        uzbrojony: w("w_locie"),
        wysokosc_m: w("wysokosc_m"),
        wznoszenie_ms: w("wznoszenie_ms"),
        predkosc_ms: w("predkosc_ms"),
        gaz_proc: null, // DJI tego nie podaje
      },
      polozenie: {
        lat: w("lat"),
        lon: w("lon"),
        kurs_deg: w("kurs_deg"),
        kurs_zrodlo: w("kurs_deg") != null ? "dji" : null,
      },
      dom: { lat: p.dom_lat ?? null, lon: p.dom_lon ?? null, wysokosc_m: null },
      misja: { punktow: 0, oczekiwanych: 0, biezacy: null, zmieniona: null },
      postawa: { roll_deg: w("roll_deg"), pitch_deg: w("pitch_deg") },
      gnss: {
        satelity: w("satelity"),
        hdop: null,
        fix: w("fix"),
        kurs_dostepny: w("kurs_deg") != null,
      },
      ekf: { flagi: null, wariancja_kursu: null, pozycja_ok: w("lat") != null },
      bateria: {
        napiecie_v: null,
        prad_a: null,
        zuzycie_mah: null,
        procent: w("bateria_proc"),
      },
      dji: {
        sn: p.sn ?? null,
        urzadzen: this.urzadzenia.size,
        odleglosc_od_domu_m: w("odleglosc_od_domu_m"),
        pozostaly_lot_s: w("pozostaly_lot_s"),
        wysokosc_bezwzgledna_m: w("wysokosc_bezwzgledna_m"),
        rtk_satelity: w("rtk_satelity"),
      },
    };
  }
}

// ---- ustawienia wpięcia (licencja DJI, podpisy) ------------------------------
//
// Licencja Cloud API (`appId`, `appKey`, `licencja`) pochodzi z konta deweloperskiego
// DJI i bez niej Pilot 2 nie załaduje modułu chmurowego. Trzymamy ją w katalogu
// danych, nie w kodzie — to poświadczenie konta, nie konfiguracja programu.
//
// ⚠ Ten sam katalog co reszta danych ruchomych: DATA_DIR (dok/DJI.md §5).
import { existsSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { randomUUID } from "node:crypto";

const KATALOG = process.env.DATA_DIR || ".";
const PLIK_USTAWIEN = join(KATALOG, "dji.json");

const DOMYSLNE = {
  appId: "",
  appKey: "",
  licencja: "",
  nazwaPlatformy: "Panorama — stacja",
  nazwaObszaru: "Stacja naziemna",
  opis: "Telemetria i obraz na stacji Panorama",
  obszarId: "",
  uzytkownik: "dji",
};

let pamiec = null;

export function ustawienia() {
  if (pamiec) return { ...pamiec };
  let z = {};
  try {
    if (existsSync(PLIK_USTAWIEN)) z = JSON.parse(readFileSync(PLIK_USTAWIEN, "utf8"));
  } catch {
    // Uszkodzony plik nie może zabrać stacji telemetrii z DRON 15 — jedziemy na domyślnych.
  }
  pamiec = { ...DOMYSLNE, ...z };
  // Identyfikator obszaru musi być stały, bo Pilot 2 wiąże z nim swoje dane.
  if (!pamiec.obszarId) {
    pamiec.obszarId = randomUUID();
    zapisz();
  }
  return { ...pamiec };
}

function zapisz() {
  try {
    mkdirSync(dirname(PLIK_USTAWIEN), { recursive: true });
    writeFileSync(PLIK_USTAWIEN, JSON.stringify(pamiec, null, 2) + "\n", { mode: 0o600 });
  } catch {
    // Bez zapisu ustawienia żyją do restartu — lepsze to niż przewrócenie serwera.
  }
}

export function ustaw(zmiany) {
  const teraz = ustawienia();
  const dozwolone = ["appId", "appKey", "licencja", "nazwaPlatformy", "nazwaObszaru", "opis"];
  for (const [k, v] of Object.entries(zmiany || {})) {
    if (!dozwolone.includes(k)) continue;
    teraz[k] = String(v ?? "").slice(0, 4096);
  }
  pamiec = teraz;
  zapisz();
  return { ...pamiec };
}

/** Czy wpięcie jest gotowe do użycia w aparaturze. */
export function gotowe() {
  const u = ustawienia();
  return Boolean(u.appId && u.appKey && u.licencja);
}
