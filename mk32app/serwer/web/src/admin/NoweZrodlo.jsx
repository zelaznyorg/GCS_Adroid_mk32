// Karta NOWE ŹRÓDŁO — podłączenie drona DJI albo kamery IP w trzech krokach.
//
// Osobno od listy źródeł (uwaga Toma, 2026-09-04): dodawanie to czynność
// z początkiem i końcem, a lista to stan. Koniec czynności musi pokazać to,
// co trzeba wpisać w aparaturze — adres RTMP i hasło — bez szukania ich
// w tabeli. W polu nie ma czasu na zgadywanie, gdzie co jest.
import { useCallback, useEffect, useState } from "react";
import { api } from "../sesja";
import { tekst } from "./pomoc";
import DaneAparatury from "./DaneAparatury";

const PUSTE = { rodzaj: "nadawane", nazwa: "", rtspGlowny: "" };

export default function NoweZrodlo({ naZmianeZrodel, naBlad, naUwaga, naKarta }) {
  const [info, setInfo] = useState(null);       // { zrodla, maks }
  const [nowe, setNowe] = useState(PUSTE);
  const [dodane, setDodane] = useState(null);   // źródło dodane przed chwilą, z hasłem
  const [pokazHaslo, setPokazHaslo] = useState(false);

  const pobierz = useCallback(() => {
    api("/api/admin/zrodla").then(setInfo).catch((e) => naBlad(tekst(e)));
  }, [naBlad]);

  useEffect(() => { pobierz(); }, [pobierz]);

  const ile = info?.zrodla?.length ?? 0;
  const maks = info?.maks ?? 6;
  const komplet = info ? ile >= maks : false;
  const nadawane = nowe.rodzaj === "nadawane";
  const adresOk = /^rtsps?:\/\//i.test(nowe.rtspGlowny.trim());
  const poprawne = Boolean(nowe.nazwa.trim()) && (nadawane || adresOk);

  const dodaj = () => {
    const cialo = { rodzaj: nowe.rodzaj, nazwa: nowe.nazwa.trim() };
    if (!nadawane) cialo.rtspGlowny = nowe.rtspGlowny.trim();
    const przed = new Set((info?.zrodla || []).map((z) => z.id));
    api("/api/admin/zrodla", { method: "POST", body: cialo })
      .then((o) => {
        naBlad(null);
        // Odpowiedź niesie całą listę; nowe źródło to jedyne, którego wcześniej nie było.
        setDodane((o.zrodla || []).find((z) => !przed.has(z.id)) || null);
        setPokazHaslo(false);
        setInfo((i) => ({ ...(i || {}), zrodla: o.zrodla }));
        naUwaga(
          o.wymagaRestartuObrazu
            ? "Stacja zaczęła przyjmować nadawanie (port 1935). MediaMTX otworzy go dopiero po restarcie usługi OBRAZ — panel STACJA."
            : null
        );
        setNowe(PUSTE);
        naZmianeZrodel?.();
        pobierz();
      })
      .catch((e) => naBlad(tekst(e)));
  };

  return (
    <>
      <section>
        <div className="etykieta">1. CO PODŁĄCZASZ</div>
        <div className="rzad wybor-rodzaju">
          <button
            type="button"
            className={`przelacznik ${nadawane ? "wlaczony" : ""}`}
            onClick={() => setNowe((n) => ({ ...n, rodzaj: "nadawane" }))}
          >
            DRON DJI — nadaje do stacji
          </button>
          <button
            type="button"
            className={`przelacznik ${!nadawane ? "wlaczony" : ""}`}
            onClick={() => setNowe((n) => ({ ...n, rodzaj: "pobierane" }))}
          >
            KAMERA IP — stacja pobiera RTSP
          </button>
        </div>
        <p className="przypis">
          {nadawane
            ? "Dron dostanie własne hasło — nim mówi stacji, kim jest. Wpisuje się je w adresie RTMP w Pilocie 2 albo w aplikacji Horyzont na aparaturze."
            : "Stacja sama ściąga obraz z podanego adresu, ale dopiero wtedy, gdy ktoś to źródło wybierze — kamera nie obciąża łącza, gdy nikt nie patrzy."}
        </p>
      </section>

      <section>
        <div className="etykieta">2. {nadawane ? "NAZWA" : "NAZWA I ADRES"}</div>
        <div className="rzad">
          <label className="pole-etykieta rozciagnij">
            NAZWA — tak zobaczą ją widzowie
            <input
              type="text"
              className="pole"
              placeholder={nadawane ? "np. Mavic 3T" : "np. Kamera analogowa"}
              value={nowe.nazwa}
              onChange={(e) => setNowe((n) => ({ ...n, nazwa: e.target.value }))}
              disabled={komplet}
            />
          </label>
          {!nadawane && (
            <label className="pole-etykieta rozciagnij">
              ADRES RTSP
              <input
                type="text"
                className="pole"
                placeholder="rtsp://127.0.0.1:8554/uav"
                value={nowe.rtspGlowny}
                onChange={(e) => setNowe((n) => ({ ...n, rtspGlowny: e.target.value }))}
                disabled={komplet}
              />
            </label>
          )}
          <button
            type="button"
            className="przelacznik"
            disabled={!poprawne || komplet}
            onClick={dodaj}
            title={komplet ? "Komplet źródeł" : "Dodaje źródło i od razu pokazuje, co wpisać w aparaturze"}
          >
            DODAJ ŹRÓDŁO
          </button>
        </div>
        <p className={`przypis ${komplet ? "blad" : ""}`}>
          {komplet
            ? `Komplet — ${ile} z ${maks}. Więcej kafelków przeglądarka na stacji nie zdekoduje. Usuń któreś w karcie ŹRÓDŁA, żeby dodać nowe.`
            : `Źródeł: ${info ? ile : "…"} z ${maks}. Jedno źródło = pełny ekran, dwa i więcej = mozaika kafelków.`}
        </p>
      </section>

      {dodane && (
        <section className="blok wynik">
          <div className="etykieta">3. GOTOWE — {dodane.nazwa.toUpperCase()} JEST NA LIŚCIE</div>
          {dodane.nadawany ? (
            <>
              <DaneAparatury zrodlo={dodane} pokaz={pokazHaslo} naPokaz={() => setPokazHaslo((p) => !p)} />
              <div className="rzad">
                <button type="button" className="przelacznik drobny" onClick={() => naKarta?.("zrodla")}>
                  PRZEJDŹ DO ŹRÓDEŁ
                </button>
                <button type="button" className="przelacznik drobny" onClick={() => setDodane(null)}>
                  DODAJ NASTĘPNE
                </button>
              </div>
            </>
          ) : (
            <>
              <div className="przypis">
                Stacja pobiera <code>{dodane.rtspGlowny}</code> na żądanie — obraz ruszy,
                gdy ktoś wybierze to źródło albo otworzy mozaikę.
              </div>
              <div className="rzad">
                <button type="button" className="przelacznik drobny" onClick={() => naKarta?.("zrodla")}>
                  PRZEJDŹ DO ŹRÓDEŁ
                </button>
                <button type="button" className="przelacznik drobny" onClick={() => setDodane(null)}>
                  DODAJ NASTĘPNE
                </button>
              </div>
            </>
          )}
          <p className="przypis">Adres i hasło znajdziesz później w karcie ŹRÓDŁA, przy tym źródle.</p>
        </section>
      )}
    </>
  );
}
