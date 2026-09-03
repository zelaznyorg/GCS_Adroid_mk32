// Klient webowy serwera podglądu Panorama.
//
// Obraz jest tłem, dane leżą na nim (zasada 2 systemu projektowego — dok/UI.md).
// To jest widok DLA WIDZA: pokazuje, nie steruje. Żadnego przycisku, który
// cokolwiek wysyła do maszyny — władza zostaje na MK32 (dok/WLADZA.md).
// Dotyczy to również administratora: jego panel zarządza dostępem, nie dronem.
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
// Motyw PRZED komponentami: zmienne muszą być w pakiecie wcześniej niż arkusze,
// które z nich korzystają (Hud.css wjeżdża razem z Osd.jsx).
import "./Motyw.css";
import { useWhep, STAN_OPIS } from "./useWhep";
import { useTelemetria } from "./useTelemetria";
import { useBudzik, usePelnyEkran } from "./useEkran";
import { usePokretlo } from "./usePokretlo";
import { useStrzalki } from "./useStrzalki";
import { BEZ_POKRETLA } from "./ogniskowanie";
import { api, zeton, wyloguj, BladDostepu, BladLacza, adresWhep, adresStacji, stacjaObca } from "./sesja";
import Osd from "./Osd";
import Wejscie from "./Wejscie";
import Widzowie from "./Widzowie";
import Admin from "./Admin";
import Stacja from "./Stacja";
import Mapa from "./Mapa";
import NaglowekPanelu from "./NaglowekPanelu";
import { IkonaOko, IkonaKlucz, IkonaStacja, IkonaStrumien, IkonaPelnyEkran, IkonaMapa, IkonaOddokuj, IkonaPokretlo, IkonaZamknij } from "./Ikony";
import Mozaika, { IkonaMozaika } from "./Mozaika";
import "./App.css";

// Czy to wąski ekran trzymany w ręce. Sprawdzamy RAZ, przy pierwszym renderze —
// to jest wybór domyślnego strumienia, a nie stan interfejsu, więc obrót telefonu
// nie ma prawa przełączyć widzowi obrazu pod palcami.
const TELEFON = typeof window !== "undefined" &&
  window.matchMedia?.("(max-width: 820px), (pointer: coarse)").matches === true;

// MediaMTX stoi na tym samym hoście co serwer stacji, na porcie 8889.
// Do 2026-08-23 host brało się z `window.location`; teraz z **adresu wybranej
// stacji**, bo klient umie rozmawiać ze stacją inną niż ta, która wydała stronę
// (polaczenie.js, dok/TELEFON.md §2a). Dla widza wchodzącego wprost ze stacji to
// dokładnie to samo, bo adresem domyślnym jest `window.location.origin`.

/**
 * Czy adres prosi o pokrętło od razu, bez klikania.
 *
 * ### ⛔ Bez tego stanowisko przy stacji było nie do ruszenia
 *
 * Pierwsza wersja włączała pokrętło WYŁĄCZNIE klawiszem `POKRĘTŁO`, a wybór
 * zapamiętywała w przeglądarce. Na papierze wyglądało to dobrze („przy stacji
 * pokrętło działa od razu po otwarciu strony"), ale zapamiętać można tylko to,
 * co ktoś wcześniej kliknął — **myszą**. Świeży profil Chromium nie ma czego
 * pamiętać, więc strona nie brała pokrętła i cała obsługa była nieosiągalna.
 *
 * Zmierzone na stacji 2026-08-29: most żył i doliczył się 155 zdarzeń, a mimo to
 * `trzyma` było puste. Kura i jajko w czystej postaci — żeby włączyć obsługę bez
 * myszy, trzeba było najpierw kliknąć myszą.
 *
 * Dlatego stanowisko prosi o pokrętło **adresem**: `rpi/podglad.sh` dokleja
 * `pokretlo=1`. Znacznik działa i w zapytaniu, i w kotwicy — żeton siedzi
 * w kotwicy i tam wygodniej go dopisać.
 *
 * ⚠ Nie włączamy tego wszystkim z automatu: pokrętło jest jedno i stoi przy
 * stacji, a serwer oddaje je dokładnie jednemu klientowi. Widz, który dostałby
 * je przypadkiem, odebrałby je stanowisku.
 */
