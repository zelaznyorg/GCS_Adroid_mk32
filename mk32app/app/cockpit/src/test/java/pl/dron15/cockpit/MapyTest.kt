package pl.dron15.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import pl.dron15.cockpit.domain.Azymut
import pl.dron15.cockpit.domain.Cieniowanie
import pl.dron15.cockpit.domain.Profil
import pl.dron15.cockpit.domain.ProfilTrasy
import pl.dron15.cockpit.domain.PunktTrasy
import pl.dron15.cockpit.domain.Rzut3D
import pl.dron15.cockpit.domain.SiatkaTerenu
import pl.dron15.cockpit.domain.Teren
import pl.dron15.cockpit.domain.Warstwice
import pl.dron15.cockpit.ui.Kafelki
import pl.dron15.cockpit.ui.Podklady
import pl.dron15.cockpit.ui.UstawieniaMapy
import pl.dron15.cockpit.ui.Zasieg
import pl.dron15.cockpit.ui.Zrodla

/**
 * Rachunki map i terenu — **wszystko bez Androida i bez karty**.
 *
 * Wartości odniesienia nie są wzięte z tej implementacji: kafelek `12/2264/1366` sprawdzono
 * wobec żywego serwera `elevation-tiles-prod` (2026-08-25, teren 165–191 m n.p.m. w rejonie
 * 19,0° E / 51,26° N), a wzór Terrarium pochodzi z opisu formatu, nie z kodu kokpitu.
 */
class MapyTest {

    // ------------------------------------------------------------------ Terrarium

    @Test
    fun `dekodowanie Terrarium zgadza sie ze wzorem formatu`() {
        // (R·256 + G + B/256) − 32768
        assertEquals(-32768f, Teren.dekoduj(0, 0, 0), 0.001f)
        assertEquals(0f, Teren.dekoduj(128, 0, 0), 0.001f)
        assertEquals(1f, Teren.dekoduj(128, 1, 0), 0.001f)
        assertEquals(0.5f, Teren.dekoduj(128, 0, 128), 0.001f)
        // wartość z prawdziwego kafelka: R=128 G=173 → 173 m n.p.m.
        assertEquals(173f, Teren.dekoduj(128, 173, 0), 0.001f)
    }

    @Test
    fun `dekodowanie z piksela ARGB pomija kanal krycia`() {
        val argb = (0xFF shl 24) or (128 shl 16) or (173 shl 8) or 0
        assertEquals(173f, Teren.dekodujArgb(argb), 0.001f)
    }

    @Test
    fun `wartosci spoza modelu odsiewane`() {
        assertFalse(Teren.sensowna(-32768f))
        assertTrue(Teren.sensowna(0f))          // morze to nie brak danych
        assertTrue(Teren.sensowna(2499f))
        assertFalse(Teren.sensowna(12000f))
    }

    // ------------------------------------------------------------------ odwzorowanie

    @Test
    fun `kafelek XYZ zgadza sie z numeracja OpenStreetMap`() {
        // Kafelek 12/2264/1366 obejmuje 18,984–19,072 E i 51,234–51,289 N. To ten sam
        // kafelek, którego zawartość sprawdzono na serwerze wysokościowym (165–191 m n.p.m.).
        val z = 12
        val x = (Kafelki.swiatX(19.05, z) / Kafelki.ROZMIAR).toInt()
        val y = (Kafelki.swiatY(51.26, z) / Kafelki.ROZMIAR).toInt()
        assertEquals(2264, x)
        assertEquals(1366, y)
    }

    @Test
    fun `metry na piksel maleja dwukrotnie na kazdym poziomie`() {
        val a = Kafelki.metryNaPiksel(52.0, 12)
        val b = Kafelki.metryNaPiksel(52.0, 13)
        assertEquals(a / 2f, b, 0.0001f)
        // z12 na 52° to ok. 23,5 m/px — stąd wybór poziomu dla modelu 30-metrowego
        assertTrue(a > 22f && a < 25f)
    }

