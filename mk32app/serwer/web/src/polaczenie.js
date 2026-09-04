// Do której stacji jesteśmy podłączeni.
//
// (Nazwa pliku brzmi „polaczenie", a nie „stacja", bo `Stacja.jsx` to panel stanu
//  sprzętu. Na Windowsie i macOS nazwy różniące się tylko wielkością liter są tym
//  samym plikiem i build się wywraca.)
//
// Do 2026-08-23 klient tego pojęcia nie miał: wszystkie adresy były względne, a bazę
// WHEP wyprowadzał z `window.location`. Działało, bo stronę i tak serwowała stacja —
// żeby ją otworzyć, trzeba było już do niej dosięgnąć.
//
// Na telefonie to się rozjeżdża. Aplikacja dodana na pulpit jest przypięta do adresu,
// spod którego ją zainstalowano, a adres stacji się zmienia: raz LAN, raz tunel
// WireGuard, raz adres publiczny spod CGNAT (dok/SERWER_PODGLADU.md §6.5 — endpoint
// przepisywany ręcznie). Ikona na pulpicie zostawała wtedy martwa.
//
// Dlatego adres stacji jest tu **osobnym, zapamiętywanym stanem**, a żeton jest
// **przypisany do adresu**, nie do przeglądarki. Powrót do stacji, w której już
// się było, nie wymaga ponownego kodu.
//
// Projekt: dok/DOSTEP_I_UZYTKOWNICY.md §3, dok/TELEFON.md §2a.

const KLUCZ = "dron15.stacje";
const KLUCZ_STARY = "dron15.zeton";   // pojedynczy żeton sprzed tej zmiany
const PORT_DOMYSLNY = 8095;

// Ile stacji pamiętamy. Więcej niż kilka nie ma sensu — to nie jest książka adresowa,
// tylko lista „gdzie ostatnio oglądałem".
const PAMIEC = 8;

// ---------------------------------------------------------------- pamięć

function pusta() {
  return { aktywna: null, stacje: {} };
}

function wczytaj() {
  try {
    const s = JSON.parse(localStorage.getItem(KLUCZ) || "null");
    if (s && typeof s === "object" && s.stacje) return { aktywna: s.aktywna ?? null, stacje: s.stacje };
  } catch {
    /* uszkodzony wpis — zaczynamy od pustego, to tylko wygoda */
  }

  // Przeniesienie ze starego układu: kto miał żeton przed tą zmianą, ma go dalej.
  // Żeton bez adresu przypisujemy do adresu, spod którego załadowano stronę —
  // bo dokładnie stamtąd go dostał.
  try {
    const stary = localStorage.getItem(KLUCZ_STARY);
    if (stary) {
      const s = pusta();
      const a = window.location.origin;
      s.stacje[a] = { zeton: stary, ostatnio: Date.now() };
      s.aktywna = a;
      localStorage.setItem(KLUCZ, JSON.stringify(s));
      localStorage.removeItem(KLUCZ_STARY);
      return s;
    }
  } catch {
    /* tryb prywatny */
  }

  return pusta();
}

function zapisz(s) {
  try {
    // Przycinamy do najświeższych — bez tego lista rośnie w nieskończoność
    // przy każdej zmianie adresu tunelu.
    const wpisy = Object.entries(s.stacje)
      .sort((a, b) => (b[1].ostatnio || 0) - (a[1].ostatnio || 0))
      .slice(0, PAMIEC);
    s.stacje = Object.fromEntries(wpisy);
    localStorage.setItem(KLUCZ, JSON.stringify(s));
  } catch {
    /* tryb prywatny — sesja przeżyje do zamknięcia karty */
  }
}

// ---------------------------------------------------------------- adres

/**
 * Sprowadza to, co wpisał człowiek, do postaci `http://host:port`.
 * Przyjmuje `192.168.1.50`, `192.168.1.50:8095`, `http://192.168.1.50:8095/`,
 * bo wszystkie trzy postacie krążą w notatkach i w komunikatorach.
 * Zwraca null, gdy z tekstu nie da się zrobić adresu.
 */
