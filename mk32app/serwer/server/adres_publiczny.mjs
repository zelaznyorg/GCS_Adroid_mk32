// Ustalanie adresu publicznego stacji — na potrzeby ręcznego wpisania endpointu
// WireGuarda w kliencie. Decyzja 6 z 2026-08-20, patrz dok/SERWER_PODGLADU.md §6.5.
//
// Dlaczego ręcznie, a nie serwerem koordynującym: automatyczna wymiana endpointów
// (rendezvous + hole punching + przekaźnik) to osobny system klasy NetBird/headscale.
// Dopóki stacja stoi za routerem z publicznym adresem, jedyne, czego brakuje,
// to wiedza JAKI to dziś adres — a to jest jedna liczba do przepisania.
//
// Node 16 wciąż w grze (jak w mediamtx.mjs), więc bez global fetch.
import https from "node:https";
import http from "node:http";

// Domyślne źródło zwraca sam adres, czystym tekstem, bez JSON-a.
const URL_ZRODLA = process.env.ADRES_PUBLICZNY_URL || "https://api.ipify.org";
// Adres wpisany na sztywno wyłącza odpytywanie na zewnątrz całkowicie.
const ADRES_STALY = process.env.ADRES_PUBLICZNY || "";
// Port nasłuchu WireGuarda — wchodzi do endpointu pokazywanego operatorowi.
const PORT_WG = Number(process.env.WG_PORT) || 51820;
const ODSWIEZAJ_MS = Number(process.env.ADRES_PUBLICZNY_ODSWIEZ_MS) || 5 * 60 * 1000;

const WYLACZONE = ADRES_STALY === "" && /^(off|nie|brak)$/i.test(URL_ZRODLA);

let ostatni = null;      // { adres, zrodlo, czas }
let ostatniBlad = null;
let wPoczekalni = null;  // obietnica trwającego zapytania — nie odpytujemy równolegle

const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;

function pobierz(adresUrl) {
  return new Promise((resolve, reject) => {
    const u = new URL(adresUrl);
    const lib = u.protocol === "http:" ? http : https;
    const req = lib.request(
      { hostname: u.hostname, port: u.port || undefined, path: u.pathname + u.search, method: "GET", timeout: 5000 },
      (res) => {
        let b = "";
        res.on("data", (d) => (b += d));
        res.on("end", () => {
          if (!res.statusCode || res.statusCode < 200 || res.statusCode >= 300) {
            return reject(new Error(`HTTP ${res.statusCode}`));
          }
          resolve(b.trim());
        });
      }
    );
    req.on("error", reject);
    req.on("timeout", () => { req.destroy(); reject(new Error("timeout")); });
    req.end();
  });
}

async function odswiez() {
  const surowy = await pobierz(URL_ZRODLA);
  // Część usług zwraca JSON — bierzemy pierwsze, co wygląda na adres IPv4.
  const adres = IPV4.test(surowy) ? surowy : (surowy.match(/\d{1,3}(\.\d{1,3}){3}/) || [])[0];
  if (!adres) throw new Error("odpowiedź bez adresu IPv4");
  ostatni = { adres, zrodlo: new URL(URL_ZRODLA).hostname, czas: Date.now() };
  ostatniBlad = null;
  return ostatni;
}

// Zwraca stan adresu publicznego. Nigdy nie rzuca — brak adresu to informacja
// dla operatora, nie awaria serwera. Ostatni znany adres zostaje pokazany razem
// z jego wiekiem, żeby było widać, że może być nieaktualny.
export async function stanAdresu() {
  if (ADRES_STALY) {
    return { adres: ADRES_STALY, zrodlo: "konfiguracja", wiek_s: 0, blad: null, port: PORT_WG,
             endpoint: `${ADRES_STALY}:${PORT_WG}` };
  }
  if (WYLACZONE) {
    return { adres: null, zrodlo: null, wiek_s: null, blad: "odpytywanie wyłączone", port: PORT_WG, endpoint: null };
  }

  const swiezy = ostatni && Date.now() - ostatni.czas < ODSWIEZAJ_MS;
  if (!swiezy) {
    if (!wPoczekalni) {
      wPoczekalni = odswiez()
        .catch((e) => { ostatniBlad = String(e.message || e); return null; })
        .finally(() => { wPoczekalni = null; });
    }
    await wPoczekalni;
  }

  if (!ostatni) {
    return { adres: null, zrodlo: null, wiek_s: null, blad: ostatniBlad, port: PORT_WG, endpoint: null };
  }
  return {
    adres: ostatni.adres,
    zrodlo: ostatni.zrodlo,
    wiek_s: Math.round((Date.now() - ostatni.czas) / 1000),
    blad: ostatniBlad,
    port: PORT_WG,
    endpoint: `${ostatni.adres}:${PORT_WG}`,
  };
}
