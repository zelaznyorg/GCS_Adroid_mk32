package pl.dron15.cockpit.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Motyw kokpitu — tokeny 1:1 z makiety `Kokpit M3.dc.html` (`_ds/industry`), §8 przekazania.
 *
 * Dwa motywy, **ten sam zestaw nazw**. Kod nigdzie nie pyta „czy jasny" — bierze token
 * i dostaje wartość właściwą dla bieżącego motywu.
 *
 * ### Dlaczego przez stan, a nie przez `CompositionLocal`
 *
 * Połowa interfejsu rysuje się w `drawBehind` i `Canvas`, czyli **poza fazą kompozycji**,
 * gdzie `@Composable` getter nie istnieje. Paleta trzymana w `mutableStateOf` działa w obu
 * fazach: Compose zapisuje odczyt stanu także w fazie rysowania, więc zmiana motywu
 * unieważnia zarówno kompozycję, jak i rysunek.
 */
data class Paleta(
    val ciemny: Boolean,
    val surf: Color,
    val surfC: Color,
    val surfCHi: Color,
    val surfCMax: Color,
    val onSurf: Color,
    val onSurfVar: Color,
    val onSurfDim: Color,
    val outline: Color,
    val outlineVar: Color,
    val prim: Color,
    /** wypełnienie elementu zaznaczonego — `--prim-c` */
    val primC: Color,
    /** tekst na pełnym wypełnieniu akcentu */
    val onPrim: Color,
    val ok: Color,
    val uwaga: Color,
    val tertC: Color,
    val err: Color,
    val errC: Color,
    /** przygaszenie pod palcem i pasek postępu */
    val state: Color,
    val stateHi: Color,
    /** przejście tonalne belki */
    val scrim: Color,
    /** przejście tonalne rzędu liczb i pasa orientacji */
    val scrimB: Color,
    /** wnętrze przyrządu: tło, podziałka, trzy poziomy tuszu */
    val instr: Color,
    val instrLinia: Color,
    val instrTusz: Color,
    val instrTusz2: Color,
    val instrTusz3: Color,
    /** wskaźnik położenia: niebo i ziemia */
    val niebo: Color,
    val ziemia: Color,
)

object Palety {
    val CIEMNY = Paleta(
        ciemny = true,
        surf = Color(0xFF0B0F11),
        surfC = Color(0x6B0A1014),
        surfCHi = Color(0xAD0A1014),
        surfCMax = Color(0xEB070B0D),
        onSurf = Color(0xFFE3EAEE),
        onSurfVar = Color(0xFF93A4AE),
        onSurfDim = Color(0xFF5C6A72),
        outline = Color(0x4D7E96A8),
        outlineVar = Color(0x247E96A8),
        prim = Color(0xFF4ED8F2),
        primC = Color(0x294ED8F2),
        onPrim = Color(0xFF00363F),
        ok = Color(0xFF35D07A),
        uwaga = Color(0xFFF5A623),
        tertC = Color(0x29F5A623),
        err = Color(0xFFFF6B62),
        errC = Color(0x2EFF6B62),
        state = Color(0x14FFFFFF),
        stateHi = Color(0x1FFFFFFF),
        scrim = Color(0xD1060809),
        scrimB = Color(0xCC060809),
        instr = Color(0xFF0F1417),
        instrLinia = Color(0x217E96A8),
        instrTusz = Color(0xD9E3EAEE),
        instrTusz2 = Color(0x80E3EAEE),
        instrTusz3 = Color(0x52E3EAEE),
        niebo = Color(0x384ED8F2),
        ziemia = Color(0x3835D07A),
    )

