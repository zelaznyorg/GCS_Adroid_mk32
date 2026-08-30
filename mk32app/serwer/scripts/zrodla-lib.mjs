// Konfiguracja źródeł obrazu: odczyt/zapis zrodla.json, walidacja,
// budowanie konfiguracji MediaMTX (strumień główny + pomocniczy).
//
// Pochodzi z projektu NRK (scripts/cameras-lib.mjs). Różnice wobec oryginału:
//   - pojęcia po polsku i pod drona (zrodlo zamiast camera),
//   - doszła sekcja `telemetria` (adres MAVLink z jednostki naziemnej MK32),
//   - usunięto ONVIF — SIYI go nie ma.
//
// Dlaczego para strumieni: ZR30 wydaje /video1 (główny) i /video2 (podgląd) niezależnie.
// Przy H.265 na głównym para pozwala w każdej chwili przełączyć przeglądarki na
// pomocniczy H.264 bez transkodowania — patrz dok/SERWER_PODGLADU.md §4.
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, isAbsolute, join } from "node:path";

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
// DATA_DIR pozwala trzymać stan poza repo — np. na wolumenie albo karcie w RPi.
export const DATA_DIR = process.env.DATA_DIR || ROOT;
export const ZRODLA_PATH = join(DATA_DIR, "zrodla.json");
export const MEDIAMTX_YML_PATH = join(ROOT, "mediamtx", "mediamtx.yml");
export const WEB_CONFIG_PATH = join(ROOT, "web", "public", "zrodla.json");

export const ID_RE = /^[A-Za-z0-9_-]+$/;

// Domyślny adres telemetrii: jednostka naziemna MK32 (INTERFEJSY.md §1 — FAKT).
export const TELEMETRIA_DOMYSLNA = { host: "192.168.144.12", port: 19856 };

// Tryby nagrywania obrazu. Rozróżnienie jest istotne i kosztowne:
//
//   nie            MediaMTX nie zapisuje obrazu wcale.
//   przy-widzach   zapisuje wtedy, gdy ktoś ogląda. Ścieżka zostaje `sourceOnDemand`,
//                  więc dopóki nikt nie patrzy, przez łącze radiowe nie idzie nic
//                  i slot ZR30 (maks. 4 strumienie) jest wolny dla kokpitu na MK32.
//                  Cena: lot, którego nikt nie oglądał, nie ma nagrania.
//   zawsze         ściąga strumień bez przerwy, więc nagranie jest kompletne.
//                  Cena: stałe obciążenie łącza radiowego i zajęty slot kamery
//                  — także wtedy, gdy nikt nie patrzy.
//
// Domyślnie `przy-widzach`, bo pasmo radiowe jest tu zasobem rzadszym niż miejsce
// na dysku. dok/SERWER_PODGLADU.md §5, dok/WDROZENIE_RPI.md §6.
export const TRYBY_WIDEO = ["nie", "przy-widzach", "zawsze"];

export const ARCHIWUM_DOMYSLNE = {
  wlaczone: true,
  katalog: "archiwum",
  wideo: "przy-widzach",
  trzymajDni: 30,
  limitGb: 50,
};

export function readArchiwum() {
  const a = readRaw().archiwum || {};
  return {
    wlaczone: a.wlaczone !== false,
    katalog: a.katalog || ARCHIWUM_DOMYSLNE.katalog,
    wideo: TRYBY_WIDEO.includes(a.wideo) ? a.wideo : ARCHIWUM_DOMYSLNE.wideo,
    trzymajDni: Number(a.trzymajDni) > 0 ? Number(a.trzymajDni) : ARCHIWUM_DOMYSLNE.trzymajDni,
    limitGb: Number(a.limitGb) > 0 ? Number(a.limitGb) : ARCHIWUM_DOMYSLNE.limitGb,
  };
}

/**
 * Katalog archiwum jako ścieżka bezwzględna. Jedno miejsce, w którym się ją liczy —
 * bo potrzebują jej dwa niezależne byty: nasz moduł archiwum (`.tlog`, sprzątanie)
 * i MediaMTX (`recordPath`). Gdyby liczyły ją osobno, prędzej czy później
 * rozjechałyby się i nagrania obrazu wylądowałyby poza zasięgiem sprzątania.
 *
 * ARCHIWUM_DIR ma pierwszeństwo — na RPi wskazuje dysk NVMe poza katalogiem projektu.
 */
