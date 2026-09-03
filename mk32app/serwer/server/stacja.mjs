// Obsługa samej STACJI: stan maszyny, usługi systemd, dziennik systemowy.
// Panel: web/src/Stacja.jsx. Procedura wdrożenia: dok/WDROZENIE_RPI.md.
//
// To jest odpowiednik `rpi/sprawdz.sh` w przeglądarce — ten sam zestaw odczytów,
// żeby nie trzeba było wchodzić po ssh. Skrypt zostaje: jest jedyną drogą wtedy,
// gdy serwer NIE wstaje, a wtedy panelu też nie ma.
//
// ⛔ GRANICA, KTÓREJ TEN MODUŁ NIE PRZEKRACZA
//
// Steruje **stacją**, nie maszyną. Nie ma tu ani jednej ścieżki prowadzącej do
// kontrolera lotu ani do głowicy — władza zostaje na MK32 (dok/WLADZA.md).
// Restart usługi podglądu zabiera obraz widzom i nic poza tym.
//
// ⛔ CO WOLNO URUCHOMIĆ
//
// Wyłącznie polecenia z listy niżej, przez execFile z tablicą argumentów.
// Nazwa usługi z żądania jest **sprawdzana wobec zamkniętej listy**, nigdy
// wklejana do polecenia. Bez tego panel admina byłby zdalną powłoką.
import { execFile } from "node:child_process";
import { readFile, statfs } from "node:fs/promises";
import { createConnection } from "node:net";
import os from "node:os";
import { join } from "node:path";
import { ROOT } from "../scripts/zrodla-lib.mjs";
import * as rejestr from "./rejestr.mjs";

/** Zamknięta lista usług, których panel może dotknąć. */
export const USLUGI = [
  {
    id: "panorama-mediamtx",
    nazwa: "OBRAZ",
    opis: "MediaMTX — remux RTSP z ZR30 na WebRTC. Restart zrywa obraz wszystkim widzom.",
    zakres: "system",
  },
  {
    id: "panorama-gcs",
    nazwa: "SERWER",
    opis: "Ta strona, API, telemetria i archiwum. Restart rozłącza także ten panel — wróci sam po kilku sekundach.",
    zakres: "system",
    // Restart tej usługi ubija proces, który obsługuje żądanie. Trzeba więc
    // najpierw odpowiedzieć, a dopiero potem zejść z pola.
    ubijaNas: true,
  },
  {
    id: "panorama-kiosk",
    nazwa: "MONITORY",
    opis: "Chromium na monitorach stacji. Jednostka UŻYTKOWNIKA — bywa nieosiągalna z usługi systemowej.",
    zakres: "uzytkownik",
  },
];

const LINUX = process.platform === "linux";
// Odczyty z /proc, /sys i systemctl są tanie, ale panel odpytuje co kilka sekund
// i po co je powtarzać częściej, niż cokolwiek się zmienia.
const CACHE_MS = Number(process.env.STACJA_CACHE_MS) || 4000;
const LIMIT_MS = 5000;

let cache = null;
let cacheDo = 0;

function uruchom(polecenie, argumenty, opcje = {}) {
  return new Promise((resolve) => {
    execFile(
      polecenie,
      argumenty,
      { timeout: LIMIT_MS, maxBuffer: 4 * 1024 * 1024, ...opcje },
      (blad, stdout, stderr) => {
        // `systemctl is-active` na zatrzymanej usłudze kończy się kodem ≠ 0
        // i to jest normalna odpowiedź, nie awaria — dlatego kod i wyjście
        // oddajemy zawsze, a decyzję zostawiamy wołającemu.
        resolve({
          kod: blad?.code ?? 0,
          wyjscie: String(stdout || "").trim(),
          blad: String(stderr || "").trim() || (blad && !blad.code ? blad.message : ""),
        });
      }
    );
  });
}

