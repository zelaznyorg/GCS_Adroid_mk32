package pl.dron15.cockpit.ui

import android.graphics.Paint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Wiatr
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.video.OdtwarzaczVlc
import kotlin.math.abs

/**
 * Przyrządy ekranu LOT — makieta `Kokpit M3.dc.html`, §4 przekazania.
 *
 * Oba są **okrągłe albo chowane**, żeby środek kadru został pusty: wskaźnik położenia
 * to rysunek bez płyty, a miniatura mapy zjeżdża do samego uchwytu.
 */

// --------------------------------------------------------------------------- wskaźnik położenia

/** 5,4 dp na stopień — z makiety: ±10° pochylenia na wnętrzu koła 132 dp. */
private const val DP_NA_STOPIEN = 5.4f
private const val ZAKRES_PRZECHYLU = 30f

/**
 * Wskaźnik położenia — **okrągły, bez płyty, z pierścieniem wiatru**.
 *
 * To nie jest sztuczny horyzont na pół ekranu, którego UI.md §7 zakazuje. Obraz z kamery
 * na stabilizowanej głowicy jest wypoziomowany i nie mówi nic o położeniu maszyny; koło
 * odpowiada na jedno pytanie — czy maszyna leci prosto.
 *
 * Wnętrze ma **niebo i ziemię** z własnych tokenów (`--niebo`, `--ziemia`): to one robią
 * z tego przyrząd czytelny nad każdym kadrem i w obu motywach. Skala przechyłu u góry
 * przechyla się **o połowę** wartości — dzięki temu przy ±30° wciąż mieści się w kole.
 *
 * ### Wiatr na pierścieniu — decyzja Toma 2026-08-28
 *
 * Wiatr siedział wcześniej na taśmie kursu, czyli **w drugim miejscu na ekranie**.
 * *„Chcę, aby wszystkie informacje były dostępne dla pilota centralnie i nie musiał
 * ich szukać"* — więc strzałka wiatru obiega teraz to samo koło, na którym pilot czyta
 * położenie.
 *
 * Kierunek jest podany **względem dziobu**, nie względem północy: strzałka u góry znaczy
 * wiatr w twarz, u dołu — w plecy. Tak się o wietrze myśli w locie, bo od tego zależy,
 * w którą stronę maszynę zniesie i gdzie zniknie zapas ciągu.
 */
/**
 * Kreska z cieniem — dwa przebiegi: najpierw ciemniejszy i szerszy, na nim wlasciwy.
 *
 * To jest odpowiedz na pytanie, jak zrobic przyrzad czytelny **nie zaslaniajac kadru**.
 * Wypelnienie tarczy probowano 2026-08-28 i zostalo odrzucone od razu: przyrzad stal sie
 * matowym dyskiem na srodku obrazu. Cien daje ten sam kontrast kosztem dwoch pikseli.
 *
 * Cien jest **miekki, nie czarna obwodka**: pierwsza wersja miala krycie 88 % i 2,5 dp
 * rozrostu, przez co przyrzad wygladal jak stary kompas obrysowany tuszem.
 */
private fun DrawScope.kreskaZKonturem(
    lewyGorny: Offset,
    rozmiar: Size,
    kolor: Color,
    kontur: Float = 1.8.dp.toPx(),
) {
    drawRect(
        Barwy.Kontur,
        topLeft = Offset(lewyGorny.x - kontur, lewyGorny.y - kontur),
        size = Size(rozmiar.width + 2 * kontur, rozmiar.height + 2 * kontur),
    )
    drawRect(kolor, topLeft = lewyGorny, size = rozmiar)
}

/** Luk z cieniem — pierscien i obwodka tarczy sa **przerwane**, nie domkniete. */
private fun DrawScope.lukZKonturem(
    srodek: Offset,
    promien: Float,
    odStopni: Float,
    dlugoscStopni: Float,
    kolor: Color,
    grubosc: Float,
) {
    val prostokat = Rect(
        srodek.x - promien, srodek.y - promien,
        srodek.x + promien, srodek.y + promien,
    )
    drawArc(
        Barwy.Kontur, odStopni, dlugoscStopni, false,
        topLeft = prostokat.topLeft, size = prostokat.size,
        style = Stroke(width = grubosc + 3.2.dp.toPx()),
    )
    drawArc(
        kolor, odStopni, dlugoscStopni, false,
        topLeft = prostokat.topLeft, size = prostokat.size,
        style = Stroke(width = grubosc),
    )
}

