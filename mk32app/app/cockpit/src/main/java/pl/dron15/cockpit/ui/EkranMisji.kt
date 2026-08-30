package pl.dron15.cockpit.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.MagazynMisji
import pl.dron15.cockpit.domain.Misja
import pl.dron15.cockpit.domain.PunktMisji
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.TrybMisji
import pl.dron15.cockpit.domain.Wspolrzedne

/**
 * Ekran MISJA — **planowanie na mapie**, makieta `Kokpit M3.dc.html`, §5 przekazania.
 *
 * Zmiana wobec poprzedniej wersji jest zasadnicza: było oglądanie listy punktów, jest
 * nanoszenie trasy palcem po mapie. Przyrządy i rząd liczb z LOT-u **nie renderują się**
 * tutaj — na tym ekranie się nie leci, tylko planuje.
 *
 * Mapa wypełnia kadr **do panelu listy (288 dp z prawej)**, panel wyszukiwania leży na niej
 * w lewym górnym rogu.
 */
@Composable
fun EkranMisji(
    stan: StanMaszyny,
    misja: Misja,
    tryb: TrybMisji,
    wybrany: Int,
    opisStanu: String,
    naMisje: (Misja) -> Unit,
    naTryb: (TrybMisji) -> Unit,
    naWybor: (Int) -> Unit,
    naWyslanie: () -> Unit,
    naPobranieZMaszyny: () -> Unit,
    naZapis: () -> Unit,
    naPauze: (Boolean) -> Unit,
    naSkok: (Int) -> Unit,
    naPrzerwanie: () -> Unit,
    mapa: UstawieniaMapy = UstawieniaMapy(),
    naMape: (UstawieniaMapy) -> Unit = {},
) {
    var srodekLat by remember { mutableStateOf(0.0) }
    var srodekLon by remember { mutableStateOf(0.0) }
    var zasieg by remember { mutableStateOf(400f) }
    var wysokoscNowych by remember { mutableStateOf(Misja.WYS_DOMYSLNA) }

    // Pierwsze wejście ustawia środek na dom albo na maszynę — bez tego mapa stoi na zerze.
    LaunchedEffect(stan.domUstalony, stan.pozycjaZnana) {
        if (srodekLat == 0.0 && srodekLon == 0.0) {
            when {
                stan.domUstalony -> { srodekLat = stan.domSzerokosc; srodekLon = stan.domDlugosc }
                stan.pozycjaZnana -> { srodekLat = stan.szerokosc; srodekLon = stan.dlugosc }
            }
        }
    }

    val edycja = tryb != TrybMisji.LEC
    val geofence = stan.parametry["FENCE_RADIUS"] ?: 0f

    fun dolozPunkt(poz: Wspolrzedne.Pozycja) {
        naMisje(misja.zDolozonym(
            PunktMisji(PunktMisji.NAV_WAYPOINT, poz.szerokosc, poz.dlugosc, wysokoscNowych)))
    }

    Box(Modifier.fillMaxSize().background(Barwy.Tlo)) {

        Row(Modifier.fillMaxSize().padding(top = Wymiary.Belka)) {

            Column(Modifier.weight(1f).fillMaxHeight()) {

                Box(Modifier.weight(1f).fillMaxWidth()) {

                    if (mapa.widok3d) {
                        Widok3D(
                            stan = stan,
                            misja = misja,
                            wybrany = wybrany,
                            srodekLat = srodekLat,
                            srodekLon = srodekLon,
                            zasiegM = zasieg,
                            ustawienia = mapa,
                            modifier = Modifier.fillMaxSize(),
                            naZasieg = { zasieg = it },
                        )
                    } else {
                        MapaMisji(
                            stan = stan,
                            misja = misja,
                            wybrany = wybrany,
                            srodekLat = srodekLat,
                            srodekLon = srodekLon,
                            zasiegM = zasieg,
                            geofenceM = geofence,
                            edycjaMozliwa = edycja,
                            naDodanie = { poz -> dolozPunkt(poz) },
                            naWybor = naWybor,
                            naPrzesuniecie = { dE, dN ->
                                srodekLat += dN / Wspolrzedne.METRY_NA_STOPIEN
                                srodekLon += dE / (Wspolrzedne.METRY_NA_STOPIEN *
                                        kotlin.math.cos(Math.toRadians(srodekLat)))
                            },
                            modifier = Modifier.fillMaxSize(),
                            ustawienia = mapa,
                            naZasieg = { zasieg = it },
                        )
                    }

                    PasekPodkladu(
                        mapa = mapa,
                        naMape = naMape,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    )

                    Text(
                        when {
                            mapa.widok3d -> "WIDOK PRZESTRZENNY — PUNKTY DOKŁADA SIĘ NA MAPIE PŁASKIEJ"
                            edycja -> "DOTKNIJ MAPĘ, ŻEBY DOŁOŻYĆ PUNKT"
                            else -> "AUTO W TOKU — EDYCJA TRASY ZABLOKOWANA"
                        },
                        color = Barwy.Wygasly, fontSize = 9.sp, letterSpacing = 0.9.sp,
                        // Nad rzedem chipow zasiegu, nie pod nim — inaczej „150 m" wchodzi na podpis.
                        modifier = Modifier.align(Alignment.BottomStart)
                            .padding(start = 8.dp, bottom = 46.dp),
                    )

                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        ZasiegPoziomo(zasieg) { zasieg = it }
                        Spacer(Modifier.width(8.dp))
                        WyborWysokosci(wysokoscNowych) { wysokoscNowych = it }
                        if (stan.domUstalony) {
                            Spacer(Modifier.width(8.dp))
                            Chip("NA DOM", false, Modifier.width(88.dp), rozmiar = 12.sp) {
                                srodekLat = stan.domSzerokosc; srodekLon = stan.domDlugosc
                            }
                        }
                    }
                }

                if (mapa.profil) {
                    PasProfilu(
                        stan = stan,
                        misja = misja,
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                    )
                }
            }

            PanelListy(
                misja = misja,
                tryb = tryb,
                wybrany = wybrany,
                opisStanu = opisStanu,
                stan = stan,
                naTryb = naTryb,
                naWybor = naWybor,
                naMisje = naMisje,
                naWyslanie = naWyslanie,
                naPobranieZMaszyny = naPobranieZMaszyny,
                naZapis = naZapis,
                naPauze = naPauze,
                naSkok = naSkok,
                naPrzerwanie = naPrzerwanie,
            )
        }

        PanelSzukania(
            stan = stan,
            naSkok = { poz -> srodekLat = poz.szerokosc; srodekLon = poz.dlugosc },
            naDodanie = { poz -> dolozPunkt(poz) },
            dodawanieMozliwe = edycja,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = Krawedzie.Ramka, top = Wymiary.SzukanieGora),
        )
    }
}

