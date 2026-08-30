package pl.dron15.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.dron15.cockpit.domain.Czujniki
import pl.dron15.cockpit.domain.Ostrzezenia
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Tryby

/**
 * Belka górna i nawigacja — makieta `Kokpit M3.dc.html`, §3 przekazania.
 *
 * Wybór ekranu przeniósł się **z pasa zakładek na kadrze do menu w belce**. Pas zabierał
 * pionowy słup na każdym z sześciu widoków; menu zajmuje jedno pole i rozwija się tylko
 * wtedy, gdy jest potrzebne.
 */

/** Sześć widoków. Kolejność jest kolejnością w menu. */
enum class Ekran(val etykieta: String, val piktogram: Piktogram, val krotka: String = "") {
    LOT("LOT", Piktogram.LOT),
    MISJA("MISJA", Piktogram.MISJA),
    KAMERA("KAMERA", Piktogram.KAMERA),
    PRZED("PRZED LOTEM", Piktogram.CHECKLISTA, "PRZED"),
    RC("RC", Piktogram.APARATURA),
    DIAG("DIAGNOSTYKA", Piktogram.DIAGNOSTYKA, "DIAG");

    /** Nazwa na klawisz menu, gdy belka jest zwięzła. */
    fun etykieta(zwiezle: Boolean): String =
        if (zwiezle && krotka.isNotEmpty()) krotka else etykieta

    /** Czy ekran rysuje się na kadrze (obraz albo mapa), czy jest panelem roboczym. */
    val naKadrze: Boolean get() = this == LOT || this == MISJA || this == KAMERA
}

/**
 * Belka: **32 dp, przejście tonalne na pełnym kryciu przez pierwsze 27 dp**, włos u dołu.
 *
 * Pola są **podpisane piktogramem, nie słowem** (decyzja Toma 2026-08-28): rysunek
 * oszczędza miejsce i broni się sam przy tłumaczeniu. Łącze idzie dalej — jego stan niesie
 * **wypełnienie i kolor słupków zasięgu**, a nie liczba z podpisem (patrz `IkonaLacza`).
 *
 * Tekstem zostają `ALTHOLD` i `UZBROJONY`: to nie etykiety, tylko stany, a dwadzieścia
 * trybów ArduPilota nie ma sensownych piktogramów.
 */
