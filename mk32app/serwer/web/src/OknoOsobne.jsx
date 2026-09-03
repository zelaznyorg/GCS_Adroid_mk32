// Oddokowane okno — mapa albo obraz na osobnym ekranie.
//
// ### Dlaczego osobne OKNO, a nie przeciąganie kafelka po stronie
//
// Żeby wynieść coś na drugi monitor, przeglądarka musi dostać osobne okno — kafelka
// wewnątrz jednej karty nie da się przeciągnąć poza jej krawędź. Stąd `window.open`
// i adres `?okno=mapa` albo `?okno=obraz`.
//
// Okno **nie dzieli stanu z rodzicem**. Zestawia własne połączenie: mapa własne SSE,
// obraz własne WHEP. Wyszło z tego prościej i pewniej niż przesyłanie stanu przez
// `postMessage`: nie ma synchronizacji, nie ma zamrożonego odczytu, gdy rodzic
// zamarznie, a zamknięcie jednego okna nie rusza drugiego. Żeton siedzi
// w `localStorage`, wspólnym dla tego samego pochodzenia, więc okno wchodzi bez pytania.
//
// ⚠ **Każde oddokowane okno to osobny widz.** Liczy się do limitu widzów
// (dok/DOSTEP_I_UZYTKOWNICY.md §5) i widać je na liście „kto ogląda" pod tym samym
// imieniem. Przy limicie 6 i dwóch oddokowanych oknach jedna osoba zajmuje trzy
// miejsca — przy większej sali limit trzeba podnieść w panelu administratora.
import { useEffect, useRef } from "react";
import "./Motyw.css";
import { useTelemetria } from "./useTelemetria";
import { useWhep, STAN_OPIS } from "./useWhep";
import { adresStacji, adresWhep, zeton } from "./sesja";
import Mapa from "./Mapa";
import "./App.css";

export default function OknoOsobne({ okno, zrodlo }) {
  const stacja = adresStacji();
  const zetonWidza = zeton(stacja);
  const videoRef = useRef(null);

  // Mapa potrzebuje telemetrii, obraz nie — ale hooki muszą lecieć bezwarunkowo,
  // więc dla okna obrazu przekazujemy `null` jako źródło i SSE i tak służy
  // za meldunek obecności.
  const { stan, polaczony } = useTelemetria(okno === "obraz" ? zrodlo : null, stacja, zetonWidza);
  const stanObrazu = useWhep(adresWhep(), okno === "obraz" ? (zrodlo ?? "brak") : "brak", videoRef);

  useEffect(() => {
    document.title = okno === "mapa" ? "Panorama — mapa" : "Panorama — obraz";
  }, [okno]);

  if (!zetonWidza) {
    return (
      <div className="ekran okno-osobne">
        <p className="przypis blad">
          To okno nie ma zaproszenia do stacji {stacja}. Otwórz je z głównego okna
          przyciskiem ODDOKUJ — wtedy przejmie Twój dostęp.
        </p>
      </div>
    );
  }

  if (okno === "mapa") {
    return (
      <div className="ekran okno-osobne">
        {!polaczony && <p className="przypis blad okno-pasek">BRAK POŁĄCZENIA ZE STACJĄ</p>}
        <Mapa stan={stan} osobne />
      </div>
    );
  }

  const zywy = stanObrazu === "zywo";
  return (
    <div className="ekran okno-osobne">
      <video ref={videoRef} className="obraz" autoPlay playsInline muted />
      {!zywy && (
        <div className="zaslona">
          <div className={`stan-obrazu ${stanObrazu}`}>{STAN_OPIS[stanObrazu]}</div>
        </div>
      )}
    </div>
  );
}
