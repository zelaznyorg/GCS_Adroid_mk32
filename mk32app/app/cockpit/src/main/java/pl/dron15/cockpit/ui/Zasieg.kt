package pl.dron15.cockpit.ui

import pl.dron15.cockpit.domain.StanMaszyny
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Zasięg mapy — **ile metrów mieści się w krótszym boku widoku**.
 *
 * Jedna definicja dla mapy lotu, mapy planowania i widoku przestrzennego, żeby przełączenie
 * między nimi nie zmieniało skali pod ręką operatora.
 *
 * ### Dlaczego drabina, a nie mnożenie przez dwa
 *
 * Klawisze `−` i `+` chodzą po ustalonych szczeblach, a nie po dowolnych liczbach. Dzięki
 * temu podziałka pokazuje okrągłe wartości („400 m", „2,5 km"), a operator wraca do tej samej
 * skali, w której planował — zamiast do 417 m, w które wpadł po kilku szczypnięciach.
 * Szczypnięcie zmienia zasięg płynnie; klawisze **zaokrąglają do najbliższego szczebla**
 * i dopiero potem przesuwają się o jeden.
 *
 * Górny szczebel to 20 km: przy takim zasięgu widać ukształtowanie terenu w skali,
 * w której cokolwiek znaczy — pojedyncze wzniesienie ma kilkaset metrów i przy zasięgu
 * 400 m jest płaskie jak stół.
 */
object Zasieg {

    /** Poniżej 50 m kafelki są rozmyte, a znacznik maszyny zajmuje pół ekranu. */
    const val MIN = 50f

    /** Powyżej 20 km wychodzimy poza rejon, dla którego ktokolwiek pobierze kafelki. */
    const val MAKS = 20_000f

    /** 0 znaczy „dobierz sam do śladu" — używa tego mapa lotu. */
    const val AUTO = 0f

    val DRABINA = floatArrayOf(
        50f, 80f, 120f, 200f, 300f, 400f, 600f,
        1_000f, 1_500f, 2_500f, 4_000f, 6_000f, 10_000f, 15_000f, 20_000f,
    )

    /** Szczebel najbliższy danej wartości. */
    fun szczebel(zasiegM: Float): Float =
        DRABINA.minByOrNull { abs(it - zasiegM) } ?: zasiegM

    /** Bliżej, czyli mniejszy zasięg. */
    fun blizej(zasiegM: Float): Float {
        val teraz = szczebel(zasiegM)
        val i = DRABINA.indexOfFirst { it >= teraz - 0.5f }
        // gdy jesteśmy między szczeblami i bieżąca wartość jest już mniejsza, schodzimy o jeden
        val cel = if (teraz < zasiegM - 0.5f) i else i - 1
        return DRABINA[cel.coerceIn(0, DRABINA.size - 1)]
    }

    /** Dalej, czyli większy zasięg. */
    fun dalej(zasiegM: Float): Float {
        val teraz = szczebel(zasiegM)
        val i = DRABINA.indexOfFirst { it >= teraz - 0.5f }
        val cel = if (teraz > zasiegM + 0.5f) i else i + 1
        return DRABINA[cel.coerceIn(0, DRABINA.size - 1)]
    }

    /** Płynna zmiana ze szczypnięcia — bez szczebli, ale w tych samych granicach. */
    fun plynnie(zasiegM: Float, powiekszenie: Float): Float =
        if (powiekszenie <= 0f) zasiegM
        else (zasiegM / powiekszenie).coerceIn(MIN, MAKS)

    fun ograniczony(zasiegM: Float): Float = zasiegM.coerceIn(MIN, MAKS)

    /**
     * Zasięg dobrany sam: cały ślad plus zapas, nie mniej niż 80 m.
     *
     * Liczony **tu, nie w [Mapa]**, bo tę samą liczbę musi pokazać odczyt przy klawiszach
     * `+`/`−`. Gdy mapa liczyła go u siebie, sterowanie zoomem wyświetlało „0 m", czyli
     * wartość znacznika `AUTO` zamiast rzeczywistej skali.
     */
    fun automatyczny(stan: StanMaszyny): Float {
        val (e, n) = if (stan.domUstalony && stan.pozycjaZnana) stan.wzgledemDomu() else 0f to 0f
        val dystans = sqrt(e * e + n * n)
        val najdalszy = stan.slad.maxOfOrNull { (se, sn) -> sqrt(se * se + sn * sn) } ?: 0f
        return ograniczony(max(80f, max(dystans, najdalszy) * 2.6f))
    }

    /** Zasięg obowiązujący: ręczny, jeśli operator go przejął, inaczej dobrany sam. */
    fun obowiazujacy(zadany: Float, stan: StanMaszyny): Float =
        if (zadany > AUTO) ograniczony(zadany) else automatyczny(stan)

    /** „400 m" · „2,5 km" · „10 km" — po polsku, z przecinkiem dziesiętnym. */
    fun opis(zasiegM: Float): String = when {
        zasiegM < 1_000f -> "%.0f m".format(zasiegM)
        zasiegM < 10_000f -> "%.1f km".format(zasiegM / 1_000f).replace('.', ',').replace(",0", "")
        else -> "%.0f km".format(zasiegM / 1_000f)
    }
}