@Composable
fun BelkaGorna(
    stan: StanMaszyny,
    teraz: Long,
    ekran: Ekran,
    menuOtwarte: Boolean,
    warstwyOtwarte: Boolean,
    naMenu: () -> Unit,
    naWarstwy: () -> Unit,
    naMotyw: () -> Unit,
    modifier: Modifier = Modifier,
    /** Kto steruje. `null` = operator MK32. Wypełni to moduł udostępniania z M5. */
    steruje: String? = null,
    /** Czy pokazywać znacznik władzy — warstwa, domyślnie zdjęta. */
    pokazWladze: Boolean = false,
) {
    val zywa = stan.telemetriaZywa(teraz)
    val wiek = stan.wiekTelemetriiS(teraz)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Wymiary.Belka)
            .background(
                Brush.verticalGradient(
                    0f to Barwy.Scrim,
                    (Wymiary.BelkaKrycie / Wymiary.Belka) to Barwy.Scrim,
                    1f to Color.Transparent,
                )
            )
            .drawBehind {
                drawRect(Barwy.Linia2, topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(size.width, 1.dp.toPx()))
            }
    ) {
        // Ekran aparatury daje 640 dp szerokości (zmierzone przez ADB 2026-08-25), a belka
        // w pełnej postaci potrzebuje ok. 800 dp. Poniżej progu zwężamy pola wg ważności,
        // zamiast pozwolić im wypchnąć menu widoków poza krawędź — patrz dok/PIERWSZY_TEST_MK32.md.
        val zwiezla = maxWidth < Wymiary.BelkaProgZwiezly
        val odstep = if (zwiezla) 7.dp else 10.dp

        Row(
            Modifier.fillMaxSize().padding(horizontal = if (zwiezla) 6.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(odstep),
        ) {
            // LEWA GRUPA — z wagą, więc mierzy się jako ostatnia i dostaje to, co zostanie.
            // To jest właściwa poprawka: pola stanu nie mogą wypchnąć sterowania poza ekran.
            Row(
                Modifier.weight(1f).clipToBounds(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(odstep),
            ) {
                ZnacznikTrybu(stan.tryb)

                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).background(
                        if (stan.uzbrojony) Barwy.Uwaga else Barwy.Drugi))
                    Text(
                        when {
                            stan.uzbrojony && zwiezla -> "UZBR."
                            stan.uzbrojony -> "UZBROJONY"
                            zwiezla -> "ROZBR."
                            else -> "ROZBROJONY"
                        },
                        style = Kroje.liczba(13.sp, FontWeight.Medium,
                            if (stan.uzbrojony) Barwy.Uwaga else Barwy.Drugi),
                        maxLines = 1,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SlupekBaterii(stan.napiecieV)
                    Wartosc13("%.1f".format(stan.napiecieV), "V", kolorNapiecia(stan.napiecieV))
                }

                // Podpisy zastapione piktogramami — decyzja Toma 2026-08-28.
                PoleZIkona(Piktogram.SATELITY, "${stan.satelity}", null,
                    if (stan.satelity >= Ostrzezenia.SATELITY_MIN) Barwy.Tekst else Barwy.Uwaga)

                // Lacze: slupki zasiegu wypelniane kolorem stanu. Liczba Hz zostaje obok,
                // bo przy diagnozie zasiegu sama ikona nie wystarcza — ale to ona niesie
                // stan, a nie podpis.
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IkonaLacza(stan, teraz)
                    if (!zwiezla) {
                        Wartosc13(
                            when {
                                zywa -> "%.0f".format(stan.ramekNaSekunde)
                                stan.telemetriaByla -> "%.0f".format(wiek)
                                else -> "—"
                            },
                            when {
                                zywa -> "Hz"
                                stan.telemetriaByla -> "s"
                                else -> null
                            },
                            kolorLacza(stan, teraz),
                        )
                    }
                }

                // Czas lotu ustępuje pierwszy: jest też na ekranie DIAGNOSTYKA.
                // Ustępuje **także wtedy, gdy czujnik padł** — wtedy pasek czujników
                // rozwija się do pełnych skrótów i potrzebuje tego miejsca. Przy dwóch
                // usterkach drugi skrót inaczej się ucinał: „BAR" i obok samo „B".
                val usterki = Czujniki.odczytaj(
                    stan.czujnikiObecne, stan.czujnikiWlaczone, stan.czujnikiZdrowe,
                ).count { it.stan != Czujniki.Stan.SPRAWNY }
                if (!zwiezla && usterki == 0) {
                    PoleZIkona(Piktogram.CZAS, czasMmSs(stan.czasLotuS(teraz)), null, Barwy.Tekst)
                }

                // Najblizszy prog paliwowy — przeniesiony z pasa przyrzadow na belke
                // (Tom, 2026-08-28). Milczy, gdy BATT_CAPACITY jest niekalibrowane.
                if (!zwiezla) PoleCzasuDoProgu(stan, teraz)

                // Stan czujników z masek SYS_STATUS. W postaci zwięzłej znika razem
                // z czasem lotu — na 640 dp aparatury liczy się każdy dp, a uszkodzenie
                // i tak zapali baner.
                if (!zwiezla) PasekCzujnikow(stan)
            }

            // PRAWA GRUPA — bez wagi, mierzona jako pierwsza, więc zawsze się mieści.
            // Stany zmieniające zakres możliwych działań — w belce, nie w banerze.
            if (!stan.kursGnssDostepny) {
                Znacznik(if (zwiezla) "BEZ KURSU" else "BRAK KURSU GNSS", Barwy.Blokada)
            } else if (!stan.rtlDostepny) {
                Znacznik(if (zwiezla) "BEZ RTL" else "RTL NIEDOSTĘPNY", Barwy.Uwaga)
            }

            if (pokazWladze) ZnacznikWladzy(steruje, zwiezla)
            KlawiszBelki(Piktogram.MOTYW, "motyw", Wymiary.BelkaKlawisz, false, naMotyw)
            KlawiszBelki(Piktogram.WARSTWY, "warstwy", Wymiary.BelkaKlawisz,
                warstwyOtwarte, naWarstwy)
            KlawiszMenu(ekran, menuOtwarte, zwiezla, naMenu)
        }
    }
}

