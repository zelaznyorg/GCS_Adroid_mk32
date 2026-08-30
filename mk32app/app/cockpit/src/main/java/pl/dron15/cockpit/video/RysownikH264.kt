package pl.dron15.cockpit.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import pl.dron15.cockpit.diag.Dziennik
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Powierzchnia, dekoder i **rytm wydawania klatek** — wspólna część torów natywnych:
 * [OdtwarzaczSiyi] i [OdtwarzaczRtsp]. Tory różnią się tylko tym, skąd biorą bajty.
 *
 * ### Dlaczego klatek NIE wolno wyświetlać natychmiast
 *
 * Pierwsza wersja oddawała każdą klatkę zaraz po zdekodowaniu
 * (`releaseOutputBuffer(indeks, render = true)`). Wyglądało to rozsądnie — najświeższa
 * klatka od razu na ekran — ale pomiar `dumpsys SurfaceFlinger --latency` pokazał, co
 * z tego wychodzi na wyświetlaczu. Odstępy między klatkami **na ekranie**, w liczbie
 * odświeżeń (2026-08-28, tor SIYI, 5,25 s):
 *
 * ```
 * … 2 3 2 3 │ 8 1 1 6 │ 3 2 2 3 …
 * ```
 *
 * Podstawowy rytm jest poprawny: 25 kl./s na ekranie 60 Hz to na przemian 2 i 3
 * odświeżenia (średnio 2,4). Ale **trzy razy na 5 sekund** pojawiała się seria
 * `8 1 1 6`: zastój **133 ms**, potem dwie klatki wciśnięte po **16 ms**, potem zastój
 * **100 ms**. To jest dokładnie ta „delikatna szarpanina", którą widać okiem.
 *
 * Przyczyny są dwie i obie leżą po naszej stronie:
 *
 * | Objaw w ciągu | Przyczyna |
 * |---|---|
 * | `8` — zastój 133 ms | przerwa w sieci przy **klatce kluczowej** (60–150 kB wobec 2–6 kB), przepuszczona prosto na ekran |
 * | `1 1` — dwie klatki po 16 ms | nadrabianie zaległości: klatki, które przyszły w kupie, wyświetlone jedna po drugiej i **zmarnowane** |
 *
 * ### Jak robi to aplikacja producenta
 *
 * Zmierzone tą samą metodą: warstwa SIYI FPV odświeża się **54,6 razy na sekundę,
 * prawie co odświeżenie ekranu** — jej kompozytor nigdy nie stoi. Przy takim rysowaniu
 * spóźniona klatka oznacza najwyżej, że poprzednia zostaje o jedno odświeżenie dłużej.
 * Serii `1 1` nie da się tam wyprodukować.
 *
 * ### Co robimy zamiast tego
 *
 * 1. **Bufor wyrównujący przed dekoderem** — [PROG_STARTU] klatek zapasu, czyli ok. 320 ms.
 *    Musi pokryć **najdłuższą** przerwę przy klatce kluczowej (133 ms na torze SIYI,
 *    do 250 ms na RTSP), nie średnią.
 * 2. **Równa siatka chwil wyświetlenia** o zmierzonym okresie, każda **dosunięta do
 *    odświeżenia ekranu** — klatka trafia na ekran wtedy, kiedy wypada, a nie wtedy,
 *    kiedy przypadkiem skończyło się dekodowanie.
 * 3. **Numerek na siatce dostaje klatka, która WYSZŁA z dekodera**, nie ta, która do
 *    niego weszła. Dekoder sprzętowy pracuje potokowo i oddaje klatkę kilka klatek
 *    później — przy planowaniu na wejściu 242 z 250 klatek wychodziło po czasie.
 * 4. Gdy dekoder nie ma nic gotowego na dane pole siatki, **na ekranie zostaje poprzednia
 *    klatka**, a siatka przesuwa się dalej. Nigdy nie wydajemy dwóch klatek pod rząd —
 *    i to właśnie usuwa serie `1 1`.
 *
 * ⚠ **Czego robić NIE wolno:** rozstawiać klatek znacznikami czasu z dużym wyprzedzeniem
 * bez bufora przed dekoderem. Próbowane wcześniej i padło — kolejka buforów `SurfaceView`
 * mieści tylko trzy klatki, czyli ok. 100 ms, a przerwy sięgają 170 ms. Zapas musi leżeć
 * **przed** dekoderem, w naszej kolejce, nie za nim w powierzchni.
 *
 * ### Widok tworzy się RAZ
 *
 * Jak w [OdtwarzaczVlc]: wymiana widoku przy zmianie zakładki kosztowała nas czarny kadr
 * i zawieszenie aplikacji. Tutaj `SurfaceView` powstaje raz i tylko zmienia rodzica.
 */
