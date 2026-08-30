package pl.dron15.cockpit.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Wiatr wyliczony z **przechyłu maszyny trzymającej pozycję**.
 *
 * ### Dlaczego nie z `WIND` (168)
 *
 * ArduPilot ma tę wiadomość, ale na wielowirnikowcu estymata wiatru wymaga ustawionych
 * `EK3_DRAG_BCOEF_X/Y` i `EK3_DRAG_MCOEF`. Na tej maszynie nie są ustawione, więc
 * wiadomość przyszłaby i była bezwartościowa. Prosić o liczbę, o której z góry wiadomo,
 * że kłamie, jest gorzej niż jej nie mieć — `dok/PROPOZYCJA_LOT.md` §4.8.
 *
 * ### Skąd zamiast tego
 *
 * Wielowirnikowiec trzymający pozycję w wietrze **stoi przechylony pod wiatr**, i to
 * rozumowanie jest już w `CLAUDE.md` poz. 45: *„dodatnie staje się dopiero na 53–58 m,
 * gdzie maszyna wisiała przechylona −2,9° — to wiatr, nie geometria"*.
 *
 * Zależność jest podręcznikowa: siła pozioma potrzebna do utrzymania pozycji równa się
 * `masa · g · tan(kąt)`, a opór powietrza rośnie z kwadratem prędkości. Stąd
 * `v = k · √(tan θ)`, gdzie `k` skleja masę, powierzchnię czołową i opór w jedną stałą.
 *
 * ### ⚠ To jest oszacowanie, nie pomiar
 *
 * [WSPOLCZYNNIK] jest **dobrany, nie zmierzony** — dla maszyny 8,4 kg o typowej dla tej
 * klasy powierzchni czołowej. Do skalibrowania jednym lotem: zawis w znanym wietrze,
 * odczyt kąta, porównanie z wiatromierzem. Do tego czasu strzałka **kierunku jest
 * wiarygodna**, a liczba metrów na sekundę mówi rząd wielkości.
 *
 * Liczymy tylko wtedy, gdy maszyna faktycznie trzyma pozycję: przy locie do przodu
 * przechył pochodzi od pilota, nie od wiatru, i ta metoda nie ma zastosowania.
 */
object Wiatr {

    /**
     * Stała wiążąca `√(tan θ)` z prędkością w m/s. **Do kalibracji w polu.**
     * 12 m/s przy 30° pochylenia to rozsądny punkt wyjścia dla tej klasy maszyny.
     */
    const val WSPOLCZYNNIK = 15.8f

    /** Powyżej tej prędkości nad ziemią przechył pochodzi od pilota, nie od wiatru. */
    const val MAKS_PREDKOSC_MS = 1.5f

    /** Poniżej tego kąta pomiar tonie w szumie i trymie. */
    const val MIN_KAT_ST = 0.8f

    /** Ile sekund uśredniamy — wiatr zmienia się wolniej niż korekty regulatora. */
    const val OKNO_S = 8

    data class Ocena(
        val znany: Boolean = false,
        /** Skąd wieje, w stopniach (0 = z północy) — jak podaje się wiatr w lotnictwie. */
        val kierunekSt: Float = 0f,
        val predkoscMs: Float = 0f,
        /** Kąt wypadkowego przechyłu, z którego to policzono. */
        val katSt: Float = 0f,
    ) {
        /** Kierunek względem dziobu: 0 = w twarz, 180 = w plecy. */
        fun wzgledemKursu(kursSt: Float): Float = ((kierunekSt - kursSt) % 360f + 360f) % 360f
    }

    /**
     * Bufor uśredniający. Trzymany po stronie wywołującego, bo to stan — a `ocen`
     * ma zostać czystą funkcją, którą da się przetestować bez zegara.
     */
    class Bufor {
        private val probki = ArrayDeque<Trojka>()

        private data class Trojka(val czas: Long, val wschod: Float, val polnoc: Float)

        fun dodaj(czas: Long, wschod: Float, polnoc: Float) {
            probki.addLast(Trojka(czas, wschod, polnoc))
            while (probki.isNotEmpty() && czas - probki.first().czas > OKNO_S * 1000L) {
                probki.removeFirst()
            }
        }

        fun wyczysc() = probki.clear()

        /** Średnia składowych; `null` gdy za mało próbek na sensowną średnią. */
        fun srednia(): Pair<Float, Float>? {
            if (probki.size < 4) return null
            return probki.sumOf { it.wschod.toDouble() }.toFloat() / probki.size to
                    probki.sumOf { it.polnoc.toDouble() }.toFloat() / probki.size
        }
    }

    /**
     * Rozkłada przechylenie i pochylenie na składowe w układzie ziemi.
     *
     * Zwraca `null`, gdy maszyna nie trzyma pozycji — wtedy nie ma czego mierzyć.
     */
    fun skladowe(stan: StanMaszyny): Pair<Float, Float>? {
        if (!stan.uzbrojony || stan.predkoscMs > MAKS_PREDKOSC_MS) return null
        val kurs = Math.toRadians(stan.kursSt.toDouble())
        // Nos w dół (pochylenie ujemne) = lot do przodu = wiatr z przodu.
        val przod = -stan.pochylenieSt
        val prawo = stan.przechylenieSt
        val wschod = (przod * sin(kurs) + prawo * cos(kurs)).toFloat()
        val polnoc = (przod * cos(kurs) - prawo * sin(kurs)).toFloat()
        return wschod to polnoc
    }

    /**
     * @param wschod średnia składowa przechyłu ku wschodowi [°]
     * @param polnoc średnia składowa przechyłu ku północy [°]
     */
    fun ocen(wschod: Float, polnoc: Float): Ocena {
        val kat = hypot(wschod, polnoc)
        if (kat < MIN_KAT_ST) return Ocena(znany = false, katSt = kat)

        // Maszyna pochyla się **w stronę** wiatru, żeby mu się przeciwstawić,
        // a wiatr podaje się jako kierunek, **z którego** wieje — te dwa obroty
        // o 180° znoszą się, więc azymut przechyłu jest wprost kierunkiem wiatru.
        val kierunek = ((Math.toDegrees(atan2(wschod.toDouble(), polnoc.toDouble()))
            .toFloat() % 360f) + 360f) % 360f

        val predkosc = WSPOLCZYNNIK * kotlin.math.sqrt(abs(tan(Math.toRadians(kat.toDouble()))))
            .toFloat()

        return Ocena(znany = true, kierunekSt = kierunek, predkoscMs = predkosc, katSt = kat)
    }
}
