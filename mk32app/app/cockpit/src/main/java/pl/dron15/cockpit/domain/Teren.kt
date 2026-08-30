package pl.dron15.cockpit.domain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dane wysokościowe terenu — **rachunki**, bez Androida i bez plików.
 *
 * Źródłem są kafelki **Terrarium** (`elevation-tiles-prod`): zwykłe PNG-i XYZ, w których
 * wysokość siedzi w barwie piksela. Wybrane świadomie, bo mieszczą się w tej samej rurze,
 * co podkład mapy: jedno narzędzie pobiera, jeden katalog na karcie, żadnej nowej biblioteki
 * i żadnego serwera w polu.
 *
 *     h [m n.p.m.] = (R · 256 + G + B / 256) − 32768
 *
 * Rozdzielczość źródła to ok. 30 m (SRTM/ASTER/EU-DEM zależnie od rejonu), więc sensowny
 * poziom powiększenia to **z12** (ok. 24 m/px na 52° szerokości). Wyżej dokłada się tylko
 * wagi pliku, nie szczegółu.
 *
 * ### Czego te dane nie wiedzą
 *
 * To **numeryczny model terenu**, czyli goła ziemia. Nie ma w nim drzew, masztów, linii
 * energetycznych ani budynków. Prześwit liczony niżej jest prześwitem **nad gruntem**
 * i nie zwalnia z patrzenia, co na tym gruncie stoi.
 */
object Teren {

    /** Rozmiar kafelka wysokościowego w pikselach. */
    const val ROZMIAR = 256

    /** Zalecany poziom powiększenia — patrz nagłówek. */
    const val POZIOM_ZALECANY = 12

    /** Terrarium: wysokość zakodowana w barwie piksela. */
    fun dekoduj(r: Int, g: Int, b: Int): Float =
        ((r * 256) + g + (b / 256.0) - 32768.0).toFloat()

    /** To samo dla piksela ARGB, tak jak wychodzi z `Bitmap.getPixels`. */
    fun dekodujArgb(argb: Int): Float =
        dekoduj((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /**
     * Wartość spoza modelu. Terrarium daje −32768 m tam, gdzie nie ma danych; morze ma
     * uczciwe zero, więc odsiewamy tylko wartości bezsensowne.
     */
    fun sensowna(h: Float): Boolean = h > -500f && h < 9000f
}

/**
 * Kwadratowa siatka wysokości wokół punktu, w metrach — **jedyna postać terenu, jakiej
 * używa reszta aplikacji**. Cieniowanie, warstwice, widok przestrzenny i profil trasy
 * liczą się z niej, więc wszystkie pokazują dokładnie ten sam teren.
 *
 * Indeksowanie: `i` rośnie na wschód, `j` rośnie **na północ**. `NaN` znaczy „brak danych",
 * a nie „poziom morza" — rysunek ma o tym mówić, nie zgadywać.
 */
class SiatkaTerenu(
    val bok: Int,
    val srodekLat: Double,
    val srodekLon: Double,
    /** długość boku siatki w metrach */
    val zasiegM: Float,
    val wysokosci: FloatArray,
) {
    /** odległość między węzłami w metrach */
    val krokM: Float get() = if (bok > 1) zasiegM / (bok - 1) else zasiegM

    val pusta: Boolean get() = wysokosci.none { !it.isNaN() }

    fun wezel(i: Int, j: Int): Float {
        if (i < 0 || j < 0 || i >= bok || j >= bok) return Float.NaN
        return wysokosci[j * bok + i]
    }

    val minimum: Float get() = wysokosci.filter { !it.isNaN() }.minOrNull() ?: Float.NaN
    val maksimum: Float get() = wysokosci.filter { !it.isNaN() }.maxOrNull() ?: Float.NaN

    /**
     * Wysokość w punkcie podanym w metrach od środka siatki (wschód, północ),
     * interpolowana dwuliniowo. Poza siatką — `NaN`.
     */
    fun wysokosc(e: Float, n: Float): Float {
        val polowa = zasiegM / 2f
        val fx = (e + polowa) / krokM
        val fy = (n + polowa) / krokM
        val i = floor(fx).toInt()
        val j = floor(fy).toInt()
        if (i < 0 || j < 0 || i >= bok - 1 || j >= bok - 1) return Float.NaN
        val tx = fx - i
        val ty = fy - j
        val a = wezel(i, j)
        val b = wezel(i + 1, j)
        val c = wezel(i, j + 1)
        val d = wezel(i + 1, j + 1)
        if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) return Float.NaN
        val dol = a + (b - a) * tx
        val gora = c + (d - c) * tx
        return dol + (gora - dol) * ty
    }
}

/**
 * Cieniowanie rzeźby liczone z siatki — **to jest ta „trzecia wymiarowość" mapy płaskiej**.
 *
 * Zwykły algorytm oświetlenia zbocza (Horn): nachylenie i wystawa z różnic centralnych,
 * potem cosinus kąta między normalną a kierunkiem światła. Wynik 0..1, gdzie 1 to stok
 * oświetlony wprost.
 *
 * Światło od **północnego zachodu** (315°) i 45° nad widnokręgiem — umowa kartograficzna,
 * przy której oko czyta doliny jako wklęsłe. Odwrócenie kierunku daje złudzenie odwrotne
 * i dlatego nie jest ustawialne.
 */
object Cieniowanie {

