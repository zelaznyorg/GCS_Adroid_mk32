package pl.dron15.cockpit.video

import android.content.Context
import pl.dron15.cockpit.diag.Dziennik

/**
 * Obraz z ZR30 **dwiema drogami naraz**: podstawową i zapasową, na jednym rysowniku.
 *
 * ### Po co
 *
 * Tor natywny SIYI (TCP 37256) daje lepszy obraz niż RTSP, ale **kamera obsługuje na nim
 * dokładnie jednego klienta**. Gdy to miejsce zajmuje ktoś inny, nasze połączenie zostaje
 * przyjęte i **nie przychodzi nic** — żadnego błędu, po prostu cisza.
 *
 * Najczęstszy zajmujący to fabryczna aplikacja SIYI FPV. Zmierzone 2026-08-28: trzyma
 * połączenie także **w tle**, więc samo przełączenie się na kokpit nie zwalnia kamery —
 * dopiero zamknięcie tamtej aplikacji. Przez pół godziny braliśmy to za awarię kamery,
 * bo objaw jest identyczny; obalił to jeden pomiar — po zatrzymaniu SIYI FPV zwykłe
 * połączenie natychmiast dostało 977 kB obrazu w 5 s.
 *
 * RTSP tego ograniczenia nie ma. Bez tej klasy wybór brzmiałby więc: albo lepszy obraz
 * i ryzyko lotu bez obrazu, albo gorszy obraz i spokój. Z nią kokpit bierze lepszy tor,
 * a gdy ten milczy — **sam schodzi na RTSP** i mówi o tym w dzienniku.
 *
 * ### Jeden rysownik, dwa źródła
 *
 * Kluczowe i wynikające z drogiego doświadczenia: **widok się nie zmienia**. Wymiana
 * widoku przy przełączaniu kosztowała nas 2026-08-26 czarny kadr i zawieszenie aplikacji
 * (patrz [OdtwarzaczVlc.widok]). Tutaj [RysownikH264] powstaje raz, oba źródła dostają
 * go w konstruktorze, a przełączenie to tylko zmiana tego, kto go karmi.
 *
 * ### Kiedy schodzimy na zapasowy
 *
 * Po [PROG_MILCZENIA_MS] bez ani jednej klatki na ekranie. **Nie po zerwaniu połączenia** —
 * to za mało, bo objawem zajętej kamery jest właśnie przyjęte połączenie i cisza.
 *
 * Zejście jest **jednokierunkowe**. Wracanie na tor natywny „na próbę" oznaczałoby
 * mruganie obrazu co kilkanaście sekund, dopóki kamera jest zajęta — a w locie gorsze
 * od słabszego obrazu jest tylko obraz, który znika i wraca.
 */
