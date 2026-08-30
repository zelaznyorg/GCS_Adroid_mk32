package pl.dron15.cockpit.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import pl.dron15.cockpit.net.mavlink.Mavlink
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * `MAV_AUTOPILOT_INVALID` — wartość, którą w heartbeacie wystawia urządzenie **nie będące
 * autopilotem**: głowica, air unit, stacja naziemna. Zweryfikowane w `common.xml` MAVLink.
 */
private const val MAV_AUTOPILOT_INVALID = 8

/** Jak często najwyżej odnotowujemy, że coś przyszło od maszyny. */
private const val ODSTEP_ZNAKU_ZYCIA_MS = 200L

/** Zamienia ramki MAVLink na jeden stan maszyny. Jedyne miejsce, gdzie dekodujemy ładunki. */
class SilnikStanu {

    private val _stan = MutableStateFlow(StanMaszyny())
    val stan: StateFlow<StanMaszyny> = _stan

    private var licznikRamek = 0
    private var oknoOd = 0L

    /**
     * Numer systemu **kontrolera lotu**, ustalony z pierwszego heartbeatu autopilota.
     * Dopóki nie znamy go na pewno, jest `0` i wtedy nie odrzucamy niczego.
     */
    private var sysidMaszyny = 0

    /**
     * ⛔ Czy ta ramka pochodzi od maszyny, a nie od innego urządzenia w sieci.
     *
     * ### Skąd ta kontrola
     *
     * Zgłoszone 2026-08-26 na sprzęcie: kokpit pokazywał **`UZBROJONY`, gdy aparatura
     * SIYI meldowała rozbrojenie**, a obok tego numer trybu `-671481856` — wartość, której
     * ArduCopter (0–27) nie może wystawić. Silnik stanu brał **każdy** heartbeat z sieci
     * 192.168.144.x, a gadają tam też air unit MK32 i głowica ZR30. Wystarczyła jedna
     * ramka obcego urządzenia, żeby przestawić wskaźnik uzbrojenia i tryb lotu.
     *
     * **To jest wada bezpieczeństwa, nie kosmetyka.** Wskaźnik uzbrojenia, który potrafi
     * skłamać w obie strony, jest gorszy od jego braku: przy zamontowanych śmigłach
     * „ROZBROJONY" na ekranie może znaczyć maszynę gotową do ruchu.
     *
     * ### Jak rozpoznajemy właściwego nadawcę
     *
     * Zgodnie ze standardem MAVLink urządzenie, które **nie jest autopilotem**, wystawia
     * w heartbeacie `autopilot = MAV_AUTOPILOT_INVALID (8)`. Po tym polu odsiewamy głowicę,
     * air unit i stacje naziemne, a z pierwszego prawdziwego heartbeatu autopilota
     * zapamiętujemy `sysid` — od tej chwili wszystko, co przychodzi z innego systemu,
     * przestaje mieć wpływ na stan maszyny.
     *
     * `compid` celowo **nie** wchodzi do filtru: głowica na tej samej maszynie ma własny
     * numer komponentu, a jej `GIMBAL_DEVICE_ATTITUDE_STATUS` chcemy przyjmować.
     */
    private fun odMaszyny(r: Mavlink.Ramka): Boolean =
        sysidMaszyny == 0 || r.sysid == sysidMaszyny