async function plik(sciezka) {
  try {
    return (await readFile(sciezka, "utf8")).trim();
  } catch {
    return null;
  }
}

// ---- stan maszyny ----------------------------------------------------------

async function temperatura() {
  // /sys działa bez vcgencmd i bez uprawnień — dlatego jest pierwszym wyborem.
  const surowa = await plik("/sys/class/thermal/thermal_zone0/temp");
  if (surowa && /^\d+$/.test(surowa)) return Number(surowa) / 1000;
  const v = await uruchom("vcgencmd", ["measure_temp"]);
  const m = /([\d.]+)/.exec(v.wyjscie || "");
  return m ? Number(m[1]) : null;
}

/**
 * Dławienie zasilania. Bit 0 = niedomiar napięcia TERAZ, bit 16 = był wcześniej.
 * To jest najczęstsza przyczyna „losowych zawieszeń" stacji, a myli, bo wygląda
 * dokładnie jak usterka oprogramowania (dok/GCS_RPI5.md §2).
 */
async function dlawienie() {
  if (!LINUX) return null;
  const r = await uruchom("vcgencmd", ["get_throttled"]);
  const m = /0x([0-9a-fA-F]+)/.exec(r.wyjscie || "");
  if (!m) return null;
  const w = parseInt(m[1], 16);
  return {
    surowe: `0x${m[1]}`,
    czysto: w === 0,
    niedomiarTeraz: Boolean(w & 0x1),
    niedomiarByl: Boolean(w & 0x10000),
    dlawienieTeraz: Boolean(w & 0x4),
    dlawienieBylo: Boolean(w & 0x40000),
  };
}

async function system() {
  const model = await plik("/proc/device-tree/model");
  const osRelease = await plik("/etc/os-release");
  const nazwa = osRelease && /PRETTY_NAME="?([^"\n]+)"?/.exec(osRelease)?.[1];
  return {
    model: model ? model.replace(/\0/g, "") : null,
    system: nazwa || `${os.type()} ${os.release()}`,
    jadro: os.release(),
    architektura: os.arch(),
    hostname: os.hostname(),
    czasPracyS: os.uptime(),
    pamiec: { calosc: os.totalmem(), wolna: os.freemem() },
    obciazenie: os.loadavg(),
    rdzeni: os.cpus().length,
  };
}

// ---- sieć ------------------------------------------------------------------

/**
 * MTU wszystkich interfejsów. Powód, dla którego to w ogóle jest w panelu:
 * najbardziej mylący objaw w projekcie to „strona i telemetria działają,
 * a obraz nie startuje" — i prawie zawsze odpowiada za niego MTU tunelu,
 * nie serwer (README, sekcja Porty; TODO 2.3).
 */
async function siec() {
  const interfejsy = [];
  const adresy = os.networkInterfaces();
  for (const [nazwa, lista] of Object.entries(adresy)) {
    for (const i of lista || []) {
      if (i.family !== "IPv4" || i.internal) continue;
      const mtu = await plik(`/sys/class/net/${nazwa}/mtu`);
      interfejsy.push({
        nazwa,
        adres: i.address,
        mtu: mtu ? Number(mtu) : null,
        tunel: nazwa.startsWith("wg"),
      });
    }
  }
  return interfejsy;
}

// Czy ktoś nasłuchuje na porcie. Własne połączenie zamiast `ss`, bo działa
// wszędzie tak samo i nie wymaga doinstalowania iproute2.
function portZajety(port, host = "127.0.0.1") {
  return new Promise((resolve) => {
    const s = createConnection({ port, host });
    const koniec = (wynik) => {
      s.destroy();
      resolve(wynik);
    };
    s.setTimeout(700);
    s.once("connect", () => koniec(true));
    s.once("timeout", () => koniec(false));
    s.once("error", () => koniec(false));
  });
}