    @Test
    fun `poziom dobrany do skali wraca do tej samej skali`() {
        val poziom = Kafelki.poziomDla(23.5f, 52.0)
        assertEquals(12, poziom)
    }

    // ------------------------------------------------------------------ siatka terenu

    private fun siatkaPochyla(bok: Int, spadekNaMetr: Float, zasiegM: Float): SiatkaTerenu {
        val krok = zasiegM / (bok - 1)
        val h = FloatArray(bok * bok)
        for (j in 0 until bok) for (i in 0 until bok) {
            h[j * bok + i] = 100f + i * krok * spadekNaMetr
        }
        return SiatkaTerenu(bok, 52.0, 20.0, zasiegM, h)
    }

    @Test
    fun `interpolacja w siatce jest dwuliniowa`() {
        val s = siatkaPochyla(5, 0.1f, 400f)     // 100 m na zachodzie, 140 m na wschodzie
        assertEquals(120f, s.wysokosc(0f, 0f), 0.01f)
        assertEquals(100f, s.wysokosc(-200f, -200f), 0.01f)
        assertEquals(140f, s.wysokosc(199.9f, 0f), 0.5f)
        assertEquals(130f, s.wysokosc(100f, 50f), 0.01f)
        assertTrue(s.wysokosc(5000f, 0f).isNaN())   // poza siatką: brak danych, nie zero
    }

    @Test
    fun `siatka podaje zakres wysokosci`() {
        val s = siatkaPochyla(9, 0.05f, 800f)
        assertEquals(100f, s.minimum, 0.01f)
        assertEquals(140f, s.maksimum, 0.01f)
    }

    // ------------------------------------------------------------------ cieniowanie

    @Test
    fun `plaski teren daje jednolite cieniowanie`() {
        val bok = 9
        val s = SiatkaTerenu(bok, 52.0, 20.0, 800f, FloatArray(bok * bok) { 120f })
        val j = Cieniowanie.licz(s)
        val oczekiwane = Math.cos(Math.toRadians(45.0)).toFloat()   // zenit 45°
        j.forEach { assertEquals(oczekiwane, it, 0.001f) }
    }

    /**
     * Zbocze opadające w zadanym kierunku. `naWschod` = teren rośnie ku zachodowi,
     * czyli powierzchnia jest zwrócona (wystawiona) na wschód.
     */
    private fun jasnoscZbocza(wystawa: String): Float {
        val bok = 9
        val zasieg = 800f
        val krok = zasieg / (bok - 1)
        val h = FloatArray(bok * bok)
        for (j in 0 until bok) for (i in 0 until bok) {
            h[j * bok + i] = 100f + krok * 0.2f * when (wystawa) {
                "wschod" -> (bok - 1 - i).toFloat()
                "zachod" -> i.toFloat()
                "polnoc" -> (bok - 1 - j).toFloat()
                else -> j.toFloat()               // południe
            }
        }
        val srodek = bok / 2 * bok + bok / 2
        return Cieniowanie.licz(SiatkaTerenu(bok, 52.0, 20.0, zasieg, h))[srodek]
    }

    @Test
    fun `swiatlo pada z polnocnego zachodu`() {
        // Umowa kartograficzna: światło z 315°, więc zbocza zwrócone na zachód i na północ
        // są jasne, a wschodnie i południowe — ciemne. Odwrotny znak którejkolwiek osi
        // odbija rzeźbę i doliny zaczynają czytać się jako grzbiety.
        assertTrue(
            "zbocze zachodnie ma być jaśniejsze od wschodniego",
            jasnoscZbocza("zachod") > jasnoscZbocza("wschod"),
        )
        assertTrue(
            "zbocze północne ma być jaśniejsze od południowego",
            jasnoscZbocza("polnoc") > jasnoscZbocza("poludnie"),
        )
        // symetria: zachód i północ leżą tak samo względem światła z 315°
        assertEquals(jasnoscZbocza("zachod"), jasnoscZbocza("polnoc"), 0.001f)
    }

    // ------------------------------------------------------------------ warstwice

