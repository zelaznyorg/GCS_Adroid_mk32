package pl.dron15.cockpit.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Misja — model trasy i zapis do pliku `.plan`.
 *
 * Format pliku jest **taki sam jak w QGroundControl** (`dok/MISJE.md` §1): misja zaplanowana
 * tutaj otwiera się tam i odwrotnie. Żadnego własnego formatu — plik z karty ma być
 * czytelny dla narzędzia, które operator już zna.
 *
 * Współrzędne w pamięci są `Double`; do maszyny idą jako `int32` w `MISSION_ITEM_INT`
 * (`1e-7` stopnia), bo przestarzałe `MISSION_ITEM` niesie je jako `float` i gubi metry.
 */
data class PunktMisji(
    val komenda: Int,
    val szerokosc: Double,
    val dlugosc: Double,
    val wysokoscM: Float,
    val p1: Float = 0f,
    val p2: Float = 0f,
    val p3: Float = 0f,
    val p4: Float = 0f,
) {
    val nazwa: String get() = nazwaKomendy(komenda)

    /** Czy punkt ma sens na mapie. `RTL` i `DO_*` nie mają własnego położenia. */
    val naMapie: Boolean
        get() = komenda in setOf(NAV_WAYPOINT, NAV_SPLINE_WAYPOINT, NAV_TAKEOFF, NAV_LAND,
            NAV_LOITER_TIME, NAV_LOITER_TURNS, NAV_LOITER_UNLIM)

    companion object {
        const val NAV_WAYPOINT = 16
        const val NAV_SPLINE_WAYPOINT = 82
        const val NAV_TAKEOFF = 22
        const val NAV_LAND = 21
        const val NAV_RETURN_TO_LAUNCH = 20
        const val NAV_LOITER_UNLIM = 17
        const val NAV_LOITER_TURNS = 18
        const val NAV_LOITER_TIME = 19

        /** `MAV_FRAME_GLOBAL_RELATIVE_ALT_INT` — wysokość względem punktu startu. */
        const val RAMKA_WZGLEDNA = 6

        /** `MAV_FRAME_GLOBAL_INT` — używana wyłącznie dla pozycji domu w pozycji 0. */
        const val RAMKA_BEZWZGLEDNA = 0

        fun nazwaKomendy(k: Int): String = when (k) {
            NAV_WAYPOINT -> "WAYPOINT"
            NAV_SPLINE_WAYPOINT -> "SPLINE"
            NAV_TAKEOFF -> "START"
            NAV_LAND -> "LĄDOWANIE"
            NAV_RETURN_TO_LAUNCH -> "POWRÓT"
            NAV_LOITER_UNLIM -> "ZAWIS"
            NAV_LOITER_TURNS -> "OKRĄŻENIA"
            NAV_LOITER_TIME -> "ZAWIS CZASOWY"
            else -> "CMD $k"
        }
    }
}

