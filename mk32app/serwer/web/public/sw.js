// Service worker aplikacji podglądu.
//
// Robi JEDNO: trzyma powłokę aplikacji (HTML, JS, CSS, ikony) na telefonie,
// żeby po dodaniu na pulpit otwierała się od razu, a nie po rundzie do serwera.
// Nie buforuje NICZEGO, co niesie stan maszyny.
//
// ⚠ Danych z drona nie wolno tu buforować i nie są buforowane.
//    Zasada 6 systemu projektowego (dok/UI.md): zamrożona liczba jest gorsza
//    niż brak liczby. Service worker, który podałby wczorajszą telemetrię
//    z pamięci podręcznej, byłby dokładnie tym błędem — na dodatek niewidocznym.
//    Dlatego /api/*, telemetria SSE i obraz WHEP idą zawsze do sieci, bez wyjątku.
//
// Uruchamia się wyłącznie w bezpiecznym kontekście (HTTPS albo localhost) —
// rejestrację odcina main.jsx. Po http:// przez tunel WireGuarda ten plik
// nigdy nie wstanie i aplikacja działa bez niego, tylko bez pamięci powłoki.
// Szczegóły: dok/TELEFON.md §4.

const WERSJA = "dron15-powloka-v1";

// Vite stempluje nazwy plików w /assets skrótem treści, więc każde wydanie ma
// inne nazwy i nie da się podać starego pliku pod nowym adresem.
const POWLOKA = [
  "/",
  "/index.html",
  "/favicon.svg",
  "/ikona-192.png",
  "/ikona-512.png",
  "/ikona-maskowalna-512.png",
  "/manifest.webmanifest",
];

self.addEventListener("install", (zdarzenie) => {
  zdarzenie.waitUntil(
    caches
      .open(WERSJA)
      // addAll przewraca całą instalację, gdy padnie jeden plik. Wolimy powłokę
      // niepełną niż brak service workera, więc dokładamy pojedynczo.
      .then((magazyn) => Promise.allSettled(POWLOKA.map((a) => magazyn.add(a))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (zdarzenie) => {
  zdarzenie.waitUntil(
    caches
      .keys()
      .then((klucze) => Promise.all(klucze.filter((k) => k !== WERSJA).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// Czy to zapytanie wolno w ogóle dotknąć.
function omijamy(zadanie, adres) {
  if (zadanie.method !== "GET") return true;
  if (adres.origin !== self.location.origin) return true;   // WHEP siedzi na porcie 8889
  if (adres.pathname.startsWith("/api/")) return true;      // telemetria, sesja, panel
  if (zadanie.headers.get("accept") === "text/event-stream") return true;
  return false;
}

self.addEventListener("fetch", (zdarzenie) => {
  const zadanie = zdarzenie.request;
  const adres = new URL(zadanie.url);
  if (omijamy(zadanie, adres)) return;                      // bez respondWith = zwykła sieć

  // Nawigacja: najpierw sieć, pamięć podręczna dopiero gdy sieci nie ma.
  // Odwrotnie byłoby wygodniej, ale wtedy po wydaniu nowej wersji telefon
  // trzymałby starą aż do wyczyszczenia danych.
  if (zadanie.mode === "navigate") {
    zdarzenie.respondWith(
      fetch(zadanie)
        .then((odp) => {
          const kopia = odp.clone();
          caches.open(WERSJA).then((m) => m.put("/index.html", kopia)).catch(() => {});
          return odp;
        })
        .catch(() => caches.match("/index.html").then((z) => z || Response.error())),
    );
    return;
  }

  // Zasoby ze stemplem treści w nazwie: z pamięci, bo pod tym adresem nic się
  // już nie zmieni. Wszystko inne — sieć z odłożeniem kopii na czarną godzinę.
  zdarzenie.respondWith(
    caches.match(zadanie).then((zPamieci) => {
      if (zPamieci) return zPamieci;
      return fetch(zadanie)
        .then((odp) => {
          if (odp.ok && odp.type === "basic") {
            const kopia = odp.clone();
            caches.open(WERSJA).then((m) => m.put(zadanie, kopia)).catch(() => {});
          }
          return odp;
        })
        .catch(() => zPamieci || Response.error());
    }),
  );
});
