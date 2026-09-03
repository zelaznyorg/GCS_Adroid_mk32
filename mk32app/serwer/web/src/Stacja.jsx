// Panel STACJI — obsługa maszyny, na której to wszystko stoi.
//
// Osobny panel od administratorskiego, i to jest rozróżnienie, a nie porządki:
//
//   PANEL ADMINISTRATORA  →  kto ma wstęp        (dostęp, zaproszenia, archiwum)
//   PANEL STACJI (ten)    →  czy sprzęt działa   (usługi, zasilanie, sieć, dziennik)
//
// ⛔ Ani jeden przycisk na tym ekranie nie idzie do maszyny latającej. Restart
// usługi podglądu zabiera obraz widzom i nic poza tym — władza zostaje na MK32
// (dok/WLADZA.md). Odpowiednik `rpi/sprawdz.sh`, tyle że bez wchodzenia po ssh.
import { useCallback, useEffect, useState } from "react";
import { api } from "./sesja";
import NaglowekPanelu from "./NaglowekPanelu";

const ODSWIEZAJ_MS = 6000;

function rozmiar(b) {
  if (b == null) return "—";
  if (b < 1024) return `${b} B`;
  if (b < 1024 ** 2) return `${(b / 1024).toFixed(0)} kB`;
  if (b < 1024 ** 3) return `${(b / 1024 ** 2).toFixed(0)} MB`;
  return `${(b / 1024 ** 3).toFixed(1)} GB`;
}

function czas(s) {
  if (s == null) return "—";
  const d = Math.floor(s / 86400);
  const g = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d) return `${d} d ${g} h`;
  if (g) return `${g} h ${m} min`;
  if (m) return `${m} min`;
  return `${Math.floor(s)} s`;
}

// Stan usługi w słowach, które coś znaczą dla patrzącego, a nie dla systemd.
const STAN_USLUGI = {
  active: { etykieta: "DZIAŁA", dioda: "ok" },
  activating: { etykieta: "WSTAJE", dioda: "" },
  deactivating: { etykieta: "SCHODZI", dioda: "" },
  inactive: { etykieta: "ZATRZYMANA", dioda: "zla" },
  failed: { etykieta: "PADŁA", dioda: "zla" },
  nieznany: { etykieta: "NIEZNANY", dioda: "" },
};

