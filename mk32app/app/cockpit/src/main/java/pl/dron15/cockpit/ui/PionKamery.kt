package pl.dron15.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.dron15.cockpit.domain.StanMaszyny

/** Co zrobi krótkie dotknięcie migawki. */
enum class TrybMigawki { FOTO, WIDEO }

private const val GORA = 25f
private const val DOL = -90f
private const val ZAKRES = GORA - DOL          // 115° — pełny zakres pochylenia ZR30

/**
 * Pion kamery — makieta `Kokpit M3.dc.html`, §4 przekazania.
 *
 * **58 × 300 dp przy krawędzi, klawisz migawki na jego środku, podpis kierunku i zoom
 * pod spodem w jednym kontenerze.**
 *
 * Skala pokazuje **−90…+25°, czyli dokładnie zakres ZR30**: znacznik nad środkiem znaczy,
 * że głowica patrzy w górę, pod środkiem — w dół. Odczyt stopni siedzi **po lewej stronie
 * skali**, bo po prawej jest krawędź ekranu.
 *
 * ### Jeden kontener zamiast trzech kotwic
 *
 * Przekazanie mówi wprost: podpis kierunku i krotność zoomu mają siedzieć **w jednym
 * kontenerze z odstępem**, nie na dwóch osobnych kotwicach — to była przyczyna nakładania
 * się w makiecie.
 */
@Composable
fun KolumnaKamery(
    stan: StanMaszyny,
    tryb: TrybMigawki,
    naMigawke: () -> Unit,
    naZmianeTrybu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(Wymiary.PionSzer, Wymiary.PionWys)) {

        SkalaPochylenia(stan, Modifier.fillMaxSize())

        // Żywy odczyt — poza kolumną, po jej lewej stronie, na wysokości znacznika.
        if (stan.glowicaOdpowiada) {
            Text(
                "%.0f°".format(stan.glowicaPitch),
                style = Kroje.liczba(13.sp, FontWeight.SemiBold, Barwy.Akcent),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = (-46).dp,
                        y = Wymiary.PionWys * udzialPionu(stan.glowicaPitch) - 8.dp,
                    ),
            )
        }

        KlawiszMigawki(
            tryb, stan.glowicaNagrywa, naMigawke, naZmianeTrybu,
            Modifier.align(Alignment.CenterStart).offset(x = (-2).dp),
        )

        // Podpis kierunku i zoom — jeden kontener pod skalą, odstęp z `gap`.
        Column(
            Modifier
                // Stopka wychodzi PONIZEJ kolumny: przy wyrownaniu do dolu jej dolna
                // krawedz lezy na dole skali, wiec przesuniecie to jej wlasna wysokosc
                // plus odstep. Wczesniej bylo `PionWys + 6`, co wypychalo ja poza ekran.
                .align(Alignment.BottomCenter)
                .offset(y = 42.dp)
                .width(Wymiary.PionSzer),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                kierunekGlowicy(stan.glowicaPitch),
                color = if (stan.glowicaOdpowiada) Barwy.Drugi else Barwy.Wygasly,
                fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1,
            )
            Text(
                "%.1f×".format(stan.glowicaZoom),
                style = Kroje.liczba(16.sp, FontWeight.SemiBold,
                    if (stan.glowicaOdpowiada) Barwy.Tekst else Barwy.Wygasly),
                maxLines = 1,
            )
        }
    }
}

/** Udział 0..1 od góry skali dla podanego pochylenia. */
private fun udzialPionu(pochylenie: Float): Float =
    (GORA - pochylenie.coerceIn(DOL, GORA)) / ZAKRES

/** Jedno słowo ze strzałką — dwuwyrazowy podpis nie mieści się w 58 dp kolumny. */
private fun kierunekGlowicy(pitch: Float): String = when {
    pitch < -4f -> "▼ DÓŁ"
    pitch > 4f -> "▲ GÓRA"
    else -> "POZIOM"
}

/**
 * Skala pochylenia: rama z włosów u góry, u dołu i po osi, kreski od prawej krawędzi
 * (dłuższe co 45°), znacznik od lewej.
 */
