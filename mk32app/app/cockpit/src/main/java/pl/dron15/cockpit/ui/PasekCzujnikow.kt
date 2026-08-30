package pl.dron15.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Czujniki
import pl.dron15.cockpit.domain.StanMaszyny

/**
 * Stan czujników i wibracje na belce — **z masek `SYS_STATUS`, które i tak odbieramy**.
 *
 * Zastępuje wnioskowanie o sprzęcie z tekstu `PreArm:`. Zero nowego pasma.
 *
 * ### Rośnie dopiero wtedy, gdy jest co pokazać
 *
 * Dziewięć kwadratów zajmowałoby na stałe ok. 130 dp belki, a belka na aparaturze ma
 * 640–950 dp i już dziś przechodzi w postać zwięzłą przy 780. Dlatego stan normalny to
 * **jeden znak**, a lista rozwija się wyłącznie przy usterce — czyli w chwili, w której
 * ma zabrać miejsce czemu innemu.
 *
 * Wibracje **nie są tutaj**, choć należą do zdrowia maszyny: belka na aparaturze
 * skończyła się na nich i „WIB" łamało się w pionie na „W / IB". Poszły do pasa zapasu,
 * gdzie i tak sąsiadują z rozrzutem silników — czyli z tą samą mechaniką.
 *
 * ### Kształt niesie to samo, co kolor
 *
 * Sprawny czujnik to sam kontur, uszkodzony — wypełniony blok. Przy pełnym słońcu
 * i w rękawicach zieleń od czerwieni odróżnia się gorzej, niż się wydaje przy biurku,
 * a kształt przechodzi przez każdą paletę.
 *
 * ### Czego tu nie ma
 *
 * Magnetometru. Ta maszyna nie ma kompasu **z decyzji** (`COMPASS_USE = 0`), więc bit
 * obecności jest zgaszony i [Czujniki] go pomija. Gdyby świecił na czerwono, pilot
 * nauczyłby się ignorować czerwień — `dok/AUDYT_M3.md` S4.
 */
@Composable
fun PasekCzujnikow(stan: StanMaszyny, modifier: Modifier = Modifier) {
    val lista = Czujniki.odczytaj(
        stan.czujnikiObecne, stan.czujnikiWlaczone, stan.czujnikiZdrowe,
    )
    val zle = lista.filter { it.stan != Czujniki.Stan.SPRAWNY }

    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // Maszyna jeszcze nic nie powiedziała — nie udajemy wiedzy.
            lista.isEmpty() -> Text("CZUJ —", style = Kroje.zgeszczona(11.sp, Barwy.Wygasly))

            zle.isEmpty() -> Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Kostka(null)
                Text("${lista.size}", style = Kroje.liczba(11.sp, FontWeight.Medium, Barwy.Drugi))
            }

            // Przy usterce **pełny skrót**, nie pierwsza litera: BAR i BAT dawały dwa
            // identyczne „B" i nie dało się odróżnić barometru od pomiaru pakietu.
            // Miejsce na to jest, bo pasek rośnie wyłącznie w tej sytuacji.
            else -> Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                zle.take(3).forEach { c ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Kostka(c)
                        Text(c.rodzaj.skrot,
                            style = Kroje.liczba(11.sp, FontWeight.SemiBold, kolorStanu(c.stan)),
                            maxLines = 1)
                    }
                }
                if (zle.size > 3) {
                    Text("+${zle.size - 3}",
                        style = Kroje.liczba(11.sp, FontWeight.Medium, Barwy.Blokada),
                        maxLines = 1)
                }
            }
        }
    }
}
/** 12 × 12 dp. `null` = zbiorczy znacznik „wszystko sprawne". */
@Composable
private fun Kostka(czujnik: Czujniki.Czujnik?) {
    val kolor = if (czujnik == null) Barwy.Dobrze else kolorStanu(czujnik.stan)
    val wypelniony = czujnik?.stan == Czujniki.Stan.USZKODZONY
    Box(
        Modifier
            .size(12.dp)
            .drawBehind {
                val w = 1.dp.toPx()
                if (wypelniony) {
                    drawRect(kolor)
                } else {
                    drawRect(kolor, size = Size(size.width, w))
                    drawRect(kolor, topLeft = Offset(0f, size.height - w),
                        size = Size(size.width, w))
                    drawRect(kolor, size = Size(w, size.height))
                    drawRect(kolor, topLeft = Offset(size.width - w, 0f),
                        size = Size(w, size.height))
                }
                // Przekreślenie: „jest, ale nie pracuje" — inne niż „zepsuty".
                if (czujnik?.stan == Czujniki.Stan.WYLACZONY) {
                    drawRect(kolor, topLeft = Offset(0f, size.height / 2 - w / 2),
                        size = Size(size.width, w))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Sam znacznik zbiorczy niesie znak; kostka usterki jest pusta, bo skrót
        // stoi obok niej pełną nazwą.
        if (czujnik == null) {
            Text("✓", style = Kroje.liczba(8.sp, FontWeight.Bold, kolor))
        }
    }
}

private fun kolorStanu(s: Czujniki.Stan): Color = when (s) {
    Czujniki.Stan.SPRAWNY -> Barwy.Dobrze
    Czujniki.Stan.WYLACZONY -> Barwy.Wygasly
    Czujniki.Stan.USZKODZONY -> Barwy.Blokada
    Czujniki.Stan.NIEOBECNY -> Barwy.Wygasly
}
