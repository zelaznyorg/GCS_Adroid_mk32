// Panorama — serwer podglądu — strona, API, strumień telemetrii i dostęp użytkowników.
// Opis architektury: dok/SERWER_PODGLADU.md, dostęp: dok/DOSTEP_I_UZYTKOWNICY.md
//
// Czego ten serwer NIE robi, celowo:
//   - nie wysyła komend do maszyny (władza zostaje na MK32 — dok/WLADZA.md),
//     dotyczy to także administratora: panel admina zarządza DOSTĘPEM, nie dronem,
//   - nie dotyka obrazu (tym zajmuje się MediaMTX, w trybie remuksu),
//   - nie zastępuje WireGuarda — logowanie daje tożsamość, nie barierę.
//
// Telemetria idzie przez SSE, nie WebSocket. Strumień jest jednokierunkowy
// (widzowie tylko oglądają), więc SSE wystarcza, nie ciągnie zależności
// i sam wznawia połączenie po stronie przeglądarki. Otwarte połączenie SSE
// jest przy okazji sygnałem obecności — patrz obecnosc.mjs.
import express from "express";
import { existsSync } from "node:fs";
import { join, sep } from "node:path";
import { networkInterfaces } from "node:os";
import {
  ROOT,
  readZrodla,
  readTelemetria,
  readArchiwum,
  writeArchiwum,
  generateMediamtxYml,
  pathsForZrodlo,
} from "../scripts/zrodla-lib.mjs";
import * as mtx from "./mediamtx.mjs";
import * as obecnosc from "./obecnosc.mjs";
import * as dostep from "./dostep.mjs";
import * as rejestr from "./rejestr.mjs";
import { stanAdresu } from "./adres_publiczny.mjs";
import { Telemetria } from "./telemetria.mjs";
import { Archiwum } from "./archiwum.mjs";
import * as stacja from "./stacja.mjs";
import * as trasy from "./trasy.mjs";
import * as nadawanie from "./nadawanie.mjs";
import { MostDji, PORT_MQTT } from "./dji.mjs";
import * as djiKonf from "./dji.mjs";
import { OdbiorZrzutu, PORT_ZRZUTU } from "./zrzut.mjs";
import { Pokretlo } from "./pokretlo.mjs";

const PORT = Number(process.env.PORT) || 8095;
const WEB_DIST = join(ROOT, "web", "dist");
// Co ile wysyłamy migawkę stanu do przeglądarek. 10 Hz jak w ARCHITEKTURA.md §3.1.
const HZ_STANU = Number(process.env.HZ_STANU) || 10;

const app = express();
app.set("trust proxy", true);
app.use(express.json());

