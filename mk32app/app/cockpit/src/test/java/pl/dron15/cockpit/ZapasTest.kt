package pl.dron15.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dron15.cockpit.diag.Dzwieki
import pl.dron15.cockpit.domain.Ciag
import pl.dron15.cockpit.domain.Energia
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Sygnaly

/**
 * Zapas ciągu, bilans energii i wyzwalanie alarmów.
 *
 * Liczby kontrolne pochodzą z **rzeczywistych lotów tej maszyny** opisanych w `CLAUDE.md`
 * poz. 45 i 55, nie z wymyślonych przypadków. Jeśli któryś z tych testów kiedyś padnie,
 * znaczy to, że przyrząd przestał zgadzać się z tym, co zmierzono w powietrzu.
 */
class ZapasTest {

    private val mapowanieLotne = mapOf(
        "SERVO1_FUNCTION" to 36f, "SERVO2_FUNCTION" to 33f,
        "SERVO3_FUNCTION" to 34f, "SERVO4_FUNCTION" to 35f,
    )

    // ------------------------------------------------------------------ zapas ciągu

    @Test
    fun `zawis 58 m z lotu 3 daje 66 us zapasu`() {
        // CLAUDE.md poz. 45: średnia 1787, najwyższe C1 = 1884, sufit 1950.
        val z = Ciag.policz(listOf(1884, 1750, 1760, 1754))
        assertTrue(z.znany)
        assertEquals(1950, z.sufitUs)
        assertEquals(66, z.zapasUs)
        // I to jest cała wartość tego przyrządu: 15 s przed spadkiem z 58 na 17 m
        // maszyna miała już świecić na bursztynowo, a nie wyglądać normalnie.
        assertEquals(Ciag.Ocena.UWAGA, z.ocena)
    }

    @Test
    fun `wyjscie na suficie to zapas zero i blokada`() {
        val z = Ciag.policz(listOf(1950, 1800, 1810, 1805))
        assertEquals(0, z.zapasUs)
        assertEquals(Ciag.Ocena.BLOKADA, z.ocena)
    }

    @Test
    fun `progi zapasu przechodza przez trzy stopnie`() {
        fun ocena(najwyzsze: Int) = Ciag.policz(listOf(najwyzsze, 1500, 1500, 1500)).ocena
        assertEquals(Ciag.Ocena.DOBRZE, ocena(1800))        // zapas 150
        assertEquals(Ciag.Ocena.UWAGA, ocena(1860))         // zapas 90
        assertEquals(Ciag.Ocena.OSTRZEZENIE, ocena(1900))   // zapas 50
        assertEquals(Ciag.Ocena.BLOKADA, ocena(1915))       // zapas 35
    }

    @Test
    fun `sufit idzie za MOT_SPIN_MAX z maszyny`() {
        assertEquals(1900, Ciag.policz(listOf(1800, 1800, 1800, 1800), spinMax = 0.90f).sufitUs)
        assertEquals(1950, Ciag.policz(listOf(1800, 1800, 1800, 1800), spinMax = 0.95f).sufitUs)
    }

    @Test
    fun `rozrzut z lotu 3 przekracza prog poz 45`() {
        // Przelot 6 m/s: rozrzut 309 µs przy progu 60.
        val z = Ciag.policz(listOf(1831, 1522, 1600, 1610))
        assertEquals(309, z.rozrzutUs)
        assertEquals(Ciag.Ocena.OSTRZEZENIE, z.ocenaRozrzutu)
    }

    @Test
    fun `silnik zatrzymany nie jest traktowany jak zapas`() {
        assertFalse(Ciag.policz(listOf(1800, 1800, 1800, 0)).znany)
        assertFalse(Ciag.policz(listOf(1800, 1800)).znany)
    }

    // ------------------------------------------------------------------ składowe

    @Test
    fun `ciezki tyl wychodzi dodatni`() {
        // S1 tył prawy, S2 przód prawy, S3 tył lewy, S4 przód lewy.
        // Tył o 100 µs wyżej niż przód.
        val z = Ciag.policz(listOf(1850, 1750, 1850, 1750), mapowanieZgodne = true)
        assertEquals(100, z.skladowe!!.tylPrzod)
        assertEquals(0, z.skladowe!!.prawoLewo)
        assertEquals("ciężki tył", z.skladowe!!.dominujaca)
    }

    @Test
    fun `ciezka prawa strona wychodzi dodatnia`() {
        val z = Ciag.policz(listOf(1850, 1850, 1750, 1750), mapowanieZgodne = true)
        assertEquals(0, z.skladowe!!.tylPrzod)
        assertEquals(100, z.skladowe!!.prawoLewo)
    }

