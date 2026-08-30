package pl.dron15.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.diag.Dziennik
import pl.dron15.cockpit.domain.Ostrzezenia
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Waga

/**
 * Ekran DIAGNOSTYKA — do szukania usterek, nie do latania.
 *
 * Trzy łącza mają osobne wskaźniki, bo w tej konstrukcji są niezależne: utrata obrazu
 * nie zabiera telemetrii, a utrata telemetrii nie zabiera sterowania głowicą.
 */
@Composable
fun EkranDiagnostyki(stan: StanMaszyny) {
    val teraz = System.currentTimeMillis()
    // Odczytujemy raz, przy wejściu na ekran — plik znacznika czytamy z dysku.
    var awaria by remember { mutableStateOf(Dziennik.ostatniaAwaria()) }
    val wpisy by Dziennik.wpisy.collectAsState()

    Column(Modifier.fillMaxSize()) {
        NaglowekEkranu("diagnostyka", "do szukania usterek, nie do latania")

        // Awaria poprzedniego uruchomienia. Pilot mógł jej nie zobaczyć — aplikacja
        // zamyka się wtedy razem z ekranem — więc mówimy o niej po powrocie.
        awaria?.let { opis ->
            PasAwarii(opis) {
                Dziennik.potwierdzAwarie()
                awaria = null
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Lacze(
                "TELEMETRIA", "UDP 192.168.144.12:19856",
                stan.telemetriaZywa(teraz),
                if (stan.telemetriaZywa(teraz)) "%.1f Hz".format(stan.ramekNaSekunde)
                else if (stan.telemetriaByla) "${stan.opisCiszy(teraz)} ciszy" else "nigdy nie było łącza",
                Modifier.weight(1f)
            )
            Lacze(
                "OBRAZ", "RTSP 192.168.144.25:8554",
                stan.wideoDziala, if (stan.wideoDziala) "płynie" else "brak", Modifier.weight(1f)
            )
            Lacze(
                "GŁOWICA", "UDP 192.168.144.25:37260",
                stan.glowicaOdpowiada,
                if (stan.glowicaOdpowiada) "pitch %.0f° zoom %.1f×".format(stan.glowicaPitch, stan.glowicaZoom)
                else "cisza",
                Modifier.weight(1f)
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kafelek("EKF", "0x%04X".format(stan.flagiEkf), Modifier.weight(1f))
            Kafelek("wariancja kursu", "%.3f".format(stan.wariancjaKursu), Modifier.weight(1f))
            Kafelek("satelity / HDOP", "${stan.satelity} / %.2f".format(stan.hdop), Modifier.weight(1f))
            Kafelek("parametry", "${stan.parametry.size}", Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kafelek("aparatura",
                if (stan.liczbaKanalowRc > 0) "${stan.liczbaKanalowRc} kan." else "brak RC",
                Modifier.weight(1f))
            Kafelek("gaz CH3",
                if (stan.kanalyRc.size >= 3 && stan.kanalyRc[2] > 0) "${stan.kanalyRc[2]} us" else "—",
                Modifier.weight(1f))
            Kafelek("czas lotu", czasMmSs(stan.czasLotuS(teraz)), Modifier.weight(1f))
            Kafelek("ostatnia komenda",
                stan.ostatniaKomenda?.let { "${it.nazwa}: ${it.stan(teraz)}" } ?: "—",
                Modifier.weight(1f))
        }

        val ostrzezenia = Ostrzezenia.ocen(stan, teraz)
        if (ostrzezenia.isNotEmpty()) {
            Text("Aktywne ostrzeżenia", color = Barwy.Drugi, fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            ostrzezenia.forEach { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        when (o.waga) {
                            Waga.BLOKADA -> "⛔"; Waga.OSTRZEZENIE -> "⚠"; Waga.INFORMACJA -> "•"
                        },
                        color = when (o.waga) {
                            Waga.BLOKADA -> Barwy.Blokada
                            Waga.OSTRZEZENIE -> Barwy.Uwaga
                            Waga.INFORMACJA -> Barwy.Akcent
                        },
                        fontSize = 16.sp, modifier = Modifier.width(30.dp)
                    )
                    // Wagi zamiast sztywnych szerokości: przy 640 dp aparatury 520 dp
                    // na sam tekst nie zostawiało miejsca na szczegół.
                    Text(o.tekst, color = Barwy.Tekst, fontSize = 16.sp,
                        modifier = Modifier.weight(1.3f), maxLines = 2)
                    Text(o.szczegol, color = Barwy.Drugi, fontSize = 15.sp,
                        modifier = Modifier.weight(1f), maxLines = 2)
                }
            }
        }

        // Dwa strumienie obok siebie, bo mówią o czym innym: po lewej to, co powiedziała
        // MASZYNA, po prawej to, co zepsuło się W APLIKACJI. Mylenie ich kosztuje czas.
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Komunikaty z kontrolera lotu", color = Barwy.Drugi, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(stan.komunikaty) { k ->
                        Text(k.zLicznikiem, color = if (k.blokujePrearm) Barwy.Uwaga else Barwy.Tekst,
                            fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                Text("Rejestr aplikacji — ${Dziennik.sciezkaLogow()}",
                    color = Barwy.Drugi, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(wpisy.asReversed()) { w -> WpisRejestru(w) }
                }
                if (wpisy.isEmpty()) {
                    Text("czysto — nic się nie zepsuło", color = Barwy.Drugi, fontSize = 14.sp)
                }
            }
        }
    }
}

/** Ślad po awarii poprzedniego uruchomienia. Znika dopiero po dotknięciu. */
@Composable
private fun PasAwarii(opis: String, naPotwierdzenie: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(Ksztalty.Male)
            .background(Barwy.Blokada.copy(alpha = 0.14f))
            .border(1.dp, Barwy.Blokada, Ksztalty.Male)
            .clickable(onClick = naPotwierdzenie)
            .padding(12.dp)
    ) {
        Text("⛔  POPRZEDNIE URUCHOMIENIE SKOŃCZYŁO SIĘ AWARIĄ — dotknij, żeby schować",
            color = Barwy.Blokada, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(opis.lineSequence().take(6).joinToString("\n"),
            color = Barwy.Tekst, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp).verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun WpisRejestru(w: Dziennik.Wpis) {
    val kolor = when (w.poziom) {
        Dziennik.Poziom.BLAD -> Barwy.Blokada
        Dziennik.Poziom.OSTRZ -> Barwy.Uwaga
        else -> Barwy.Drugi
    }
    Column(Modifier.padding(vertical = 1.dp)) {
        Row {
            Text(w.godzina, color = Barwy.Drugi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("  ${w.poziom.name}  ", color = kolor, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text("[${w.obszar}]", color = Barwy.Drugi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(w.wiadomosc, color = Barwy.Tekst, fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp))
        w.stos?.let {
            Text(it.lineSequence().take(3).joinToString(" ⏎ "), color = Barwy.Drugi,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Lacze(nazwa: String, adres: String, zywe: Boolean, opis: String, modifier: Modifier) {
    val kolor = if (zywe) Barwy.Dobrze else Barwy.Blokada
    Column(
        modifier
            .clip(Ksztalty.Male)
            .background(kolor.copy(alpha = 0.10f))
            .border(1.dp, kolor.copy(alpha = 0.5f), Ksztalty.Male)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (zywe) "●" else "○", color = kolor, fontSize = 16.sp)
            Text("  $nazwa", color = Barwy.Tekst, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(opis, color = kolor, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
        Text(adres, color = Barwy.Drugi, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Kafelek(etykieta: String, wartosc: String, modifier: Modifier) {
    Column(
        modifier
            .clip(Ksztalty.Male)
            .background(Color(0x0AFFFFFF))
            .padding(14.dp)
    ) {
        Text(etykieta.uppercase(), color = Barwy.Drugi, fontSize = 12.sp, letterSpacing = 1.sp)
        Text(wartosc, color = Barwy.Tekst, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace)
    }
}
