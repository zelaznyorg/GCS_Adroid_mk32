package pl.dron15.cockpit.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Jeden zbiorczy stan maszyny. Wszystko, co widać na ekranie, pochodzi stąd.
 *
 * Pola `czas*` trzymają moment ostatniej aktualizacji (zegar systemowy w ms), bo interfejs
 * ma pokazywać wiek danych, a nie zamrożoną liczbę sprzed minuty — patrz dok/UI.md, zasada 6.
 */
data class StanMaszyny(
    // --- lot
    val tryb: String = "—",
    val uzbrojony: Boolean = false,
    val wysokoscM: Float = 0f,
    val wznoszenieMs: Float = 0f,
    val predkoscMs: Float = 0f,
    val przechylenieSt: Float = 0f,
    val pochylenieSt: Float = 0f,
    val kursSt: Float = 0f,

    // --- położenie
    val szerokosc: Double = 0.0,
    val dlugosc: Double = 0.0,
    val domSzerokosc: Double = 0.0,
    val domDlugosc: Double = 0.0,
    val domUstalony: Boolean = false,
    /** Czy dom pochodzi z `HOME_POSITION`, czy tylko z naszego zgadywania przy uzbrojeniu. */
    val domZMaszyny: Boolean = false,
    /** Ślad przebytej trasy w metrach względem domu (wschód, północ). */
    val slad: List<Pair<Float, Float>> = emptyList(),

    // --- GNSS
    val satelity: Int = 0,
    val hdop: Float = 0f,
    val rodzajFixa: Int = 0,
    val kursGnssDostepny: Boolean = false,
    val kursGnssSt: Float = 0f,

    // --- EKF
    val flagiEkf: Int = 0,
    val wariancjaKursu: Float = 0f,

    // --- zasilanie
    val napiecieV: Float = 0f,
    val pradA: Float = 0f,
    val zuzycieMah: Int = 0,

    // --- silniki: SERVO_OUTPUT_RAW, źródło zapasu ciągu (domain/Ciag.kt)
    val wyjsciaSilnikow: List<Int> = emptyList(),
    val czasWyjsc: Long = 0L,
    /** Gaz zbiorczy w procentach z VFR_HUD — główny wskaźnik poz. 55. */
    val gazProc: Int = 0,

    // --- wibracje
    val wibracjeX: Float = 0f,
    val wibracjeY: Float = 0f,
    val wibracjeZ: Float = 0f,
    val przyciecia: Int = 0,
    val czasWibracji: Long = 0L,

    // --- zdrowie czujników: maski z SYS_STATUS, które dotąd pomijaliśmy
    val czujnikiObecne: Int = 0,
    val czujnikiWlaczone: Int = 0,
    val czujnikiZdrowe: Int = 0,

    // --- cel automatu (NAV_CONTROLLER_OUTPUT)
    /** Metry do bieżącego punktu; `-1` gdy nieznane. */
    val doPunktuM: Float = -1f,
    val bladWysokosciM: Float = 0f,
    /** Odchyłka od zadanego toru [m]. */
    val bladToruM: Float = 0f,
    /** Namiar na cel [°]; `-1` gdy nieznany. */
    val namiarNaCelSt: Float = -1f,
    val czasCelu: Long = 0L,

    // --- geofence (FENCE_STATUS)
    val naruszenieOgrodzenia: Ogrodzenie.Naruszenie = Ogrodzenie.Naruszenie.BRAK,
    val liczbaNaruszenOgrodzenia: Int = 0,

    // --- wysokość bezwzględna i tor lotu
    val wysokoscMslM: Float = 0f,
    /** Kierunek toru nad ziemią; `-1` gdy maszyna stoi albo pozycja nieznana. */
    val kursToruSt: Float = -1f,

    // --- głowica (z MAVLinka; kokpit czyta ją też wprost z ZR30)
    val glowicaPitch: Float = 0f,
    val glowicaYaw: Float = 0f,
    val glowicaZoom: Float = 1f,
    val glowicaNagrywa: Boolean = false,
    val glowicaTrybRuchu: String = "—",

    // --- aparatura
    val kanalyRc: List<Int> = emptyList(),
    val liczbaKanalowRc: Int = 0,
    val rssiRc: Int = 255,
    val czasRc: Long = 0L,

    // --- misja
    val punktMisji: Int = 0,

    // --- łącza
    val czasHeartbeatu: Long = 0L,

    /**
     * Czas **dowolnej** ramki od maszyny. To on, a nie sam heartbeat, mówi, czy łącze żyje.
     *
     * Zmierzone 2026-08-26 na sprzęcie: przez jednostkę naziemną MK32 heartbeat dociera
     * rzadko (2 sztuki na 30 s w niezależnym nasłuchu), podczas gdy położenie, pozycja
     * i napięcie płyną bez przerwy. Ocena łącza oparta na samym heartbeacie zapalała więc
     * „UTRATA TELEMETRII" nad ekranem, na którym **wszystkie liczby się odświeżały**.
     * Fałszywy alarm jest tu kosztowny podwójnie: uczy pilota nie wierzyć banerowi.
     */
    val czasRamki: Long = 0L,
    val ramekNaSekunde: Float = 0f,
    val wideoDziala: Boolean = false,
    val glowicaOdpowiada: Boolean = false,

    // --- czas lotu
    val czasUzbrojenia: Long = 0L,
    val czasLotuMs: Long = 0L,

    // --- ostatnia komenda i odpowiedź maszyny
    val ostatniaKomenda: Komenda? = null,

    // --- komunikaty z FC (najnowsze na początku)
    val komunikaty: List<Komunikat> = emptyList(),

    // --- parametry pobrane z maszyny (do checklisty i panelu RC)
    val parametry: Map<String, Float> = emptyMap(),
) {
    val napiecieNaOgniwo: Float get() = if (napiecieV > 0f) napiecieV / OGNIW else 0f

    /** Czy `SERVO_OUTPUT_RAW` dochodzi. Przyrządy zapasu mają bez tego mówić „brak", nie „0". */
    fun wyjsciaZnane(teraz: Long): Boolean =
        wyjsciaSilnikow.size >= 4 && czasWyjsc > 0L && teraz - czasWyjsc < 3000

    fun wibracjeZnane(teraz: Long): Boolean = czasWibracji > 0L && teraz - czasWibracji < 5000

    /** Czy autopilot melduje, dokąd leci. Poza trybami automatycznymi milczy. */
    fun celZnany(teraz: Long): Boolean = czasCelu > 0L && teraz - czasCelu < 3000

    /** Największa z trzech osi — tyle wystarczy na słupek; próg ArduPilota to 30 m/s². */
    val wibracjeSzczyt: Float get() = maxOf(wibracjeX, wibracjeY, wibracjeZ)

    /** Wiek telemetrii w sekundach. */
    fun wiekTelemetriiS(teraz: Long): Float {
        val ostatnia = maxOf(czasHeartbeatu, czasRamki)
        return if (ostatnia == 0L) Float.MAX_VALUE else (teraz - ostatnia) / 1000f
    }

    fun telemetriaZywa(teraz: Long): Boolean = wiekTelemetriiS(teraz) < 3f

    /** Czy w tej sesji przyszła choć jedna ramka od maszyny. */
    val telemetriaByla: Boolean get() = czasHeartbeatu > 0L || czasRamki > 0L

    /**
     * Cisza na łączu opisana słowem — **nigdy nie wypisuje wartownika** z [wiekTelemetriiS].
     *
     * Dla operatora „nigdy" i „12 s" to dwie różne sytuacje: pierwsza znaczy, że łącze
     * nie stanęło ani razu (szukaj kabla, portu, zasilania air unitu), druga — że stało
     * i padło (szukaj zasięgu). Bez tego rozróżnienia na belce pojawiała się surowa
     * wartość `Float.MAX_VALUE`, czyli 34028234663852886000000000000000000000 s.
     */
    fun opisCiszy(teraz: Long): String =
        if (!telemetriaByla) "nigdy" else "%.0f s".format(wiekTelemetriiS(teraz))

    /** Czas lotu w sekundach: liczony od uzbrojenia, zatrzymany po rozbrojeniu. */
    fun czasLotuS(teraz: Long): Long =
        (if (uzbrojony && czasUzbrojenia > 0L) czasLotuMs + (teraz - czasUzbrojenia) else czasLotuMs) / 1000

    /** Odległość do punktu startu w metrach. Ujemna, gdy dom nie jest ustalony. */
    val dystansDoDomuM: Float
        get() = if (!domUstalony || !pozycjaZnana) -1f else {
            val (e, n) = wzgledemDomu()
            sqrt(e * e + n * n)
        }

    /** Namiar na dom w stopniach (0 = północ). Ujemny, gdy nieznany. */
    val namiarNaDomSt: Float
        get() = if (!domUstalony || !pozycjaZnana) -1f else {
            val (e, n) = wzgledemDomu()
            ((Math.toDegrees(atan2(e.toDouble(), n.toDouble())).toFloat() % 360f) + 360f) % 360f
        }

    val pozycjaZnana: Boolean get() = abs(szerokosc) > 1e-7 || abs(dlugosc) > 1e-7

    /** Pozycja względem domu w metrach: (wschód, północ). Płaska aproksymacja — do 10 km wystarcza. */
    fun wzgledemDomu(
        lat: Double = szerokosc,
        lon: Double = dlugosc,
    ): Pair<Float, Float> {
        val dLat = lat - domSzerokosc
        val dLon = lon - domDlugosc
        val n = (dLat * METRY_NA_STOPIEN).toFloat()
        val e = (dLon * METRY_NA_STOPIEN * cos(Math.toRadians(domSzerokosc))).toFloat()
        return e to n
    }

    /**
     * Czy RTL ma szansę zadziałać. Na tej maszynie kurs pochodzi wyłącznie z bazy GNSS
     * (EK3_SRC1_YAW=2, brak kompasu), więc bez kursu nie ma pozycji, a bez pozycji nie ma RTL.
     */
    val rtlDostepny: Boolean
        get() = kursGnssDostepny &&
                (flagiEkf and FLAGA_POZ_POZIOM_WZGL) != 0 &&
                (flagiEkf and FLAGA_STALA_POZYCJA) == 0

    /** Powód, dla którego komenda lotu jest niedostępna — do podpisania przycisku. */
    fun powodBrakuKomend(teraz: Long): String? = when {
        !telemetriaZywa(teraz) -> "brak telemetrii"
        !kursGnssDostepny -> "brak kursu GNSS"
        else -> null
    }

    companion object {
        const val FLAGA_POZ_POZIOM_WZGL = 8       // EKF_POS_HORIZ_REL
        const val FLAGA_POZ_POZIOM_BEZWZGL = 16   // EKF_POS_HORIZ_ABS
        const val FLAGA_STALA_POZYCJA = 128       // EKF_CONST_POS_MODE
        const val FLAGA_GPS_ZAKLOCENIA = 32768    // EKF_GPS_GLITCHING

        /** Liczba ogniw pakietu. ⚠ 6S dziś; przy przejściu na 8S (poz. 56) do zmiany. */
        const val OGNIW = 6f

        const val METRY_NA_STOPIEN = 111_320.0
        const val DLUGOSC_SLADU = 900             // ok. 15 minut przy jednym punkcie na sekundę
    }
}

