package pl.dron15.zrzut

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlin.concurrent.thread

/**
 * Przechwytywanie ekranu aparatury i wysyłka obrazu na stację.
 *
 * ### Droga obrazu
 *
 *   ekran aparatury ──MediaProjection──► VirtualDisplay ──► Surface kodera
 *                    ──MediaCodec H.264──► NadajnikTcp ──► stacja ──ffmpeg──► RTMP
 *
 * Koder dostaje obraz **wprost na swoją powierzchnię wejściową**, więc klatki nie
 * przechodzą przez pamięć aplikacji ani przez procesor: rysuje je układ graficzny,
 * a koduje sprzętowy koder. To jedyny wariant, który na aparaturze ma szansę
 * nadążyć obok działającego DJI Pilot 2.
 *
 * ### ⛔ Pauza, nie zatrzymanie — to jest sedno ergonomii w locie
 *
 * Android pyta o zgodę na przechwytywanie ekranu **przy każdym nowym uruchomieniu**
 * i nie da się tego zapamiętać (jest to celowe zabezpieczenie systemu). Gdyby STOP
 * zwalniał przechwytywanie, każde ponowne włączenie w powietrzu oznaczałoby okienko
 * systemowe do odklikania — pilotowi, który trzyma drążki.
 *
 * Dlatego zgodę bierzemy **raz**, a START i STOP przełączają tylko wysyłanie:
 *
 * | Stan | `MediaProjection` | koder i obraz wirtualny | gniazdo do stacji |
 * |---|---|---|---|
 * | nadaje | trzymana | pracują | otwarte |
 * | **pauza** | **trzymana** | zwolnione (zero obciążenia) | zamknięte |
 * | koniec | zwolniona | zwolnione | zamknięte |
 *
 * Wznowienie z pauzy tworzy nowy obraz wirtualny z **tej samej** zgody — bez pytania.
 *
 * ### Trzy drogi do tego samego przełącznika
 *
 * Pilot nie będzie wracał do naszej aplikacji, więc start i stop są osiągalne:
 * z **kafelka w szybkich ustawieniach** (jedno przeciągnięcie i dotknięcie z dowolnej
 * aplikacji), z **powiadomienia** i z ekranu aplikacji. Stan trzyma [Stan], żeby
 * wszystkie trzy pokazywały to samo.
 *
 * ### ⚠ Ryzyko rozstrzygane dopiero na sprzęcie
 *
 * Jeśli DJI oznacza podgląd wideo jako `FLAG_SECURE`, dostaniemy czarny prostokąt
 * i nie da się tego obejść z aplikacji. Stacja wykrywa to sama po przepływności
 * (`server/zrzut.mjs`) i pisze o tym wprost w dzienniku.
 */
class UslugaZrzutu : Service() {

    private var projekcja: MediaProjection? = null
    private var ekranWirtualny: VirtualDisplay? = null
    private var koder: MediaCodec? = null
    private var powierzchnia: Surface? = null
    private var nadajnik: NadajnikTcp? = null
    private var watek: Thread? = null

    @Volatile private var nadawaj = false
    private var ustawienia = Ustawienia()

    private data class Ustawienia(
        val adres: String = "",
        val port: Int = 5601,
        val haslo: String = "",
        val fps: Int = 15,
        val bitrate: Int = 3_000_000,
        val podzialka: Int = 100,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AKCJA_PAUZA -> { pauza("operator"); return START_STICKY }
            AKCJA_WZNOW -> { wznow(); return START_STICKY }
            AKCJA_PRZELACZ -> { if (nadawaj) pauza("operator") else wznow(); return START_STICKY }
            AKCJA_KONIEC -> { zakoncz("operator"); return START_NOT_STICKY }
        }

        // ⛔ Powiadomienie MUSI pójść przed `getMediaProjection`, inaczej Android 10+
        // rzuca wyjątkiem: zgody na przechwytywanie nie wolno użyć poza usługą
        // pierwszoplanową, która już się ogłosiła.
        startForeground(ID_POWIADOMIENIA, powiadomienie())

