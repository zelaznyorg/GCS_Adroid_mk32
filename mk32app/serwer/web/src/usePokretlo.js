// Obsługa pokrętła stacji po stronie przeglądarki.
//
// ### Model obsługi — przepisany z panelu, nie wymyślony od nowa
//
// Operator zna już to pokrętło z okrągłego ekranu GC9A01: **obrót przechodzi
// między pozycjami, naciśnięcie wchodzi, przytrzymanie cofa**. Powielamy dokładnie
// ten model, żeby pamięć ruchowa przenosiła się bez uczenia się drugiego zwyczaju.
//
//   obrót          → przesuwa ognisko po elementach bieżącego widoku
//   klik           → naciska to, na czym stoi ognisko
//   klik na polu   → WCHODZI w nie (tryb zmiany wartości)
//   obrót w polu   → zmienia wartość, nie przesuwa ogniska
//   klik w polu    → zatwierdza i wraca do przechodzenia
//   przytrzymanie  → cofa: zamyka panel albo wychodzi z pola
//
// ### Czego to NIE robi
//
// **Nie odbiera niczego myszy ani dotykowi.** Pokrętło porusza zwykłym ogniskiem
// przeglądarki (`focus`) i naciska zwykłym `click()`. Każdy przycisk, lista i pole,
// które działa myszą, działa pokrętłem — i odwrotnie. Nie ma tu drugiej, równoległej
// mapy sterowania, która mogłaby się rozjechać z pierwszą.
//
// To jest też powód, dla którego nie budujemy własnej listy „pozycji menu":
// taka lista rozjeżdża się z interfejsem przy pierwszej zmianie układu, a wtedy
// pokrętło zaczyna wskazywać rzeczy, których już nie ma.
import { useCallback, useEffect, useRef, useState } from "react";
import { adresStacji, zeton } from "./sesja";
import { oznaczOgnisko, polePodmiany, elementy, obszar, przesunOgnisko } from "./ogniskowanie";

/** Ile trwa „przytrzymanie". Krócej myli się z klikiem, dłużej irytuje. */
const PRZYTRZYMANIE_MS = 600;

/**
 * Powyżej tego progu przytrzymanie należy do PANELU, nie do nas.
 *
 * Panel GC9A01 odbiera pokrętło po długim przytrzymaniu — w jego logu:
 * `Dlugie przytrzymanie (2.8 s) — pokretlo wraca do panelu`. Gdybyśmy zrobili
 * wtedy swoje „cofnij", oddanie pokrętła zamykałoby przy okazji otwarty panel,
 * czyli jeden ruch palca robiłby dwie niepowiązane rzeczy.
 */
const PRZEJECIE_PANELU_MS = 2000;

