package pl.dron15.cockpit.net.mavlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.dron15.cockpit.diag.Dziennik
import pl.dron15.cockpit.domain.Misja
import pl.dron15.cockpit.domain.PunktMisji
import pl.dron15.cockpit.domain.Wspolrzedne

/**
 * Wysyłka i pobranie misji — standardowy protokół MAVLink (dok/MISJE.md §1).
 *
 * Przebieg wysyłki jest **sterowany przez maszynę, nie przez nas**: my zapowiadamy liczbę
 * punktów, a ona prosi o nie po kolei. Dlatego to jest maszyna stanów wpięta w strumień
 * ramek, a nie pętla wysyłająca wszystko naraz.
 *
 * ```
 *   my  ── MISSION_COUNT(n) ──────────►
 *       ◄── MISSION_REQUEST_INT(0) ────
 *   my  ── MISSION_ITEM_INT(0) ───────►
 *       ◄── MISSION_REQUEST_INT(1) ────
 *        …
 *       ◄── MISSION_ACK(0) ────────────   koniec
 * ```
 *
 * **Punkt 0 to zawsze pozycja domu** — tego wymaga ArduPilot i tak samo robi QGC. Trasa
 * operatora zaczyna się od `seq = 1`.
 *
 * Każdy krok ma dozorcę czasu: przy zerwanym łączu maszyna po prostu przestaje pytać,
 * a bez dozorcy ekran zostawałby z napisem „wysyłam" na zawsze.
 */
