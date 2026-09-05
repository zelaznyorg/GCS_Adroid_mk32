// Wspólny nagłówek paneli: tytuł, przejście do pozostałych paneli, ZAMKNIJ.
//
// ### Skąd się wziął — z użytkowania, nie z projektu
//
// Po pierwszym dniu pracy na stacji zgłoszono: „jak się wejdzie w sekcję admin,
// to już nie można przejść nigdzie więcej, tak samo z innymi zakładkami, i trzeba
// restartować całą stronę". Były na to dwie niezależne przyczyny i obie siedziały
// w warstwie układu, nie w logice:
//
//  1. `.zaslona.panel` miało `justify-content: center` przy `overflow-y: auto`.
//     Przewijalny kontener z wyśrodkowaną zawartością **obcina górę** i nie da się
//     do niej dojechać — to znana pułapka flexboksa. Panel administratora ma siedem
//     sekcji, więc jego nagłówek z przyciskiem ZAMKNIJ lądował poza zasięgiem.
//     Naprawa jest w Hud.css/App.css: `flex-start` + `margin-block: auto` na karcie.
//  2. Nawet z widocznym ZAMKNIJ przejście do innego panelu wymagało dwóch ruchów
//     przez dolny pasek, który panel zasłania. Stąd ten przełącznik.
//
// Wniosek na przyszłość: panel bez wyjścia widocznego **bez przewijania** jest
// panelem bez wyjścia. Nagłówek jest dlatego przyklejony do góry karty.
import { useEffect } from "react";

/** Panele, między którymi da się przeskakiwać. `rola` ogranicza widoczność. */
const PANELE = [
  { id: "mapa", etykieta: "MAPA", opis: "Gdzie jest maszyna, dom i trasa", minRola: "widz" },
  { id: "widzowie", etykieta: "OGLĄDA", opis: "Kto teraz patrzy", minRola: "widz" },
  { id: "admin", etykieta: "ADMIN", opis: "Zaproszenia, dostęp, źródła, archiwum, diagnostyka", minRola: "admin" },
  { id: "stacja", etykieta: "STACJA", opis: "Usługi, zasilanie, sieć, dziennik", minRola: "admin" },
];

const RANGA = { widz: 1, operator: 2, admin: 3 };

export default function NaglowekPanelu({ tytul, panel, naPanel, naZamknij, rola = "widz", children }) {
  // Escape zamyka. Oczywiste dla klawiatury, a na stacji z podpiętym pulpitem
  // bywa szybsze niż celowanie w przycisk.
  useEffect(() => {
    const naKlawisz = (e) => {
      if (e.key === "Escape") naZamknij();
    };
    window.addEventListener("keydown", naKlawisz);
    return () => window.removeEventListener("keydown", naKlawisz);
  }, [naZamknij]);

  const widoczne = PANELE.filter((p) => RANGA[rola] >= RANGA[p.minRola]);

  return (
    <div className="naglowek-panelu">
      <h2>{tytul}</h2>

      <div className="rzad">
        {children}

        {/* Przełącznik paneli. Aktywny jest wyłączony, żeby kliknięcie w to,
            na co się właśnie patrzy, nie przeładowywało widoku bez powodu. */}
        {widoczne.length > 1 && (
          <div className="rzad zakladki-paneli">
            {widoczne.map((p) => (
              <button
                key={p.id}
                type="button"
                className={`przelacznik drobny ${panel === p.id ? "wlaczony" : ""}`}
                onClick={() => naPanel(p.id)}
                disabled={panel === p.id}
                title={p.opis}
              >
                {p.etykieta}
              </button>
            ))}
          </div>
        )}

        <button type="button" className="przelacznik" onClick={naZamknij} title="Escape też zamyka">
          ZAMKNIJ
        </button>
      </div>
    </div>
  );
}
