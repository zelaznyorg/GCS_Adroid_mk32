// Wspólne drobiazgi kart panelu ADMIN: stałe, formaty, zaznaczanie kodów.
//
// Karty (zaproszenia, dostęp, źródła, archiwum, diagnostyka) są osobnymi plikami,
// bo każda odpowiada na inne pytanie i odświeża inne dane. To, co im wspólne,
// leży tutaj — żeby żadna nie kopiowała formatu godziny od sąsiadki.

export const ODSWIEZAJ_MS = 4000;
// Stan archiwum wymaga przejścia po katalogach na dysku, więc pytamy o niego
// znacznie rzadziej niż o widzów. Nic tam nie zmienia się z sekundy na sekundę.
export const ODSWIEZAJ_ARCHIWUM_MS = 20000;

export const WAZNOSC = [
  { etykieta: "1 godzina", min: 60 },
  { etykieta: "1 dzień", min: 60 * 24 },
  { etykieta: "bezterminowo", min: null },
];

// Opisy ról z dok/DOSTEP_I_UZYTKOWNICY.md §2 — pokazywane przy wyborze, żeby
// w polu nie trzeba było pamiętać, czym operator różni się od widza.
export const ROLE = [
  { id: "widz", opis: "ogląda obraz, telemetrię i listę pozostałych widzów" },
  { id: "operator", opis: "to samo co widz — rola zarezerwowana na przyszłe uprawnienia stanowiskowe" },
  { id: "admin", opis: "zaprasza, odcina, dodaje źródła, steruje archiwum i panelem STACJA" },
];

export const TRYBY_WIDEO = [
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

export function czasKrotki(s) {
  if (s == null) return "—";
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

export function rozmiar(b) {
  if (b == null) return "—";
  if (b < 1024) return `${b} B`;
  if (b < 1024 ** 2) return `${(b / 1024).toFixed(0)} kB`;
  if (b < 1024 ** 3) return `${(b / 1024 ** 2).toFixed(1)} MB`;
  return `${(b / 1024 ** 3).toFixed(1)} GB`;
}

export function godzina(ms) {
  if (!ms) return "—";
  return new Date(ms).toLocaleTimeString("pl-PL", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function zaznacz(e) {
  window.getSelection().selectAllChildren(e.currentTarget);
}

export const tekst = (e) => String(e?.message || e);

// Link zapraszający budujemy z adresu, POD KTÓRYM ADMIN MA OTWARTĄ TĘ STRONĘ.
// Inaczej się nie da — serwer nie wie, którą drogą gość będzie się łączył
// (LAN, tunel, adres publiczny). Stąd ostrzeżenie w karcie ZAPROSZENIA: panel
// otwarty na localhost wyprodukuje link działający wyłącznie na tej jednej maszynie.
export const linkDo = (kod) => `${window.location.origin}/#z=${kod}`;

/**
 * To samo, ale pod WSKAZANYM adresem stacji.
 *
 * ⛔ Od 2026-09-22 adres jest wyborem, nie skutkiem ubocznym. Panel otwarty na
 * stanowisku ma w pasku `127.0.0.1`, więc link i kod połączeniowy wychodziły
 * z niego martwe dla każdego poza tą jedną maszyną — panel mówił o tym
 * ostrzeżeniem, ale poprawić się tego nie dało bez otwierania panelu od nowa
 * pod innym adresem. Teraz admin wybiera, którą drogą gość przyjdzie.
 */
export const linkDoNa = (adres, kod) => `${String(adres || "").replace(/\/+$/, "")}/#z=${kod}`;

/**
 * Pamięć drobnych wyborów admina w tej przeglądarce (adres zewnętrzny, port).
 *
 * Kto raz ustawił przekierowanie na routerze, ma je przez cały sezon — a wpisywanie
 * adresu i portu przy każdym zaproszeniu pokrętłem, znak po znaku, jest dokładnie
 * tym, co ten panel ma zdejmować z operatora. Klucze w przestrzeni `dron15.*`,
 * jak reszta (sesja.js tłumaczy, czemu nazw nie zmieniamy).
 */
export function zapamietane(klucz, domyslna = "") {
  try {
    return localStorage.getItem(klucz) ?? domyslna;
  } catch {
    return domyslna;
  }
}

export function zapamietaj(klucz, wartosc) {
  try {
    localStorage.setItem(klucz, String(wartosc ?? ""));
  } catch {
    /* tryb prywatny — wybór przeżyje do zamknięcia karty */
  }
}

/** „za 43 min", „za 20 h" — ile jeszcze wpuszcza. Null znaczy bezterminowo. */
export function zostalo(wygasa) {
  if (!wygasa) return "bezterminowo";
  const ms = wygasa - Date.now();
  if (ms <= 0) return "już nie wpuszcza";
  const min = Math.round(ms / 60000);
  if (min < 60) return `jeszcze ${min} min`;
  const godz = Math.round(min / 60);
  if (godz < 48) return `jeszcze ${godz} h`;
  return `jeszcze ${Math.round(godz / 24)} dni`;
}

export const NA_LOKALNYM = ["localhost", "127.0.0.1", "::1"].includes(window.location.hostname);
