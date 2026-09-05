// Panel administratora — zarządza DOSTĘPEM, nie maszyną.
//
// Ani jeden przycisk na tym ekranie nie wysyła niczego do drona. Władza nad lotem
// zostaje na MK32 (dok/WLADZA.md) i administrator stacji też jej nie ma.
// Projekt panelu: dok/DOSTEP_I_UZYTKOWNICY.md §4.
//
// ### Karty, nie jedna długa ściana (uwaga Toma, 2026-09-04)
//
// Do tego dnia panel był jedną kartą z ośmioma sekcjami jedna pod drugą:
// sterowanie dostępem, kto ogląda, zaproszenia, źródła, adresy, archiwum, rejestr,
// dziennik. Na stacji, z pokrętłem zamiast myszy, znaleźć w tym „gdzie wydaję kod"
// wymagało przewijania i zgadywania. W polu nie ma na to czasu.
//
// Teraz każda karta odpowiada na JEDNO pytanie, a jej nazwa jest tym pytaniem:
//   ZAPROSZENIA  — kogo wpuścić
//   DOSTĘP       — kto ogląda i co może (cisza, limit, odcinanie, dziennik wejść)
//   NOWE ŹRÓDŁO  — jak podłączyć drona albo kamerę (trzy kroki, na końcu hasło do wpisania)
//   ŹRÓDŁA       — co stacja pokazuje (nazwa, widoczność, hasło, usuwanie)
//   ARCHIWUM     — co nagrywamy i ile mamy miejsca
//   DIAGNOSTYKA  — czy usługi żyją i co się zepsuło
// Dodawanie źródła jest osobno od listy celowo: dodawanie to czynność z końcem,
// którym jest hasło do wpisania w aparaturze; lista to stan.
//
// Każda karta pobiera własne dane i odświeża je tylko, gdy jest otwarta — sześć
// pętli odpytywania naraz na Raspberry Pi nie miało sensu, skoro widać jedną.
import { useEffect, useState } from "react";
import NaglowekPanelu from "./NaglowekPanelu";
import Zaproszenia from "./admin/Zaproszenia";
import Dostep from "./admin/Dostep";
import NoweZrodlo from "./admin/NoweZrodlo";
import Zrodla from "./admin/Zrodla";
import Archiwum from "./admin/Archiwum";
import Diagnostyka from "./admin/Diagnostyka";

const KARTY = [
  { id: "zaproszenia", etykieta: "ZAPROSZENIA", opis: "Kogo wpuścić — wydaj link albo kod, unieważnij, gdy przestanie być potrzebny." },
  { id: "dostep", etykieta: "DOSTĘP", opis: "Kto ogląda i co może — tryb ciszy, limit widzów, odcinanie, dziennik wejść." },
  { id: "nowe", etykieta: "NOWE ŹRÓDŁO", opis: "Podłącz drona DJI albo kamerę IP — trzy kroki, na końcu hasło do wpisania w aparaturze." },
  { id: "zrodla", etykieta: "ŹRÓDŁA", opis: "Drony i kamery na stacji — nazwa, widoczność dla widzów, hasło, usuwanie." },
  { id: "archiwum", etykieta: "ARCHIWUM", opis: "Nagrywanie telemetrii i obrazu po stronie stacji, miejsce na dysku." },
  { id: "diagnostyka", etykieta: "DIAGNOSTYKA", opis: "Czy MediaMTX i telemetria żyją, adres publiczny stacji, rejestr techniczny." },
];

// Ostatnia karta zostaje zapamiętana w tej przeglądarce: kto dodaje trzy drony,
// nie chce po każdym otwarciu panelu zaczynać od zaproszeń. Klucz w przestrzeni
// `dron15.*` jak pozostałe — zob. sesja.js, dlaczego nazwy kluczy nie zmieniono.
const KLUCZ_KARTY = "dron15.admin.karta";

function zapamietanaKarta() {
  try {
    const k = localStorage.getItem(KLUCZ_KARTY);
    return KARTY.some((x) => x.id === k) ? k : KARTY[0].id;
  } catch {
    return KARTY[0].id;
  }
}

export default function Admin({ zrodla, naZmianeZrodel, naZamknij, panel, naPanel }) {
  const [karta, setKarta] = useState(zapamietanaKarta);
  const [blad, setBlad] = useState(null);
  // Uwaga wspólna dla obu kart źródeł: przełączenie nadawania (port 1935) wymaga
  // restartu usługi OBRAZ i ma być widoczne niezależnie od tego, w której karcie
  // padło kliknięcie.
  const [uwaga, setUwaga] = useState(null);

  useEffect(() => {
    try { localStorage.setItem(KLUCZ_KARTY, karta); } catch { /* prywatne okno — nie pamiętamy, nie szkodzi */ }
  }, [karta]);

  const wybierz = (id) => {
    setKarta(id);
    setBlad(null);
  };

  const aktywna = KARTY.find((k) => k.id === karta) || KARTY[0];

  return (
    <div className="zaslona panel admin">
      <div className="karta szeroka" onClick={(e) => e.stopPropagation()}>
        <NaglowekPanelu
          tytul="PANEL ADMINISTRATORA"
          panel={panel}
          naPanel={naPanel}
          naZamknij={naZamknij}
          rola="admin"
        />

        <nav className="zakladki-admina" aria-label="Karty panelu administratora">
          {KARTY.map((k) => (
            <button
              key={k.id}
              type="button"
              className={`zakladka ${karta === k.id ? "wlaczona" : ""}`}
              onClick={() => wybierz(k.id)}
              aria-pressed={karta === k.id}
              title={k.opis}
            >
              {k.etykieta}
            </button>
          ))}
        </nav>
        <p className="opis-karty">{aktywna.opis}</p>

        {blad && <p className="przypis blad">{blad}</p>}
        {uwaga && <p className="przypis blad">{uwaga}</p>}

        {karta === "zaproszenia" && <Zaproszenia naBlad={setBlad} />}
        {karta === "dostep" && <Dostep zrodla={zrodla} naBlad={setBlad} />}
        {karta === "nowe" && (
          <NoweZrodlo naZmianeZrodel={naZmianeZrodel} naBlad={setBlad} naUwaga={setUwaga} naKarta={wybierz} />
        )}
        {karta === "zrodla" && (
          <Zrodla naZmianeZrodel={naZmianeZrodel} naBlad={setBlad} naUwaga={setUwaga} naKarta={wybierz} />
        )}
        {karta === "archiwum" && <Archiwum naBlad={setBlad} />}
        {karta === "diagnostyka" && <Diagnostyka naBlad={setBlad} />}
      </div>
    </div>
  );
}
