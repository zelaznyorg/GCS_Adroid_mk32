package pl.dron15.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Piktogramy rysowane wektorowo, bez plików graficznych i bez zależności.
 *
 * Powód: na 7 cali w słońcu ikona czyta się szybciej niż słowo, a operator i tak zna te
 * funkcje z aparatury. Podpis zostaje, ale schodzi do 9 sp — jest podpowiedzią, nie treścią.
 *
 * Wszystkie rysunki liczą się w kwadracie 0..1 przemnożonym przez rozmiar, więc jedna
 * ikona wygląda tak samo w pasku stanu i na przycisku.
 */
enum class Piktogram {
    RTL, LADUJ, PRZERWIJ, FOTO, REC, STOP_REC,
    MAPA, OBRAZ, CELOWNIK, ZOOM_PLUS, ZOOM_MINUS,
    SATELITY, BATERIA, LACZE, CZAS, DOM, GLOWICA,
    LOT, MISJA, KAMERA, CHECKLISTA, APARATURA, DIAGNOSTYKA,
    MOTYW, WARSTWY, STRZALKA_DOL, STRZALKA_GORA, SZUKAJ, PUNKT, OSTROSC,

    // Piktogramy zamiast podpisów — decyzja Toma 2026-08-28. Oszczędzają miejsce
    // w pasie przyrządów i **bronią się same przy tłumaczeniu**: rysunek nie ma języka.
    WIATR, CIAG, ROZRZUT, WIBRACJE, GAZ, WYSOKOSC, PREDKOSC, WZNOSZENIE,

    /** Prog JOKER: od tej chwili trzeba ruszac do domu, zeby wrocic z rezerwa. */
    WRACAJ,

    /** Prog BINGO: powrot przestaje byc mozliwy. */
    REZERWA,
}

@Composable
fun Ikona(
    piktogram: Piktogram,
    modifier: Modifier = Modifier,
    kolor: Color = Barwy.Tekst,
    rozmiar: Dp = 22.dp,
) {
    Canvas(modifier.size(rozmiar)) { rysuj(piktogram, kolor) }
}