    @Test
    fun `roznica CW minus CCW liczy sie po przekatnych`() {
        // CW = przód lewy (S4) + tył prawy (S1); CCW = przód prawy (S2) + tył lewy (S3).
        val z = Ciag.policz(listOf(1850, 1750, 1750, 1850), mapowanieZgodne = true)
        assertEquals(100, z.skladowe!!.cwCcw)
        assertEquals(0, z.skladowe!!.tylPrzod)
        assertEquals(0, z.skladowe!!.prawoLewo)
    }

    @Test
    fun `bez potwierdzonego mapowania skladowych nie pokazujemy`() {
        val z = Ciag.policz(listOf(1850, 1750, 1850, 1750), mapowanieZgodne = false)
        assertNull(z.skladowe)
    }

    @Test
    fun `archiwalne mapowanie z etapu 20 nie jest uznawane`() {
        // 34/36/33/35 to wersja oznaczona w CLAUDE.md jako nieaktualna — dok/AUDYT_M3.md B2.
        assertFalse(Ciag.mapowanieZgodne(mapOf(
            "SERVO1_FUNCTION" to 34f, "SERVO2_FUNCTION" to 36f,
            "SERVO3_FUNCTION" to 33f, "SERVO4_FUNCTION" to 35f,
        )))
        assertTrue(Ciag.mapowanieZgodne(mapowanieLotne))
    }

    @Test
    fun `brak parametrow to brak zgody na rozklad`() {
        assertFalse(Ciag.mapowanieZgodne(emptyMap()))
        assertFalse(Ciag.mapowanieZgodne(mapOf("SERVO1_FUNCTION" to 36f)))
    }

    // ------------------------------------------------------------------ energia

    private fun stanWLocie(
        zuzycie: Int,
        pojemnosc: Int,
        czasLotuS: Long = 300,
        dystans: Double = 0.0,
        wysokosc: Float = 0f,
    ) = StanMaszyny(
        uzbrojony = true,
        zuzycieMah = zuzycie,
        czasLotuMs = czasLotuS * 1000,
        wysokoscM = wysokosc,
        szerokosc = 52.0 + dystans / StanMaszyny.METRY_NA_STOPIEN,
        dlugosc = 20.0,
        domSzerokosc = 52.0,
        domDlugosc = 20.0,
        domUstalony = true,
        parametry = mapOf("BATT_CAPACITY" to pojemnosc.toFloat()),
    )

    @Test
    fun `dzisiejsza konfiguracja jest uznana za niekalibrowana`() {
        // BATT_CAPACITY = 3300 przy 4538 mAh zużytych w locie 2 — CLAUDE.md poz. 40.
        val b = Energia.policz(stanWLocie(4538, 3300), teraz = 0)
        assertFalse(b.wiarygodny)
        assertNull(b.doJokeraS)
        assertTrue(b.powodNiepewnosci!!.contains("kalibracja"))
        // Same mAh muszą się pokazać mimo to — to jedyna prawdziwa liczba, jaką mamy.
        assertEquals(4538, b.zuzycieMah)
    }

    @Test
    fun `brak BATT_CAPACITY tez jest niewiarygodny`() {
        val b = Energia.policz(StanMaszyny(zuzycieMah = 1000), teraz = 0)
        assertFalse(b.wiarygodny)
        assertTrue(b.powodNiepewnosci!!.contains("BATT_CAPACITY"))
    }

    @Test
    fun `przy skalibrowanym pakiecie liczy udzial i czasy`() {
        // 22500 mAh = pakiet 8S5P z poz. 56; zużyte 4500 = 20 %.
        val b = Energia.policz(stanWLocie(4500, 22500), teraz = 0)
        assertTrue(b.wiarygodny)
        assertEquals(0.20f, b.udzial, 0.001f)
        assertTrue("JOKER musi być przed BINGO", b.doJokeraS!! < b.doBingoS!!)
        assertTrue("oba przed nami", b.doJokeraS!! > 0)
    }

    @Test
    fun `po przekroczeniu progu JOKER wychodzi ujemny`() {
        // 70 % zużyte, zostało 30 % — poniżej progu JOKER (35 %).
        val b = Energia.policz(stanWLocie(15750, 22500), teraz = 0)
        assertTrue(b.wiarygodny)
        assertTrue(b.poJokerze)
        assertFalse(b.poBingo)
    }

