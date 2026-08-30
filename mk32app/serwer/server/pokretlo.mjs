// Pokrętło stacji — most do panelu GC9A01 z `PI5setup full`.
//
// ### Skąd to się bierze
//
// Przy stacji **nie ma myszy ani klawiatury**. Jest okrągły wyświetlacz GC9A01
// i enkoder obrotowy, obsługiwane przez `pi5-control-panel`. Linie GPIO enkodera
// są zajmowane na wyłączność, więc pokrętło ma **jednego właściciela** — panel —
// a ten rozgłasza zdarzenia gniazdem UNIX każdemu, kto się zgłosi
// (`/opt/pi5setup-full/src/gcs_most.py`).
//
// Ten moduł jest takim klientem. Nie przejmuje pokrętła, tylko prosi o nie.
//
// ```
//   enkoder → panel GC9A01 (właściciel GPIO)
//                  │  /run/gcs/pokretlo.sock, JSON po linii
//                  ▼
//            dron15-gcs (ten moduł)
//                  │  SSE /api/pokretlo
//                  ▼
//            przeglądarka — obrót przesuwa ognisko, klik naciska
// ```
//
// ### Protokół — przepisany z ich modułu, nie wymyślony
//
//   panel → my    {"typ":"obrot","kierunek":±1}
//                 {"typ":"klik"}
//                 {"typ":"wcisniety"} / {"typ":"puszczony"}   surowy stan przycisku,
//                                                             przytrzymanie mierzymy sami
//                 {"typ":"polecenie","co":"nagrywanie"}
//                 {"typ":"ognisko","gdzie":"panel"|"pulpit"}
//   my → panel    {"cmd":"ognisko","gdzie":"pulpit"|"panel"}
//                 {"cmd":"stan","nagrywa":bool,"opis":"…"}
//                 {"cmd":"siec","lan":"…","wifi":"…","wan":"…"}
//
// ### ⛔ Ognisko nie może utknąć poza panelem
//
// To ich zasada bezpieczeństwa i przejmujemy ją bez zmian: **przy maszynie nie ma
// klawiatury**, więc pokrętło uwięzione w martwym pulpicie znaczy panel nie do
// obsłużenia. Gdy odchodzi ostatni klient, ich most sam oddaje ognisko panelowi —
// my dokładamy do tego swoje: oddajemy je jawnie, gdy przeglądarka puszcza pokrętło
// albo gdy zrywa się jej strumień.
import { connect } from "node:net";
import { EventEmitter } from "node:events";
import * as rejestr from "./rejestr.mjs";

export const GNIAZDO = process.env.GCS_GNIAZDO_POKRETLA || "/run/gcs/pokretlo.sock";
const PONOWIENIE_MS = 3000;

export class Pokretlo extends EventEmitter {
  constructor(sciezka = GNIAZDO) {
    super();
    this.sciezka = sciezka;
    this.gniazdo = null;
    this.bufor = "";
    this.polaczone = false;
    this.ognisko = "panel";
    this.timerPonowienia = null;
    this.zdarzen = 0;
    /**
     * Kto trzyma pokrętło. Pokrętło jest JEDNO i fizycznie stoi przy stacji, więc
     * rozsyłanie jego obrotów wszystkim widzom przestawiałoby ekrany ludziom,
     * którzy go nie dotykają. Trzyma je dokładnie jedno połączenie SSE.
     */
    this.trzymajacy = null;
  }

  start() {
    this.polacz();
  }

  stop() {
    if (this.timerPonowienia) clearTimeout(this.timerPonowienia);
    this.timerPonowienia = null;
    this.oddajOgnisko();
    this.gniazdo?.destroy();
    this.gniazdo = null;
  }

