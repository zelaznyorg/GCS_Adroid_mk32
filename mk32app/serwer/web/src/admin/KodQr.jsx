// Kod QR — najkrótsza droga od ekranu stacji do telefonu gościa.
//
// ### Po co, skoro jest link
//
// Link trzeba komuś WYSŁAĆ, a przy stacji nie ma ani poczty, ani komunikatora,
// ani klawiatury. Dotąd zostawało przepisywanie 24 znaków kodu z ekranu na
// telefon — w polu, w rękawicach, przy słońcu. Kod QR zdejmuje ten krok: gość
// celuje aparatem w ekran stacji i jest w środku.
//
// Trzy drogi wyjścia, bo trzy różne sytuacje przy maszynie:
//   POWIĘKSZ      — gość stoi obok; kod na pół ekranu widać z dwóch metrów
//   NA PENDRIVE   — gościa nie ma; kod jedzie na nośniku (server/nosniki.mjs)
//   WYŚLIJ/KOPIUJ — panel otwarty na telefonie albo laptopie, gdzie poczta jest
//
// ### ⛔ Ten obrazek JEST kluczem
//
// Kod niesie zaproszenie, więc kto go sfotografuje, ten wejdzie. Dlatego nie
// pokazuje się sam z siebie — trzeba go włączyć — a przy roli `admin` mówimy
// wprost, co się oddaje. To samo dotyczy pendrive'a: zapis zostawia ślad
// w dzienniku dostępu.
//
// Samo rysowanie siedzi w `qr.js` — bez Reacta, więc da się je wywołać i sprawdzić
// osobno, a fast refresh nie potyka się o plik eksportujący i komponent, i funkcję.
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../sesja";
import { rozmiar, tekst } from "./pomoc";
import { narysuj, pobierzPlik } from "./qr";

