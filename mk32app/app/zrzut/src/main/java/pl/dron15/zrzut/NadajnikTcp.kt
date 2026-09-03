package pl.dron15.zrzut

import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Wysyłka obrazu na stację — jedno gniazdo TCP i nic więcej.
 *
 * ### Dlaczego surowy H.264, a nie RTMP
 *
 * RTMP z aparatury znaczyłby własny handshake, chunkowanie i muxer FLV — kilkaset
 * linii protokołu na urządzeniu, którego nie da się wygodnie podejrzeć. Tutaj
 * wysyłamy dokładnie to, co wypluwa [android.media.MediaCodec]: strumień elementarny
 * Annex-B. Całą resztę robi `ffmpeg` na stacji, gdzie jest i konsola, i dziennik.
 *
 * Protokół jest więc taki:
 *
 *   1. jedna linia JSON zakończona `\n` — hasło urządzenia, rozmiar, tempo;
 *   2. potem już tylko klatki, jedna za drugą, bez żadnej ramki własnej.
 *
 * ⚠ Nagłówek niesie hasło jawnie. Sieć jest lokalna (stacja i aparatura w jednym
 * segmencie), a hasło i tak wraca w adresie RTMP — ale to znaczy, że **po utracie
 * sprzętu hasło trzeba wymienić** (`POST /api/nadawanie/nowe-haslo`).
 */
class NadajnikTcp(
    private val adres: String,
    private val port: Int,
    private val haslo: String,
) {
    private var gniazdo: Socket? = null
    private var wyjscie: OutputStream? = null

    /** Ile bajtów poszło od zestawienia łącza — do pokazania operatorowi. */
    @Volatile var wyslano: Long = 0
        private set

    fun polacz(szerokosc: Int, wysokosc: Int, fps: Int) {
        val g = Socket()
        // Krótki limit: jeśli stacji nie ma, operator ma się dowiedzieć od razu,
        // a nie po trzydziestu sekundach patrzenia w nieruchomy ekran.
        g.connect(InetSocketAddress(adres, port), 4000)
        // Obraz na żywo: zwłoka boli bardziej niż narzut małych pakietów.
        g.tcpNoDelay = true
        val out = BufferedOutputStream(g.getOutputStream(), 64 * 1024)
        val naglowek = """{"haslo":"$haslo","szer":$szerokosc,"wys":$wysokosc,"fps":$fps}""" + "\n"
        out.write(naglowek.toByteArray(Charsets.UTF_8))
        out.flush()
        gniazdo = g
        wyjscie = out
        wyslano = 0
        Log.i(TAG, "łącze do stacji zestawione: $adres:$port")
    }

    /**
     * Wysyła jedną porcję strumienia.
     *
     * ⛔ Zwraca `false` zamiast rzucać: pętla kodera nie może się wywrócić przez
     * zerwane łącze, bo wtedy przechwytywanie ekranu zostaje uruchomione, a nikt
     * już go nie odbiera.
     */
    fun wyslij(bufor: ByteBuffer, dlugosc: Int): Boolean {
        val out = wyjscie ?: return false
        return try {
            val tab = ByteArray(dlugosc)
            bufor.get(tab, 0, dlugosc)
            out.write(tab)
            out.flush()
            wyslano += dlugosc
            true
        } catch (e: Exception) {
            Log.w(TAG, "zerwane łącze do stacji: ${e.message}")
            zamknij()
            false
        }
    }

    fun zamknij() {
        try { wyjscie?.flush() } catch (_: Exception) {}
        try { gniazdo?.close() } catch (_: Exception) {}
        wyjscie = null
        gniazdo = null
    }

    val polaczony: Boolean get() = gniazdo?.isConnected == true && gniazdo?.isClosed == false

    private companion object { const val TAG = "zrzut.nadajnik" }
}
