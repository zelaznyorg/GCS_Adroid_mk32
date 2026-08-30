// Zachowanie ekranu telefonu: nie gaśnie w trakcie oglądania, a na żądanie
// przechodzi w tryb kinowy — pełny ekran w poziomie.
//
// Oba mechanizmy są przeglądarkowe i oba mogą być niedostępne. Zwracamy więc
// nie tylko stan, ale i `dostepny` — ekran ma pokazać różnicę między „trzyma"
// a „ta przeglądarka tego nie ma", zamiast milczeć (zasada 6, dok/UI.md).
import { useCallback, useEffect, useState } from "react";

// ---------------------------------------------------------------------------
// Ekran nie gaśnie
//
// Telefon leżący na masce samochodu gasi ekran po 30 s, bo nikt go nie dotyka —
// a widz patrzy. Screen Wake Lock trzyma podświetlenie, dopóki karta jest na
// wierzchu. System zwalnia blokadę SAM przy przejściu w tło i po powrocie
// trzeba ją wziąć jeszcze raz; stąd nasłuch na visibilitychange.
//
// ⚠ API wymaga bezpiecznego kontekstu (HTTPS albo localhost). Stacja chodzi po
//    http://, więc na telefonie w tunelu `dostepny` będzie false. To nie awaria,
//    tylko cena decyzji z dok/SERWER_PODGLADU.md §9 — opis w dok/TELEFON.md §4.
export function useBudzik(aktywny) {
  const dostepny = typeof navigator !== "undefined" && "wakeLock" in navigator;
  const [zajeta, setZajeta] = useState(false);
  // Wynik wyliczamy, zamiast zerować stan przy każdym wyłączeniu — inaczej sam
  // przejazd `aktywny` przez false wymuszałby dodatkowe przejście przez render.
  const trzyma = zajeta && dostepny && aktywny;

  useEffect(() => {
    if (!dostepny || !aktywny) return;
    let blokada = null;
    let zamkniete = false;

    const wez = async () => {
      if (zamkniete || document.visibilityState !== "visible" || blokada) return;
      try {
        blokada = await navigator.wakeLock.request("screen");
        if (zamkniete) {
          blokada.release().catch(() => {});
          blokada = null;
          return;
        }
        setZajeta(true);
        blokada.addEventListener("release", () => {
          blokada = null;
          setZajeta(false);
        });
      } catch {
        // Najczęstsza przyczyna to oszczędzanie energii albo brak bezpiecznego
        // kontekstu. Nie ponawiamy w pętli — kolejna próba przy powrocie karty.
        setZajeta(false);
      }
    };

    const naWidocznosc = () => {
      if (document.visibilityState === "visible") wez();
    };

    wez();
    document.addEventListener("visibilitychange", naWidocznosc);
    return () => {
      zamkniete = true;
      document.removeEventListener("visibilitychange", naWidocznosc);
      if (blokada) blokada.release().catch(() => {});
      setZajeta(false);
    };
  }, [dostepny, aktywny]);

  return { trzyma, dostepny };
}

// ---------------------------------------------------------------------------
// Tryb kinowy
//
// Pełny ekran plus obrót w poziom. Blokada orientacji działa w przeglądarce
// WYŁĄCZNIE gdy dokument jest już na pełnym ekranie — dlatego kolejność jest
// sztywna: najpierw requestFullscreen, potem orientation.lock.
//
// iPhone nie ma pełnego ekranu dla zwykłych elementów (tylko dla <video>),
// więc `dostepny` będzie tam false i przycisku nie pokazujemy. Na iOS ten sam
// efekt daje obrót telefonu w poziom — układ przełącza się sam, po orientacji.
export function usePelnyEkran(ref) {
  const dostepny =
    typeof document !== "undefined" &&
    Boolean(document.fullscreenEnabled || document.webkitFullscreenEnabled);
  const [wlaczony, setWlaczony] = useState(false);

  useEffect(() => {
    const odswiez = () =>
      setWlaczony(Boolean(document.fullscreenElement || document.webkitFullscreenElement));
    document.addEventListener("fullscreenchange", odswiez);
    document.addEventListener("webkitfullscreenchange", odswiez);
    odswiez();
    return () => {
      document.removeEventListener("fullscreenchange", odswiez);
      document.removeEventListener("webkitfullscreenchange", odswiez);
    };
  }, []);

  const przelacz = useCallback(async () => {
    const el = ref.current;
    if (!el) return;
    const wPelnym = Boolean(document.fullscreenElement || document.webkitFullscreenElement);

    if (wPelnym) {
      try {
        // Odblokowanie orientacji przed wyjściem — po wyjściu ekran już nie jest
        // nasz i część przeglądarek zgłasza wtedy błąd zamiast po prostu zwolnić.
        screen.orientation?.unlock?.();
      } catch { /* nie każda przeglądarka to ma */ }
      try {
        await (document.exitFullscreen?.() ?? document.webkitExitFullscreen?.());
      } catch { /* użytkownik mógł już wyjść klawiszem Esc */ }
      return;
    }

    try {
      await (el.requestFullscreen?.() ?? el.webkitRequestFullscreen?.());
    } catch {
      return;   // odmowa pełnego ekranu — obracanie nie ma już sensu
    }
    try {
      await screen.orientation?.lock?.("landscape");
    } catch {
      // Na tablecie i na biurku blokada orientacji nie istnieje albo jest zabroniona.
      // Pełny ekran i tak zadziałał, więc to nie jest powód do komunikatu.
    }
  }, [ref]);

  return { wlaczony, dostepny, przelacz };
}
