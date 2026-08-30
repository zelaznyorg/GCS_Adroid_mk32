// Trasy do pokazania na mapie — wczytywane z plików na stacji.
//
// ### Dlaczego z plików, a nie z maszyny
//
// Pobranie trasy z kontrolera lotu wymaga **wysłania** do niego zapytania
// (`MISSION_REQUEST_LIST`). Stacja tego nie robi i nie będzie robić: nie wysyła
// do maszyny niczego (dok/WLADZA.md), a rozgałęźnik w aparaturze jest
// jednokierunkowy z założenia (CLAUDE.md poz. 57).
//
// Zostają więc dwie drogi, obie bierne, i obie są zaimplementowane:
//
//   1. **plik** — `.plan` z QGroundControl albo `.waypoints` (WPL 110), położony
//      w `DATA_DIR/trasy`. To jest droga zaplanowana w GCS_RPI5.md: „trasy przenosi
//      się plikiem .plan". Działa zawsze, także bez drona pod napięciem.
//   2. **podsłuch** — gdy kokpit na MK32 pobiera albo wysyła trasę, jej punkty
//      przechodzą przez rozgałęźnik i widzimy je po drodze (server/telemetria.mjs).
//      Nic nie kosztuje, ale pojawia się dopiero przy transferze.
//
// Pierwsza droga jest przewidywalna, druga samoczynna. Mapa pokazuje obie i mówi,
// skąd wzięła to, co rysuje — bo „trasa na ekranie" bez wskazania źródła to
// najlepszy sposób, żeby polecieć według nieaktualnego planu.
import { readdir, readFile, mkdir } from "node:fs/promises";
import { join, extname, basename } from "node:path";
import { DATA_DIR } from "../scripts/zrodla-lib.mjs";
import { PUNKTY_TRASY } from "./mavlink.mjs";
import * as rejestr from "./rejestr.mjs";

export const KATALOG_TRAS = process.env.TRASY_DIR || join(DATA_DIR, "trasy");

/** Nazwa pliku z żądania NIGDY nie trafia do ścieżki wprost — patrz `wczytaj`. */
const NAZWA_OK = /^[A-Za-z0-9 _.-]+$/;

export async function zapewnijKatalog() {
  try {
    await mkdir(KATALOG_TRAS, { recursive: true });
  } catch (e) {
    rejestr.ostrzezenie("trasy", `nie mogę utworzyć ${KATALOG_TRAS}`, { blad: e.message });
  }
}

/**
 * Plan z QGroundControl (`.plan`) — JSON.
 *
 * Współrzędne siedzą w `params[4]` i `params[5]`, a wysokość w `params[6]`.
 * To nie jest kaprys formatu: `params` to dokładnie siedem pól polecenia MAV_CMD,
 * a dla poleceń nawigacyjnych piąte i szóste to szerokość i długość.
 */
function zPlanu(tekst, nazwa) {
  const d = JSON.parse(tekst);
  const m = d.mission || {};
  const punkty = [];
  for (const it of m.items || []) {
    // Elementy złożone (`ComplexItem` — siatki, przeloty) mają własne punkty
    // w `TransectStyleComplexItem`. Nie rozwijamy ich: pokazalibyśmy trasę
    // inaczej, niż policzy ją maszyna, a to gorsze niż nie pokazać wcale.
    if (it.type && it.type !== "SimpleItem") continue;
    const p = it.params || [];
    const lat = Number(p[4]);
    const lon = Number(p[5]);
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || (lat === 0 && lon === 0)) continue;
    punkty.push({
      seq: punkty.length,
      command: Number(it.command),
      lat,
      lon,
      wysokosc_m: Number(p[6]) || Number(it.Altitude) || 0,
      frame: Number(it.frame) ?? null,
      nawigacyjny: PUNKTY_TRASY.has(Number(it.command)),
    });
  }
  const dom = Array.isArray(m.plannedHomePosition) ? m.plannedHomePosition : null;
  return {
    nazwa,
    zrodlo: "plik .plan",
    punkty,
    domPlanowany: dom ? { lat: dom[0], lon: dom[1], wysokosc_m: dom[2] ?? null } : null,
  };
}

/**
 * Format tekstowy WPL 110 — ten, który zapisuje Mission Planner.
 * Kolumny rozdzielone tabulatorem: seq, current, frame, command, p1..p4, x, y, z, autocontinue.
 */
function zWaypoints(tekst, nazwa) {
  const punkty = [];
  let domPlanowany = null;
  for (const linia of tekst.split(/\r?\n/)) {
    if (!linia.trim() || linia.startsWith("QGC")) continue;
    const k = linia.split(/\t|\s{2,}/).map((x) => x.trim());
    if (k.length < 12) continue;
    const seq = Number(k[0]);
    const command = Number(k[3]);
    const lat = Number(k[8]);
    const lon = Number(k[9]);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
    // Wiersz zerowy w WPL to pozycja domu, nie punkt trasy.
    if (seq === 0) {
      if (lat !== 0 || lon !== 0) domPlanowany = { lat, lon, wysokosc_m: Number(k[10]) || null };
      continue;
    }
    if (lat === 0 && lon === 0) continue;
    punkty.push({
      seq,
      command,
      lat,
      lon,
      wysokosc_m: Number(k[10]) || 0,
      frame: Number(k[2]),
      nawigacyjny: PUNKTY_TRASY.has(command),
    });
  }
  return { nazwa, zrodlo: "plik .waypoints", punkty, domPlanowany };
}

export async function lista() {
  await zapewnijKatalog();
  let pliki;
  try {
    pliki = await readdir(KATALOG_TRAS);
  } catch {
    return [];
  }
  const out = [];
  for (const nazwa of pliki) {
    const r = extname(nazwa).toLowerCase();
    if (r !== ".plan" && r !== ".waypoints" && r !== ".txt") continue;
    try {
      const t = await wczytaj(nazwa);
      out.push({ nazwa, zrodlo: t.zrodlo, punktow: t.punkty.length });
    } catch (e) {
      // Uszkodzony plik nie może zabrać listy pozostałym — melduje się i wypada.
      out.push({ nazwa, zrodlo: "nieczytelny", punktow: 0, blad: String(e.message || e) });
      rejestr.ostrzezenie("trasy", `nie mogę odczytać ${nazwa}`, { blad: e.message });
    }
  }
  return out;
}

/**
 * Wczytanie jednej trasy.
 *
 * ⛔ Nazwa pochodzi z żądania HTTP, więc jest sprawdzana wobec wzorca i pozbawiana
 * ścieżki przez `basename` — bez tego `../../etc/passwd` czytałoby, co chciało.
 */
export async function wczytaj(nazwa) {
  const czysta = basename(String(nazwa || ""));
  if (!czysta || !NAZWA_OK.test(czysta)) throw new Error(`Niedozwolona nazwa trasy: "${nazwa}".`);
  const sciezka = join(KATALOG_TRAS, czysta);
  const tekst = await readFile(sciezka, "utf8");
  const r = extname(czysta).toLowerCase();
  if (r === ".plan") return zPlanu(tekst, czysta);
  if (r === ".waypoints" || r === ".txt") return zWaypoints(tekst, czysta);
  throw new Error(`Nieznany format trasy: "${czysta}".`);
}
