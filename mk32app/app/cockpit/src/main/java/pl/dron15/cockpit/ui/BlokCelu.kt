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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Ogrodzenie
import pl.dron15.cockpit.domain.StanMaszyny
import kotlin.math.abs

/**
 * **CEL** — dokąd zmierza autopilot i o ile chybia. Plus zapas do geofence.
 *
 * ### Pokazuje się tylko wtedy, gdy jest co pokazać
 *
 * Blok wchodzi w pas przyrządów **wyłącznie w trybach automatycznych** — czyli wtedy,
 * gdy maszyna leci sama i pilot nie ma innego sposobu dowiedzieć się, dokąd. Poza nimi
 * znika i oddaje miejsce, zamiast wisieć z myślnikami. To ta sama zasada, według której
 * pasek czujników rośnie dopiero przy usterce.
 *
 * Do 2026-08-28 w trybie AUTO pilot nie widział nawet numeru punktu, do którego leci
 * (`dok/AUDYT_M3.md`, brak F5 rozszerzony w `dok/PROPOZYCJA_LOT.md` B5).
 *
 * Błąd toru i wysokości ma tu miejsce nieprzypadkowo: poz. 51 opisuje przestrzeliwanie
 * zadanego kąta o 30–50 % przy hamowaniu, a to jest wielkość, w której to widać w locie,
 * a nie dopiero w logu.
 */
@Composable
fun BlokCelu(stan: StanMaszyny, teraz: Long, modifier: Modifier = Modifier) {
    val plot = Ogrodzenie.policz(stan)
    val celZnany = stan.celZnany(teraz)

    Column(
        modifier
            .width(Wymiary.BlokCeluSzer)
            .height(Wymiary.PasPrzyrzadow)
            .plyta(7.dp, Barwy.TaflaMocna, kolorCelu(stan, plot))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Ikona(Piktogram.CELOWNIK, kolor = Barwy.Drugi, rozmiar = 13.dp)
            Spacer(Modifier.width(5.dp))
            if (stan.punktMisji > 0) {
                Text("pkt ${stan.punktMisji}",
                    style = Kroje.liczba(11.sp, FontWeight.Medium, Barwy.Drugi))
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (celZnany && stan.namiarNaCelSt >= 0f)
                    "%03.0f°".format(stan.namiarNaCelSt) else "—",
                style = Kroje.liczba(11.sp, FontWeight.Medium, Barwy.Drugi),
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (celZnany && stan.doPunktuM >= 0f) "%.0f".format(stan.doPunktuM) else "—",
                style = Kroje.liczba(19.sp, FontWeight.Bold, Barwy.Tekst),
            )
            Text(" m", color = Barwy.Drugi, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            if (celZnany) {
                // Znak błędu wysokości mówi więcej niż wartość: „−" znaczy poniżej zadanej.
                Text("Δh %+.0f".format(-stan.bladWysokosciM),
                    style = Kroje.liczba(11.sp, FontWeight.Medium,
                        if (abs(stan.bladWysokosciM) > 5f) Barwy.Uwaga else Barwy.Drugi))
            }
        }

        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (celZnany && abs(stan.bladToruM) > 0.5f) {
                Text("tor %+.0f m".format(stan.bladToruM),
                    color = if (abs(stan.bladToruM) > 5f) Barwy.Uwaga else Barwy.Wygasly,
                    fontSize = 9.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
            }
            ZapasPlotu(plot)
        }
    }
}

/** Zapas do granicy geofence — mniejszy z poziomego i pionowego. */
@Composable
private fun ZapasPlotu(plot: Ogrodzenie.Zapas) {
    val tekst = when {
        !plot.wlaczone -> "geofence wył."
        plot.naruszenie != Ogrodzenie.Naruszenie.BRAK -> plot.naruszenie.opis
        plot.najmniejszyM != null -> "płot %.0f m".format(plot.najmniejszyM) +
                // Zapas poziomy liczy się od domu, więc dom zgadnięty psuje tę liczbę.
                if (!plot.pewny) "?" else ""
        else -> "płot —"
    }
    Text(tekst, color = kolorPlotu(plot.ocena), fontSize = 9.sp, maxLines = 1)
}

private fun kolorCelu(stan: StanMaszyny, plot: Ogrodzenie.Zapas): Color = when {
    plot.ocena == Ogrodzenie.Ocena.NARUSZONE -> Barwy.Blokada
    plot.ocena == Ogrodzenie.Ocena.OSTRZEZENIE -> Barwy.Uwaga
    abs(stan.bladToruM) > 5f || abs(stan.bladWysokosciM) > 5f -> Barwy.Uwaga
    else -> Barwy.Akcent
}

private fun kolorPlotu(o: Ogrodzenie.Ocena): Color = when (o) {
    Ogrodzenie.Ocena.NARUSZONE -> Barwy.Blokada
    Ogrodzenie.Ocena.OSTRZEZENIE -> Barwy.Blokada
    Ogrodzenie.Ocena.UWAGA -> Barwy.Uwaga
    Ogrodzenie.Ocena.WYLACZONE -> Barwy.Wygasly
    Ogrodzenie.Ocena.DOBRZE -> Barwy.Wygasly
}
