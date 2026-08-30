package pl.dron15.cockpit.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pl.dron15.cockpit.diag.Dziennik
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serwery kafelków — **skąd mapa bierze się z sieci**.
 *
 * Te same adresy, co w `narzedzia/kafelki.py`, żeby kafelek pobrany w aplikacji i kafelek
 * pobrany przed wyjazdem na komputerze były tym samym plikiem w tym samym miejscu.
 *
 * ⚠ Kolejność u Esri to `{z}/{y}/{x}`, u reszty `{z}/{x}/{y}`. To jedyna różnica i siedzi
 * wyłącznie tutaj.
 */
object Zrodla {

    val WARSTWY = mapOf(
        "zdjecia" to
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "opisy" to
            "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}",
        "drogi" to
            "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}",
        "topo" to "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        "mapa" to "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "noc" to "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
    )

    /** Dane wysokościowe — Terrarium; wysokość siedzi w barwie piksela. */
    const val TEREN = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    fun ma(warstwa: String): Boolean = WARSTWY.containsKey(warstwa)

    fun adres(wzor: String, z: Int, x: Int, y: Int): String =
        wzor.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")

    /** Serwery bywają rozpoznawane po nagłówku; podajemy się uczciwie. */
    const val NAGLOWEK = "DRON15-cockpit/0.3 (MK32 ground station; one flight area)"
}

/**
 * Wskazuje sieć, którą wolno pytać o kafelki — **a to nie jest sieć domyślna**.
 *
 * ### Skąd to się wzięło
 *
 * Zmierzone na aparaturze 2026-08-26. Gdy MK32 jest równocześnie w sieci pokładowej drona
 * (`eth0`) i w Wi-Fi, Android robi domyślną **sieć pokładową** — a ta ogłasza o sobie
 * nieprawdę:
 *
 * ```
 * Active default network: eth0
 *   Capabilities: INTERNET & VALIDATED
 *   Routes: 0.0.0.0/0 -> 192.168.144.12
 *   DnsAddresses: 8.8.8.8
 * ```
 *
 * Deklaruje internet i bramę do świata, której nie ma — `8.8.8.8` jest przez to łącze
 * nieosiągalny. Kokpit pytał sieć domyślną i dostawał `UnknownHostException`, mimo że
 * Wi-Fi obok działało bez zarzutu. Objaw: **czarna mapa przy sprawnym internecie.**
 *
 * Dlatego nie ufamy tu ani „domyślności", ani fladze `VALIDATED`, tylko **rodzajowi
 * łącza**: kafelki ciągniemy przez Wi-Fi albo sieć komórkową, nigdy przez Ethernet,
 * bo Ethernet w tej maszynie z definicji prowadzi do drona, nie do internetu.
 */
object SiecDoInternetu {

    @Volatile
    private var polaczenia: ConnectivityManager? = null

