package pl.dron15.cockpit.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Checklista przedlotowa licząca się sama z parametrów kontrolera lotu.
 *
 * To jest funkcja, której nie zrobi żaden gotowy GCS: reguły pochodzą z CLAUDE.md **tej**
 * maszyny. Najważniejsza z nich to FRAME_CLASS — parametr, który po pracy w Mission Plannerze
 * potrafił cofnąć się z 1 na 4 i uzbroić miksler ośmiu silników na maszynie, która ma cztery.
 *
 * Reguły siedzą w pliku JSON (assets/preflight_rules.json), więc dają się poprawić bez
 * przebudowy aplikacji — a wartości oczekiwane trzymamy w jednym miejscu, nie po kodzie.
 */
enum class Werdykt { OK, OSTRZEZENIE, BLOKADA, BRAK_DANYCH }

/**
 * Jedna niezgodność, którą **da się naprawić zapisem jednego parametru**.
 *
 * Powstaje wyłącznie z warunków postaci „parametr X ma być równy Y" — bo tylko tam
 * wartość docelowa jest jednoznaczna. Warunki typu „co najmniej", „maska" czy liczone
 * z wyrażenia poprawki nie dają: nie wiadomo, jaką liczbę wpisać, a zgadywanie w miejscu,
 * które zapisuje do kontrolera lotu, jest ostatnią rzeczą, jakiej tu potrzeba.
 */
data class Poprawka(
    val parametr: String,
    val obecna: Float?,
    val docelowa: Float,
)

data class PozycjaChecklisty(
    val id: String,
    val opis: String,
    val werdykt: Werdykt,
    val wartosc: String,
    val oczekiwane: String,
    val komunikat: String = "",
    /** Co można poprawić z tego ekranu. Puste, gdy pozycja jest zgodna albo nie do zapisu. */
    val poprawki: List<Poprawka> = emptyList(),
)

class Checklista(private val reguly: List<Regula>) {

    /** Nazwy parametrów, o które trzeba zapytać maszynę. Pytamy tylko o to, co oceniamy. */
    val potrzebneParametry: List<String> by lazy {
        reguly.flatMap { it.warunki.flatMap { w -> w.parametry() } }.distinct().sorted()
    }

    fun ocen(parametry: Map<String, Float>, stan: StanMaszyny, teraz: Long): List<PozycjaChecklisty> =
        reguly.map { it.ocen(parametry, stan, teraz) }

    /** Jeden werdykt na dole ekranu: GOTOWY / OSTRZEŻENIA / NIE STARTOWAĆ. */
    fun werdyktZbiorczy(pozycje: List<PozycjaChecklisty>): Werdykt = when {
        pozycje.any { it.werdykt == Werdykt.BLOKADA } -> Werdykt.BLOKADA
        pozycje.any { it.werdykt == Werdykt.BRAK_DANYCH } -> Werdykt.BRAK_DANYCH
        pozycje.any { it.werdykt == Werdykt.OSTRZEZENIE } -> Werdykt.OSTRZEZENIE
        else -> Werdykt.OK
    }

    // ------------------------------------------------------------------ model reguł

    data class Regula(
        val id: String,
        val opis: String,
        val poziom: Waga,
        val warunki: List<Warunek>,
        val komunikatBledu: String,
    ) {
        fun ocen(parametry: Map<String, Float>, stan: StanMaszyny, teraz: Long): PozycjaChecklisty {
            val wyniki = warunki.map { it.sprawdz(parametry, stan, teraz) }
            val werdykt = when {
                wyniki.any { it.brakDanych } -> Werdykt.BRAK_DANYCH
                wyniki.all { it.spelniony } -> Werdykt.OK
                poziom == Waga.BLOKADA -> Werdykt.BLOKADA
                poziom == Waga.OSTRZEZENIE -> Werdykt.OSTRZEZENIE
                else -> Werdykt.OK          // poziom INFORMACJA nigdy nie zatrzymuje lotu
            }
            return PozycjaChecklisty(
                id = id, opis = opis, werdykt = werdykt,
                wartosc = wyniki.joinToString(" · ") { it.wartosc },
                oczekiwane = warunki.joinToString(" · ") { it.opisOczekiwania() },
                komunikat = if (werdykt == Werdykt.OK) "" else komunikatBledu,
                poprawki = if (werdykt == Werdykt.OK) emptyList()
                else warunki.mapNotNull { it.poprawka(parametry) },
            )
        }
    }

    data class Wynik(val spelniony: Boolean, val wartosc: String, val brakDanych: Boolean = false)

