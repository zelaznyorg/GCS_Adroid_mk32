// Mozaika źródeł — wszystkie widoczne strumienie naraz, każdy w kafelku.
//
// ### Skąd ta zasada
//
// Do 2026-09-03 stacja pokazywała jeden obraz — z ZR30 przez MK32 — i lista źródeł
// była rozwijaną listą w dolnym pasku. Z dronami DJI i torem analogowym źródeł jest
// kilka, a operator chce **zobaczyć, co w ogóle nadaje**, zanim wybierze. Stąd:
//
//   * jedno widoczne źródło  → od razu pełny ekran, jak dotąd (mozaiki nie ma),
//   * dwa i więcej           → kafelki, każdy z żywym obrazem; klik = pełny ekran,
//   * najwyżej sześć         → limit ustawiony po stronie serwera (MAKS_ZRODEL),
//                              bo każdy kafelek to osobne połączenie WebRTC i osobne
//                              dekodowanie H.264 w przeglądarce, a na malinie nie ma
//                              dla niego sprzętu (dok/GCS_RPI5.md).
//
// ⚠ Kafelek źródła POBIERANEGO uruchamia jego pobieranie (`sourceOnDemand`):
// dopóki mozaika jest na ekranie, ZR30 leci przez łącze radiowe, nawet gdy nikt
// nie patrzy na jej kafelek. To jest cena podglądu „co nadaje" — świadoma.
import { useRef } from "react";
import { STAN_OPIS, useWhep } from "./useWhep";

export function IkonaMozaika() {
  return (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="3" y="4" width="8" height="7" rx="1" />
      <rect x="13" y="4" width="8" height="7" rx="1" />
      <rect x="3" y="13" width="8" height="7" rx="1" />
      <rect x="13" y="13" width="8" height="7" rx="1" />
    </svg>
  );
}

function Kafelek({ zrodlo, bazaWhep, naWybor }) {
  const ref = useRef(null);
  const stan = useWhep(bazaWhep, zrodlo.id, ref);
  // Źródło nadawane bez nadawcy to nie awaria — aparatura jeszcze nie nadaje.
  // Napis ma mówić to, a nie „BRAK SYGNAŁU", które brzmi jak zerwane łącze.
  const opis = stan !== "zywo" && zrodlo.nadawany && zrodlo.zywe === false
    ? "CZEKA NA NADAWCĘ"
    : STAN_OPIS[stan];
  return (
    <button
      type="button"
      className={`kafelek-zrodla ${stan}`}
      onClick={() => naWybor(zrodlo.id)}
      title={`${zrodlo.nazwa} — kliknij, żeby oglądać na całym ekranie`}
    >
      <video ref={ref} autoPlay playsInline muted />
      <span className="kafelek-nazwa">{zrodlo.nazwa}</span>
      <span className={`kafelek-stan ${stan}`}>{opis}</span>
    </button>
  );
}

export default function Mozaika({ zrodla, bazaWhep, naWybor }) {
  const lista = zrodla.slice(0, 6);
  // 2 → obok siebie, 3–4 → 2×2, 5–6 → 3×2. Więcej niż sześciu serwer nie przyjmie.
  const kolumny = lista.length <= 2 ? 2 : lista.length <= 4 ? 2 : 3;
  return (
    <div className="mozaika" style={{ "--kolumny": kolumny }}>
      {lista.map((z) => (
        <Kafelek key={z.id} zrodlo={z} bazaWhep={bazaWhep} naWybor={naWybor} />
      ))}
    </div>
  );
}
