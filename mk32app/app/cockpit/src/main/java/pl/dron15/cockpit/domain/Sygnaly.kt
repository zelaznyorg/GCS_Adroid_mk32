package pl.dron15.cockpit.domain

import pl.dron15.cockpit.diag.Dzwieki

/**
 * Decyduje, **kiedy** zagrać alarm. Sam dźwięk wydaje `diag/Dzwieki.kt`.
 *
 * Rozdzielone celowo: reguła „zapas ciągu spadł poniżej progu" jest logiką lotu i da się
 * ją przetestować bez głośnika, emulatora i Androida. `ToneGenerator` testować się nie da.
 *
 * ### Zasada nadrzędna — cisza znaczy „nic się nie dzieje"
 *
 * Alarm, który gra bez przerwy, pilot wyłącza. Dlatego:
 * - JOKER i BINGO grają **raz na przekroczenie**, nie w kółko;
 * - zapas ciągu gra tylko poniżej progu uwagi, a tempo rośnie dopiero przy zbliżaniu
 *   się do zera;
 * - utrata łącza gra co 5 s, bo to stan, o którym trzeba przypominać;
 * - potwierdzenia komend grają raz na komendę.
 */
class Sygnaly {

    private var byloPoJokerze = false
    private var byloPoBingo = false
    private var bylaCisza = false
    private var ostatniaKomenda: Long = 0L

    data class Sygnal(val rodzaj: Dzwieki.Rodzaj, val pilnosc: Float = 0f)

    /**
     * @return sygnały do zagrania w tej chwili; pusta lista to stan normalny.
     */
    fun ocen(stan: StanMaszyny, teraz: Long): List<Sygnal> {
        val lista = ArrayList<Sygnal>(2)

        // --- łącze
        val cisza = !stan.telemetriaZywa(teraz) && stan.telemetriaByla
        if (cisza) lista += Sygnal(Dzwieki.Rodzaj.UTRATA_LACZA)
        bylaCisza = cisza

        // --- zapas ciągu; tylko w locie, bo na ziemi silniki stoją i zapas jest pełny
        if (stan.uzbrojony && stan.wyjsciaZnane(teraz)) {
            val z = Ciag.policz(
                stan.wyjsciaSilnikow,
                stan.parametry["MOT_SPIN_MAX"] ?: Ciag.SPIN_MAX_DOMYSLNY,
            )
            if (z.znany && z.zapasUs <= Ciag.PROG_UWAGI_US) {
                // 0 przy progu uwagi, 1 przy zerowym zapasie.
                val pilnosc = 1f - (z.zapasUs.toFloat() / Ciag.PROG_UWAGI_US).coerceIn(0f, 1f)
                lista += Sygnal(Dzwieki.Rodzaj.ZAPAS_CIAGU, pilnosc)
            }
        }

        // --- paliwo; przejścia, nie stany
        val b = Energia.policz(stan, teraz)
        if (b.wiarygodny && stan.uzbrojony) {
            if (b.poBingo && !byloPoBingo) lista += Sygnal(Dzwieki.Rodzaj.BINGO)
            if (b.poJokerze && !byloPoJokerze && !b.poBingo) lista += Sygnal(Dzwieki.Rodzaj.JOKER)
            byloPoBingo = b.poBingo
            byloPoJokerze = b.poJokerze
        } else if (!stan.uzbrojony) {
            // Nowy lot zaczyna od czystego licznika przekroczeń.
            byloPoBingo = false
            byloPoJokerze = false
        }

        // --- odpowiedź na komendę
        stan.ostatniaKomenda?.let { k ->
            if (k.wynik != null && k.czasOdpowiedzi > ostatniaKomenda) {
                ostatniaKomenda = k.czasOdpowiedzi
                lista += Sygnal(
                    if (k.przyjeta) Dzwieki.Rodzaj.KOMENDA_OK
                    else Dzwieki.Rodzaj.KOMENDA_ODRZUCONA
                )
            }
        }

        return lista
    }
}
