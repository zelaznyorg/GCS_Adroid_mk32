// Odbiór obrazu z ekranu aparatury — wejście dla naszego APK na kontrolerze DJI.
//
// ### Po co to jest
//
// Mavic 3 Pro nie odda telemetrii żadną drogą (dok/DJI.md §1), a jego natywna
// transmisja RTMP wymaga podpiętego mikrofonu i nie niesie nakładki OSD w postaci
// danych. Zrzut ekranu aparatury obchodzi obie te sprawy naraz: bierze **to, co
// widzi operator** — obraz razem z całą nakładką.
//
// ### Dlaczego surowy H.264 po TCP, a nie RTMP z Androida
//
// RTMP z telefonu znaczy własny handshake, chunkowanie i muxer FLV — kilkaset linii
// protokołu na urządzeniu, którego nie mamy pod ręką do debugowania. Tutaj aparatura
// wysyła **to, co wypluwa `MediaCodec`**, czyli strumień elementarny Annex-B, a całą
// resztę robi `ffmpeg`, który na stacji i tak jest. Po stronie APK zostaje jedno
// gniazdo i pętla zapisu.
//
//   APK na aparaturze ──H.264 po TCP──► ten moduł ──ffmpeg -c copy──► RTMP :1935 ──► MediaMTX
//
// `-c copy` znaczy **bez przekodowania**: procesor stacji tego nie dotyka, tak samo
// jak przy strumieniu z kamery ZR30.
//
// ### ⛔ Ryzyko, które rozstrzyga się dopiero na sprzęcie
//
// Jeśli DJI oznacza swój podgląd wideo jako `FLAG_SECURE`, `MediaProjection` odda
// **czarny prostokąt** zamiast obrazu — i nie da się tego obejść z aplikacji.
// Dlatego pilnujemy przepływności: czerń koduje się prawie darmo, więc spadek do
// jednostek kb/s stawia w dzienniku jednoznaczne ostrzeżenie zamiast ciszy i domysłów
// (`sprawdzPrzeplywnosc`). To podejrzenie, nie dowód — rozstrzyga spojrzenie na obraz.
import { createServer } from "node:net";
import { spawn } from "node:child_process";
import * as rejestr from "./rejestr.mjs";

export const PORT_ZRZUTU = Number(process.env.ZRZUT_PORT) || 5601;

/** Ile bajtów nagłówka najwyżej czytamy, zanim uznamy to za śmieci. */
const MAKS_NAGLOWEK = 512;
const CZAS_NAGLOWKA_MS = 5000;
const MAKS_OCZEKUJACYCH = 16;

/**
 * Poniżej tylu kb/s uznajemy obraz za podejrzanie pusty. Zmierzone: czarny ekran
 * 1080p30 w H.264 schodzi do jednostek kb/s, a zwykły zrzut ekranu z mapą i HUD-em
 * DJI trzyma setki kb/s nawet przy nieruchomym obrazie.
 */
const PROG_PUSTEGO_KBS = 20;

/**
 * Nadawca, który milczy dłużej, jest martwy — gniazdo TCP potrafi wisieć jako
 * ESTABLISHED godzinami po tym, jak aparatura zmieniła sieć albo aplikacja padła.
 * Zmierzone 2026-09-05: pierwsze połączenie z 14:12 wisiało bez danych, a każde
 * następne z tego samego kontrolera dostawało „odrzucony drugi nadawca" — 20 razy
 * w 3 minuty, aż do restartu serwera.
 *
 * ⚠ Nieruchomy ekran TEŻ jest ciszą: koder MediaCodec karmiony powierzchnią dostaje
 * klatkę tylko wtedy, gdy ekran się zmieni. Zmierzone tego samego dnia: pulpit
 * kontrolera bez ruchu = 0 bajtów, MediaMTX po 10 s zrzucał nadawcę RTMP
 * (`closed: i/o timeout`), ffmpeg padał, a aplikacja „nadawała" w próżnię. Dlatego
 * próg jest długi (60 s, spójny z `readTimeout` MediaMTX), a właściwa naprawa siedzi
 * w Horyzoncie: koder ma powtarzać ostatnią klatkę (KEY_REPEAT_PREVIOUS_FRAME_AFTER).
 */
const CISZA_ZERWANIA_MS = 60000;
/** Nowy nadawca z poprawnym hasłem przejmuje, gdy dotychczasowy milczy co najmniej tyle. */
const CISZA_PRZEJECIA_MS = 2000;

