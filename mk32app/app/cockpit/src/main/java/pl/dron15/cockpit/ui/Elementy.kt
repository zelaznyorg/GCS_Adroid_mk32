package pl.dron15.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.dron15.cockpit.domain.Ostrzezenia
import pl.dron15.cockpit.domain.Ostrzezenie
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Waga
import pl.dron15.cockpit.domain.Wspolrzedne
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Elementy wspólne — odwzorowanie makiety `Kokpit M3.dc.html`.
 *
 * ### Płyta — jedna forma, z której zbudowany jest cały interfejs
 *
 * W makiecie każdy element chromu przechodzi przez pomocnik `plyta(sc, tło, kolor)`:
 *
 * - **ścięcie dwóch przeciwległych naroży** (lewy górny i prawy dolny), nie czterech,
 * - **krawędź akcentu 2 dp wyłącznie po lewej** — to ona mówi, czym element jest,
 * - włos `outline-var` na pozostałych trzech krawędziach.
 *
 * Ta forma niesie kierunek: oko biegnie od grubej krawędzi w prawo. Ścięcie wszystkich
 * naroży i ramka dookoła — czyli to, co robiłby domyślny `border` — ten kierunek gubi.
 */

// --------------------------------------------------------------------------- płyta

fun Modifier.plyta(
    sciecie: Dp = 7.dp,
    tlo: Color = Barwy.TaflaMocna,
    kolor: Color = Barwy.Linia,
    grubosc: Dp = 2.dp,
    nakladka: Color = Color.Transparent,
): Modifier = this
    .clip(Ksztalty.plyta(sciecie))
    .background(tlo)
    .background(nakladka)
    .drawBehind {
        val g = grubosc.toPx()
        val w = 1.dp.toPx()
        drawRect(Barwy.Linia2, size = Size(size.width, w))
        drawRect(Barwy.Linia2, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
        drawRect(Barwy.Linia2, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
        drawRect(kolor, size = Size(g, size.height))
    }

/** Tafla robocza — płyta z wypełnieniem `surf-c` i włosem zamiast akcentu. */
fun Modifier.tafla(
    tlo: Color = Barwy.Tafla,
    obwod: Color = Barwy.Linia,
    sciecie: Dp = 7.dp,
): Modifier = plyta(sciecie, tlo, obwod)

@Composable
fun Tafla(
    modifier: Modifier = Modifier,
    tytul: String? = null,
    tlo: Color = Barwy.Tafla,
    obwod: Color = Barwy.Linia,
    sciecie: Dp = 7.dp,
    tresc: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.plyta(sciecie, tlo, obwod).padding(10.dp)) {
        if (tytul != null) {
            Etykieta(tytul)
            Spacer(Modifier.height(4.dp))
        }
        tresc()
    }
}

@Composable
fun Etykieta(tekst: String, kolor: Color = Barwy.Drugi, modifier: Modifier = Modifier) {
    Text(tekst.uppercase(), style = Kroje.Podpis.copy(color = kolor), maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * Wartość telemetryczna świadoma własnego wieku — zasada 6 z UI.md.
 * Powyżej 2 s przygasa, powyżej 10 s zamienia się w kreski.
 */
@Composable
fun Wartosc(
    etykieta: String,
    wartosc: String,
    jednostka: String = "",
    wiekS: Float = 0f,
    rozmiar: androidx.compose.ui.unit.TextUnit = Kroje.Duza,
    kolor: Color = Barwy.Tekst,
    modifier: Modifier = Modifier,
) {
    val stare = wiekS > 2f
    val martwe = wiekS > 10f
    Column(modifier.padding(bottom = 6.dp)) {
        Etykieta(etykieta)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (martwe) "———" else wartosc,
                style = Kroje.liczba(rozmiar, kolor = if (stare) Barwy.Wygasly else kolor),
                maxLines = 1, softWrap = false,
            )
            if (jednostka.isNotEmpty() && !martwe) {
                Text(" $jednostka", style = Kroje.liczba(11.sp, FontWeight.Normal,
                    if (stare) Barwy.Wygasly else Barwy.Drugi))
            }
        }
    }
}

fun kolorNapiecia(v: Float): Color = when {
    v > Ostrzezenia.NAPIECIE_GORNE -> Barwy.Uwaga
    v in 0.1f..Ostrzezenia.NAPIECIE_DOLNE -> Barwy.Blokada
    else -> Barwy.Tekst
}

fun czasMmSs(sekundy: Long): String = "%d:%02d".format(sekundy / 60, sekundy % 60)

// --------------------------------------------------------------------------- taśma kursu

/**
 * Kształt taśmy: **ostre czubki po bokach**, nie ścięte rogi — makieta
 * `polygon(9px 0, calc(100% − 9px) 0, 100% 50%, calc(100% − 9px) 100%, 9px 100%, 0 50%)`.
 */
private class KsztaltTasmy(private val czubek: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { czubek.toPx() }
        return Outline.Generic(Path().apply {
            moveTo(c, 0f)
            lineTo(size.width - c, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width - c, size.height)
            lineTo(c, size.height)
            lineTo(0f, size.height / 2f)
            close()
        })
    }
}

