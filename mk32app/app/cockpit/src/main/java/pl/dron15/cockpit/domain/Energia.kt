package pl.dron15.cockpit.domain

import kotlin.math.max

/**
 * Bilans energii pakietu i momenty **JOKER** oraz **BINGO**.
 *
 * ### Po co to istnieje
 *
 * Na tej maszynie **pomiar napięcia nie działa** (`CLAUDE.md` poz. 37): wejście czyta
 * stabilizowaną szynę, więc `BAT.Volt` stoi na 24,907…25,020 V przy prądzie skaczącym
 * 47→61 A. `BATT_LOW_VOLT` i `BATT_CRT_VOLT` nigdy nie zadziałają, a słupek baterii
 * na belce będzie zielony do samego końca.
 *
 * Jedyną wielkością, która naprawdę mierzy zużycie, jest **licznik amperogodzin**.
 * Był dekodowany od początku i nie pokazywany nigdzie (`dok/PROPOZYCJA_LOT.md`, fakt 2).
 *
 * ### JOKER i BINGO
 *
 * - **JOKER** — moment, w którym trzeba ruszyć do domu, żeby wrócić z rezerwą.
 * - **BINGO** — moment, po którym powrót przestaje być możliwy.
 *
 * Poz. 45 kończy się zdaniem *„Pilot nie miał żadnego ostrzeżenia"*. To jest odpowiedź
 * na tamto zdanie.
 *
 * ### ⚠ Wiarygodność
 *
 * `BATT_CAPACITY = 3300` przy zużyciu **4538 mAh w jednym locie** (poz. 40) znaczy, że
 * pojemność albo `BATT_AMP_PERVLT` są niekalibrowane. Dlatego [Bilans.wiarygodny] jest
 * `false`, dopóki pojemność nie wygląda sensownie, a ekran ma wtedy pokazywać
 * **surowe mAh i A**, nigdy procent ani minuty. Liczba, której nie da się zweryfikować,
 * jest gorsza od liczby, której nie ma.
 */
object Energia {

    /** Udział pojemności, przy którym pada JOKER. */
    const val PROG_JOKER = 0.35f

    /** Udział pojemności, przy którym pada BINGO. */
    const val PROG_BINGO = 0.20f

    /** Domyślna prędkość powrotu [m/s], gdy `WPNAV_SPEED` nie został pobrany. */
    private const val PREDKOSC_POWROTU_MS = 6f

    /** Domyślna prędkość opadania [m/s]. */
    private const val PREDKOSC_OPADANIA_MS = 0.75f

    /** Margines na dolot, ustawienie się i lądowanie [s]. */
    private const val MARGINES_S = 45f

    /**
     * Zakres, w którym średnia z licznika jest wiarygodna.
     *
     * Ta maszyna ciągnie w zawisie 50–60 A (`CLAUDE.md` poz. 45), a szczyty przy starcie
     * dwukrotnie tyle. Wartość poza tym przedziałem znaczy, że licznik i zegar lotu
     * mówią o różnych rzeczach — a nie że maszyna naprawdę pobiera 581 A.
     */
    private const val MIN_SENSOWNY_PRAD_A = 1f
    private const val MAKS_SENSOWNY_PRAD_A = 200f

    data class Bilans(
        val zuzycieMah: Int = 0,
        val pojemnoscMah: Int = 0,
        val pradA: Float = 0f,
        val sredniPradA: Float = 0f,
        /** Udział zużytej pojemności 0..1; sensowny tylko przy [wiarygodny]. */
        val udzial: Float = 0f,
        /** Sekundy do JOKER; ujemne = już minął. `null` gdy nie da się policzyć. */
        val doJokeraS: Int? = null,
        val doBingoS: Int? = null,
        /** Ile sekund zajmie sam powrót z obecnej pozycji. */
        val powrotS: Int = 0,
        val wiarygodny: Boolean = false,
        val powodNiepewnosci: String? = null,
    ) {
        val poJokerze: Boolean get() = wiarygodny && (doJokeraS ?: 1) <= 0
        val poBingo: Boolean get() = wiarygodny && (doBingoS ?: 1) <= 0
    }

