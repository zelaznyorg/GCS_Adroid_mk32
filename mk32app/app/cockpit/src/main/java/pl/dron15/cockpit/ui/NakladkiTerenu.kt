package pl.dron15.cockpit.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import pl.dron15.cockpit.domain.Azymut
import pl.dron15.cockpit.domain.Cieniowanie
import pl.dron15.cockpit.domain.PoziomWarstwicy
import pl.dron15.cockpit.domain.SiatkaTerenu
import pl.dron15.cockpit.domain.Warstwice
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Nakładki liczone z terenu — **rzeźba widoczna na dowolnym podkładzie**.
 *
 * Cieniowanie i warstwice nie pochodzą z pobranego obrazka, tylko z danych wysokościowych
 * ([MagazynTerenu]). Dzięki temu kładą się także na zdjęciu lotniczym, gdzie żaden gotowy
 * podkład topograficzny nie sięga — a to właśnie zdjęcie jest tu podkładem obowiązkowym.
 *
 * ### Skąd te liczby
 *
 * Siatka ma [BOK] węzłów na krawędzi i pokrywa kwadrat [WSPOLCZYNNIK] × zasięg mapy —
 * tyle, żeby starczyło na dłuższy bok ekranu przy każdym obrocie. Przy 65 węzłach jedno
 * przeliczenie to ok. 25 tys. działań, czyli poniżej milisekundy; można je robić przy
 * każdym przesunięciu mapy i aparatura tego nie czuje.
 */
object Nakladki {
    /** liczba węzłów siatki na krawędzi */
    const val BOK = 65

    /** ile razy siatka jest większa niż zasięg mapy liczony po krótszym boku ekranu */
    const val WSPOLCZYNNIK = 2.0f
}

@Composable
fun pamietajSiatke(
    magazyn: MagazynTerenu,
    lat: Double,
    lon: Double,
    zasiegM: Float,
    wlaczona: Boolean,
): SiatkaTerenu? {
    val wersja = magazyn.wersja
    val bokM = zasiegM * Nakladki.WSPOLCZYNNIK
    return remember(wlaczona, magazyn, wersja, zaokragl(lat), zaokragl(lon), bokM) {
        if (!wlaczona || !magazyn.maDane || (lat == 0.0 && lon == 0.0)) null
        else magazyn.siatka(lat, lon, bokM, Nakladki.BOK).takeIf { !it.pusta }
    }
}

/**
 * Cieniowanie jako obrazek [Nakladki.BOK] × [Nakladki.BOK], rozciągany przy rysowaniu.
 * Jedno wywołanie rysujące zamiast kilku tysięcy prostokątów — inaczej mapa gubiłaby klatki.
 */
@Composable
fun pamietajCieniowanie(siatka: SiatkaTerenu?): ImageBitmap? =
    remember(siatka) {
        if (siatka == null) null else {
            val jasnosci = Cieniowanie.licz(siatka)
            val n = siatka.bok
            val piksele = IntArray(n * n)
            for (j in 0 until n) for (i in 0 until n) {
                // wiersz 0 obrazka to północ, a węzeł 0 siatki to południe — stąd odwrócenie
                val v = jasnosci[(n - 1 - j) * n + i]
                piksele[j * n + i] = if (v.isNaN()) 0 else barwaCienia(v)
            }
            Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
                .apply { setPixels(piksele, 0, n, 0, 0, n, n) }
                .asImageBitmap()
        }
    }

/** Zbocze odwrócone od światła przyciemniamy, oświetlone rozjaśniamy — delikatnie. */
private fun barwaCienia(v: Float): Int {
    return if (v < 0.5f) {
        val a = ((0.5f - v) * 2f * 150f).roundToInt().coerceIn(0, 150)
        (a shl 24)                       // czerń o zmiennym kryciu
    } else {
        val a = ((v - 0.5f) * 2f * 60f).roundToInt().coerceIn(0, 60)
        (a shl 24) or 0x00FFFFFF         // biel o zmiennym kryciu
    }
}

@Composable
fun pamietajWarstwice(siatka: SiatkaTerenu?, krokM: Int): List<PoziomWarstwicy> =
    remember(siatka, krokM) {
        if (siatka == null) emptyList() else Warstwice.licz(siatka, krokM)
    }

// --------------------------------------------------------------------------- rysowanie

/**
 * Kładzie cieniowanie na mapę. [srodek] to punkt, wokół którego liczono siatkę,
 * [skala] to liczba pikseli na metr.
 */
