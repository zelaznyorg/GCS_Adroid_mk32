package pl.dron15.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.util.VLCVideoLayout
import pl.dron15.cockpit.domain.Ostrzezenia
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Tryby
import pl.dron15.cockpit.video.OdtwarzaczVlc
import pl.dron15.cockpit.video.TorWideo

/**
 * **Cztery stałe krawędzi.** Reguła z §2 przekazania, która rozwiązała większość kolizji
 * w makiecie: *każdy element liczy swoją krawędź od zajętego sąsiada, nie od ramki.*
 * Wartości 1:1 z makiety (`lewaWolna`, `kolLewa`, `KOL`).
 */
object Krawedzie {
    /** margines od ramki ekranu */
    val Ramka = 12.dp

    /** pierwszy wolny wiersz pod belką górną — tam siada taśma kursu */
    val PodBelka = Wymiary.TasmaGora

    /** szerokość, jaką zabiera kolumna przyrządów razem z marginesami (`KOL + 30`) */
    val PrzyKolumnie = Wymiary.Kolumna + 30.dp

    /** panele robocze zaczynają się poniżej belki i taśmy */
    val PanelGora = Wymiary.PanelGora
}

/**
 * Ekran LOT — makieta `Kokpit M3.dc.html`, §2 i §4 przekazania.
 *
 * Warstwy od spodu, każda z własnym miejscem: kadr → taśma kursu → przyrządy → komendy →
 * rząd liczb. Belka górna i nakładki należą do [Aplikacja], bo są wspólne dla widoków.
 *
 * ### Kolumna przyrządów
 *
 * Przyrządy i klawisze zebrane są **w jednym pasie 190 dp u krawędzi**, żeby środek kadru
 * został pusty: pion kamery z migawką pośrodku wysokości, miniatura mapy u dołu. Strona
 * kolumny (lewa / prawa) jest do wyboru w warstwach ekranu, a komendy siedzą **przy
 * krawędzi przeciwnej**, więc nigdy nie sąsiadują z migawką.
 *
 * Czego tu nadal nie ma: uzbrajania (zostaje na CH9), ruchu i zoomu głowicy (są
 * na pokrętłach aparatury — zasada 7 z UI.md), komunikatów z FC (należą do DIAGNOSTYKI).
 */
