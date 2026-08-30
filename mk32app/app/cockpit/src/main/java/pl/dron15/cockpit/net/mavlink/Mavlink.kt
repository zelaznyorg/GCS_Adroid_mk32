package pl.dron15.cockpit.net.mavlink

/**
 * Minimalna obsługa MAVLink 2 — tyle, ile potrzebuje kokpit.
 *
 * Świadomie bez biblioteki zewnętrznej: odbieramy kilkanaście typów wiadomości i wysyłamy dwa,
 * a własne kilkaset linii daje pełną kontrolę nad tym, co leci do maszyny. Wartości `crcExtra`
 * i układ pól pobrane z definicji dialektu `ardupilotmega` (pymavlink), nie z pamięci.
 */
object Mavlink {

    const val HEARTBEAT = 0
    const val PARAM_REQUEST_READ = 20
    const val SYS_STATUS = 1
    const val PARAM_VALUE = 22
    const val PARAM_SET = 23

    /** MAV_PARAM_TYPE_REAL32 — ArduPilot rzutuje sam na typ wlasny parametru. */
    private const val MAV_PARAM_TYPE_REAL32 = 9
    const val GPS_RAW_INT = 24
    const val ATTITUDE = 30
    const val MISSION_CURRENT = 42
    const val RC_CHANNELS = 65
    const val GLOBAL_POSITION_INT = 33
    const val VFR_HUD = 74
    const val COMMAND_ACK = 77
    const val COMMAND_LONG = 76
    const val BATTERY_STATUS = 147
    const val EKF_STATUS_REPORT = 193
    const val GIMBAL_DEVICE_ATTITUDE_STATUS = 285
    const val STATUSTEXT = 253

    // --- przyrządy zapasu i zdrowia (dok/PROPOZYCJA_LOT.md §4)
    /** Wyjścia silników w µs — z tego liczy się zapas ciągu i rozrzut (domain/Ciag.kt). */
    const val SERVO_OUTPUT_RAW = 36
    const val VIBRATION = 241
    /** Punkt domu **z maszyny**, zamiast zgadywanego w chwili uzbrojenia. */
    const val HOME_POSITION = 242
    const val FENCE_STATUS = 162
    /** Dokąd zmierza autopilot i o ile chybia — jedyne źródło tej wiedzy w RTL i AUTO. */
    const val NAV_CONTROLLER_OUTPUT = 62

    // --- protokół misji (dok/MISJE.md §1). Zawsze MISSION_ITEM_INT, nigdy MISSION_ITEM:
    // to drugie niesie współrzędne jako float i gubi precyzję na poziomie metrów.
    const val MISSION_REQUEST = 40            // starszy wariant zapytania — ArduPilot bywa go używa
    const val MISSION_REQUEST_LIST = 43
    const val MISSION_COUNT = 44
    const val MISSION_CLEAR_ALL = 45
    const val MISSION_ITEM_REACHED = 46
    const val MISSION_ACK = 47
    const val MISSION_REQUEST_INT = 51
    const val MISSION_ITEM_INT = 73

    /** crc_extra — bez tego bajtu suma kontrolna MAVLinka się nie zgadza. */
    private val CRC_EXTRA = mapOf(
        HEARTBEAT to 50, SYS_STATUS to 124, PARAM_REQUEST_READ to 214, PARAM_VALUE to 220,
        PARAM_SET to 168, GPS_RAW_INT to 24,
        ATTITUDE to 39, GLOBAL_POSITION_INT to 104, MISSION_CURRENT to 28,
        RC_CHANNELS to 118, VFR_HUD to 20, COMMAND_LONG to 152, COMMAND_ACK to 143,
        BATTERY_STATUS to 154, EKF_STATUS_REPORT to 71,
        GIMBAL_DEVICE_ATTITUDE_STATUS to 137, STATUSTEXT to 83,
        MISSION_REQUEST to 230, MISSION_REQUEST_LIST to 132, MISSION_COUNT to 221,
        MISSION_CLEAR_ALL to 232, MISSION_ITEM_REACHED to 11, MISSION_ACK to 153,
        MISSION_REQUEST_INT to 196, MISSION_ITEM_INT to 38,
        SERVO_OUTPUT_RAW to 222, VIBRATION to 90, HOME_POSITION to 104,
        FENCE_STATUS to 189, NAV_CONTROLLER_OUTPUT to 183,
    )

