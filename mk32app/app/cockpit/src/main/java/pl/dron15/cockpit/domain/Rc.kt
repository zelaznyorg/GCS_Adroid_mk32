package pl.dron15.cockpit.domain

/**
 * Model aparatury: co siedzi na którym kanale i czy to działa.
 *
 * Powód istnienia opisany w dok/RC_PRZYPISANIA.md. W skrócie: kontroler lotu wie, że kanał 6
 * wyzwala RTL, ale nie wie, że kanał 6 to przełącznik SB. To wie tylko człowiek — więc organ
 * jest **deklarowany w aplikacji**, a funkcja **czytana z maszyny** (`RCn_OPTION`).
 *
 * Aplikacja nigdy nie zapisuje tych parametrów (PLAN.md §9). Panel pokazuje stan i wykrywa
 * usterki; zmiany idą przez tools/fc_write_params.py z komputera.
 */
enum class PozycjaPrzelacznika(val etykieta: String) {
    DOL("DÓŁ"), SRODEK("ŚRODEK"), GORA("GÓRA"), BRAK("—")
}

/** Jeden kanał, gotowy do pokazania. */
data class KanalRc(
    val numer: Int,
    val mikrosekundy: Int,
    val organ: String,
    val kodFunkcji: Int?,
    val nazwaFunkcji: String,
    val pozycja: PozycjaPrzelacznika,
    val udzial: Float,              // 0..1 — położenie w zakresie RCn_MIN..MAX
    val proporcjonalny: Boolean,    // drążek albo pokrętło: pokazujemy procent, nie pozycję
    val zywy: Boolean,
    val obslugiwanySprzetowo: Boolean,
)

data class UsterkaRc(val waga: Waga, val tekst: String, val szczegol: String = "", val kanaly: List<Int> = emptyList())

data class OcenaRc(
    val kanaly: List<KanalRc>,
    val usterki: List<UsterkaRc>,
    val kanalTrybow: Int,
    val tryby: List<Pair<Int, String>>,     // numer slotu → nazwa trybu
    val slotyOsiagalne: List<Int>,
    val liczbaKanalow: Int,
    val zywa: Boolean,
)

object Rc {

    const val KANALOW = 16

    // Progi pozycji przełącznika AUX — RC_Channel::AuxSwitchPos @ Copter-4.6.3
    const val PROG_DOLNY = 1200
    const val PROG_GORNY = 1800

    /** Domyślny podział organów wg mapy kanałów MK32 — sekcja 2.2 CLAUDE.md. */
    fun domyslnyOrgan(kanal: Int): String = when (kanal) {
        1 -> "DRĄŻEK ROLL"
        2 -> "DRĄŻEK PITCH"
        3 -> "DRĄŻEK GAZ"
        4 -> "DRĄŻEK KIERUNEK"
        in 5..10 -> "PRZEŁĄCZNIK S" + ('A' + (kanal - 5))
        11 -> "POKRĘTŁO LD1"
        12 -> "POKRĘTŁO RD1"
        13 -> "POKRĘTŁO LD2"
        14 -> "POKRĘTŁO RD2"
        15 -> "PRZYCISK S1"
        16 -> "PRZYCISK S2"
        else -> "—"
    }

    fun proporcjonalny(kanal: Int): Boolean = kanal in 1..4 || kanal in 11..14

    fun pozycja(us: Int): PozycjaPrzelacznika = when {
        us <= 0 -> PozycjaPrzelacznika.BRAK
        us < PROG_DOLNY -> PozycjaPrzelacznika.DOL
        us <= PROG_GORNY -> PozycjaPrzelacznika.SRODEK
        else -> PozycjaPrzelacznika.GORA
    }

    // --- funkcje AUX -------------------------------------------------------------------
    // Statusy wg konwencji z sekcji 8 CLAUDE.md. FAKT = zweryfikowane w RC_Channel.h @ 4.6.3
    // przy okazji prac nad tą maszyną. DEKLARACJA = wzięte z instrukcji ZR30, str. 83.