    val JASNY = Paleta(
        ciemny = false,
        surf = Color(0xFFE6EBED),
        surfC = Color(0xE0F4F7F8),
        surfCHi = Color(0xF0F7FAFB),
        surfCMax = Color(0xF5F9FBFC),
        onSurf = Color(0xFF101619),
        onSurfVar = Color(0xFF3E4C55),
        onSurfDim = Color(0xFF6C7A83),
        outline = Color(0x57182C3A),
        outlineVar = Color(0x26182C3A),
        prim = Color(0xFF00616F),
        primC = Color(0x2400616F),
        onPrim = Color(0xFFFFFFFF),
        ok = Color(0xFF0E6B3C),
        uwaga = Color(0xFF8A4B00),
        tertC = Color(0x248A4B00),
        err = Color(0xFFA5231C),
        errC = Color(0x24A5231C),
        state = Color(0x1200141E),
        stateHi = Color(0x1F00141E),
        scrim = Color(0xEDEEF2F4),
        scrimB = Color(0xEBEEF2F4),
        instr = Color(0xFFF4F7F8),
        instrLinia = Color(0x29182C3A),
        instrTusz = Color(0xCC101619),
        instrTusz2 = Color(0x7A101619),
        instrTusz3 = Color(0x4D101619),
        niebo = Color(0x3D00616F),
        ziemia = Color(0x3D0E6B3C),
    )

    /**
     * **DZIEŃ** — pełne słońce. Nie „jasny motyw", tylko inny zestaw zasad.
     *
     * Trzy różnice wobec [JASNY], każda wymuszona przez odbicie w ekranie:
     * **zero przezroczystości** (panel warstw przy kryciu 92 % przepuszczał rząd liczb —
     * `dok/AUDYT_M3.md` U1), **czerń zamiast grafitu** na tekst, i **grubsze krawędzie**.
     * Kolory akcentów są ciemne i nasycone, bo jasny błękit na bieli znika.
     */
    val DZIEN = Paleta(
        ciemny = false,
        surf = Color(0xFFFFFFFF),
        surfC = Color(0xFFF2F5F6),
        surfCHi = Color(0xFFFAFCFC),
        surfCMax = Color(0xFFFFFFFF),
        onSurf = Color(0xFF000000),
        onSurfVar = Color(0xFF2B3439),
        onSurfDim = Color(0xFF5A656B),
        outline = Color(0xFF1A2226),
        outlineVar = Color(0xFF8C979D),
        prim = Color(0xFF004A73),
        primC = Color(0xFFCFE2EF),
        onPrim = Color(0xFFFFFFFF),
        ok = Color(0xFF075B2E),
        uwaga = Color(0xFF7A3F00),
        tertC = Color(0xFFFFE2B8),
        err = Color(0xFF8E0011),
        errC = Color(0xFFFFD2D6),
        state = Color(0xFFE2E8EA),
        stateHi = Color(0xFFCFD8DC),
        scrim = Color(0xFFFFFFFF),
        scrimB = Color(0xFFFFFFFF),
        instr = Color(0xFFFFFFFF),
        instrLinia = Color(0xFF8C979D),
        instrTusz = Color(0xFF000000),
        instrTusz2 = Color(0xFF3A4247),
        instrTusz3 = Color(0xFF6B7479),
        niebo = Color(0x4700455C),
        ziemia = Color(0x47075B2E),
    )

    /**
     * **NOC** — bursztyn na czerni, jasność zbita do ok. jednej trzeciej.
     *
     * Biel po zmroku psuje adaptację wzroku na kilkanaście minut, a pilot musi na przemian
     * patrzeć na ekran i w niebo. Bursztyn jest kompromisem: dość jasny, żeby czytać,
     * dość ciepły, żeby nie kasować widzenia nocnego tak jak biel i błękit.
     */
    val NOC = Paleta(
        ciemny = true,
        surf = Color(0xFF000000),
        surfC = Color(0xD9080503),
        surfCHi = Color(0xF0080503),
        surfCMax = Color(0xFA050302),
        onSurf = Color(0xFFD99A3C),
        onSurfVar = Color(0xFF9C6E2B),
        onSurfDim = Color(0xFF63461C),
        outline = Color(0x8C7A5522),
        outlineVar = Color(0x3D7A5522),
        prim = Color(0xFFF0AE4A),
        primC = Color(0x2EF0AE4A),
        onPrim = Color(0xFF120A02),
        ok = Color(0xFF8C9440),
        uwaga = Color(0xFFD99A3C),
        tertC = Color(0x2ED99A3C),
        err = Color(0xFFC24E2E),
        errC = Color(0x33C24E2E),
        state = Color(0x14D99A3C),
        stateHi = Color(0x24D99A3C),
        scrim = Color(0xE0000000),
        scrimB = Color(0xDB000000),
        instr = Color(0xF00A0603),
        instrLinia = Color(0x3D7A5522),
        instrTusz = Color(0xE6D99A3C),
        instrTusz2 = Color(0x8CD99A3C),
        instrTusz3 = Color(0x4DD99A3C),
        niebo = Color(0x33D99A3C),
        ziemia = Color(0x2E8C9440),
    )