  polacz() {
    if (this.gniazdo) return;
    const s = connect(this.sciezka);
    this.gniazdo = s;

    s.on("connect", () => {
      this.polaczone = true;
      this.bufor = "";
      rejestr.info("pokretlo", `most z panelem GC9A01 zestawiony (${this.sciezka})`);
      this.emit("polaczenie", true);
      // Panel przy powitaniu sam przysyła, gdzie stoi ognisko — nie zgadujemy.
    });

    s.on("data", (kawalek) => {
      this.bufor += kawalek.toString("utf8");
      let i;
      while ((i = this.bufor.indexOf("\n")) >= 0) {
        const linia = this.bufor.slice(0, i).trim();
        this.bufor = this.bufor.slice(i + 1);
        if (linia) this.przyjmij(linia);
      }
      // Strumień śmieci bez znaku końca linii nie może rosnąć bez końca.
      if (this.bufor.length > 64 * 1024) this.bufor = "";
    });

    const rozlacz = (powod) => {
      if (!this.gniazdo) return;
      this.gniazdo = null;
      const bylo = this.polaczone;
      this.polaczone = false;
      this.ognisko = "panel";
      if (bylo) {
        rejestr.ostrzezenie("pokretlo", `most z panelem zerwany (${powod}) — ponawiam`);
        this.emit("polaczenie", false);
      }
      if (!this.timerPonowienia) {
        this.timerPonowienia = setTimeout(() => {
          this.timerPonowienia = null;
          this.polacz();
        }, PONOWIENIE_MS);
        this.timerPonowienia.unref?.();
      }
    };

    s.on("error", (e) => {
      // Brak gniazda to normalny stan na maszynie bez panelu (np. przy próbach
      // na Windows) — nie hałasujemy, po prostu ponawiamy w tle.
      if (this.polaczone) rejestr.ostrzezenie("pokretlo", "błąd gniazda pokrętła", { blad: e.message });
      rozlacz(e.code || e.message);
    });
    s.on("close", () => rozlacz("zamknięte"));
  }

  przyjmij(linia) {
    let w;
    try {
      w = JSON.parse(linia);
    } catch {
      return;
    }
    if (w.typ === "ognisko") {
      this.ognisko = w.gdzie;
      rejestr.info("pokretlo", `ognisko: ${w.gdzie}`);
      this.emit("zdarzenie", { typ: "ognisko", gdzie: w.gdzie });
      return;
    }
    this.zdarzen += 1;
    this.emit("zdarzenie", w);
  }

  wyslij(wiadomosc) {
    if (!this.gniazdo || !this.polaczone) return false;
    try {
      this.gniazdo.write(JSON.stringify(wiadomosc) + "\n");
      return true;
    } catch (e) {
      rejestr.ostrzezenie("pokretlo", "nie udało się odezwać do panelu", { blad: e.message });
      return false;
    }
  }

  /** Prosi panel o oddanie pokrętła stronie. */
  wezOgnisko() {
    return this.wyslij({ cmd: "ognisko", gdzie: "pulpit" });
  }

  /**
   * Przekazuje pokrętło sąsiadowi — u nas zawsze pulpitowi GCS.
   *
   * ### ⛔ To jest jedyne wyjście z pełnoekranowej strony
   *
   * Ich most rozsyła zdarzenia **wyłącznie właścicielowi** (`gcs_most.py`,
   * `rozglos`: „Wysyłanie tego wszystkim było przyczyną «klikam w aplikacji,
   * a pulpit uruchamia inne programy»"). Dopóki pokrętło trzyma strona, pulpit
   * nie dostaje ani jednego zdarzenia — więc jego przytrzymanie nie wywoła go
   * na wierzch, a kafelek `✕ ZAMKNIJ` jest nieosiągalny.
   *
   * Przy stacji nie ma klawiatury, a okno jest pełnoekranowe i bez ramki, więc
   * bez tego operator zostaje w aplikacji, z której nie ma jak wyjść.
   * Długie przytrzymanie oddaje więc pokrętło dalej — dokładnie tak, jak ich
   * własne „przytrzymanie = o krok wstecz".
   */
  przekazDalej() {
    return this.wyslij({ cmd: "ognisko", gdzie: "inny" });
  }

  /** Oddaje pokrętło panelowi. Wołane zawsze, gdy strona przestaje go trzymać. */
  oddajOgnisko() {
    return this.wyslij({ cmd: "ognisko", gdzie: "panel" });
  }

  /**
   * Melduje panelowi, co się dzieje z nagrywaniem — okrągły ekran ma pokazywać
   * prawdę, a nie własne domysły. To ich wymaganie z opisu protokołu.
   */
  meldujStan(nagrywa, opis) {
    return this.wyslij({ cmd: "stan", nagrywa: Boolean(nagrywa), opis: String(opis || "") });
  }

  meldujSiec({ lan, wifi, wan } = {}) {
    return this.wyslij({ cmd: "siec", lan: lan || "—", wifi: wifi || "—", wan: wan || "—" });
  }

  stan() {
    return {
      polaczone: this.polaczone,
      gniazdo: this.sciezka,
      ognisko: this.ognisko,
      zdarzen: this.zdarzen,
      trzyma: Boolean(this.trzymajacy),
    };
  }
}
