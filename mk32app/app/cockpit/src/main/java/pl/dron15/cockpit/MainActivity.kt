package pl.dron15.cockpit

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.sample
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.dron15.cockpit.diag.Dziennik
import pl.dron15.cockpit.domain.Checklista
import pl.dron15.cockpit.domain.MagazynMisji
import pl.dron15.cockpit.domain.Misja
import pl.dron15.cockpit.domain.TrybMisji
import pl.dron15.cockpit.domain.Ciag
import pl.dron15.cockpit.domain.Ogrodzenie
import pl.dron15.cockpit.domain.Rc
import pl.dron15.cockpit.domain.SilnikStanu
import pl.dron15.cockpit.domain.Tryby
import pl.dron15.cockpit.net.mavlink.LaczeMavlink
import pl.dron15.cockpit.net.siyi.KlientSiyi
import pl.dron15.cockpit.ui.AkcjeKamery
import pl.dron15.cockpit.ui.AkcjeLotu
import pl.dron15.cockpit.ui.AkcjeMisji
import pl.dron15.cockpit.ui.Aplikacja
import pl.dron15.cockpit.ui.Barwy
import pl.dron15.cockpit.ui.ZEkranemStartowym
import pl.dron15.cockpit.ui.Motyw
import pl.dron15.cockpit.ui.Podklady
import pl.dron15.cockpit.ui.UstawieniaMapy
import pl.dron15.cockpit.ui.WarstwyEkranu
import pl.dron15.cockpit.video.TorZZapasem
import pl.dron15.cockpit.video.OdtwarzaczVlc
import pl.dron15.cockpit.video.TorWideo

/**
 * Kokpit DRON 15 — aplikacja samodzielna.
 *
 * Trzy niezależne łącza, każde z własnym wątkiem i własnym watchdogiem:
 *   telemetria  UDP 192.168.144.12:19856   (jednostka naziemna MK32)
 *   obraz       RTSP 192.168.144.25:8554   (ZR30)
 *   głowica     UDP 192.168.144.25:37260   (SIYI SDK, z pominięciem kontrolera lotu)
 *
 * Awaria jednego nie może zabrać pozostałych — to wymóg z dok/ARCHITEKTURA.md, nie życzenie.
 *
 * Adresy da się nadpisać przy uruchomieniu, co służy testom na biurku:
 *   adb shell am start -n pl.dron15.cockpit/.MainActivity -e host 10.0.2.2
 */
private const val PRZEKATNA_CALE = 7.0f      // SIYI MK32 — panel 7 cali

/** Jak często stan maszyny trafia na ekran. 66 ms = 15 razy na sekundę. */
private const val ODSTEP_EKRANU_MS = 66L

class MainActivity : ComponentActivity() {

    private val silnik = SilnikStanu()
    private lateinit var lacze: LaczeMavlink
    private lateinit var glowica: KlientSiyi
    /**
     * Odtwarzacz obrazu. **Stan Compose**, bo powstaje poza wątkiem głównym i pojawia się
     * dopiero po chwili — interfejs musi się o tym dowiedzieć.
     *
     * Powód: konstruktor `LibVLC` ładuje biblioteki natywne i na MK32 **zajmował 2,4 s
     * wątku głównego** (`Choreographer: Skipped 143 frames`, zmierzone 2026-08-26).
     * Przez ten czas kokpit nie odpowiadał na dotknięcia. Obraz i tak nie jest potrzebny
     * w pierwszej sekundzie po starcie, a telemetria i przyrządy są.
     */
    private var odtwarzacz: TorWideo? by mutableStateOf(null)

    /** Który tor obrazu jest czynny w tym uruchomieniu. */
    private var torSiyi = false
    private var torNatywny = false
    private var checklista: Checklista? = null
    private lateinit var ustawienia: android.content.SharedPreferences

    /**
     * Gęstość, w jakiej rysujemy interfejs — **własna, nie systemowa**.
     *
     * MK32 melduje `density 320` (`dumpsys display`: 317,5 × 318,7 dpi), a panel 7" przy
     * 1280 × 800 ma w rzeczywistości **216 dpi**. Przy deklarowanych 320 jeden `dp` mierzy
     * 0,236 mm zamiast wzorcowych 0,159 — czyli **wszystko jest 1,5 raza za duże**: pisma,
     * ikony, taśma kursu. Zmierzone i potwierdzone wzrokiem na aparaturze 2026-08-25.
     *
     * Systemowej gęstości aplikacja zmienić nie może (i nie powinna — dotknęłoby to też
     * SIYI FPV i TX), ale własne drzewo Compose owijamy [LocalDensity] policzonym
     * z przekątnej panelu. Skutkiem ubocznym kadr rośnie z 640 × 400 do ok. 960 × 600 dp.
     */
    private var gestoscUkladu = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pelnyEkran()