// ---- zapytania międzyźródłowe (CORS) ----
//
// Potrzebne od 2026-08-23, odkąd klient umie rozmawiać ze stacją pod adresem INNYM
// niż ten, spod którego załadowano stronę (dok/TELEFON.md §2a). Aplikacja dodana
// na pulpit telefonu jest przypięta do adresu instalacji, a adres stacji się zmienia:
// LAN, tunel WireGuard, adres publiczny. Bez tych nagłówków przeglądarka odrzuca
// takie wywołanie sama, zanim serwer je zobaczy.
//
// ⚠ TO NIE JEST OTWARCIE SERWERA NA ŚWIAT. Nagłówki CORS nie wpuszczają nikogo —
//    mówią tylko przeglądarce, że wolno jej pokazać odpowiedź skryptowi z innego
//    źródła. Barierą pozostaje WireGuard (dok/SERWER_PODGLADU.md §9): kto nie ma
//    drogi sieciowej do stacji, nie dostanie niczego niezależnie od tych nagłówków,
//    a kto ma — i tak potrzebuje ważnego żetonu. Portu 8095 nadal NIE wystawiać
//    do internetu.
//
// Odbijamy źródło zamiast `*`, bo `*` jest nie do pogodzenia z żądaniami niosącymi
// poświadczenia, gdyby kiedyś doszły. Ciasteczek nie używamy i `Allow-Credentials`
// świadomie NIE wysyłamy — żeton jedzie nagłówkiem Authorization, więc odbicie
// dowolnego źródła nie otwiera drogi do sesji przeglądarki widza.
app.use("/api", (req, res, next) => {
  const zrodlo = req.get("origin");
  if (zrodlo) {
    res.setHeader("Access-Control-Allow-Origin", zrodlo);
    res.setHeader("Vary", "Origin");
    res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    res.setHeader("Access-Control-Max-Age", "600");
  }
  // Zapytanie wstępne kończymy tutaj — dalej nie ma czego szukać, a przepuszczone
  // do tras trafiłoby w 404 dla `/api/*` i przeglądarka zgłosiłaby błąd CORS
  // zamiast prawdziwej przyczyny.
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

// Ustawienia archiwum trzymamy w zmiennej, bo zmieniają się w trakcie pracy
// (panel admina) i muszą trafić w dwa miejsca naraz: do naszego modułu zapisu
// telemetrii i do ścieżek MediaMTX, który nagrywa obraz.
let ustArchiwum = readArchiwum();
const archiwum = new Archiwum(ustArchiwum);
const telemetria = new Telemetria({ ...readTelemetria(), archiwum });

// Drugie źródło telemetrii: drony DJI przez Cloud API. Ten sam kształt stanu,
// więc strona nie musi wiedzieć, na co patrzy (dok/DJI.md §3).
const dji = new MostDji(() => nadawanie.haslo());
// Obraz z ekranu aparatury DJI — nasz APK na kontrolerze (dok/DJI.md §7).
const zrzut = new OdbiorZrzutu(() => nadawanie.haslo(), "dji");

/**
 * Pokrętło stacji. Przy maszynie nie ma myszy ani klawiatury — jest enkoder
 * obrotowy obsługiwany przez panel GC9A01 z `PI5setup full`, a my jesteśmy
 * jednym z odbiorców jego zdarzeń (server/pokretlo.mjs).
 */
const pokretlo = new Pokretlo();

// Adresy, z których łączył się dany żeton. Potrzebne, żeby przy odcinaniu trafić
// w sesję WebRTC — MediaMTX zna widza po adresie, nie po naszym żetonie.
const adresyZetonu = new Map(); // zetonId -> Set(ip)

function zapamietajAdres(zetonId, ip) {
  if (!zetonId || !ip) return;
  if (!adresyZetonu.has(zetonId)) adresyZetonu.set(zetonId, new Set());
  adresyZetonu.get(zetonId).add(ip);
}

const czystyIp = (s) => String(s || "").replace(/^::ffff:/, "").replace(/^\[|\]$/g, "");

// Każde nieprzewidziane potknięcie w trasie ma zostawić ślad ze stosem wywołań.
// Bez tego "500" w przeglądarce jest wszystkim, co wiadomo — a to nic.
function wrap(fn) {
  return (req, res) =>
    fn(req, res).catch((e) => {
      rejestr.wyjatek("api", `${req.method} ${req.path}`, e, { kto: req.kto?.imie || null });
      res.status(500).json({ blad: String(e.message || e) });
    });
}

// ---- tożsamość ----
//
// Żeton przychodzi nagłówkiem Authorization, a dla EventSource — parametrem w adresie.
// EventSource nie umie ustawiać nagłówków i to jest jedyny powód tego wyjątku.
// Konsekwencja: żeton bywa widoczny w logach dostępu. Wewnątrz tunelu przyjmujemy
// to świadomie (dok/DOSTEP_I_UZYTKOWNICY.md §7).
function wezZeton(req) {
  const naglowek = req.get("authorization") || "";
  if (/^Bearer /i.test(naglowek)) return naglowek.slice(7).trim();
  if (req.query && typeof req.query.zeton === "string") return req.query.zeton;
  return null;
}

function rozpoznaj(req) {
  const z = dostep.sprawdzZeton(wezZeton(req));
  if (z) zapamietajAdres(z.id, czystyIp(req.ip));
  return z;
}

const RANGA = { widz: 1, operator: 2, admin: 3 };

function wymagaj(minRola = "widz") {
  return (req, res, next) => {
    const z = rozpoznaj(req);
    if (!z) return res.status(401).json({ blad: "Potrzebne zaproszenie." });
    if (RANGA[z.rola] < RANGA[minRola]) return res.status(403).json({ blad: "Za mało uprawnień." });
    req.kto = z;
    next();
  };
}

// ---- wejście: zaproszenia ----

app.post("/api/zaproszenie", wrap(async (req, res) => {
  try {
    const wynik = dostep.uzyjZaproszenia(req.body?.kod, czystyIp(req.ip));
    res.json(wynik);
  } catch (e) {
    // 403, nie 500 — odrzucone zaproszenie to normalny przebieg, nie awaria.
    res.status(403).json({ blad: String(e.message || e) });
  }
}));

app.get("/api/ja", (req, res) => {
  const z = rozpoznaj(req);
  if (!z) return res.status(401).json({ blad: "Potrzebne zaproszenie." });
  const u = dostep.ustawienia();
  res.json({
    imie: z.imie,
    rola: z.rola,
    zetonId: z.id,
    zrodloDomyslne: u.zrodloDomyslne,
    cisza: u.cisza,
  });
});

// ---- API: źródła obrazu ----

app.get("/api/zrodla", wymagaj("widz"), wrap(async (_req, res) => {
  res.json({
    zrodla: readZrodla().map((z) => ({
      id: z.id,
      nazwa: z.nazwa,
      maPomocniczy: Boolean(z.rtspPomocniczy),
    })),
    zrodloDomyslne: dostep.ustawienia().zrodloDomyslne,
  });
}));

app.get("/api/status", wymagaj("widz"), wrap(async (_req, res) => {
  res.json({
    mediamtx: await mtx.isUp(),
    sciezki: await mtx.pathsStatus(),
    telemetria: telemetria.stan().lacze,
  });
}));

// ---- API: telemetria + obecność ----

/**
 * Który dostawca telemetrii obsługuje wybrane źródło obrazu.
 *
 * Stacja obsługuje dwie różne maszyny naraz: DRON 15 gada MAVLinkiem przez MK32,
 * a drony DJI — Cloud API po MQTT. Kształt stanu jest ten sam, więc wystarczy
 * wskazać właściwe źródło; strona, HUD i mapa nie muszą o tym wiedzieć.
 */
function telemetriaDla(zrodlo) {
  return String(zrodlo || "").startsWith("dji") ? dji : telemetria;
}

app.get("/api/stan", wymagaj("widz"), (req, res) =>
  res.json(telemetriaDla(req.query.zrodlo).stan())
);

// Kto teraz ogląda. Widz dostaje imiona i strumienie, bez adresów (decyzja 8).
app.get("/api/widzowie", wymagaj("widz"), (req, res) => {
  res.json({ widzowie: obecnosc.lista(), ja: req.kto.id });
});

// Strumień SSE. Przeglądarka: new EventSource("/api/telemetria?zeton=...").
app.get("/api/telemetria", (req, res) => {
  const kto = rozpoznaj(req);
  if (!kto) return res.status(401).json({ blad: "Potrzebne zaproszenie." });

  const u = dostep.ustawienia();
  if (u.cisza && kto.rola !== "admin") {
    return res.status(423).json({ blad: "Stacja w trybie ciszy." });
  }
  if (kto.rola !== "admin" && obecnosc.liczbaWidzow() >= u.limitWidzow) {
    return res.status(429).json({ blad: `Komplet widzów (${u.limitWidzow}).` });
  }

  res.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
    // Wyłącza buforowanie w pośrednikach — bez tego strumień potrafi utknąć.
    "X-Accel-Buffering": "no",
  });

  // Źródło wybiera nie tylko obraz, ale i telemetrię — patrz `telemetriaDla`.
  const dostawca = telemetriaDla(req.query.zrodlo);

  const idPol = obecnosc.dolacz({
    zetonId: kto.id,
    imie: kto.imie,
    rola: kto.rola,
    ip: czystyIp(req.ip),
    zrodlo: typeof req.query.zrodlo === "string" ? req.query.zrodlo : null,
  });

  // Pierwsza wiadomość niesie identyfikator połączenia — przeglądarka melduje nim
  // później, na co przełączyła obraz.
  res.write(`event: polaczenie\ndata: ${JSON.stringify({ id: idPol, imie: kto.imie, rola: kto.rola })}\n\n`);

  const timer = setInterval(() => {
    res.write(`data: ${JSON.stringify(dostawca.stan())}\n\n`);
  }, Math.round(1000 / HZ_STANU));

  let zamkniete = false;
  const sprzataj = () => {
    if (zamkniete) return;
    zamkniete = true;
    clearInterval(timer);
    obecnosc.usunZamykacz(idPol);
    obecnosc.odlacz(idPol);
  };

  // Administrator odcinający widza musi móc zerwać to połączenie z zewnątrz.
  obecnosc.zarejestrujZamykacz(idPol, () => {
    try {
      res.write(`event: odciety\ndata: {}\n\n`);
      res.end();
    } finally {
      clearInterval(timer);
      zamkniete = true;
    }
  });

  req.on("close", sprzataj);
  res.on("close", sprzataj);

  dostep.zapiszZdarzenie("polaczenie", `${kto.imie} zaczął oglądać`, { imie: kto.imie, ip: czystyIp(req.ip) });
  rejestr.szczegol("obecnosc", `SSE otwarte: ${kto.imie}`, { polaczenie: idPol, ip: czystyIp(req.ip) });
});

