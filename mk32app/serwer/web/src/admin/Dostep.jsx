// Karta DOSTĘP — jedno pytanie: kto teraz ogląda i co może.
//
// Ustawienia wstępu (cisza, limit, źródło domyślne), lista oglądających
// z odcinaniem i dziennik wejść. Zaproszeń tu nie ma — wydawanie kodów to
// osobna czynność i osobna karta.
import { useCallback, useEffect, useState } from "react";
import { api } from "../sesja";
import { ODSWIEZAJ_MS, czasKrotki, godzina, tekst } from "./pomoc";

export default function Dostep({ zrodla, naBlad }) {
  const [stan, setStan] = useState(null);
  const [dziennik, setDziennik] = useState([]);

  const odswiez = useCallback(() => {
    api("/api/admin/stan").then(setStan).catch((e) => naBlad(tekst(e)));
    api("/api/admin/dziennik?ile=40").then((d) => setDziennik(d.dziennik || [])).catch(() => {});
  }, [naBlad]);

  useEffect(() => {
    odswiez();
    const t = setInterval(odswiez, ODSWIEZAJ_MS);
    return () => clearInterval(t);
  }, [odswiez]);

  const dzialanie = (obietnica) =>
    obietnica
      .then(() => {
        naBlad(null);
        odswiez();
      })
      .catch((e) => naBlad(tekst(e)));

  const ustaw = (zmiany) => dzialanie(api("/api/admin/ustawienia", { method: "POST", body: zmiany }));

  const u = stan?.ustawienia;
  const widzowie = stan?.widzowie || [];

  return (
    <>
      <section>
        <div className="etykieta">USTAWIENIA WSTĘPU</div>
        <div className="rzad">
          <button
            type="button"
            className={`przelacznik ${u?.cisza ? "wlaczony pilne" : ""}`}
            onClick={() => ustaw({ cisza: !u?.cisza })}
            title="Odcina wszystkich widzów naraz. Odwracalne. Administratorzy przechodzą."
          >
            {u?.cisza ? "CISZA — WŁĄCZONA" : "TRYB CISZY"}
          </button>

          <label className="pole-etykieta">
            LIMIT WIDZÓW
            <input
              type="number"
              className="pole waskie"
              min={1}
              max={50}
              value={u?.limitWidzow ?? 6}
              onChange={(e) => ustaw({ limitWidzow: e.target.value })}
            />
          </label>

          <label className="pole-etykieta">
            ŹRÓDŁO DOMYŚLNE
            <select
              className="pole"
              value={u?.zrodloDomyslne ?? ""}
              onChange={(e) => ustaw({ zrodloDomyslne: e.target.value || null })}
            >
              <option value="">(pierwsze z listy)</option>
              {zrodla.map((z) => (
                <option key={z.id} value={z.id}>{z.nazwa}</option>
              ))}
            </select>
          </label>
        </div>
        <p className="przypis">
          Każdy widz to osobny strumień — limit chroni łącze. Źródło domyślne dostają
          nowo wchodzący; każdy może je u siebie zmienić.
        </p>
      </section>

      <section>
        <div className="etykieta">
          KTO OGLĄDA TERAZ — {stan ? widzowie.length : "…"}
          {u ? ` z ${u.limitWidzow}` : ""}
        </div>
        <table className="tabela">
          <tbody>
            {widzowie.map((w) => (
              <tr key={w.id}>
                <td><span className={`kropka ${w.rola}`} /> {w.imie}</td>
                <td className="przypis">{w.rola}</td>
                <td className="przypis">{w.zrodlo || "—"}</td>
                <td className="przypis">{w.ip}</td>
                <td className="przypis">{czasKrotki(w.sekund)}</td>
                <td>
                  {w.rola !== "admin" && (
                    <button
                      type="button"
                      className="przelacznik drobny pilne"
                      onClick={() => dzialanie(api("/api/admin/odetnij", { method: "POST", body: { zetonId: w.zetonId } }))}
                      title="Zabiera stronę, telemetrię i strumień obrazu. Żeby wrócić, potrzebuje nowego zaproszenia"
                    >
                      ODETNIJ
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {stan && widzowie.length === 0 && (
              <tr><td colSpan={6} className="przypis">nikt nie ogląda</td></tr>
            )}
          </tbody>
        </table>
        <p className="przypis">
          Odcięcie zabiera żeton — kod zaproszenia, z którego pochodził, zostaje ważny,
          jeśli jest wielokrotny. Żeby zamknąć drzwi na dobre, unieważnij go w karcie ZAPROSZENIA.
        </p>
      </section>

      <section>
        <div className="etykieta">DZIENNIK DOSTĘPU — kto co zrobił</div>
        <ul className="dziennik">
          {dziennik.map((d, i) => (
            <li key={`${d.czas}-${i}`}>
              <span className="przypis">{godzina(d.czas)}</span>
              <span className={`znacznik-zdarzenia ${d.rodzaj}`}>{d.rodzaj}</span>
              <span>{d.opis}</span>
            </li>
          ))}
          {dziennik.length === 0 && <li className="przypis">pusto</li>}
        </ul>
      </section>
    </>
  );
}