function zadanoPokretla() {
  try {
    return /[?&#]pokretlo=1(?:[&#]|$)/.test(`${window.location.search}${window.location.hash}`);
  } catch {
    return false;
  }
}

export default function App() {
  const [ja, setJa] = useState(null);              // { imie, rola } albo null
  const [powodWejscia, setPowodWejscia] = useState("brak");
  const [gotowe, setGotowe] = useState(false);

  const [zrodla, setZrodla] = useState([]);
  const [wybrane, setWybrane] = useState(null);
  // Telefon startuje na strumieniu POMOCNICZYM (ZR30 `/video2`), biurko na głównym.
  // Powód jest dwojaki: sub-strumień jest lżejszy dla łącza LTE i zgodniejszy
  // z dekoderami telefonów, a główny zostaje w H.265 (decyzja z 2026-08-20,
  // dok/SERWER_PODGLADU.md §4). Widz może przełączyć jednym dotknięciem.
  const [pomocniczy, setPomocniczy] = useState(TELEFON);
  const [bladZrodel, setBladZrodel] = useState(null);
  const [panel, setPanel] = useState(null);        // "widzowie" | "admin" | "stacja" | null
  // Mozaika: wszystkie widoczne źródła naraz. Włącza się sama, gdy jest ich dwa
  // lub więcej; klik w kafelek albo wybór z listy wraca do pełnego ekranu.
  const [mozaika, setMozaika] = useState(false);
  const pierwszeZrodla = useRef(true);
  const videoRef = useRef(null);
  const ekranRef = useRef(null);

  // ---- kim jestem ----
  const rozpoznaj = useCallback(() => {
    if (!zeton()) {
      setJa(null);
      setGotowe(true);
      return;
    }
    api("/api/ja")
      .then(setJa)
      .catch((e) => {
        setJa(null);
        // Trzy różne przyczyny, trzy różne rady dla widza. „Stacja nie odpowiada"
        // znaczy sprawdź adres i tunel; „zaproszenie wygasło" znaczy poproś o nowy kod.
        // Zlanie ich w jedno „coś poszło nie tak" kosztowałoby telefon w polu
        // kwadrans zgadywania.
        if (e instanceof BladLacza) setPowodWejscia("nieosiagalna");
        else setPowodWejscia(e instanceof BladDostepu && e.status === 401 ? "wygasle" : "brak");
      })
      .finally(() => setGotowe(true));
  }, []);

  // Jednorazowe rozpoznanie zapisanej sesji jest celowym przejściem stanu aplikacji.
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(rozpoznaj, [rozpoznaj]);

  // ---- źródła obrazu ----
  // Wołane przy wejściu i po każdej zmianie w panelu ADMIN (dodanie drona, ukrycie
  // źródła) — inaczej nowy kafelek pojawiałby się dopiero po przeładowaniu strony.
  const wczytajZrodla = useCallback(() => {
    if (!ja) return;
    api("/api/zrodla")
      .then((d) => {
        const lista = d.zrodla || [];
        setZrodla(lista);
        // Administrator narzuca domyślne, widz może zmienić u siebie (decyzja 9).
        setWybrane((w) => (w && lista.some((z) => z.id === w) ? w : d.zrodloDomyslne ?? lista[0]?.id ?? null));
        // Reguła z 2026-09-03: jedno źródło → od razu pełny ekran; dwa i więcej →
        // mozaika. Decyzję podejmujemy raz, przy wejściu — potem rządzi operator.
        if (pierwszeZrodla.current) {
          pierwszeZrodla.current = false;
          setMozaika(lista.length >= 2);
        } else if (lista.length < 2) {
          setMozaika(false);
        }
      })
      .catch((e) => setBladZrodel(String(e.message || e)));
  }, [ja]);

  // Lista źródeł ładuje się przy wejściu — to celowe przejście stanu, nie skutek uboczny.
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(wczytajZrodla, [wczytajZrodla]);

  const pokazMozaike = mozaika && zrodla.length >= 2;

  const zrodlo = useMemo(() => zrodla.find((z) => z.id === wybrane) || null, [zrodla, wybrane]);

  const idStrumienia = zrodlo
    ? pomocniczy && zrodlo.maPomocniczy
      ? `${zrodlo.id}_pom`
      : zrodlo.id
    : null;

  // Adres stacji przekazujemy jawnie: siedzi w localStorage, więc React nie zauważy
  // jego zmiany sam, a po wpisaniu kodu połączeniowego telemetria i obraz muszą
  // przeskoczyć na nowy serwer.
  const stacja = adresStacji();
  // Żeton, a nie sam fakt zalogowania: przy wejściu ze stacji macierzystej adres
  // się nie zmienia, więc to pojawienie się żetonu jest jedynym sygnałem, że można
  // zestawić telemetrię.
  const zetonWidza = ja ? zeton(stacja) : null;
  const { stan, polaczony, odciety } = useTelemetria(idStrumienia, stacja, zetonWidza);
  // W mozaice pełnoekranowy odtwarzacz nie ma czego grać — kafelki mają własne
  // połączenia. Pusty identyfikator zamiast prawdziwego, żeby nie ciągnąć strumienia
  // po nic (dla ZR30 to łącze radiowe).
  const stanObrazu = useWhep(adresWhep(), pokazMozaike ? "brak" : idStrumienia ?? "brak", videoRef);
  const zywy = stanObrazu === "zywo";

  // Telefon w terenie: ekran ma nie gasnąć, dopóki jest co oglądać, i ma dać się
  // przełączyć w poziom na pełny ekran. Oba mechanizmy w useEkran.js.
  // Blokadę wygaszania bierzemy przy żywym łączu — nie przy samym otwarciu strony,
  // bo trzymanie podświetlenia nad komunikatem „BRAK SYGNAŁU" to tylko zjedzona bateria.
  const budzik = useBudzik(zywy || polaczony);
  const pelnyEkran = usePelnyEkran(ekranRef);

  // Odcięcie w trakcie oglądania wraca na ekran wejścia z podanym powodem —
  // biała strona albo migające „ŁĄCZENIE" nie mówiłyby, co się stało.
  useEffect(() => {
    if (odciety) {
      wyloguj();
      // Odcięcie przychodzi z zewnętrznego strumienia SSE i musi natychmiast
      // przełączyć widza na ekran wejścia.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setPowodWejscia("odciety");
      setJa(null);
    }
  }, [odciety]);

  /**
   * Wynosi mapę albo obraz do osobnego okna — do przeniesienia na drugi monitor.
   *
   * `window.open` z nazwą okna, żeby drugie kliknięcie w ten sam kafelek wróciło
   * do już otwartego zamiast mnożyć okna. Rozmiar podajemy, bo bez niego część
   * przeglądarek otwiera kartę zamiast okna, a karty na drugi ekran nie przeniesiesz.
   *
   * ⚠ Każde takie okno to OSOBNY WIDZ: własne SSE, własny strumień, własne miejsce
   * w limicie widzów. Przy limicie 6 i dwóch oddokowanych oknach jedna osoba zajmuje
   * trzy miejsca.
   */
  /**
   * Pokrętło stacji. Włącza je operator, bo pokrętło jest jedno i fizycznie
   * stoi przy malinie — widz na telefonie nie ma czego przejmować. Serwer
   * pilnuje, żeby trzymał je dokładnie jeden klient.
   *
   * Wybór zapamiętany w przeglądarce: stanowisko przy stacji ma pokrętło
   * działać od razu po otwarciu strony, bez klikania myszą, której tam nie ma.
   */
    const [chcePokretla, setChcePokretla] = useState(() => {
      // Sama pamięć przeglądarki nie wystarcza — patrz `zadanoPokretla` niżej.
      try {
        if (zadanoPokretla()) {
          localStorage.setItem("dron15.pokretlo", "tak");
          return true;
        }
        return localStorage.getItem("dron15.pokretlo") === "tak";
      } catch {
        return zadanoPokretla();
      }
    });
  // ⛔ Żeton MUSI iść tu jako zależność. Na świeżym profilu pojawia się dopiero
  // po wymianie kodu z adresu, a hook czytający go raz przy montażu kończył
  // wtedy pustą ręką i nigdy nie ponawiał — strona pokazywała "BRAK", choć
  // most żył i pokrętło było wolne. Zmierzone na stacji 2026-08-29.
  const pokretlo = usePokretlo({ wlaczone: chcePokretla, zetonWidza });
  // Klawisze pilota pulpitu GCS (`↑ ↓ ← →`, ENTER, TAB, ESC) działają ZAWSZE,
  // także wtedy, gdy strona nie trzyma pokrętła — to droga odwrotu, gdy most
  // jest zajęty albo panel wziął ognisko dla siebie. Opis: dok/POKRETLO.md §8.
  useStrzalki();

  /**
   * Czy to jest ekran samej stacji.
   *
   * Znak rozpoznawczy jest ten sam, co dla pokrętła: adres z `pokretlo=1`, który
   * dokleja `rpi/podglad.sh`. Zapamiętujemy go, bo strona czyści adres po wymianie
   * kodu, a klawisz ZAMKNIJ ma zostać do końca sesji.
   */
  const [jestStacja] = useState(() => {
    try {
      if (zadanoPokretla()) {
        localStorage.setItem("dron15.stacja", "tak");
        return true;
      }
      return localStorage.getItem("dron15.stacja") === "tak";
    } catch {
      return zadanoPokretla();
    }
  });

  /**
   * Zamyka okno podglądu na stacji.
   *
   * Najpierw prosimy przeglądarkę. `window.close()` bywa odrzucane dla okien,
   * których skrypt nie otwierał, więc **nie polegamy na nim** — po chwili prosimy
   * serwer, żeby zamknął proces okna. Serwer robi to tylko dla żądań z samej
   * stacji (`zSamejStacji`), więc widz z telefonu nikomu ekranu nie zgasi.
   */
  const zamknijPodglad = async () => {
    try { window.close(); } catch { /* przeglądarka odmówiła — niżej jest droga pewna */ }
    await new Promise((r) => setTimeout(r, 300));
    try {
      await api("/api/stacja/zamknij-podglad", { method: "POST" });
    } catch (e) {
      // Jedyne, co możemy jeszcze zrobić, to powiedzieć o tym wprost — okno zostaje.
      alert(`Nie udało się zamknąć okna: ${e.message}`);
    }
  };

  const przelaczPokretlo = () => {
    setChcePokretla((p) => {
      const n = !p;
      try {
        localStorage.setItem("dron15.pokretlo", n ? "tak" : "nie");
      } catch { /* tryb prywatny — wybór przeżyje do zamknięcia karty */ }
      return n;
    });
  };

  const oddokuj = (co) => {
    const p = new URLSearchParams({ okno: co });
    if (co === "obraz" && idStrumienia) p.set("zrodlo", idStrumienia);
    window.open(
      `${window.location.origin}/?${p.toString()}`,
      `dron15-${co}`,
      "width=1280,height=760,menubar=no,toolbar=no,location=no,status=no",
    );
  };

  if (!gotowe) return <div className="ekran" />;

  if (!ja) {
    return (
      <Wejscie
        powod={powodWejscia}
        naWejscie={(d) => {
          setJa(d);
          setPowodWejscia("brak");
        }}
      />
    );
  }

  return (
    <div className={`ekran ${pelnyEkran.wlaczony ? "kino" : ""}`} ref={ekranRef}>
      {/* Scena to obraz i wszystko, co go dotyczy. Na biurku i w poziomie wypełnia
          cały ekran, a dane leżą na niej. Na telefonie w pionie kurczy się do
          paska 16:9 u góry — pełnoekranowy obraz w pionie to dwa czarne pasy
          i telemetria wpisana w to, co zostało. */}
      <div className="scena">
        {pokazMozaike ? (
          <Mozaika
            zrodla={zrodla}
            bazaWhep={adresWhep()}
            naWybor={(id) => {
              setWybrane(id);
              setMozaika(false);
            }}
          />
        ) : (
        <video ref={videoRef} className="obraz" autoPlay playsInline muted />
        )}

        {!pokazMozaike && !zywy && (
          <div className="zaslona">
            <div className={`stan-obrazu ${stanObrazu}`}>{STAN_OPIS[stanObrazu]}</div>
            {stanObrazu === "brak_dekodera" && (
              <p className="podpowiedz">
                Połączenie działa, ale ta przeglądarka nie zdekodowała obrazu.
                Najczęstsza przyczyna to strumień H.265 w Firefoksie.
                {zrodlo?.maPomocniczy && " Spróbuj strumienia pomocniczego."}
              </p>
            )}
            {stanObrazu === "odmowa" && (
              <p className="podpowiedz">
                Serwer nie zgodził się wydać obrazu. Zwykle znaczy to tryb ciszy
                albo komplet widzów — telemetria może działać dalej.
              </p>
            )}
            {bladZrodel && <p className="podpowiedz">Nie udało się pobrać listy źródeł: {bladZrodel}</p>}
          </div>
        )}
      </div>

      {/* Nakładka wariantu D. Klawisze wchodzą w dolny pasek telemetrii — dokładnie
          tam, gdzie kokpit MK32 trzyma komendy. Różnica jest zasadnicza i celowa:
          tam są LĄDUJ i RTL, tutaj wyłącznie „co oglądam" (dok/WLADZA.md). */}
      <Osd
        stan={stan}
        polaczony={polaczony}
        mozaika={pokazMozaike}
        diody={
          <span className="diody lapie">
            <span className="imie-widza" title={`zalogowany jako ${ja.imie}`}>{ja.imie}</span>
            {/* Adres stacji pokazujemy tylko wtedy, gdy NIE jest to ta, spod której
                załadowano stronę. Aplikacja z pulpitu telefonu może wskazywać dowolną,
                a widz ma prawo wiedzieć, na co właściwie patrzy. */}
            {stacjaObca() && (
              <span className="imie-widza" title={`stacja: ${adresStacji()}`}>
                {adresStacji().replace(/^https?:\/\//, "")}
              </span>
            )}
            <span className={`dioda-mala ${zywy ? "ok" : "zla"}`} title="Stan obrazu">obraz</span>
            {/* Czy ekran telefonu na pewno nie zgaśnie w połowie lotu. Trzy stany,
                bo „nie wiem" jest tu osobną odpowiedzią — patrz dok/TELEFON.md §4.
                Stanu telemetrii nie dublujemy: niesie go pole łącza w pasie górnym. */}
            <span
              className={`dioda-mala ${budzik.trzyma ? "ok" : budzik.dostepny ? "" : "zla"}`}
              title={
                budzik.trzyma
                  ? "Ekran nie zgaśnie, dopóki ta karta jest na wierzchu"
                  : budzik.dostepny
                    ? "Blokada wygaszania chwilowo zwolniona — wróci przy powrocie do karty"
                    : "Ta przeglądarka nie blokuje wygaszania po http:// — ustaw dłuższy czas wygaszania w telefonie"
              }
            >
              ekran
            </span>
          </span>
        }
        klawisze={
          <div className="rzad-klawiszy">
            {zrodla.length > 1 && (
              <button
                type="button"
                className={`klawisz ${pokazMozaike ? "wlaczony" : ""}`}
                onClick={() => setMozaika((m) => !m)}
                title="Wszystkie źródła naraz — klik w kafelek wybiera jedno"
              >
                <IkonaMozaika />
                <span className="klawisz-podpis">MOZAIKA</span>
              </button>
            )}

            {zrodla.length > 1 && (
              <select
                className="klawisz-wybor"
                value={wybrane ?? ""}
                onChange={(e) => { setWybrane(e.target.value); setMozaika(false); }}
                title="Które źródło obrazu"
              >
                {zrodla.map((z) => (
                  <option key={z.id} value={z.id}>{z.nazwa}</option>
                ))}
              </select>
            )}

            {zrodlo?.maPomocniczy && (
              <button
                type="button"
                className={`klawisz ${pomocniczy ? "wlaczony" : ""}`}
                onClick={() => setPomocniczy((p) => !p)}
                title="Strumień pomocniczy (video2) jest lżejszy i zgodniejszy z dekoderami telefonów"
              >
                <IkonaStrumien />
                <span className="klawisz-podpis">{pomocniczy ? "POMOC." : "GŁÓWNY"}</span>
              </button>
            )}

            <button
              type="button"
              className={`klawisz ${panel === "mapa" ? "wlaczony" : ""}`}
              onClick={() => setPanel((p) => (p === "mapa" ? null : "mapa"))}
              title="Gdzie jest maszyna, punkt startu i trasa"
            >
              <IkonaMapa />
              <span className="klawisz-podpis">MAPA</span>
            </button>

            {/* Obraz na drugi monitor. Osobne okno, bo kafelka wewnątrz karty
                nie da się przeciągnąć poza jej krawędź. */}
            {/* ZAMKNIJ tylko na stacji: okno jest tam pełnoekranowe i bez ramki,
                a klawiatury nie ma — nie ma więc ani krzyżyka, ani Alt+F4.
                ⛔ Ten klawisz MUSI być osiągalny pokrętłem: jest wyjściem. */}
            {jestStacja && (
              <button
                type="button"
                className="klawisz zamknij-stacje"
                onClick={zamknijPodglad}
                title="Zamyka okno podglądu i wraca na pulpit GCS"
              >
                <IkonaZamknij />
                <span className="klawisz-podpis">ZAMKNIJ</span>
              </button>
            )}

            {/* Pokrętło: stanowisko przy stacji nie ma myszy. Klawisz jest widoczny
                zawsze, bo o tym, gdzie stoi maszyna, decyduje człowiek, a nie
                zgadywanie po adresie przeglądarki. */}
            <button
              type="button"
              className={`klawisz ${chcePokretla ? "wlaczony" : ""} ${chcePokretla && !pokretlo.polaczone ? "pilne" : ""}`}
              onClick={przelaczPokretlo}
              {...{ [BEZ_POKRETLA]: "tak" }}
              title={
                !chcePokretla
                  ? "Steruj stroną pokrętłem stacji (obrót przechodzi, klik naciska, przytrzymanie cofa)"
                  : pokretlo.polaczone
                    ? "Pokrętło stacji steruje tą stroną — kliknij, żeby oddać"
                    : pokretlo.blad || "Pokrętło niedostępne"
              }
            >
              <IkonaPokretlo />
              <span className="klawisz-podpis">{chcePokretla ? (pokretlo.polaczone ? "POKRĘTŁO" : "BRAK") : "POKRĘTŁO"}</span>
            </button>

            <button
              type="button"
              className="klawisz"
              onClick={() => oddokuj("obraz")}
              title="Obraz w osobnym oknie — do przeniesienia na drugi ekran"
            >
              <IkonaOddokuj />
              <span className="klawisz-podpis">ODDOKUJ</span>
            </button>

            <button
              type="button"
              className={`klawisz ${panel === "widzowie" ? "wlaczony" : ""}`}
              onClick={() => setPanel((p) => (p === "widzowie" ? null : "widzowie"))}
              title="Kto jeszcze ogląda"
            >
              <IkonaOko />
              <span className="klawisz-podpis">OGLĄDA</span>
            </button>

            {ja.rola === "admin" && (
              <button
                type="button"
                className={`klawisz ${panel === "admin" ? "wlaczony" : ""}`}
                onClick={() => setPanel((p) => (p === "admin" ? null : "admin"))}
                title="Kto ma wstęp: zaproszenia, widzowie, archiwum"
              >
                <IkonaKlucz />
                <span className="klawisz-podpis">ADMIN</span>
              </button>
            )}

            {/* Osobny klawisz od ADMIN, bo to osobne pytanie: tamten mówi, KTO ma
                wstęp, ten — czy SPRZĘT działa. Mieszanie ich kończy się panelem,
                w którym nie wiadomo, czego się szuka. */}
            {ja.rola === "admin" && (
              <button
                type="button"
                className={`klawisz ${panel === "stacja" ? "wlaczony" : ""}`}
                onClick={() => setPanel((p) => (p === "stacja" ? null : "stacja"))}
                title="Stan maszyny, na której to stoi: usługi, zasilanie, sieć, dziennik"
              >
                <IkonaStacja />
                <span className="klawisz-podpis">STACJA</span>
              </button>
            )}

            {/* Tryb kinowy — pełny ekran i obrót w poziom jednym dotknięciem.
                Klawisza nie ma tam, gdzie pełny ekran nie istnieje (iPhone);
                na iOS ten sam efekt daje obrócenie telefonu. */}
            {pelnyEkran.dostepny && (
              <button
                type="button"
                className={`klawisz ${pelnyEkran.wlaczony ? "wlaczony" : ""}`}
                onClick={pelnyEkran.przelacz}
                title="Pełny ekran w poziomie"
              >
                <IkonaPelnyEkran wlaczony={pelnyEkran.wlaczony} />
                <span className="klawisz-podpis">{pelnyEkran.wlaczony ? "WYJDŹ" : "EKRAN"}</span>
              </button>
            )}
          </div>
        }
      />

      {/* Panele dostają `panel` i `naPanel`, żeby dało się przeskoczyć między nimi
          bez zamykania. Dolny pasek jest w tym czasie zasłonięty, więc bez tego
          każde przejście wymagało dwóch ruchów — a przy obciętym nagłówku
          (patrz App.css, `.zaslona.panel`) nie było ich wcale. */}
      {panel === "widzowie" && (
        <Widzowie
          zrodla={zrodla}
          panel={panel}
          naPanel={setPanel}
          rola={ja.rola}
          naZamknij={() => setPanel(null)}
        />
      )}
      {panel === "admin" && (
        <Admin
          zrodla={zrodla}
          naZmianeZrodel={wczytajZrodla}
          panel={panel}
          naPanel={setPanel}
          naZamknij={() => setPanel(null)}
        />
      )}
      {panel === "stacja" && (
        <Stacja panel={panel} naPanel={setPanel} naZamknij={() => setPanel(null)} />
      )}
      {chcePokretla && pokretlo.wRegulacji && (
        <div className="pasek-pokretla">
          OBRÓT ZMIENIA WARTOŚĆ · KLIK ZATWIERDZA · PRZYTRZYMANIE COFA
        </div>
      )}

      {panel === "mapa" && (
        <div className="zaslona panel" onClick={() => setPanel(null)}>
          <div className="karta szeroka karta-mapy" onClick={(e) => e.stopPropagation()}>
            <NaglowekPanelu
              tytul="MAPA"
              panel={panel}
              naPanel={setPanel}
              naZamknij={() => setPanel(null)}
              rola={ja.rola}
            />
            <Mapa stan={stan} naOddokuj={() => oddokuj("mapa")} />
          </div>
        </div>
      )}
    </div>
  );
}
