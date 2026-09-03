package pl.dron15.zrzut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Kafelek w szybkich ustawieniach — najszybsza droga do wstrzymania obrazu w locie.
 *
 * ### Dlaczego akurat kafelek
 *
 * Pilot patrzy na DJI Pilot 2 i trzyma drążki. Powrót do naszej aplikacji to
 * przełączanie programów w trakcie lotu — i tego nie zrobi. Kafelek jest
 * **jednym przeciągnięciem paska i jednym dotknięciem, z dowolnej aplikacji**,
 * bez opuszczania widoku lotu na dłużej niż sekundę.
 *
 * Powiadomienie robi to samo i jest równie blisko; kafelek dokładamy, bo trafia się
 * w niego bez czytania — sam kolor mówi, czy obraz idzie.
 *
 * ⛔ Kafelek **nie potrafi wziąć zgody na przechwytywanie ekranu** — systemowego
 * okienka nie da się pokazać z szybkich ustawień. Dlatego pierwsze uruchomienie
 * zawsze idzie przez ekran aplikacji, a kafelek tylko przełącza wysyłanie
 * (patrz `UslugaZrzutu`: pauza trzyma zgodę). Gdy zgody nie ma, kafelek otwiera
 * aplikację, zamiast udawać, że coś zrobił.
 */
class KafelekZrzutu : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        odmaluj()
    }

    override fun onClick() {
        super.onClick()
        if (!Stan.gotowy) {
            // Nie ma zgody — jedyne sensowne to zaprowadzić operatora tam, gdzie da się
            // ją wziąć. Udawanie, że kafelek coś włączył, byłoby gorsze niż nic.
            val i = Intent(this, GlownaAktywnosc::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this, 0, i,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        val i = Intent(this, UslugaZrzutu::class.java).setAction(UslugaZrzutu.AKCJA_PRZELACZ)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        odmaluj()
    }

    private fun odmaluj() {
        val kafelek: Tile = qsTile ?: return
        kafelek.state = when {
            !Stan.gotowy -> Tile.STATE_UNAVAILABLE
            Stan.nadaje -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        kafelek.label = "Zrzut ekranu"
        // Podpis mówi rzecz najważniejszą: czy obraz IDZIE. Reszta jest drugorzędna.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            kafelek.subtitle = when {
                !Stan.gotowy -> "nieuruchomione"
                // Kafelek zostaje zapalony (operator WŁĄCZYŁ nadawanie), ale podpis
                // nie udaje, że obraz dociera, kiedy łącze leży.
                Stan.nadaje && !Stan.plynie -> "łączy się…"
                Stan.nadaje && Stan.czern -> "obraz pusty!"
                Stan.nadaje -> "${Stan.kbs} kb/s"
                else -> "wstrzymane"
            }
        }
        kafelek.icon = Icon.createWithResource(
            this,
            if (Stan.nadaje) android.R.drawable.presence_video_online
            else android.R.drawable.presence_video_away,
        )
        kafelek.updateTile()
    }

    companion object {
        /** Prosi system o odświeżenie kafelka — wołane, gdy zmienia się [Stan]. */
        fun odswiez(kontekst: Context) {
            try {
                requestListeningState(kontekst, ComponentName(kontekst, KafelekZrzutu::class.java))
            } catch (_: Exception) {
                // Na niektórych aparaturach szybkich ustawień może nie być wcale —
                // wtedy zostaje powiadomienie i ekran aplikacji.
            }
        }
    }
}
