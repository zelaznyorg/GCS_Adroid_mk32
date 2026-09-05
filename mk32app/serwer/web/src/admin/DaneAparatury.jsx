// Dane do wpisania w aparaturze: hasło źródła, adres dla Horyzontu, adres RTMP dla Pilota 2.
//
// Osobny, pełnoszeroki blok — nie komórka tabeli. Powód (Tom, 2026-09-05, przy
// kontrolerze DJI w ręku): hasło i adres siedziały w wąskiej komórce obok klawiszy
// i na ekranie stacji były obcięte. Hasło ma 24 znaki i przepisuje się je ręcznie
// na klawiaturze kontrolera, więc ma być DUŻE, w całości, monospace, z odstępem
// między znakami, żeby `l`, `1`, `I`, `0` i `O` dało się odróżnić.
//
// Schowek (`navigator.clipboard`) istnieje tylko na https:// i localhost, a stacja
// chodzi po http:// (dok/TELEFON.md §4) — klawisze KOPIUJ pokazujemy więc tylko tam,
// gdzie zadziałają. Kliknięcie w wartość zawsze ją zaznacza.
import { useState } from "react";
import { zaznacz } from "./pomoc";

const maskuj = (adres) => String(adres || "").replace(/pass=.*$/, "pass=••••••••");

export default function DaneAparatury({ zrodlo, pokaz, naPokaz, duplikaty = [] }) {
  const [skopiowane, setSkopiowane] = useState(null);
  const schowek = typeof navigator !== "undefined" && Boolean(navigator.clipboard?.writeText);

  const kopiuj = (co, tekst) =>
    navigator.clipboard
      .writeText(tekst)
      .then(() => {
        setSkopiowane(co);
        setTimeout(() => setSkopiowane(null), 2000);
      })
      .catch(() => setSkopiowane("blad"));

  return (
    <div className="dane-aparatury">
      <div className="etykieta">DANE DO WPISANIA W APARATURZE — {zrodlo.nazwa}</div>

      <div className="dana">
        <span className="etykieta">HASŁO ŹRÓDŁA — {String(zrodlo.haslo || "").length} znaków, wielkość liter ma znaczenie</span>
        <code className={`sekret ${pokaz ? "" : "ukryty"}`} onClick={zaznacz} title="Kliknij, żeby zaznaczyć">
          {pokaz ? zrodlo.haslo : "••••••••••••"}
        </code>
      </div>

      <div className="dana">
        <span className="etykieta">HORYZONT (zrzut ekranu) — ADRES STACJI</span>
        <code className="sekret" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">{zrodlo.adresZrzutu}</code>
      </div>

      <div className="dana">
        <span className="etykieta">PILOT 2 / DJI FLY — ADRES RTMP (hasło w środku)</span>
        <code className="sekret drobniejszy" onClick={zaznacz} title="Kliknij, żeby zaznaczyć">
          {pokaz ? zrodlo.adresRtmp : maskuj(zrodlo.adresRtmp)}
        </code>
      </div>

      {duplikaty.length > 0 && (
        <p className="przypis blad">
          To samo hasło ma też: {duplikaty.join(", ")}. Zrzut ekranu nie podaje ścieżki, więc
          z tym hasłem trafi zawsze do pierwszego źródła z listy — daj każdemu dronowi własne (NOWE HASŁO).
        </p>
      )}

      <div className="rzad">
        <button
          type="button"
          className="przelacznik drobny"
          onClick={naPokaz}
          title="Hasło pokazujemy tylko na żądanie — ekran bywa oglądany przez ramię"
        >
          {pokaz ? "SCHOWAJ HASŁO" : "POKAŻ HASŁO"}
        </button>
        {pokaz && schowek && (
          <>
            <button type="button" className="przelacznik drobny" onClick={() => kopiuj("haslo", zrodlo.haslo)}>
              {skopiowane === "haslo" ? "SKOPIOWANE ✓" : "KOPIUJ HASŁO"}
            </button>
            <button type="button" className="przelacznik drobny" onClick={() => kopiuj("rtmp", zrodlo.adresRtmp)}>
              {skopiowane === "rtmp" ? "SKOPIOWANE ✓" : "KOPIUJ ADRES RTMP"}
            </button>
          </>
        )}
        {skopiowane === "blad" && <span className="przypis blad">schowek odmówił — zaznacz i skopiuj ręcznie</span>}
      </div>
    </div>
  );
}
