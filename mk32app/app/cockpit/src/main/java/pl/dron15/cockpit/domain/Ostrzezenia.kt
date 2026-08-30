package pl.dron15.cockpit.domain

/**
 * Ocena bezpieczeństwa — jedno miejsce, w którym powstają banery.
 *
 * Reguły wprost z CLAUDE.md tej maszyny. Liczy je aplikacja na MK32 i rozsyła gotowe,
 * żeby każdy podłączony klient widział dokładnie ten sam alarm (dok/UI.md, element „baner").
 */
enum class Waga { BLOKADA, OSTRZEZENIE, INFORMACJA }

data class Ostrzezenie(
    val id: String,
    val waga: Waga,
    val tekst: String,
    val szczegol: String = "",
)

object Ostrzezenia {

    const val NAPIECIE_GORNE = 25.2f     // górny limit ZR30 i air unitu MK32 — poz. 8
    const val NAPIECIE_DOLNE = 22.2f     // BATT_LOW_VOLT
    const val SATELITY_MIN = 12
    const val HDOP_MAKS = 1.2f
    const val WARIANCJA_KURSU_MAKS = 0.3f

    /** Nagły spadek liczby satelitów — podejrzenie zagłuszania przez VTX (poz. 36). */
    private const val SPADEK_SATELITOW = 8
    private const val OKNO_SPADKU_MS = 10_000L

    private val historiaSatelitow = ArrayDeque<Pair<Long, Int>>()

