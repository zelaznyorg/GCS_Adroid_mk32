// Hook podłączający element <video> do strumienia WHEP z MediaMTX.
// Pochodzi z projektu NRK (CameraTile.jsx) — wyciągnięty do osobnego pliku,
// bo tutaj obraz jest jeden i pełnoekranowy, a nie jeden z kafelków siatki.
//
// Dołożone wobec oryginału: wykrywanie przypadku "połączenie stoi, ale klatek nie ma".
// To sygnatura braku dekodera HEVC w przeglądarce (np. Firefox), a nie awarii łącza —
// i widz musi zobaczyć różnicę, zamiast w kółko oglądać "Brak sygnału".
// Patrz dok/SERWER_PODGLADU.md §4.
import { useEffect, useRef, useState } from "react";
import { WhepClient } from "./whep";
import { naglowekBasic } from "./sesja";

const PONOWIENIE_MS = 3000;
// Ile czekamy na pierwszą klatkę po zestawieniu połączenia, zanim uznamy,
// że przeglądarka nie umie zdekodować tego kodeka.
const CZAS_NA_KLATKE_MS = 6000;

export const STAN_OPIS = {
  laczenie: "ŁĄCZENIE…",
  zywo: "NA ŻYWO",
  ponawianie: "PONAWIANIE…",
  blad: "BRAK SYGNAŁU",
  brak_dekodera: "PRZEGLĄDARKA NIE ODTWARZA TEGO KODEKA",
  odmowa: "DOSTĘP DO OBRAZU ODEBRANY",
};

const MAPA_STANOW = {
  connecting: "laczenie",
  live: "zywo",
  reconnecting: "ponawianie",
  error: "blad",
};

export function useWhep(bazaWhep, idStrumienia, videoRef) {
  const [stan, setStan] = useState("laczenie");
  const timerKlatki = useRef(null);

  useEffect(() => {
    let przerwane = false;
    let timerPonowienia = null;
    let klient = null;
    const url = `${bazaWhep}/${idStrumienia}/whep`;

    const wyczyscTimerKlatki = () => {
      if (timerKlatki.current) clearTimeout(timerKlatki.current);
      timerKlatki.current = null;
    };

    const zaplanujPonowienie = () => {
      if (przerwane || timerPonowienia) return;
      timerPonowienia = setTimeout(() => {
        timerPonowienia = null;
        polacz();
      }, PONOWIENIE_MS);
    };

    // Po zestawieniu połączenia sprawdzamy, czy naprawdę lecą klatki.
    // Brak klatek przy zdrowym połączeniu = przeglądarka nie ma dekodera.
    const pilnujKlatek = () => {
      wyczyscTimerKlatki();
      timerKlatki.current = setTimeout(() => {
        if (przerwane) return;
        const v = videoRef.current;
        const klatki = v?.getVideoPlaybackQuality?.().totalVideoFrames ?? v?.webkitDecodedFrameCount ?? 0;
        if (!klatki) setStan("brak_dekodera");
      }, CZAS_NA_KLATKE_MS);
    };

    const polacz = async () => {
      if (przerwane) return;
      if (klient) {
        try { await klient.stop(); } catch { /* nieistotne */ }
      }
      klient = new WhepClient(url, videoRef.current, {
        autoryzacja: naglowekBasic(),
        onState: (s) => {
          if (przerwane) return;
          const nasz = MAPA_STANOW[s] ?? "laczenie";
          setStan(nasz);
          if (nasz === "zywo") pilnujKlatek();
        },
        onFatal: zaplanujPonowienie,
      });
      try {
        await klient.start();
      } catch (e) {
        if (przerwane) return;
        if (e && e.odmowa) {
          // Nie ponawiamy — dostęp wróci dopiero z decyzją administratora.
          setStan("odmowa");
          return;
        }
        setStan("blad");
        zaplanujPonowienie();
      }
    };

    polacz();
    return () => {
      przerwane = true;
      wyczyscTimerKlatki();
      if (timerPonowienia) clearTimeout(timerPonowienia);
      if (klient) klient.stop();
    };
  }, [bazaWhep, idStrumienia, videoRef]);

  return stan;
}
