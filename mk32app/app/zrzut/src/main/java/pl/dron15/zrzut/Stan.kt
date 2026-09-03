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

    /** Obraz naprawdę leci na stację. */
    @Volatile var nadaje: Boolean = false

    /** Krótki opis dla człowieka: co się dzieje i dlaczego. */
    @Volatile var opis: String = "gotowe"

    /** Ile kilobitów na sekundę idzie w tej chwili — 0, gdy pauza. */
    @Volatile var kbs: Int = 0

    /** Sekundy nadawania od ostatniego wznowienia. */
    @Volatile var sekund: Long = 0

    /** Ile razy łącze zostało odtworzone samo. Rośnie — znaczy, że sieć kuleje. */
    @Volatile var ponowien: Int = 0

    fun czysty() {
        gotowy = false; nadaje = false; kbs = 0; sekund = 0; ponowien = 0
        opis = "gotowe"
    }
}