// Melunek "przełączyłem się na ten strumień" — jedyna rzecz, której z samego
// połączenia SSE nie widać.
app.post("/api/obecnosc", wymagaj("widz"), (req, res) => {
  const ok = obecnosc.ustawZrodlo(req.body?.polaczenie, req.body?.zrodlo);
  res.json({ ok });
});

// ---- uwierzytelnianie dla MediaMTX ----
//
// MediaMTX pyta nas o zgodę przed KAŻDYM odtworzeniem (authMethod: http).
// To jest jedyne miejsce, w którym decyduje się, czy widz zobaczy obraz —
// strona i telemetria to osobna sprawa.
app.post("/api/mtx-auth", (req, res) => {
  const { user, password, action, path, ip } = req.body || {};

  // Zapytanie przychodzi z lokalnego MediaMTX. Gdyby przyszło skądinąd, to znaczy,
  // że ktoś próbuje udawać serwer mediów — odmawiamy bez zastanowienia.
  const zrodloIp = czystyIp(req.ip);
  if (zrodloIp !== "127.0.0.1" && zrodloIp !== "::1") {
    return res.status(401).json({ blad: "Nie z tego adresu." });
  }

  // Publikować do nas nikt nie ma prawa — kamera jest źródłem pobieranym,
  // nie nadawcą. Zostaje samo czytanie.
  // Publikować wolno WYŁĄCZNIE źródłom, które same wypychają obraz (drony DJI):
  // pod ścieżki z listy i osobnym hasłem nadawania, nigdy żetonem widza.
  // Kamera pokładowa zostaje źródłem POBIERANYM — dla niej nic się nie zmienia.
  if (action === "publish") {
    if (nadawanie.wolnoNadawac(path, password)) {
      rejestr.info("nadawanie", `źródło nadaje pod ścieżkę ${path}`, { ip: czystyIp(ip) });
      return res.status(200).end();
    }
    dostep.zapiszZdarzenie("odmowa", `odmowa nadawania (${path || "?"})`, { ip: czystyIp(ip) });
    return res.status(401).end();
  }
  if (action && action !== "read") return res.status(401).end();

  // Odczyt po RTSP z pętli zwrotnej — to NAGRYWARKA pulpitu (gcs_pulpit) albo inny
  // proces na tej samej maszynie. Nasłuch RTSP jest przypięty do 127.0.0.1
  // (zrodla-lib.mjs), więc nikt z sieci tą drogą nie wejdzie; żeton widza nie ma
  // tu sensu, bo ffmpeg nagrywarki go nie ma i mieć nie będzie. ⚠ Warunek jest na
  // PROTOKÓŁ, nie na sam adres: przeglądarka pulpitu też pyta z 127.0.0.1, ale po
  // WebRTC — i ona ma się nadal legitymować żetonem, bo tak odcina się widza.
  const ipKlienta = czystyIp(ip);
  const zPetli = ipKlienta === "127.0.0.1" || ipKlienta === "::1";
  if (req.body?.protocol === "rtsp" && zPetli) {
    return res.status(200).end();
  }

  const kto = dostep.sprawdzZeton(`${user}.${password}`);
  if (!kto) {
    dostep.zapiszZdarzenie("odmowa", `odmowa obrazu (${path || "?"})`, { ip: czystyIp(ip) });
    return res.status(401).end();
  }

  const u = dostep.ustawienia();
  if (u.cisza && kto.rola !== "admin") return res.status(401).end();

  zapamietajAdres(kto.id, czystyIp(ip));
  res.status(200).end();
});