class TorZZapasem(
    host: String,
    private val zaczynajOdSiyi: Boolean,
    private val zZapasem: Boolean = true,
) : TorWideo {

    private val rysownik = RysownikH264("obraz")
    private val siyi = OdtwarzaczSiyi(host, rysownik)
    private val rtsp = OdtwarzaczRtsp(host, rysownik = rysownik)

    /** Wołane, gdy tor sam zszedł na zapasowy — kokpit robi z tego komunikat dla pilota. */
    var przyZejsciu: ((String) -> Unit)? = null

    @Volatile
    private var naSiyi = zaczynajOdSiyi

    @Volatile
    private var zamkniety = false

    /** Czy tor wskazał operator. Wtedy nie schodzimy samoczynnie. */
    @Volatile
    private var recznie = false

    @Volatile
    private var ostatniaZmianaNs = 0L
    private var stroz: Thread? = null

    override var przyStanie: ((Boolean) -> Unit)?
        get() = rysownik.przyStanie
        set(v) {
            rysownik.przyStanie = v
        }

    init {
        // ⚠ MUSI być po zbudowaniu obu źródeł — każde z nich przypisuje sobie ten sam
        // uchwyt w swoim `init`. O powierzchni decyduje jedno miejsce: to.
        rysownik.przyPowierzchni = { jest ->
            powierzchniaDoCzynnego(jest)
            if (jest) zacznijPilnowac()
        }
        Dziennik.info(
            "wideo",
            "tor obrazu: ${if (naSiyi) "SIYI 37256" else "RTSP 8554"}" +
                if (zZapasem && naSiyi) ", zapas RTSP po ${PROG_MILCZENIA_MS / 1000} s ciszy" else "",
        )
    }

    override fun widok(kontekst: Context) = rysownik.widok(kontekst)

    override fun zapewnijOdtwarzanie() {
        if (naSiyi) siyi.zapewnijOdtwarzanie() else rtsp.zapewnijOdtwarzanie()
    }

    /** Czy obraz idzie torem natywnym SIYI. Do pokazania w panelu STRUMIEŃ. */
    val naTorzeSiyi: Boolean get() = naSiyi

    /**
     * Ręczne przełączenie toru z ekranu KAMERA. Odstawia dotychczasowe źródło i uruchamia
     * drugie na **tym samym rysowniku** — widok się nie zmienia, więc nie ma czarnego kadru.
     *
     * Wybór ręczny **wyłącza samoczynne zejście**: skoro operator wskazał tor świadomie,
     * aplikacja nie ma go po cichu zmieniać.
     */
    fun przelacz(naSiyiTeraz: Boolean) {
        if (naSiyiTeraz == naSiyi || zamkniety) return

        // ⛔⛔ KAMERA ŹLE ZNOSI PONOWNE ŁĄCZENIE NA PORCIE 37256.
        //
        // Zmierzone 2026-08-28, dwa razy tego samego popołudnia:
        //
        // - powrót na SIYI **6 s** po odejściu — zadziałał, obraz po 0,6 s;
        // - powrót **4 s** po odejściu — `Read timed out`, obrazu nie ma;
        // - po tej serii port przestał nadawać **komukolwiek**, także po powitaniu
        //   i z laptopa, przy działającym bez zarzutu RTSP;
        // - wcześniej, przy **trzech zmianach w pięć sekund**, kamera przestała
        //   nasłuchiwać na **wszystkich** portach naraz (8554, 37256, SDK 37260),
        //   odpowiadając już tylko na ping — odratował ją dopiero cykl zasilania.
        //
        // Stąd odstęp liczony w dziesiątkach sekund, nie w sekundach. To nie jest
        // ostrożność teoretyczna: koszt pomyłki to utrata strumienia natywnego do końca
        // lotu, a w gorszym przypadku także sterowania głowicą.
        val teraz = System.nanoTime()
        val zostalo = (ODSTEP_ZMIAN_NS - (teraz - ostatniaZmianaNs)) / 1_000_000_000
        if (ostatniaZmianaNs != 0L && zostalo > 0) {
            Dziennik.ostrzezenie(
                "wideo",
                "zmiana toru zignorowana — kamera źle znosi częste przełączanie, " +
                    "odczekaj jeszcze $zostalo s",
            )
            przyZejsciu?.invoke("Zmiana toru: odczekaj $zostalo s")
            return
        }
        ostatniaZmianaNs = teraz
        recznie = true
        naSiyi = naSiyiTeraz

        // ⛔ Nie na wątku ekranu. [OdtwarzaczSiyi.wstrzymaj] czeka, aż stary wątek zejdzie
        // (do 1,5 s) — na wątku głównym byłoby to zamrożenie interfejsu, a przy dłuższym
        // czekaniu ANR. Klawisz ma odpowiadać od razu, przełączenie dzieje się obok.
        Thread({
            stroz?.interrupt()
            if (naSiyiTeraz) rtsp.wstrzymaj() else siyi.wstrzymaj()
            if (zamkniety) return@Thread
            Dziennik.info(
                "wideo",
                "tor obrazu przełączony ręcznie na ${if (naSiyiTeraz) "SIYI 37256" else "RTSP 8554"}",
            )
            powierzchniaDoCzynnego(true)
        }, "wideo-zmiana").apply { isDaemon = true; start() }
    }

    override fun zwolnij() {
        zamkniety = true
        stroz?.interrupt()
        siyi.zwolnij()
        rtsp.zwolnij()
    }

    private fun zacznijPilnowac() {
        if (!zZapasem || recznie || !naSiyi || zamkniety || stroz?.isAlive == true) return
        stroz = Thread({ pilnuj() }, "wideo-zapas").apply { isDaemon = true; start() }
    }

    private fun pilnuj() {
        val poczatek = System.nanoTime()
        try {
            while (!zamkniety && naSiyi && !recznie) {
                Thread.sleep(500)
                val ostatnia = rysownik.ostatniaKlatkaNs
                // Dopóki nie było ANI JEDNEJ klatki, liczymy od uruchomienia — inaczej
                // kamera zajęta od samego początku nigdy nie wyzwoliłaby zejścia.
                val odniesienie = if (ostatnia == 0L) poczatek else ostatnia
                if ((System.nanoTime() - odniesienie) / 1_000_000 < PROG_MILCZENIA_MS) continue

                val powod = if (ostatnia == 0L) "nie dał ani jednej klatki" else "zamilkł"
                Dziennik.ostrzezenie(
                    "wideo",
                    "tor SIYI $powod przez ${PROG_MILCZENIA_MS / 1000} s — przechodzę na RTSP. " +
                        "Dwie znane przyczyny: (1) kamerę trzyma inny klient — fabryczna " +
                        "aplikacja SIYI FPV zajmuje ją także w tle; (2) port zaciął się po " +
                        "ponownym łączeniu i wraca dopiero po cyklu zasilania kamery. " +
                        "RTSP działa w obu przypadkach.",
                )
                naSiyi = false
                siyi.wstrzymaj()
                rtsp.naPowierzchnie(true)
                przyZejsciu?.invoke("Obraz: przejscie na RTSP")
                return
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun powierzchniaDoCzynnego(jest: Boolean) {
        if (naSiyi) siyi.naPowierzchnie(jest) else rtsp.naPowierzchnie(jest)
    }

    companion object {
        /**
         * Ile ciszy wystarczy, żeby zejść na tor zapasowy.
         *
         * Dwanaście sekund to kompromis: dłużej niż najdłuższa zmierzona przerwa w obrazie
         * (0,4 s) i dłużej niż pełne ponowne łączenie z ponowieniami (ok. 7 s), a wciąż
         * krótko na tyle, żeby przed startem operator zobaczył obraz bez czekania.
         */
        const val PROG_MILCZENIA_MS = 12_000L

        /**
         * Najkrótszy dopuszczalny odstęp między ręcznymi zmianami toru — **30 s**.
         *
         * Wartość dobrana z zapasem, nie zmierzona: wiemy, że 4 s to za mało, a 6 s raz
         * wystarczyło. Zmierzenie prawdziwego progu wymagałoby wielokrotnego doprowadzania
         * kamery do zawieszenia i cyklowania zasilaniem po każdej próbie, a to sprzęt
         * na pokładzie maszyny z podpiętym pakietem. Do czasu takiego pomiaru obowiązuje
         * zapas.
         */
        private const val ODSTEP_ZMIAN_NS = 30_000_000_000L
    }
}
