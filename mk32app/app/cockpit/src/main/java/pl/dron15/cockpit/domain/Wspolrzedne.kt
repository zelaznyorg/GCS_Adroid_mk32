package pl.dron15.cockpit.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Współrzędne — trzy formaty w obie strony (`dok/PRZEKAZANIE_M3.md` §4 i §5).
 *
 * | Format | Przykład | Po co |
 * |---|---|---|
 * | dziesiętne | `52.23412 N 21.00871 E` | wpisanie w mapę, wymiana z QGC |
 * | MGRS | `34U EC 12345 67890` | podanie przez radio — krótkie i odporne na przekręcenie |
 * | stopnie-minuty-sekundy | `52°14'02.8" N 21°00'31.4" E` | mapy papierowe i lotnicze |
 *
 * Wszystko liczone **lokalnie, bez sieci** — aparatura jej nie ma. To dlatego wyszukiwanie
 * po współrzędnych może wejść pierwsze, a adresy i POI czekają na dane offline (§5).
 *
 * Elipsoida WGS84, odwzorowanie poprzeczne Merkatora wg wzorów szeregowych USGS PP 1395
 * (te same, których używa GeoTrans). Dokładność rzędu centymetrów w pasie UTM — grubo
 * poniżej błędu pozycji z GNSS, więc konwersja niczego nie psuje.
 */
object Wspolrzedne {

    data class Pozycja(val szerokosc: Double, val dlugosc: Double)

    // ------------------------------------------------------------------ formaty

    fun dziesietne(lat: Double, lon: Double): String =
        "%.5f %s  %.5f %s".format(abs(lat), if (lat >= 0) "N" else "S",
            abs(lon), if (lon >= 0) "E" else "W")

    fun dms(lat: Double, lon: Double): String =
        "${dmsOs(lat, "N", "S")}  ${dmsOs(lon, "E", "W")}"

    private fun dmsOs(v: Double, dodatni: String, ujemny: String): String {
        val znak = if (v >= 0) dodatni else ujemny
        var reszta = abs(v)
        val st = floor(reszta).toInt()
        reszta = (reszta - st) * 60
        val min = floor(reszta).toInt()
        val sek = (reszta - min) * 60
        return "%d°%02d'%04.1f\" %s".format(st, min, sek, znak)
    }