/**
 * Taśma kursu — **400 × 20 dp (320 na KAMERZE), zakres 60°**, wyśrodkowana nad obrazem.
 *
 * Po lewej pole 44 dp z kursem, po prawej 44 dp z podpisem źródła. Na podziałce **domek
 * z azymutem do punktu startu**: sam trójkąt mówił „tam", a nie „ile stopni w prawo",
 * a przy kursie wyłącznie z GNSS to jedyna nawigacja ratunkowa.
 */
/** Podpisy stron świata na taśmie kursu — wspólne dla liter i dla podziałki. */
internal val STRONY_SWIATA = listOf(
    0f to "N", 45f to "NE", 90f to "E", 135f to "SE",
    180f to "S", 225f to "SW", 270f to "W", 315f to "NW",
)

/**
 * Czy litera strony świata pod kątem [kat] jest zasłaniana przez znacznik punktu startu.
 *
 * Oba elementy siedzą przy górnej krawędzi taśmy, która ma 20 dp — nie ma tam miejsca na
 * dwa napisy naraz. Namiar do domu jest przy kursie wyłącznie z GNSS jedyną nawigacją
 * ratunkową, więc to on ma pierwszeństwo.
 */
internal fun zaslanianePrzezDom(kat: Float, stan: StanMaszyny, kurs: Float): Boolean {
    if (stan.namiarNaDomSt < 0f) return false
    return abs(roznicaKatow(kat, stan.namiarNaDomSt)) < POLE_ZNACZNIKA_DOMU_ST
}

/** Ile stopni po obu stronach znacznika domu zajmuje jego ikona z namiarem. */
private const val POLE_ZNACZNIKA_DOMU_ST = 6f

/**
 * Czy pod stopniem [k] stoi litera strony świata, która **zastępuje** kreskę podziałki.
 *
 * Warunek widoczności musi być dokładnie ten sam, co przy rysowaniu litery — inaczej
 * przy krawędzi taśmy zniknęłaby i litera, i kreska, robiąc dziurę w podziałce.
 */
internal fun literaZamiastKreski(k: Int, kurs: Float, zakres: Float): Boolean {
    val znormalizowany = ((k % 360) + 360) % 360
    if (znormalizowany % 45 != 0) return false
    return abs(roznicaKatow(znormalizowany.toFloat(), kurs)) <= zakres / 2 - 4f
}

@Composable
fun TasmaKursu(
    stan: StanMaszyny,
    modifier: Modifier = Modifier,
    szerokosc: Dp = Wymiary.TasmaKursuSzer,
    zakres: Float = 60f,
) {
    val kurs = if (stan.kursGnssDostepny) stan.kursGnssSt else stan.kursSt

    Row(
        modifier
            .width(szerokosc)
            .height(Wymiary.TasmaKursu)
            .clip(KsztaltTasmy(9.dp))
            .background(Barwy.Tafla)
            .drawBehind {
                drawRect(Barwy.Linia2, topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(size.width, 1.dp.toPx()))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (stan.kursGnssDostepny) "%03.0f".format(kurs) else "---",
            style = Kroje.liczba(13.sp, FontWeight.SemiBold,
                if (stan.kursGnssDostepny) Barwy.Tekst else Barwy.Blokada),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp),
        )

        BoxWithConstraints(Modifier.weight(1f).fillMaxSize().clipToBounds()) {
            val naStopien = maxWidth / zakres

            Box(Modifier.fillMaxSize().drawBehind {
                val srodekX = size.width / 2f
                val px = size.width / zakres
                var k = ((kurs - zakres / 2).toInt() / 5) * 5
                while (k <= kurs + zakres / 2) {
                    val x = srodekX + roznicaKatow(k.toFloat(), kurs) * px
                    val duza = k % 30 == 0
                    // Gdzie stoi litera strony świata, tam ona jest podziałką. Kreska co 30°
                    // idzie przez całą wysokość taśmy, a litery leżą przy górnej krawędzi,
                    // więc N, E, S i W dostawały linię przez środek znaku — na ekranie
                    // czytało się to jako „É" i „$" zamiast E i S.
                    if (!literaZamiastKreski(k, kurs, zakres)) {
                        drawRect(
                            Barwy.Linia.copy(alpha = if (duza) 0.9f else 0.5f),
                            topLeft = Offset(x, if (duza) 0f else size.height * 0.55f),
                            size = Size(1.dp.toPx(), if (duza) size.height else size.height * 0.45f),
                        )
                    }
                    k += 5
                }
                drawRect(Barwy.Akcent, topLeft = Offset(srodekX - 0.5.dp.toPx(), 0f),
                    size = Size(1.dp.toPx(), size.height))
            })

            STRONY_SWIATA.forEach { (kat, nazwa) ->
                val roznica = roznicaKatow(kat, kurs)
                // Znacznik domu stoi w tym samym pasie co litery i jest ważniejszy: przy
                // kursie 350° „N" i domek z namiarem nakładały się na siebie tak, że nie
                // dało się odczytać ani jednego. Litera ustępuje, domek zostaje.
                if (abs(roznica) <= zakres / 2 - 4f && !zaslanianePrzezDom(kat, stan, kurs)) {
                    Text(
                        nazwa,
                        style = Kroje.zgeszczona(12.sp, Barwy.Drugi),
                        modifier = Modifier.align(Alignment.TopCenter)
                            .offset(x = naStopien * roznica, y = 1.dp),
                    )
                }
            }

            if (stan.namiarNaDomSt >= 0f) {
                val roznica = roznicaKatow(stan.namiarNaDomSt, kurs)
                    .coerceIn(-zakres / 2 + 5f, zakres / 2 - 5f)
                Column(
                    Modifier.align(Alignment.TopCenter).offset(x = naStopien * roznica, y = 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Ikona(Piktogram.DOM, kolor = Barwy.Dobrze, rozmiar = 11.dp)
                    Text("%03.0f".format(stan.namiarNaDomSt),
                        color = Barwy.Dobrze, fontSize = 7.sp, lineHeight = 8.sp,
                        letterSpacing = 0.4.sp)
                }
            }
        }

        Text("GNSS", style = Kroje.Podpis, textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp))
    }
}