@Composable
fun OkragPolozenia(
    stan: StanMaszyny,
    teraz: Long,
    modifier: Modifier = Modifier,
    wiatr: Wiatr.Ocena = Wiatr.Ocena(),
) {
    val wiek = stan.wiekTelemetriiS(teraz)
    val stare = wiek > 2f
    val martwe = wiek > 10f
    val przechyl = stan.przechylenieSt
    val pochyl = stan.pochylenieSt
    val kurs = if (stan.kursGnssDostepny) stan.kursGnssSt else stan.kursSt

    Box(modifier.size(Wymiary.Okrag), contentAlignment = Alignment.Center) {

        if (martwe) {
            Text("———", style = Kroje.liczba(Kroje.Duza, kolor = Barwy.Wygasly))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val srodek = Offset(size.width / 2f, size.height / 2f)
                val rZewn = size.width / 2f - 1.dp.toPx()
                val rTarcza = rZewn - Wymiary.PierscienKursu.toPx()
                val naStopien = DP_NA_STOPIEN * density
                val tusz = if (stare) Barwy.Wygasly else Barwy.Tekst

                val tarcza = Path().apply {
                    addOval(Rect(srodek.x - rTarcza, srodek.y - rTarcza,
                        srodek.x + rTarcza, srodek.y + rTarcza))
                }

                // ⛔ ZERO WYPELNIENIA. Wnetrze zostaje przezroczyste — przyrzad lezy
                // na kadrze i nie ma prawa go zaslonic (UI.md par. 7).
                clipPath(tarcza) {
                    rotate(degrees = -przechyl.coerceIn(-90f, 90f), pivot = srodek) {
                        val y = srodek.y + pochyl.coerceIn(-45f, 45f) * naStopien
                        val zapas = size.width
                        drawRect(Barwy.Niebo, topLeft = Offset(-zapas, -zapas),
                            size = Size(size.width + 2 * zapas, zapas + y))
                        drawRect(Barwy.Ziemia, topLeft = Offset(-zapas, y),
                            size = Size(size.width + 2 * zapas, size.height + zapas))

                        // Horyzont: **przerwany w srodku**, zeby symbol maszyny mial swoje
                        // miejsce i nie zlewal sie z linia. Tak robi kazdy wspolczesny HUD.
                        val przerwa = 30.dp.toPx()
                        kreskaZKonturem(Offset(-zapas, y - 1.dp.toPx()),
                            Size(zapas + srodek.x - przerwa, 2.5.dp.toPx()), tusz)
                        kreskaZKonturem(Offset(srodek.x + przerwa, y - 1.dp.toPx()),
                            Size(zapas + srodek.x, 2.5.dp.toPx()), tusz)

                        listOf(5f, -5f).forEach { st ->
                            val dl = 34.dp.toPx()
                            val yy = y - st * naStopien
                            kreskaZKonturem(Offset(srodek.x - dl / 2f, yy),
                                Size(dl, 2.dp.toPx()), tusz)
                        }
                        listOf(2.5f, -2.5f).forEach { st ->
                            val dl = 16.dp.toPx()
                            val yy = y - st * naStopien
                            kreskaZKonturem(Offset(srodek.x - dl / 2f, yy),
                                Size(dl, 1.dp.toPx()), Barwy.Drugi)
                        }
                    }
                }

                // Obwodka tarczy — **cztery luki z przerwami** na godzinie 12, 3, 6 i 9.
                // Domkniete kolo czytalo sie jak tarcza starego kompasu.
                listOf(-78f, 12f, 102f, 192f).forEach { od ->
                    lukZKonturem(srodek, rTarcza, od, 66f, Barwy.Tekst, 1.8.dp.toPx())
                }

                // Pierscien kursu: sama podzialka, bez obwodki i bez wypelnienia.
                rotate(degrees = -kurs, pivot = srodek) {
                    var k = 0
                    while (k < 360) {
                        val duza = k % 90 == 0
                        val srednia = k % 30 == 0
                        if (duza || srednia) {
                            val dl = if (duza) 8.dp.toPx() else 5.dp.toPx()
                            rotate(degrees = k.toFloat(), pivot = srodek) {
                                kreskaZKonturem(
                                    Offset(srodek.x - 0.75.dp.toPx(),
                                        srodek.y - rZewn + 1.dp.toPx()),
                                    Size(1.5.dp.toPx(), dl),
                                    if (duza) Barwy.Tekst else Barwy.Drugi,
                                    kontur = 1.dp.toPx(),
                                )
                            }
                        }
                        k += 30
                    }
                    literyStronSwiata(srodek, rZewn)

                    if (stan.namiarNaDomSt >= 0f) {
                        znacznikDomu(srodek, rZewn, stan.namiarNaDomSt)
                    }
                }

                // Wskaznik dziobu — smukly trojkat u gory.
                fun dziob(rozrost: Float, kolor: Color) = drawPath(Path().apply {
                    moveTo(srodek.x, srodek.y - rZewn + 10.dp.toPx() + rozrost)
                    lineTo(srodek.x - 4.dp.toPx() - rozrost, srodek.y - rZewn - rozrost)
                    lineTo(srodek.x + 4.dp.toPx() + rozrost, srodek.y - rZewn - rozrost)
                    close()
                }, kolor)
                dziob(1.5.dp.toPx(), Barwy.Kontur)
                dziob(0f, Barwy.Akcent)

                // Symbol maszyny — **skrzydelka**, nie krzyz: dwa odcinki i punkt srodka.
                // To jest znak z prawdziwych przyrzadow polozenia i czyta sie od razu.
                val ramie = 26.dp.toPx()
                val skos = 5.dp.toPx()
                fun skrzydla(g: Float, kolor: Color) {
                    val s2 = Stroke(width = g, cap = StrokeCap.Round)
                    drawPath(Path().apply {
                        moveTo(srodek.x - ramie, srodek.y)
                        lineTo(srodek.x - ramie * 0.42f, srodek.y)
                        lineTo(srodek.x - ramie * 0.20f, srodek.y + skos)
                    }, kolor, style = s2)
                    drawPath(Path().apply {
                        moveTo(srodek.x + ramie, srodek.y)
                        lineTo(srodek.x + ramie * 0.42f, srodek.y)
                        lineTo(srodek.x + ramie * 0.20f, srodek.y + skos)
                    }, kolor, style = s2)
                }
                skrzydla(6.dp.toPx(), Barwy.Kontur)
                skrzydla(2.6.dp.toPx(), Barwy.Akcent)
                drawCircle(Barwy.Kontur, radius = 3.dp.toPx(), center = srodek)
                drawCircle(Barwy.Akcent, radius = 1.5.dp.toPx(), center = srodek)

                if (wiatr.znany) strzalkaWiatru(srodek, rZewn, wiatr, kurs)
            }
        }

        // Kurs liczba — w pudelku nad wskaznikiem dziobu.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-8).dp)
                .plyta(4.dp, Barwy.TaflaPelna, Barwy.Akcent, grubosc = 1.dp)
                .padding(horizontal = 5.dp),
        ) {
            Text(
                if (martwe) "———" else if (stan.kursGnssDostepny) "%03.0f".format(kurs) else "---",
                style = Kroje.liczba(13.sp, FontWeight.Bold,
                    if (stan.kursGnssDostepny && !stare) Barwy.Tekst else Barwy.Blokada),
            )
        }

        if (wiatr.znany && !martwe) {
            Row(
                Modifier.align(Alignment.BottomCenter).offset(y = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Ikona(Piktogram.WIATR, kolor = Barwy.Akcent, rozmiar = 11.dp)
                Text("%.0f".format(wiatr.predkoscMs),
                    style = Kroje.liczba(11.sp, FontWeight.SemiBold, Barwy.Akcent))
            }
        }

        OdczytKola(
            if (martwe) "———" else stopnie(przechyl), stare,
            Modifier.align(Alignment.BottomStart).offset(x = (-8).dp, y = 5.dp),
        )
        OdczytKola(
            if (martwe) "———" else stopnie(pochyl), stare,
            Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 5.dp),
        )
    }
}