/** Pole belki z **piktogramem zamiast podpisu** — rysunek nie ma języka. */
@Composable
private fun PoleZIkona(
    piktogram: Piktogram,
    wartosc: String,
    jednostka: String?,
    kolor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Ikona(piktogram, kolor = Barwy.Drugi, rozmiar = 14.dp)
        Wartosc13(wartosc, jednostka, kolor)
    }
}

/** Pole belki: podpis 11 sp i wartość 13 sp, ewentualnie z jednostką 9 sp. */
@Composable
private fun Pole(
    podpis: String,
    wartosc: String,
    jednostka: String?,
    kolor: Color,
    bezPodpisu: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Przy ciasnocie zostaje sama wartość z jednostką — "18" obok słupka baterii
        // czyta się bez podpisu, a podpis kosztuje kilkadziesiąt dp.
        if (!bezPodpisu) Text(podpis, color = Barwy.Drugi, fontSize = 11.sp, maxLines = 1)
        Wartosc13(wartosc, jednostka, kolor)
    }
}

@Composable
private fun Wartosc13(wartosc: String, jednostka: String?, kolor: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(wartosc, style = Kroje.liczba(13.sp, FontWeight.SemiBold, kolor), maxLines = 1)
        if (jednostka != null) {
            Text(jednostka, color = Barwy.Drugi, fontSize = 9.sp, maxLines = 1)
        }
    }
}

/** Znacznik trybu: płyta ze ściętym narożem, krawędź akcentu po lewej. */
@Composable
private fun ZnacznikTrybu(tryb: String) {
    val automat = Tryby.automatyczny(tryb)
    Box(
        Modifier
            .height(20.dp)
            .plyta(6.dp, Barwy.AkcentTlo, Barwy.Akcent)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(tryb, style = Kroje.zgeszczona(15.sp,
            if (automat) Barwy.Akcent else Barwy.Tekst), maxLines = 1)
    }
}

/**
 * Słupek baterii — 40 × 11 dp, jak w makiecie: prostokąt z włosem, wypełnienie w kolorze
 * stanu. Skala 6S: pusty przy `BATT_CRT_VOLT` = 21,0 V, pełny przy 25,2 V.
 */
@Composable
fun SlupekBaterii(napiecieV: Float) {
    val udzial = ((napiecieV - PUSTY_6S) / (Ostrzezenia.NAPIECIE_GORNE - PUSTY_6S)).coerceIn(0f, 1f)
    val kolor = if (napiecieV > Ostrzezenia.NAPIECIE_DOLNE) Barwy.Dobrze else Barwy.Blokada
    Box(
        Modifier
            .size(40.dp, 11.dp)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(Barwy.Linia, size = Size(size.width, w))
                drawRect(Barwy.Linia, topLeft = Offset(0f, size.height - w),
                    size = Size(size.width, w))
                drawRect(Barwy.Linia, size = Size(w, size.height))
                drawRect(Barwy.Linia, topLeft = Offset(size.width - w, 0f),
                    size = Size(w, size.height))
                if (napiecieV > 0.1f) {
                    drawRect(
                        kolor,
                        topLeft = Offset(2 * w, 2 * w),
                        size = Size((size.width - 4 * w) * udzial, size.height - 4 * w),
                    )
                }
            }
    )
}

private const val PUSTY_6S = 21.0f       // BATT_CRT_VOLT