    /**
     * Warunek pojedynczy. Świadomie prosty zestaw porównań — reguły mają być czytelne
     * dla człowieka, który za pół roku będzie szukał, czemu maszyna nie chce wystartować.
     */
    class Warunek(
        private val param: String? = null,
        private val zakres: Pair<String, String>? = null,   // np. SERVO5_FUNCTION..SERVO16_FUNCTION
        private val pole: String? = null,                   // dane z telemetrii, np. gnss.satelity
        private val wyrazenie: Pair<String, String>? = null, // A - B
        private val rowne: Float? = null,
        private val rozneOd: Float? = null,
        private val tolerancja: Float = 0.0001f,
        private val coNajmniej: Float? = null,
        private val najwyzej: Float? = null,
        private val wiekszeNiz: Float? = null,
        private val modulMniejszyNizStopni: Float? = null,
        private val maskaUstawiona: Int? = null,
        private val prawda: Boolean? = null,
    ) {
        fun parametry(): List<String> = buildList {
            param?.let { add(it) }
            zakres?.let { addAll(rozwinZakres(it.first, it.second)) }
            wyrazenie?.let { add(it.first); add(it.second) }
        }

        /**
         * Poprawka dla tego warunku — albo `null`, gdy nie ma czego jednoznacznie zapisać.
         *
         * Celowo **tylko** `param` + `rowne`. Zakresy `SERVO5..16` odpadają, bo jeden klawisz
         * zapisywałby dwanaście parametrów naraz; „co najmniej", „maska" i wyrażenia odpadają,
         * bo wartość docelowa nie jest z nich wyprowadzalna.
         */
        fun poprawka(parametry: Map<String, Float>): Poprawka? {
            val nazwa = param ?: return null
            val cel = rowne ?: return null
            val obecna = parametry[nazwa]
            if (obecna != null && abs(obecna - cel) <= tolerancja) return null
            return Poprawka(nazwa, obecna, cel)
        }

        fun sprawdz(parametry: Map<String, Float>, stan: StanMaszyny, teraz: Long): Wynik {
            // --- warunki na parametrach
            if (param != null) {
                val v = parametry[param] ?: return Wynik(false, "—", brakDanych = true)
                return porownaj(v, sformatuj(v))
            }
            if (zakres != null) {
                val nazwy = rozwinZakres(zakres.first, zakres.second)
                val wartosci = nazwy.map { parametry[it] }
                if (wartosci.any { it == null }) return Wynik(false, "—", brakDanych = true)
                val zle = wartosci.filterNotNull().filter { !porownaj(it, "").spelniony }
                return Wynik(zle.isEmpty(), if (zle.isEmpty()) "wszystkie zgodne" else "${zle.size} niezgodnych")
            }
            if (wyrazenie != null) {
                val a = parametry[wyrazenie.first] ?: return Wynik(false, "—", brakDanych = true)
                val b = parametry[wyrazenie.second] ?: return Wynik(false, "—", brakDanych = true)
                val roznica = a - b
                return porownaj(roznica, "%.0f − %.0f = %.0f".format(a, b, roznica))
            }
            // --- warunki na telemetrii
            if (pole != null) {
                val v = wartoscPola(pole, stan, teraz) ?: return Wynik(false, "—", brakDanych = true)
                return porownaj(v, sformatujPole(pole, v))
            }
            return Wynik(true, "—")
        }

        private fun porownaj(v: Float, opis: String): Wynik {
            rowne?.let { return Wynik(abs(v - it) <= tolerancja, opis) }
            rozneOd?.let { return Wynik(abs(v - it) > tolerancja, opis) }
            coNajmniej?.let { return Wynik(v >= it, opis) }
            najwyzej?.let { return Wynik(v <= it, opis) }
            wiekszeNiz?.let { return Wynik(v > it, opis) }
            modulMniejszyNizStopni?.let {
                val stopnie = Math.toDegrees(v.toDouble()).toFloat()
                return Wynik(abs(stopnie) < it, "%.2f°".format(stopnie))
            }
            maskaUstawiona?.let { return Wynik((v.toInt() and it) == it, "0x%04X".format(v.toInt())) }
            prawda?.let { return Wynik((v != 0f) == it, if (v != 0f) "tak" else "nie") }
            return Wynik(true, opis)
        }

        fun opisOczekiwania(): String = when {
            rowne != null -> sformatuj(rowne)
            rozneOd != null -> "≠ ${sformatuj(rozneOd)}"
            coNajmniej != null -> "≥ ${sformatuj(coNajmniej)}"
            najwyzej != null -> "≤ ${sformatuj(najwyzej)}"
            wiekszeNiz != null -> "> ${sformatuj(wiekszeNiz)}"
            modulMniejszyNizStopni != null -> "|kąt| < ${sformatuj(modulMniejszyNizStopni)}°"
            maskaUstawiona != null -> "maska 0x%04X".format(maskaUstawiona)
            prawda != null -> if (prawda) "tak" else "nie"
            else -> "—"
        }

        private fun wartoscPola(nazwa: String, s: StanMaszyny, teraz: Long): Float? = when (nazwa) {
            "gnss.satelity" -> s.satelity.toFloat().takeIf { s.telemetriaZywa(teraz) }
            "gnss.hdop" -> s.hdop.takeIf { s.telemetriaZywa(teraz) && s.hdop > 0f }
            "gnss.kurs_dostepny" -> if (s.kursGnssDostepny) 1f else 0f
            "ekf.flagi" -> s.flagiEkf.toFloat().takeIf { s.flagiEkf != 0 }
            "ekf.wariancja_kursu" -> s.wariancjaKursu
            "bateria.napiecie" -> s.napiecieV.takeIf { it > 0.1f }
            "lacze.zywe" -> if (s.telemetriaZywa(teraz)) 1f else 0f
            "wideo.dziala" -> if (s.wideoDziala) 1f else 0f
            "glowica.odpowiada" -> if (s.glowicaOdpowiada) 1f else 0f
            else -> null
        }

        private fun sformatujPole(nazwa: String, v: Float): String = when (nazwa) {
            "gnss.satelity" -> "${v.toInt()}"
            "ekf.flagi" -> "0x%04X".format(v.toInt())
            "bateria.napiecie" -> "%.2f V".format(v)
            "gnss.kurs_dostepny", "lacze.zywe", "wideo.dziala", "glowica.odpowiada" ->
                if (v != 0f) "tak" else "nie"
            else -> sformatuj(v)
        }
    }

