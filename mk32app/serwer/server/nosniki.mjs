// Nośniki wymienne stacji — pendrive, na który zgrywa się kod QR zaproszenia.
//
// ### Skąd tu pendrive
//
// Przy stacji nie ma ani myszy, ani poczty, ani komunikatora. Kod dla gościa
// trzeba czasem WYNIEŚĆ: wydrukować z laptopa, pokazać na innym ekranie, wkleić
// do zgłoszenia lotu. Ekran stacji pokaże QR do zeskanowania telefonem, a kto
// nie ma telefonu pod ręką, wkłada pendrive.
//
// ### Skąd wiemy, gdzie on jest
//
// Montuje go pulpit GCS, nie my: jądro → udev (`99-gcs-nosnik.rules`) →
// `gcs-nosnik zamontuj` → `/media/gcs/<ETYKIETA>`. Czytamy więc to, co system
// już wie (`/proc/mounts`), i nie dotykamy montowania ani odmontowania — to
// należy do pulpitu, razem z jego ekranem PLIKI.
//
// ⛔ Zapisujemy WYŁĄCZNIE pod ścieżkę, którą system melduje jako zamontowany
// nośnik w tym katalogu. Nazwa pliku jest PRZEPISYWANA na bezpieczny zestaw
// znaków, nigdy sklejana z tym, co przyszło z żądania. Inaczej ten punkt byłby
// zapisem w dowolne miejsce na karcie stacji.
import { readFile, writeFile, statfs } from "node:fs/promises";
import { join, basename } from "node:path";
import * as rejestr from "./rejestr.mjs";

/** Gdzie pulpit GCS montuje pendrive'y. Ta sama zmienna, co po jego stronie. */
export const KATALOG_NOSNIKOW = process.env.GCS_NOSNIKI || "/media/gcs";

/** Największy plik, jaki wolno tędy zapisać. Kod QR to kilka kilobajtów. */
export const MAKS_BAJTOW = 512 * 1024;

/** `/proc/mounts` koduje spacje i tabulatory ósemkowo — inaczej rozjechałby się podział na pola. */
function odkoduj(s) {
  return String(s || "").replace(/\\(\d{3})/g, (_, ozn) => String.fromCharCode(parseInt(ozn, 8)));
}

/**
 * Punkty montowania z `/proc/mounts` leżące bezpośrednio w `katalog`.
 *
 * Wydzielone z odczytu pliku, żeby dało się to sprawdzić testem — format jest
 * prosty, ale ma pułapkę: spacje w nazwie nośnika przyjeżdżają jako `\040`.
 */
export function parsujMounts(tekst, katalog = KATALOG_NOSNIKOW) {
  const przedrostek = katalog.endsWith("/") ? katalog : `${katalog}/`;
  const out = [];
  for (const linia of String(tekst || "").split("\n")) {
    const pola = linia.split(" ");
    if (pola.length < 3) continue;
    const punkt = odkoduj(pola[1]);
    if (!punkt.startsWith(przedrostek)) continue;
    // Tylko bezpośrednie dzieci katalogu — katalog NA nośniku to nie nośnik.
    if (punkt.slice(przedrostek.length).includes("/")) continue;
    out.push({ sciezka: punkt, nazwa: basename(punkt), system: pola[2], urzadzenie: odkoduj(pola[0]) });
  }
  return out;
}

/**
 * Nazwa pliku bezpieczna do zapisania na nośniku.
 *
 * ⛔ Nie „oczyszczona", tylko PRZEPISANA: bierzemy wyłącznie znaki z białej listy,
 * a resztę zastępujemy myślnikiem. `basename` na wejściu ucina każdą próbę podania
 * ścieżki, także `..`.
 */
export function bezpiecznaNazwa(nazwa, domyslna = "kod-qr.png") {
  const czysta = basename(String(nazwa || ""))
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    // ⚠ „ł" i „Ł" NIE rozkładają się w NFD — to osobne litery, nie „l" z kreską.
    // Bez tej linii „zaproszenie-Łukasz.png" lądowało na pendrivie jako
    // „zaproszenie--ukasz.png", a nazwa pliku ma mówić, czyje to zaproszenie.
    .replace(/ł/g, "l")
    .replace(/Ł/g, "L")
    .replace(/[^A-Za-z0-9._-]+/g, "-")
    .replace(/^[-.]+/, "")
    .slice(0, 64);
  return czysta || domyslna;
}

/** Zamontowane nośniki razem z wolnym miejscem. Nigdy nie rzuca — brak nośnika to nie awaria. */
export async function lista() {
  let tekst;
  try {
    tekst = await readFile("/proc/mounts", "utf8");
  } catch {
    // Nie Linux albo brak /proc — na maszynie deweloperskiej to normalne.
    return [];
  }
  const out = [];
  for (const n of parsujMounts(tekst)) {
    let wolneBajtow = null;
    try {
      const s = await statfs(n.sciezka);
      wolneBajtow = s.bavail * s.bsize;
    } catch {
      /* nośnik mógł właśnie zniknąć — pokazujemy go bez liczby */
    }
    out.push({ ...n, wolneBajtow });
  }
  return out;
}

/**
 * Zapisuje plik na WSKAZANYM nośniku — o ile system nadal melduje go jako zamontowany.
 * Zwraca ścieżkę i rozmiar; rzuca czytelnym błędem, gdy się nie da.
 */
export async function zapisz({ nosnik, nazwa, dane, domyslnaNazwa }) {
  const zamontowane = await lista();
  const cel = zamontowane.find((n) => n.sciezka === nosnik);
  if (!cel) {
    throw new Error(
      zamontowane.length
        ? "Tego nośnika już nie ma — odśwież listę."
        : `Nie widzę pendrive'a w ${KATALOG_NOSNIKOW}. Włóż go i poczekaj, aż pulpit go zamontuje.`,
    );
  }
  if (!Buffer.isBuffer(dane) || !dane.length) throw new Error("Pusty plik — nie ma czego zapisać.");
  if (dane.length > MAKS_BAJTOW) throw new Error(`Plik większy niż ${Math.round(MAKS_BAJTOW / 1024)} kB.`);
  const plik = join(cel.sciezka, bezpiecznaNazwa(nazwa, domyslnaNazwa));
  try {
    await writeFile(plik, dane, { flag: "w" });
  } catch (e) {
    // Najczęstsze: nośnik zamontowany tylko do odczytu albo bez praw dla naszego konta.
    throw new Error(`Nie mogę zapisać na ${cel.nazwa}: ${e.message}`);
  }
  rejestr.info("nosniki", `zapisano ${plik}`, { bajtow: dane.length });
  return { plik, bajtow: dane.length, nosnik: cel.nazwa };
}

/** Rozbiera `data:image/png;base64,…` na bufor. Przyjmujemy wyłącznie PNG — QR idzie jako PNG. */
export function zDataUrl(dataUrl) {
  const m = /^data:image\/png;base64,([A-Za-z0-9+/=]+)$/.exec(String(dataUrl || "").trim());
  if (!m) throw new Error("Oczekuję obrazu PNG w postaci data:image/png;base64,…");
  return Buffer.from(m[1], "base64");
}
