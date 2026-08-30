package pl.dron15.cockpit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.net.siyi.KlientSiyi
import pl.dron15.cockpit.video.TorWideo
import kotlin.math.roundToInt

/** Wszystko, co ekran KAMERA umie kazać zrobić głowicy. Jeden worek zamiast piętnastu pól. */
data class AkcjeKamery(
    val obroc: (Int, Int) -> Unit,
    val stopObrotu: () -> Unit,
    val kat: (Float, Float) -> Unit,
    val centrum: () -> Unit,
    val zoom: (Int) -> Unit,
    val zoomBezwzgledny: (Float) -> Unit,
    val trybRuchu: (KlientSiyi.TrybRuchu) -> Unit,
    val ostroscWPunkcie: (Int, Int, Int, Int) -> Unit,
    val ostroscReczna: (Int) -> Unit,
    /**
     * CMD 0x21 — kodek i rozdzielczość. **Bez klawisza w interfejsie**: ta komenda
     * zawiesiła kamerę 2026-08-28. Zostaje w kontrakcie, bo droga jest sprawna i wróci,
     * gdy 0x21 zostanie sprawdzone na ziemi.
     */
    val ustawStrumien: (KlientSiyi.Kodek, Int, Int, Int) -> Unit,
    val zdjecie: () -> Unit,
    val nagrywanie: () -> Unit,
    val strumienRtsp: (String) -> Unit,
    /** Czy obraz idzie torem natywnym SIYI (`true`) czy RTSP (`false`). */
    val torSiyi: () -> Boolean,
    /** Ręczna zmiana drogi obrazu. */
    val przelaczTor: (Boolean) -> Unit,
    /** CMD 0x80 — restart kamery i/lub głowicy. Pierwszy argument kamera, drugi głowica. */
    val restart: (Boolean, Boolean) -> Unit,
)

private enum class ZakladkaKamery(val etykieta: String) {
    GLOWICA("GŁOWICA"), OBIEKTYW("OBIEKTYW"), AI("AI"), STRUMIEN("STRUMIEŃ")
}

/**
 * Ekran KAMERA — **stanowisko obsługi**, `dok/PRZEKAZANIE_M3.md` §6.
 *
 * Pełny obraz z HUD-em i **nic z komend lotu**: RTL, LĄDUJ i przyrządy zostają na LOT.
 * Powielanie ich tutaj tylko mnożyłoby miejsca, w których można je nacisnąć przez pomyłkę.
 *
 * Kadr jest domyślnie czysty. Ustawienia wyjeżdżają z góry po dotknięciu zakładki i chowają
 * się tym samym dotknięciem — zamiast doku, który zabierał dolny pas na stałe.
 */