@Composable
fun Kokpit(
    stan: StanMaszyny,
    teraz: Long,
    odtwarzacz: TorWideo?,
    tloMapa: Boolean,
    warstwy: WarstwyEkranu,
    mapa: UstawieniaMapy,
    zasiegMapy: Float,
    naZasiegMapy: (Float) -> Unit,
    miniaturaWysunieta: Boolean,
    trybMigawki: TrybMigawki,
    potwierdzenieMigawki: Boolean,
    naZamiane: () -> Unit,
    naWysunieciMiniatury: () -> Unit,
    naRtl: () -> Unit,
    naLadowanie: () -> Unit,
    naPrzerwanieAutomatu: () -> Unit,
    naMigawke: () -> Unit,
    naZmianeTrybuMigawki: () -> Unit,
) {
    val baner = Ostrzezenia.najwazniejsze(Ostrzezenia.ocen(stan, teraz))

    // Wiatr z uśrednionego przechyłu w zawisie — cała logika w ui/PomiarWiatru.kt.
    val wiatr = pamietajWiatr(stan, teraz)

    // Bez wlasnego tla: pod spodem lezy warstwa obrazu z `Aplikacja`, a nieprzezroczyste
    // tlo zamalowywaloby kadr. Tlo calego ekranu maluje juz `Aplikacja`.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wolny kadr to pas między kolumną komend przy lewej krawędzi a kolumną kamery
        // przy prawej. Środek liczymy z rzeczywistej szerokości, a nie ze stałej z makiety:
        // `TasmaSrodekLewa = 504 dp` pochodziło z ramki 960 dp i przy 640 dp wypychało
        // taśmę 64 dp poza ekran — stąd ucięte „GNS" na zrzucie z aparatury.
        val lewaZajeta = Krawedzie.Ramka + Wymiary.KomendaSzer      // kolumna komend
        val prawaZajeta = Wymiary.KolumnaKamery + Krawedzie.Ramka   // kolumna kamery
        val srodekTasmy = (lewaZajeta + (maxWidth - prawaZajeta)) / 2
        val tasmaSzer = minOf(
            Wymiary.TasmaKursuSzer,
            maxWidth - lewaZajeta - prawaZajeta - Krawedzie.Ramka - Krawedzie.Ramka,
        )

        // 1 — kadr na pełnej powierzchni
        Tlo(stan, teraz, odtwarzacz, tloMapa, mapa,
            if (warstwy.rzadLiczb) Wymiary.RzadLiczb else 0.dp,
            zasiegMapy, naZasiegMapy,
            dziuraMiniatury = if (warstwy.miniaturaMapy) przesuniecieMiniatury(miniaturaWysunieta)
            else null)

        // Przybliżanie mapy lotu — przy LEWEJ krawędzi, w pionie, pod kolumną komend.
        // Dół kadru zajmuje miniatura i rząd liczb, prawą krawędź kolumna kamery, a góra
        // należy do belki; środek lewej krawędzi jest jedynym wolnym miejscem.
        if (tloMapa) {
            ZasiegPionowo(
                zasiegM = Zasieg.obowiazujacy(zasiegMapy, stan),
                auto = zasiegMapy <= Zasieg.AUTO,
                naZasieg = naZasiegMapy,
                naAuto = { naZasiegMapy(Zasieg.AUTO) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Krawedzie.Ramka),
            )
        }

        // 3 — taśma kursu: środkuje się nad OBRAZEM, nie nad ramką
        if (warstwy.tasmaKursu) {
            TasmaKursu(
                stan,
                szerokosc = tasmaSzer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = srodekTasmy - tasmaSzer / 2, y = Wymiary.TasmaGora),
            )
        }

        // 4 — przyrządy
        // Pas przyrzadow zapasu zajmuje wiersz nad rzedem liczb, wiec okrag idzie w gore
        // o jego wysokosc — regula z §2 przekazania: krawedz liczy sie od zajetego sasiada.
        val pasPrzyrzadow = warstwy.pasZapasu || warstwy.blokEnergii
        if (warstwy.okragPolozenia) {
            OkragPolozenia(
                stan, teraz,
                wiatr = wiatr,
                modifier = Modifier.align(Alignment.BottomCenter).padding(
                    bottom = (if (warstwy.rzadLiczb) Wymiary.OkragNadRzedem
                    else Wymiary.OkragBezRzedu) +
                            (if (pasPrzyrzadow) Wymiary.PasPrzyrzadow else 0.dp)
                ),
            )
        }

        if (warstwy.dokAkcji) {
            KolumnaKamery(
                stan = stan,
                tryb = trybMigawki,
                naMigawke = naMigawke,
                naZmianeTrybu = naZmianeTrybuMigawki,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        end = Krawedzie.Ramka,
                        // Stopka pionu (kierunek + zoom) zwisa 42 dp PONIZEJ skali i ma
                        // wlasne ~28 dp wysokosci. Rezerwujemy caly zwis, inaczej "POZIOM
                        // 1,0x" laduje na bloku POZYCJA MASZYNY w rzedzie liczb.
                        bottom = (if (warstwy.rzadLiczb) Wymiary.RzadLiczb else 0.dp) +
                                Wymiary.PionStopkaZwis,
                    ),
            )
        }

        if (warstwy.miniaturaMapy) {
            MiniaturaMapy(
                stan = stan,
                teraz = teraz,
                tloMapa = tloMapa,
                mapa = mapa,
                wysunieta = miniaturaWysunieta,
                naZamiane = naZamiane,
                naWysuniecie = naWysunieciMiniatury,
                modifier = Modifier
                    // Poprawka makiety 1.1: prawa krawedz nalezy w calosci do kolumny
                    // kamery, wiec miniatura siada w lewym dolnym narozniku — NAD rzedem
                    // liczb, nie obok niego (uchwyt schowanej ma byc w pasie y 322-344).
                    .align(Alignment.BottomStart)
                    .padding(
                        start = Krawedzie.Ramka,
                        bottom = if (warstwy.rzadLiczb) Wymiary.RzadLiczb else 0.dp,
                    ),
            )
        }

        // 5 — komendy przy krawędzi przeciwnej do kolumny, 68 dp od góry
        if (warstwy.dokAkcji) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = Krawedzie.Ramka, top = Wymiary.KomendyGora),
                verticalArrangement = Arrangement.spacedBy(Wymiary.KomendaOdstep),
            ) {
                Komendy(stan, teraz, naRtl, naLadowanie, naPrzerwanieAutomatu)
            }
        }

        // 6 — rząd liczb na pełnej szerokości. Kolumna kamery kończy się na y 290,
        // a rząd zaczyna na 344, więc nie kolidują — po poprawce makiety 1.1 zwolniło się
        // miejsce na blok POZYCJA MASZYNY po prawej stronie.
        if (warstwy.rzadLiczb) {
            RzadLiczb(
                stan, teraz,
                Modifier.align(Alignment.BottomStart).padding(horizontal = Krawedzie.Ramka),
            )
        }

        // 7 — przyrzady zapasu: pas nad rzedem liczb, miedzy miniatura mapy a kolumna
        // kamery. Miniatura konczy sie na 202 dp, kolumna zaczyna na maxWidth-70 —
        // 526 dp pasa miesci sie tam wysrodkowane.
        if (pasPrzyrzadow) {
            // Przypięty do LEWEJ krawędzi wolnego pasa, nie wyśrodkowany: wyśrodkowany
            // rozpychał się w obie strony i przy trzech blokach wchodził pod miniaturę
            // mapy z lewej oraz pod kolumnę kamery z prawej (zrzuty z 2026-08-28).
            // Krawędź liczy się od zajętego sąsiada — zasada z §2 przekazania M3.
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = Krawedzie.Ramka + Wymiary.MiniaturaSzer + 8.dp,
                        bottom = if (warstwy.rzadLiczb) Wymiary.RzadLiczb + 4.dp else 8.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (warstwy.pasZapasu) PasZapasu(stan, teraz)
                if (warstwy.blokEnergii) BlokEnergii(stan, teraz)
                // Cel wchodzi tylko wtedy, gdy maszyna leci sama — patrz BlokCelu.
                if (warstwy.blokCelu && Tryby.automatyczny(stan.tryb)) BlokCelu(stan, teraz)
            }
        }

        // 8 — komunikaty ponad wszystkim
        if (baner != null) {
            Baner(
                baner,
                Modifier.align(Alignment.TopCenter)
                    .padding(top = Wymiary.TasmaGora + Wymiary.TasmaKursu + 8.dp),
            )
        }

        PotwierdzenieKomendy(
            stan, teraz,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 126.dp),
        )

        if (potwierdzenieMigawki) {
            PotwierdzenieMigawki(
                trybMigawki,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 164.dp),
            )
        }
    }
}