fun DrawScope.rysujCieniowanie(obraz: ImageBitmap?, srodek: Offset, bokM: Float, skala: Float) {
    if (obraz == null) return
    val bokPx = bokM * skala
    drawImage(
        image = obraz,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(obraz.width, obraz.height),
        dstOffset = IntOffset((srodek.x - bokPx / 2f).roundToInt(), (srodek.y - bokPx / 2f).roundToInt()),
        dstSize = IntSize(bokPx.roundToInt(), bokPx.roundToInt()),
    )
}

/**
 * Warstwice. Co piąta grubsza i podpisana — jak na mapie papierowej, żeby dało się czytać
 * kierunek spadku bez wpatrywania się w każdą linię.
 */
fun DrawScope.rysujWarstwice(
    poziomy: List<PoziomWarstwicy>,
    srodek: Offset,
    bokM: Float,
    skala: Float,
    barwa: Color,
    podpisy: Boolean = true,
) {
    if (poziomy.isEmpty()) return
    val bokPx = bokM * skala
    val lewy = srodek.x - bokPx / 2f
    val dol = srodek.y + bokPx / 2f

    val cienkie = Path()
    val grube = Path()
    for (poziom in poziomy) {
        val sciezka = if (poziom.gruba) grube else cienkie
        for (o in poziom.odcinki) {
            sciezka.moveTo(lewy + o.x1 * bokPx, dol - o.y1 * bokPx)
            sciezka.lineTo(lewy + o.x2 * bokPx, dol - o.y2 * bokPx)
        }
    }
    drawPath(cienkie, barwa.copy(alpha = 0.45f), style = Stroke(width = 1.dp.toPx()))
    drawPath(grube, barwa.copy(alpha = 0.8f), style = Stroke(width = 1.8.dp.toPx()))

    if (!podpisy) return
    val farba = Paint().apply {
        color = barwa.toArgb()
        textSize = 9.dp.toPx()
        isAntiAlias = true
    }
    for (poziom in poziomy) {
        if (!poziom.gruba) continue
        val o = poziom.odcinki.getOrNull(poziom.odcinki.size / 2) ?: continue
        drawContext.canvas.nativeCanvas.drawText(
            "${poziom.wysokoscM}",
            lewy + o.x1 * bokPx + 2.dp.toPx(),
            dol - o.y1 * bokPx - 2.dp.toPx(),
            farba,
        )
    }
}

/**
 * Pierścień azymutu — **narzędzie do lotu na kierunek, nie ozdoba**.
 *
 * Kreska co 10°, podpis co 30°, wyróżnione strony świata. Azymuty są **geograficzne**:
 * ta maszyna nie ma kompasu i bierze kurs z bazy GNSS, więc północ na tym pierścieniu jest
 * tą samą północą, którą widzi kontroler lotu (patrz `domain/Azymut`).
 */
fun DrawScope.rysujAzymut(
    srodek: Offset,
    promienPx: Float,
    barwa: Color,
    barwaOsi: Color,
) {
    if (promienPx < 24f) return
    drawCircle(barwa.copy(alpha = 0.35f), radius = promienPx, center = srodek,
        style = Stroke(width = 1.dp.toPx()))
    drawCircle(barwa.copy(alpha = 0.2f), radius = promienPx / 2f, center = srodek,
        style = Stroke(width = 1.dp.toPx()))

    val farba = Paint().apply {
        color = barwa.toArgb()
        textSize = 9.dp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    for (st in 0 until 360 step 10) {
        val kat = Math.toRadians(st.toDouble() - 90.0)
        val glowna = st % 90 == 0
        val dluga = st % 30 == 0
        val dlugosc = when {
            glowna -> 12.dp.toPx()
            dluga -> 8.dp.toPx()
            else -> 4.dp.toPx()
        }
        val cosK = cos(kat).toFloat()
        val sinK = sin(kat).toFloat()
        val od = Offset(srodek.x + cosK * (promienPx - dlugosc), srodek.y + sinK * (promienPx - dlugosc))
        val do_ = Offset(srodek.x + cosK * promienPx, srodek.y + sinK * promienPx)
        drawLine(
            if (glowna) barwaOsi else barwa.copy(alpha = if (dluga) 0.7f else 0.4f),
            od, do_, strokeWidth = if (glowna) 2.dp.toPx() else 1.dp.toPx(),
        )
        if (dluga) {
            val r = promienPx - dlugosc - 8.dp.toPx()
            val opis = if (glowna) Azymut.roza(st.toFloat()) else "$st"
            farba.color = (if (glowna) barwaOsi else barwa).toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                opis,
                srodek.x + cosK * r,
                srodek.y + sinK * r + 3.dp.toPx(),
                farba,
            )
        }
    }
}

/** Zaokrąglenie środka mapy — bez tego każdy piksel przesunięcia przeliczałby całą siatkę. */
private fun zaokragl(v: Double): Double = Math.round(v * 20000.0) / 20000.0
