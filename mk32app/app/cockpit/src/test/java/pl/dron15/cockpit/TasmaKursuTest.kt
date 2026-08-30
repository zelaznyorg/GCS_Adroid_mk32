package pl.dron15.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dron15.cockpit.ui.STRONY_SWIATA
import pl.dron15.cockpit.ui.literaZamiastKreski

/**
 * Podziałka taśmy kursu. Kreska co 30° biegnie przez całą wysokość taśmy, a litery stron
 * świata leżą przy jej górnej krawędzi — bez tej reguły N, E, S i W dostawały linię przez
 * środek znaku i na aparaturze czytało się „É" zamiast E oraz „$" zamiast S.
 */
class TasmaKursuTest {

    private val zakres = 60f

    @Test
    fun `litera zastepuje kreske na kazdej stronie swiata`() {
        // Kurs ustawiony wprost na daną stronę świata, więc litera jest na środku taśmy.
        STRONY_SWIATA.forEach { (kat, nazwa) ->
            assertTrue("$nazwa powinno zastapic kreske",
                literaZamiastKreski(kat.toInt(), kat, zakres))
        }
    }

    @Test
    fun `zwykla kreska podzialki zostaje`() {
        // 85 i 95 to sasiedzi wschodu — tam nie ma litery, więc kreska musi być.
        assertFalse(literaZamiastKreski(85, 90f, zakres))
        assertFalse(literaZamiastKreski(95, 90f, zakres))
        // 60 i 120 to kreski co 30°, ale bez litery.
        assertFalse(literaZamiastKreski(60, 90f, zakres))
        assertFalse(literaZamiastKreski(120, 90f, zakres))
    }

    @Test
    fun `przy krawedzi tasmy litery nie ma, wiec kreska musi zostac`() {
        // Litera rysuje się tylko przy |roznica| <= zakres/2 - 4 = 26°.
        assertTrue(literaZamiastKreski(90, 65f, zakres))    // roznica 25° — litera jest
        assertFalse(literaZamiastKreski(90, 63f, zakres))   // roznica 27° — litery brak
    }

    @Test
    fun `regula dziala na przejsciu przez polnoc`() {
        // Kurs 355°, północ leży 5° w prawo — stopien przychodzi z petli jako 360.
        assertTrue(literaZamiastKreski(360, 355f, zakres))
        // ...a przy kursie 5° ten sam punkt przychodzi jako 0 i jako -0/−360.
        assertTrue(literaZamiastKreski(0, 5f, zakres))
        // Ujemny stopien z petli: -45 to NW = 315°. Przy kursie 340° lezy 25° w bok,
        // czyli w polu widzenia; przy kursie 350° juz 35°, wiec litery nie ma i kreska zostaje.
        assertTrue(literaZamiastKreski(-45, 340f, zakres))
        assertFalse(literaZamiastKreski(-45, 350f, zakres))
    }

    @Test
    fun `lista stron swiata jest pelna i co 45 stopni`() {
        assertEquals(8, STRONY_SWIATA.size)
        STRONY_SWIATA.forEachIndexed { i, (kat, _) ->
            assertEquals(i * 45f, kat, 0f)
        }
    }
}
