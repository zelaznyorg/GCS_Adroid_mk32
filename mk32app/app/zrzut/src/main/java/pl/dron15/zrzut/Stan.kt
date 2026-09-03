package pl.dron15.zrzut

/**
 * Stan nadawania, widoczny dla wszystkich trzech dróg obsługi: ekranu aplikacji,
 * kafelka w szybkich ustawieniach i powiadomienia.
 *
 * ### Dlaczego to jest osobny, wspólny obiekt
 *
 * Pilot w locie ma sięgnąć po start i stop **z dowolnego miejsca**, nie wracając do
 * naszej aplikacji. Skoro sterowanie jest w trzech miejscach, stan musi być w jednym
 * — inaczej kafelek pokazywałby co innego niż powiadomienie.
 */
object Stan {
    /** Zgoda na przechwytywanie wzięta — usługa żyje i da się wznowić bez pytania. */
    @Volatile var gotowy: Boolean = false

    /** Operator włączył nadawanie — usługa próbuje wysyłać. */
    @Volatile var nadaje: Boolean = false

    /**
     * Łącze do stacji stoi i klatki naprawdę przez nie idą.
     *
     * ⛔ To NIE to samo, co [nadaje], i różnica jest tu istotna. Gdy sieć padnie,
     * usługa dalej chce nadawać (`nadaje = true`) i co kilka sekund ponawia próbę —
     * ale obraz **nie dociera**. Bez tego rozróżnienia karta stanu świeciłaby wtedy
     * na zielono, czyli kłamała: w tej aplikacji zielone znaczy „obraz idzie".
     */
    @Volatile var plynie: Boolean = false

    /** Krótki opis dla człowieka: co się dzieje i dlaczego. */
    @Volatile var opis: String = "gotowe"

    /** Ile kilobitów na sekundę idzie w tej chwili — 0, gdy pauza. */
    @Volatile var kbs: Int = 0

    /** Sekundy nadawania od ostatniego wznowienia. */
    @Volatile var sekund: Long = 0

    /** Ile razy łącze zostało odtworzone samo. Rośnie — znaczy, że sieć kuleje. */
    @Volatile var ponowien: Int = 0

    /**
     * Podejrzenie, że wysyłamy czarny prostokąt zamiast obrazu.
     *
     * ⛔ To najgroźniejszy cichy przypadek tej aplikacji: gdy DJI oznacza podgląd
     * jako `FLAG_SECURE`, przechwytywanie **działa** — koder pracuje, gniazdo żyje,
     * stacja przyjmuje strumień — tyle że w obrazie nie ma nic. Wszystkie zwykłe
     * wskaźniki mówiłyby wtedy „w porządku".
     *
     * Rozpoznajemy to po przepływności: czarny ekran koduje się do znikomych klatek
     * (patrz [UslugaZrzutu]). To **podejrzenie, nie dowód** — nieruchomy, ciemny
     * ekran daje podobny wynik. Dlatego skutkiem jest podpowiedź, nie przełączenie
     * czegokolwiek za operatora.
     */
    @Volatile var czern: Boolean = false

    fun czysty() {
        gotowy = false; nadaje = false; plynie = false
        kbs = 0; sekund = 0; ponowien = 0; czern = false
        opis = "gotowe"
    }
}