    fun ocen(s: StanMaszyny, teraz: Long): List<Ostrzezenie> {
        val lista = ArrayList<Ostrzezenie>(6)

        if (!s.telemetriaZywa(teraz)) {
            lista += Ostrzezenie(
                "telemetria", Waga.BLOKADA, "UTRATA TELEMETRII",
                if (s.telemetriaByla) "brak danych od ${s.opisCiszy(teraz)}"
                else "nie przyszedł ani jeden heartbeat — sprawdź air unit i port"
            )
        }

        // FRAME_CLASS potrafi cofnąć się sam po pracy w Mission Plannerze — udokumentowane 3×
        s.parametry["FRAME_CLASS"]?.let { klasa ->
            if (klasa.toInt() != 1) lista += Ostrzezenie(
                "rama", Waga.BLOKADA, "NIE STARTOWAĆ — MIKSLER 8 SILNIKÓW",
                "FRAME_CLASS=${klasa.toInt()}, a maszyna ma cztery silniki"
            )
        }

        if (!s.kursGnssDostepny) {
            lista += Ostrzezenie(
                "kurs", Waga.BLOKADA, "BRAK KURSU GNSS — RTL I MISJA NIEDOSTĘPNE",
                "kurs pochodzi wyłącznie z bazy GNSS; sprowadź maszynę w AltHold"
            )
        }

        if (wykryjSpadekSatelitow(s.satelity, teraz)) {
            lista += Ostrzezenie(
                "satelity_spadek", Waga.BLOKADA, "NAGŁA UTRATA SATELITÓW — SPRAWDŹ VTX",
                "spadek o co najmniej $SPADEK_SATELITOW w 10 s"
            )
        }

        if (s.napiecieV > NAPIECIE_GORNE) {
            lista += Ostrzezenie(
                "napiecie_gorne", Waga.OSTRZEZENIE, "NAPIĘCIE NA GRANICY ZR30 I AIR UNITU",
                "%.2f V przy limicie %.1f V".format(s.napiecieV, NAPIECIE_GORNE)
            )
        } else if (s.napiecieV in 0.1f..NAPIECIE_DOLNE) {
            lista += Ostrzezenie(
                "napiecie_dolne", Waga.BLOKADA, "NISKIE NAPIĘCIE — LĄDUJ",
                "%.2f V, próg %.1f V".format(s.napiecieV, NAPIECIE_DOLNE)
            )
        }

        // Czujnik zgłoszony przez maszynę jako niezdrowy. Bierzemy to wprost z masek
        // SYS_STATUS, a nie z tekstu PreArm — dok/PROPOZYCJA_LOT.md §4.4.
        // Nieobecne czujniki są tu pominięte: brak kompasu na tej maszynie jest decyzją.
        val czujniki = Czujniki.odczytaj(s.czujnikiObecne, s.czujnikiWlaczone, s.czujnikiZdrowe)
        Czujniki.opisUsterek(czujniki)?.let { opis ->
            lista += Ostrzezenie(
                "czujniki", Waga.BLOKADA, "CZUJNIK NIESPRAWNY", opis,
            )
        }

        // Geofence: naruszenie jest faktem z maszyny, zapas liczymy sami.
        val plot = Ogrodzenie.policz(s)
        when (plot.ocena) {
            Ogrodzenie.Ocena.NARUSZONE -> lista += Ostrzezenie(
                "plot", Waga.BLOKADA, "GEOFENCE NARUSZONY", plot.naruszenie.opis,
            )
            Ogrodzenie.Ocena.OSTRZEZENIE, Ogrodzenie.Ocena.UWAGA -> lista += Ostrzezenie(
                "plot_zapas", Waga.OSTRZEZENIE, "BLISKO GRANICY GEOFENCE",
                "%.0f m zapasu".format(plot.najmniejszyM ?: 0f) +
                        if (!plot.pewny) " — dom zgadnięty, nie z maszyny" else "",
            )
            else -> Unit
        }

        if ((s.flagiEkf and StanMaszyny.FLAGA_GPS_ZAKLOCENIA) != 0) {
            lista += Ostrzezenie("gps_glitch", Waga.OSTRZEZENIE, "ZAKŁÓCENIA GPS")
        }

        if (s.satelity in 1 until SATELITY_MIN) {
            lista += Ostrzezenie(
                "satelity", Waga.OSTRZEZENIE, "MAŁO SATELITÓW",
                "${s.satelity}, wymagane $SATELITY_MIN"
            )
        }

        if (s.hdop > HDOP_MAKS) {
            lista += Ostrzezenie("hdop", Waga.OSTRZEZENIE, "SŁABA GEOMETRIA GNSS", "HDOP %.2f".format(s.hdop))
        }

        if (s.wariancjaKursu > WARIANCJA_KURSU_MAKS) {
            lista += Ostrzezenie(
                "wariancja", Waga.OSTRZEZENIE, "NIESTABILNY KURS",
                "wariancja %.2f".format(s.wariancjaKursu)
            )
        }

        if (!s.wideoDziala && s.telemetriaZywa(teraz)) {
            lista += Ostrzezenie("wideo", Waga.OSTRZEZENIE, "BRAK OBRAZU Z KAMERY")
        }

        s.komunikaty.firstOrNull { it.blokujePrearm && teraz - it.czas < 10_000 }?.let {
            lista += Ostrzezenie("prearm", Waga.OSTRZEZENIE, "BLOKADA PRZED UZBROJENIEM", it.tekst)
        }

        return lista.sortedBy { it.waga.ordinal }
    }

    /** Najważniejszy aktywny baner. Widoczny jest tylko jeden — dok/UI.md. */
    fun najwazniejsze(lista: List<Ostrzezenie>): Ostrzezenie? = lista.firstOrNull()

    private fun wykryjSpadekSatelitow(satelity: Int, teraz: Long): Boolean {
        if (satelity <= 0) return false
        historiaSatelitow.addLast(teraz to satelity)
        while (historiaSatelitow.isNotEmpty() && teraz - historiaSatelitow.first().first > OKNO_SPADKU_MS) {
            historiaSatelitow.removeFirst()
        }
        val szczyt = historiaSatelitow.maxOfOrNull { it.second } ?: return false
        return szczyt - satelity >= SPADEK_SATELITOW
    }
}
