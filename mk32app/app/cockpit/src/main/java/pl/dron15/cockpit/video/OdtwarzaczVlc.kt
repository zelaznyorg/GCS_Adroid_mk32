package pl.dron15.cockpit.video

import android.content.Context
import android.view.ViewGroup
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import pl.dron15.cockpit.diag.Dziennik
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Obraz z ZR30 po RTSP.
 *
 * libVLC, nie ExoPlayer — kamera domyślnie nadaje H.265, a RTSP z HEVC w ExoPlayerze bywa
 * zawodne (dok/WIDEO.md). Opcje dobrane pod małe opóźnienie; te same trafiają do ffmpeg
 * w narzedzia/restream.py, żeby zachowanie było przewidywalne po obu stronach.
 *
 * ### ⛔ Nic tu nie wolno robić na wątku głównym
 *
 * Zmierzone na MK32 2026-08-26: uruchomienie strumienia na nieosiągalnym adresie RTSP
 * **zamrażało kokpit na 2,5 s** (`Choreographer: Skipped 149 frames`). Wychodziło to przy
 * każdym wejściu na ekran LOT, bo `AndroidView.factory` wykonuje się w kompozycji, czyli
 * na wątku głównym. Ekrany bez obrazu przełączały się w tym samym czasie bez jednego
 * zgubionego kadru.
 *
 * **W locie to jest wada bezpieczeństwa, nie wygody**: gdy łącze z głowicą pada w powietrzu
 * — a wiadomo, że potrafi (`CLAUDE.md`, poz. 28) — kokpit zamierałby co kilkanaście sekund
 * na ponad dwie sekundy. Dlatego wszystkie polecenia do libVLC idą na własny wątek,
 * a wątek główny co najwyżej podpina widok.
 */
class OdtwarzaczVlc(context: Context) : TorWideo {

    /**
     * ### Bufor, zegar i transport — cały zestaw dobrany pomiarem
     *
     * Stan końcowy po dochodzeniu 2026-08-28: **UDP, bufor 150 ms, `--clock-jitter=0`,
     * dekoder sprzętowy** (ten ostatni przy [Media] niżej). W tym układzie licznik
     * `Buffering 0%` — czyli zacięć obrazu — wynosi **zero** w dwóch pomiarach po 45 s.
     *
     * Droga do tego wiodła przez kilka obalonych hipotez i warto je znać, żeby nie
     * powtarzać:
     *
     * ```
     * H.265 + sprzętowy + TCP + 150 ms            15 zacięć/min   (stan wyjściowy)
     * H.265 + sprzętowy + UDP + 300 ms            19
     * H.265 + programowy + TCP + 300 ms            8
     * H.265 + programowy + TCP + 700 ms           10   <- większy bufor NIE pomaga
     * H.264 + sprzętowy + TCP + 300 ms            19
     * H.264 + programowy + TCP + 300 ms       19, 14
     * H.264 + 960x540 + programowy                22   <- mniejszy strumień NIE pomaga
     * H.264 + sprzętowy + UDP + 300 ms         11, 5, 6
     * + ograniczenie odświeżania HUD do 15 Hz       4   <- patrz MainActivity
     * + bufor 150 ms i --clock-jitter=0          0, 0   <- TO JEST TEN UKŁAD
     * ```
     *
     * Dwa wnioski, które łatwo pomylić:
     *
     * **Zacięcia nie brały się z pasma.** Kamera nadaje bez zarzutu — 901 klatek w 30 s,
     * zero przerw > 100 ms, mierzone prosto z niej z pominięciem radia. Fabryczna
     * aplikacja SIYI FPV pokazywała ten sam strumień płynnie, co wykluczyło radio.
     *
     * **Największą pojedynczą poprawę dało zdjęcie obciążenia z interfejsu**, nie zmiany
     * w odtwarzaczu: HUD przebudowywany 65 razy na sekundę zabierał wątek główny
     * (`Slow UI thread` w 505 z 723 klatek) i pod nieplynnym interfejsem obraz nie miał
     * jak płynąć.
     *
     * ⚠ `--clock-jitter=0` znaczy „nie resetuj zegara, gdy PCR się spóźni". Przy 150 ms
     * bufora jest to warunek, nie ozdoba: bez tego każde spóźnienie ok. 90 ms — a takie
     * na tym łączu bywają — wywracałoby odtwarzanie w przebudowę bufora.
     */
    private val libVlc = LibVLC(
        context, arrayListOf(
            "--network-caching=$BUFOR_MS",
            "--clock-synchro=0",
            "--no-audio",
            "-vv",
        )
    )

