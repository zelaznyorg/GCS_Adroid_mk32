package pl.dron15.cockpit.domain

import kotlin.math.abs

/**
 * Zapas ciągu i rozrzut silników, liczone z `SERVO_OUTPUT_RAW` na żywo.
 *
 * ### Po co to istnieje
 *
 * 2026-08-16 maszyna spadła z 58 na 17 m przy gazie zadanym 175 % (`CLAUDE.md` poz. 45).
 * Przyczyną było **nasycenie miksera**: wyjście `C1` siedziało na `MOT_SPIN_MAX` przez 89 %
 * próbek, więc ArduPilot obniżał gaz zbiorczy, żeby zachować autorytet w roll/pitch/yaw.
 * Pilot nie dostał żadnego sygnału — wykryto to z logu, tygodnie później.
 *
 * Wielkość, która to zapowiadała, rosła przez trzy loty na oczach telemetrii:
 *
 * | Okno | średnia 4 wyjść | najwyższe | rozrzut |
 * |---|---|---|---|
 * | zawis 10 m | 1736 | 1774 | 101 µs |
 * | zawis 53 m | 1781 | 1876 | 168 µs |
 * | zawis 58 m tuż przed | 1787 | **1884** | **173 µs** |
 * | przelot 6 m/s | 1759 | 1831 | **309 µs** |
 *
 * Ten moduł liczy dokładnie to samo, co `tools\fc_balans.py` liczy z pliku `.bin` po locie.
 *
 * ### Przypisanie wyjść
 *
 * Mapowanie potwierdzone stabilnym lotem 2 z 2026-08-16 (`CLAUDE.md` sekcja 1):
 * `SERVO1` = Motor4 **tył prawy**, `SERVO2` = Motor1 **przód prawy**,
 * `SERVO3` = Motor2 **tył lewy**, `SERVO4` = Motor3 **przód lewy**.
 * Kierunki obrotu Quad X: CCW = przód prawy + tył lewy, CW = przód lewy + tył prawy.
 *
 * ⛔ **Po każdej zmianie `SERVOn_FUNCTION` te wzory przestają znaczyć to, co tu napisano.**
 * Checkliście przedlotowej zdarzyło się już rozjechać z maszyną w tej właśnie sprawie
 * (`dok/AUDYT_M3.md`, B2), więc rozkład na składowe pokazujemy **tylko wtedy**, gdy
 * odczytane z maszyny `SERVO1..4_FUNCTION` zgadzają się z powyższym.
 */
object Ciag {

    /** Domyślny sufit wyjścia, gdy `MOT_SPIN_MAX` nie został jeszcze pobrany z maszyny. */
    const val SPIN_MAX_DOMYSLNY = 0.95f

    /** Zapas [µs], poniżej którego zaczynamy uprzedzać. */
    const val PROG_UWAGI_US = 100

    /** Zapas [µs], przy którym maszyna zaczyna tracić autorytet sterowania. */
    const val PROG_OSTRZEZENIA_US = 60

    /** Zapas [µs], przy którym mikser nasyca się przy pierwszym mocniejszym ruchu. */
    const val PROG_BLOKADY_US = 40

    /** Rozrzut [µs], powyżej którego poz. 45 zabrania latania. */
    const val PROG_ROZRZUTU_US = 60

    /** Mapowanie `SERVOn_FUNCTION` potwierdzone lotem 2 z 2026-08-16. */
    val MAPOWANIE_LOTNE = intArrayOf(36, 33, 34, 35)

    /**
     * Parametry, bez których te przyrządy liczą na domyślnych.
     * `MOT_SPIN_MAX` wyznacza sufit wyjścia, funkcje wyjść — czy wolno rozkładać rozrzut
     * na składowe, `WPNAV_SPEED` i `LAND_SPEED_HIGH` — czas powrotu dla JOKER i BINGO.
     */
    val POTRZEBNE_PARAMETRY = listOf(
        "MOT_SPIN_MAX", "MOT_THST_HOVER", "WPNAV_SPEED", "LAND_SPEED_HIGH",
        "SERVO1_FUNCTION", "SERVO2_FUNCTION", "SERVO3_FUNCTION", "SERVO4_FUNCTION",
    )

    enum class Ocena { DOBRZE, UWAGA, OSTRZEZENIE, BLOKADA }