    const val FUNKCJA_BRAK = 0
    const val FUNKCJA_RTL = 4
    const val FUNKCJA_FENCE = 11
    const val FUNKCJA_CHOWANIE_GLOWICY = 27
    const val FUNKCJA_ARM = 153
    const val FUNKCJA_BLOKADA_GLOWICY = 163
    const val FUNKCJA_NAGRYWANIE = 166
    const val FUNKCJA_ZOOM = 167
    const val FUNKCJA_OSTROSC_RECZNA = 168
    const val FUNKCJA_AUTOFOKUS = 169
    const val FUNKCJA_GLOWICA_PITCH = 213
    const val FUNKCJA_GLOWICA_YAW = 214

    private val NAZWY = mapOf(
        FUNKCJA_BRAK to "brak przypisania",
        FUNKCJA_RTL to "RTL — powrót do startu",
        FUNKCJA_FENCE to "geofence",
        FUNKCJA_CHOWANIE_GLOWICY to "chowanie głowicy",
        FUNKCJA_ARM to "uzbrojenie / rozbrojenie",
        FUNKCJA_BLOKADA_GLOWICY to "blokada głowicy (Mount Lock)",
        FUNKCJA_NAGRYWANIE to "nagrywanie wideo",
        FUNKCJA_ZOOM to "zoom kamery",
        FUNKCJA_OSTROSC_RECZNA to "ostrość ręczna",
        FUNKCJA_AUTOFOKUS to "autofokus",
        FUNKCJA_GLOWICA_PITCH to "głowica — pochylenie",
        FUNKCJA_GLOWICA_YAW to "głowica — obrót",
    )

    /**
     * Nazwa funkcji albo uczciwe „nierozpoznana". Zgadywanie nazw dla kodów, których nie
     * sprawdziliśmy w źródle, byłoby gorsze niż brak nazwy — patrz konwencja z CLAUDE.md §8.
     */
    fun nazwaFunkcji(kod: Int?): String = when (kod) {
        null -> "—"
        else -> NAZWY[kod] ?: "OPCJA $kod — nierozpoznana"
    }

    fun rozpoznana(kod: Int?): Boolean = kod != null && NAZWY.containsKey(kod)

    /** Funkcje, dla których kokpit ma przycisk — i którego nie pokazuje, gdy jest przełącznik. */
    val FUNKCJE_DUBLOWANE_PRZEZ_EKRAN = setOf(
        FUNKCJA_RTL, FUNKCJA_ZOOM, FUNKCJA_NAGRYWANIE, FUNKCJA_GLOWICA_PITCH, FUNKCJA_GLOWICA_YAW
    )

    // --- analiza -----------------------------------------------------------------------

