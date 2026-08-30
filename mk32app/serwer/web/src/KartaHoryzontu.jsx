// Karta horyzontu wariantu D — odpowiednik `KartaHoryzontu` z ui/Karty.kt.
//
// 212 × 150 w prawym górnym narożniku. Rysunek mówi „jak bardzo”, liczby w narożniku
// mówią „ile” — bo przy przechyle powyżej ±30° łuk już nie odpowie, a liczba tak.
//
// Bez barw ziemi i nieba: kolor w tym systemie znaczy stan, a nie dekorację
// (dok/UI.md §1 zasada 4). Ziemia jest ledwie jaśniejszym prostokątem.
//
// W kokpicie nad tą kartą stoi jeszcze karta mapy. Tu jej nie ma — podgląd nie wozi
// kafelków mapy (te siedzą na karcie MK32, dok/TELEFON.md), więc horyzont zostaje sam.

const ZAKRES_POCHYLENIA = 10;   // ±10° na całą wysokość okna
const ZAKRES_PRZECHYLU = 30;    // powyżej tego wskaźnik łuku robi się bursztynowy

const W = 212;
const H = 150;

export default function KartaHoryzontu({ stan, stare = false }) {
  const przechyl = stan?.postawa?.roll_deg;
  const pochylenie = stan?.postawa?.pitch_deg;
  const martwe = przechyl === null || przechyl === undefined ||
                 pochylenie === null || pochylenie === undefined;

  if (martwe) {
    return (
      <div className="karta-horyzontu tafla">
        <span className="etykieta-hud karta-podpis">horyzont</span>
        <div className="karta-puste liczba stara">———</div>
      </div>
    );
  }

  const srodekX = W / 2;
  const srodekY = H / 2;
  const naStopien = (H / 2) / ZAKRES_POCHYLENIA;
  const przesuniecie = Math.max(-40, Math.min(40, pochylenie)) * naStopien;
  const obrot = -Math.max(-90, Math.min(90, przechyl));
  const y = srodekY + przesuniecie;
  const zapas = W;   // z zapasem, bo rysunek jest obrócony

  // Łuk przechyłu. Promień liczony z WYSOKOŚCI, nie szerokości — karta jest szersza
  // niż wyższa i przy w × 0,40 górne znaczniki wychodziły poza kadr (uwaga z Karty.kt).
  const promien = H * 0.38;
  const znacznikiLuku = [-30, -20, -10, 0, 10, 20, 30].map((st) => {
    const kat = ((-90 + st) * Math.PI) / 180;
    const dl = st === 0 ? 8 : 5;
    return (
      <line
        key={st}
        x1={srodekX + promien * Math.cos(kat)} y1={srodekY + promien * Math.sin(kat)}
        x2={srodekX + (promien + dl) * Math.cos(kat)} y2={srodekY + (promien + dl) * Math.sin(kat)}
        stroke="currentColor" strokeOpacity="0.5" strokeWidth="1"
      />
    );
  });

  const katWskaz = ((-90 + Math.max(-30, Math.min(30, przechyl))) * Math.PI) / 180;
  const wx = srodekX + promien * Math.cos(katWskaz);
  const wy = srodekY + promien * Math.sin(katWskaz);
  const przechylZaDuzy = Math.abs(przechyl) > ZAKRES_PRZECHYLU;

  const r = 14;   // znak maszyny

  return (
    <div className="karta-horyzontu tafla">
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" height="100%" className={stare ? "stara" : ""}>
        <defs>
          <clipPath id="kadr-horyzontu">
            <rect x="0" y="0" width={W} height={H} />
          </clipPath>
        </defs>

        <g clipPath="url(#kadr-horyzontu)" color={stare ? "var(--wygasly)" : "var(--tekst)"}>
          <g transform={`rotate(${obrot} ${srodekX} ${srodekY})`}>
            {/* „Ziemia” — samo wypełnienie, bez koloru znaczącego. */}
            <rect x={-zapas} y={y} width={W + 2 * zapas} height={H + 2 * zapas} fill="var(--linia2)" />
            <line x1={-zapas} y1={y} x2={W + zapas} y2={y} stroke="currentColor" strokeWidth="1.6" />

            {/* Drabinka co 5°, kreska krótsza dla wartości pośrednich. */}
            {[-10, -5, 5, 10].map((st) => {
              const yy = y - st * naStopien;
              const dl = st % 10 === 0 ? W * 0.2 : W * 0.11;
              return (
                <line
                  key={st} x1={srodekX - dl} y1={yy} x2={srodekX + dl} y2={yy}
                  stroke="currentColor" strokeOpacity="0.55" strokeWidth="1"
                />
              );
            })}
          </g>

          {znacznikiLuku}
          <path
            d={`M ${wx} ${wy} L ${wx - 4} ${wy - 7} L ${wx + 4} ${wy - 7} Z`}
            fill={przechylZaDuzy ? "var(--uwaga)" : "var(--akcent)"}
          />

          {/* Znak maszyny — nieruchomy, bo to ona jest układem odniesienia. */}
          <line x1={srodekX - r * 2} y1={srodekY} x2={srodekX - r * 0.5} y2={srodekY}
            stroke="var(--akcent)" strokeWidth="2" />
          <line x1={srodekX + r * 0.5} y1={srodekY} x2={srodekX + r * 2} y2={srodekY}
            stroke="var(--akcent)" strokeWidth="2" />
          <circle cx={srodekX} cy={srodekY} r="2" fill="none" stroke="var(--akcent)" strokeWidth="2" />
        </g>
      </svg>

      {/* Podpis na taflce, nie wprost na rysunku — inaczej drabinka pochylenia
          przechodzi mu przez litery. */}
      <span className="etykieta-hud karta-podpis">horyzont</span>

      <span className={`karta-wartosci liczba ${stare ? "stara" : ""}`}>
        {`prz ${przechyl >= 0 ? "+" : ""}${przechyl.toFixed(0)}°  poch ${pochylenie >= 0 ? "+" : ""}${pochylenie.toFixed(0)}°`}
      </span>
    </div>
  );
}
