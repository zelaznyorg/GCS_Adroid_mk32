package pl.dron15.cockpit.video

import android.content.Context
import android.view.View

/**
 * Wspólny kształt obu torów obrazu — **RTSP przez libVLC** ([OdtwarzaczVlc]) i **własny
 * protokół SIYI po TCP 37256** ([OdtwarzaczSiyi]).
 *
 * Istnieje po to, żeby ekrany nie wiedziały, którym torem przyszedł kadr. Wybór zapada
 * raz, przy starcie, w `MainActivity` — a przełącznik w panelu STRUMIEŃ pozwala wrócić
 * na RTSP bez przebudowy aplikacji, gdyby nowy tor okazał się gorszy w polu.
 *
 * Reguła wspólna dla obu i najważniejsza w całym module: **widok tworzy się raz i nie
 * wolno go wymieniać**. Wymiana kosztowała nas 2026-08-26 czarny kadr po zmianie zakładki
 * i zawieszenie aplikacji — powody opisane szczegółowo przy [OdtwarzaczVlc.widok].
 */
interface TorWideo {

    /** Wywoływane, gdy obraz zaczyna i przestaje płynąć — kokpit robi z tego baner. */
    var przyStanie: ((Boolean) -> Unit)?

    /** **Jedyny** widok tego toru; kolejne wywołania zwracają ten sam, zdjęty z rodzica. */
    fun widok(kontekst: Context): View

    /** Włącza odtwarzanie, jeśli nie działa. Wołane przy wejściu na ekran z obrazem. */
    fun zapewnijOdtwarzanie()

    fun zwolnij()
}