export class OdbiorZrzutu {
  /**
   * @param {(haslo: string) => ({id: string, haslo: string} | null)} rozpoznaj — po haśle
   *        z nagłówka mówi, KTÓRE źródło nadawane nadaje (nadawanie.zrodloPoHasle).
   *        Aparatura nie podaje ścieżki: zna tylko swoje hasło, a to hasło jest
   *        tożsamością drona — jeden odbiornik obsługuje wszystkie drony DJI.
   *   i przy DJI Cloud API. Jedno hasło, jedno miejsce do wymiany.
   */
  constructor(rozpoznaj) {
    this.rozpoznaj = rozpoznaj;
    // Ustawiane przy każdym połączeniu z tego, kto się zgłosił.
    this.sciezka = null;
    this.hasloZrodla = null;
    this.serwer = null;
    this.polaczenie = null;
    this.ffmpeg = null;
    this.ostatniaKlatka = 0;
    this.bajtow = 0;
    this.czarny = false;
    this.wOknie = 0;
    this.oknoOd = 0;
    this.zegar = null;
    this.oczekujace = new Set();
  }

  start() {
    this.serwer = createServer((gniazdo) => this.przyjmij(gniazdo));
    this.serwer.on("error", (e) =>
      rejestr.ostrzezenie("zrzut", "nasłuch zrzutu ekranu nie wstał", { blad: e.message })
    );
    this.serwer.listen(PORT_ZRZUTU, () =>
      rejestr.info("zrzut", `nasłuch obrazu z aparatury na :${PORT_ZRZUTU}`)
    );
  }

  stop() {
    for (const gniazdo of this.oczekujace) gniazdo.destroy();
    this.rozlacz("zatrzymanie serwera");
    this.serwer?.close();
  }

  przyjmij(gniazdo) {
    const skad = gniazdo.remoteAddress || "?";
    let limitCzasu;
    const zwolnijOczekiwanie = () => {
      clearTimeout(limitCzasu);
      this.oczekujace.delete(gniazdo);
    };
    // Obce lub stare gniazdo nie może zakończyć aktualnego strumienia.
    gniazdo.on("error", () => gniazdo.destroy());
    gniazdo.on("close", () => {
      zwolnijOczekiwanie();
      if (this.polaczenie === gniazdo) this.rozlacz("nadawca się rozłączył");
    });

    // ⛔ Jeden nadawca naraz. Dwa strumienie pod tę samą ścieżkę dałyby przeplot
    // klatek z dwóch źródeł — obraz nie do oglądania, a przyczyna nieoczywista.
    // Ale odrzucamy tylko wtedy, gdy obecny nadawca NAPRAWDĘ nadaje. Milczący
    // dostaje szansę na przejęcie po sprawdzeniu hasła (niżej, w nagłówku).
    if (this.polaczenie && this.ciszaNadawcy() < CISZA_PRZEJECIA_MS) {
      rejestr.ostrzezenie("zrzut", `odrzucony drugi nadawca (${skad}) — ${this.sciezka} nadaje żywo`);
      gniazdo.end('{"blad":"zajete"}\n');
      return;
    }
    if (this.oczekujace.size >= MAKS_OCZEKUJACYCH) {
      gniazdo.destroy();
      return;
    }
    this.oczekujace.add(gniazdo);
    // Limit całkowity, nie odnawiany pojedynczym bajtem od powolnego klienta.
    limitCzasu = setTimeout(() => gniazdo.destroy(), CZAS_NAGLOWKA_MS);
    limitCzasu.unref?.();

    let bufor = Buffer.alloc(0);
    let wpuszczony = false;

    const naglowek = (kawalek) => {
      const nowyKoniec = kawalek.indexOf(0x0a);
      const rozmiar = bufor.length + (nowyKoniec < 0 ? kawalek.length : nowyKoniec);
      if (rozmiar > MAKS_NAGLOWEK) {
        gniazdo.destroy();
        return;
      }
      bufor = Buffer.concat([bufor, kawalek]);
      const koniec = bufor.indexOf(0x0a); // nagłówek kończy się znakiem nowej linii
      if (koniec < 0) {
        if (bufor.length > MAKS_NAGLOWEK) {
          rejestr.ostrzezenie("zrzut", `nadawca (${skad}) nie przysłał nagłówka — rozłączam`);
          gniazdo.destroy();
        }
        return;
      }
      let n;
      try {
        n = JSON.parse(bufor.subarray(0, koniec).toString("utf8"));
      } catch {
        rejestr.ostrzezenie("zrzut", `nieczytelny nagłówek od ${skad}`);
        gniazdo.destroy();
        return;
      }
      if (!n || typeof n !== "object" || Array.isArray(n) || typeof n.haslo !== "string") {
        gniazdo.destroy();
        return;
      }
      const zrodlo = this.rozpoznaj(String(n.haslo || ""));
      if (!zrodlo) {
        rejestr.ostrzezenie("zrzut", `hasło od ${skad} nie pasuje do żadnego źródła nadawanego`);
        // Jedna linia odpowiedzi, żeby aparatura mogła powiedzieć „złe hasło" zamiast
        // „stacja nie odpowiada" — bez niej zamknięcie gniazda wygląda jak brak łącza.
        gniazdo.end('{"blad":"zle-haslo"}\n');
        return;
      }
      // Kilka gniazd mogło czekać na hasło przed startem pierwszego nadawcy —
      // albo poprzedni nadawca wisi martwy. Żywego nie ruszamy, martwego zastępujemy.
      if (this.polaczenie) {
        const cisza = this.ciszaNadawcy();
        if (cisza < CISZA_PRZEJECIA_MS) {
          gniazdo.end('{"blad":"zajete"}\n');
          return;
        }
        rejestr.info("zrzut", `nowy nadawca (${skad}) przejmuje — poprzedni milczy od ${Math.round(cisza / 1000)} s`);
        this.rozlacz("zastąpiony przez nowego nadawcę");
      }
      zwolnijOczekiwanie();
      this.sciezka = zrodlo.id;
      this.hasloZrodla = zrodlo.haslo;

      const fps = Math.min(60, Math.max(1, Number(n.fps) || 30));
      rejestr.info("zrzut", `aparatura zaczyna nadawać ekran (${skad})`, {
        zrodlo: this.sciezka,
        rozmiar: `${n.szer || "?"}x${n.wys || "?"}`,
        fps,
      });

      wpuszczony = true;
      this.polaczenie = gniazdo;
      this.ostatniaKlatka = Date.now();
      this.wOknie = 0;
      this.oknoOd = Date.now();
      this.zegar = setInterval(() => this.sprawdzPrzeplywnosc(), 5000);
      this.zegar.unref?.();
      this.uruchomFfmpeg(fps);

      // Reszta bufora to już obraz.
      const ogon = bufor.subarray(koniec + 1);
      if (ogon.length) this.doFfmpeg(ogon);
      bufor = Buffer.alloc(0);
    };

    gniazdo.on("data", (kawalek) => {
      if (gniazdo.destroyed) return;
      if (!wpuszczony) naglowek(kawalek);
      else this.doFfmpeg(kawalek);
    });
  }

