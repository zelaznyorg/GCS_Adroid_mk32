// Lista „kto teraz ogląda" — dla widza, nie dla administratora.
//
// Pokazuje imiona (decyzja 8): przy pięciu znajomych osobach anonimowość niczego
// nie chroni, a lista bez imion niewiele mówi. Adresów IP tu nie ma — te widzi
// wyłącznie administrator w swoim panelu.
import { useEffect, useState } from "react";
import { api } from "./sesja";
import NaglowekPanelu from "./NaglowekPanelu";

const ODSWIEZAJ_MS = 5000;

function czas(s) {
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  const g = Math.floor(s / 3600);
  return `${g} h ${Math.floor((s % 3600) / 60)} min`;
}

export default function Widzowie({ zrodla, naZamknij, panel, naPanel, rola = "widz" }) {
  const [dane, setDane] = useState(null);
  const [blad, setBlad] = useState(null);

  useEffect(() => {
    let zywy = true;
    const pobierz = () =>
      api("/api/widzowie")
        .then((d) => zywy && setDane(d))
        .catch((e) => zywy && setBlad(String(e.message || e)));
    pobierz();
    const t = setInterval(pobierz, ODSWIEZAJ_MS);
    return () => { zywy = false; clearInterval(t); };
  }, []);

  const nazwaZrodla = (id) => {
    if (!id) return "—";
    const pom = id.endsWith("_pom");
    const bazowe = pom ? id.slice(0, -4) : id;
    const z = zrodla.find((x) => x.id === bazowe);
    return `${z ? z.nazwa : bazowe}${pom ? " · pomocniczy" : ""}`;
  };

  return (
    <div className="zaslona panel" onClick={naZamknij}>
      <div className="karta" onClick={(e) => e.stopPropagation()}>
        <NaglowekPanelu
          tytul="OGLĄDA TERAZ"
          panel={panel}
          naPanel={naPanel}
          naZamknij={naZamknij}
          rola={rola}
        />

        {blad && <p className="przypis blad">{blad}</p>}
        {!dane && !blad && <p className="przypis">…</p>}

        <ul className="lista-widzow">
          {(dane?.widzowie || []).map((w) => (
            <li key={w.id} className={w.zetonId === dane.ja ? "ja" : ""}>
              <span className={`kropka ${w.rola}`} aria-hidden="true" />
              <span className="imie">{w.imie}</span>
              {w.rola !== "widz" && <span className="odznaka">{w.rola.toUpperCase()}</span>}
              <span className="przypis rozciagnij">{nazwaZrodla(w.zrodlo)}</span>
              <span className="przypis">{czas(w.sekund)}</span>
              {w.zetonId === dane.ja && <span className="odznaka ty">TY</span>}
            </li>
          ))}
        </ul>

        {dane && dane.widzowie.length === 0 && (
          <p className="przypis">Nikogo nie ma — nawet Ciebie, co znaczy, że lista się myli.</p>
        )}

      </div>
    </div>
  );
}
