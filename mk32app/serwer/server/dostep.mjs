// Dostęp: zaproszenia, żetony, ustawienia i dziennik zdarzeń.
// Projekt i uzasadnienia: dok/DOSTEP_I_UZYTKOWNICY.md
//
// Zasada, o której łatwo zapomnieć przy czytaniu tego pliku: to NIE jest bariera
// bezpieczeństwa. Barierą jest WireGuard (dok/SERWER_PODGLADU.md §9). Ta warstwa daje
// TOŻSAMOŚĆ — imię przy widzu, możliwość odcięcia jednej osoby, dziennik. Bez tunelu
// nie wystawiamy niczego, także z logowaniem.
//
// Stan trzymamy w jednym pliku JSON. Przy pięciu osobach baza danych byłaby pracą
// bez pokrycia, a plik da się obejrzeć i poprawić edytorem w terenie.
import { randomBytes, timingSafeEqual } from "node:crypto";
import { readFileSync, writeFileSync, existsSync, renameSync } from "node:fs";
import { join } from "node:path";
import { DATA_DIR } from "../scripts/zrodla-lib.mjs";
import * as rejestr from "./rejestr.mjs";

const PLIK = join(DATA_DIR, "dostep.json");
const ROLE = ["widz", "operator", "admin"];
const DZIENNIK_MAX = 500;

const USTAWIENIA_DOMYSLNE = {
  limitWidzow: 6,       // każdy widz to osobny strumień — pasmo jest skończone
  cisza: false,         // jeden przełącznik odcinający wszystkich naraz
  zrodloDomyslne: null, // admin narzuca domyślne, widz może zmienić (decyzja 7)
};

const teraz = () => Date.now();
const losowy = (n) => randomBytes(n).toString("hex");

function pusty() {
  return { zaproszenia: [], zetony: [], ustawienia: { ...USTAWIENIA_DOMYSLNE }, dziennik: [] };
}

let stan = null;

function wczytaj() {
  if (stan) return stan;
  if (existsSync(PLIK)) {
    try {
      const s = JSON.parse(readFileSync(PLIK, "utf8"));
      stan = {
        zaproszenia: s.zaproszenia || [],
        zetony: s.zetony || [],
        ustawienia: { ...USTAWIENIA_DOMYSLNE, ...(s.ustawienia || {}) },
        dziennik: s.dziennik || [],
      };
    } catch (e) {
      // Uszkodzony plik nie może zatrzymać serwera podglądu — ważniejsze, żeby obraz
      // był na ekranie. Zaczynamy od pustego i mówimy o tym głośno.
      rejestr.wyjatek("dostep", `${PLIK} nieczytelny — zaczynam od pustego`, e);
      stan = pusty();
    }
  } else {
    stan = pusty();
  }
  return stan;
}

function zapisz() {
  const s = wczytaj();
  s.dziennik = s.dziennik.slice(-DZIENNIK_MAX);
  // Zapis przez plik tymczasowy: przerwane zasilanie RPi nie zostawi obciętego JSON-a.
  const tmp = `${PLIK}.tmp`;
  writeFileSync(tmp, JSON.stringify(s, null, 2) + "\n", "utf8");
  renameSync(tmp, PLIK);
}

// ---- dziennik ----

export function zapiszZdarzenie(rodzaj, opis, szczegoly = {}) {
  const s = wczytaj();
  s.dziennik.push({ czas: teraz(), rodzaj, opis, ...szczegoly });
  zapisz();
}

export function dziennik(ile = 100) {
  return wczytaj().dziennik.slice(-ile).reverse();
}

// ---- ustawienia ----

export function ustawienia() {
  return { ...wczytaj().ustawienia };
}

export function ustaw(zmiany) {
  const s = wczytaj();
  if (zmiany.limitWidzow != null) {
    const n = Number(zmiany.limitWidzow);
    if (!Number.isFinite(n) || n < 1 || n > 50) throw new Error("limitWidzow poza zakresem 1–50");
    s.ustawienia.limitWidzow = Math.round(n);
  }
  if (zmiany.cisza != null) s.ustawienia.cisza = Boolean(zmiany.cisza);
  if ("zrodloDomyslne" in zmiany) s.ustawienia.zrodloDomyslne = zmiany.zrodloDomyslne || null;
  zapisz();
  zapiszZdarzenie("ustawienia", "zmiana ustawień", { zmiany });
  return ustawienia();
}

// ---- zaproszenia ----

// Zaproszenie to kod do wymienienia na żeton. Dzięki temu link krążący po
// komunikatorach przestaje działać, gdy tylko ktoś go użyje (jeśli jednorazowy)
// albo gdy minie termin.
export function utworzZaproszenie({ imie, rola = "widz", waznoscMin = null, jednorazowe = true }) {
  if (!imie || !String(imie).trim()) throw new Error("Zaproszenie musi mieć imię.");
  if (!ROLE.includes(rola)) throw new Error(`Nieznana rola "${rola}".`);
  const s = wczytaj();
  const z = {
    id: losowy(4),
    kod: losowy(12),
    imie: String(imie).trim().slice(0, 40),
    rola,
    jednorazowe: Boolean(jednorazowe),
    wygasa: waznoscMin ? teraz() + Number(waznoscMin) * 60000 : null,
    utworzono: teraz(),
    uzyte: 0,
    uniewaznione: false,
  };
  s.zaproszenia.push(z);
  zapisz();
  zapiszZdarzenie("zaproszenie", `wydano zaproszenie dla ${z.imie}`, { imie: z.imie, rola: z.rola });
  return z;
}