export function usePokretlo({ wlaczone = true, zetonWidza = null } = {}) {
  const [polaczone, setPolaczone] = useState(false);
  const [blad, setBlad] = useState(null);
  const [wRegulacji, setWRegulacji] = useState(null); // "lista" | "liczba" | "tekst" | null
  const wcisnietyOd = useRef(0);
  const przytrzymane = useRef(false);
  const trybRef = useRef(null);

  useEffect(() => { trybRef.current = wRegulacji; }, [wRegulacji]);

  // ---- ruchy ----

  const przesun = useCallback((krok) => {
    // `pomijajZablokowane` trzyma pokrętło z dala od własnego wyłącznika.
    przesunOgnisko(krok, { pomijajZablokowane: true });
  }, []);

  const zmienWartosc = useCallback((krok) => {
    const el = document.activeElement;
    const rodzaj = polePodmiany(el);
    if (rodzaj === "lista") {
      const n = el.options.length;
      if (!n) return;
      el.selectedIndex = (el.selectedIndex + krok + n) % n;
      el.dispatchEvent(new Event("change", { bubbles: true }));
    } else if (rodzaj === "liczba") {
      const kroczek = Number(el.step) || 1;
      const teraz = Number(el.value) || 0;
      let v = teraz + krok * kroczek;
      if (el.min !== "") v = Math.max(Number(el.min), v);
      if (el.max !== "") v = Math.min(Number(el.max), v);
      // React nie zauważy `el.value = …`, bo podmienia setter na własny.
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
      setter.call(el, String(v));
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
    }
  }, []);

  const cofnij = useCallback(() => {
    if (trybRef.current) {
      setWRegulacji(null);
      document.activeElement?.blur?.();
      return;
    }
    // Poza polem przytrzymanie zamyka panel — tak samo jak Escape.
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  }, []);

  const nacisnij = useCallback(() => {
    const el = document.activeElement;
    const rodzaj = polePodmiany(el);
    if (trybRef.current) {
      // Drugi klik zatwierdza i wraca do przechodzenia.
      setWRegulacji(null);
      return;
    }
    if (rodzaj) {
      setWRegulacji(rodzaj);
      return;
    }
    if (el && el !== document.body) {
      el.click();
      // Po kliknięciu widok często się zmienia (otwiera się panel), więc ognisko
      // trzeba postawić na nowo — inaczej pierwszy obrót w nowym widoku skakałby
      // od przypadkowego miejsca.
      setTimeout(() => {
        const pierwszy = elementy({ pomijajZablokowane: true })[0];
        if (!obszar().contains(document.activeElement)) {
          pierwszy?.focus();
          oznaczOgnisko(pierwszy);
        } else {
          oznaczOgnisko(document.activeElement);
        }
      }, 120);
    } else {
      const pierwszy = elementy({ pomijajZablokowane: true })[0];
      pierwszy?.focus();
      oznaczOgnisko(pierwszy);
    }
  }, []);

  // ---- strumień zdarzeń ze stacji ----

  useEffect(() => {
    if (!wlaczone) return undefined;
    const stacja = adresStacji();
    // Żeton z zewnątrz ma pierwszeństwo — przy wejściu z kodem pojawia się
    // dopiero po jego wymianie, więc odczyt z pamięci przy montażu jest pusty.
    const z = zetonWidza || zeton(stacja);
    if (!z) return undefined;

    const zrodlo = new EventSource(`${stacja}/api/pokretlo?zeton=${encodeURIComponent(z)}`);
    let zywe = true;

    zrodlo.onopen = () => zywe && setPolaczone(true);
    zrodlo.onerror = () => {
      if (!zywe) return;
      setPolaczone(false);
      // EventSource ponawia sam; komunikat zostawiamy, bo 409 (ktoś inny trzyma
      // pokrętło) wygląda tu tak samo jak zerwane łącze.
      setBlad("Pokrętło niedostępne — może trzyma je ktoś inny albo panel nie działa.");
    };

    zrodlo.onmessage = (e) => {
      // ⛔ Nie polegamy na samym `onopen`. Zmierzone na stacji: serwer odnotował
      // „monitory stacji bierze pokrętło", a strona nadal pokazywała BRAK — czyli
      // `onopen` nie zadziałało, choć strumień żył i powitanie przyszło. Wskaźnik
      // kłamiący o sprawnym pokrętle jest gorszy niż jego brak, bo każe operatorowi
      // szukać usterki tam, gdzie jej nie ma.
      if (!zywe) return;
      setPolaczone(true);
      let w;
      try {
        w = JSON.parse(e.data);
      } catch {
        return;
      }
      if (w.typ === "powitanie" || w.typ === "most") {
        setBlad(null);
        return;
      }
      if (w.typ === "obrot") {
        const krok = Number(w.kierunek) >= 0 ? 1 : -1;
        if (trybRef.current) zmienWartosc(krok);
        else przesun(krok);
        return;
      }
      if (w.typ === "wcisniety") {
        wcisnietyOd.current = Date.now();
        przytrzymane.current = false;
        return;
      }
      if (w.typ === "puszczony") {
        // Przytrzymanie mierzymy sami — panel przysyła surowy stan przycisku
        // właśnie po to, żeby każdy klient mógł mieć własny próg.
        const trwalo = Date.now() - wcisnietyOd.current;
        if (wcisnietyOd.current && trwalo >= PRZEJECIE_PANELU_MS) {
          // To już przytrzymanie „oddaj pokrętło" — zostawiamy je panelowi.
          przytrzymane.current = true;
          wcisnietyOd.current = 0;
          return;
        }
        if (wcisnietyOd.current && trwalo >= PRZYTRZYMANIE_MS) {
          przytrzymane.current = true;
          cofnij();
        }
        wcisnietyOd.current = 0;
        return;
      }
      if (w.typ === "klik") {
        // Po przytrzymaniu panel przysyła jeszcze klik — pomijamy go, inaczej
        // jeden ruch palca robiłby dwie rzeczy naraz.
        if (przytrzymane.current) {
          przytrzymane.current = false;
          return;
        }
        nacisnij();
      }
    };

    return () => {
      zywe = false;
      zrodlo.close();
      setPolaczone(false);
      // Oddane pokrętło nie może zostawić po sobie obwódki sugerującej, że
      // czymś jeszcze steruje.
      oznaczOgnisko(null);
    };
  }, [wlaczone, zetonWidza, przesun, zmienWartosc, nacisnij, cofnij]);

  return { polaczone, blad, wRegulacji };
}
