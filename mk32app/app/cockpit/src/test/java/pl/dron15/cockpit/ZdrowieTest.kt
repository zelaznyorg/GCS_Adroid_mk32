package pl.dron15.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dron15.cockpit.domain.Czujniki
import pl.dron15.cockpit.domain.Ogrodzenie
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Wiatr

/**
 * Czujniki, geofence i wiatr — etapy 4 i 5 z `dok/PROPOZYCJA_LOT.md`.
 *
 * Przypadki są pod **tę** maszynę: brak kompasu z decyzji, dom bywa zgadnięty,
 * a wiatr liczy się z przechyłu, bo estymata ArduPilota jest tu bezwartościowa.
 */
class ZdrowieTest {

    private val GPS = 0x00000020
    private val MAG = 0x00000004
    private val ZYR = 0x00000001
    private val BAT = 0x02000000

    // ------------------------------------------------------------------ czujniki

    @Test
    fun `brak kompasu nie jest usterka`() {
        // Ta maszyna ma COMPASS_USE = 0 i bit obecności zgaszony. Gdyby magnetometr
        // pokazywał się na czerwono, pilot nauczyłby się ignorować czerwień.
        val obecne = GPS or ZYR or BAT           // MAG celowo nieobecny
        val lista = Czujniki.odczytaj(obecne, obecne, obecne)
        assertTrue("magnetometru nie ma na liście",
            lista.none { it.rodzaj == Czujniki.Rodzaj.GPS && false } &&
                    lista.all { it.rodzaj.bit != MAG })
        assertNull("nic nie jest uszkodzone", Czujniki.opisUsterek(lista))
        assertEquals(Czujniki.Stan.NIEOBECNY, Czujniki.stan(MAG, obecne, obecne, obecne))
    }

    @Test
    fun `niezdrowy GPS to usterka, wylaczony to nie`() {
        val obecne = GPS or ZYR
        assertEquals(Czujniki.Stan.USZKODZONY, Czujniki.stan(GPS, obecne, obecne, ZYR))
        assertEquals(Czujniki.Stan.WYLACZONY, Czujniki.stan(GPS, obecne, ZYR, obecne))
        assertEquals(Czujniki.Stan.SPRAWNY, Czujniki.stan(GPS, obecne, obecne, obecne))
    }

    @Test
    fun `opis usterek wymienia trzy i liczy reszte`() {
        val wszystkie = Czujniki.Rodzaj.entries.fold(0) { a, r -> a or r.bit }
        // Wszystko obecne i włączone, nic zdrowe.
        val opis = Czujniki.opisUsterek(Czujniki.odczytaj(wszystkie, wszystkie, 0))
        assertNotNull(opis)
        assertTrue("ma wymieniać skróty", opis!!.contains("GPS"))
        assertTrue("ma podsumować resztę liczbą", opis.contains("więcej"))
    }

    @Test
    fun `zerowe maski znacza brak wiedzy, nie awarie wszystkiego`() {
        val lista = Czujniki.odczytaj(0, 0, 0)
        assertTrue("nic nie jest obecne, więc lista pusta", lista.isEmpty())
        assertNull("i nic nie zgłaszamy jako usterkę", Czujniki.opisUsterek(lista))
    }

    // ------------------------------------------------------------------ geofence

    private fun stan(
        dystansM: Double = 0.0,
        wysokosc: Float = 0f,
        promien: Float = 300f,
        pulap: Float = 120f,
        wlaczone: Boolean = true,
        domZMaszyny: Boolean = true,
    ) = StanMaszyny(
        wysokoscM = wysokosc,
        szerokosc = 52.0 + dystansM / StanMaszyny.METRY_NA_STOPIEN,
        dlugosc = 20.0,
        domSzerokosc = 52.0, domDlugosc = 20.0,
        domUstalony = true, domZMaszyny = domZMaszyny,
        parametry = mapOf(
            "FENCE_ENABLE" to if (wlaczone) 1f else 0f,
            "FENCE_RADIUS" to promien,
            "FENCE_ALT_MAX" to pulap,
        ),
    )

    @Test
    fun `zapas liczy sie od mniejszego z dwoch`() {
        // 100 m od domu przy promieniu 300 → 200 m poziomo; 110 m przy pułapie 120 → 10 m.
        val z = Ogrodzenie.policz(stan(dystansM = 100.0, wysokosc = 110f))
        assertEquals(200f, z.doGranicyM!!, 1f)
        assertEquals(10f, z.doPulapuM!!, 0.1f)
        assertEquals(10f, z.najmniejszyM!!, 0.1f)
        assertEquals(Ogrodzenie.Ocena.OSTRZEZENIE, z.ocena)
    }