  doFfmpeg(dane) {
    this.bajtow += dane.length;
    this.wOknie += dane.length;
    this.ostatniaKlatka = Date.now();
    this.ffmpeg?.stdin.write(dane, () => {});
  }

  /**
   * Czy obraz jest podejrzanie „pusty".
   *
   * ### ⛔ Po co to jest
   *
   * Jeśli DJI oznacza swój podgląd jako `FLAG_SECURE`, `MediaProjection` odda czarny
   * prostokąt. Czerń koduje się prawie darmo, więc **przepływność spada do kilku
   * kb/s** — i to jest sygnał, który da się odczytać bez dekodowania obrazu.
   *
   * ⚠ To jest PODEJRZENIE, nie dowód. Nieruchomy ciemny ekran wygląda tak samo.
   * Rozstrzyga spojrzenie na obraz w przeglądarce — stąd ostrzeżenie mówi wprost,
   * czego szukać, zamiast twierdzić, że wie.
   */
  /** Ile ms minęło od ostatnich danych obecnego nadawcy (Infinity, gdy nikt nie nadaje). */
  ciszaNadawcy() {
    return this.polaczenie ? Date.now() - this.ostatniaKlatka : Infinity;
  }

  sprawdzPrzeplywnosc() {
    const teraz = Date.now();
    // Strażnik martwego nadawcy — patrz CISZA_ZERWANIA_MS.
    if (this.polaczenie && teraz - this.ostatniaKlatka > CISZA_ZERWANIA_MS) {
      rejestr.ostrzezenie(
        "zrzut",
        `brak danych od aparatury od ${Math.round((teraz - this.ostatniaKlatka) / 1000)} s — zamykam, żeby mogła wrócić`
      );
      this.rozlacz("cisza nadawcy");
      return;
    }
    const sekund = (teraz - this.oknoOd) / 1000;
    if (sekund < 5) return;
    const kbs = (this.wOknie * 8) / 1000 / sekund;
    this.wOknie = 0;
    this.oknoOd = teraz;
    const pusty = kbs < PROG_PUSTEGO_KBS;
    if (pusty && !this.czarny) {
      this.czarny = true;
      rejestr.ostrzezenie(
        "zrzut",
        `obraz z aparatury niesie tylko ${kbs.toFixed(1)} kb/s — prawdopodobnie CZARNY ` +
          "(DJI blokuje zrzut ekranu, FLAG_SECURE). Sprawdź podgląd okiem."
      );
    } else if (!pusty && this.czarny) {
      this.czarny = false;
      rejestr.info("zrzut", `obraz wrócił (${Math.round(kbs)} kb/s)`);
    }
  }

