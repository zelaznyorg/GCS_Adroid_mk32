// Rysowanie kodu QR na płótnie — osobno od komponentu, żeby dało się to wywołać
// (i sprawdzić) bez Reacta.
//
// ### Dlaczego rysujemy po module, a nie bierzemy gotowego obrazka
//
// `qrcode-generator` umie oddać gotowy `<img>`, ale w formacie GIF i w rozmiarze,
// który sam wybierze. Na ekranie stacji (oglądanym z odległości ręki) i przy zapisie
// na pendrive potrzebny jest PNG o znanym boku i z ciszą dookoła — więc rysujemy
// sami: dziesięć linii, a daje ostry kod w każdym rozmiarze.
import qrcode from "qrcode-generator";

/** Modułów ciszy dookoła kodu. Poniżej czterech czytniki zaczynają gubić narożniki. */
export const MARGINES = 4;

/** Korekcja błędów: „M" znosi zabrudzony ekran i odbicie światła, a nie rozdyma kodu. */
export const KOREKCJA = "M";

/**
 * Rysuje kod na płótnie. Zwraca rzeczywisty bok w pikselach — zaokrąglony w dół
 * do pełnych modułów, żeby żaden nie wyszedł rozmyty na pół piksela.
 */
export function narysuj(canvas, tresc, bokZadany) {
  if (!canvas || !tresc) return 0;
  const kod = qrcode(0, KOREKCJA);
  kod.addData(String(tresc));
  kod.make();

  const modulow = kod.getModuleCount() + 2 * MARGINES;
  const piksel = Math.max(1, Math.floor(bokZadany / modulow));
  const bok = piksel * modulow;

  canvas.width = bok;
  canvas.height = bok;
  const g = canvas.getContext("2d");
  // Biel i czerń na sztywno, nie z motywu: kod ma być czytelny dla aparatu, a nie
  // pasować do reszty panelu. Ciemny QR na ciemnym tle nie skanuje się wcale.
  g.fillStyle = "#ffffff";
  g.fillRect(0, 0, bok, bok);
  g.fillStyle = "#000000";
  for (let w = 0; w < kod.getModuleCount(); w += 1) {
    for (let k = 0; k < kod.getModuleCount(); k += 1) {
      if (kod.isDark(w, k)) {
        g.fillRect((k + MARGINES) * piksel, (w + MARGINES) * piksel, piksel, piksel);
      }
    }
  }
  return bok;
}

/** Pobranie pliku przez przeglądarkę — bez tego PNG zostaje tylko na ekranie. */
export function pobierzPlik(dataUrl, nazwa) {
  if (!dataUrl) return;
  const a = document.createElement("a");
  a.href = dataUrl;
  a.download = nazwa;
  document.body.appendChild(a);
  a.click();
  a.remove();
}
