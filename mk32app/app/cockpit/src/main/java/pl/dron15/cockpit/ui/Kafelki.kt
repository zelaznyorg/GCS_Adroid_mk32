package pl.dron15.cockpit.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Podkład mapy — kafelki rastrowe z karty TF, w układzie XYZ (ten sam, co OpenStreetMap).
 *
 * Świadomie **bez MapLibre**: aplikacja ma być lekka (instrukcja MK32 odradza obciążanie
 * aparatury), a operator i tak lata po jednym rejonie. Kafelki przygotowuje się raz na
 * komputerze (`narzedzia/kafelki.py`) i kopiuje na kartę:
 *
 *     /sdcard/dron15/kafelki/{warstwa}/{z}/{x}/{y}.png
 *
 * **Warstwa** to nazwa katalogu (`zdjecia`, `opisy`, `drogi`, `topo`, `mapa`, `noc`) —
 * z nich [Podklady] składa podkłady widoczne dla operatora. Stary układ bez nazwy warstwy
 * (`kafelki/{z}/{x}/{y}.png`) czytamy jako `zdjecia`, żeby karta sprzed tej zmiany nadal działała.
 *
 * Gdy kafelków nie ma, mapa rysuje samą siatkę metryczną — nadal pokazuje ślad, dom
 * i maszynę, więc brak podkładu niczego nie blokuje.
 */
object Kafelki {

    const val ROZMIAR = 256

    /** Web Mercator: pozycja w pikselach świata na danym powiększeniu. */
    fun swiatX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * ROZMIAR * 2.0.pow(z)

    fun swiatY(lat: Double, z: Int): Double {
        val s = lat * PI / 180.0
        return (1.0 - asinh(tan(s)) / PI) / 2.0 * ROZMIAR * 2.0.pow(z)
    }

    /** Ile metrów przypada na piksel kafelka na danej szerokości i powiększeniu. */
    fun metryNaPiksel(lat: Double, z: Int): Float =
        (156543.03392 * cos(lat * PI / 180.0) / 2.0.pow(z)).toFloat()

    /** Powiększenie najbliższe żądanej skali; poza 2..19 nie ma sensu schodzić. */
    fun poziomDla(metryNaPiksel: Float, lat: Double): Int {
        val z = ln(156543.03392 * cos(lat * PI / 180.0) / metryNaPiksel) / ln(2.0)
        return z.toInt().coerceIn(2, 19)
    }

    /**
     * Katalogi, w których szukamy kafelków — **przemiatane wszystkie i sumowane**
     * (`zbadajKarte`), nie „pierwszy wygrywa". Inaczej karta przygotowana na komputerze
     * przesłaniałaby warstwy dociągnięte z sieci, bo obie leżą pod innym korzeniem.
     */
    fun katalogi(context: Context): List<File> = listOfNotNull(
        File(KARTA),
        context.getExternalFilesDir("kafelki"),
        File(context.filesDir, "kafelki"),
    )

    const val KARTA = "/sdcard/dron15/kafelki"

    /**
     * Katalog, do którego zapisujemy kafelki ściągnięte z sieci. **Własny katalog aplikacji**,
     * a nie `/sdcard/dron15/kafelki`: od Androida 10 zapis do katalogu ogólnego wymaga
     * uprawnień, których ta aplikacja świadomie nie prosi, a czytamy oba tak samo.
     */
    fun katalogPobrany(context: Context): File =
        (context.getExternalFilesDir("kafelki") ?: File(context.filesDir, "kafelki"))
            .also { it.mkdirs() }

    /** Warstwa, pod którą podszywa się stary, bezimienny układ katalogów. */
    const val WARSTWA_STARA = "zdjecia"
}

/**
 * Magazyn kafelków: pamięć podręczna + wczytywanie w tle, **z podziałem na warstwy**.
 *
 * Rysowanie nie może czekać na dysk — brakujący kafelek zwraca `null`, ląduje w kolejce,
 * a po wczytaniu podbija `wersja`, co odświeża mapę. Dzięki temu mapa nigdy nie zacina
 * obrazu z kamery.
 */
class MagazynKafelkow(context: Context) {

    private val korzenie = Kafelki.katalogi(context)
    private val katalogPobrany = Kafelki.katalogPobrany(context)
    private val pobieracz = Pobieracz(katalogPobrany, "mapa")

