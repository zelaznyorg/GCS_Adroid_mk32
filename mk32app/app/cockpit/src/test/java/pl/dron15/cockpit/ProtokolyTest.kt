package pl.dron15.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dron15.cockpit.domain.Ostrzezenia
import pl.dron15.cockpit.domain.SilnikStanu
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Waga
import pl.dron15.cockpit.net.mavlink.Mavlink
import pl.dron15.cockpit.net.siyi.KlientSiyi

/**
 * Testy protokołów bez sprzętu.
 *
 * Ramki odniesienia wygenerowane pymavlinkiem (dialekt ardupilotmega) — nasz kod ma je
 * dekodować co do bajtu tak samo. Przykłady SIYI pochodzą wprost z instrukcji ZR30 v1.4.
 * Uruchomienie: `gradle :cockpit:testDebugUnitTest`
 */
class ProtokolyTest {

    private fun bajty(hex: String) = ByteArray(hex.length / 2) {
        ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte()
    }

    // ------------------------------------------------------------------ MAVLink: nadawanie

    @Test
    fun `heartbeat GCS zgadza sie co do bajtu z pymavlinkiem`() {
        val nasz = Mavlink.heartbeat()
        val wzorzec = bajty("fd09000000ffbe0000000000000006080004033d48")
        // bajt 4 to numer kolejny — rośnie przy każdym wysłaniu, więc go pomijamy
        val naszBezNumeru = nasz.copyOf().also { it[4] = 0 }
        assertEquals(wzorzec.toList(), naszBezNumeru.toList())
    }

    @Test
    fun `komenda RTL ma poprawna sume kontrolna`() {
        val ramka = Mavlink.komenda(Mavlink.CMD_POWROT_DO_STARTU)
        // Jeśli CRC jest złe, własny skaner odrzuci ramkę — to jest test obu stron naraz.
        val odczytane = Mavlink.skanuj(ramka)
        assertEquals(1, odczytane.size)
        assertEquals(Mavlink.COMMAND_LONG, odczytane[0].msgid)
        val o = Mavlink.Odczyt(odczytane[0].ladunek)
        repeat(7) { o.f32() }
        assertEquals(Mavlink.CMD_POWROT_DO_STARTU, o.u16())
    }

    @Test
    fun `ramka z bledna suma kontrolna jest odrzucana`() {
        val ramka = Mavlink.komenda(Mavlink.CMD_POWROT_DO_STARTU)
        ramka[ramka.size - 1] = (ramka[ramka.size - 1] + 1).toByte()
        assertTrue(Mavlink.skanuj(ramka).isEmpty())
    }

    // ------------------------------------------------------------------ MAVLink: odbiór

    @Test
    fun `GPS_RAW_INT daje satelity, HDOP i kurs z bazy GNSS`() {
        val silnik = SilnikStanu()
        val ramki = Mavlink.skanuj(
            bajty(
                "fd34000000010118000040420f00000000008768111f8798fe0bc0d40100460" +
                        "05a00d2005c12031200000000000000000000000000000000000000005c12627e"
            )
        )
        assertEquals(1, ramki.size)
        silnik.zastosuj(ramki[0], 1000L)
        val s = silnik.stan.value
        assertEquals(18, s.satelity)
        assertEquals(0.70f, s.hdop, 0.001f)
        assertEquals(3, s.rodzajFixa)
        assertTrue(s.kursGnssDostepny)
        assertEquals(47.0f, s.kursGnssSt, 0.01f)
    }

