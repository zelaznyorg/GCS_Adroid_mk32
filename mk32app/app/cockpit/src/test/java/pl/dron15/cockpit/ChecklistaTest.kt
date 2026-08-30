package pl.dron15.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dron15.cockpit.domain.Checklista
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Werdykt
import java.io.File

/**
 * Testy checklisty przedlotowej na **prawdziwym** pliku reguł z assets — nie na wersji
 * pisanej pod test. Dzięki temu literówka w regule wywala się tutaj, a nie na lotnisku.
 */
class ChecklistaTest {

    private val checklista =
        Checklista.zJson(File("src/main/assets/preflight_rules.json").readText())

    /**
     * Konfiguracja zgodna z dok/ODNIESIENIE_QUAD_20260815.parm — **poza mapowaniem wyjść**.
     *
     * Mapowanie `36/33/34/35` pochodzi z logu lotu 2 z 2026-08-16 i to nim maszyna
     * faktycznie latała (błąd kąta sigma < 1,2 st.). Do 2026-08-26 stało tu `34/36/33/35`
     * z etapu 20, nigdy nieoblatane — ten sam nieaktualny zestaw co w regułach. Odczyt
     * z kontrolera 2026-08-26 potwierdził wersję z lotu. Patrz CLAUDE.md, sekcja 1.
     */
    private fun parametryPoprawne() = mapOf(
        "FRAME_CLASS" to 1f, "FRAME_TYPE" to 1f,
        "SERVO1_FUNCTION" to 36f, "SERVO2_FUNCTION" to 33f,
        "SERVO3_FUNCTION" to 34f, "SERVO4_FUNCTION" to 35f,
        "RTL_ALT" to 5000f, "RTL_CLIMB_MIN" to 500f, "RTL_ALT_FINAL" to 0f,
        "FS_THR_ENABLE" to 1f, "FS_THR_VALUE" to 975f, "RC3_MIN" to 1045f,
        "INS_ACCSCAL_X" to 0.997694f, "INS_ACCSCAL_Y" to 0.997094f, "INS_ACCSCAL_Z" to 0.997249f,
        "AHRS_TRIM_X" to -0.0029f, "AHRS_TRIM_Y" to 0.0065f,
        "MNT1_TYPE" to 8f, "SERIAL2_PROTOCOL" to 8f,
        "SERIAL6_PROTOCOL" to 2f, "SERIAL6_OPTIONS" to 4096f,
        "EK3_SRC1_YAW" to 2f, "COMPASS_USE" to 0f,
        "GPS1_TYPE" to 25f, "GPS1_MB_TYPE" to 1f, "GPS1_MB_OFS_X" to 0.4f,
    ) + (5..16).associate { "SERVO${it}_FUNCTION" to 0f }

    private fun stanPoprawny(teraz: Long) = StanMaszyny(
        czasHeartbeatu = teraz, satelity = 18, hdop = 0.7f, kursGnssDostepny = true,
        flagiEkf = 0x033F, wariancjaKursu = 0.02f, napiecieV = 24.1f,
    )

    private fun pozycja(id: String, parametry: Map<String, Float>, stan: StanMaszyny) =
        checklista.ocen(parametry, stan, 1000L).first { it.id == id }

    // ------------------------------------------------------------------ stan poprawny

    @Test
    fun `poprawna konfiguracja daje werdykt GOTOWY`() {
        val pozycje = checklista.ocen(parametryPoprawne(), stanPoprawny(1000L), 1000L)
        val zle = pozycje.filter { it.werdykt != Werdykt.OK }
        assertTrue("nie powinno byc uwag, a sa: $zle", zle.isEmpty())
        assertEquals(Werdykt.OK, checklista.werdyktZbiorczy(pozycje))
    }

    @Test
    fun `checklista wie, o ktore parametry zapytac maszyne`() {
        val potrzebne = checklista.potrzebneParametry
        assertTrue("FRAME_CLASS" in potrzebne)
        assertTrue("SERVO16_FUNCTION" in potrzebne)      // zakres musi się rozwinąć
        assertTrue("RC3_MIN" in potrzebne)               // z wyrażenia RC3_MIN - FS_THR_VALUE
        assertTrue("FS_THR_VALUE" in potrzebne)
        // Pełny zrzut to 1306 parametrów; checklista ma pytać o garść, nie o wszystko.
        assertTrue("za duzo parametrow: ${potrzebne.size}", potrzebne.size in 30..60)
    }

    // ------------------------------------------------------------------ blokady

    @Test
    fun `FRAME_CLASS rowny 4 to blokada — miksler osmiu silnikow`() {
        val p = pozycja("rama", parametryPoprawne() + ("FRAME_CLASS" to 4f), stanPoprawny(1000L))
        assertEquals(Werdykt.BLOKADA, p.werdykt)
        assertTrue(p.komunikat.contains("OŚMIU"))
    }

