package pl.dron15.cockpit.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.KanalRc
import pl.dron15.cockpit.domain.PozycjaPrzelacznika
import pl.dron15.cockpit.domain.Rc
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Waga

/**
 * Ekran RC — przypisania kanałów i przełączników MK32.
 *
 * Pełne uzasadnienie: dok/RC_PRZYPISANIA.md. W skrócie — kokpit ma nie dublować przyciskiem
 * tego, co robi kciuk bez patrzenia (zasada 7 z dok/UI.md), a żeby tego pilnować, musi wiedzieć,
 * co siedzi na kanałach. Funkcje czyta z maszyny (`RCn_OPTION`), organy deklaruje operator.
 *
 * Ten ekran **niczego nie zapisuje do kontrolera lotu** — PLAN.md §9.
 */
@Composable
fun EkranRc(
    stan: StanMaszyny,
    teraz: Long,
    sprzetowe: Set<Int>,
    naPrzelaczSprzetowe: (Int) -> Unit,
    naOdswiez: () -> Unit,
) {
    val ocena = Rc.ocen(stan, sprzetowe = sprzetowe, teraz = teraz)

    Column(Modifier.fillMaxSize()) {

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            NaglowekEkranu(
                "RC · aparatura",
                if (ocena.zywa) "${ocena.liczbaKanalow} kanałów · %.0f Hz · RSSI %s"
                    .format(stan.ramekNaSekunde,
                        if (stan.rssiRc in 1..254) "${stan.rssiRc}" else "brak (S.Bus nie przenosi)")
                else "brak danych z aparatury",
                Modifier.weight(1f),
            )
            Przycisk("ODŚWIEŻ", naOdswiez, Modifier.size(140.dp, 52.dp), podpis = "parametry z FC")
        }

        if (ocena.usterki.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                ocena.usterki.take(3).forEach { u ->
                    val kolor = when (u.waga) {
                        Waga.BLOKADA -> Barwy.Blokada
                        Waga.OSTRZEZENIE -> Barwy.Uwaga
                        Waga.INFORMACJA -> Barwy.Akcent
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .background(kolor.copy(alpha = 0.10f)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (u.waga == Waga.BLOKADA) "⛔" else "⚠", color = kolor, fontSize = 15.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(u.tekst, color = kolor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Text(u.szczegol, color = Barwy.Drugi, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        NaglowekTabeli()

        LazyColumn(Modifier.weight(1f)) {
            items(ocena.kanaly) { k ->
                WierszKanalu(k, ocena.kanalTrybow, sprzetowe.contains(k.numer), naPrzelaczSprzetowe)
            }
        }

        Spacer(Modifier.height(8.dp))
        PasTrybow(ocena.kanalTrybow, ocena.tryby, ocena.slotyOsiagalne)
    }
}

/**
 * Szerokości kolumn tabeli kanałów — **jedno miejsce dla nagłówka i wiersza**.
 *
 * Wcześniej te same liczby stały wpisane w dwóch miejscach i sumowały się do 678 dp
 * przy panelu szerokim na 660 dp: kolumna funkcji dostawała zero, a nagłówek „obsługa"
 * był ucięty. Trzymanie ich obok siebie sprawia, że taka rozbieżność rzuca się w oczy.
 */
private val SZER_KANAL = 70.dp
private val SZER_ORGAN = 140.dp
private val SZER_POLOZENIE = 280.dp
private val SZER_OBSLUGA = 140.dp

@Composable
private fun NaglowekTabeli() {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
        Etykieta2("kanał", SZER_KANAL)
        Etykieta2("organ", SZER_ORGAN)
        Etykieta2("położenie", SZER_POLOZENIE)
        Etykieta2("funkcja z FC", 0.dp, waga = 1f)
        Etykieta2("obsługa", 150.dp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Etykieta2(
    tekst: String, szerokosc: androidx.compose.ui.unit.Dp, waga: Float = 0f,
) {
    val m = if (waga > 0f) Modifier.weight(waga) else Modifier.width(szerokosc)
    Box(m) { Etykieta(tekst) }
}

@Composable
private fun WierszKanalu(
    k: KanalRc,
    kanalTrybow: Int,
    zadeklarowanySprzetowo: Boolean,
    naPrzelacz: (Int) -> Unit,
) {
    val ma = k.kodFunkcji != null && k.kodFunkcji != Rc.FUNKCJA_BRAK
    val kolorFunkcji = when {
        !ma -> Barwy.Wygasly
        !Rc.rozpoznana(k.kodFunkcji) -> Barwy.Uwaga
        else -> Barwy.Tekst
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (k.zywy) Color(0x08FFFFFF) else Color(0x03FFFFFF))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("CH%02d".format(k.numer), style = Kroje.liczba(14.sp),
            modifier = Modifier.width(SZER_KANAL))
        Text(k.organ, color = Barwy.Drugi, fontSize = 12.sp, modifier = Modifier.width(SZER_ORGAN),
            maxLines = 1, overflow = TextOverflow.Ellipsis)

        Row(Modifier.width(SZER_POLOZENIE), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(120.dp)) {
                Pasek(k.udzial, if (k.zywy) Barwy.Akcent else Barwy.Linia2, wysokosc = 10)
            }
            Spacer(Modifier.width(10.dp))
            Text(if (k.zywy) "${k.mikrosekundy}" else "—",
                style = Kroje.liczba(13.sp, kolor = if (k.zywy) Barwy.Tekst else Barwy.Wygasly),
                modifier = Modifier.width(52.dp))
            Text(
                if (!k.zywy) "brak"
                else if (k.proporcjonalny) "%.0f %%".format(k.udzial * 100)
                else k.pozycja.etykieta,
                color = when {
                    !k.zywy -> Barwy.Wygasly
                    k.pozycja == PozycjaPrzelacznika.GORA -> Barwy.Akcent
                    else -> Barwy.Drugi
                },
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                if (k.numer == kanalTrybow) "TRYBY LOTU" else k.nazwaFunkcji,
                color = if (k.numer == kanalTrybow) Barwy.Akcent else kolorFunkcji,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            when {
                k.numer == kanalTrybow -> Text("FLTMODE_CH", color = Barwy.Wygasly, fontSize = 11.sp)
                ma -> Text("RC${k.numer}_OPTION = ${k.kodFunkcji}", color = Barwy.Wygasly, fontSize = 11.sp)
            }
        }

        val sprzetowoAktywne = zadeklarowanySprzetowo || (ma && k.kodFunkcji in Rc.FUNKCJE_DUBLOWANE_PRZEZ_EKRAN)
        Box(
            Modifier
                .width(SZER_OBSLUGA)
                .height(44.dp)
                .background(if (sprzetowoAktywne) Barwy.Dobrze.copy(alpha = 0.14f) else Color.Transparent)
                .pointerInput(k.numer) { detectTapGestures(onTap = { naPrzelacz(k.numer) }) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (sprzetowoAktywne) "✔ sprzętowo" else "— z ekranu",
                color = if (sprzetowoAktywne) Barwy.Dobrze else Barwy.Drugi,
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Pas trybów lotu.
 *
 * ### ⛔ Objaśnienie musi mieć własny wiersz
 *
 * Do 2026-08-26 stało w jednym rzędzie z chipami trybów, z `weight(1f)`. Chipy nie mają wagi,
 * więc mierzą się **pierwsze** i zabierały całą szerokość — na tekst zostawało ok. 26 dp,
 * czyli **jeden znak na wiersz**. Napis rozciągał się wtedy na ~24 linie i **cały pas rósł
 * do ok. 310 dp wysokości**, zabierając tę wysokość liście kanałów, która dzieli się nią
 * przez `weight(1f)`: lista dostawała 100 dp zamiast 370 i pokazywała dwa kanały z szesnastu.
 *
 * Jeden błąd układu dawał więc dwa niepowiązane z pozoru objawy. Dlatego objaśnienie stoi
 * teraz w osobnym wierszu, gdzie ma pełną szerokość i nie konkuruje z niczym o pomiar.
 */
@Composable
private fun PasTrybow(kanal: Int, tryby: List<Pair<Int, String>>, osiagalne: List<Int>) {
    Column(Modifier.fillMaxWidth().background(Barwy.TaflaPelna).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(150.dp)) {
                Etykieta("tryby lotu")
                Text("CH$kanal", style = Kroje.liczba(20.sp))
            }
            if (tryby.isEmpty()) {
                Text("brak odczytu FLTMODE1..6 — dotknij ODŚWIEŻ",
                    color = Barwy.Drugi, fontSize = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    tryby.forEach { (slot, nazwa) ->
                        val dostepny = slot in osiagalne
                        Column(
                            Modifier
                                .background(if (dostepny) Color(0x10FFFFFF) else Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("$slot", color = if (dostepny) Barwy.Akcent else Barwy.Wygasly,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(nazwa, color = if (dostepny) Barwy.Tekst else Barwy.Wygasly,
                                fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        if (tryby.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "osiągalne z przełącznika trzypozycyjnego: sloty ${osiagalne.joinToString(", ")}",
                color = Barwy.Drugi, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
