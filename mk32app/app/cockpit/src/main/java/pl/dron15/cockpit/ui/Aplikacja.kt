package pl.dron15.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.dron15.cockpit.diag.Dzwieki
import pl.dron15.cockpit.domain.Checklista
import pl.dron15.cockpit.domain.Misja
import pl.dron15.cockpit.domain.Poprawka
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Sygnaly
import pl.dron15.cockpit.domain.TrybMisji
import pl.dron15.cockpit.domain.Werdykt
import pl.dron15.cockpit.video.TorWideo

/** Komendy lotu. Trzy, bo tyle ma ekran — reszta zostaje na aparaturze. */
data class AkcjeLotu(
    val rtl: () -> Unit,
    val ladowanie: () -> Unit,
    val przerwanieAutomatu: () -> Unit,
)

/** Czynności panelu misji — §5 przekazania. */
data class AkcjeMisji(
    val wyslij: () -> Unit,
    val pobierzZMaszyny: () -> Unit,
    val zapisz: () -> Unit,
    val pauza: (Boolean) -> Unit,
    val skok: (Int) -> Unit,
    val przerwij: () -> Unit,
)

/**
 * Powłoka aplikacji — belka górna, wybór widoku i nakładki.
 *
 * ### Panele robocze leżą na kadrze, nie zamiast niego
 *
 * W makiecie PRZED LOTEM, RC i DIAGNOSTYKA to **płyty wpisane w kadr** (`top 64`,
 * `bottom 12`, bok od strony kolumny), a nie pełnoekranowe widoki. Obraz z kamery jest
 * pod nimi widoczny przez cały czas — operator nie traci go z oczu, wchodząc w checklistę.
 */
