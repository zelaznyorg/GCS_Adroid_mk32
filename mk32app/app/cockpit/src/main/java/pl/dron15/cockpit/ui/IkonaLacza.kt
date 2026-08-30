package pl.dron15.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pl.dron15.cockpit.domain.StanMaszyny

/**
 * Stan łącza telemetrycznego jako **słupki zasięgu, nie liczba z podpisem**.
 *
 * Decyzja Toma 2026-08-28: *„zamiast ŁĄCZE daj ikonę łącza i pokazuj kolorem wypełnienia —
 * jak link jest ok to zielony, jak spadnie to pomarańczowy, plus brak to czerwony"*.
 *
 * Niesie dwie rzeczy naraz i obie bez słowa:
 * - **ile słupków jest wypełnionych** — jak szybko przychodzą ramki,
 * - **jakim kolorem** — czy łącze w ogóle żyje.
 *
 * Kształt i barwa mówią to samo dwa razy. To nie jest nadmiar: w palecie NVG wszystko
 * leży w rodzinie czerwieni i wtedy zostaje sama liczba wypełnionych słupków.
 *
 * ### Progi
 *
 * Zmierzone na tej maszynie: **48 Hz na aparaturze MK32** przez Wi-Fi, ok. 100 Hz
 * na emulatorze po pętli lokalnej. Dlatego pełne cztery słupki zaczynają się już od 30 Hz,
 * a nie od stu — inaczej na prawdziwym sprzęcie nigdy nie byłoby zielono.
 */
@Composable
fun IkonaLacza(stan: StanMaszyny, teraz: Long, rozmiar: androidx.compose.ui.unit.Dp = 16.dp) {
    val zywa = stan.telemetriaZywa(teraz)
    val hz = stan.ramekNaSekunde

    val kolor = when {
        !zywa -> Barwy.Blokada
        hz < PROG_SLABE_HZ -> Barwy.Uwaga
        else -> Barwy.Dobrze
    }
    val pelne = when {
        !zywa -> 0
        hz >= PROG_PELNE_HZ -> 4
        hz >= PROG_DOBRE_HZ -> 3
        hz >= PROG_SLABE_HZ -> 2
        else -> 1
    }

    Canvas(Modifier.size(rozmiar)) {
        val w = size.width
        val g = w * 0.17f
        val odstep = w * 0.10f
        repeat(4) { i ->
            val h = w * (0.26f + 0.22f * i)
            val x = i * (g + odstep)
            val y = w - h
            if (i < pelne) {
                drawRect(kolor, topLeft = Offset(x, y), size = Size(g, h))
            } else {
                // Pusty słupek zostaje konturem — brak zasięgu ma być widoczny
                // jako **brak wypełnienia**, a nie jako brak elementu.
                drawRect(
                    Barwy.Wygasly,
                    topLeft = Offset(x, y),
                    size = Size(g, h),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.05f),
                )
            }
        }
        // Przekreślenie przy martwym łączu: kolor sam nie wystarcza w NVG.
        if (!zywa) {
            drawLine(
                Barwy.Blokada,
                Offset(0f, w), Offset(w * 0.92f, w * 0.08f),
                strokeWidth = w * 0.10f,
            )
        }
    }
}

/** Poniżej tylu ramek na sekundę łącze jest **słabe**, nie martwe. */
private const val PROG_SLABE_HZ = 8f
private const val PROG_DOBRE_HZ = 16f

/** Od tylu ramek świecą wszystkie cztery słupki. MK32 daje ok. 48 Hz. */
private const val PROG_PELNE_HZ = 30f

/** Kolor stanu łącza — do podpisania go liczbą, gdy jest miejsce. */
fun kolorLacza(stan: StanMaszyny, teraz: Long): Color = when {
    !stan.telemetriaZywa(teraz) -> Barwy.Blokada
    stan.ramekNaSekunde < PROG_SLABE_HZ -> Barwy.Uwaga
    else -> Barwy.Dobrze
}
