// Kto teraz ogląda. Rejestr obecności widzów.
//
// Sygnałem obecności jest OTWARTE POŁĄCZENIE SSE z telemetrią — to samo, które i tak
// każdy widz trzyma, żeby widzieć dane. Nie dokładamy odpytywania ani drugiego kanału:
// przeglądarka zamyka kartę, połączenie pada, obecność znika. Jedyne, co dochodzi,
// to lekki zapis "co teraz oglądam", bo tego z samego połączenia nie widać.
import { randomBytes } from "node:crypto";

const polaczenia = new Map(); // idPolaczenia -> wpis

export function dolacz({ zetonId, imie, rola, ip, zrodlo = null }) {
  const id = randomBytes(6).toString("hex");
  polaczenia.set(id, {
    id,
    zetonId,
    imie,
    rola,
    ip,
    zrodlo,
    od: Date.now(),
  });
  return id;
}

export function odlacz(id) {
  polaczenia.delete(id);
}

export function ustawZrodlo(id, zrodlo) {
  const w = polaczenia.get(id);
  if (!w) return false;
  w.zrodlo = zrodlo || null;
  return true;
}

// Lista dla widza: imię, rola, od kiedy, co ogląda. Bez adresów IP — te są
// wyłącznie dla administratora (dok/DOSTEP_I_UZYTKOWNICY.md §4).
export function lista() {
  const t = Date.now();
  return [...polaczenia.values()]
    .sort((a, b) => a.od - b.od)
    .map((w) => ({
      id: w.id,
      zetonId: w.zetonId,
      imie: w.imie,
      rola: w.rola,
      zrodlo: w.zrodlo,
      sekund: Math.round((t - w.od) / 1000),
    }));
}

// To samo z adresami — tylko dla administratora.
export function listaPelna() {
  const t = Date.now();
  return [...polaczenia.values()]
    .sort((a, b) => a.od - b.od)
    .map((w) => ({ ...w, sekund: Math.round((t - w.od) / 1000) }));
}

// Ilu widzów liczy się do limitu. Administrator nie liczy się do limitu —
// inaczej pełna sala odcinałaby od stacji osobę, która ma nią zarządzać.
export function liczbaWidzow() {
  return [...polaczenia.values()].filter((w) => w.rola !== "admin").length;
}

// Ile połączeń trzyma dany żeton. Używane przy odcinaniu, żeby wiedzieć,
// czy jest kogo odcinać.
export function polaczeniaZetonu(zetonId) {
  return [...polaczenia.values()].filter((w) => w.zetonId === zetonId);
}

// Zamknięcie połączeń danego żetonu wykonuje index.mjs (to on trzyma uchwyt res).
// Tu rejestrujemy wywołania zwrotne, żeby nie plątać warstw.
const zamykacze = new Map(); // idPolaczenia -> funkcja

export function zarejestrujZamykacz(id, fn) {
  zamykacze.set(id, fn);
}

export function zamknijZeton(zetonId) {
  let ile = 0;
  for (const w of polaczeniaZetonu(zetonId)) {
    const fn = zamykacze.get(w.id);
    if (fn) {
      try {
        fn();
        ile += 1;
      } catch {
        /* połączenie i tak już padło */
      }
    }
    zamykacze.delete(w.id);
    polaczenia.delete(w.id);
  }
  return ile;
}

export function zamknijWszystkich({ pomijajAdmina = true } = {}) {
  let ile = 0;
  for (const w of [...polaczenia.values()]) {
    if (pomijajAdmina && w.rola === "admin") continue;
    const fn = zamykacze.get(w.id);
    if (fn) {
      try {
        fn();
        ile += 1;
      } catch {
        /* już padło */
      }
    }
    zamykacze.delete(w.id);
    polaczenia.delete(w.id);
  }
  return ile;
}

export function usunZamykacz(id) {
  zamykacze.delete(id);
}
