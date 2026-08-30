package pl.dron15.cockpit

import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dron15.cockpit.ui.zdiagnozujPobieranie
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException

/**
 * Rozpoznawanie przyczyny, dla ktorej kafelek nie chce sie sciagnac.
 *
 * Powod istnienia: 2026-08-26 na MK32 mapa pokazywala pusta siatke, a w logu siedzial
 * `CertificateNotYetValidException` — aparatura miala fabryczny zegar z 2023 roku, wiec
 * kazdy certyfikat HTTPS byl dla niej "jeszcze niewazny". Siec dzialala bez zarzutu.
 * Z pustej siatki nie da sie tego odgadnac, a naprawa to dwa dotkniecia w Androidzie.
 */
class PobieranieMapTest {

    /** 2023-10-02 — data, ktora naprawde stala na aparaturze. */
    private val zegarAparatury = 1_696_262_781_000L

    /** Tak jak w rzeczywistosci: przyczyna lezy dwa poziomy nizej. */
    private fun bladCertyfikatu(): Throwable = SSLHandshakeException("handshake failed").apply {
        initCause(CertificateException("Unacceptable certificate: CN=GlobalSign Atlas R3")
            .apply { initCause(CertificateNotYetValidException("not valid until 2025-07-16")) })
    }

    @Test
    fun `zly zegar rozpoznaje sie po lancuchu przyczyn, nie po wierzchu`() {
        val opis = zdiagnozujPobieranie(bladCertyfikatu(), zegarAparatury)
        assertTrue("brak slowa o zegarze: $opis", opis.contains("zegar"))
        assertTrue("brak daty aparatury: $opis", opis.contains("2023-10-02"))
    }

    @Test
    fun `diagnoza mowi, co zrobic, a nie tylko co sie stalo`() {
        val opis = zdiagnozujPobieranie(bladCertyfikatu(), zegarAparatury)
        assertTrue("brak wskazowki dla operatora: $opis",
            opis.contains("Ustaw") && opis.contains("datę"))
    }

    @Test
    fun `brak sieci to inna diagnoza niz zly zegar`() {
        val opis = zdiagnozujPobieranie(UnknownHostException("tile.openstreetmap.org"), zegarAparatury)
        assertTrue(opis.contains("sieci"))
        assertTrue("brak sieci nie moze obwiniac zegara: $opis", !opis.contains("zegar"))
    }

    @Test
    fun `cisza serwera to trzecia diagnoza`() {
        val opis = zdiagnozujPobieranie(SocketTimeoutException("read timed out"), zegarAparatury)
        assertTrue(opis.contains("nie odpowiada"))
    }

    @Test
    fun `nieznany blad nie udaje, ze wie wiecej, niz wie`() {
        val opis = zdiagnozujPobieranie(IOException("cos innego"), zegarAparatury)
        assertTrue("powinna paść nazwa wyjatku: $opis", opis.contains("IOException"))
        assertTrue(!opis.contains("zegar"))
    }

    @Test
    fun `zapetlony lancuch przyczyn nie zawiesza diagnozy`() {
        // Wyjatek wskazujacy sam na siebie zdarza sie w bibliotekach; petla ma sie urwac.
        val petla = IOException("petla")
        try {
            petla.initCause(petla)
        } catch (_: IllegalArgumentException) {
            // Java broni sie przed samo-przyczyna; wtedy budujemy dluga liste recznie.
            var poprzedni: Throwable = IOException("dno")
            repeat(40) { poprzedni = IOException("poziom", poprzedni) }
            val opis = zdiagnozujPobieranie(poprzedni, zegarAparatury)
            assertTrue(opis.isNotEmpty())
            return
        }
        assertTrue(zdiagnozujPobieranie(petla, zegarAparatury).isNotEmpty())
    }
}
