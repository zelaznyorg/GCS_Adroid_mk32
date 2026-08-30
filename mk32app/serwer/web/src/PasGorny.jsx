// Pas górny wariantu D — odpowiednik `PasGorny` z ui/Elementy.kt.
//
// 28 px na przejściu tonalnym, bez linii odcinającej. Mieści to, co musi być widoczne
// zawsze: tryb, uzbrojenie, zasilanie, GNSS, stan łącza. Reszta chowa się niżej.
//
// ⚠ RÓŻNICA WOBEC KOKPITU, ŚWIADOMA: pole władzy po prawej mówi tu **PODGLĄD**,
//    a nie „STERUJESZ TY”. Ten klient nie wysyła do maszyny niczego (dok/WLADZA.md)
//    i pas ma to powiedzieć wprost, żeby nikt patrzący przez ramię nie pomylił
//    telefonu z aparaturą.
//
// Czego w tym pasie nie ma, choć jest w kokpicie: **czas lotu** i **dystans do domu**.
// Serwer podglądu ich nie wysyła (nie zna punktu startu ani chwili uzbrojenia —
// ARCHITEKTURA.md §3.1), a wyliczanie ich z momentu wejścia widza dałoby liczbę
// wyglądającą na prawdziwą i nieprawdziwą. Lepiej ich nie pokazywać wcale.

const KRESKI = "———";

// Tryby, które prowadzą maszynę same — domain/Tryby.kt `automatyczny()`.
const AUTOMATYCZNE = new Set(["AUTO", "RTL", "SMART_RTL", "AUTO_RTL", "LAND", "GUIDED"]);

// Progi z domain/Ostrzezenia.kt. 25,2 V to górny limit ZR30 i air unitu MK32
// (CLAUDE.md poz. 8), 22,2 V to BATT_LOW_VOLT, 21,0 V to BATT_CRT_VOLT.
const NAPIECIE_GORNE = 25.2;
const NAPIECIE_DOLNE = 22.2;
const PUSTY_6S = 21.0;
const SATELITY_MIN = 12;

function klasaNapiecia(v) {
  if (v === null || v === undefined) return "";
  if (v > NAPIECIE_GORNE) return "uwaga";
  if (v >= 0.1 && v <= NAPIECIE_DOLNE) return "blokada";
  return "";
}

/** Słupek baterii — liczba mówi ile jest, słupek ile zostało. Skala 6S: 21,0 → 25,2 V. */
function SlupekBaterii({ napiecie }) {
  const znane = typeof napiecie === "number" && napiecie > 0.1;
  const udzial = znane
    ? Math.min(1, Math.max(0, (napiecie - PUSTY_6S) / (NAPIECIE_GORNE - PUSTY_6S)))
    : 0;
  return (
    <span className={`slupek ${klasaNapiecia(napiecie)}`} aria-hidden="true">
      <span className="slupek-korpus">
        <span className="slupek-wypelnienie" style={{ width: `${udzial * 100}%` }} />
      </span>
      <span className="slupek-czubek" />
    </span>
  );
}