    const val AZYMUT_SWIATLA = 315.0
    const val WYSOKOSC_SWIATLA = 45.0

    fun licz(
        siatka: SiatkaTerenu,
        azymutSt: Double = AZYMUT_SWIATLA,
        wysokoscSt: Double = WYSOKOSC_SWIATLA,
        przesada: Float = 1.6f,
    ): FloatArray {
        val n = siatka.bok
        val wynik = FloatArray(n * n) { Float.NaN }
        val zenit = Math.toRadians(90.0 - wysokoscSt)
        val azymut = Math.toRadians(360.0 - azymutSt + 90.0)
        val krok = siatka.krokM * 2f

        for (j in 0 until n) for (i in 0 until n) {
            val srodek = siatka.wezel(i, j)
            if (srodek.isNaN()) continue
            fun h(di: Int, dj: Int): Float {
                val v = siatka.wezel(i + di, j + dj)
                return if (v.isNaN()) srodek else v
            }
            val dzdx = ((h(1, 0) - h(-1, 0)) / krok) * przesada
            // Uwaga na znak: wzór Horna zakłada oś `y` rosnącą **na południe** (kolejność
            // wierszy rastra), a w [SiatkaTerenu] `j` rośnie na północ. Bez tej zamiany
            // cieniowanie wychodziło odbite względem równoleżnika i doliny czytały się
            // jako grzbiety.
            val dzdy = ((h(0, -1) - h(0, 1)) / krok) * przesada
            val nachylenie = atan(sqrt((dzdx * dzdx + dzdy * dzdy).toDouble()))
            val wystawa = if (dzdx != 0f) {
                var a = atan2(dzdy.toDouble(), -dzdx.toDouble())
                if (a < 0) a += 2 * Math.PI
                a
            } else {
                if (dzdy > 0) Math.PI / 2 else if (dzdy < 0) 3 * Math.PI / 2 else 0.0
            }
            val v = cos(zenit) * cos(nachylenie) +
                    sin(zenit) * sin(nachylenie) * cos(azymut - wystawa)
            wynik[j * n + i] = v.coerceIn(0.0, 1.0).toFloat()
        }
        return wynik
    }
}

/** Odcinek warstwicy we współrzędnych **znormalizowanych** siatki (0..1, `y` na północ). */
data class OdcinekWarstwicy(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

data class PoziomWarstwicy(val wysokoscM: Int, val gruba: Boolean, val odcinki: List<OdcinekWarstwicy>)

/**
 * Warstwice liczone z siatki metodą maszerujących kwadratów.
 *
 * Po co, skoro istnieje gotowy podkład topograficzny: **warstwice policzone tutaj kładą się
 * na dowolnym podkładzie, także na zdjęciu lotniczym**. Operator lecący na azymut widzi
 * wtedy naraz to, co jest na ziemi, i to, jak ta ziemia się układa — czego żaden pojedynczy
 * gotowy podkład nie daje.
 *
 * Co piąta warstwica jest gruba (co 100 m przy kroku 20 m) — jak na mapie papierowej.
 */
object Warstwice {

    /** Zabezpieczenie przed rysowaniem tysięcy linii przy dużym zakresie wysokości. */
    const val MAKS_POZIOMOW = 40

    fun licz(siatka: SiatkaTerenu, krokM: Int): List<PoziomWarstwicy> {
        if (krokM <= 0 || siatka.pusta) return emptyList()
        val min = siatka.minimum
        val maks = siatka.maksimum
        if (min.isNaN() || maks.isNaN() || maks - min < krokM) return emptyList()

        val pierwszy = (floor(min / krokM).toInt() + 1) * krokM
        val poziomy = ArrayList<PoziomWarstwicy>()
        var h = pierwszy
        while (h < maks && poziomy.size < MAKS_POZIOMOW) {
            val odcinki = jedenPoziom(siatka, h.toFloat())
            if (odcinki.isNotEmpty()) {
                poziomy += PoziomWarstwicy(h, (h / krokM) % 5 == 0, odcinki)
            }
            h += krokM
        }
        return poziomy
    }

    private fun jedenPoziom(siatka: SiatkaTerenu, poziom: Float): List<OdcinekWarstwicy> {
        val n = siatka.bok
        val odcinki = ArrayList<OdcinekWarstwicy>()
        val skala = 1f / (n - 1)

        for (j in 0 until n - 1) for (i in 0 until n - 1) {
            val a = siatka.wezel(i, j)              // lewy dolny
            val b = siatka.wezel(i + 1, j)          // prawy dolny
            val c = siatka.wezel(i + 1, j + 1)      // prawy górny
            val d = siatka.wezel(i, j + 1)          // lewy górny
            if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) continue

            var kod = 0
            if (a > poziom) kod = kod or 1
            if (b > poziom) kod = kod or 2
            if (c > poziom) kod = kod or 4
            if (d > poziom) kod = kod or 8
            if (kod == 0 || kod == 15) continue

            fun mieszaj(h1: Float, h2: Float): Float {
                val d1 = h2 - h1
                return if (abs(d1) < 1e-6f) 0.5f else ((poziom - h1) / d1).coerceIn(0f, 1f)
            }
            // punkty na krawędziach komórki, we współrzędnych węzłów
            val dol = i + mieszaj(a, b) to j.toFloat()
            val prawo = (i + 1).toFloat() to j + mieszaj(b, c)
            val gora = i + mieszaj(d, c) to (j + 1).toFloat()
            val lewo = i.toFloat() to j + mieszaj(a, d)

            fun dodaj(p: Pair<Float, Float>, q: Pair<Float, Float>) {
                odcinki += OdcinekWarstwicy(
                    p.first * skala, p.second * skala,
                    q.first * skala, q.second * skala,
                )
            }

            when (kod) {
                1, 14 -> dodaj(lewo, dol)
                2, 13 -> dodaj(dol, prawo)
                3, 12 -> dodaj(lewo, prawo)
                4, 11 -> dodaj(prawo, gora)
                6, 9 -> dodaj(dol, gora)
                7, 8 -> dodaj(lewo, gora)
                // przypadki niejednoznaczne — rozstrzygane średnią z czterech węzłów
                5 -> if ((a + b + c + d) / 4f > poziom) {
                    dodaj(lewo, gora); dodaj(dol, prawo)
                } else {
                    dodaj(lewo, dol); dodaj(prawo, gora)
                }
                10 -> if ((a + b + c + d) / 4f > poziom) {
                    dodaj(lewo, dol); dodaj(prawo, gora)
                } else {
                    dodaj(lewo, gora); dodaj(dol, prawo)
                }
            }
        }
        return odcinki
    }
}

// --------------------------------------------------------------------------- profil trasy

data class PunktTrasy(
    val szerokosc: Double,
    val dlugosc: Double,
    /** wysokość zadana **względem punktu startu**, tak jak w misji ArduPilota */
    val wysokoscWzglednaM: Float,
)

data class ProbkaProfilu(
    val dystansM: Float,
    /** teren nad poziomem morza; `NaN` = brak danych */
    val terenM: Float,
    /** zadana wysokość lotu nad poziomem morza */
    val lotM: Float,
    /** prześwit nad gruntem; `NaN` = brak danych */
    val przeswitM: Float,
)

data class ProfilTrasy(
    val probki: List<ProbkaProfilu>,
    /** czy dla całej trasy były dane wysokościowe */
    val kompletny: Boolean,
    val dlugoscM: Float,
    val minPrzeswitM: Float,
    val minPrzeswitDystansM: Float,
    val maksTerenM: Float,
    val minTerenM: Float,
) {
    val pusty: Boolean get() = probki.isEmpty()

    /** Czy trasa wchodzi w ziemię. Nie ostrzeżenie — zderzenie. */
    val kolizja: Boolean get() = !minPrzeswitM.isNaN() && minPrzeswitM <= 0f

    companion object {
        val PUSTY = ProfilTrasy(emptyList(), false, 0f, Float.NaN, 0f, Float.NaN, Float.NaN)

        /**
         * Prześwit, poniżej którego kokpit ostrzega. 30 m to nie przepis, tylko wysokość,
         * przy której drzewo albo maszt przestaje mieścić się w zapasie — a modelu terenu
         * ani jednego, ani drugiego nie zna (patrz nagłówek [Teren]).
         */
        const val PROG_OSTRZEZENIA_M = 30f
    }
}

/**
 * Profil terenu pod trasą.
 *
 * ### Skąd wysokość „zero"
 *
 * Misja ArduPilota niesie wysokości **względem punktu startu**, a model terenu — nad poziomem
 * morza. Punktem styku jest wysokość terenu **w miejscu startu**, wzięta z tego samego modelu.
 * Dzięki temu prześwit jest różnicą dwóch liczb z jednego źródła i **błąd bezwzględny modelu
 * (rzędu kilku metrów) się skraca** — zostaje błąd względny, znacznie mniejszy.
 *
 * Nie używamy tu wysokości barometrycznej z maszyny: przed lotem jej nie ma, a po starcie
 * jest liczona od tego samego punktu startu, więc niczego by nie dołożyła.
 */
object Profil {