// ---- API: adresy serwera ----
//
// Adres publiczny stacji to adres bramy do sieci — widzowi do niczego nie jest
// potrzebny, a wiedza o nim jest warta więcej niż wygoda. Stąd rola admina.
// Konfiguracja dla strony, którą otwiera DJI Pilot 2 (`web/public/dji.html`).
//
// ⛔ Ten punkt NIE może wymagać żetonu widza: aparatura DJI go nie ma i mieć nie
// będzie. Zamiast tego pilnuje go klucz — to samo hasło urządzenia, które i tak
// wraca w odpowiedzi. Kto zna klucz, ten i tak mógłby się połączyć z brokerem,
// więc nic nowego nie oddajemy; kto go nie zna, nie dostaje nic.
app.get("/api/dji/konfiguracja", (req, res) => {
  const klucz = String(req.query.k || "");
  const oczekiwany = nadawanie.haslo();
  if (klucz.length !== oczekiwany.length || klucz !== oczekiwany) {
    dostep.zapiszZdarzenie("odmowa", "odmowa konfiguracji DJI", { ip: czystyIp(req.ip) });
    return res.status(401).json({ blad: "Zły klucz." });
  }
  const u = djiKonf.ustawienia();
  const gospodarz = String(req.headers.host || "").split(":")[0];
  res.json({
    // Pilot 2 wymaga przedrostka tcp:// albo ws:// — bez niego moduł się nie ładuje.
    mqtt: `tcp://${gospodarz}:${PORT_MQTT}`,
    uzytkownik: u.uzytkownik,
    haslo: oczekiwany,
    appId: u.appId,
    appKey: u.appKey,
    licencja: u.licencja,
    nazwaPlatformy: u.nazwaPlatformy,
    nazwaObszaru: u.nazwaObszaru,
    opis: u.opis,
    obszarId: u.obszarId,
  });
});

app.get("/api/zrzut", wymagaj("admin"), (req, res) => {
  const gospodarz = String(req.headers.host || "").split(":")[0];
  res.json({
    ...zrzut.stan(),
    // Adres do wpisania w APK na aparaturze.
    adres: `${gospodarz}:${PORT_ZRZUTU}`,
    haslo: nadawanie.haslo(),
  });
});

app.get("/api/dji/ustawienia", wymagaj("admin"), (req, res) => {
  const u = djiKonf.ustawienia();
  const gospodarz = String(req.headers.host || "").split(":")[0];
  res.json({
    ...u,
    gotowe: djiKonf.gotowe(),
    // Adres do wpisania w aparaturze: Pilot 2 → Cloud Service → Open Platforms.
    adresDlaPilota: `http://${gospodarz}:${Number(process.env.PORT) || 8095}/dji.html?k=${nadawanie.haslo()}`,
    brokerMqtt: `tcp://${gospodarz}:${PORT_MQTT}`,
  });
});

app.post("/api/dji/ustawienia", wymagaj("admin"), (req, res) => {
  const u = djiKonf.ustaw(req.body || {});
  rejestr.info("dji", "zmieniono ustawienia wpięcia DJI", { gotowe: djiKonf.gotowe() });
  res.json({ ...u, gotowe: djiKonf.gotowe() });
});

app.get("/api/nadawanie", wymagaj("admin"), (req, res) => {
  // Adres do wpisania w aparaturze DJI. Niesie hasło, więc widzowi go nie pokazujemy.
  const gospodarz = String(req.headers.host || "").split(":")[0] || "192.168.88.30";
  res.json({
    haslo: nadawanie.haslo(),
    sciezki: nadawanie.SCIEZKI_NADAWANIA,
    adresy: nadawanie.SCIEZKI_NADAWANIA.map(
      (s) => `rtmp://${gospodarz}:1935/${s}?user=dji&pass=${nadawanie.haslo()}`
    ),
  });
});

app.post("/api/nadawanie/nowe-haslo", wymagaj("admin"), (_req, res) => {
  const h = nadawanie.nowHaslo();
  rejestr.ostrzezenie("nadawanie", "wymieniono hasło nadawania — stare adresy przestały działać");
  res.json({ haslo: h });
});

app.get("/api/adresy", wymagaj("admin"), wrap(async (_req, res) => {
  const adresy = [];
  for (const [nazwa, lista] of Object.entries(networkInterfaces())) {
    for (const i of lista || []) {
      if (i.family !== "IPv4" || i.internal) continue;
      adresy.push({ interfejs: nazwa, adres: i.address });
    }
  }
  res.json({
    adresy,
    publiczny: await stanAdresu(),
    porty: { strona: PORT, whep: 8889, media_udp: 8189 },
  });
}));