    @Test
    fun `warstwice na rownym zboczu leza co zadany krok`() {
        val s = siatkaPochyla(33, 0.1f, 800f)      // 100 → 180 m
        val poziomy = Warstwice.licz(s, 20)
        assertEquals(listOf(120, 140, 160), poziomy.map { it.wysokoscM })
        assertTrue(poziomy.all { it.odcinki.isNotEmpty() })
        // co piąta gruba: 100, 200… — w tym zakresie żadna
        assertTrue(poziomy.none { it.gruba })
    }

    @Test
    fun `co piata warstwica jest gruba`() {
        val s = siatkaPochyla(33, 0.5f, 800f)      // 100 → 500 m
        val poziomy = Warstwice.licz(s, 20)
        val grube = poziomy.filter { it.gruba }.map { it.wysokoscM }
        assertEquals(listOf(200, 300, 400), grube)
    }

    @Test
    fun `teren plaski nie ma warstwic`() {
        val bok = 9
        val s = SiatkaTerenu(bok, 52.0, 20.0, 800f, FloatArray(bok * bok) { 120f })
        assertTrue(Warstwice.licz(s, 20).isEmpty())
    }

    @Test
    fun `warstwica lezy tam gdzie teren osiaga jej wysokosc`() {
        val s = siatkaPochyla(33, 0.1f, 800f)      // 100 m na x=0, 180 m na x=1
        val poziom = Warstwice.licz(s, 20).first { it.wysokoscM == 120 }
        // 120 m wypada w 1/4 szerokości siatki
        poziom.odcinki.forEach {
            assertEquals(0.25f, it.x1, 0.02f)
            assertEquals(0.25f, it.x2, 0.02f)
        }
    }

    // ------------------------------------------------------------------ profil trasy

    /** Teren rosnący ku wschodowi: 100 m n.p.m. na 20,000° E, +1 m na każde 0,0001°. */
    private fun terenRosnacy(lon: Double): Float =
        (100.0 + (lon - 20.0) * 10000.0).toFloat()

    @Test
    fun `nad plaskim terenem przeswit rowna sie zadanej wysokosci`() {
        val punkty = listOf(
            PunktTrasy(52.0, 20.0, 0f),
            PunktTrasy(52.0, 20.01, 100f),
        )
        val profil = Profil.licz(punkty, 150f, 50) { _, _ -> 150f }
        assertTrue(profil.kompletny)
        assertEquals(0f, profil.probki.first().przeswitM, 0.01f)
        assertEquals(100f, profil.probki.last().przeswitM, 0.01f)
        assertEquals(0f, profil.minPrzeswitM, 0.01f)
    }

    @Test
    fun `wznoszacy sie teren zjada przeswit i konczy sie kolizja`() {
        val punkty = listOf(
            PunktTrasy(52.0, 20.0, 50f),
            PunktTrasy(52.0, 20.02, 50f),          // teren rośnie o 200 m na tej długości
        )
        val profil = Profil.licz(punkty, 100f, 100) { _, lon -> terenRosnacy(lon) }
        assertTrue("trasa musi wejść w zbocze", profil.kolizja)
        assertTrue(profil.minPrzeswitM < 0f)
        // najmniejszy prześwit na końcu trasy
        assertTrue(profil.minPrzeswitDystansM > profil.dlugoscM * 0.9f)
        assertEquals(300f, profil.maksTerenM, 1f)
    }

    @Test
    fun `wysokosc misji liczy sie od terenu w miejscu startu`() {
        // dom leży 200 m n.p.m., punkt zadany na 80 m nad startem, teren pod punktem 210 m
        val punkty = listOf(
            PunktTrasy(52.0, 20.0, 80f),
            PunktTrasy(52.0, 20.001, 80f),
        )
        val profil = Profil.licz(punkty, 200f, 10) { _, _ -> 210f }
        assertEquals(280f, profil.probki.first().lotM, 0.01f)
        assertEquals(70f, profil.probki.first().przeswitM, 0.01f)
    }

