package pl.dron15.cockpit.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Azymut
import pl.dron15.cockpit.domain.Cieniowanie
import pl.dron15.cockpit.domain.Misja
import pl.dron15.cockpit.domain.SiatkaTerenu
import pl.dron15.cockpit.domain.Rzut3D
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Wspolrzedne
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Widok przestrzenny terenu — **rzeźba, po której ma lecieć trasa**.
 *
 * ### Dlaczego bez biblioteki 3D i bez MapLibre
 *
 * MK32 to Android 9 (API 28). Sprzętowo przyśpieszone `Canvas.drawVertices`, na którym
 * stoją zwykle takie widoki, **działa dopiero od API 29** — na tej aparaturze byłoby
 * pustym wywołaniem. Zamiast tego używamy `drawBitmapMesh`, obsługiwanego od API 18:
 * jedna siatka, jedna tekstura, jedno wywołanie rysujące.
 *
 * Teksturą jest **ten sam podkład, co na mapie płaskiej** (domyślnie hybryda), przemnożony
 * przez cieniowanie rzeźby. Dzięki temu widok pokazuje prawdziwy teren, a nie kolorową
 * makietę — operator rozpoznaje w nim las i drogę, obok których ma lecieć.
 *
 * ### Czego ten widok nie robi
 *
 * Nie sortuje trójkątów po głębi (`drawBitmapMesh` rysuje je rzędami). Przy pochyleniu
 * poniżej ok. 25° i bardzo stromym zboczu bliższy grzbiet potrafi się „przebić" przez
 * dalszy. Na terenie, po jakim ta maszyna lata, tego nie widać; gdyby przeszkadzało —
 * podnieść pochylenie.
 */
