package pl.dron15.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Wspolrzedne

/**
 * Okno pełnej pozycji — otwierane dotknięciem bloku pozycji w rzędzie liczb.
 *
 * ### Po co
 *
 * W rzędzie liczb MGRS dzieli jedną linię ze stanem GNSS i ma **9 sp**, czyli przy
 * gęstości tej aparatury ok. 12 px wysokości. Zgłoszone przez Toma 2026-08-28: *„informacja
 * w MGRS jest za mała i jej nie widać"*. A to jedyna rzecz na ekranie, którą przepisuje się
 * komuś przez radio — więc musi dać się odczytać, a nie tylko zmieścić.
 *
 * Rząd liczb zostaje bez zmian: on ma mówić „gdzie jestem" jednym rzutem oka. To okno
 * odpowiada na inne pytanie — „podaj dokładne współrzędne" — i wtedy miejsce na ekranie
 * przestaje być ograniczeniem, bo pilot i tak stoi i czyta.
 *
 * ### Trzy zapisy naraz
 *
 * Dziesiętny, stopnie-minuty-sekundy i MGRS. Który jest potrzebny, zależy od tego, komu
 * się podaje: służbom zwykle DMS albo MGRS, do QGC i plików misji dziesiętne.
 * Wszystkie trzy są tu duże, bo nie wiadomo z góry, o który poprosi rozmówca.
 */
@Composable
fun OknoPozycji(stan: StanMaszyny, naZamkniecie: () -> Unit) {
    // ⛔ `Dialog` zakłada **własne okno** i nie dziedziczy `LocalDensity` nadpisanego
    // w `MainActivity`. A cały układ tej aplikacji stoi właśnie na tym nadpisaniu:
    // MK32 melduje gęstość 320, choć panel 7" 1280 × 800 ma realnie ok. 216 dpi.
    // Bez przekazania gęstości okno rysowało się w innej skali niż reszta kokpitu —
    // 560 dp wychodziło 1120 px zamiast 755 i zasłaniało kolumnę komend.
    val gestosc = LocalDensity.current
    Dialog(
        onDismissRequest = naZamkniecie,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalDensity provides gestosc) {
        Column(
            Modifier
                .width(Wymiary.OknoPozycjiSzer)
                .plyta(14.dp, Barwy.TaflaPelna, Barwy.Akcent)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Naglowek(naZamkniecie)
            Spacer(Modifier.height(12.dp))

            if (!stan.pozycjaZnana) {
                Text(
                    "BRAK POZYCJI",
                    style = Kroje.liczba(26.sp, FontWeight.Bold, Barwy.Blokada),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Maszyna nie ma rozwiązania pozycji. Na tej maszynie kurs pochodzi " +
                            "wyłącznie z bazy GNSS, więc bez niego nie ma też RTL — " +
                            "sprowadź w AltHold.",
                    color = Barwy.Drugi, fontSize = 13.sp,
                )
            } else {
                Zapis("WGS84 · dziesiętne",
                    Wspolrzedne.dziesietne(stan.szerokosc, stan.dlugosc))
                Zapis("WGS84 · stopnie, minuty, sekundy",
                    Wspolrzedne.dms(stan.szerokosc, stan.dlugosc))
                Zapis("MGRS", Wspolrzedne.mgrs(stan.szerokosc, stan.dlugosc))

                Spacer(Modifier.height(10.dp))
                Kreska()
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Drobiazg("WYSOKOŚĆ", "%.1f m".format(stan.wysokoscM))
                    Drobiazg(
                        "DO DOMU",
                        if (stan.dystansDoDomuM >= 0f) "%.0f m".format(stan.dystansDoDomuM)
                        else "—",
                    )
                    Drobiazg(
                        "NAMIAR",
                        if (stan.namiarNaDomSt >= 0f) "%03.0f°".format(stan.namiarNaDomSt)
                        else "—",
                    )
                    Drobiazg("GNSS", "${stan.satelity} sat · HDOP %.2f".format(stan.hdop))
                }

                if (stan.domUstalony) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "DOM  " + Wspolrzedne.dziesietne(stan.domSzerokosc, stan.domDlugosc) +
                                "   " + Wspolrzedne.mgrs(stan.domSzerokosc, stan.domDlugosc) +
                                // Skąd wzięty dom, decyduje o tym, czy „do domu" znaczy
                                // to samo, co zrobi RTL — patrz domain/Ogrodzenie.kt.
                                if (stan.domZMaszyny) "" else "  (zgadnięty, nie z maszyny)",
                        color = Barwy.Drugi, fontSize = 12.sp,
                    )
                }
            }
        }
        }
    }
}
@Composable
private fun Naglowek(naZamkniecie: () -> Unit) {
    val zamknij by rememberUpdatedState(naZamkniecie)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("POZYCJA MASZYNY", style = Kroje.zgeszczona(22.sp))
        Box(
            Modifier
                .size(Wymiary.CelDotyku)
                .pointerInput(Unit) { detectTapGestures(onTap = { zamknij() }) },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Barwy.Drugi, fontSize = 22.sp)
        }
    }
}

/** Jeden zapis współrzędnych: podpis i **duża** liczba, bo to ona ma być czytana. */
@Composable
private fun Zapis(podpis: String, wartosc: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(podpis, color = Barwy.Drugi, fontSize = 11.sp, letterSpacing = 0.6.sp)
        Text(wartosc, style = Kroje.liczba(24.sp, FontWeight.Bold, Barwy.Tekst), maxLines = 1)
    }
}

@Composable
private fun Drobiazg(podpis: String, wartosc: String) {
    Column {
        Text(podpis, color = Barwy.Drugi, fontSize = 10.sp, letterSpacing = 0.5.sp)
        Text(wartosc, style = Kroje.liczba(14.sp, FontWeight.SemiBold, Barwy.Tekst), maxLines = 1)
    }
}

@Composable
private fun Kreska() {
    Box(Modifier.fillMaxWidth().height(1.dp).drawBehind {
        drawRect(Barwy.Linia2, size = Size(size.width, size.height))
    })
}
