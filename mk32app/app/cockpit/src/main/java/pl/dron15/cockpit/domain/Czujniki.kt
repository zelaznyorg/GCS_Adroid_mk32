package pl.dron15.cockpit.domain

/**
 * Stan każdego czujnika z osobna, z trzech masek `SYS_STATUS`.
 *
 * ### Skąd to się bierze
 *
 * `SYS_STATUS` niesie trzy pola po 32 bity: czujniki **obecne**, **włączone** i **zdrowe**.
 * Do 2026-08-26 kokpit przeskakiwał je wszystkie (`o.pomin(12)`) i szedł po samo napięcie,
 * a o stanie sprzętu wnioskował z tekstu `PreArm:` przechwyconego ze `STATUSTEXT`.
 * Czyli z komunikatu zamiast ze stanu — `dok/PROPOZYCJA_LOT.md` §4.4.
 *
 * Zero nowego pasma: te bajty i tak przychodzą w każdej ramce.
 *
 * ### ⛔ Brak czujnika to nie usterka
 *
 * Ta maszyna **nie ma kompasu i to jest decyzja**, nie awaria: `COMPASS_USE = 0`,
 * kurs idzie wyłącznie z bazy GNSS (`EK3_SRC1_YAW = 2`, `CLAUDE.md` sekcja 5).
 * Ogólny pasek czujników świeciłby na czerwono przy magnetometrze przez cały czas.
 *
 * Dlatego bit **obecności** rozstrzyga wcześniej niż bit zdrowia: czego nie ma,
 * to nie jest zepsute — jest [Stan.NIEOBECNY] i nie zajmuje miejsca na pasku.
 * Ta sama zasada, której złamanie opisuje audyt w S4: przyrząd, który kłamie na czerwono,
 * uczy pilota ignorowania czerwieni.
 */
object Czujniki {

    enum class Stan {
        /** Nie ma go na pokładzie — nie pokazujemy wcale. */
        NIEOBECNY,

        /** Jest, ale wyłączony w konfiguracji. */
        WYLACZONY,

        /** Jest, włączony, zdrowy. */
        SPRAWNY,

        /** Jest, włączony, **niezdrowy** — to jedyny stan, który ma prawo świecić. */
        USZKODZONY,
    }

    /**
     * Czujnik pokazywany na pasku. Kolejność pól = kolejność na ekranie, od najważniejszego.
     *
     * Wybór jest **pod tę maszynę**, nie kompletny: z trzydziestu bitów `SYS_STATUS`
     * na pasek trafia dziewięć, które na tym płatowcu naprawdę coś znaczą.
     */
    enum class Rodzaj(val bit: Int, val skrot: String, val opis: String) {
        GPS(0x00000020, "GPS", "odbiornik GNSS — jedyne źródło kursu na tej maszynie"),
        AHRS(0x00200000, "AHRS", "wyrównanie orientacji"),
        ZYROSKOP(0x00000001, "ŻYR", "żyroskop — jeden, bez redundancji (poz. 1)"),
        AKCELEROMETR(0x00000002, "ACC", "akcelerometr — jeden, bez redundancji (poz. 1)"),
        BAROMETR(0x00000008, "BAR", "barometr — jedyne źródło wysokości w AltHold"),
        ODBIORNIK_RC(0x00010000, "RC", "odbiornik S.Bus z air unitu MK32"),
        SILNIKI(0x00008000, "SIL", "wyjścia silników"),
        BATERIA(0x02000000, "BAT", "pomiar pakietu — ⚠ napięcie martwe, poz. 37"),
        OGRODZENIE(0x00100000, "FEN", "geofence"),
    }

    data class Czujnik(val rodzaj: Rodzaj, val stan: Stan)

    /**
     * @param obecne maska `onboard_control_sensors_present`
     * @param wlaczone maska `onboard_control_sensors_enabled`
     * @param zdrowe maska `onboard_control_sensors_health`
     * @return czujniki **obecne na pokładzie**, w kolejności [Rodzaj]; nieobecne pomijamy
     */
    fun odczytaj(obecne: Int, wlaczone: Int, zdrowe: Int): List<Czujnik> =
        Rodzaj.entries.mapNotNull { r ->
            val stan = stan(r.bit, obecne, wlaczone, zdrowe)
            if (stan == Stan.NIEOBECNY) null else Czujnik(r, stan)
        }

    fun stan(bit: Int, obecne: Int, wlaczone: Int, zdrowe: Int): Stan = when {
        obecne and bit == 0 -> Stan.NIEOBECNY
        wlaczone and bit == 0 -> Stan.WYLACZONY
        zdrowe and bit == 0 -> Stan.USZKODZONY
        else -> Stan.SPRAWNY
    }

    /** Czy cokolwiek jest uszkodzone — do koloru całego paska. */
    fun ktorykolwiekUszkodzony(lista: List<Czujnik>): Boolean =
        lista.any { it.stan == Stan.USZKODZONY }

    /**
     * Zdanie do banera, gdy coś jest uszkodzone. `null`, gdy wszystko w porządku.
     *
     * Nie sklejamy tu listy wszystkiego — baner ma miejsce na jedną myśl, a `PreArm`
     * i tak powie więcej. Wymieniamy do trzech, resztę liczbą.
     */
    fun opisUsterek(lista: List<Czujnik>): String? {
        val zle = lista.filter { it.stan == Stan.USZKODZONY }
        if (zle.isEmpty()) return null
        val nazwy = zle.take(3).joinToString(", ") { it.rodzaj.skrot }
        return if (zle.size > 3) "$nazwy i ${zle.size - 3} więcej" else nazwy
    }
}
