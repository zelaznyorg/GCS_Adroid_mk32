package pl.dron15.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import pl.dron15.cockpit.domain.PozycjaPrzelacznika
import pl.dron15.cockpit.domain.Rc
import pl.dron15.cockpit.domain.SilnikStanu
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Tryby
import pl.dron15.cockpit.domain.Waga
import pl.dron15.cockpit.net.mavlink.Mavlink
import org.junit.Test

/**
 * Aparatura, mapa i potwierdzenia komend — rzeczy dołożone przy przebudowie interfejsu
 * (dok/AUDYT_UI.md, znaleziska F1, F2, F4, F6).
 *
 * Ramki odniesienia wygenerowane pymavlinkiem, dialekt ardupilotmega — tak jak w ProtokolyTest.
 */
class RcTest {

    private fun bajty(hex: String) = ByteArray(hex.length / 2) {
        ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte()
    }

    // RC_CHANNELS: 16 kanałów, zakres MK32 1045–1945 µs
    private val RAMKA_RC =
        "fd2a0000000101410000e80300001504d7059907d70515049907d70515041504d705d705d705d705d70515041504" +
                "0000000010ffec56"

    @Test
    fun `RC_CHANNELS daje pozycje drazkow i liczbe kanalow`() {
        val silnik = SilnikStanu()
        silnik.zastosuj(Mavlink.skanuj(bajty(RAMKA_RC))[0], 1000L)
        val s = silnik.stan.value
        assertEquals(16, s.liczbaKanalowRc)
        assertEquals(16, s.kanalyRc.size)
        assertEquals(1045, s.kanalyRc[0])
        assertEquals(1495, s.kanalyRc[1])
        assertEquals(1945, s.kanalyRc[2])
    }

    @Test
    fun `pozycja przelacznika liczy sie wg progow ArduPilota`() {
        assertEquals(PozycjaPrzelacznika.DOL, Rc.pozycja(1045))
        assertEquals(PozycjaPrzelacznika.SRODEK, Rc.pozycja(1495))
        assertEquals(PozycjaPrzelacznika.GORA, Rc.pozycja(1945))
        assertEquals(PozycjaPrzelacznika.BRAK, Rc.pozycja(0))
    }

    @Test
    fun `duplikat funkcji na dwoch kanalach jest blokada`() {
        // Zdarzyło się naprawdę: zoom na CH12 i CH16 dawał "Arm: Duplicate Aux Switch Options"
        val stan = StanMaszyny(
            czasHeartbeatu = 1000L,
            kanalyRc = List(16) { 1495 },
            liczbaKanalowRc = 16,
            parametry = mapOf(
                "RC12_OPTION" to 167f,
                "RC16_OPTION" to 167f,
                "RC9_OPTION" to 153f,
                "RC6_OPTION" to 4f,
            ),
        )
        val ocena = Rc.ocen(stan, teraz = 1500L)
        val duplikat = ocena.usterki.firstOrNull { it.tekst.contains("DWÓCH KANAŁACH") }
        assertTrue("duplikat ma być wykryty", duplikat != null)
        assertEquals(Waga.BLOKADA, duplikat!!.waga)
        assertEquals(listOf(12, 16), duplikat.kanaly)
    }

    @Test
    fun `brak uzbrojenia na przelaczniku jest blokada bo kokpit nie uzbraja z ekranu`() {
        val stan = StanMaszyny(
            czasHeartbeatu = 1000L,
            kanalyRc = List(16) { 1495 },
            liczbaKanalowRc = 16,
            parametry = mapOf("RC6_OPTION" to 4f),
        )
        val ocena = Rc.ocen(stan, teraz = 1500L)
        assertTrue(ocena.usterki.any { it.tekst.contains("UZBROJENIE") && it.waga == Waga.BLOKADA })
        assertFalse(ocena.usterki.any { it.tekst.contains("RTL NIE JEST") })
    }

    @Test
    fun `nierozpoznanego kodu funkcji nie nazywamy zgadujac`() {
        assertEquals("RTL — powrót do startu", Rc.nazwaFunkcji(4))
        assertEquals("OPCJA 199 — nierozpoznana", Rc.nazwaFunkcji(199))
        assertFalse(Rc.rozpoznana(199))
    }

    // ------------------------------------------------------------------ mapa i dom