    /**
     * Składa obraz aparatury z trzech źródeł: żywe RC_CHANNELS, parametry z FC i deklaracje
     * operatora. Wszystkie kontrole biorą się z rzeczy, które w tym projekcie zdarzyły się
     * naprawdę — spis w dok/RC_PRZYPISANIA.md §6.
     */
    fun ocen(
        stan: StanMaszyny,
        deklaracje: Map<Int, String> = emptyMap(),
        sprzetowe: Set<Int> = emptySet(),
        teraz: Long = System.currentTimeMillis(),
    ): OcenaRc {
        val p = stan.parametry
        val zywa = stan.telemetriaZywa(teraz) && stan.liczbaKanalowRc > 0

        val kanaly = (1..KANALOW).map { nr ->
            val us = stan.kanalyRc.getOrElse(nr - 1) { 0 }
            val kod = p["RC${nr}_OPTION"]?.toInt()
            val min = p["RC${nr}_MIN"]?.toInt() ?: 1000
            val maks = p["RC${nr}_MAX"]?.toInt() ?: 2000
            val rozpietosc = (maks - min).coerceAtLeast(1)
            KanalRc(
                numer = nr,
                mikrosekundy = us,
                organ = deklaracje[nr] ?: domyslnyOrgan(nr),
                kodFunkcji = kod,
                nazwaFunkcji = nazwaFunkcji(kod),
                pozycja = pozycja(us),
                udzial = ((us - min).toFloat() / rozpietosc).coerceIn(0f, 1f),
                proporcjonalny = proporcjonalny(nr),
                zywy = us > 0,
                obslugiwanySprzetowo = nr in sprzetowe ||
                        (kod != null && kod != FUNKCJA_BRAK && kod in FUNKCJE_DUBLOWANE_PRZEZ_EKRAN),
            )
        }

        val usterki = ArrayList<UsterkaRc>(4)

        // Duplikaty: maszyna odmawia uzbrojenia komunikatem "Duplicate Aux Switch Options"
        kanaly.filter { it.kodFunkcji != null && it.kodFunkcji != FUNKCJA_BRAK }
            .groupBy { it.kodFunkcji }
            .filterValues { it.size > 1 }
            .forEach { (kod, lista) ->
                usterki += UsterkaRc(
                    Waga.BLOKADA,
                    "TA SAMA FUNKCJA NA DWÓCH KANAŁACH",
                    "${nazwaFunkcji(kod)} — kanały ${lista.joinToString(", ") { "CH${it.numer}" }}; " +
                            "maszyna odmówi uzbrojenia (Duplicate Aux Switch Options)",
                    lista.map { it.numer },
                )
            }

        val maFunkcje = { kod: Int -> kanaly.any { it.kodFunkcji == kod } }
        if (p.keys.any { it.endsWith("_OPTION") }) {
            if (!maFunkcje(FUNKCJA_RTL)) usterki += UsterkaRc(
                Waga.OSTRZEZENIE, "RTL NIE JEST NA ŻADNYM PRZEŁĄCZNIKU",
                "powrót zostaje wyłącznie z ekranu i z failsafe"
            )
            if (!maFunkcje(FUNKCJA_ARM)) usterki += UsterkaRc(
                Waga.BLOKADA, "UZBROJENIE NIE JEST NA ŻADNYM PRZEŁĄCZNIKU",
                "kokpit świadomie nie uzbraja z ekranu — bez przełącznika nie ma jak wystartować"
            )
        }

        if (zywa) {
            val martwe = kanaly.take(stan.liczbaKanalowRc.coerceAtMost(KANALOW)).filter { !it.zywy }
            if (martwe.isNotEmpty()) usterki += UsterkaRc(
                Waga.OSTRZEZENIE, "KANAŁY BEZ SYGNAŁU",
                martwe.joinToString(", ") { "CH${it.numer}" },
                martwe.map { it.numer },
            )
            if (stan.liczbaKanalowRc in 1 until KANALOW) usterki += UsterkaRc(
                Waga.OSTRZEZENIE, "APARATURA ODDAJE ${stan.liczbaKanalowRc} KANAŁÓW Z 16",
                "przy 50 Hz pętli FC gubił kanały — poz. 2 i 23 CLAUDE.md"
            )
        } else {
            usterki += UsterkaRc(
                Waga.OSTRZEZENIE, "BRAK DANYCH Z APARATURY",
                "RC_CHANNELS nie przychodzi — na samym USB air unit jest bez zasilania i to normalne"
            )
        }

        // Gaz samocentrujący bez PILOT_THR_BHV — poz. 24b CLAUDE.md
        p["PILOT_THR_BHV"]?.let { bhv ->
            if (bhv.toInt() and 1 == 0) usterki += UsterkaRc(
                Waga.OSTRZEZENIE, "GAZ: PUNKT ODNIESIENIA NA SPODZIE DRĄŻKA",
                "drążek MK32 samocentruje; bez bitu 0 w PILOT_THR_BHV środek znaczy pół mocy"
            )
        }

        val kanalTrybow = p["FLTMODE_CH"]?.toInt() ?: 5
        val tryby = (1..6).mapNotNull { slot ->
            p["FLTMODE$slot"]?.let { slot to Tryby.nazwa(it.toInt()) }
        }

        return OcenaRc(
            kanaly = kanaly,
            usterki = usterki.sortedBy { it.waga.ordinal },
            kanalTrybow = kanalTrybow,
            tryby = tryby,
            // Przy zakresie 1045–1945 µs z MK32 osiągalne są tylko sloty 1, 4 i 6 — poz. 4 CLAUDE.md
            slotyOsiagalne = listOf(1, 4, 6),
            liczbaKanalow = stan.liczbaKanalowRc,
            zywa = zywa,
        )
    }

    /** Parametry, o które pyta panel. Imiennie, tak jak checklista — nie cała lista 1306. */
    val POTRZEBNE_PARAMETRY: List<String> =
        (1..KANALOW).map { "RC${it}_OPTION" } +
                listOf("FLTMODE_CH", "FLTMODE1", "FLTMODE2", "FLTMODE3", "FLTMODE4", "FLTMODE5",
                    "FLTMODE6", "PILOT_THR_BHV", "RC3_MIN", "RC3_MAX", "FS_THR_VALUE")
}