    /**
     * **NVG** — pod gogle noktowizyjne. Ciemna czerwień, **zero bieli i zero błękitu**.
     *
     * Biały piksel zasypia gogle: wzmacniacz obrazu ściemnia całe pole, żeby się bronić,
     * i pilot na moment traci to, co miał widzieć. Czerwień leży poza zakresem, na który
     * gogle reagują najmocniej.
     *
     * ⛔ **Stany nie różnią się tu odcieniem, tylko jasnością** — wszystkie mieszczą się
     * w rodzinie czerwieni. Dlatego znaczenie musi nieść także kształt, nie sam kolor:
     * pasek czujników rysuje sprawny czujnik konturem, a uszkodzony wypełnieniem
     * (`ui/PasekCzujnikow.kt`), i ta decyzja bierze się właśnie stąd.
     */
    val NVG = Paleta(
        ciemny = true,
        surf = Color(0xFF000000),
        surfC = Color(0xD90A0202),
        surfCHi = Color(0xF00A0202),
        surfCMax = Color(0xFA060101),
        onSurf = Color(0xFFB83A2C),
        onSurfVar = Color(0xFF832619),
        onSurfDim = Color(0xFF551710),
        outline = Color(0x8C7A2418),
        outlineVar = Color(0x3D7A2418),
        prim = Color(0xFFD94834),
        primC = Color(0x2ED94834),
        onPrim = Color(0xFF0C0201),
        ok = Color(0xFF8E3327),
        uwaga = Color(0xFFC2523A),
        tertC = Color(0x2EC2523A),
        err = Color(0xFFFF5F45),
        errC = Color(0x33FF5F45),
        state = Color(0x14B83A2C),
        stateHi = Color(0x24B83A2C),
        scrim = Color(0xE0000000),
        scrimB = Color(0xDB000000),
        instr = Color(0xF0080101),
        instrLinia = Color(0x3D7A2418),
        instrTusz = Color(0xE6B83A2C),
        instrTusz2 = Color(0x8CB83A2C),
        instrTusz3 = Color(0x4DB83A2C),
        niebo = Color(0x33B83A2C),
        ziemia = Color(0x2E8E3327),
    )
}

/**
 * Warunki, w jakich pilot patrzy na ekran. Nie „skórki" — każdy wpis odpowiada innej
 * sytuacji w polu, a nie innemu gustowi.
 */
enum class Motyw(val etykieta: String, val opis: String) {
    CIEMNY("CIEMNY", "domyślny kokpit"),
    JASNY("JASNY", "jasne otoczenie"),
    DZIEN("DZIEŃ", "pełne słońce — mono, pełne krycie"),
    NOC("NOC", "zmrok — bursztyn, zbita jasność"),
    NVG("NVG", "gogle noktowizyjne — bez bieli i błękitu");

    val paleta: Paleta
        get() = when (this) {
            CIEMNY -> Palety.CIEMNY
            JASNY -> Palety.JASNY
            DZIEN -> Palety.DZIEN
            NOC -> Palety.NOC
            NVG -> Palety.NVG
        }
}

/**
 * Bieżące barwy. Stare nazwy (`Tekst`, `Akcent`, `Tafla`…) zostają jako role — zmieniła się
 * ich wartość, nie znaczenie.
 */
object Barwy {
    var paleta by mutableStateOf(Palety.CIEMNY)

    val ciemny: Boolean get() = paleta.ciemny