// ---- panel administratora ----

app.get("/api/admin/stan", wymagaj("admin"), wrap(async (_req, res) => {
  res.json({
    widzowie: obecnosc.listaPelna(),
    zaproszenia: dostep.zaproszenia(),
    zetony: dostep.zetony(),
    ustawienia: dostep.ustawienia(),
    mediamtx: await mtx.isUp(),
    sesjeObrazu: await mtx.webrtcSessions(),
    telemetria: telemetria.stan().lacze,
  });
}));

app.post("/api/admin/zaproszenie", wymagaj("admin"), wrap(async (req, res) => {
  try {
    const z = dostep.utworzZaproszenie({
      imie: req.body?.imie,
      rola: req.body?.rola || "widz",
      waznoscMin: req.body?.waznoscMin ?? null,
      jednorazowe: req.body?.jednorazowe ?? true,
    });
    res.json({ id: z.id, kod: z.kod, imie: z.imie, rola: z.rola, wygasa: z.wygasa });
  } catch (e) {
    res.status(400).json({ blad: String(e.message || e) });
  }
}));

app.get("/api/admin/zaproszenie/:id/kod", wymagaj("admin"), (req, res) => {
  const kod = dostep.kodZaproszenia(req.params.id);
  if (!kod) return res.status(404).json({ blad: "Nie ma takiego zaproszenia." });
  res.json({ kod });
});

app.delete("/api/admin/zaproszenie/:id", wymagaj("admin"), wrap(async (req, res) => {
  try {
    dostep.uniewaznijZaproszenie(req.params.id);
    res.json({ ok: true });
  } catch (e) {
    res.status(404).json({ blad: String(e.message || e) });
  }
}));

// Odcięcie widza. Trzy kroki, bo trzy rzeczy trzeba zabrać: prawo wstępu,
// otwarte połączenie telemetrii i trwającą sesję obrazu.
app.post("/api/admin/odetnij", wymagaj("admin"), wrap(async (req, res) => {
  const id = req.body?.zetonId;
  try {
    const z = dostep.odetnij(id);
    const zerwane = obecnosc.zamknijZeton(id);
    const ubite = await mtx.kickPoAdresie(adresyZetonu.get(id) || []);
    res.json({ ok: true, imie: z.imie, zerwaneStrumienieDanych: zerwane, ubiteSesjeObrazu: ubite });
  } catch (e) {
    res.status(404).json({ blad: String(e.message || e) });
  }
}));

app.post("/api/admin/ustawienia", wymagaj("admin"), wrap(async (req, res) => {
  try {
    const u = dostep.ustaw(req.body || {});
    // Tryb ciszy działa od razu, nie od następnego wejścia — inaczej byłby
    // deklaracją, a nie przełącznikiem.
    if (u.cisza) {
      const zerwane = obecnosc.zamknijWszystkich({ pomijajAdmina: true });
      const adresy = new Set();
      for (const [zetonId, zbior] of adresyZetonu) {
        const z = dostep.zetony().find((x) => x.id === zetonId);
        if (z && z.rola !== "admin") for (const ip of zbior) adresy.add(ip);
      }
      const ubite = await mtx.kickPoAdresie(adresy);
      return res.json({ ustawienia: u, zerwaneStrumienieDanych: zerwane, ubiteSesjeObrazu: ubite });
    }
    res.json({ ustawienia: u });
  } catch (e) {
    res.status(400).json({ blad: String(e.message || e) });
  }
}));

app.get("/api/admin/dziennik", wymagaj("admin"), (req, res) => {
  res.json({ dziennik: dostep.dziennik(Number(req.query.ile) || 100) });
});

// Rejestr techniczny — co się zepsuło, ze stosem wywołań. To inny dziennik niż powyższy:
// tamten mówi, kto co zrobił, ten — dlaczego nie zadziałało (dok/LOGI_I_BLEDY.md).
app.get("/api/admin/logi", wymagaj("admin"), (req, res) => {
  res.json({
    wpisy: rejestr.ostatnieWpisy(Number(req.query.ile) || 120, req.query.poziom || null),
    plik: rejestr.sciezkaPliku(),
  });
});

// ---- archiwum ----
//
// Archiwum zapisuje to, co i tak przez stację przechodzi. Włączenie nagrywania
// obrazu w trybie "zawsze" jest jedyną rzeczą w tym panelu, która ZMIENIA ruch
// na łączu radiowym — dlatego jest opisana wprost, a nie schowana pod przełącznikiem.
// Komend do maszyny nadal nie wysyłamy (dok/WLADZA.md).

app.get("/api/admin/archiwum", wymagaj("admin"), wrap(async (req, res) => {
  res.json(await archiwum.stan(Number(req.query.ile) || 20));
}));