/** Różnica kątów w zakresie −180…180. */
private fun roznicaKatow(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

// --------------------------------------------------------------------------- rząd liczb

/**
 * Rząd liczb — **64 dp u spodu, tylko na LOT**. Nie sięga pod kolumnę przyrządów:
 * kończy się na jej krawędzi, więc miniatura mapy nie ma z czym kolidować.
 *
 * Każda wartość to blok 96 dp: liczba z jednostką, **pasek 2 dp** i etykieta pod nim.
 * Wznoszenie liczy się **od środka skali**, bo jego znak jest ważniejszy niż wartość.
 *
 * Po prawej blok **POZYCJA MASZYNY** — dziesiętne, pod nimi MGRS i stan GNSS. To jedyna
 * rzecz na ekranie, którą przepisuje się komuś przez radio, więc jest pełna, nie skrócona.
 */
@Composable
fun RzadLiczb(stan: StanMaszyny, teraz: Long, modifier: Modifier = Modifier) {
    val wiek = stan.wiekTelemetriiS(teraz)
    Row(
        modifier
            .fillMaxWidth()
            .height(Wymiary.RzadLiczb)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Barwy.ScrimDol)))
            .drawBehind { drawRect(Barwy.Linia2, size = Size(size.width, 1.dp.toPx())) }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Odczyt(Piktogram.WYSOKOSC, "%.1f".format(stan.wysokoscM), "m", wiek, Kroje.Ogromna,
            udzial = stan.wysokoscM / WYSOKOSC_SKALA)
        Odczyt(Piktogram.DOM, if (stan.dystansDoDomuM >= 0f) "%.0f".format(stan.dystansDoDomuM) else "—",
            "m", wiek, Kroje.Duza,
            udzial = if (stan.dystansDoDomuM >= 0f) stan.dystansDoDomuM / DYSTANS_SKALA else 0f)
        Odczyt(Piktogram.PREDKOSC, "%.1f".format(stan.predkoscMs), "m/s", wiek, Kroje.Duza,
            udzial = stan.predkoscMs / PREDKOSC_SKALA)
        Odczyt(Piktogram.WZNOSZENIE, "%+.1f".format(stan.wznoszenieMs), "m/s", wiek, Kroje.Duza,
            kolor = if (abs(stan.wznoszenieMs) > 4f) Barwy.Uwaga else Barwy.Tekst,
            udzial = stan.wznoszenieMs / WZNOSZENIE_SKALA, odSrodka = true)

        Spacer(Modifier.weight(1f))
        BlokPozycji(stan, wiek)
    }
}

/** Skale pasków — z makiety: 120 m, 300 m, 18 m/s, ±3 m/s. */
private const val WYSOKOSC_SKALA = 120f
private const val DYSTANS_SKALA = 300f
private const val PREDKOSC_SKALA = 18f
private const val WZNOSZENIE_SKALA = 3f

/**
 * Blok rzędu liczb. Podpis jest **piktogramem, nie słowem** — decyzja Toma 2026-08-28:
 * oszczędza miejsce, a przy tłumaczeniu rysunek broni się sam.
 */
