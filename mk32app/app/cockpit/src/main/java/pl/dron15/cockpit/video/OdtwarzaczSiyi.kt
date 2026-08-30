package pl.dron15.cockpit.video

import android.content.Context
import pl.dron15.cockpit.diag.Dziennik
import pl.dron15.cockpit.net.SiecPokladowa
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Obraz z ZR30 **tą samą drogą, którą bierze go fabryczna aplikacja SIYI** — własnym
 * protokołem kamery po TCP 37256, z pominięciem RTSP.
 *
 * ### Dlaczego to jest tor podstawowy
 *
 * RTSP działa, ale ma dwie wady zmierzone 2026-08-28 na żywej maszynie:
 *
 * 1. **Sesja RTSP wygasa co 60 s.** Kamera zrywa połączenie, kokpit łączy się od nowa
 *    i przez ok. 1,5 s nie ma obrazu — na ekranie mruga `BRAK OBRAZU Z KAMERY`.
 *    W logu widać to jak zegar: 11:54:52, 11:56:03, 11:57:14, 11:58:26, 11:59:37.
 * 2. **Trzeba składać klatki z pakietów RTP** (RFC 6184). Trzy błędy w tym składaniu
 *    kosztowały nas cały wieczór smug i rozmycia przy zoomie.
 *
 * Tutaj nie ma ani jednego, ani drugiego. **Jedna ramka TCP to jedna cała klatka**,
 * gotowa dla dekodera, a sesję trzyma pakiet podtrzymania co sekundę.
 *
 * ### Protokół — zmierzony, nie zgadnięty
 *
 * ```
 * 0..3    55 66 aa bb   sygnatura
 * 4       typ           0x00 = obraz, 0x01 = sterowanie
 * 5..8    uint32 LE     długość ładunku
 * 9..10   uint16 LE     licznik ramek
 * 11      flaga         0x90 w obrazie, 0x80 w podtrzymaniu
 * 12..15  uint32 LE     CRC bajtów 0..11 — patrz [SumaSiyi]
 * 16..19                w obrazie: drugi licznik; w podtrzymaniu: CRC bajtów 0..15
 * 20..                  H.264 Annex-B, CAŁA jednostka dostępu
 * ```
 *
 * ### Bez podtrzymania kamera rozłącza po 5 sekundach
 *
 * Zmierzone dokładnie: 4,95 s ciszy i koniec połączenia. Nie wystarczy wysłać
 * czegokolwiek — bajt `0x00` co sekundę nie pomaga, a pakiet z podmienionym licznikiem
 * i starymi sumami jest odrzucany tak samo jak brak pakietu. **Kamera sprawdza obie
 * sumy.** Poprawny pakiet buduje [SumaSiyi.podtrzymanie]; przy nim strumień szedł 40 s
 * bez przerwy, 26,4 kl./s.
 *
 * ### Kamera obsługuje JEDNEGO odbiorcę
 *
 * Gdy ten tor jest czynny, fabryczna aplikacja SIYI FPV nie dostanie obrazu — i odwrotnie.
 * Przy RTSP jest tak samo. Do porównań obu aplikacji obok siebie trzeba zamknąć kokpit.
 *
 * ### Wymaga H.264
 *
 * Dekoder to `video/avc`. Kamera musi być ustawiona na H.264 — sprawdzenie:
 * `python narzedzia/siyi_gimbal.py codec --strumien glowny`.
 */