async function porty() {
  const opis = [
    // Port strony bierzemy ze zmiennej, nie z liczby wpisanej na sztywno —
    // inaczej panel uruchomiony na innym porcie meldowałby własną śmierć.
    { port: Number(process.env.PORT) || 8095, rola: "strona, API, telemetria", wTunelu: true },
    { port: 8889, rola: "MediaMTX — sygnalizacja WHEP", wTunelu: true },
    { port: 9997, rola: "API MediaMTX — TYLKO lokalnie", wTunelu: false },
    { port: 8555, rola: "RTSP dla NAGRYWARKI pulpitu — TYLKO lokalnie", wTunelu: false },
  ];
  const out = [];
  for (const p of opis) out.push({ ...p, zywy: await portZajety(p.port) });

  // Porty „TYLKO lokalnie” wystawione na świat to dziura: 9997 pozwala każdemu
  // w sieci przestawiać ścieżki obrazu, a 8555 oddaje obraz bez żetonu widza
  // (mtx-auth ufa RTSP właśnie dlatego, że ma być z pętli zwrotnej). Sprawdzamy
  // to osobno, pod adresem zewnętrznym stacji.
  const zewnetrzny = Object.values(os.networkInterfaces())
    .flat()
    .find((i) => i && i.family === "IPv4" && !i.internal)?.address;
  if (zewnetrzny) {
    for (const p of out) {
      if (!p.wTunelu) p.wystawioneNaSwiat = await portZajety(p.port, zewnetrzny);
    }
  }

  return out;
}

// ---- oprogramowanie --------------------------------------------------------

async function wersje() {
  // bin/mediamtx, nie mediamtx — obok stoi katalog konfiguracji o tej samej nazwie.
  const mtx = await uruchom(join(ROOT, "bin", "mediamtx"), ["--version"]);
  const chrom = LINUX
    ? await uruchom("sh", ["-c", "chromium-browser --version || chromium --version"])
    : { wyjscie: "" };
  return {
    node: process.version,
    mediamtx: mtx.wyjscie || null,
    chromium: chrom.wyjscie || null,
  };
}

/**
 * Czy w systemie jest dekoder HEVC. RPi 5 stracił blok H.264 i ma wyłącznie
 * sprzętowy dekoder HEVC (dok/GCS_RPI5.md §2), więc na monitorach chcemy H.265.
 *
 * ⚠ Obecność dekodera w systemie NIE dowodzi, że Chromium z niego korzysta —
 * to rozstrzyga wyłącznie chrome://media-internals podczas odtwarzania
 * (zadanie 2.2 z TODO.md). Panel mówi tylko tyle, ile widzi.
 */
async function dekoder() {
  if (!LINUX) return { dostepny: null, powod: "nie Linux" };
  const r = await uruchom("sh", [
    "-c",
    "for d in /dev/video*; do v4l2-ctl -d $d --list-formats 2>/dev/null | grep -qi hevc && echo $d; done",
  ]);
  const urzadzenia = r.wyjscie ? r.wyjscie.split("\n").filter(Boolean) : [];
  if (urzadzenia.length) return { dostepny: true, urzadzenia };
  const maV4l2 = await uruchom("sh", ["-c", "command -v v4l2-ctl"]);
  return {
    dostepny: false,
    powod: maV4l2.wyjscie ? "żadne /dev/video* nie zgłasza HEVC" : "brak v4l2-ctl (sudo apt install v4l-utils)",
  };
}

// ---- usługi ----------------------------------------------------------------

