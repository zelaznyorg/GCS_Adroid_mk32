// Panel administratora — zarządza DOSTĘPEM, nie maszyną.
//
// Ani jeden przycisk na tym ekranie nie wysyła niczego do drona. Władza nad lotem
// zostaje na MK32 (dok/WLADZA.md) i administrator stacji też jej nie ma.
// Projekt panelu: dok/DOSTEP_I_UZYTKOWNICY.md §4.
import { useCallback, useEffect, useState } from "react";
import { api, zbudujKodPolaczenia } from "./sesja";
import NaglowekPanelu from "./NaglowekPanelu";

const ODSWIEZAJ_MS = 4000;
// Stan archiwum wymaga przejścia po katalogach na dysku, więc pytamy o niego
// znacznie rzadziej niż o widzów. Nic tam nie zmienia się z sekundy na sekundę.
const ODSWIEZAJ_ARCHIWUM_MS = 20000;

const TRYBY_WIDEO = [
  { id: "nie", etykieta: "NIE NAGRYWAJ", opis: "Zapisujemy samą telemetrię." },
  {
    id: "przy-widzach",
    etykieta: "GDY KTOŚ OGLĄDA",
    opis: "Obraz zapisuje się tylko wtedy, gdy ktoś patrzy. Lot bez widza nie ma nagrania.",
  },
  {
    id: "zawsze",
    etykieta: "ZAWSZE",
    opis: "Nagranie kompletne, ale strumień z kamery leci bez przerwy — obciąża łącze radiowe i zajmuje slot ZR30.",
  },
];

const WAZNOSC = [
  { etykieta: "1 godzina", min: 60 },
  { etykieta: "1 dzień", min: 60 * 24 },
  { etykieta: "bezterminowo", min: null },
];