    fun zastosuj(r: Mavlink.Ramka, teraz: Long) {
        if (!odMaszyny(r)) return
        licznikRamek++
        // Znak życia łącza. Odświeżamy najwyżej 5 razy na sekundę — przy pełnym strumieniu
        // zapis przy każdej ramce byłby kilkudziesięcioma przebudowami ekranu na sekundę
        // bez żadnego zysku dla operatora.
        if (teraz - _stan.value.czasRamki >= ODSTEP_ZNAKU_ZYCIA_MS) {
            _stan.update { it.copy(czasRamki = teraz) }
        }
        if (oknoOd == 0L) oknoOd = teraz
        if (teraz - oknoOd >= 1000) {
            val hz = licznikRamek * 1000f / (teraz - oknoOd)
            licznikRamek = 0
            oknoOd = teraz
            _stan.update { it.copy(ramekNaSekunde = hz) }
        }

        val o = Mavlink.Odczyt(r.ladunek)
        when (r.msgid) {
            Mavlink.HEARTBEAT -> {
                val tryb = o.u32()
                o.u8()                      // type — jakiego rodzaju to statek
                val autopilot = o.u8()
                val bazowy = o.u8()
                // Heartbeat urządzenia, które nie jest autopilotem (głowica, air unit,
                // stacja naziemna), nie mówi nic o maszynie — patrz [odMaszyny].
                if (autopilot == MAV_AUTOPILOT_INVALID) return
                if (sysidMaszyny == 0) sysidMaszyny = r.sysid
                val uzbrojony = (bazowy and 128) != 0
                _stan.update { st ->
                    val zmianaUzbrojenia = uzbrojony != st.uzbrojony
                    st.copy(
                        tryb = Tryby.nazwa(tryb.toInt()),
                        uzbrojony = uzbrojony,
                        czasHeartbeatu = teraz,
                        // Dom ustala się w chwili uzbrojenia — tak samo robi ArduPilot.
                        // Ślad startuje od zera, żeby nie mieszać dwóch lotów na jednej mapie.
                        // Zgadujemy dom tylko dopóki maszyna nie przyśle własnego.
                        domSzerokosc = if (!st.domZMaszyny && zmianaUzbrojenia && uzbrojony && st.pozycjaZnana) st.szerokosc else st.domSzerokosc,
                        domDlugosc = if (!st.domZMaszyny && zmianaUzbrojenia && uzbrojony && st.pozycjaZnana) st.dlugosc else st.domDlugosc,
                        domUstalony = st.domUstalony || (zmianaUzbrojenia && uzbrojony && st.pozycjaZnana),
                        slad = if (zmianaUzbrojenia && uzbrojony) emptyList() else st.slad,
                        czasUzbrojenia = when {
                            zmianaUzbrojenia && uzbrojony -> teraz
                            zmianaUzbrojenia -> 0L
                            else -> st.czasUzbrojenia
                        },
                        czasLotuMs = when {
                            zmianaUzbrojenia && uzbrojony -> 0L
                            zmianaUzbrojenia && st.czasUzbrojenia > 0L -> teraz - st.czasUzbrojenia
                            else -> st.czasLotuMs
                        },
                    )
                }
            }

            Mavlink.SYS_STATUS -> {
                // Trzy maski po 4 bajty: obecne, włączone, ZDROWE. Do 2026-08-26 wszystkie
                // trzy szły do kosza przez `pomin(12)`, a stan czujników zgadywaliśmy
                // z tekstu `PreArm:` — dok/PROPOZYCJA_LOT.md §4.4.
                val obecne = o.i32()
                val wlaczone = o.i32()
                val zdrowe = o.i32()
                o.u16()                                    // obciążenie procesora
                val napiecie = o.u16()                     // mV
                val prad = o.i16()                         // cA
                _stan.update {
                    it.copy(
                        czujnikiObecne = obecne,
                        czujnikiWlaczone = wlaczone,
                        czujnikiZdrowe = zdrowe,
                        napiecieV = if (napiecie == 0xFFFF) 0f else napiecie / 1000f,
                        pradA = if (prad == -1) 0f else prad / 100f,
                    )
                }
            }

            Mavlink.GPS_RAW_INT -> {
                o.pomin(8)                                 // time_usec
                o.i32(); o.i32(); o.i32()                  // lat, lon, alt
                val eph = o.u16()
                o.u16(); o.u16(); o.u16()                  // epv, vel, cog
                val fix = o.u8()
                val sat = o.u8()
                o.i32(); o.u32(); o.u32(); o.u32(); o.u32() // dokładności
                val yaw = o.u16()                          // cdeg; 0 = brak, 65535 = niedostępne
                _stan.update {
                    it.copy(
                        satelity = sat,
                        hdop = if (eph == 0xFFFF) 0f else eph / 100f,
                        rodzajFixa = fix,
                        kursGnssDostepny = yaw != 0 && yaw != 0xFFFF,
                        kursGnssSt = if (yaw == 0 || yaw == 0xFFFF) 0f else yaw / 100f,
                    )
                }
            }

            Mavlink.ATTITUDE -> {
                o.u32()
                val roll = o.f32(); val pitch = o.f32(); val yaw = o.f32()
                _stan.update {
                    it.copy(
                        przechylenieSt = (roll * 180f / PI).toFloat(),
                        pochylenieSt = (pitch * 180f / PI).toFloat(),
                        kursSt = ((yaw * 180f / PI).toFloat() + 360f) % 360f,
                    )
                }
            }

            Mavlink.GLOBAL_POSITION_INT -> {
                o.u32()
                val lat = o.i32(); val lon = o.i32()
                val msl = o.i32()                          // mm nad poziomem morza
                val wzgl = o.i32()                         // mm nad punktem startu
                val vx = o.i16(); val vy = o.i16(); val vz = o.i16()
                _stan.update { st ->
                    val nowaSzer = lat / 1e7
                    val nowaDlug = lon / 1e7
                    // Do czasu uzbrojenia dom jest prowizoryczny — inaczej mapa nie miałaby
                    // środka i nie dałoby się jej użyć przed startem.
                    val ustalDom = !st.domUstalony && !st.domZMaszyny &&
                            st.satelity >= 6 && (lat != 0 || lon != 0)
                    val domS = if (ustalDom) nowaSzer else st.domSzerokosc
                    val domD = if (ustalDom) nowaDlug else st.domDlugosc
                    val pomocniczy = st.copy(domSzerokosc = domS, domDlugosc = domD)
                    val (e, n) = pomocniczy.wzgledemDomu(nowaSzer, nowaDlug)
                    val ostatni = st.slad.lastOrNull()
                    val dosc = ostatni == null ||
                            sqrt(((e - ostatni.first) * (e - ostatni.first) +
                                    (n - ostatni.second) * (n - ostatni.second)).toDouble()) > 2.0
                    st.copy(
                        szerokosc = nowaSzer, dlugosc = nowaDlug,
                        domSzerokosc = domS, domDlugosc = domD,
                        domUstalony = st.domUstalony || ustalDom,
                        wysokoscM = wzgl / 1000f,
                        wysokoscMslM = msl / 1000f,
                        predkoscMs = sqrt((vx * vx + vy * vy).toDouble()).toFloat() / 100f,
                        // Sam kierunek toru, nie tylko jego długość: przy wietrze bocznym
                        // różni się od kursu o kilkanaście stopni i to jest ta różnica,
                        // którą pilot musi widzieć (wektor prędkości, dok/PROPOZYCJA_LOT.md B1).
                        kursToruSt = if (vx * vx + vy * vy > 25)
                            ((Math.toDegrees(atan2(vy.toDouble(), vx.toDouble()))
                                .toFloat() % 360f) + 360f) % 360f
                        else -1f,
                        wznoszenieMs = -vz / 100f,
                        slad = if (dosc && (st.domUstalony || ustalDom))
                            (st.slad + (e to n)).takeLast(StanMaszyny.DLUGOSC_SLADU) else st.slad,
                    )
                }
            }

            Mavlink.VFR_HUD -> {
                o.f32()                                    // prędkość powietrzna
                o.f32(); o.f32()                           // prędkość względem ziemi, wysokość
                val wznoszenie = o.f32()
                o.i16()
                val gaz = o.u16()
                _stan.update { it.copy(gazProc = gaz, wznoszenieMs = wznoszenie) }
            }

            Mavlink.EKF_STATUS_REPORT -> {
                o.f32(); o.f32(); o.f32()
                val wariancjaKursu = o.f32()
                o.f32()
                val flagi = o.u16()
                _stan.update { it.copy(flagiEkf = flagi, wariancjaKursu = wariancjaKursu) }
            }

            Mavlink.BATTERY_STATUS -> {
                val zuzycie = o.i32()
                o.i32(); o.i16()                           // energia, temperatura
                val ogniwa = IntArray(10) { o.u16() }
                _stan.update {
                    val suma = ogniwa.filter { v -> v != 0xFFFF }.sum()
                    it.copy(
                        zuzycieMah = if (zuzycie < 0) 0 else zuzycie,
                        napiecieV = if (suma > 0) suma / 1000f else it.napiecieV,
                    )
                }
            }

            /**
             * Pozycje drążków i przełączników. Bez tego pilot nie ma jak odróżnić
             * „przełącznik nie działa" od „funkcja nie jest przypisana" — dok/RC_PRZYPISANIA.md.
             */
            Mavlink.RC_CHANNELS -> {
                o.u32()                                    // time_boot_ms
                val kanaly = List(18) { o.u16() }.map { if (it == 0xFFFF) 0 else it }
                val ile = o.u8()
                val rssi = o.u8()
                _stan.update {
                    it.copy(
                        kanalyRc = kanaly.take(Rc.KANALOW),
                        liczbaKanalowRc = ile,
                        rssiRc = rssi,
                        czasRc = teraz,
                    )
                }
            }

            Mavlink.MISSION_CURRENT -> {
                val punkt = o.u16()
                _stan.update { it.copy(punktMisji = punkt) }
            }

            Mavlink.COMMAND_ACK -> {
                val komenda = o.u16()
                val wynik = o.u8()
                _stan.update { st ->
                    val k = st.ostatniaKomenda
                    if (k != null && k.kod == komenda && k.wynik == null)
                        st.copy(ostatniaKomenda = k.copy(wynik = wynik, czasOdpowiedzi = teraz))
                    else st
                }
            }

            Mavlink.GIMBAL_DEVICE_ATTITUDE_STATUS -> {
                o.u32()
                val w = o.f32(); val x = o.f32(); val y = o.f32(); val z = o.f32()
                _stan.update {
                    it.copy(
                        glowicaPitch = pitchZKwaternionu(w, x, y, z),
                        glowicaYaw = yawZKwaternionu(w, x, y, z),
                        glowicaOdpowiada = true,
                    )
                }
            }

            /**
             * Wyjścia silników — źródło zapasu ciągu i rozrzutu (domain/Ciag.kt).
             *
             * To jest ta wiadomość, której brak sprawił, że nasycenie miksera w locie 3
             * (CLAUDE.md poz. 45) wyszło dopiero z logu, tygodnie po locie.
             */
            Mavlink.SERVO_OUTPUT_RAW -> {
                o.u32()                                    // time_usec (uint32)
                val wyjscia = List(4) { o.u16() }
                _stan.update { it.copy(wyjsciaSilnikow = wyjscia, czasWyjsc = teraz) }
            }

            Mavlink.VIBRATION -> {
                o.u32(); o.u32()                           // time_usec (uint64)
                val x = o.f32(); val y = o.f32(); val z = o.f32()
                val c0 = o.u32(); val c1 = o.u32(); val c2 = o.u32()
                _stan.update {
                    it.copy(
                        wibracjeX = x, wibracjeY = y, wibracjeZ = z,
                        przyciecia = (c0 + c1 + c2).toInt(),
                        czasWibracji = teraz,
                    )
                }
            }

            /**
             * Dom **z maszyny**. Do 2026-08-26 zgadywaliśmy go sami w chwili uzbrojenia,
             * więc strzałka „DO DOMU" mogła wskazywać nie tam, gdzie poleci RTL.
             */
            Mavlink.HOME_POSITION -> {
                val lat = o.i32(); val lon = o.i32()
                if (lat != 0 || lon != 0) {
                    _stan.update {
                        it.copy(
                            domSzerokosc = lat / 1e7, domDlugosc = lon / 1e7,
                            domUstalony = true, domZMaszyny = true,
                        )
                    }
                }
            }

            /**
             * Naruszenie geofence. Zapas **do** granicy liczy `domain/Ogrodzenie.kt`
             * z pozycji i parametrów — MAVLink go nie podaje, a to on jest użyteczny
             * zanim cokolwiek się stanie.
             */
            /**
             * Cel automatu. W RTL i AUTO to jedyne źródło informacji, **dokąd maszyna
             * zmierza i o ile chybia** — poz. 51 notuje przestrzelenie zadanego kąta
             * o 30–50 % przy hamowaniu, co widać właśnie w tych błędach.
             */
            Mavlink.NAV_CONTROLLER_OUTPUT -> {
                o.f32(); o.f32()                           // nav_roll, nav_pitch
                val bladWys = o.f32()
                o.f32()                                    // aspd_error — wielowirnikowiec nie ma
                val bladToru = o.f32()
                o.i16()                                    // nav_bearing
                val namiar = o.i16()
                val dystans = o.u16()
                _stan.update {
                    it.copy(
                        doPunktuM = dystans.toFloat(),
                        bladWysokosciM = bladWys,
                        bladToruM = bladToru,
                        namiarNaCelSt = ((namiar % 360) + 360) % 360f,
                        czasCelu = teraz,
                    )
                }
            }

            Mavlink.FENCE_STATUS -> {
                o.u32()                                    // breach_time
                val ile = o.u16()
                // Kolejność pól: breach_count, breach_STATUS, breach_TYPE. `status` mówi,
                // czy naruszenie trwa **teraz**; `type` pamięta ostatni rodzaj także po
                // powrocie w granice — bez tego rozróżnienia baner nie gasłby nigdy.
                val trwa = o.u8() != 0
                val rodzaj = o.u8()
                _stan.update {
                    it.copy(
                        naruszenieOgrodzenia =
                            if (trwa) Ogrodzenie.Naruszenie.z(rodzaj)
                            else Ogrodzenie.Naruszenie.BRAK,
                        liczbaNaruszenOgrodzenia = ile,
                    )
                }
            }

            Mavlink.STATUSTEXT -> {
                val waga = o.u8()
                val tekst = o.tekst(50)
                // Powtorzenia zwijamy: FC potrafi nadawac ten sam banner co sekunde,
                // a lista, w ktorej wszystko jest takie samo, nie niesie zadnej informacji.
                if (tekst.isNotBlank()) _stan.update { st ->
                    if (st.komunikaty.firstOrNull()?.tekst == tekst) {
                        st.copy(komunikaty = st.komunikaty.mapIndexed { i, k ->
                            if (i == 0) k.copy(czas = teraz, powtorzenia = k.powtorzenia + 1) else k
                        })
                    } else {
                        st.copy(komunikaty = (listOf(Komunikat(waga, tekst, teraz)) + st.komunikaty).take(40))
                    }
                }
            }

            Mavlink.PARAM_VALUE -> {
                val wartosc = o.f32()
                o.u16(); o.u16()
                val nazwa = o.tekst(16)
                if (nazwa.isNotBlank()) _stan.update { it.copy(parametry = it.parametry + (nazwa to wartosc)) }
            }
        }
    }