/**
 * Komenda wysłana do maszyny i to, co maszyna na nią odpowiedziała.
 *
 * Bez tego przycisk RTL jest obietnicą bez pokrycia: ramka poszła w UDP i tyle wiadomo.
 * `COMMAND_ACK` zamienia to w informację — patrz dok/UI.md, „potwierdzenie komendy".
 */
data class Komenda(
    val kod: Int,
    val nazwa: String,
    val czasWyslania: Long,
    val wynik: Int? = null,
    val czasOdpowiedzi: Long = 0L,
) {
    val przyjeta: Boolean get() = wynik == WYNIK_PRZYJETA
    val czeka: Boolean get() = wynik == null

    fun stan(teraz: Long): String = when {
        wynik == WYNIK_PRZYJETA -> "przyjęta"
        wynik != null -> "odrzucona: " + opisWyniku(wynik)
        teraz - czasWyslania > 3000 -> "bez potwierdzenia"
        else -> "wysłana…"
    }

    companion object {
        const val WYNIK_PRZYJETA = 0              // MAV_RESULT_ACCEPTED

        fun opisWyniku(w: Int): String = when (w) {
            0 -> "przyjęta"
            1 -> "tymczasowo odrzucona"
            2 -> "odrzucona"
            3 -> "nieobsługiwana"
            4 -> "błąd wykonania"
            5 -> "w toku"
            6 -> "odmowa — brak uprawnień"
            else -> "wynik $w"
        }
    }
}

data class Komunikat(val waga: Int, val tekst: String, val czas: Long, val powtorzenia: Int = 0) {
    val blokujePrearm: Boolean get() = tekst.startsWith("PreArm", ignoreCase = true)

    /** Tekst z licznikiem powtorzen, np. "PreArm: ... (×12)". */
    val zLicznikiem: String get() = if (powtorzenia > 0) "$tekst  (×${powtorzenia + 1})" else tekst
}