    @Test
    fun `czas powrotu rosnie z dystansem i wysokoscia`() {
        val blisko = Energia.czasPowrotuS(stanWLocie(0, 22500, dystans = 0.0, wysokosc = 0f))
        val daleko = Energia.czasPowrotuS(stanWLocie(0, 22500, dystans = 600.0, wysokosc = 60f))
        assertTrue("dalej i wyżej musi trwać dłużej", daleko > blisko + 100)
    }

    @Test
    fun `daleki lot przesuwa JOKER wczesniej niz sam prog pojemnosci`() {
        val blisko = Energia.policz(stanWLocie(9000, 22500, dystans = 0.0), teraz = 0)
        val daleko = Energia.policz(stanWLocie(9000, 22500, dystans = 3000.0, wysokosc = 100f),
            teraz = 0)
        assertTrue("im dalej od domu, tym mniej czasu do JOKER",
            daleko.doJokeraS!! < blisko.doJokeraS!!)
    }

    /**
     * Test na **liczby bezwzględne**, nie na relacje.
     *
     * Pierwsza wersja tego modułu sprawdzała tylko, czy JOKER wypada przed BINGO i czy
     * rośnie z dystansem. Oba warunki przechodziły, a przyrząd pokazywał na ekranie
     * **„JOKER 61097:33"** — czyli tysiąckrotną pomyłkę jednostek w dwóch miejscach naraz
     * (mAh↔A i A↔mAh/s). Relacje między błędnymi liczbami też się zgadzają.
     */
    @Test
    fun `czasy wychodza w minutach, nie w tysiacach godzin`() {
        // Pakiet 22 500 mAh, zużyte 2100, prąd średni 12 A → zostało 20 400 mAh.
        // Przy 12 A to 20400/12000 h = 1,70 h = 102 min do zera.
        // BINGO stoi na 20 % pojemności (4500 mAh), więc zostaje 15 900 mAh = 79,5 min.
        val stan = StanMaszyny(
            uzbrojony = true,
            zuzycieMah = 2100,
            czasLotuMs = 630_000,          // 10,5 min przy 12 A daje właśnie 2100 mAh
            domUstalony = true,
            szerokosc = 52.0, dlugosc = 20.0, domSzerokosc = 52.0, domDlugosc = 20.0,
            parametry = mapOf("BATT_CAPACITY" to 22500f),
        )
        val b = Energia.policz(stan, teraz = 0)
        assertEquals("prąd średni z licznika", 12f, b.sredniPradA, 0.2f)
        // BINGO: 15 900 mAh przy 3,33 mAh/s = 4770 s = 79,5 min.
        assertEquals(79, b.doBingoS!! / 60)
        // JOKER: rezerwa 35 % = 7875 mAh, zostaje 12 525 mAh = 3758 s = 62,6 min.
        assertEquals(62, b.doJokeraS!! / 60)
    }

    /**
     * Licznik `zuzycieMah` liczy **od podłączenia pakietu**, a zegar lotu od uzbrojenia.
     * Gdy się rozjadą, `zużycie / czas` daje absurd: 2100 mAh przy 13 s to 581 A,
     * a wtedy JOKER i BINGO wypadają natychmiast na 0:00. Zobaczyłem to na ekranie
     * 2026-08-28 — testy na relacjach tego nie łapały.
     */
    @Test
    fun `rozjechany licznik nie psuje czasow`() {
        val stan = StanMaszyny(
            uzbrojony = true,
            zuzycieMah = 2100,          // licznik z poprzedniego lotu
            czasLotuMs = 13_000,        // a w powietrzu dopiero 13 s
            pradA = 12f,                // chwilowy odczyt mówi prawdę
            domUstalony = true,
            szerokosc = 52.0, dlugosc = 20.0, domSzerokosc = 52.0, domDlugosc = 20.0,
            parametry = mapOf("BATT_CAPACITY" to 22500f),
        )
        val b = Energia.policz(stan, teraz = 0)
        assertEquals("odrzucamy 581 A i bierzemy chwilowy odczyt", 12f, b.sredniPradA, 0.5f)
        assertTrue("JOKER nie może wypaść od razu", b.doJokeraS!! > 600)
        assertFalse(b.poJokerze)
    }

    @Test
    fun `sensowna srednia z licznika jest uzywana`() {
        // 2100 mAh w 630 s to 12 A — mieści się w zakresie, więc licznik wygrywa.
        val stan = StanMaszyny(
            uzbrojony = true, zuzycieMah = 2100, czasLotuMs = 630_000, pradA = 99f,
            domUstalony = true,
            szerokosc = 52.0, dlugosc = 20.0, domSzerokosc = 52.0, domDlugosc = 20.0,
            parametry = mapOf("BATT_CAPACITY" to 22500f),
        )
        assertEquals(12f, Energia.policz(stan, teraz = 0).sredniPradA, 0.3f)
    }