/**
 * Pole władzy — UI.md §4 („pas władzy"). Dopóki steruje operator MK32, jest to spokojny
 * podpis w ramce. Z chwilą przekazania nazwa robi się bursztynowa.
 *
 * Moduł udostępniania (M5) jeszcze nie istnieje, więc dziś steruje wyłącznie MK32 — i tak
 * właśnie ekran ma to mówić, zamiast milczeć.
 */
@Composable
private fun ZnacznikWladzy(steruje: String?, zwiezle: Boolean = false) {
    Row(
        Modifier
            .heightIn(min = 20.dp)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(Barwy.Linia, size = Size(size.width, w))
                drawRect(Barwy.Linia, topLeft = Offset(0f, size.height - w),
                    size = Size(size.width, w))
                drawRect(Barwy.Linia, size = Size(w, size.height))
                drawRect(Barwy.Linia, topLeft = Offset(size.width - w, 0f),
                    size = Size(w, size.height))
            }
            .padding(horizontal = if (zwiezle) 5.dp else 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Przy ciasnocie podpis "władza" ustępuje, ale sam stan zostaje zawsze: kto steruje
        // maszyną, to nie jest informacja, którą wolno schować (dok/WLADZA.md §4).
        if (!zwiezle) Etykieta("władza")
        Text(
            when {
                steruje != null -> steruje.uppercase()
                zwiezle -> "STERUJESZ"
                else -> "STERUJESZ TY"
            },
            style = Kroje.zgeszczona(14.sp, if (steruje == null) Barwy.Dobrze else Barwy.Uwaga),
            maxLines = 1,
        )
    }
}

