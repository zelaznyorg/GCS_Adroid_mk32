// Hasło nadawania — dla źródeł, które SAME wypychają obraz do stacji.
//
// ### Dlaczego to jest osobne od żetonów widzów
//
// Reszta systemu działa w jedną stronę: stacja **pobiera** obraz z kamery
// (`rtsp://…`), a widz go tylko ogląda. Dlatego `/api/mtx-auth` odrzuca każdą
// próbę publikowania — i ma tak zostać.
//
// Drony DJI działają odwrotnie: obraz wypycha aparatura (DJI Fly / Pilot 2)
// strumieniem RTMP. Żeby to wpuścić, potrzebna jest furtka — ale **wąska**:
//
//   * osobny sekret, nie żeton widza. Widz nie może zacząć nadawać, a wykradziony
//     adres RTMP nie daje wglądu w nic innego;
//   * wyłącznie ścieżki z listy (`SCIEZKI_NADAWANIA`), nie dowolna nazwa. Bez tego
//     ktoś podstawiłby własny obraz pod ścieżkę kamery pokładowej;
//   * sekret leży w katalogu danych, nie w kodzie, i da się go wymienić.
//
// ⚠ Adres RTMP niesie sekret jawnie — taki jest protokół. Traktować go jak hasło:
// wpisany do aparatury zostaje w niej, więc po utracie sprzętu **wymienić**.
import { randomBytes } from "node:crypto";
import { existsSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";

const DATA_DIR = process.env.DATA_DIR || ".";
const PLIK = join(DATA_DIR, "nadawanie.txt");

/** Ścieżki, do których wolno nadawać. Nazwa mówi, co to jest — i nic więcej nie wolno. */
export const SCIEZKI_NADAWANIA = ["dji", "dji2"];

let sekret = null;

export function haslo() {
  if (sekret) return sekret;
  try {
    if (existsSync(PLIK)) {
      sekret = readFileSync(PLIK, "utf8").trim();
      if (sekret) return sekret;
    }
    sekret = randomBytes(12).toString("hex");
    mkdirSync(dirname(PLIK), { recursive: true });
    writeFileSync(PLIK, sekret + "\n", { mode: 0o600 });
  } catch {
    // Bez zapisu na dysk hasło żyje do restartu — lepsze to niż brak nadawania.
    sekret = sekret || randomBytes(12).toString("hex");
  }
  return sekret;
}

export function nowHaslo() {
  sekret = randomBytes(12).toString("hex");
  try {
    mkdirSync(dirname(PLIK), { recursive: true });
    writeFileSync(PLIK, sekret + "\n", { mode: 0o600 });
  } catch { /* zostaje w pamięci */ }
  return sekret;
}

/** Czy wolno publikować pod tę ścieżkę tym hasłem. */
export function wolnoNadawac(sciezka, podaneHaslo) {
  if (!SCIEZKI_NADAWANIA.includes(String(sciezka || ""))) return false;
  const oczekiwane = haslo();
  const a = Buffer.from(String(podaneHaslo || ""));
  const b = Buffer.from(oczekiwane);
  return a.length === b.length && a.equals(b);
}
