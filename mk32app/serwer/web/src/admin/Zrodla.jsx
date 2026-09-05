import React from "react";
// Karta ŹRÓDŁA — stan i zarządzanie: drony i kamery, które stacja pokazuje.
//
// Nazwa, widoczność dla widzów, hasło drona, usuwanie. Dodawanie jest osobną
// kartą (NOWE ŹRÓDŁO) — tu jest to, co już jest. Dane do aparatury (hasło, adresy)
// otwierają się w osobnym, pełnoszerokim wierszu pod źródłem — patrz DaneAparatury.jsx.
import { useCallback, useEffect, useState } from "react";
import { api } from "../sesja";
import { ODSWIEZAJ_MS, tekst } from "./pomoc";
import DaneAparatury from "./DaneAparatury";

export default function Zrodla({ naZmianeZrodel, naBlad, naUwaga, naKarta }) {
  const [info, setInfo] = useState(null);
  // Usunięcie źródła zabiera obraz każdemu, kto na nie patrzy — dwa kliknięcia,
  // jak przy restarcie usługi; uzbrojenie mija po 5 s.
  const [usunUzbrojone, setUsunUzbrojone] = useState(null);
  const [otwarte, setOtwarte] = useState({});     // które źródła mają rozwinięte dane do aparatury
  const [pokazHaslo, setPokazHaslo] = useState({});

  const pobierz = useCallback(() => {
    api("/api/admin/zrodla").then(setInfo).catch((e) => naBlad(tekst(e)));
  }, [naBlad]);

  useEffect(() => {
    pobierz();
    const t = setInterval(pobierz, ODSWIEZAJ_MS);
    return () => clearInterval(t);
  }, [pobierz]);

  useEffect(() => {
    if (!usunUzbrojone) return undefined;
    const t = setTimeout(() => setUsunUzbrojone(null), 5000);
    return () => clearTimeout(t);
  }, [usunUzbrojone]);

  // Zmiana źródeł ma być widoczna od razu: tu i na ekranie głównym (mozaika,
  // lista widza) — stąd oba odświeżenia.
  const dzialanie = (obietnica) =>
    obietnica
      .then((o) => {
        naBlad(null);
        if (o?.zrodla) setInfo((i) => ({ ...(i || {}), zrodla: o.zrodla }));
        naUwaga(
          o?.wymagaRestartuObrazu
            ? "Zmieniło się, czy stacja przyjmuje nadawanie (port 1935). MediaMTX otworzy go dopiero po restarcie usługi OBRAZ — panel STACJA."
            : null
        );
        pobierz();
        naZmianeZrodel?.();
      })
      .catch((e) => naBlad(tekst(e)));

  const sciezka = (id) => `/api/admin/zrodla/${encodeURIComponent(id)}`;

  const usun = (id) => {
    if (usunUzbrojone !== id) {
      setUsunUzbrojone(id);
      return;
    }
    setUsunUzbrojone(null);
    dzialanie(api(sciezka(id), { method: "DELETE" }));
  };

  const lista = info?.zrodla || [];
  const maks = info?.maks ?? 6;

  // Dwa drony z tym samym hasłem to pułapka: zrzut ekranu nie podaje ścieżki, więc
  // stacja przypisze go pierwszemu z listy. Pokazujemy to przy danych do aparatury.
  const duplikaty = (z) =>
    lista.filter((o) => o.nadawany && o.id !== z.id && o.haslo && o.haslo === z.haslo).map((o) => o.nazwa);

  return (
    <>
      <section>
        <div className="rzad rozstrzelony">
          <div className="etykieta">ŹRÓDŁA OBRAZU — {info ? lista.length : "…"} z {maks}</div>
          <button type="button" className="przelacznik drobny" onClick={() => naKarta?.("nowe")} disabled={lista.length >= maks}>
            + NOWE ŹRÓDŁO
          </button>
        </div>

        <table className="tabela">
          <tbody>
            {lista.map((z) => (
              <React.Fragment key={z.id}>
                <tr>
                  <td>
                    <span
                      className={`dioda ${z.zywe ? "ok" : ""}`}
                      title={z.zywe ? "strumień idzie" : z.nadawany ? "czeka na nadawcę" : "nikt nie ogląda — pobieranie na żądanie"}
                    >
                      {z.zywe ? "NADAJE" : z.nadawany ? "CZEKA" : "GOTOWE"}
                    </span>
                  </td>
                  <td>
                    <input
                      type="text"
                      className="pole"
                      defaultValue={z.nazwa}
                      title="Nazwa widoczna dla widzów — zmiana zapisuje się po wyjściu z pola"
                      onBlur={(e) => {
                        const nazwa = e.target.value.trim();
                        if (nazwa && nazwa !== z.nazwa) dzialanie(api(sciezka(z.id), { method: "PUT", body: { nazwa } }));
                      }}
                    />
                    <div className="przypis drobne">
                      <code>{z.id}</code> · {z.nadawany ? "nadawane (dron wypycha obraz)" : "pobierane (stacja ściąga RTSP)"}
                      {z.czytelnikow ? ` · ogląda: ${z.czytelnikow}` : ""}
                    </div>
                    {!z.nadawany && (
                      <div className="przypis drobne">
                        <code>{z.rtspGlowny}</code>
                        {z.rtspPomocniczy ? <> · pomocniczy <code>{z.rtspPomocniczy}</code></> : null}
                      </div>
                    )}
                  </td>
                  <td>
                    <div className="rzad">
                      <button
                        type="button"
                        className={`przelacznik drobny ${z.widoczne ? "wlaczony" : ""}`}
                        onClick={() => dzialanie(api(sciezka(z.id), { method: "PUT", body: { widoczne: !z.widoczne } }))}
                        title={z.widoczne ? "Widzowie widzą to źródło — kliknij, żeby ukryć" : "Ukryte przed widzami — kliknij, żeby pokazać"}
                      >
                        {z.widoczne ? "WIDOCZNE" : "UKRYTE"}
                      </button>
                      {z.nadawany && (
                        <>
                          <button
                            type="button"
                            className={`przelacznik drobny ${otwarte[z.id] ? "wlaczony" : ""}`}
                            onClick={() => setOtwarte((o) => ({ ...o, [z.id]: !o[z.id] }))}
                            title="Hasło i adresy do wpisania w aparaturze — w osobnym wierszu, w całości"
                          >
                            {otwarte[z.id] ? "ZWIŃ DANE" : "DANE APARATURY"}
                          </button>
                          <button
                            type="button"
                            className="przelacznik drobny"
                            onClick={() => dzialanie(api(`${sciezka(z.id)}/nowe-haslo`, { method: "POST" }))}
                            title="Nowe hasło — adres wpisany w aparaturze przestanie działać"
                          >
                            NOWE HASŁO
                          </button>
                        </>
                      )}
                      <button
                        type="button"
                        className={`przelacznik drobny ${usunUzbrojone === z.id ? "pilne" : ""}`}
                        onClick={() => usun(z.id)}
                        title="Usuwa źródło i jego ścieżkę — obraz zniknie każdemu, kto na nie patrzy"
                      >
                        {usunUzbrojone === z.id ? "NA PEWNO?" : "USUŃ"}
                      </button>
                    </div>
                  </td>
                </tr>
                {z.nadawany && otwarte[z.id] && (
                  <tr className="wiersz-danych">
                    <td colSpan={3}>
                      <DaneAparatury
                        zrodlo={z}
                        pokaz={Boolean(pokazHaslo[z.id])}
                        naPokaz={() => setPokazHaslo((p) => ({ ...p, [z.id]: !p[z.id] }))}
                        duplikaty={duplikaty(z)}
                      />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            ))}
            {info && lista.length === 0 && (
              <tr><td colSpan={3} className="przypis">brak źródeł — dodaj pierwsze w karcie NOWE ŹRÓDŁO</td></tr>
            )}
          </tbody>
        </table>

        <p className="przypis">
          NADAJE — obraz idzie. CZEKA — dron jeszcze nie nadaje. GOTOWE — kamera IP, stacja
          pobierze ją, gdy ktoś wybierze. Ukryte źródło ma ścieżkę i przyjmuje obraz, ale nie
          trafia na listę widza ani do mozaiki.
        </p>
      </section>
    </>
  );
}