data class Misja(
    val punkty: List<PunktMisji> = emptyList(),
    /** Skąd pochodzi ta trasa — do nagłówka panelu. */
    val zrodlo: String = "nowa",
) {
    val pusta: Boolean get() = punkty.isEmpty()

    /** Punkty mające położenie — te, które da się narysować i zmierzyć. */
    val naMapie: List<PunktMisji> get() = punkty.filter { it.naMapie }

    /** Długość trasy w metrach, licząc kolejne odcinki między punktami na mapie. */
    val dlugoscM: Float
        get() {
            val p = naMapie
            if (p.size < 2) return 0f
            var suma = 0.0
            for (i in 1 until p.size) {
                val dLat = (p[i].szerokosc - p[i - 1].szerokosc) * METRY_NA_STOPIEN
                val dLon = (p[i].dlugosc - p[i - 1].dlugosc) * METRY_NA_STOPIEN *
                        kotlin.math.cos(Math.toRadians(p[i - 1].szerokosc))
                suma += sqrt(dLat * dLat + dLon * dLon)
            }
            return suma.toFloat()
        }

    val podsumowanie: String
        get() = "${naMapie.size} pkt · ${"%.0f".format(dlugoscM)} m"

    /**
     * Dokłada `WAYPOINT` **na końcu trasy, ale przed `RETURN_TO_LAUNCH`** — powrót ma zostać
     * ostatni, inaczej dołożony punkt byłby wykonywany po wylądowaniu.
     */
    fun zDolozonym(punkt: PunktMisji): Misja {
        val i = punkty.indexOfFirst { it.komenda == PunktMisji.NAV_RETURN_TO_LAUNCH }
        val nowe = punkty.toMutableList()
        if (i >= 0) nowe.add(i, punkt) else nowe.add(punkt)
        return copy(punkty = nowe)
    }

    fun bez(indeks: Int): Misja =
        if (indeks !in punkty.indices) this
        else copy(punkty = punkty.toMutableList().also { it.removeAt(indeks) })

    fun zeZmienionaWysokoscia(indeks: Int, oM: Float): Misja {
        if (indeks !in punkty.indices) return this
        val nowe = punkty.toMutableList()
        val p = nowe[indeks]
        nowe[indeks] = p.copy(wysokoscM = (p.wysokoscM + oM).coerceIn(WYS_MIN, WYS_MAKS))
        return copy(punkty = nowe)
    }

    /** Domyka trasę powrotem, jeśli go jeszcze nie ma. */
    fun zPowrotem(): Misja =
        if (punkty.any { it.komenda == PunktMisji.NAV_RETURN_TO_LAUNCH }) this
        else copy(punkty = punkty + PunktMisji(PunktMisji.NAV_RETURN_TO_LAUNCH, 0.0, 0.0, 0f))

    // ------------------------------------------------------------------ plik .plan

    /**
     * Zapis w formacie QGC `.plan`. `plannedHomePosition` bierze pozycję domu, bo bez niej
     * QGC nie potrafi narysować pierwszego odcinka.
     */
    fun doPlanJson(domSzerokosc: Double, domDlugosc: Double, domWysokosc: Double = 0.0): String {
        val pozycje = JSONArray()
        punkty.forEachIndexed { i, p ->
            pozycje.put(JSONObject().apply {
                put("AMSLAltAboveTerrain", JSONObject.NULL)
                put("Altitude", p.wysokoscM.toDouble())
                put("AltitudeMode", 1)               // wysokość względna
                put("autoContinue", true)
                put("command", p.komenda)
                put("doJumpId", i + 1)
                put("frame", PunktMisji.RAMKA_WZGLEDNA)
                put("params", JSONArray(listOf(
                    p.p1.toDouble(), p.p2.toDouble(), p.p3.toDouble(), p.p4.toDouble(),
                    p.szerokosc, p.dlugosc, p.wysokoscM.toDouble(),
                )))
                put("type", "SimpleItem")
            })
        }

        val misja = JSONObject().apply {
            put("cruiseSpeed", 6.0)                  // WPNAV_SPEED tej maszyny
            put("firmwareType", 3)                   // MAV_AUTOPILOT_ARDUPILOTMEGA
            put("globalPlanAltitudeMode", 1)
            put("hoverSpeed", 5.0)
            put("items", pozycje)
            put("plannedHomePosition", JSONArray(listOf(domSzerokosc, domDlugosc, domWysokosc)))
            put("vehicleType", 2)                    // MAV_TYPE_QUADROTOR
            put("version", 2)
        }

        return JSONObject().apply {
            put("fileType", "Plan")
            put("geoFence", JSONObject().apply {
                put("circles", JSONArray()); put("polygons", JSONArray()); put("version", 2)
            })
            put("groundStation", "DRON15 kokpit")
            put("mission", misja)
            put("rallyPoints", JSONObject().apply {
                put("points", JSONArray()); put("version", 2)
            })
            put("version", 1)
        }.toString(2)
    }

    companion object {
        const val WYS_MIN = 2f
        const val WYS_MAKS = 120f          // FENCE_ALT_MAX tej maszyny
        const val WYS_DOMYSLNA = 30f
        private const val METRY_NA_STOPIEN = 111_320.0

        /** Odczyt `.plan`. Zwraca `null`, gdy plik nie jest planem — cisza byłaby gorsza. */
        fun zPlanJson(tekst: String): Misja? = try {
            val root = JSONObject(tekst)
            if (root.optString("fileType") != "Plan") null
            else {
                val pozycje = root.getJSONObject("mission").getJSONArray("items")
                val punkty = ArrayList<PunktMisji>(pozycje.length())
                for (i in 0 until pozycje.length()) {
                    val o = pozycje.getJSONObject(i)
                    if (o.optString("type") != "SimpleItem") continue
                    val par = o.getJSONArray("params")
                    punkty.add(
                        PunktMisji(
                            komenda = o.getInt("command"),
                            p1 = par.optDouble(0, 0.0).toFloat(),
                            p2 = par.optDouble(1, 0.0).toFloat(),
                            p3 = par.optDouble(2, 0.0).toFloat(),
                            p4 = par.optDouble(3, 0.0).toFloat(),
                            szerokosc = par.optDouble(4, 0.0),
                            dlugosc = par.optDouble(5, 0.0),
                            wysokoscM = par.optDouble(6, 0.0).toFloat(),
                        )
                    )
                }
                Misja(punkty, zrodlo = "z pliku")
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Który tryb pracy panelu misji — §5 przekazania. */
enum class TrybMisji(val etykieta: String, val opis: String) {
    PLANUJ("PLANUJ", "nowa trasa od zera"),
    LEC("LEĆ", "podgląd wykonywanej misji"),
    EDYTUJ("EDYTUJ", "zapisany plik z karty"),
}