async function stanUslugi(u) {
  if (!LINUX) return { ...u, stan: "nieznany", wlaczona: null, powod: "systemd tylko na Linuksie" };

  const args = u.zakres === "uzytkownik" ? ["--user"] : [];
  const aktywna = await uruchom("systemctl", [...args, "is-active", u.id]);
  const wlaczona = await uruchom("systemctl", [...args, "is-enabled", u.id]);
  const wlasciwosci = await uruchom("systemctl", [
    ...args,
    "show",
    u.id,
    "--property=ActiveEnterTimestampMonotonic,NRestarts,MemoryCurrent,Result",
  ]);

  const pola = {};
  for (const linia of wlasciwosci.wyjscie.split("\n")) {
    const i = linia.indexOf("=");
    if (i > 0) pola[linia.slice(0, i)] = linia.slice(i + 1);
  }

  const odMono = Number(pola.ActiveEnterTimestampMonotonic || 0) / 1e6;
  return {
    id: u.id,
    nazwa: u.nazwa,
    opis: u.opis,
    zakres: u.zakres,
    stan: aktywna.wyjscie || "nieznany",
    wlaczona: wlaczona.wyjscie === "enabled" ? true : wlaczona.wyjscie === "disabled" ? false : null,
    // Monotoniczny znacznik liczy się od startu systemu, więc na sekundy
    // przeliczamy go przez czas pracy maszyny — inaczej wyszłaby data z 1970.
    dzialaOdS: odMono > 0 ? Math.max(0, os.uptime() - odMono) : null,
    restartow: Number(pola.NRestarts || 0),
    pamiecB: pola.MemoryCurrent && /^\d+$/.test(pola.MemoryCurrent) ? Number(pola.MemoryCurrent) : null,
    wynik: pola.Result || null,
  };
}

export async function uslugi() {
  const out = [];
  for (const u of USLUGI) out.push(await stanUslugi(u));
  return out;
}

// ---- przegląd całości ------------------------------------------------------

export async function przeglad({ zCache = true } = {}) {
  if (zCache && cache && Date.now() < cacheDo) return cache;

  const [sys, dlaw, temp, netto, listaPortow, wer, dek, listaUslug] = await Promise.all([
    system(),
    dlawienie(),
    temperatura(),
    siec(),
    porty(),
    wersje(),
    dekoder(),
    uslugi(),
  ]);

  let dysk = null;
  try {
    const s = await statfs(process.env.DATA_DIR || ROOT);
    dysk = { calosc: s.blocks * s.bsize, wolne: s.bavail * s.bsize };
  } catch {
    /* bez tej liczby panel działa dalej */
  }

  cache = {
    linux: LINUX,
    czas: Date.now(),
    system: sys,
    temperaturaC: temp,
    dlawienie: dlaw,
    dysk,
    siec: netto,
    porty: listaPortow,
    wersje: wer,
    dekoder: dek,
    uslugi: listaUslug,
    // Panel ma widzieć te same katalogi, co usługi — inaczej „gdzie leżą logi"
    // trzeba by za każdym razem sprawdzać w dokumentacji.
    katalogi: {
      kod: ROOT,
      dane: process.env.DATA_DIR || ROOT,
      archiwum: process.env.ARCHIWUM_DIR || null,
      logi: rejestr.sciezkaPliku(),
    },
  };
  cacheDo = Date.now() + CACHE_MS;
  return cache;
}

export function wyczyscCache() {
  cache = null;
  cacheDo = 0;
}

// ---- działania -------------------------------------------------------------

/**
 * Restart jednej usługi z zamkniętej listy.
 *
 * Nazwa NIGDY nie trafia do polecenia wprost — najpierw musi się znaleźć
 * w USLUGI. Inaczej ten endpoint byłby zdalną powłoką z prawami roota.
 */