    companion object {

        fun sformatuj(v: Float): String =
            if (abs(v - v.toInt()) < 0.0001f) v.toInt().toString() else "%.4f".format(v).trimEnd('0')

        /** SERVO5_FUNCTION..SERVO16_FUNCTION → lista dwunastu nazw. */
        fun rozwinZakres(od: String, doo: String): List<String> {
            val liczba = Regex("\\d+")
            val a = liczba.find(od)?.value?.toInt() ?: return listOf(od, doo)
            val b = liczba.find(doo)?.value?.toInt() ?: return listOf(od, doo)
            if (a > b) return listOf(od, doo)
            return (a..b).map { od.replaceFirst(liczba, it.toString()) }
        }

        /** Wczytanie reguł. Nieznane klucze pomijamy — plik ma prawo wyprzedzać kod. */
        fun zJson(tresc: String): Checklista {
            val root = JSONObject(tresc)
            val reguly = ArrayList<Regula>()
            for (sekcja in listOf("parametry", "telemetria")) {
                val tablica = root.optJSONArray(sekcja) ?: continue
                for (i in 0 until tablica.length()) {
                    val o = tablica.getJSONObject(i)
                    val warunki = when {
                        o.has("warunki") -> czytajWarunki(o.getJSONArray("warunki"))
                        o.has("warunek") -> listOf(czytajWarunek(o.getJSONObject("warunek")))
                        else -> emptyList()
                    }
                    if (warunki.isEmpty()) continue
                    reguly += Regula(
                        id = o.optString("id", "reguła $i"),
                        opis = o.optString("opis", ""),
                        poziom = when (o.optString("poziom")) {
                            "blokada" -> Waga.BLOKADA
                            "ostrzezenie" -> Waga.OSTRZEZENIE
                            else -> Waga.INFORMACJA
                        },
                        warunki = warunki,
                        komunikatBledu = o.optString("komunikat_bledu", ""),
                    )
                }
            }
            return Checklista(reguly)
        }

        private fun czytajWarunki(t: JSONArray): List<Warunek> =
            (0 until t.length()).map { czytajWarunek(t.getJSONObject(it)) }

        private fun czytajWarunek(o: JSONObject): Warunek {
            val zakres = o.optString("param_zakres").takeIf { it.contains("..") }
                ?.split("..")?.let { it[0] to it[1] }
            val wyrazenie = o.optString("wyrazenie").takeIf { it.contains("-") }
                ?.split("-")?.map { it.trim() }?.let { it[0] to it[1] }
            fun f(k: String): Float? = if (o.has(k)) o.getDouble(k).toFloat() else null
            return Warunek(
                param = o.optString("param").ifBlank { null },
                zakres = zakres,
                pole = o.optString("pole").ifBlank { null },
                wyrazenie = wyrazenie,
                rowne = f("rowne"),
                rozneOd = f("rozne_od"),
                tolerancja = f("tolerancja") ?: 0.0001f,
                coNajmniej = f("co_najmniej"),
                najwyzej = f("najwyzej"),
                wiekszeNiz = f("wieksze_niz"),
                modulMniejszyNizStopni = f("modul_mniejszy_niz_stopni"),
                maskaUstawiona = o.optString("maska_ustawiona").ifBlank { null }
                    ?.let { Integer.decode(it) },
                prawda = if (o.has("prawda")) o.getBoolean("prawda") else null,
            )
        }
    }
}
