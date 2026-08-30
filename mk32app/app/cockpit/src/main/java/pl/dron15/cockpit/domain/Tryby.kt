package pl.dron15.cockpit.domain

/**
 * Numery trybów ArduCopter — kolejność z `Copter::Mode::Number` @ 4.6.3.
 *
 * Wydzielone z SilnikStanu, bo tej tablicy potrzebuje też panel RC: `FLTMODE1..6` trzymają
 * numery trybów, a pilot ma zobaczyć słowo, nie liczbę.
 */
object Tryby {

    val NAZWY = mapOf(
        0 to "STABILIZE", 1 to "ACRO", 2 to "ALTHOLD", 3 to "AUTO", 4 to "GUIDED",
        5 to "LOITER", 6 to "RTL", 7 to "CIRCLE", 9 to "LAND", 11 to "DRIFT",
        13 to "SPORT", 14 to "FLIP", 15 to "AUTOTUNE", 16 to "POSHOLD", 17 to "BRAKE",
        18 to "THROW", 20 to "GUIDED_NOGPS", 21 to "SMART_RTL", 22 to "FLOWHOLD",
        23 to "FOLLOW", 24 to "ZIGZAG", 27 to "AUTO_RTL",
    )

    const val ALTHOLD = 2
    const val AUTO = 3
    const val LOITER = 5
    const val RTL = 6
    const val LAND = 9
    const val BRAKE = 17

    fun nazwa(nr: Int): String = NAZWY[nr] ?: "TRYB $nr"

    /** Tryby, które prowadzą maszynę same. Z nich wychodzi się „przerwaniem automatu". */
    fun automatyczny(nazwa: String): Boolean =
        nazwa == "AUTO" || nazwa == "RTL" || nazwa == "SMART_RTL" ||
                nazwa == "AUTO_RTL" || nazwa == "LAND" || nazwa == "GUIDED"

    /** Tryby wymagające estymaty pozycji — na tej maszynie zależnej od kursu z GNSS. */
    fun wymagaPozycji(nazwa: String): Boolean =
        nazwa != "STABILIZE" && nazwa != "ACRO" && nazwa != "ALTHOLD" && nazwa != "DRIFT"
}