@Composable
fun EkranKamery(
    stan: StanMaszyny,
    teraz: Long,
    odtwarzacz: TorWideo?,
    adresStrumienia: String,
    akcje: AkcjeKamery,
) {
    var zakladka by remember { mutableStateOf<ZakladkaKamery?>(null) }
    var ostrosc by remember { mutableStateOf<Offset?>(null) }
    var czasOstrosci by remember { mutableStateOf(0L) }
    val gestosc = LocalDensity.current
    // Ten sam powod co w MapaMisji: lambda gestu ma widziec biezace akcje, nie te
    // z pierwszej kompozycji.
    val a by rememberUpdatedState(akcje)

    // Ramka ostrości gaśnie sama — inaczej zostawałaby na kadrze do końca lotu.
    LaunchedEffect(czasOstrosci) {
        if (czasOstrosci > 0) {
            delay(1500)
            ostrosc = null
        }
    }

    // Bez wlasnego tla i bez wlasnego `WidokWideo`: kadr rysuje warstwa zamontowana
    // na stale w `Aplikacja`, a ten ekran kladzie na nim tylko HUD i gesty.
    Box(Modifier.fillMaxSize()) {

        // Warstwa gestów: przeciągnięcie steruje prędkością obrotu, samo dotknięcie
        // stawia ramkę ostrości. Dwa `pointerInput`, bo to dwa niezależne rozpoznania.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val polowaX = size.width / 2f
                    val polowaY = size.height / 2f
                    detectDragGestures(
                        onDragEnd = { a.stopObrotu() },
                        onDragCancel = { a.stopObrotu() },
                    ) { zmiana, _ ->
                        zmiana.consume()
                        val dx = (zmiana.position.x - polowaX) / polowaX
                        val dy = (zmiana.position.y - polowaY) / polowaY
                        a.obroc(
                            (dx * 100).roundToInt().coerceIn(-100, 100),
                            (-dy * 100).roundToInt().coerceIn(-100, 100),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { punkt ->
                        ostrosc = punkt
                        czasOstrosci = System.currentTimeMillis()
                        a.ostroscWPunkcie(
                            punkt.x.roundToInt(), punkt.y.roundToInt(),
                            size.width, size.height,
                        )
                    }
                }
        ) {
            Celownik(Modifier.fillMaxSize())
            // Podpowiedz gestow tylko przy zamknietej szufladzie — inaczej chowa sie pod nia.
            if (zakladka == null) {
                Text(
                    "PRZECIĄGNIJ OBRAZ — OBRÓT 0x07 · DOTKNIJ — OSTROŚĆ W PUNKCIE 0x04",
                    color = Barwy.Drugi, fontSize = 11.sp, letterSpacing = 0.5.sp,
                    modifier = Modifier.align(Alignment.Center).offset(y = 32.dp),
                )
            }
            ostrosc?.let { p ->
                val bok = 56.dp
                Box(
                    Modifier
                        .offset(
                            x = with(gestosc) { p.x.toDp() } - bok / 2,
                            y = with(gestosc) { p.y.toDp() } - bok / 2,
                        )
                        .size(bok)
                        .drawBehind {
                            val w = 1.dp.toPx()
                            drawRect(Barwy.Dobrze, size = Size(size.width, w))
                            drawRect(Barwy.Dobrze, topLeft = Offset(0f, size.height - w),
                                size = Size(size.width, w))
                            drawRect(Barwy.Dobrze, size = Size(w, size.height))
                            drawRect(Barwy.Dobrze, topLeft = Offset(size.width - w, 0f),
                                size = Size(w, size.height))
                        },
                )
            }
        }

        // --- HUD: zoom, kąty, nagrywanie. Kąty schodzą z kadru, gdy szuflada otwarta —
        // te same wartości są w środku, a dwa razy to samo to szum.
        // Makieta stawia zoom na 10 dp od gory; tam siedzi belka, wiec HUD zaczyna sie
        // tuz pod nia. Kolejnosc i odstepy zostaja: zoom, katy, wskaznik nagrywania.
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = Krawedzie.Ramka, top = 36.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "%.1f×".format(stan.glowicaZoom),
                    style = Kroje.liczba(20.sp, FontWeight.SemiBold,
                        if (stan.glowicaOdpowiada) Barwy.Tekst else Barwy.Wygasly),
                )
                Etykieta("zoom hybrydowy")
            }
            if (zakladka == null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "%.1f°  ·  %.1f°  ·  %.1f°"
                            .format(stan.glowicaYaw, stan.glowicaPitch, stan.przechylenieSt),
                        style = Kroje.liczba(13.sp, FontWeight.Medium,
                            if (stan.glowicaOdpowiada) Barwy.Tekst else Barwy.Wygasly),
                    )
                    Etykieta("obrót · pochylenie · przechył · 0x0D")
                }
            }
            if (stan.glowicaNagrywa) {
                Row(
                    Modifier
                        .height(22.dp)
                        .background(Barwy.BlokadaTlo)
                        .drawBehind {
                            val w = 1.dp.toPx()
                            drawRect(Barwy.Blokada, size = Size(size.width, w))
                            drawRect(Barwy.Blokada, topLeft = Offset(0f, size.height - w),
                                size = Size(size.width, w))
                            drawRect(Barwy.Blokada, size = Size(w, size.height))
                            drawRect(Barwy.Blokada, topLeft = Offset(size.width - w, 0f),
                                size = Size(w, size.height))
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(7.dp).background(Barwy.Blokada))
                    Text("REC · KARTA TF W ZR30",
                        style = Kroje.liczba(11.sp, FontWeight.Medium, Barwy.Blokada))
                }
            }
            if (!stan.glowicaOdpowiada) {
                Text(
                    "GŁOWICA NIE ODPOWIADA — zasilanie ZR30 i sieć 192.168.144.25",
                    style = Kroje.zgeszczona(11.sp, Barwy.Uwaga),
                    modifier = Modifier.plyta(6.dp, Barwy.TaflaPelna, Barwy.Uwaga)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // --- zakładki tekstem na kadrze, prawy górny
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = Krawedzie.Ramka, top = Wymiary.ZakladkiGora),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ZakladkaKamery.entries.forEach { z ->
                ZakladkaTekstem(z.etykieta, zakladka == z) {
                    zakladka = if (zakladka == z) null else z
                }
            }
        }

        Szuflada(
            zakladka = zakladka,
            stan = stan,
            adresStrumienia = adresStrumienia,
            akcje = akcje,
            modifier = Modifier.align(Alignment.TopStart),
        )

        PasOrientacji(
            stan, teraz,
            Modifier.align(Alignment.BottomStart)
                .padding(start = Krawedzie.Ramka, end = Wymiary.SzufladaPrawa),
        )
    }
}