    /**
     * Wynik pomiaru. `znany = false` oznacza, że `SERVO_OUTPUT_RAW` nie dochodzi —
     * wtedy ekran ma powiedzieć „brak danych", a nie narysować zero.
     */
    data class Zapas(
        val znany: Boolean = false,
        val zapasUs: Int = 0,
        val rozrzutUs: Int = 0,
        val najwyzszeUs: Int = 0,
        val sredniaUs: Int = 0,
        val sufitUs: Int = 0,
        /** Rozkład rozrzutu; `null`, gdy mapowania wyjść nie da się potwierdzić. */
        val skladowe: Skladowe? = null,
    ) {
        val ocena: Ocena
            get() = when {
                !znany -> Ocena.DOBRZE
                zapasUs <= PROG_BLOKADY_US -> Ocena.BLOKADA
                zapasUs <= PROG_OSTRZEZENIA_US -> Ocena.OSTRZEZENIE
                zapasUs <= PROG_UWAGI_US -> Ocena.UWAGA
                else -> Ocena.DOBRZE
            }

        val ocenaRozrzutu: Ocena
            get() = when {
                !znany -> Ocena.DOBRZE
                rozrzutUs > PROG_ROZRZUTU_US * 3 -> Ocena.OSTRZEZENIE
                rozrzutUs > PROG_ROZRZUTU_US -> Ocena.UWAGA
                else -> Ocena.DOBRZE
            }

        /** Udział wykorzystanego zakresu — do słupka. 1,0 = wyjście na suficie. */
        val wypelnienie: Float
            get() = if (!znany || sufitUs <= 1000) 0f
            else ((najwyzszeUs - 1000).toFloat() / (sufitUs - 1000)).coerceIn(0f, 1f)
    }

    /**
     * Rozkład różnic na trzy niezależne przyczyny — ten sam podział, co w `fc_balans.py`.
     *
     * Wartości w µs. Dodatnie `tylPrzod` = środek ciężkości za środkiem geometrycznym.
     */
    data class Skladowe(val tylPrzod: Int, val prawoLewo: Int, val cwCcw: Int) {
        /** Która składowa jest największa co do wartości bezwzględnej. */
        val dominujaca: String
            get() = when (maxOf(abs(tylPrzod), abs(prawoLewo), abs(cwCcw))) {
                abs(tylPrzod) -> if (tylPrzod > 0) "ciężki tył" else "ciężki przód"
                abs(prawoLewo) -> if (prawoLewo > 0) "ciężka prawa" else "ciężka lewa"
                else -> "moment obrotu"
            }
    }

    /**
     * @param wyjscia cztery wartości `SERVO1..4_RAW` w µs, w kolejności wyjść na płycie
     * @param spinMax `MOT_SPIN_MAX` pobrany z maszyny; przy braku [SPIN_MAX_DOMYSLNY]
     * @param mapowanieZgodne czy `SERVO1..4_FUNCTION` zgadzają się z [MAPOWANIE_LOTNE]
     */
    fun policz(
        wyjscia: List<Int>,
        spinMax: Float = SPIN_MAX_DOMYSLNY,
        mapowanieZgodne: Boolean = false,
    ): Zapas {
        // Silnik zatrzymany daje 0 albo 1000 — takich próbek nie ma sensu liczyć jako zapas.
        if (wyjscia.size < 4 || wyjscia.any { it < 900 }) return Zapas(znany = false)

        val m = wyjscia.take(4)
        val sufit = (1000f + 1000f * spinMax).toInt()
        val najwyzsze = m.max()
        val najnizsze = m.min()

        return Zapas(
            znany = true,
            zapasUs = sufit - najwyzsze,
            rozrzutUs = najwyzsze - najnizsze,
            najwyzszeUs = najwyzsze,
            sredniaUs = m.sum() / 4,
            sufitUs = sufit,
            skladowe = if (mapowanieZgodne) skladowe(m) else null,
        )
    }

    /**
     * S1 tył prawy, S2 przód prawy, S3 tył lewy, S4 przód lewy.
     * CW = przód lewy + tył prawy = S4 + S1; CCW = przód prawy + tył lewy = S2 + S3.
     */
    private fun skladowe(m: List<Int>): Skladowe = Skladowe(
        tylPrzod = (m[0] + m[2]) / 2 - (m[1] + m[3]) / 2,
        prawoLewo = (m[0] + m[1]) / 2 - (m[2] + m[3]) / 2,
        cwCcw = (m[3] + m[0]) / 2 - (m[1] + m[2]) / 2,
    )

    /** Czy odczytane z maszyny funkcje wyjść zgadzają się z mapowaniem potwierdzonym lotem. */
    fun mapowanieZgodne(parametry: Map<String, Float>): Boolean {
        val f = (1..4).map { parametry["SERVO${it}_FUNCTION"] ?: return false }
        return f.mapIndexed { i, v -> v.toInt() == MAPOWANIE_LOTNE[i] }.all { it }
    }
}
