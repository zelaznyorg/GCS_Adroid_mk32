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
//            panorama-gcs (ten moduł)
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

/**
 * Ile czekamy na potwierdzenie, że most oddał nam pokrętło.
 *
 * ⛔ Most ODMAWIA bez słowa. Prośbę o ognisko odrzuca, gdy pokrętło trzyma ktoś
 * inny (zwykle pulpit), i nie odsyła przy tym niczego — jedyny ślad zostaje
 * w dzienniku panelu: „klient-2 prosi o pokrętło zajęte przez pulpit — odmawiam"
 * (GSB 2026-09-22). Bez tego licznika serwer meldował stronie przejęcie, którego
 * nie było: wskaźnik świecił POKRĘTŁO, a obroty szły do przykrytego pulpitu, który
 * przesuwał kafelki i uruchamiał programy.
 *
 * Potwierdzeniem jest wiadomość `{"typ":"ognisko","gdzie":"pulpit"}` — most wysyła
 * ją przy każdej zmianie właściciela, każdemu klientowi jego własną prawdę.
 */
const POTWIERDZENIE_MS = Number(process.env.POKRETLO_POTWIERDZENIE_MS) || 400;
/**
 * Ile razy ponowimy prośbę, zanim powiemy operatorowi, że pokrętła nie dostaliśmy.
 * Pulpit ustępuje je JEDNORAZOWO, w chwili uruchamiania aplikacji z kafelka, więc
 * prośba wysłana ułamek sekundy za wcześnie przepada — i to ponowienie ją ratuje.
 * Po wyczerpaniu prób milkniemy: każda kolejna to wpis w dzienniku panelu.
 */
const PONOWIEN_PROSBY = 2;
const ODSTEP_PROSBY_MS = Number(process.env.POKRETLO_ODSTEP_MS) || 1500;

const PANEL = "panel";
const PULPIT = "pulpit";

export class Pokretlo extends EventEmitter {
  constructor(sciezka = GNIAZDO) {
    super();
    this.sciezka = sciezka;
    this.gniazdo = null;
    this.bufor = "";
    this.polaczone = false;
    this.ognisko = "panel";
    this.timerPonowienia = null;
    // Trwające staranie o ognisko: czekanie na potwierdzenie albo odstęp przed ponowieniem.
    this.staranie = null;
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
    this.przerwijStaranie();
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
      // Dostaliśmy, o co prosiliśmy — nie ma po co ponawiać.
      if (w.gdzie === PULPIT) this.przerwijStaranie();
      // `mamy` mówi stronie wprost, czy pokrętło jest U NAS. Samo `gdzie` tego nie
      // niesie czytelnie: most nazywa klienta z pokrętłem „pulpitem", a to słowo
      // w tym projekcie znaczy też sąsiednią aplikację.
      this.emit("zdarzenie", { typ: "ognisko", gdzie: w.gdzie, mamy: w.gdzie === PULPIT });
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

  /** Czy pokrętło jest w tej chwili u nas. */
  get mamyOgnisko() {
    return this.polaczone && this.ognisko === PULPIT;
  }

  przerwijStaranie() {
    if (this.staranie) clearTimeout(this.staranie);
    this.staranie = null;
  }

  /**
   * Prosi o pokrętło dla siebie i SPRAWDZA, czy je dostała.
   *
   * Odmowa nie wraca żadną wiadomością (patrz POTWIERDZENIE_MS), więc jedyne, co
   * możemy zrobić, to odczekać na potwierdzenie i po kilku próbach powiedzieć
   * stronie prawdę. Prawda brzmi: pokrętłem steruje w tej chwili pulpit, a drogą
   * wyjścia jest jego kafelek STERUJ.
   */
  wezOgnisko(proba = 0) {
    this.przerwijStaranie();
    // ⛔ ŻADNEGO wyjścia na skróty przy `ognisko === "pulpit"`. Ten stan bywa
    // NIEAKTUALNY: gdy strumień strony zerwie się i wstanie w ciągu milisekund,
    // nasze „oddaję" jest już w drodze do mostu, a my jeszcze myślimy, że mamy
    // pokrętło — i nie prosimy o nie ponownie. Skutek zmierzony na GSB
    // 2026-09-22: po takim przeplocie ognisko zostawało przy panelu, a strona
    // czekała w nieskończoność. Ponowna prośba przy pokrętle, które i tak mamy,
    // nic nie kosztuje: most odpowiada na nią zgodą bez zmiany właściciela.
    if (!this.wyslij({ cmd: "ognisko", gdzie: PULPIT })) return false;
    this.staranie = setTimeout(() => {
      this.staranie = null;
      if (this.ognisko === PULPIT) return;
      if (proba < PONOWIEN_PROSBY) {
        this.staranie = setTimeout(() => this.wezOgnisko(proba + 1), ODSTEP_PROSBY_MS);
        this.staranie.unref?.();
        return;
      }
      rejestr.ostrzezenie(
        "pokretlo",
        "most nie oddał pokrętła — trzyma je pulpit; wyjście przez kafelek STERUJ na pulpicie"
      );
      this.emit("zdarzenie", { typ: "ognisko", gdzie: this.ognisko, mamy: false, odmowa: true });
    }, POTWIERDZENIE_MS);
    this.staranie.unref?.();
    return true;
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
    this.przerwijStaranie();
    return this.wyslij({ cmd: "ognisko", gdzie: "inny" });
  }

  /** Oddaje pokrętło panelowi. Wołane zawsze, gdy strona przestaje go trzymać. */
  oddajOgnisko() {
    this.przerwijStaranie();
    return this.wyslij({ cmd: "ognisko", gdzie: PANEL });
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
      mamy: this.mamyOgnisko,
      zdarzen: this.zdarzen,
      trzyma: Boolean(this.trzymajacy),
    };
  }
}