/**
 * Strzalka wiatru na zewnetrznej krawedzi pierscienia, wzgledem dziobu: u gory wiatr
 * w twarz, u dolu w plecy. Grot skierowany do srodka, bo wiatr napiera na maszyne.
 */
private fun DrawScope.strzalkaWiatru(
    srodek: Offset,
    promien: Float,
    wiatr: Wiatr.Ocena,
    kursSt: Float,
) {
    rotate(degrees = wiatr.wzgledemKursu(kursSt), pivot = srodek) {
        val dl = 7.dp.toPx()
        val szer = 4.dp.toPx()
        val y = srodek.y - promien - dl - 1.dp.toPx()
        fun grot(rozrost: Float, kolor: Color) = drawPath(Path().apply {
            moveTo(srodek.x, y + dl + rozrost)
            lineTo(srodek.x - szer - rozrost, y - rozrost)
            lineTo(srodek.x + szer + rozrost, y - rozrost)
            close()
        }, kolor)
        grot(1.2.dp.toPx(), Barwy.Kontur)
        grot(0f, Barwy.Akcent)
    }
}

/**
 * Znacznik domu na pierscieniu kursu — domek, bo to jedyna nawigacja ratunkowa
 * na maszynie bez kompasu (CLAUDE.md sekcja 5a).
 */