/**
 * Zakładka: sam tekst na kadrze.
 *
 * Wysokość i **minimalna szerokość** biorą się z [Wymiary.CelDotyku]. Do 2026-08-26 było
 * 48 dp wysokości i sama treść wszerz, przez co „AI" miała ok. 40 dp — trafienie w nią
 * w rękawicy było loterią.
 */
@Composable
private fun ZakladkaTekstem(etykieta: String, aktywna: Boolean, naDotkniecie: () -> Unit) {
    Box(
        Modifier
            .height(Wymiary.ZakladkaKamery)
            .widthIn(min = Wymiary.CelDotyku)
            .plyta(6.dp, Barwy.TaflaMocna, if (aktywna) Barwy.Akcent else Barwy.Linia2,
                nakladka = if (aktywna) Barwy.AkcentTlo else Color.Transparent)
            .pointerInput(etykieta, aktywna) { detectTapGestures(onTap = { naDotkniecie() }) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(etykieta,
            style = Kroje.zgeszczona(13.sp, if (aktywna) Barwy.Akcent else Barwy.Tekst))
    }
}

/**
 * Szuflada ustawień — wyjeżdża z góry, `left 12 / right 82 / top 94`, do 322 dp wysokości.
 * Te liczby nie są dowolne: mijają pas odczytów HUD po lewej i miejsce kolumny po prawej.
 */
@Composable
private fun Szuflada(
    zakladka: ZakladkaKamery?,
    stan: StanMaszyny,
    adresStrumienia: String,
    akcje: AkcjeKamery,
    modifier: Modifier = Modifier,
) {
    // „wysokosc do 322 dp" z §6 znaczy sufit, nie stala: szuflada ma byc wysoka na tyle,
    // ile zajmuje jej tresc, a nie zostawiac pod nia pustego prostokata.
    AnimatedVisibility(
        visible = zakladka != null,
        enter = expandVertically(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(150)),
        modifier = modifier
            .padding(start = Wymiary.SzufladaLewa, top = Wymiary.SzufladaGora,
                end = Wymiary.SzufladaPrawa),
    ) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = Wymiary.SzufladaMaks)
            // Krawedz akcentu u GORY, nie po lewej: szuflada wyjezdza z gory i to
            // ta krawedz mowi, skad przyszla (makieta: border-top 2px prim).
            .clip(Ksztalty.Szuflada)
            .background(Barwy.TaflaPelna)
            .drawBehind {
                drawRect(Barwy.Akcent, size = Size(size.width, 2.dp.toPx()))
                drawRect(Barwy.Linia2, topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(size.width, 1.dp.toPx()))
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        when (zakladka) {
            ZakladkaKamery.GLOWICA -> PanelGlowicy(stan, akcje)
            ZakladkaKamery.OBIEKTYW -> PanelObiektywu(stan, akcje)
            ZakladkaKamery.STRUMIEN -> PanelStrumienia(adresStrumienia, akcje)
            ZakladkaKamery.AI -> PanelAi()
            null -> Unit
        }
    }
    }
}