    fun ustawWideo(dziala: Boolean) = _stan.update { it.copy(wideoDziala = dziala) }

    fun ustawGlowice(
        pitch: Float, yaw: Float, zoom: Float, nagrywa: Boolean,
        trybRuchu: String = "—", odpowiada: Boolean = true,
    ) = _stan.update {
        it.copy(glowicaPitch = pitch, glowicaYaw = yaw, glowicaZoom = zoom,
            glowicaNagrywa = nagrywa, glowicaTrybRuchu = trybRuchu, glowicaOdpowiada = odpowiada)
    }

    /** Zapamiętanie wysłanej komendy — odpowiedź dopisze COMMAND_ACK. */
    fun zapiszKomende(kod: Int, nazwa: String, teraz: Long = System.currentTimeMillis()) =
        _stan.update { it.copy(ostatniaKomenda = Komenda(kod, nazwa, teraz)) }

    fun dopiszKomunikat(tekst: String, waga: Int = 6, teraz: Long = System.currentTimeMillis()) =
        _stan.update { it.copy(komunikaty = (listOf(Komunikat(waga, tekst, teraz)) + it.komunikaty).take(40)) }

    private companion object {

        fun pitchZKwaternionu(w: Float, x: Float, y: Float, z: Float): Float {
            val s = 2f * (w * y - z * x)
            val ograniczone = s.coerceIn(-1f, 1f)
            return (kotlin.math.asin(ograniczone) * 180f / PI).toFloat()
        }

        fun yawZKwaternionu(w: Float, x: Float, y: Float, z: Float): Float {
            val a = 2f * (w * z + x * y)
            val b = 1f - 2f * (y * y + z * z)
            return (kotlin.math.atan2(a, b) * 180f / PI).toFloat()
        }
    }
}
