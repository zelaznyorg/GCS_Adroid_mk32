package pl.dron15.cockpit.net.siyi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.dron15.cockpit.diag.Dziennik
import pl.dron15.cockpit.net.SiecPokladowa
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Sterowanie głowicą ZR30 po UDP (192.168.144.25:37260) — SIYI Gimbal SDK.
 *
 * Ta droga **omija kontroler lotu**. W tym projekcie łącze szeregowe FC↔ZR30 było źródłem
 * najdłuższej awarii (poz. 28 w CLAUDE.md), więc kokpit steruje kamerą po sieci lokalnej:
 * działa nawet przy MNT1_TYPE=0 i nie zajmuje pasma łącza radiowego.
 *
 * Ramkowanie i CRC-16/XMODEM sprawdzone wobec przykładów producenta — patrz
 * narzedzia/siyi_gimbal.py (`--selftest`), ten kod jest jego odpowiednikiem w Kotlinie.
 */
class KlientSiyi(
    private val host: String = DOMYSLNY_HOST,
    private val port: Int = DOMYSLNY_PORT,
) {
    data class StanGlowicy(
        val pitch: Float = 0f,
        val yaw: Float = 0f,
        val roll: Float = 0f,
        val zoom: Float = 1f,
        val zoomMaksymalny: Float = 30f,
        val nagrywa: Boolean = false,
        val trybRuchu: String = "—",
        val odpowiada: Boolean = false,
    )

    private var zadanie: Job? = null

    @Volatile
    private var gniazdo: DatagramSocket? = null

    /** Numer ramki. Dotykany wyłącznie z wątku `glowica-tx`, więc nie wymaga synchronizacji. */
    private var numer = 0

    /** Kolejka wysyłkowa — jeden wątek, żeby komendy wychodziły w kolejności nadania. */
    private var nadajnik: ExecutorService = nowyNadajnik()

    @Volatile
    var stan = StanGlowicy()
        private set

    var przyZmianie: ((StanGlowicy) -> Unit)? = null

    fun start(zakres: CoroutineScope) {
        if (zadanie?.isActive == true) return
        if (nadajnik.isShutdown) nadajnik = nowyNadajnik()
        // Jak przy telemetrii: wyjątek w tej korutynie ma zabrać głowicę, nie aplikację.
        zadanie = zakres.launch(Dispatchers.IO + Dziennik.uchwytKorutyny("glowica")) { petla() }
    }

    fun stop() {
        zadanie?.cancel()
        nadajnik.shutdownNow()
        gniazdo?.close()
        gniazdo = null
    }

    private fun nowyNadajnik(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "glowica-tx").apply { isDaemon = true } }

    // ------------------------------------------------------------------ komendy

    /**
     * CMD 0x07 — obrót ciągły. Prędkości −100…100; zero zatrzymuje.
     *
     * ### Dławienie, bez którego sterowanie szarpie
     *
     * `detectDragGestures` w Compose woła to przy **każdym** zdarzeniu dotyku, czyli
     * 60–120 razy na sekundę. Głowica tego nie wyrabia: przy równoległym odpytywaniu
     * o orientację zmierzyliśmy 2026-08-26 **24 zgubione odpowiedzi na 90 zapytań**.
     * Efekt na ekranie to ruch szarpany i spóźniony — nie dlatego, że komend jest za mało,
     * tylko że jest ich za dużo.
     *
     * `0x07` jest komendą **ciągłą**: głowica kręci się z zadaną prędkością do następnej
     * zmiany. Powtarzanie tej samej wartości sto razy na sekundę nic więc nie wnosi.
     * Wysyłamy najwyżej co [ODSTEP_OBROTU_MS] i tylko wtedy, gdy prędkość faktycznie
     * się zmieniła.
     *
     * **Zatrzymanie (0, 0) idzie zawsze i natychmiast.** To jedyna komenda, której nie
     * wolno zgubić ani opóźnić: zdławiona zostawiłaby głowicę w ruchu po puszczeniu palca.
     */
    fun obroc(yaw: Int, pitch: Int) {
        val y = yaw.coerceIn(-100, 100)
        val p = pitch.coerceIn(-100, 100)
        val teraz = System.currentTimeMillis()
        if (y != 0 || p != 0) {
            if (y == ostatniYaw && p == ostatniPitch) return
            if (teraz - ostatniObrotMs < ODSTEP_OBROTU_MS) return
        }
        ostatniYaw = y
        ostatniPitch = p
        ostatniObrotMs = teraz
        wyslij(0x07, byteArrayOf(y.toByte(), p.toByte()))
    }

    @Volatile private var ostatniObrotMs = 0L
    @Volatile private var ostatniYaw = 0
    @Volatile private var ostatniPitch = 0

    fun stopObrotu() = obroc(0, 0)

    /** CMD 0x0E — kąt bezwzględny. ZR30: yaw ±270°, pitch −90…+25°. */
    fun ustawKat(yawSt: Float, pitchSt: Float) {
        val y = (yawSt.coerceIn(-270f, 270f) * 10).roundToInt()
        val p = (pitchSt.coerceIn(-90f, 25f) * 10).roundToInt()
        wyslij(0x0E, byteArrayOf((y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
            (p and 0xFF).toByte(), ((p shr 8) and 0xFF).toByte()))
    }

    /** CMD 0x05 — zoom ręczny: 1 do wewnątrz, 0 stop, −1 na zewnątrz. */
    fun zoom(kierunek: Int) = wyslij(0x05, byteArrayOf(kierunek.coerceIn(-1, 1).toByte()))

    fun centruj() = wyslij(0x08, byteArrayOf(1))

    fun zdjecie() = wyslij(0x0C, byteArrayOf(0))

    fun nagrywanie() = wyslij(0x0C, byteArrayOf(2))

    // ---------------------------------------------------------------- dołożone dla M3
    //
    // Komendy z tabeli w dok/PRZEKAZANIE_M3.md §6, oznaczone tam jako „do dopisania".
    //
    // ⚠ W odróżnieniu od komend wyżej **nie są sprawdzone na sprzęcie**: te działające
    // przeszły weryfikację przez narzedzia/siyi_gimbal.py wobec przykładów producenta,
    // te niżej pochodzą z instrukcji i z przekazania. Układ ładunku może wymagać korekty
    // przy pierwszym podłączeniu głowicy — patrz §6 przekazania, krok 6 kolejności wdrożenia.

    /** CMD 0x0C funkcje 3–5 — tryb ruchu głowicy. */
    fun trybRuchu(tryb: TrybRuchu) = wyslij(0x0C, byteArrayOf(tryb.funkcja.toByte()))

    /**
     * CMD 0x0F — zoom bezwzględny 1,0…30,0×. Ładunek: część całkowita i dziesiąte.
     * ZR30 ma zoom optyczny 30×, więc powyżej tej wartości głowica i tak przytnie.
     */
    fun zoomBezwzgledny(krotnosc: Float) {
        val k = krotnosc.coerceIn(1f, 30f)
        val calosc = k.toInt()
        val dziesiate = ((k - calosc) * 10).roundToInt().coerceIn(0, 9)
        wyslij(0x0F, byteArrayOf(calosc.toByte(), dziesiate.toByte()))
    }

    /** CMD 0x18 — odczyt bieżącej krotności; CMD 0x16 — odczyt maksymalnej. */
    fun odpytajZoom() = wyslij(0x18)

    fun odpytajMaksymalnyZoom() = wyslij(0x16)

    /**
     * CMD 0x04 — ostrość w punkcie kadru. `x`, `y` to położenie dotknięcia, `szer`/`wys`
     * rozdzielczość obrazu, w której je zmierzono — bez niej głowica nie wie, do czego
     * odnieść współrzędne.
     */
    fun ostroscWPunkcie(x: Int, y: Int, szer: Int, wys: Int) =
        wyslij(0x04, i16(x) + i16(y) + i16(szer) + i16(wys))

    /** CMD 0x06 — ostrość ręczna: 1 dalej, −1 bliżej, 0 stop. */
    fun ostroscReczna(kierunek: Int) =
        wyslij(0x06, byteArrayOf(kierunek.coerceIn(-1, 1).toByte()))

    /** CMD 0x20 — odczyt ustawień strumienia. `typ`: 0 nagranie, 1 podgląd. */
    fun odpytajStrumien(typ: Int = 1) = wyslij(0x20, byteArrayOf(typ.toByte()))

    /**
     * CMD 0x21 — ustawienia strumienia: kodek, rozdzielczość, bitrate w kb/s.
     *
     * Ładunek ma **dziewięć** bajtów: `stream_type`, `VideoEncType`, `Resolution_L`,
     * `Resolution_H`, `VideoBitrate` i **`reserve`** na końcu. Do 2026-08-28 brakowało
     * tu ostatniego bajtu, przez co komenda wychodziła o bajt za krótka — razem z błędną
     * wartością kodeka (patrz [Kodek]) wystarczyło to, żeby zawiesić kamerę.
     *
     * ⚠ Zmierzone 2026-08-28: **kamera ignoruje żądany bitrate strumienia głównego.**
     * Odpowiada `sta = 1` (sukces), po czym odczyt pokazuje niezmienione 1570 kb/s —
     * tak samo przy żądaniu 2000 i 1000. Kodek i rozdzielczość przyjmuje. Tej liczby
     * najwyraźniej nie oddaje sterowaniu z zewnątrz.
     */
    fun ustawStrumien(typ: Int, kodek: Kodek, szer: Int, wys: Int, bitrateKbps: Int) =
        wyslij(0x21, byteArrayOf(typ.toByte(), kodek.wartosc.toByte()) +
                i16(szer) + i16(wys) + i16(bitrateKbps) + byteArrayOf(0))

    /**
     * CMD 0x80 — programowy restart kamery lub głowicy (instrukcja ZR30 v1.4, str. 58).
     *
     * Ładunek to **dwa bajty**: `Camera_reboot`, `Gimbal_reset`, każdy `0` = nie ruszaj,
     * `1` = zrestartuj. Rozdzielenie jest istotne — restart samej kamery **nie porusza
     * głowicą**, więc nie zmienia kierunku patrzenia.
     *
     * ⚠ Obraz znika na kilkanaście sekund. To jedyna droga odzyskania kamery bez cyklu
     * zasilania, a zasilana jest wprost z pakietu — w powietrzu wyłącznika nie ma.
     */
    fun restart(kamera: Boolean, glowica: Boolean) =
        wyslij(0x80, byteArrayOf(if (kamera) 1 else 0, if (glowica) 1 else 0))

    /**
     * CMD 0x25 — subskrypcja strumienia danych o położeniu i prędkościach osi.
     * `hz`: 0 wyłącza, dopuszczalne 2, 4, 5, 10, 20, 50, 100.
     */
    fun subskrybujDane(hz: Int = 5) = wyslij(0x25, byteArrayOf(1, hz.toByte()))

    enum class TrybRuchu(val funkcja: Int, val etykieta: String) {
        LOCK(3, "LOCK"), FOLLOW(4, "FOLLOW"), FPV(5, "FPV")
    }

    /**
     * ⛔ Wartości wprost z instrukcji ZR30 v1.4, `CMD 0x21`: **1 = H264, 2 = H265**.
     *
     * Do 2026-08-28 stało tu `H264(0), H265(1)` — przesunięte o jeden. Skutki były dwa
     * i oba paskudne: „H.264" wysyłało `0`, czyli wartość, której protokół nie zna,
     * a „H.265" wysyłało `1`, czyli w rzeczywistości **H.264**. Klawisz robił więc coś
     * innego, niż mówił.
     *
     * Tego samego dnia jedna taka komenda **zawiesiła kamerę na głucho** — odpowiadała
     * na ping, przyjmowała połączenia na 8554 i 37260, i milczała na wszystko, także
     * na odczyt wersji. Odratował ją dopiero cykl zasilania. Poprawny ładunek (wysłany
     * z `narzedzia/siyi_gimbal.py`) kamera przyjęła bez mrugnięcia.
     */
    enum class Kodek(val wartosc: Int, val etykieta: String) {
        H264(1, "H.264"), H265(2, "H.265")
    }

    private fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun odpytajOrientacje() = wyslij(0x0D)

    private fun odpytajKonfiguracje() = wyslij(0x0A)

    // ------------------------------------------------------------------ transport

    /**
     * Wysyłka komendy — **zawsze z wątku `glowica-tx`, nigdy z tego, który zawołał**.
     *
     * ### Dlaczego to nie może iść wprost
     *
     * Zgłoszone 2026-08-26: klawisze kamery na ekranie KAMERA nie robiły nic. W dzienniku
     * przy każdym dotknięciu leciało `błąd wysyłki komendy 0x07` ze stosem:
     *
     * ```
     * android.os.NetworkOnMainThreadException
     *     at pl.dron15.cockpit.net.siyi.KlientSiyi.wyslij
     *     at pl.dron15.cockpit.net.siyi.KlientSiyi.obroc
     * ```
     *
     * Android od API 11 zabija każdą operację sieciową wykonaną na wątku interfejsu.
     * Pętla odbiorcza chodzi po `Dispatchers.IO` i jej odpytania przechodziły bez pudła —
     * ale komendy operatora wychodzą z kompozycji, czyli z wątku głównego, i **żadna
     * nigdy nie opuściła aparatury**. Wyjątek był łapany i zapisywany, więc nic się nie
     * wywracało; po prostu głowica nie reagowała.
     *
     * ### Dlaczego jeden wątek, a nie korutyna na `Dispatchers.IO`
     *
     * Pula wątków nie gwarantuje kolejności. Przy sterowaniu ciągłym (`0x07`) komenda
     * „stój" (0, 0) mogłaby wyprzedzić poprzedzające ją „obracaj" — i głowica zostałaby
     * w ruchu po puszczeniu klawisza. Jeden wątek to kolejka FIFO: co nadane wcześniej,
     * wychodzi wcześniej.
     */
    private fun wyslij(cmd: Int, dane: ByteArray = ByteArray(0)) {
        val n = nadajnik
        if (n.isShutdown) return
        try {
            n.execute {
                val g = gniazdo ?: return@execute
                try {
                    val ramka = zbuduj(cmd, dane, numer++ and 0xFFFF)
                    g.send(DatagramPacket(ramka, ramka.size, InetAddress.getByName(host), port))
                } catch (e: Exception) {
                    Dziennik.ostrzezenie("glowica", "błąd wysyłki komendy 0x%02X".format(cmd), e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Kolejka już zamykana — nie ma czego wysyłać.
        }
    }

    private suspend fun petla() {
        val bufor = ByteArray(512)
        while (coroutineContext.isActive) {
            var g: DatagramSocket? = null
            try {
                g = DatagramSocket().apply { soTimeout = 300 }
                SiecPokladowa.zwiaz(g)
                gniazdo = g
                var ostatnieOdpytanie = 0L
                var bezOdpowiedzi = 0
                while (coroutineContext.isActive) {
                    val teraz = System.currentTimeMillis()
                    if (teraz - ostatnieOdpytanie >= 200) {
                        odpytajOrientacje()
                        if (teraz % 1000 < 200) odpytajKonfiguracje()
                        ostatnieOdpytanie = teraz
                    }
                    val paczka = DatagramPacket(bufor, bufor.size)
                    try {
                        g.receive(paczka)
                        bezOdpowiedzi = 0
                    } catch (_: SocketTimeoutException) {
                        if (++bezOdpowiedzi > 10 && stan.odpowiada) {
                            stan = stan.copy(odpowiada = false)
                            przyZmianie?.invoke(stan)
                        }
                        continue
                    }
                    obsluz(paczka.data, paczka.length)
                }
            } catch (e: Exception) {
                Dziennik.blad("glowica", "łącze do głowicy przerwane — ponawiam za 2 s", e)
                delay(2000)
            } finally {
                g?.close()
                gniazdo = null
            }
        }
    }

    private fun obsluz(dane: ByteArray, dlugosc: Int) {
        val (cmd, ladunek) = rozbierz(dane, dlugosc) ?: return
        when (cmd) {
            0x0D -> if (ladunek.size >= 12) {
                stan = stan.copy(
                    yaw = i16(ladunek, 0) / 10f,
                    pitch = i16(ladunek, 2) / 10f,
                    roll = i16(ladunek, 4) / 10f,
                    odpowiada = true,
                )
                przyZmianie?.invoke(stan)
            }

            0x0A -> if (ladunek.size >= 7) {
                stan = stan.copy(
                    nagrywa = ladunek[3].toInt() == 1,
                    trybRuchu = when (ladunek[4].toInt()) {
                        0 -> "LOCK"; 1 -> "FOLLOW"; 2 -> "FPV"; else -> "—"
                    },
                    odpowiada = true,
                )
                przyZmianie?.invoke(stan)
            }

            0x05, 0x0F, 0x18 -> if (ladunek.size >= 2) {
                stan = stan.copy(zoom = u16(ladunek, 0) / 10f, odpowiada = true)
                przyZmianie?.invoke(stan)
            }

            0x16 -> if (ladunek.size >= 2) {
                stan = stan.copy(zoomMaksymalny = u16(ladunek, 0) / 10f, odpowiada = true)
                przyZmianie?.invoke(stan)
            }
        }
    }

    companion object {
        /**
         * Najmniejszy odstęp między komendami obrotu. 60 ms to ok. 16 na sekundę —
         * płynniej, niż oko rozróżni, a łącze do głowicy zostaje drożne.
         */
        private const val ODSTEP_OBROTU_MS = 60L

        const val DOMYSLNY_HOST = "192.168.144.25"
        const val DOMYSLNY_PORT = 37260

        private fun i16(d: ByteArray, i: Int): Int =
            ((d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

        private fun u16(d: ByteArray, i: Int): Int =
            (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)

        /** CRC-16/XMODEM: wielomian 0x1021, init 0x0000, bez odbicia. */
        fun crc16(d: ByteArray, od: Int, ile: Int): Int {
            var crc = 0x0000
            for (i in od until od + ile) {
                crc = crc xor ((d[i].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                    else (crc shl 1) and 0xFFFF
                }
            }
            return crc
        }

        fun zbuduj(cmd: Int, dane: ByteArray, numer: Int, ctrl: Int = 1): ByteArray {
            val r = ByteArray(10 + dane.size)
            r[0] = 0x55; r[1] = 0x66
            r[2] = ctrl.toByte()
            r[3] = (dane.size and 0xFF).toByte()
            r[4] = ((dane.size shr 8) and 0xFF).toByte()
            r[5] = (numer and 0xFF).toByte()
            r[6] = ((numer shr 8) and 0xFF).toByte()
            r[7] = cmd.toByte()
            System.arraycopy(dane, 0, r, 8, dane.size)
            val suma = crc16(r, 0, 8 + dane.size)
            r[8 + dane.size] = (suma and 0xFF).toByte()
            r[9 + dane.size] = ((suma shr 8) and 0xFF).toByte()
            return r
        }

        fun rozbierz(d: ByteArray, dlugosc: Int): Pair<Int, ByteArray>? {
            if (dlugosc < 10) return null
            if ((d[0].toInt() and 0xFF) != 0x55 || (d[1].toInt() and 0xFF) != 0x66) return null
            val dlLadunku = u16(d, 3)
            val koniec = 8 + dlLadunku
            if (dlugosc < koniec + 2) return null
            if (u16(d, koniec) != crc16(d, 0, koniec)) return null
            return (d[7].toInt() and 0xFF) to d.copyOfRange(8, koniec)
        }
    }
}
