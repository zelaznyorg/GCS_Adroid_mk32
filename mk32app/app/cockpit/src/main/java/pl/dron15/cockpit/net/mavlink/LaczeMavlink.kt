package pl.dron15.cockpit.net.mavlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.dron15.cockpit.diag.Dziennik
import pl.dron15.cockpit.net.SiecPokladowa
import pl.dron15.cockpit.domain.SilnikStanu
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.coroutineContext

/**
 * Łącze telemetryczne do jednostki naziemnej MK32 (UDP 192.168.144.12:19856).
 *
 * Jednostka naziemna pracuje jako serwer, więc najpierw musimy się odezwać — stąd heartbeat
 * co sekundę. Warunek po stronie aparatury: SIYI TX → Datalink → Connection = UDP,
 * Flight Controller = PX4/ArduPilot, 115200 (dok/INTERFEJSY.md, sekcja 1).
 */
class LaczeMavlink(
    private val silnik: SilnikStanu,
    private val host: String = DOMYSLNY_HOST,
    private val port: Int = DOMYSLNY_PORT,
) {
    private var zadanie: Job? = null

    @Volatile
    private var gniazdo: DatagramSocket? = null

    /**
     * Kolejka wysyłkowa — jeden wątek, w kolejności nadania.
     *
     * Komendy operatora (RTL, LAND, zmiana trybu, misje) wychodzą z kompozycji, czyli
     * z wątku głównego, a tam Android zabija każdą operację sieciową
     * (`NetworkOnMainThreadException`, zaobserwowane 2026-08-26). Ta sama wada co
     * w [pl.dron15.cockpit.net.siyi.KlientSiyi] — i to samo lekarstwo.
     */
    private var nadajnik: ExecutorService = nowyNadajnik()

    /**
     * Podgląd surowych ramek. Wpina się w to [TransferMisji] — protokół misji jest
     * sterowany przez maszynę, więc musi widzieć strumień, a nie tylko gotowy stan.
     */
    var naRamke: ((Mavlink.Ramka) -> Unit)? = null

    /** Wymiana misji. Dostępna po [start], bo potrzebuje zakresu korutyn. */
    var misje: TransferMisji? = null
        private set

    /**
     * Rozgałęźnik telemetrii dla stacji podglądu i innych odbiorców.
     *
     * Jednostka naziemna obsługuje jednego klienta, więc drugi odbiorca nie dokłada się
     * do strumienia, tylko **zabiera go kokpitowi**. Rozgałęzienie robi więc ta strona,
     * która i tak trzyma jedyne łącze. Ruch idzie wyłącznie w dół — patrz
     * [RozglosTelemetrii].
     */
    val rozglos = RozglosTelemetrii()

    fun start(zakres: CoroutineScope) {
        if (zadanie?.isActive == true) return
        if (nadajnik.isShutdown) nadajnik = nowyNadajnik()
        misje = TransferMisji(this, zakres).also { t -> naRamke = { r -> t.obsluz(r) } }
        // Uchwyt wyjątków jest tu warunkiem, nie ozdobą: bez niego błąd w tej korutynie
        // wywraca CAŁĄ aplikację, zamiast zabrać samą telemetrię (dok/ARCHITEKTURA.md).
        // Bez tego wpisu nie da sie odroznic "symulator na biurku" od "zywa maszyna".
        // 2026-08-28 kosztowalo to kilkanascie minut analizy danych z prawdziwego drona
        // w przekonaniu, ze to symulator: emulator routuje przez laptop, a laptop mial
        // dostep do sieci pokladowej, wiec domyslny adres 192.168.144.12 zadzialal.
        Dziennik.info("telemetria", "nasluch $host:$port")
        rozglos.start()
        zadanie = zakres.launch(Dispatchers.IO + Dziennik.uchwytKorutyny("telemetria")) { petla() }
        zamowPrzyrzady(zakres)
    }

    fun stop() {
        rozglos.stop()
        zadanie?.cancel()
        nadajnik.shutdownNow()
        gniazdo?.close()
        gniazdo = null
    }

    private fun nowyNadajnik(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "telemetria-tx").apply { isDaemon = true }
        }

    /**
     * Komendy do kontrolera lotu. Wysyłamy tylko na wyraźną akcję operatora.
     * Parametrów nie zapisujemy nigdy — od tego jest tools/fc_write_params.py z logiem.
     */
    fun wyslij(ramka: ByteArray) {
        val n = nadajnik
        if (n.isShutdown) return
        try {
            n.execute {
                val g = gniazdo ?: return@execute
                try {
                    g.send(DatagramPacket(ramka, ramka.size, InetAddress.getByName(host), port))
                } catch (e: Exception) {
                    Dziennik.ostrzezenie("telemetria", "nie udało się wysłać ramki", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Kolejka już zamykana — nie ma czego wysyłać.
        }
    }

    /**
     * Pobranie wskazanych parametrów, z ponawianiem tych, które nie wróciły.
     *
     * Ponawianie nie jest ostrożnością na wyrost: przy wgrywaniu pliku parametrów pięć wartości
     * przepadło kiedyś po cichu (poz. 22 w CLAUDE.md). Zakładamy, że pojedyncze zapytanie
     * ma prawo zginąć, i pytamy dopóki nie dostaniemy odpowiedzi.
     */
    fun pobierzParametry(zakres: CoroutineScope, nazwy: List<String>, prob: Int = 5) {
        zakres.launch(Dispatchers.IO) {
            repeat(prob) { proba ->
                val brakujace = nazwy.filter { it !in silnik.stan.value.parametry }
                if (brakujace.isEmpty()) {
                    if (proba > 0) silnik.dopiszKomunikat("Parametry: komplet (${nazwy.size})")
                    return@launch
                }
                for (nazwa in brakujace) {
                    wyslij(Mavlink.zadanieParametru(nazwa))
                    delay(30)          // 115 200 baud dzielone z telemetrią — nie zalewamy łącza
                }
                delay(1500)
            }
            val brak = nazwy.filter { it !in silnik.stan.value.parametry }
            if (brak.isNotEmpty()) {
                silnik.dopiszKomunikat("Parametry bez odpowiedzi: ${brak.joinToString(", ")}", waga = 4)
            }
        }
    }

    /**
     * Zapis jednego parametru do maszyny — **z ekranu PRZED LOTEM i tylko stamtąd**.
     *
     * ### Co się tu zmieniło i dlaczego
     *
     * Do 2026-08-26 kokpit nie zapisywał parametrów w ogóle; od tego było
     * `tools/fc_write_params.py` z logiem. Zasada była dobra, ale w polu okazała się
     * niewykonalna: żeby poprawić jedną liczbę, którą checklista sama wskazała jako złą,
     * trzeba było wyjąć laptop, a przy wpiętym USB aparatura traci sieć pokładową
     * (patrz `mk32app/dok/`). Operator stał więc przed wyborem: lecieć z ostrzeżeniem
     * albo zwijać stanowisko.
     *
     * Dlatego zapis wchodzi do kokpitu — ale z zawężeniami, które zostawiają go
     * przewidywalnym:
     *
     * 1. **tylko parametry wskazane przez checklistę**, z wartością z reguły, nigdy
     *    z pola tekstowego (patrz [pl.dron15.cockpit.domain.Poprawka]);
     * 2. **nigdy przy uzbrojonej maszynie** — parametr zmieniony w locie to ostatnia
     *    rzecz, jakiej ktokolwiek potrzebuje;
     * 3. **potwierdzenie odczytem, nie założeniem** — maszyna odsyła `PARAM_VALUE`
     *    z wartością po zapisie i dopiero to uznajemy za sukces. To nie jest ostrożność
     *    teoretyczna: przy wgrywaniu pliku parametrów pięć wartości przepadło kiedyś
     *    po cichu (poz. 22 w CLAUDE.md), a dzisiejsze łącze zgubiło odpowiedź przy
     *    pierwszym z dwóch odczytów tego samego parametru;
     * 4. **każda próba i każdy wynik trafiają do dziennika** — z ekranu DIAGNOSTYKA
     *    widać potem, kto i co zmienił.
     */
    fun zapiszParametr(zakres: CoroutineScope, nazwa: String, wartosc: Float) {
        if (silnik.stan.value.uzbrojony) {
            Dziennik.ostrzezenie("parametry", "odmowa zapisu $nazwa — maszyna uzbrojona")
            silnik.dopiszKomunikat("Zapis $nazwa odrzucony: maszyna uzbrojona", waga = 4)
            return
        }
        Dziennik.info("parametry", "zapis $nazwa = $wartosc")
        silnik.dopiszKomunikat("Zapis $nazwa = ${bezOgona(wartosc)}…")
        zakres.launch(Dispatchers.IO + Dziennik.uchwytKorutyny("parametry")) {
            repeat(PROB_ZAPISU) {
                wyslij(Mavlink.zapisParametru(nazwa, wartosc))
                delay(400)
                wyslij(Mavlink.zadanieParametru(nazwa))
                delay(600)
                val odczyt = silnik.stan.value.parametry[nazwa]
                if (odczyt != null && kotlin.math.abs(odczyt - wartosc) <= 0.001f) {
                    Dziennik.info("parametry", "$nazwa potwierdzone = $odczyt")
                    silnik.dopiszKomunikat("$nazwa = ${bezOgona(odczyt)} potwierdzone")
                    return@launch
                }
            }
            Dziennik.blad("parametry", "zapis $nazwa = $wartosc bez potwierdzenia")
            silnik.dopiszKomunikat("$nazwa: brak potwierdzenia zapisu", waga = 4)
        }
    }

    private fun bezOgona(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else "%.3f".format(v)

    /**
     * Komendy lotu. Każda zapisuje się w stanie, żeby ekran mógł pokazać, co maszyna
     * odpowiedziała — bez tego przycisk jest obietnicą bez pokrycia (dok/UI.md).
     */
    fun powrotDoStartu() {
        silnik.zapiszKomende(Mavlink.CMD_POWROT_DO_STARTU, "RTL")
        wyslij(Mavlink.komenda(Mavlink.CMD_POWROT_DO_STARTU))
    }

    fun ladowanie() {
        silnik.zapiszKomende(Mavlink.CMD_LADOWANIE, "LĄDUJ")
        wyslij(Mavlink.komenda(Mavlink.CMD_LADOWANIE))
    }

    /** Przerwanie automatu: wyjście z AUTO/RTL do trybu trzymającego pozycję. */
    fun ustawTryb(numerTrybu: Int, nazwa: String) {
        silnik.zapiszKomende(Mavlink.CMD_USTAW_TRYB, nazwa)
        wyslij(Mavlink.ustawTryb(numerTrybu))
    }

    /** Skok do wskazanego punktu trwającej misji (§5 przekazania, tryb LEĆ). */
    fun skokDoPunktu(numer: Int) {
        silnik.zapiszKomende(Mavlink.CMD_USTAW_BIEZACY_PUNKT, "SKOK $numer")
        wyslij(Mavlink.komenda(Mavlink.CMD_USTAW_BIEZACY_PUNKT, p1 = numer.toFloat()))
    }

    /** Pauza i wznowienie misji. `p1 = 0` wstrzymuje, `1` wznawia. */
    fun pauzaMisji(wstrzymaj: Boolean) {
        silnik.zapiszKomende(Mavlink.CMD_PAUZA, if (wstrzymaj) "PAUZA" else "WZNÓW")
        wyslij(Mavlink.komenda(Mavlink.CMD_PAUZA, p1 = if (wstrzymaj) 0f else 1f))
    }

    /**
     * Prośba o wiadomości, których ArduPilot domyślnie nadaje rzadko albo wcale.
     *
     * Powtarzana, bo pojedyncza komenda ma prawo zginąć w UDP, a maszyna po restarcie
     * wraca do własnych stawek. Nie zmienia **żadnego parametru maszyny** — patrz
     * [Mavlink.zadanieInterwalu] i zastrzeżenie o `SERIAL6_OPTIONS = 4096`.
     */
    fun zamowPrzyrzady(zakres: CoroutineScope) {
        zakres.launch(Dispatchers.IO + Dziennik.uchwytKorutyny("telemetria")) {
            val zamowienie = listOf(
                Mavlink.SERVO_OUTPUT_RAW to 200,   // 5 Hz — zapas ciągu i rozrzut
                Mavlink.VIBRATION to 1000,         // 1 Hz — wibracje zmieniają się wolno
                Mavlink.HOME_POSITION to 5000,     // rzadko; interesuje nas sama zmiana
                Mavlink.NAV_CONTROLLER_OUTPUT to 500,  // 2 Hz — cel automatu
                Mavlink.FENCE_STATUS to 2000,      // naruszenie, nie zapas
            )
            repeat(POWTORZEN_ZAMOWIENIA) {
                for ((msgid, okres) in zamowienie) {
                    wyslij(Mavlink.zadanieInterwalu(msgid, okres))
                    delay(60)
                }
                delay(20_000)
            }
        }
    }

    fun kontrolaPrzedlotowa() {
        silnik.zapiszKomende(Mavlink.CMD_KONTROLA_PRZEDLOTOWA, "PREARM")
        wyslij(Mavlink.komenda(Mavlink.CMD_KONTROLA_PRZEDLOTOWA, p1 = 1f))
    }

    private suspend fun petla() {
        val bufor = ByteArray(4096)
        // Sklejarka strumienia. Jednostka naziemna tnie MAVLink na kawałki po 115 bajtów
        // bez oglądania się na granice ramek (pomiar 2026-08-26), więc ogon nierozebrany
        // w tym datagramie musi doczekać następnego — patrz Mavlink.skanujStrumien.
        val strumien = ByteArray(POJEMNOSC_SKLEJARKI)
        var wStrumieniu = 0
        while (coroutineContext.isActive) {
            var g: DatagramSocket? = null
            try {
                g = DatagramSocket().apply { soTimeout = 500 }
                // Bez tego Android przy włączonym Wi-Fi blokuje ruch do sieci pokładowej.
                SiecPokladowa.zwiaz(g)
                gniazdo = g
                silnik.dopiszKomunikat("Lacze: nasluch $host:$port")
                var ostatniHeartbeat = 0L
                while (coroutineContext.isActive) {
                    val teraz = System.currentTimeMillis()
                    if (teraz - ostatniHeartbeat >= 1000) {
                        wyslij(Mavlink.heartbeat())
                        ostatniHeartbeat = teraz
                    }
                    val paczka = DatagramPacket(bufor, bufor.size)
                    try {
                        g.receive(paczka)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val odebrano = System.currentTimeMillis()
                    // Kopia dla odbiorców z dołu — surowa, przed sklejaniem. Każdy z nich
                    // ma własną sklejarkę, więc przepuszczamy bajty bez zmian: rozgałęźnik
                    // nie ma wtedy jak niczego zgubić ani przekłamać.
                    rozglos.rozeslij(paczka.data, paczka.length)
                    // Ogon dłuższy niż bufor znaczy strumień bez ani jednej rozpoznanej
                    // ramki — wtedy trzymanie go dalej nic nie da, zaczynamy od czysta.
                    if (wStrumieniu + paczka.length > strumien.size) wStrumieniu = 0
                    System.arraycopy(paczka.data, 0, strumien, wStrumieniu, paczka.length)
                    wStrumieniu += paczka.length

                    val wynik = Mavlink.skanujStrumien(strumien, wStrumieniu)
                    for (ramka in wynik.ramki) {
                        silnik.zastosuj(ramka, odebrano)
                        naRamke?.invoke(ramka)
                    }
                    val ogon = wStrumieniu - wynik.zuzyte
                    if (ogon > 0) System.arraycopy(strumien, wynik.zuzyte, strumien, 0, ogon)
                    wStrumieniu = ogon
                }
            } catch (e: Exception) {
                Dziennik.blad("telemetria", "łącze przerwane — ponawiam za 2 s", e)
                silnik.dopiszKomunikat("Lacze przerwane: ${e.message}", waga = 4)
                delay(2000)
            } finally {
                g?.close()
                gniazdo = null
            }
        }
    }

    companion object {
        const val DOMYSLNY_HOST = "192.168.144.12"
        const val DOMYSLNY_PORT = 19856

        /** Ile razy ponawiamy zamówienie strumieni — ok. 20 s odstępu, czyli 10 minut. */
        private const val POWTORZEN_ZAMOWIENIA = 30

        /** Ile razy ponawiamy zapis parametru, zanim uznamy go za niepotwierdzony. */
        private const val PROB_ZAPISU = 4

        /** Bufor sklejania strumienia — z zapasem na najdłuższą ramkę i kilka kawałków. */
        private const val POJEMNOSC_SKLEJARKI = 8192
    }
}