    val Tlo: Color get() = paleta.surf
    val Tafla: Color get() = paleta.surfC
    val TaflaMocna: Color get() = paleta.surfCHi
    val TaflaPelna: Color get() = paleta.surfCMax
    val Linia: Color get() = paleta.outline
    val Linia2: Color get() = paleta.outlineVar
    val Tekst: Color get() = paleta.onSurf
    val Drugi: Color get() = paleta.onSurfVar
    val Wygasly: Color get() = paleta.onSurfDim
    val Akcent: Color get() = paleta.prim
    val AkcentTlo: Color get() = paleta.primC
    val NaAkcencie: Color get() = paleta.onPrim
    val Dobrze: Color get() = paleta.ok
    val Uwaga: Color get() = paleta.uwaga
    val UwagaTlo: Color get() = paleta.tertC
    val Blokada: Color get() = paleta.err
    val BlokadaTlo: Color get() = paleta.errC
    /**
     * Kontur pod jasnymi kreskami przyrzadow rysowanych **na kadrze**.
     *
     * Wskaznik polozenia nie ma plyty i miec nie moze (UI.md par. 7) — zaslonilby obraz.
     * Wyrazistosc daje wiec podwojna kreska: najpierw ta ciemniejsza i grubsza, na niej
     * wlasciwa. Tak samo robi sie napisy na wideo i tak dziala kazdy HUD.
     */
    val Kontur: Color get() = paleta.surf.copy(alpha = 0.82f)

    val Stan: Color get() = paleta.state
    val StanMocny: Color get() = paleta.stateHi
    val Scrim: Color get() = paleta.scrim
    val ScrimDol: Color get() = paleta.scrimB

    val Instr: Color get() = paleta.instr
    val InstrLinia: Color get() = paleta.instrLinia
    val InstrTusz: Color get() = paleta.instrTusz
    val InstrTusz2: Color get() = paleta.instrTusz2
    val InstrTusz3: Color get() = paleta.instrTusz3
    val Niebo: Color get() = paleta.niebo
    val Ziemia: Color get() = paleta.ziemia

    val Klawisz: Color get() = paleta.state
    val KlawiszWcisniety: Color get() = paleta.stateHi

    fun przelacz() {
        paleta = if (ciemny) Palety.JASNY else Palety.CIEMNY
    }

    fun ustaw(motyw: Motyw) {
        paleta = motyw.paleta
    }
}

/**
 * Kształty makiety — **ścięcie dwóch przeciwległych naroży, nie czterech**.
 *
 * W makiecie każda płyta ma
 * `clip-path: polygon(SC 0, 100% 0, 100% calc(100% − SC), calc(100% − SC) 100%, 0 100%, 0 SC)`,
 * czyli ścięte **lewy górny i prawy dolny** róg. Pozostałe dwa zostają ostre. To razem
 * z krawędzią akcentu po lewej daje kierunek, po którym element się rozpoznaje —
 * ścięcie wszystkich czterech naroży ten kierunek gubi.
 */
object Ksztalty {
    fun plyta(sciecie: Dp) = CutCornerShape(topStart = sciecie, bottomEnd = sciecie)

    /** znaczniki w belce, drobne pola */
    val Male = plyta(6.dp)
    /** klawisze, chipy, akcje */
    val Klawisz = plyta(7.dp)
    /** dok akcji */
    val Dok = plyta(9.dp)
    /** karty narożnika, menu widoków */
    val Karta = plyta(14.dp)
    /** panel wyszukiwania */
    val Panel = plyta(16.dp)
    /** panele robocze, lista misji */
    val PanelDuzy = plyta(18.dp)
    /** szuflada kamery */
    val Szuflada = plyta(20.dp)
}

object Wymiary {
    // --- belka i menu (§3)
    /**
     * Poniżej tej szerokości belka przechodzi w postać zwięzłą. Aparatura MK32 daje 640 dp
     * (zmierzone 2026-08-25), a belka w pełnej postaci potrzebuje ok. 800 dp.
     */
    val BelkaProgZwiezly = 780.dp

