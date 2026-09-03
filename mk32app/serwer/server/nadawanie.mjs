// Hasła nadawania — dla źródeł, które SAME wypychają obraz do stacji (drony DJI).
//
// ### Dlaczego to jest osobne od żetonów widzów
//
// Reszta systemu działa w jedną stronę: stacja **pobiera** obraz z kamery
// (`rtsp://…`), a widz go tylko ogląda. Dlatego `/api/mtx-auth` odrzuca każdą
// próbę publikowania — i ma tak zostać.
//
// Drony DJI działają odwrotnie: obraz wypycha aparatura (DJI Fly / Pilot 2 albo
// nasz APK) strumieniem RTMP / TCP. Żeby to wpuścić, potrzebna jest furtka —
// ale **wąska**:
//
//   * osobny sekret, nie żeton widza. Widz nie może zacząć nadawać, a wykradziony
//     adres RTMP nie daje wglądu w nic innego;
//   * wyłącznie ścieżki źródeł NADAWANYCH z `zrodla.json`, nie dowolna nazwa. Bez
//     tego ktoś podstawiłby własny obraz pod ścieżkę kamery pokładowej;
//   * sekret leży w katalogu danych, nie w kodzie, i da się go wymienić.
//
// ### Od 2026-09-03: hasło NA ŹRÓDŁO, nie jedno na stację
//
// Panorama pokazuje wiele dronów naraz. Przy jednym wspólnym haśle każda aparatura
// mogłaby nadawać pod ścieżkę każdej innej — a odbiornik zrzutu ekranu nie miałby
// jak poznać, **który** dron do niego mówi. Teraz hasło identyfikuje źródło:
// aparatura zna tylko swoje, a stacja po haśle wie, pod którą ścieżkę wpuścić.
//
// Hasła leżą w `nadawanie.json` (`{ "<id źródła>": "<hasło>" }`). Stare wspólne
// `nadawanie.txt` zostaje jako **klucz stacji** dla Cloud API DJI (`dji.html`,
// broker MQTT) — to inna rzecz: nie nadawanie obrazu, tylko konfiguracja telemetrii.
// Przy pierwszym uruchomieniu po zmianie każde istniejące źródło nadawane dostaje
// właśnie ten stary klucz, żeby wpisane już w aparaturę adresy nie przestały działać.
//
// ⚠ Adres RTMP niesie sekret jawnie — taki jest protokół. Traktować go jak hasło:
// wpisany do aparatury zostaje w niej, więc po utracie sprzętu **wymienić**.
import { randomBytes, timingSafeEqual } from "node:crypto";
import { existsSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { readZrodla } from "../scripts/zrodla-lib.mjs";

const DATA_DIR = process.env.DATA_DIR || ".";
const PLIK_KLUCZA = join(DATA_DIR, "nadawanie.txt");
const PLIK_HASEL = join(DATA_DIR, "nadawanie.json");

/** Nazwa użytkownika w adresie RTMP — stała; tożsamość niesie hasło. */
export const UZYTKOWNIK_RTMP = "dji";

let klucz = null;
let hasla = null; // Map id -> hasło

function nowySekret() {
  return randomBytes(12).toString("hex");
}

function zapiszPlik(sciezka, tresc) {
  try {
    mkdirSync(dirname(sciezka), { recursive: true });
    writeFileSync(sciezka, tresc, { mode: 0o600 });
    return true;
  } catch {
    // Bez zapisu na dysk sekret żyje do restartu — lepsze to niż brak nadawania.
    return false;
  }
}

function rowne(a, b) {
  const x = Buffer.from(String(a || ""));
  const y = Buffer.from(String(b || ""));
  return x.length === y.length && x.length > 0 && timingSafeEqual(x, y);
}

// ---- klucz stacji (Cloud API DJI) ------------------------------------------------

/** Klucz stacji: pilnuje `/api/dji/konfiguracja` i logowania do brokera MQTT. */
export function haslo() {
  if (klucz) return klucz;
  try {
    if (existsSync(PLIK_KLUCZA)) {
      klucz = readFileSync(PLIK_KLUCZA, "utf8").trim();
      if (klucz) return klucz;
    }
  } catch { /* niżej: nowy */ }
  klucz = nowySekret();
  zapiszPlik(PLIK_KLUCZA, klucz + "\n");
  return klucz;
}

export function nowHaslo() {
  klucz = nowySekret();
  zapiszPlik(PLIK_KLUCZA, klucz + "\n");
  return klucz;
}

// ---- hasła źródeł nadawanych --------------------------------------------------------

function wczytajHasla() {
  if (hasla) return hasla;
  hasla = new Map();
  try {
    if (existsSync(PLIK_HASEL)) {
      const d = JSON.parse(readFileSync(PLIK_HASEL, "utf8"));
      for (const [id, h] of Object.entries(d || {})) if (typeof h === "string" && h) hasla.set(id, h);
    }
  } catch { /* uszkodzony plik nie może zabrać nadawania — zaczynamy od pustej listy */ }
  return hasla;
}

function zapiszHasla() {
  const obiekt = Object.fromEntries([...wczytajHasla().entries()].sort());
  zapiszPlik(PLIK_HASEL, JSON.stringify(obiekt, null, 2) + "\n");
}

/** Źródła nadawane z konfiguracji — jedyne, pod które wolno publikować. */
function zrodlaNadawane() {
  try {
    return readZrodla().filter((z) => z.nadawany || !z.rtspGlowny);
  } catch {
    return [];
  }
}

/**
 * Hasło źródła nadawanego; zakładane przy pierwszym pytaniu.
 *
 * Źródło, które istniało przed wprowadzeniem haseł na źródło, dostaje stary klucz
 * stacji — żeby adres wpisany już w aparaturę dalej działał. Nowe źródła dostają
 * własny, losowy sekret.
 */
export function hasloZrodla(id) {
  const h = wczytajHasla();
  if (h.has(id)) return h.get(id);
  const istniejeStaryKlucz = existsSync(PLIK_KLUCZA);
  const nowe = istniejeStaryKlucz && h.size === 0 ? haslo() : nowySekret();
  h.set(id, nowe);
  zapiszHasla();
  return nowe;
}

export function noweHasloZrodla(id) {
  const h = wczytajHasla();
  h.set(id, nowySekret());
  zapiszHasla();
  return h.get(id);
}

export function usunHasloZrodla(id) {
  const h = wczytajHasla();
  if (h.delete(id)) zapiszHasla();
}

/** Czy wolno publikować pod tę ścieżkę tym hasłem. Ścieżka = id źródła nadawanego. */
export function wolnoNadawac(sciezka, podaneHaslo) {
  const id = String(sciezka || "");
  if (!zrodlaNadawane().some((z) => z.id === id)) return false;
  return rowne(podaneHaslo, hasloZrodla(id));
}

/**
 * Które źródło nadawane ma to hasło — dla odbiornika zrzutu ekranu, gdzie
 * aparatura nie podaje ścieżki, tylko hasło. `null`, gdy żadne.
 */
export function zrodloPoHasle(podaneHaslo) {
  for (const z of zrodlaNadawane()) {
    if (rowne(podaneHaslo, hasloZrodla(z.id))) return { id: z.id, haslo: hasloZrodla(z.id) };
  }
  return null;
}

/** Adres RTMP do wpisania w aparaturę (Pilot 2 / DJI Fly) dla danego źródła. */
export function adresRtmp(gospodarz, id) {
  return `rtmp://${gospodarz}:1935/${id}?user=${UZYTKOWNIK_RTMP}&pass=${hasloZrodla(id)}`;
}
