// Odbiór telemetrii MAVLink z jednostki naziemnej MK32 i utrzymywanie stanu maszyny.
//
// Kierunek jest jednostronny: SŁUCHAMY. Serwer podglądu nie wysyła komend ani
// heartbeatu — władza nad maszyną zostaje na MK32 (dok/WLADZA.md). Jedyne, co
// wychodzi, to pusty datagram otwierający ścieżkę powrotną, bo jednostka naziemna
// jest serwerem UDP i musi wiedzieć, dokąd odsyłać (INTERFEJSY.md §1 — FAKT).
// Pusty datagram nie jest ramką MAVLink, więc nikt nie zobaczy nas jako GCS.
//
// Format stanu odpowiada dok/ARCHITEKTURA.md §3.1, żeby klient webowy i klient
// aplikacji MK32 mówiły tym samym językiem.
import dgram from "node:dgram";
import { parsujRamki, dekoduj } from "./mavlink.mjs";
import * as rejestr from "./rejestr.mjs";

// Po tylu sekundach bez nowej wiadomości wartość uznajemy za nieaktualną.
// Zasada 6 systemu projektowego: każda wartość zna swój wiek (dok/UI.md).
const WIEK_PRZETERMINOWANY_S = 10;
// Brak heartbeatu dłużej niż to = utrata łącza.
const CISZA_LACZA_S = 3;

export class Telemetria {
  /**
   * @param {{host: string, port: number, keepaliveMs?: number, archiwum?: {ramka: Function}}} opcje
   */
  constructor({ host, port, keepaliveMs = 5000, archiwum = null }) {
    this.host = host;
    this.port = port;
    this.keepaliveMs = keepaliveMs;
    // Archiwum dostaje ramki SUROWE, przed dekodowaniem. Dzięki temu w `.tlog`
    // ląduje wszystko, co przyszło z łącza — także wiadomości, których ten serwer
    // nie rozumie. Nagranie ma być wierne, nie wybiórcze (server/archiwum.mjs).
    this.archiwum = archiwum;

    this.gniazdo = null;
    this.bufor = Buffer.alloc(0);
    this.timerKeepalive = null;

    // Surowe pola + znacznik czasu ostatniej aktualizacji każdej grupy.
    this.pola = {};
    this.znaczniki = {};
    this.komunikaty = []; // ostatnie STATUSTEXT
    /**
     * Trasa złapana biernie z łącza — `seq` punktu → punkt.
     *
     * Mapa (Map), nie tablica, bo punkty potrafią przyjść nie po kolei i po kilka
     * razy: protokół misji MAVLink ponawia zgubione. Klucz po `seq` sam to porządkuje.
     *
     * ⚠ NIE jest częścią migawki stanu wysyłanej 10 razy na sekundę. Trasa bywa
     * pięćdziesięciopunktowa i doklejanie jej do każdej ramki SSE byłoby marnowaniem
     * łącza na dane, które zmieniają się raz na lot. Wychodzi osobno, przez /api/misja.
     */
    this.misja = new Map();
    this.punktowOczekiwanych = null;
    this.misjaZmieniona = 0;
    this.ostatniHeartbeat = 0;
    this.ramek = 0;
    this.bledow = 0;
  }

  start() {
    if (this.gniazdo) return;

    const s = dgram.createSocket({ type: "udp4", reuseAddr: true });
    this.gniazdo = s;

    s.on("message", (msg) => this._odbierz(msg));
    s.on("error", (e) => {
      this.bledow++;
      // ECONNRESET na gnieździe UDP to skutek ICMP "port unreachable" — na Windows
      // dostajemy je, gdy jednostka naziemna jeszcze nie słucha albo właśnie znikła.
      // To stan normalny (drona nie ma pod napięciem), nie awaria: nie hałasujemy
      // i nie zamykamy gniazda, bo MK32 może wrócić w każdej chwili.
      if (e.code === "ECONNRESET" || e.code === "ECONNREFUSED") return;
      rejestr.wyjatek("telemetria", "błąd gniazda", e, { host: this.host, port: this.port });
    });

    s.bind(() => {
      rejestr.info("telemetria", `nasłuch, źródło ${this.host}:${this.port}`);
      this._zaczepka();
      this.timerKeepalive = setInterval(() => this._zaczepka(), this.keepaliveMs);
    });
  }

  stop() {
    if (this.timerKeepalive) clearInterval(this.timerKeepalive);
    this.timerKeepalive = null;
    if (this.gniazdo) this.gniazdo.close();
    this.gniazdo = null;
  }

