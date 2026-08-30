package pl.dron15.cockpit.net.mavlink

import pl.dron15.cockpit.diag.Dziennik
import pl.dron15.cockpit.net.SiecPokladowa
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Rozgałęźnik telemetrii — aparatura oddaje strumień dalej, sama zostając jedynym
 * klientem jednostki naziemnej.
 *
 * ### Po co to istnieje
 *
 * Jednostka naziemna MK32 obsługuje **jednego klienta MAVLink** (CLAUDE.md poz. 57).
 * Każdy drugi odbiorca — stacja podglądu na RPi, laptop z narzędziami — nie dokłada się
 * do strumienia, tylko **podbiera go aparaturze**. Zmierzone: przy nasłuchu z laptopa
 * kokpit przestaje dostawać dane.
 *
 * Dlatego rozgałęzienie robi ta strona, która i tak musi być pierwsza: **kokpit**.
 * Trzyma jedyne łącze do `.12:19856` i rozdaje kopie wszystkim, którzy się zgłoszą.
 *
 * ```
 *   jednostka naziemna .12:19856
 *            │  (jeden klient — kokpit)
 *            ▼
 *      KOKPIT MK32  ──┬──►  stacja RPi        (podgląd, archiwum .tlog)
 *                     ├──►  laptop z narzędziami
 *                     └──►  kolejny widz
 * ```
 *
 * ### ⛔ RUCH IDZIE TYLKO W JEDNĄ STRONĘ
 *
 * To jest **cała** zasada bezpieczeństwa tego modułu, nie jedna z wielu.
 *
 * Datagram przychodzący od odbiorcy służy **wyłącznie** do tego, żeby poznać jego adres
 * — treść jest **wyrzucana bez oglądania**. Nie ma tu ścieżki, którą cokolwiek z dołu
 * mogłoby dojść do kontrolera lotu. Nawet gdyby stacja wysłała poprawną ramkę `COMMAND_LONG`
 * z rozbrojeniem, skończy ona w koszu tej metody.
 *
 * Dzięki temu udostępnienie telemetrii **nie jest** przekazaniem władzy: widz dostaje
 * obraz sytuacji, a nie wpływ na nią (dok/WLADZA.md).
 *
 * ### Dlaczego odbiorcy się zgłaszają, zamiast być wpisani na listę
 *
 * Bo dokładnie tak zachowuje się jednostka naziemna i tak samo pyta o dane stacja:
 * wysyła pusty datagram pod `host:port`, żeby otworzyć drogę powrotną, i od tej chwili
 * słucha (`server/telemetria.mjs`, `_zaczepka()`). Powielenie tego zachowania znaczy,
 * że **po stronie stacji nie trzeba zmieniać ani linijki** — wystarczy przestawić adres
 * z `192.168.144.12` na adres aparatury.
 *
 * Odbiorca, który milczy dłużej niż [WAZNOSC_MS], wypada z listy sam. Nie ma
 * wypisywania się ani sprzątania po kimś, kto odszedł bez pożegnania.
 */
class RozglosTelemetrii(private val port: Int = DOMYSLNY_PORT) {

    /** Adres odbiorcy → chwila ostatniego znaku życia. */
    private val odbiorcy = ConcurrentHashMap<Adres, Long>()

    @Volatile
    private var gniazdo: DatagramSocket? = null

    @Volatile
    private var watek: Thread? = null

    @Volatile
    var dziala: Boolean = false
        private set

    private var wyslanych: Long = 0
    private var ostatniMeldunek: Long = 0

    data class Adres(val host: InetAddress, val port: Int)

    /** Ilu odbiorców jest teraz podłączonych. Do pokazania na ekranie diagnostyki. */
    fun ilu(): Int {
        posprzataj()
        return odbiorcy.size
    }

    /** Krótki opis odbiorców — adresy bez portów, bo port źródłowy nic nie mówi. */
    fun opis(): String {
        posprzataj()
        if (odbiorcy.isEmpty()) return "brak odbiorców"
        return odbiorcy.keys.joinToString(", ") { it.host.hostAddress ?: "?" }
    }

    fun start() {
        if (watek?.isAlive == true) return
        val t = Thread({ petla() }, "telemetria-rozglos").apply { isDaemon = true }
        watek = t
        t.start()
    }

    fun stop() {
        dziala = false
        watek?.interrupt()
        watek = null
        gniazdo?.close()
        gniazdo = null
        odbiorcy.clear()
    }

