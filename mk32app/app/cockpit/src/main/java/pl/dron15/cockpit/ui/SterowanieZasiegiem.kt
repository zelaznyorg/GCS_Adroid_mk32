package pl.dron15.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Przybliżanie i oddalanie mapy — **klawiszami, nie tylko szczypnięciem**.
 *
 * Szczypnięcie działa wszędzie (patrz [Mapa], [MapaMisji], [Widok3D]), ale nie może być
 * jedyną drogą: aparaturę trzyma się w polu dwiema rękami, często w rękawicach, a MK32 ma
 * siedem cali. Klawisz `+`/`−` da się nacisnąć kciukiem, nie puszczając drążków.
 *
 * Między klawiszami stoi **odczyt zasięgu**, czyli ile metrów mieści się w krótszym boku
 * widoku. Ta sama liczba rządzi mapą płaską i widokiem przestrzennym, więc przełączenie
 * 2D ↔ 3D nie zmienia skali.
 */

/**
 * Postać pionowa — na ekran LOT, przy lewej krawędzi pod kolumną komend. Dół i prawa strona
 * kadru są tam zajęte przez miniaturę, rząd liczb i kolumnę kamery.
 *
 * `auto` znaczy, że zasięg dobiera się sam do śladu; klawisz **AUTO** pojawia się dopiero
 * wtedy, gdy operator przejął zoom ręcznie — i służy do oddania go z powrotem.
 */
@Composable
fun ZasiegPionowo(
    zasiegM: Float,
    auto: Boolean,
    naZasieg: (Float) -> Unit,
    naAuto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Chip("+", false, Modifier.width(SZER), WYS, 18.sp) { naZasieg(Zasieg.blizej(zasiegM)) }
        Spacer(Modifier.height(4.dp))
        Text(
            Zasieg.opis(zasiegM),
            style = Kroje.liczba(11.sp, kolor = if (auto) Barwy.Drugi else Barwy.Akcent),
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Chip("−", false, Modifier.width(SZER), WYS, 18.sp) { naZasieg(Zasieg.dalej(zasiegM)) }
        if (!auto) {
            Spacer(Modifier.height(6.dp))
            Chip("AUTO", false, Modifier.width(SZER), rozmiar = 11.sp) { naAuto() }
        }
    }
}

/**
 * Postać pozioma — na ekran MISJA, w rzędzie u dołu mapy. Zastąpiła cztery stałe chipy
 * (150 m / 400 m / 1 km / 2,5 km): przy zoomie płynnym chip zapalał się tylko przy trafieniu
 * w wartość co do metra, a górna granica 2,5 km była za niska, żeby zobaczyć ukształtowanie
 * terenu — pojedyncze wzniesienie ma kilkaset metrów i przy 400 m jest płaskie jak stół.
 */
@Composable
fun ZasiegPoziomo(
    zasiegM: Float,
    modifier: Modifier = Modifier,
    naZasieg: (Float) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Chip("−", false, Modifier.width(Wymiary.CelDotykuSzer), rozmiar = 18.sp) {
            naZasieg(Zasieg.dalej(zasiegM))
        }
        Text(
            Zasieg.opis(zasiegM),
            style = Kroje.liczba(13.sp, kolor = Barwy.Tekst),
            maxLines = 1,
            modifier = Modifier.width(58.dp),
        )
        Chip("+", false, Modifier.width(Wymiary.CelDotykuSzer), rozmiar = 18.sp) {
            naZasieg(Zasieg.blizej(zasiegM))
        }
    }
}

private val SZER = Wymiary.CelDotykuSzer
private val WYS = Wymiary.CelDotyku
