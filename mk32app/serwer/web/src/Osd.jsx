// Nakładka podglądu — wariant D (dok/UI.md §9), ten sam język wizualny co kokpit MK32.
//
// Odpowiednik `ui/Kokpit.kt`: składa pas górny, taśmę kursu, banery, kartę horyzontu
// i dolny pasek telemetrii. Rozkład siedzi w Hud.css, barwy i wymiary w Motyw.css —
// oba przepisane z `ui/Motyw.kt`, żeby telefon i aparatura nie rozjechały się kolorem.
//
// ⚠ CZEGO TU NIE MA I NIE BĘDZIE: klawiszy komend. Kokpit ma w dolnym pasku
//    FOTO · REC · LĄDUJ · RTL; ten klient nie wysyła do maszyny niczego i pole władzy
//    w pasie górnym mówi wprost PODGLĄD (dok/WLADZA.md). W miejscu komend stoją
//    klawisze widza, podane z App.jsx przez `klawisze`.
//
// Diody stanu klienta (`diody`) siedzą w pasku telemetrii, a nie w pasie górnym.
// Powód jest wymiarowy: pas ma 28 px wysokości i na telefonie 390 px szerokości
// wypychał z siebie „UZBROJONY", czyli akurat to, co musi być widoczne zawsze.
//
// Czego nie ma z powodu danych, a nie decyzji: **karty mapy**, **DOM** (dystans do startu),
// **czasu lotu** i **znacznika namiaru na dom**. Serwer podglądu nie zna pozycji domu ani
// chwili uzbrojenia (ARCHITEKTURA.md §3.1), a podgląd nie wozi kafelków mapy.
import PasGorny from "./PasGorny";
import TasmaKursu from "./TasmaKursu";
import Banery from "./Banery";
import KartaHoryzontu from "./KartaHoryzontu";
import PasekTelemetrii from "./PasekTelemetrii";
import "./Hud.css";

// Reguła wieku danych — zasada 6 z dok/UI.md. Powyżej 2 s wartość przygasa.
// Powyżej 10 s serwer przysyła już null i pokazujemy kreski, więc drugiego progu
// nie musimy liczyć tutaj: robi to `_swieze()` w server/telemetria.mjs.
const PRÓG_STARE_S = 2;

export default function Osd({ stan, polaczony, klawisze = null, diody = null }) {
  const czekaNaDane = !stan;

  // Jeden wiek dla całej nakładki, tak samo jak `stan.wiekTelemetriiS()` w kokpicie.
  // Per-pole serwer i tak nie podaje, a udawanie dokładniejszej wiedzy niż mamy
  // byłoby dokładnie tym, przed czym broni zasada 6.
  const odHeartbeatu = stan?.lacze?.sekund_od_heartbeatu ?? null;
  const stare = odHeartbeatu !== null && odHeartbeatu > PRÓG_STARE_S;

  return (
    <div className="hud">
      <PasGorny stan={stan} polaczony={polaczony} />

      {!czekaNaDane && <TasmaKursu stan={stan} />}

      <Banery
        ostrzezenia={stan?.ostrzezenia ?? []}
        czekaNaDane={czekaNaDane}
        polaczony={polaczony}
      />

      {!czekaNaDane && (
        <div className="karty-narozne">
          <KartaHoryzontu stan={stan} stare={stare} />
        </div>
      )}

      <PasekTelemetrii stan={stan} stare={stare} diody={diody} klawisze={klawisze} />
    </div>
  );
}