@Composable
private fun PanelGlowicy(stan: StanMaszyny, akcje: AkcjeKamery) {
    Column {
        Etykieta("położenie")
        Text(
            "obrót %+.1f°    pochylenie %+.1f°".format(stan.glowicaYaw, stan.glowicaPitch),
            style = Kroje.liczba(16.sp, FontWeight.Bold),
        )
        Text("tryb ruchu: ${stan.glowicaTrybRuchu}",
            style = Kroje.liczba(12.sp, FontWeight.Medium, Barwy.Drugi))

        Spacer(Modifier.height(12.dp))
        Etykieta("tryb ruchu")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KlientSiyi.TrybRuchu.entries.forEach { t ->
                Przycisk(
                    t.etykieta, { akcje.trybRuchu(t) }, Modifier.width(88.dp).height(42.dp),
                    kolor = if (stan.glowicaTrybRuchu == t.etykieta) Barwy.Akcent else Barwy.Linia,
                    wyrozniony = stan.glowicaTrybRuchu == t.etykieta,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Etykieta("nastawy pochylenia")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Przycisk("0°", { akcje.kat(0f, 0f) }, Modifier.width(70.dp).height(42.dp))
            Przycisk("−45°", { akcje.kat(0f, -45f) }, Modifier.width(70.dp).height(42.dp))
            Przycisk("−90°", { akcje.kat(0f, -90f) }, Modifier.width(70.dp).height(42.dp))
            Przycisk("CENTRUM", akcje.centrum, Modifier.width(96.dp).height(42.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Ruch i zoom mają też pokrętła aparatury (CH12, CH15, CH16) — ekran ich nie zastępuje, " +
                    "tylko dokłada nastawy, których na pokrętle nie ma.",
            color = Barwy.Wygasly, fontSize = 10.sp,
        )
    }
}

/**
 * Kolejna krotność zoomu — **mnożna**, żeby krok był równie wyraźny przy 1× i przy 20×.
 * Zaokrąglona do 0,1, bo tyle właśnie niesie ładunek CMD 0x0F.
 */
private fun krokZoomu(obecny: Float, kierunek: Int): Float {
    val podstawa = if (obecny < 1f) 1f else obecny
    val nowy = if (kierunek > 0) podstawa * 1.35f else podstawa / 1.35f
    return (Math.round(nowy * 10f) / 10f).coerceIn(1f, 30f)
}

@Composable
private fun PanelObiektywu(stan: StanMaszyny, akcje: AkcjeKamery) {
    Column {
        Etykieta("zoom")
        Text("%.1f×".format(stan.glowicaZoom), style = Kroje.liczba(20.sp, FontWeight.Bold, Barwy.Akcent))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1f, 2f, 5f, 10f, 20f, 30f).forEach { k ->
                Przycisk("%.0f×".format(k), { akcje.zoomBezwzgledny(k) },
                    Modifier.width(56.dp).height(42.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        // ⛔ Nie wracać do `zoom(1)` + `zoom(0)`.
        //
        // Tak było do 2026-08-26 i dawało szarpnięcie zamiast ruchu: CMD 0x05 uruchamia
        // zoom CIĄGŁY, a natychmiastowe zero zatrzymywało go w tej samej chwili. Wynik
        // zależał od tego, ile milisekund minęło między dwoma pakietami — czyli od
        // przypadku. Krok bezwzględny (0x0F) jest powtarzalny: wiadomo, gdzie się skończy.
        //
        // Krok jest MNOŻNY, nie stały: 1,0 → 1,3 → 1,7 … bo przy 20× dodanie 0,5 jest
        // niezauważalne, a przy 1× to skok o połowę kadru.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Przycisk("ODDAL", { akcje.zoomBezwzgledny(krokZoomu(stan.glowicaZoom, -1)) },
                Modifier.width(96.dp).height(42.dp))
            Przycisk("PRZYBLIŻ", { akcje.zoomBezwzgledny(krokZoomu(stan.glowicaZoom, 1)) },
                Modifier.width(112.dp).height(42.dp))
        }

        Spacer(Modifier.height(12.dp))
        Etykieta("ostrość")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Przycisk("BLIŻEJ", { akcje.ostroscReczna(-1); akcje.ostroscReczna(0) },
                Modifier.width(88.dp).height(42.dp))
            Przycisk("DALEJ", { akcje.ostroscReczna(1); akcje.ostroscReczna(0) },
                Modifier.width(88.dp).height(42.dp))
            Przycisk("HDR", { }, Modifier.width(88.dp).height(42.dp),
                dostepny = false, powod = "ZR30 nie ma")
        }
        Spacer(Modifier.height(6.dp))
        Text("Ostrość w punkcie: dotknij kadru. HDR: producent podaje ZR30 jako nieobsługujące.",
            color = Barwy.Wygasly, fontSize = 10.sp)
    }
}

@Composable
private fun PanelStrumienia(adresStrumienia: String, akcje: AkcjeKamery) {
    var naSiyi by remember { mutableStateOf(akcje.torSiyi()) }
    Column {
        // ### Droga obrazu — do przełączenia w polu, bez laptopa
        //
        // Dwie drogi różnią się realnie, a nie kosmetycznie:
        //
        // - **SIYI 37256** — ta sama, którą bierze obraz fabryczna aplikacja. Równiejszy
        //   rytm, ale kamera obsługuje na niej **jednego klienta**: gdy działa SIYI FPV
        //   (także w tle), przychodzi cisza.
        // - **RTSP 8554** — zawsze dostępna, znosi wielu odbiorców, tempo nieco gorsze.
        //
        // Kokpit startuje na SIYI i sam schodzi na RTSP po 12 s bez klatki. Ręczny wybór
        // tutaj **wyłącza to samoczynne zejście** — skoro operator wskazał tor świadomie,
        // aplikacja nie ma go po cichu zmieniać.
        Etykieta("droga obrazu")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Podświetlenie bierzemy z odpowiedzi toru, nie z dotknięcia — gdyby zmiana
            // nie doszła do skutku (np. przy starym torze libVLC), klawisz wraca sam
            // zamiast kłamać, że przełączył.
            Przycisk("SIYI", { akcje.przelaczTor(true); naSiyi = akcje.torSiyi() },
                Modifier.width(104.dp).height(46.dp), podpis = "37256",
                kolor = if (naSiyi) Barwy.Akcent else Barwy.Linia, wyrozniony = naSiyi)
            Przycisk("RTSP", { akcje.przelaczTor(false); naSiyi = akcje.torSiyi() },
                Modifier.width(104.dp).height(46.dp), podpis = "8554",
                kolor = if (!naSiyi) Barwy.Akcent else Barwy.Linia, wyrozniony = !naSiyi)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (naSiyi) "Zajęta przez SIYI FPV? Zamknij tamtą aplikację." else adresStrumienia,
            color = Barwy.Drugi, fontSize = 10.sp,
        )

        // ### Restart — jedyne wyjście, gdy kamera przestaje odpowiadać
        //
        // Zasilanie idzie wprost z pakietu, więc w powietrzu nie ma wyłącznika. `CMD 0x80`
        // to programowy odpowiednik cyklu zasilania (instrukcja ZR30 v1.4 str. 58).
        //
        // ⚠ Rozdzielone celowo: **restart kamery nie porusza głowicą**, więc nie zmienia
        // kierunku patrzenia. Restart głowicy ją przestawia i dlatego jest osobnym
        // klawiszem, nie wspólnym.
        //
        // Oba wymagają **drugiego dotknięcia** — obraz znika na kilkanaście sekund
        // i przypadkowe naciśnięcie w locie byłoby kosztowne.
        Spacer(Modifier.height(16.dp))
        Etykieta("restart")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KlawiszZPotwierdzeniem("KAMERA", "bez ruchu głowicy") {
                akcje.restart(true, false)
            }
            KlawiszZPotwierdzeniem("GŁOWICA", "przestawi kierunek") {
                akcje.restart(false, true)
            }
        }

        Spacer(Modifier.height(16.dp))
        Etykieta("kodek i rozdzielczość")
        Spacer(Modifier.height(4.dp))
        // ⛔ ZMIANA WYŁĄCZONA — `CMD 0x21` ZAWIESIŁA KAMERĘ 2026-08-28.
        //
        // Jedna komenda 0x21 i ZR30 przestał obsługiwać cokolwiek: odpowiadał na ping,
        // przyjmował połączenia na 8554 i 37260, milczał na wszystko. Odratował go dopiero
        // cykl zasilania. Przyczyną był nasz błąd w ładunku (kodek przesunięty o jeden,
        // 8 bajtów zamiast 9) — poprawiony, ale nieprzetestowany na sprzęcie.
        //
        // Warunek przywrócenia: sprawdzić 0x21 na ziemi, z dostępem do zasilania głowicy.
        // Do tego czasu zostaje `narzedzia/siyi_gimbal.py setcodec`.
        Text(
            "Zmiana wyłączona — komenda 0x21 zawiesiła kamerę 2026-08-28. " +
                "Ustawiaj z komputera: narzedzia/siyi_gimbal.py setcodec",
            color = Barwy.Uwaga, fontSize = 11.sp,
        )
    }
}