export default function KodQr({ tresc, nazwaPliku = "zaproszenie.png", podpis, ostrzezenie, bok = 220 }) {
  const plotno = useRef(null);
  const plotnoDuze = useRef(null);
  const [powiekszony, setPowiekszony] = useState(false);
  const [nosniki, setNosniki] = useState(null);      // null = jeszcze nie pytaliśmy
  const [zapis, setZapis] = useState(null);          // komunikat po zapisie
  const [blad, setBlad] = useState(null);
  const [skopiowane, setSkopiowane] = useState(false);

  const schowek = typeof navigator !== "undefined" && Boolean(navigator.clipboard?.writeText);
  const udostepnianie = typeof navigator !== "undefined" && typeof navigator.share === "function";

  useEffect(() => {
    narysuj(plotno.current, tresc, bok);
  }, [tresc, bok]);

  useEffect(() => {
    if (!powiekszony) return undefined;
    // Kod na pół ekranu: bierzemy krótszy bok okna, bo ekran stacji bywa i pionowy.
    const bokDuzy = Math.min(window.innerWidth, window.innerHeight) - 160;
    narysuj(plotnoDuze.current, tresc, Math.max(240, bokDuzy));
    const klawisz = (e) => {
      if (e.key === "Escape") setPowiekszony(false);
    };
    window.addEventListener("keydown", klawisz);
    return () => window.removeEventListener("keydown", klawisz);
  }, [powiekszony, tresc]);

  const png = useCallback(() => plotno.current?.toDataURL("image/png") || null, []);

  const pytajONosniki = () => {
    setBlad(null);
    setZapis(null);
    api("/api/admin/nosniki")
      .then((d) => setNosniki(d.nosniki || []))
      .catch((e) => setBlad(tekst(e)));
  };

  const zgraj = (nosnik) => {
    const obraz = png();
    if (!obraz) return;
    setBlad(null);
    api("/api/admin/nosniki/zapisz", {
      method: "POST",
      body: { nosnik: nosnik.sciezka, nazwa: nazwaPliku, png: obraz, tekst: tresc },
    })
      .then((d) => {
        setZapis(`Zgrane na ${d.nosnik}: ${d.pliki.map((p) => p.split("/").pop()).join(", ")}`);
        setNosniki(null);
      })
      .catch((e) => setBlad(tekst(e)));
  };

  const wyslij = () => {
    setBlad(null);
    navigator
      .share({ title: podpis || "Zaproszenie do Panoramy", text: podpis || "", url: tresc })
      .catch(() => { /* użytkownik zamknął arkusz udostępniania — to nie błąd */ });
  };

  const kopiuj = () => {
    navigator.clipboard
      .writeText(tresc)
      .then(() => {
        setSkopiowane(true);
        setTimeout(() => setSkopiowane(false), 2000);
      })
      .catch(() => setBlad("Schowek odmówił — zaznacz adres i skopiuj ręcznie."));
  };

  return (
    <div className="kod-qr">
      <div className="kod-qr-obraz">
        <button
          type="button"
          className="kod-qr-plotno"
          onClick={() => setPowiekszony(true)}
          title="Powiększ na pół ekranu — do zeskanowania z odległości"
        >
          <canvas ref={plotno} />
        </button>
        <div className="kod-qr-opis">
          {podpis && <div className="etykieta">{podpis}</div>}
          <p className="przypis">Zeskanuj aparatem telefonu — otworzy stronę i wpuści bez wpisywania kodu.</p>
          {ostrzezenie && <p className="przypis blad">{ostrzezenie}</p>}
        </div>
      </div>

      <div className="rzad">
        <button type="button" className="przelacznik drobny" onClick={() => setPowiekszony(true)}>
          POWIĘKSZ
        </button>
        <button
          type="button"
          className="przelacznik drobny"
          onClick={() => pobierzPlik(png(), nazwaPliku)}
          title="Zapisuje PNG tam, gdzie ta przeglądarka trzyma pobrane pliki"
        >
          POBIERZ PNG
        </button>
        <button
          type="button"
          className={`przelacznik drobny ${nosniki ? "wlaczony" : ""}`}
          onClick={() => (nosniki ? setNosniki(null) : pytajONosniki())}
          title="Zgraj kod na pendrive wetknięty do stacji"
        >
          NA PENDRIVE
        </button>
        {udostepnianie && (
          <button type="button" className="przelacznik drobny" onClick={wyslij} title="Wyślij dalej — poczta, komunikator, cokolwiek jest na tym urządzeniu">
            WYŚLIJ
          </button>
        )}
        {schowek && (
          <button type="button" className="przelacznik drobny" onClick={kopiuj}>
            {skopiowane ? "SKOPIOWANE ✓" : "KOPIUJ ADRES"}
          </button>
        )}
      </div>

      {nosniki && (
        <div className="lista-nosnikow">
          {nosniki.length === 0 && (
            <p className="przypis">
              Nie widzę pendrive'a. Włóż go do stacji — pulpit montuje go sam, a potem
              naciśnij <strong>NA PENDRIVE</strong> jeszcze raz.
            </p>
          )}
          {nosniki.map((n) => (
            <button key={n.sciezka} type="button" className="przelacznik drobny" onClick={() => zgraj(n)}>
              ZGRAJ NA {n.nazwa.toUpperCase()}
              <span className="przypis drobne"> {n.system} · wolne {rozmiar(n.wolneBajtow)}</span>
            </button>
          ))}
        </div>
      )}

      {zapis && <p className="przypis">{zapis}</p>}
      {blad && <p className="przypis blad">{blad}</p>}

      {powiekszony && (
        <div className="kod-qr-pelny" onClick={() => setPowiekszony(false)}>
          <canvas ref={plotnoDuze} />
          <div className="kod-qr-pelny-opis">
            {podpis && <div className="etykieta">{podpis}</div>}
            <code className="endpoint maly">{tresc}</code>
            <button type="button" className="przelacznik" onClick={() => setPowiekszony(false)}>
              ZAMKNIJ
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