    /**
     * Wysokość belki górnej — **56 dp**.
     *
     * Do 2026-08-26 było 32 dp, czyli **5,0 mm**. Wszystko, co w niej stało, było przez to
     * za małe z definicji: klawisz wyboru ekranu miał **22 dp = 3,5 mm**, czyli jedną
     * czwartą opuszka palca. Tom zgłosił, że dotknięcie „nie zawsze działa" — i nie chodziło
     * o obsługę zdarzeń, tylko o to, że w taki cel po prostu trudno trafić.
     *
     * Belka jest **nakładką na kadr**, nie zajmuje miejsca w układzie, więc jej powiększenie
     * kosztuje wyłącznie zasłonięcie 24 dp nieba u góry ekranu.
     *
     * ⚠ Klawisze belki mają 52 dp, a nie [CelDotyku] 64 dp. To świadome odstępstwo: pasek
     * stanu, który się głównie **czyta**, zabrałby przy 64 dp jedenaście procent wysokości
     * ekranu. 52 dp = 8,2 mm, czyli powyżej minimum Androida (48 dp).
     */
    val Belka = 56.dp

    /** Dokąd sięga nieprzezroczyste tło belki, zanim przejdzie w przezroczystość. */
    val BelkaKrycie = 48.dp

    /** Klawisze w belce — patrz uwaga przy [Belka]. */
    val BelkaKlawisz = 52.dp
    val MenuSzer = 180.dp
    val MenuPozycja = 44.dp

    // --- taśma kursu (§4). Szerokość zależy od widoku: 400 na LOT, 320 na KAMERZE.
    // Po naprawie gestosci kadr ma znowu ~960 dp, wiec wracamy do wymiaru z makiety M3.
    // Kokpit.kt i tak ogranicza tasme do wolnego kadru, wiec na wezszym ekranie sie zwezi.
    val TasmaKursuSzer = 400.dp
    val TasmaKursuSzerKamera = 320.dp
    val TasmaKursu = 20.dp
    val TasmaGora = 60.dp

    /** Środek taśmy: liczy się od środka **obrazu**, nie ramki — kolumna zabiera prawą stronę. */
    val TasmaSrodekPrawa = 456.dp
    val TasmaSrodekLewa = 504.dp
    val TasmaSrodekKamera = 420.dp

    // --- kolumna przyrządów (makieta: KOL = 190)
    val Kolumna = 190.dp
    val KolumnaMargines = 12.dp

    // --- przyrządy (§4)
    /**
     * Wskaźnik położenia — **główny przyrząd**. Urósł ze 132 dp, bo doszedł pierścień
     * kursu ze znacznikiem domu i strzałką wiatru (decyzja Toma 2026-08-28).
     */
    val Okrag = 152.dp

    /** Szerokość pierścienia kursu wokół tarczy horyzontu. */
    val PierscienKursu = 15.dp

    val OkragNadRzedem = 82.dp
    val OkragBezRzedu = 24.dp
    val PionSzer = 58.dp
    val PionWys = 300.dp
    val Migawka = 58.dp

    // --- komendy (§4, §7)
    /**
     * Klawisze komend lotu — **72 x 68 dp, czyli 11,3 x 10,7 mm** na tej aparaturze.
     *
     * Do 2026-08-26 było 44 x 40 dp, czyli **6,9 x 6,3 mm**: poniżej minimum Androida
     * (48 dp) i wyraźnie poniżej opuszka palca (10-14 mm, w rękawicy więcej). Tom zgłosił
     * to z ręki: „za małe, nie mieszczą się pod palcem".
     *
     * To akurat są klawisze, którymi sprowadza się maszynę — RTL, LĄDUJ, PRZERWIJ. Mają być
     * trafialne kciukiem, bez patrzenia i bez zdejmowania rękawicy. Kolumna jest wąska,
     * więc kadr traci 28 dp szerokości, a zyskuje pewność trafienia.
     */
    val KomendaSzer = 72.dp

    /** Kolumna kamery przy prawej krawedzi — poprawka makiety 1.1: 52 x 180 dp. */
    val KolumnaKamery = 52.dp