class TransferMisji(
    private val lacze: LaczeMavlink,
    private val zakres: CoroutineScope,
) {
    /** Krótki opis tego, co się dzieje — do nagłówka panelu misji. */
    var przyPostepie: ((String) -> Unit)? = null

    private var wysylane: List<PunktMisji>? = null
    private var przyWysylce: ((Boolean, String) -> Unit)? = null

    private var pobierane: MutableMap<Int, PunktMisji>? = null
    private var ileDoPobrania = 0
    private var przyPobraniu: ((Misja?, String) -> Unit)? = null

    private var dozorca: Job? = null

    val zajety: Boolean get() = wysylane != null || pobierane != null

    // ------------------------------------------------------------------ wysyłka

    fun wyslij(
        misja: Misja,
        domSzerokosc: Double,
        domDlugosc: Double,
        przyWyniku: (Boolean, String) -> Unit,
    ) {
        if (zajety) {
            przyWyniku(false, "trwa inna wymiana misji")
            return
        }
        if (misja.pusta) {
            przyWyniku(false, "trasa jest pusta")
            return
        }

        // seq 0 = dom, potem trasa operatora
        val zDomem = listOf(
            PunktMisji(PunktMisji.NAV_WAYPOINT, domSzerokosc, domDlugosc, 0f)
        ) + misja.punkty

        wysylane = zDomem
        przyWysylce = przyWyniku
        przyPostepie?.invoke("wysyłam ${misja.punkty.size} pkt…")
        lacze.wyslij(Mavlink.misjaLiczba(zDomem.size))
        uzbrojDozorce("maszyna nie poprosiła o punkty")
    }

    // ------------------------------------------------------------------ pobranie

    fun pobierz(przyWyniku: (Misja?, String) -> Unit) {
        if (zajety) {
            przyWyniku(null, "trwa inna wymiana misji")
            return
        }
        pobierane = LinkedHashMap()
        ileDoPobrania = 0
        przyPobraniu = przyWyniku
        przyPostepie?.invoke("pobieram misję…")
        lacze.wyslij(Mavlink.misjaZadanieListy())
        uzbrojDozorce("maszyna nie odpowiedziała na zapytanie o misję")
    }

    // ------------------------------------------------------------------ strumień ramek

    fun obsluz(ramka: Mavlink.Ramka) {
        when (ramka.msgid) {
            Mavlink.MISSION_REQUEST_INT, Mavlink.MISSION_REQUEST -> odpowiedzPunktem(ramka)
            Mavlink.MISSION_COUNT -> przyjmijLiczbe(ramka)
            Mavlink.MISSION_ITEM_INT -> przyjmijPunkt(ramka)
            Mavlink.MISSION_ACK -> przyjmijPotwierdzenie(ramka)
        }
    }

    private fun odpowiedzPunktem(ramka: Mavlink.Ramka) {
        val lista = wysylane ?: return
        val seq = Mavlink.Odczyt(ramka.ladunek).u16()
        if (seq !in lista.indices) {
            Dziennik.ostrzezenie("misja", "maszyna prosi o punkt $seq spoza zakresu")
            return
        }
        val p = lista[seq]
        lacze.wyslij(
            Mavlink.misjaPunkt(
                seq = seq,
                komenda = p.komenda,
                lat = Wspolrzedne.doInt(p.szerokosc),
                lon = Wspolrzedne.doInt(p.dlugosc),
                wysokosc = p.wysokoscM,
                p1 = p.p1, p2 = p.p2, p3 = p.p3, p4 = p.p4,
                // Punkt 0 to dom — w ramce bezwzględnej, reszta względem punktu startu.
                ramka = if (seq == 0) PunktMisji.RAMKA_BEZWZGLEDNA else PunktMisji.RAMKA_WZGLEDNA,
                biezacy = seq == 0,
            )
        )
        przyPostepie?.invoke("wysyłam punkt ${seq + 1}/${lista.size}")
        uzbrojDozorce("przerwane po punkcie $seq")
    }

    private fun przyjmijLiczbe(ramka: Mavlink.Ramka) {
        val mapa = pobierane ?: return
        ileDoPobrania = Mavlink.Odczyt(ramka.ladunek).u16()
        if (ileDoPobrania == 0) {
            zakoncPobieranie(Misja(emptyList(), zrodlo = "z maszyny"), "maszyna nie ma misji")
            return
        }
        mapa.clear()
        lacze.wyslij(Mavlink.misjaZadaniePunktu(0))
        przyPostepie?.invoke("pobieram 1/$ileDoPobrania")
        uzbrojDozorce("maszyna nie przysłała punktów")
    }

    private fun przyjmijPunkt(ramka: Mavlink.Ramka) {
        val mapa = pobierane ?: return
        val o = Mavlink.Odczyt(ramka.ladunek)
        val p1 = o.f32(); val p2 = o.f32(); val p3 = o.f32(); val p4 = o.f32()
        val lat = o.i32(); val lon = o.i32(); val z = o.f32()
        val seq = o.u16(); val komenda = o.u16()

        mapa[seq] = PunktMisji(
            komenda = komenda,
            szerokosc = Wspolrzedne.zInt(lat),
            dlugosc = Wspolrzedne.zInt(lon),
            wysokoscM = z,
            p1 = p1, p2 = p2, p3 = p3, p4 = p4,
        )

        val nastepny = (0 until ileDoPobrania).firstOrNull { it !in mapa }
        if (nastepny == null) {
            lacze.wyslij(Mavlink.misjaPotwierdzenie(0))
            // Punkt 0 to pozycja domu, nie element trasy — nie pokazujemy go na liście.
            val trasa = (1 until ileDoPobrania).mapNotNull { mapa[it] }
            zakoncPobieranie(Misja(trasa, zrodlo = "z maszyny"), "pobrano ${trasa.size} pkt")
        } else {
            lacze.wyslij(Mavlink.misjaZadaniePunktu(nastepny))
            przyPostepie?.invoke("pobieram ${mapa.size + 1}/$ileDoPobrania")
            uzbrojDozorce("przerwane na punkcie $nastepny")
        }
    }

    private fun przyjmijPotwierdzenie(ramka: Mavlink.Ramka) {
        val o = Mavlink.Odczyt(ramka.ladunek)
        o.u8(); o.u8()
        val typ = o.u8()
        if (wysylane != null) {
            val ok = typ == 0
            zakonczWysylke(ok, if (ok) "misja przyjęta" else Mavlink.opisWynikuMisji(typ))
        }
    }

    // ------------------------------------------------------------------ dozorca i sprzątanie

    private fun uzbrojDozorce(powod: String) {
        dozorca?.cancel()
        dozorca = zakres.launch {
            delay(CZAS_OCZEKIWANIA_MS)
            if (wysylane != null) zakonczWysylke(false, powod)
            if (pobierane != null) zakoncPobieranie(null, powod)
        }
    }

    private fun zakonczWysylke(ok: Boolean, opis: String) {
        dozorca?.cancel(); dozorca = null
        val zwrot = przyWysylce
        wysylane = null
        przyWysylce = null
        przyPostepie?.invoke(opis)
        zwrot?.invoke(ok, opis)
    }

    private fun zakoncPobieranie(misja: Misja?, opis: String) {
        dozorca?.cancel(); dozorca = null
        val zwrot = przyPobraniu
        pobierane = null
        przyPobraniu = null
        przyPostepie?.invoke(opis)
        zwrot?.invoke(misja, opis)
    }

    fun przerwij() {
        if (wysylane != null) zakonczWysylke(false, "przerwane")
        if (pobierane != null) zakoncPobieranie(null, "przerwane")
    }

    private companion object {
        /** Osiem sekund na krok. Łącze 115 200 dzielone z telemetrią bywa wolne. */
        const val CZAS_OCZEKIWANIA_MS = 8000L
    }
}
