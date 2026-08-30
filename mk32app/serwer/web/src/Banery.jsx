// Banery ostrzeżeń wariantu D — odpowiednik `Baner` z ui/Elementy.kt.
//
// Pełna tafla (nieprzezroczysta), obwód w kolorze wagi, wypełnienie tym samym kolorem
// na 16 %, znak przed tekstem. **Nigdy sam kolor** — dok/UI.md §2: każdy stan ma też
// znak i słowo, żeby dało się go odczytać na słońcu i przy wadzie widzenia barw.
//
// Ostrzeżenia liczy SERWER, nie ten komponent (ARCHITEKTURA.md §3.1). Dzięki temu każdy
// widz widzi dokładnie to samo co operator i żaden nowy klient nie może o nich zapomnieć.

const ZNAKI = {
  blokada: "⛔",
  ostrzezenie: "⚠",
  informacja: "○",
};

export default function Banery({ ostrzezenia = [], czekaNaDane = false, polaczony = false }) {
  if (czekaNaDane) {
    return (
      <div className="banery">
        <div className="baner czeka">
          <span className="baner-znak">○</span>
          <span className="baner-tekst">
            {polaczony ? "CZEKAM NA DANE" : "BRAK POŁĄCZENIA Z SERWEREM"}
          </span>
        </div>
      </div>
    );
  }

  if (!ostrzezenia.length) return null;

  // Kolejność ważności: blokady przed ostrzeżeniami. Serwer zwraca je w swojej
  // kolejności, my ją tylko utrwalamy — sortowanie stabilne zachowa resztę.
  const waga = { blokada: 0, ostrzezenie: 1, informacja: 2 };
  const uszeregowane = [...ostrzezenia].sort(
    (a, b) => (waga[a.poziom] ?? 3) - (waga[b.poziom] ?? 3),
  );

  return (
    <div className="banery">
      {uszeregowane.map((o) => (
        <div key={o.id} className={`baner ${o.poziom}`}>
          <span className="baner-znak">{ZNAKI[o.poziom] ?? "○"}</span>
          <span className="baner-tekst">{o.tekst}</span>
        </div>
      ))}
    </div>
  );
}
