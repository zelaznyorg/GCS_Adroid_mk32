// Archiwum stacji — nagrania telemetrii (`.tlog`) i miejsce na nagrania obrazu.
// Etap M6, zadanie 2.4 z TODO.md. Opis: dok/GCS_RPI5.md §1, dok/WDROZENIE_RPI.md §6.
//
// PODZIAŁ PRACY, ważny do zrozumienia całości:
//
//   telemetria (.tlog)  →  TEN MODUŁ. Ramki MAVLink przychodzą i tak, niezależnie
//                          od tego, czy ktoś ogląda, więc zapis jest darmowy.
//   obraz (.mp4)        →  MEDIAMTX, wprost z remuksowanego strumienia (`record: yes`
//                          w generowanym mediamtx.yml). Serwer obrazu nie dotyka —
//                          gdyby dotykał, musiałby go dekodować i cała oszczędność
//                          z remuksu by przepadła.
//
// Ten moduł pilnuje za to MIEJSCA NA DYSKU dla obu rodzajów plików. MediaMTX ma
// własne `recordDeleteAfter`, ale ono liczy wyłącznie czas i nie wie, ile zostało
// wolnego. Na karcie w RPi to za mało.
//
// Format `.tlog` jest ten sam, co w Mission Plannerze i QGroundControl: ciąg wpisów
//   [8 bajtów, uint64 big-endian, mikrosekundy UTC][surowa ramka MAVLink]
// Weryfikacja: MAVProxy `mavutil.py`, klasa `mavlogfile` — czas zapisywany jako
// `>Q` w mikrosekundach przed każdą ramką. Dzięki temu nagrania ze stacji otwiera
// się tym samym narzędziem, co logi z Mission Plannera.
import {
  createWriteStream,
  mkdirSync,
  readdirSync,
  statSync,
  unlinkSync,
} from "node:fs";
import { statfs } from "node:fs/promises";
import { join } from "node:path";
import { katalogArchiwum } from "../scripts/zrodla-lib.mjs";
import * as rejestr from "./rejestr.mjs";

// Po tylu sekundach bez ramki uznajemy, że lot się skończył i zamykamy plik.
// Krótsza cisza to zwykłe potknięcie łącza — nie ma sensu ciąć na tym nagrania.
const CISZA_KONIEC_S = Number(process.env.ARCHIWUM_CISZA_S) || 60;
// Co ile sprawdzamy miejsce na dysku.
const SPRZATANIE_MS = Number(process.env.ARCHIWUM_SPRZATANIE_MS) || 15 * 60 * 1000;

function zapewnij(sciezka) {
  try {
    mkdirSync(sciezka, { recursive: true });
    return true;
  } catch (e) {
    rejestr.wyjatek("archiwum", `nie mogę utworzyć katalogu ${sciezka}`, e);
    return false;
  }
}