/**
 * Komendy lotu: **RTL nad LĄDUJ**, obie 44 × 40 dp na przytrzymanie 1200 ms.
 *
 * RTL nosi **akcent**, nie czerwień: czerwień znaczy blokadę, a RTL jest komendą pierwotną.
 * Wyróżnia go pełniejsze krycie i to, że stoi pierwszy.
 *
 * W trybie automatycznym LĄDUJ zamienia się w PRZERWIJ. To nie jest w §4 przekazania,
 * ale audyt UI (F5) wykazał, że **automat raz uruchomiony był z ekranu nieodwoływalny** —
 * usunięcie tego klawisza byłoby cofnięciem naprawy, nie realizacją projektu.
 */
@Composable
private fun Komendy(
    stan: StanMaszyny,
    teraz: Long,
    naRtl: () -> Unit,
    naLadowanie: () -> Unit,
    naPrzerwanieAutomatu: () -> Unit,
) {
    val zywa = stan.telemetriaZywa(teraz)
    val powod = stan.powodBrakuKomend(teraz)
    val automat = Tryby.automatyczny(stan.tryb)

    KlawiszKomendy(
        Piktogram.RTL, "RTL", naRtl,
        kolor = Barwy.Akcent, tlo = Barwy.AkcentTlo, krycie = 0.78f,
        dostepny = zywa && stan.rtlDostepny,
        powod = powod ?: "brak poz.",
    )
    KlawiszKomendy(
        Piktogram.LADUJ, "LĄDUJ", naLadowanie,
        kolor = Barwy.Uwaga, tlo = Barwy.UwagaTlo, krycie = 0.72f,
        dostepny = zywa, powod = powod,
    )
    // Trzy klawisze STALE, a nie dwa z podmienianym drugim (poprawka makiety 1.1).
    // Klawisz, ktory zmienia znaczenie pod kciukiem, lamie pamiec ruchowa — a zasada
    // z audytu mowi: blokuj bez pokrycia i podaj powod, nie chowaj.
    KlawiszKomendy(
        Piktogram.PRZERWIJ, "PRZERWIJ", naPrzerwanieAutomatu,
        kolor = Barwy.Blokada, tlo = Barwy.BlokadaTlo, krycie = 0.72f,
        dostepny = zywa && automat,
        powod = powod ?: "tryb ręczny",
    )
}

// --------------------------------------------------------------------------- kadr

/**
 * Kadr: obraz albo mapa. Zamiana jest jedna dla całej aplikacji (zasada 2 z UI.md),
 * dlatego stan trzyma [Aplikacja], a nie ten ekran.
 *
 * `wcieciePodolu` mówi mapie, ile dołu przykrywa rząd liczb — bez tego podziałka mapy
 * wchodziła pod wysokość i dystans.
 */
