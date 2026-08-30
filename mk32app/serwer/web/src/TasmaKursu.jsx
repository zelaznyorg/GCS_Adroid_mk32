// Taśma kursu wariantu D — odpowiednik `TasmaKursu` z ui/Elementy.kt.
//
// 460 × 20, zakres 60°. Kreska co 5°, dłuższa co 30°, róża wiatrów w literach,
// wskaźnik środka w kolorze akcentu. Krótsza i węższa w zakresie niż w wersji 2.1,
// bo w wariancie D wisi nad obrazem, zamiast ciąć kadr w poprzek (dok/UI.md §9).
//
// Czego tu nie ma, a jest w kokpicie: **znacznika namiaru na dom**. Serwer podglądu
// nie zna punktu startu (ARCHITEKTURA.md §3.1), więc nie ma czego rysować.
//
// ⚠ Na tej maszynie kurs pochodzi WYŁĄCZNIE z bazy GNSS — brak kompasu,
//    `EK3_SRC1_YAW=2` (CLAUDE.md §5). Gdy baza go nie daje, EKF nadal podaje jakąś
//    liczbę, ale jest ona niewiarygodna. Taśma przygasa wtedy i dostaje znacznik,
//    zamiast udawać, że wie, gdzie jest północ.

const ZAKRES = 60;
const KIERUNKI = [
  [0, "N"], [45, "NE"], [90, "E"], [135, "SE"],
  [180, "S"], [225, "SW"], [270, "W"], [315, "NW"],
];

// Najkrótsza różnica kątów w stopniach, wynik w (-180, 180].
function roznicaKatow(a, b) {
  return ((((a - b) % 360) + 540) % 360) - 180;
}

export default function TasmaKursu({ stan }) {
  const kurs = stan?.polozenie?.kurs_deg;
  const pewny = stan?.gnss?.kurs_dostepny !== false;
  const brak = kurs === null || kurs === undefined;

  // Rysujemy w układzie 460 × 20 i skalujemy przez viewBox — dzięki temu ta sama
  // geometria działa na telefonie, gdzie taśma bywa węższa niż 460 px.
  const W = 460;
  const H = 20;
  const naStopien = W / ZAKRES;
  const srodek = W / 2;

  const kreski = [];
  const litery = [];

  if (!brak) {
    const od = Math.floor((kurs - ZAKRES / 2) / 5) * 5;
    for (let k = od; k <= kurs + ZAKRES / 2; k += 5) {
      const x = srodek + roznicaKatow(k, kurs) * naStopien;
      if (x < -10 || x > W + 10) continue;
      const duza = ((k % 360) + 360) % 360 % 30 === 0;
      kreski.push(
        <line
          key={`k${k}`}
          x1={x} y1={duza ? H * 0.35 : H * 0.6} x2={x} y2={H}
          stroke="#fff" strokeOpacity={duza ? 0.7 : 0.3} strokeWidth={duza ? 2 : 1}
        />,
      );
    }
    for (const [kat, nazwa] of KIERUNKI) {
      const r = roznicaKatow(kat, kurs);
      if (Math.abs(r) > ZAKRES / 2 - 4) continue;
      litery.push(
        <text
          key={nazwa} x={srodek + r * naStopien} y={H * 0.42}
          textAnchor="middle" fontSize="10" fontWeight="700"
          fill="#fff" fillOpacity="0.9"
        >
          {nazwa}
        </text>,
      );
    }
  }

  return (
    <div className={`tasma-kursu ${pewny ? "" : "niepewna"} ${brak ? "brak" : ""}`}>
      {/* Liczba stoi w rzędzie przed taśmą, nie obok niej w pozycji bezwzględnej.
          Bezwzględna mieściła się przy 460 px, ale na telefonie wyjeżdżała poza kadr
          i po prostu znikała — a to jedyne miejsce, gdzie kurs jest podany wprost. */}
      <span className={`tasma-wartosc liczba ${pewny ? "" : "niepewna"}`}>
        {brak ? "———" : `${Math.round(((kurs % 360) + 360) % 360)}°`}
        {!brak && !pewny && <span className="tasma-znacznik" title="Kurs niepewny — brak bazy GNSS">?</span>}
      </span>

      <span className="tasma-rysunek">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" width="100%" height={H}>
        {kreski}
        {litery}
        {/* Wskaźnik środka — tu jest dziób maszyny. */}
        <path
          d={`M ${srodek} ${H * 0.45} L ${srodek - 7} ${H} L ${srodek + 7} ${H} Z`}
          fill="var(--akcent)" fillOpacity="0.9"
        />
      </svg>
      </span>
    </div>
  );
}