/**
 * Wybór podkładu i widoku **na samej mapie** — bez wchodzenia w panel warstw.
 *
 * Podkład zmienia się w trakcie planowania kilka razy: zdjęcie mówi, co jest na ziemi,
 * topo — jak ziemia się układa, a widok przestrzenny rozstrzyga, czy trasa przejdzie nad
 * grzbietem. Trzy dotknięcia zamiast wędrówki przez menu.
 *
 * Podkład, którego nie ma na karcie, jest wyszarzony i nieklikalny — ta sama zasada,
 * co w pasku zakładek: widać od razu, czego brakuje.
 */
@Composable
private fun PasekPodkladu(
    mapa: UstawieniaMapy,
    naMape: (UstawieniaMapy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kontekst = androidx.compose.ui.platform.LocalContext.current
    val magazyn = remember(kontekst) { MagazynKafelkow.dla(kontekst) }

    Column(modifier, horizontalAlignment = Alignment.End) {
        // Dwa rzedy po trzy, nie jeden po piec. Po powiekszeniu chipow do rozmiaru palca
        // (64 dp) rzad pieciu mial 396 dp i siegal **pod panel wyszukiwania**, ucinajac
        // napis „HYBRYDA" do „RYDA". Rzad trzech ma 244 dp i mija panel z zapasem.
        Podklady.wszystkie.chunked(3).forEach { rzad ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rzad.forEach { p ->
                    Chip(
                        etykieta = p.nazwa,
                        wybrany = mapa.podklad == p.id,
                        modifier = Modifier.width(76.dp),
                        rozmiar = 11.sp,
                        dostepny = magazyn.maPodklad(p),
                    ) { naMape(mapa.copy(podklad = p.id)) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        // Wąskie rzędy wyrównane do prawej mijają panel wyszukiwania, który zajmuje
        // 336 dp od lewej krawędzi mapy. Szeroki rząd chowałby pod nim pierwsze chipy.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("2D", !mapa.widok3d, Modifier.width(Wymiary.CelDotykuSzer), rozmiar = 11.sp) {
                naMape(mapa.copy(widok3d = false))
            }
            Chip("3D", mapa.widok3d, Modifier.width(Wymiary.CelDotykuSzer), rozmiar = 11.sp) {
                naMape(mapa.copy(widok3d = true))
            }
            Chip("CIEŃ", mapa.cieniowanie, Modifier.width(68.dp), rozmiar = 11.sp) {
                naMape(mapa.copy(cieniowanie = !mapa.cieniowanie))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("WARSTWICE", mapa.warstwice, Modifier.width(96.dp), rozmiar = 11.sp) {
                naMape(mapa.copy(warstwice = !mapa.warstwice))
            }
            Chip("AZYMUT", mapa.azymut, Modifier.width(78.dp), rozmiar = 11.sp) {
                naMape(mapa.copy(azymut = !mapa.azymut))
            }
            Chip("PROFIL", mapa.profil, Modifier.width(76.dp), rozmiar = 11.sp) {
                naMape(mapa.copy(profil = !mapa.profil))
            }
        }
    }
}

// --------------------------------------------------------------------------- panel listy

@Composable
private fun PanelListy(
    misja: Misja,
    tryb: TrybMisji,
    wybrany: Int,
    opisStanu: String,
    stan: StanMaszyny,
    naTryb: (TrybMisji) -> Unit,
    naWybor: (Int) -> Unit,
    naMisje: (Misja) -> Unit,
    naWyslanie: () -> Unit,
    naPobranieZMaszyny: () -> Unit,
    naZapis: () -> Unit,
    naPauze: (Boolean) -> Unit,
    naSkok: (Int) -> Unit,
    naPrzerwanie: () -> Unit,
) {
    var wstrzymana by remember { mutableStateOf(false) }
    val kolorStanu = if (tryb == TrybMisji.LEC) Barwy.Dobrze else Barwy.Uwaga

    Column(
        Modifier
            .width(Wymiary.PanelListy)
            .fillMaxHeight()
            .background(Barwy.TaflaPelna)
            .drawBehind { drawRect(Barwy.Akcent, size = Size(2.dp.toPx(), size.height)) }
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(opisStanu.uppercase(), color = kolorStanu, fontSize = 11.sp,
                letterSpacing = 0.9.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(misja.podsumowanie, style = Kroje.liczba(11.sp, FontWeight.Medium, Barwy.Drugi))
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TrybMisji.entries.forEach { t ->
                Chip(t.etykieta, t == tryb, Modifier.weight(1f), rozmiar = 13.sp) { naTryb(t) }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Czynności zależą od trybu — §5 przekazania.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (tryb) {
                TrybMisji.PLANUJ -> {
                    PrzyciskAkcji("WYCZYŚĆ", { naMisje(Misja()) }, Modifier.weight(1f))
                    PrzyciskAkcji("ZAPISZ", naZapis, Modifier.weight(1f), podpis = ".plan")
                    PrzyciskPrzytrzymaj(
                        "WYŚLIJ", naWyslanie, Modifier.weight(1f),
                        dostepny = !misja.pusta && stan.domUstalony,
                        powod = if (misja.pusta) "pusta" else "brak domu",
                    )
                }

                TrybMisji.LEC -> {
                    PrzyciskAkcji(
                        if (wstrzymana) "WZNÓW" else "PAUZA",
                        { wstrzymana = !wstrzymana; naPauze(wstrzymana) },
                        Modifier.weight(1f),
                    )
                    PrzyciskPrzytrzymaj(
                        "SKOK", { if (wybrany >= 0) naSkok(wybrany + 1) }, Modifier.weight(1f),
                        dostepny = wybrany >= 0, powod = "wybierz pkt",
                    )
                    PrzyciskPrzytrzymaj(
                        "PRZERWIJ", naPrzerwanie, Modifier.weight(1f), rodzaj = Rodzaj.BLOKADA,
                    )
                }

                TrybMisji.EDYTUJ -> {
                    PrzyciskAkcji("POBIERZ", naPobranieZMaszyny, Modifier.weight(1f),
                        podpis = "z maszyny")
                    PrzyciskAkcji("ZAPISZ", naZapis, Modifier.weight(1f), podpis = ".plan")
                    PrzyciskPrzytrzymaj(
                        "WYŚLIJ", naWyslanie, Modifier.weight(1f),
                        dostepny = !misja.pusta && stan.domUstalony,
                        powod = if (misja.pusta) "pusta" else "brak domu",
                    )
                }
            }
        }

        Text(
            if (tryb == TrybMisji.LEC)
                "Skok do punktu i przerwanie automatu — na przytrzymanie 1,2 s."
            else if (!stan.kursGnssDostepny)
                "Wysyłka zablokowana do potwierdzenia kursu GNSS — AUTO wymaga pozycji z bazy."
            else "Wysyłka i zmiany trasy — na przytrzymanie 1,2 s.",
            color = if (tryb == TrybMisji.LEC) Barwy.Wygasly else Barwy.Uwaga,
            fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(8.dp))

        if (tryb == TrybMisji.LEC && !misja.pusta) {
            PostepMisji(stan, misja)
            Spacer(Modifier.height(8.dp))
        }

        if (misja.pusta) {
            Text(
                when (tryb) {
                    TrybMisji.PLANUJ -> "Dotknij mapy, żeby dołożyć punkt."
                    TrybMisji.LEC -> "Maszyna nie ma wgranej misji albo jeszcze jej nie pobrano."
                    TrybMisji.EDYTUJ -> "Pobierz misję z maszyny albo wczytaj plik z karty."
                },
                color = Barwy.Drugi, fontSize = 12.sp,
            )
            if (tryb == TrybMisji.EDYTUJ) ListaPlikow(naMisje)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(misja.punkty) { i, p ->
                    WierszPunktu(
                        numer = misja.naMapie.indexOf(p) + 1,
                        punkt = p,
                        wybrany = i == wybrany,
                        biezacy = tryb == TrybMisji.LEC && stan.punktMisji == i + 1,
                        edycja = tryb != TrybMisji.LEC,
                        naWybor = { naWybor(i) },
                        naWyzej = { naMisje(misja.zeZmienionaWysokoscia(i, +5f)) },
                        naNizej = { naMisje(misja.zeZmienionaWysokoscia(i, -5f)) },
                        naUsun = { naMisje(misja.bez(i)) },
                    )
                }
            }
        }
    }
}

/** Postęp wykonywanej misji — trzy liczby z makiety: punkt, dystans, szacowany czas. */
@Composable
private fun PostepMisji(stan: StanMaszyny, misja: Misja) {
    val ile = misja.naMapie.size
    val biezacy = stan.punktMisji.coerceIn(0, ile)
    val doPunktu = stan.dystansDoDomuM      // brak lepszego źródła bez pełnej nawigacji
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        PoleMisji("$biezacy", "/$ile", "PUNKT")
        PoleMisji(if (doPunktu >= 0f) "%.0f".format(doPunktu) else "—", "m", "DO PUNKTU")
        PoleMisji(
            if (stan.predkoscMs > 0.5f && doPunktu > 0f)
                czasMmSs((doPunktu / stan.predkoscMs).toLong()) else "—:—",
            null, "SZACOWANY CZAS",
        )
    }
}

@Composable
private fun PoleMisji(wartosc: String, jednostka: String?, etykieta: String) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(wartosc, style = Kroje.liczba(20.sp, FontWeight.SemiBold))
            if (jednostka != null) {
                Text(jednostka, color = Barwy.Drugi, fontSize = 11.sp)
            }
        }
        Etykieta(etykieta)
    }
}

