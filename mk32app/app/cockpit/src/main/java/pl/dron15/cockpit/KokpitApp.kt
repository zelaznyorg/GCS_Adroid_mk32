package pl.dron15.cockpit

import android.app.Application
import pl.dron15.cockpit.diag.Dziennik

/**
 * Wstaje przed jakąkolwiek aktywnością — i o to chodzi.
 *
 * Awaria potrafi zdarzyć się przy starcie, zanim pojawi się pierwszy ekran. Gdyby pułapka
 * na wyjątki siedziała w [MainActivity], taka awaria nie zostawiłaby żadnego śladu i po
 * powrocie z pola nie byłoby czego czytać.
 *
 * Rejestracja: `android:name=".KokpitApp"` w AndroidManifest.xml.
 */
class KokpitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Dziennik.start(this, zeSzczegolami = BuildConfig.DEBUG)

        // Kafelki mapy mają iść siecią, która NAPRAWDĘ ma internet — sieć pokładowa drona
        // ogłasza `INTERNET & VALIDATED`, a prowadzi wyłącznie do maszyny. Szczegóły
        // i pomiar: pl.dron15.cockpit.ui.SiecDoInternetu.
        pl.dron15.cockpit.ui.SiecDoInternetu.zapamietaj(this)

        // Odwrotnie niż wyżej: gniazda do maszyny mają iść siecią pokładową, nawet gdy
        // Android uzna Wi-Fi za domyślne. Patrz pl.dron15.cockpit.net.SiecPokladowa.
        pl.dron15.cockpit.net.SiecPokladowa.zapamietaj(this)

        // Kluczowe: NIE połykamy wyjątku. Zapisujemy i oddajemy sterowanie systemowi,
        // żeby proces zakończył się normalnie. Aplikacja, która po nieprzechwyconym
        // wyjątku „działa dalej", pokazuje pilotowi dane, którym nie wolno wierzyć —
        // a przy locie to gorsze niż zamknięcie okna.
        val poprzedni = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { watek, e ->
            try {
                Dziennik.awaria(watek, e)
            } catch (_: Throwable) {
                // Nawet zapis się nie udał — nie ma już nic do zrobienia.
            }
            poprzedni?.uncaughtException(watek, e)
        }

        Dziennik.info(
            "start",
            "DRON15 Cockpit ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE}, " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )
    }
}
