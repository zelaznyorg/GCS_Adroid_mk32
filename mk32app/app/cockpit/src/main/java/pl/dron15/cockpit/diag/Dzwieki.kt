package pl.dron15.cockpit.diag

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Sygnalizacja dźwiękowa alarmów.
 *
 * ### Po co
 *
 * Audyt interfejsu z 2026-08-19 zgłosił brak dźwięku jako pozycję F10:
 * *„baner na ekranie, na który pilot nie patrzy, nie istnieje"*. Do 2026-08-26 w kodzie
 * nie było ani `ToneGenerator`, ani `SoundPool` — jedynym sprzężeniem zwrotnym była
 * wibracja przy naciśnięciu klawisza.
 *
 * Pilot patrzy na maszynę, nie na ekran. To jest kanał, który działa wtedy, gdy wzrok
 * jest zajęty czymś ważniejszym.
 *
 * ### Zasady
 *
 * - **Każdy alarm ma inny dźwięk** — rozpoznawalny bez patrzenia.
 * - **Nic się nie powtarza w kółko bez powodu.** Alarmy jednorazowe (JOKER, BINGO,
 *   potwierdzenie komendy) grają raz na zdarzenie; ciągłe (zapas ciągu, utrata łącza)
 *   powtarzają się z odstępem, który skraca się wraz z pogorszeniem sytuacji.
 * - **Awaria dźwięku nie może zabrać telemetrii.** `ToneGenerator` potrafi rzucić
 *   `RuntimeException`, gdy sprzęt audio jest zajęty — a na MK32 obok chodzi SIYI FPV.
 *   Dlatego wszystko jest w `runCatching`, a brak dźwięku jest odnotowany raz i pomijany.
 *
 * ⚠ **Niesprawdzone na sprzęcie:** czy MK32 pozwala aplikacji grać przy jednocześnie
 * działającym SIYI FPV. Do potwierdzenia w polu — dok/PROPOZYCJA_LOT.md §11 pkt 6.
 */
class Dzwieki {

    private var generator: ToneGenerator? = null
    private var zepsuty = false

    /** Ostatnie zagranie każdego rodzaju — do wygaszania powtórzeń. */
    private val ostatnie = HashMap<Rodzaj, Long>()

    enum class Rodzaj {
        /** Zapas ciągu poniżej progu — tempo rośnie z pogorszeniem. */
        ZAPAS_CIAGU,

        /** Czas ruszać do domu. */
        JOKER,

        /** Powrót przestaje być możliwy. */
        BINGO,

        /** Utrata telemetrii albo kursu GNSS. */
        UTRATA_LACZA,

        /** Komenda przyjęta przez maszynę. */
        KOMENDA_OK,

        /** Komenda odrzucona. */
        KOMENDA_ODRZUCONA,
    }

    private fun generator(): ToneGenerator? {
        if (zepsuty) return null
        generator?.let { return it }
        return runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, GLOSNOSC).also { generator = it }
        }.onFailure {
            zepsuty = true
            Dziennik.ostrzezenie("dzwiek", "nie udało się otworzyć wyjścia audio", it)
        }.getOrNull()
    }

    /**
     * Zagraj, o ile od poprzedniego razu minęło dość czasu.
     *
     * @param pilnosc 0..1 dla alarmów ciągłych — skraca odstęp między powtórzeniami.
     */
    fun zagraj(rodzaj: Rodzaj, teraz: Long, pilnosc: Float = 0f) {
        val odstep = odstepMs(rodzaj, pilnosc)
        val poprzednie = ostatnie[rodzaj] ?: 0L
        if (teraz - poprzednie < odstep) return
        ostatnie[rodzaj] = teraz

        val g = generator() ?: return
        val (ton, czas) = when (rodzaj) {
            Rodzaj.ZAPAS_CIAGU -> ToneGenerator.TONE_CDMA_HIGH_L to 120
            Rodzaj.JOKER -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 400
            Rodzaj.BINGO -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK to 800
            Rodzaj.UTRATA_LACZA -> ToneGenerator.TONE_SUP_ERROR to 600
            Rodzaj.KOMENDA_OK -> ToneGenerator.TONE_PROP_ACK to 90
            Rodzaj.KOMENDA_ODRZUCONA -> ToneGenerator.TONE_PROP_NACK to 200
        }
        runCatching { g.startTone(ton, czas) }.onFailure {
            zepsuty = true
            Dziennik.ostrzezenie("dzwiek", "wyjście audio przestało odpowiadać", it)
        }
    }

    /** Zdarzenie jednorazowe: zagraj tylko przy przejściu ze stanu spokojnego. */
    fun zapomnij(rodzaj: Rodzaj) {
        ostatnie.remove(rodzaj)
    }

    fun zwolnij() {
        runCatching { generator?.release() }
        generator = null
    }

    private fun odstepMs(rodzaj: Rodzaj, pilnosc: Float): Long = when (rodzaj) {
        // Od 2 s przy pierwszym przekroczeniu progu do 250 ms przy zapasie na zerze.
        Rodzaj.ZAPAS_CIAGU -> (2000L - (1750L * pilnosc.coerceIn(0f, 1f))).toLong()
        Rodzaj.UTRATA_LACZA -> 5000L
        Rodzaj.JOKER, Rodzaj.BINGO -> 60_000L
        Rodzaj.KOMENDA_OK, Rodzaj.KOMENDA_ODRZUCONA -> 300L
    }

    private companion object {
        /** 0..100. Głośność systemową i tak ustawia pilot na aparaturze. */
        const val GLOSNOSC = 90
    }
}