app.post("/api/admin/archiwum", wymagaj("admin"), wrap(async (req, res) => {
  const poprzedniTryb = ustArchiwum.wideo;
  try {
    ustArchiwum = writeArchiwum(req.body || {});
  } catch (e) {
    return res.status(400).json({ blad: String(e.message || e) });
  }
  archiwum.ustaw(ustArchiwum);
  archiwum.start();

  // Tryb nagrywania obrazu siedzi w konfiguracji ŚCIEŻEK MediaMTX, więc zmiana
  // wymaga ich przepisania. Robimy to na żywo, przez API — restart stacji zabrałby
  // obraz wszystkim widzom po to, żeby zmienić ustawienie zapisu.
  let sciezek = 0;
  if (ustArchiwum.wideo !== poprzedniTryb || req.body?.trzymajDni !== undefined) {
    for (const z of readZrodla()) {
      for (const { name, conf } of pathsForZrodlo(z, ustArchiwum)) {
        try {
          if (await mtx.upsertPath(name, conf)) sciezek += 1;
        } catch (e) {
          rejestr.wyjatek("archiwum", `nie mogę przestawić ścieżki ${name}`, e);
        }
      }
    }
    // Plik na dysku też, żeby ustawienie przeżyło restart MediaMTX.
    generateMediamtxYml(readZrodla(), ustArchiwum);
    dostep.zapiszZdarzenie("archiwum", `nagrywanie obrazu: ${ustArchiwum.wideo}`, { kto: req.kto.imie });
  }

  res.json({ ustawienia: ustArchiwum, przestawionychSciezek: sciezek });
}));

// Sprzątanie na żądanie — normalnie chodzi samo co kwadrans, ale przed wyjazdem
// w teren chce się wiedzieć od razu, ile miejsca jest naprawdę.
app.post("/api/admin/archiwum/sprzataj", wymagaj("admin"), wrap(async (_req, res) => {
  const wynik = archiwum.sprzataj();
  res.json({ wynik, stan: await archiwum.stan() });
}));

// ---- pokrętło ----
//
// Strumień osobny od telemetrii i **tylko dla jednego odbiorcy**: pokrętło jest
// jedno i stoi fizycznie przy stacji. Rozsyłanie jego obrotów wszystkim widzom
// przestawiałoby ekrany ludziom, którzy go nie dotykają.
//
// Zamknięcie tego strumienia oddaje pokrętło panelowi — to ta sama zasada, co
// u nich: ognisko nie może utknąć poza panelem, bo przy maszynie nie ma klawiatury.

/**
 * Czy żądanie przyszło z samej maszyny stacji.
 *
 * Przeglądarka na stacji łączy się po adresie sieciowym (`192.168.88.30:8095`),
 * a nie po `localhost`, więc samo sprawdzenie pętli zwrotnej nie wystarcza —
 * porównujemy też z adresami własnych kart sieciowych.
 */
function zSamejStacji(req) {
  const skad = String(req.ip || "").replace(/^::ffff:/, "");
  if (skad === "127.0.0.1" || skad === "::1") return true;
  for (const karty of Object.values(networkInterfaces())) {
    for (const k of karty || []) if (k.address === skad) return true;
  }
  return false;
}

app.post("/api/stacja/zamknij-podglad", async (req, res) => {
  const kto = rozpoznaj(req);
  if (!kto) return res.status(401).json({ blad: "Potrzebne zaproszenie." });
  // ⛔ Tylko z samej stacji. Bez tego widz z telefonu gasiłby ekran operatorowi —
  // a zdalne zamknięcie okna nikomu poza stanowiskiem do niczego nie służy.
  if (!zSamejStacji(req)) {
    return res.status(403).json({ blad: "Zamknąć podgląd można tylko z ekranu stacji." });
  }
  const wynik = await stacja.zamknijPodglad();
  rejestr.info("stacja", `${kto.imie} zamyka okno podglądu na stacji`, wynik);
  res.json(wynik);
});

app.post("/api/pokretlo/oddaj", (req, res) => {
  const kto = rozpoznaj(req);
  if (!kto) return res.status(401).json({ blad: "Potrzebne zaproszenie." });
  // Wolno tylko temu, kto pokrętło trzyma — inaczej dowolny widz odbierałby je stacji.
  if (pokretlo.trzymajacy?.imie !== kto.imie) {
    return res.status(409).json({ blad: "Pokrętła nie trzyma ta przeglądarka." });
  }
  const poszlo = pokretlo.przekazDalej();
  rejestr.info("pokretlo", `${kto.imie} przekazuje pokrętło pulpitowi (długie przytrzymanie)`);
  res.json({ ok: poszlo });
});

app.get("/api/pokretlo", (req, res) => {
  const kto = rozpoznaj(req);
  if (!kto) return res.status(401).json({ blad: "Potrzebne zaproszenie." });

  if (pokretlo.trzymajacy && pokretlo.trzymajacy.imie !== kto.imie) {
    return res.status(409).json({
      blad: `Pokrętło trzyma już ${pokretlo.trzymajacy.imie}.`,
      trzyma: pokretlo.trzymajacy.imie,
    });
  }

  res.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  });

  const wyslij = (w) => res.write(`data: ${JSON.stringify(w)}\n\n`);
  pokretlo.trzymajacy = { imie: kto.imie, wyslij };
  pokretlo.wezOgnisko();
  wyslij({ typ: "powitanie", ...pokretlo.stan() });
  rejestr.info("pokretlo", `${kto.imie} bierze pokrętło`);
  dostep.zapiszZdarzenie("pokretlo", `${kto.imie} przejął pokrętło`, { imie: kto.imie });

  // Podtrzymanie: pośrednicy potrafią uciąć strumień, w którym długo nic nie leci,
  // a pokrętło bywa nieruszane godzinami.
  const bicie = setInterval(() => res.write(": bicie\n\n"), 20000);

  const koniec = () => {
    clearInterval(bicie);
    if (pokretlo.trzymajacy?.wyslij === wyslij) {
      pokretlo.trzymajacy = null;
      pokretlo.oddajOgnisko();
      rejestr.info("pokretlo", `${kto.imie} oddaje pokrętło — ognisko wraca do panelu`);
    }
  };
  req.on("close", koniec);
  res.on("close", koniec);
});