/**
 * Wiersz punktu — **dwie linie**: numer, typ i wysokość, pod nimi **pełne współrzędne**
 * i klawisze.
 *
 * Współrzędnych nie wolno skracać (§5 przekazania): w makiecie kolumna `1fr` w jednej linii
 * zwijała się do 89 dp i ucinała odczyt, a to jest ta liczba, którą przepisuje się przez radio.
 */
@Composable
private fun WierszPunktu(
    numer: Int,
    punkt: PunktMisji,
    wybrany: Boolean,
    biezacy: Boolean,
    edycja: Boolean,
    naWybor: () -> Unit,
    naWyzej: () -> Unit,
    naNizej: () -> Unit,
    naUsun: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (biezacy || wybrany) Barwy.AkcentTlo else Color.Transparent)
            .drawBehind {
                drawRect(Barwy.Linia2, topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(size.width, 1.dp.toPx()))
                if (biezacy) drawRect(Barwy.Akcent, size = Size(2.dp.toPx(), size.height))
            }
            .pointerInput(numer, wybrany) { detectTapGestures(onTap = { naWybor() }) }
            .padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (punkt.naMapie) "%2d".format(numer) else " —",
                style = Kroje.liczba(13.sp, FontWeight.SemiBold,
                    if (biezacy || wybrany) Barwy.Akcent else Barwy.Wygasly),
            )
            Spacer(Modifier.width(7.dp))
            Text(punkt.nazwa, style = Kroje.liczba(13.sp, FontWeight.Medium), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(if (punkt.naMapie) "%.0f m".format(punkt.wysokoscM) else "—",
                style = Kroje.liczba(13.sp, FontWeight.SemiBold))
        }

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (punkt.naMapie) Wspolrzedne.dziesietne(punkt.szerokosc, punkt.dlugosc)
                else "bez własnego położenia",
                color = Barwy.Drugi, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (edycja) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (punkt.naMapie) {
                        MalyKlawisz("−", akcja = naNizej)
                        MalyKlawisz("+", akcja = naWyzej)
                    }
                    MalyKlawisz("✕", Barwy.Blokada, naUsun)
                }
            }
        }
    }
}