private fun DrawScope.znacznikDomu(srodek: Offset, promien: Float, namiarSt: Float) {
    rotate(degrees = namiarSt, pivot = srodek) {
        val y = srodek.y - promien + Wymiary.PierscienKursu.toPx() * 0.5f
        fun domek(a: Float, kolor: Color) = drawPath(Path().apply {
            moveTo(srodek.x, y - a)
            lineTo(srodek.x - a, y)
            lineTo(srodek.x - a * 0.6f, y)
            lineTo(srodek.x - a * 0.6f, y + a)
            lineTo(srodek.x + a * 0.6f, y + a)
            lineTo(srodek.x + a * 0.6f, y)
            lineTo(srodek.x + a, y)
            close()
        }, kolor)
        domek(5.dp.toPx(), Barwy.Kontur)
        domek(3.6.dp.toPx(), Barwy.Dobrze)
    }
}

/** N, E, S, W na pierscieniu — z cieniem, bo leza wprost na kadrze. */
private fun DrawScope.literyStronSwiata(srodek: Offset, promien: Float) {
    val y = srodek.y - promien + 11.dp.toPx()
    val kontur = Paint().apply {
        color = Barwy.Kontur.toArgb()
        textAlign = Paint.Align.CENTER
        textSize = 9.dp.toPx()
        isFakeBoldText = true
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5.dp.toPx()
    }
    val farba = Paint().apply {
        color = Barwy.Tekst.toArgb()
        textAlign = Paint.Align.CENTER
        textSize = 9.dp.toPx()
        isFakeBoldText = true
        isAntiAlias = true
    }
    listOf(0f to "N", 90f to "E", 180f to "S", 270f to "W").forEach { (kat, znak) ->
        rotate(degrees = kat, pivot = srodek) {
            drawContext.canvas.nativeCanvas.drawText(znak, srodek.x, y, kontur)
            drawContext.canvas.nativeCanvas.drawText(znak, srodek.x, y, farba)
        }
    }
}

/** Opisy „5" przy długich kreskach drabinki — rysowane natywnie, bo są wewnątrz obrotu. */
private fun DrawScope.opisyDrabinki(srodek: Offset, y: Float, naStopien: Float) {
    val farba = Paint().apply {
        color = Barwy.InstrTusz2.toArgb()
        textSize = 8.dp.toPx()
        isAntiAlias = true
    }
    val x = srodek.x + 26.dp.toPx()
    drawContext.canvas.nativeCanvas.drawText("5", x, y - 5f * naStopien + 3.dp.toPx(), farba)
    drawContext.canvas.nativeCanvas.drawText("5", x, y + 5f * naStopien + 3.dp.toPx(), farba)
}

/** Bez „−0,0°": przy wartości poniżej dziesiątej stopnia znak nie niesie informacji. */
private fun stopnie(v: Float): String = if (abs(v) < 0.05f) "0.0" else "%+.1f".format(v)