@Composable
fun Widok3D(
    stan: StanMaszyny,
    misja: Misja,
    wybrany: Int,
    srodekLat: Double,
    srodekLon: Double,
    zasiegM: Float,
    ustawienia: UstawieniaMapy,
    modifier: Modifier = Modifier,
    naZasieg: (Float) -> Unit = {},
) {
    val kontekst = LocalContext.current
    val magazyn = remember(kontekst) { MagazynKafelkow.dla(kontekst) }
    val teren = remember(kontekst) { MagazynTerenu.dla(kontekst) }
    magazyn.zInternetu = ustawienia.zInternetu
    teren.zInternetu = ustawienia.zInternetu

    var azymut by remember { mutableStateOf(0f) }
    var pochylenie by remember { mutableStateOf(45f) }
    var przesada by remember { mutableStateOf(2f) }

    // Szczypnięcie zmienia **wielkość pokazywanego terenu**, nie odległość kamery. Odsunięcie
    // kamery od tego samego kwadratu nie pokazuje ani metra więcej krajobrazu, a to o niego
    // chodzi: żeby zobaczyć ukształtowanie, trzeba objąć kilka kilometrów.
    val zoom by rememberUpdatedState(naZasieg)
    val zasiegTeraz by rememberUpdatedState(zasiegM)

    val podklad = ustawienia.podkladObiekt
    val wersjaKafelkow = magazyn.wersja
    val wersjaTerenu = teren.wersja

    val siatka = remember(teren, wersjaTerenu, srodekLat, srodekLon, zasiegM) {
        if (!teren.maDane || (srodekLat == 0.0 && srodekLon == 0.0)) null
        else teren.siatka(srodekLat, srodekLon, zasiegM, BOK_SIATKI).takeIf { !it.pusta }
    }

    val tekstura = remember(magazyn, wersjaKafelkow, podklad.id, srodekLat, srodekLon, zasiegM, siatka) {
        if (siatka == null) null
        else zbudujTeksture(magazyn, podklad, srodekLat, srodekLon, zasiegM, siatka)
    }

    val terenDomu = remember(wersjaTerenu, stan.domSzerokosc, stan.domDlugosc, stan.domUstalony) {
        if (stan.domUstalony) teren.wysokosc(stan.domSzerokosc, stan.domDlugosc) else Float.NaN
    }

    Box(modifier.background(TLO_MAPY).clipToBounds()) {

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, przesuniecie, powiekszenie, _ ->
                        if (powiekszenie != 1f) {
                            zoom(Zasieg.plynnie(zasiegTeraz, powiekszenie))
                        } else {
                            azymut = ((azymut - przesuniecie.x * 0.25f) % 360f + 360f) % 360f
                            pochylenie = (pochylenie + przesuniecie.y * 0.12f).coerceIn(12f, 85f)
                        }
                    }
                }
        ) {
            if (siatka == null) return@Canvas

            val odniesienie = srednia(siatka)
            val rzut = Rzut3D(
                szerokoscPx = size.width,
                wysokoscPx = size.height,
                azymutSt = azymut,
                pochylenieSt = pochylenie,
                dystansM = zasiegM * ODLEGLOSC_KAMERY,
                wysokoscOdniesieniaM = odniesienie,
                przesadaPionowa = przesada,
            )

            rysujSiatke(siatka, rzut, tekstura)
            rysujTrase(stan, misja, wybrany, siatka, rzut, srodekLat, srodekLon, terenDomu)
            rysujRoze(rzut, siatka)
        }

        if (!teren.maDane || (!teren.maNaKarcie && teren.usterkaSieci != null)) {
            Text(
                teren.usterkaSieci?.let { "model terenu nie dociąga się z sieci — $it" }
                    ?: ("brak danych wysokościowych — na kartę: /sdcard/dron15/teren\n" +
                        "pobranie: python narzedzia\\kafelki.py --lat .. --lon .. --teren"),
                color = Barwy.Uwaga, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        } else if (siatka == null) {
            Text("wczytuję teren…", color = Barwy.Wygasly, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.Center))
        }

        // Sterowanie widoku siedzi przy LEWEJ krawędzi, w pionie: dół ekranu należy do rzędu
        // zasięgu i wysokości z ekranu MISJA, a góra do panelu wyszukiwania i chipów podkładu.
        // Rząd u dołu nachodził na „150 m" — sprawdzone na emulatorze MK32.
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "PATRZYSZ Z ${Azymut.opis((azymut + 180f) % 360f)} " +
                        Azymut.roza((azymut + 180f) % 360f),
                color = Barwy.Drugi, fontSize = 9.sp, letterSpacing = 0.8.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Chip("PION ×${przesada.roundToInt()}", false, Modifier.width(96.dp), rozmiar = 12.sp) {
                przesada = when (przesada) {
                    1f -> 2f
                    2f -> 3f
                    else -> 1f
                }
            }
            Spacer(Modifier.height(4.dp))
            Chip("Z PÓŁNOCY", azymut == 0f, Modifier.width(96.dp), rozmiar = 12.sp) {
                azymut = 0f; pochylenie = 45f
            }
            Text(
                "przeciągnij: obrót i pochylenie\nszczypnij: zasięg",
                color = Barwy.Wygasly, fontSize = 9.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// --------------------------------------------------------------------------- rysowanie

/** Liczba węzłów siatki na krawędzi. 65 to 8192 trójkątów — aparatura wyrabia się w klatce. */
private const val BOK_SIATKI = 65

/** Bok tekstury podkładu naciąganej na siatkę. */
private const val BOK_TEKSTURY = 384

/**
 * Odległość kamery w krotności boku pokazywanego kwadratu terenu. Stała, bo zoomem jest
 * teraz sam zasięg — kamera ma tylko ładnie kadrować to, co zasięg wybrał.
 */
private const val ODLEGLOSC_KAMERY = 1.25f

private fun srednia(siatka: SiatkaTerenu): Float {
    var suma = 0.0
    var ile = 0
    for (v in siatka.wysokosci) if (!v.isNaN()) { suma += v; ile++ }
    return if (ile == 0) 0f else (suma / ile).toFloat()
}

/**
 * Siatka terenu jednym wywołaniem `drawBitmapMesh`. Bez tekstury (brak kafelków) rysujemy
 * ją samym cieniowaniem na barwie terenu — widok nadal działa, tylko bez treści mapy.
 */
private fun DrawScope.rysujSiatke(siatka: SiatkaTerenu, rzut: Rzut3D, tekstura: Bitmap?) {
    val bok = siatka.bok
    val krok = siatka.krokM
    val polowa = siatka.zasiegM / 2f
    val wierzcholki = FloatArray(bok * bok * 2)
    val zastepcza = srednia(siatka)

    for (j in 0 until bok) for (i in 0 until bok) {
        val e = -polowa + i * krok
        val n = -polowa + j * krok
        val h = siatka.wezel(i, j).let { if (it.isNaN()) zastepcza else it }
        val p = rzut.rzutuj(e, n, h)
        // wiersz 0 tekstury to północ, a węzeł 0 siatki to południe
        val indeks = ((bok - 1 - j) * bok + i) * 2
        wierzcholki[indeks] = if (p.x.isNaN()) 0f else p.x
        wierzcholki[indeks + 1] = if (p.y.isNaN()) 0f else p.y
    }

    val farba = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    if (tekstura != null) {
        drawContext.canvas.nativeCanvas.drawBitmapMesh(
            tekstura, bok - 1, bok - 1, wierzcholki, 0, null, 0, farba,
        )
    }
    // Krawędzie oczek: przy braku tekstury to jedyna treść widoku, przy teksturze —
    // delikatna siatka odległości, po której oko czyta nachylenie.
    rysujOczka(siatka, wierzcholki, tekstura != null)
}

private fun DrawScope.rysujOczka(siatka: SiatkaTerenu, wierzcholki: FloatArray, jestTekstura: Boolean) {
    val bok = siatka.bok
    val krok = if (jestTekstura) 8 else 2      // z teksturą wystarczy co ósma linia
    val sciezka = Path()
    fun punkt(i: Int, j: Int): Offset {
        val indeks = ((bok - 1 - j) * bok + i) * 2
        return Offset(wierzcholki[indeks], wierzcholki[indeks + 1])
    }
    var j = 0
    while (j < bok) {
        val p0 = punkt(0, j)
        sciezka.moveTo(p0.x, p0.y)
        for (i in 1 until bok) {
            val p = punkt(i, j)
            sciezka.lineTo(p.x, p.y)
        }
        j += krok
    }
    var i = 0
    while (i < bok) {
        val p0 = punkt(i, 0)
        sciezka.moveTo(p0.x, p0.y)
        for (jj in 1 until bok) {
            val p = punkt(i, jj)
            sciezka.lineTo(p.x, p.y)
        }
        i += krok
    }
    drawPath(
        sciezka,
        Barwy.Akcent.copy(alpha = if (jestTekstura) 0.10f else 0.35f),
        style = Stroke(width = 1f),
    )
}

/**
 * Trasa w przestrzeni: linia na wysokości lotu i **maszty do gruntu** przy każdym punkcie.
 * Maszt jest tu najważniejszy — to on pokazuje prześwit, którego na mapie płaskiej nie widać.
 */
private fun DrawScope.rysujTrase(
    stan: StanMaszyny,
    misja: Misja,
    wybrany: Int,
    siatka: SiatkaTerenu,
    rzut: Rzut3D,
    srodekLat: Double,
    srodekLon: Double,
    terenDomuM: Float,
) {
    val naStopienLon = Wspolrzedne.METRY_NA_STOPIEN * cos(Math.toRadians(srodekLat))
    fun metry(lat: Double, lon: Double): Pair<Float, Float> {
        val n = ((lat - srodekLat) * Wspolrzedne.METRY_NA_STOPIEN).toFloat()
        val e = ((lon - srodekLon) * naStopienLon).toFloat()
        return e to n
    }

    if (stan.domUstalony) {
        val (e, n) = metry(stan.domSzerokosc, stan.domDlugosc)
        val h = siatka.wysokosc(e, n)
        if (!h.isNaN()) {
            val p = rzut.rzutuj(e, n, h)
            if (p.widoczny) znacznikDomu(Offset(p.x, p.y), 8.dp.toPx())
        }
    }

    val punkty = misja.naMapie
    if (punkty.isEmpty() || terenDomuM.isNaN()) return

    val farba = Paint().apply {
        color = Barwy.Tekst.toArgb()
        textSize = 10.dp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    val linia = Path()
    var pierwszy = true
    punkty.forEachIndexed { indeks, p ->
        val (e, n) = metry(p.szerokosc, p.dlugosc)
        val teren = siatka.wysokosc(e, n)
        val lot = terenDomuM + p.wysokoscM
        val gora = rzut.rzutuj(e, n, lot)
        if (!gora.widoczny) return@forEachIndexed

        if (pierwszy) { linia.moveTo(gora.x, gora.y); pierwszy = false }
        else linia.lineTo(gora.x, gora.y)

        if (!teren.isNaN()) {
            val dol = rzut.rzutuj(e, n, teren)
            val barwa = barwaPrzeswitu(lot - teren)
            if (dol.widoczny) {
                drawLine(barwa.copy(alpha = 0.8f), Offset(dol.x, dol.y), Offset(gora.x, gora.y),
                    strokeWidth = 1.5.dp.toPx())
                drawCircle(barwa, radius = 2.5.dp.toPx(), center = Offset(dol.x, dol.y))
            }
        }

        val wybranyPunkt = misja.punkty.getOrNull(wybrany) === p
        drawCircle(
            if (wybranyPunkt) Barwy.Akcent else Barwy.Tekst,
            radius = (if (wybranyPunkt) 6 else 4).dp.toPx(),
            center = Offset(gora.x, gora.y),
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${indeks + 1}", gora.x, gora.y - 8.dp.toPx(), farba,
        )
    }
    drawPath(linia, Barwy.Akcent, style = Stroke(width = 2.dp.toPx()))
}

/** Strzałka północy wpisana w teren — bez niej obrócony widok nie mówi, gdzie jest północ. */
private fun DrawScope.rysujRoze(rzut: Rzut3D, siatka: SiatkaTerenu) {
    val zasieg = siatka.zasiegM / 2f
    val h = srednia(siatka)
    val srodek = rzut.rzutuj(0f, 0f, h)
    val polnoc = rzut.rzutuj(0f, zasieg * 0.9f, h)
    if (!srodek.widoczny || !polnoc.widoczny) return
    drawLine(Barwy.Akcent.copy(alpha = 0.5f), Offset(srodek.x, srodek.y),
        Offset(polnoc.x, polnoc.y), strokeWidth = 1.5.dp.toPx())
    val farba = Paint().apply {
        color = Barwy.Akcent.toArgb()
        textSize = 12.dp.toPx()
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText("N", polnoc.x, polnoc.y, farba)
}

// --------------------------------------------------------------------------- tekstura

/**
 * Skleja kwadratową teksturę z kafelków podkładu i **przyciemnia ją cieniowaniem rzeźby**.
 *
 * Cieniowanie wchodzi w teksturę, a nie osobną warstwą, bo `drawBitmapMesh` nie umie
 * przyjąć barw wierzchołków ze sprzętowym rysowaniem — a to jedyna droga na Androidzie 9
 * (patrz nagłówek [Widok3D]).
 */
private fun zbudujTeksture(
    magazyn: MagazynKafelkow,
    podklad: Podklad,
    lat: Double,
    lon: Double,
    zasiegM: Float,
    siatka: SiatkaTerenu,
): Bitmap? {
    val obraz = Bitmap.createBitmap(BOK_TEKSTURY, BOK_TEKSTURY, Bitmap.Config.ARGB_8888)
    val plotno = android.graphics.Canvas(obraz)
    plotno.drawColor(TLO_MAPY.toArgb())

    var cokolwiek = false
    val mpp = zasiegM / BOK_TEKSTURY
    for (warstwa in podklad.katalogi) {
        if (!magazyn.maWarstwe(warstwa)) continue
        val poziom = magazyn.najblizszyPoziom(warstwa, Kafelki.poziomDla(mpp, lat))
        val metryNaPikselKafelka = Kafelki.metryNaPiksel(lat, poziom)
        val polowaPx = (zasiegM / 2f) / metryNaPikselKafelka
        val skala = BOK_TEKSTURY / (2f * polowaPx)
        val lewy = Kafelki.swiatX(lon, poziom) - polowaPx
        val gorny = Kafelki.swiatY(lat, poziom) - polowaPx
        val odX = floor(lewy / Kafelki.ROZMIAR).toInt()
        val doX = floor((lewy + 2 * polowaPx) / Kafelki.ROZMIAR).toInt()
        val odY = floor(gorny / Kafelki.ROZMIAR).toInt()
        val doY = floor((gorny + 2 * polowaPx) / Kafelki.ROZMIAR).toInt()
        val maks = (1 shl poziom) - 1
        val zrodlo = Rect(0, 0, Kafelki.ROZMIAR, Kafelki.ROZMIAR)
        val farba = Paint().apply { isFilterBitmap = true }

        for (tx in odX..doX) for (ty in odY..doY) {
            if (tx < 0 || ty < 0 || tx > maks || ty > maks) continue
            val kafelek = magazyn.kafelek(warstwa, poziom, tx, ty) ?: continue
            val x0 = ((tx * Kafelki.ROZMIAR - lewy) * skala).toFloat()
            val y0 = ((ty * Kafelki.ROZMIAR - gorny) * skala).toFloat()
            val cel = RectF(x0, y0, x0 + Kafelki.ROZMIAR * skala, y0 + Kafelki.ROZMIAR * skala)
            plotno.drawBitmap(kafelek.asAndroidBitmap(), zrodlo, cel, farba)
            cokolwiek = true
        }
    }

    nalozCieniowanie(obraz, siatka, cokolwiek)
    return obraz
}

/**
 * Mnoży teksturę przez cieniowanie. Bez kafelków (`jestPodklad = false`) maluje sam teren
 * skalą barwną wysokości — widok ma działać także wtedy, gdy operator pobrał sam teren.
 */
private fun nalozCieniowanie(obraz: Bitmap, siatka: SiatkaTerenu, jestPodklad: Boolean) {
    val jasnosci = Cieniowanie.licz(siatka)
    val n = siatka.bok
    val bok = obraz.width
    val piksele = IntArray(bok * bok)
    obraz.getPixels(piksele, 0, bok, 0, 0, bok, bok)

    val min = siatka.minimum
    val maks = siatka.maksimum
    val rozpietosc = if (maks - min > 1f) maks - min else 1f

    for (y in 0 until bok) for (x in 0 until bok) {
        // piksel (0,0) tekstury to północno-zachodni narożnik; węzeł (0,0) to południowy zachód
        val fi = x * (n - 1f) / (bok - 1f)
        val fj = (bok - 1 - y) * (n - 1f) / (bok - 1f)
        val i = fi.toInt().coerceIn(0, n - 1)
        val j = fj.toInt().coerceIn(0, n - 1)
        val v = jasnosci[j * n + i]
        val jasnosc = if (v.isNaN()) 0.6f else v

        val indeks = y * bok + x
        val zrodlo = if (jestPodklad) piksele[indeks] else {
            val h = siatka.wezel(i, j)
            if (h.isNaN()) 0xFF404040.toInt() else skalaWysokosci((h - min) / rozpietosc)
        }
        val mnoznik = 0.45f + 0.9f * jasnosc          // nigdy do zera: cień ma zostać czytelny
        val r = (((zrodlo shr 16) and 0xFF) * mnoznik).toInt().coerceIn(0, 255)
        val g = (((zrodlo shr 8) and 0xFF) * mnoznik).toInt().coerceIn(0, 255)
        val b = ((zrodlo and 0xFF) * mnoznik).toInt().coerceIn(0, 255)
        piksele[indeks] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    obraz.setPixels(piksele, 0, bok, 0, 0, bok, bok)
}

/** Skala barwna wysokości: zieleń dolin → brąz → biel grzbietów. */
private fun skalaWysokosci(t: Float): Int {
    val v = t.coerceIn(0f, 1f)
    val (r, g, b) = when {
        v < 0.5f -> {
            val u = v / 0.5f
            Triple(0.29f + 0.45f * u, 0.45f + 0.24f * u, 0.24f + 0.10f * u)
        }
        else -> {
            val u = (v - 0.5f) / 0.5f
            Triple(0.74f + 0.24f * u, 0.69f + 0.28f * u, 0.34f + 0.60f * u)
        }
    }
    return (0xFF shl 24) or
            ((r * 255).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255).toInt().coerceIn(0, 255) shl 8) or
            (b * 255).toInt().coerceIn(0, 255)
}