    /** Ile stopka pionu kamery zwisa pod skala: 42 dp odsuniecia + ~28 dp wlasnej wysokosci. */
    val PionStopkaZwis = 70.dp
    val KomendaWys = 68.dp

    /** Odstęp między klawiszami komend — na tyle duży, żeby nie trafić w sąsiedni. */
    val KomendaOdstep = 8.dp
    val KomendyGora = 68.dp

    // --- miniatura mapy (§4)
    val MiniaturaSzer = 190.dp
    val MiniaturaWys = 126.dp
    val Uchwyt = 20.dp

    // --- rząd liczb (§4)
    val RzadLiczb = 64.dp

    // --- przyrządy zapasu (dok/PROPOZYCJA_LOT.md §6, wariant 1)
    /** Pas zapasu ciągu: mieści słupek, dwie liczby i gaz zbiorczy. */
    val PasZapasuSzer = 300.dp

    /** Blok energii: mAh, prąd, JOKER, BINGO. */
    val BlokEnergiiSzer = 158.dp

    /**
     * Blok celu automatu. Wchodzi do pasa **tylko w trybach automatycznych**.
     *
     * Rozmiary trzech bloków są dobrane tak, żeby komplet zmieścił się w pasie między
     * miniaturą mapy a kolumną kamery: 300 + 180 + 140 + 2 × 8 = 636 dp przy 666 dp
     * dostępnych na ekranie 950 dp. Przy poprzednich 320/196/150 pas rozpychał się poza
     * kadr i wjeżdżał pod miniaturę — widać to na zrzutach z 2026-08-28.
     */
    val BlokCeluSzer = 140.dp

    /**
     * Okno pełnej pozycji. Dość szerokie, żeby każdy z trzech zapisów zmieścił się
     * w jednej linii — łamany MGRS przepisuje się przez radio gorzej niż mały — ale
     * **nie na tyle, żeby zasłonić kolumnę komend**: 470 dp wyśrodkowane na 950 dp
     * zostawia RTL i LĄDUJ odsłonięte. Najdłuższy wiersz to zapis DMS, ok. 390 dp.
     */
    val OknoPozycjiSzer = 470.dp

    /**
     * Logo producenta na ekranie uruchamiania.
     *
     * 356 dp przy gestosci ukladu 1,348 daje 480 px — dokladnie tyle, ile ma bitmapa
     * i ile rysuje tlo okna (res/drawable/tlo_startowe.xml). Dzieki temu w chwili,
     * gdy Compose przejmuje rysowanie od tla okna, logo **nie zmienia rozmiaru**.
     */
    val LogoSzer = 356.dp

    /**
     * Wysokość obu przyrządów zapasu. **Stała, jednakowa dla obu i niezależna od stanu** —
     * blok, który zmienia wysokość, gdy dane przychodzą albo znikają, przesuwa sąsiadów
     * w locie i pilot traci pamięć miejsca.
     */
    val PasPrzyrzadow = 66.dp
    val OdczytSzer = 96.dp

    // --- nakładki
    val WarstwySzer = 252.dp

    // --- MISJA (§5)
    val PanelListy = 288.dp
    val PanelSzukania = 336.dp
    val SzukanieGora = 62.dp
    val ZnacznikPunktu = 24.dp
    val KlawiszPunktu = 28.dp

    // --- KAMERA (§6)
    /** Równe [CelDotyku]; wpisane liczbą, bo obiekt nie zna jeszcze tamtej wartości. */
    val ZakladkaKamery = 64.dp
    val ZakladkiGora = 38.dp
    val SzufladaLewa = 12.dp
    val SzufladaPrawa = 82.dp
    val SzufladaGora = 94.dp
    val SzufladaMaks = 322.dp
    val PasOrientacji = 52.dp

    // --- panele robocze (stylPanel z makiety)
    val PanelGora = 64.dp
    val PanelBok = 68.dp
    val PanelDol = 12.dp

    // --- wspólne
    val Odstep = 8.dp