/**
 * Klawisz, który trzeba dotknąć dwa razy: pierwsze dotknięcie odsłania POTWIERDŹ.
 *
 * Ten sam wzorzec co przy zapisie parametru z ekranu PRZED LOTEM. Powód jest tu taki sam:
 * skutku nie da się cofnąć jednym ruchem, a klawisz leży pod palcem w locie.
 */
@Composable
private fun KlawiszZPotwierdzeniem(etykieta: String, podpis: String, akcja: () -> Unit) {
    var pytany by remember { mutableStateOf(false) }
    Przycisk(
        etykieta = if (pytany) "POTWIERDŹ" else etykieta,
        akcja = { if (pytany) { pytany = false; akcja() } else pytany = true },
        modifier = Modifier.width(140.dp).height(46.dp),
        podpis = if (pytany) etykieta else podpis,
        kolor = if (pytany) Barwy.Uwaga else Barwy.Linia,
    )
}

/**
 * Zakładka AI — **wyłączona, ale widoczna, z podanym powodem** (§6 przekazania).
 *
 * Ukrycie jej byłoby gorsze: operator, który raz zobaczył śledzenie w materiałach SIYI,
 * szukałby go po całym interfejsie. Tu od razu widzi, czego brakuje i dlaczego.
 */
@Composable
private fun PanelAi() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⛔", color = Barwy.Blokada, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text("NIE NA TEJ MASZYNIE",
                style = Kroje.liczba(14.sp, FontWeight.Bold, Barwy.Blokada))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Rozpoznawanie, śledzenie, AI follow i ramka celu nie są funkcją ZR30 — wymagają " +
                    "osobnego modułu SIYI AI Tracking, którego na tej maszynie nie ma.",
            color = Barwy.Tekst, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        listOf("rozpoznawanie obiektów", "śledzenie celu", "AI follow", "ramka celu").forEach {
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("○", color = Barwy.Wygasly, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(it, color = Barwy.Wygasly, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("brak modułu", color = Barwy.Wygasly, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "⚠ Nawet po dokupieniu modułu nie wiadomo, czy da się go prowadzić z naszego kodu: " +
                    "instrukcja opisuje sterowanie przez aplikację SIYI FPV, nie przez protokół. " +
                    "Do sprawdzenia na sprzęcie przed planowaniem tej funkcji.",
            color = Barwy.Uwaga, fontSize = 11.sp,
        )
    }
}

/**
 * Pas orientacji u spodu — **wartości, których nie ma w belce** (§6 przekazania):
 * wysokość, kurs, odległość do domu i namiar na dom. Operator kamery ma wiedzieć,
 * gdzie jest maszyna, nie odrywając się od obrazu.
 */
@Composable
private fun PasOrientacji(stan: StanMaszyny, teraz: Long, modifier: Modifier = Modifier) {
    val wiek = stan.wiekTelemetriiS(teraz)
    val stare = wiek > 2f
    val kolor = if (stare) Barwy.Wygasly else Barwy.Tekst
    Row(
        modifier
            .fillMaxWidth()
            .height(Wymiary.PasOrientacji)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Barwy.Scrim.copy(alpha = 0f),
                    0.45f to Barwy.Scrim.copy(alpha = Barwy.Scrim.alpha * 0.85f),
                    1f to Barwy.Scrim)
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        PoleOrientacji("wysokość", "%.1f m".format(stan.wysokoscM), kolor)
        PoleOrientacji("kurs",
            if (stan.kursGnssDostepny) "%03.0f°".format(stan.kursGnssSt) else "---",
            if (stan.kursGnssDostepny) kolor else Barwy.Blokada)
        PoleOrientacji("do domu",
            if (stan.dystansDoDomuM >= 0f) "%.0f m".format(stan.dystansDoDomuM) else "—", kolor)
        PoleOrientacji("namiar na dom",
            if (stan.namiarNaDomSt >= 0f) "%03.0f°".format(stan.namiarNaDomSt) else "—",
            if (stan.namiarNaDomSt >= 0f) Barwy.Dobrze else Barwy.Wygasly)
    }
}

@Composable
private fun PoleOrientacji(etykieta: String, wartosc: String, kolor: Color) {
    Column {
        Etykieta(etykieta)
        Text(wartosc, style = Kroje.liczba(15.sp, FontWeight.Bold, kolor))
    }
}

/** Celownik w środku kadru — cienki krzyż z przerwą, żeby nie zasłaniał celu. */
@Composable
private fun Celownik(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = Offset(size.width / 2f, size.height / 2f)
        val d = 22.dp.toPx()
        val p = 7.dp.toPx()
        val k = Barwy.Akcent.copy(alpha = 0.75f)
        val g = 1.5.dp.toPx()
        drawLine(k, Offset(s.x - d, s.y), Offset(s.x - p, s.y), strokeWidth = g)
        drawLine(k, Offset(s.x + p, s.y), Offset(s.x + d, s.y), strokeWidth = g)
        drawLine(k, Offset(s.x, s.y - d), Offset(s.x, s.y - p), strokeWidth = g)
        drawLine(k, Offset(s.x, s.y + p), Offset(s.x, s.y + d), strokeWidth = g)
        drawCircle(k, radius = 2.dp.toPx(), center = s, style = Stroke(width = g))
    }
}