    @Test
    fun `HEARTBEAT daje tryb lotu i stan uzbrojenia`() {
        // base_mode=89 — bit uzbrojenia (128) NIE jest ustawiony
        val rozbrojony = SilnikStanu()
        rozbrojony.zastosuj(Mavlink.skanuj(bajty("fd09000000010100000002000000020359040371a3"))[0], 1000L)
        assertEquals("ALTHOLD", rozbrojony.stan.value.tryb)
        assertFalse(rozbrojony.stan.value.uzbrojony)

        // base_mode=217 = 89 + 128 — ta sama maszyna, uzbrojona
        val uzbrojony = SilnikStanu()
        uzbrojony.zastosuj(Mavlink.skanuj(bajty("fd090000000101000000020000000203d904031f8e"))[0], 1000L)
        assertEquals("ALTHOLD", uzbrojony.stan.value.tryb)
        assertTrue(uzbrojony.stan.value.uzbrojony)
    }

    @Test
    fun `SYS_STATUS daje napiecie i przelicza je na ogniwo`() {
        val silnik = SilnikStanu()
        silnik.zastosuj(
            Mavlink.skanuj(
                bajty("fd1d00000001010100000000000000000000000000002c01245ece0400000000000000000000479d2e")
            )[0], 1000L
        )
        val s = silnik.stan.value
        assertEquals(24.1f, s.napiecieV, 0.01f)
        assertEquals(4.017f, s.napiecieNaOgniwo, 0.01f)   // 6S
        assertEquals(12.3f, s.pradA, 0.01f)
    }

    @Test
    fun `ATTITUDE przelicza radiany na stopnie`() {
        val silnik = SilnikStanu()
        silnik.zastosuj(Mavlink.skanuj(bajty("fd1000000001011e0000e80300000000000000000000db0fc93f2a4e"))[0], 1000L)
        assertEquals(90.0f, silnik.stan.value.kursSt, 0.1f)
    }

    @Test
    fun `GLOBAL_POSITION_INT daje wysokosc wzgledna i predkosc`() {
        val silnik = SilnikStanu()
        silnik.zastosuj(
            Mavlink.skanuj(
                bajty("fd1c0000000101210000e80300008768111f8798fe0ba003020070300000d2000000e2ff5c12f87e")
            )[0], 1000L
        )
        val s = silnik.stan.value
        assertEquals(12.4f, s.wysokoscM, 0.01f)
        assertEquals(2.1f, s.predkoscMs, 0.01f)
        assertEquals(52.1234567, s.szerokosc, 1e-6)
    }

    @Test
    fun `STATUSTEXT rozpoznaje blokade PreArm`() {
        val silnik = SilnikStanu()
        silnik.zastosuj(
            Mavlink.skanuj(
                bajty("fd1b0000000101fd00000650726541726d3a204d6f756e743a206e6f74206865616c746879d735")
            )[0], 1000L
        )
        val k = silnik.stan.value.komunikaty.first()
        assertEquals("PreArm: Mount: not healthy", k.tekst)
        assertTrue(k.blokujePrearm)
    }

    @Test
    fun `EKF_STATUS_REPORT daje flagi i wariancje kursu`() {
        val silnik = SilnikStanu()
        silnik.zastosuj(
            Mavlink.skanuj(bajty("fd160000000101c10000cdcccc3dcdcc4c3ecdcccc3d0ad7a33c000000003f0373be"))[0],
            1000L
        )
        val s = silnik.stan.value
        assertEquals(0x033F, s.flagiEkf)
        assertEquals(0.02f, s.wariancjaKursu, 0.001f)
    }

    // ------------------------------------------------------------------ SIYI

    @Test
    fun `CRC16 zgadza sie z przykladami z instrukcji ZR30`() {
        val przyklady = listOf(
            "5566010000000040" to 0x9c81,   // Request Hardware ID
            "5566010000000019" to 0x575d,   // Request Working Mode
            "5566010000000016" to 0xa6b2,   // Request Max Zoom
            "5566010000000018" to 0x477c,   // Request Zoom Value
        )
        for ((hex, oczekiwane) in przyklady) {
            val d = bajty(hex)
            assertEquals(oczekiwane, KlientSiyi.crc16(d, 0, d.size))
        }
    }