app.get("/api/pokretlo/stan", wymagaj("widz"), (_req, res) => {
  res.json({ ...pokretlo.stan(), trzyma: pokretlo.trzymajacy?.imie ?? null });
});

// ---- trasy i misja dla mapy ----
//
// Osobno od /api/stan, bo to dane, które zmieniają się raz na lot, a migawka
// stanu leci 10 razy na sekundę. Doklejenie do niej pięćdziesięciopunktowej trasy
// byłoby marnowaniem łącza na powtarzanie tego samego.

app.get("/api/trasy", wymagaj("widz"), wrap(async (_req, res) => {
  res.json({ trasy: await trasy.lista(), katalog: trasy.KATALOG_TRAS });
}));

app.get("/api/trasy/:nazwa", wymagaj("widz"), wrap(async (req, res) => {
  try {
    res.json(await trasy.wczytaj(req.params.nazwa));
  } catch (e) {
    res.status(404).json({ blad: String(e.message || e) });
  }
}));

/** Trasa złapana biernie z łącza — pojawia się, gdy kokpit ją przepuści. */
app.get("/api/misja", wymagaj("widz"), (_req, res) => {
  res.json(telemetria.trasaZlapana());
});

// ---- stacja ----
//
// Obsługa MASZYNY, na której to stoi: usługi, dziennik systemowy, zasilanie, sieć.
// Odpowiednik `rpi/sprawdz.sh` w przeglądarce — żeby nie trzeba było wchodzić po ssh.
//
// ⛔ Granica bez wyjątków: stąd nie prowadzi żadna droga do kontrolera lotu ani
// do głowicy. Restart usługi podglądu zabiera obraz widzom i nic poza tym.
// Władza nad maszyną zostaje na MK32 (dok/WLADZA.md).

app.get("/api/admin/stacja", wymagaj("admin"), wrap(async (req, res) => {
  res.json(await stacja.przeglad({ zCache: req.query.swiezo !== "1" }));
}));

app.get("/api/admin/stacja/dziennik", wymagaj("admin"), wrap(async (req, res) => {
  try {
    res.json(await stacja.dziennikUslugi(req.query.usluga, req.query.ile));
  } catch (e) {
    res.status(400).json({ blad: String(e.message || e) });
  }
}));

app.post("/api/admin/stacja/restart", wymagaj("admin"), wrap(async (req, res) => {
  const nazwa = req.body?.usluga;
  try {
    const wynik = await stacja.restart(nazwa);
    // Do dziennika dostępu, nie tylko do rejestru technicznego: restart usługi
    // widać u wszystkich widzów, więc ma zostać ślad, KTO go zrobił.
    dostep.zapiszZdarzenie("stacja", `restart usługi ${nazwa}`, { kto: req.kto.imie });
    rejestr.info("stacja", `${req.kto.imie} restartuje ${nazwa}`);
    res.json({ ok: true, ...wynik });
  } catch (e) {
    rejestr.ostrzezenie("stacja", `restart ${nazwa} nie powiódł się`, { blad: String(e.message || e) });
    res.status(400).json({ blad: String(e.message || e) });
  }
}));

// ---- nieznane trasy API ----
//
// Bez tego literówka w adresie dostaje stronę HTML z kodem 200, a w konsoli
// przeglądarki widać "Unexpected token <". Szukanie takiej pomyłki potrafi zająć
// godzinę, więc mówimy wprost, czego nie ma.
app.all("/api/*", (req, res) => {
  rejestr.ostrzezenie("api", `nieznana trasa ${req.method} ${req.path}`);
  res.status(404).json({ blad: `Nie ma takiej trasy: ${req.method} ${req.path}` });
});

// ---- strona ----

if (existsSync(WEB_DIST)) {
  app.use(
    express.static(WEB_DIST, {
      setHeaders(res, sciezka) {
        // Service worker i manifest MUSZĄ być sprawdzane przy każdym wejściu.
        // Zbuforowany sw.js potrafi trzymać starą wersję aplikacji na telefonie
        // tygodniami — i żadne odświeżenie strony tego nie ruszy, bo to właśnie
        // stary worker odpowiada na zapytania. Patrz dok/TELEFON.md §4.
        if (sciezka.endsWith("sw.js") || sciezka.endsWith(".webmanifest")) {
          res.setHeader("Cache-Control", "no-cache");
        } else if (sciezka.endsWith("index.html")) {
          // index.html wskazuje na pakiety, więc trzymanie GO w pamięci znaczy
          // stary program mimo świeżo wgranej wersji. Zmierzone 2026-08-29:
          // po wgraniu mapy przeglądarka wciąż uruchamiała poprzedni pakiet,
          // choć serwer oddawał już nowy index.html.
          res.setHeader("Cache-Control", "no-store, must-revalidate");
        } else if (sciezka.includes(`${sep}assets${sep}`)) {
          // Pakiety mają skrót w nazwie, więc mogą leżeć w pamięci bez końca —
          // zmiana kodu zmienia nazwę pliku.
          res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        }
      },
    }),
  );
  /**
   * Nieistniejący plik z /assets/ ma dawać 404, a NIE stronę.
   *
   * Bez tego reguła „wszystko inne to strona" odpowiadała HTML-em z kodem 200 na
   * żądanie skasowanego pakietu. Przeglądarka dostawała `<!doctype html>` tam,
   * gdzie spodziewała się JavaScriptu, i meldowała „Unexpected token <" — objaw,
   * po którym nie sposób zgadnąć przyczyny. Ta sama zasada, co przy /api/* wyżej.
   */
  app.get("/assets/*", (req, res) => {
    rejestr.ostrzezenie("strona", `brak pakietu ${req.path} — przeglądarka ma nieaktualny index.html`);
    res.status(404).type("text/plain").send("Nie ma takiego pakietu. Odśwież stronę (Ctrl+F5).");
  });

  app.get("*", (req, res, next) => {
    if (req.path.startsWith("/api")) return next();
    res.sendFile(join(WEB_DIST, "index.html"));
  });
  rejestr.info("start", `strona z ${WEB_DIST}`);
} else {
  rejestr.ostrzezenie("start", "brak web/dist — w trybie deweloperskim stronę serwuje Vite");
}