    @Test
    fun `brak danych wysokosciowych daje pusty profil zamiast zerowego`() {
        val punkty = listOf(PunktTrasy(52.0, 20.0, 50f), PunktTrasy(52.0, 20.01, 50f))
        assertTrue(Profil.licz(punkty, null, 10) { _, _ -> Float.NaN }.pusty)
    }

    @Test
    fun `luka w modelu nie unieważnia calego profilu`() {
        val punkty = listOf(PunktTrasy(52.0, 20.0, 50f), PunktTrasy(52.0, 20.01, 50f))
        val profil = Profil.licz(punkty, 100f, 20) { _, lon ->
            if (lon > 20.004 && lon < 20.006) Float.NaN else 100f
        }
        assertFalse(profil.kompletny)
        assertFalse(profil.pusty)
        assertEquals(50f, profil.minPrzeswitM, 0.01f)
    }

    @Test
    fun `trasa z domu zaczyna sie na wysokosci pierwszego punktu`() {
        // Wielowirnikowiec wznosi sie nad punktem startu pionowo, wiec odcinek dom -> punkt 1
        // pokonuje juz na wysokosci przelotowej. Dom na wysokosci zero dawalby przeswit 0
        // w pierwszej probce KAZDEJ trasy, czyli kolizje meldowana zawsze.
        val trasa = Profil.trasaZDomu(52.0, 20.0, listOf(
            PunktTrasy(52.0, 20.01, 80f),
            PunktTrasy(52.0, 20.02, 120f),
        ))
        assertEquals(3, trasa.size)
        assertEquals(52.0, trasa[0].szerokosc, 1e-9)
        assertEquals(80f, trasa[0].wysokoscWzglednaM, 0.001f)
    }

    @Test
    fun `pusta misja nie tworzy trasy z samym domem`() {
        assertTrue(Profil.trasaZDomu(52.0, 20.0, emptyList()).isEmpty())
    }

    @Test
    fun `trasa z domu nad plaskim terenem nie melduje kolizji`() {
        val trasa = Profil.trasaZDomu(52.0, 20.0, listOf(
            PunktTrasy(52.0, 20.01, 80f),
            PunktTrasy(52.0, 20.02, 80f),
        ))
        val profil = Profil.licz(trasa, 150f, 60) { _, _ -> 150f }
        assertFalse("nad rownym terenem nie ma kolizji", profil.kolizja)
        assertEquals(80f, profil.minPrzeswitM, 0.01f)
    }

    @Test
    fun `prog ostrzegawczy to 30 metrow nad gruntem`() {
        assertEquals(30f, ProfilTrasy.PROG_OSTRZEZENIA_M, 0.001f)
    }

    // ------------------------------------------------------------------ azymut

    @Test
    fun `azymut liczy sie od polnocy zgodnie ze wskazowkami zegara`() {
        assertEquals(0f, Azymut.miedzy(52.0, 20.0, 52.01, 20.0), 0.1f)
        assertEquals(90f, Azymut.miedzy(52.0, 20.0, 52.0, 20.01), 0.1f)
        assertEquals(180f, Azymut.miedzy(52.0, 20.0, 51.99, 20.0), 0.1f)
        assertEquals(270f, Azymut.miedzy(52.0, 20.0, 52.0, 19.99), 0.1f)
    }

    @Test
    fun `roza wiatrow nazywa kierunki`() {
        assertEquals("N", Azymut.roza(0f))
        assertEquals("NE", Azymut.roza(45f))
        assertEquals("E", Azymut.roza(90f))
        assertEquals("SW", Azymut.roza(225f))
        assertEquals("N", Azymut.roza(359f))
    }

    @Test
    fun `azymut zawsze w zakresie 0-360`() {
        assertEquals(315f, Azymut.zPrzesuniecia(-100f, 100f), 0.1f)
        assertEquals(135f, Azymut.zPrzesuniecia(100f, -100f), 0.1f)
    }

    // ------------------------------------------------------------------ rzut 3D