    /**
     * @param stan bieżący stan maszyny
     * @param teraz zegar systemowy [ms]
     */
    fun policz(stan: StanMaszyny, teraz: Long): Bilans {
        val pojemnosc = (stan.parametry["BATT_CAPACITY"] ?: 0f).toInt()
        val zuzycie = stan.zuzycieMah
        val czasLotuS = stan.czasLotuS(teraz)

        // Prąd średni. mAh → A: ÷1000 (na Ah) ×3600 (na godziny), czyli ×3,6.
        //
        // ⚠ Licznik `zuzycieMah` liczy **od podłączenia pakietu**, a nie od startu lotu,
        // więc `zużycie / czas lotu` potrafi dać absurd: 2100 mAh przy 13 s w powietrzu
        // wychodzi 581 A i JOKER wypada natychmiast na 0:00. Widać to było na ekranie
        // 2026-08-28, zanim licznik zdążył się zrównać z zegarem.
        //
        // Dlatego średnia z licznika obowiązuje **tylko wtedy, gdy wychodzi lotna**;
        // poza tym zakresem wierzymy chwilowemu odczytowi, który jest zaszumiony,
        // ale nie kłamie o rząd wielkości.
        val zLicznika = if (czasLotuS > 10) zuzycie * 3.6f / czasLotuS else 0f
        val sredni = if (zLicznika in MIN_SENSOWNY_PRAD_A..MAKS_SENSOWNY_PRAD_A) zLicznika
        else stan.pradA

        val powrot = czasPowrotuS(stan)

        // Pojemność mniejsza niż to, co już zużyliśmy, jest dowodem błędnej kalibracji
        // — a nie powodem, żeby pokazać 138 %.
        val powod = when {
            pojemnosc <= 0 -> "BATT_CAPACITY nie pobrane z maszyny"
            zuzycie > pojemnosc -> "zużycie ($zuzycie mAh) przekracza BATT_CAPACITY ($pojemnosc) — kalibracja"
            else -> null
        }
        if (powod != null) {
            return Bilans(
                zuzycieMah = zuzycie, pojemnoscMah = pojemnosc,
                pradA = stan.pradA, sredniPradA = sredni,
                powrotS = powrot, wiarygodny = false, powodNiepewnosci = powod,
            )
        }

        val udzial = (zuzycie.toFloat() / pojemnosc).coerceIn(0f, 1f)
        val zostaloMah = pojemnosc - zuzycie
        // A → mAh/s: ×1000 (na mA) ÷3600 (na sekundy), czyli ÷3,6.
        val mahNaSekunde = if (sredni > 0.5f) sredni / 3.6f else 0f

        // JOKER wypada wtedy, gdy zostanie tyle, ile trzeba na powrót plus rezerwa.
        val rezerwaJoker = pojemnosc * PROG_JOKER
        val rezerwaBingo = pojemnosc * PROG_BINGO
        val naPowrotMah = if (mahNaSekunde > 0f) powrot * mahNaSekunde else 0f

        fun sekundDo(rezerwa: Float): Int? =
            if (mahNaSekunde <= 0f) null
            else ((zostaloMah - max(rezerwa, naPowrotMah)) / mahNaSekunde).toInt()

        return Bilans(
            zuzycieMah = zuzycie,
            pojemnoscMah = pojemnosc,
            pradA = stan.pradA,
            sredniPradA = sredni,
            udzial = udzial,
            doJokeraS = sekundDo(rezerwaJoker),
            doBingoS = sekundDo(rezerwaBingo),
            powrotS = powrot,
            wiarygodny = true,
        )
    }

    /**
     * Ile sekund zajmie powrót: dolot poziomy plus opadanie plus margines.
     * Prędkości bierzemy z parametrów maszyny, gdy są — inaczej z domyślnych.
     */
    fun czasPowrotuS(stan: StanMaszyny): Int {
        val dystans = stan.dystansDoDomuM.takeIf { it >= 0f } ?: 0f
        val predkosc = (stan.parametry["WPNAV_SPEED"]?.div(100f) ?: PREDKOSC_POWROTU_MS)
            .coerceAtLeast(1f)
        val opadanie = (stan.parametry["LAND_SPEED_HIGH"]?.div(100f)
            ?: PREDKOSC_OPADANIA_MS).coerceAtLeast(0.2f)
        val wysokosc = stan.wysokoscM.coerceAtLeast(0f)
        return (dystans / predkosc + wysokosc / opadanie + MARGINES_S).toInt()
    }

    /** „3:24" albo „—". */
    fun czas(sekundy: Int?): String = when {
        sekundy == null -> "—"
        sekundy <= 0 -> "0:00"
        else -> "%d:%02d".format(sekundy / 60, sekundy % 60)
    }
}
