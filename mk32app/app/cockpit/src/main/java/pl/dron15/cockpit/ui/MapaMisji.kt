package pl.dron15.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Misja
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Wspolrzedne
import kotlin.math.cos
import kotlin.math.min

/**
 * Mapa planowania — `dok/PRZEKAZANIE_M3.md` §5.
 *
 * Różnica wobec mapy z ekranu LOT jest jedna, ale zasadnicza: **ta mapa przyjmuje dotknięcia**.
 * Dotknięcie pustego miejsca dokłada punkt trasy, dotknięcie znacznika wybiera punkt,
 * przeciągnięcie przesuwa widok. Bez przesuwania planer byłby ograniczony do tego, co widać
 * wokół domu — przekazanie tego nie rozstrzyga, ale planowanie bez tego nie działa.
 *
 * Odwzorowanie jest to samo, co w [Mapa]: metry względem środka widoku, kafelki rastrowe
 * z karty. Dzięki temu obie mapy pokazują ten sam teren w tej samej skali.
 *
 * ### Wysokość punktu jest tu liczbą **dwuznaczną i dlatego podpisaną dwa razy**
 *
 * Misja niesie wysokość nad punktem startu, a przeszkodą jest teren pod punktem. Znacznik
 * pokazuje więc obie: zadaną (`120 m`) i **prześwit nad gruntem** (`+45`), liczony z danych
 * wysokościowych. Ujemny prześwit to trasa wchodząca w zbocze — znacznik robi się czerwony.
 */