@Composable
fun Aplikacja(
    stan: StanMaszyny,
    checklista: Checklista?,
    odtwarzacz: TorWideo?,
    adresStrumienia: String,
    kanalySprzetowe: Set<Int>,
    warstwy: WarstwyEkranu,
    mapa: UstawieniaMapy,
    misja: Misja,
    trybMisji: TrybMisji,
    wybranyPunkt: Int,
    opisMisji: String,
    akcjeLotu: AkcjeLotu,
    akcjeKamery: AkcjeKamery,
    akcjeMisji: AkcjeMisji,
    naWarstwy: (WarstwyEkranu) -> Unit,
    naMape: (UstawieniaMapy) -> Unit,
    naMisje: (Misja) -> Unit,
    naTrybMisji: (TrybMisji) -> Unit,
    naWyborPunktu: (Int) -> Unit,
    naKontrolePrzedlotowa: () -> Unit,
    naOdswiezParametry: () -> Unit,
    /** Zapis parametru wskazanego przez checklistę — jedyna droga zmiany maszyny z ekranu. */
    naPoprawkeParametru: (Poprawka) -> Unit,
    naPrzelaczKanal: (Int) -> Unit,
) {
    var ekran by remember { mutableStateOf(Ekran.LOT) }
    var menuOtwarte by remember { mutableStateOf(false) }
    var warstwyOtwarte by remember { mutableStateOf(false) }
    var tloMapa by remember { mutableStateOf(false) }
    // 0 = zasięg dobiera się sam do śladu. Zoom ręczny **nie** przeżywa restartu: po starcie
    // maszyny operator ma zobaczyć całą trasę, a nie skalę sprzed tygodnia.
    var zasiegMapy by remember { mutableStateOf(Zasieg.AUTO) }
    var miniaturaWysunieta by remember { mutableStateOf(true) }
    var trybMigawki by remember { mutableStateOf(TrybMigawki.FOTO) }
    var potwierdzenieMigawki by remember { mutableStateOf(false) }

    // Jeden zegar dla całej aplikacji. Wiek danych ma tykać nawet wtedy, gdy telemetria
    // zamilkła — inaczej ekran zamarza razem z łączem (UI.md, zasada 6).
    var teraz by remember { mutableStateOf(System.currentTimeMillis()) }

    // Alarmy dźwiękowe. Siedzą tu, a nie na ekranie LOT, bo mają grać niezależnie
    // od otwartej zakładki — pilot planujący misję też musi usłyszeć utratę łącza.
    // dok/PROPOZYCJA_LOT.md §6E, audyt UI z 2026-08-19 pozycja F10.
    val dzwieki = remember { Dzwieki() }
    val sygnaly = remember { Sygnaly() }
    DisposableEffect(Unit) { onDispose { dzwieki.zwolnij() } }
    LaunchedEffect(teraz, warstwy.dzwiek) {
        if (warstwy.dzwiek) {
            sygnaly.ocen(stan, teraz).forEach { dzwieki.zagraj(it.rodzaj, teraz, it.pilnosc) }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            teraz = System.currentTimeMillis()
            delay(250)
        }
    }

    LaunchedEffect(potwierdzenieMigawki) {
        if (potwierdzenieMigawki) {
            delay(1500)
            potwierdzenieMigawki = false
        }
    }

    Box(Modifier.fillMaxSize().background(Barwy.Tlo)) {

        // ⛔ OBRAZ Z KAMERY WISI TUTAJ I NIE WOLNO GO STĄD RUSZAĆ.
        //
        // Jest **jeden** na całą aplikację, zamontowany raz i na zawsze. Ekrany rysują się
        // nad nim; te, które mają go zasłonić (mapa, MISJA), robią to nieprzezroczystym
        // tłem. Gdy tłem jest mapa, kadr **zmienia rozmiar i wjeżdża w ramkę miniatury** —
        // ale nadal jest tym samym elementem w tym samym miejscu drzewa.
        //
        // Zmierzone na aparaturze 2026-08-26, trzy podejścia, każde obalone:
        //  1. własny `VLCVideoLayout` w każdym ekranie → przy zmianie zakładki libVLC gubi
        //     wyjście obrazu (`destroying useless vout`, zjazd na dekoder programowy)
        //     i **nie zgłasza żadnego błędu** — kadr czarny, w dzienniku cisza;
        //  2. restart strumienia po przełączeniu → `attachViews` na wątku głównym czeka
        //     na ten sam natywny mutex, który trzyma wątek wideo w `stop()`/`play()`;
        //     ANR po 19 s i aplikacja zamknięta przez system;
        //  3. jeden widok wędrujący między rodzicami → przy zmianie rodzica ginie
        //     powierzchnia, a libVLC trzyma stare wiązanie okna:
        //     `no vout display modules matched` → `video output creation failed`.
        //
        // Zmiana rozmiaru `SurfaceView` powierzchni **nie** niszczy — i tylko dlatego
        // ten wariant działa. Szczegóły i pomiary: OdtwarzaczVlc.widok.
        val obrazWMiniaturze = tloMapa && warstwy.miniaturaMapy && ekran == Ekran.LOT
        val przesuniecieMin = przesuniecieMiniatury(miniaturaWysunieta)
        WidokWideo(
            odtwarzacz,
            if (obrazWMiniaturze) {
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = Krawedzie.Ramka,
                        bottom = if (warstwy.rzadLiczb) Wymiary.RzadLiczb else 0.dp,
                    )
                    .offset(y = przesuniecieMin)
                    // Wysokość bez uchwytu: kadr siada pod paskiem z napisem OBRAZ/MAPA,
                    // dokładnie tam, gdzie miniatura ma swoje pole podglądu.
                    .size(Wymiary.MiniaturaSzer, Wymiary.MiniaturaWys - Wymiary.Uchwyt)
            } else {
                Modifier.fillMaxSize()
            },
        )

        when (ekran) {
            Ekran.LOT -> Kokpit(
                stan = stan,
                teraz = teraz,
                odtwarzacz = odtwarzacz,
                tloMapa = tloMapa,
                warstwy = warstwy,
                mapa = mapa,
                zasiegMapy = zasiegMapy,
                naZasiegMapy = { zasiegMapy = it },
                miniaturaWysunieta = miniaturaWysunieta,
                trybMigawki = trybMigawki,
                potwierdzenieMigawki = potwierdzenieMigawki,
                naZamiane = { tloMapa = !tloMapa },
                naWysunieciMiniatury = { miniaturaWysunieta = !miniaturaWysunieta },
                naRtl = akcjeLotu.rtl,
                naLadowanie = akcjeLotu.ladowanie,
                naPrzerwanieAutomatu = akcjeLotu.przerwanieAutomatu,
                naMigawke = {
                    if (trybMigawki == TrybMigawki.FOTO) akcjeKamery.zdjecie()
                    else akcjeKamery.nagrywanie()
                },
                naZmianeTrybuMigawki = {
                    trybMigawki =
                        if (trybMigawki == TrybMigawki.FOTO) TrybMigawki.WIDEO else TrybMigawki.FOTO
                    potwierdzenieMigawki = true
                },
            )

            Ekran.MISJA -> EkranMisji(
                stan = stan,
                misja = misja,
                tryb = trybMisji,
                wybrany = wybranyPunkt,
                opisStanu = opisMisji,
                naMisje = naMisje,
                naTryb = naTrybMisji,
                naWybor = naWyborPunktu,
                naWyslanie = akcjeMisji.wyslij,
                naPobranieZMaszyny = akcjeMisji.pobierzZMaszyny,
                naZapis = akcjeMisji.zapisz,
                naPauze = akcjeMisji.pauza,
                naSkok = akcjeMisji.skok,
                naPrzerwanie = akcjeMisji.przerwij,
                mapa = mapa,
                naMape = naMape,
            )

            Ekran.KAMERA -> EkranKamery(
                stan = stan,
                teraz = teraz,
                odtwarzacz = odtwarzacz,
                adresStrumienia = adresStrumienia,
                akcje = akcjeKamery,
            )

            Ekran.PRZED, Ekran.RC, Ekran.DIAG -> {
                // Kadr zostaje widoczny pod panelem — operator nie traci obrazu z oczu.
                Tlo(stan, teraz, odtwarzacz, tloMapa)
                PanelRoboczy {
                    when (ekran) {
                        Ekran.PRZED -> {
                            val pozycje = checklista?.ocen(stan.parametry, stan, teraz).orEmpty()
                            EkranChecklisty(
                                pozycje = pozycje,
                                werdykt = checklista?.werdyktZbiorczy(pozycje)
                                    ?: Werdykt.BRAK_DANYCH,
                                pobranychParametrow = stan.parametry.size,
                                naOdswiez = naOdswiezParametry,
                                naKontrolePrzedlotowa = naKontrolePrzedlotowa,
                                naPoprawke = naPoprawkeParametru,
                                uzbrojony = stan.uzbrojony,
                            )
                        }

                        Ekran.RC -> EkranRc(
                            stan = stan,
                            teraz = teraz,
                            sprzetowe = kanalySprzetowe,
                            naPrzelaczSprzetowe = naPrzelaczKanal,
                            naOdswiez = naOdswiezParametry,
                        )

                        else -> EkranDiagnostyki(stan)
                    }
                }
            }
        }

        BelkaGorna(
            stan = stan,
            teraz = teraz,
            ekran = ekran,
            menuOtwarte = menuOtwarte,
            warstwyOtwarte = warstwyOtwarte,
            naMenu = { menuOtwarte = !menuOtwarte; warstwyOtwarte = false },
            naWarstwy = { warstwyOtwarte = !warstwyOtwarte; menuOtwarte = false },
            pokazWladze = warstwy.znacznikWladzy,
            naMotyw = {
                // Klawisz na belce **cykluje** po warunkach oświetlenia, bo w polu nie ma
                // czasu na otwieranie panelu. Pełna lista z opisami jest w WARSTWACH.
                val nowy = Motyw.entries[(warstwy.motyw.ordinal + 1) % Motyw.entries.size]
                Barwy.ustaw(nowy)
                naWarstwy(warstwy.copy(motyw = nowy))
            },
            modifier = Modifier.align(Alignment.TopStart),
        )

        // Nakładki gasną po dotknięciu obok — bez tego menu zostawałoby otwarte na kadrze.
        if (menuOtwarte || warstwyOtwarte) {
            Box(
                Modifier.fillMaxSize().pointerInput(menuOtwarte, warstwyOtwarte) {
                    detectTapGestures(onTap = { menuOtwarte = false; warstwyOtwarte = false })
                }
            )
        }

        if (menuOtwarte) {
            ListaWidokow(
                wybrany = ekran,
                naWybor = { ekran = it; menuOtwarte = false },
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = 34.dp, end = Krawedzie.Ramka),
            )
        }

        if (warstwyOtwarte) {
            NakladkaWarstw(
                warstwy = warstwy,
                mapa = mapa,
                naZmiane = { nowe ->
                    if (nowe.motyw != warstwy.motyw) Barwy.ustaw(nowe.motyw)
                    naWarstwy(nowe)
                },
                naZmianeMapy = naMape,
                naZamknij = { warstwyOtwarte = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = Wymiary.Belka)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * Płyta panelu roboczego — `stylPanel` z makiety: `top 64`, `bottom 12`, bok od strony
 * kolumny przyrządów, ścięcie 18 dp, krawędź akcentu po lewej.
 */
@Composable
private fun BoxScope.PanelRoboczy(tresc: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.align(Alignment.TopStart).fillMaxSize()) {
        // Odstępy z makiety rezerwowały miejsce na **kolumnę przyrządów**, której te trzy
        // panele (PRZED LOTEM, RC, DIAGNOSTYKA) w ogóle nie rysują: 68 dp z lewej i 220 dp
        // z prawej, czyli **30 % szerokości ekranu oddane pod nic**.
        //
        // Zmierzone 2026-08-26 na MK32: panel miał 686 z 962 dp. Skutkiem nie był sam
        // marnowany kadr — w RC kolumny wiersza (78+150+300+150 dp) przestawały się mieścić,
        // przez co kolumna funkcji dostawała zero szerokości, a nagłówek „obsługa" był ucięty.
        //
        // Rezerwa znika bezwarunkowo. Wąski ekran dostaje węższy margines, ale to już tylko
        // estetyka, nie miejsce na cudzy element.
        val ciasno = maxWidth < Wymiary.BelkaProgZwiezly
        val bok = if (ciasno) 14.dp else 20.dp
        val przyKolumnie = bok

        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = if (ciasno) 42.dp else Krawedzie.PanelGora,
                    bottom = Wymiary.PanelDol,
                    start = bok,
                    end = przyKolumnie,
                )
                .plyta(18.dp, Barwy.TaflaMocna, Barwy.Akcent)
                .padding(horizontal = if (ciasno) 10.dp else 13.dp, vertical = 11.dp)
        ) {
            tresc()
        }
    }
}