@Composable
private fun SkalaPochylenia(stan: StanMaszyny, modifier: Modifier = Modifier) {
    val zywa = stan.glowicaOdpowiada
    Canvas(modifier) {
        val w = 1.dp.toPx()

        drawRect(Barwy.Linia2, size = Size(size.width, w))
        drawRect(Barwy.Linia2, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
        drawRect(Barwy.Linia2, topLeft = Offset(size.width / 2f - w, 0f), size = Size(w, size.height))

        listOf(-90, -60, -45, -30, -15, 0, 25).forEach { kat ->
            val dluga = kat % 45 == 0
            val dl = if (dluga) 14.dp.toPx() else 8.dp.toPx()
            val y = size.height * ((90 + kat) / ZAKRES)
            drawRect(
                if (kat == 0) Barwy.Drugi else Barwy.Linia,
                topLeft = Offset(size.width - dl, y),
                size = Size(dl, w),
            )
        }

        if (zywa) {
            val y = size.height * udzialPionu(stan.glowicaPitch)
            drawRect(Barwy.Akcent, topLeft = Offset(0f, y - w), size = Size(20.dp.toPx(), 2 * w))
        }
    }
}

/**
 * Klawisz migawki — **okrągły, 58 dp, na środku pionu kamery**.
 *
 * Jeden klawisz zamiast pary FOTO + REC, bo aparat też ma jeden.
 *
 * | Gest | Skutek |
 * |---|---|
 * | krótkie dotknięcie, tryb WIDEO | start / stop nagrywania |
 * | krótkie dotknięcie, tryb FOTO | wyzwolenie migawki |
 * | przytrzymanie 700 ms | zmiana trybu |
 *
 * Postęp przytrzymania wypełnia klawisz **od dołu** — tak jak w makiecie. Przy nagrywaniu
 * obwódka i ikona czerwienieją; to jedyne miejsce, gdzie czerwień nie znaczy blokady,
 * i wynika z tego, że tak wygląda każdy aparat.
 */
@Composable
private fun KlawiszMigawki(
    tryb: TrybMigawki,
    nagrywa: Boolean,
    naMigawke: () -> Unit,
    naZmianeTrybu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var postep by remember { mutableStateOf(0f) }
    var trzymany by remember { mutableStateOf(false) }
    var zmienionoTryb by remember { mutableStateOf(false) }
    val haptyka = LocalHapticFeedback.current
    val czerwony = tryb == TrybMigawki.WIDEO && nagrywa
    val kolor = if (czerwony) Barwy.Blokada else Barwy.Akcent
    val tresc = if (czerwony) Barwy.Blokada else Barwy.Tekst

    LaunchedEffect(trzymany) {
        if (!trzymany) {
            postep = 0f
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (trzymany && postep < 1f) {
            postep = ((System.currentTimeMillis() - start).toFloat() / CZAS_ZMIANY_TRYBU)
                .coerceAtMost(1f)
            if (postep >= 1f) {
                haptyka.performHapticFeedback(HapticFeedbackType.LongPress)
                zmienionoTryb = true
                naZmianeTrybu()
                trzymany = false
            }
            delay(16)
        }
    }

    Box(
        modifier
            .size(Wymiary.Migawka)
            .clip(CircleShape)
            .background(Barwy.TaflaMocna)
            .background(if (czerwony) Barwy.BlokadaTlo else Color.Transparent)
            .drawBehind {
                if (postep > 0f) {
                    drawRect(
                        Barwy.StanMocny,
                        topLeft = Offset(0f, size.height * (1f - postep)),
                        size = Size(size.width, size.height * postep),
                    )
                }
                drawCircle(kolor, radius = size.width / 2f - 1.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx()))
            }
            .pointerInput(tryb, nagrywa) {
                detectTapGestures(
                    onPress = {
                        zmienionoTryb = false
                        trzymany = true
                        tryAwaitRelease()
                        trzymany = false
                    },
                    // Krótkie dotknięcie działa tylko wtedy, gdy przytrzymanie nie zdążyło
                    // przestawić trybu — inaczej jedno naciśnięcie robiłoby obie rzeczy.
                    onTap = { if (!zmienionoTryb) naMigawke() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Ikona(
                when {
                    tryb == TrybMigawki.FOTO -> Piktogram.FOTO
                    nagrywa -> Piktogram.STOP_REC
                    else -> Piktogram.REC
                },
                kolor = tresc, rozmiar = 20.dp,
            )
            Text(
                if (tryb == TrybMigawki.WIDEO) (if (nagrywa) "STOP" else "REC") else "FOTO",
                color = tresc, fontSize = 9.sp, letterSpacing = 1.1.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private const val CZAS_ZMIANY_TRYBU = 700L

/**
 * Potwierdzenie zmiany trybu migawki. Pokazuje się na 1,5 s po przytrzymaniu — bez tego
 * przytrzymanie nie daje żadnego sygnału zwrotnego poza zmianą ikony, której pilot
 * przy słońcu może nie zauważyć.
 */
@Composable
fun PotwierdzenieMigawki(tryb: TrybMigawki, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(30.dp)
            .background(Barwy.TaflaPelna)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(Barwy.Akcent, size = Size(size.width, w))
                drawRect(Barwy.Akcent, topLeft = Offset(0f, size.height - w),
                    size = Size(size.width, w))
                drawRect(Barwy.Akcent, size = Size(w, size.height))
                drawRect(Barwy.Akcent, topLeft = Offset(size.width - w, 0f),
                    size = Size(w, size.height))
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("MIGAWKA — ${tryb.name}", style = Kroje.zgeszczona(16.sp, Barwy.Akcent))
    }
}