    // ------------------------------------------------------------------ MGRS

    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)
    private const val EP2 = E2 / (1 - E2)
    private const val K0 = 0.9996

    /** Pasy szerokości MGRS, po 8° od −80°; `X` jest wyjątkowo szeroki (72…84°). */
    private const val PASY = "CDEFGHJKLMNPQRSTUVWX"

    private val KOLUMNY = listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val WIERSZE = "ABCDEFGHJKLMNPQRSTUV"

    fun strefa(lat: Double, lon: Double): Int {
        var s = (floor((lon + 180) / 6) + 1).toInt().coerceIn(1, 60)
        // Dwa wyjątki ze standardu: południowo-zachodnia Norwegia i Svalbard.
        if (lat in 56.0..64.0 && lon in 3.0..12.0) s = 32
        if (lat in 72.0..84.0) {
            when {
                lon in 0.0..9.0 -> s = 31
                lon in 9.0..21.0 -> s = 33
                lon in 21.0..33.0 -> s = 35
                lon in 33.0..42.0 -> s = 37
            }
        }
        return s
    }

    fun pas(lat: Double): Char {
        if (lat < -80 || lat > 84) return '?'
        val i = ((lat + 80) / 8).toInt().coerceIn(0, PASY.length - 1)
        return PASY[i]
    }

    /** Zwraca `easting` i `northing` w metrach dla podanej strefy. */
    private fun doUtm(lat: Double, lon: Double, strefa: Int): Pair<Double, Double> {
        val fi = Math.toRadians(lat)
        val lambda = Math.toRadians(lon)
        val lambda0 = Math.toRadians((strefa - 1) * 6.0 - 180.0 + 3.0)

        val n = A / sqrt(1 - E2 * sin(fi).pow(2))
        val t = tan(fi).pow(2)
        val c = EP2 * cos(fi).pow(2)
        val a1 = cos(fi) * (lambda - lambda0)
        val m = A * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256) * fi -
                (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2.pow(3) / 1024) * sin(2 * fi) +
                (15 * E2 * E2 / 256 + 45 * E2.pow(3) / 1024) * sin(4 * fi) -
                (35 * E2.pow(3) / 3072) * sin(6 * fi))

        val e = K0 * n * (a1 + (1 - t + c) * a1.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a1.pow(5) / 120) + 500000.0
        var north = K0 * (m + n * tan(fi) * (a1 * a1 / 2 +
                (5 - t + 9 * c + 4 * c * c) * a1.pow(4) / 24 +
                (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a1.pow(6) / 720))
        if (lat < 0) north += 10_000_000.0
        return e to north
    }

    private fun zUtm(easting: Double, northing: Double, strefa: Int, polnocna: Boolean): Pozycja {
        val north = if (polnocna) northing else northing - 10_000_000.0
        val m = north / K0
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256))
        val fi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
                (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
                (151 * e1.pow(3) / 96) * sin(6 * mu) +
                (1097 * e1.pow(4) / 512) * sin(8 * mu)

        val c1 = EP2 * cos(fi1).pow(2)
        val t1 = tan(fi1).pow(2)
        val n1 = A / sqrt(1 - E2 * sin(fi1).pow(2))
        val r1 = A * (1 - E2) / (1 - E2 * sin(fi1).pow(2)).pow(1.5)
        val d = (easting - 500000.0) / (n1 * K0)

        val fi = fi1 - (n1 * tan(fi1) / r1) * (d * d / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lambda = Math.toRadians((strefa - 1) * 6.0 - 180.0 + 3.0) +
                (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                        (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120) / cos(fi1)

        return Pozycja(Math.toDegrees(fi), Math.toDegrees(lambda))
    }

    /** MGRS z dokładnością do metra: `34U EC 12345 67890`. */
    fun mgrs(lat: Double, lon: Double): String {
        if (lat < -80 || lat > 84) return "poza MGRS"
        val s = strefa(lat, lon)
        val p = pas(lat)
        val (e, n) = doUtm(lat, lon, s)
        val kolumna = KOLUMNY[(s - 1) % 3][(floor(e / 100000).toInt() - 1).coerceIn(0, 7)]
        var wiersz = (floor(n / 100000).toLong() % 20).toInt()
        if (s % 2 == 0) wiersz = (wiersz + 5) % 20
        return "%d%c %c%c %05d %05d".format(
            s, p, kolumna, WIERSZE[wiersz],
            (e % 100000).roundToInt().coerceIn(0, 99999),
            (n % 100000).roundToInt().coerceIn(0, 99999),
        )
    }

    // ------------------------------------------------------------------ parsowanie

    private val DZIESIETNE = Regex(
        """^\s*([NS])?\s*(-?\d+(?:[.,]\d+)?)\s*°?\s*([NS])?\s*[,;\s]\s*([EW])?\s*(-?\d+(?:[.,]\d+)?)\s*°?\s*([EW])?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val DMS = Regex(
        """^\s*(\d+)\s*[°: ]\s*(\d+)\s*['′: ]\s*(\d+(?:[.,]\d+)?)\s*["″]?\s*([NS])""" +
                """\s*[,;\s]\s*(\d+)\s*[°: ]\s*(\d+)\s*['′: ]\s*(\d+(?:[.,]\d+)?)\s*["″]?\s*([EW])\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val MGRS = Regex(
        """^\s*(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z])\s*([A-HJ-NP-V])\s*(\d{2,10})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Rozpoznaje format sam. Zwraca `null`, gdy tekst nie jest współrzędnymi w żadnym
     * z trzech zapisów — pole wyszukiwania ma wtedy powiedzieć „nie rozumiem", a nie zgadywać.
     */
    fun parsuj(tekst: String): Pozycja? {
        val t = tekst.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        parsujMgrs(t)?.let { return it }
        parsujDms(t)?.let { return it }
        return parsujDziesietne(t)
    }

    private fun parsujDziesietne(t: String): Pozycja? {
        val m = DZIESIETNE.find(t) ?: return null
        val (przedLat, latTekst, poLat, przedLon, lonTekst, poLon) = m.destructured
        var lat = latTekst.replace(',', '.').toDoubleOrNull() ?: return null
        var lon = lonTekst.replace(',', '.').toDoubleOrNull() ?: return null
        val litLat = (przedLat + poLat).uppercase()
        val litLon = (przedLon + poLon).uppercase()
        if (litLat.contains('S')) lat = -abs(lat)
        if (litLon.contains('W')) lon = -abs(lon)
        if (abs(lat) > 90 || abs(lon) > 180) return null
        return Pozycja(lat, lon)
    }

    private fun parsujDms(t: String): Pozycja? {
        val m = DMS.find(t) ?: return null
        val g = m.groupValues
        fun scal(st: String, min: String, sek: String, lit: String, ujemna: String): Double? {
            val s = st.toDoubleOrNull() ?: return null
            val mi = min.toDoubleOrNull() ?: return null
            val se = sek.replace(',', '.').toDoubleOrNull() ?: return null
            val v = s + mi / 60 + se / 3600
            return if (lit.uppercase() == ujemna) -v else v
        }
        val lat = scal(g[1], g[2], g[3], g[4], "S") ?: return null
        val lon = scal(g[5], g[6], g[7], g[8], "W") ?: return null
        if (abs(lat) > 90 || abs(lon) > 180) return null
        return Pozycja(lat, lon)
    }

    private fun parsujMgrs(t: String): Pozycja? {
        val m = MGRS.find(t.replace(" ", "").let { zwarte ->
            // wpis bez spacji też ma działać: 34UEC1234567890
            if (zwarte.length >= 7) zwarte else t
        }) ?: MGRS.find(t.replace(" ", "")) ?: return null
        val g = m.groupValues
        val strefa = g[1].toIntOrNull() ?: return null
        if (strefa !in 1..60) return null
        val pas = g[2].uppercase()[0]
        val kolumna = g[3].uppercase()[0]
        val wiersz = g[4].uppercase()[0]
        val cyfry = g[5]
        if (cyfry.length % 2 != 0) return null

        val polowa = cyfry.length / 2
        val mnoznik = 10.0.pow(5 - polowa)
        val eReszta = cyfry.substring(0, polowa).toDouble() * mnoznik
        val nReszta = cyfry.substring(polowa).toDouble() * mnoznik

        val kolumnyPasa = KOLUMNY[(strefa - 1) % 3]
        val kolIdx = kolumnyPasa.indexOf(kolumna)
        if (kolIdx < 0) return null
        val easting = (kolIdx + 1) * 100000.0 + eReszta

        var wierszIdx = WIERSZE.indexOf(wiersz)
        if (wierszIdx < 0) return null
        if (strefa % 2 == 0) wierszIdx = (wierszIdx - 5 + 20) % 20
        val northingWPasie = wierszIdx * 100000.0 + nReszta

        // Wiersze powtarzają się co 2 000 km — pas szerokości rozstrzyga, o który chodzi.
        val przyblizonaSzerokosc = (PASY.indexOf(pas) * 8.0) - 80.0 + 4.0
        val polnocna = przyblizonaSzerokosc >= 0
        val (_, northingPasa) = doUtm(przyblizonaSzerokosc, (strefa - 1) * 6.0 - 180.0 + 3.0, strefa)
        val cykl = 2_000_000.0
        val baza = floor(northingPasa / cykl) * cykl
        var northing = baza + northingWPasie
        if (northing - northingPasa > cykl / 2) northing -= cykl
        if (northingPasa - northing > cykl / 2) northing += cykl

        val poz = zUtm(easting, northing, strefa, polnocna)
        if (abs(poz.szerokosc) > 90 || abs(poz.dlugosc) > 180) return null
        return poz
    }

    // ------------------------------------------------------------------ odległość

    /** Odległość w metrach — płaska aproksymacja, do 10 km w zupełności wystarcza. */
    fun odleglosc(a: Pozycja, b: Pozycja): Double {
        val dLat = (b.szerokosc - a.szerokosc) * METRY_NA_STOPIEN
        val dLon = (b.dlugosc - a.dlugosc) * METRY_NA_STOPIEN * cos(Math.toRadians(a.szerokosc))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    fun opisOdleglosci(m: Double): String =
        if (m < 1000) "%.0f m".format(m) else "%.2f km".format(m / 1000)

    const val METRY_NA_STOPIEN = 111_320.0

    /** Zaokrąglenie do `1e-7` stopnia — tyle, ile niesie `MISSION_ITEM_INT`. */
    fun doInt(stopnie: Double): Int = (stopnie * 1e7).roundToLong().toInt()

    fun zInt(v: Int): Double = v / 1e7
}
