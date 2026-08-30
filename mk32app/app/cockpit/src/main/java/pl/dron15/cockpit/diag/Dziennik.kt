package pl.dron15.cockpit.diag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Rejestr techniczny kokpitu — jedno miejsce, do którego trafia wszystko, co poszło nie tak.
 * Konwencje wspólne dla całego projektu: dok/LOGI_I_BLEDY.md
 *
 * Uwaga na dwa różne strumienie komunikatów, bo łatwo je pomylić:
 *
 *   SilnikStanu.dopiszKomunikat   DLA PILOTA    krótkie, po polsku, widoczne na ekranie
 *   Dziennik (ten plik)           DLA NAS       ze stosem wywołań, do pliku, do debugowania
 *
 * Zasady:
 *  - nic tutaj nie może wywrócić aplikacji. Log, który psuje lot, jest gorszy niż brak loga.
 *  - zapis idzie na osobny wątek, żeby karta TF nie zacinała rysowania ekranu.
 *  - wyjątkiem jest [awaria]: tam proces już umiera, więc piszemy natychmiast i z tego wątku.
 *
 * Gdzie ląduje plik: `Android/data/pl.dron15.cockpit/files/logi/kokpit-RRRR-MM-DD.log`.
 * To katalog własny aplikacji, więc nie wymaga żadnych uprawnień, a mimo to widać go
 * z komputera po podpięciu MK32 kablem.
 */
object Dziennik {

    enum class Poziom { BLAD, OSTRZ, INFO, SZCZEG }

    data class Wpis(
        val czas: Long,
        val poziom: Poziom,
        val obszar: String,
        val wiadomosc: String,
        val stos: String? = null,
    ) {
        val godzina: String get() = GODZINA.format(Date(czas))
    }

    private const val TAG = "DRON15"
    private const val W_PAMIECI = 300
    private const val DNI_DO_ZACHOWANIA = 7

    private val GODZINA = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val DZIEN = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val ZNACZNIK = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val zapisywacz = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dziennik").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private val _wpisy = MutableStateFlow<List<Wpis>>(emptyList())

    /** Ostatnie wpisy, od najnowszego. Podglądane na ekranie DIAGNOSTYKA. */
    val wpisy: StateFlow<List<Wpis>> = _wpisy

    @Volatile private var katalog: File? = null
    @Volatile private var progSzczegolow = false

    /**
     * Wywołać jak najwcześniej — z [pl.dron15.cockpit.KokpitApp.onCreate], nie z aktywności.
     * Awaria przy starcie zdarza się przed pierwszym ekranem i też ma zostawić ślad.
     */
    fun start(kontekst: Context, zeSzczegolami: Boolean = false) {
        progSzczegolow = zeSzczegolami
        katalog = try {
            (kontekst.getExternalFilesDir("logi") ?: File(kontekst.filesDir, "logi"))
                .apply { mkdirs() }
        } catch (e: Throwable) {
            Log.w(TAG, "brak katalogu logow: ${e.message}")
            null
        }
        sprzatnijStare()
        info("start", "kokpit wstaje, logi w ${katalog?.absolutePath ?: "(tylko pamięć)"}")
    }

    fun blad(obszar: String, wiadomosc: String, e: Throwable? = null) =
        pisz(Poziom.BLAD, obszar, wiadomosc, e)

    fun ostrzezenie(obszar: String, wiadomosc: String, e: Throwable? = null) =
        pisz(Poziom.OSTRZ, obszar, wiadomosc, e)

    fun info(obszar: String, wiadomosc: String) = pisz(Poziom.INFO, obszar, wiadomosc, null)

    fun szczegol(obszar: String, wiadomosc: String) {
        if (progSzczegolow) pisz(Poziom.SZCZEG, obszar, wiadomosc, null)
    }