@Composable
fun Tlo(
    stan: StanMaszyny,
    teraz: Long,
    odtwarzacz: TorWideo?,
    tloMapa: Boolean,
    mapa: UstawieniaMapy = UstawieniaMapy(),
    wcieciePodolu: Dp = 0.dp,
    zasiegMapy: Float = Zasieg.AUTO,
    naZasiegMapy: (Float) -> Unit = {},
    /**
     * Wysunięcie miniatury, gdy ma ona pokazywać OBRAZ — wtedy mapa dostaje w tym miejscu
     * **otwór**. `null` znaczy: rysuj mapę na całości.
     */
    dziuraMiniatury: Dp? = null,
) {
    // Mapa zaslania obraz swoim nieprzezroczystym tlem. Gdy jej nie ma, nie rysujemy tu
    // NIC — kadr pokazuje warstwa zamontowana na stale w `Aplikacja`. Nie wstawiac tu
    // `WidokWideo`: wymiana widoku przy zmianie zakladki gasila obraz na dobre
    // (`video output creation failed`) i potrafila zawiesic aplikacje. Patrz OdtwarzaczVlc.widok.
    if (!tloMapa) return

    // ### Otwór na kadr w miniaturze
    //
    // Obraz jest zamontowany na dnie ekranu (patrz `Aplikacja`), więc mapa rysowana na
    // pełnej powierzchni zasłania go **także w ramce miniatury**. Zamiast przenosić kadr
    // nad mapę — co znów znaczyłoby ruszanie widoku i powrót czarnego kadru — wycinamy
    // w mapie prostokąt dokładnie tam, gdzie miniatura ma swoje pole podglądu.
    //
    // `CompositingStrategy.Offscreen` jest tu warunkiem, nie ozdobą: bez rysowania
    // do osobnego bufora `BlendMode.Clear` wymazałby wszystko, co leży pod mapą.
    val modMapy = if (dziuraMiniatury == null) Modifier.fillMaxSize() else Modifier
        .fillMaxSize()
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val szer = Wymiary.MiniaturaSzer.toPx()
            val wys = (Wymiary.MiniaturaWys - Wymiary.Uchwyt).toPx()
            val lewa = Krawedzie.Ramka.toPx()
            val dol = size.height - wcieciePodolu.toPx() + dziuraMiniatury.toPx()
            drawRect(
                Color.Transparent,
                topLeft = Offset(lewa, dol - wys),
                size = Size(szer, wys),
                blendMode = BlendMode.Clear,
            )
        }

    Mapa(stan, teraz, modMapy, zasiegM = zasiegMapy,
        ustawienia = mapa, wcieciePodolu = wcieciePodolu, naZasieg = naZasiegMapy)
}

/** Powyzej tylu milisekund podpiecie obrazu jest juz odczuwalnym zacieciem. */
private const val PROG_PODPIECIA_MS = 100L

@Composable
fun WidokWideo(odtwarzacz: TorWideo?, modifier: Modifier = Modifier) {
    if (odtwarzacz == null) {
        Box(modifier.background(Barwy.Tlo), contentAlignment = Alignment.Center) {
            Text("BRAK ODTWARZACZA", color = Barwy.Wygasly, fontSize = 12.sp)
        }
        return
    }
    // `factory` chodzi na watku glownym. Wszystko, co dotyka libVLC poza podpieciem widoku,
    // musi wiec byc nieblokujace — patrz naglowek OdtwarzaczVlc.
    AndroidView(
        modifier = modifier,
        factory = { kontekst ->
            // Straznik regresji: to wykonuje sie na watku glownym, wiec ma byc tanie.
            // Przed poprawka z 2026-08-26 samo uruchomienie strumienia trwalo tu 2,5 s.
            val poczatek = android.os.SystemClock.elapsedRealtime()
            // Ten sam widok dla wszystkich ekranow — patrz OdtwarzaczVlc.widok. Wymiana
            // widoku przy zmianie zakladki dawala czarny kadr i potrafila zawiesic
            // aplikacje; tutaj widok tylko zmienia rodzica.
            val widok = odtwarzacz.widok(kontekst)
            odtwarzacz.zapewnijOdtwarzanie()
            val trwalo = android.os.SystemClock.elapsedRealtime() - poczatek
            if (trwalo > PROG_PODPIECIA_MS) {
                pl.dron15.cockpit.diag.Dziennik.ostrzezenie("wideo",
                    "podpiecie obrazu zajelo $trwalo ms na watku glownym")
            }
            widok
        },
        // Zejscie z ekranu NIE odpina niczego. Widok jest jeden na cala aplikacje i zyje
        // tak dlugo, jak ona — patrz TorWideo. Odpinanie przy zmianie zakladki kosztowalo
        // nas 2026-08-26 czarny kadr i zawieszenie aplikacji.
    )
}