class RysownikH264(private val nazwaToru: String) {

    /** Wołane przy pierwszej wyświetlonej klatce i przy utracie obrazu. */
    var przyStanie: ((Boolean) -> Unit)? = null

    /** Wołane, gdy powierzchnia jest gotowa albo znika — tor decyduje, czy się łączyć. */
    var przyPowierzchni: ((Boolean) -> Unit)? = null

    @Volatile
    private var powierzchnia: Surface? = null
    private var widok: SurfaceView? = null

    private var dekoder: MediaCodec? = null
    private var watekRytmu: Thread? = null

    /**
     * Klatki czekające na swoją chwilę. Trzyma **cudze tablice bez kopiowania** — tory
     * przydzielają nową na każdą klatkę, więc kopia byłaby czystym marnotrawstwem
     * (przy 150 kB i 25 kl./s to 3,7 MB/s przepisywane bez powodu).
     */
    private val kolejka = LinkedBlockingQueue<ByteArray>(POJEMNOSC_KOLEJKI)

    @Volatile
    private var okresVsyncNs = 16_666_667L        // 60 Hz do czasu odczytu z ekranu

    @Volatile
    private var ostatniVsyncNs = 0L

    /** Zmierzony okres przychodzenia klatek — średnia krocząca, nie założenie. */
    @Volatile
    private var okresKlatkiNs = 40_000_000L

    private var poprzedniePrzyjscieNs = 0L

    @Volatile
    private var stracone = 0

    @Volatile
    private var spoznione = 0

    /**
     * Kiedy ostatnia klatka poszła na ekran. Po tym [TorZZapasem] poznaje, że źródło
     * zamilkło — sam brak połączenia nie wystarczy, bo kamera potrafi **przyjąć
     * połączenie i milczeć**.
     */
    @Volatile
    var ostatniaKlatkaNs = 0L
        private set

    /** Pola siatki, w których dekoder nie miał nic gotowego — poprzednia klatka zostaje. */
    @Volatile
    private var glodne = 0

    val maPowierzchnie: Boolean get() = powierzchnia != null
    val gotowy: Boolean get() = dekoder != null

