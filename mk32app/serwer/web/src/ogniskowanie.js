// Wodzenie ogniskiem po stronie — wspólne dla pokrętła i dla klawiszy.
//
// ### Dlaczego to jest osobny plik
//
// Stacja ma DWIE drogi sterowania bez myszy i obie muszą chodzić po tych samych
// pozycjach, w tej samej kolejności:
//
//   1. **pokrętło przez most** (`usePokretlo.js`) — gdy strona trzyma pokrętło,
//   2. **klawisze z pilota pulpitu** (`useStrzalki.js`) — `↑ ↓ ← →`, `ENTER`,
//      `TAB`, `ESC`, wysyłane przez `gcs_pulpit/pilot.py` przez `wlrctl`.
//
// Druga droga działa **niezależnie od nas**: pilot wysyła zwykłe klawisze, których
// nie da się odróżnić od klawiatury, i nie pyta nikogo o pozwolenie. Dlatego strona
// musi je rozumieć nawet wtedy, gdy pokrętła nie trzyma — a wtedy obie drogi nie
// mogą wodzić po dwóch różnych listach.
//
// ⚠ Nie budujemy własnej listy „pozycji menu". Kolejność bierze się z układu strony,
// więc nie ma czego synchronizować przy zmianie interfejsu.

/** Co da się wskazać. Kolejność wynika z układu strony, nie z osobnej listy. */
export const WSKAZYWALNE =
  'button:not([disabled]):not([tabindex="-1"]), select:not([disabled]), ' +
  'input:not([disabled]):not([type="hidden"]), [href], [tabindex]:not([tabindex="-1"])';

/**
 * Klasa znacząca „tu stoi wskazanie" — zakładana ręcznie, NIE przez `:focus`.
 *
 * Powód wyszedł przy próbach: gdy okno przeglądarki nie ma ogniska systemowego
 * (`document.hasFocus() === false`), selektor `:focus` przestaje pasować i obwódka
 * znika, choć `document.activeElement` wskazuje poprawny element.
 */
export const KLASA_OGNISKA = "ognisko-pokretla";

export function oznaczOgnisko(el) {
  for (const stary of document.querySelectorAll(`.${KLASA_OGNISKA}`)) {
    stary.classList.remove(KLASA_OGNISKA);
  }
  el?.classList.add(KLASA_OGNISKA);
}

/** Pola, w które się WCHODZI, zamiast je naciskać. */
export function polePodmiany(el) {
  if (!el) return null;
  if (el.tagName === "SELECT") return "lista";
  if (el.tagName === "INPUT" && el.type === "number") return "liczba";
  if (el.tagName === "INPUT" && (el.type === "text" || el.type === "")) return "tekst";
  return null;
}

/**
 * Widoczne i naprawdę klikalne. `offsetParent === null` odsiewa schowane, a rozmiar
 * — te, które są w drzewie, ale mają zerowe pole.
 */
export function widoczny(el) {
  if (el.offsetParent === null && getComputedStyle(el).position !== "fixed") return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
}

/**
 * Obszar, po którym chodzi wskazanie. Gdy otwarty jest panel, zostajemy W NIM —
 * inaczej wodzenie wyprowadzałoby ognisko na przyciski schowane pod zasłoną,
 * a operator traciłby orientację, nie widząc, gdzie stoi.
 */
export function obszar() {
  return document.querySelector(".zaslona.panel .karta") || document.body;
}

/**
 * Atrybut na elemencie, którego **pokrętło** ma nie dosięgnąć.
 *
 * ### ⛔ Pokrętło nie może wyłączyć samo siebie
 *
 * Klawisz `POKRĘTŁO` stoi w dolnym pasku, czyli w tej samej liście, po której
 * pokrętło wodzi. Zmierzone na stacji 2026-08-29: klik trafił w ten klawisz
 * i strona oddała pokrętło — panel odnotował `Ognisko pokrętła: panel` **bez**
 * ostrzeżenia o przytrzymaniu, czyli to nie operator je odebrał, tylko my sami.
 * Przy stacji nie ma myszy, więc z takiego stanu nie ma jak wrócić pokrętłem.
 *
 * Oddawanie pokrętła należy do panelu — ma na to długie przytrzymanie (2,8 s)
 * i robi to niezawodnie. Klawisz zostaje dla myszy, dotyku i klawiszy pilota,
 * bo tamtymi drogami wyłączenie jest świadome i odwracalne.
 */
export const BEZ_POKRETLA = "data-bez-pokretla";

/**
 * @param {{pomijajZablokowane?: boolean}} opcje — `pomijajZablokowane` odsiewa
 *   pozycje oznaczone `BEZ_POKRETLA`; używa tego wyłącznie wodzenie pokrętłem.
 */
export function elementy({ pomijajZablokowane = false } = {}) {
  let lista = [...obszar().querySelectorAll(WSKAZYWALNE)].filter(widoczny);
  if (pomijajZablokowane) lista = lista.filter((el) => !el.hasAttribute(BEZ_POKRETLA));
  return lista;
}

/** Przesuwa wskazanie o `krok` pozycji. Zawija się na końcach. */
export function przesunOgnisko(krok, opcje) {
  const lista = elementy(opcje);
  if (!lista.length) return null;
  const teraz = document.activeElement;
  let i = lista.indexOf(teraz);
  // Gdy ognisko jest gdzieś indziej (np. na `body` zaraz po otwarciu panelu),
  // wchodzimy od początku listy zamiast zgadywać — inaczej pierwszy ruch
  // skakałby w losowe miejsce.
  i = i < 0 ? (krok > 0 ? -1 : 0) : i;
  const nast = lista[(i + krok + lista.length) % lista.length];
  nast?.focus({ preventScroll: false });
  oznaczOgnisko(nast);
  nast?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  return nast;
}