// Nazwa pliku z czasu LOKALNEGO. Celowo nie UTC: nagranie szuka się po godzinie,
// o której się latało, a nie po przeliczeniu w głowie.
function znacznikCzasu(d = new Date()) {
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}_${p(d.getHours())}-${p(d.getMinutes())}-${p(d.getSeconds())}`;
}

// Wszystkie pliki w drzewie, w kolejności przypadkowej. Katalogi MediaMTX są
// zagnieżdżone (wideo/<sciezka>/<plik>.mp4), więc schodzimy rekurencyjnie.
function plikiRekurencyjnie(katalog, rodzaj, wynik = []) {
  let wpisy;
  try {
    wpisy = readdirSync(katalog, { withFileTypes: true });
  } catch {
    return wynik;
  }
  for (const w of wpisy) {
    const pelna = join(katalog, w.name);
    if (w.isDirectory()) {
      plikiRekurencyjnie(pelna, rodzaj, wynik);
      continue;
    }
    try {
      const s = statSync(pelna);
      wynik.push({ sciezka: pelna, nazwa: w.name, rodzaj, bajtow: s.size, czas: s.mtimeMs });
    } catch {
      /* plik zniknął w trakcie czytania katalogu — nic się nie stało */
    }
  }
  return wynik;
}

export class Archiwum {
  /**
   * @param {{wlaczone?: boolean, katalog?: string, wideo?: string,
   *          trzymajDni?: number, limitGb?: number}} ustawienia
   */
  constructor(ustawienia = {}) {
    this.ustaw(ustawienia);

    this.strumien = null;
    this.plikBiezacy = null;
    this.ramekWPliku = 0;
    this.bajtowWPliku = 0;
    this.ostatniaRamka = 0;
    this.timerSprzatania = null;
    this.timerZamkniecia = null;
    this.ostatnieSprzatanie = null;
  }

  // Ustawienia przychodzą już znormalizowane z readArchiwum() — tu tylko je
  // przyjmujemy i przeliczamy ścieżki.
  ustaw(u = {}) {
    this.wlaczone = u.wlaczone !== false;
    this.tryb = u.wideo || "przy-widzach";
    this.trzymajDni = Number(u.trzymajDni) > 0 ? Number(u.trzymajDni) : 30;
    this.limitGb = Number(u.limitGb) > 0 ? Number(u.limitGb) : 50;
    this.katalog = katalogArchiwum(u);
    this.katalogTlog = join(this.katalog, "tlog");
    this.katalogWideo = join(this.katalog, "wideo");
  }

  start() {
    if (!this.wlaczone) {
      rejestr.info("archiwum", "wyłączone w konfiguracji — nic nie zapisuję");
      return;
    }
    if (!zapewnij(this.katalogTlog) || !zapewnij(this.katalogWideo)) {
      // Brak katalogu nie może wywrócić stacji: podgląd ma działać także wtedy,
      // gdy dysk archiwum nie został podmontowany.
      this.wlaczone = false;
      rejestr.ostrzezenie("archiwum", "brak katalogu — archiwum wyłączone, podgląd działa dalej");
      return;
    }
    rejestr.info(
      "archiwum",
      `katalog ${this.katalog}, wideo: ${this.tryb}, limit ${this.limitGb} GB / ${this.trzymajDni} dni`
    );
    this.sprzataj();
    this.timerSprzatania = setInterval(() => this.sprzataj(), SPRZATANIE_MS);
    this.timerSprzatania.unref?.();
  }

  stop() {
    if (this.timerSprzatania) clearInterval(this.timerSprzatania);
    this.timerSprzatania = null;
    if (this.timerZamkniecia) clearTimeout(this.timerZamkniecia);
    this.timerZamkniecia = null;
    this.zamknijPlik("zatrzymanie serwera");
  }

  // ---- zapis telemetrii ----

  /**
   * Dopisuje jedną ramkę MAVLink. Wołane z Telemetrii dla KAŻDEJ odebranej ramki,
   * także tej, której nie umiemy zdekodować — archiwum ma być wierne, nie wybiórcze.
   * @param {Buffer} surowa bajty ramki tak, jak przyszły z łącza
   */
  ramka(surowa) {
    if (!this.wlaczone || !surowa || !surowa.length) return;

    if (!this.strumien && !this.otworzPlik()) return;

    // Jeden bufor, jeden zapis. Sklejenie nie jest ozdobnikiem: `surowa` bywa
    // WIDOKIEM na bufor odbiorczy telemetrii (mavlink.mjs), a strumień może
    // odłożyć zapis na później. Kopia rozcina tę zależność raz na zawsze.
    const wpis = Buffer.allocUnsafe(8 + surowa.length);
    wpis.writeBigUInt64BE(BigInt(Date.now()) * 1000n, 0);
    surowa.copy(wpis, 8);
    try {
      this.strumien.write(wpis);
    } catch (e) {
      rejestr.wyjatek("archiwum", "zapis ramki nie powiódł się", e, { plik: this.plikBiezacy });
      this.zamknijPlik("błąd zapisu");
      return;
    }

    this.ramekWPliku += 1;
    this.bajtowWPliku += 8 + surowa.length;
    this.ostatniaRamka = Date.now();
    this.przestawZamkniecie();
  }

  otworzPlik() {
    const nazwa = `${znacznikCzasu()}.tlog`;
    const sciezka = join(this.katalogTlog, nazwa);
    try {
      this.strumien = createWriteStream(sciezka, { flags: "a" });
      // Błąd strumienia bez tej pułapki wywraca proces — a to jest zapis
      // do archiwum, nie warunek działania podglądu.
      this.strumien.on("error", (e) => {
        rejestr.wyjatek("archiwum", "strumień nagrania padł", e, { plik: sciezka });
        this.strumien = null;
        this.plikBiezacy = null;
      });
    } catch (e) {
      rejestr.wyjatek("archiwum", `nie mogę otworzyć ${sciezka}`, e);
      this.strumien = null;
      return false;
    }
    this.plikBiezacy = sciezka;
    this.ramekWPliku = 0;
    this.bajtowWPliku = 0;
    rejestr.info("archiwum", `nagrywam telemetrię do ${nazwa}`);
    return true;
  }

  zamknijPlik(powod) {
    if (!this.strumien) return;
    const { plikBiezacy, ramekWPliku, bajtowWPliku } = this;
    try {
      this.strumien.end();
    } catch {
      /* i tak zaraz porzucamy uchwyt */
    }
    this.strumien = null;
    this.plikBiezacy = null;
    rejestr.info("archiwum", `zamknięto nagranie (${powod})`, {
      plik: plikBiezacy,
      ramek: ramekWPliku,
      bajtow: bajtowWPliku,
    });
    // Nagranie bez ani jednej ramki to śmieć po nieudanym starcie — kasujemy,
    // żeby katalog nie zapełniał się plikami zerowej długości.
    if (!ramekWPliku && plikBiezacy) {
      try {
        unlinkSync(plikBiezacy);
      } catch {
        /* nieistotne */
      }
    }
  }

  przestawZamkniecie() {
    if (this.timerZamkniecia) clearTimeout(this.timerZamkniecia);
    this.timerZamkniecia = setTimeout(
      () => this.zamknijPlik(`cisza dłuższa niż ${CISZA_KONIEC_S} s`),
      CISZA_KONIEC_S * 1000
    );
    this.timerZamkniecia.unref?.();
  }

  // ---- miejsce na dysku ----

  pliki() {
    return [
      ...plikiRekurencyjnie(this.katalogTlog, "tlog"),
      ...plikiRekurencyjnie(this.katalogWideo, "wideo"),
    ].sort((a, b) => a.czas - b.czas);
  }

  /**
   * Kasuje najstarsze nagrania: najpierw te za stare, potem — jeśli trzeba —
   * kolejne od najstarszego, aż zejdziemy pod limit.
   *
   * Plik nagrywany w tej chwili jest nietykalny. To jedyny wyjątek i wynika
   * z tego, co archiwum ma chronić: bieżący lot jest ważniejszy niż wczorajszy.
   */
  sprzataj() {
    if (!this.wlaczone) return null;

    const granicaCzasu = Date.now() - this.trzymajDni * 24 * 3600 * 1000;
    const limitBajtow = this.limitGb * 1024 * 1024 * 1024;
    const pliki = this.pliki().filter((p) => p.sciezka !== this.plikBiezacy);
    let skasowane = 0;
    let odzyskane = 0;

    const skasuj = (p) => {
      try {
        unlinkSync(p.sciezka);
        skasowane += 1;
        odzyskane += p.bajtow;
        return true;
      } catch (e) {
        rejestr.ostrzezenie("archiwum", `nie mogę skasować ${p.nazwa}`, { blad: e.message });
        return false;
      }
    };

    const zostaly = [];
    for (const p of pliki) {
      if (p.czas < granicaCzasu) skasuj(p);
      else zostaly.push(p);
    }

    let suma = zostaly.reduce((a, p) => a + p.bajtow, 0);
    for (const p of zostaly) {
      if (suma <= limitBajtow) break;
      if (skasuj(p)) suma -= p.bajtow;
    }

    if (skasowane) {
      rejestr.info(
        "archiwum",
        `sprzątanie: skasowano ${skasowane} plików, ${(odzyskane / 1e6).toFixed(0)} MB`
      );
    }
    this.ostatnieSprzatanie = Date.now();
    return { skasowane, odzyskane, zajete: suma };
  }

  async wolneMiejsce() {
    try {
      const s = await statfs(this.katalog);
      return { wolneBajtow: s.bavail * s.bsize, calosc: s.blocks * s.bsize };
    } catch {
      // statfs jest od Node 18.15 i nie na każdym systemie plików działa.
      // Brak tej liczby nie jest awarią — sprzątanie po limicie działa bez niej.
      return { wolneBajtow: null, calosc: null };
    }
  }

  // ---- podgląd stanu dla panelu admina ----

  async stan(ile = 20) {
    const pliki = this.pliki();
    const zajete = pliki.reduce((a, p) => a + p.bajtow, 0);
    const { wolneBajtow, calosc } = await this.wolneMiejsce();
    return {
      wlaczone: this.wlaczone,
      katalog: this.katalog,
      wideo: this.tryb,
      trzymajDni: this.trzymajDni,
      limitGb: this.limitGb,
      nagrywam: Boolean(this.strumien),
      biezacy: this.plikBiezacy
        ? { plik: this.plikBiezacy, ramek: this.ramekWPliku, bajtow: this.bajtowWPliku }
        : null,
      zajeteBajtow: zajete,
      wolneBajtow,
      dyskBajtow: calosc,
      ostatnieSprzatanie: this.ostatnieSprzatanie,
      // Najnowsze na górze — tego szuka się najczęściej.
      pliki: pliki
        .slice()
        .reverse()
        .slice(0, ile)
        .map((p) => ({ nazwa: p.nazwa, rodzaj: p.rodzaj, bajtow: p.bajtow, czas: p.czas })),
      plikow: pliki.length,
    };
  }
}
