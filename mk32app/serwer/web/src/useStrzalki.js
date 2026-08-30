// Klawisze pilota pulpitu GCS — druga, niezależna droga sterowania bez myszy.
//
// ### Skąd to się bierze
//
// `gcs_pulpit/pilot.py` zamienia pokrętło na klawisze i wysyła je przez `wlrctl`
// do okna, które jest na wierzchu: **`↑ ↓ ← →`, `ENTER`, `TAB`, `⇧TAB`, `ESC`,
// `PgUp`/`PgDn`**. Ich model to „obrót WYBIERA klawisz, klik go WCISKA", więc
// operator ustawia `↓` i klika tyle razy, ile trzeba.
//
// ⛔ **Dlatego strzałki muszą przesuwać wskazanie.** Bez tego pilot jest bezużyteczny
// na naszej stronie — zmierzone: `TAB`, `ENTER` i `ESC` działały, a `↓`/`→` nie
// robiły nic, bo przeglądarka sama nie wodzi nimi ogniska. To była główna przyczyna
// wrażenia „pokrętłem nic nie da się zrobić", mimo sprawnego mostu.
//
// Ta droga działa **niezależnie od tego, czy strona trzyma pokrętło** — pilot wysyła
// zwykłe klawisze, nie do odróżnienia od klawiatury. To dobrze: jest drogą odwrotu,
// gdy most jest zajęty albo panel oddał ognisko sobie.
//
// ### Czego to NIE zabiera
//
// W polu tekstowym, liczbowym i na liście rozwijanej strzałki zostawiamy
// przeglądarce — inaczej nie dałoby się wpisać wartości ani wybrać pozycji.
import { useEffect } from "react";
import { przesunOgnisko, oznaczOgnisko, polePodmiany } from "./ogniskowanie";

/** Czy klawisz trafia w pole, które samo używa strzałek. */
function wPolu(el) {
  if (!el) return false;
  if (el.isContentEditable) return true;
  const rodzaj = polePodmiany(el);
  // Lista rozwijana i pola tekstowe/liczbowe obsługują strzałki po swojemu.
  return rodzaj === "lista" || rodzaj === "liczba" || rodzaj === "tekst";
}

export function useStrzalki({ wlaczone = true } = {}) {
  useEffect(() => {
    if (!wlaczone) return undefined;

    const naKlawisz = (e) => {
      if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;

      // TAB wodzi ogniskiem sam — my tylko dorysowujemy obwódkę, żeby wyglądała
      // tak samo jak przy strzałkach i przy pokrętle.
      if (e.key === "Tab") {
        setTimeout(() => oznaczOgnisko(document.activeElement), 0);
        return;
      }

      const wDol = e.key === "ArrowDown" || e.key === "ArrowRight";
      const wGore = e.key === "ArrowUp" || e.key === "ArrowLeft";
      if (!wDol && !wGore) return;
      if (wPolu(document.activeElement)) return;

      e.preventDefault();
      przesunOgnisko(wDol ? 1 : -1);
    };

    window.addEventListener("keydown", naKlawisz);
    return () => window.removeEventListener("keydown", naKlawisz);
  }, [wlaczone]);
}