  // Otwiera ścieżkę powrotną w routerze/tunelu. Treść bez znaczenia — liczy się
  // to, że jednostka naziemna pozna nasz adres i port źródłowy.
  _zaczepka() {
    if (!this.gniazdo) return;
    this.gniazdo.send(Buffer.alloc(0), this.port, this.host, (e) => {
      if (e) rejestr.ostrzezenie("telemetria", "zaczepka nie doszła", { blad: e.message });
    });
  }

  _odbierz(msg) {
    // Sklejamy z resztą, bo jedna datagram może nieść kilka ramek, a ostatnia
    // bywa ucięta. Zabezpieczenie przed narastaniem bufora przy strumieniu śmieci.
    this.bufor = this.bufor.length ? Buffer.concat([this.bufor, msg]) : msg;
    if (this.bufor.length > 64 * 1024) this.bufor = this.bufor.subarray(-4096);

    const { ramki, reszta } = parsujRamki(this.bufor);
    this.bufor = reszta;

    for (const ramka of ramki) {
      this.ramek++;
      this.archiwum?.ramka(ramka.surowa);
      let dane;
      try {
        dane = dekoduj(ramka);
      } catch {
        this.bledow++;
        continue;
      }
      if (dane) this._zastosuj(dane);
    }
  }

  _zastosuj(dane) {
    const teraz = Date.now();
    const { typ, ...reszta } = dane;

    if (typ === "punkt_misji") {
      const p = reszta.punkt;
      // Punkt bez współrzędnych (np. RETURN_TO_LAUNCH albo zmiana prędkości)
      // trzymamy, ale mapa go nie narysuje — decyduje `nawigacyjny`.
      this.misja.set(p.seq, p);
      this.misjaZmieniona = teraz;
      return;
    }

    if (typ === "liczba_punktow") {
      // Nowa zapowiedź długości trasy znaczy, że zaczyna się NOWY transfer —
      // stara trasa przestaje obowiązywać. Bez tego dwie kolejne misje zlałyby
      // się w jedną, dłuższą i nieprawdziwą.
      if (reszta.punktow !== this.punktowOczekiwanych) this.misja.clear();
      this.punktowOczekiwanych = reszta.punktow;
      this.misjaZmieniona = teraz;
      return;
    }

    if (typ === "komunikat") {
      if (!reszta.tekst) return;
      this.komunikaty.unshift({ ...reszta, czas: teraz });
      this.komunikaty = this.komunikaty.slice(0, 20);
      return;
    }

    if (typ === "heartbeat") this.ostatniHeartbeat = teraz;

    Object.assign(this.pola, reszta);
    for (const klucz of Object.keys(reszta)) this.znaczniki[klucz] = teraz;
  }

  // Wiek pola w sekundach; null gdy nigdy nie przyszło.
  wiek(klucz) {
    const t = this.znaczniki[klucz];
    return t ? (Date.now() - t) / 1000 : null;
  }

  // Zwraca wartość tylko wtedy, gdy jest świeża — inaczej null.
  _swieze(klucz) {
    const w = this.wiek(klucz);
    if (w === null || w > WIEK_PRZETERMINOWANY_S) return null;
    return this.pola[klucz] ?? null;
  }