    /** Ile próbek na całą trasę — 240 wystarcza na wykres 300 px i nie kosztuje nic. */
    const val PROBEK = 240

    fun licz(
        punkty: List<PunktTrasy>,
        terenDomuM: Float?,
        probek: Int = PROBEK,
        wysokoscTerenu: (Double, Double) -> Float,
    ): ProfilTrasy {
        if (punkty.size < 2 || terenDomuM == null || terenDomuM.isNaN()) return ProfilTrasy.PUSTY

        // długości kolejnych odcinków
        val odcinki = FloatArray(punkty.size - 1)
        for (i in 1 until punkty.size) {
            odcinki[i - 1] = odleglosc(punkty[i - 1], punkty[i])
        }
        val calosc = odcinki.sum()
        if (calosc <= 0f) return ProfilTrasy.PUSTY

        val probki = ArrayList<ProbkaProfilu>(probek)
        var brakujace = 0
        var minPrzeswit = Float.MAX_VALUE
        var minPrzeswitNa = 0f
        var maksTeren = -Float.MAX_VALUE
        var minTeren = Float.MAX_VALUE

        for (k in 0 until probek) {
            val dystans = calosc * k / (probek - 1f)
            val (indeks, t) = naOdcinku(odcinki, dystans)
            val a = punkty[indeks]
            val b = punkty[indeks + 1]
            val lat = a.szerokosc + (b.szerokosc - a.szerokosc) * t
            val lon = a.dlugosc + (b.dlugosc - a.dlugosc) * t
            val lotWzgledny = a.wysokoscWzglednaM + (b.wysokoscWzglednaM - a.wysokoscWzglednaM) * t
            val lotAmsl = terenDomuM + lotWzgledny

            val teren = wysokoscTerenu(lat, lon)
            val przeswit = if (teren.isNaN()) Float.NaN else lotAmsl - teren
            if (teren.isNaN()) brakujace++ else {
                maksTeren = max(maksTeren, teren)
                minTeren = min(minTeren, teren)
                if (przeswit < minPrzeswit) {
                    minPrzeswit = przeswit
                    minPrzeswitNa = dystans
                }
            }
            probki += ProbkaProfilu(dystans, teren, lotAmsl, przeswit)
        }

        return ProfilTrasy(
            probki = probki,
            kompletny = brakujace == 0,
            dlugoscM = calosc,
            minPrzeswitM = if (minPrzeswit == Float.MAX_VALUE) Float.NaN else minPrzeswit,
            minPrzeswitDystansM = minPrzeswitNa,
            maksTerenM = if (maksTeren == -Float.MAX_VALUE) Float.NaN else maksTeren,
            minTerenM = if (minTeren == Float.MAX_VALUE) Float.NaN else minTeren,
        )
    }