        val host = intent?.getStringExtra("host") ?: LaczeMavlink.DOMYSLNY_HOST
        // Port telemetrii z intencji — pozwala postawić drugi symulator obok działającego
        // i sprawdzić na nim nową wersję, nie zabijając tego pierwszego.
        val portTelemetrii = intent?.getStringExtra("port")?.toIntOrNull()
            ?: LaczeMavlink.DOMYSLNY_PORT
        val hostGlowicy = intent?.getStringExtra("glowica") ?: KlientSiyi.DOMYSLNY_HOST
        val adresRtsp = intent?.getStringExtra("rtsp") ?: OdtwarzaczVlc.DOMYSLNY_ADRES

        gestoscUkladu = policzGestosc(intent?.getStringExtra("cale")?.toFloatOrNull() ?: PRZEKATNA_CALE)
        ustawienia = getSharedPreferences("kokpit", Context.MODE_PRIVATE)
        poprosOKarte()
        checklista = wczytajCheckliste()

        lacze = LaczeMavlink(silnik, host, portTelemetrii)
        glowica = KlientSiyi(hostGlowicy)
        glowica.przyZmianie = { g ->
            silnik.ustawGlowice(g.pitch, g.yaw, g.zoom, g.nagrywa, g.trybRuchu, g.odpowiada)
        }

        // ### Wybór toru obrazu
        //
        // `SIYI` — własny protokół kamery po TCP 37256, prosto do `MediaCodec`. Tak robi
        // fabryczna aplikacja SIYI FPV i dlatego chodzi płynnie, bez opóźnienia: nie ma
        // tam RTP ani zegara PCR, z którym libVLC walczyło cały 2026-08-28.
        // `RTSP` — stara droga przez libVLC, zostawiona jako odwrót.
        //
        // Przełącznik jest w panelu STRUMIEŃ na ekranie KAMERA; da się też narzucić przy
        // uruchomieniu: `adb shell am start -n pl.dron15.cockpit/.MainActivity -e wideo rtsp`.
        val zadanyTor = intent?.getStringExtra("wideo")
        torSiyi = when (zadanyTor) {
            "siyi" -> true
            "rtsp", "vlc" -> false
            // Domyślnie tor SIYI — ten sam, którym obraz bierze fabryczna aplikacja.
            // Bezpieczne, bo [TorZZapasem] sam schodzi na RTSP, gdy przez kilkanaście
            // sekund nic nie przychodzi. Bez tego zapasu byłoby ryzykowne: kamera
            // obsługuje **jednego klienta** na porcie 37256 i milczy, gdy zajmie go ktoś
            // inny — a w locie nie ma jak tego naprawić.
            else -> ustawienia.getBoolean(KLUCZ_TOR_SIYI, true)
        }

        if (zadanyTor != "vlc") {
            odtwarzacz = TorZZapasem(hostGlowicy, zaczynajOdSiyi = torSiyi).also {
                it.przyStanie = { dziala -> silnik.ustawWideo(dziala) }
                it.przyZejsciu = { tekst -> silnik.dopiszKomunikat(tekst, waga = 4) }
            }
            torNatywny = true
        }

        // Tor RTSP: budowa odtwarzacza schodzi z wątku głównego — patrz komentarz przy polu.
        // `Dispatchers.Default`, nie `IO`: to praca procesora (ładowanie i inicjalizacja
        // bibliotek), nie czekanie na dysk.
        if (!torSiyi && !torNatywny) lifecycleScope.launch {
            val zbudowany = withContext(Dispatchers.Default) {
                val poczatek = android.os.SystemClock.elapsedRealtime()
                try {
                    OdtwarzaczVlc(this@MainActivity).also {
                        Dziennik.info("wideo", "odtwarzacz gotowy po " +
                                "${android.os.SystemClock.elapsedRealtime() - poczatek} ms")
                    }
                } catch (e: Throwable) {
                    // Brak obrazu nie może zabrać telemetrii — aplikacja ma działać dalej.
                    Dziennik.blad("wideo", "nie udało się zbudować odtwarzacza", e)
                    null
                }
            }
            if (zbudowany == null) {
                silnik.dopiszKomunikat("Wideo niedostepne", waga = 4)
                return@launch
            }
            zbudowany.przyStanie = { dziala -> silnik.ustawWideo(dziala) }
            if (adresRtsp != OdtwarzaczVlc.DOMYSLNY_ADRES) {
                silnik.dopiszKomunikat("Strumien: $adresRtsp")
                zbudowany.graj(adresRtsp)
            }
            odtwarzacz = zbudowany
        }

