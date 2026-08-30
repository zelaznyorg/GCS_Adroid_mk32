package pl.dron15.cockpit.video

import android.content.Context
import android.util.Base64
import pl.dron15.cockpit.diag.Dziennik
import pl.dron15.cockpit.net.SiecPokladowa
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Obraz z ZR30 przez **własnego klienta RTSP**, prosto do `MediaCodec`.
 *
 * ### Dlaczego własny, skoro jest libVLC
 *
 * Nie chodzi o RTSP — chodzi o zegar. libVLC pilnuje zegara strumienia i przy spóźnieniu
 * ok. 90 ms, a takie na łączu SIYI bywają, wyrzuca klatkę albo przebudowuje bufor. Wieczór
 * 2026-08-28 zszedł na strojeniu tego: bufor, transport, kodek, dekoder — najlepszy wynik
 * to i tak kilka zacięć na minutę, przy widocznym opóźnieniu na pokrętła.
 *
 * Fabryczna aplikacja SIYI FPV chodzi płynnie, bo **wyświetla klatkę od razu po
 * zdekodowaniu**. Tę samą radę („render on arrival", `sync=false`) powtarzają wątki
 * o zacinającym się RTSP na Androidzie. Tego w libVLC nie da się wyłączyć, więc bierzemy
 * transport na siebie, a rysowanie oddajemy [RysownikH264].
 *
 * ### Dlaczego RTSP, a nie własny port SIYI
 *
 * Pierwsze podejście szło portem 37256, którego używa SIYI FPV — protokół udało się
 * rozłożyć (patrz [OdtwarzaczSiyi]), ale kamera rozłącza po ok. 5 s klienta, który nie
 * odsyła poprawnego pakietu podtrzymania. Osiem bajtów w tym pakiecie nie poddało się
 * żadnej sumie kontrolnej i wygląda na dane szyfrowane. Przegląd sieci potwierdził:
 * **żadna publiczna integracja SIYI nie używa tego portu** — wszystkie biorą RTSP
 * `8554/main.264`. Odtwarzanie zamkniętego uzgodnienia to niepewny projekt; RTSP jest
 * otwarty, opisany i kamera nie zrywa na nim połączeń.
 *
 * ### Co robi ten klient
 *
 * Minimum z RFC 2326 i RFC 6184, bez rzeczy, których ta kamera nie potrzebuje:
 *
 * 1. `DESCRIBE` → z SDP bierzemy `sprop-parameter-sets`, czyli SPS i PPS w base64,
 *    i numer ścieżki obrazu;
 * 2. `SETUP` z `Transport: RTP/AVP/TCP;interleaved=0-1` — RTP idzie **tym samym
 *    połączeniem**, więc nie ma osobnych gniazd UDP ani kłopotu z zaporą;
 * 3. `PLAY`, a potem czytanie ramek przeplatanych: `$`, kanał, długość, pakiet RTP;
 * 4. rozpakowanie H.264: pojedyncze NAL, `FU-A` (typ 28) i `STAP-A` (typ 24).
 *
 * Znaczniki czasu RTP **celowo ignorujemy** — rysownik wyświetla klatkę, gdy tylko ją ma.
 */
class OdtwarzaczRtsp(
    private val host: String = DOMYSLNY_HOST,
    private val sciezka: String = DOMYSLNA_SCIEZKA,
    private val rysownik: RysownikH264 = RysownikH264("tor RTSP"),
) : TorWideo {

    override var przyStanie: ((Boolean) -> Unit)?
        get() = rysownik.przyStanie
        set(v) {
            rysownik.przyStanie = v
        }

    @Volatile
    private var zamkniety = false

    @Volatile
    private var pokolenie = 0
    private var watek: Thread? = null

    /**
     * Gniazdo bieżącej próby — trzymane po to, żeby [wstrzymaj] mogło je **zamknąć
     * natychmiast**. Samo `interrupt()` nie przerywa blokującego odczytu z gniazda;
     * wątek dogasałby jeszcze do czasu przeterminowania odczytu, czyli kilka sekund,
     * i przez ten czas trzymał kamerę zajętą.
     */
    @Volatile
    private var gniazdoBiezace: java.net.Socket? = null

    init {
        // ⚠ [TorZZapasem] nadpisuje to przypisanie — patrz komentarz w OdtwarzaczSiyi.
        rysownik.przyPowierzchni = { jest -> naPowierzchnie(jest) }
    }

    /** Wołane, gdy powierzchnia pojawia się albo znika. Publiczne dla [TorZZapasem]. */
    fun naPowierzchnie(jest: Boolean) {
        if (jest) uruchom() else pokolenie++
    }

    /** Odstawia to źródło, nie zamykając go na dobre — patrz [OdtwarzaczSiyi.wstrzymaj]. */
    fun wstrzymaj() {
        pokolenie++
        try {
            gniazdoBiezace?.close()          // odblokowuje odczyt od razu
        } catch (_: Exception) {
        }
        watek?.interrupt()
        try {
            watek?.join(CZAS_DOGASANIA_MS)   // czekamy, aż stary wątek naprawdę zejdzie
        } catch (_: InterruptedException) {
        }
        kolejka.zatrzymaj()
        rysownik.zatrzymaj()
    }

    override fun widok(kontekst: Context): android.view.View = rysownik.widok(kontekst)

    override fun zapewnijOdtwarzanie() {
        if (watek?.isAlive != true) uruchom()
    }

    private fun uruchom() {
        if (zamkniety || !rysownik.maPowierzchnie || watek?.isAlive == true) return
        val moje = ++pokolenie
        watek = Thread({ petla(moje) }, "wideo-rtsp").apply { isDaemon = true; start() }
    }

    private fun petla(moje: Int) {
        var nieudane = 0
        while (!zamkniety && moje == pokolenie) {
            var gniazdo: Socket? = null
            try {
                gniazdo = Socket().apply {
                    tcpNoDelay = true
                    soTimeout = CZAS_CZEKANIA_MS
                    SiecPokladowa.zwiaz(this)
                    connect(InetSocketAddress(host, PORT), CZAS_CZEKANIA_MS)
                }
                gniazdoBiezace = gniazdo
                nieudane = 0
                sesja(gniazdo, moje)
            } catch (e: Exception) {
                if (!zamkniety && moje == pokolenie) {
                    val zwloka = ODCZEKANIA_MS[nieudane.coerceAtMost(ODCZEKANIA_MS.lastIndex)]
                    nieudane++
                    Dziennik.ostrzezenie(
                        "wideo",
                        "tor RTSP: brak obrazu z $host:$PORT$sciezka — ponowienie za " +
                            "${zwloka / 1000} s (${e.javaClass.simpleName}: ${e.message})",
                    )
                    przyStanie?.invoke(false)
                    try {
                        Thread.sleep(zwloka)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            } finally {
                // ⛔ Dekoder zatrzymujemy TYLKO we własnym pokoleniu — patrz komentarz
                // w OdtwarzaczSiyi. Inaczej odstawiony wątek gasi obraz nowego toru.
                kolejka.zatrzymaj()
                if (moje == pokolenie) rysownik.zatrzymaj()
                gniazdoBiezace = null
                try {
                    gniazdo?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ sesja RTSP

    private fun sesja(gniazdo: Socket, moje: Int) {
        val we = DataInputStream(BufferedInputStream(gniazdo.getInputStream(), BUFOR))
        val wy = gniazdo.getOutputStream()
        val adres = "rtsp://$host:$PORT$sciezka"
        var cseq = 1

        wyslij(wy, "DESCRIBE", adres, cseq++, "Accept: application/sdp")
        val opis = odpowiedz(we)
        val sdp = opis.second
        val (sps, pps) = parametryZSdp(sdp)
            ?: throw IllegalStateException("SDP bez sprop-parameter-sets")
        val trasa = torWSdp(sdp, adres)

        wyslij(wy, "SETUP", trasa, cseq++, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
        val ustawienie = odpowiedz(we)
        val sesja = ustawienie.first["session"]?.substringBefore(';')?.trim()
            ?: throw IllegalStateException("SETUP bez nagłówka Session")

        wyslij(wy, "PLAY", adres, cseq++, "Session: $sesja", "Range: npt=0.000-")
        odpowiedz(we)

        if (!rysownik.uruchom(sps, pps)) throw IllegalStateException("dekoder nie wystartował")

        val stroz = uruchomPodtrzymanie(wy, adres, sesja, moje)
        try {
            czytajPrzeplot(we, moje)
        } finally {
            stroz.interrupt()
        }
    }

    /**
     * Podtrzymuje sesję RTSP, bo **kamera zamyka ją po 60 s ciszy sterującej**.
     *
     * Zmierzone 2026-08-28 w logu kokpitu: bez tego obraz padał co **71 s**, jak zegar —
     * 11:54:52, 11:56:03, 11:57:14, 11:58:26, 11:59:37 — a przy każdym ponownym łączeniu
     * ekran pokazywał na ok. 1,5 s `BRAK OBRAZU Z KAMERY`. Sam strumień był w tym czasie
     * bez zarzutu; wygasała **sesja**, nie łącze.
     *
     * `OPTIONS` z nagłówkiem `Session` to najtańszy sposób jej odnowienia (RFC 2326 §10.1).
     * Odpowiedzi nie czytamy — [czytajPrzeplot] pomija wszystko, co nie zaczyna się od `$`,
     * a krótki tekst RTSP nie zawiera tego bajtu.
     */
    private fun uruchomPodtrzymanie(wy: OutputStream, adres: String, sesja: String, moje: Int): Thread =
        Thread({
            var cseq = BAZA_CSEQ_PODTRZYMANIA
            while (!zamkniety && moje == pokolenie) {
                try {
                    Thread.sleep(ODSTEP_SESJI_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                try {
                    wyslij(wy, "OPTIONS", adres, cseq++, "Session: $sesja")
                } catch (_: Exception) {
                    return@Thread                     // gniazdo padło; pętla główna to zauważy
                }
            }
        }, "rtsp-podtrzymanie").apply { isDaemon = true; start() }

    private fun wyslij(wy: OutputStream, metoda: String, adres: String, cseq: Int, vararg naglowki: String) {
        val tekst = buildString {
            append("$metoda $adres RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append("User-Agent: DRON15-cockpit\r\n")
            naglowki.forEach { append("$it\r\n") }
            append("\r\n")
        }
        wy.write(tekst.toByteArray(Charsets.US_ASCII))
        wy.flush()
    }

    /** Zwraca nagłówki (małymi literami) i treść. Rzuca, gdy kod odpowiedzi nie jest 200. */
    private fun odpowiedz(we: DataInputStream): Pair<Map<String, String>, String> {
        pomijPrzeplot(we)
        val pierwsza = wiersz(we)
        if (!pierwsza.contains(" 200 ")) throw IllegalStateException("RTSP: $pierwsza")
        val naglowki = HashMap<String, String>()
        while (true) {
            val w = wiersz(we)
            if (w.isEmpty()) break
            val i = w.indexOf(':')
            if (i > 0) naglowki[w.substring(0, i).trim().lowercase()] = w.substring(i + 1).trim()
        }
        val dlugosc = naglowki["content-length"]?.toIntOrNull() ?: 0
        val tresc = if (dlugosc > 0) ByteArray(dlugosc).also { we.readFully(it) } else ByteArray(0)
        return naglowki to String(tresc, Charsets.US_ASCII)
    }

    /**
     * Przeskakuje ramki RTP wmieszane w odpowiedzi RTSP.
     *
     * Po ponownym połączeniu kamera potrafi nadawać obraz, zanim odpowie na nasze pytanie —
     * a wtedy parser bierze binarne RTP za wiersz statusu i sesja pada z komunikatem pełnym
     * krzaków (zaobserwowane 2026-08-28 przy każdym wznowieniu). Ramka przeplatana zaczyna
     * się bajtem `$`, ma numer kanału i długość, więc da się ją po prostu przeczytać
     * i wyrzucić.
     */
    private fun pomijPrzeplot(we: DataInputStream) {
        we.mark(4)
        while (we.read().also { if (it < 0) throw IllegalStateException("koniec strumienia") } == 0x24) {
            we.read()                                     // kanał
            val dlugosc = (we.read() shl 8) or we.read()
            if (dlugosc <= 0 || dlugosc > MAKS_PAKIET) throw IllegalStateException("zła długość RTP")
            we.skipBytes(dlugosc)
            we.mark(4)
        }
        we.reset()                                        // to nie było `$` — oddajemy bajt
    }

    private fun wiersz(we: DataInputStream): String {
        val bufor = StringBuilder()
        while (true) {
            val b = we.read()
            if (b < 0) throw IllegalStateException("koniec strumienia w nagłówku RTSP")
            if (b == '\n'.code) return bufor.toString().trimEnd('\r')
            bufor.append(b.toChar())
        }
    }

    /** `a=fmtp:96 …sprop-parameter-sets=<SPS>,<PPS>` — oba w base64, bez znaczników startowych. */
    private fun parametryZSdp(sdp: String): Pair<ByteArray, ByteArray>? {
        val klucz = "sprop-parameter-sets="
        val i = sdp.indexOf(klucz)
        if (i < 0) return null
        val wartosc = sdp.substring(i + klucz.length).substringBefore(';').substringBefore('\r')
            .substringBefore('\n').trim()
        val czesci = wartosc.split(',')
        if (czesci.size < 2) return null
        return try {
            ZNACZNIK + Base64.decode(czesci[0], Base64.DEFAULT) to
                ZNACZNIK + Base64.decode(czesci[1], Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    /** `a=control:` — bywa adresem bezwzględnym albo przyrostkiem do dołączenia. */
    private fun torWSdp(sdp: String, adres: String): String {
        val i = sdp.indexOf("a=control:")
        if (i < 0) return "$adres/trackID=0"
        val wartosc = sdp.substring(i + "a=control:".length).substringBefore('\r')
            .substringBefore('\n').trim()
        return when {
            wartosc.startsWith("rtsp://") -> wartosc
            wartosc == "*" -> adres
            else -> "$adres/$wartosc"
        }
    }

    // ------------------------------------------------------------------ RTP

    /**
     * Ramki przeplatane: `$` (0x24), numer kanału, długość 16 bitów big-endian, pakiet RTP.
     * Kanał nieparzysty to RTCP — pomijamy, nie jest nam do niczego potrzebny.
     */
    private fun czytajPrzeplot(we: DataInputStream, moje: Int) {
        val sk = Skladanie()
        while (!zamkniety && moje == pokolenie) {
            var b = we.read()
            if (b < 0) throw IllegalStateException("koniec strumienia RTP")
            if (b != 0x24) continue                       // szukamy znacznika '$'
            val kanal = we.read()
            val dlugosc = (we.read() shl 8) or we.read()
            if (dlugosc <= 0 || dlugosc > MAKS_PAKIET) throw IllegalStateException("zła długość RTP: $dlugosc")
            val pakiet = ByteArray(dlugosc)
            we.readFully(pakiet)
            if (kanal != 0) continue                      // RTCP albo inny tor
            rozpakuj(pakiet, sk)
        }
    }

    /** Wielokrotnie używany bufor sklejania `FU-A` — rośnie do największej ramki i zostaje. */
    /**
     * Składanie **jednostki dostępu** — jednej klatki — z pakietów RTP.
     *
     * Klatka kończy się pakietem z ustawionym **bitem znacznika** (marker). Dopiero wtedy
     * oddajemy całość dekoderowi. Po drodze pilnujemy ciągłości numerów kolejnych pakietów:
     * dziura znaczy, że klatka jest niepełna, więc **wyrzucamy ją zamiast dekodować**.
     * Dekodowanie połówki klatki jest gorsze niż jej brak — to właśnie od tego brały się
     * smugi ciągnące się aż do następnej klatki kluczowej.
     */
    private class Skladanie {
        var bufor = ByteArray(256 * 1024)
        var ile = 0
        var uszkodzona = false
        var wToku = false
        var oczekiwanyNumer = -1

        /** Znacznik czasu RTP bieżącej klatki — jego zmiana kończy poprzednią. */
        var czas = -1L

        /** Pierwszą złożoną jednostkę porzucamy: prawie na pewno zaczęliśmy w jej środku. */
        var pierwsza = true

        fun dopisz(zrodlo: ByteArray, offset: Int, dlugosc: Int, zeZnacznikiem: Boolean) {
            val potrzeba = ile + dlugosc + if (zeZnacznikiem) 4 else 0
            if (potrzeba > bufor.size) bufor = bufor.copyOf(maxOf(bufor.size * 2, potrzeba))
            if (zeZnacznikiem) {
                bufor[ile] = 0; bufor[ile + 1] = 0; bufor[ile + 2] = 0; bufor[ile + 3] = 1
                ile += 4
            }
            System.arraycopy(zrodlo, offset, bufor, ile, dlugosc)
            ile += dlugosc
            wToku = true
        }

        fun wyczysc() {
            ile = 0
            uszkodzona = false
            wToku = false
        }
    }

    private fun rozpakuj(pakiet: ByteArray, sk: Skladanie) {
        if (pakiet.size < 12) return
        val numer = ((pakiet[2].toInt() and 0xFF) shl 8) or (pakiet[3].toInt() and 0xFF)
        val czasRtp = ((pakiet[4].toLong() and 0xFF) shl 24) or ((pakiet[5].toLong() and 0xFF) shl 16) or
            ((pakiet[6].toLong() and 0xFF) shl 8) or (pakiet[7].toLong() and 0xFF)
        if (sk.oczekiwanyNumer >= 0 && numer != sk.oczekiwanyNumer) {
            // Dziura w numeracji — reszta tej klatki jest nie do odtworzenia.
            sk.uszkodzona = true
            zgubione++
        }
        sk.oczekiwanyNumer = (numer + 1) and 0xFFFF
        val znacznikKonca = (pakiet[1].toInt() and 0x80) != 0

        // ### Granica klatki: znacznik ALBO zmiana czasu RTP
        //
        // Wszystkie pakiety jednej klatki noszą ten sam znacznik czasu, a koniec klatki
        // powinien mieć ustawiony bit znacznika. „Powinien" — nie każdy koder to robi
        // konsekwentnie, a wtedy dwie klatki sklejają się w jedną i dekoder oddaje
        // rozmyty, pokratkowany obraz. Zgłoszone przez operatora jako „pojedyncze
        // smużenia i nieostrości z pikseli" przy zerowej utracie pakietów — a po TCP
        // zgubić się nie mogą, więc winne mogło być tylko ramkowanie po naszej stronie.
        if (sk.wToku && sk.czas >= 0 && czasRtp != sk.czas) oddaj(sk)
        sk.czas = czasRtp

        val csrc = pakiet[0].toInt() and 0x0F
        val rozszerzenie = (pakiet[0].toInt() and 0x10) != 0
        var i = 12 + csrc * 4
        if (rozszerzenie) {
            if (pakiet.size < i + 4) return
            val dlugoscRozsz = ((pakiet[i + 2].toInt() and 0xFF) shl 8) or (pakiet[i + 3].toInt() and 0xFF)
            i += 4 + dlugoscRozsz * 4
        }
        if (i >= pakiet.size) return

        when (val typ = pakiet[i].toInt() and 0x1F) {
            in 1..23 -> sk.dopisz(pakiet, i, pakiet.size - i, zeZnacznikiem = true)

            24 -> {                                        // STAP-A: kilka NAL w jednym pakiecie
                var j = i + 1
                while (j + 2 <= pakiet.size) {
                    val dl = ((pakiet[j].toInt() and 0xFF) shl 8) or (pakiet[j + 1].toInt() and 0xFF)
                    j += 2
                    if (dl <= 0 || j + dl > pakiet.size) break
                    sk.dopisz(pakiet, j, dl, zeZnacznikiem = true)
                    j += dl
                }
            }

            28 -> {                                        // FU-A: jedna NAL w kilku pakietach
                if (pakiet.size < i + 2) return
                val naglowekFu = pakiet[i + 1].toInt()
                val poczatek = (naglowekFu and 0x80) != 0
                val typNal = naglowekFu and 0x1F
                if (poczatek) {
                    czolo[0] = ((pakiet[i].toInt() and 0xE0) or typNal).toByte()
                    sk.dopisz(czolo, 0, 1, zeZnacznikiem = true)
                }
                sk.dopisz(pakiet, i + 2, pakiet.size - i - 2, zeZnacznikiem = false)
            }

            else -> Dziennik.szczegol("wideo", "tor RTSP: pomijam pakiet RTP typu $typ")
        }

        if (znacznikKonca && sk.wToku) oddaj(sk)
    }

    /** Oddaje złożoną jednostkę dostępu dekoderowi — albo ją porzuca, gdy jest niepełna. */
    private fun oddaj(sk: Skladanie) {
        when {
            sk.pierwsza -> sk.pierwsza = false          // zaczęliśmy w środku klatki
            sk.uszkodzona -> {
                porzucone++
                if (porzucone % 30 == 1) {
                    Dziennik.ostrzezenie(
                        "wideo",
                        "tor RTSP: porzucono $porzucone niepełnych klatek (zgubionych pakietów: $zgubione)",
                    )
                }
            }
            else -> {
                odnotujPrzyjscie(sk.ile)
                // ⚠ Od 2026-08-28 wyrównywaniem zajmuje się [RysownikH264] — ma i kolejkę,
                // i wydawanie na siatce dosuniętej do odświeżeń ekranu. [KolejkaWyrownujaca]
                // niżej jest **nieużywana**; zostaje jako zapis wcześniejszego podejścia,
                // które wyrównywało tylko wejście dekodera i dlatego nie wystarczyło.
                // Podwójne wyrównywanie dołożyłoby opóźnienia i nic nie poprawiło.
                rysownik.podaj(sk.bufor.copyOf(sk.ile))
            }
        }
        sk.wyczysc()
    }

    /**
     * Pomiar **chwili złożenia klatki** — przed dekoderem i przed wyświetlaniem.
     *
     * Rozstrzyga pytanie, którego nie da się rozstrzygnąć z `SurfaceFlinger --latency`:
     * czy przerwy w obrazie powstają już na wejściu (sieć), czy dopiero u nas. Jeśli tu
     * widać równe 33 ms, a na ekranie dziury — winne jest nasze wyświetlanie. Jeśli dziury
     * są już tutaj — dane przychodzą zrywami i żadna zmiana po naszej stronie tego nie
     * naprawi.
     */
    private fun odnotujPrzyjscie(rozmiar: Int) {
        val teraz = System.nanoTime()
        if (poprzedniePrzyjscie > 0) {
            val odstepMs = ((teraz - poprzedniePrzyjscie) / 1_000_000).toInt()
            odstepy.add(odstepMs)
            // Do średniej nie liczymy przerw przy klatkach kluczowych — inaczej tempo
            // wydawania rozjechałoby się w stronę tych dziur, zamiast je wygładzać.
            if (odstepMs in 10..70) {
                sredniOdstepNs = (sredniOdstepNs * 15 + odstepMs * 1_000_000L) / 16
            }
            if (odstepMs > najwiekszyOdstep) {
                najwiekszyOdstep = odstepMs
                najwiekszaRamka = rozmiar
            }
            if (odstepy.size >= PROBEK_DO_RAPORTU) {
                val posortowane = odstepy.sorted()
                val mediana = posortowane[posortowane.size / 2]
                val duze = odstepy.count { it > 60 }
                Dziennik.info(
                    "wideo",
                    "tor RTSP: przyjście — mediana $mediana ms, >60 ms: $duze/${odstepy.size}, " +
                        "maks $najwiekszyOdstep ms (klatka $najwiekszaRamka B); " +
                        "kolejka: min $minKolejka, śr ${sumaKolejka / maxOf(1, probKolejki)}, " +
                        "maks $maksKolejka; tempo wydawania ${sredniOdstepNs / 1_000_000} ms",
                )
                odstepy.clear()
                najwiekszyOdstep = 0
                minKolejka = 999; maksKolejka = 0; sumaKolejka = 0; probKolejki = 0
            }
        }
        poprzedniePrzyjscie = teraz
    }

    /**
     * Kolejka wyrównująca — **przytrzymuje klatki przed dekoderem** i wydaje je równo.
     *
     * ### Dlaczego przed, a nie po dekoderze
     *
     * Zmierzone 2026-08-28 prosto z sieci: klatki przychodzą z medianą 39 ms, ale
     * **8-9 razy na 250 klatek** robi się przerwa 140-170 ms — i **zawsze przy klatce
     * kluczowej** (92 kB, 102 kB, 126 kB). Kamera wysyła co sekundę klatkę kilkadziesiąt
     * razy większą od zwykłej, jej przesłanie trwa i przez ten czas nie ma nic.
     *
     * Próba wyrównania tego **po** dekoderze, czasem prezentacji, nie działa: kolejka
     * buforów `SurfaceView` mieści trzy klatki, czyli ok. 100 ms, a przerwa sięga 170 ms.
     * Podniesienie wyprzedzenia do 220 ms nie zmieniło nic — nie było gdzie tego zapasu
     * trzymać.
     *
     * Tutaj limitu nie ma. Trzymamy [ZAPAS_MS] gotowych klatek i podajemy je dekoderowi
     * co [OKRES_MS]. Cena to [ZAPAS_MS] opóźnienia obrazu — świadoma i jedyna, jaką
     * ten problem ma.
     */
    private inner class KolejkaWyrownujaca {
        private val klatki = java.util.concurrent.LinkedBlockingQueue<ByteArray>(60)

        @Volatile
        private var pracuje = false
        private var watekWydajacy: Thread? = null

        fun dolacz(zrodlo: ByteArray, dlugosc: Int) {
            if (!klatki.offer(zrodlo.copyOf(dlugosc))) {
                klatki.poll()                              // najstarsza ustępuje — liczy się świeżość
                klatki.offer(zrodlo.copyOf(dlugosc))
            }
            if (!pracuje) uruchomWydawanie()
        }

        private fun uruchomWydawanie() {
            pracuje = true
            watekWydajacy = Thread({
                // ### Najpierw zapas, dopiero potem wydawanie
                //
                // Pierwsza wersja zaczynała wydawać od razu i kolejka nigdy nie rosła:
                // wydawanie co 33 ms przy przyjściu co 39 ms opróżnia ją szybciej, niż się
                // napełnia. Zapas trzeba **uzbierać**, zanim się z niego korzysta — inaczej
                // nie ma czym przykryć przerwy przy klatce kluczowej.
                //
                // Zmierzone: klatka kluczowa ma 60-106 kB wobec 2-6 kB zwykłej i zajmuje
                // łącze na 150-200 ms. Stąd próg [PROG_STARTU] klatek.
                var okresNs = sredniOdstepNs
                var nastepna = 0L
                while (pracuje && !zamkniety) {
                    if (nastepna == 0L) {
                        // Napełnianie — czekamy, aż uzbiera się poduszka.
                        while (pracuje && !zamkniety && klatki.size < PROG_STARTU) {
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                                return@Thread
                            }
                        }
                        if (!pracuje || zamkniety) return@Thread
                        nastepna = System.nanoTime()
                    }
                    val klatka = try {
                        klatki.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (klatka == null) {
                        nastepna = 0L                      // strumień zamilkł — zbieramy od nowa
                        continue
                    }

                    // ### Tempo wydawania = ZMIERZONE tempo przychodzenia
                    //
                    // Nie 30 klatek na sekundę „bo tyle nadaje kamera". Pomiar z sieci mówi
                    // co innego: mediana przyjścia **39 ms**, czyli 25,6 kl./s — część klatek
                    // nie dociera. Wydawanie co 33 ms opróżniało kolejkę szybciej, niż się
                    // napełniała, więc poduszka znikała po sekundzie i klatka kluczowa znów
                    // zatrzymywała obraz. Poprzednia korekta o ±4 ms była na to za słaba.
                    //
                    // Bierzemy więc średnią kroczącą rzeczywistych odstępów i wydajemy w jej
                    // tempie, a głębokość kolejki koryguje tylko drobno: za dużo zapasu —
                    // odrobinę szybciej, za mało — odrobinę wolniej.
                    // Odbudowa poduszki musi być SZYBKA. Między klatkami kluczowymi jest
                    // tylko ok. 30 klatek; korekta o 8 % potrzebowałaby kilku sekund, więc
                    // poduszka istniała tylko raz, na starcie, i pierwsza dziura ją zjadała.
                    // Zwalniamy więc wyraźnie, dopóki zapas nie wróci — o 25 % to niecałe
                    // 8 ms na klatkę, dla oka niewidoczne, a cushion wraca w sekundę.
                    val ile = klatki.size
                    val zmierzony = sredniOdstepNs
                    okresNs = when {
                        ile >= PROG_STARTU * 2 -> (zmierzony * 80) / 100
                        ile >= PROG_STARTU -> zmierzony
                        else -> (zmierzony * 125) / 100
                    }

                    val czekaj = nastepna - System.nanoTime()
                    if (czekaj > 0) {
                        try {
                            Thread.sleep(czekaj / 1_000_000, (czekaj % 1_000_000).toInt())
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                    if (ile < minKolejka) minKolejka = ile
                    if (ile > maksKolejka) maksKolejka = ile
                    sumaKolejka += ile
                    probKolejki++

                    rysownik.podaj(klatka, 0, klatka.size)
                    nastepna += okresNs
                    val teraz = System.nanoTime()
                    if (nastepna < teraz) nastepna = teraz
                }
            }, "wideo-wydawanie").apply { isDaemon = true; start() }
        }

        fun zatrzymaj() {
            pracuje = false
            watekWydajacy?.interrupt()
            klatki.clear()
        }
    }

    private val kolejka = KolejkaWyrownujaca()

    private val czolo = ByteArray(1)

    /**
     * Średnia krocząca odstępów przyjścia — tempo, w jakim strumień NAPRAWDĘ dociera.
     * Startuje od 33 ms i dociąga się do rzeczywistości w kilkanaście klatek.
     */
    @Volatile
    private var sredniOdstepNs = 33_000_000L

    /** Głębokość kolejki — czy poduszka w ogóle powstaje i czy się utrzymuje. */
    @Volatile
    private var minKolejka = 999

    @Volatile
    private var maksKolejka = 0

    @Volatile
    private var sumaKolejka = 0L

    @Volatile
    private var probKolejki = 0

    private var poprzedniePrzyjscie = 0L
    private val odstepy = ArrayList<Int>(300)
    private var najwiekszyOdstep = 0
    private var najwiekszaRamka = 0

    /** Liczniki jakości odbioru — pokazywane w dzienniku, żeby dało się to ocenić z pola. */
    @Volatile
    private var zgubione = 0

    @Volatile
    private var porzucone = 0

    override fun zwolnij() {
        zamkniety = true
        pokolenie++
        watek?.interrupt()
        kolejka.zatrzymaj()
        rysownik.zwolnij()
    }

    companion object {
        const val PORT = 8554
        const val DOMYSLNY_HOST = "192.168.144.25"
        const val DOMYSLNA_SCIEZKA = "/main.264"

        private val ZNACZNIK = byteArrayOf(0, 0, 0, 1)
        private const val BUFOR = 64 * 1024
        private const val MAKS_PAKIET = 65535
        private const val CZAS_CZEKANIA_MS = 5000

        /** Ile czekamy, aż odstawiony wątek zejdzie, zanim ruszy następny tor. */
        private const val CZAS_DOGASANIA_MS = 1500L

        /** Kamera zamyka sesję po 60 s. Odnawiamy trzykrotnie częściej, niż trzeba. */
        private const val ODSTEP_SESJI_MS = 20_000L

        /** Numeracja podtrzymań startuje wysoko, żeby nie zderzyć się z CSeq uzgodnienia. */
        private const val BAZA_CSEQ_PODTRZYMANIA = 1000
        private val ODCZEKANIA_MS = longArrayOf(1000, 2000, 4000, 8000, 15000)

        /** Co tyle klatek (ok. 8 s) meldujemy rozkład przyjść. */
        private const val PROBEK_DO_RAPORTU = 250

        /** Ile obrazu trzymamy w zapasie. Musi przykryć zmierzoną przerwę przy klatce kluczowej. */
        private const val ZAPAS_MS = 250L

        /** Ile klatek zbieramy, zanim ruszy wydawanie. Osiem to ok. 250 ms obrazu. */
        private const val PROG_STARTU = 8

        /** Odstęp wydawania klatek dekoderowi — kamera nadaje 30 kl./s. */
        private const val OKRES_MS = 33L
    }
}