    /**
     * Trasa do profilu: **punkt startu z przodu, na wysokości pierwszego punktu**.
     *
     * Dom musi być w profilu, bo dolot do pierwszego punktu też przechodzi nad terenem.
     * Ale nie na wysokości zero: wielowirnikowiec wznosi się nad punktem startu pionowo
     * (`NAV_TAKEOFF`, a bez niego i tak start z ziemi), więc odcinek dom → punkt 1 pokonuje
     * **już na wysokości przelotowej**.
     *
     * Dosłowne wpisanie zera dawało prześwit 0 w pierwszej próbce każdej trasy, czyli
     * **kolizję z terenem meldowaną zawsze** — niezależnie od tego, gdzie ta trasa biegła.
     */
    fun trasaZDomu(
        domSzerokosc: Double,
        domDlugosc: Double,
        punkty: List<PunktTrasy>,
    ): List<PunktTrasy> {
        if (punkty.isEmpty()) return emptyList()
        return listOf(PunktTrasy(domSzerokosc, domDlugosc, punkty.first().wysokoscWzglednaM)) + punkty
    }

    /** Który odcinek i jak głęboko w nim leży dana odległość od początku trasy. */
    private fun naOdcinku(odcinki: FloatArray, dystans: Float): Pair<Int, Float> {
        var pozostalo = dystans
        for (i in odcinki.indices) {
            if (pozostalo <= odcinki[i] || i == odcinki.size - 1) {
                val t = if (odcinki[i] <= 0f) 0f else (pozostalo / odcinki[i]).coerceIn(0f, 1f)
                return i to t
            }
            pozostalo -= odcinki[i]
        }
        return 0 to 0f
    }

