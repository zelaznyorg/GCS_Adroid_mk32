package pl.dron15.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Misja
import pl.dron15.cockpit.domain.Profil
import pl.dron15.cockpit.domain.ProfilTrasy
import pl.dron15.cockpit.domain.PunktTrasy
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Wspolrzedne

/**
 * Profil terenu pod trasą — **jedyne miejsce, w którym widać, czy trasa mieści się nad ziemią**.
 *
 * Mapa płaska pokazuje, *którędy*; ten pas pokazuje, *jak wysoko*. Na maszynie o zapasie
 * ciągu, jaki ma DRON 15 (`../CLAUDE.md`, poz. 55 i 56), wznoszenie nad przeszkodę nie jest
 * darmowe — dlatego prześwit jest liczbą, którą trzeba zobaczyć **przed** lotem, a nie
 * odkryć w powietrzu.
 *
 * Kolory: zielony ponad progiem [ProfilTrasy.PROG_OSTRZEZENIA_M], pomarańczowy pod progiem,
 * czerwony przy prześwicie ujemnym — czyli tam, gdzie trasa wchodzi w zbocze.
 */
@Composable
fun PasProfilu(
    stan: StanMaszyny,
    misja: Misja,
    modifier: Modifier = Modifier,
    wysokoscPasa: androidx.compose.ui.unit.Dp = 116.dp,
) {
    val kontekst = LocalContext.current
    val teren = remember(kontekst) { MagazynTerenu.dla(kontekst) }
    val wersja = teren.wersja

    val profil = remember(wersja, misja, stan.domSzerokosc, stan.domDlugosc, stan.domUstalony) {
        if (!stan.domUstalony) ProfilTrasy.PUSTY
        else {
            val terenDomu = teren.wysokosc(stan.domSzerokosc, stan.domDlugosc)
            // Trasa liczy się od domu: maszyna startuje stamtąd, więc pierwszy odcinek
            // profilu to dolot, nie „teleport" do pierwszego punktu.
            val punkty = Profil.trasaZDomu(
                stan.domSzerokosc, stan.domDlugosc,
                misja.naMapie.map { PunktTrasy(it.szerokosc, it.dlugosc, it.wysokoscM) },
            )
            Profil.licz(punkty, terenDomu.takeIf { !it.isNaN() }) { lat, lon ->
                teren.wysokosc(lat, lon)
            }
        }
    }

    Column(modifier.height(wysokoscPasa).plyta(12.dp, Barwy.TaflaMocna, Barwy.Akcent)) {
        Naglowek(profil, teren.maDane, stan.domUstalony)
        Box(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
            if (profil.pusty) {
                Text(
                    when {
                        !stan.domUstalony -> "brak punktu startu — profil policzy się po ustaleniu domu"
                        !teren.maNaKarcie && teren.usterkaSieci != null ->
                            "model terenu nie dociąga się z sieci — ${teren.usterkaSieci}"
                        !teren.maDane -> "brak danych wysokościowych na karcie (/sdcard/dron15/teren)"
                        else -> "dołóż punkty trasy"
                    },
                    color = Barwy.Wygasly, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Wykres(profil, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun Naglowek(profil: ProfilTrasy, maTeren: Boolean, domUstalony: Boolean) {
    val barwa = when {
        profil.pusty -> Barwy.Wygasly
        profil.kolizja -> Barwy.Blokada
        profil.minPrzeswitM < ProfilTrasy.PROG_OSTRZEZENIA_M -> Barwy.Uwaga
        else -> Barwy.Dobrze
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Etykieta("profil trasy")
        if (!profil.pusty) {
            Text(
                Wspolrzedne.opisOdleglosci(profil.dlugoscM.toDouble()),
                style = Kroje.liczba(12.sp, kolor = Barwy.Drugi),
            )
            Text(
                if (profil.minPrzeswitM.isNaN()) "prześwit —"
                else "min. prześwit %+.0f m".format(profil.minPrzeswitM),
                style = Kroje.liczba(13.sp, kolor = barwa),
            )
            if (!profil.maksTerenM.isNaN()) {
                Text(
                    "teren %.0f–%.0f m".format(profil.minTerenM, profil.maksTerenM),
                    style = Kroje.liczba(12.sp, kolor = Barwy.Wygasly),
                )
            }
            if (!profil.kompletny && maTeren) {
                Text("dane niepełne", color = Barwy.Uwaga, fontSize = 10.sp)
            }
        } else if (!maTeren && domUstalony) {
            Text("brak danych wysokościowych", color = Barwy.Uwaga, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Wykres(profil: ProfilTrasy, modifier: Modifier) {
    Canvas(modifier) {
        val probki = profil.probki
        if (probki.size < 2) return@Canvas

        val wysokosci = probki.flatMap {
            listOfNotNull(it.terenM.takeIf { v -> !v.isNaN() }, it.lotM)
        }
        val min = (wysokosci.minOrNull() ?: 0f)
        val maks = (wysokosci.maxOrNull() ?: (min + 1f))
        val zapas = ((maks - min) * 0.12f).coerceAtLeast(5f)
        val dol = min - zapas
        val gora = maks + zapas
        val rozpietosc = (gora - dol).coerceAtLeast(1f)

        fun x(dystans: Float) = size.width * dystans / profil.dlugoscM.coerceAtLeast(1f)
        fun y(h: Float) = size.height * (1f - (h - dol) / rozpietosc)

        // teren jako wypełniona bryła — to jest ziemia, nie linia wykresu
        val ziemia = Path().apply {
            moveTo(0f, size.height)
            probki.forEach { p ->
                val h = if (p.terenM.isNaN()) dol else p.terenM
                lineTo(x(p.dystansM), y(h))
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(ziemia, Barwy.Ziemia.copy(alpha = 0.55f))
        val obrys = Path().apply {
            probki.forEachIndexed { i, p ->
                val h = if (p.terenM.isNaN()) dol else p.terenM
                if (i == 0) moveTo(x(p.dystansM), y(h)) else lineTo(x(p.dystansM), y(h))
            }
        }
        drawPath(obrys, Barwy.Drugi, style = Stroke(width = 1.5.dp.toPx()))

        // linia lotu, kolorowana odcinkami wg prześwitu
        for (i in 1 until probki.size) {
            val a = probki[i - 1]
            val b = probki[i]
            val przeswit = if (b.przeswitM.isNaN()) a.przeswitM else b.przeswitM
            val barwa = when {
                przeswit.isNaN() -> Barwy.Wygasly
                przeswit <= 0f -> Barwy.Blokada
                przeswit < ProfilTrasy.PROG_OSTRZEZENIA_M -> Barwy.Uwaga
                else -> Barwy.Akcent
            }
            drawLine(
                barwa,
                Offset(x(a.dystansM), y(a.lotM)),
                Offset(x(b.dystansM), y(b.lotM)),
                strokeWidth = 2.dp.toPx(),
            )
        }

        // próg ostrzegawczy nad terenem — linia przerywana, na wysokości „teren + 30 m"
        val prog = Path().apply {
            probki.forEachIndexed { i, p ->
                if (p.terenM.isNaN()) return@forEachIndexed
                val h = p.terenM + ProfilTrasy.PROG_OSTRZEZENIA_M
                if (i == 0) moveTo(x(p.dystansM), y(h)) else lineTo(x(p.dystansM), y(h))
            }
        }
        drawPath(
            prog, Barwy.Uwaga.copy(alpha = 0.5f),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
            ),
        )

        // miejsce najmniejszego prześwitu
        if (!profil.minPrzeswitM.isNaN()) {
            val px = x(profil.minPrzeswitDystansM)
            drawLine(
                if (profil.kolizja) Barwy.Blokada else Barwy.Uwaga,
                Offset(px, 0f), Offset(px, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
            )
        }

        drawLine(Barwy.Linia2, Offset(0f, size.height), Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx())
    }
}

/** Barwa dla wartości prześwitu — wspólna dla profilu, znaczników i widoku przestrzennego. */
internal fun barwaPrzeswitu(przeswitM: Float): Color = when {
    przeswitM.isNaN() -> Barwy.Wygasly
    przeswitM <= 0f -> Barwy.Blokada
    przeswitM < ProfilTrasy.PROG_OSTRZEZENIA_M -> Barwy.Uwaga
    else -> Barwy.Dobrze
}