private fun DrawScope.rysuj(p: Piktogram, k: Color) {
    val w = size.width
    val g = w * 0.09f                       // grubość kreski
    val kreska = Stroke(width = g, cap = StrokeCap.Round)
    fun pt(x: Float, y: Float) = Offset(x * w, y * w)
    fun linia(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(k, pt(x1, y1), pt(x2, y2), strokeWidth = g, cap = StrokeCap.Round)

    when (p) {
        // powrót do domu: dach + strzałka wchodząca do środka
        Piktogram.RTL -> {
            linia(0.12f, 0.50f, 0.50f, 0.16f)
            linia(0.50f, 0.16f, 0.88f, 0.50f)
            linia(0.24f, 0.46f, 0.24f, 0.86f)
            linia(0.76f, 0.46f, 0.76f, 0.86f)
            linia(0.24f, 0.86f, 0.76f, 0.86f)
            drawPath(Path().apply {
                moveTo(0.50f * w, 0.78f * w)
                lineTo(0.36f * w, 0.58f * w)
                lineTo(0.64f * w, 0.58f * w)
                close()
            }, k)
        }
        // lądowanie: strzałka w dół na linię gruntu
        Piktogram.LADUJ -> {
            linia(0.50f, 0.12f, 0.50f, 0.62f)
            linia(0.28f, 0.42f, 0.50f, 0.66f)
            linia(0.72f, 0.42f, 0.50f, 0.66f)
            linia(0.14f, 0.86f, 0.86f, 0.86f)
        }
        // przerwanie automatu: krzyżyk
        Piktogram.PRZERWIJ -> {
            linia(0.22f, 0.22f, 0.78f, 0.78f)
            linia(0.78f, 0.22f, 0.22f, 0.78f)
        }
        Piktogram.FOTO -> {
            drawRoundRect(
                color = k, topLeft = pt(0.08f, 0.28f),
                size = Size(w * 0.84f, w * 0.52f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(g, g),
                style = kreska,
            )
            linia(0.34f, 0.28f, 0.42f, 0.16f)
            linia(0.66f, 0.28f, 0.58f, 0.16f)
            drawCircle(k, radius = w * 0.15f, center = pt(0.50f, 0.54f), style = kreska)
        }
        Piktogram.REC -> drawCircle(k, radius = w * 0.30f, center = pt(0.5f, 0.5f))
        Piktogram.STOP_REC -> drawRect(k, topLeft = pt(0.24f, 0.24f), size = Size(w * 0.52f, w * 0.52f))
        // mapa: rozłożona harmonijka
        Piktogram.MAPA -> {
            drawPath(Path().apply {
                moveTo(0.08f * w, 0.28f * w); lineTo(0.36f * w, 0.16f * w)
                lineTo(0.64f * w, 0.30f * w); lineTo(0.92f * w, 0.18f * w)
                lineTo(0.92f * w, 0.74f * w); lineTo(0.64f * w, 0.86f * w)
                lineTo(0.36f * w, 0.72f * w); lineTo(0.08f * w, 0.84f * w); close()
            }, k, style = kreska)
            linia(0.36f, 0.16f, 0.36f, 0.72f)
            linia(0.64f, 0.30f, 0.64f, 0.86f)
        }
        // obraz z kamery: prostokąt z „dzióbkiem"
        Piktogram.OBRAZ -> {
            drawRoundRect(
                color = k, topLeft = pt(0.08f, 0.28f),
                size = Size(w * 0.56f, w * 0.44f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(g, g),
                style = kreska,
            )
            drawPath(Path().apply {
                moveTo(0.70f * w, 0.42f * w); lineTo(0.92f * w, 0.28f * w)
                lineTo(0.92f * w, 0.72f * w); lineTo(0.70f * w, 0.58f * w); close()
            }, k, style = kreska)
        }
        Piktogram.CELOWNIK -> {
            drawCircle(k, radius = w * 0.30f, center = pt(0.5f, 0.5f), style = kreska)
            linia(0.50f, 0.04f, 0.50f, 0.22f)
            linia(0.50f, 0.78f, 0.50f, 0.96f)
            linia(0.04f, 0.50f, 0.22f, 0.50f)
            linia(0.78f, 0.50f, 0.96f, 0.50f)
        }
        Piktogram.ZOOM_PLUS, Piktogram.ZOOM_MINUS -> {
            drawCircle(k, radius = w * 0.28f, center = pt(0.44f, 0.44f), style = kreska)
            linia(0.66f, 0.66f, 0.92f, 0.92f)
            linia(0.30f, 0.44f, 0.58f, 0.44f)
            if (p == Piktogram.ZOOM_PLUS) linia(0.44f, 0.30f, 0.44f, 0.58f)
        }
        // satelita: korpus + dwa panele
        Piktogram.SATELITY -> {
            drawRect(k, topLeft = pt(0.40f, 0.36f), size = Size(w * 0.20f, w * 0.28f), style = kreska)
            linia(0.10f, 0.30f, 0.34f, 0.30f)
            linia(0.10f, 0.30f, 0.10f, 0.70f)
            linia(0.10f, 0.70f, 0.34f, 0.70f)
            linia(0.66f, 0.30f, 0.90f, 0.30f)
            linia(0.90f, 0.30f, 0.90f, 0.70f)
            linia(0.66f, 0.70f, 0.90f, 0.70f)
        }
        Piktogram.BATERIA -> {
            drawRoundRect(
                color = k, topLeft = pt(0.06f, 0.30f),
                size = Size(w * 0.74f, w * 0.40f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(g * 0.6f, g * 0.6f),
                style = kreska,
            )
            drawRect(k, topLeft = pt(0.84f, 0.42f), size = Size(w * 0.10f, w * 0.16f))
        }
        // łącze: maszt z falami
        Piktogram.LACZE -> {
            linia(0.50f, 0.36f, 0.50f, 0.90f)
            drawArc(k, -120f, 60f, false, topLeft = pt(0.22f, 0.10f),
                size = Size(w * 0.56f, w * 0.56f), style = kreska)
            drawArc(k, -130f, 80f, false, topLeft = pt(0.06f, -0.06f),
                size = Size(w * 0.88f, w * 0.88f), style = kreska)
        }
        Piktogram.CZAS -> {
            drawCircle(k, radius = w * 0.38f, center = pt(0.5f, 0.5f), style = kreska)
            linia(0.50f, 0.28f, 0.50f, 0.52f)
            linia(0.50f, 0.52f, 0.68f, 0.62f)
        }
        Piktogram.DOM -> {
            linia(0.14f, 0.48f, 0.50f, 0.16f)
            linia(0.50f, 0.16f, 0.86f, 0.48f)
            linia(0.26f, 0.46f, 0.26f, 0.86f)
            linia(0.74f, 0.46f, 0.74f, 0.86f)
            linia(0.26f, 0.86f, 0.74f, 0.86f)
        }
        // głowica: kula w widełkach
        Piktogram.GLOWICA -> {
            drawCircle(k, radius = w * 0.26f, center = pt(0.5f, 0.56f), style = kreska)
            drawArc(k, 180f, 180f, false, topLeft = pt(0.14f, 0.18f),
                size = Size(w * 0.72f, w * 0.72f), style = kreska)
            drawCircle(k, radius = w * 0.08f, center = pt(0.5f, 0.56f))
        }
        // zakładki
        Piktogram.LOT -> {                       // czterowirnikowiec z góry
            drawCircle(k, radius = w * 0.11f, center = pt(0.5f, 0.5f), style = kreska)
            listOf(0.22f to 0.22f, 0.78f to 0.22f, 0.22f to 0.78f, 0.78f to 0.78f).forEach { (x, y) ->
                linia(0.5f, 0.5f, x, y)
                drawCircle(k, radius = w * 0.11f, center = pt(x, y), style = Stroke(width = g * 0.8f))
            }
        }
        Piktogram.MISJA -> {                     // trasa z punktami
            linia(0.16f, 0.80f, 0.42f, 0.34f)
            linia(0.42f, 0.34f, 0.84f, 0.56f)
            drawCircle(k, radius = w * 0.10f, center = pt(0.16f, 0.80f))
            drawCircle(k, radius = w * 0.10f, center = pt(0.42f, 0.34f), style = Stroke(width = g * 0.8f))
            drawCircle(k, radius = w * 0.10f, center = pt(0.84f, 0.56f), style = Stroke(width = g * 0.8f))
        }
        Piktogram.KAMERA -> {
            drawRoundRect(
                color = k, topLeft = pt(0.08f, 0.26f),
                size = Size(w * 0.84f, w * 0.50f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(g, g),
                style = kreska,
            )
            drawCircle(k, radius = w * 0.14f, center = pt(0.50f, 0.51f), style = kreska)
        }
        Piktogram.CHECKLISTA -> {
            drawRect(k, topLeft = pt(0.16f, 0.12f), size = Size(w * 0.68f, w * 0.76f), style = kreska)
            linia(0.30f, 0.38f, 0.42f, 0.50f)
            linia(0.42f, 0.50f, 0.68f, 0.26f)
            linia(0.30f, 0.66f, 0.70f, 0.66f)
        }
        Piktogram.APARATURA -> {                 // drążki
            drawRoundRect(
                color = k, topLeft = pt(0.06f, 0.28f),
                size = Size(w * 0.88f, w * 0.44f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(g, g),
                style = kreska,
            )
            drawCircle(k, radius = w * 0.09f, center = pt(0.30f, 0.50f))
            drawCircle(k, radius = w * 0.09f, center = pt(0.70f, 0.50f))
        }
        Piktogram.DIAGNOSTYKA -> {               // linia pulsu
            linia(0.06f, 0.56f, 0.30f, 0.56f)
            linia(0.30f, 0.56f, 0.42f, 0.22f)
            linia(0.42f, 0.22f, 0.56f, 0.80f)
            linia(0.56f, 0.80f, 0.68f, 0.56f)
            linia(0.68f, 0.56f, 0.94f, 0.56f)
        }
        // --- dołożone dla przekazania M3 (dok/PRZEKAZANIE_M3.md)

        // motyw: półksiężyc wpisany w tarczę — jeden znak na oba stany
        Piktogram.MOTYW -> {
            drawCircle(k, radius = w * 0.34f, center = pt(0.5f, 0.5f), style = kreska)
            drawPath(Path().apply {
                moveTo(0.50f * w, 0.16f * w)
                cubicTo(0.74f * w, 0.28f * w, 0.74f * w, 0.72f * w, 0.50f * w, 0.84f * w)
                cubicTo(0.72f * w, 0.72f * w, 0.72f * w, 0.28f * w, 0.50f * w, 0.16f * w)
                close()
            }, k)
        }
        // warstwy: trzy nałożone romby
        Piktogram.WARSTWY -> {
            listOf(0.30f, 0.50f, 0.70f).forEach { y ->
                drawPath(Path().apply {
                    moveTo(0.50f * w, (y - 0.16f) * w); lineTo(0.86f * w, y * w)
                    lineTo(0.50f * w, (y + 0.16f) * w); lineTo(0.14f * w, y * w); close()
                }, k, style = Stroke(width = g * 0.8f))
            }
        }
        Piktogram.STRZALKA_DOL -> {
            linia(0.20f, 0.38f, 0.50f, 0.66f)
            linia(0.80f, 0.38f, 0.50f, 0.66f)
        }
        Piktogram.STRZALKA_GORA -> {
            linia(0.20f, 0.62f, 0.50f, 0.34f)
            linia(0.80f, 0.62f, 0.50f, 0.34f)
        }
        Piktogram.SZUKAJ -> {
            drawCircle(k, radius = w * 0.28f, center = pt(0.44f, 0.44f), style = kreska)
            linia(0.64f, 0.64f, 0.92f, 0.92f)
        }
        // punkt trasy: kropla ze środkiem
        Piktogram.PUNKT -> {
            drawPath(Path().apply {
                moveTo(0.50f * w, 0.92f * w)
                cubicTo(0.18f * w, 0.58f * w, 0.20f * w, 0.14f * w, 0.50f * w, 0.14f * w)
                cubicTo(0.80f * w, 0.14f * w, 0.82f * w, 0.58f * w, 0.50f * w, 0.92f * w)
                close()
            }, k, style = kreska)
            drawCircle(k, radius = w * 0.11f, center = pt(0.50f, 0.42f))
        }
        // ostrość: ramka z narożnikami
        Piktogram.OSTROSC -> {
            listOf(
                Triple(0.10f, 0.10f, 1f), Triple(0.90f, 0.10f, -1f),
            ).forEach { (x, y, kx) ->
                linia(x, y, x + kx * 0.22f, y)
                linia(x, y, x, y + 0.22f)
            }
            listOf(
                Triple(0.10f, 0.90f, 1f), Triple(0.90f, 0.90f, -1f),
            ).forEach { (x, y, kx) ->
                linia(x, y, x + kx * 0.22f, y)
                linia(x, y, x, y - 0.22f)
            }
            drawCircle(k, radius = w * 0.12f, center = pt(0.5f, 0.5f), style = Stroke(width = g * 0.8f))
        }

        // JOKER: dom z zegarem — czas ruszac do domu
        Piktogram.WRACAJ -> {
            linia(0.04f, 0.46f, 0.32f, 0.20f)
            linia(0.32f, 0.20f, 0.60f, 0.46f)
            linia(0.12f, 0.42f, 0.12f, 0.80f)
            linia(0.52f, 0.42f, 0.52f, 0.62f)
            linia(0.12f, 0.80f, 0.44f, 0.80f)
            drawCircle(k, radius = w * 0.24f, center = pt(0.72f, 0.72f),
                style = Stroke(width = g * 0.9f))
            linia(0.72f, 0.56f, 0.72f, 0.72f)
            linia(0.72f, 0.72f, 0.85f, 0.79f)
        }

        // BINGO: bateria PRZEKRESLONA — powrot przestal byc mozliwy.
        // Pierwsza wersja miala wykrzyknik w srodku; przy 14 dp kreska i kropka
        // zlewaly sie w jeden blok i ikona czytala sie jak zwykla bateria.
        Piktogram.REZERWA -> {
            drawRect(k, topLeft = pt(0.06f, 0.30f), size = Size(w * 0.72f, w * 0.40f),
                style = Stroke(width = g * 0.9f))
            drawRect(k, topLeft = pt(0.82f, 0.42f), size = Size(w * 0.10f, w * 0.16f))
            linia(0.10f, 0.82f, 0.76f, 0.18f)
        }

        // wiatr: trzy smugi, dwie z zawiniętym końcem
        Piktogram.WIATR -> {
            linia(0.08f, 0.30f, 0.60f, 0.30f)
            drawArc(k, -100f, 210f, false,
                topLeft = pt(0.56f, 0.16f), size = Size(w * 0.28f, w * 0.28f),
                style = kreska)
            linia(0.08f, 0.52f, 0.72f, 0.52f)
            linia(0.08f, 0.74f, 0.52f, 0.74f)
            drawArc(k, -110f, 200f, false,
                topLeft = pt(0.46f, 0.62f), size = Size(w * 0.24f, w * 0.24f),
                style = kreska)
        }

        // zapas ciągu: sufit i strzałka, która się w niego wspina
        Piktogram.CIAG -> {
            linia(0.14f, 0.16f, 0.86f, 0.16f)
            linia(0.50f, 0.88f, 0.50f, 0.34f)
            linia(0.50f, 0.34f, 0.30f, 0.54f)
            linia(0.50f, 0.34f, 0.70f, 0.54f)
        }

        // rozrzut silników: cztery wirniki w układzie X, jeden wyraźnie większy.
        // Waga na klinie była przy 13 dp nie do odczytania, a to i tak mówi więcej:
        // rzecz w tym, że **jeden silnik pracuje mocniej niż reszta**.
        Piktogram.ROZRZUT -> {
            linia(0.26f, 0.26f, 0.74f, 0.74f)
            linia(0.74f, 0.26f, 0.26f, 0.74f)
            drawCircle(k, radius = w * 0.11f, center = pt(0.26f, 0.74f), style = Stroke(g * 0.8f))
            drawCircle(k, radius = w * 0.11f, center = pt(0.74f, 0.26f), style = Stroke(g * 0.8f))
            drawCircle(k, radius = w * 0.11f, center = pt(0.26f, 0.26f), style = Stroke(g * 0.8f))
            drawCircle(k, radius = w * 0.20f, center = pt(0.74f, 0.74f))
        }

        // wibracje: fala o rosnącej amplitudzie
        Piktogram.WIBRACJE -> {
            val p2 = Path().apply {
                moveTo(0.08f * w, 0.50f * w)
                lineTo(0.24f * w, 0.38f * w)
                lineTo(0.40f * w, 0.62f * w)
                lineTo(0.56f * w, 0.26f * w)
                lineTo(0.72f * w, 0.74f * w)
                lineTo(0.88f * w, 0.50f * w)
            }
            drawPath(p2, k, style = kreska)
        }

        // gaz: słupek wypełniony od dołu do ok. połowy.
        // Suwak z poprzeczką czytał się przy 13 dp jako znak „+".
        Piktogram.GAZ -> {
            drawRect(k, topLeft = pt(0.30f, 0.10f), size = Size(w * 0.40f, w * 0.80f),
                style = Stroke(width = g * 0.9f))
            drawRect(k, topLeft = pt(0.36f, 0.46f), size = Size(w * 0.28f, w * 0.38f))
        }

        // wysokość: strzałka w górę od gruntu
        Piktogram.WYSOKOSC -> {
            linia(0.12f, 0.88f, 0.88f, 0.88f)
            linia(0.50f, 0.82f, 0.50f, 0.20f)
            linia(0.50f, 0.20f, 0.32f, 0.38f)
            linia(0.50f, 0.20f, 0.68f, 0.38f)
        }

        // prędkość: łuk prędkościomierza ze wskazówką
        Piktogram.PREDKOSC -> {
            drawArc(k, 165f, 210f, false,
                topLeft = pt(0.10f, 0.16f), size = Size(w * 0.80f, w * 0.80f),
                style = kreska)
            linia(0.50f, 0.56f, 0.74f, 0.34f)
        }

        // wznoszenie: strzałki w górę i w dół obok siebie
        Piktogram.WZNOSZENIE -> {
            linia(0.30f, 0.86f, 0.30f, 0.20f)
            linia(0.30f, 0.20f, 0.16f, 0.34f)
            linia(0.30f, 0.20f, 0.44f, 0.34f)
            linia(0.70f, 0.14f, 0.70f, 0.80f)
            linia(0.70f, 0.80f, 0.56f, 0.66f)
            linia(0.70f, 0.80f, 0.84f, 0.66f)
        }
    }
}
