package pl.dron15.cockpit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Ciag
import pl.dron15.cockpit.domain.StanMaszyny

/**
 * **ZAPAS CIĄGU** i **ROZRZUT** — przyrząd, którego brak kosztował lot 3 z 2026-08-16.
 *
 * Czyta `SERVO_OUTPUT_RAW` przez [Ciag]. Gdy ta wiadomość nie dochodzi — a może nie
 * dochodzić, bo `SERIAL6_OPTIONS = 4096` każe maszynie ignorować prośby o stawki —
 * pas mówi **„BRAK DANYCH O SILNIKACH"** zamiast rysować zero. Przyrząd, który przy
 * braku danych pokazuje wartość bezpieczną, jest gorszy niż jego brak.
 *
 * Układ: 320 × 34 dp, słupek wykorzystania zakresu z trzema progami, obok liczby.
 */
@Composable
fun PasZapasu(
    stan: StanMaszyny,
    teraz: Long,
    modifier: Modifier = Modifier,
) {
    val znane = stan.wyjsciaZnane(teraz)
    val zapas = if (znane) Ciag.policz(
        stan.wyjsciaSilnikow,
        stan.parametry["MOT_SPIN_MAX"] ?: Ciag.SPIN_MAX_DOMYSLNY,
        Ciag.mapowanieZgodne(stan.parametry),
    ) else Ciag.Zapas(znany = false)

    Row(
        modifier
            .width(Wymiary.PasZapasuSzer)
            .height(Wymiary.PasPrzyrzadow)
            .plyta(7.dp, Barwy.TaflaMocna, kolorOceny(zapas.ocena))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⛔ Bez `return@Row`. Wczesny powrót z lambdy kompozycyjnej psuje stos grup
        // Compose i wywraca aplikację (`ArrayIndexOutOfBoundsException` w `SlotTable`).
        // Zdarzyło się to już raz, w EkranMisji — patrz dok/AUDYT_M3.md §7.
        if (!zapas.znany) {
            Ikona(Piktogram.CIAG, kolor = Barwy.Wygasly, rozmiar = 13.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                "BRAK DANYCH O SILNIKACH",
                style = Kroje.zgeszczona(13.sp, Barwy.Wygasly),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text("SERVO_OUTPUT_RAW", color = Barwy.Wygasly, fontSize = 9.sp, maxLines = 1)
        } else {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Ikona(Piktogram.CIAG, kolor = Barwy.Drugi, rozmiar = 13.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${zapas.zapasUs}",
                        style = Kroje.liczba(19.sp, FontWeight.Bold, kolorOceny(zapas.ocena)),
                    )
                    Text(" µs", color = Barwy.Drugi, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Wibracje(stan, teraz)
                    Spacer(Modifier.width(8.dp))
                    Ikona(Piktogram.GAZ, kolor = Barwy.Drugi, rozmiar = 13.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "${stan.gazProc}%",
                        style = Kroje.liczba(13.sp, FontWeight.SemiBold, Barwy.Tekst),
                    )
                }
                Spacer(Modifier.height(3.dp))
                SlupekZapasu(zapas)
            }

            Spacer(Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                Ikona(Piktogram.ROZRZUT, kolor = Barwy.Drugi, rozmiar = 13.dp)
                Text(
                    "${zapas.rozrzutUs}",
                    style = Kroje.liczba(15.sp, FontWeight.Bold, kolorOceny(zapas.ocenaRozrzutu)),
                )
                zapas.skladowe?.let {
                    Text(it.dominujaca, color = Barwy.Wygasly, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Wibracje — szczyt z trzech osi, przy rozrzucie silników, bo to ta sama mechanika.
 *
 * Próg ostrzegawczy ArduPilota to 30 m/s²; przycięcie akcelerometru (`clipping`)
 * to osobna i poważniejsza sprawa, więc dostaje własny znak. Zmierzone na tej maszynie:
 * 1,4–2,5 m/s² normalnie, **17,2 m/s²** przy oscylacji hamowania w locie 5
 * (`CLAUDE.md` poz. 51).
 */
@Composable
private fun Wibracje(stan: StanMaszyny, teraz: Long) {
    if (!stan.wibracjeZnane(teraz)) return

    val szczyt = stan.wibracjeSzczyt
    val kolor = when {
        stan.przyciecia > 0 || szczyt >= PROG_WIBRACJI -> Barwy.Blokada
        szczyt >= PROG_WIBRACJI * 0.5f -> Barwy.Uwaga
        else -> Barwy.Tekst
    }
    Ikona(Piktogram.WIBRACJE, kolor = Barwy.Drugi, rozmiar = 13.dp)
    Spacer(Modifier.width(3.dp))
    Text("%.0f".format(szczyt),
        style = Kroje.liczba(13.sp, FontWeight.SemiBold, kolor), maxLines = 1)
    if (stan.przyciecia > 0) Text("⚠", color = Barwy.Blokada, fontSize = 11.sp, maxLines = 1)
}

/** Próg ostrzegawczy ArduPilota dla wibracji [m/s²]. */
private const val PROG_WIBRACJI = 30f

/**
 * Słupek wykorzystania zakresu wyjścia. Znaczniki stoją tam, gdzie leżą progi zapasu —
 * pilot ma widzieć **jak blisko sufitu**, a nie samą liczbę.
 */
@Composable
private fun SlupekZapasu(zapas: Ciag.Zapas) {
    val kolor = kolorOceny(zapas.ocena)
    Box(
        Modifier
            .fillMaxWidth()
            .height(9.dp)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(Barwy.Linia2, size = Size(size.width, size.height), style =
                    androidx.compose.ui.graphics.drawscope.Stroke(w))
                drawRect(kolor.copy(alpha = 0.85f),
                    topLeft = Offset(w, w),
                    size = Size((size.width - 2 * w) * zapas.wypelnienie, size.height - 2 * w))

                // Progi: liczone od sufitu w dół, więc na słupku leżą przy prawej krawędzi.
                val zakres = (zapas.sufitUs - 1000).toFloat()
                if (zakres > 0f) {
                    listOf(
                        Ciag.PROG_UWAGI_US to Barwy.Uwaga,
                        Ciag.PROG_BLOKADY_US to Barwy.Blokada,
                    ).forEach { (prog, barwa) ->
                        val x = size.width * (1f - prog / zakres)
                        drawRect(barwa, topLeft = Offset(x, 0f), size = Size(w * 2, size.height))
                    }
                }
            }
    )
}

private fun kolorOceny(o: Ciag.Ocena): Color = when (o) {
    Ciag.Ocena.DOBRZE -> Barwy.Dobrze
    Ciag.Ocena.UWAGA -> Barwy.Uwaga
    Ciag.Ocena.OSTRZEZENIE -> Barwy.Uwaga
    Ciag.Ocena.BLOKADA -> Barwy.Blokada
}