    @Test
    fun `formatowanie czasu`() {
        assertEquals("—", Energia.czas(null))
        assertEquals("0:00", Energia.czas(-30))
        assertEquals("3:24", Energia.czas(204))
        assertEquals("12:05", Energia.czas(725))
    }

    // ------------------------------------------------------------------ alarmy

    @Test
    fun `na ziemi zapas ciagu nie alarmuje`() {
        val s = StanMaszyny(
            uzbrojony = false,
            wyjsciaSilnikow = listOf(1950, 1950, 1950, 1950),
            czasWyjsc = 1000,
            czasHeartbeatu = 1000,
        )
        assertTrue(Sygnaly().ocen(s, 1000).none { it.rodzaj == Dzwieki.Rodzaj.ZAPAS_CIAGU })
    }

    @Test
    fun `w locie zanik zapasu alarmuje i rosnie pilnosc`() {
        fun pilnosc(najwyzsze: Int): Float {
            val s = StanMaszyny(
                uzbrojony = true,
                wyjsciaSilnikow = listOf(najwyzsze, 1500, 1500, 1500),
                czasWyjsc = 1000,
                czasHeartbeatu = 1000,
            )
            return Sygnaly().ocen(s, 1000)
                .first { it.rodzaj == Dzwieki.Rodzaj.ZAPAS_CIAGU }.pilnosc
        }
        assertTrue("przy zapasie 50 µs pilnosc rosnie", pilnosc(1900) > pilnosc(1860))
        assertEquals("na suficie pilnosc pelna", 1f, pilnosc(1950), 0.001f)
    }

    @Test
    fun `cisza na laczu alarmuje dopiero gdy lacze kiedys zylo`() {
        val nigdy = StanMaszyny(czasHeartbeatu = 0)
        assertTrue(Sygnaly().ocen(nigdy, 60_000).none { it.rodzaj == Dzwieki.Rodzaj.UTRATA_LACZA })

        val zerwane = StanMaszyny(czasHeartbeatu = 1000)
        assertTrue(Sygnaly().ocen(zerwane, 60_000).any { it.rodzaj == Dzwieki.Rodzaj.UTRATA_LACZA })
    }

    @Test
    fun `JOKER gra raz na przekroczenie, nie w kolko`() {
        val s = stanWLocie(15750, 22500)
        val sygnaly = Sygnaly()
        assertTrue(sygnaly.ocen(s, 1000).any { it.rodzaj == Dzwieki.Rodzaj.JOKER })
        assertTrue("drugie wywołanie w tym samym stanie ma milczeć",
            sygnaly.ocen(s, 2000).none { it.rodzaj == Dzwieki.Rodzaj.JOKER })
    }

    @Test
    fun `rozbrojenie zeruje pamiec przekroczen`() {
        val sygnaly = Sygnaly()
        val wLocie = stanWLocie(15750, 22500)
        assertTrue(sygnaly.ocen(wLocie, 1000).any { it.rodzaj == Dzwieki.Rodzaj.JOKER })
        sygnaly.ocen(wLocie.copy(uzbrojony = false), 2000)
        assertTrue("nowy lot alarmuje od nowa",
            sygnaly.ocen(wLocie, 3000).any { it.rodzaj == Dzwieki.Rodzaj.JOKER })
    }

    @Test
    fun `odpowiedz na komende gra raz`() {
        val komenda = pl.dron15.cockpit.domain.Komenda(
            kod = 20, nazwa = "RTL", czasWyslania = 900,
            wynik = 0, czasOdpowiedzi = 1000,
        )
        val s = StanMaszyny(czasHeartbeatu = 1000, ostatniaKomenda = komenda)
        val sygnaly = Sygnaly()
        assertTrue(sygnaly.ocen(s, 1000).any { it.rodzaj == Dzwieki.Rodzaj.KOMENDA_OK })
        assertTrue(sygnaly.ocen(s, 1100).none { it.rodzaj == Dzwieki.Rodzaj.KOMENDA_OK })
    }

    @Test
    fun `odrzucona komenda ma wlasny dzwiek`() {
        val komenda = pl.dron15.cockpit.domain.Komenda(
            kod = 20, nazwa = "RTL", czasWyslania = 900,
            wynik = 4, czasOdpowiedzi = 1000,
        )
        val s = StanMaszyny(czasHeartbeatu = 1000, ostatniaKomenda = komenda)
        assertTrue(Sygnaly().ocen(s, 1000).any {
            it.rodzaj == Dzwieki.Rodzaj.KOMENDA_ODRZUCONA
        })
    }
}
