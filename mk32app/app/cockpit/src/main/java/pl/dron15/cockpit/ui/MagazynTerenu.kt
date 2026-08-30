package pl.dron15.cockpit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.dron15.cockpit.domain.SiatkaTerenu
import pl.dron15.cockpit.domain.Teren
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * Dane wysokościowe z karty — **ta sama rura, co kafelki mapy**.
 *
 *     /sdcard/dron15/teren/{z}/{x}/{y}.png
 *
 * Kafelek Terrarium rozpakowujemy raz do tablicy metrów i trzymamy **jako liczby, nie jako
 * obraz**: rysunek pyta o wysokość setki razy na klatkę, a dekodowanie barwy piksela przy
 * każdym pytaniu byłoby zauważalne na aparaturze.
 *
 * Brakujący kafelek nie jest błędem — pytanie o wysokość zwraca wtedy `NaN`, kafelek ląduje
 * w kolejce, a po wczytaniu podbija [wersja] i rysunek się odświeża. Mapa działa bez terenu
 * dokładnie tak, jak działała.
 */
class MagazynTerenu(context: Context) {

    private val korzenie = listOfNotNull(
        File(KARTA),
        context.getExternalFilesDir("teren"),
        File(context.filesDir, "teren"),
    ).filter { it.isDirectory }

    private val katalogPobrany =
        (context.getExternalFilesDir("teren") ?: File(context.filesDir, "teren"))
            .also { it.mkdirs() }
    private val pobieracz = Pobieracz(katalogPobrany, "teren")

    /** Czy wolno dociągać brakujące kafelki wysokościowe z sieci — jak w [MagazynKafelkow]. */
    var zInternetu by mutableStateOf(true)

    private val zakres = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pamiec = LinkedHashMap<String, FloatArray?>(16, 0.75f, true)
    private val wKolejce = HashSet<String>()

    var wersja by mutableStateOf(0)
        private set

    /** Poziomy powiększenia leżące na karcie. */
    val poziomy: List<Int> = korzenie
        .flatMap { it.list()?.toList().orEmpty() }
        .mapNotNull { it.toIntOrNull() }
        .distinct()
        .sorted()

    val maDane: Boolean get() = poziomy.isNotEmpty() || zInternetu

    /**
     * Poziom, z którego czytamy wysokości. Model ma ok. 30 m rozdzielczości, więc powyżej
     * `z13` dokłada się wyłącznie wagi pliku — bierzemy najbliższy zalecanemu.
     */
    val poziom: Int = poziomy.minByOrNull { abs(it - Teren.POZIOM_ZALECANY) } ?: Teren.POZIOM_ZALECANY

    /** Ile kafelków wysokościowych dociągnęliśmy w tym uruchomieniu. */
    val pobraneZSieci: Int get() = pobieracz.pobrane

    /**
     * Dlaczego dociąganie modelu terenu nie działa, albo `null`.
     *
     * [maDane] mówi „tak" już przy samej włączonej sieci, więc bez tego pola zepsute
     * pobieranie kończy się wiecznym „wczytuję teren…" i prześwitami bez wartości —
     * a operator nie ma jak zgadnąć, że zawiniło coś, co da się naprawić na miejscu.
     */
    val usterkaSieci: String? get() = pobieracz.usterka

    /** Czy model leży na karcie — bez liczenia na sieć. */
    val maNaKarcie: Boolean get() = poziomy.isNotEmpty()

    // ------------------------------------------------------------------ pytania o wysokość

