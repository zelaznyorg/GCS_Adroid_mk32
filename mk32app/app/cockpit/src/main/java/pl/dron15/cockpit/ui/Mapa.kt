package pl.dron15.cockpit.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.StanMaszyny
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mapa: podkład z kafelków z karty TF (patrz [Kafelki]) plus warstwa własna —
 * ślad, dom, maszyna, linia powrotu, a na życzenie rzeźba terenu i pierścień azymutu.
 *
 * Gdy kafelków nie ma, zostaje siatka metryczna. To nie jest awaria: ślad, dom i dystans
 * działają tak samo, a operator od razu widzi, że podkładu brakuje.
 */
@Composable
fun Mapa(
    stan: StanMaszyny,
    teraz: Long,
    modifier: Modifier = Modifier,
    zwarta: Boolean = false,
    zasiegM: Float = Zasieg.AUTO, // 0 = dobierz sam do śladu
    ustawienia: UstawieniaMapy = UstawieniaMapy(),
    /**
     * Szczypnięcie zmienia zasięg. `null` znaczy „mapa bez zoomu" — tak chodzi miniatura,
     * gdzie dwa palce i tak się nie mieszczą, a skala ma pilnować się sama.
     */
    naZasieg: ((Float) -> Unit)? = null,
    /**
     * Ile dołu mapy przykrywa interfejs. W wariancie D (dok/UI.md §9) mapa idzie na pełną
     * szybę, a rząd liczb leży na niej — bez tego podziałka wchodziła pod wysokość i dystans.
     */
    wcieciePodolu: Dp = 0.dp,
) {
    val kontekst = LocalContext.current
    val magazyn = remember(kontekst) { MagazynKafelkow.dla(kontekst) }
    val teren = remember(kontekst) { MagazynTerenu.dla(kontekst) }
    magazyn.zInternetu = ustawienia.zInternetu
    teren.zInternetu = ustawienia.zInternetu

    val (e, n) = if (stan.domUstalony && stan.pozycjaZnana) stan.wzgledemDomu() else 0f to 0f
    val zasieg = Zasieg.obowiazujacy(zasiegM, stan)
    // Gest żyje tak długo, jak klucze `pointerInput`; bez `rememberUpdatedState` szczypnięcie
    // liczyłoby się od zasięgu z pierwszej kompozycji i skakało przy każdym dotknięciu.
    val zasiegTeraz by rememberUpdatedState(zasieg)
    val zywa = stan.telemetriaZywa(teraz)
    val wersja = magazyn.wersja                      // odczyt wiąże mapę ze stanem magazynu

    val srodekLat = if (stan.domUstalony) stan.domSzerokosc else stan.szerokosc
    val srodekLon = if (stan.domUstalony) stan.domDlugosc else stan.dlugosc
    val podklad = ustawienia.podkladObiekt

    // Rzeźba w miniaturze nie ma czego pokazać — 190 × 126 dp to za mało na warstwice.
    val rzezba = !zwarta && (ustawienia.cieniowanie || ustawienia.warstwice)
    val siatkaTerenu = pamietajSiatke(teren, srodekLat, srodekLon, zasieg, rzezba)
    val cieniowanie = pamietajCieniowanie(if (ustawienia.cieniowanie) siatkaTerenu else null)
    val warstwice = pamietajWarstwice(
        if (ustawienia.warstwice) siatkaTerenu else null, ustawienia.krokWarstwicM)

    val gest = if (zwarta || naZasieg == null) Modifier else Modifier.pointerInput(Unit) {
        detectTransformGestures { _, _, powiekszenie, _ ->
            if (powiekszenie != 1f) naZasieg(Zasieg.plynnie(zasiegTeraz, powiekszenie))
        }
    }

    Box(modifier.background(TLO_MAPY).clipToBounds().then(gest)) {
        Canvas(Modifier.fillMaxSize()) {
            wersja.let { }

            val mpp = zasieg / minOf(size.width, size.height)
            val poziom = magazyn.najblizszyPoziom(podklad.baza, Kafelki.poziomDla(mpp, srodekLat))
            val skala = 1f / mpp                     // px na metr
            val srodek = Offset(size.width / 2f, size.height / 2f)
            val krok = krokSiatki(mpp * minOf(size.width, size.height))
            fun punkt(we: Float, wn: Float) = Offset(srodek.x + we * skala, srodek.y - wn * skala)

            // Kafelek z poziomu z ma swoją skalę; jeśli różni się od skali widoku, rozciągamy go.
            val wspolczynnik =
                if (srodekLat != 0.0) Kafelki.metryNaPiksel(srodekLat, poziom) / mpp else 1f
            val podkladJest = (stan.pozycjaZnana || stan.domUstalony) &&
                    kafelki(magazyn, podklad.katalogi, srodekLat, srodekLon, poziom, srodek, wspolczynnik)
            if (podkladJest) {
                // Podkład jest tłem dla śladu, nie treścią samą w sobie — ile przyciemnić,
                // mówi sam podkład: zdjęcie w słońcu potrzebuje więcej niż mapa kreskowa.
                drawRect(Color.Black.copy(alpha = podklad.przyciemnienie))
            } else {
                siatka(srodek, skala, krok)
            }

            if (rzezba) {
                val bokM = zasieg * Nakladki.WSPOLCZYNNIK
                rysujCieniowanie(cieniowanie, srodek, bokM, skala)
                rysujWarstwice(warstwice, srodek, bokM, skala,
                    if (Barwy.ciemny) Color(0xFFC9A227) else Color(0xFF7A5C00))
            }

            if (ustawienia.azymut && stan.domUstalony) {
                val promien = minOf(size.width, size.height) / 2f - 10.dp.toPx()
                rysujAzymut(punkt(0f, 0f), promien, Barwy.InstrTusz3, Barwy.Akcent)
            }

            if (stan.slad.size > 1) {
                val sciezka = Path()
                stan.slad.forEachIndexed { i, (se, sn) ->
                    val p = punkt(se, sn)
                    if (i == 0) sciezka.moveTo(p.x, p.y) else sciezka.lineTo(p.x, p.y)
                }
                drawPath(sciezka, Barwy.Akcent.copy(alpha = 0.85f),
                    style = Stroke(width = if (zwarta) 1.5.dp.toPx() else 2.5.dp.toPx()))
            }

            if (stan.domUstalony) {
                val dom = punkt(0f, 0f)
                if (stan.pozycjaZnana) {
                    drawLine(
                        Barwy.Dobrze.copy(alpha = 0.55f), dom, punkt(e, n),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                    )
                }
                znacznikDomu(dom, if (zwarta) 6.dp.toPx() else 9.dp.toPx())
            }

            if (stan.pozycjaZnana && stan.domUstalony) {
                val maszyna = punkt(e, n)
                val kurs = if (stan.kursGnssDostepny) stan.kursGnssSt else stan.kursSt
                val barwa = when {
                    !zywa -> Barwy.Wygasly
                    stan.uzbrojony -> Barwy.Uwaga
                    else -> Color.White
                }
                rotate(kurs, maszyna) {
                    val r = if (zwarta) 8.dp.toPx() else 12.dp.toPx()
                    drawPath(Path().apply {
                        moveTo(maszyna.x, maszyna.y - r)
                        lineTo(maszyna.x - r * 0.72f, maszyna.y + r * 0.8f)
                        lineTo(maszyna.x, maszyna.y + r * 0.35f)
                        lineTo(maszyna.x + r * 0.72f, maszyna.y + r * 0.8f)
                        close()
                    }, barwa)
                }
                if (!stan.kursGnssDostepny) {
                    drawCircle(Barwy.Blokada, radius = (if (zwarta) 11 else 16).dp.toPx(),
                        center = maszyna, style = Stroke(width = 1.5.dp.toPx()))
                }
            }

            if (!zwarta) podzialka(krok, skala, wcieciePodolu)
        }

        if (!zwarta && trzebaOstrzec(magazyn, podklad)) {
            Text(
                brakPodkladu(podklad, magazyn.usterkaSieci),
                color = if (podklad.wymagany) Barwy.Uwaga else Barwy.Wygasly, fontSize = 10.sp,
                lineHeight = 13.sp,
                // Diagnoza usterki jest zdaniem, nie hasłem — bez ograniczenia szerokości
                // rozlałaby się na całą mapę i przykryła ślad.
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).widthIn(max = 300.dp),
            )
        }

        if (!stan.domUstalony || !stan.pozycjaZnana) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BRAK POZYCJI", color = Barwy.Blokada,
                    fontSize = if (zwarta) 11.sp else 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Komunikat o brakującym podkładzie mówi **nazwę katalogu**, nie „brak kafelków" —
 * operator ma wiedzieć, czego dograć, bez zaglądania do dokumentacji.
 *
 * Gdy podkładu nie ma na karcie **i** dociąganie z sieci się wywraca, ważniejsza jest
 * przyczyna niż ścieżka: pusta siatka wygląda tak samo przy braku kafelków, przy braku
 * sieci i przy źle ustawionym zegarze, a tylko ostatnie dwa da się naprawić na miejscu.
 */
internal fun brakPodkladu(podklad: Podklad, usterka: String? = null): String {
    val skad = "na kartę: ${Kafelki.KARTA}/${podklad.baza}"
    return if (usterka == null) "brak podkładu „${podklad.nazwa}” — $skad"
    else "„${podklad.nazwa}” nie dociąga się z sieci — $usterka  ·  $skad"
}

/**
 * Czy ostrzec o podkładzie: albo nie da się go pokazać w ogóle, albo obiecaliśmy dociągnąć
 * go z sieci, a sieć nie dowozi. Drugi przypadek jest gorszy, bo wygląda jak działający.
 */
internal fun trzebaOstrzec(magazyn: MagazynKafelkow, podklad: Podklad): Boolean =
    !magazyn.maPodklad(podklad) ||
            (magazyn.usterkaSieci != null && !magazyn.maNaKarcie(podklad.baza))

// --------------------------------------------------------------------------- rysowanie

internal val TLO_MAPY = Color(0xFF0A0F12)

/**
 * Kafelki pokrywające widok, warstwa po warstwie od spodu (`zdjecia` → `opisy` → `drogi`).
 * Zwraca `true`, jeśli **warstwa bazowa** dała choć jeden kafelek — nakładki same z siebie
 * nie są podkładem.
 */
internal fun DrawScope.kafelki(
    magazyn: MagazynKafelkow,
    warstwy: List<String>,
    lat: Double,
    lon: Double,
    z: Int,
    srodek: Offset,
    wspolczynnik: Float,
): Boolean {
    if (lat == 0.0 && lon == 0.0) return false
    if (!magazyn.maKafelki) return false
    var baza = false
    warstwy.forEachIndexed { i, warstwa ->
        val narysowano = jednaWarstwa(magazyn, warstwa, lat, lon, z, srodek, wspolczynnik)
        if (i == 0) baza = narysowano
    }
    return baza
}

private fun DrawScope.jednaWarstwa(
    magazyn: MagazynKafelkow,
    warstwa: String,
    lat: Double,
    lon: Double,
    z: Int,
    srodek: Offset,
    wspolczynnik: Float,
): Boolean {
    if (!magazyn.maWarstwe(warstwa)) return false
    // Nakładka może mieć inny zapas poziomów niż baza — pytamy o poziom osobno dla każdej,
    // inaczej warstwa nazw znikała wszędzie tam, gdzie pobrano ją płycej niż zdjęcia.
    val poziom = magazyn.najblizszyPoziom(warstwa, z)
    val wsp = wspolczynnik * Math.pow(2.0, (z - poziom).toDouble()).toFloat()
    val bok = Kafelki.ROZMIAR * wsp
    val swiatX = Kafelki.swiatX(lon, poziom) * wsp
    val swiatY = Kafelki.swiatY(lat, poziom) * wsp
    val lewy = swiatX - srodek.x
    val gorny = swiatY - srodek.y
    val odX = floor(lewy / bok).toInt()
    val doX = floor((lewy + size.width) / bok).toInt()
    val odY = floor(gorny / bok).toInt()
    val doY = floor((gorny + size.height) / bok).toInt()
    val maks = (1 shl poziom) - 1

    var cokolwiek = false
    for (tx in odX..doX) for (ty in odY..doY) {
        if (tx < 0 || ty < 0 || tx > maks || ty > maks) continue
        val obraz = magazyn.kafelek(warstwa, poziom, tx, ty) ?: continue
        drawImage(
            image = obraz,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(obraz.width, obraz.height),
            dstOffset = IntOffset((tx * bok - lewy).toInt(), (ty * bok - gorny).toInt()),
            // +1 px, żeby przy ułamkowej skali nie prześwitywały szpary między kafelkami
            dstSize = IntSize(bok.toInt() + 1, bok.toInt() + 1),
        )
        cokolwiek = true
    }
    return cokolwiek
}

internal fun DrawScope.siatka(srodek: Offset, skala: Float, krokM: Float) {
    val krok = krokM * skala
    if (krok < 8f) return
    var x = srodek.x % krok
    while (x < size.width) {
        drawLine(Barwy.Linia2, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += krok
    }
    var y = srodek.y % krok
    while (y < size.height) {
        drawLine(Barwy.Linia2, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += krok
    }
}

/**
  * Podziałka z **podpisem**. Sam odcinek wystarczał, dopóki skala chodziła po czterech
  * ustalonych wartościach; przy zoomie płynnym operator musi widzieć, ile ten odcinek znaczy.
  */
internal fun DrawScope.podzialka(krokM: Float, skala: Float, wcieciePodolu: Dp) {
    val px = krokM * skala
    val y = size.height - 14.dp.toPx() - wcieciePodolu.toPx()
    val x0 = 14.dp.toPx()
    val barwa = Color.White.copy(alpha = 0.7f)
    drawLine(barwa, Offset(x0, y), Offset(x0 + px, y), strokeWidth = 2.dp.toPx())
    drawLine(barwa, Offset(x0, y - 4.dp.toPx()), Offset(x0, y + 4.dp.toPx()), strokeWidth = 2.dp.toPx())
    drawLine(barwa, Offset(x0 + px, y - 4.dp.toPx()), Offset(x0 + px, y + 4.dp.toPx()),
        strokeWidth = 2.dp.toPx())
    val farba = Paint().apply {
        color = barwa.toArgb()
        textSize = 10.dp.toPx()
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(
        Zasieg.opis(krokM), x0 + px + 6.dp.toPx(), y + 4.dp.toPx(), farba)
}

internal fun DrawScope.znacznikDomu(p: Offset, r: Float) {
    drawCircle(Barwy.Dobrze, radius = r, center = p, style = Stroke(width = 2f))
    drawLine(Barwy.Dobrze, Offset(p.x - r, p.y), Offset(p.x + r, p.y), strokeWidth = 2f)
    drawLine(Barwy.Dobrze, Offset(p.x, p.y - r), Offset(p.x, p.y + r), strokeWidth = 2f)
}

/** Krok siatki i podziałki dobrany tak, żeby na ekranie było 4–8 oczek. */
internal fun krokSiatki(zasiegM: Float): Float {
    val cel = zasiegM / 5f
    val kroki = floatArrayOf(5f, 10f, 25f, 50f, 100f, 250f, 500f, 1000f, 2500f)
    return kroki.minByOrNull { abs(it - cel) } ?: 50f
}