    @Test
    fun `zbudowana ramka SIYI zgadza sie z przykladem producenta`() {
        val ramka = KlientSiyi.zbuduj(0x40, ByteArray(0), numer = 0)
        assertEquals("5566010000000040819c", ramka.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `odpowiedz glowicy jest rozbierana poprawnie`() {
        val odp = bajty("5566020C000900403638303131333031313100007b8b")
        val (cmd, ladunek) = KlientSiyi.rozbierz(odp, odp.size)!!
        assertEquals(0x40, cmd)
        assertEquals("6801130111", ladunek.take(10).map { it.toInt().toChar() }.joinToString(""))
    }

    // ------------------------------------------------------------------ bezpieczenstwo

    @Test
    fun `brak kursu GNSS blokuje RTL i daje baner`() {
        val stan = StanMaszyny(
            czasHeartbeatu = 1000L, kursGnssDostepny = false,
            flagiEkf = 0x033F, satelity = 18, napiecieV = 24.1f
        )
        assertFalse(stan.rtlDostepny)
        val baner = Ostrzezenia.najwazniejsze(Ostrzezenia.ocen(stan, 1200L))!!
        assertEquals(Waga.BLOKADA, baner.waga)
        assertTrue(baner.tekst.contains("KURS"))
    }

    @Test
    fun `FRAME_CLASS inny niz 1 zatrzymuje maszyne na ziemi`() {
        val stan = StanMaszyny(
            czasHeartbeatu = 1000L, kursGnssDostepny = true, kursGnssSt = 47f,
            flagiEkf = 0x033F, satelity = 18, napiecieV = 24.1f,
            parametry = mapOf("FRAME_CLASS" to 4f)
        )
        val ostrzezenia = Ostrzezenia.ocen(stan, 1200L)
        assertTrue(ostrzezenia.any { it.id == "rama" && it.waga == Waga.BLOKADA })
    }

    @Test
    fun `pelne napiecie 6S jest tuz przy limicie ZR30`() {
        val stan = StanMaszyny(
            czasHeartbeatu = 1000L, kursGnssDostepny = true, flagiEkf = 0x033F,
            satelity = 18, napiecieV = 25.3f
        )
        assertTrue(Ostrzezenia.ocen(stan, 1200L).any { it.id == "napiecie_gorne" })
    }

    @Test
    fun `martwa telemetria jest wykrywana po trzech sekundach`() {
        val stan = StanMaszyny(czasHeartbeatu = 1000L)
        assertTrue(stan.telemetriaZywa(2000L))
        assertFalse(stan.telemetriaZywa(5000L))
    }

    // --- wartownik wieku telemetrii nie ma prawa trafic na ekran ------------------

    @Test
    fun `laczy ktore nigdy nie stanelo opisuje sie slowem, nie liczba`() {
        val stan = StanMaszyny(czasHeartbeatu = 0L)
        assertFalse(stan.telemetriaByla)
        assertEquals("nigdy", stan.opisCiszy(9_000_000L))
        // Wartownik istnieje w domenie, ale nie wolno mu wyjsc na belke.
        assertEquals(Float.MAX_VALUE, stan.wiekTelemetriiS(9_000_000L), 0f)
    }

    @Test
    fun `zerwane lacze podaje ile sekund ciszy`() {
        val stan = StanMaszyny(czasHeartbeatu = 1000L)
        assertTrue(stan.telemetriaByla)
        assertEquals("12 s", stan.opisCiszy(13_000L))
    }

    @Test
    fun `baner odroznia brak lacza od lacza zerwanego`() {
        val nigdy = Ostrzezenia.ocen(StanMaszyny(czasHeartbeatu = 0L), 5000L)
            .first { it.id == "telemetria" }
        val zerwane = Ostrzezenia.ocen(StanMaszyny(czasHeartbeatu = 1000L), 13_000L)
            .first { it.id == "telemetria" }

        assertTrue("baner nie moze wypisywac wartownika: ${nigdy.szczegol}",
            nigdy.szczegol.none { it.isDigit() })
        assertTrue(zerwane.szczegol.contains("12 s"))
    }
}