        if (projekcja == null) {
            val kod = intent?.getIntExtra(DODATEK_KOD, 0) ?: 0
            val dane = intent?.getParcelableExtra<Intent>(DODATEK_DANE)
            if (dane == null) {
                zglos("Brak zgody na przechwytywanie ekranu")
                stopSelf()
                return START_NOT_STICKY
            }
            ustawienia = Ustawienia(
                adres = intent.getStringExtra(DODATEK_ADRES).orEmpty(),
                port = intent.getIntExtra(DODATEK_PORT, 5601),
                haslo = intent.getStringExtra(DODATEK_HASLO).orEmpty(),
                fps = intent.getIntExtra(DODATEK_FPS, 15),
                bitrate = intent.getIntExtra(DODATEK_BITRATE, 3_000_000),
                podzialka = intent.getIntExtra(DODATEK_PODZIALKA, 100),
            )
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projekcja = mgr.getMediaProjection(kod, dane)
            if (projekcja == null) {
                zglos("System nie oddał uchwytu do ekranu")
                stopSelf()
                return START_NOT_STICKY
            }
            // Gdy zgoda zostanie cofnięta (np. operator naciśnie „Zatrzymaj" w pasku
            // systemu), sprzątamy sami — inaczej zostaje usługa bez obrazu.
            projekcja?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { zakoncz("system cofnął zgodę") }
            }, null)
            Stan.gotowy = true
        }
        wznow()
        return START_STICKY
    }

    // ---- przełączanie nadawania -------------------------------------------------

    private fun wznow() {
        if (nadawaj) return
        val p = projekcja ?: run { zglos("Najpierw uruchom z ekranu aplikacji"); return }
        nadawaj = true
        Stan.nadaje = true
        watek = thread(name = "zrzut-koder") { pracuj(p) }
    }

    private fun pauza(powod: String) {
        if (!nadawaj) return
        nadawaj = false
        Stan.nadaje = false
        Stan.plynie = false
        Stan.kbs = 0
        zglos("Wstrzymane ($powod) — wznowienie bez pytania o zgodę")
        // Wątek sam posprząta obraz wirtualny, koder i gniazdo.
    }

    private fun zakoncz(powod: String) {
        nadawaj = false
        Stan.czysty()
        try { projekcja?.stop() } catch (_: Exception) {}
        projekcja = null
        zglos("Zakończone ($powod)")
        stopForeground(true)
        stopSelf()
    }

    // ---- właściwa praca ---------------------------------------------------------

    /**
     * Pętla nadawania. Sama zestawia łącze, sama je odtwarza po zerwaniu i sama
     * sprząta przy pauzie.
     *
     * ⛔ **Zerwane łącze nie może kończyć przechwytywania.** W locie sieć potrafi
     * mrugnąć; gdyby aplikacja wtedy odpuszczała, pilot musiałby wrócić do niej
     * i odklikać zgodę od nowa. Dlatego ponawiamy w miejscu, z rosnącą przerwą.
     */
    private fun pracuj(p: MediaProjection) {
        var przerwa = 1000L
        while (nadawaj) {
            val ok = jedenPrzebieg(p)
            if (!nadawaj) break
            if (!ok) {
                Stan.ponowien += 1
                zglos("Łącze zerwane — ponawiam za ${przerwa / 1000} s (${Stan.ponowien}.)")
                powiadom()
                var czekano = 0L
                while (nadawaj && czekano < przerwa) { Thread.sleep(200); czekano += 200 }
                przerwa = (przerwa * 2).coerceAtMost(15_000)
            } else {
                przerwa = 1000L
            }
        }
        sprzatajTor()
        powiadom()
    }

    /** Jedno zestawienie łącza i nadawanie aż do zerwania albo pauzy. */
    private fun jedenPrzebieg(p: MediaProjection): Boolean {
        val (szer, wys, gestosc) = rozmiarObrazu()
        val n = NadajnikTcp(ustawienia.adres, ustawienia.port, ustawienia.haslo)
        nadajnik = n
        try {
            n.polacz(szer, wys, ustawienia.fps)
        } catch (e: Exception) {
            zglos("Stacja nie odpowiada: ${e.message}")
            return false
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, szer, wys).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, ustawienia.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, ustawienia.fps)
            // Klatka kluczowa co sekundę: widz dołączający do strumienia nie może
            // czekać na obraz dłużej niż to konieczne.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }
        val k: MediaCodec
        try {
            k = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            k.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            powierzchnia = k.createInputSurface()
            k.start()
        } catch (e: Exception) {
            zglos("Koder obrazu odmówił: ${e.message}")
            n.zamknij()
            return false
        }
        koder = k

        ekranWirtualny = p.createVirtualDisplay(
            "dron15-zrzut", szer, wys, gestosc,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            powierzchnia, null, null,
        )
        if (ekranWirtualny == null) {
            zglos("Nie udało się utworzyć obrazu wirtualnego")
            sprzatajTor()
            return false
        }

        // Dopiero TERAZ obraz naprawdę idzie: gniazdo stoi, koder pracuje, obraz
        // wirtualny istnieje. Wcześniej `nadaje` znaczyło samą wolę operatora.
        Stan.plynie = true
        zglos("Nadaje ${szer}×$wys @ ${ustawienia.fps} kl./s")
        powiadom()

        val info = MediaCodec.BufferInfo()
        var odliczanie = System.currentTimeMillis()
        var wOknie = 0L
        var poczatek = System.currentTimeMillis()
        var sekundCiszy = 0

        while (nadawaj) {
            val nr = try {
                k.dequeueOutputBuffer(info, 100_000)
            } catch (e: Exception) {
                Log.w(TAG, "koder przerwał: ${e.message}"); sprzatajTor(); return false
            }
            if (nr < 0) continue
            val bufor = k.getOutputBuffer(nr)
            if (bufor != null && info.size > 0) {
                bufor.position(info.offset)
                bufor.limit(info.offset + info.size)
                // `csd` (SPS/PPS) wysyłamy jak zwykłe dane: w strumieniu Annex-B to
                // po prostu kolejne jednostki NAL, a ffmpeg oczekuje ich w środku.
                if (!n.wyslij(bufor, info.size)) {
                    k.releaseOutputBuffer(nr, false)
                    sprzatajTor()
                    return false
                }
                wOknie += info.size
            }
            k.releaseOutputBuffer(nr, false)

            // Miernik dla operatora: przepływność i czas nadawania. Liczony rzadko,
            // żeby nie obciążać pętli, która ma nadążać za koderem.
            val teraz = System.currentTimeMillis()
            if (teraz - odliczanie >= 1000) {
                Stan.kbs = ((wOknie * 8) / (teraz - odliczanie)).toInt()
                Stan.sekund = (teraz - poczatek) / 1000
                wOknie = 0
                odliczanie = teraz

                // ⛔ Czarny ekran koduje się do niczego: kilkanaście kb/s zamiast
                // setek. Ta sama miara, co po stronie stacji (`PROG_PUSTEGO_KBS`),
                // tylko liczona u źródła — dzięki temu operator dowiaduje się o tym
                // na aparaturze, a nie z dziennika serwera, którego w polu nie widzi.
                // Pierwsze sekundy pomijamy: koder rozbiega się wolniej niż zegar.
                if (Stan.sekund >= 3) {
                    if (Stan.kbs < PROG_CZERNI_KBS) sekundCiszy += 1 else sekundCiszy = 0
                    val czern = sekundCiszy >= SEKUND_DO_PODEJRZENIA
                    if (czern != Stan.czern) {
                        Stan.czern = czern
                        zglos(
                            if (czern)
                                "Obraz prawie pusty (${Stan.kbs} kb/s) — możliwa blokada zrzutu przez DJI"
                            else "Obraz wrócił (${Stan.kbs} kb/s)"
                        )
                    }
                }
                powiadom()
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        sprzatajTor()
        return true
    }

    /** Zwalnia koder, obraz wirtualny i gniazdo — ale NIE zgodę na przechwytywanie. */
    private fun sprzatajTor() {
        try { ekranWirtualny?.release() } catch (_: Exception) {}
        try { koder?.stop(); koder?.release() } catch (_: Exception) {}
        try { powierzchnia?.release() } catch (_: Exception) {}
        nadajnik?.zamknij()
        ekranWirtualny = null; koder = null; powierzchnia = null; nadajnik = null
        Stan.kbs = 0
        Stan.plynie = false
        Stan.czern = false
    }

    /**
     * Rozmiar kodowanego obrazu.
     *
     * ⚠ Zaokrąglamy w dół do wielokrotności 16: koder sprzętowy potrafi odmówić
     * konfiguracji przy nietypowych wymiarach, a ekrany aparatur bywają nietypowe.
     * Podziałka pozwala nadawać mniejszy obraz niż ekran — mniej pasma i mniej
     * pracy kodera, co w locie znaczy więcej niż ostrość.
     */
    private fun rozmiarObrazu(): Triple<Int, Int, Int> {
        val m = DisplayMetrics()
        val okna = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        okna.defaultDisplay.getRealMetrics(m)
        val u = ustawienia.podzialka.coerceIn(25, 100)
        val szer = ((m.widthPixels * u / 100) / 16) * 16
        val wys = ((m.heightPixels * u / 100) / 16) * 16
        return Triple(szer.coerceAtLeast(320), wys.coerceAtLeast(240), m.densityDpi)
    }

    override fun onDestroy() {
        nadawaj = false
        sprzatajTor()
        try { projekcja?.stop() } catch (_: Exception) {}
        projekcja = null
        Stan.czysty()
        super.onDestroy()
    }

    // ---- powiadomienie: to jest główny pilot w locie -----------------------------

    /**
     * Powiadomienie z dwoma klawiszami.
     *
     * ⛔ To NIE jest ozdoba — w locie jest to najszybsza droga do zatrzymania obrazu:
     * jedno przeciągnięcie paska z dowolnej aplikacji i jedno dotknięcie. Dlatego
     * niesie stan (przepływność, czas, ponowienia), a nie samą nazwę programu.
     */
    private fun powiadomienie(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val kanal = NotificationChannel(KANAL, "Zrzut ekranu", NotificationManager.IMPORTANCE_LOW)
            kanal.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(kanal)
        }
        fun akcja(nazwa: String, co: String): Notification.Action {
            val i = Intent(this, UslugaZrzutu::class.java).setAction(co)
            val cz = PendingIntent.getService(
                this, co.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(null, nazwa, cz).build()
        }
        // ⛔ Powiadomienie nie ma prawa ogłaszać nadawania, gdy łącze leży — to jest
        // jedyna rzecz, jaką pilot widzi w locie, i musi mówić prawdę.
        val tytul = when {
            Stan.nadaje && Stan.plynie -> "NADAJE — obraz idzie na stację"
            Stan.nadaje -> "ŁĄCZY SIĘ — obraz NIE idzie"
            else -> "WSTRZYMANE — obraz nie idzie"
        }
        val tresc = when {
            Stan.nadaje && Stan.plynie ->
                "${Stan.kbs} kb/s · ${Stan.sekund} s" +
                    (if (Stan.ponowien > 0) " · ponowień: ${Stan.ponowien}" else "") +
                    (if (Stan.czern) " · OBRAZ PUSTY" else "")
            Stan.nadaje -> "Stacja nie odpowiada — ponawiam (${Stan.ponowien})"
            else -> "Wznowienie nie wymaga zgody — jedno dotknięcie"
        }
        val otworz = PendingIntent.getActivity(
            this, 0, Intent(this, GlownaAktywnosc::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, KANAL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(tytul)
            .setContentText(tresc)
            .setSmallIcon(
                if (Stan.nadaje) android.R.drawable.presence_video_online
                else android.R.drawable.presence_video_away
            )
            .setOngoing(true)
            .setContentIntent(otworz)
            .addAction(
                if (Stan.nadaje) akcja("WSTRZYMAJ", AKCJA_PAUZA) else akcja("WZNÓW", AKCJA_WZNOW)
            )
            .addAction(akcja("ZAKOŃCZ", AKCJA_KONIEC))
            .build()
    }

    private fun powiadom() {
        try {
            getSystemService(NotificationManager::class.java).notify(ID_POWIADOMIENIA, powiadomienie())
        } catch (_: Exception) { /* usługa mogła już zniknąć */ }
        // Kafelek szybkich ustawień ma pokazywać to samo, co powiadomienie.
        KafelekZrzutu.odswiez(this)
    }

    /** Meldunek na ekran aplikacji — operator ma widzieć powód, nie tylko skutek. */
    private fun zglos(tekst: String) {
        Log.i(TAG, tekst)
        Stan.opis = tekst
        sendBroadcast(Intent(AKCJA_STAN).setPackage(packageName).putExtra(DODATEK_STAN, tekst))
        powiadom()
    }

    companion object {
        const val TAG = "zrzut.usluga"
        const val KANAL = "zrzut"
        const val ID_POWIADOMIENIA = 7

        /**
         * Poniżej tylu kb/s uznajemy obraz za pusty. Wartość wzięta ze stacji
         * (`server/zrzut.mjs`, `PROG_PUSTEGO_KBS`), żeby obie strony mówiły to samo.
         * Nawet nieruchomy ekran z zegarem daje wielokrotnie więcej.
         */
        const val PROG_CZERNI_KBS = 20

        /** Ile sekund z rzędu, żeby nie krzyczeć po jednym mrugnięciu kodera. */
        const val SEKUND_DO_PODEJRZENIA = 6

        const val AKCJA_PAUZA = "pl.dron15.zrzut.PAUZA"
        const val AKCJA_WZNOW = "pl.dron15.zrzut.WZNOW"
        const val AKCJA_PRZELACZ = "pl.dron15.zrzut.PRZELACZ"
        const val AKCJA_KONIEC = "pl.dron15.zrzut.KONIEC"
        const val AKCJA_STAN = "pl.dron15.zrzut.STAN"

        const val DODATEK_STAN = "stan"
        const val DODATEK_KOD = "kod"
        const val DODATEK_DANE = "dane"
        const val DODATEK_ADRES = "adres"
        const val DODATEK_PORT = "port"
        const val DODATEK_HASLO = "haslo"
        const val DODATEK_FPS = "fps"
        const val DODATEK_BITRATE = "bitrate"
        const val DODATEK_PODZIALKA = "podzialka"
    }
}
