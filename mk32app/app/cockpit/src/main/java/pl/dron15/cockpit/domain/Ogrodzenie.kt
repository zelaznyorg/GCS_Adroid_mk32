package pl.dron15.cockpit.domain

/**
 * Zapas do granicy geofence — poziomo i w pionie.
 *
 * ### Po co
 *
 * Kokpit rysował okrąg geofence na mapie misji z parametru `FENCE_RADIUS`, ale
 * **nie wiedział, czy nastąpiło naruszenie** ani jak blisko granicy jest maszyna.
 * Ogrodzenie siedzi na CH7, `FENCE_ALT_MAX = 120` (`CLAUDE.md` poz. 4 i sekcja 10).
 *
 * Naruszenie przychodzi w `FENCE_STATUS`; **zapas** liczymy sami z pozycji i parametrów,
 * bo tej wielkości MAVLink nie podaje, a to ona jest użyteczna **zanim** coś się stanie.
 *
 * ### ⚠ Zapas poziomy jest wart tyle, co punkt domu
 *
 * Liczy się od punktu startu, więc dopóki dom nie jest ustalony — albo jest zgadnięty
 * przez nas zamiast wzięty z `HOME_POSITION` — ta liczba mówi mniej, niż wygląda.
 * Stąd [Zapas.pewny].
 */
object Ogrodzenie {

    /** Poniżej tylu metrów zapasu zaczynamy uprzedzać. */
    const val PROG_UWAGI_M = 30f

    /** Poniżej tylu metrów zapas jest już tylko formalnością. */
    const val PROG_OSTRZEZENIA_M = 10f

    /** Rodzaj naruszenia wg `FENCE_BREACH_*` z dialektu. */
    enum class Naruszenie { BRAK, MIN_WYSOKOSC, MAKS_WYSOKOSC, GRANICA, NIEZNANE;

        companion object {
            fun z(kod: Int): Naruszenie = when (kod) {
                0 -> BRAK
                1 -> MIN_WYSOKOSC
                2 -> MAKS_WYSOKOSC
                3 -> GRANICA
                else -> NIEZNANE
            }
        }

        val opis: String
            get() = when (this) {
                BRAK -> "bez naruszeń"
                MIN_WYSOKOSC -> "poniżej dolnej granicy"
                MAKS_WYSOKOSC -> "powyżej pułapu"
                GRANICA -> "poza granicą poziomą"
                NIEZNANE -> "naruszenie nieznanego rodzaju"
            }
    }

    enum class Ocena { WYLACZONE, DOBRZE, UWAGA, OSTRZEZENIE, NARUSZONE }

    data class Zapas(
        val wlaczone: Boolean = false,
        /** Metry do granicy poziomej; `null` gdy nie da się policzyć. */
        val doGranicyM: Float? = null,
        /** Metry do pułapu; `null` gdy `FENCE_ALT_MAX` nieznane. */
        val doPulapuM: Float? = null,
        val naruszenie: Naruszenie = Naruszenie.BRAK,
        val liczbaNaruszen: Int = 0,
        /** Czy zapas poziomy opiera się na domu wziętym z maszyny, a nie zgadniętym. */
        val pewny: Boolean = false,
    ) {
        /** Mniejszy z dwóch zapasów — ten rządzi kolorem. */
        val najmniejszyM: Float?
            get() = listOfNotNull(doGranicyM, doPulapuM).minOrNull()

        val ocena: Ocena
            get() = when {
                !wlaczone -> Ocena.WYLACZONE
                naruszenie != Naruszenie.BRAK -> Ocena.NARUSZONE
                najmniejszyM == null -> Ocena.DOBRZE
                najmniejszyM!! <= PROG_OSTRZEZENIA_M -> Ocena.OSTRZEZENIE
                najmniejszyM!! <= PROG_UWAGI_M -> Ocena.UWAGA
                else -> Ocena.DOBRZE
            }
    }

    /**
     * @param stan bieżący stan maszyny; korzysta z `parametry`, pozycji i wysokości
     */
    fun policz(stan: StanMaszyny): Zapas {
        // FENCE_ENABLE = 0 znaczy wyłączone; brak parametru znaczy „jeszcze nie wiem".
        val wlaczone = (stan.parametry["FENCE_ENABLE"] ?: 0f) > 0.5f
        if (!wlaczone) return Zapas(wlaczone = false, naruszenie = stan.naruszenieOgrodzenia)

        val promien = stan.parametry["FENCE_RADIUS"]?.takeIf { it > 1f }
        val pulap = stan.parametry["FENCE_ALT_MAX"]?.takeIf { it > 1f }

        val dystans = stan.dystansDoDomuM
        val doGranicy = if (promien != null && dystans >= 0f) promien - dystans else null
        val doPulapu = if (pulap != null) pulap - stan.wysokoscM else null

        return Zapas(
            wlaczone = true,
            doGranicyM = doGranicy,
            doPulapuM = doPulapu,
            naruszenie = stan.naruszenieOgrodzenia,
            liczbaNaruszen = stan.liczbaNaruszenOgrodzenia,
            pewny = stan.domZMaszyny,
        )
    }

    /** Parametry, bez których zapas nie da się policzyć. */
    val POTRZEBNE_PARAMETRY = listOf("FENCE_ENABLE", "FENCE_RADIUS", "FENCE_ALT_MAX")
}