@Composable
fun MapaMisji(
    stan: StanMaszyny,
    misja: Misja,
    wybrany: Int,
    srodekLat: Double,
    srodekLon: Double,
    zasiegM: Float,
    geofenceM: Float,
    edycjaMozliwa: Boolean,
    naDodanie: (Wspolrzedne.Pozycja) -> Unit,
    naWybor: (Int) -> Unit,
    naPrzesuniecie: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    ustawienia: UstawieniaMapy = UstawieniaMapy(),
    naZasieg: (Float) -> Unit = {},
) {
    val kontekst = LocalContext.current
    val magazyn = remember(kontekst) { MagazynKafelkow.dla(kontekst) }
    val teren = remember(kontekst) { MagazynTerenu.dla(kontekst) }
    magazyn.zInternetu = ustawienia.zInternetu
    teren.zInternetu = ustawienia.zInternetu
    val gestosc = LocalDensity.current

    // Lambdy w `pointerInput` zyja tak dlugo, jak jego klucze. Bez `rememberUpdatedState`
    // dotkniecie mapy widzialoby trase z pierwszej kompozycji — czyli pusta — i kazdy nowy
    // punkt kasowalby poprzednie.
    val dodaj by rememberUpdatedState(naDodanie)
    val przesun by rememberUpdatedState(naPrzesuniecie)
    val zoom by rememberUpdatedState(naZasieg)
    val zasiegTeraz by rememberUpdatedState(zasiegM)

    val podklad = ustawienia.podkladObiekt
    val rzezba = ustawienia.cieniowanie || ustawienia.warstwice
    val siatkaTerenu = pamietajSiatke(teren, srodekLat, srodekLon, zasiegM, rzezba)
    val cieniowanie = pamietajCieniowanie(if (ustawienia.cieniowanie) siatkaTerenu else null)
    val warstwice = pamietajWarstwice(
        if (ustawienia.warstwice) siatkaTerenu else null, ustawienia.krokWarstwicM)

    // Wysokość terenu w miejscu startu — punkt odniesienia dla wysokości z misji.
    val terenDomu = remember(teren.wersja, stan.domSzerokosc, stan.domDlugosc, stan.domUstalony) {
        if (stan.domUstalony) teren.wysokosc(stan.domSzerokosc, stan.domDlugosc) else Float.NaN
    }

    BoxWithConstraints(modifier.background(TLO_MAPY).clipToBounds()) {
        val bok = min(maxWidth.value, maxHeight.value)
        val dpNaMetr = bok / zasiegM
        val srodekX = maxWidth / 2
        val srodekY = maxHeight / 2
        val wersja = magazyn.wersja

        /** Pozycja geograficzna → punkt na ekranie (w dp). */
        fun doEkranu(lat: Double, lon: Double): Pair<Dp, Dp> {
            val n = (lat - srodekLat) * Wspolrzedne.METRY_NA_STOPIEN
            val e = (lon - srodekLon) * Wspolrzedne.METRY_NA_STOPIEN *
                    cos(Math.toRadians(srodekLat))
            return (srodekX + (e * dpNaMetr).dp) to (srodekY - (n * dpNaMetr).dp)
        }

        /** Punkt na ekranie (w px) → pozycja geograficzna. */
        fun zEkranu(x: Float, y: Float): Wspolrzedne.Pozycja {
            val xdp = with(gestosc) { x.toDp() }
            val ydp = with(gestosc) { y.toDp() }
            val e = (xdp - srodekX).value / dpNaMetr
            val n = (srodekY - ydp).value / dpNaMetr
            return Wspolrzedne.Pozycja(
                srodekLat + n / Wspolrzedne.METRY_NA_STOPIEN,
                srodekLon + e / (Wspolrzedne.METRY_NA_STOPIEN * cos(Math.toRadians(srodekLat))),
            )
        }

        Canvas(
            Modifier
                .fillMaxSize()
                // Jeden detektor na przesuwanie I szczypanie. Dwa osobne biły się o gest:
                // przy dwóch palcach `detectDragGestures` zjadał zdarzenia i zoom nie ruszał.
                .pointerInput(srodekLat, srodekLon, zasiegM) {
                    detectTransformGestures { _, przesuniecie, powiekszenie, _ ->
                        if (powiekszenie != 1f) zoom(Zasieg.plynnie(zasiegTeraz, powiekszenie))
                        if (przesuniecie != Offset.Zero) {
                            val dE = with(gestosc) { -przesuniecie.x.toDp() }.value / dpNaMetr
                            val dN = with(gestosc) { przesuniecie.y.toDp() }.value / dpNaMetr
                            przesun(dE, dN)
                        }
                    }
                }
                .pointerInput(srodekLat, srodekLon, zasiegM, edycjaMozliwa) {
                    detectTapGestures { punkt ->
                        if (edycjaMozliwa) dodaj(zEkranu(punkt.x, punkt.y))
                    }
                }
        ) {
            wersja.let { }
            val skala = dpNaMetr * density              // px na metr
            val srodekPx = Offset(size.width / 2f, size.height / 2f)
            val mpp = 1f / skala
            val poziom = magazyn.najblizszyPoziom(podklad.baza, Kafelki.poziomDla(mpp, srodekLat))
            val wspolczynnik =
                if (srodekLat != 0.0) Kafelki.metryNaPiksel(srodekLat, poziom) / mpp else 1f

            fun punkt(lat: Double, lon: Double): Offset {
                val n = (lat - srodekLat) * Wspolrzedne.METRY_NA_STOPIEN
                val e = (lon - srodekLon) * Wspolrzedne.METRY_NA_STOPIEN *
                        cos(Math.toRadians(srodekLat))
                return Offset(srodekPx.x + (e * skala).toFloat(), srodekPx.y - (n * skala).toFloat())
            }

            val jest = kafelki(magazyn, podklad.katalogi, srodekLat, srodekLon, poziom,
                srodekPx, wspolczynnik)
            // Na mapie planowania przyciemniamy o połowę słabiej niż w locie: tu podkład
            // jest treścią — operator wybiera z niego miejsce na punkt trasy.
            if (jest) drawRect(Color.Black.copy(alpha = podklad.przyciemnienie / 2f))
            else siatka(srodekPx, skala, krokSiatki(zasiegM))

            if (rzezba) {
                val bokM = zasiegM * Nakladki.WSPOLCZYNNIK
                rysujCieniowanie(cieniowanie, srodekPx, bokM, skala)
                rysujWarstwice(warstwice, srodekPx, bokM, skala,
                    if (Barwy.ciemny) Color(0xFFC9A227) else Color(0xFF7A5C00))
            }

            if (ustawienia.azymut && stan.domUstalony) {
                val dom = punkt(stan.domSzerokosc, stan.domDlugosc)
                rysujAzymut(dom, min(size.width, size.height) / 2f - 10.dp.toPx(),
                    Barwy.InstrTusz3, Barwy.Akcent)
            }

            // geofence — linia przerywana, bo obrys bez włączonego geofence to tylko rysunek
            if (stan.domUstalony && geofenceM > 0f) {
                val dom = punkt(stan.domSzerokosc, stan.domDlugosc)
                drawCircle(
                    Barwy.Uwaga.copy(alpha = 0.7f),
                    radius = geofenceM * skala,
                    center = dom,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
                    ),
                )
            }

            // trasa — odcinkami, w kolejności punktów
            val naMapie = misja.naMapie
            if (naMapie.size > 1) {
                val sciezka = Path()
                naMapie.forEachIndexed { i, p ->
                    val o = punkt(p.szerokosc, p.dlugosc)
                    if (i == 0) sciezka.moveTo(o.x, o.y) else sciezka.lineTo(o.x, o.y)
                }
                drawPath(sciezka, Barwy.Akcent.copy(alpha = 0.9f),
                    style = Stroke(width = 2.5.dp.toPx()))
            }

            // odcinek z domu do pierwszego punktu — zaznacza, gdzie trasa się zaczyna
            if (stan.domUstalony && naMapie.isNotEmpty()) {
                val dom = punkt(stan.domSzerokosc, stan.domDlugosc)
                val pierwszy = punkt(naMapie[0].szerokosc, naMapie[0].dlugosc)
                drawLine(
                    Barwy.Dobrze.copy(alpha = 0.6f), dom, pierwszy,
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                )
            }

            if (stan.domUstalony) {
                znacznikDomu(punkt(stan.domSzerokosc, stan.domDlugosc), 9.dp.toPx())
            }

            podzialka(krokSiatki(zasiegM), skala, 0.dp)

            if (stan.pozycjaZnana) {
                val maszyna = punkt(stan.szerokosc, stan.dlugosc)
                val kurs = if (stan.kursGnssDostepny) stan.kursGnssSt else stan.kursSt
                rotate(kurs, maszyna) {
                    val r = 12.dp.toPx()
                    drawPath(Path().apply {
                        moveTo(maszyna.x, maszyna.y - r)
                        lineTo(maszyna.x - r * 0.72f, maszyna.y + r * 0.8f)
                        lineTo(maszyna.x, maszyna.y + r * 0.35f)
                        lineTo(maszyna.x + r * 0.72f, maszyna.y + r * 0.8f)
                        close()
                    }, if (stan.uzbrojony) Barwy.Uwaga else Color.White)
                }
            }
        }

        // Znaczniki punktów jako elementy interfejsu, nie rysunek — dzięki temu mają własny
        // cel dotykowy i podpis wysokości, którego w Canvasie trzeba by mierzyć ręcznie.
        misja.punkty.forEachIndexed { i, p ->
            if (!p.naMapie) return@forEachIndexed
            val numer = misja.naMapie.indexOf(p) + 1
            val (x, y) = doEkranu(p.szerokosc, p.dlugosc)
            val przeswit = if (terenDomu.isNaN()) Float.NaN else {
                val podPunktem = teren.wysokosc(p.szerokosc, p.dlugosc)
                if (podPunktem.isNaN()) Float.NaN else terenDomu + p.wysokoscM - podPunktem
            }
            ZnacznikPunktu(
                numer = numer,
                wysokoscM = p.wysokoscM,
                przeswitM = przeswit,
                wybrany = i == wybrany,
                naWybor = { naWybor(i) },
                modifier = Modifier.offset(
                    x = x - Wymiary.ZnacznikPunktu / 2,
                    y = y - Wymiary.ZnacznikPunktu / 2,
                ),
            )
        }

        if (trzebaOstrzec(magazyn, podklad)) {
            Text(
                brakPodkladu(podklad, magazyn.usterkaSieci),
                color = if (podklad.wymagany) Barwy.Uwaga else Barwy.Wygasly, fontSize = 10.sp,
                lineHeight = 13.sp,
                // Nad podpisem trybu (48 dp) i nad podziałką skali (14 dp) — inaczej diagnoza
                // kładzie się na „100 m" i na podpowiedzi o dokładaniu punktów.
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 62.dp)
                    .widthIn(max = 420.dp),
            )
        }
    }
}

