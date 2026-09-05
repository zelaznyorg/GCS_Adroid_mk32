// Karta ARCHIWUM — nagrywanie telemetrii i obrazu po naszej stronie, miejsce na dysku.
import { useCallback, useEffect, useState } from "react";
import { api } from "../sesja";
import { ODSWIEZAJ_ARCHIWUM_MS, TRYBY_WIDEO, rozmiar, godzina, tekst } from "./pomoc";

export default function Archiwum({ naBlad }) {
  const [archiwum, setArchiwum] = useState(null);

  const pobierz = useCallback(() => {
    api("/api/admin/archiwum?ile=12").then(setArchiwum).catch(() => setArchiwum(null));
  }, []);

  useEffect(() => {
    pobierz();
    const t = setInterval(pobierz, ODSWIEZAJ_ARCHIWUM_MS);
    return () => clearInterval(t);
  }, [pobierz]);

  const zmien = (zmiany) =>
    api("/api/admin/archiwum", { method: "POST", body: zmiany })
      .then(() => {
        naBlad(null);
        pobierz();
      })
      .catch((e) => naBlad(tekst(e)));

  return (
    <section>
      <div className="rzad rozstrzelony">
        <div className="etykieta">ARCHIWUM</div>
        <span className={`dioda ${archiwum?.nagrywam ? "ok" : ""}`}>
          {archiwum?.nagrywam ? "TELEMETRIA — NAGRYWAM" : "TELEMETRIA — CISZA"}
        </span>
        <button type="button" className="przelacznik drobny" onClick={pobierz}>
          ODŚWIEŻ
        </button>
      </div>

      {!archiwum ? (
        <p className="przypis">brak danych o archiwum</p>
      ) : !archiwum.wlaczone ? (
        <p className="przypis blad">
          Archiwum wyłączone — nic się nie zapisuje. Włącza się je w <code>zrodla.json</code>.
        </p>
      ) : (
        <>
          <div className="rzad">
            {TRYBY_WIDEO.map((t) => (
              <button
                key={t.id}
                type="button"
                className={`przelacznik ${archiwum.wideo === t.id ? "wlaczony" : ""} ${t.id === "zawsze" && archiwum.wideo === t.id ? "pilne" : ""}`}
                onClick={() => zmien({ wideo: t.id })}
                title={t.opis}
              >
                {t.etykieta}
              </button>
            ))}
            <span className="przypis">nagrywanie obrazu</span>
          </div>
          <p className="przypis">{TRYBY_WIDEO.find((t) => t.id === archiwum.wideo)?.opis}</p>

          <div className="rzad">
            <label className="pole-etykieta">
              TRZYMAJ DNI
              <input
                type="number"
                className="pole waskie"
                min={1}
                max={3650}
                defaultValue={archiwum.trzymajDni}
                onBlur={(e) => zmien({ trzymajDni: e.target.value })}
              />
            </label>
            <label className="pole-etykieta">
              LIMIT GB
              <input
                type="number"
                className="pole waskie"
                min={1}
                defaultValue={archiwum.limitGb}
                onBlur={(e) => zmien({ limitGb: e.target.value })}
              />
            </label>
            <button
              type="button"
              className="przelacznik drobny"
              onClick={() =>
                api("/api/admin/archiwum/sprzataj", { method: "POST" })
                  .then((o) => setArchiwum(o.stan))
                  .catch((e) => naBlad(tekst(e)))
              }
              title="Kasuje najstarsze nagrania ponad limit. Bieżącego nagrania nie rusza."
            >
              SPRZĄTAJ TERAZ
            </button>
          </div>

          <div className="pasek-miejsca">
            <div
              className="wypelnienie"
              style={{
                width: `${Math.min(100, (archiwum.zajeteBajtow / (archiwum.limitGb * 1024 ** 3)) * 100).toFixed(1)}%`,
              }}
            />
          </div>
          <p className="przypis">
            {rozmiar(archiwum.zajeteBajtow)} z {archiwum.limitGb} GB · {archiwum.plikow} plików
            {archiwum.wolneBajtow != null && ` · wolne na dysku: ${rozmiar(archiwum.wolneBajtow)}`}
            {" · "}
            <code>{archiwum.katalog}</code>
          </p>

          <table className="tabela">
            <tbody>
              {archiwum.pliki.map((f) => (
                <tr key={`${f.rodzaj}-${f.nazwa}-${f.czas}`}>
                  <td className="przypis">{f.rodzaj}</td>
                  <td>{f.nazwa}</td>
                  <td className="przypis">{rozmiar(f.bajtow)}</td>
                  <td className="przypis">{godzina(f.czas)}</td>
                </tr>
              ))}
              {archiwum.pliki.length === 0 && (
                <tr><td colSpan={4} className="przypis">archiwum puste</td></tr>
              )}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