export function normalizujAdres(tekst) {
  let t = String(tekst || "").trim();
  if (!t) return null;
  if (!/^https?:\/\//i.test(t)) t = `http://${t}`;
  let u;
  try {
    u = new URL(t);
  } catch {
    return null;
  }
  if (!u.hostname) return null;
  const port = u.port || String(PORT_DOMYSLNY);
  return `${u.protocol}//${u.hostname}:${port}`;
}

/** Adres stacji, z którą rozmawiamy. Domyślnie ten, spod którego załadowano stronę. */
export function adresStacji() {
  const s = wczytaj();
  return s.aktywna || window.location.origin;
}

/** Baza WHEP dla MediaMTX — ten sam host co strona, port 8889 (SERWER_PODGLADU.md §6.4). */
export function adresWhep() {
  const u = new URL(adresStacji());
  return `${u.protocol}//${u.hostname}:8889`;
}

/** Czy rozmawiamy ze stacją inną niż ta, która wydała tę stronę. */
export function stacjaObca() {
  return adresStacji() !== window.location.origin;
}

export function listaStacji() {
  const s = wczytaj();
  return Object.entries(s.stacje)
    .map(([adres, w]) => ({ adres, imie: w.imie ?? null, rola: w.rola ?? null, ostatnio: w.ostatnio || 0 }))
    .sort((a, b) => b.ostatnio - a.ostatnio);
}

export function ustawStacje(adres) {
  const a = normalizujAdres(adres);
  if (!a) return null;
  const s = wczytaj();
  s.aktywna = a;
  if (!s.stacje[a]) s.stacje[a] = {};
  s.stacje[a].ostatnio = Date.now();
  zapisz(s);
  return a;
}

export function zapomnijStacje(adres) {
  const s = wczytaj();
  delete s.stacje[adres];
  if (s.aktywna === adres) s.aktywna = null;
  zapisz(s);
}

// ---------------------------------------------------------------- żeton

export function zeton(adres = adresStacji()) {
  return wczytaj().stacje[adres]?.zeton || null;
}

export function zapiszZeton(zetonNowy, dane = {}, adres = adresStacji()) {
  const s = wczytaj();
  if (!s.stacje[adres]) s.stacje[adres] = {};
  if (zetonNowy) {
    s.stacje[adres] = {
      ...s.stacje[adres],
      zeton: zetonNowy,
      imie: dane.imie ?? s.stacje[adres].imie ?? null,
      rola: dane.rola ?? s.stacje[adres].rola ?? null,
      // Z jakiego kodu zaproszenia wziął się ten żeton — żeby INNY kod w adresie
      // (np. kafelek stacji z kodem admina) mógł podmienić tożsamość, a ten sam
      // nie logował od nowa przy każdym otwarciu.
      kod: dane.kod ?? s.stacje[adres].kod ?? null,
      ostatnio: Date.now(),
    };
    s.aktywna = adres;
  } else {
    delete s.stacje[adres].zeton;
  }
  zapisz(s);
}

/** Kod zaproszenia, z którego pochodzi zapamiętany żeton (null, gdy nieznany). */
export function kodZetonu(adres = adresStacji()) {
  return wczytaj().stacje[adres]?.kod || null;
}

export function wyloguj(adres = adresStacji()) {
  zapiszZeton(null, {}, adres);
}

// ---------------------------------------------------------------- kod połączeniowy

// Zwykły kod zaproszenia to 24 znaki szesnastkowe i mówi WYŁĄCZNIE „kim jesteś".
// Kod połączeniowy niesie dodatkowo „gdzie" — dzięki temu jedna rzecz wysłana
// komunikatorem wystarczy, żeby telefon trafił do stacji i się przedstawił.
//
// Postać: `D15-<base64url("adres|kod")>`. Świadomie NIE jest to adres URL:
// komunikatory zamieniają linki na podglądy i łamią je w połowie, a ten ciąg
// przechodzi przez nie bez zmian. Prefiks pozwala odróżnić go od zwykłego kodu.
const PREFIKS = "D15-";

function naBase64Url(tekst) {
  const bajty = new TextEncoder().encode(tekst);
  let binarnie = "";
  for (const b of bajty) binarnie += String.fromCharCode(b);
  return btoa(binarnie).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function zBase64Url(tekst) {
  const uzupelnienie = "=".repeat((4 - (tekst.length % 4)) % 4);
  const binarnie = atob(tekst.replace(/-/g, "+").replace(/_/g, "/") + uzupelnienie);
  const bajty = Uint8Array.from(binarnie, (z) => z.charCodeAt(0));
  return new TextDecoder().decode(bajty);
}

export function zbudujKodPolaczenia(adres, kod) {
  const a = normalizujAdres(adres);
  if (!a || !kod) return null;
  return PREFIKS + naBase64Url(`${a}|${String(kod).trim()}`);
}

/**
 * Rozbiera to, co człowiek wkleił. Zwraca `{ adres, kod }`, gdzie `adres` bywa null —
 * dla zwykłego kodu zaproszenia, który mówi tylko „kim jesteś".
 */
export function rozbierzKod(tekst) {
  const t = String(tekst || "").trim();
  if (!t) return null;

  if (t.startsWith(PREFIKS)) {
    try {
      const [adres, kod] = zBase64Url(t.slice(PREFIKS.length)).split("|");
      const a = normalizujAdres(adres);
      if (!a || !kod) return null;
      return { adres: a, kod };
    } catch {
      return null;   // uszkodzony po drodze — traktujemy jak zły kod
    }
  }

  // Ktoś wkleił cały link `http://adres:8095/#z=KOD` — bierzemy z niego oba człony.
  if (/^https?:\/\//i.test(t)) {
    try {
      const u = new URL(t);
      const kod = /[#&?]z=([A-Za-z0-9-]+)/.exec(u.hash + u.search)?.[1];
      const a = normalizujAdres(`${u.protocol}//${u.hostname}:${u.port || PORT_DOMYSLNY}`);
      if (a && kod) return { adres: a, kod };
    } catch {
      /* nie link — spadamy do zwykłego kodu */
    }
  }

  return { adres: null, kod: t };
}

/** Kod z adresu strony: `.../#z=KOD` albo `.../?z=KOD`. Przyjmuje też kod połączeniowy. */
export function kodZAdresu() {
  const zHash = /[#&]z=([A-Za-z0-9\-_]+)/.exec(window.location.hash || "");
  if (zHash) return zHash[1];
  return new URLSearchParams(window.location.search).get("z");
}

export function wyczyscKodZAdresu() {
  window.history.replaceState({}, "", window.location.pathname);
}