    @Test
    fun `wylaczony geofence nie alarmuje`() {
        val z = Ogrodzenie.policz(stan(dystansM = 299.0, wysokosc = 119f, wlaczone = false))
        assertFalse(z.wlaczone)
        assertEquals(Ogrodzenie.Ocena.WYLACZONE, z.ocena)
    }

    @Test
    fun `naruszenie bije zapas`() {
        val s = stan(dystansM = 10.0).copy(
            naruszenieOgrodzenia = Ogrodzenie.Naruszenie.MAKS_WYSOKOSC)
        assertEquals(Ogrodzenie.Ocena.NARUSZONE, Ogrodzenie.policz(s).ocena)
    }

    @Test
    fun `zgadniety dom oznacza zapas jako niepewny`() {
        assertFalse(Ogrodzenie.policz(stan(domZMaszyny = false)).pewny)
        assertTrue(Ogrodzenie.policz(stan(domZMaszyny = true)).pewny)
    }

    // ------------------------------------------------------------------ wiatr

    private fun wZawisie(przechyl: Float, pochylenie: Float, kurs: Float, predkosc: Float = 0.3f) =
        StanMaszyny(
            uzbrojony = true,
            przechylenieSt = przechyl,
            pochylenieSt = pochylenie,
            kursSt = kurs,
            predkoscMs = predkosc,
        )

    @Test
    fun `lot do przodu wyklucza pomiar wiatru`() {
        assertNull(Wiatr.skladowe(wZawisie(0f, -5f, 0f, predkosc = 6f)))
    }

    @Test
    fun `maszyna rozbrojona nie mierzy wiatru`() {
        val s = wZawisie(0f, -5f, 0f).copy(uzbrojony = false)
        assertNull(Wiatr.skladowe(s))
    }

    @Test
    fun `nos w dol przy kursie polnocnym to wiatr z polnocy`() {
        // Pochylenie −5° = nos w dół = maszyna pcha na północ = wiatr wieje z północy.
        val (w, n) = Wiatr.skladowe(wZawisie(0f, -5f, 0f))!!
        assertEquals(0f, w, 0.01f)
        assertEquals(5f, n, 0.01f)
        val o = Wiatr.ocen(w, n)
        assertTrue(o.znany)
        assertEquals(0f, o.kierunekSt, 1f)
    }

    @Test
    fun `ten sam przechyl przy kursie wschodnim to wiatr ze wschodu`() {
        val (w, n) = Wiatr.skladowe(wZawisie(0f, -5f, 90f))!!
        val o = Wiatr.ocen(w, n)
        assertEquals(90f, o.kierunekSt, 1f)
    }

    @Test
    fun `przechyl w prawo przy kursie polnocnym to wiatr ze wschodu`() {
        val (w, n) = Wiatr.skladowe(wZawisie(5f, 0f, 0f))!!
        assertEquals(90f, Wiatr.ocen(w, n).kierunekSt, 1f)
    }

    @Test
    fun `maly kat nie daje odczytu`() {
        assertFalse(Wiatr.ocen(0.2f, 0.2f).znany)
    }

    @Test
    fun `predkosc rosnie z katem`() {
        val slaby = Wiatr.ocen(0f, 3f).predkoscMs
        val mocny = Wiatr.ocen(0f, 12f).predkoscMs
        assertTrue("większy przechył to silniejszy wiatr", mocny > slaby)
        assertTrue("rząd wielkości ma być lotny, nie kosmiczny", mocny < 40f)
    }

    @Test
    fun `kierunek wzgledem kursu`() {
        val o = Wiatr.Ocena(znany = true, kierunekSt = 90f)
        assertEquals("wiatr ze wschodu przy kursie północnym wieje z prawej",
            90f, o.wzgledemKursu(0f), 0.1f)
        assertEquals("przy kursie wschodnim wieje w twarz",
            0f, o.wzgledemKursu(90f), 0.1f)
    }

    @Test
    fun `bufor usrednia dopiero od czterech probek`() {
        val b = Wiatr.Bufor()
        b.dodaj(1000, 1f, 1f)
        b.dodaj(1100, 1f, 1f)
        b.dodaj(1200, 1f, 1f)
        assertNull("trzy próbki to za mało", b.srednia())
        b.dodaj(1300, 1f, 1f)
        assertNotNull(b.srednia())
    }

    @Test
    fun `bufor zapomina probki starsze niz okno`() {
        val b = Wiatr.Bufor()
        repeat(4) { b.dodaj(1000L + it, 10f, 0f) }
        // Skok o więcej niż okno wypycha wszystko poprzednie.
        repeat(4) { b.dodaj(1000L + Wiatr.OKNO_S * 1000L + 100 + it, 2f, 0f) }
        val (w, _) = b.srednia()!!
        assertEquals("stare próbki nie mogą ciągnąć średniej", 2f, w, 0.01f)
    }
}
