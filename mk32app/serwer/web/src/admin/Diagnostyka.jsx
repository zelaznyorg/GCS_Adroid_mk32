// Karta DIAGNOSTYKA — czy sprzęt strony serwerowej działa i co się zepsuło.
//
// Stan MediaMTX i telemetrii, sesje obrazu, adres publiczny stacji (WireGuard)
// i rejestr techniczny. To narzędzie do szukania usterek, nie wskaźnik, na który
// patrzy się w kółko — stąd rzadsze odświeżanie i osobna karta.
import { useCallback, useEffect, useState } from "react";
import { api } from "../sesja";
import { ODSWIEZAJ_MS, godzina, zaznacz, tekst } from "./pomoc";

export default function Diagnostyka({ naBlad }) {
  const [stan, setStan] = useState(null);
  const [adresy, setAdresy] = useState(null);
  const [logi, setLogi] = useState(null);
  const [poziom, setPoziom] = useState("info");

  const odswiez = useCallback(() => {
    api("/api/admin/stan").then(setStan).catch((e) => naBlad(tekst(e)));
  }, [naBlad]);

  useEffect(() => {
    odswiez();
    api("/api/adresy").then(setAdresy).catch(() => setAdresy(null));
    const t = setInterval(odswiez, ODSWIEZAJ_MS);
    return () => clearInterval(t);
  }, [odswiez]);

  const pobierzLogi = useCallback((p) => {
    api(`/api/admin/logi?ile=120&poziom=${p}`).then(setLogi).catch((e) => naBlad(tekst(e)));
  }, [naBlad]);

  useEffect(() => { pobierzLogi(poziom); }, [pobierzLogi, poziom]);

  return (
    <>
      <section>
        <div className="etykieta">USŁUGI I ŁĄCZA</div>
        <div className="rzad rozstrzelony">
          <span className={`dioda ${stan?.mediamtx ? "ok" : "zla"}`}>MEDIAMTX</span>
          <span className={`dioda ${stan?.telemetria?.zywe ? "ok" : "zla"}`}>TELEMETRIA</span>
          <span className="przypis">sesji obrazu: {stan?.sesjeObrazu?.length ?? 0}</span>
        </div>
        <p className="przypis">
          Restart usług, zasilanie i sieć są w panelu STACJA — to osobne pytanie: czy sprzęt działa.
        </p>
      </section>

      <section>
        <div className="etykieta">ADRES PUBLICZNY STACJI</div>
        {adresy?.publiczny?.endpoint ? (
          <>
            <div className="przypis">ENDPOINT WIREGUARD — do wpisania w kliencie</div>
            <code className="endpoint" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">
              {adresy.publiczny.endpoint}
            </code>
            <div className="przypis">
              adres z {adresy.publiczny.zrodlo}, sprzed {adresy.publiczny.wiek_s} s.
              Pokazujemy adres, spod którego stacja WYCHODZI w świat — jeśli sama siedzi
              za komercyjnym VPN-em, połączenia przychodzące i tak nie zadziałają.
            </div>
          </>
        ) : (
          <div className="przypis blad">
            Adres publiczny nieustalony{adresy?.publiczny?.blad ? `: ${adresy.publiczny.blad}` : ""}.
          </div>
        )}
      </section>

      <section>
        <div className="rzad rozstrzelony">
          <div className="etykieta">REJESTR TECHNICZNY — co się zepsuło</div>
          <select className="pole" value={poziom} onChange={(e) => setPoziom(e.target.value)}>
            <option value="blad">same błędy</option>
            <option value="ostrzezenie">błędy i ostrzeżenia</option>
            <option value="info">wszystko poza szczegółami</option>
            <option value="szczegol">ze szczegółami</option>
          </select>
          <button type="button" className="przelacznik drobny" onClick={() => pobierzLogi(poziom)}>
            ODŚWIEŻ
          </button>
        </div>

        <ul className="dziennik rejestr">
          {(logi?.wpisy || []).map((w, i) => (
            <li key={`${w.czas}-${i}`}>
              <span className="przypis">{godzina(w.czas)}</span>
              <span className={`znacznik-zdarzenia poziom-${w.poziom}`}>{w.poziom}</span>
              <span className="przypis">{w.obszar}</span>
              <span className="tresc-wpisu">
                {w.wiadomosc}
                {w.kontekst?.blad ? ` — ${w.kontekst.blad}` : ""}
                {w.kontekst?.stos && <span className="stos">{w.kontekst.stos}</span>}
              </span>
            </li>
          ))}
          {logi && logi.wpisy.length === 0 && <li className="przypis">czysto — nic się nie zepsuło</li>}
        </ul>
        {logi?.plik && <p className="przypis">pełny zapis: <code>{logi.plik}</code></p>}
      </section>
    </>
  );
}