class OdtwarzaczSiyi(
    private val host: String = DOMYSLNY_HOST,
    private val rysownik: RysownikH264 = RysownikH264("tor SIYI"),
) : TorWideo {

    override var przyStanie: ((Boolean) -> Unit)?
        get() = rysownik.przyStanie
        set(v) {
            rysownik.przyStanie = v
        }

    @Volatile
    private var zamkniety = false

    @Volatile
    private var pokolenie = 0

    private var watek: Thread? = null

    /**
     * Gniazdo bieżącej próby — trzymane po to, żeby [wstrzymaj] mogło je **zamknąć
     * natychmiast**. Samo `interrupt()` nie przerywa blokującego odczytu z gniazda;
     * wątek dogasałby jeszcze do czasu przeterminowania odczytu, czyli kilka sekund,
     * i przez ten czas trzymał kamerę zajętą.
     */
    @Volatile
    private var gniazdoBiezace: java.net.Socket? = null

    init {
        // Bez powierzchni nie ma gdzie rysować — wtedy nie trzymamy kamery zajętej.
        // ⚠ [TorZZapasem] nadpisuje to przypisanie, bo przy dwóch źródłach na jednym
        // rysowniku o powierzchni musi decydować jedno miejsce.
        rysownik.przyPowierzchni = { jest -> naPowierzchnie(jest) }
    }

    /** Wołane, gdy powierzchnia pojawia się albo znika. Publiczne dla [TorZZapasem]. */
    fun naPowierzchnie(jest: Boolean) {
        if (jest) uruchom() else pokolenie++
    }

    /**
     * Odstawia to źródło, nie zamykając go na dobre — inaczej niż [zwolnij].
     * Używa tego [TorZZapasem] przy przejściu na tor zapasowy.
     */
    fun wstrzymaj() {
        pokolenie++
        try {
            gniazdoBiezace?.close()          // odblokowuje odczyt od razu
        } catch (_: Exception) {
        }
        watek?.interrupt()
        try {
            watek?.join(CZAS_DOGASANIA_MS)   // czekamy, aż stary wątek naprawdę zejdzie
        } catch (_: InterruptedException) {
        }
        rysownik.zatrzymaj()
    }

    override fun widok(kontekst: Context) = rysownik.widok(kontekst)

    override fun zapewnijOdtwarzanie() {
        if (watek?.isAlive != true) uruchom()
    }

    private fun uruchom() {
        if (zamkniety || !rysownik.maPowierzchnie) return
        if (watek?.isAlive == true) return
        val moje = ++pokolenie
        watek = Thread({ petla(moje) }, "wideo-siyi").apply { isDaemon = true; start() }
    }

    private fun petla(moje: Int) {
        var nieudane = 0
        while (!zamkniety && moje == pokolenie) {
            var gniazdo: Socket? = null
            try {
                gniazdo = Socket().apply {
                    tcpNoDelay = true                 // liczy się opóźnienie, nie przepustowość
                    soTimeout = CZAS_CZEKANIA_MS
                    SiecPokladowa.zwiaz(this)         // inaczej Wi-Fi przejmuje ruch
                    connect(InetSocketAddress(host, PORT), CZAS_CZEKANIA_MS)
                }
                gniazdoBiezace = gniazdo
                nieudane = 0
                przywitajSie(gniazdo.getOutputStream())
                val stroz = uruchomPodtrzymanie(gniazdo.getOutputStream(), moje)
                try {
                    odbieraj(DataInputStream(gniazdo.getInputStream().buffered(BUFOR_WEJSCIA)), moje)
                } finally {
                    stroz.interrupt()
                }
            } catch (e: Exception) {
                if (!zamkniety && moje == pokolenie) {
                    val zwloka = ODCZEKANIA_MS[nieudane.coerceAtMost(ODCZEKANIA_MS.lastIndex)]
                    nieudane++
                    Dziennik.ostrzezenie(
                        "wideo",
                        "tor SIYI: brak obrazu z $host:$PORT — ponowienie za ${zwloka / 1000} s " +
                            "(${e.javaClass.simpleName}: ${e.message})",
                    )
                    przyStanie?.invoke(false)
                    try {
                        Thread.sleep(zwloka)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            } finally {
                // ⛔ Dekoder wolno zatrzymać TYLKO wtedy, gdy to nadal nasze pokolenie.
                //
                // Bez tego warunku przełączenie toru zamrażało obraz: nowy tor ruszał,
                // a stary wątek — jeszcze żywy, bo wisiał na odczycie z gniazda —
                // po kilku sekundach wchodził tutaj i **zatrzymywał dekoder należący już
                // do nowego toru**. Objaw: obraz staje kilka sekund po przełączeniu.
                if (moje == pokolenie) rysownik.zatrzymaj()
                gniazdoBiezace = null
                try {
                    gniazdo?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Wysyła powitanie, po którym kamera **zaczyna nadawać obraz** — patrz
     * [SumaSiyi.POWITANIE].
     *
     * Bez tego świeżo zasilona ZR30 przyjmuje połączenie, odsyła jedną ramkę sterującą
     * i milczy. Podtrzymanie sesji tego nie naprawia: sesja żyje, obrazu nie ma.
     * Zmierzone dwukrotnie, po każdym cyklu zasilania kamery.
     *
     * Odstępy między ramkami są takie jak u producenta (tam 10–400 ms). Bez odstępu
     * kamera dostaje wszystko w jednym segmencie TCP — nie sprawdzone, czy jej to
     * przeszkadza, więc nie ryzykujemy.
     */
    private fun przywitajSie(wyjscie: OutputStream) {
        SumaSiyi.POWITANIE.forEach { ramka ->
            wyjscie.write(ramka)
            wyjscie.flush()
            Thread.sleep(ODSTEP_POWITANIA_MS)
        }
    }

    /**
     * Osobny wątek, bo nadawanie nie może czekać na odbiór. Gdyby podtrzymanie szło
     * z pętli czytającej, wystarczyłaby jedna dłuższa przerwa w obrazie, żeby spóźnić
     * pakiet i stracić sesję — czyli **dokładnie wtedy, gdy najbardziej jej potrzeba**.
     */
    private fun uruchomPodtrzymanie(wyjscie: OutputStream, moje: Int): Thread =
        Thread({
            var licznik = SumaSiyi.PIERWSZE_PODTRZYMANIE
            while (!zamkniety && moje == pokolenie) {
                try {
                    wyjscie.write(SumaSiyi.podtrzymanie(licznik))
                    wyjscie.flush()
                } catch (_: Exception) {
                    return@Thread                     // gniazdo padło; pętla główna to zauważy
                }
                licznik = (licznik + 1) and 0xFFFF
                try {
                    Thread.sleep(ODSTEP_PODTRZYMANIA_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "siyi-podtrzymanie").apply { isDaemon = true; start() }

    private fun odbieraj(wejscie: DataInputStream, moje: Int) {
        val naglowek = ByteArray(DLUGOSC_NAGLOWKA)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var pominietych = 0L
        var poczatekOkna = System.nanoTime()
        var wOknie = 0
        val odstepy = ArrayList<Long>(400)
        var poprzednia = 0L

        while (!zamkniety && moje == pokolenie) {
            pominietych += zsynchronizuj(wejscie, naglowek)
            val dlugosc = (naglowek[5].toInt() and 0xFF) or
                ((naglowek[6].toInt() and 0xFF) shl 8) or
                ((naglowek[7].toInt() and 0xFF) shl 16) or
                ((naglowek[8].toInt() and 0xFF) shl 24)
            if (dlugosc < 0 || dlugosc > MAKS_RAMKA) {
                throw IllegalStateException("niedorzeczna długość ramki: $dlugosc")
            }
            if (dlugosc == 0) continue                // ramka sterująca od kamery — pomijamy
            val dane = ByteArray(dlugosc)
            wejscie.readFully(dane)
            // ⚠ Obraz poznajemy po FLADZE (bajt 11 = 0x90), nie po bajcie typu.
            // Pierwsza wersja filtrowała po typie `0x00` i to był błąd: w jednej sesji
            // kamera nadaje obraz z typem `0x00`, w innej z `0x02` (oba zrzuty z 2026-08-28),
            // a flaga `0x90` jest w obu ta sama. Ramki sterujące mają 0x80, 0x83, 0x94, 0xb4.
            if (naglowek[11] != FLAGA_OBRAZU) continue

            val teraz = System.nanoTime()
            if (poprzednia != 0L) odstepy += (teraz - poprzednia) / 1_000_000
            poprzednia = teraz
            wOknie++

            if (!rysownik.gotowy) {
                // Dekoder ruszy dopiero, gdy znamy SPS i PPS. Kamera wysyła je przed
                // każdą klatką kluczową, więc czekamy najwyżej sekundę.
                if (sps == null) sps = wytnijNal(dane, TYP_SPS)
                if (pps == null) pps = wytnijNal(dane, TYP_PPS)
                val s = sps
                val p = pps
                if (s == null || p == null) continue
                if (!rysownik.uruchom(s, p)) {
                    throw IllegalStateException("dekoder video/avc nie wystartował")
                }
            }
            rysownik.podaj(dane)

            if (teraz - poczatekOkna >= OKNO_POMIARU_NS) {
                zamelduj(wOknie, odstepy, pominietych, teraz - poczatekOkna)
                poczatekOkna = teraz
                wOknie = 0
                odstepy.clear()
                pominietych = 0
            }
        }
    }

    /**
     * Ustawia strumień na początku poprawnego nagłówka i zwraca, ile bajtów wypadło.
     *
     * Pierwsza wersja przy zgubieniu sygnatury **zrywała połączenie**. To była zła
     * zamiana: jeden przekłamany bajt kosztował pełne ponowne łączenie i sekundę bez
     * obrazu. Tutaj przesuwamy okno o bajt i szukamy dalej — a że nagłówek niesie własną
     * sumę kontrolną ([SumaSiyi]), przypadkowe trafienie na `55 66 aa bb` w środku obrazu
     * zostaje odrzucone, zamiast rozjechać strumień.
     */
    private fun zsynchronizuj(wejscie: DataInputStream, naglowek: ByteArray): Int {
        wejscie.readFully(naglowek)
        var pominiete = 0
        while (true) {
            if (naglowek[0] == 0x55.toByte() && naglowek[1] == 0x66.toByte() &&
                naglowek[2] == 0xAA.toByte() && naglowek[3] == 0xBB.toByte() &&
                SumaSiyi.naglowekPoprawny(naglowek)
            ) {
                if (pominiete > 0) {
                    Dziennik.ostrzezenie("wideo", "tor SIYI: odzyskana synchronizacja po $pominiete B")
                }
                return pominiete
            }
            if (pominiete >= MAKS_SZUKANIA) {
                throw IllegalStateException("nie znaleziono nagłówka w $pominiete B")
            }
            System.arraycopy(naglowek, 1, naglowek, 0, DLUGOSC_NAGLOWKA - 1)
            naglowek[DLUGOSC_NAGLOWKA - 1] = wejscie.readByte()
            pominiete++
        }
    }

    private fun zamelduj(klatek: Int, odstepy: List<Long>, pominietych: Long, oknoNs: Long) {
        if (odstepy.isEmpty()) return
        val posortowane = odstepy.sorted()
        val mediana = posortowane[posortowane.size / 2]
        val duze = odstepy.count { it > PROG_PRZERWY_MS }
        val maks = odstepy.max()
        val kls = klatek * 1e9 / oknoNs
        Dziennik.info(
            "wideo",
            "tor SIYI: %.1f kl/s, mediana %d ms, >%d ms: %d/%d, maks %d ms%s".format(
                kls, mediana, PROG_PRZERWY_MS, duze, odstepy.size, maks,
                if (pominietych > 0) ", zgubione bajty: $pominietych" else "",
            ),
        )
    }

    /**
     * Wycina jednostkę NAL wskazanego typu **razem ze znacznikiem startowym** —
     * `MediaCodec` oczekuje `csd` w postaci Annex-B.
     */
    private fun wytnijNal(dane: ByteArray, typ: Int): ByteArray? {
        var i = 0
        var poczatek = -1
        while (i + 4 < dane.size) {
            val cztery = dane[i] == 0.toByte() && dane[i + 1] == 0.toByte() &&
                dane[i + 2] == 0.toByte() && dane[i + 3] == 1.toByte()
            val trzy = !cztery && dane[i] == 0.toByte() &&
                dane[i + 1] == 0.toByte() && dane[i + 2] == 1.toByte()
            if (!cztery && !trzy) {
                i++
                continue
            }
            val dlZnacznika = if (cztery) 4 else 3
            if (poczatek >= 0) return dane.copyOfRange(poczatek, i)
            if ((dane[i + dlZnacznika].toInt() and 0x1F) == typ) poczatek = i
            i += dlZnacznika
        }
        return if (poczatek >= 0) dane.copyOfRange(poczatek, dane.size) else null
    }

    override fun zwolnij() {
        zamkniety = true
        pokolenie++
        watek?.interrupt()
        rysownik.zwolnij()
    }

    companion object {
        const val PORT = 37256
        const val DOMYSLNY_HOST = "192.168.144.25"

        private const val DLUGOSC_NAGLOWKA = 20
        private const val FLAGA_OBRAZU: Byte = 0x90.toByte()
        private const val TYP_SPS = 7
        private const val TYP_PPS = 8

        /** Fabryczna aplikacja wysyła co 1,000 s. Kamera rozłącza po ok. 5 s ciszy — zapas 5×. */
        private const val ODSTEP_PODTRZYMANIA_MS = 1000L

        /** Odstęp między ramkami powitania — jak u producenta, żeby nie skleiły się w jeden segment. */
        private const val ODSTEP_POWITANIA_MS = 60L

        /** Największa zmierzona klatka kluczowa to ok. 150 kB. Zapas trzykrotny. */
        private const val MAKS_RAMKA = 512 * 1024
        private const val MAKS_SZUKANIA = 512 * 1024

        private const val BUFOR_WEJSCIA = 128 * 1024
        private const val CZAS_CZEKANIA_MS = 4000

        /** Ile czekamy, aż odstawiony wątek zejdzie, zanim ruszy następny tor. */
        private const val CZAS_DOGASANIA_MS = 1500L

        private const val OKNO_POMIARU_NS = 10_000_000_000L
        private const val PROG_PRZERWY_MS = 60L

        private val ODCZEKANIA_MS = longArrayOf(1000, 2000, 4000, 8000, 15000)
    }
}