    @Test
    fun `dystans i namiar na dom licza sie z pozycji`() {
        val stan = StanMaszyny(
            domSzerokosc = 52.0, domDlugosc = 20.0, domUstalony = true,
            szerokosc = 52.0009, dlugosc = 20.0,          // ok. 100 m na północ
        )
        assertEquals(100f, stan.dystansDoDomuM, 3f)
        assertEquals(0f, stan.namiarNaDomSt, 2f)

        val naWschod = stan.copy(szerokosc = 52.0, dlugosc = 20.00146)
        assertEquals(100f, naWschod.dystansDoDomuM, 5f)
        assertEquals(90f, naWschod.namiarNaDomSt, 2f)
    }

    @Test
    fun `bez ustalonego domu dystans jest ujemny, a nie zerowy`() {
        val stan = StanMaszyny(szerokosc = 52.0, dlugosc = 20.0)
        assertTrue(stan.dystansDoDomuM < 0f)
        assertTrue(stan.namiarNaDomSt < 0f)
    }

    @Test
    fun `pozycja z fixem ustala dom prowizoryczny i buduje slad`() {
        val silnik = SilnikStanu()
        // 18 satelitów — dopiero wtedy wolno przyjąć pozycję za punkt odniesienia mapy
        silnik.zastosuj(Mavlink.skanuj(bajty(
            "fd34000000010118000040420f00000000008768111f8798fe0bc0d4010046005a00c800000003120000000000" +
                    "00000000000000000000000000000094119ea3"))[0], 1000L)
        silnik.zastosuj(Mavlink.skanuj(bajty(
            "fd1a0000000101210000e80300008768111f8798fe0ba003020070300000d2000000e2ff43fa"))[0], 1100L)

        val poPierwszej = silnik.stan.value
        assertTrue("dom ma się ustalić z pierwszej pewnej pozycji", poPierwszej.domUstalony)
        assertEquals(0f, poPierwszej.dystansDoDomuM, 0.5f)

        // druga pozycja ok. 100 m na północ — ślad rośnie, dystans też
        silnik.zastosuj(Mavlink.skanuj(bajty(
            "fd1a0000000101210000d0070000af8b111f8798fe0ba003020070300000d2000000e2ff7ef9"))[0], 2100L)
        val poDrugiej = silnik.stan.value
        assertEquals(100f, poDrugiej.dystansDoDomuM, 5f)
        assertTrue("ślad ma zapamiętać przebytą drogę", poDrugiej.slad.size >= 1)
    }

    // ------------------------------------------------------------------ potwierdzenia komend

    @Test
    fun `COMMAND_ACK dopisuje wynik do ostatniej komendy`() {
        val silnik = SilnikStanu()
        silnik.zapiszKomende(Mavlink.CMD_POWROT_DO_STARTU, "RTL", 1000L)
        silnik.zastosuj(Mavlink.skanuj(bajty("fd0100000001014d0000141c0d"))[0], 1200L)
        val k = silnik.stan.value.ostatniaKomenda!!
        assertTrue(k.przyjeta)
        assertEquals("przyjęta", k.stan(1300L))
    }

    @Test
    fun `komenda bez odpowiedzi po trzech sekundach mowi o tym wprost`() {
        val silnik = SilnikStanu()
        silnik.zapiszKomende(Mavlink.CMD_POWROT_DO_STARTU, "RTL", 1000L)
        val k = silnik.stan.value.ostatniaKomenda!!
        assertEquals("wysłana…", k.stan(2000L))
        assertEquals("bez potwierdzenia", k.stan(5000L))
    }

    @Test
    fun `tryby automatyczne sa rozpoznawane, bo z nich trzeba umiec wyjsc`() {
        assertTrue(Tryby.automatyczny("RTL"))
        assertTrue(Tryby.automatyczny("AUTO"))
        assertFalse(Tryby.automatyczny("ALTHOLD"))
        assertEquals("LOITER", Tryby.nazwa(Tryby.LOITER))
    }

    @Test
    fun `czas lotu liczy sie od uzbrojenia i zatrzymuje po rozbrojeniu`() {
        val wLocie = StanMaszyny(uzbrojony = true, czasUzbrojenia = 10_000L, czasLotuMs = 0L)
        assertEquals(30L, wLocie.czasLotuS(40_000L))
        val poLocie = StanMaszyny(uzbrojony = false, czasUzbrojenia = 0L, czasLotuMs = 95_000L)
        assertEquals(95L, poLocie.czasLotuS(999_999L))
    }
}
