package pl.dron15.cockpit.domain

import pl.dron15.cockpit.diag.Dziennik
import java.io.File

/**
 * Pliki misji na karcie — katalog `/sdcard/dron15/misje`, pliki `.plan`.
 *
 * Ten sam katalog co kafelki mapy, żeby operator miał jedno miejsce do wgrywania rzeczy
 * przed wyjazdem. Format `.plan` jest formatem QGroundControl (dok/MISJE.md §1), więc plik
 * przygotowany na komputerze otwiera się tutaj i odwrotnie.
 *
 * **Awaria zapisu nie może zabrać planowania** — trasa żyje w pamięci aplikacji niezależnie
 * od tego, czy karta jest, czy jej nie ma. Dlatego wszystko zwraca komunikat, a nie wyjątek.
 */
object MagazynMisji {

    val katalog: File get() = File("/sdcard/dron15/misje")

    fun lista(): List<File> = try {
        katalog.listFiles { f -> f.isFile && f.name.endsWith(".plan", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    } catch (e: Exception) {
        Dziennik.ostrzezenie("misja", "nie udało się wylistować katalogu misji", e)
        emptyList()
    }

    /** Zwraca komunikat do pokazania operatorowi — sukces albo powód niepowodzenia. */
    fun zapisz(misja: Misja, nazwa: String, domSzerokosc: Double, domDlugosc: Double): String = try {
        if (!katalog.exists() && !katalog.mkdirs()) {
            "nie ma dostępu do ${katalog.path}"
        } else {
            val plik = File(katalog, if (nazwa.endsWith(".plan")) nazwa else "$nazwa.plan")
            plik.writeText(misja.doPlanJson(domSzerokosc, domDlugosc))
            "zapisano ${plik.name}"
        }
    } catch (e: Exception) {
        Dziennik.ostrzezenie("misja", "zapis misji nie powiódł się", e)
        "zapis nieudany: ${e.message}"
    }

    fun wczytaj(plik: File): Misja? = try {
        Misja.zPlanJson(plik.readText())?.copy(zrodlo = plik.name)
    } catch (e: Exception) {
        Dziennik.ostrzezenie("misja", "odczyt ${plik.name} nie powiódł się", e)
        null
    }

    /** Nazwa dla nowego zapisu: `trasa_HHMM.plan`. Zegar systemowy wystarcza. */
    fun proponowanaNazwa(teraz: Long): String {
        val kal = java.util.Calendar.getInstance().apply { timeInMillis = teraz }
        return "trasa_%02d%02d".format(
            kal.get(java.util.Calendar.HOUR_OF_DAY),
            kal.get(java.util.Calendar.MINUTE),
        )
    }
}
