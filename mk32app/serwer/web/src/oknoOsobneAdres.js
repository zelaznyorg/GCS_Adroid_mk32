// Odczyt tego, czym ma być bieżące okno — wydzielony z komponentu.
//
// Osobny plik, bo moduł eksportujący i komponent, i zwykłą funkcję, psuje
// odświeżanie na gorąco (`react-refresh/only-export-components`). Drobiazg,
// ale kosztuje przy każdej zmianie w trakcie pracy.

/** `?okno=mapa` / `?okno=obraz` albo null, gdy to zwykłe okno aplikacji. */
export function czegoDotyczy() {
  const p = new URLSearchParams(window.location.search);
  const okno = p.get("okno");
  if (okno !== "mapa" && okno !== "obraz") return null;
  return { okno, zrodlo: p.get("zrodlo") || null };
}
