package pl.dron15.zrzut

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/**
 * Druga droga obrazu na stację: **natywna transmisja RTMP z DJI Pilot 2**.
 *
 * ### Po co druga droga, skoro pierwsza działa
 *
 * Zrzut ekranu i natywny RTMP nie są zamiennikami — każdy ma to, czego nie ma drugi:
 *
 * | | zrzut ekranu (ta aplikacja) | natywny RTMP z Pilota 2 |
 * |---|---|---|
 * | co widać | obraz **z nakładką OSD** — wysokość, bateria, tryb | **czysty obraz** z kamery |
 * | ostrość | przez ekran aparatury, więc gorsza | prosto ze strumienia drona |
 * | ⛔ ryzyko | `FLAG_SECURE` DJI może dać czarny prostokąt | brak |
 *
 * Dla Mavic 3 Pro zrzut jest jedyną drogą do **jakiejkolwiek** telemetrii — bo tam
 * liczby istnieją wyłącznie jako piksele w nakładce. Ale jeśli DJI zrzut zablokuje,
 * natywny RTMP zostaje jedynym sposobem, żeby cokolwiek zobaczyć na stacji.
 *
 * ### Dlaczego to siedzi w tej aplikacji, a nie w instrukcji na kartce
 *
 * Adres RTMP jest długi, niesie hasło i jednego literówkowego znaku wystarczy, żeby
 * nadawanie milczało bez powodu. Operator wpisał już adres stacji i hasło **w dwa
 * pola powyżej** — to komplet danych, żeby ten adres złożyć za niego. Zadaniem
 * człowieka zostaje wkleić, nie przepisać.
 *
 * ⚠ **Adres trafia do schowka razem z hasłem urządzenia.** To hasło samego nadawania
 * (`/var/lib/dron15/nadawanie.txt`), nie konta — ale zostaje w schowku aparatury,
 * dopóki nie skopiuje się czegoś innego.
 */
object DrogaPilota {

    /**
     * Ścieżka MediaMTX dla natywnego RTMP.
     *
     * ⛔ **Celowo inna niż ścieżka zrzutu (`dji`).** Stacja dopuszcza obie
     * (`SCIEZKI_NADAWANIA` w `server/nadawanie.mjs`), więc oba obrazy mogą iść
     * równocześnie i pokazać się jako dwa osobne źródła. Gdyby dzieliły ścieżkę,
     * drugi nadawca zostałby odrzucony.
     */
    const val SCIEZKA = "dji2"

    /** Port RTMP MediaMTX. Stały — nie ma po co pytać o niego operatora. */
    const val PORT_RTMP = 1935

    /** Login nadawcy po stronie stacji; hasło jest wspólne dla obu dróg. */
    private const val UZYTKOWNIK = "dji"

    /**
     * Nazwy pakietów, pod którymi bywa aplikacja DJI. Próbujemy po kolei.
     *
     * ⚠ Lista jest **niepewna** — zależy od wersji i modelu aparatury. Dlatego
     * nieznalezienie żadnej z nich nie jest błędem aplikacji, tylko powodem, żeby
     * powiedzieć operatorowi „otwórz Pilota ręcznie, adres masz w schowku".
     */
    val PAKIETY = listOf(
        "dji.v5.pilot",   // DJI Pilot 2
        "dji.pilot",      // DJI Pilot (starszy)
        "dji.go.v5",      // DJI Fly
        "dji.go.v4",      // DJI GO 4
    )

    /** Składa gotowy adres nadawania z tego, co operator wpisał w dwa pola. */
    fun adres(host: String, haslo: String): String =
        "rtmp://$host:$PORT_RTMP/$SCIEZKA?user=$UZYTKOWNIK&pass=$haslo"

    /** Sam host, bez portu — pole na ekranie trzyma `host:port` toru zrzutu. */
    fun host(zPola: String): String =
        zPola.trim().substringBefore(":").ifBlank { "192.168.88.30" }

    fun skopiuj(k: Context, tekst: String): Boolean = try {
        val schowek = k.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        schowek.setPrimaryClip(ClipData.newPlainText("adres nadawania DRON 15", tekst))
        true
    } catch (e: Exception) {
        Log.w(TAG, "schowek odmówił: ${e.message}")
        false
    }

    /**
     * Otwiera aplikację DJI, jeśli da się ją znaleźć.
     *
     * ⚠ Na Androidzie 11+ system **ukrywa cudze pakiety**, dopóki nie wymieni się
     * ich w `<queries>` w manifeście — bez tego `getLaunchIntentForPackage` zwraca
     * `null` nawet dla zainstalowanej aplikacji.
     *
     * @return nazwa otwartego pakietu albo `null`, gdy żadnego nie ma
     */
    fun otworzPilota(k: Context): String? {
        for (p in PAKIETY) {
            val i = try { k.packageManager.getLaunchIntentForPackage(p) } catch (_: Exception) { null }
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                return try { k.startActivity(i); p } catch (e: Exception) {
                    Log.w(TAG, "$p nie chciał się otworzyć: ${e.message}"); null
                }
            }
        }
        return null
    }

    private const val TAG = "zrzut.pilot"
}
