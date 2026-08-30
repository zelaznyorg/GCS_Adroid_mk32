// Mapa: gdzie jest maszyna, skąd wystartowała i którędy ma lecieć.
//
// ### Kafelki ciągnie PRZEGLĄDARKA WIDZA, nie stacja
//
// To jest decyzja, nie szczegół. Stacja stoi w sieci, która nie musi mieć internetu,
// a przez łącze radiowe i tak nie ma czego przepychać. Widz siedzi zwykle tam, gdzie
// internet jest — i to jego przeglądarka pobiera podkład wprost z OpenStreetMap.
//
// Skutki, obydwa świadome:
//   ✅ stacja nie wozi kafelków, nie potrzebuje internetu i nie robi się wąskim gardłem,
//   ⚠ widz bez internetu **nie zobaczy podkładu** — zobaczy same znaczniki na siatce.
//     Dlatego mapa nigdy nie udaje, że podkład jest: pisze wprost, że go brakuje.
//
// Kokpit na MK32 działa odwrotnie — tam kafelki leżą na karcie, bo aparatura
// w polu internetu nie ma (dok/TELEFON.md). To dwa różne stanowiska i dwie różne
// odpowiedzi na to samo pytanie.
import { useCallback, useEffect, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { api } from "./sesja";

// Ile punktów śladu trzymamy. Ślad budujemy U SIEBIE z tego, co i tak przychodzi
// telemetrią — serwer go nie liczy i nie wysyła, więc każdy widz ma własny,
// od chwili wejścia. To jest tańsze niż doklejanie historii do każdej ramki SSE.
const SLAD_MAX = 3000;
// Poniżej tego przesunięcia nie dokładamy punktu — inaczej ślad w zawisie
// zamienia się w kilkanaście tysięcy punktów w jednym miejscu.
const SLAD_MIN_M = 1.5;

const PODKLADY = [
  {
    id: "osm",
    nazwa: "OpenStreetMap",
    url: "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    opis: "© OpenStreetMap",
    maxZoom: 19,
  },
  {
    id: "topo",
    nazwa: "Topograficzna",
    url: "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
    opis: "© OpenTopoMap, © OpenStreetMap",
    maxZoom: 17,
  },
];

/**
 * Barwy bierzemy z motywu, ale **przez opcje Leafletu, nie przez klasy CSS**.
 *
 * Mapa rysuje wektory na KANWIE (`preferCanvas`), bo ślad potrafi mieć tysiące
 * punktów i w SVG by się zadławił. Kanwa nie zna arkuszy stylów: `className`
 * na warstwie nic tam nie robi i trasa wychodzi domyślnie niebieska. Zmierzone —
 * kanwa miała treść, a żadna reguła CSS jej nie dotyczyła.
 */
function barwa(nazwa, zapas) {
  if (typeof window === "undefined") return zapas;
  const v = getComputedStyle(document.documentElement).getPropertyValue(nazwa).trim();
  return v || zapas;
}

/** Odległość w metrach — wzór haversine'a, bo na dystansach lotu płaska aproksymacja kłamie. */
function metry(a, b) {
  if (!a || !b) return Infinity;
  const R = 6371000;
  const f1 = (a.lat * Math.PI) / 180;
  const f2 = (b.lat * Math.PI) / 180;
  const df = ((b.lat - a.lat) * Math.PI) / 180;
  const dl = ((b.lon - a.lon) * Math.PI) / 180;
  const h = Math.sin(df / 2) ** 2 + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/** Znacznik maszyny — strzałka obrócona kursem. Rysowana, nie obrazkowa. */
function ikonaDrona(kurs) {
  const obrot = Number.isFinite(kurs) ? kurs : 0;
  return L.divIcon({
    className: "znacznik-drona",
    html: `<svg viewBox="0 0 24 24" width="28" height="28" style="transform: rotate(${obrot}deg)">
             <path d="M12 2 L19 21 L12 17 L5 21 Z" />
           </svg>`,
    iconSize: [28, 28],
    iconAnchor: [14, 14],
  });
}

const ikonaDomu = L.divIcon({
  className: "znacznik-domu",
  html: `<svg viewBox="0 0 24 24" width="22" height="22">
           <path d="M3 12 L12 4 L21 12" /><path d="M6 11 V20 H18 V11" />
         </svg>`,
  iconSize: [22, 22],
  iconAnchor: [11, 18],
});

export default function Mapa({ stan, naOddokuj = null, osobne = false }) {
  const pojemnik = useRef(null);
  const mapa = useRef(null);
  const warstwy = useRef({});
  const slad = useRef([]);

  const [podklad, setPodklad] = useState(PODKLADY[0].id);
  const [sledz, setSledz] = useState(true);
  const [trasy, setTrasy] = useState([]);
  const [wybranaTrasa, setWybranaTrasa] = useState("");
  const [punktyTrasy, setPunktyTrasy] = useState(null);
  const [zrodloTrasy, setZrodloTrasy] = useState(null);
  const [kafelkiDzialaja, setKafelkiDzialaja] = useState(null);
  const [blad, setBlad] = useState(null);

  const poz = stan?.polozenie;
  const dom = stan?.dom;
  const maPozycje = Number.isFinite(poz?.lat) && Number.isFinite(poz?.lon);
  const maDom = Number.isFinite(dom?.lat) && Number.isFinite(dom?.lon);
  // Łącze martwe znaczy, że wszystko na tej mapie jest sprzed jego utraty.
  // Pozycja znika sama (serwer przestaje ją podawać po 10 s), ale DOM zostaje —
  // celowo, bo punkt startu się nie starzeje. Cena jest taka, że mapa mogłaby
  // wyglądać na żywą, mając na sobie wyłącznie znacznik sprzed awarii. Dlatego
  // przy martwym łączu mówimy o tym wprost. Zaobserwowane 2026-08-29: kokpit
  // na MK32 zgasł, pozycja zniknęła, a dom został jak gdyby nigdy nic.
  const laczeMartwe = stan?.lacze?.zywe === false;

  // ---- utworzenie mapy raz ----
  useEffect(() => {
    if (mapa.current || !pojemnik.current) return;
    const m = L.map(pojemnik.current, {
      zoomControl: true,
      attributionControl: true,
      // Bez animacji zoomu: przy 10 aktualizacjach pozycji na sekundę
      // animowane przesuwanie zjada procesor i szarpie.
      zoomAnimation: false,
      preferCanvas: true,
    }).setView([52.0, 20.0], 13);
    mapa.current = m;

    /**
     * Leaflet zapamiętuje rozmiar pojemnika przy tworzeniu i sam go nie sprawdza.
     * Gdy pojemnik urośnie później — a tu rośnie zawsze: panel otwiera się
     * animacją, okno oddokowane bywa przeciągane na inny monitor, użytkownik
     * zmienia rozmiar — mapa dalej liczy po starych wymiarach.
     *
     * Objaw jest mylący, bo mapa DZIAŁA: znacznik ląduje przy krawędzi zamiast
     * na środku, a kafelków dociąga się tyle, ile zmieściłoby się w pierwotnym,
     * mniejszym oknie. Zmierzone: przy oknie 1400×900 doszły **dwa** kafelki
     * i znacznik stał na lewym brzegu.
     *
     * `ResizeObserver` zamiast nasłuchu na `resize` okna, bo pojemnik zmienia
     * rozmiar także wtedy, gdy okno stoi w miejscu — na przykład przy otwarciu panelu.
     */
    const obserwator = new ResizeObserver(() => m.invalidateSize({ animate: false }));
    obserwator.observe(pojemnik.current);

    warstwy.current.slad = L.polyline([], {
      color: barwa("--akcent", "#35c7e8"),
      weight: 2,
      opacity: 0.75,
    }).addTo(m);
    warstwy.current.trasa = L.layerGroup().addTo(m);
    return () => {
      obserwator.disconnect();
      m.remove();
      mapa.current = null;
    };
  }, []);

  // ---- podkład ----
  useEffect(() => {
    const m = mapa.current;
    if (!m) return;
    const opis = PODKLADY.find((p) => p.id === podklad) || PODKLADY[0];
    if (warstwy.current.kafelki) m.removeLayer(warstwy.current.kafelki);
    const w = L.tileLayer(opis.url, { maxZoom: opis.maxZoom, attribution: opis.opis });
    // Trzy stany, nie dwa: „ładują się", „są", „nie ma internetu". Zasada 6
    // z dok/UI.md — brak wiedzy jest osobną odpowiedzią, nie udawaniem sukcesu.
    setKafelkiDzialaja(null);
    w.on("tileload", () => setKafelkiDzialaja(true));
    w.on("tileerror", () => setKafelkiDzialaja((s) => (s === true ? s : false)));
    w.addTo(m);
    warstwy.current.kafelki = w;
  }, [podklad]);

  // ---- pozycja maszyny i ślad ----
  useEffect(() => {
    const m = mapa.current;
    if (!m || !maPozycje) return;
    const p = { lat: poz.lat, lon: poz.lon };

    if (!warstwy.current.dron) {
      warstwy.current.dron = L.marker([p.lat, p.lon], { icon: ikonaDrona(poz.kurs_deg) }).addTo(m);
      m.invalidateSize({ animate: false });
      m.setView([p.lat, p.lon], 17);
    } else {
      warstwy.current.dron.setLatLng([p.lat, p.lon]);
      warstwy.current.dron.setIcon(ikonaDrona(poz.kurs_deg));
    }

    const ost = slad.current[slad.current.length - 1];
    if (!ost || metry(ost, p) >= SLAD_MIN_M) {
      slad.current.push(p);
      if (slad.current.length > SLAD_MAX) slad.current.shift();
      warstwy.current.slad.setLatLngs(slad.current.map((x) => [x.lat, x.lon]));
    }
    if (sledz) m.panTo([p.lat, p.lon], { animate: false });
  }, [poz?.lat, poz?.lon, poz?.kurs_deg, maPozycje, sledz]);

  // ---- dom ----
  useEffect(() => {
    const m = mapa.current;
    if (!m || !maDom) return;
    if (!warstwy.current.dom) {
      warstwy.current.dom = L.marker([dom.lat, dom.lon], { icon: ikonaDomu, title: "punkt startu" }).addTo(m);
    } else {
      warstwy.current.dom.setLatLng([dom.lat, dom.lon]);
    }
    // Przygaszony, gdy łącze padło — ten sam język, co przygaszone liczby
    // na nakładce (zasada 6 z dok/UI.md: wartość zna swój wiek).
    const el = warstwy.current.dom.getElement?.();
    if (el) el.style.opacity = laczeMartwe ? "0.35" : "1";
  }, [dom?.lat, dom?.lon, maDom, laczeMartwe]);

  // ---- lista tras z plików ----
  const odswiezTrasy = useCallback(() => {
    api("/api/trasy")
      .then((d) => setTrasy(d.trasy || []))
      .catch((e) => setBlad(String(e.message || e)));
  }, []);
  useEffect(() => { odswiezTrasy(); }, [odswiezTrasy]);

  // ---- wczytanie wybranej trasy ----
  // Bez czyszczenia stanu z wnętrza efektu — to robi kaskadę renderów. Zamiast
  // tego wynik nosi ze sobą nazwę trasy, której dotyczy, a widok bierze go pod
  // uwagę tylko wtedy, gdy pasuje do wyboru. Ten sam wzorzec co przy dzienniku
  // usług w panelu STACJA.
  useEffect(() => {
    if (!wybranaTrasa) return;
    const zadanie =
      wybranaTrasa === "__zlapana"
        ? api("/api/misja").then((d) => ({ punkty: d.punkty, zrodlo: "podsłuch łącza", kompletna: d.kompletna }))
        : api(`/api/trasy/${encodeURIComponent(wybranaTrasa)}`).then((d) => ({
            punkty: d.punkty,
            zrodlo: d.zrodlo,
            domPlanowany: d.domPlanowany,
          }));
    zadanie
      .then((d) => {
        setPunktyTrasy({ dla: wybranaTrasa, punkty: d.punkty || [] });
        setZrodloTrasy({ dla: wybranaTrasa, ...d });
        setBlad(null);
      })
      .catch((e) => setBlad(String(e.message || e)));
  }, [wybranaTrasa]);

  // ---- rysowanie trasy ----
  const trasaDoRysowania = wybranaTrasa && punktyTrasy?.dla === wybranaTrasa ? punktyTrasy.punkty : null;
  const opisTrasy = wybranaTrasa && zrodloTrasy?.dla === wybranaTrasa ? zrodloTrasy : null;

  useEffect(() => {
    const m = mapa.current;
    const g = warstwy.current.trasa;
    if (!m || !g) return;
    g.clearLayers();
    if (!trasaDoRysowania?.length) return;

    const nawig = trasaDoRysowania.filter((p) => p.nawigacyjny && Number.isFinite(p.lat) && Number.isFinite(p.lon));
    if (nawig.length > 1) {
      L.polyline(nawig.map((p) => [p.lat, p.lon]), {
        color: barwa("--uwaga", "#f5a623"),
        weight: 2,
        dashArray: "6 4",
      }).addTo(g);
    }
    nawig.forEach((p, i) => {
      L.circleMarker([p.lat, p.lon], {
        radius: 5,
        color: barwa("--uwaga", "#f5a623"),
        weight: 2,
        fillColor: barwa("--uwaga", "#f5a623"),
        fillOpacity: 0.35,
      })
        .bindTooltip(`${i + 1}. ${p.wysokosc_m?.toFixed?.(0) ?? "?"} m`, { direction: "top" })
        .addTo(g);
    });
    if (nawig.length) m.fitBounds(L.latLngBounds(nawig.map((p) => [p.lat, p.lon])), { padding: [40, 40] });
  }, [trasaDoRysowania]);

  // Zwykłe wyliczenie, nie useMemo: to jeden pierwiastek na render, a memoizacja
  // po polach z opcjonalnym łańcuchem i tak nie przechodzi kompilatora Reacta.
  const dystansDoDomu =
    maPozycje && Number.isFinite(dom?.lat) && Number.isFinite(dom?.lon)
      ? metry({ lat: poz.lat, lon: poz.lon }, { lat: dom.lat, lon: dom.lon })
      : null;

  return (
    <div className={`mapa-obszar ${osobne ? "osobne" : ""}`}>
      <div ref={pojemnik} className="mapa-plotno" />

      <div className="mapa-pasek">
        <select className="pole" value={podklad} onChange={(e) => setPodklad(e.target.value)} title="Podkład mapy">
          {PODKLADY.map((p) => <option key={p.id} value={p.id}>{p.nazwa}</option>)}
        </select>

        <select
          className="pole"
          value={wybranaTrasa}
          onChange={(e) => setWybranaTrasa(e.target.value)}
          title="Którą trasę pokazać"
        >
          <option value="">bez trasy</option>
          {stan?.misja?.punktow > 0 && (
            <option value="__zlapana">złapana z łącza ({stan.misja.punktow} pkt)</option>
          )}
          {trasy.map((t) => (
            <option key={t.nazwa} value={t.nazwa}>{t.nazwa} ({t.punktow} pkt)</option>
          ))}
        </select>

        <button
          type="button"
          className={`przelacznik drobny ${sledz ? "wlaczony" : ""}`}
          onClick={() => setSledz((s) => !s)}
          title="Mapa sama wodzi za maszyną"
        >
          ŚLEDŹ
        </button>

        <button
          type="button"
          className="przelacznik drobny"
          onClick={() => { slad.current = []; warstwy.current.slad?.setLatLngs([]); }}
          title="Kasuje ślad narysowany od Twojego wejścia"
        >
          WYCZYŚĆ ŚLAD
        </button>

        {naOddokuj && (
          <button type="button" className="przelacznik drobny" onClick={naOddokuj} title="Osobne okno — do przeniesienia na drugi monitor">
            ODDOKUJ
          </button>
        )}

        <span className="mapa-odczyty przypis">
          {dystansDoDomu != null && <>DOM {dystansDoDomu < 1000 ? `${dystansDoDomu.toFixed(0)} m` : `${(dystansDoDomu / 1000).toFixed(2)} km`}</>}
          {opisTrasy && <> · trasa: {opisTrasy.zrodlo}</>}
        </span>
      </div>

      {blad && <p className="przypis blad mapa-komunikat">{blad}</p>}

      {kafelkiDzialaja === false && (
        <p className="przypis mapa-komunikat">
          Podkład się nie ładuje — ta przeglądarka nie ma teraz internetu. Znaczniki, trasa
          i ślad działają dalej, brakuje tylko obrazu mapy pod nimi.
        </p>
      )}

      {laczeMartwe && (
        <p className="przypis blad mapa-komunikat">
          BRAK TELEMETRII — to, co widać na mapie, jest sprzed utraty łącza.
          {maDom && " Punkt startu został z poprzedniego odczytu; nie jest potwierdzeniem, że maszyna nadal go zna."}
        </p>
      )}

      {!maPozycje && !laczeMartwe && (
        <p className="przypis mapa-komunikat">
          Brak pozycji z maszyny. Na tym dronie kurs i pozycja pochodzą wyłącznie z GNSS —
          bez fixa mapa nie ma czego pokazać.
        </p>
      )}
    </div>
  );
}