export function katalogArchiwum(ustawienia) {
  const k = process.env.ARCHIWUM_DIR || ustawienia?.katalog || ARCHIWUM_DOMYSLNE.katalog;
  return isAbsolute(k) ? k : join(DATA_DIR, k);
}

export function validateId(id) {
  if (!id || typeof id !== "string") return "Brak 'id' źródła.";
  if (!ID_RE.test(id)) return `Niepoprawne 'id' "${id}" (dozwolone: litery, cyfry, _ -).`;
  if (id.endsWith("_pom")) return `'id' nie może kończyć się na "_pom" (zarezerwowane).`;
  return null;
}

export function normalizeZrodlo(z) {
  return {
    id: z.id,
    nazwa: z.nazwa || z.name || z.id,
    rtspGlowny: z.rtspGlowny || z.rtspMain || z.rtsp || "",
    rtspPomocniczy: z.rtspPomocniczy || z.rtspSub || "",
  };
}

export function validateZrodlo(z) {
  const idErr = validateId(z.id);
  if (idErr) return idErr;
  if (!z.rtspGlowny) return `Źródło "${z.id}": brak adresu 'rtspGlowny'.`;
  if (!/^rtsps?:\/\//i.test(z.rtspGlowny))
    return `Źródło "${z.id}": 'rtspGlowny' musi zaczynać się od rtsp://`;
  if (z.rtspPomocniczy && !/^rtsps?:\/\//i.test(z.rtspPomocniczy))
    return `Źródło "${z.id}": 'rtspPomocniczy' musi zaczynać się od rtsp://`;
  return null;
}

function readRaw() {
  if (!existsSync(ZRODLA_PATH)) return {};
  try {
    return JSON.parse(readFileSync(ZRODLA_PATH, "utf8"));
  } catch (e) {
    throw new Error(`Nie mogę odczytać ${ZRODLA_PATH}: ${e.message}`);
  }
}

export function readZrodla() {
  const raw = readRaw();
  const list = Array.isArray(raw.zrodla) ? raw.zrodla : [];
  return list.map(normalizeZrodlo);
}

export function readTelemetria() {
  const t = readRaw().telemetria || {};
  return {
    host: t.host || TELEMETRIA_DOMYSLNA.host,
    port: Number(t.port) || TELEMETRIA_DOMYSLNA.port,
  };
}

export function writeZrodla(zrodla, telemetria) {
  const seen = new Set();
  for (const z of zrodla) {
    const err = validateZrodlo(z);
    if (err) throw new Error(err);
    if (seen.has(z.id)) throw new Error(`Zduplikowane 'id' źródła: "${z.id}".`);
    seen.add(z.id);
  }
  const out = {
    zrodla: zrodla.map((z) => {
      const o = { id: z.id, nazwa: z.nazwa, rtspGlowny: z.rtspGlowny };
      if (z.rtspPomocniczy) o.rtspPomocniczy = z.rtspPomocniczy;
      return o;
    }),
    telemetria: telemetria || readTelemetria(),
    // Zapis źródeł nie może po cichu skasować ustawień archiwum — plik jest jeden
    // i każdy zapis przepisuje go w całości.
    archiwum: readArchiwum(),
  };
  writeFileSync(ZRODLA_PATH, JSON.stringify(out, null, 2) + "\n", "utf8");
}

/** Zmienia SAME ustawienia archiwum, zostawiając źródła i telemetrię nietknięte. */
export function writeArchiwum(zmiany = {}) {
  const nowe = { ...readArchiwum() };

  if (zmiany.wlaczone !== undefined) nowe.wlaczone = Boolean(zmiany.wlaczone);
  if (zmiany.katalog) nowe.katalog = String(zmiany.katalog);
  if (zmiany.wideo !== undefined) {
    if (!TRYBY_WIDEO.includes(zmiany.wideo)) {
      throw new Error(`Nieznany tryb nagrywania obrazu: "${zmiany.wideo}" (dozwolone: ${TRYBY_WIDEO.join(", ")}).`);
    }
    nowe.wideo = zmiany.wideo;
  }
  if (zmiany.trzymajDni !== undefined) {
    const d = Number(zmiany.trzymajDni);
    if (!(d > 0)) throw new Error("trzymajDni musi być liczbą większą od zera.");
    nowe.trzymajDni = d;
  }
  if (zmiany.limitGb !== undefined) {
    const g = Number(zmiany.limitGb);
    if (!(g > 0)) throw new Error("limitGb musi być liczbą większą od zera.");
    nowe.limitGb = g;
  }

  const raw = readRaw();
  const out = {
    zrodla: Array.isArray(raw.zrodla) ? raw.zrodla : [],
    telemetria: readTelemetria(),
    archiwum: nowe,
  };
  writeFileSync(ZRODLA_PATH, JSON.stringify(out, null, 2) + "\n", "utf8");
  return nowe;
}

// Konfiguracja pojedynczej ścieżki MediaMTX.
//
// sourceOnDemand jest tu ważniejszy niż w NRK: dopóki nikt nie ogląda, przez łącze
// radiowe nie idzie nic, a slot ZR30 (maks. 4 strumienie) zostaje wolny dla kokpitu
// na MK32. Patrz dok/SERWER_PODGLADU.md §5.
//
// Wyjątek robi tryb archiwum `zawsze`: nagranie kompletne wymaga strumienia bez
// przerwy, więc wtedy — i tylko wtedy — ścieżka wisi otwarta (TRYBY_WIDEO wyżej).
export function mtxPathConf(source, opcje = {}) {
  const conf = {
    source,
    sourceOnDemand: !opcje.ciagle,
    sourceOnDemandCloseAfter: "30s",
    rtspTransport: "tcp",
  };
  if (opcje.nagrywaj) {
    conf.record = true;
    // %path robi katalog na źródło, reszta to data i godzina startu segmentu.
    // Znaczniki są MediaMTX-owe (strftime + %f na mikrosekundy), nie nasze.
    // Ukośniki w jedną stronę: MediaMTX dostaje tę ścieżkę jako wzorzec tekstowy,
    // a mieszanka `C:\dane/wideo/...` z prób na Windows czyta się fatalnie w logach.
    const katalog = String(opcje.katalog).replace(/\\/g, "/").replace(/\/+$/, "");
    conf.recordPath = `${katalog}/wideo/%path/%Y-%m-%d_%H-%M-%S-%f`;
    // fMP4, nie MPEG-TS: przeżywa ucięcie pliku przy zaniku zasilania stacji,
    // a przy H.265 nie wymaga żadnego przepakowania w drugą stronę.
    conf.recordFormat = "fmp4";
    conf.recordSegmentDuration = opcje.segment || "10m";
    // Kasowanie po czasie zostawiamy MediaMTX, bo tylko on wie, kiedy zamknął
    // segment. Kasowanie po ZAJĘTOŚCI robi server/archiwum.mjs — MediaMTX nie
    // umie patrzeć na wolne miejsce, a na karcie w RPi to jest właśnie ten limit,
    // który kończy się pierwszy.
    conf.recordDeleteAfter = `${Math.round(opcje.trzymajDni * 24)}h`;
  }
  return conf;
}

/**
 * Ścieżki MediaMTX dla jednego źródła: główna i (jeśli jest) pomocnicza.
 *
 * NAGRYWAMY WYŁĄCZNIE STRUMIEŃ GŁÓWNY. Pomocniczy istnieje jako droga odwrotu dla
 * przeglądarek, które nie odtworzą H.265 (SERWER_PODGLADU.md §4) — zapisywanie obu
 * podwoiłoby zużycie dysku, żeby mieć drugą, gorszą kopię tego samego lotu.
 */
export function pathsForZrodlo(z, archiwum = null) {
  const nagrywaj = Boolean(archiwum && archiwum.wlaczone && archiwum.wideo !== "nie");
  const opcje = nagrywaj
    ? {
        nagrywaj: true,
        ciagle: archiwum.wideo === "zawsze",
        katalog: katalogArchiwum(archiwum),
        trzymajDni: archiwum.trzymajDni,
      }
    : {};
  const out = [{ name: z.id, conf: mtxPathConf(z.rtspGlowny, opcje) }];
  if (z.rtspPomocniczy) out.push({ name: `${z.id}_pom`, conf: mtxPathConf(z.rtspPomocniczy) });
  return out;
}

export function allPaths(zrodla, archiwum = null) {
  return zrodla.flatMap((z) => pathsForZrodlo(z, archiwum));
}

const yq = (s) => `'${String(s).replace(/'/g, "''")}'`;

// YAML MediaMTX chce `yes`/`no`, nie `true`/`false`. Liczby zostawiamy gołe,
// resztę cytujemy — w recordPath siedzą znaki `%`, które bez cudzysłowu
// potrafią zdziwić parser.
const yv = (v) => {
  if (typeof v === "boolean") return v ? "yes" : "no";
  if (typeof v === "number") return String(v);
  return yq(v);
};

export function generateMediamtxYml(zrodla, archiwum = readArchiwum()) {
  // Adres, pod którym MediaMTX pyta nas o zgodę. Musi wskazywać na TEN serwer.
  const authAddr = process.env.MTX_AUTH_ADDRESS
    || `http://127.0.0.1:${Number(process.env.PORT) || 8095}/api/mtx-auth`;
  // Wypisujemy dokładnie te klucze, które policzył mtxPathConf — żeby plik na dysku
  // i ścieżki dosyłane przez API MediaMTX (index.mjs) nie mogły się rozjechać.
  const pathsYml = allPaths(zrodla, archiwum)
    .map(({ name, conf }) =>
      [`  ${name}:`, ...Object.entries(conf).map(([k, v]) => `    ${k}: ${yv(v)}`)].join("\n")
    )
    .join("\n");

  const yml = `# PLIK GENEROWANY AUTOMATYCZNIE z zrodla.json — nie edytuj ręcznie.
# Serwer podglądu DRON15. Opis: dok/SERWER_PODGLADU.md
#
# MediaMTX wyłącznie PRZEPAKOWUJE strumienie (remux) — nie dekoduje i nie koduje.
# Dlatego koszt procesora jest bliski zeru i nie zależy od kodeka ani liczby widzów.
logLevel: info
logDestinations: [stdout]

# API kontrolne (status + dodawanie źródeł na żywo). TYLKO LOKALNIE — nie wystawiać.
api: yes
apiAddress: 127.0.0.1:9997

# WebRTC / WHEP — z tego korzysta strona. Endpoint: http://host:8889/<id>/whep
# webrtcLocalUDPAddress: jeden stały port mediów zamiast losowego zakresu.
# To upraszcza regułę w tunelu WireGuard do jednego wpisu (SERWER_PODGLADU.md §6.2).
webrtc: yes
webrtcAddress: :8889
webrtcLocalUDPAddress: :8189
webrtcAllowOrigins: ['*']

# Nieużywane protokoły wyłączone — kamera jest tylko ŹRÓDŁEM, nie publikuje do nas.
rtsp: no
rtmp: no
hls: no
srt: no
# MoQ (MediaMTX 1.19+) otwiera własny nasłuch i generuje sobie certyfikat.
# Nie korzystamy z niego — mniej otwartych portów, mniej plików w katalogu.
moq: no

# Uwierzytelnianie zewnętrzne: o każdym odtworzeniu decyduje NASZ serwer.
# Bez tego "odetnij widza" zabierałoby stronę i telemetrię, ale nie strumień,
# który ktoś już ogląda. dok/DOSTEP_I_UZYTKOWNICY.md §6.
# Przeglądarka podaje żeton jako HTTP Basic: użytkownik = id żetonu, hasło = sekret.
authMethod: http
authHTTPAddress: ${authAddr}
# API kontrolne obsługujemy sami, z 127.0.0.1 — nie może pytać samo siebie o zgodę.
authHTTPExclude:
- action: api
- action: metrics
- action: pprof

paths:
${pathsYml || "  # (brak źródeł)"}
`;
  mkdirSync(dirname(MEDIAMTX_YML_PATH), { recursive: true });
  writeFileSync(MEDIAMTX_YML_PATH, yml, "utf8");
  return MEDIAMTX_YML_PATH;
}

// Okrojona lista dla przeglądarki — BEZ adresów RTSP.
export function generateWebConfig(zrodla) {
  const webConfig = {
    zrodla: zrodla.map((z) => ({
      id: z.id,
      nazwa: z.nazwa,
      maPomocniczy: Boolean(z.rtspPomocniczy),
    })),
  };
  mkdirSync(dirname(WEB_CONFIG_PATH), { recursive: true });
  writeFileSync(WEB_CONFIG_PATH, JSON.stringify(webConfig, null, 2) + "\n", "utf8");
  return WEB_CONFIG_PATH;
}