@Composable
private fun Odczyt(
    piktogram: Piktogram,
    wartosc: String,
    jednostka: String,
    wiekS: Float,
    rozmiar: androidx.compose.ui.unit.TextUnit,
    kolor: Color = Barwy.Tekst,
    udzial: Float = 0f,
    odSrodka: Boolean = false,
) {
    val stare = wiekS > 2f
    val martwe = wiekS > 10f
    // Stala szerokosc, nie `widthIn(min)`: pasek pod liczba ma `fillMaxWidth`,
    // wiec przy samym minimum pierwszy blok zabieral cala wolna przestrzen.
    Column(Modifier.width(Wymiary.OdczytSzer)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (martwe) "——" else wartosc,
                style = Kroje.liczba(rozmiar, kolor = if (stare) Barwy.Wygasly else kolor))
            Text(jednostka, style = Kroje.liczba(11.sp, FontWeight.Normal, Barwy.Drugi))
        }
        Spacer(Modifier.height(2.dp))
        PasekWartosci(if (martwe) 0f else udzial, if (stare) Barwy.Wygasly else Barwy.Akcent, odSrodka)
        Spacer(Modifier.height(3.dp))
        Ikona(piktogram, kolor = if (stare) Barwy.Wygasly else Barwy.Drugi, rozmiar = 13.dp)
    }
}

/** Pasek pod liczbą: 2 dp, ze ściętym prawym końcem. `odSrodka` rysuje w obie strony. */
@Composable
private fun PasekWartosci(udzial: Float, kolor: Color, odSrodka: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Barwy.Linia2)
            .drawBehind {
                if (odSrodka) {
                    val u = udzial.coerceIn(-1f, 1f)
                    val srodek = size.width / 2f
                    val dl = srodek * abs(u)
                    drawRect(kolor, topLeft = Offset(if (u >= 0f) srodek else srodek - dl, 0f),
                        size = Size(dl, size.height))
                } else {
                    val dl = size.width * udzial.coerceIn(0f, 1f)
                    if (dl > 0f) {
                        val skos = 2.dp.toPx().coerceAtMost(dl)
                        drawPath(Path().apply {
                            moveTo(0f, 0f); lineTo(dl, 0f)
                            lineTo(dl - skos, size.height); lineTo(0f, size.height); close()
                        }, kolor)
                    }
                }
            }
    )
}

@Composable
private fun BlokPozycji(stan: StanMaszyny, wiekS: Float) {
    val stare = wiekS > 2f
    val kolor = if (stare) Barwy.Wygasly else Barwy.Tekst
    var oknoOtwarte by remember { mutableStateOf(false) }

    var wcisniety by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            // ⛔ KOLEJNOŚĆ MODYFIKATORÓW MA ZNACZENIE. `pointerInput` **przed**
            // `padding`, bo modyfikatory działają od zewnątrz: przy odwrotnej kolejności
            // obszar dotyku był pomniejszony o margines, czyli mniejszy niż sam napis.
            // Zgłoszone przez Toma 2026-08-28: „ciężko wyklikać, po prostu za mała".
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        wcisniety = true
                        tryAwaitRelease()
                        wcisniety = false
                    },
                    onTap = { oknoOtwarte = true },
                )
            }
            // Ramka i wcięcie **wewnątrz** obszaru dotyku: powiększają cel zamiast go
            // przycinać, a przy okazji pokazują, że blok w ogóle jest do naciśnięcia.
            .plyta(
                sciecie = 6.dp,
                tlo = if (wcisniety) Barwy.StanMocny else Color.Transparent,
                kolor = Barwy.Linia2,
                grubosc = 1.dp,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Ikona(Piktogram.PUNKT, kolor = Barwy.Drugi, rozmiar = 12.dp)
            // Podpowiedź, że to się otwiera. Bez niej dotknięcie jest funkcją ukrytą.
            Text("⤢", color = Barwy.Akcent, fontSize = 11.sp)
        }
        if (!stan.pozycjaZnana) {
            Text("——— BRAK POZYCJI ———",
                style = Kroje.liczba(Kroje.Wspolrzedne, FontWeight.SemiBold, Barwy.Blokada))
        } else {
            Text(
                Wspolrzedne.dziesietne(stan.szerokosc, stan.dlugosc),
                style = Kroje.liczba(Kroje.Wspolrzedne, FontWeight.SemiBold, kolor), maxLines = 1,
            )
            // 9 sp było nie do odczytania na aparaturze (zgłoszone 2026-08-28). Pełny,
            // duży zapis jest w oknie; tu podnosimy do 11 sp i zdejmujemy z tej linii
            // stan GNSS, żeby MGRS dostał całą szerokość.
            Text(
                Wspolrzedne.mgrs(stan.szerokosc, stan.dlugosc),
                style = Kroje.liczba(11.sp, FontWeight.Medium, Barwy.Drugi),
                maxLines = 1,
            )
            Text(opisFixa(stan), color = Barwy.Wygasly, fontSize = 9.sp,
                letterSpacing = 0.4.sp, maxLines = 1)
        }
    }

    if (oknoOtwarte) OknoPozycji(stan) { oknoOtwarte = false }
}