    private fun odleglosc(a: PunktTrasy, b: PunktTrasy): Float {
        val dn = (b.szerokosc - a.szerokosc) * Wspolrzedne.METRY_NA_STOPIEN
        val de = (b.dlugosc - a.dlugosc) * Wspolrzedne.METRY_NA_STOPIEN *
                cos(Math.toRadians(a.szerokosc))
        return hypot(dn, de).toFloat()
    }
}

// --------------------------------------------------------------------------- azymut

/**
 * Azymut i odległość — **liczone geograficznie, nie magnetycznie**.
 *
 * Ta maszyna nie ma kompasu (`COMPASS_USE=0`), a kurs bierze z bazy GNSS
 * (`EK3_SRC1_YAW=2`), czyli **kurs rzeczywisty względem północy geograficznej**.
 * Wszystkie azymuty w kokpicie są więc geograficzne. Operator odczytujący kierunek
 * z busoli w terenie musi doliczyć deklinację (w Polsce ok. +6°E) — kokpit tego nie robi,
 * bo nie zna miejsca ani daty pomiaru busolą.
 */
object Azymut {

    /** Azymut z punktu A do B w stopniach 0..360, gdzie 0 = północ. */
    fun miedzy(latA: Double, lonA: Double, latB: Double, lonB: Double): Float {
        val dn = (latB - latA) * Wspolrzedne.METRY_NA_STOPIEN
        val de = (lonB - lonA) * Wspolrzedne.METRY_NA_STOPIEN * cos(Math.toRadians(latA))
        var a = Math.toDegrees(atan2(de, dn)).toFloat()
        if (a < 0) a += 360f
        return a
    }

