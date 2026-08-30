// Rozmowa ze stacją: wywołania API, wymiana kodu na żeton, nagłówki dla WHEP.
//
// „Która stacja" i „czyj żeton" siedzą w polaczenie.js — tutaj jest tylko warstwa wywołań.
// Do 2026-08-23 adresy były względne (`/api/...`), czyli klient mógł rozmawiać wyłącznie
// z tym serwerem, który wydał stronę. Teraz są **bezwzględne, budowane z adresu stacji** —
// telefon z aplikacją na pulpicie może się przełączyć na stację pod innym adresem.
//
// Żeton ma postać "id.sekret" i przeglądarka musi go podać na trzy różne sposoby,
// z których tylko jeden obsłużyłoby ciasteczko:
//   - nagłówek Authorization  → zwykłe wywołania API,
//   - parametr w adresie      → EventSource, który nie umie ustawiać nagłówków,
//   - HTTP Basic              → WHEP do MediaMTX (id = użytkownik, sekret = hasło).
//
// Wywołanie na inny adres niż strona jest zapytaniem międzyźródłowym, więc serwer
// musi odesłać nagłówki CORS — robi to `server/index.mjs`. Ciasteczek nie używamy
// i `credentials` zostaje domyślne, dzięki czemu serwer może odbić dowolne źródło
// bez otwierania drogi do sesji przeglądarki.
import { adresStacji, zeton, zapiszZeton, ustawStacje, rozbierzKod } from "./polaczenie";

export { zeton, ustawStacje, rozbierzKod };
export { wyloguj, adresStacji, adresWhep, listaStacji, zapomnijStacje, stacjaObca,
         normalizujAdres, zbudujKodPolaczenia, kodZAdresu, wyczyscKodZAdresu } from "./polaczenie";

// Rozkłada żeton na parę dla HTTP Basic.
export function basic(adres = adresStacji()) {
  const z = zeton(adres);
  if (!z) return null;
  const i = z.indexOf(".");
  if (i < 1) return null;
  return { uzytkownik: z.slice(0, i), haslo: z.slice(i + 1) };
}

export function naglowekBasic(adres = adresStacji()) {
  const b = basic(adres);
  if (!b) return null;
  return "Basic " + btoa(`${b.uzytkownik}:${b.haslo}`);
}

export class BladDostepu extends Error {
  constructor(status, wiadomosc) {
    super(wiadomosc);
    this.status = status;
  }
}

/** Rozpoznaje, że stacja jest nieosiągalna — inaczej niż „odmówiła”. */
export class BladLacza extends Error {
  constructor(adres, przyczyna) {
    super(`Nie ma łączności ze stacją ${adres}.`);
    this.adres = adres;
    this.przyczyna = przyczyna;
  }
}

const pelnyAdres = (sciezka, adres = adresStacji()) =>
  sciezka.startsWith("http") ? sciezka : `${adres}${sciezka}`;

// Jedno wejście do API. Rzuca BladDostepu przy 401/403, żeby ekran mógł odróżnić
// „nie masz zaproszenia" od zwykłej awarii, i BladLacza, gdy stacji w ogóle nie ma —
// to dwie różne rady dla widza, a nie jeden komunikat „coś poszło nie tak".
export async function api(sciezka, opcje = {}) {
  const adres = opcje.adres || adresStacji();
  const z = opcje.zeton !== undefined ? opcje.zeton : zeton(adres);
  const naglowki = { ...(opcje.headers || {}) };
  if (z) naglowki.Authorization = `Bearer ${z}`;
  if (opcje.body && !naglowki["Content-Type"]) naglowki["Content-Type"] = "application/json";

  let res;
  try {
    res = await fetch(pelnyAdres(sciezka, adres), {
      ...opcje,
      headers: naglowki,
      body: opcje.body && typeof opcje.body !== "string" ? JSON.stringify(opcje.body) : opcje.body,
    });
  } catch (e) {
    throw new BladLacza(adres, e);
  }

  let dane = null;
  try {
    dane = await res.json();
  } catch {
    /* odpowiedź bez JSON-a — zostaje status */
  }

  if (!res.ok) {
    throw new BladDostepu(res.status, dane?.blad || `${res.status} ${res.statusText}`);
  }
  return dane;
}

// Adres SSE z żetonem — EventSource inaczej się nie przedstawi.
export function adresTelemetrii(zrodlo) {
  const adres = adresStacji();
  const p = new URLSearchParams();
  const z = zeton(adres);
  if (z) p.set("zeton", z);
  if (zrodlo) p.set("zrodlo", zrodlo);
  return `${adres}/api/telemetria?${p.toString()}`;
}

/**
 * Wymiana kodu na żeton. `kod` może być zwykłym kodem zaproszenia albo kodem
 * połączeniowym, który niesie też adres stacji — wtedy adres bierzemy z niego
 * i to on staje się stacją aktywną.
 *
 * `adresRezerwowy` jest dla zwykłego kodu: to, co widz wpisał w polu adresu.
 */
export async function przyjmijZaproszenie(kod, adresRezerwowy = null) {
  const rozebrany = rozbierzKod(kod);
  if (!rozebrany) throw new BladDostepu(400, "Nie rozpoznaję tego kodu.");

  const adres = rozebrany.adres || (adresRezerwowy ? ustawStacje(adresRezerwowy) : adresStacji());
  if (!adres) throw new BladDostepu(400, "Podaj adres stacji.");

  let res;
  try {
    res = await fetch(`${adres}/api/zaproszenie`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ kod: rozebrany.kod }),
    });
  } catch (e) {
    throw new BladLacza(adres, e);
  }

  const dane = await res.json().catch(() => null);
  if (!res.ok) throw new BladDostepu(res.status, dane?.blad || "Zaproszenie odrzucone.");

  // Dopiero teraz zapamiętujemy stację: dopisanie jej przed udaną wymianą zostawiałoby
  // na liście adresy, pod którymi nigdy nie było wstępu.
  ustawStacje(adres);
  zapiszZeton(dane.zeton, { imie: dane.imie, rola: dane.rola }, adres);
  return dane;
}