    fun zapamietaj(kontekst: Context) {
        polaczenia = kontekst.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    /** Otwiera połączenie siecią z prawdziwym internetem; przy jej braku — jak dotąd. */
    fun otworz(adres: String): HttpURLConnection {
        val url = URL(adres)
        val siec = wybierz()
        return (siec?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
    }

    private fun wybierz(): Network? {
        val cm = polaczenia ?: return null
        var zapasowa: Network? = null
        for (siec in cm.allNetworks) {
            val moznosci = cm.getNetworkCapabilities(siec) ?: continue
            if (!moznosci.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (moznosci.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            if (moznosci.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return siec
            zapasowa = siec          // komórkowa albo cokolwiek innego niż Ethernet
        }
        return zapasowa
    }
}

/**
 * Pobieranie kafelków z sieci — **uzupełnia kartę, nie zastępuje jej**.
 *
 * ### Dlaczego jedno i drugie
 *
 * W polu aparatura MK32 siedzi zwykle w sieci pokładowej drona i **internetu tam nie ma**;
 * pobrany wcześniej zapas kafelków to jedyna mapa, jaką operator wtedy ma. Ale wszędzie tam,
 * gdzie sieć jest — w domu, w aucie z telefonem jako punktem dostępu, przed wyjazdem —
 * mapa ma się dociągać sama, zamiast czekać na to, aż ktoś uruchomi narzędzie na komputerze.
 *
 * Dlatego pobrany kafelek **ląduje na karcie** (katalog własny aplikacji, ten sam układ
 * `{warstwa}/{z}/{x}/{y}`) i od tej chwili działa bez sieci. Oglądnięcie rejonu przy sieci
 * jest więc równocześnie przygotowaniem go na lot bez sieci.
 *
 * Którą siecią pytamy — patrz [SiecDoInternetu]; **nie jest to sieć domyślna**.
 *
 * ### Czego pilnuje
 *
 * - **cztery pobrania naraz** i odstęp między nimi — regulaminy serwerów kafelkowych
 *   (zwłaszcza OpenStreetMap) zabraniają masowego ściągania; przeglądanie własnego rejonu
 *   mieści się w granicach, zalewanie serwera nie;
 * - **nie prosi dwa razy o to samo** — ani równolegle, ani zaraz po nieudanej próbie;
 * - **nie zapisuje śmieci** — obrazek „Access blocked" ma parę setek bajtów i serwer odsyła
 *   go z kodem 200, więc bez tej kontroli trafiłby na kartę i zostałby tam na zawsze.
 */
class Pobieracz(
    private val katalog: File,
    private val nazwa: String,
) {
    private val zakres = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bramka = Semaphore(ROWNOLEGLE)
    private val wDrodze = HashSet<String>()
    private val nieudane = HashMap<String, Long>()

    /** Rośnie po każdym zapisanym kafelku — magazyn nasłuchuje i odświeża rysunek. */
    @Volatile
    var pobrane: Int = 0
        private set

    /**
     * Dlaczego pobieranie nie działa — **jedno zdanie dla operatora**, albo `null`.
     *
     * Bez tego brak podkładu wygląda dokładnie tak samo, niezależnie od przyczyny: pusta
     * siatka metryczna. A przyczyny bywają zupełnie różne i tylko część da się naprawić
     * w polu — dlatego mapa ma powiedzieć, którą z nich ma przed sobą.
     *
     * Stan Compose, nie zwykłe pole: ustawia go wątek pobierania, a czyta rysunek mapy.
     */
    var usterka: String? by mutableStateOf(null)
        private set

    /**
     * Zamawia kafelek. Zwraca `true`, jeśli faktycznie ruszyło pobieranie — magazyn wie
     * wtedy, że warto zapytać o plik ponownie za chwilę.
     */
    fun zamow(wzorAdresu: String, podkatalog: String, z: Int, x: Int, y: Int,
              tylkoPng: Boolean = false, poZapisie: () -> Unit): Boolean {
        if (z < 1 || z > MAKS_POZIOM) return false
        val klucz = "$podkatalog/$z/$x/$y"
        synchronized(wDrodze) {
            if (klucz in wDrodze) return false
            val ostatniaPorazka = nieudane[klucz]
            if (ostatniaPorazka != null &&
                System.currentTimeMillis() - ostatniaPorazka < ODCZEKANIE_MS) return false
            if (wDrodze.size >= MAKS_W_KOLEJCE) return false
            wDrodze += klucz
        }
        zakres.launch {
            var udane = false
            try {
                bramka.withPermit {
                    udane = sciagnij(Zrodla.adres(wzorAdresu, z, x, y),
                        File(katalog, "$podkatalog/$z/$x"), y, tylkoPng)
                    delay(ODSTEP_MS)
                }
            } catch (e: Throwable) {
                usterka = zdiagnozujPobieranie(e, System.currentTimeMillis())
                Dziennik.blad("mapa", "pobieranie kafelka $nazwa $klucz — $usterka", e)
            } finally {
                synchronized(wDrodze) {
                    wDrodze -= klucz
                    if (udane) nieudane.remove(klucz)
                    else nieudane[klucz] = System.currentTimeMillis()
                }
            }
            if (udane) {
                pobrane++
                usterka = null          // ostatnia próba wyszła — poprzednia diagnoza jest nieaktualna
                poZapisie()
            }
        }
        return true
    }

    private fun sciagnij(adres: String, katalogDocelowy: File, y: Int, tylkoPng: Boolean): Boolean {
        val polaczenie = SiecDoInternetu.otworz(adres).apply {
            connectTimeout = CZAS_MS
            readTimeout = CZAS_MS
            setRequestProperty("User-Agent", Zrodla.NAGLOWEK)
            instanceFollowRedirects = true
        }
        try {
            if (polaczenie.responseCode != 200) return false
            val dane = polaczenie.inputStream.use { it.readBytes() }
            if (dane.size < MINIMUM_BAJTOW) return false
            val rozszerzenie = rozpoznaj(dane) ?: return false
            if (tylkoPng && rozszerzenie != "png") return false

            katalogDocelowy.mkdirs()
            // Zapis przez plik tymczasowy: przerwane pobieranie nie może zostawić na karcie
            // obciętego kafelka, bo ten wyglądałby jak poprawny i nigdy by się nie odświeżył.
            val tymczasowy = File(katalogDocelowy, "$y.$rozszerzenie.tmp")
            tymczasowy.writeBytes(dane)
            return tymczasowy.renameTo(File(katalogDocelowy, "$y.$rozszerzenie"))
        } finally {
            polaczenie.disconnect()
        }
    }

    /** Format z zawartości, nie z adresu: Esri odsyła zdjęcia jako JPEG, resztę jako PNG. */
    private fun rozpoznaj(dane: ByteArray): String? = when {
        dane.size > 8 && dane[0] == 0x89.toByte() && dane[1] == 'P'.code.toByte() -> "png"
        dane.size > 3 && dane[0] == 0xFF.toByte() && dane[1] == 0xD8.toByte() -> "jpg"
        dane.size > 12 && dane[0] == 'R'.code.toByte() && dane[1] == 'I'.code.toByte() -> "webp"
        else -> null
    }

    private companion object {
        const val ROWNOLEGLE = 4
        const val ODSTEP_MS = 80L
        const val CZAS_MS = 15_000
        const val MAKS_W_KOLEJCE = 48
        const val ODCZEKANIE_MS = 60_000L
        const val MAKS_POZIOM = 19

        /** Zastępczy obrazek „Access blocked" z OSM ma kilkaset bajtów i kod 200. */
        const val MINIMUM_BAJTOW = 400
    }
}

/**
 * Zamienia wyjątek z pobierania na **jedno zdanie, które coś operatorowi daje**.
 *
 * ### Skąd to się wzięło
 *
 * 2026-08-26 na MK32 żaden kafelek nie chciał się ściągnąć, a mapa pokazywała pustą siatkę
 * bez słowa wyjaśnienia. W logu siedziało:
 *
 *     CertificateNotYetValidException: Certificate not valid until Wed Jul 16 2025
 *     (compared to Mon Oct 02 23:26:21 2023)
 *
 * Aparatura miała **fabryczny zegar z 2023 roku**, więc każdy certyfikat HTTPS był dla niej
 * „jeszcze nieważny" i TLS zrywał połączenie. Sieć działała bez zarzutu. Tego z pustej
 * siatki nie da się odgadnąć, a naprawa to dwa dotknięcia w ustawieniach Androida —
 * pod warunkiem, że ktoś wie, czego szukać.
 *
 * [teraz] podajemy z zewnątrz zamiast czytać zegar w środku, żeby dało się to sprawdzić testem.
 */
internal fun zdiagnozujPobieranie(blad: Throwable, teraz: Long): String {
    var przyczyna: Throwable? = blad
    var glebokosc = 0
    while (przyczyna != null && glebokosc < 12) {
        when (przyczyna) {
            is CertificateNotYetValidException, is CertificateExpiredException -> {
                val data = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(teraz))
                return "zegar aparatury pokazuje $data, więc każdy certyfikat HTTPS jest dla " +
                        "niej nieważny. Ustaw datę i strefę czasu w Androidzie."
            }
            is UnknownHostException ->
                return "brak sieci — adres serwera map nie daje się rozwiązać."
            is java.net.SocketTimeoutException ->
                return "serwer map nie odpowiada."
        }
        przyczyna = przyczyna.cause
        glebokosc++
    }
    return "pobieranie kafelków nie działa (${blad.javaClass.simpleName})."
}