@Composable
private fun MalyKlawisz(znak: String, kolor: Color = Barwy.Drugi, akcja: () -> Unit) {
    Box(
        Modifier
            .size(Wymiary.KlawiszPunktu)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(Barwy.Linia2, size = Size(size.width, w))
                drawRect(Barwy.Linia2, topLeft = Offset(0f, size.height - w),
                    size = Size(size.width, w))
                drawRect(Barwy.Linia2, size = Size(w, size.height))
                drawRect(Barwy.Linia2, topLeft = Offset(size.width - w, 0f),
                    size = Size(w, size.height))
            }
            .pointerInput(znak) { detectTapGestures(onTap = { akcja() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(znak, color = kolor, fontSize = 13.sp)
    }
}

@Composable
private fun WyborWysokosci(wysokosc: Float, naZmiane: (Float) -> Unit) {
    Row(
        Modifier.plyta(7.dp, Barwy.TaflaPelna, Barwy.Linia)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Etykieta("wys. nowych")
        Spacer(Modifier.width(6.dp))
        MalyKlawisz("−") { naZmiane((wysokosc - 5f).coerceAtLeast(Misja.WYS_MIN)) }
        Text("  %.0f m  ".format(wysokosc), style = Kroje.liczba(13.sp, FontWeight.SemiBold))
        MalyKlawisz("+") { naZmiane((wysokosc + 5f).coerceAtMost(Misja.WYS_MAKS)) }
    }
}

@Composable
private fun ListaPlikow(naMisje: (Misja) -> Unit) {
    val pliki = remember { MagazynMisji.lista() }
    Spacer(Modifier.height(8.dp))
    Etykieta("pliki na karcie")
    if (pliki.isEmpty()) {
        Text("brak plików w ${MagazynMisji.katalog.path}", color = Barwy.Wygasly, fontSize = 11.sp)
        return
    }
    LazyColumn {
        itemsIndexed(pliki) { _, plik ->
            PrzyciskAkcji(plik.name, { MagazynMisji.wczytaj(plik)?.let(naMisje) },
                Modifier.fillMaxWidth().padding(vertical = 2.dp))
        }
    }
}

// --------------------------------------------------------------------------- wyszukiwanie

private enum class TrybSzukania(val etykieta: String, val podpowiedz: String) {
    WSPOLRZEDNE("WSPÓŁRZĘDNE", "52.23412 N 21.00871 E  ·  albo 34U EC 12345 67890"),
    ADRES("ADRES", "Miejscowość, ulica, numer"),
    POI("POI", "wieża, most, wysypisko, hałda…"),
}

/**
 * Wyszukiwanie — panel 336 dp w lewym górnym rogu mapy (§5 przekazania).
 *
 * **Współrzędne działają bez żadnych danych** i dlatego weszły pierwsze — parsowane
 * lokalnie w trzech zapisach. Adres i POI wymagają danych offline na karcie, bo aparatura
 * nie ma sieci; do czasu decyzji o ich źródle obie zakładki **mówią o tym wprost**,
 * zamiast udawać, że szukają.
 *
 * ### Zwinięty domyślnie
 *
 * Rozwinięty panel przykrywa **336 x 200 dp mapy**, czyli jej lewy górny narożnik — a tam
 * leży teren, na którym planuje się trasę, i tam nie da się postawić punktu. Szukanie jest
 * czynnością okazjonalną, oglądanie mapy ciągłą, więc domyślnie stoi zwinięty do jednego
 * klawisza. Rozwinięcie kosztuje jedno dotknięcie i przeżywa tyle, ile trzeba.
 */
@Composable
private fun PanelSzukania(
    stan: StanMaszyny,
    naSkok: (Wspolrzedne.Pozycja) -> Unit,
    naDodanie: (Wspolrzedne.Pozycja) -> Unit,
    dodawanieMozliwe: Boolean,
    modifier: Modifier = Modifier,
) {
    var tryb by remember { mutableStateOf(TrybSzukania.WSPOLRZEDNE) }
    var wpis by remember { mutableStateOf("") }
    var rozwiniety by remember { mutableStateOf(false) }

    val znalezione = remember(wpis, tryb) {
        if (tryb == TrybSzukania.WSPOLRZEDNE) Wspolrzedne.parsuj(wpis) else null
    }

    if (!rozwiniety) {
        Chip("SZUKAJ  ▾", false, modifier.width(120.dp), rozmiar = 12.sp) { rozwiniety = true }
        return
    }

    Column(
        modifier
            .width(Wymiary.PanelSzukania)
            .plyta(16.dp, Barwy.TaflaPelna, Barwy.Akcent)
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SZUKAJ", style = Kroje.zgeszczona(12.sp, Barwy.Drugi),
                modifier = Modifier.weight(1f))
            Chip("ZWIŃ  ▴", false, Modifier.width(96.dp), rozmiar = 11.sp) { rozwiniety = false }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TrybSzukania.entries.forEach { t ->
                Chip(t.etykieta, t == tryb, Modifier.weight(1f), rozmiar = 12.sp) { tryb = t }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = wpis,
                onValueChange = { wpis = it },
                singleLine = true,
                textStyle = Kroje.liczba(13.sp, FontWeight.Medium, Barwy.Tekst),
                cursorBrush = SolidColor(Barwy.Akcent),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .background(Barwy.Tafla)
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawRect(Barwy.Linia, size = Size(size.width, w))
                        drawRect(Barwy.Linia, topLeft = Offset(0f, size.height - w),
                            size = Size(size.width, w))
                        drawRect(Barwy.Linia, size = Size(w, size.height))
                        drawRect(Barwy.Linia, topLeft = Offset(size.width - w, 0f),
                            size = Size(w, size.height))
                    }
                    .padding(horizontal = 9.dp, vertical = 9.dp),
            )
            PrzyciskAkcji("SZUKAJ", { }, rodzaj = Rodzaj.AKCENT,
                dostepny = tryb == TrybSzukania.WSPOLRZEDNE && znalezione != null,
                powod = "—")
        }

        Spacer(Modifier.height(4.dp))
        Text(tryb.podpowiedz, color = Barwy.Wygasly, fontSize = 9.sp, maxLines = 2)
        Spacer(Modifier.height(8.dp))

        when {
            tryb != TrybSzukania.WSPOLRZEDNE -> BrakDanychOffline(tryb)

            wpis.isBlank() -> Text("Wpisz współrzędne w dowolnym z trzech zapisów.",
                color = Barwy.Drugi, fontSize = 11.sp)

            znalezione == null -> Text("Nie rozumiem tego zapisu.",
                style = Kroje.zgeszczona(12.sp, Barwy.Uwaga))

            else -> Wynik(znalezione, stan, dodawanieMozliwe,
                naSkok = { naSkok(znalezione) }, naDodanie = { naDodanie(znalezione) })
        }
    }
}