function Ikona({ nazwa }) {
  // Piktogramy odpowiadają `Piktogram.SATELITY` i `Piktogram.LACZE` z ui/Ikony.kt.
  const wspolne = { width: 14, height: 14, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" };
  if (nazwa === "satelity") {
    return (
      <svg {...wspolne}>
        <path d="M5 13 11 7" /><path d="M3 11 7 15" /><path d="M9 5l4 4" />
        <path d="M14 10l5-5" /><path d="M16 16a6 6 0 0 0-6-6" /><path d="M20 20a12 12 0 0 0-12-12" />
      </svg>
    );
  }
  return (
    <svg {...wspolne}>
      <path d="M2 8a15 15 0 0 1 20 0" /><path d="M5.5 11.5a10 10 0 0 1 13 0" />
      <path d="M9 15a5 5 0 0 1 6 0" /><circle cx="12" cy="19" r="0.6" fill="currentColor" />
    </svg>
  );
}

export default function PasGorny({ stan, polaczony }) {
  const lot = stan?.lot;
  const gnss = stan?.gnss;
  const bateria = stan?.bateria;
  const lacze = stan?.lacze;

  const tryb = lot?.tryb ?? null;
  const automat = tryb ? AUTOMATYCZNE.has(tryb) : false;
  const uzbrojony = lot?.uzbrojony ?? null;
  const napiecie = bateria?.napiecie_v ?? null;
  const satelity = gnss?.satelity ?? null;
  const zywe = Boolean(lacze?.zywe);

  // Dwie grupy, nie jeden rząd. Pas ma stałą wysokość 28 px i przy wąskim oknie
  // czegoś musi zabraknąć — ale NIE pola władzy: to ono mówi, że ten ekran nie steruje.
  // Dlatego lewa grupa ma `min-width: 0` i chowa nadmiar za krawędzią (z wygaszeniem,
  // żeby ucięcie czytało się jako „przesuń"), a prawa jest nieściśliwa.
  return (
    <div className="pas-gorny">
      <span className="pas-lewa">
        <span className={`pas-tryb liczba ${automat ? "automat" : ""}`}>{tryb ?? KRESKI}</span>

        <span className="pas-pole">
          <span className={`kropka-stanu ${uzbrojony ? "uzbrojony" : ""}`}>●</span>
          <span className={`liczba pas-uzbrojenie ${uzbrojony ? "uzbrojony" : ""}`}>
            {uzbrojony === null ? KRESKI : uzbrojony ? "UZBROJONY" : "ROZBROJONY"}
          </span>
        </span>

        <span className="pas-pole">
          <SlupekBaterii napiecie={napiecie} />
          <span className={`liczba ${klasaNapiecia(napiecie)}`}>
            {napiecie === null ? KRESKI : `${napiecie.toFixed(1)} V`}
          </span>
        </span>

        <span className={`pas-pole ${satelity !== null && satelity < SATELITY_MIN ? "uwaga" : "ok"}`}>
          <Ikona nazwa="satelity" />
          <span className="liczba">{satelity ?? KRESKI}</span>
          {/* HDOP obok satelitów, bo obie liczby mówią o tej samej rzeczy: czy pozycja
              jest wiarygodna. W kokpicie HDOP siedzi w DIAGNOSTYCE, ale tu nie ma
              drugiego ekranu, na który można go odesłać. */}
          {gnss?.hdop !== null && gnss?.hdop !== undefined && (
            <span className="pas-drobne liczba">hdop {gnss.hdop.toFixed(2)}</span>
          )}
        </span>

        <span className={`pas-pole ${zywe ? "" : "blokada"}`}>
          <Ikona nazwa="lacze" />
          <span className="liczba">
            {!polaczony
              ? "BRAK SERWERA"
              : zywe
                ? `${(lacze?.sekund_od_heartbeatu ?? 0).toFixed(1)} s`
                : "CISZA"}
          </span>
        </span>

        {/* Stan zmieniający zakres możliwych działań — w pasie, nie w banerze,
            bo dotyczy całego lotu, a nie chwili. Odpowiednik `Znacznik` z Elementy.kt.
            Na tej maszynie kurs to jedyne źródło orientacji: bez niego nie ma
            RTL ani misji (CLAUDE.md §5). */}
        {gnss?.kurs_dostepny === false && <span className="znacznik blokada">BRAK KURSU GNSS</span>}
      </span>

      <span className="pas-prawa">
        <span className="pas-pole pas-wladza" title="Ten ekran pokazuje, nie steruje — dok/WLADZA.md">
          <span className="kropka-stanu podglad">◎</span>
          <span className="liczba pas-uzbrojenie">PODGLĄD</span>
        </span>
      </span>
    </div>
  );
}