/**
 * Znacznik punktu trasy: **ścięty kwadrat 24 dp z numerem**, obok podpis wysokości.
 * Wybrany dostaje wypełnienie akcentu — nie samą obwódkę, bo w słońcu obwódka ginie.
 *
 * Pod wysokością zadaną stoi **prześwit nad gruntem** — jedyna liczba, która mówi, czy
 * ten punkt jest do przelecenia. Bez danych wysokościowych zostaje kreska, nie zero.
 */
@Composable
private fun ZnacznikPunktu(
    numer: Int,
    wysokoscM: Float,
    przeswitM: Float,
    wybrany: Boolean,
    naWybor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barwa = barwaPrzeswitu(przeswitM)
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(Wymiary.ZnacznikPunktu)
                .plyta(
                    6.dp,
                    if (wybrany) Barwy.Akcent else Barwy.TaflaPelna,
                    if (przeswitM <= 0f && !przeswitM.isNaN()) Barwy.Blokada else Barwy.Akcent,
                    1.dp,
                )
                .pointerInput(numer, wybrany) { detectTapGestures(onTap = { naWybor() }) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$numer",
                style = Kroje.liczba(11.sp, FontWeight.SemiBold,
                    if (wybrany) Barwy.NaAkcencie else Barwy.Tekst),
            )
        }
        // Podpis wysokosci po PRAWEJ stronie znacznika, nie pod nim: pod spodem wchodzil
        // na trase i na sasiedni punkt (makieta: left 26px, top 3px).
        Column(Modifier.padding(start = 2.dp, top = 1.dp)) {
            Text(
                "%.0f m".format(wysokoscM),
                color = Barwy.Drugi, fontSize = 9.sp, letterSpacing = 0.5.sp, maxLines = 1,
            )
            Text(
                if (przeswitM.isNaN()) "— agl" else "%+.0f agl".format(przeswitM),
                color = barwa, fontSize = 9.sp, letterSpacing = 0.5.sp, maxLines = 1,
            )
        }
    }
}