// ---- ostatnia deska ratunku dla tras ----
//
// Express łapie tu wyjątki rzucone SYNCHRONICZNIE w trasach. Te z `async`
// przechwytuje wrap(), a te z timerów i gniazd — pułapki procesu w rejestr.mjs.
// Trzy różne drogi, bo w Node nie ma jednej.
app.use((e, req, res, _next) => {
  rejestr.wyjatek("api", `nieprzechwycony błąd trasy ${req.method} ${req.path}`, e);
  if (res.headersSent) return;
  res.status(500).json({ blad: "Błąd serwera — szczegóły w logach stacji." });
});

// ---- start ----

async function start() {
  const zrodla = readZrodla();
  generateMediamtxYml(zrodla, ustArchiwum);

  const pierwszeZaproszenie = dostep.zapewnijAdmina();
  if (pierwszeZaproszenie) {
    console.log("");
    console.log("  ┌──────────────────────────────────────────────────────────────┐");
    console.log("  │  PIERWSZE WEJŚCIE ADMINISTRATORA                             │");
    console.log("  └──────────────────────────────────────────────────────────────┘");
    console.log(`  kod: ${pierwszeZaproszenie.kod}`);
    console.log(`  link: http://<adres-stacji>:${PORT}/#z=${pierwszeZaproszenie.kod}`);
    console.log("  Kod zostaje ważny, dopóki nie unieważnisz go w panelu.");
    console.log("");
  }

  archiwum.start();
  await trasy.zapewnijKatalog();

  // Zdarzenia pokrętła idą wyłącznie do tego, kto je trzyma.
  pokretlo.on("zdarzenie", (z) => pokretlo.trzymajacy?.wyslij(z));
  pokretlo.on("polaczenie", (jest) => {
    pokretlo.trzymajacy?.wyslij({ typ: "most", polaczony: jest });
    // Okrągły ekran ma pokazywać prawdę o nagrywaniu, a nie własne domysły.
    if (jest) meldujPanelowi();
  });
  pokretlo.start();
  // Meldunek stanu co pół minuty — panel go tylko wyświetla, sam nic nie liczy.
  const timerMeldunku = setInterval(meldujPanelowi, 30000);
  timerMeldunku.unref?.();
  telemetria.start();
  zrzut.start();
  dji.start().catch((e) => rejestr.ostrzezenie("dji", "most DJI nie wstal", { blad: e.message }));

  const serwer = app.listen(PORT, () => rejestr.info("start", `nasłuch http://0.0.0.0:${PORT}`));
  serwer.on("error", (e) => {
    // Zajęty port to najczęstszy powód, dla którego "nie działa" — ma być widoczny wprost.
    rejestr.wyjatek("start", `nie mogę zająć portu ${PORT}`, e);
    process.exit(1);
  });

  if (await mtx.waitUntilUp(20000)) {
    for (const z of zrodla) {
      for (const { name, conf } of pathsForZrodlo(z, ustArchiwum)) {
        try {
          await mtx.upsertPath(name, conf);
        } catch (e) {
          rejestr.wyjatek("mediamtx", `synchronizacja ścieżki ${name} nie powiodła się`, e);
        }
      }
    }
    rejestr.info("mediamtx", `zsynchronizowano ${zrodla.length} źródeł`);
  } else {
    rejestr.ostrzezenie("mediamtx", "nie odpowiada — ścieżki wezmą się z wygenerowanego mediamtx.yml");
  }
}

// Sprzątanie wspólne dla wyjścia normalnego i awaryjnego.
/** Co pokazać na okrągłym ekranie panelu: czy nagrywamy i krótki opis. */
function meldujPanelowi() {
  const s = telemetria.stan();
  const zywe = s.lacze?.zywe;
  const opis = zywe
    ? `${s.lot?.tryb ?? "—"} · ${s.gnss?.satelity ?? "?"} sat`
    : "brak telemetrii";
  pokretlo.meldujStan(archiwum.wlaczone && archiwum.tryb !== "nie", opis);
}

function posprzataj() {
  pokretlo.stop();
  telemetria.stop();
  dji.stop();
  zrzut.stop();
  // Zamyka bieżący .tlog. Bez tego ostatnie ramki zostają w buforze strumienia
  // i nagranie kończy się kilka sekund wcześniej, niż lot.
  archiwum.stop();
}

rejestr.zainstalujPulapki({ przedWyjsciem: posprzataj });

start().catch((e) => {
  rejestr.wyjatek("start", "start serwera nie powiódł się", e);
  process.exit(1);
});