    /**
     * Czy wolno dociągać brakujące kafelki z sieci. Ustawiają to mapy przy każdej kompozycji
     * z [UstawieniaMapy]; magazyn jest jeden na proces, więc jedno ustawienie wystarcza.
     */
    var zInternetu by mutableStateOf(true)
    private val zakres = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pamiec = LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true)
    private val wKolejce = HashSet<String>()

    /** Rośnie po każdym wczytanym kafelku — mapa obserwuje to jako stan Compose. */
    var wersja by mutableStateOf(0)
        private set

    /**
     * Warstwa → katalogi, w których faktycznie leżą jej kafelki, i poziomy powiększenia,
     * które tam są. Bez tego mapa prosiła o kafelek z poziomu, którego nikt nie pobrał,
     * i pokazywała pustą siatkę mimo pełnej karty.
     */
    private val warstwy: Map<String, WarstwaNaKarcie> = zbadajKarte(korzenie)

    /**
     * Czy da się pokazać tę warstwę: albo leży na karcie, albo umiemy ją dociągnąć.
     * Bez drugiego warunku podkład, którego nikt nie pobrał przed wyjazdem, byłby
     * wyszarzony także wtedy, gdy aparatura ma sieć.
     */
    fun maWarstwe(warstwa: String): Boolean =
        warstwy.containsKey(warstwa) || (zInternetu && Zrodla.ma(warstwa))

    /** Czy da się narysować ten podkład: liczy się **warstwa bazowa**, nakładki są dodatkiem. */
    fun maPodklad(podklad: Podklad): Boolean = maWarstwe(podklad.baza)

    var maKafelki by mutableStateOf(warstwy.isNotEmpty())
        private set

    /** Ile kafelków dociągnęliśmy z sieci w tym uruchomieniu — do panelu warstw. */
    val pobraneZSieci: Int get() = pobieracz.pobrane

    /**
     * Dlaczego dociąganie nie działa, albo `null`. Mapa dopisuje to do komunikatu o braku
     * podkładu — inaczej zepsute pobieranie i brak kafelków wyglądają identycznie.
     */
    val usterkaSieci: String? get() = pobieracz.usterka

    /** Czy ta warstwa leżała na karcie przy starcie — bez liczenia na sieć. */
    fun maNaKarcie(warstwa: String): Boolean = warstwy.containsKey(warstwa)

    fun poziomy(warstwa: String): List<Int> = warstwy[warstwa]?.poziomy.orEmpty()

    /**
     * Poziomy, które doszły **w trakcie pracy** — ściągnięte z sieci po starcie aplikacji.
     * Karta czytana jest raz, więc bez tego zbioru świeży kafelek nie miałby jak trafić
     * na ekran.
     */
    private val dolozone = HashMap<String, MutableSet<Int>>()

    private fun dolozonePoziomy(warstwa: String): Set<Int> =
        synchronized(dolozone) { dolozone[warstwa]?.toSet().orEmpty() }

    private fun odnotujPoziom(warstwa: String, z: Int) {
        synchronized(dolozone) { dolozone.getOrPut(warstwa) { HashSet() }.add(z) }
    }

    /**
     * Najbliższy dostępny poziom danej warstwy. Gdy operator zejdzie bliżej, niż sięga zapas
     * kafelków, rysujemy powiększony kafelek z niższego poziomu — rozmyty, ale prawdziwy.
     *
     * ### ⛔ Nie skracać tego przez „przy sieci bierzemy poziom żądany"
     *
     * Do 2026-08-26 była tu linia: *jeśli pobieranie z sieci jest włączone, zwróć poziom
     * żądany — serwer ma każdy, a kafelek dociągnie się sam*. Założenie jest prawdziwe
     * dokładnie tak długo, jak długo sieć **naprawdę** działa.
     *
     * Zmierzone na aparaturze: MK32 w sieci pokładowej drona nie ma internetu, kokpit
     * prosił więc o `zdjecia/19/...`, nie dostawał nic — i **pomijał przy tym zapas
     * z karty**. Na ekranie została sama siatka metryczna, mimo 45 MB kafelków leżących
     * obok. Najgorszy możliwy wynik: mapa znika akurat wtedy, gdy sieci nie ma, czyli
     * w polu.
     *
     * Reguła jest teraz jedna i nie zależy od zgadywania stanu sieci: **rysuj poziom
     * żądany, jeśli go masz; jeśli nie — najbliższy, który masz.** Pobranie i tak zostaje
     * zamówione, więc po ściągnięciu obraz sam się wyostrzy.
     */
    fun najblizszyPoziom(warstwa: String, zadany: Int): Int {
        val cel = zadany.coerceIn(1, 19)
        // Poziomy z karty **plus** te, które dociągnęliśmy w trakcie pracy. Bez tej sumy
        // świeżo pobrany kafelek nigdy by się nie pokazał: kartę czytamy raz przy starcie.
        val dostepne = poziomy(warstwa) + dolozonePoziomy(warstwa)
        if (dostepne.isEmpty() || cel in dostepne) return cel
        return dostepne.minByOrNull { kotlin.math.abs(it - cel) } ?: cel
    }

    fun kafelek(warstwa: String, z: Int, x: Int, y: Int): ImageBitmap? {
        val klucz = "$warstwa/$z/$x/$y"
        synchronized(pamiec) {
            if (pamiec.containsKey(klucz)) {
                val gotowy = pamiec[klucz]
                if (gotowy != null) return gotowy
                // Pusty wpis znaczy „nie było tego na karcie". Gdy sieć jest włączona,
                // zamawiamy kafelek i **zapominamy o pustym wpisie** — inaczej raz nieudane
                // wczytanie zamykałoby drogę pobraniu na resztę lotu.
                if (!zInternetu) return null
                if (zamow(warstwa, z, x, y)) pamiec.remove(klucz)
                return null
            }
            if (klucz in wKolejce) return null
            wKolejce += klucz
        }
        zakres.launch {
            val obraz = wczytaj(warstwa, z, x, y)
            synchronized(pamiec) {
                pamiec[klucz] = obraz
                wKolejce -= klucz
                while (pamiec.size > MAKS_W_PAMIECI) {
                    val najstarszy = pamiec.keys.firstOrNull() ?: break
                    pamiec.remove(najstarszy)
                }
            }
            if (obraz != null) {
                maKafelki = true
                wersja++
            } else if (zInternetu) {
                synchronized(pamiec) { if (zamow(warstwa, z, x, y)) pamiec.remove(klucz) }
            }
        }
        return null
    }

    private fun zamow(warstwa: String, z: Int, x: Int, y: Int): Boolean {
        val wzor = Zrodla.WARSTWY[warstwa] ?: return false
        return pobieracz.zamow(wzor, warstwa, z, x, y) {
            maKafelki = true
            odnotujPoziom(warstwa, z)
            wersja++
        }
    }

    private fun wczytaj(warstwa: String, z: Int, x: Int, y: Int): ImageBitmap? {
        // Katalog pobranych jest przeszukiwany zawsze, także dla warstwy, której nie było
        // na karcie przy starcie — inaczej pierwszy ściągnięty kafelek nie miałby jak wrócić.
        val katalogi = warstwy[warstwa]?.katalogi.orEmpty() + File(katalogPobrany, warstwa)
        for (katalog in katalogi) {
            for (rozszerzenie in ROZSZERZENIA) {
                val plik = File(katalog, "$z/$x/$y.$rozszerzenie")
                if (!plik.isFile) continue
                // inScaled=false: bez tego Android skaluje kafelek pod gęstość ekranu
                // i podkład rozjeżdża się z siatką metryczną o kilka procent.
                val opcje = BitmapFactory.Options().apply { inScaled = false }
                val mapa = BitmapFactory.decodeFile(plik.absolutePath, opcje) ?: continue
                return mapa.asImageBitmap()
            }
        }
        return null
    }

    companion object {
        /**
         * **Jedna instancja na proces.** Mapa lotu, mapa planowania, widok przestrzenny,
         * pasek podkładu i panel warstw pytają o te same kafelki; osobny magazyn w każdym
         * z tych miejsc znaczyłby sześć pamięci podręcznych po 55 MB i sześć przemiatań
         * karty. Karta czytana jest **raz, przy pierwszym użyciu** — kafelki dołożone przy
         * działającej aplikacji zobaczy dopiero następne uruchomienie.
         */
        @Volatile
        private var wspolny: MagazynKafelkow? = null

        fun dla(context: Context): MagazynKafelkow =
            wspolny ?: synchronized(this) {
                wspolny ?: MagazynKafelkow(context.applicationContext).also { wspolny = it }
            }

        const val MAKS_W_PAMIECI = 220           // ok. 55 MB przy kafelku 256×256 ARGB
        val ROZSZERZENIA = listOf("png", "jpg", "jpeg", "webp")

        /** Jedna warstwa widziana na karcie: gdzie leży i jakie ma poziomy powiększenia. */
        data class WarstwaNaKarcie(val katalogi: List<File>, val poziomy: List<Int>)

        /**
         * Przemiata korzenie i buduje mapę warstw. Katalog o nazwie **liczbowej** znaczy,
         * że karta jest w starym układzie (`kafelki/{z}/...`) — wtedy sam korzeń jest
         * warstwą [Kafelki.WARSTWA_STARA].
         */
        fun zbadajKarte(korzenie: List<File>): Map<String, WarstwaNaKarcie> {
            val katalogiWarstw = LinkedHashMap<String, MutableList<File>>()
            for (korzen in korzenie) {
                if (!korzen.isDirectory) continue
                val dzieci = korzen.listFiles()?.filter { it.isDirectory }.orEmpty()
                val stary = dzieci.any { it.name.toIntOrNull() != null }
                if (stary) {
                    katalogiWarstw.getOrPut(Kafelki.WARSTWA_STARA) { mutableListOf() } += korzen
                }
                for (dziecko in dzieci) {
                    if (dziecko.name.toIntOrNull() != null) continue
                    katalogiWarstw.getOrPut(dziecko.name) { mutableListOf() } += dziecko
                }
            }
            return katalogiWarstw.mapNotNull { (nazwa, katalogi) ->
                val poziomy = katalogi
                    .flatMap { it.list()?.toList().orEmpty() }
                    .mapNotNull { it.toIntOrNull() }
                    .distinct()
                    .sorted()
                if (poziomy.isEmpty()) null else nazwa to WarstwaNaKarcie(katalogi, poziomy)
            }.toMap()
        }
    }
}