    @Test
    fun `patrzac pionowo z gory polnoc jest u gory a wschod z prawej`() {
        val r = Rzut3D(800f, 600f, azymutSt = 0f, pochylenieSt = 89f,
            dystansM = 1000f, wysokoscOdniesieniaM = 100f)
        val srodek = r.rzutuj(0f, 0f, 100f)
        val polnoc = r.rzutuj(0f, 300f, 100f)
        val wschod = r.rzutuj(300f, 0f, 100f)
        assertTrue(polnoc.widoczny && wschod.widoczny)
        assertTrue("północ ma być wyżej na ekranie", polnoc.y < srodek.y)
        assertTrue("wschód ma być z prawej", wschod.x > srodek.x)
        assertEquals(srodek.x, polnoc.x, 0.5f)
    }

    @Test
    fun `obrot o 90 stopni zamienia polnoc na bok ekranu`() {
        val r = Rzut3D(800f, 600f, azymutSt = 90f, pochylenieSt = 89f,
            dystansM = 1000f, wysokoscOdniesieniaM = 100f)
        val srodek = r.rzutuj(0f, 0f, 100f)
        val polnoc = r.rzutuj(0f, 300f, 100f)
        assertTrue("po obrocie północ idzie w bok", polnoc.x < srodek.x)
        assertEquals(srodek.y, polnoc.y, 1f)
    }

    @Test
    fun `wyzszy punkt rysuje sie wyzej`() {
        val r = Rzut3D(800f, 600f, azymutSt = 0f, pochylenieSt = 45f,
            dystansM = 1000f, wysokoscOdniesieniaM = 100f)
        val nisko = r.rzutuj(0f, 0f, 100f)
        val wysoko = r.rzutuj(0f, 0f, 200f)
        assertTrue(wysoko.y < nisko.y)
    }

    @Test
    fun `przesada pionowa zwieksza roznice wysokosci na ekranie`() {
        fun roznica(przesada: Float): Float {
            val r = Rzut3D(800f, 600f, 0f, 45f, 1000f, 100f, przesadaPionowa = przesada)
            return r.rzutuj(0f, 0f, 100f).y - r.rzutuj(0f, 0f, 200f).y
        }
        assertTrue(roznica(3f) > roznica(1f) * 2.5f)
    }

    @Test
    fun `punkt za obserwatorem jest niewidoczny`() {
        val r = Rzut3D(800f, 600f, azymutSt = 0f, pochylenieSt = 20f,
            dystansM = 100f, wysokoscOdniesieniaM = 100f)
        assertFalse(r.rzutuj(0f, -500f, 100f).widoczny)
    }

    // ------------------------------------------------------------------ zasięg mapy

    @Test
    fun `klawisze chodza po szczeblach drabiny`() {
        assertEquals(300f, Zasieg.blizej(400f), 0.01f)
        assertEquals(600f, Zasieg.dalej(400f), 0.01f)
        assertEquals(1000f, Zasieg.dalej(600f), 0.01f)
        assertEquals(2500f, Zasieg.blizej(4000f), 0.01f)
    }

    @Test
    fun `drabina nie wychodzi poza swoje konce`() {
        assertEquals(Zasieg.MIN, Zasieg.blizej(Zasieg.MIN), 0.01f)
        assertEquals(Zasieg.MAKS, Zasieg.dalej(Zasieg.MAKS), 0.01f)
        assertEquals(Zasieg.MIN, Zasieg.blizej(10f), 0.01f)
    }

    @Test
    fun `po szczypnieciu klawisz zaokragla do szczebla`() {
        // Zoom płynny zostawia 470 m; „+" ma dać 400, a nie 300 — czyli najpierw wrócić
        // na drabinę, a dopiero potem przesunąć się o szczebel.
        assertEquals(400f, Zasieg.blizej(470f), 0.01f)
        assertEquals(600f, Zasieg.dalej(470f), 0.01f)
        assertEquals(400f, Zasieg.blizej(550f), 0.01f)
        assertEquals(600f, Zasieg.dalej(550f), 0.01f)
    }