@Composable
private fun Wynik(
    poz: Wspolrzedne.Pozycja,
    stan: StanMaszyny,
    dodawanieMozliwe: Boolean,
    naSkok: () -> Unit,
    naDodanie: () -> Unit,
) {
    val odleglosc = if (stan.domUstalony) {
        Wspolrzedne.odleglosc(Wspolrzedne.Pozycja(stan.domSzerokosc, stan.domDlugosc), poz)
    } else null

    Column {
        listOf(
            Wspolrzedne.dziesietne(poz.szerokosc, poz.dlugosc) to "wpisane wprost",
            Wspolrzedne.mgrs(poz.szerokosc, poz.dlugosc) to "MGRS · to samo miejsce",
            Wspolrzedne.dms(poz.szerokosc, poz.dlugosc) to "stopnie, minuty, sekundy",
        ).forEach { (nazwa, opis) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Barwy.Tafla)
                    .drawBehind { drawRect(Barwy.Linia2, size = Size(2.dp.toPx(), size.height)) }
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(nazwa, style = Kroje.liczba(13.sp, FontWeight.Medium), maxLines = 1)
                    Text(opis, color = Barwy.Wygasly, fontSize = 9.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(3.dp))
        }

        if (odleglosc != null) {
            Text("od domu: ${Wspolrzedne.opisOdleglosci(odleglosc)}",
                style = Kroje.liczba(11.sp, FontWeight.Medium, Barwy.Dobrze))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrzyciskAkcji("POKAŻ", naSkok, Modifier.weight(1f))
            PrzyciskAkcji("DODAJ", naDodanie, Modifier.weight(1f), rodzaj = Rodzaj.AKCENT,
                dostepny = dodawanieMozliwe, powod = "tryb LEĆ")
        }
    }
}

/**
 * Adres i POI wymagają danych offline. **Zakładka mówi, czego brakuje i skąd to wziąć**,
 * zamiast pokazywać puste wyniki — pusta lista wygląda jak „nic nie znaleziono", a to nie
 * to samo co „nie ma czym szukać".
 */
@Composable
private fun BrakDanychOffline(tryb: TrybSzukania) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", color = Barwy.Uwaga, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text("BRAK DANYCH NA KARCIE", style = Kroje.zgeszczona(12.sp, Barwy.Uwaga))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Aparatura nie ma sieci, więc " +
                    (if (tryb == TrybSzukania.ADRES) "adresy" else "obiekty terenowe") +
                    " muszą pochodzić z pliku na karcie. Do rozstrzygnięcia przed wdrożeniem: " +
                    "lokalny indeks z OSM dla rejonu albo import punktów przygotowany na stacji.",
            color = Barwy.Drugi, fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text("Wyszukiwanie po współrzędnych działa bez żadnych danych.",
            color = Barwy.Wygasly, fontSize = 10.sp)
    }
}