    /** Nasz identyfikator w sieci MAVLink. 255/190 to zwyczajowa para dla stacji naziemnej. */
    const val NASZ_SYSID = 255
    const val NASZ_COMPID = 190

    data class Ramka(val msgid: Int, val sysid: Int, val compid: Int, val ladunek: ByteArray) {
        // data class z ByteArray wymaga ręcznych equals/hashCode
        override fun equals(other: Any?): Boolean =
            this === other || (other is Ramka && msgid == other.msgid && sysid == other.sysid &&
                    compid == other.compid && ladunek.contentEquals(other.ladunek))

        override fun hashCode(): Int =
            (((msgid * 31 + sysid) * 31 + compid) * 31) + ladunek.contentHashCode()
    }

    // ------------------------------------------------------------------ suma kontrolna

    private fun crcAkumuluj(bajt: Int, crc: Int): Int {
        var tmp = bajt xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)) and 0xFFFF
    }

    private fun crc(dane: ByteArray, od: Int, ile: Int, crcExtra: Int): Int {
        var c = 0xFFFF
        for (i in od until od + ile) c = crcAkumuluj(dane[i].toInt() and 0xFF, c)
        return crcAkumuluj(crcExtra, c)
    }

    // ------------------------------------------------------------------ odbiór

    /** Co udało się wyłuskać i ile bajtów wolno już wyrzucić z bufora. */
    data class Wynik(val ramki: List<Ramka>, val zuzyte: Int)

    /**
     * Wyłuskuje ramki z **ciągłego strumienia bajtów**, nie z pojedynczego datagramu.
     *
     * ### Dlaczego strumień, a nie datagram
     *
     * Zmierzone na sprzęcie 2026-08-26: jednostka naziemna MK32 nie przesyła wiadomości
     * MAVLink, tylko **surowy strumień pocięty na kawałki po 115 bajtów**, bez oglądania
     * się na granice ramek. Z 25 kolejnych datagramów tylko **2** zaczynały się od
     * początku ramki. Rozbieranie każdego datagramu osobno gubiło więc każdą wiadomość
     * przeciętą granicą kawałka — a heartbeat, jedna mała ramka na sekundę, wpadał w to
     * sito wyjątkowo często. Stąd baner „utrata telemetrii" co kilka sekund przy
     * całkowicie sprawnym łączu.
     *
     * Zwracamy [Wynik.zuzyte], żeby wołający zachował ogon i skleił go z następnym
     * kawałkiem.
     *
     * ### Suma kontrolna także dla MAVLink 1
     *
     * Do 2026-08-26 ramki `0xFE` przyjmowaliśmy **bez sprawdzania CRC**. Przy strumieniu
     * bajtów to jest wprost groźne: przypadkowy bajt `0xFE` w środku ładunku daje
     * „ramkę" o losowej treści. Tak powstał `HEARTBEAT` z `custom_mode = -671481856`,
     * który zapalił na ekranie **UZBROJONY przy rozbrojonej maszynie**. Wskaźnik
     * uzbrojenia nie ma prawa zależeć od niesprawdzonych bajtów.
     *
     * Ramki o nieznanym `msgid` pomijamy — nie mamy dla nich `crcExtra`, więc nie da się
     * ich uczciwie sprawdzić, a zgadywanie byłoby gorsze niż pominięcie.
     */
    fun skanujStrumien(dane: ByteArray, dlugosc: Int = dane.size): Wynik {
        val wynik = ArrayList<Ramka>(4)
        var i = 0
        while (i < dlugosc) {
            val znacznik = dane[i].toInt() and 0xFF
            if (znacznik == 0xFD) {
                // Nagłówek jeszcze nie w całości — czekamy na kolejny kawałek.
                if (dlugosc - i < 12) break
                val dlLadunku = dane[i + 1].toInt() and 0xFF
                val flagiNiezgodne = dane[i + 2].toInt() and 0xFF
                val podpis = if (flagiNiezgodne and 0x01 != 0) 13 else 0
                val calosc = 12 + dlLadunku + podpis
                if (dlugosc - i < calosc) break
                val msgid = (dane[i + 7].toInt() and 0xFF) or
                        ((dane[i + 8].toInt() and 0xFF) shl 8) or
                        ((dane[i + 9].toInt() and 0xFF) shl 16)
                val extra = CRC_EXTRA[msgid]
                var zgodna = false
                if (extra != null) {
                    val wRamce = (dane[i + 10 + dlLadunku].toInt() and 0xFF) or
                            ((dane[i + 11 + dlLadunku].toInt() and 0xFF) shl 8)
                    if (crc(dane, i + 1, 9 + dlLadunku, extra) == wRamce) {
                        zgodna = true
                        wynik.add(
                            Ramka(
                                msgid,
                                dane[i + 5].toInt() and 0xFF,
                                dane[i + 6].toInt() and 0xFF,
                                dane.copyOfRange(i + 10, i + 10 + dlLadunku)
                            )
                        )
                    }
                }
                // Zła suma znaczy, że to nie był początek ramki — szukamy dalej od
                // NASTĘPNEGO bajtu, a nie za rzekomym końcem, żeby nie przeskoczyć
                // prawdziwego początku leżącego w środku.
                i += if (zgodna || extra == null) calosc else 1
            } else if (znacznik == 0xFE) {
                if (dlugosc - i < 8) break
                val dlLadunku = dane[i + 1].toInt() and 0xFF
                val calosc = 8 + dlLadunku
                if (dlugosc - i < calosc) break
                val msgid = dane[i + 5].toInt() and 0xFF
                val extra = CRC_EXTRA[msgid]
                var zgodna = false
                if (extra != null) {
                    val wRamce = (dane[i + 6 + dlLadunku].toInt() and 0xFF) or
                            ((dane[i + 7 + dlLadunku].toInt() and 0xFF) shl 8)
                    if (crc(dane, i + 1, 5 + dlLadunku, extra) == wRamce) {
                        zgodna = true
                        wynik.add(
                            Ramka(
                                msgid,
                                dane[i + 3].toInt() and 0xFF,
                                dane[i + 4].toInt() and 0xFF,
                                dane.copyOfRange(i + 6, i + 6 + dlLadunku)
                            )
                        )
                    }
                }
                i += if (zgodna || extra == null) calosc else 1
            } else {
                i++
            }
        }
        return Wynik(wynik, i)
    }

    /** Wygodne opakowanie dla wywołań, które mają w ręku całą wiadomość (testy, misje). */
    fun skanuj(dane: ByteArray, dlugosc: Int = dane.size): List<Ramka> =
        skanujStrumien(dane, dlugosc).ramki

    /**
     * Czytnik ładunku. MAVLink 2 obcina końcowe zera, więc odczyt poza końcem tablicy
     * musi zwracać zero, a nie wyjątek — inaczej połowa wiadomości wywracałaby aplikację.
     */
    class Odczyt(private val d: ByteArray) {
        private var i = 0
        private fun b(k: Int): Int = if (k < d.size) d[k].toInt() and 0xFF else 0

        fun u8(): Int = b(i).also { i += 1 }
        fun i8(): Int = b(i).toByte().toInt().also { i += 1 }
        fun u16(): Int = (b(i) or (b(i + 1) shl 8)).also { i += 2 }
        fun i16(): Int = (b(i) or (b(i + 1) shl 8)).toShort().toInt().also { i += 2 }
        fun i32(): Int = (b(i) or (b(i + 1) shl 8) or (b(i + 2) shl 16) or (b(i + 3) shl 24)).also { i += 4 }
        fun u32(): Long = (i32().toLong() and 0xFFFFFFFFL)
        fun f32(): Float = Float.fromBits(i32())
        fun pomin(n: Int) { i += n }
        fun tekst(n: Int): String {
            val sb = StringBuilder()
            for (k in 0 until n) {
                val z = b(i + k)
                if (z == 0) break
                sb.append(z.toChar())
            }
            i += n
            return sb.toString()
        }
    }

    // ------------------------------------------------------------------ nadawanie

    private var numerKolejny = 0

    private fun zapakuj(msgid: Int, ladunek: ByteArray): ByteArray {
        val extra = CRC_EXTRA[msgid] ?: error("brak crcExtra dla msgid $msgid")
        val ramka = ByteArray(12 + ladunek.size)
        ramka[0] = 0xFD.toByte()
        ramka[1] = ladunek.size.toByte()
        ramka[2] = 0                                  // flagi niezgodne (brak podpisu)
        ramka[3] = 0                                  // flagi zgodne
        ramka[4] = (numerKolejny++ and 0xFF).toByte()
        ramka[5] = NASZ_SYSID.toByte()
        ramka[6] = NASZ_COMPID.toByte()
        ramka[7] = (msgid and 0xFF).toByte()
        ramka[8] = ((msgid shr 8) and 0xFF).toByte()
        ramka[9] = ((msgid shr 16) and 0xFF).toByte()
        System.arraycopy(ladunek, 0, ramka, 10, ladunek.size)
        val suma = crc(ramka, 1, 9 + ladunek.size, extra)
        ramka[10 + ladunek.size] = (suma and 0xFF).toByte()
        ramka[11 + ladunek.size] = ((suma shr 8) and 0xFF).toByte()
        return ramka
    }

    private class Zapis(rozmiar: Int) {
        val d = ByteArray(rozmiar)
        private var i = 0
        fun u8(v: Int) = apply { d[i++] = (v and 0xFF).toByte() }
        fun u16(v: Int) = apply { d[i++] = (v and 0xFF).toByte(); d[i++] = ((v shr 8) and 0xFF).toByte() }
        fun i32(v: Int) = apply {
            d[i++] = (v and 0xFF).toByte(); d[i++] = ((v shr 8) and 0xFF).toByte()
            d[i++] = ((v shr 16) and 0xFF).toByte(); d[i++] = ((v shr 24) and 0xFF).toByte()
        }
        fun f32(v: Float) = i32(v.toRawBits())
    }

    /** Heartbeat stacji naziemnej. Tego SIYI FPV nie wysyła — patrz poz. 34 w CLAUDE.md. */
    fun heartbeat(): ByteArray = zapakuj(
        HEARTBEAT,
        Zapis(9).i32(0)      // custom_mode
            .u8(6)           // MAV_TYPE_GCS
            .u8(8)           // MAV_AUTOPILOT_INVALID
            .u8(0)           // base_mode
            .u8(4)           // MAV_STATE_ACTIVE
            .u8(3)           // wersja MAVLink
            .d
    )

    fun komenda(
        komenda: Int,
        p1: Float = 0f, p2: Float = 0f, p3: Float = 0f, p4: Float = 0f,
        p5: Float = 0f, p6: Float = 0f, p7: Float = 0f,
        celSys: Int = 1, celKomp: Int = 1, potwierdzenie: Int = 0,
    ): ByteArray = zapakuj(
        COMMAND_LONG,
        Zapis(33).f32(p1).f32(p2).f32(p3).f32(p4).f32(p5).f32(p6).f32(p7)
            .u16(komenda).u8(celSys).u8(celKomp).u8(potwierdzenie).d
    )

    /**
     * Zapytanie o jeden parametr po nazwie (index = -1). Pytamy imiennie, nie o cała listę:
     * checklista potrzebuje ok. trzydziestu wartości, a pełny zrzut to 1306 parametrów,
     * czyli kilka minut na łączu 115 200 dzielonym z telemetrią.
     */
    fun zadanieParametru(nazwa: String): ByteArray {
        val id = ByteArray(16)
        val bajty = nazwa.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bajty, 0, id, 0, minOf(16, bajty.size))
        val z = Zapis(20).u16(0xFFFF).u8(1).u8(1)      // param_index = -1, cel 1/1
        System.arraycopy(id, 0, z.d, 4, 16)
        return zapakuj(PARAM_REQUEST_READ, z.d)
    }

    /**
     * Zapis pojedynczego parametru do maszyny — **jedyna droga, którą kokpit cokolwiek
     * w niej zmienia**.
     *
     * ### Zakres celowo wąski
     *
     * Wysyłamy to wyłącznie z ekranu PRZED LOTEM i wyłącznie dla parametrów, które
     * checklista sama wskazała jako niezgodne, z wartością docelową wziętą z reguły —
     * nie z pola, w którym operator może wpisać cokolwiek. Dzięki temu zbiór możliwych
     * zapisów jest **skończony, znany z góry i opisany** w `assets/preflight_rules.json`.
     *
     * ### Dlaczego REAL32
     *
     * ArduPilot przyjmuje `MAV_PARAM_TYPE_REAL32` dla każdego parametru i sam rzutuje
     * na typ własny; podawanie typu „prawdziwego" wymagałoby jego znajomości dla każdego
     * parametru z osobna i przy pomyłce kończyłoby się cichym odrzuceniem zapisu.
     *
     * Maszyna odpowiada `PARAM_VALUE` z wartością **po zapisie** — i dopiero to jest
     * potwierdzenie. Patrz [pl.dron15.cockpit.net.mavlink.LaczeMavlink.zapiszParametr].
     */
    fun zapisParametru(nazwa: String, wartosc: Float): ByteArray {
        val id = ByteArray(16)
        val bajty = nazwa.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bajty, 0, id, 0, minOf(16, bajty.size))
        val z = Zapis(23).f32(wartosc).u8(1).u8(1)     // wartość, cel 1/1
        System.arraycopy(id, 0, z.d, 6, 16)
        z.d[22] = MAV_PARAM_TYPE_REAL32.toByte()
        return zapakuj(PARAM_SET, z.d)
    }

    /**
     * Zmiana trybu lotu. `p1` = MAV_MODE_FLAG_CUSTOM_MODE_ENABLED (1), `p2` = numer trybu
     * ArduCoptera (patrz domain/Tryby.kt). Uzbrojenia tą drogą nie wysyłamy nigdy —
     * zostaje na przełączniku CH9 (PLAN.md §4).
     */
    fun ustawTryb(numerTrybu: Int): ByteArray =
        komenda(CMD_USTAW_TRYB, p1 = 1f, p2 = numerTrybu.toFloat())

    // MAV_CMD używane przez kokpit
    const val CMD_POWROT_DO_STARTU = 20       // NAV_RETURN_TO_LAUNCH
    const val CMD_LADOWANIE = 21              // NAV_LAND
    const val CMD_USTAW_TRYB = 176            // DO_SET_MODE
    const val CMD_KONTROLA_PRZEDLOTOWA = 401  // RUN_PREARM_CHECKS
    const val CMD_USTAW_INTERWAL = 511        // SET_MESSAGE_INTERVAL

    /**
     * Prośba o nadawanie wskazanej wiadomości co `okresMs`.
     *
     * Wybrane zamiast `REQUEST_DATA_STREAM`, bo **nie zmienia parametrów maszyny** —
     * działa na czas sesji i na naszym kanale. To istotne: `SRn_*` są wspólne dla portu,
     * a aplikacja SIYI potrafi je nadpisać (poz. 34 w CLAUDE.md).
     *
     * ⚠ Może zostać zignorowane, gdy port ma `SERIALn_OPTIONS` z bitem „Ignore Streamrate"
     * (u nas `SERIAL6_OPTIONS = 4096`, poz. 35). Dlatego przyrządy, które z tego korzystają,
     * **muszą znosić brak danych**, a nie pokazywać zero — patrz `StanMaszyny.wyjsciaZnane`.
     */
    fun zadanieInterwalu(msgid: Int, okresMs: Int): ByteArray =
        komenda(CMD_USTAW_INTERWAL, p1 = msgid.toFloat(), p2 = (okresMs * 1000).toFloat())

    // ------------------------------------------------------------------ misja

    /**
     * Zapowiedź liczby punktów. `mission_type = 0` to trasa; 1 to geofence, 2 to punkty
     * zbiórki — trzy niezależne listy, ta sama para wiadomości (dok/MISJE.md §1).
     */
    fun misjaLiczba(ile: Int, celSys: Int = 1, celKomp: Int = 1): ByteArray = zapakuj(
        MISSION_COUNT,
        Zapis(5).u16(ile).u8(celSys).u8(celKomp).u8(0).d
    )

    /**
     * Jeden punkt trasy. Kolejność pól jest kolejnością MAVLinka (najpierw czterobajtowe,
     * potem dwubajtowe, na końcu jednobajtowe) — nie kolejnością z dokumentacji.
     *
     * `x` i `y` to `int32` w jednostkach `1e-7` stopnia, `z` to metry.
     */
    fun misjaPunkt(
        seq: Int,
        komenda: Int,
        lat: Int,
        lon: Int,
        wysokosc: Float,
        p1: Float = 0f, p2: Float = 0f, p3: Float = 0f, p4: Float = 0f,
        ramka: Int = 6,
        biezacy: Boolean = false,
        celSys: Int = 1, celKomp: Int = 1,
    ): ByteArray = zapakuj(
        MISSION_ITEM_INT,
        Zapis(38)
            .f32(p1).f32(p2).f32(p3).f32(p4)
            .i32(lat).i32(lon).f32(wysokosc)
            .u16(seq).u16(komenda)
            .u8(celSys).u8(celKomp).u8(ramka).u8(if (biezacy) 1 else 0).u8(1)
            .u8(0)                                  // mission_type = trasa
            .d
    )

    fun misjaZadanieListy(celSys: Int = 1, celKomp: Int = 1): ByteArray =
        zapakuj(MISSION_REQUEST_LIST, Zapis(3).u8(celSys).u8(celKomp).u8(0).d)

    fun misjaZadaniePunktu(seq: Int, celSys: Int = 1, celKomp: Int = 1): ByteArray =
        zapakuj(MISSION_REQUEST_INT, Zapis(5).u16(seq).u8(celSys).u8(celKomp).u8(0).d)

    /** `typ = 0` to MAV_MISSION_ACCEPTED. */
    fun misjaPotwierdzenie(typ: Int = 0, celSys: Int = 1, celKomp: Int = 1): ByteArray =
        zapakuj(MISSION_ACK, Zapis(4).u8(celSys).u8(celKomp).u8(typ).u8(0).d)

    fun misjaWyczysc(celSys: Int = 1, celKomp: Int = 1): ByteArray =
        zapakuj(MISSION_CLEAR_ALL, Zapis(3).u8(celSys).u8(celKomp).u8(0).d)

    fun opisWynikuMisji(t: Int): String = when (t) {
        0 -> "przyjęta"
        1 -> "błąd ogólny"
        2 -> "nieobsługiwana ramka"
        3 -> "nieobsługiwana komenda"
        4 -> "brak miejsca"
        5 -> "zły parametr"
        6, 7, 8, 9, 10, 11, 12, 13 -> "zły parametr $t"
        14 -> "nie ma takiej misji"
        15 -> "operacja w toku"
        else -> "wynik $t"
    }

    /** Skok do punktu w trwającej misji. */
    const val CMD_USTAW_BIEZACY_PUNKT = 224       // DO_SET_MISSION_CURRENT
    const val CMD_PAUZA = 193                     // DO_PAUSE_CONTINUE
}