    /** Azymut z przesunięcia metrycznego (wschód, północ). */
    fun zPrzesuniecia(e: Float, n: Float): Float {
        var a = Math.toDegrees(atan2(e.toDouble(), n.toDouble())).toFloat()
        if (a < 0) a += 360f
        return a
    }

    fun opis(st: Float): String = "%03.0f°".format(st)

    /** Kierunek świata — szesnastka róży, do podpisu przy azymucie. */
    fun roza(st: Float): String {
        val nazwy = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val i = (((st % 360f) + 360f) % 360f / 22.5f + 0.5f).toInt() % 16
        return nazwy[i]
    }
}

// --------------------------------------------------------------------------- rzut 3D

/** Punkt po rzucie: położenie na ekranie i głębia (odległość od obserwatora wzdłuż osi patrzenia). */
data class PunktRzutu(val x: Float, val y: Float, val glebia: Float) {
    val widoczny: Boolean get() = glebia > 1f
}

/**
 * Rzut perspektywiczny dla widoku przestrzennego terenu.
 *
 * Kamera patrzy w środek siatki z odległości [dystansM], obrócona o [azymutSt] i pochylona
 * o [pochylenieSt] nad poziom (0° = dokładnie z boku, 90° = pionowo z góry). Pion jest
 * **przesadzony** ([przesadaPionowa]) — bez tego rzeźba niskiego terenu, po jakim ta maszyna
 * lata, jest na ekranie niewidoczna, a to ona jest treścią tego widoku.
 *
 * Świadomie własne pięć linii zamiast biblioteki 3D: rzutujemy jedną siatkę i jedną trasę,
 * a MK32 ma Androida 9 i pracuje w polu — każda zależność, która nie musi tam być, nie jedzie.
 */
class Rzut3D(
    val szerokoscPx: Float,
    val wysokoscPx: Float,
    val azymutSt: Float,
    val pochylenieSt: Float,
    val dystansM: Float,
    val wysokoscOdniesieniaM: Float,
    val przesadaPionowa: Float = 2.0f,
    val poleWidzeniaSt: Float = 55f,
) {
    private val a = Math.toRadians(azymutSt.toDouble())
    private val t = Math.toRadians(pochylenieSt.toDouble().coerceIn(5.0, 89.0))
    private val ogniskowa = (wysokoscPx / 2f) / kotlin.math.tan(Math.toRadians(poleWidzeniaSt / 2.0)).toFloat()

    /**
     * @param e metry na wschód od środka siatki
     * @param n metry na północ od środka siatki
     * @param h wysokość n.p.m.
     */
    fun rzutuj(e: Float, n: Float, h: Float): PunktRzutu {
        val cosA = cos(a).toFloat()
        val sinA = sin(a).toFloat()
        // obrót o azymut: po obrocie „w głąb ekranu" idzie oś y
        val xr = e * cosA - n * sinA
        val yr = e * sinA + n * cosA
        val dz = (h - wysokoscOdniesieniaM) * przesadaPionowa

        val cosT = cos(t).toFloat()
        val sinT = sin(t).toFloat()
        val glebia = yr * cosT - dz * sinT + dystansM
        if (glebia <= 1f) return PunktRzutu(Float.NaN, Float.NaN, glebia)
        val gora = (yr + dystansM * cosT) * sinT + (dz - dystansM * sinT) * cosT

        return PunktRzutu(
            x = szerokoscPx / 2f + ogniskowa * xr / glebia,
            y = wysokoscPx / 2f - ogniskowa * gora / glebia,
            glebia = glebia,
        )
    }

}
