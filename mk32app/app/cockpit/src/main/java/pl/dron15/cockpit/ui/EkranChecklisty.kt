package pl.dron15.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.dron15.cockpit.domain.Poprawka
import pl.dron15.cockpit.domain.PozycjaChecklisty
import pl.dron15.cockpit.domain.Werdykt

/**
 * Ekran PRZED LOTEM. Każda pozycja z werdyktem wyliczonym z parametrów maszyny
 * i z bieżącej telemetrii — patrz domain/Checklista.kt.
 *
 * Zmiany po audycie: kolumny liczbowe mają **wagi**, a nie sztywne szerokości (U3), pozycje
 * blokujące idą **na górę** listy (nie trzeba ich szukać), a dolna krawędź listy dostaje
 * cień, żeby było widać, że coś jeszcze jest niżej (U4).
 */
@Composable
fun EkranChecklisty(
    pozycje: List<PozycjaChecklisty>,
    werdykt: Werdykt,
    pobranychParametrow: Int,
    naOdswiez: () -> Unit,
    naKontrolePrzedlotowa: () -> Unit,
    /** Zapis wskazanej poprawki do maszyny. */
    naPoprawke: (Poprawka) -> Unit = {},
    /** Przy uzbrojonej maszynie nie zapisujemy niczego — klawisze mają wtedy zniknąć. */
    uzbrojony: Boolean = false,
) {
    val uporzadkowane = remember(pozycje) {
        pozycje.sortedBy {
            when (it.werdykt) {
                Werdykt.BLOKADA -> 0
                Werdykt.OSTRZEZENIE -> 1
                Werdykt.BRAK_DANYCH -> 2
                Werdykt.OK -> 3
            }
        }
    }
    val lista = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        NaglowekEkranu("przed lotem", "odczytano $pobranychParametrow parametrów")

        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = lista) {
                items(uporzadkowane) { p -> Wiersz(p, naPoprawke, uzbrojony) }
            }
            // Cień na dolnej krawędzi: lista, która się urywa bez znaku, wygląda na skończoną.
            if (lista.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Barwy.Tlo)))
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        PasekWerdyktu(werdykt, pozycje, naOdswiez, naKontrolePrzedlotowa)
    }
}

@Composable
private fun Wiersz(
    p: PozycjaChecklisty,
    naPoprawke: (Poprawka) -> Unit,
    uzbrojony: Boolean,
) {
    val kolor = kolorWerdyktu(p.werdykt)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .background(if (p.werdykt == Werdykt.OK) Color(0x06FFFFFF) else kolor.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(znakWerdyktu(p.werdykt), color = kolor, fontSize = 17.sp,
                modifier = Modifier.width(30.dp))
            Text(p.opis, color = Barwy.Tekst, fontSize = 16.sp, modifier = Modifier.weight(1.4f))
            Text(p.wartosc, color = kolor, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1.1f))
            Text(p.oczekiwane, color = Barwy.Drugi, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        }
        if (p.komunikat.isNotEmpty() && p.werdykt != Werdykt.OK) {
            Text(p.komunikat, color = Barwy.Drugi, fontSize = 13.sp,
                modifier = Modifier.padding(start = 30.dp, top = 3.dp))
        }
        // Poprawki: jeden klawisz na jeden parametr, z wartością wprost z reguły.
        // Przy uzbrojonej maszynie nie pokazujemy ich wcale — zamiast kusić klawiszem,
        // który i tak odmówi.
        if (p.poprawki.isNotEmpty() && !uzbrojony) {
            Row(
                Modifier.padding(start = 30.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                p.poprawki.forEach { poprawka -> KlawiszPoprawki(poprawka, naPoprawke) }
            }
        }
    }
}

/**
 * Klawisz „ustaw" — **dwustopniowy**.
 *
 * Pierwsze dotknięcie odsłania wartość i słowo POTWIERDŹ, dopiero drugie wysyła zapis.
 * Ekran PRZED LOTEM bywa dotykany w rękawicach i przy słońcu, a to jest jedyne miejsce
 * w kokpicie, które **zmienia coś w kontrolerze lotu na trwałe** — pojedyncze muśnięcie
 * nie może wystarczyć. Zamiar gaśnie sam po czterech sekundach.
 */
@Composable
private fun KlawiszPoprawki(poprawka: Poprawka, naPoprawke: (Poprawka) -> Unit) {
    var pewny by remember(poprawka) { mutableStateOf(false) }
    LaunchedEffect(pewny) {
        if (pewny) {
            delay(4000)
            pewny = false
        }
    }
    val docelowa = if (poprawka.docelowa == poprawka.docelowa.toLong().toFloat())
        poprawka.docelowa.toLong().toString() else "%.3f".format(poprawka.docelowa)
    Przycisk(
        if (pewny) "POTWIERDŹ" else "${poprawka.parametr} = $docelowa",
        {
            if (pewny) {
                naPoprawke(poprawka)
                pewny = false
            } else {
                pewny = true
            }
        },
        Modifier.height(38.dp),
        kolor = if (pewny) Barwy.Uwaga else Barwy.Linia,
        wyrozniony = pewny,
    )
}

@Composable
private fun PasekWerdyktu(
    werdykt: Werdykt,
    pozycje: List<PozycjaChecklisty>,
    naOdswiez: () -> Unit,
    naKontrolePrzedlotowa: () -> Unit,
) {
    val blokady = pozycje.count { it.werdykt == Werdykt.BLOKADA }
    val ostrzezenia = pozycje.count { it.werdykt == Werdykt.OSTRZEZENIE }
    val braki = pozycje.count { it.werdykt == Werdykt.BRAK_DANYCH }
    val kolor = kolorWerdyktu(werdykt)

    Row(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(kolor.copy(alpha = 0.12f))
            .drawBehind { drawRect(kolor.copy(alpha = 0.7f), style = Stroke(width = 2.dp.toPx())) }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Wymiary.Odstep),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when (werdykt) {
                    Werdykt.OK -> "GOTOWY"
                    Werdykt.OSTRZEZENIE -> "OSTRZEŻENIA"
                    Werdykt.BLOKADA -> "NIE STARTOWAĆ"
                    Werdykt.BRAK_DANYCH -> "BRAK DANYCH"
                },
                color = kolor, fontSize = 26.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "$blokady blokad · $ostrzezenia ostrzeżeń · $braki bez odpowiedzi",
                color = Barwy.Drugi, fontSize = 14.sp
            )
        }
        Przycisk("ODŚWIEŻ", naOdswiez, Modifier.size(160.dp, Wymiary.CelDotyku),
            podpis = "pytaj FC o parametry")
        Przycisk("PREARM Z FC", naKontrolePrzedlotowa, Modifier.size(160.dp, Wymiary.CelDotyku),
            podpis = "wymuś kontrolę")
    }
}

private fun kolorWerdyktu(w: Werdykt) = when (w) {
    Werdykt.OK -> Barwy.Dobrze
    Werdykt.OSTRZEZENIE -> Barwy.Uwaga
    Werdykt.BLOKADA -> Barwy.Blokada
    Werdykt.BRAK_DANYCH -> Barwy.Drugi
}

private fun znakWerdyktu(w: Werdykt) = when (w) {
    Werdykt.OK -> "✔"
    Werdykt.OSTRZEZENIE -> "⚠"
    Werdykt.BLOKADA -> "⛔"
    Werdykt.BRAK_DANYCH -> "…"
}