@Composable
private fun Znacznik(tekst: String, kolor: Color) {
    Box(
        Modifier
            .height(20.dp)
            .plyta(6.dp, Color.Transparent, kolor,
                nakladka = kolor.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(tekst, style = Kroje.zgeszczona(11.sp, kolor), maxLines = 1)
    }
}

@Composable
private fun KlawiszBelki(
    piktogram: Piktogram,
    opis: String,
    bok: androidx.compose.ui.unit.Dp,
    aktywny: Boolean,
    akcja: () -> Unit,
) {
    val akcjaTeraz by rememberUpdatedState(akcja)
    Box(
        Modifier
            .size(bok)
            .background(if (aktywny) Barwy.AkcentTlo else Color.Transparent)
            .drawBehind {
                if (aktywny) {
                    val w = 1.dp.toPx()
                    drawRect(Barwy.Akcent, size = Size(size.width, w))
                    drawRect(Barwy.Akcent, topLeft = Offset(0f, size.height - w),
                        size = Size(size.width, w))
                    drawRect(Barwy.Akcent, size = Size(w, size.height))
                    drawRect(Barwy.Akcent, topLeft = Offset(size.width - w, 0f),
                        size = Size(w, size.height))
                }
            }
            // `pointerInput(Unit)`, nie `pointerInput(opis, aktywny)`: zmiana klucza
            // **przerywa i buduje od nowa** detektor gestów, gubiąc dotknięcie, które
            // akurat trwa. `aktywny` zmienia się przy każdym otwarciu panelu warstw,
            // a całe drzewo przelicza się kilka razy na sekundę od telemetrii.
            .pointerInput(Unit) { detectTapGestures(onTap = { akcjaTeraz() }) },
        contentAlignment = Alignment.Center,
    ) {
        Ikona(piktogram, kolor = if (aktywny) Barwy.Akcent else Barwy.Tekst, rozmiar = 20.dp)
    }
}

/**
 * Klawisz menu widoków: nazwa bieżącego ekranu i strzałka.
 *
 * ⛔ Miał **22 dp wysokości (3,5 mm)** i był najmniejszym celem dotykowym w aplikacji —
 * stąd zgłoszenie, że przełączanie ekranów „nie zawsze działa". Teraz wypełnia belkę.
 */
@Composable
private fun KlawiszMenu(ekran: Ekran, otwarte: Boolean, zwiezle: Boolean, akcja: () -> Unit) {
    val akcjaTeraz by rememberUpdatedState(akcja)
    Row(
        Modifier
            .height(Wymiary.BelkaKlawisz)
            .widthIn(min = 104.dp)
            .plyta(7.dp, if (otwarte) Barwy.Tafla else Color.Transparent,
                if (otwarte) Barwy.Akcent else Barwy.Linia2,
                nakladka = if (otwarte) Barwy.AkcentTlo else Color.Transparent)
            // Klucz `Unit`: `otwarte` zmienia się w reakcji na to samo dotknięcie, więc
            // detektor przebudowywał się w trakcie gestu.
            .pointerInput(Unit) { detectTapGestures(onTap = { akcjaTeraz() }) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Bez `weight` — klawisz siedzi w prawej grupie belki, ktora nie ma ograniczenia
        // szerokosci, wiec wazony napis rozepchnalby go na caly pasek i wypchnal pola stanu.
        Text(ekran.etykieta(zwiezle),
            style = Kroje.zgeszczona(15.sp, if (otwarte) Barwy.Akcent else Barwy.Tekst),
            maxLines = 1)
        Ikona(
            if (otwarte) Piktogram.STRZALKA_GORA else Piktogram.STRZALKA_DOL,
            kolor = if (otwarte) Barwy.Akcent else Barwy.Tekst, rozmiar = 16.dp,
        )
    }
}

/**
 * Rozwinięta lista widoków — 180 dp, sześć pozycji po 38 dp. Aktywna nosi **krawędź
 * akcentu po lewej**, tak jak każda inna płyta w tym interfejsie; ramka dookoła byłaby
 * obcym elementem.
 *
 * Pozycje mają [Wymiary.CelDotyku], czyli 64 dp. Sześć pozycji to 396 dp z 601 dp ekranu —
 * dużo, ale lista jest **chwilowa**: pojawia się na jedno dotknięcie i znika po wyborze.
 * Wcześniejsze 44 dp było kompromisem na rzecz miejsca, którego ta lista i tak nie zajmuje
 * na stałe.
 */
@Composable
fun ListaWidokow(
    wybrany: Ekran,
    naWybor: (Ekran) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(Wymiary.MenuSzer)
            .plyta(14.dp, Barwy.TaflaPelna, Barwy.Akcent)
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Ekran.entries.forEach { e ->
            val aktywny = e == wybrany
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Wymiary.CelDotyku)
                    .background(if (aktywny) Barwy.AkcentTlo else Color.Transparent)
                    .drawBehind {
                        if (aktywny) drawRect(Barwy.Akcent, size = Size(2.dp.toPx(), size.height))
                    }
                    .pointerInput(e) { detectTapGestures(onTap = { naWybor(e) }) }
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Ikona(e.piktogram, kolor = if (aktywny) Barwy.Akcent else Barwy.Tekst, rozmiar = 17.dp)
                Text(e.etykieta,
                    style = Kroje.zgeszczona(15.sp, if (aktywny) Barwy.Akcent else Barwy.Tekst),
                    maxLines = 1)
            }
        }
    }
}

/**
 * Warstwy ekranu — **panel przy prawej krawędzi, 252 dp, od belki do spodu**.
 *
 * Pilot decyduje, co leży na kadrze. Wszystko poza belką da się zdjąć; ustawienie przeżywa
 * restart. Tu jest też wybór krawędzi kolumny przyrządów i motywu.
 */