@Composable
private fun OdczytKola(wartosc: String, stare: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(wartosc, style = Kroje.liczba(13.sp, FontWeight.SemiBold,
            if (stare) Barwy.Wygasly else Barwy.Tekst))
        Text("°", color = Barwy.Drugi, fontSize = 9.sp)
    }
}

// --------------------------------------------------------------------------- miniatura mapy

/**
 * Miniatura mapy — **190 × 126 dp u dołu kolumny, chowana w dół do samego uchwytu**.
 *
 * Trzy rzeczy w jednym elemencie:
 * - pokazuje **to, czego nie ma na kadrze** (przy obrazie w tle jest tu mapa i odwrotnie),
 * - **dotknięcie zamienia ją z kadrem** — zasada 2 z UI.md; osobny klawisz MAPA/OBRAZ
 *   zniknął, bo robił dokładnie to samo,
 * - uchwyt 20 dp chowa ją w dół; schowana zostawia na kadrze wyłącznie uchwyt.
 */
@Composable
fun MiniaturaMapy(
    stan: StanMaszyny,
    teraz: Long,
    tloMapa: Boolean,
    mapa: UstawieniaMapy,
    wysunieta: Boolean,
    naZamiane: () -> Unit,
    naWysuniecie: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val przesuniecie = przesuniecieMiniatury(wysunieta)

    // Gdy w miniaturze ma byc OBRAZ, plyta nie moze mieć wypelnienia: kadr rysuje warstwa
    // zamontowana na stale w `Aplikacja`, ktora lezy POD miniatura. Zostaje sama ramka
    // i uchwyt, ktore maja wlasne tla. Patrz OdtwarzaczVlc.widok.
    Column(
        modifier
            .offset(y = przesuniecie)
            .size(Wymiary.MiniaturaSzer, Wymiary.MiniaturaWys)
            .plyta(14.dp, if (tloMapa) Color.Transparent else Barwy.Tafla, Barwy.Akcent),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Wymiary.Uchwyt)
                .background(Barwy.TaflaMocna)
                .background(Barwy.Stan)
                .pointerInput(wysunieta) { detectTapGestures(onTap = { naWysuniecie() }) }
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Ikona(
                if (wysunieta) Piktogram.STRZALKA_DOL else Piktogram.STRZALKA_GORA,
                kolor = Barwy.Tekst, rozmiar = 14.dp,
            )
            // Po zwezeniu miniatury do 152 dp (poprawka makiety 1.1) pelny podpis
            // wypychal akcje poza uchwyt — "SCHOWAJ" ucinalo sie do "SCH".
            Text(
                if (tloMapa) "OBRAZ" else "MAPA",
                color = Barwy.Tekst, fontSize = 9.sp, letterSpacing = 1.1.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (wysunieta) "SCHOWAJ" else "WYSUŃ",
                style = Kroje.zgeszczona(12.sp, Barwy.Akcent), maxLines = 1,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .pointerInput(tloMapa) { detectTapGestures(onTap = { naZamiane() }) }
        ) {
            if (tloMapa) {
                // Sam kadr przychodzi spod spodu — tu tylko komunikat, gdy go nie ma.
                if (!stan.wideoDziala) {
                    Box(Modifier.fillMaxSize().background(Barwy.Tafla),
                        contentAlignment = Alignment.Center) {
                        Text("BRAK OBRAZU", style = Kroje.zgeszczona(11.sp, Barwy.Uwaga))
                    }
                }
            } else {
                Mapa(stan, teraz, Modifier.fillMaxSize(), zwarta = true, ustawienia = mapa)
            }
        }
    }
}

/**
 * Wysunięcie miniatury. Wspólne dla samej miniatury i dla warstwy obrazu w [Aplikacja],
 * żeby kadr jechał **dokładnie** razem z ramką, w której siedzi.
 */
@Composable
fun przesuniecieMiniatury(wysunieta: Boolean): Dp {
    val przesuniecie by animateDpAsState(
        targetValue = if (wysunieta) 0.dp else Wymiary.MiniaturaWys - Wymiary.Uchwyt,
        animationSpec = tween(durationMillis = 150),
        label = "miniatura",
    )
    return przesuniecie
}