    fun widok(kontekst: Context): android.view.View {
        val istniejacy = widok
        if (istniejacy != null) {
            (istniejacy.parent as? ViewGroup)?.removeView(istniejacy)
            return istniejacy
        }
        zacznijSledzicVsync(kontekst)
        val nowy = SurfaceView(kontekst)
        nowy.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                powierzchnia = holder.surface
                przyPowierzchni?.invoke(true)
            }

            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                powierzchnia = null
                przyPowierzchni?.invoke(false)
                przyStanie?.invoke(false)
            }
        })
        widok = nowy
        return nowy
    }

    /**
     * Uczy się fazy i okresu odświeżania ekranu. Bez tego nie da się dosunąć klatki
     * do odświeżenia, a właśnie na tym polega różnica między `2 3 2 3` a `8 1 1 6`.
     */
    private fun zacznijSledzicVsync(kontekst: Context) {
        val odswiezanie = try {
            (kontekst.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.refreshRate
        } catch (_: Exception) {
            60f
        }
        if (odswiezanie > 20f) okresVsyncNs = (1_000_000_000.0 / odswiezanie).toLong()
        Handler(Looper.getMainLooper()).post {
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    ostatniVsyncNs = frameTimeNanos
                    Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        Dziennik.info("wideo", "$nazwaToru: ekran %.1f Hz (odświeżenie %.2f ms)"
            .format(1e9 / okresVsyncNs, okresVsyncNs / 1e6))
    }

    /**
     * Uruchamia dekoder na podanych zestawach parametrów. `sps` i `pps` **ze znacznikami
     * startowymi Annex-B** — tego oczekuje `MediaCodec` w `csd-0` i `csd-1`.
     */
    fun uruchom(sps: ByteArray, pps: ByteArray): Boolean {
        val cel = powierzchnia ?: return false
        zatrzymaj()
        return try {
            val format = MediaFormat.createVideoFormat(TYP_MIME, SZEROKOSC_WSTEPNA, WYSOKOSC_WSTEPNA)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            val d = MediaCodec.createDecoderByType(TYP_MIME).apply {
                configure(format, cel, null, 0)
                start()
            }
            dekoder = d
            kolejka.clear()
            poprzedniePrzyjscieNs = 0L
            stracone = 0
            spoznione = 0
            glodne = 0
            watekRytmu = Thread({ rytm(d) }, "rytm-wideo").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            Dziennik.blad("wideo", "$nazwaToru: nie udało się uruchomić dekodera $TYP_MIME", e)
            dekoder = null
            false
        }
    }

    /**
     * Przyjmuje **całą jednostkę dostępu** — jedną klatkę, ze znacznikami startowymi
     * w środku. Nie oddaje jej dekoderowi od razu, tylko wkłada do kolejki; o tym, kiedy
     * klatka pójdzie dalej i kiedy trafi na ekran, decyduje [rytm].
     *
     * ⛔ Nie wołać tego dla pojedynczych NAL-i. Klatka H.264 bywa złożona z kilku jednostek
     * i wszystkie muszą trafić do dekodera **razem, z jednym znacznikiem czasu**. Pierwsza
     * wersja podawała każdą osobno, więc dekoder brał je za osobne klatki — obraz rozmywał
     * się i ciągnął smugi przy ruchu.
     *
     * ⚠ Podana tablica **przechodzi na własność** rysownika. Wołający nie może jej użyć
     * ponownie.
     */
    fun podaj(dane: ByteArray, offset: Int = 0, dlugosc: Int = dane.size - offset) {
        if (dekoder == null) return
        val klatka = if (offset == 0 && dlugosc == dane.size) dane else dane.copyOfRange(offset, offset + dlugosc)

        val teraz = System.nanoTime()
        if (poprzedniePrzyjscieNs != 0L) {
            val odstep = teraz - poprzedniePrzyjscieNs
            // Średnia krocząca po **wiarygodnych** odstępach: przerwa przy klatce kluczowej
            // ani nadrabianie zaległości nie mogą przestawić okresu siatki.
            if (odstep in NAJKROTSZY_ODSTEP_NS..NAJDLUZSZY_ODSTEP_NS) {
                okresKlatkiNs = (okresKlatkiNs * 7 + odstep) / 8
            }
        }
        poprzedniePrzyjscieNs = teraz

        if (!kolejka.offer(klatka)) {
            kolejka.poll()                        // najstarsza ustępuje — liczy się świeżość
            kolejka.offer(klatka)
            stracone++
        }
    }

    /**
     * Wydaje klatki na równej siatce dosuniętej do odświeżeń ekranu.
     *
     * ### Chwila wyświetlenia przypisywana jest na WYJŚCIU, nie na wejściu
     *
     * Pierwsza wersja wkładała klatkę do dekodera 50 ms przed jej chwilą i liczyła, że
     * tyle wystarczy. Nie wystarczyło: **dekoder sprzętowy pracuje potokowo** i oddaje
     * klatkę kilka klatek po podaniu, a nie zaraz. Skutek zmierzony w logu —
     * **242 z 250 klatek porzuconych jako spóźnione**, obraz 0,26 kl./s. Wyprzedzenia
     * nie da się dobrać, bo opóźnienie potoku zależy od dekodera i od treści.
     *
     * Dlatego: dekoder karmimy **tak szybko, jak przyjmuje**, a numerek na siatce dostaje
     * dopiero ta klatka, która z niego **wyszła**. Zapas leży wtedy w trzech miejscach
     * naraz — w naszej kolejce, w potoku dekodera i w jednej klatce przed ekranem —
     * i żadne z nich nie musi być odmierzone co do milisekundy.
     *
     * ### Zapas zbiera się raz, na starcie i po każdej ciszy
     *
     * Wydawanie zaczyna się dopiero przy [PROG_STARTU] klatkach w kolejce. Bez tego
     * kolejka nigdy nie urośnie: wydawanie w tym samym tempie, w jakim klatki przychodzą,
     * opróżnia ją tak samo szybko, jak się napełnia, i nie ma czym przykryć przerwy
     * przy klatce kluczowej.
     */
    private fun rytm(d: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var nastepneWydanieNs = 0L
        var pierwszaKlatka = true
        var wydane = 0L
        var licznikWejscia = 0L
        var odebrane = 0L

        try {
            while (dekoder === d) {
                if (nastepneWydanieNs == 0L) {
                    while (dekoder === d && kolejka.size < PROG_STARTU) Thread.sleep(5)
                    if (dekoder !== d) return
                    // Rozruch: kilka klatek do potoku dekodera, żeby zdążył oddać pierwszą.
                    // Reszta zapasu zostaje w [kolejka] — i to ona pokrywa przerwy w sieci.
                    var podane = 0
                    while (dekoder === d && podane < ROZRUCH_POTOKU && dokarm(d, licznikWejscia)) {
                        licznikWejscia++
                        podane++
                    }
                    nastepneWydanieNs = doOdswiezenia(System.nanoTime() + ROZBIEG_NS)
                }

                val teraz = System.nanoTime()
                if (teraz >= nastepneWydanieNs - okresVsyncNs) {
                    // ### Jedna klatka wchodzi, jedna wychodzi — na każde pole siatki
                    //
                    // To jest cała regulacja i jest odporna z definicji: liczba klatek
                    // w dekoderze się nie zmienia, więc zapas zostaje tam, gdzie go
                    // widzimy — w naszej kolejce. Rośnie, gdy klatki przychodzą gęściej
                    // niż siatka, i **maleje w czasie przerwy w sieci**, zamiast oddawać
                    // pusty ekran.
                    //
                    // ⚠ Karmienie zachłanne (wszystko, co przyjmie dekoder) tego nie daje:
                    // przenosi zapas do buforów dekodera, których liczby nie znamy, i po
                    // przerwie 150 ms obraz i tak stawał. Sztywny limit „w locie" też nie:
                    // przy trzech klatkach dekoder nie oddawał **nic** i obraz nie ruszał.
                    //
                    // ⛔ Karmić na KAŻDYM polu siatki, ale tylko do [GLEBOKOSC_POTOKU]
                    // klatek w locie. Dwie skrajności zostały sprawdzone i obie są złe:
                    //
                    // - bez limitu: przy każdym pustym polu jedna klatka przenosi się
                    //   z kolejki do dekodera **na stałe**; zmierzone 207 pustych pól
                    //   i kolejka przyklejona do zera mimo zapasu ustawionego na sześć;
                    // - karmienie wyłącznie przy wydanej klatce: potok powoli się
                    //   opróżnia (każde puste pole to o jedną klatkę mniej w dekoderze),
                    //   aż dekoder nie ma z czego dekodować i **obraz zamarza na dobre** —
                    //   zmierzone: zamarł po minucie.
                    //
                    // - limit klatek „w locie": ten dekoder (Qualcomm AVC, „frame by frame
                    //   mode") **nie oddaje pierwszej klatki**, dopóki nie dostanie pełnego
                    //   wejścia; przy limicie 5 i 8 obraz nie ruszał wcale.
                    //
                    // Zostaje karmienie na każdym polu siatki. Zapas rzeczywiście częściowo
                    // przenosi się wtedy do dekodera, ale nadrabia to regulacja kroku
                    // ([krokSiatki]) — i to ona, a nie limit, ścięła najdłuższy zastój
                    // z 233 ms do 117 ms.
                    if (dokarm(d, licznikWejscia)) licznikWejscia++

                    val wyjsciowy = d.dequeueOutputBuffer(info, 0)
                    when {
                        wyjsciowy >= 0 -> {
                            odebrane++
                            val cel = doOdswiezenia(nastepneWydanieNs)
                            if (cel > teraz) d.releaseOutputBuffer(wyjsciowy, cel)
                            else d.releaseOutputBuffer(wyjsciowy, true)
                            nastepneWydanieNs = maxOf(cel, teraz) + krokSiatki()
                            wydane++
                            ostatniaKlatkaNs = teraz
                            if (pierwszaKlatka) pierwszaKlatka = zglosPierwsza(true)
                            if (wydane % MELDUNEK_CO == 0L) zamelduj()
                        }
                        // Dekoder jeszcze nic nie oddał — to rozruch, nie zacięcie.
                        // Przesuwamy start i dokładamy klatkę, żeby potok ruszył.
                        odebrane == 0L -> nastepneWydanieNs = System.nanoTime() + okresKlatkiNs
                        // Nie ma czym wypełnić pola — na ekranie zostaje poprzednia klatka.
                        // Lepsze niż wydanie dwóch pod rząd, gdy wreszcie przyjdą.
                        else -> {
                            nastepneWydanieNs += krokSiatki()
                            glodne++
                        }
                    }
                    // Zaległość większa niż pół sekundy znaczy, że siatka straciła sens.
                    if (nastepneWydanieNs < System.nanoTime() - PROG_ZGUBIENIA_NS) {
                        spoznione++
                        nastepneWydanieNs = 0L
                    }
                } else {
                    Thread.sleep(1)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Dziennik.blad("wideo", "$nazwaToru: rytm wydawania przerwany", e)
        }
    }

    /**
     * Krok siatki — zmierzony okres klatki, **lekko naciągnięty albo skrócony** zależnie
     * od tego, ile zapasu leży w kolejce.
     *
     * Bez tego zapas zużyty na jedną przerwę w sieci **nigdy się nie odbudowuje**: przy
     * sztywnej zasadzie „jedna w, jedna out" poziom kolejki zostaje taki, jaki był po
     * przerwie, i następna przerwa zatrzymuje obraz od razu. Zmierzone: przerwy rosły
     * ze 133 ms do 233 ms w miarę trwania sesji.
     *
     * Korekta wynosi 3 %, czyli ok. 1,2 ms na klatkę — niewidoczna dla oka.
     *
     * ⚠ **Mocniejsza korekta jest gorsza, nie lepsza.** Zapas trzyma się na 1–3 klatkach
     * zamiast sześciu, więc kusi, żeby odbudowywać go szybciej. Sprawdzone przy 8 %:
     * odsetek klatek z przerwą powyżej 83 ms wzrósł z **3,2 % na 7,9 %** — bo przy
     * chronicznie niskiej kolejce korekta pracuje bez przerwy i **sama** wydłuża pola
     * siatki do 83 ms. Regulacja ma poprawiać rytm, nie stawać się jego zaburzeniem.
     */
    private fun krokSiatki(): Long {
        val ile = kolejka.size
        return when {
            ile > ZAPAS_DOCELOWY + 2 -> (okresKlatkiNs * 97) / 100
            ile < ZAPAS_DOCELOWY -> (okresKlatkiNs * 103) / 100
            else -> okresKlatkiNs
        }
    }

    /**
     * Wkłada do dekodera jedną klatkę z kolejki, jeśli jest co i jest gdzie.
     * Zwraca `false`, gdy nic nie wsadzono — wołający nie ma wtedy na co czekać.
     *
     * Znacznik czasu jest tu **umowny** (kolejny numer razy okres) i służy wyłącznie
     * temu, żeby dekoder nie protestował. O chwili wyświetlenia decyduje [rytm]
     * dopiero na wyjściu.
     */
    private fun dokarm(d: MediaCodec, numer: Long): Boolean {
        if (kolejka.isEmpty()) return false
        val wejsciowy = d.dequeueInputBuffer(0)
        if (wejsciowy < 0) return false
        val klatka = kolejka.poll() ?: return false
        val bufor = d.getInputBuffer(wejsciowy)
        if (bufor == null || bufor.capacity() < klatka.size) {
            stracone++
            Dziennik.ostrzezenie("wideo", "$nazwaToru: klatka ${klatka.size} B nie mieści się w buforze dekodera")
            return false
        }
        bufor.clear()
        bufor.put(klatka)
        d.queueInputBuffer(wejsciowy, 0, klatka.size, numer * (okresKlatkiNs / 1000), 0)
        return true
    }

    private fun zglosPierwsza(pierwsza: Boolean): Boolean {
        if (!pierwsza) return false
        Dziennik.info("wideo", "$nazwaToru: obraz płynie")
        przyStanie?.invoke(true)
        return false
    }

    private fun zamelduj() {
        Dziennik.info(
            "wideo",
            "$nazwaToru: rytm %.1f ms, kolejka %d%s%s%s".format(
                okresKlatkiNs / 1e6, kolejka.size,
                if (glodne > 0) ", puste pola siatki: $glodne" else "",
                if (stracone > 0) ", przepełnienia: $stracone" else "",
                if (spoznione > 0) ", zbieranie od nowa: $spoznione" else "",
            ),
        )
    }

    /**
     * Dosuwa chwilę do najbliższego odświeżenia ekranu, z niewielkim wyprzedzeniem, żeby
     * bufor zdążył zostać przejęty w tym odświeżeniu, a nie w następnym.
     */
    private fun doOdswiezenia(chwilaNs: Long): Long {
        val v = ostatniVsyncNs
        if (v == 0L) return chwilaNs
        val ile = Math.round((chwilaNs - v).toDouble() / okresVsyncNs)
        return v + ile * okresVsyncNs - okresVsyncNs / 5
    }

    /**
     * Zwalnia dekoder. **Musi być wołane z tej samej ścieżki, która go stworzyła** —
     * porzucony `MediaCodec` trzyma powierzchnię i następne uruchomienie kończy się
     * błędem konfiguracji. Kosztowało to 2026-08-28 pętlę ok. 30 nieudanych startów
     * na minutę, zanim cykl życia trafił w jedno miejsce.
     */
    fun zatrzymaj() {
        val d = dekoder ?: return
        dekoder = null
        watekRytmu?.interrupt()
        try {
            watekRytmu?.join(300)
        } catch (_: InterruptedException) {
        }
        watekRytmu = null
        kolejka.clear()
        try {
            d.stop()
            d.release()
        } catch (_: Exception) {
        }
    }

    fun zwolnij() {
        zatrzymaj()
        widok = null
        powierzchnia = null
    }

    companion object {
        private const val TYP_MIME = "video/avc"
        private const val CZEKANIE_US = 10_000L

        /** Tyle razy pytamy o wolny bufor wejściowy, zanim uznamy klatkę za straconą. */
        private const val PROB_WEJSCIA = 12

        /**
         * Zapas przed rozpoczęciem wydawania. **Osiem klatek to ok. 320 ms.**
         *
         * Cztery (160 ms) okazały się za małe: pokrywały przerwę 133 ms toru SIYI, ale
         * nie 250–300 ms, jakie zdarzają się na RTSP. Kolejka pustoszała, siatka gubiła
         * rytm i pomiar wyszedł **gorzej** niż przed poprawką (6,3 % zamiast 4,0 % klatek
         * z przerwą powyżej 83 ms). Zapas musi pokryć najdłuższą przerwę, nie średnią.
         *
         * Z tego dwanaście minus [ROZRUCH_POTOKU] zostaje w kolejce jako rzeczywisty
         * zapas — ok. 320 ms — a reszta pracuje w potoku dekodera. Każda klatka
         * w potoku to też opóźnienie obrazu, więc potok trzymamy krótki.
         */
        private const val PROG_STARTU = 12

        /**
         * Ile klatek chcemy trzymać w zapasie w ustalonym ruchu — ok. 240 ms. Tyle
         * pokrywa zmierzone przerwy toru SIYI (130–160 ms) z marginesem. Za to tyle
         * właśnie wynosi dołożone opóźnienie obrazu, więc podnoszenie tej liczby
         * kosztuje świeżość kadru.
         */
        private const val ZAPAS_DOCELOWY = 6

        /**
         * O ile w przód ustawiamy pierwsze pole siatki po zebraniu zapasu. Nie jest to
         * już „czas na dekodowanie" — potok dekodera napełnia się wcześniej, przy
         * wpuszczaniu zapasu — tylko zwykły rozbieg, żeby pierwsza klatka nie była
         * spóźniona już w chwili narodzin.
         */
        private const val ROZBIEG_NS = 60_000_000L

        /** Powyżej tego zaległości siatka nie ma sensu — zbieramy zapas od nowa. */
        private const val PROG_ZGUBIENIA_NS = 500_000_000L

        /**
         * Ile klatek naraz może być „w locie" w dekoderze. Trzy wystarczą, żeby potok
         * nigdy nie stał, a reszta zapasu zostaje w [kolejka], gdzie ją widać i mierzymy.
         */
        /**
         * Ile klatek wpuszczamy do dekodera na rozruch, zanim ruszy siatka. Dekoder
         * sprzętowy potrzebuje kilku klatek, zanim odda pierwszą — przy trzech potrafił
         * nie oddać **nic** i obraz nie ruszał wcale.
         */
        private const val ROZRUCH_POTOKU = 8

        /**
         * Ile klatek naraz może być „w locie" w dekoderze w ustalonym ruchu. Reszta
         * zapasu zostaje w [kolejka], gdzie ją widać i gdzie ją mierzymy.
         */
        private const val GLEBOKOSC_POTOKU = 5

        private const val POJEMNOSC_KOLEJKI = 60

        /** Granice wiarygodnego odstępu — poza nimi to przerwa albo nadrabianie, nie tempo. */
        private const val NAJKROTSZY_ODSTEP_NS = 15_000_000L
        private const val NAJDLUZSZY_ODSTEP_NS = 70_000_000L

        private const val MELDUNEK_CO = 250L

        /** Podpowiedź; prawdziwy rozmiar dekoder odczyta z SPS. */
        private const val SZEROKOSC_WSTEPNA = 1280
        private const val WYSOKOSC_WSTEPNA = 720
    }
}
