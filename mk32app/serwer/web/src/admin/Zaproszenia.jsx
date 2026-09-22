// Karta ZAPROSZENIA — jedno pytanie: kogo wpuścić.
//
// Droga jest jedna i idzie z góry na dół: wybierz KOMU i NA JAK DŁUGO → wydaj →
// pokaż gościowi kod QR albo wyślij link → sprawdź listę ważnych.
//
// ### Trzy rzeczy, które zmieniły się 2026-09-22 (audyt panelu)
//
// 1. **Kod QR.** Dotąd wpuszczenie kogoś z telefonem znaczyło przepisanie 24
//    znaków z ekranu stacji — w polu, w rękawicach, przy słońcu. Teraz gość celuje
//    aparatem w ekran. Kod da się powiększyć na pół ekranu, zgrać na pendrive
//    i wysłać dalej, gdy panel jest otwarty na telefonie (KodQr.jsx).
// 2. **Adres jest wyborem, nie skutkiem ubocznym.** Link brał adres z paska
//    przeglądarki, więc panel otwarty na stanowisku (`127.0.0.1`) produkował
//    zaproszenia martwe dla wszystkich poza tą maszyną. Panel o tym ostrzegał,
//    ale poprawić się tego nie dało inaczej niż otwarciem panelu pod innym adresem.
// 3. **Zestawy.** Trzy sytuacje wracają w kółko — gość na jeden lot, ktoś z zespołu
//    na stałe, ekran stacji. Zamiast ustawiać za każdym razem trzy pola, klika się
//    jeden klawisz; pola zostają widoczne, więc nadal da się zrobić inaczej.
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, zbudujKodPolaczenia } from "../sesja";
import { ODSWIEZAJ_MS, WAZNOSC, ROLE, godzina, zostalo, linkDoNa, zaznacz, tekst, NA_LOKALNYM } from "./pomoc";
import KodQr from "./KodQr";

/**
 * Zestawy dla trzech sytuacji, które wracają w kółko.
 *
 * ⚠ Zestaw ustawia pola, ale NIE wydaje zaproszenia — imię i tak trzeba podać,
 * a dziennik dostępu bez imienia jest wart tyle, co lista adresów IP.
 */
const ZESTAWY = [
  {
    id: "gosc",
    etykieta: "GOŚĆ NA JEDEN LOT",
    opis: "widz · 1 dzień · jednorazowe",
    rola: "widz",
    waznosc: 1,
    jednorazowe: true,
  },
  {
    id: "zespol",
    etykieta: "KTOŚ Z ZESPOŁU",
    opis: "widz · bezterminowo · wielokrotne",
    rola: "widz",
    waznosc: 2,
    jednorazowe: false,
  },
  {
    id: "stanowisko",
    etykieta: "EKRAN STACJI",
    opis: "admin · bezterminowo · wielokrotne",
    rola: "admin",
    waznosc: 2,
    jednorazowe: false,
  },
];