    /** Wysokość n.p.m. w punkcie; `NaN`, gdy kafelka jeszcze nie ma albo go nie pobrano. */
    fun wysokosc(lat: Double, lon: Double): Float {
        if (!maDane) return Float.NaN
        val skala = Teren.ROZMIAR * Math.pow(2.0, poziom.toDouble())
        val gx = Kafelki.swiatX(lon, poziom)
        val gy = Kafelki.swiatY(lat, poziom)
        if (gx < 0 || gy < 0 || gx >= skala || gy >= skala) return Float.NaN

        // środek piksela leży w jego połowie — stąd −0,5 przed interpolacją
        val fx = gx - 0.5
        val fy = gy - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()

        val a = piksel(x0, y0)
        val b = piksel(x0 + 1, y0)
        val c = piksel(x0, y0 + 1)
        val d = piksel(x0 + 1, y0 + 1)
        if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) return Float.NaN
        val gora = a + (b - a) * tx
        val dol = c + (d - c) * tx
        return gora + (dol - gora) * ty
    }

    /**
     * Kwadratowa siatka wysokości wokół punktu — postać, z której liczą się cieniowanie,
     * warstwice i widok przestrzenny. `bok` to liczba węzłów na krawędzi.
     */
    fun siatka(lat: Double, lon: Double, zasiegM: Float, bok: Int): SiatkaTerenu {
        val wysokosci = FloatArray(bok * bok) { Float.NaN }
        if (maDane && bok > 1) {
            val krok = zasiegM / (bok - 1)
            val polowa = zasiegM / 2f
            val naStopienLon = METRY_NA_STOPIEN * cos(Math.toRadians(lat))
            for (j in 0 until bok) for (i in 0 until bok) {
                val e = -polowa + i * krok
                val n = -polowa + j * krok
                val plat = lat + n / METRY_NA_STOPIEN
                val plon = lon + e / naStopienLon
                wysokosci[j * bok + i] = wysokosc(plat, plon)
            }
        }
        return SiatkaTerenu(bok, lat, lon, zasiegM, wysokosci)
    }

    // ------------------------------------------------------------------ kafelki

    private fun piksel(gx: Int, gy: Int): Float {
        val maks = (1 shl poziom) * Teren.ROZMIAR
        if (gx < 0 || gy < 0 || gx >= maks || gy >= maks) return Float.NaN
        val tx = gx / Teren.ROZMIAR
        val ty = gy / Teren.ROZMIAR
        val dane = kafelek(tx, ty) ?: return Float.NaN
        val lx = gx - tx * Teren.ROZMIAR
        val ly = gy - ty * Teren.ROZMIAR
        val h = dane[ly * Teren.ROZMIAR + lx]
        return if (Teren.sensowna(h)) h else Float.NaN
    }

    private fun kafelek(x: Int, y: Int): FloatArray? {
        val klucz = "$poziom/$x/$y"
        synchronized(pamiec) {
            if (pamiec.containsKey(klucz)) return pamiec[klucz]
            if (klucz in wKolejce) return null
            wKolejce += klucz
        }
        zakres.launch {
            val dane = wczytaj(x, y)
            synchronized(pamiec) {
                pamiec[klucz] = dane
                wKolejce -= klucz
                while (pamiec.size > MAKS_W_PAMIECI) {
                    val najstarszy = pamiec.keys.firstOrNull() ?: break
                    pamiec.remove(najstarszy)
                }
            }
            if (dane != null) wersja++
            else if (zInternetu) {
                // `tylkoPng`: wysokość siedzi w dokładnej barwie piksela, więc kafelek
                // przysłany w formacie stratnym jest gorszy niż żaden.
                val zamowiono = pobieracz.zamow(
                    Zrodla.TEREN, "", poziom, x, y, tylkoPng = true) { wersja++ }
                if (zamowiono) synchronized(pamiec) { pamiec.remove(klucz) }
            }
        }
        return null
    }

    private fun wczytaj(x: Int, y: Int): FloatArray? {
        for (korzen in korzenie + katalogPobrany) {
            for (rozszerzenie in ROZSZERZENIA) {
                val plik = File(korzen, "$poziom/$x/$y.$rozszerzenie")
                if (!plik.isFile) continue
                // Skalowanie albo stratna konwersja barwy **zniszczyłyby wysokość** —
                // w Terrarium liczy się dokładna wartość każdego z trzech kanałów, więc
                // wymuszamy ARGB_8888 i wyłączamy dopasowanie do gęstości ekranu.
                val opcje = BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val obraz = BitmapFactory.decodeFile(plik.absolutePath, opcje) ?: continue
                if (obraz.width != Teren.ROZMIAR || obraz.height != Teren.ROZMIAR) {
                    obraz.recycle()
                    continue
                }
                val piksele = IntArray(Teren.ROZMIAR * Teren.ROZMIAR)
                obraz.getPixels(piksele, 0, Teren.ROZMIAR, 0, 0, Teren.ROZMIAR, Teren.ROZMIAR)
                obraz.recycle()
                return FloatArray(piksele.size) { Teren.dekodujArgb(piksele[it]) }
            }
        }
        return null
    }

    companion object {
        /** Jedna instancja na proces — powód ten sam, co przy [MagazynKafelkow.dla]. */
        @Volatile
        private var wspolny: MagazynTerenu? = null

        fun dla(context: Context): MagazynTerenu =
            wspolny ?: synchronized(this) {
                wspolny ?: MagazynTerenu(context.applicationContext).also { wspolny = it }
            }

        const val KARTA = "/sdcard/dron15/teren"

        /** Kafelek to 256 KB w tablicy `float`; 16 kafelków to 4 MB i pokrywa rejon lotów. */
        const val MAKS_W_PAMIECI = 16
        val ROZSZERZENIA = listOf("png", "webp")
        const val METRY_NA_STOPIEN = 111_320.0
    }
}