    /**
     * Rozsyła surowy kawałek strumienia, dokładnie w postaci, w jakiej przyszedł
     * z jednostki naziemnej.
     *
     * Świadomie **nie** sklejamy ramek przed wysłaniem. Jednostka naziemna tnie MAVLink
     * co 115 bajtów w środku ramek (CLAUDE.md poz. 57), a każdy odbiorca i tak ma własną
     * sklejarkę — stacja robi to w `parsujRamki()`. Przepuszczanie bajtów bez zmian
     * znaczy, że rozgałęźnik nie ma jak niczego zgubić ani przekłamać.
     */
    fun rozeslij(dane: ByteArray, dlugosc: Int) {
        val g = gniazdo ?: return
        if (dlugosc <= 0 || odbiorcy.isEmpty()) return
        posprzataj()
        for (a in odbiorcy.keys) {
            try {
                g.send(DatagramPacket(dane, dlugosc, a.host, a.port))
            } catch (e: Exception) {
                // Odbiorca, do którego nie da się wysłać, znika przy najbliższym
                // sprzątaniu — nie ma powodu hałasować przy każdym pakiecie.
                odbiorcy.remove(a)
                Dziennik.ostrzezenie("rozglos", "odbiorca ${a.host.hostAddress} odpadł", e)
            }
        }
        wyslanych += dlugosc.toLong()
        meldunek()
    }

    private fun petla() {
        try {
            val g = DatagramSocket(port).apply { soTimeout = 500 }
            // Bez przypięcia do eth0 Android z włączonym Wi-Fi odmawia wysyłki
            // do sieci pokładowej — ta sama pułapka co w LaczeMavlink.
            SiecPokladowa.zwiaz(g)
            gniazdo = g
            dziala = true
            Dziennik.info("rozglos", "rozgałęźnik telemetrii na porcie $port")

            val bufor = ByteArray(2048)
            while (!Thread.currentThread().isInterrupted) {
                val paczka = DatagramPacket(bufor, bufor.size)
                try {
                    g.receive(paczka)
                } catch (_: SocketTimeoutException) {
                    posprzataj()
                    continue
                }
                // ⛔ TREŚĆ IDZIE DO KOSZA. Bierzemy z tego datagramu wyłącznie adres
                // nadawcy. To jest to miejsce, w którym kończy się każda próba
                // wysłania czegokolwiek do maszyny od strony odbiorców.
                val a = Adres(paczka.address, paczka.port)
                if (odbiorcy.put(a, System.currentTimeMillis()) == null) {
                    Dziennik.info("rozglos", "nowy odbiorca telemetrii: ${a.host.hostAddress}:${a.port}")
                }
            }
        } catch (e: Exception) {
            // Zajęty port albo brak uprawnień nie mogą zabrać kokpitowi telemetrii —
            // rozgałęźnik jest dodatkiem, nie warunkiem działania.
            dziala = false
            Dziennik.ostrzezenie("rozglos", "rozgałęźnik nie wstał (port $port) — kokpit działa dalej", e)
        } finally {
            dziala = false
            gniazdo?.close()
            gniazdo = null
        }
    }

    private fun posprzataj() {
        val granica = System.currentTimeMillis() - WAZNOSC_MS
        val it = odbiorcy.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value < granica) {
                it.remove()
                Dziennik.info("rozglos", "odbiorca ${e.key.host.hostAddress} zamilkł — wypisany")
            }
        }
    }

    private fun meldunek() {
        val teraz = System.currentTimeMillis()
        if (teraz - ostatniMeldunek < MELDUNEK_MS) return
        ostatniMeldunek = teraz
        if (odbiorcy.isNotEmpty()) {
            Dziennik.info("rozglos", "${odbiorcy.size} odbiorców, ${wyslanych / 1024} kB rozesłane")
        }
    }

    companion object {
        /**
         * Ten sam numer, co u jednostki naziemnej — celowo. Dla odbiorcy zmienia się
         * wtedy wyłącznie adres, a nie sposób pytania.
         */
        const val DOMYSLNY_PORT = 19856

        /** Po tylu milisekundach ciszy odbiorca wypada z listy. */
        private const val WAZNOSC_MS = 15_000L

        /** Jak często meldować w dzienniku, żeby nie zalać go przy 65 ramkach na sekundę. */
        private const val MELDUNEK_MS = 30_000L
    }
}