    /**
     * **Najmniejszy cel dotykowy w aplikacji — 64 dp, czyli 10,0 mm.**
     *
     * Token był tu zadeklarowany od początku i **nie używało go nic**: chipy mapy miały
     * 28-34 dp (4,4-5,3 mm), czyli mniej niż połowę opuszka palca, a klawisze komend
     * 44 x 40 dp. Tom zgłosił to z ręki 2026-08-26. Od tej pory [Chip] podnosi każdą
     * podaną wysokość do tej wartości, więc żadne wywołanie nie może zejść niżej.
     *
     * Android wymaga 48 dp. Bierzemy zapas, bo tej aparatury używa się na dworze,
     * w rękawicy i trzymając ją obiema rękami.
     */
    val CelDotyku = 64.dp

    /** Klawisze `+`/`−` mapy — trafia się w nie wielokrotnie pod rząd. */
    val CelDotykuSzer = 64.dp
}

object Kroje {
    val Ogromna = 26.sp      // jedna wartość na ekran — wysokość
    val Duza = 20.sp
    val Wspolrzedne = 15.sp
    val Srednia = 16.sp
    val PasStanu = 13.sp
    val Baner = 18.sp
    val Tekst = 13.sp
    val Etykieta = 9.sp

    /**
     * Cyfry o stałej szerokości. Bez `tnum` wartość „11.1 → 9.8" przeskakuje w poziomie
     * przy każdej zmianie.
     */
    fun liczba(rozmiar: TextUnit, waga: FontWeight = FontWeight.SemiBold, kolor: Color = Barwy.Tekst) =
        TextStyle(
            color = kolor,
            fontSize = rozmiar,
            fontWeight = waga,
            fontFeatureSettings = "tnum",
        )

    /**
     * Napis „zgęszczony" — w makiecie Barlow Condensed. Aparatura nie ma tego kroju,
     * więc zastępujemy go **rozstrzeleniem ujemnym i wagą**: ten sam efekt gęstego,
     * technicznego napisu bez dokładania pliku czcionki do APK.
     */
    fun zgeszczona(rozmiar: TextUnit, kolor: Color = Barwy.Tekst) = TextStyle(
        color = kolor,
        fontSize = rozmiar,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = "tnum",
    )

    val Podpis: TextStyle
        get() = TextStyle(
            color = Barwy.Drugi,
            fontSize = Etykieta,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.1.sp,
        )
}

/**
 * Warstwy ekranu — nakładka z §4 przekazania. Ustawienie **przeżywa restart**.
 * Nazwy i opisy 1:1 z `warstwyDef` w makiecie.
 */
data class WarstwyEkranu(
    /**
     * ⚠ Domyślnie **zdjęta od 2026-08-28**: kurs, dom i wiatr przeniosły się na okrąg
     * położenia, więc taśma powtarzałaby to samo. Zostaje do włączenia w warstwach,
     * bo daje drobniejszą podziałkę w wąskim oknie ±30°.
     */
    val tasmaKursu: Boolean = false,
    val miniaturaMapy: Boolean = true,
    val okragPolozenia: Boolean = true,
    val rzadLiczb: Boolean = true,
    val dokAkcji: Boolean = true,
    /** Pas zapasu ciągu i rozrzutu silników — dok/PROPOZYCJA_LOT.md, etap 1. */
    val pasZapasu: Boolean = true,
    /** Blok energii z JOKER i BINGO — etap 2. */
    val blokEnergii: Boolean = true,
    /** Cel automatu i zapas geofence — widoczne tylko w trybach automatycznych. */
    val blokCelu: Boolean = true,
    /**
     * Znacznik władzy na belce. **Domyślnie zdjęty od 2026-08-28** — Tom uznał go
     * za zbędny na tej maszynie, gdzie steruje jeden operator. Zostaje w warstwach,
     * bo przy dwóch stacjach naziemnych (dok/WLADZA.md) informacja wraca na wagę.
     */
    val znacznikWladzy: Boolean = false,

    /** Sygnalizacja dźwiękowa alarmów — etap 3. */
    val dzwiek: Boolean = true,
    /** Warunki oświetlenia, w jakich pilot patrzy na ekran — patrz [Motyw]. */
    val motyw: Motyw = Motyw.CIEMNY,
)