export async function restart(nazwa) {
  const u = USLUGI.find((x) => x.id === nazwa);
  if (!u) throw new Error(`Nieznana usługa: "${nazwa}".`);
  if (!LINUX) throw new Error("systemd jest tylko na Linuksie — na Windows restartuj ręcznie.");

  wyczyscCache();

  if (u.zakres === "uzytkownik") {
    // Jednostka użytkownika żyje w sesji graficznej, do której usługa systemowa
    // zwykle nie ma dostępu. Próbujemy, ale niepowodzenie to nie awaria panelu.
    const r = await uruchom("systemctl", ["--user", "restart", u.id]);
    if (r.kod !== 0) {
      throw new Error(
        `Nie mogę zrestartować ${u.id} — jednostka użytkownika bywa nieosiągalna z usługi systemowej. ` +
          `Z konsoli stacji: systemctl --user restart ${u.id}` + (r.blad ? ` (${r.blad})` : "")
      );
    }
    return { usluga: u.id, opozniony: false };
  }

  if (u.ubijaNas) {
    // Odpowiadamy TERAZ, restart odpalamy po chwili. Inaczej systemd ubija nas
    // w połowie wysyłania odpowiedzi i przeglądarka dostaje zerwane połączenie
    // zamiast potwierdzenia.
    setTimeout(() => {
      rejestr.info("stacja", `restart własnej usługi ${u.id} — schodzę z pola`);
      execFile("sudo", ["-n", "systemctl", "restart", u.id], () => {});
    }, 700);
    return { usluga: u.id, opozniony: true };
  }

  const r = await uruchom("sudo", ["-n", "systemctl", "restart", u.id]);
  if (r.kod !== 0) {
    throw new Error(
      `Restart ${u.id} nie powiódł się. Najczęstsza przyczyna: brak wpisu w sudoers — ` +
        `zakłada go rpi/instaluj.sh (${r.blad || "kod " + r.kod}).`
    );
  }
  return { usluga: u.id, opozniony: false };
}

/** Dziennik systemowy jednej usługi. Odczyt wymaga grupy `adm` albo `systemd-journal`. */
export async function dziennikUslugi(nazwa, ile = 80) {
  const u = USLUGI.find((x) => x.id === nazwa);
  if (!u) throw new Error(`Nieznana usługa: "${nazwa}".`);
  if (!LINUX) return { linie: [], powod: "journalctl jest tylko na Linuksie" };

  const args = u.zakres === "uzytkownik" ? ["--user"] : [];
  const r = await uruchom("journalctl", [
    ...args,
    "-u",
    u.id,
    "-n",
    String(Math.min(500, Math.max(10, Number(ile) || 80))),
    "--no-pager",
    "-o",
    "short-iso",
  ]);

  if (r.kod !== 0 && !r.wyjscie) {
    return {
      linie: [],
      powod:
        `Nie mogę czytać dziennika (${r.blad || "kod " + r.kod}). ` +
        "Konto usługi musi należeć do grupy `adm` albo `systemd-journal` — dodaje je rpi/instaluj.sh.",
    };
  }
  return { linie: r.wyjscie ? r.wyjscie.split("\n") : [] };
}

/**
 * Zamyka okno podglądu na ekranie stacji.
 *
 * ### ⛔ Dlaczego strona nie może zamknąć się sama
 *
 * Okno wstaje pełnoekranowe, bez ramki i bez paska (`rpi/podglad.sh`), a przy
 * stacji nie ma klawiatury — więc nie ma ani krzyżyka, ani `Alt+F4`.
 * `window.close()` przeglądarka odrzuca dla okien, których sama nie otworzyła
 * skryptem, więc na tym nie da się polegać. Zostaje zamknięcie procesu.
 *
 * ⚠ Wzorzec celuje **wyłącznie w nasz profil** (`panorama-podglad`), więc nie
 * dotknie ani Chromium uruchomionego do czegoś innego, ani przeglądarki
 * z sąsiedniego kafelka pulpitu. Nie ma tu `sudo` — to ten sam użytkownik.
 */
export function zamknijPodglad() {
  return new Promise((gotowe) => {
    execFile("pkill", ["-f", "user-data-dir=.*panorama-podglad"], (blad) => {
      // pkill zwraca 1, gdy nic nie pasowało — to nie jest awaria, tylko
      // „okna już nie ma". Rozróżniamy, żeby nie meldować sukcesu na pusto.
      const kod = blad?.code;
      gotowe({ zamkniete: !blad, nicNiePasowalo: kod === 1 });
    });
  });
}