  uruchomFfmpeg(fps) {
    this.czarny = false;
    const cel = `rtmp://127.0.0.1:1935/${this.sciezka}?user=dji&pass=${this.hasloZrodla}`;
    const argumenty = [
      "-hide_banner", "-loglevel", "info",
      // Wejście: goły strumień H.264. Znaczników czasu w nim nie ma. Tempo z nagłówka
      // jest tylko ZAPASEM — koder na aparaturze karmiony powierzchnią wydaje klatkę
      // przy każdej zmianie ekranu (do 60/s), a przy bezruchu powtarza ostatnią, więc
      // rzeczywiste tempo jest zmienne. Sztywne stemplowanie „15 kl./s" dawało obraz,
      // który raz przyspieszał, raz zamierał (Tom, 2026-09-05: „nie jest płynny").
      // Znacznikiem jest więc chwila PRZYJŚCIA klatki — z dokładnością do drgań
      // sieci lokalnej, których odtwarzacz i tak wygładza buforem.
      "-use_wallclock_as_timestamps", "1",
      "-f", "h264", "-framerate", String(fps), "-i", "pipe:0",
      // ⛔ Żadnych filtrów przy `-c copy` — to się wyklucza. Pierwsza wersja miała tu
      // `-vf blackdetect` do wykrywania `FLAG_SECURE` i ffmpeg wysypywał się na
      // starcie (`Filtergraph … with -c copy`, wyjście −22). Zmierzone, nie wywnioskowane.
      // Czerń wykrywamy teraz po przepływności (`podejrzenieCzerni`) — bez dekodowania,
      // bo RPi 5 nie ma sprzętowego dekodera H.264 i płaciłby za to procesorem.
      "-c", "copy", "-f", "flv", cel,
    ];
    const p = spawn("ffmpeg", argumenty, { stdio: ["pipe", "ignore", "pipe"] });
    this.ffmpeg = p;

    p.stderr.on("data", (b) => {
      const s = b.toString();
      if (/error|Invalid|failed/i.test(s)) {
        rejestr.ostrzezenie("zrzut", "ffmpeg zgłasza problem", { tresc: s.trim().slice(0, 200) });
      }
    });

    p.on("exit", (kod) => {
      rejestr.info("zrzut", `przepakowywanie zakończone (kod ${kod})`);
      if (this.ffmpeg !== p) return;
      this.ffmpeg = null;
      // ⛔ ffmpeg padł, a aparatura dalej nadaje w próżnię: gniazdo żyje, dane lecą
      // w `this.ffmpeg?.stdin` = nic, panel mówi „nadaje", widz ma czarny kafelek.
      // Rozłączamy — aplikacja ponawia sama i zaczyna od świeżej klatki kluczowej
      // (SPS/PPS/IDR), czego nowy ffmpeg w środku strumienia by nie dostał.
      if (this.polaczenie) this.rozlacz(`ffmpeg zakończył się w trakcie nadawania (kod ${kod})`);
    });
    // ⛔ Bez tego zerwane gniazdo przewraca proces: `write` po zamkniętym stdin
    // podnosi EPIPE, którego nikt nie łapie.
    p.stdin.on("error", () => {});
  }

  rozlacz(powod) {
    if (!this.polaczenie && !this.ffmpeg) return;
    rejestr.info("zrzut", `koniec nadawania z aparatury (${powod})`, {
      odebrano_kb: Math.round(this.bajtow / 1024),
    });
    if (this.zegar) clearInterval(this.zegar);
    this.zegar = null;
    try {
      this.ffmpeg?.stdin.end();
    } catch { /* już zamknięte */ }
    this.ffmpeg?.kill("SIGTERM");
    this.ffmpeg = null;
    this.polaczenie?.destroy();
    this.polaczenie = null;
    this.bajtow = 0;
  }

  stan() {
    const od = this.ostatniaKlatka ? (Date.now() - this.ostatniaKlatka) / 1000 : null;
    return {
      port: PORT_ZRZUTU,
      nadaje: Boolean(this.polaczenie),
      sekund_od_klatki: od,
      odebrano_kb: Math.round(this.bajtow / 1024),
      obraz_czarny: this.czarny,
      sciezka: this.sciezka,
    };
  }
}
