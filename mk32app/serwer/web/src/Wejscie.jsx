// Ekran wejścia — jedyne, co widzi ktoś bez ważnego żetonu do tej stacji.
//
// Do 2026-08-23 pytał wyłącznie o kod, bo adres był oczywisty: stronę serwowała
// stacja, więc żeby zobaczyć ten ekran, trzeba było już do niej dosięgnąć.
// Na telefonie tak nie jest — aplikacja z pulpitu wstaje spod adresu, który mógł
// przestać istnieć (tunel, CGNAT, inna sieć). Dlatego pyta teraz o dwie rzeczy:
// **gdzie jest stacja** i **kim jesteś**.
//
// Kod połączeniowy (`D15-…`) niesie oba naraz — wtedy pole adresu wypełnia się samo
// i nie ma czego wpisywać. Zwykły kod zaproszenia nadal działa; wymaga tylko podania
// adresu, tak jak dotąd wymagał otwarcia strony pod właściwym adresem.
import { useEffect, useMemo, useState } from "react";
import {
  przyjmijZaproszenie, kodZAdresu, wyczyscKodZAdresu, rozbierzKod,
  adresStacji, listaStacji, zapomnijStacje, normalizujAdres, ustawStacje,
  zeton, api, BladLacza, BladDostepu,
} from "./sesja";

const POWODY = {
  brak: "Ta stacja wymaga zaproszenia.",
  wygasle: "Zaproszenie straciło ważność.",
  odciety: "Administrator zakończył Twój dostęp.",
  komplet: "Komplet widzów — spróbuj za chwilę.",
  cisza: "Stacja wstrzymała podgląd.",
  nieosiagalna: "Stacja nie odpowiada pod zapamiętanym adresem.",
};

const czasOd = (t) => {
  if (!t) return "";
  const m = Math.round((Date.now() - t) / 60000);
  if (m < 1) return "przed chwilą";
  if (m < 60) return `${m} min temu`;
  const g = Math.round(m / 60);
  if (g < 24) return `${g} godz. temu`;
  return `${Math.round(g / 24)} dni temu`;
};