    private val odtwarzacz = MediaPlayer(libVlc)

    /** Wywoływane, gdy obraz zaczyna i przestaje płynąć — kokpit robi z tego baner. */
    override var przyStanie: ((Boolean) -> Unit)? = null

    private var adres: String = DOMYSLNY_ADRES

    /**
     * Widok, do którego libVLC rysuje **w tej chwili** — nie sama flaga „podpięte".
     *
     * Rozróżnienie jest tu istotne, bo ekrany LOT i KAMERA mają własne `VLCVideoLayout`,
     * a Compose przy przejściu **najpierw tworzy nowy, a dopiero potem zwalnia stary**.
     * Patrz [odepnij].
     */
    private var podpietyWidok: VLCVideoLayout? = null

    /** Jeden wątek na wszystkie polecenia do libVLC — kolejka, więc się nie wyścigują. */
    private val watek: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wideo").apply { isDaemon = true }
    }

    @Volatile
    private var zamkniety = false

    /** Numer kolejnej nieudanej próby — steruje narastającym odczekaniem. */
    @Volatile
    private var nieudanePodrzad = 0

    /** Ile wyjść obrazu ma libVLC. Zero przy grającym strumieniu = kadr jest ciemny. */
    @Volatile
    private var liczbaVout = 0

    /** Czy strumień jest uruchomiony. Pytamy o to przed powtórnym uruchomieniem. */
    val gra: Boolean get() = !zamkniety && odtwarzacz.isPlaying

    init {
        odtwarzacz.setEventListener { zdarzenie ->
            when (zdarzenie.type) {
                MediaPlayer.Event.Playing -> {
                    nieudanePodrzad = 0
                    przyStanie?.invoke(true)
                }
                MediaPlayer.Event.EncounteredError -> {
                    przyStanie?.invoke(false)
                    wznowZaChwile()
                }
                MediaPlayer.Event.EndReached -> {
                    przyStanie?.invoke(false)
                    wznowZaChwile()
                }
                MediaPlayer.Event.Vout -> liczbaVout = zdarzenie.voutCount
                MediaPlayer.Event.Stopped -> {
                    liczbaVout = 0
                    przyStanie?.invoke(false)
                }
            }
        }
    }

    fun ustawAdres(url: String) {
        adres = url
    }

    /**
     * **Jedyny** widok obrazu w całej aplikacji — tworzony raz i podpinany raz.
     *
     * ### Dlaczego jeden, na zawsze
     *
     * Ekrany LOT, KAMERA, panele i miniatura miały dotąd własne `VLCVideoLayout`,
     * a `when (ekran)` wymienia całe poddrzewo Compose. Każda zmiana zakładki znaczyła
     * więc `detachViews` + `attachViews`, a to okazało się kosztować dwie usterki naraz.
     *
     * **Czarny kadr.** Zmierzone na aparaturze (`logcat`, 2026-08-26 17:32:53): odpięcie
     * widoku w trakcie grania powoduje `killing decoder fourcc 'hevc'`, `destroying
     * useless vout`, usunięcie `gles2` i `egl_android`, a potem zjazd na dekoder
     * programowy. **Wyjście obrazu nie odbudowuje się samo** — procesor stoi na 18 %
     * zamiast kilkuset, a w logu nie ma już żadnego `vout`. Przy tym `isPlaying` zwraca
     * `true` i nie pada żadne zdarzenie błędu, więc ponowienia nie mają się od czego odbić.
     *
     * **Zawieszenie aplikacji.** Zapis ANR z aparatury: `attachViews` czeka na natywny
     * mutex libVLC, ten sam, który wątek `wideo` trzyma przez cały `stop()` i `play()`.
     * Przełączenie ekranu w złej chwili zatrzymywało wątek główny na 19 s i system
     * zamykał aplikację.
     *
     * Przeniesienie `attachViews` na wątek `wideo` **nie jest wyjściem** — sprawdzone
     * na sprzęcie: `Can't create handler inside thread Thread[wideo] that has not called
     * Looper.prepare()`. libVLC dotyka tam widoków, więc musi zostać na wątku głównym.
     *
     * Zostaje jedyna droga, która znosi obie usterki naraz: **nie wymieniać widoku.**
     * Jeden `VLCVideoLayout` wędruje między ekranami — zmienia rodzica, nie tożsamość —
     * więc `attachViews` wykonuje się dokładnie raz, przy pierwszym użyciu.
     *
     * Zmiana rodzica jest bezpieczna, bo naraz obraz pokazuje **tylko jeden** element:
     * albo tło ekranu, albo miniatura. Zdejmujemy widok z poprzedniego rodzica sami,
     * bo Compose tworzy nowe drzewo przed zwolnieniem starego i inaczej doszłoby
     * do „child already has a parent".
     */
    override fun widok(kontekst: Context): android.view.View {
        val w = podpietyWidok ?: VLCVideoLayout(kontekst).also { nowy ->
            podpietyWidok = nowy
            try {
                odtwarzacz.attachViews(nowy, null, false, false)
            } catch (e: Exception) {
                Dziennik.blad("wideo", "nie udało się podpiąć obrazu do widoku", e)
            }
        }
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    /**
     * Zejście ekranu z widoku **nie odpina już obrazu** — widok jest jeden i żyje przez
     * całe życie aplikacji (patrz [widok]). Zostaje wyłącznie po to, żeby wywołanie
     * z `AndroidView.onRelease` miało dokąd trafić.
     */
    @Suppress("UNUSED_PARAMETER")
    fun odepnij(widok: VLCVideoLayout? = null) = Unit

    /**
     * Uruchamia strumień pod wskazanym adresem. **Wraca natychmiast** — robota idzie
     * na wątek `wideo`. To jest jawne polecenie (zmiana adresu, wybór strumienia),
     * więc zaczyna od nowa nawet wtedy, gdy coś już gra.
     */
    fun graj(url: String = adres) {
        adres = url
        nieudanePodrzad = 0
        wykonaj { uruchom(url) }
    }

    /**
     * Włącza strumień **tylko wtedy, gdy nie gra** — tego używa widok obrazu.
     *
     * Do 2026-08-26 wejście na ekran LOT wywoływało `graj()` bezwarunkowo, więc każdy
     * powrót z MISJI czy DIAGNOSTYKI **zrywał działający strumień i budował go od zera**:
     * kilka sekund czarnego kadru przy każdym przełączeniu, mimo sprawnej kamery.
     */
    override fun zapewnijOdtwarzanie() {
        // `isPlaying` też jest wywołaniem libVLC, więc sprawdzamy je na wątku `wideo`,
        // a nie tu — patrz [podepnij].
        wykonaj { if (!odtwarzacz.isPlaying) uruchom(adres) }
    }

    /** Wyłącznie z wątku `wideo`. */
    private fun uruchom(url: String) {
        try {
            val media = Media(libVlc, Uri.parse(url)).apply {
                // ### Dekoder sprzętowy — ale dopiero przy H.264
                //
                // Zmierzone na aparaturze 2026-08-28, minuta na próbkę, licznik
                // `Buffering 0%` (każdy wpis to jedno zacięcie obrazu):
                //
                //   H.265 + sprzętowy + TCP            15
                //   H.265 + sprzętowy + UDP            19
                //   H.265 + programowy + TCP            8
                //   H.264 + sprzętowy + TCP            19
                //   H.264 + programowy + TCP        19, 14
                //   H.264 + 960x540 + programowy       22
                //   H.264 + SPRZĘTOWY + UDP      11, 5, 6   <- ten układ
                //
                // Przy H.265 sprzętowy dekoder tego układu (msm8953) wywracał się
                // kilkanaście razy na minutę: `AMediaCodec Buffer failed`, `buffer deadlock
                // prevented`, potem `Received first picture`, czyli start od zera. Przy
                // H.264 zachowuje się stabilnie — restartów spadło z 9–13 na 2 — i zdejmuje
                // 20 punktów procenta obciążenia (108 % → 88 %).
                //
                // ⚠ Ten wybór jest związany z kodekiem KAMERY. Gdyby ktoś przestawił ją
                // z powrotem na H.265, sprzętowy dekoder znów zacznie się wywracać i lepszy
                // będzie programowy. Kodek sprawdza `narzedzia/siyi_gimbal.py codec`.
                //
                // Rozstrzygnęło porównanie z fabryczną aplikacją SIYI FPV: na tym samym
                // łączu i tej samej kamerze obraz był tam płynny, co wykluczyło radio
                // i kamerę (ta nadaje 901 klatek w 30 s bez jednej przerwy > 100 ms).
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=$BUFOR_MS")
                addOption(":no-audio")
            }
            odtwarzacz.media = media
            media.release()
            odtwarzacz.play()
        } catch (e: Exception) {
            Dziennik.blad("wideo", "nie udało się uruchomić strumienia", e)
            przyStanie?.invoke(false)
            wznowZaChwile()
        }
    }

    /**
     * Ponowienie z **narastającym odczekaniem** 1 → 2 → 4 → 8 → 15 s.
     *
     * Poprzednio ponawiało natychmiast i w kółko, na wątku, z którego przyszło zdarzenie.
     * Gdy kamery nie ma — a na biurku nie ma jej nigdy — dawało to nieprzerwaną pętlę
     * `stop()` + `play()`. Odczekanie nie służy tylko oszczędzaniu procesora: daje też
     * głowicy czas na powrót po chwilowym zerwaniu łącza, zamiast dobijać ją zapytaniami.
     */
    private fun wznowZaChwile() {
        if (zamkniety) return
        val numer = nieudanePodrzad++
        val zwloka = ODCZEKANIA_S[numer.coerceAtMost(ODCZEKANIA_S.lastIndex)]
        Dziennik.ostrzezenie("wideo", "brak obrazu — ponowienie za $zwloka s (próba ${numer + 1})")
        try {
            watek.schedule({
                if (zamkniety) return@schedule
                if (numer == 0 || (numer + 1) % CO_KTORA_SONDA == 0) {
                    Dziennik.ostrzezenie("wideo", zbadajLacze(adres))
                }
                try {
                    odtwarzacz.stop()
                } catch (_: Exception) {
                }
                uruchom(adres)
            }, zwloka, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Wątek już zamykany — nie ma czego ponawiać.
        }
    }

    /**
     * Dlaczego nie ma obrazu — jednym zdaniem, do logu kokpitu.
     *
     * Powód jest w libVLC, ale ten nie przechodzi przez `MediaPlayer.Event` — 2026-08-26
     * kosztowało to pół dnia: log kokpitu mówił „brak obrazu", a `connection timed out`
     * dało się przeczytać wyłącznie `logcatem`, czyli przy podpiętym kablu. W polu kabla
     * nie ma. Zamiast wyciągać komunikat z libVLC, pytamy sami: samo zwykłe połączenie
     * TCP rozróżnia trzy przypadki, które w polu znaczą trzy różne czynności.
     *
     * Wyłącznie z wątku `wideo` — blokuje do [SONDA_MS].
     */
    private fun zbadajLacze(url: String): String {
        val adr = Uri.parse(url)
        val gospodarz = adr.host ?: return "nie umiem odczytać adresu kamery z „$url”"
        val port = if (adr.port > 0) adr.port else PORT_RTSP
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(gospodarz, port), SONDA_MS)
                "port $port na $gospodarz przyjmuje połączenia — sieć jest dobra, " +
                    "usterka siedzi w samym RTSP (sprawdź ścieżkę strumienia i kodek)"
            }
        } catch (_: SocketTimeoutException) {
            "$gospodarz:$port nie odpowiada — kamera nie zgłasza się w sieci pokładowej"
        } catch (e: IOException) {
            val tresc = e.message.orEmpty()
            when {
                tresc.contains("ENETUNREACH") || tresc.contains("unreachable", true) ->
                    "brak trasy do $gospodarz — aparatura nie jest w sieci pokładowej " +
                        "(sprawdź przejściówkę Ethernet)"
                tresc.contains("ECONNREFUSED") || tresc.contains("refused", true) ->
                    "$gospodarz odpowiada, ale port $port jest zamknięty — " +
                        "serwer RTSP w kamerze nie działa"
                else -> "$gospodarz:$port — $tresc"
            }
        }
    }

    private fun wykonaj(zadanie: () -> Unit) {
        if (zamkniety) return
        try {
            watek.execute { if (!zamkniety) zadanie() }
        } catch (_: Exception) {
            // Kolejka odrzuciła zadanie przy zamykaniu.
        }
    }

    override fun zwolnij() {
        // Kolejność ma znaczenie: najpierw zamykamy kolejkę, żeby nic już nie weszło
        // do libVLC, a dopiero potem odpinamy i zwalniamy — synchronicznie, bo po
        // `shutdownNow` nie ma kto tego wykonać.
        zamkniety = true
        podpietyWidok = null
        watek.shutdownNow()
        try {
            odtwarzacz.detachViews()
        } catch (_: Exception) {
        }
        odtwarzacz.release()
        libVlc.release()
    }

    companion object {
        /** Bufor sieciowy odtwarzacza. 300 ms = zmierzone drgania zegara (do 99 ms) z zapasem. */
        private const val BUFOR_MS = 300

        /** Odczekania przed kolejnymi ponowieniami, w sekundach. */
        private val ODCZEKANIA_S = longArrayOf(1, 2, 4, 8, 15)

        /** Ile czekamy na zestawienie TCP w sondzie [zbadajLacze]. */
        private const val SONDA_MS = 2000
        private const val PORT_RTSP = 8554

        /** Sonda przy pierwszej nieudanej próbie i potem co dziesiątą — żeby nie zalać logu. */
        private const val CO_KTORA_SONDA = 10

        /**
         * ⚠ Ścieżka **zmierzona na sprzęcie 2026-08-26**, nie przepisana z instrukcji.
         *
         * Instrukcja ZR30 v1.4 (str. 143) podaje `/video1` dla strumienia głównego
         * i `/video2` dla podglądu. Ten egzemplarz — zoom fw `0x77000106`,
         * kamera fw `0x78000202` — odpowiada na obie ścieżki `404 Stream Not Found`,
         * a `DESCRIBE` przechodzi wyłącznie pod **`/main.264`**. To był powód, dla którego
         * obrazu nie było ani razu, mimo sprawnej sieci i skonfigurowanego kodeka.
         *
         * Mimo rozszerzenia w nazwie strumień jest **H265** (`a=rtpmap:96 H265/90000`,
         * LIVE555 v2019.08.12) — czyli wybór libVLC zamiast ExoPlayera nadal ma sens.
         *
         * **Strumienia podglądu ten egzemplarz nie ma w ogóle** — instrukcja mówi wprost,
         * że sub stream występuje tylko w ZT30. `ADRES_PODGLADU` wskazuje więc na ten sam
         * strumień, żeby wybór jakości w kokpicie nie prowadził donikąd.
         */
        const val DOMYSLNY_ADRES = "rtsp://192.168.144.25:8554/main.264"
        const val ADRES_PODGLADU = DOMYSLNY_ADRES
    }
}