private fun opisFixa(stan: StanMaszyny): String = when (stan.rodzajFixa) {
    0, 1 -> "BRAK FIXA"
    2 -> "GNSS 2D"
    3 -> "GNSS 3D"
    4 -> "GNSS DGPS"
    5 -> "GNSS RTK FLOAT"
    6 -> "GNSS RTK FIX"
    else -> "GNSS ${stan.rodzajFixa}"
} + " · ${stan.satelity} sat"

// --------------------------------------------------------------------------- baner

@Composable
fun Baner(o: Ostrzezenie, modifier: Modifier = Modifier) {
    val kolor = when (o.waga) {
        Waga.BLOKADA -> Barwy.Blokada
        Waga.OSTRZEZENIE -> Barwy.Uwaga
        Waga.INFORMACJA -> Barwy.Akcent
    }
    val tlo = when (o.waga) {
        Waga.BLOKADA -> Barwy.BlokadaTlo
        Waga.OSTRZEZENIE -> Barwy.UwagaTlo
        Waga.INFORMACJA -> Barwy.AkcentTlo
    }
    Row(
        modifier
            .plyta(9.dp, Barwy.TaflaPelna, kolor, nakladka = tlo)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (o.waga) {
                Waga.BLOKADA -> "⛔"
                Waga.OSTRZEZENIE -> "⚠"
                Waga.INFORMACJA -> "•"
            },
            color = kolor, fontSize = 15.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(o.tekst, style = Kroje.zgeszczona(Kroje.Baner, kolor),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (o.szczegol.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(o.szczegol, color = Barwy.Drugi, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

// --------------------------------------------------------------------------- komendy

/**
 * Klawisz komendy lotu — **44 × 40 dp, płyta ze ściętym narożem 7 dp, krawędź 2 dp
 * w kolorze funkcji, przytrzymanie 1200 ms**.
 *
 * W makiecie RTL nosi **akcent**, a nie czerwień: czerwień jest zarezerwowana dla blokady,
 * a RTL nie jest blokadą, tylko komendą pierwotną. Wyróżnia go pełniejsze krycie i to,
 * że stoi pierwszy.
 *
 * ### Postęp liczy się z czasu wciśnięcia, nie z akumulatora
 *
 * §9 przekazania: w makiecie zliczanie ramek plus `onPointerLeave` gubiło gest przy każdym
 * zabłąkanym zdarzeniu. Tutaj postęp to `(teraz − początek) / czas`, a `tryAwaitRelease()`
 * trzyma gest do faktycznego puszczenia palca.
 */
@Composable
fun KlawiszKomendy(
    piktogram: Piktogram,
    etykieta: String,
    akcja: () -> Unit,
    modifier: Modifier = Modifier,
    kolor: Color = Barwy.Akcent,
    tlo: Color = Barwy.AkcentTlo,
    krycie: Float = 0.78f,
    czasMs: Long = 1200,
    dostepny: Boolean = true,
    powod: String? = null,
) {
    var postep by remember { mutableStateOf(0f) }
    var trzymany by remember { mutableStateOf(false) }
    val haptyka = LocalHapticFeedback.current
    val akcjaTeraz by rememberUpdatedState(akcja)

    LaunchedEffect(trzymany) {
        if (!trzymany) {
            postep = 0f
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (trzymany && postep < 1f) {
            postep = ((System.currentTimeMillis() - start).toFloat() / czasMs).coerceAtMost(1f)
            if (postep >= 1f) {
                haptyka.performHapticFeedback(HapticFeedbackType.LongPress)
                akcjaTeraz()
                trzymany = false
            }
            delay(16)
        }
    }

    Box(
        modifier
            .size(Wymiary.KomendaSzer, Wymiary.KomendaWys)
            .alpha(if (dostepny) krycie else 0.45f)
            .plyta(7.dp, Barwy.TaflaMocna, if (dostepny) kolor else Barwy.Linia2,
                nakladka = if (dostepny) tlo else Color.Transparent)
            .drawBehind {
                if (postep > 0f) {
                    drawRect(kolor.copy(alpha = 0.34f), size = Size(size.width * postep, size.height))
                }
            }
            .pointerInput(etykieta, dostepny) {
                detectTapGestures(
                    onPress = {
                        if (!dostepny) return@detectTapGestures
                        trzymany = true
                        haptyka.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        trzymany = false
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Ikona(piktogram, kolor = if (dostepny) kolor else Barwy.Wygasly, rozmiar = 22.dp)
            Spacer(Modifier.height(3.dp))
            Text(
                if (dostepny) etykieta else (powod?.take(12) ?: "brak"),
                // Po powiekszeniu klawisza do 72 dp napis wraca do czytelnego rozmiaru;
                // przy 44 dp trzeba bylo zejsc do 11 sp, zeby zmiescic „LADUJ".
                style = if (dostepny) Kroje.zgeszczona(13.sp, kolor)
                else Kroje.zgeszczona(9.sp, Barwy.Uwaga),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Potwierdzenie komendy — **wyśrodkowane, 126 dp nad spodem**, jak w makiecie.
 * Znika po ośmiu sekundach; bez tego przycisk jest obietnicą bez pokrycia.
 */
@Composable
fun PotwierdzenieKomendy(stan: StanMaszyny, teraz: Long, modifier: Modifier = Modifier) {
    val k = stan.ostatniaKomenda ?: return
    if (teraz - k.czasWyslania > 8000) return
    val kolor = when {
        k.przyjeta -> Barwy.Dobrze
        k.wynik != null -> Barwy.Blokada
        teraz - k.czasWyslania > 3000 -> Barwy.Uwaga
        else -> Barwy.Drugi
    }
    Row(
        modifier
            .height(30.dp)
            .background(Barwy.TaflaPelna)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(kolor, size = Size(size.width, w))
                drawRect(kolor, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
                drawRect(kolor, size = Size(w, size.height))
                drawRect(kolor, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (k.przyjeta) "✔" else if (k.wynik != null) "⛔" else "…",
            color = kolor, fontSize = 15.sp)
        Text("${k.nazwa} — ${k.stan(teraz).uppercase()}",
            style = Kroje.zgeszczona(16.sp, kolor), maxLines = 1)
    }
}

// --------------------------------------------------------------------------- klawisze

/** Chip wyboru — `chip()` z makiety: 30 dp, ścięcie 7 dp, akcent po lewej gdy wybrany. */
/*
 * ⚠ AKCJE KLAWISZY: `Modifier.pointerInput(klucze) { … }` **nie odświeża się**, dopóki klucze
 * się nie zmienią, a lambda akcji jest przy każdej kompozycji nowa. Bez `rememberUpdatedState`
 * klawisz wywołuje domknięcie z **pierwszej** kompozycji — czyli działa na stanie sprzed
 * pierwszego dotknięcia.
 *
 * Wyszło to na klawiszach zasięgu mapy (2026-08-26): „+" przybliżał raz i przestawał, bo za
 * każdym razem liczył od zasięgu, który obowiązywał, gdy mapa pierwszy raz się narysowała.
 * Dotąd nie było widać, bo każdy klawisz w kokpicie robił coś niezależnego od zmiennego stanu.
 * Ta sama pułapka jest opisana przy dotknięciach mapy w `MapaMisji`.
 */

@Composable
fun Chip(
    etykieta: String,
    wybrany: Boolean,
    modifier: Modifier = Modifier,
    // Nigdy ponizej Wymiary.CelDotyku — wysokosc podana nizej jest podnoszona po cichu,
    // zeby zadne wywolanie nie moglo zejsc ponizej rozmiaru palca.
    wysokosc: Dp = Wymiary.CelDotyku,
    rozmiar: androidx.compose.ui.unit.TextUnit = 14.sp,
    dostepny: Boolean = true,
    akcja: () -> Unit,
) {
    val akcjaTeraz by rememberUpdatedState(akcja)
    Box(
        modifier
            .height(maxOf(wysokosc, Wymiary.CelDotyku))
            .plyta(7.dp, Barwy.Tafla, if (wybrany) Barwy.Akcent else Barwy.Linia2,
                nakladka = if (wybrany) Barwy.AkcentTlo else Color.Transparent)
            .pointerInput(etykieta, wybrany, dostepny) {
                detectTapGestures(onTap = { if (dostepny) akcjaTeraz() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            etykieta,
            style = Kroje.zgeszczona(rozmiar, when {
                !dostepny -> Barwy.Wygasly
                wybrany -> Barwy.Akcent
                else -> Barwy.Drugi
            }),
            maxLines = 1,
        )
    }
}

/** Rodzaj klawisza akcji — decyduje o kolorze krawędzi i napisu. */
enum class Rodzaj { ZWYKLY, AKCENT, BLOKADA, UWAGA }

private fun kolorRodzaju(r: Rodzaj): Color = when (r) {
    Rodzaj.ZWYKLY -> Barwy.Linia
    Rodzaj.AKCENT -> Barwy.Akcent
    Rodzaj.BLOKADA -> Barwy.Blokada
    Rodzaj.UWAGA -> Barwy.Uwaga
}

/**
 * Klawisz akcji — `akcja()` z makiety: 34 dp, ścięcie 7 dp, wypełnienie `state` nad
 * `surf-c-hi`, krawędź w kolorze rodzaju.
 */
@Composable
fun PrzyciskAkcji(
    etykieta: String,
    akcja: () -> Unit,
    modifier: Modifier = Modifier,
    rodzaj: Rodzaj = Rodzaj.ZWYKLY,
    podpis: String? = null,
    dostepny: Boolean = true,
    powod: String? = null,
) {
    val kolor = kolorRodzaju(rodzaj)
    val napis = if (rodzaj == Rodzaj.ZWYKLY) Barwy.Tekst else kolor
    var wcisniety by remember { mutableStateOf(false) }
    val haptyka = LocalHapticFeedback.current

    Box(
        modifier
            .height(34.dp)
            .plyta(7.dp, Barwy.TaflaMocna, if (dostepny) kolor else Barwy.Linia2,
                nakladka = if (wcisniety) Barwy.StanMocny else Barwy.Stan)
            .pointerInput(etykieta, dostepny) {
                detectTapGestures(
                    onPress = {
                        if (!dostepny) return@detectTapGestures
                        wcisniety = true
                        haptyka.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        wcisniety = false
                    },
                    onTap = { if (dostepny) akcja() },
                )
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(etykieta, style = Kroje.zgeszczona(15.sp,
                if (dostepny) napis else Barwy.Wygasly), maxLines = 1)
            val pod = if (!dostepny) powod else podpis
            if (pod != null) {
                Text(pod, color = if (dostepny) Barwy.Drugi else Barwy.Uwaga, fontSize = 9.sp,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Zgodność wstecz dla ekranów roboczych, które wołają `Przycisk`. */
@Composable
fun Przycisk(
    etykieta: String,
    akcja: () -> Unit,
    modifier: Modifier = Modifier,
    podpis: String? = null,
    kolor: Color = Barwy.Linia,
    dostepny: Boolean = true,
    powod: String? = null,
    wyrozniony: Boolean = false,
) = PrzyciskAkcji(
    etykieta = etykieta,
    akcja = akcja,
    modifier = modifier,
    rodzaj = when {
        kolor == Barwy.Blokada -> Rodzaj.BLOKADA
        kolor == Barwy.Uwaga -> Rodzaj.UWAGA
        wyrozniony || kolor == Barwy.Akcent -> Rodzaj.AKCENT
        else -> Rodzaj.ZWYKLY
    },
    podpis = podpis,
    dostepny = dostepny,
    powod = powod,
)

/**
 * Klawisz akcji z przytrzymaniem — dla komend na panelach (WYŚLIJ, PRZERWIJ, SKOK).
 * Ta sama reguła co w [KlawiszKomendy]: postęp z czasu wciśnięcia.
 */
@Composable
fun PrzyciskPrzytrzymaj(
    etykieta: String,
    akcja: () -> Unit,
    modifier: Modifier = Modifier,
    rodzaj: Rodzaj = Rodzaj.AKCENT,
    czasMs: Long = 1200,
    dostepny: Boolean = true,
    powod: String? = null,
) {
    val kolor = kolorRodzaju(rodzaj)
    var postep by remember { mutableStateOf(0f) }
    var trzymany by remember { mutableStateOf(false) }
    val haptyka = LocalHapticFeedback.current
    val akcjaTeraz by rememberUpdatedState(akcja)

    LaunchedEffect(trzymany) {
        if (!trzymany) {
            postep = 0f
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (trzymany && postep < 1f) {
            postep = ((System.currentTimeMillis() - start).toFloat() / czasMs).coerceAtMost(1f)
            if (postep >= 1f) {
                haptyka.performHapticFeedback(HapticFeedbackType.LongPress)
                akcjaTeraz()
                trzymany = false
            }
            delay(16)
        }
    }

    Box(
        modifier
            .height(36.dp)
            .plyta(7.dp, Barwy.TaflaMocna, if (dostepny) kolor else Barwy.Linia2,
                nakladka = Barwy.Stan)
            .drawBehind {
                if (postep > 0f) {
                    drawRect(Barwy.StanMocny, size = Size(size.width * postep, size.height))
                }
            }
            .pointerInput(etykieta, dostepny) {
                detectTapGestures(
                    onPress = {
                        if (!dostepny) return@detectTapGestures
                        trzymany = true
                        haptyka.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        trzymany = false
                    }
                )
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(etykieta, style = Kroje.zgeszczona(13.sp,
                if (dostepny) kolor else Barwy.Wygasly), maxLines = 1)
            Text(
                when {
                    !dostepny -> powod ?: "brak"
                    postep > 0f -> "${(postep * 100).roundToInt()} %"
                    else -> "przytrzymaj"
                },
                color = if (!dostepny) Barwy.Uwaga else Barwy.Drugi, fontSize = 9.sp, maxLines = 1,
            )
        }
    }
}

/** Przycisk na piktogramie — `klawisz()` z makiety: 48 × 48, ścięcie 9 dp. */
@Composable
fun PrzyciskIkona(
    piktogram: Piktogram,
    etykieta: String,
    akcja: () -> Unit,
    modifier: Modifier = Modifier,
    kolor: Color = Barwy.Tekst,
    obwod: Color = Barwy.Linia,
    dostepny: Boolean = true,
    powod: String? = null,
    aktywny: Boolean = false,
) {
    var wcisniety by remember { mutableStateOf(false) }
    val haptyka = LocalHapticFeedback.current
    Box(
        modifier
            .size(48.dp)
            .plyta(9.dp, Barwy.TaflaMocna,
                if (!dostepny) Barwy.Linia2 else if (aktywny) Barwy.Akcent else obwod,
                nakladka = when {
                    wcisniety -> Barwy.StanMocny
                    aktywny -> Barwy.AkcentTlo
                    else -> Color.Transparent
                }),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().pointerInput(etykieta, dostepny) {
                detectTapGestures(
                    onPress = {
                        if (!dostepny) return@detectTapGestures
                        wcisniety = true
                        haptyka.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        wcisniety = false
                    },
                    onTap = { if (dostepny) akcja() },
                )
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Ikona(piktogram, kolor = if (dostepny) kolor else Barwy.Wygasly, rozmiar = 20.dp)
            Spacer(Modifier.height(3.dp))
            Text(etykieta, color = if (dostepny) Barwy.Drugi else Barwy.Wygasly,
                fontSize = Kroje.Etykieta, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp,
                maxLines = 1)
            if (!dostepny && powod != null) {
                Text(powod, color = Barwy.Uwaga, fontSize = 8.sp, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --------------------------------------------------------------------------- drobiazgi

/**
 * Przełącznik — tor 44 × 24 z suwakiem 18 × 18, jak w makiecie. Używany w warstwach
 * ekranu i w szufladzie kamery.
 */
@Composable
fun Przelacznik(
    wlaczony: Boolean,
    modifier: Modifier = Modifier,
    dostepny: Boolean = true,
    naZmiane: (Boolean) -> Unit,
) {
    val zmianaTeraz by rememberUpdatedState(naZmiane)
    Box(
        modifier
            .size(44.dp, 24.dp)
            .background(if (wlaczony) Barwy.AkcentTlo else Color.Transparent)
            .drawBehind {
                val w = 1.dp.toPx()
                val k = if (wlaczony) Barwy.Akcent else Barwy.Linia
                drawRect(k, size = Size(size.width, w))
                drawRect(k, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
                drawRect(k, size = Size(w, size.height))
                drawRect(k, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
            }
            .pointerInput(wlaczony, dostepny) {
                detectTapGestures(onTap = { if (dostepny) zmianaTeraz(!wlaczony) })
            }
            .padding(2.dp),
        contentAlignment = if (wlaczony) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).background(if (wlaczony) Barwy.Akcent else Barwy.Wygasly))
    }
}

/** Nagłówek grupy w szufladzie: etykieta po lewej, znacznik komendy po prawej. */
@Composable
fun NaglowekGrupy(etykieta: String, tag: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Etykieta(etykieta)
        Text(tag, color = Barwy.Wygasly, fontSize = 9.sp, letterSpacing = 0.6.sp)
    }
}

/** Pasek udziału — bateria, kanały RC, postęp misji. */
@Composable
fun Pasek(udzial: Float, kolor: Color, modifier: Modifier = Modifier, wysokosc: Int = 6) {
    Box(
        modifier
            .fillMaxWidth()
            .height(wysokosc.dp)
            .background(Barwy.Linia2)
            .drawBehind {
                drawRect(kolor, size = Size(size.width * udzial.coerceIn(0f, 1f), size.height))
            }
    )
}

/** Dioda stanu łącza: znak + kolor + słowo. Nigdy sam kolor. */
@Composable
fun Dioda(nazwa: String, zywe: Boolean, opis: String, modifier: Modifier = Modifier) {
    val kolor = if (zywe) Barwy.Dobrze else Barwy.Blokada
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (zywe) "●" else "○", color = kolor, fontSize = 11.sp)
        Spacer(Modifier.width(5.dp))
        Text(nazwa, color = Barwy.Drugi, fontSize = 11.sp, modifier = Modifier.width(58.dp), maxLines = 1)
        Text(opis, style = Kroje.liczba(12.sp, FontWeight.Medium, kolor), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NaglowekEkranu(tytul: String, podtytul: String = "", modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(tytul.uppercase(), style = Kroje.zgeszczona(20.sp), maxLines = 1)
        if (podtytul.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(podtytul, color = Barwy.Drugi, fontSize = 11.sp)
        }
    }
}