export default function Stacja({ naZamknij, panel, naPanel }) {
  const [s, setS] = useState(null);
  const [blad, setBlad] = useState(null);
  const [komunikat, setKomunikat] = useState(null);
  // Restart usługi widzą wszyscy widzowie, więc jeden odruch dłoni to za mało.
  // Pierwsze kliknięcie uzbraja, drugie wykonuje; po 5 s uzbrojenie samo mija.
  const [uzbrojony, setUzbrojony] = useState(null);
  const [dziennikUslugi, setDziennikUslugi] = useState("panorama-gcs");
  const [dziennik, setDziennik] = useState(null);

  const odswiez = useCallback((swiezo = false) => {
    api(`/api/admin/stacja${swiezo ? "?swiezo=1" : ""}`)
      .then((d) => {
        setS(d);
        setBlad(null);
      })
      .catch((e) => setBlad(String(e.message || e)));
  }, []);

  useEffect(() => {
    odswiez();
    const t = setInterval(() => odswiez(), ODSWIEZAJ_MS);
    return () => clearInterval(t);
  }, [odswiez]);

  useEffect(() => {
    if (!uzbrojony) return undefined;
    const t = setTimeout(() => setUzbrojony(null), 5000);
    return () => clearTimeout(t);
  }, [uzbrojony]);

  // Wynik nosi ze sobą nazwę usługi, której dotyczy. Dzięki temu przy przełączeniu
  // nie trzeba czyścić stanu z wnętrza efektu (co robi kaskadę renderów) — widok
  // sam widzi, że ma dane nie tej usługi, i pisze „czytam…".
  const pobierzDziennik = useCallback((usluga) => {
    api(`/api/admin/stacja/dziennik?usluga=${encodeURIComponent(usluga)}&ile=120`)
      .then((d) => setDziennik({ ...d, usluga }))
      .catch((e) => setBlad(String(e.message || e)));
  }, []);

  useEffect(() => { pobierzDziennik(dziennikUslugi); }, [pobierzDziennik, dziennikUslugi]);

  const restartuj = (usluga) => {
    if (uzbrojony !== usluga) {
      setUzbrojony(usluga);
      return;
    }
    setUzbrojony(null);
    setKomunikat(null);
    api("/api/admin/stacja/restart", { method: "POST", body: { usluga } })
      .then((o) => {
        setBlad(null);
        setKomunikat(
          o.opozniony
            ? "Serwer schodzi z pola — panel wróci sam za kilka sekund."
            : `Zrestartowano ${usluga}.`
        );
        // Po restarcie stan i tak przez chwilę kłamie, więc dajemy usłudze
        // wstać, zanim znów o nią zapytamy.
        setTimeout(() => odswiez(true), 2500);
      })
      .catch((e) => setBlad(String(e.message || e)));
  };

  // Dziennik pokazujemy tylko wtedy, gdy dotyczy wybranej usługi.
  const aktualny = dziennik?.usluga === dziennikUslugi ? dziennik : null;

  const dl = s?.dlawienie;
  const tunel = (s?.siec || []).filter((i) => i.tunel);
  // Porty „TYLKO lokalnie” (9997 — API MediaMTX, 8555 — RTSP dla nagrywarki), które
  // odpowiadają spoza pętli zwrotnej. Każdy taki to dziura, nie ciekawostka.
  const wystawione = (s?.porty || []).filter((p) => !p.wTunelu && p.wystawioneNaSwiat);

  return (
    <div className="zaslona panel admin">
      <div className="karta szeroka" onClick={(e) => e.stopPropagation()}>
        <NaglowekPanelu
          tytul="STACJA"
          panel={panel}
          naPanel={naPanel}
          naZamknij={naZamknij}
          rola="admin"
        >
          <button type="button" className="przelacznik drobny" onClick={() => odswiez(true)}>ODŚWIEŻ</button>
        </NaglowekPanelu>

        <p className="przypis">
          Ten panel obsługuje <strong>stację</strong>, nie maszynę latającą. Nie ma tu drogi
          do kontrolera lotu ani do głowicy.
        </p>

        {blad && <p className="przypis blad">{blad}</p>}
        {komunikat && <p className="przypis">{komunikat}</p>}
        {!s && !blad && <p className="przypis">czytam stan stacji…</p>}

        {s && !s.linux && (
          <p className="przypis blad">
            To nie jest Linux — systemd, dziennik systemowy i odczyty zasilania są niedostępne.
            Reszta pokazuje, co się da. Na stacji docelowej (Raspberry Pi OS) działa całość.
          </p>
        )}

        {s && (
          <>
            {/* ---- najpierw to, co potrafi położyć stację po cichu ---- */}
            {dl && !dl.czysto && (
              <section>
                <div className="etykieta blad">⛔ ZASILANIE — {dl.surowe}</div>
                <p className="przypis blad">
                  {dl.niedomiarTeraz && "NIEDOMIAR NAPIĘCIA TERAZ. "}
                  {!dl.niedomiarTeraz && dl.niedomiarByl && "Niedomiar napięcia wystąpił wcześniej. "}
                  {dl.dlawienieTeraz && "Procesor dławiony TERAZ. "}
                  {!dl.dlawienieTeraz && dl.dlawienieBylo && "Procesor był dławiony wcześniej. "}
                  Niedomiar zasilania objawia się losowymi zawieszeniami stacji i myli, bo wygląda
                  dokładnie jak usterka oprogramowania. Sprawdź, czy to oryginalny zasilacz 27 W.
                </p>
              </section>
            )}

            {wystawione.map((p) => (
              <section key={p.port}>
                <div className="etykieta blad">⛔ PORT {p.port} WYSTAWIONY NA SIEĆ</div>
                <p className="przypis blad">
                  {p.rola} — odpowiada spoza pętli zwrotnej.{" "}
                  {p.port === 9997
                    ? "Każdy w tej sieci może przestawiać ścieżki obrazu."
                    : "Każdy w tej sieci dostaje obraz bez żetonu widza."}{" "}
                  W generowanym <code>mediamtx.yml</code> ten nasłuch ma być przypięty do
                  <code> 127.0.0.1</code>.
                </p>
              </section>
            ))}

            {/* ---- usługi ---- */}
            <section>
              <div className="etykieta">USŁUGI</div>
              <table className="tabela">
                <tbody>
                  {(s.uslugi || []).map((u) => {
                    const op = STAN_USLUGI[u.stan] || STAN_USLUGI.nieznany;
                    const uz = uzbrojony === u.id;
                    return (
                      <tr key={u.id}>
                        <td><span className={`dioda ${op.dioda}`}>{op.etykieta}</span></td>
                        <td title={u.opis}><strong>{u.nazwa}</strong><br /><span className="przypis">{u.id}</span></td>
                        <td className="przypis">
                          {u.dzialaOdS != null ? `od ${czas(u.dzialaOdS)}` : "—"}
                          {u.restartow > 0 && <><br />restartów: {u.restartow}</>}
                        </td>
                        <td className="przypis">{u.pamiecB != null ? rozmiar(u.pamiecB) : "—"}</td>
                        <td className="przypis">
                          {u.wlaczona === true ? "wstaje sama" : u.wlaczona === false ? "NIE wstaje sama" : "—"}
                        </td>
                        <td>
                          <button
                            type="button"
                            className={`przelacznik drobny ${uz ? "pilne wlaczony" : ""}`}
                            onClick={() => restartuj(u.id)}
                            disabled={!s.linux}
                            title={u.opis}
                          >
                            {uz ? "NA PEWNO?" : "RESTART"}
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              <p className="przypis">
                Pierwsze kliknięcie uzbraja, drugie wykonuje — restart widzą wszyscy widzowie.
                Uzbrojenie mija samo po pięciu sekundach.
              </p>
            </section>

            {/* ---- maszyna ---- */}
            <section>
              <div className="etykieta">MASZYNA</div>
              <table className="tabela">
                <tbody>
                  <tr><td className="przypis">model</td><td>{s.system.model || "—"}</td></tr>
                  <tr><td className="przypis">system</td><td>{s.system.system}</td></tr>
                  <tr><td className="przypis">jądro</td><td>{s.system.jadro} {s.system.architektura}</td></tr>
                  <tr><td className="przypis">nazwa w sieci</td><td>{s.system.hostname}</td></tr>
                  <tr><td className="przypis">czas pracy</td><td>{czas(s.system.czasPracyS)}</td></tr>
                  <tr>
                    <td className="przypis">temperatura</td>
                    <td className={s.temperaturaC > 80 ? "blad" : ""}>
                      {s.temperaturaC != null ? `${s.temperaturaC.toFixed(1)} °C` : "—"}
                    </td>
                  </tr>
                  <tr>
                    <td className="przypis">zasilanie</td>
                    <td>{dl ? (dl.czysto ? "czysto" : dl.surowe) : "—"}</td>
                  </tr>
                  <tr>
                    <td className="przypis">obciążenie</td>
                    <td>
                      {s.system.obciazenie.map((o) => o.toFixed(2)).join(" · ")}
                      <span className="przypis"> (1/5/15 min, {s.system.rdzeni} rdzeni)</span>
                    </td>
                  </tr>
                  <tr>
                    <td className="przypis">pamięć</td>
                    <td>{rozmiar(s.system.pamiec.calosc - s.system.pamiec.wolna)} z {rozmiar(s.system.pamiec.calosc)}</td>
                  </tr>
                  {s.dysk && (
                    <tr>
                      <td className="przypis">dysk danych</td>
                      <td>wolne {rozmiar(s.dysk.wolne)} z {rozmiar(s.dysk.calosc)}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </section>

            {/* ---- sieć ---- */}
            <section>
              <div className="etykieta">SIEĆ I PORTY</div>
              <table className="tabela">
                <tbody>
                  {(s.siec || []).map((i) => (
                    <tr key={`${i.nazwa}-${i.adres}`}>
                      <td className="przypis">{i.nazwa}{i.tunel && " (tunel)"}</td>
                      <td><code>{i.adres}</code></td>
                      <td className={i.tunel && i.mtu > 1420 ? "blad" : "przypis"}>MTU {i.mtu ?? "—"}</td>
                    </tr>
                  ))}
                  {(s.porty || []).map((p) => (
                    <tr key={p.port}>
                      <td><span className={`dioda ${p.zywy ? "ok" : "zla"}`}>{p.port}</span></td>
                      <td className="przypis">{p.rola}</td>
                      <td className="przypis">{p.wTunelu ? "przepuścić w tunelu" : "nie wystawiać"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {tunel.some((i) => i.mtu > 1420) && (
                <p className="przypis blad">
                  MTU tunelu powyżej 1420. Objaw, którego to dotyczy, jest mylący:
                  strona i telemetria działają, a obraz nie startuje. Pod LTE albo PPPoE
                  bywa potrzebne 1280.
                </p>
              )}
            </section>

            {/* ---- oprogramowanie ---- */}
            <section>
              <div className="etykieta">OPROGRAMOWANIE</div>
              <table className="tabela">
                <tbody>
                  <tr><td className="przypis">node</td><td>{s.wersje.node}</td></tr>
                  <tr><td className="przypis">mediamtx</td><td>{s.wersje.mediamtx || "—"}</td></tr>
                  <tr><td className="przypis">chromium</td><td>{s.wersje.chromium || "—"}</td></tr>
                  <tr>
                    <td className="przypis">dekoder HEVC</td>
                    <td>
                      {s.dekoder.dostepny === true
                        ? s.dekoder.urzadzenia.join(", ")
                        : <span className="przypis">{s.dekoder.powod}</span>}
                    </td>
                  </tr>
                </tbody>
              </table>
              <p className="przypis">
                Obecność dekodera w systemie <strong>nie</strong> dowodzi, że Chromium z niego
                korzysta. To rozstrzyga wyłącznie <code>chrome://media-internals</code> podczas
                odtwarzania — pole „Decoder" ma pokazać dekoder sprzętowy, nie FFmpegVideoDecoder.
              </p>
            </section>

            {/* ---- katalogi ---- */}
            <section>
              <div className="etykieta">GDZIE CO LEŻY</div>
              <ul className="lista-adresow">
                <li><span className="przypis">kod</span><code>{s.katalogi.kod}</code></li>
                <li><span className="przypis">dane</span><code>{s.katalogi.dane}</code></li>
                {s.katalogi.archiwum && <li><span className="przypis">archiwum</span><code>{s.katalogi.archiwum}</code></li>}
                <li><span className="przypis">rejestr</span><code>{s.katalogi.logi}</code></li>
              </ul>
            </section>

            {/* ---- dziennik systemowy ---- */}
            <section>
              <div className="rzad rozstrzelony">
                <div className="etykieta">DZIENNIK SYSTEMOWY</div>
                <select
                  className="pole"
                  value={dziennikUslugi}
                  onChange={(e) => setDziennikUslugi(e.target.value)}
                >
                  {(s.uslugi || []).map((u) => (
                    <option key={u.id} value={u.id}>{u.nazwa} — {u.id}</option>
                  ))}
                </select>
                <button type="button" className="przelacznik drobny" onClick={() => pobierzDziennik(dziennikUslugi)}>
                  ODŚWIEŻ
                </button>
              </div>
              {aktualny?.powod && <p className="przypis blad">{aktualny.powod}</p>}
              <pre className="dziennik systemowy">
                {aktualny ? (aktualny.linie.join("\n") || "pusto") : "czytam…"}
              </pre>
              <p className="przypis">
                To jest dziennik <strong>systemu</strong> — co powiedziała usługa przy starcie
                i przy padnięciu. Rejestr techniczny serwera (ze stosami wywołań) jest w panelu
                administratora.
              </p>
            </section>
          </>
        )}
      </div>
    </div>
  );
}