function czasKrotki(s) {
  if (s == null) return "—";
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

function rozmiar(b) {
  if (b == null) return "—";
  if (b < 1024) return `${b} B`;
  if (b < 1024 ** 2) return `${(b / 1024).toFixed(0)} kB`;
  if (b < 1024 ** 3) return `${(b / 1024 ** 2).toFixed(1)} MB`;
  return `${(b / 1024 ** 3).toFixed(1)} GB`;
}

function godzina(ms) {
  if (!ms) return "—";
  return new Date(ms).toLocaleTimeString("pl-PL", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function zaznacz(e) {
  window.getSelection().selectAllChildren(e.currentTarget);
}

// Link zapraszający budujemy z adresu, POD KTÓRYM ADMIN MA OTWARTĄ TĘ STRONĘ.
// Inaczej się nie da — serwer nie wie, którą drogą gość będzie się łączył
// (LAN, tunel, adres publiczny). Stąd ostrzeżenie niżej: panel otwarty na
// localhost wyprodukuje link działający wyłącznie na tej jednej maszynie.
const linkDo = (kod) => `${window.location.origin}/#z=${kod}`;

// Kod połączeniowy — kod zaproszenia PLUS adres stacji w jednym ciągu.
// Po co, skoro jest link: link jest adresem URL, więc komunikatory robią z niego
// podgląd i łamią go w połowie, a przeglądarka telefonu otworzy go tylko wtedy,
// gdy akurat ma drogę do stacji. Kod połączeniowy wkleja się na ekranie wejścia
// aplikacji **już zainstalowanej** i mówi jej, dokąd ma się przełączyć —
// patrz dok/TELEFON.md §2a. Adres bierzemy z tego samego miejsca co link,
// więc obowiązuje to samo zastrzeżenie o localhoście.

const NA_LOKALNYM = ["localhost", "127.0.0.1", "::1"].includes(window.location.hostname);

export default function Admin({ zrodla, naZamknij, panel, naPanel }) {
  const [stan, setStan] = useState(null);
  const [adresy, setAdresy] = useState(null);
  const [dziennik, setDziennik] = useState([]);
  const [logi, setLogi] = useState(null);
  const [archiwum, setArchiwum] = useState(null);
  const [poziomLogow, setPoziomLogow] = useState("info");
  const [blad, setBlad] = useState(null);
  const [nowyLink, setNowyLink] = useState(null);

  const [imie, setImie] = useState("");
  const [rola, setRola] = useState("widz");
  const [waznosc, setWaznosc] = useState(1);      // domyślnie 1 dzień
  const [jednorazowe, setJednorazowe] = useState(true);

  const odswiez = useCallback(() => {
    api("/api/admin/stan").then(setStan).catch((e) => setBlad(String(e.message || e)));
  }, []);

  useEffect(() => {
    odswiez();
    api("/api/adresy").then(setAdresy).catch(() => setAdresy(null));
    api("/api/admin/dziennik?ile=40").then((d) => setDziennik(d.dziennik || [])).catch(() => {});
    const t = setInterval(odswiez, ODSWIEZAJ_MS);
    return () => clearInterval(t);
  }, [odswiez]);

  const pobierzArchiwum = useCallback(() => {
    api("/api/admin/archiwum?ile=12").then(setArchiwum).catch(() => setArchiwum(null));
  }, []);

  useEffect(() => {
    pobierzArchiwum();
    const t = setInterval(pobierzArchiwum, ODSWIEZAJ_ARCHIWUM_MS);
    return () => clearInterval(t);
  }, [pobierzArchiwum]);

  const zmienArchiwum = (zmiany) =>
    api("/api/admin/archiwum", { method: "POST", body: zmiany })
      .then(() => {
        setBlad(null);
        pobierzArchiwum();
      })
      .catch((e) => setBlad(String(e.message || e)));

  // Rejestr techniczny pobieramy osobno i rzadziej — to narzędzie do szukania usterek,
  // nie wskaźnik, na który się patrzy w kółko.
  const pobierzLogi = useCallback((poziom) => {
    api(`/api/admin/logi?ile=120&poziom=${poziom}`)
      .then(setLogi)
      .catch((e) => setBlad(String(e.message || e)));
  }, []);

  useEffect(() => { pobierzLogi(poziomLogow); }, [pobierzLogi, poziomLogow]);

  const dzialanie = (obietnica) =>
    obietnica
      .then(() => {
        setBlad(null);
        odswiez();
        api("/api/admin/dziennik?ile=40").then((d) => setDziennik(d.dziennik || [])).catch(() => {});
      })
      .catch((e) => setBlad(String(e.message || e)));

  const zapros = (e) => {
    e.preventDefault();
    if (!imie.trim()) return;
    const w = WAZNOSC[waznosc];
    api("/api/admin/zaproszenie", {
      method: "POST",
      body: { imie: imie.trim(), rola, waznoscMin: w.min, jednorazowe },
    })
      .then((z) => {
        setNowyLink({ imie: z.imie, link: linkDo(z.kod), kod: z.kod });
        setImie("");
        odswiez();
      })
      .catch((err) => setBlad(String(err.message || err)));
  };

  // Kod zaproszenia da się pokazać ponownie — serwer trzyma go do unieważnienia.
  // Bez tego zamknięcie okienka z linkiem znaczyłoby, że trzeba wydać nowe
  // zaproszenie tej samej osobie, a stare zostaje wiszące i ważne.
  const pokazLink = (z) =>
    api(`/api/admin/zaproszenie/${z.id}/kod`)
      .then((d) => setNowyLink({ imie: z.imie, link: linkDo(d.kod), kod: d.kod }))
      .catch((e) => setBlad(String(e.message || e)));

  const u = stan?.ustawienia;

  return (
    <div className="zaslona panel admin">
      <div className="karta szeroka" onClick={(e) => e.stopPropagation()}>
        <NaglowekPanelu
          tytul="PANEL ADMINISTRATORA"
          panel={panel}
          naPanel={naPanel}
          naZamknij={naZamknij}
          rola="admin"
        />

        {blad && <p className="przypis blad">{blad}</p>}

        {/* ---- ustawienia: to, co zmienia zachowanie stacji od zaraz ---- */}
        <section>
          <div className="etykieta">STEROWANIE DOSTĘPEM</div>
          <div className="rzad">
            <button
              type="button"
              className={`przelacznik ${u?.cisza ? "wlaczony pilne" : ""}`}
              onClick={() => dzialanie(api("/api/admin/ustawienia", { method: "POST", body: { cisza: !u?.cisza } }))}
              title="Odcina wszystkich widzów naraz. Odwracalne."
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
                onChange={(e) =>
                  dzialanie(api("/api/admin/ustawienia", { method: "POST", body: { limitWidzow: e.target.value } }))
                }
              />
            </label>

            <label className="pole-etykieta">
              ŹRÓDŁO DOMYŚLNE
              <select
                className="pole"
                value={u?.zrodloDomyslne ?? ""}
                onChange={(e) =>
                  dzialanie(api("/api/admin/ustawienia", { method: "POST", body: { zrodloDomyslne: e.target.value || null } }))
                }
              >
                <option value="">(pierwsze z listy)</option>
                {zrodla.map((z) => (
                  <option key={z.id} value={z.id}>{z.nazwa}</option>
                ))}
              </select>
            </label>
          </div>
          <p className="przypis">
            Źródło domyślne dostają nowo wchodzący. Każdy może je u siebie zmienić —
            narzucanie widoku komuś, kto ogląda na telefonie w słońcu, częściej przeszkadza.
          </p>
        </section>

        {/* ---- kto ogląda ---- */}
        <section>
          <div className="etykieta">
            KTO OGLĄDA — {stan?.widzowie?.length ?? 0}
            {u ? ` z ${u.limitWidzow}` : ""}
          </div>
          <table className="tabela">
            <tbody>
              {(stan?.widzowie || []).map((w) => (
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
                        title="Zabiera stronę, telemetrię i strumień obrazu"
                      >
                        ODETNIJ
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {stan?.widzowie?.length === 0 && (
                <tr><td colSpan={6} className="przypis">nikt nie ogląda</td></tr>
              )}
            </tbody>
          </table>
        </section>

        {/* ---- zaproszenia ---- */}
        <section>
          <div className="etykieta">ZAPROSZENIA</div>

          <form className="rzad" onSubmit={zapros}>
            <input
              className="pole"
              placeholder="imię"
              value={imie}
              onChange={(e) => setImie(e.target.value)}
              autoComplete="off"
            />
            <select className="pole" value={rola} onChange={(e) => setRola(e.target.value)}>
              <option value="widz">widz</option>
              <option value="operator">operator</option>
              <option value="admin">admin</option>
            </select>
            <select className="pole" value={waznosc} onChange={(e) => setWaznosc(Number(e.target.value))}>
              {WAZNOSC.map((w, i) => <option key={w.etykieta} value={i}>{w.etykieta}</option>)}
            </select>
            <label className="pole-etykieta poziomo">
              <input type="checkbox" checked={jednorazowe} onChange={(e) => setJednorazowe(e.target.checked)} />
              jednorazowe
            </label>
            <button type="submit" className="przelacznik" disabled={!imie.trim()}>ZAPROŚ</button>
          </form>

          {nowyLink && (
            <div className="nowy-link">
              <div className="przypis">Link dla: <strong>{nowyLink.imie}</strong> — wyślij go dowolną drogą.</div>
              <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">{nowyLink.link}</code>
              {/* Sam kod, do wklejenia ręcznie na ekranie wejścia. Potrzebny wtedy,
                  gdy gość wchodzi z innego adresu niż ten w linku — albo gdy link
                  po drodze rozjedzie się w komunikatorze. */}
              <div className="przypis">albo sam kod, do wklejenia na ekranie wejścia:</div>
              <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">{nowyLink.kod}</code>

              {/* Dla aplikacji na telefonie: jeden ciąg niosący kod I adres stacji.
                  Sam kod wystarcza tylko temu, kto już umie otworzyć stronę stacji. */}
              <div className="przypis">
                <strong>kod połączeniowy</strong> — dla aplikacji na telefonie; niesie
                też adres stacji, więc nie trzeba go wpisywać osobno:
              </div>
              <code className="endpoint maly" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">
                {zbudujKodPolaczenia(window.location.origin, nowyLink.kod)}
              </code>
              {NA_LOKALNYM && (
                <p className="przypis blad">
                  Masz otwarty panel na <code>{window.location.hostname}</code>, więc link
                  <strong> i kod połączeniowy</strong> zadziałają <strong>tylko na tej
                  maszynie</strong> — oba biorą adres stąd. Wyślij sam kod, albo otwórz
                  panel pod adresem, którym łączy się gość — adresy stacji są niżej,
                  w sekcji ŁĄCZA I ADRESY.
                </p>
              )}
              <button type="button" className="przelacznik drobny" onClick={() => setNowyLink(null)}>SCHOWAJ</button>
            </div>
          )}

          <table className="tabela">
            <tbody>
              {(stan?.zaproszenia || []).filter((z) => z.wazne).map((z) => (
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
                        className="przelacznik drobny"
                        onClick={() => dzialanie(api(`/api/admin/zaproszenie/${z.id}`, { method: "DELETE" }))}
                      >
                        UNIEWAŻNIJ
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {stan?.zaproszenia?.filter((z) => z.wazne).length === 0 && (
                <tr><td colSpan={6} className="przypis">brak ważnych zaproszeń</td></tr>
              )}
            </tbody>
          </table>
        </section>

        {/* ---- łącza i adresy ---- */}
        <section>
          <div className="etykieta">ŁĄCZA I ADRESY</div>
          <div className="rzad rozstrzelony">
            <span className={`dioda ${stan?.mediamtx ? "ok" : "zla"}`}>MEDIAMTX</span>
            <span className={`dioda ${stan?.telemetria?.zywe ? "ok" : "zla"}`}>TELEMETRIA</span>
            <span className="przypis">
              sesji obrazu: {stan?.sesjeObrazu?.length ?? 0}
            </span>
          </div>

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

          <ul className="lista-adresow">
            {(adresy?.adresy || []).map((a) => (
              <li key={`${a.interfejs}-${a.adres}`}>
                <span className="przypis">{a.interfejs}</span>
                <code>{`http://${a.adres}:${adresy.porty.strona}`}</code>
              </li>
            ))}
          </ul>
        </section>

        {/* ---- archiwum ---- */}
        <section>
          <div className="rzad rozstrzelony">
            <div className="etykieta">ARCHIWUM</div>
            <span className={`dioda ${archiwum?.nagrywam ? "ok" : ""}`}>
              {archiwum?.nagrywam ? "TELEMETRIA — NAGRYWAM" : "TELEMETRIA — CISZA"}
            </span>
            <button type="button" className="przelacznik drobny" onClick={pobierzArchiwum}>
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
                    onClick={() => zmienArchiwum({ wideo: t.id })}
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
                    onBlur={(e) => zmienArchiwum({ trzymajDni: e.target.value })}
                  />
                </label>
                <label className="pole-etykieta">
                  LIMIT GB
                  <input
                    type="number"
                    className="pole waskie"
                    min={1}
                    defaultValue={archiwum.limitGb}
                    onBlur={(e) => zmienArchiwum({ limitGb: e.target.value })}
                  />
                </label>
                <button
                  type="button"
                  className="przelacznik drobny"
                  onClick={() =>
                    api("/api/admin/archiwum/sprzataj", { method: "POST" })
                      .then((o) => setArchiwum(o.stan))
                      .catch((e) => setBlad(String(e.message || e)))
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

        {/* ---- rejestr techniczny ---- */}
        <section>
          <div className="rzad rozstrzelony">
            <div className="etykieta">REJESTR TECHNICZNY — co się zepsuło</div>
            <select
              className="pole"
              value={poziomLogow}
              onChange={(e) => setPoziomLogow(e.target.value)}
            >
              <option value="blad">same błędy</option>
              <option value="ostrzezenie">błędy i ostrzeżenia</option>
              <option value="info">wszystko poza szczegółami</option>
              <option value="szczegol">ze szczegółami</option>
            </select>
            <button type="button" className="przelacznik drobny" onClick={() => pobierzLogi(poziomLogow)}>
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
                  {w.kontekst?.stos && (
                    <span className="stos">{w.kontekst.stos}</span>
                  )}
                </span>
              </li>
            ))}
            {logi && logi.wpisy.length === 0 && <li className="przypis">czysto — nic się nie zepsuło</li>}
          </ul>
          {logi?.plik && <p className="przypis">pełny zapis: <code>{logi.plik}</code></p>}
        </section>

        {/* ---- dziennik dostępu ---- */}
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
      </div>
    </div>
  );
}