    @Test
    fun `zamienione mapowanie silnikow to blokada`() {
        val zamienione = parametryPoprawne() +
                mapOf("SERVO1_FUNCTION" to 33f, "SERVO3_FUNCTION" to 34f)
        assertEquals(Werdykt.BLOKADA, pozycja("silniki", zamienione, stanPoprawny(1000L)).werdykt)
    }

    @Test
    fun `failsafe gazu powyzej minimum RC to blokada`() {
        // FS_THR_VALUE=1040 przy RC3_MIN=1045 daje margines 5 — za mało, próg praktycznie
        // pokrywa się z dolnym zakresem drążka
        val p = pozycja("failsafe_gaz", parametryPoprawne() + ("FS_THR_VALUE" to 1040f), stanPoprawny(1000L))
        assertEquals(Werdykt.BLOKADA, p.werdykt)
    }

    @Test
    fun `zerowa baza GNSS to blokada`() {
        val p = pozycja("gnss_baza", parametryPoprawne() + ("GPS1_MB_OFS_X" to 0f), stanPoprawny(1000L))
        assertEquals(Werdykt.BLOKADA, p.werdykt)
    }

    // ------------------------------------------------------------------ ostrzezenia

    @Test
    fun `skale akcelerometru rowne jeden to sygnatura kalibracji uproszczonej`() {
        val jedynki = parametryPoprawne() + mapOf(
            "INS_ACCSCAL_X" to 1f, "INS_ACCSCAL_Y" to 1f, "INS_ACCSCAL_Z" to 1f
        )
        assertEquals(Werdykt.OSTRZEZENIE, pozycja("kalibracja_acc", jedynki, stanPoprawny(1000L)).werdykt)
    }

    @Test
    fun `trim ponad stopien to ostrzezenie, a wartosc pokazuje sie w stopniach`() {
        // 0,0439 rad = 2,52° — dokładnie ten przypadek, który zdarzył się przy poziomowaniu
        // na krzywym podłożu
        val p = pozycja("poziomowanie", parametryPoprawne() + ("AHRS_TRIM_X" to 0.043935f), stanPoprawny(1000L))
        assertEquals(Werdykt.OSTRZEZENIE, p.werdykt)
        assertTrue("brak stopni w opisie: ${p.wartosc}", p.wartosc.contains("2,5") || p.wartosc.contains("2.5"))
    }

    @Test
    fun `swiadomy brak kompasu to informacja, nie blokada`() {
        val pozycje = checklista.ocen(parametryPoprawne(), stanPoprawny(1000L), 1000L)
        val nawigacja = pozycje.first { it.id == "nawigacja" }
        assertEquals(Werdykt.OK, nawigacja.werdykt)
    }

    // ------------------------------------------------------------------ telemetria

    @Test
    fun `brak kursu GNSS to blokada w checklistcie`() {
        val stan = stanPoprawny(1000L).copy(kursGnssDostepny = false)
        assertEquals(Werdykt.BLOKADA, pozycja("kurs_gnss", parametryPoprawne(), stan).werdykt)
    }

    @Test
    fun `flagi EKF bez pozycji poziomej to blokada`() {
        // 0x00A7 — stan spod dachu: fix jest, pozycji poziomej nie ma
        val stan = stanPoprawny(1000L).copy(flagiEkf = 0x00A7)
        assertEquals(Werdykt.BLOKADA, pozycja("ekf", parametryPoprawne(), stan).werdykt)
    }

    @Test
    fun `pelne 6S ponad limit ZR30 to ostrzezenie`() {
        val stan = stanPoprawny(1000L).copy(napiecieV = 25.3f)
        assertEquals(Werdykt.OSTRZEZENIE, pozycja("napiecie_gorne", parametryPoprawne(), stan).werdykt)
    }

    @Test
    fun `martwa telemetria to blokada`() {
        val stan = stanPoprawny(1000L)
        assertEquals(Werdykt.BLOKADA, pozycja("lacze", parametryPoprawne(), stan.copy(czasHeartbeatu = 0L)).werdykt)
    }

    // ------------------------------------------------------------------ braki danych

    @Test
    fun `brak parametru to BRAK_DANYCH, nie ciche przejscie`() {
        val bez = parametryPoprawne() - "FRAME_CLASS"
        val p = pozycja("rama", bez, stanPoprawny(1000L))
        assertEquals(Werdykt.BRAK_DANYCH, p.werdykt)
    }

    @Test
    fun `werdykt zbiorczy stawia blokade ponad ostrzezeniem`() {
        val zle = parametryPoprawne() + mapOf("FRAME_CLASS" to 4f, "INS_ACCSCAL_X" to 1f)
        val pozycje = checklista.ocen(zle, stanPoprawny(1000L), 1000L)
        assertEquals(Werdykt.BLOKADA, checklista.werdyktZbiorczy(pozycje))
    }

    @Test
    fun `rozwijanie zakresu parametrow`() {
        val nazwy = Checklista.rozwinZakres("SERVO5_FUNCTION", "SERVO16_FUNCTION")
        assertEquals(12, nazwy.size)
        assertEquals("SERVO5_FUNCTION", nazwy.first())
        assertEquals("SERVO16_FUNCTION", nazwy.last())
    }
}