@Composable
fun NakladkaWarstw(
    warstwy: WarstwyEkranu,
    mapa: UstawieniaMapy,
    naZmiane: (WarstwyEkranu) -> Unit,
    naZmianeMapy: (UstawieniaMapy) -> Unit,
    naZamknij: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(Wymiary.WarstwySzer)
            .fillMaxHeight()
            .background(Barwy.TaflaPelna)
            .drawBehind { drawRect(Barwy.Linia, size = Size(1.dp.toPx(), size.height)) }
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("WARSTWY EKRANU", style = Kroje.zgeszczona(20.sp))
            Box(
                Modifier.size(28.dp).pointerInput(Unit) {
                    detectTapGestures(onTap = { naZamknij() })
                },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Barwy.Drugi, fontSize = 18.sp)
            }
        }
        Text(
            "Każdy element kokpitu można zdjąć z kadru. Ustawienie zostaje między lotami.",
            color = Barwy.Drugi, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        WierszWarstwy("Taśma kursu", "400 × 20 dp, zakres 60°", warstwy.tasmaKursu) {
            naZmiane(warstwy.copy(tasmaKursu = it))
        }
        WierszWarstwy(
            "Miniatura mapy", "dół kolumny · dotknięcie zamienia z kadrem",
            warstwy.miniaturaMapy,
        ) { naZmiane(warstwy.copy(miniaturaMapy = it)) }
        WierszWarstwy("Wskaźnik położenia", "okrągły, przezroczysty, 132 dp",
            warstwy.okragPolozenia) { naZmiane(warstwy.copy(okragPolozenia = it)) }
        WierszWarstwy("Rząd liczb", "wysokość · dom · prędkość · wznoszenie",
            warstwy.rzadLiczb) { naZmiane(warstwy.copy(rzadLiczb = it)) }
        WierszWarstwy("Dok akcji", "komendy przy krawędzi i pion kamery",
            warstwy.dokAkcji) { naZmiane(warstwy.copy(dokAkcji = it)) }
        WierszWarstwy("Zapas ciągu", "ile µs do sufitu wyjścia i rozrzut silników",
            warstwy.pasZapasu) { naZmiane(warstwy.copy(pasZapasu = it)) }
        WierszWarstwy("Energia", "zużyte mAh, prąd, JOKER i BINGO",
            warstwy.blokEnergii) { naZmiane(warstwy.copy(blokEnergii = it)) }
        WierszWarstwy("Cel automatu", "dokąd leci RTL i AUTO, zapas geofence",
            warstwy.blokCelu) { naZmiane(warstwy.copy(blokCelu = it)) }
        WierszWarstwy("Znacznik władzy", "kto steruje maszyną — przy jednej stacji zbędny",
            warstwy.znacznikWladzy) { naZmiane(warstwy.copy(znacznikWladzy = it)) }
        WierszWarstwy("Alarmy dźwiękiem", "zapas ciągu, paliwo, utrata łącza",
            warstwy.dzwiek) { naZmiane(warstwy.copy(dzwiek = it)) }

        Spacer(Modifier.height(16.dp))
        SekcjaMapy(mapa, naZmianeMapy)

        Spacer(Modifier.height(16.dp))
        Etykieta("motyw")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Motyw.entries.take(3).forEach { m ->
                Chip(m.etykieta, warstwy.motyw == m, Modifier.weight(1f)) {
                    naZmiane(warstwy.copy(motyw = m))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Motyw.entries.drop(3).forEach { m ->
                Chip(m.etykieta, warstwy.motyw == m, Modifier.weight(1f)) {
                    naZmiane(warstwy.copy(motyw = m))
                }
            }
        }
    }
}

/**
 * Podkład mapy i nakładki terenu — **wybór map, którego kokpit nie miał do 2026-08-25**.
 *
 * Do tej pory mapa brała cokolwiek leżało na karcie i nie dało się tego zmienić w polu.
 * Teraz operator wybiera podkład, a kokpit mówi wprost, których kafelków na karcie brakuje —
 * bo brakujący podkład wychodzi zwykle dopiero na miejscu startu, gdzie nie ma już internetu.
 */
