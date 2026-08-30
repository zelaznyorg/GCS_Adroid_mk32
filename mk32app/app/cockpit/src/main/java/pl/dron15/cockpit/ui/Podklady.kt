package pl.dron15.cockpit.ui

/**
 * Podkłady mapy — **co leży pod śladem, trasą i siatką**.
 *
 * Do 2026-08-25 kokpit miał jeden, bezimienny podkład: cokolwiek leżało w
 * `/sdcard/dron15/kafelki/{z}/{x}/{y}.png`. Operator nie mógł go zmienić ani sprawdzić,
 * co właściwie ogląda. Teraz podkład jest **wyborem**, a każdy wybór to zestaw katalogów
 * kafelków na karcie.
 *
 * ### Dlaczego hybryda jest obowiązkowa
 *
 * Samo zdjęcie lotnicze nie mówi, jak nazywa się droga, przy której stoi operator, ani gdzie
 * kończy się wieś. Sama mapa kreskowa nie pokazuje, czy pole jest zaorane, czy stoi na nim
 * las. Do lotu potrzebne są oba naraz — dlatego **hybryda (zdjęcie + nazwy + drogi) jest
 * podkładem domyślnym i jedynym oznaczonym jako wymagany**; przy jej braku kokpit mówi
 * o tym wprost, zamiast po cichu pokazać samą siatkę.
 *
 * ### Układ katalogów na karcie
 *
 * ```
 * /sdcard/dron15/kafelki/{warstwa}/{z}/{x}/{y}.png
 * /sdcard/dron15/teren/{z}/{x}/{y}.png          — dane wysokościowe (Terrarium)
 * ```
 *
 * Stary układ **bez** nazwy warstwy (`kafelki/{z}/{x}/{y}.png`) nadal działa — traktujemy go
 * jako warstwę `zdjecia`, żeby karta przygotowana przed tą zmianą nie zgasła w polu.
 */
data class Podklad(
    val id: String,
    /** etykieta na chipie — krótka, wielkimi literami */
    val nazwa: String,
    /** jedno zdanie do panelu warstw: po co ten podkład operatorowi */
    val opis: String,
    /** katalog kafelków rysowanych jako pierwszy */
    val baza: String,
    /** katalogi rysowane na wierzchu (przezroczyste PNG-i: nazwy, drogi) */
    val nakladki: List<String> = emptyList(),
    /**
     * Ile przyciemnić podkład pod interfejsem lotu. Zdjęcie lotnicze w słońcu zabija kontrast
     * śladu i cyfr; mapa kreskowa jest już jasna i przyciemnienia prawie nie potrzebuje.
     */
    val przyciemnienie: Float = 0.35f,
    /** Podkład, bez którego nie startujemy — patrz nagłówek. */
    val wymagany: Boolean = false,
) {
    /** Wszystkie katalogi tego podkładu, od spodu do wierzchu. */
    val katalogi: List<String> get() = listOf(baza) + nakladki
}

object Podklady {

    val HYBRYDA = Podklad(
        id = "hybryda",
        nazwa = "HYBRYDA",
        opis = "zdjęcie lotnicze + nazwy i drogi — podkład obowiązkowy",
        baza = "zdjecia",
        nakladki = listOf("opisy", "drogi"),
        przyciemnienie = 0.35f,
        wymagany = true,
    )

    val ZDJECIA = Podklad(
        id = "zdjecia",
        nazwa = "ZDJĘCIA",
        opis = "samo zdjęcie, bez napisów — najwięcej szczegółu terenu",
        baza = "zdjecia",
        przyciemnienie = 0.35f,
    )

    val TOPO = Podklad(
        id = "topo",
        nazwa = "TOPO",
        opis = "warstwice i rzeźba — podkład do lotu na azymut",
        baza = "topo",
        przyciemnienie = 0.18f,
    )

    val MAPA = Podklad(
        id = "mapa",
        nazwa = "MAPA",
        opis = "mapa kreskowa: drogi, zabudowa, lasy — czytelna w słońcu",
        baza = "mapa",
        przyciemnienie = 0.18f,
    )

    val NOC = Podklad(
        id = "noc",
        nazwa = "NOC",
        opis = "ciemna mapa kreskowa — nie oślepia po zmroku",
        baza = "noc",
        przyciemnienie = 0.10f,
    )

    val wszystkie = listOf(HYBRYDA, ZDJECIA, TOPO, MAPA, NOC)

    val domyslny = HYBRYDA

    fun poId(id: String?): Podklad = wszystkie.firstOrNull { it.id == id } ?: domyslny
}

/**
 * Nakładki rysowane **przez kokpit**, nie pobrane z sieci — liczone z danych wysokościowych
 * albo z geometrii. Działają nad każdym podkładem, także nad zdjęciem lotniczym.
 *
 * Ustawienie przeżywa restart (`MainActivity.zapiszMape`).
 */
data class UstawieniaMapy(
    val podklad: String = Podklady.domyslny.id,
    /**
     * Czy dociągać brakujące kafelki i dane wysokościowe **z internetu**.
     *
     * Domyślnie tak. W polu aparatura zwykle siedzi w sieci pokładowej drona i internetu
     * tam nie ma — wtedy to ustawienie nic nie zmienia i mapa działa z karty. Ale wszędzie
     * tam, gdzie sieć jest, mapa dociąga się sama, a pobrany kafelek zostaje na karcie
     * i działa później bez sieci.
     */
    val zInternetu: Boolean = true,
    /** cieniowanie rzeźby liczone z danych wysokościowych — rzeźba widoczna także na zdjęciu */
    val cieniowanie: Boolean = false,
    /** warstwice liczone z danych wysokościowych, co [krokWarstwicM] metrów */
    val warstwice: Boolean = false,
    val krokWarstwicM: Int = 20,
    /** pierścień azymutu wokół domu — do lotu na azymut */
    val azymut: Boolean = false,
    /** widok przestrzenny terenu na ekranie planowania */
    val widok3d: Boolean = false,
    /** pas profilu terenu pod trasą, na ekranie planowania */
    val profil: Boolean = true,
) {
    val podkladObiekt: Podklad get() = Podklady.poId(podklad)
}
