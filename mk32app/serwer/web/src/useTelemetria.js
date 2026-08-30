// Odbiór stanu maszyny ze strumienia SSE serwera.
//
// EventSource sam wznawia zerwane połączenie, więc nie piszemy własnego ponawiania.
// Adres budujemy z **adresu wybranej stacji** (polaczenie.js), a nie z `window.location` —
// aplikacja na telefonie może rozmawiać ze stacją inną niż ta, spod której ją
// zainstalowano. Dla widza wchodzącego wprost ze stacji wychodzi na to samo.
// Patrz dok/SERWER_PODGLADU.md §6.4 i dok/TELEFON.md §2a.
//
// To samo połączenie jest sygnałem obecności widza (dok/DOSTEP_I_UZYTKOWNICY.md §5):
// serwer odsyła na starcie identyfikator połączenia, którym meldujemy potem, na jaki
// strumień przełączył się widz.
import { useEffect, useRef, useState } from "react";
import { adresTelemetrii, api } from "./sesja";

/**
 * @param zrodlo      strumień, który widz właśnie ogląda (melduje obecność)
 * @param stacja      adres stacji
 * @param zetonWidza  żeton do tej stacji albo null, gdy jeszcze nie wpuszczony
 *
 * Oba ostatnie MUSZĄ być parametrami, a nie odczytem ze środka. Siedzą
 * w `localStorage`, więc React nie zauważy ich zmiany sam, a od nich zależy,
 * z KIM i JAKO KTO rozmawiamy. Dwa błędy, które to naprawia — oba dawały ten sam,
 * mylący objaw: „BRAK SERWERA" mimo udanego wejścia:
 *
 *  1. efekt z pustą listą zależności zestawiał SSE raz, przy montowaniu — czyli
 *     jeszcze na ekranie wejścia, pod adresem samej aplikacji. Po wpisaniu kodu
 *     połączeniowego telemetria dalej pytała niewłaściwy serwer;
 *  2. sam adres nie wystarczał: przy wejściu ze stacji MACIERZYSTEJ adres się
 *     nie zmienia, więc efekt nie wznawiał — a połączenie zestawione przed
 *     wpisaniem kodu nie miało żetonu i serwer odrzucał je z 401.
 */
export function useTelemetria(zrodlo, stacja, zetonWidza) {
  // Stan trzymamy RAZEM z adresem stacji, z której pochodzi. Dzięki temu przy
  // przełączeniu stacji stary odczyt przestaje pasować i znika sam, bez zerowania
  // go w efekcie. Zamrożona liczba z zupełnie innej maszyny byłaby najgorszym
  // możliwym błędem tego ekranu (zasada 6, dok/UI.md).
  const [dane, setDane] = useState({ stacja: null, stan: null, polaczony: false });
  const [odciety, setOdciety] = useState(false);
  const idPolaczenia = useRef(null);

  // Zmiana STRUMIENIA nie może zrywać telemetrii — zerwanie i zestawienie na nowo
  // liczyłoby się jako wyjście i wejście widza. Zmiana STACJI albo ŻETONU musi,
  // bo to inny serwer albo inna tożsamość.
  useEffect(() => {
    // Bez żetonu nie ma po co pukać — serwer odpowie 401, a EventSource wpadnie
    // w pętlę ponawiania, której sam nie umie przerwać.
    if (!zetonWidza) return undefined;

    const es = new EventSource(adresTelemetrii());
    const oznacz = (zmiany) => setDane((d) => ({ ...d, stacja, ...zmiany }));

    es.onopen = () => oznacz({ polaczony: true });
    es.onerror = () => oznacz({ polaczony: false });
    es.onmessage = (ev) => {
      try {
        oznacz({ stan: JSON.parse(ev.data), polaczony: true });
      } catch {
        /* niekompletna ramka — następna przyjdzie za chwilę */
      }
    };
    es.addEventListener("polaczenie", (ev) => {
      try {
        idPolaczenia.current = JSON.parse(ev.data).id;
      } catch {
        /* nieistotne */
      }
    });
    es.addEventListener("odciety", () => {
      // Administrator zabrał dostęp. Zamykamy sami, żeby EventSource nie próbował
      // wracać w kółko i żeby widz zobaczył powód zamiast migającego „ŁĄCZENIE".
      setOdciety(true);
      es.close();
    });

    return () => {
      idPolaczenia.current = null;
      es.close();
    };
  }, [stacja, zetonWidza]);

  // Odczyt uznajemy za nasz tylko wtedy, gdy pochodzi z TEJ stacji i mamy do niej
  // wstęp. Wyliczenie zamiast zerowania stanu w efekcie — inaczej każde przełączenie
  // przechodziłoby przez dodatkowy render tylko po to, żeby skasować.
  const pasuje = Boolean(zetonWidza) && dane.stacja === stacja;
  const polaczony = pasuje && dane.polaczony;

  // Meldunek „oglądam ten strumień" — z samego połączenia SSE tego nie widać.
  useEffect(() => {
    if (!idPolaczenia.current || !zrodlo) return;
    api("/api/obecnosc", {
      method: "POST",
      body: { polaczenie: idPolaczenia.current, zrodlo },
    }).catch(() => { /* obecność to wygoda, nie warunek oglądania */ });
  }, [zrodlo, polaczony]);

  return { stan: pasuje ? dane.stan : null, polaczony, odciety };
}