export default function Wejscie({ powod = "brak", naWejscie }) {
  const [kod, setKod] = useState("");
  const [adres, setAdres] = useState(() => adresStacji());
  const [blad, setBlad] = useState(null);
  const [trwa, setTrwa] = useState(false);
  const [znane, setZnane] = useState(() => listaStacji());

  // Kod połączeniowy sam podaje adres — pole adresu przestaje być wtedy pytaniem
  // i staje się potwierdzeniem. Rozbieramy przy każdej zmianie, żeby widz od razu
  // widział, dokąd ten kod prowadzi, zanim cokolwiek wyśle.
  const zKodu = useMemo(() => (kod.trim() ? rozbierzKod(kod) : null), [kod]);
  const adresZKodu = zKodu?.adres ?? null;
  const adresDocelowy = adresZKodu ?? normalizujAdres(adres);

  const wejdz = (surowy, adresPola) => {
    setTrwa(true);
    setBlad(null);
    return przyjmijZaproszenie(surowy, adresPola)
      .then((d) => {
        wyczyscKodZAdresu();
        naWejscie(d);
      })
      .catch((e) => {
        setBlad(
          e instanceof BladLacza
            ? `Nie ma łączności ze stacją ${e.adres}. Sprawdź adres i czy tunel jest zestawiony.`
            : String(e.message || e),
        );
        setZnane(listaStacji());
      })
      .finally(() => setTrwa(false));
  };

  // Kod z adresu wymieniamy od razu, bez pytania — kliknięcie w link JEST zgodą.
  useEffect(() => {
    const zAdresu = kodZAdresu();
    if (!zAdresu) return;
    // Kod w adresie jest zewnętrznym wejściem i celowo rozpoczyna zmianę stanu sesji.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    wejdz(zAdresu, null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const wyslij = (e) => {
    e.preventDefault();
    if (!kod.trim() || trwa) return;
    wejdz(kod.trim(), adres);
  };

  // Powrót do stacji, w której już byliśmy. Żeton jest przypisany do adresu, więc
  // kod jest tu niepotrzebny — sprawdzamy tylko, czy nadal jest ważny i czy stacja
  // odpowiada. Bez tego lista byłaby wyłącznie ozdobnym wypełniaczem pola adresu.
  const wrocDo = (docelowa) => {
    if (trwa) return;
    if (!zeton(docelowa)) {
      // Adres znamy, ale żetonu do niego nie mamy (albo został unieważniony).
      // Wtedy lista podpowiada tylko adres, a kod trzeba wpisać.
      setAdres(docelowa);
      setBlad(null);
      return;
    }
    setTrwa(true);
    setBlad(null);
    ustawStacje(docelowa);
    api("/api/ja", { adres: docelowa })
      .then(naWejscie)
      .catch((e) => {
        setAdres(docelowa);
        setBlad(
          e instanceof BladLacza
            ? `Stacja ${docelowa.replace(/^https?:\/\//, "")} nie odpowiada. Sprawdź, czy tunel jest zestawiony.`
            : e instanceof BladDostepu && e.status === 401
              ? "Żeton do tej stacji stracił ważność — potrzebny nowy kod."
              : String(e.message || e),
        );
        setZnane(listaStacji());
      })
      .finally(() => setTrwa(false));
  };

  return (
    <div className="ekran wejscie">
      <form className="karta-wejscia" onSubmit={wyslij}>
        <h1>DRON 15 — PODGLĄD</h1>
        <p className="przypis">{POWODY[powod] || POWODY.brak}</p>

        {/* Stacje, w których już byliśmy. Żeton jest przypisany do adresu, więc
            powrót do znanej stacji nie wymaga kodu — wystarczy dotknięcie. */}
        {znane.length > 0 && (
          <section>
            <span className="etykieta">STACJE, W KTÓRYCH JUŻ BYŁEŚ</span>
            <p className="przypis drobne">
              Dotknięcie adresu wchodzi od razu — żeton jest zapamiętany osobno
              dla każdej stacji i nie wymaga kodu drugi raz.
            </p>
            <ul className="lista-adresow">
              {znane.map((s) => (
                <li key={s.adres}>
                  <button
                    type="button"
                    className="przelacznik drobny"
                    onClick={() => wrocDo(s.adres)}
                    disabled={trwa}
                    title={s.imie ? `wejdź jako ${s.imie}` : "wybierz ten adres"}
                  >
                    {s.adres.replace(/^https?:\/\//, "")}
                  </button>
                  <span className="przypis drobne">
                    {s.imie ? `${s.imie} · ` : ""}{czasOd(s.ostatnio)}
                  </span>
                  <button
                    type="button"
                    className="przelacznik drobny pilne"
                    onClick={() => { zapomnijStacje(s.adres); setZnane(listaStacji()); }}
                    title="Zapomnij ten adres i żeton do niego"
                  >
                    ZAPOMNIJ
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}

        <label className="pole-etykieta" htmlFor="kod">
          KOD AUTORYZACJI
          <input
            id="kod"
            className="pole"
            value={kod}
            onChange={(e) => setKod(e.target.value)}
            autoComplete="off"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            placeholder="wklej kod od administratora stacji"
          />
        </label>

        {/* Adres jest pytaniem tylko wtedy, gdy kod go nie niesie. */}
        {adresZKodu ? (
          <p className="przypis">
            Ten kod prowadzi do <code>{adresZKodu.replace(/^https?:\/\//, "")}</code> —
            adresu nie trzeba wpisywać.
          </p>
        ) : (
          <label className="pole-etykieta" htmlFor="adres">
            ADRES STACJI
            <input
              id="adres"
              className="pole"
              value={adres}
              onChange={(e) => setAdres(e.target.value)}
              autoComplete="off"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              inputMode="url"
              placeholder="np. 192.168.1.50 albo 10.7.0.1:8095"
            />
          </label>
        )}

        {blad && <p className="przypis blad">{blad}</p>}

        <button
          type="submit"
          className="przelacznik"
          disabled={trwa || !kod.trim() || !adresDocelowy}
        >
          {trwa ? "ŁĄCZĘ…" : "POŁĄCZ"}
        </button>

        <p className="przypis drobne">
          Zaproszenia wydaje administrator stacji. Sam kod nie wystarczy — trzeba jeszcze
          mieć drogę do stacji: być w jej sieci albo w jej tunelu WireGuard. Port domyślny
          to 8095, więc przy typowym ustawieniu wpisuje się sam adres.
        </p>
      </form>
    </div>
  );
}