        lacze.start(lifecycleScope)
        glowica.start(lifecycleScope)
        odswiezParametry()

        Barwy.ustaw(wczytajWarstwy().motyw)

        setContent {
            // ⛔ Stan maszyny podawany ekranowi **z ograniczoną częstotliwością**.
            //
            // Zmierzone na aparaturze 2026-08-28, `dumpsys gfxinfo`: **98,6 % klatek
            // interfejsu spóźnionych**, `Slow UI thread` w 505 z 723 klatek, mediana czasu
            // klatki 34 ms zamiast 16. Ekran rysował się w ok. 29 kl./s, a obraz z kamery
            // zacinał się — bo nie ma płynnego wideo pod nieplynnym interfejsem.
            //
            // Przyczyna powstała tego samego dnia i była skutkiem ubocznym własnej poprawki:
            // sklejanie strumienia MAVLink (Mavlink.skanujStrumien) podniosło telemetrię
            // z 3–8 Hz na **65 Hz**, a każda ramka zmieniała stan i przebudowywała cały HUD.
            // Naprawa telemetrii kupiła więc dane kosztem płynności obrazu.
            //
            // `sample` bierze **ostatnią** wartość z okna, więc nic się nie gubi poza
            // wartościami pośrednimi, których oko i tak nie zobaczy. 15 razy na sekundę
            // to więcej, niż potrzeba do czytania liczb i horyzontu.
            //
            // Nie dotyczy to danych, które muszą widzieć każdą ramkę — protokół misji
            // pracuje na `naRamke` w [pl.dron15.cockpit.net.mavlink.LaczeMavlink], obok
            // tego strumienia.
            // ### Tryb pomiarowy: SAM OBRAZ, bez HUD-u
            //
            // `-e goly 1` rysuje wyłącznie kadr. Służy do rozstrzygnięcia jednego pytania:
            // czy nierówne tempo obrazu bierze się z rywalizacji o rysowanie z interfejsem,
            // czy z samego toru wideo. Porównuje się `dumpsys SurfaceFlinger --latency`
            // z tym samym pomiarem przy pełnym ekranie. Do latania to się nie nadaje.
            if (intent?.getStringExtra("goly") != null) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().background(pl.dron15.cockpit.ui.Barwy.Tlo)
                ) {
                    pl.dron15.cockpit.ui.WidokWideo(odtwarzacz, Modifier.fillMaxSize())
                }
                return@setContent
            }

            val stan by remember { silnik.stan.sample(ODSTEP_EKRANU_MS) }
                .collectAsState(initial = silnik.stan.value)
            var adresStrumienia by androidx.compose.runtime.remember { mutableStateOf(adresRtsp) }
            var kanalySprzetowe by androidx.compose.runtime.remember {
                mutableStateOf(wczytajKanalySprzetowe())
            }
            var warstwy by androidx.compose.runtime.remember { mutableStateOf(wczytajWarstwy()) }
            var mapa by androidx.compose.runtime.remember { mutableStateOf(wczytajMape()) }

            // Misja zyje tutaj, a nie w interfejsie: pobranie z maszyny wraca z korutyny
            // lacza, wiec stan musi przezyc rekompozycje ekranu.
            var misja by androidx.compose.runtime.remember { mutableStateOf(Misja()) }
            var trybMisji by androidx.compose.runtime.remember { mutableStateOf(TrybMisji.PLANUJ) }
            var wybranyPunkt by androidx.compose.runtime.remember { mutableStateOf(-1) }
            var opisMisji by androidx.compose.runtime.remember { mutableStateOf("nowa trasa") }

            androidx.compose.runtime.LaunchedEffect(Unit) {
                lacze.misje?.przyPostepie = { opis -> opisMisji = opis }
            }

            // Wejscie w tryb LEC pobiera misje z maszyny - inaczej nie ma czego pokazac.
            androidx.compose.runtime.LaunchedEffect(trybMisji) {
                if (trybMisji == TrybMisji.LEC && misja.pusta) {
                    lacze.misje?.pobierz { pobrana, opis ->
                        if (pobrana != null) misja = pobrana
                        opisMisji = opis
                    }
                }
            }

            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(gestoscUkladu, fontScale = 1f)
            ) {
            // Ekran uruchamiania z logo producenta. Kokpit komponuje sie POD spodem,
            // wiec zaslona niczego nie opoznia — przykrywa tylko budowe toru obrazu
            // (na MK32 ok. 2,4 s). Gotowosc = tor obrazu stoi; szczegoly w EkranStartowy.
            ZEkranemStartowym(
                gotowe = odtwarzacz != null,
                etap = if (odtwarzacz == null) "uruchamianie toru obrazu"
                else "łączenie z maszyną",
            ) {
            Aplikacja(
                stan = stan,
                checklista = checklista,
                odtwarzacz = odtwarzacz,
                adresStrumienia = adresStrumienia,
                kanalySprzetowe = kanalySprzetowe,
                warstwy = warstwy,
                mapa = mapa,
                misja = misja,
                trybMisji = trybMisji,
                wybranyPunkt = wybranyPunkt,
                opisMisji = opisMisji,
                akcjeLotu = AkcjeLotu(
                    rtl = { lacze.powrotDoStartu() },
                    ladowanie = { lacze.ladowanie() },
                    przerwanieAutomatu = {
                        // Wyjscie z automatu do trybu, ktory maszyna na pewno przyjmie: LOITER
                        // wymaga pozycji, wiec bez kursu GNSS schodzimy do AltHold.
                        if (stan.kursGnssDostepny) lacze.ustawTryb(Tryby.LOITER, "LOITER")
                        else lacze.ustawTryb(Tryby.ALTHOLD, "ALTHOLD")
                    },
                ),
                akcjeKamery = AkcjeKamery(
                    obroc = { yaw, pitch -> glowica.obroc(yaw, pitch) },
                    stopObrotu = { glowica.stopObrotu() },
                    kat = { yaw, pitch -> glowica.ustawKat(yaw, pitch) },
                    centrum = { glowica.centruj() },
                    zoom = { kierunek -> glowica.zoom(kierunek) },
                    zoomBezwzgledny = { k -> glowica.zoomBezwzgledny(k) },
                    trybRuchu = { t -> glowica.trybRuchu(t) },
                    ostroscWPunkcie = { x, y, w, h -> glowica.ostroscWPunkcie(x, y, w, h) },
                    ostroscReczna = { kierunek -> glowica.ostroscReczna(kierunek) },
                    ustawStrumien = { kodek, w, h, bitrate ->
                        glowica.ustawStrumien(0, kodek, w, h, bitrate)
                        silnik.dopiszKomunikat("Nagranie: " + w + "x" + h + " " + kodek.etykieta)
                    },
                    zdjecie = { glowica.zdjecie() },
                    nagrywanie = { glowica.nagrywanie() },
                    strumienRtsp = { adres ->
                        // Dotyczy wyłącznie toru RTSP; tor SIYI ma jeden stały port.
                        adresStrumienia = adres
                        (odtwarzacz as? OdtwarzaczVlc)?.graj(adres)
                        silnik.dopiszKomunikat("Strumien: " + adres)
                    },
                    torSiyi = { (odtwarzacz as? TorZZapasem)?.naTorzeSiyi ?: false },
                    przelaczTor = { naSiyi ->
                        (odtwarzacz as? TorZZapasem)?.przelacz(naSiyi)
                        silnik.dopiszKomunikat(
                            if (naSiyi) "Obraz: tor SIYI" else "Obraz: tor RTSP",
                        )
                    },
                    restart = { kamera, glowicaTez ->
                        glowica.restart(kamera, glowicaTez)
                        silnik.dopiszKomunikat(
                            if (kamera) "Restart kamery — obraz wroci za chwile"
                            else "Restart glowicy",
                            waga = 4,
                        )
                    },
                ),
                akcjeMisji = AkcjeMisji(
                    wyslij = {
                        lacze.misje?.wyslij(
                            misja.zPowrotem(), stan.domSzerokosc, stan.domDlugosc,
                        ) { ok, opis ->
                            opisMisji = opis
                            silnik.dopiszKomunikat("Misja: " + opis, waga = if (ok) 6 else 4)
                        }
                    },
                    pobierzZMaszyny = {
                        lacze.misje?.pobierz { pobrana, opis ->
                            if (pobrana != null) misja = pobrana
                            opisMisji = opis
                        }
                    },
                    zapisz = {
                        opisMisji = MagazynMisji.zapisz(
                            misja,
                            MagazynMisji.proponowanaNazwa(System.currentTimeMillis()),
                            stan.domSzerokosc, stan.domDlugosc,
                        )
                    },
                    pauza = { wstrzymaj -> lacze.pauzaMisji(wstrzymaj) },
                    skok = { numer -> lacze.skokDoPunktu(numer) },
                    przerwij = {
                        if (stan.kursGnssDostepny) lacze.ustawTryb(Tryby.LOITER, "LOITER")
                        else lacze.ustawTryb(Tryby.ALTHOLD, "ALTHOLD")
                    },
                ),
                naWarstwy = { nowe -> warstwy = nowe; zapiszWarstwy(nowe) },
                naMape = { nowe -> mapa = nowe; zapiszMape(nowe) },
                naMisje = { nowa -> misja = nowa },
                naTrybMisji = { t -> trybMisji = t },
                naWyborPunktu = { i -> wybranyPunkt = if (wybranyPunkt == i) -1 else i },
                naKontrolePrzedlotowa = { lacze.kontrolaPrzedlotowa() },
                naOdswiezParametry = { odswiezParametry() },
                naPoprawkeParametru = { poprawka ->
                    lacze.zapiszParametr(lifecycleScope, poprawka.parametr, poprawka.docelowa)
                },
                naPrzelaczKanal = { nr -> kanalySprzetowe = przelaczKanal(nr) },
            )
            }
            }
        }

    }

    /**
     * Pytamy imiennie o to, czego faktycznie używamy: reguły checklisty, przypisania RC
     * i pojemność pakietu. Pełny zrzut to 1306 parametrów, czyli kilka minut na łączu
     * 115 200 dzielonym z telemetrią.
     */
    private fun odswiezParametry() {
        val potrzebne = (checklista?.potrzebneParametry.orEmpty() +
                Rc.POTRZEBNE_PARAMETRY + Ciag.POTRZEBNE_PARAMETRY +
                Ogrodzenie.POTRZEBNE_PARAMETRY + listOf("BATT_CAPACITY")).distinct()
        if (potrzebne.isEmpty()) return
        lacze.pobierzParametry(lifecycleScope, potrzebne)
    }

    /**
     * Odczyt karty jest potrzebny tylko do kafelków mapy. Odmowa niczego nie psuje —
     * mapa rysuje wtedy samą siatkę metryczną, więc nie blokujemy startu aplikacji.
     */
    private fun poprosOKarte() {
        if (android.os.Build.VERSION.SDK_INT > 29) return
        val zgoda = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (zgoda != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    /**
     * Warstwy ekranu — §4 przekazania: „ustawienie przeżywa restart".
     *
     * Przekazanie mówi `DataStore`; tu jest `SharedPreferences`. Wymóg brzmi „przeżywa
     * restart", a nie „konkretna biblioteka" — a `SharedPreferences` już w tym pliku jest,
     * robi dokładnie to samo na Androidzie 9 i **nie dokłada zależności**, dzięki czemu
     * projekt nadal buduje się offline.
     */
    private fun wczytajWarstwy(): WarstwyEkranu = WarstwyEkranu(
        // Domyslnie zdjeta: kurs, dom i wiatr sa teraz na okregu polozenia.
        tasmaKursu = ustawienia.getBoolean("w_tasma", false),
        miniaturaMapy = ustawienia.getBoolean("w_miniatura", true),
        okragPolozenia = ustawienia.getBoolean("w_okrag", true),
        rzadLiczb = ustawienia.getBoolean("w_liczby", true),
        dokAkcji = ustawienia.getBoolean("w_dok", true),
        pasZapasu = ustawienia.getBoolean("w_zapas", true),
        blokEnergii = ustawienia.getBoolean("w_energia", true),
        blokCelu = ustawienia.getBoolean("w_cel", true),
        dzwiek = ustawienia.getBoolean("w_dzwiek", true),
        // Zgodność wstecz: do 2026-08-28 motyw był boolem `w_ciemny`. Kto miał zapisany
        // jasny, dostanie jasny; reszta domyślnie ciemny.
        motyw = ustawienia.getString("w_motyw", null)
            ?.let { n -> Motyw.entries.firstOrNull { it.name == n } }
            ?: if (ustawienia.getBoolean("w_ciemny", true)) Motyw.CIEMNY else Motyw.JASNY,
    )

    /**
     * Ustawienia mapy — podkład i nakładki terenu. Ten sam powód, co przy warstwach ekranu:
     * operator dobiera je raz, pod swój rejon lotów, i nie chce robić tego przy każdym starcie.
     */
    private fun wczytajMape(): UstawieniaMapy = UstawieniaMapy(
        podklad = ustawienia.getString("m_podklad", null) ?: Podklady.domyslny.id,
        cieniowanie = ustawienia.getBoolean("m_cien", false),
        warstwice = ustawienia.getBoolean("m_warstwice", false),
        krokWarstwicM = ustawienia.getInt("m_krok", 20),
        azymut = ustawienia.getBoolean("m_azymut", false),
        widok3d = ustawienia.getBoolean("m_3d", false),
        profil = ustawienia.getBoolean("m_profil", true),
        zInternetu = ustawienia.getBoolean("m_internet", true),
    )

    private fun zapiszMape(m: UstawieniaMapy) {
        ustawienia.edit()
            .putString("m_podklad", m.podklad)
            .putBoolean("m_cien", m.cieniowanie)
            .putBoolean("m_warstwice", m.warstwice)
            .putInt("m_krok", m.krokWarstwicM)
            .putBoolean("m_azymut", m.azymut)
            .putBoolean("m_3d", m.widok3d)
            .putBoolean("m_profil", m.profil)
            .putBoolean("m_internet", m.zInternetu)
            .apply()
    }

    private fun zapiszWarstwy(w: WarstwyEkranu) {
        ustawienia.edit()
            .putBoolean("w_tasma", w.tasmaKursu)
            .putBoolean("w_miniatura", w.miniaturaMapy)
            .putBoolean("w_okrag", w.okragPolozenia)
            .putBoolean("w_liczby", w.rzadLiczb)
            .putBoolean("w_dok", w.dokAkcji)
            .putBoolean("w_zapas", w.pasZapasu)
            .putBoolean("w_energia", w.blokEnergii)
            .putBoolean("w_cel", w.blokCelu)
            .putBoolean("w_dzwiek", w.dzwiek)
            .putString("w_motyw", w.motyw.name)
            .apply()
    }

    /** Kanały zadeklarowane jako „obsługiwane sprzętowo" — dok/RC_PRZYPISANIA.md §1. */
    private fun wczytajKanalySprzetowe(): Set<Int> =
        ustawienia.getStringSet(KLUCZ_SPRZETOWE, emptySet())
            .orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    private fun przelaczKanal(nr: Int): Set<Int> {
        val nowe = wczytajKanalySprzetowe().toMutableSet()
        if (!nowe.add(nr)) nowe.remove(nr)
        ustawienia.edit().putStringSet(KLUCZ_SPRZETOWE, nowe.map { it.toString() }.toSet()).apply()
        return nowe
    }

    private fun wczytajCheckliste(): Checklista? = try {
        Checklista.zJson(assets.open("preflight_rules.json").bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        Dziennik.blad("checklista", "nie udało się wczytać reguł", e)
        silnik.dopiszKomunikat("Checklista niedostepna: ${e.message}", waga = 4)
        null
    }

    override fun onDestroy() {
        lacze.stop()
        glowica.stop()
        odtwarzacz?.zwolnij()
        super.onDestroy()
    }

    /** Gęstość z rzeczywistej przekątnej panelu: `dpi = przekątna w pikselach / cale`. */
    private fun policzGestosc(cale: Float): Float {
        val m = resources.displayMetrics
        val przekatnaPx = kotlin.math.hypot(m.widthPixels.toFloat(), m.heightPixels.toFloat())
        val dpi = przekatnaPx / cale
        val gestosc = dpi / 160f
        Dziennik.info("uklad", "panel %.0f px przekątnej, %.1f\" → %.0f dpi → gęstość %.2f (system melduje %.2f)"
            .format(przekatnaPx, cale, dpi, gestosc, m.density))
        return gestosc
    }

    private fun pelnyEkran() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val KLUCZ_SPRZETOWE = "kanaly_sprzetowe"

        /** Który tor obrazu: własny protokół SIYI (true) czy RTSP przez libVLC (false). */
        const val KLUCZ_TOR_SIYI = "tor_wideo_siyi"

        /** Własny klient RTSP zamiast libVLC. Domyślnie tak — patrz OdtwarzaczRtsp. */
        const val KLUCZ_TOR_NATYWNY = "tor_wideo_natywny"
    }
}
