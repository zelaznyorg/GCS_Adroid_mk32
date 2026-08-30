package pl.dron15.cockpit.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.dron15.cockpit.BuildConfig

/**
 * Ekran uruchamiania z logo producenta.
 *
 * Aplikacja wstaje **pod spodem** — treść jest komponowana od pierwszej chwili, a ekran
 * startowy leży na niej i znika, gdy kokpit jest gotowy. Dzięki temu nic się nie opóźnia
 * przez samą zasłonę; ona tylko przykrywa moment, w którym tor obrazu się buduje
 * (na MK32 to ok. 2,4 s — patrz nagłówek `OdtwarzaczVlc`).
 *
 * ### Kiedy znika
 *
 * Dwa warunki naraz, plus twardy sufit:
 * - kokpit zgłosił gotowość ([gotowe]),
 * - minęło [MIN_MS], żeby logo dało się w ogóle zobaczyć — mignięcie na 100 ms wygląda
 *   jak usterka, nie jak ekran startowy,
 * - a jeśli gotowość nie przyjdzie, zasłona schodzi po [MAKS_MS] mimo wszystko.
 *   ⛔ Ekran startowy **nie ma prawa zablokować dostępu do kokpitu**: kokpit steruje
 *   maszyną i musi być osiągalny nawet wtedy, gdy coś w inicjalizacji poszło nie tak.
 *
 * ### Logo wczytywane po nazwie, nie przez `R`
 *
 * Przez `getIdentifier`, więc brak pliku nie wywala kompilacji ani aplikacji — zamiast
 * grafiki pokazuje się sama nazwa. To pozwoliło zbudować i przetestować całą mechanikę,
 * zanim plik trafił do repozytorium.
 */
@Composable
fun ZEkranemStartowym(
    gotowe: Boolean,
    etap: String = "",
    tresc: @Composable () -> Unit,
) {
    var widoczny by remember { mutableStateOf(true) }
    var przezroczystosc by remember { mutableStateOf(1f) }

    LaunchedEffect(Unit) {
        val poczatek = System.currentTimeMillis()
        while (System.currentTimeMillis() - poczatek < MAKS_MS) {
            if (gotowe && System.currentTimeMillis() - poczatek >= MIN_MS) break
            delay(60)
        }
        // Zejście, nie zniknięcie: skok z logo na kokpit czyta się jak awaria.
        val zanik = System.currentTimeMillis()
        while (true) {
            val t = (System.currentTimeMillis() - zanik).toFloat() / ZANIK_MS
            if (t >= 1f) break
            przezroczystosc = 1f - t
            delay(16)
        }
        widoczny = false
    }

    Box(Modifier.fillMaxSize()) {
        tresc()
        if (widoczny) {
            Zaslona(etap, Modifier.alpha(przezroczystosc))
        }
    }
}

@Composable
private fun Zaslona(etap: String, modifier: Modifier = Modifier) {
    val kontekst = LocalContext.current
    val idLogo = remember {
        kontekst.resources.getIdentifier("logo_aerothink", "drawable", kontekst.packageName)
    }

    Box(
        modifier.fillMaxSize().background(Barwy.Tlo),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (idLogo != 0) {
                Image(
                    painter = painterResource(idLogo),
                    contentDescription = "AEROTHINK",
                    modifier = Modifier.width(Wymiary.LogoSzer),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("AEROTHINK", style = Kroje.zgeszczona(40.sp))
            }

            Spacer(Modifier.height(30.dp))
            Postep()

            Spacer(Modifier.height(10.dp))
            // Nazwa etapu, nie sam ruch paska: pasek mowi "cos sie dzieje",
            // a to mowi **co**. Przy zawieszeniu od razu widac, na czym stanelo.
            Text(
                if (etap.isNotEmpty()) etap else "uruchamianie",
                style = Kroje.zgeszczona(13.sp, Barwy.Drugi),
            )

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DRON15 COCKPIT", style = Kroje.zgeszczona(15.sp, Barwy.Tekst))
                Spacer(Modifier.width(10.dp))
                Text(
                    BuildConfig.VERSION_NAME,
                    style = Kroje.liczba(15.sp, FontWeight.SemiBold, Barwy.Akcent),
                )
            }
        }
    }
}

/**
 * Pasek postępu bez określonego końca — segment przechodzący w kółko.
 *
 * Nie udaje, że zna procent gotowości, bo go nie zna: mówi tylko „coś się dzieje".
 * Pasek, który stoi w miejscu, jest gorszy od jego braku, bo wygląda jak zawieszenie.
 */
@Composable
private fun Postep() {
    val ruch = rememberInfiniteTransition(label = "postep")
    val faza by ruch.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "faza",
    )

    Box(
        Modifier
            .width(Wymiary.LogoSzer)
            .height(4.dp)
            .drawBehind {
                drawRect(Barwy.Linia2, size = Size(size.width, size.height))
                val szer = size.width * 0.28f
                // Segment wjeżdża z lewej i wyjeżdża prawą, bez skoku na końcu cyklu.
                val x = -szer + (size.width + szer) * faza
                drawRect(
                    Barwy.Akcent,
                    topLeft = Offset(x.coerceAtLeast(0f), 0f),
                    size = Size(
                        (szer + minOf(0f, x)).coerceIn(0f, size.width - x.coerceAtLeast(0f)),
                        size.height,
                    ),
                )
            }
    )
}

/** Poniżej tylu milisekund logo miga i wygląda jak usterka, a nie ekran startowy. */
private const val MIN_MS = 1100L

/** Twardy sufit: po tym czasie zasłona schodzi niezależnie od gotowości. */
private const val MAKS_MS = 4500L

private const val ZANIK_MS = 320f
