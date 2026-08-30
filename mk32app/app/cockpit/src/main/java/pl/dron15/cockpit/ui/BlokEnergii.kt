package pl.dron15.cockpit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Energia
import pl.dron15.cockpit.domain.StanMaszyny

/**
 * **ENERGIA** — jedyny działający wskaźnik paliwa na tej maszynie.
 *
 * Napięcie jest martwe (`CLAUDE.md` poz. 37), więc słupek baterii na belce nic nie znaczy.
 * Znaczy licznik amperogodzin — dekodowany od początku i do 2026-08-26 nigdzie
 * niepokazywany.
 *
 * **JOKER** i **BINGO** pojawiają się tylko wtedy, gdy `BATT_CAPACITY` wygląda sensownie.
 * Przy dzisiejszych 3300 mAh wobec 4538 mAh zużytych w jednym locie (poz. 40) blok mówi
 * wprost, że pojemność jest niekalibrowana, i **pokazuje same mAh i ampery**.
 */
@Composable
fun BlokEnergii(
    stan: StanMaszyny,
    teraz: Long,
    modifier: Modifier = Modifier,
) {
    val b = Energia.policz(stan, teraz)

    Column(
        modifier
            .width(Wymiary.BlokEnergiiSzer)
            .height(Wymiary.PasPrzyrzadow)
            .plyta(7.dp, Barwy.TaflaMocna, kolorEnergii(b))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Ikona(Piktogram.BATERIA, kolor = Barwy.Drugi, rozmiar = 13.dp)
            Spacer(Modifier.weight(1f))
            Text(
                "%.0f".format(if (b.pradA > 0.1f) b.pradA else b.sredniPradA),
                style = Kroje.liczba(13.sp, FontWeight.SemiBold, Barwy.Tekst),
            )
            Text(" A", color = Barwy.Drugi, fontSize = 10.sp)
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${b.zuzycieMah}",
                style = Kroje.liczba(19.sp, FontWeight.Bold, Barwy.Tekst),
            )
            Text(" mAh", color = Barwy.Drugi, fontSize = 10.sp)
            if (b.wiarygodny) {
                Spacer(Modifier.weight(1f))
                Text("%.0f%%".format((1f - b.udzial) * 100f),
                    style = Kroje.liczba(13.sp, FontWeight.SemiBold, kolorEnergii(b)))
            }
        }

        Spacer(Modifier.height(2.dp))

        // Progi czasowe przeniesione na belkę (ui/PoleCzasuDoProgu.kt, 2026-08-28):
        // to informacja czasowa i czyta się ją razem z zegarem lotu, a nie z mAh.
        if (b.wiarygodny) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("pozostało %d mAh".format(b.pojemnoscMah - b.zuzycieMah),
                    color = Barwy.Wygasly, fontSize = 9.sp, maxLines = 1)
            }
        } else {
            Text(
                b.powodNiepewnosci ?: "brak kalibracji pakietu",
                color = Barwy.Uwaga, fontSize = 9.sp, maxLines = 2,
            )
        }
    }
}

private fun kolorEnergii(b: Energia.Bilans) = when {
    !b.wiarygodny -> Barwy.Uwaga
    b.poBingo -> Barwy.Blokada
    b.poJokerze -> Barwy.Uwaga
    else -> Barwy.Dobrze
}