export default function Zaproszenia({ naBlad }) {
  const [lista, setLista] = useState(null);
  const [adresy, setAdresy] = useState(null);
  const [nowyLink, setNowyLink] = useState(null);   // { imie, kod, rola }
  const [wybranyAdres, setWybranyAdres] = useState(null);

  const [imie, setImie] = useState("");
  const [rola, setRola] = useState("widz");
  const [waznosc, setWaznosc] = useState(1); // domyślnie 1 dzień
  const [jednorazowe, setJednorazowe] = useState(true);
  const poleImienia = useRef(null);

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

  // Adresy, którymi gość może przyjść. Pierwszy jest ten, spod którego patrzy admin —
  // ale gdy to pętla zwrotna, domyślnie proponujemy adres sieciowy, bo link
  // z `127.0.0.1` nie wpuści nikogo poza tą jedną maszyną.
  const adresyDoWyboru = useMemo(() => {
    const port = adresy?.porty?.strona ?? 8095;
    const biezacy = window.location.origin;
    const out = [{ id: biezacy, etykieta: `ten panel — ${biezacy.replace(/^https?:\/\//, "")}`, lokalny: NA_LOKALNYM }];
    for (const a of adresy?.adresy || []) {
      const adres = `http://${a.adres}:${port}`;
      if (adres !== biezacy) out.push({ id: adres, etykieta: `${a.interfejs} — ${a.adres}`, lokalny: false });
    }
    return out;
  }, [adresy]);

  // Wyliczane, nie zapisywane w efekcie: adres wybrany ręcznie ma pierwszeństwo,
  // a dopóki nikt nie wybierał — pierwszy, który cokolwiek wpuści.
  const adresDocelowy =
    wybranyAdres || adresyDoWyboru.find((a) => !a.lokalny)?.id || adresyDoWyboru[0]?.id || window.location.origin;

  const dzialanie = (obietnica) =>
    obietnica
      .then(() => {
        naBlad(null);
        odswiez();
      })
      .catch((e) => naBlad(tekst(e)));

  const zastosujZestaw = (z) => {
    setRola(z.rola);
    setWaznosc(z.waznosc);
    setJednorazowe(z.jednorazowe);
    poleImienia.current?.focus();
  };

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
        setNowyLink({ imie: z.imie, kod: z.kod, rola: z.rola });
        setImie("");
        odswiez();
      })
      .catch((err) => naBlad(tekst(err)));
  };

  // Kod zaproszenia da się pokazać ponownie — serwer trzyma go do unieważnienia.
  // Bez tego zamknięcie okienka znaczyłoby wydanie nowego zaproszenia tej samej
  // osobie, a stare zostawałoby wiszące i ważne.
  const pokazKod = (z) =>
    api(`/api/admin/zaproszenie/${z.id}/kod`)
      .then((d) => setNowyLink({ imie: z.imie, kod: d.kod, rola: z.rola }))
      .catch((e) => naBlad(tekst(e)));

  const wazne = (lista || []).filter((z) => z.wazne);
  const opisRoli = ROLE.find((r) => r.id === rola)?.opis;
  const link = nowyLink ? linkDoNa(adresDocelowy, nowyLink.kod) : null;
  const naLokalnym = adresDocelowy.includes("127.0.0.1") || adresDocelowy.includes("localhost");

  return (
    <>
      <section>
        <div className="etykieta">1. WYDAJ ZAPROSZENIE</div>
        <div className="rzad zestawy">
          {ZESTAWY.map((z) => (
            <button
              key={z.id}
              type="button"
              className={`przelacznik drobny ${
                rola === z.rola && waznosc === z.waznosc && jednorazowe === z.jednorazowe ? "wlaczony" : ""
              }`}
              onClick={() => zastosujZestaw(z)}
              title={`Ustawia pola: ${z.opis}`}
            >
              {z.etykieta}
              <span className="przypis drobne"> {z.opis}</span>
            </button>
          ))}
        </div>

        <form className="rzad" onSubmit={zapros}>
          <label className="pole-etykieta rozciagnij">
            DLA KOGO
            <input
              ref={poleImienia}
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
        {rola === "admin" && (
          <p className="przypis blad">
            ⛔ Rola <strong>admin</strong> oddaje panel w całości: zaproszenia, odcinanie,
            źródła razem z hasłami dronów, archiwum i restart usług stacji. To rola dla
            urządzenia albo osoby, którą znasz — nie dla gościa na jeden lot.
          </p>
        )}
      </section>

      {nowyLink && (
        <section className="blok wynik">
          <div className="etykieta">2. ZAPROSZENIE DLA {nowyLink.imie.toUpperCase()} — GOTOWE</div>

          {adresyDoWyboru.length > 1 && (
            <label className="pole-etykieta rozciagnij">
              ADRES, KTÓRYM PRZYJDZIE GOŚĆ — link i kod QR prowadzą właśnie tam
              <select className="pole" value={adresDocelowy} onChange={(e) => setWybranyAdres(e.target.value)}>
                {adresyDoWyboru.map((a) => (
                  <option key={a.id} value={a.id}>{a.etykieta}</option>
                ))}
              </select>
            </label>
          )}

          {/* ⛔ Kod QR niesie zaproszenie — kto go sfotografuje, ten wejdzie. Przy roli
              `admin` mówimy to wprost, zamiast liczyć na to, że nikt nie stoi za plecami. */}
          <KodQr
            tresc={link}
            nazwaPliku={`zaproszenie-${nowyLink.imie}.png`}
            podpis={`PANORAMA — ${nowyLink.imie} (${nowyLink.rola})`}
            ostrzezenie={
              nowyLink.rola === "admin"
                ? "To kod ADMINA — kto go zeskanuje, dostaje panel stacji. Nie zostawiaj go na ekranie."
                : null
            }
          />

          {naLokalnym && (
            <p className="przypis blad">
              Wybrany adres to pętla zwrotna — link i kod QR zadziałają <strong>tylko na tej
              maszynie</strong>. Wybierz adres sieciowy z listy wyżej albo wyślij gościowi
              sam kod, a on poda swój adres stacji.
            </p>
          )}

          <div className="przypis">Link — otwiera stronę i wpuszcza:</div>
          <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">{link}</code>

          {/* Sam kod, do wklejenia ręcznie na ekranie wejścia. Potrzebny wtedy, gdy gość
              wchodzi z innego adresu niż ten w linku — albo gdy link po drodze rozjedzie
              się w komunikatorze. */}
          <div className="przypis">Sam kod — do wpisania na ekranie wejścia, gdy strona jest już otwarta:</div>
          <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">{nowyLink.kod}</code>

          {/* Dla aplikacji na telefonie: jeden ciąg niosący kod I adres stacji. Świadomie
              NIE robimy z niego kodu QR — aparat telefonu pokazałby tylko tekst, bo to nie
              jest adres. Kod QR robimy z linku, który po zeskanowaniu otwiera stronę. */}
          <div className="przypis">
            <strong>Kod połączeniowy</strong> — do wklejenia w aplikacji na telefonie; niesie
            też adres stacji, więc nie trzeba go wpisywać osobno:
          </div>
          <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">
            {zbudujKodPolaczenia(adresDocelowy, nowyLink.kod)}
          </code>

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
                <td className="przypis" title={z.wygasa ? `do ${godzina(z.wygasa)}` : "bez terminu"}>
                  {zostalo(z.wygasa)}
                </td>
                <td className="przypis">{z.uzyte ? `użyte ${z.uzyte}×` : "nieużyte"}</td>
                <td>
                  <div className="rzad">
                    <button
                      type="button"
                      className="przelacznik drobny"
                      onClick={() => pokazKod(z)}
                      title="Pokaż kod QR i link jeszcze raz — bez wydawania nowego zaproszenia"
                    >
                      POKAŻ QR
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
        <div className="etykieta">ADRESY STACJI</div>
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
          Gość z tej samej sieci przyjdzie adresem lokalnym — wybierz go przy zaproszeniu.
          Dla gościa spoza sieci endpoint WireGuarda jest w karcie DIAGNOSTYKA.
        </p>
      </section>
    </>
  );
}
