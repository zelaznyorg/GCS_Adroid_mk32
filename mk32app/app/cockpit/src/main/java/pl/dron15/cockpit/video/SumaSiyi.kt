package pl.dron15.cockpit.video

/**
 * Suma kontrolna nagłówka strumienia SIYI na porcie 37256.
 *
 * **CRC-32 MSB-first, wielomian `0x04C11DB7`, rejestr początkowy 0, bez końcowej negacji.**
 * Ta sama rodzina co CRC-32/MPEG-2. ⚠ To **nie jest** `zlib.crc32` ani CRC-16/XMODEM,
 * którego SIYI używa w SDK głowicy na porcie 37260 — kamera liczy tu co innego.
 *
 * ### Skąd to wiadomo
 *
 * Rozłożone 2026-08-28 z podsłuchu fabrycznej aplikacji (`tcpdump` na aparaturze,
 * 31 ramek klienta) i sprawdzone na **200 ramkach wideo przysłanych przez kamerę** —
 * zgodność 200/200. Droga dojścia: osiem ostatnich bajtów nagłówka okazało się
 * **liniowych względem licznika ramek** (zmiana jednego bitu licznika dawała zawsze ten
 * sam XOR), co wyklucza szyfrowanie i wskazuje sumę kontrolną. Dalej wystarczyło
 * porównać deltę z wielomianami kandydatami.
 *
 * ### Dwie sumy, nie jedna
 *
 * | Bajty | Zawartość |
 * |---|---|
 * | 0..11 | nagłówek: `55 66 aa bb`, typ, długość (4 B LE), licznik (2 B LE), flaga |
 * | 12..15 | **suma z bajtów 0..11**, zapisana LE |
 * | 16..19 | w pakiecie klienta: **suma z bajtów 0..15**. W ramce wideo od kamery: licznik |
 *
 * Kamera **sprawdza obie** w pakiecie podtrzymania. Zmierzone: pakiet z podmienionym
 * licznikiem, ale starymi sumami, kończy się rozłączeniem po ok. 5 s — tak samo, jakby
 * nie wysłać nic. Powtórzony bajt w bajt pakiet z podsłuchu jest przyjmowany, i to
 * **także po restarcie kamery** — czyli suma nie zależy od sesji ani od czasu.
 */
object SumaSiyi {

    private const val WIELOMIAN = 0x04C11DB7L

    private val tablica = IntArray(256) { i ->
        var r = i.toLong() shl 24
        repeat(8) {
            r = if (r and 0x80000000L != 0L) ((r shl 1) xor WIELOMIAN) else (r shl 1)
        }
        (r and 0xFFFFFFFFL).toInt()
    }

    fun policz(dane: ByteArray, od: Int = 0, dlugosc: Int = dane.size - od): Int {
        var r = 0
        for (i in od until od + dlugosc) {
            r = (r shl 8) xor tablica[((r ushr 24) xor (dane[i].toInt() and 0xFF)) and 0xFF]
        }
        return r
    }

    /** Czy nagłówek 20-bajtowy ma poprawną sumę w bajtach 12..15. */
    fun naglowekPoprawny(naglowek: ByteArray, od: Int = 0): Boolean {
        val policzona = policz(naglowek, od, 12)
        val zapisana = (naglowek[od + 12].toInt() and 0xFF) or
            ((naglowek[od + 13].toInt() and 0xFF) shl 8) or
            ((naglowek[od + 14].toInt() and 0xFF) shl 16) or
            ((naglowek[od + 15].toInt() and 0xFF) shl 24)
        return policzona == zapisana
    }

    /**
     * Buduje pakiet podtrzymania sesji — dokładnie taki, jaki wysyła fabryczna aplikacja
     * co 1,000 s. Sprawdzone: wynik jest **co do bajtu** równy pakietowi z podsłuchu.
     */
    fun podtrzymanie(licznik: Int): ByteArray {
        val r = ByteArray(20)
        r[0] = 0x55; r[1] = 0x66; r[2] = 0xAA.toByte(); r[3] = 0xBB.toByte()
        r[4] = 0x01                       // typ: sterowanie (0x00 = ramka obrazu)
        // bajty 5..8 = długość ładunku = 0
        r[9] = (licznik and 0xFF).toByte()
        r[10] = ((licznik ushr 8) and 0xFF).toByte()
        r[11] = 0x80.toByte()             // flaga podtrzymania
        wpiszLe(r, 12, policz(r, 0, 12))
        wpiszLe(r, 16, policz(r, 0, 16))
        return r
    }

    /**
     * Powitanie, po którym kamera **zaczyna nadawać obraz**. Pięć ramek, odtworzonych
     * co do bajtu z podsłuchu fabrycznej aplikacji (`wl.pcap`, pierwsze 0,5 s połączenia).
     *
     * ### Dlaczego bez tego nie ma obrazu
     *
     * Zmierzone 2026-08-28, dwa razy, po każdym cyklu zasilania kamery: świeżo włączona
     * ZR30 przyjmuje połączenie na 37256, odsyła **jedną ramkę sterującą i milczy**.
     * Podtrzymanie sesji tego nie zmienia — sesja żyje, obrazu nie ma. Dopiero to
     * powitanie uruchamia strumień: **502 klatki w 20 s, 25,1 kl./s**.
     *
     * Raz włączony strumień **zostaje włączony do wyłączenia zasilania**. Stąd wcześniejsze
     * pomyłkowe wrażenie, że kamera nadaje „sama z siebie" — nadawała, bo wcześniej tego
     * dnia uruchamiano fabryczną aplikację.
     *
     * ⚠ Ramki 3–5 niosą jednobajtowy ładunek i **są odtwarzane dosłownie**, nie liczone.
     * Dla ramek z ładunkiem nie udało się ustalić, gdzie dokładnie leży druga suma
     * (zgadzała się na 27 z 31 ramek — te 4 niezgodne to właśnie ramki z ładunkiem).
     * Kamera nie ma ochrony przed powtórzeniem, więc odtworzenie działa; gdyby kiedyś
     * przestało, trzeba dokończyć rozkład ramki z ładunkiem.
     *
     * Numery kolejne w powitaniu to 1–5, więc podtrzymanie zaczyna się od 6.
     */
    val POWITANIE: List<ByteArray> = listOf(
        "556 6aabb 01 00000000 0100 94 06 96b76a c5943bef",
        "556 6aabb 01 00000000 0200 80 23 cecb37 e4327345",
        "556 6aabb 01 01000000 0300 83 6c 2c0774 01 38a12e18",
        "556 6aabb 01 01000000 0400 90 50 965e30 01 1676 2c7a",
        "556 6aabb 01 01000000 0500 90 d7 3a8631 01 4327 c145",
    ).map { h -> h.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray() }

    /** Od którego numeru startuje podtrzymanie po wysłaniu [POWITANIE]. */
    const val PIERWSZE_PODTRZYMANIE = 6

    private fun wpiszLe(cel: ByteArray, od: Int, wartosc: Int) {
        cel[od] = (wartosc and 0xFF).toByte()
        cel[od + 1] = ((wartosc ushr 8) and 0xFF).toByte()
        cel[od + 2] = ((wartosc ushr 16) and 0xFF).toByte()
        cel[od + 3] = ((wartosc ushr 24) and 0xFF).toByte()
    }
}
