// Dolny pasek telemetrii wariantu D — odpowiednik `PasekTelemetrii` z ui/Elementy.kt.
//
// 78 px, jeden rząd liczb na przejściu tonalnym, **bez tafli**. To jest zmiana, którą
// wariant D wprowadza wobec siatki z §3: liczby leżą wprost na obrazie, a nie na panelu,
// który mu zabiera kadr (dok/UI.md §9).
//
// Hierarchia rozmiarów jest regułą, nie ozdobą: **jedna** wartość na ekran jest ogromna
// (26 px — wysokość), reszta duża (20 px). Cztery równe liczby to tablica przyrządów,
// a nie HUD — uwaga operatora z wersji 2.1.
//
// Czego tu nie ma, a jest w kokpicie: **DOM** (dystans do punktu startu). Serwer podglądu
// nie zna pozycji domu, więc nie ma z czego go policzyć — ARCHITEKTURA.md §3.1.
// Po prawej stronie kokpit trzyma klawisze komend; tutaj w ich miejscu stoją klawisze
// **widza**, bo ten klient nie wysyła do maszyny niczego (dok/WLADZA.md).

const KRESKI = "——";

function Odczyt({ etykieta, wartosc, jednostka, miejsca = 1, znak = false, rozmiar = "duza", stare = false, kolor = null }) {
  const brak = wartosc === null || wartosc === undefined;
  let tekst = KRESKI;
  if (!brak) {
    const s = Math.abs(wartosc).toFixed(miejsca);
    tekst = znak ? `${wartosc >= 0 ? "+" : "−"}${s}` : wartosc.toFixed(miejsca);
  }
  return (
    <div className="odczyt">
      <span className="etykieta-hud">{etykieta}</span>
      <span className="odczyt-wiersz">
        <span className={`liczba odczyt-${rozmiar} ${stare ? "stara" : ""} ${kolor ?? ""}`}>{tekst}</span>
        {!brak && <span className="odczyt-jednostka">{jednostka}</span>}
      </span>
    </div>
  );
}

export default function PasekTelemetrii({ stan, stare = false, diody = null, klawisze = null }) {
  const lot = stan?.lot;
  const wzn = lot?.wznoszenie_ms ?? null;

  return (
    <div className="pasek-telemetrii">
      <Odczyt etykieta="wys" wartosc={lot?.wysokosc_m ?? null} jednostka="m"
        miejsca={1} rozmiar="ogromna" stare={stare} />
      <Odczyt etykieta="prędkość" wartosc={lot?.predkosc_ms ?? null} jednostka="m/s"
        miejsca={1} stare={stare} />
      {/* Bursztyn przy wznoszeniu ponad 4 m/s — ten sam próg co w kokpicie.
          Kolor niesie znaczenie: to jest tempo, przy którym warto spojrzeć. */}
      <Odczyt etykieta="wzn" wartosc={wzn} jednostka="m/s" miejsca={1} znak stare={stare}
        kolor={wzn !== null && Math.abs(wzn) > 4 ? "uwaga" : null} />

      <div className="pasek-wypelniacz" />

      {/* Stan TEJ przeglądarki — czy idzie obraz i czy ekran nie zgaśnie.
          Serwer o tym nie wie, więc nie ma tego w ostrzeżeniach. */}
      {diody}

      {klawisze}
    </div>
  );
}
