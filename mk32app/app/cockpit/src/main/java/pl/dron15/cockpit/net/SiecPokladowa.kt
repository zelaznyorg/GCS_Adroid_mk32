package pl.dron15.cockpit.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import pl.dron15.cockpit.diag.Dziennik
import java.net.DatagramSocket
import java.net.Socket

/**
 * Przypina gniazda do **sieci pokładowej drona**, zamiast zdawać się na sieć domyślną.
 *
 * ### Dlaczego to jest konieczne
 *
 * Zmierzone na aparaturze 2026-08-28. Gdy MK32 złapie Wi-Fi, Android robi je siecią
 * domyślną — bo ma internet — i **blokuje ruch aplikacji do sieci pokładowej**, mimo że
 * trasa istnieje i `ping` z powłoki przechodzi:
 *
 * ```
 * java.io.IOException: sendto failed: EPERM (Operation not permitted)
 * Active default network: 106            (Wi-Fi)
 * ip route get 192.168.144.25  ->  dev eth0 src 192.168.144.20     (trasa jest)
 * ```
 *
 * Skutek jest paskudny i cichy: **kokpit traci telemetrię, głowicę i obraz naraz**,
 * w chwili gdy operator wjedzie w zasięg domowego Wi-Fi. Bez tego wiązania aparatura
 * musiałaby mieć Wi-Fi wyłączone — a wtedy mapa nie dociągnie kafelków.
 *
 * To jest dokładne odbicie [pl.dron15.cockpit.ui.SiecDoInternetu]: tam wybieramy sieć,
 * która **naprawdę** ma internet, tu tę, która **naprawdę** prowadzi do maszyny. W obu
 * przypadkach o wyborze decyduje rodzaj łącza, nie „domyślność".
 *
 * Gdy sieci pokładowej nie ma, wiązanie jest pomijane — gniazdo działa jak dotąd,
 * a przyczynę i tak zamelduje pierwsza nieudana wysyłka.
 */
object SiecPokladowa {

    @Volatile
    private var polaczenia: ConnectivityManager? = null

    fun zapamietaj(kontekst: Context) {
        polaczenia = kontekst.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    fun zwiaz(gniazdo: DatagramSocket) {
        val siec = ethernet() ?: return
        try {
            siec.bindSocket(gniazdo)
        } catch (e: Exception) {
            Dziennik.ostrzezenie("siec", "nie udało się przypiąć gniazda UDP do eth0", e)
        }
    }

    fun zwiaz(gniazdo: Socket) {
        val siec = ethernet() ?: return
        try {
            siec.bindSocket(gniazdo)
        } catch (e: Exception) {
            Dziennik.ostrzezenie("siec", "nie udało się przypiąć gniazda TCP do eth0", e)
        }
    }

    /** Sieć po Ethernecie — w tej maszynie to z definicji sieć pokładowa drona. */
    private fun ethernet(): Network? {
        val cm = polaczenia ?: return null
        for (siec in cm.allNetworks) {
            val m = cm.getNetworkCapabilities(siec) ?: continue
            if (m.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return siec
        }
        return null
    }
}