    @Test
    fun `szczypniecie zmienia zasieg plynnie i w granicach`() {
        assertEquals(200f, Zasieg.plynnie(400f, 2f), 0.01f)      // dwa palce w rozsuw = bliżej
        assertEquals(800f, Zasieg.plynnie(400f, 0.5f), 0.01f)
        assertEquals(Zasieg.MIN, Zasieg.plynnie(60f, 100f), 0.01f)
        assertEquals(Zasieg.MAKS, Zasieg.plynnie(19000f, 0.01f), 0.01f)
    }

    @Test
    fun `gorny szczebel siega 20 km — tyle trzeba, zeby zobaczyc rzezbe`() {
        assertEquals(20_000f, Zasieg.MAKS, 0.01f)
        assertEquals(20_000f, Zasieg.DRABINA.last(), 0.01f)
    }

    @Test
    fun `opis zasiegu po polsku`() {
        assertEquals("400 m", Zasieg.opis(400f))
        assertEquals("1 km", Zasieg.opis(1000f))
        assertEquals("2,5 km", Zasieg.opis(2500f))
        assertEquals("10 km", Zasieg.opis(10_000f))
        assertEquals("20 km", Zasieg.opis(20_000f))
    }

    // ------------------------------------------------------------------ źródła z sieci

    @Test
    fun `adres kafelka podstawia z x y`() {
        assertEquals(
            "https://a.tile.opentopomap.org/12/2264/1366.png",
            Zrodla.adres(Zrodla.WARSTWY.getValue("topo"), 12, 2264, 1366),
        )
    }

    @Test
    fun `Esri ma odwrotna kolejnosc y i x`() {
        // Sprawdzone wobec żywego serwera 2026-08-25: ten adres zwraca kafelek 12/2264/1366.
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/" +
                    "MapServer/tile/12/1366/2264",
            Zrodla.adres(Zrodla.WARSTWY.getValue("zdjecia"), 12, 2264, 1366),
        )
    }

    @Test
    fun `kazda warstwa podkladow ma zrodlo w sieci`() {
        val potrzebne = Podklady.wszystkie.flatMap { it.katalogi }.distinct()
        potrzebne.forEach {
            assertTrue("brak adresu dla warstwy $it", Zrodla.ma(it))
        }
    }

    @Test
    fun `adres terenu prowadzi do kafelkow Terrarium`() {
        val adres = Zrodla.adres(Zrodla.TEREN, 12, 2264, 1366)
        assertTrue(adres.contains("terrarium"))
        assertTrue(adres.endsWith("/12/2264/1366.png"))
    }

    @Test
    fun `mapa domyslnie dociaga sie z internetu`() {
        assertTrue(UstawieniaMapy().zInternetu)
    }

    // ------------------------------------------------------------------ podkłady

    @Test
    fun `hybryda jest podkladem domyslnym i wymaganym`() {
        assertEquals(Podklady.HYBRYDA, Podklady.domyslny)
        assertTrue(Podklady.HYBRYDA.wymagany)
        assertEquals(1, Podklady.wszystkie.count { it.wymagany })
        assertEquals(Podklady.HYBRYDA.id, UstawieniaMapy().podklad)
    }

    @Test
    fun `hybryda to zdjecie z nazwami i drogami na wierzchu`() {
        assertEquals(listOf("zdjecia", "opisy", "drogi"), Podklady.HYBRYDA.katalogi)
        assertEquals(listOf("zdjecia"), Podklady.ZDJECIA.katalogi)
    }

    @Test
    fun `nieznany podklad wraca do domyslnego zamiast wysypywac mape`() {
        assertEquals(Podklady.HYBRYDA, Podklady.poId("czegos-takiego-nie-ma"))
        assertEquals(Podklady.HYBRYDA, Podklady.poId(null))
        assertEquals(Podklady.TOPO, Podklady.poId("topo"))
    }

    @Test
    fun `zdjecia sa przyciemniane mocniej niz mapa kreskowa`() {
        assertTrue(Podklady.HYBRYDA.przyciemnienie > Podklady.MAPA.przyciemnienie)
    }
}
