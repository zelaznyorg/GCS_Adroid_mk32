// Rejestr techniczny serwera — jedno miejsce, do którego trafia wszystko, co poszło nie tak.
// Opis i konwencje: dok/LOGI_I_BLEDY.md
//
// UWAGA NA DWA RÓŻNE DZIENNIKI. Łatwo je pomylić, więc na wstępie:
//
//   dostep.mjs → dziennik    KTO CO ZROBIŁ    dla administratora, widoczny w panelu,
//                                             trwały, po polsku, bez szczegółów technicznych
//   rejestr.mjs (ten plik)   CO SIĘ ZEPSUŁO   dla nas, do debugowania, ze stosem wywołań,
//                                             rotowany, kasowalny bez straty
//
// Zasady:
//   - nic w tym module nie może wywrócić serwera. Zapis loga, który psuje działanie
//     programu, jest gorszy niż brak loga.
//   - każdy wpis ma OBSZAR (telemetria, mediamtx, api, ...), żeby dało się filtrować.
//   - ostatnie wpisy trzymamy też w pamięci, żeby panel admina pokazał je bez czytania pliku.
import { appendFileSync, mkdirSync, statSync, renameSync, existsSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "../scripts/zrodla-lib.mjs";

const KATALOG = process.env.LOGI_DIR || join(ROOT, "logi");
const PLIK = join(KATALOG, "serwer.log");
const MAX_BAJTOW = Number(process.env.LOGI_MAX_BAJTOW) || 5 * 1024 * 1024;
const ILE_ARCHIWUM = Number(process.env.LOGI_ARCHIWUM) || 3;
const W_PAMIECI = Number(process.env.LOGI_W_PAMIECI) || 400;

export const POZIOMY = { blad: 0, ostrzezenie: 1, info: 2, szczegol: 3 };
const NAZWY = ["BLAD", "OSTRZ", "INFO", "SZCZEG"];

// Domyślnie bez szczegółów — inaczej strumień telemetrii zasypałby plik.
// Podniesienie: POZIOM=szczegol sh start.sh
const PROG = POZIOMY[process.env.POZIOM] ?? POZIOMY.info;

const ostatnie = [];
let zapisDziala = true;

function katalog() {
  try {
    mkdirSync(KATALOG, { recursive: true });
    return true;
  } catch {
    return false;
  }
}

// Rotacja przy przekroczeniu rozmiaru: serwer.log → .1 → .2 → .3 → kosz.
// Bez tego karta w RPi zapełnia się po tygodniu ciszy w logach debugowych.
function obrocJesliTrzeba() {
  try {
    if (!existsSync(PLIK)) return;
    if (statSync(PLIK).size < MAX_BAJTOW) return;
    const najstarszy = `${PLIK}.${ILE_ARCHIWUM}`;
    if (existsSync(najstarszy)) unlinkSync(najstarszy);
    for (let i = ILE_ARCHIWUM - 1; i >= 1; i--) {
      const z = `${PLIK}.${i}`;
      if (existsSync(z)) renameSync(z, `${PLIK}.${i + 1}`);
    }
    renameSync(PLIK, `${PLIK}.1`);
  } catch {
    /* rotacja to wygoda, nie warunek działania */
  }
}

function sformatuj(poziom, obszar, wiadomosc, kontekst) {
  const czas = new Date().toISOString();
  const nazwa = NAZWY[poziom].padEnd(6);
  const gdzie = String(obszar || "-").padEnd(12);
  let linia = `${czas} ${nazwa} [${gdzie}] ${wiadomosc}`;
  if (kontekst && Object.keys(kontekst).length) {
    // Kontekst w jednej linii — plik ma się dać czytać przez grep, nie przez parser.
    try {
      linia += ` | ${JSON.stringify(kontekst)}`;
    } catch {
      linia += " | (kontekst nieserializowalny)";
    }
  }
  return linia;
}

function pisz(poziom, obszar, wiadomosc, kontekst) {
  if (poziom > PROG) return;
  const linia = sformatuj(poziom, obszar, wiadomosc, kontekst);

  ostatnie.push({
    czas: Date.now(),
    poziomNr: poziom,
    poziom: NAZWY[poziom].trim(),
    obszar,
    wiadomosc,
    kontekst: kontekst || null,
  });
  if (ostatnie.length > W_PAMIECI) ostatnie.splice(0, ostatnie.length - W_PAMIECI);

  // Konsola zawsze — przy uruchomieniu z ręki to jedyne, co widać od razu.
  if (poziom === POZIOMY.blad) console.error(linia);
  else if (poziom === POZIOMY.ostrzezenie) console.warn(linia);
  else console.log(linia);

  if (!zapisDziala) return;
  try {
    if (!katalog()) throw new Error("brak katalogu logów");
    obrocJesliTrzeba();
    appendFileSync(PLIK, linia + "\n", "utf8");
  } catch (e) {
    // Mówimy o tym RAZ i przestajemy próbować. Log, który przy każdym wpisie
    // wypisuje własny błąd, jest gorszy od braku loga.
    zapisDziala = false;
    console.error(`[rejestr] zapis do ${PLIK} niemożliwy (${e.message}) — dalej tylko konsola`);
  }
}

// Stos wywołań jest tym, po co się do loga sięga. Skracamy go, ale nie obcinamy do jednej linii.
export function opisBledu(e) {
  if (!e) return { blad: "(brak)" };
  if (e instanceof Error) {
    return {
      blad: e.message,
      typ: e.name,
      stos: String(e.stack || "").split("\n").slice(0, 8).join(" ⏎ "),
      ...(e.code ? { kod: e.code } : {}),
    };
  }
  return { blad: String(e) };
}

export const blad = (obszar, wiadomosc, kontekst) => pisz(POZIOMY.blad, obszar, wiadomosc, kontekst);
export const ostrzezenie = (obszar, wiadomosc, kontekst) => pisz(POZIOMY.ostrzezenie, obszar, wiadomosc, kontekst);
export const info = (obszar, wiadomosc, kontekst) => pisz(POZIOMY.info, obszar, wiadomosc, kontekst);
export const szczegol = (obszar, wiadomosc, kontekst) => pisz(POZIOMY.szczegol, obszar, wiadomosc, kontekst);

// Wyjątek zapisujemy zawsze z tym samym rozkładem pól, żeby grep po "BLAD" wystarczał.
export function wyjatek(obszar, wiadomosc, e, kontekst = {}) {
  pisz(POZIOMY.blad, obszar, wiadomosc, { ...opisBledu(e), ...kontekst });
}

// `doPoziomu` zawęża do wpisów co najmniej tak ważnych: "blad" da same błędy,
// "ostrzezenie" błędy i ostrzeżenia, i tak dalej.
export function ostatnieWpisy(ile = 200, doPoziomu = null) {
  const prog = doPoziomu != null ? POZIOMY[doPoziomu] ?? POZIOMY.szczegol : POZIOMY.szczegol;
  return ostatnie.filter((w) => w.poziomNr <= prog).slice(-ile).reverse();
}

export function sciezkaPliku() {
  return PLIK;
}

// ---- łapanie tego, czego nikt nie złapał ----
//
// Rzecz do zrozumienia przed zmianą tego kodu: po `uncaughtException` proces jest
// w stanie NIEOKREŚLONYM. Node wykonał połowę czegoś i przerwał. Dalsza praca oznacza
// serwer, który udaje, że działa — a przy podglądzie z drona „udaje" jest gorsze
// od „nie działa", bo nikt nie zauważy. Dlatego: zapisz, posprzątaj, zejdź z pola
// i daj się podnieść z zewnątrz (start.sh --pilnuj albo systemd).
//
// Inaczej z `unhandledRejection`: tam zwykle chodzi o jedno nieobsłużone `await`,
// reszta programu jest zdrowa. Zapisujemy i pracujemy dalej.
export function zainstalujPulapki({ przedWyjsciem = null } = {}) {
  process.on("uncaughtException", (e) => {
    wyjatek("krytyczny", "nieprzechwycony wyjątek — kończę pracę", e);
    try {
      przedWyjsciem?.();
    } catch (e2) {
      wyjatek("krytyczny", "sprzątanie też się nie udało", e2);
    }
    process.exit(1);
  });

  process.on("unhandledRejection", (powod) => {
    wyjatek("obietnica", "nieobsłużone odrzucenie obietnicy — pracuję dalej", powod);
  });

  process.on("warning", (w) => {
    ostrzezenie("node", w.message, { typ: w.name });
  });

  for (const sygnal of ["SIGINT", "SIGTERM"]) {
    process.on(sygnal, () => {
      info("start", `${sygnal} — zamykam`);
      try {
        przedWyjsciem?.();
      } catch {
        /* zamykamy się i tak */
      }
      process.exit(0);
    });
  }
}
