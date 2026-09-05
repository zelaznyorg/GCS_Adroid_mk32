// Karta ZAPROSZENIA — jedno pytanie: kogo wpuścić.
//
// Trzy kroki od góry do dołu: wydaj zaproszenie → dostań link/kod → sprawdź
// listę ważnych. Adresy stacji są na końcu, bo link bierze adres z paska
// przeglądarki i bywa, że trzeba wiedzieć, jaki to adres.
import { useCallback, useEffect, useState } from "react";
import { api, zbudujKodPolaczenia } from "../sesja";
import { ODSWIEZAJ_MS, WAZNOSC, ROLE, godzina, linkDo, zaznacz, tekst, NA_LOKALNYM } from "./pomoc";

export default function Zaproszenia({ naBlad }) {
  const [lista, setLista] = useState(null);
  const [adresy, setAdresy] = useState(null);
  const [nowyLink, setNowyLink] = useState(null);

  const [imie, setImie] = useState("");
  const [rola, setRola] = useState("widz");
  const [waznosc, setWaznosc] = useState(1); // domyślnie 1 dzień
  const [jednorazowe, setJednorazowe] = useState(true);

  const odswiez = useCallback(() => {
    api("/api/admin/stan")
      .then((s) => setLista(s.zaproszenia || []))
      .catch((e) => naBlad(tekst(e)));
  }, [naBlad]);

  useEffect(() => {
    odswiez();
    const t = setInterval(odswiez, ODSWIEZAJ_MS);
    return () => clearInterval(t);
  }, [odswiez]);

  useEffect(() => {
    api("/api/adresy").then(setAdresy).catch(() => setAdresy(null));
  }, []);

  const dzialanie = (obietnica) =>
    obietnica
      .then(() => {
        naBlad(null);
        odswiez();
      })
      .catch((e) => naBlad(tekst(e)));

  const zapros = (e) => {
    e.preventDefault();
    if (!imie.trim()) return;
    const w = WAZNOSC[waznosc];
    api("/api/admin/zaproszenie", {
      method: "POST",
      body: { imie: imie.trim(), rola, waznoscMin: w.min, jednorazowe },
    })
      .then((z) => {
        naBlad(null);
        setNowyLink({ imie: z.imie, link: linkDo(z.kod), kod: z.kod });
        setImie("");
        odswiez();
      })
      .catch((err) => naBlad(tekst(err)));
  };

  // Kod zaproszenia da się pokazać ponownie — serwer trzyma go do unieważnienia.
  // Bez tego zamknięcie okienka z linkiem znaczyłoby, że trzeba wydać nowe
  // zaproszenie tej samej osobie, a stare zostaje wiszące i ważne.
  const pokazLink = (z) =>
    api(`/api/admin/zaproszenie/${z.id}/kod`)
      .then((d) => setNowyLink({ imie: z.imie, link: linkDo(d.kod), kod: d.kod }))
      .catch((e) => naBlad(tekst(e)));

  const wazne = (lista || []).filter((z) => z.wazne);
  const opisRoli = ROLE.find((r) => r.id === rola)?.opis;

  return (
    <>
      <section>
        <div className="etykieta">1. WYDAJ ZAPROSZENIE</div>
        <form className="rzad" onSubmit={zapros}>
          <label className="pole-etykieta rozciagnij">
            DLA KOGO
            <input
              className="pole"
              placeholder="imię albo nazwa urządzenia"
              value={imie}
              onChange={(e) => setImie(e.target.value)}
              autoComplete="off"
            />
          </label>
          <label className="pole-etykieta">
            ROLA
            <select className="pole" value={rola} onChange={(e) => setRola(e.target.value)}>
              {ROLE.map((r) => <option key={r.id} value={r.id}>{r.id}</option>)}
            </select>
          </label>
          <label className="pole-etykieta">
            WAŻNE
            <select className="pole" value={waznosc} onChange={(e) => setWaznosc(Number(e.target.value))}>
              {WAZNOSC.map((w, i) => <option key={w.etykieta} value={i}>{w.etykieta}</option>)}
            </select>
          </label>
          <label className="pole-etykieta poziomo">
            <input type="checkbox" checked={jednorazowe} onChange={(e) => setJednorazowe(e.target.checked)} />
            jednorazowe
          </label>
          <button type="submit" className="przelacznik" disabled={!imie.trim()}>ZAPROŚ</button>
        </form>
        <p className="przypis">
          <strong>{rola}</strong> — {opisRoli}.{" "}
          {jednorazowe
            ? "Kod jednorazowy gaśnie po pierwszym wejściu — dla kafelka stacji, kiosku i urządzeń otwierających stronę wielokrotnie odznacz „jednorazowe”."
            : "Kod wielokrotny wpuszcza każde kolejne okno — właściwy dla kafelka stacji i kiosku; unieważnij go, gdy przestanie być potrzebny."}
        </p>
      </section>

      {nowyLink && (
        <section className="blok wynik">
          <div className="etykieta">2. ZAPROSZENIE DLA {nowyLink.imie.toUpperCase()} — GOTOWE</div>
          <div className="przypis">Link — wyślij dowolną drogą; otwiera stronę i wpuszcza:</div>
          <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">{nowyLink.link}</code>
          {/* Sam kod, do wklejenia ręcznie na ekranie wejścia. Potrzebny wtedy,
              gdy gość wchodzi z innego adresu niż ten w linku — albo gdy link
              po drodze rozjedzie się w komunikatorze. */}
          <div className="przypis">Sam kod — do wpisania na ekranie wejścia, gdy strona jest już otwarta:</div>
          <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">{nowyLink.kod}</code>
          {/* Dla aplikacji na telefonie: jeden ciąg niosący kod I adres stacji.
              Sam kod wystarcza tylko temu, kto już umie otworzyć stronę stacji. */}
          <div className="przypis">
            <strong>Kod połączeniowy</strong> — dla aplikacji na telefonie; niesie też adres
            stacji, więc nie trzeba go wpisywać osobno:
          </div>
          <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">
            {zbudujKodPolaczenia(window.location.origin, nowyLink.kod)}
          </code>
          {NA_LOKALNYM && (
            <p className="przypis blad">
              Masz otwarty panel na <code>{window.location.hostname}</code>, więc link
              <strong> i kod połączeniowy</strong> zadziałają <strong>tylko na tej
              maszynie</strong> — oba biorą adres stąd. Wyślij sam kod, albo otwórz
              panel pod adresem, którym łączy się gość — lista adresów jest niżej.
            </p>
          )}
          <div className="rzad">
            <button type="button" className="przelacznik drobny" onClick={() => setNowyLink(null)}>SCHOWAJ</button>
          </div>
        </section>
      )}

      <section>
        <div className="etykieta">{nowyLink ? "3." : "2."} WAŻNE ZAPROSZENIA — {lista ? wazne.length : "…"}</div>
        <table className="tabela">
          <tbody>
            {wazne.map((z) => (
              <tr key={z.id}>
                <td>{z.imie}</td>
                <td className="przypis">{z.rola}</td>
                <td className="przypis">{z.jednorazowe ? "jednorazowe" : "wielokrotne"}</td>
                <td className="przypis">{z.wygasa ? `do ${godzina(z.wygasa)}` : "bezterminowo"}</td>
                <td className="przypis">{z.uzyte ? `użyte ${z.uzyte}×` : "nieużyte"}</td>
                <td>
                  <div className="rzad">
                    <button
                      type="button"
                      className="przelacznik drobny"
                      onClick={() => pokazLink(z)}
                      title="Pokaż link i kod jeszcze raz — bez wydawania nowego zaproszenia"
                    >
                      POKAŻ LINK
                    </button>
                    <button
                      type="button"
                      className="przelacznik drobny pilne"
                      onClick={() => dzialanie(api(`/api/admin/zaproszenie/${z.id}`, { method: "DELETE" }))}
                      title="Kod przestaje wpuszczać. Kto już wszedł, zostaje — odcina go karta DOSTĘP"
                    >
                      UNIEWAŻNIJ
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {lista && wazne.length === 0 && (
              <tr><td colSpan={6} className="przypis">brak ważnych zaproszeń</td></tr>
            )}
          </tbody>
        </table>
      </section>

      <section>
        <div className="etykieta">ADRESY STACJI — link zaproszenia bierze adres z paska przeglądarki</div>
        <ul className="lista-adresow">
          {(adresy?.adresy || []).map((a) => (
            <li key={`${a.interfejs}-${a.adres}`}>
              <span className="przypis">{a.interfejs}</span>
              <code>{`http://${a.adres}:${adresy.porty.strona}`}</code>
            </li>
          ))}
          {adresy && (adresy.adresy || []).length === 0 && <li className="przypis">brak adresów w sieci lokalnej</li>}
        </ul>
        <p className="przypis">
          Gość z tej samej sieci dostaje link z adresem lokalnym. Dla gościa spoza sieci
          endpoint WireGuarda jest w karcie DIAGNOSTYKA.
        </p>
      </section>
    </>
  );
}