    private fun pisz(poziom: Poziom, obszar: String, wiadomosc: String, e: Throwable?) {
        val czas = System.currentTimeMillis()
        val stos = e?.let { skrocStos(it) }

        // Logcat zostaje — przy podpiętym kablu to najszybsza droga do podejrzenia.
        when (poziom) {
            Poziom.BLAD -> Log.e(TAG, "[$obszar] $wiadomosc", e)
            Poziom.OSTRZ -> Log.w(TAG, "[$obszar] $wiadomosc")
            else -> Log.i(TAG, "[$obszar] $wiadomosc")
        }

        val wpis = Wpis(czas, poziom, obszar, wiadomosc, stos)
        _wpisy.value = (_wpisy.value + wpis).takeLast(W_PAMIECI)

        val linia = sformatuj(wpis)
        try {
            zapisywacz.execute { dopiszDoPliku(linia) }
        } catch (_: Throwable) {
            // Kolejka odrzuciła zadanie (np. przy zamykaniu) — wpis zostaje w pamięci.
        }
    }

    private fun sformatuj(w: Wpis): String {
        val podstawa = "${ZNACZNIK.format(Date(w.czas))} ${w.poziom.name.padEnd(6)} " +
                "[${w.obszar.padEnd(12)}] ${w.wiadomosc}"
        return if (w.stos == null) podstawa else "$podstawa\n    ${w.stos.replace("\n", "\n    ")}"
    }

    private fun skrocStos(e: Throwable): String {
        val pelny = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        // Osiem linii wystarcza, żeby zobaczyć, skąd przyszło; reszta to zwykle Android.
        return pelny.lineSequence().take(8).joinToString("\n").trim()
    }

    private fun plikNaDzis(): File? {
        val k = katalog ?: return null
        return File(k, "kokpit-${DZIEN.format(Date())}.log")
    }

    private fun dopiszDoPliku(linia: String) {
        try {
            plikNaDzis()?.appendText(linia + "\n")
        } catch (e: Throwable) {
            Log.w(TAG, "zapis do dziennika nieudany: ${e.message}")
        }
    }

    /** Karta w aparaturze jest mała, a logi rosną. Trzymamy tydzień i tyle. */
    private fun sprzatnijStare() {
        try {
            val granica = System.currentTimeMillis() - DNI_DO_ZACHOWANIA * 24L * 3600_000L
            katalog?.listFiles { f -> f.name.startsWith("kokpit-") && f.name.endsWith(".log") }
                ?.filter { it.lastModified() < granica }
                ?.forEach { it.delete() }
        } catch (_: Throwable) {
            // Sprzątanie to wygoda, nie warunek działania.
        }
    }

    // ---- awarie ----

    /**
     * Zapis w chwili, gdy proces się kończy. Piszemy SYNCHRONICZNIE i z bieżącego wątku —
     * zadanie oddane do kolejki nie zdążyłoby się wykonać.
     */
    fun awaria(watek: Thread, e: Throwable) {
        val linia = "${ZNACZNIK.format(Date())} AWARIA [${watek.name.padEnd(12)}] " +
                "${e.javaClass.simpleName}: ${e.message}\n    " +
                StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                    .trim().replace("\n", "\n    ")
        Log.e(TAG, "AWARIA na wątku ${watek.name}", e)
        try {
            plikNaDzis()?.appendText(linia + "\n")
            // Znacznik czytany przy następnym starcie: pilot ma się dowiedzieć, że
            // poprzednie uruchomienie skończyło się awarią, nawet jeśli tego nie widział.
            katalog?.let { File(it, "ostatnia_awaria.txt").writeText(linia) }
        } catch (_: Throwable) {
            // Nie ma już czego ratować.
        }
    }

    /** Opis ostatniej awarii albo null. Czytane raz, przy starcie aplikacji. */
    fun ostatniaAwaria(): String? = try {
        katalog?.let { File(it, "ostatnia_awaria.txt") }?.takeIf { it.exists() }?.readText()
    } catch (_: Throwable) {
        null
    }

    fun potwierdzAwarie() {
        try {
            katalog?.let { File(it, "ostatnia_awaria.txt").delete() }
        } catch (_: Throwable) {
            // Zostanie do następnego razu — nie szkodzi.
        }
    }

    fun sciezkaLogow(): String = katalog?.absolutePath ?: "(brak — tylko pamięć)"

    /**
     * Do przekazania każdemu `launch`, które robi coś w tle. Bez tego wyjątek
     * w korutynie wywraca całą aplikację, zamiast zabrać jedno łącze.
     */
    fun uchwytKorutyny(obszar: String) = CoroutineExceptionHandler { _, e ->
        blad(obszar, "korutyna przerwana wyjątkiem", e)
    }
}
