package pl.dron15.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
 * **JOKER i BINGO na belce** — jedno pole, nie dwa.
 *
 * ### Co to za progi
 *
 * - **JOKER** — moment, w którym trzeba **ruszyć do domu**, żeby wrócić z rezerwą.
 *   Liczony z dystansu, wysokości, prędkości powrotu i średniego poboru.
 * - **BINGO** — moment, po którym **powrót przestaje być możliwy**; rezerwa 20 %.
 *
 * Wzięły się stąd, że `CLAUDE.md` poz. 45 kończy opis spadku z 58 m zdaniem
 * *„Pilot nie miał żadnego ostrzeżenia"*.
 *
 * ### Dlaczego jedno pole, a nie dwa
 *
 * Przeniesione z pasa przyrządów na belkę (Tom, 2026-08-28). Belka nie ma miejsca na
 * dwie liczby, a pilot i tak potrzebuje **tylko najbliższej**: dopóki JOKER przed nim,
 * liczy się do JOKER; po jego przekroczeniu sens ma już tylko BINGO. Druga liczba jest
 * wtedy szumem, bo decyzja i tak zapadła.
 *
 * Podpisem jest **piktogram, nie nazwa** (Tom, 2026-08-28): dom z zegarem znaczy
 * „czas wracać”, a pusta bateria z wykrzyknikiem — „powrót przestał być możliwy”.
 * Nazwy JOKER i BINGO zostają w dokumentacji; na ekranie rysunek mówi to samo
 * bez języka i bez tłumaczenia.
 *
 * ### Milczy, kiedy nie ma czego powiedzieć
 *
 * Przy niekalibrowanym `BATT_CAPACITY` (poz. 9 i 40) obie liczby byłyby zmyślone,
 * więc pole **znika**, zamiast pokazywać wartość, której nie da się sprawdzić.
 * Surowe mAh zostają w bloku energii.
 */
@Composable
fun PoleCzasuDoProgu(stan: StanMaszyny, teraz: Long, modifier: Modifier = Modifier) {
    val b = Energia.policz(stan, teraz)
    if (!b.wiarygodny) return

    val poBingo = b.poBingo
    // Ikona, nie nazwa (Tom, 2026-08-28): dom z zegarem znaczy „czas wracać”,
    // pusta bateria z wykrzyknikiem — „powrót przestał być możliwy”.
    val piktogram = if (b.poJokerze) Piktogram.REZERWA else Piktogram.WRACAJ
    val sekundy = if (b.poJokerze) b.doBingoS else b.doJokeraS
    val kolor = when {
        poBingo -> Barwy.Blokada
        b.poJokerze -> Barwy.Uwaga
        else -> Barwy.Tekst
    }

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Ikona(piktogram, kolor = if (poBingo) Barwy.Blokada else Barwy.Drugi, rozmiar = 14.dp)
        Text(
            Energia.czas(sekundy),
            style = Kroje.liczba(13.sp, FontWeight.SemiBold, kolor),
        )
    }
}