@Composable
private fun SekcjaMapy(mapa: UstawieniaMapy, naZmiane: (UstawieniaMapy) -> Unit) {
    val kontekst = androidx.compose.ui.platform.LocalContext.current
    val magazyn = androidx.compose.runtime.remember(kontekst) { MagazynKafelkow.dla(kontekst) }
    val teren = androidx.compose.runtime.remember(kontekst) { MagazynTerenu.dla(kontekst) }

    WierszWarstwy(
        "Mapa z internetu",
        if (mapa.zInternetu) "dociąga brakujące kafelki i zostawia je na karcie"
        else "tylko to, co już leży na karcie",
        mapa.zInternetu,
    ) { naZmiane(mapa.copy(zInternetu = it)) }
    if (mapa.zInternetu) {
        Text(
            "w polu aparatura siedzi w sieci drona i internetu tam nie ma — " +
                    "rejon obejrzany przy sieci zostaje pobrany na później" +
                    if (magazyn.pobraneZSieci + teren.pobraneZSieci > 0)
                        "\npobrano w tym uruchomieniu: " +
                                "${magazyn.pobraneZSieci} kafelków mapy, " +
                                "${teren.pobraneZSieci} terenu"
                    else "",
            color = Barwy.Wygasly, fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
    }

    Spacer(Modifier.height(10.dp))
    Etykieta("podkład mapy")
    Spacer(Modifier.height(6.dp))
    Podklady.wszystkie.chunked(2).forEach { para ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            para.forEach { p ->
                Chip(
                    etykieta = p.nazwa,
                    wybrany = mapa.podklad == p.id,
                    modifier = Modifier.weight(1f),
                    rozmiar = 12.sp,
                    dostepny = magazyn.maPodklad(p),
                ) { naZmiane(mapa.copy(podklad = p.id)) }
            }
            if (para.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    Text(
        mapa.podkladObiekt.opis,
        color = Barwy.Wygasly, fontSize = 10.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    // Przy włączonym internecie „brak" znaczy tylko „nie leży jeszcze na karcie".
    val brakujace =
        if (mapa.zInternetu) emptyList()
        else Podklady.wszystkie.filterNot { magazyn.maPodklad(it) }
    if (brakujace.isNotEmpty()) {
        Text(
            "na karcie brakuje: " + brakujace.joinToString(", ") { it.nazwa.lowercase() } +
                    " — dograć narzedzia/kafelki.py",
            color = if (brakujace.any { it.wymagany }) Barwy.Uwaga else Barwy.Wygasly,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    Etykieta("teren")
    Spacer(Modifier.height(2.dp))
    if (!teren.maDane) {
        Text(
            "brak danych wysokościowych na karcie — cieniowanie, warstwice, prześwit " +
                    "i widok przestrzenny nie policzą się bez nich",
            color = Barwy.Uwaga, fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    WierszWarstwy("Cieniowanie rzeźby", "z danych wysokościowych, także na zdjęciu",
        mapa.cieniowanie) { naZmiane(mapa.copy(cieniowanie = it)) }
    WierszWarstwy("Warstwice", "co ${mapa.krokWarstwicM} m, co piąta gruba",
        mapa.warstwice) { naZmiane(mapa.copy(warstwice = it)) }
    WierszWarstwy("Pierścień azymutu", "kreska co 10°, podpis co 30° — azymut geograficzny",
        mapa.azymut) { naZmiane(mapa.copy(azymut = it)) }

    if (mapa.warstwice) {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 20, 50).forEach { krok ->
                Chip("$krok m", mapa.krokWarstwicM == krok, Modifier.weight(1f), rozmiar = 11.sp) {
                    naZmiane(mapa.copy(krokWarstwicM = krok))
                }
            }
        }
    }
}

@Composable
private fun WierszWarstwy(
    nazwa: String,
    opis: String,
    wlaczona: Boolean,
    naZmiane: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .drawBehind {
                drawRect(Barwy.Linia2, topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(size.width, 1.dp.toPx()))
            }
            .pointerInput(nazwa, wlaczona) { detectTapGestures(onTap = { naZmiane(!wlaczona) }) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Przelacznik(wlaczona) { naZmiane(it) }
        Column {
            Text(nazwa, color = Barwy.Tekst, fontSize = 13.sp, maxLines = 1)
            Text(opis, color = Barwy.Wygasly, fontSize = 9.sp, letterSpacing = 0.6.sp, maxLines = 1)
        }
    }
}