  /** Migawka stanu do wysłania klientom. Format: ARCHITEKTURA.md §3.1. */
  stan() {
    const teraz = Date.now();
    const odHeartbeatu = this.ostatniHeartbeat
      ? (teraz - this.ostatniHeartbeat) / 1000
      : null;
    const lacze = odHeartbeatu !== null && odHeartbeatu < CISZA_LACZA_S;

    const kursDostepny = this._swieze("kurs_dostepny");

    return {
      typ: "stan",
      czas: teraz / 1000,
      lacze: {
        zywe: lacze,
        sekund_od_heartbeatu: odHeartbeatu,
        ramek: this.ramek,
        bledow: this.bledow,
      },
      lot: {
        tryb: this._swieze("tryb"),
        uzbrojony: this._swieze("uzbrojony"),
        wysokosc_m: this._swieze("wysokosc_m"),
        wznoszenie_ms: this._swieze("wznoszenie_ms"),
        predkosc_ms: this._swieze("predkosc_ms"),
        gaz_proc: this._swieze("gaz_proc"),
      },
      polozenie: {
        lat: this._swieze("lat"),
        lon: this._swieze("lon"),
        kurs_deg: this._swieze("kurs_deg") ?? this._swieze("yaw_deg"),
        kurs_zrodlo: kursDostepny ? "gnss" : null,
      },
      /**
       * Punkt startu. Trzy liczby, więc mieszczą się w migawce bez wyrzutów
       * sumienia — w odróżnieniu od trasy, która idzie osobnym wejściem.
       *
       * Bez wieku: dom nie „psuje się" jak prędkość. Raz podany obowiązuje do
       * końca lotu, a maszyna i tak powtarza go co jakiś czas.
       */
      dom: {
        lat: this.pola.dom_lat ?? null,
        lon: this.pola.dom_lon ?? null,
        wysokosc_m: this.pola.dom_wysokosc_m ?? null,
      },
      misja: {
        // Sama zapowiedź, żeby klient wiedział, CZY warto pytać o szczegóły.
        punktow: this.misja.size,
        oczekiwanych: this.punktowOczekiwanych,
        biezacy: this._swieze("biezacy_punkt"),
        zmieniona: this.misjaZmieniona || null,
      },
      postawa: {
        roll_deg: this._swieze("roll_deg"),
        pitch_deg: this._swieze("pitch_deg"),
      },
      gnss: {
        satelity: this._swieze("satelity"),
        hdop: this._swieze("hdop"),
        fix: this._swieze("fix"),
        kurs_dostepny: kursDostepny,
      },
      ekf: {
        flagi: this._swieze("flagi"),
        wariancja_kursu: this._swieze("wariancja_kursu"),
        pozycja_ok: this._swieze("pozycja_ok"),
      },
      bateria: {
        napiecie_v: this._swieze("napiecie_v"),
        prad_a: this._swieze("prad_a"),
        procent: this._swieze("bateria_proc"),
      },
      komunikaty: this.komunikaty.slice(0, 5),
      ostrzezenia: this._ostrzezenia(lacze, kursDostepny),
    };
  }

  /** Złapana trasa, uporządkowana po `seq`. Wychodzi przez /api/misja. */
  trasaZlapana() {
    return {
      punkty: [...this.misja.values()].sort((a, b) => a.seq - b.seq),
      oczekiwanych: this.punktowOczekiwanych,
      kompletna: this.punktowOczekiwanych !== null && this.misja.size >= this.punktowOczekiwanych,
      zmieniona: this.misjaZmieniona || null,
    };
  }

  // Ostrzeżenia liczy serwer, nie klient — żeby każdy widz zobaczył to samo
  // i żeby nowy klient nie mógł o nich zapomnieć (zasada z ARCHITEKTURA.md §3.1).
  _ostrzezenia(lacze, kursDostepny) {
    const out = [];

    if (!lacze) {
      out.push({
        poziom: "blokada",
        id: "brak_telemetrii",
        tekst: "BRAK TELEMETRII — dane nieaktualne",
      });
      return out; // reszta i tak byłaby zgadywaniem
    }

    // Na tej maszynie kurs pochodzi wyłącznie z bazy GNSS (EK3_SRC1_YAW=2,
    // brak kompasu). Jego utrata zabiera pozycję, RTL i wszystkie tryby
    // poza AltHold — patrz CLAUDE.md poz. 27 i sekcja 5a.
    if (kursDostepny === false) {
      out.push({
        poziom: "blokada",
        id: "kurs_gnss",
        tekst: "BRAK KURSU GNSS — RTL I MISJA NIEDOSTĘPNE",
      });
    }

    const napiecie = this._swieze("napiecie_v");
    if (typeof napiecie === "number" && napiecie > 1) {
      // 6S: BATT_LOW_VOLT=22,2 / BATT_CRT_VOLT=21,0 (CLAUDE.md §1)
      if (napiecie < 21.0) {
        out.push({ poziom: "blokada", id: "bateria_kryt", tekst: `BATERIA KRYTYCZNA ${napiecie.toFixed(1)} V` });
      } else if (napiecie < 22.2) {
        out.push({ poziom: "ostrzezenie", id: "bateria_niska", tekst: `BATERIA NISKA ${napiecie.toFixed(1)} V` });
      }
    }

    const sat = this._swieze("satelity");
    if (typeof sat === "number" && sat < 10) {
      out.push({ poziom: "ostrzezenie", id: "gnss_slabo", tekst: `MAŁO SATELITÓW: ${sat}` });
    }

    return out;
  }
}