export function zaproszenia() {
  const t = teraz();
  return wczytaj().zaproszenia.map((z) => ({
    id: z.id,
    imie: z.imie,
    rola: z.rola,
    jednorazowe: z.jednorazowe,
    wygasa: z.wygasa,
    utworzono: z.utworzono,
    uzyte: z.uzyte,
    uniewaznione: z.uniewaznione,
    wazne: !z.uniewaznione && (!z.wygasa || z.wygasa > t) && !(z.jednorazowe && z.uzyte > 0),
  }));
}

export function kodZaproszenia(id) {
  const z = wczytaj().zaproszenia.find((x) => x.id === id);
  return z ? z.kod : null;
}

export function uniewaznijZaproszenie(id) {
  const s = wczytaj();
  const z = s.zaproszenia.find((x) => x.id === id);
  if (!z) throw new Error("Nie ma takiego zaproszenia.");
  z.uniewaznione = true;
  zapisz();
  zapiszZdarzenie("zaproszenie", `unieważniono zaproszenie dla ${z.imie}`, { imie: z.imie });
  return true;
}

// Wymiana kodu na żeton. Zwraca pełny żeton "id.sekret" — jedyny moment,
// w którym sekret opuszcza serwer.
export function uzyjZaproszenia(kod, ip) {
  const s = wczytaj();
  const t = teraz();
  const z = s.zaproszenia.find((x) => x.kod === String(kod || "").trim());
  if (!z) throw new Error("Nieznany kod zaproszenia.");
  if (z.uniewaznione) throw new Error("Zaproszenie zostało unieważnione.");
  if (z.wygasa && z.wygasa <= t) throw new Error("Zaproszenie straciło ważność.");
  if (z.jednorazowe && z.uzyte > 0) throw new Error("Zaproszenie jednorazowe zostało już użyte.");

  z.uzyte += 1;
  const zeton = {
    id: losowy(4),
    sekret: losowy(16),
    imie: z.imie,
    rola: z.rola,
    zZaproszenia: z.id,
    utworzono: t,
    ostatnioWidziany: t,
    odciety: false,
    ip: ip || null,
  };
  s.zetony.push(zeton);
  zapisz();
  zapiszZdarzenie("wejscie", `${z.imie} przyjął zaproszenie`, { imie: z.imie, rola: z.rola, ip });
  return { zeton: `${zeton.id}.${zeton.sekret}`, imie: zeton.imie, rola: zeton.rola };
}

// ---- żetony ----

function rowne(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

// Zwraca żeton albo null. Nie rzuca — brak dostępu to normalny stan, nie awaria.
export function sprawdzZeton(pelny) {
  if (!pelny || typeof pelny !== "string") return null;
  const kropka = pelny.indexOf(".");
  if (kropka < 1) return null;
  const id = pelny.slice(0, kropka);
  const sekret = pelny.slice(kropka + 1);
  const z = wczytaj().zetony.find((x) => x.id === id);
  if (!z || z.odciety || !rowne(z.sekret, sekret)) return null;
  z.ostatnioWidziany = teraz();
  return z;
}

export function zetony() {
  return wczytaj().zetony.map((z) => ({
    id: z.id,
    imie: z.imie,
    rola: z.rola,
    utworzono: z.utworzono,
    ostatnioWidziany: z.ostatnioWidziany,
    odciety: z.odciety,
    ip: z.ip,
  }));
}

export function odetnij(id) {
  const s = wczytaj();
  const z = s.zetony.find((x) => x.id === id);
  if (!z) throw new Error("Nie ma takiego żetonu.");
  z.odciety = true;
  zapisz();
  zapiszZdarzenie("odciecie", `odcięto ${z.imie}`, { imie: z.imie, zetonId: z.id });
  return z;
}

// ---- pierwszy admin ----
//
// Bez tego serwer byłby zamknięty sam przed sobą: nie ma admina, więc nie ma kto
// wydać pierwszego zaproszenia. Przy pustym stanie wypisujemy kod na konsolę.
export function zapewnijAdmina() {
  const s = wczytaj();
  const maAdmina = s.zetony.some((z) => z.rola === "admin" && !z.odciety);
  const maZaproszenieAdmina = zaproszenia().some((z) => z.rola === "admin" && z.wazne);
  if (maAdmina || maZaproszenieAdmina) return null;

  return utworzZaproszenie({
    imie: process.env.ADMIN_IMIE || "administrator",
    rola: "admin",
    jednorazowe: false, // pierwszy admin bywa potrzebny na kilku urządzeniach
  });
}

export const ROLE_ZNANE = ROLE;
