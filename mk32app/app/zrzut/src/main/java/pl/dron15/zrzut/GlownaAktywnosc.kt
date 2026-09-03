package pl.dron15.zrzut

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Ekran obsługi na aparaturze — Material 3.
 *
 * ### Czym ten ekran jest, a czym nie
 *
 * **Nie jest pilotem do obsługi w locie.** W powietrzu pilot patrzy na DJI Pilot 2
 * i nie będzie przełączał programów — od wstrzymywania obrazu jest kafelek szybkich
 * ustawień i powiadomienie ([KafelekZrzutu], [UslugaZrzutu]).
 *
 * Ten ekran służy **przed lotem**: ustawić, sprawdzić łącze, wziąć zgodę i ruszyć.
 *
 * ### Co daje Material 3
 *
 * Karty, pola z obwódką i **segmentowany wybór** (`MaterialButtonToggleGroup`) to
 * gotowe odpowiedniki tego, co wcześniej rysowaliśmy ręcznie — z porządnymi stanami
 * dotknięcia, ogniskiem i typografią. Na Androidzie 12+ dochodzi **Material You**:
 * barwy pobrane z tapety systemu.
 *
 * ⚠ Kontrolery DJI to Android 9–11, więc tam Material You się **nie włączy** i zostaje
 * nasza paleta (`res/values/colors.xml`) — ta sama, co na stacji. Barwa nie jest tu
 * ozdobą: **zielone jest wyłącznie to, co znaczy „obraz idzie"**, pomarańczowe —
 * wstrzymanie. Dlatego barwy stanu podmieniamy jawnie, zamiast zdawać się na motyw,
 * który na Androidzie 12+ mógłby je zabrać z tapety.
 */
class GlownaAktywnosc : AppCompatActivity() {

    private lateinit var prefy: SharedPreferences

    private lateinit var kartaStanu: MaterialCardView
    private lateinit var napisStanu: TextView
    private lateinit var wartoscPrzeplyw: TextView
    private lateinit var wartoscCzas: TextView
    private lateinit var szczegol: TextView
    private lateinit var klawiszGlowny: MaterialButton
    private lateinit var klawiszKoniec: MaterialButton
    private lateinit var poleAdres: TextInputEditText
    private lateinit var poleHaslo: TextInputEditText
    private lateinit var wyborJakosci: MaterialButtonToggleGroup
    private lateinit var podsumowanieJakosci: TextView
    private lateinit var przelacznikChowania: MaterialSwitch
    private lateinit var napisAdresRtmp: TextView
    private lateinit var kartaPodpowiedzi: MaterialCardView
    private lateinit var napisPodpowiedzi: TextView

    private var fps = 15
    private var kbs = 3000
    private var podzialka = 75

    /** Nastawa jakości: klatki, skala, przepływność i słowo dla człowieka. */
    private data class Jakosc(val fps: Int, val skala: Int, val kbs: Int, val opis: String)

    private val jakosci by lazy {
        mapOf(
            R.id.jakoscLekka to Jakosc(15, 50, 1500, "oszczędna"),
            R.id.jakoscZwykla to Jakosc(15, 75, 3000, "na co dzień"),
            R.id.jakoscOstra to Jakosc(30, 100, 6000, "obciąża aparaturę"),
        )
    }

    private val zegar = Handler(Looper.getMainLooper())
    private val odswiezanie = object : Runnable {
        override fun run() { odmaluj(); zegar.postDelayed(this, 500) }
    }

    /**
     * Czy czekamy na start, żeby schować aplikację.
     *
     * Sens jest taki: pilot naciska START, widzi przez chwilę zieloną kartę
     * (potwierdzenie, że obraz poszedł) i ekran **sam schodzi mu z drogi** — bez
     * szukania klawisza. Jedno naciśnięcie zamiast dwóch, w chwili, gdy uwaga jest
     * już przy maszynie.
     */
    private var czekamNaStart = false

    /** Do kiedy trzymać ostatni komunikat, zanim wróci opis stanu. */
    private var komunikatDo = 0L
    private val odbiorca = object : BroadcastReceiver() {
        override fun onReceive(k: Context?, i: Intent?) {
            // Meldunek usługi nie depcze świeżej odpowiedzi na naciśnięcie operatora.
            i?.getStringExtra(UslugaZrzutu.DODATEK_STAN)?.let {
                if (System.currentTimeMillis() > komunikatDo) szczegol.text = it
            }
            odmaluj()
        }
    }

    override fun onCreate(zapis: Bundle?) {
        // Material You: na Androidzie 12+ podmienia paletę na pobraną z tapety.
        // Na starszych (a takie są kontrolery DJI) nie robi nic i zostaje nasza.
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(zapis)
        setContentView(R.layout.glowna)

        prefy = getSharedPreferences("zrzut", Context.MODE_PRIVATE)
        fps = prefy.getInt("fps", 15)
        kbs = prefy.getInt("bitrate", 3000)
        podzialka = prefy.getInt("podzialka", 75)

        kartaStanu = findViewById(R.id.kartaStanu)
        napisStanu = findViewById(R.id.napisStanu)
        wartoscPrzeplyw = findViewById(R.id.wartoscPrzeplyw)
        wartoscCzas = findViewById(R.id.wartoscCzas)
        szczegol = findViewById(R.id.szczegol)
        klawiszGlowny = findViewById(R.id.klawiszGlowny)
        klawiszKoniec = findViewById(R.id.klawiszKoniec)
        poleAdres = findViewById(R.id.poleAdres)
        poleHaslo = findViewById(R.id.poleHaslo)
        wyborJakosci = findViewById(R.id.wyborJakosci)
        podsumowanieJakosci = findViewById(R.id.podsumowanieJakosci)
        przelacznikChowania = findViewById(R.id.przelacznikChowania)
        przelacznikChowania.isChecked = prefy.getBoolean("chowaj", true)
        przelacznikChowania.setOnCheckedChangeListener { _, w ->
            prefy.edit().putBoolean("chowaj", w).apply()
        }

        findViewById<MaterialButton>(R.id.klawiszUkryj).setOnClickListener { ukryj("operator") }

        poleAdres.setText(prefy.getString("adres", "192.168.88.30:5601"))
        poleHaslo.setText(prefy.getString("haslo", ""))

        klawiszGlowny.setOnClickListener { nacisnietoGlowny() }
        klawiszKoniec.setOnClickListener {
            startService(Intent(this, UslugaZrzutu::class.java).setAction(UslugaZrzutu.AKCJA_KONIEC))
            powiedz("Zakończone — kolejny start znów poprosi o zgodę.")
        }
        findViewById<MaterialButton>(R.id.klawiszProba).setOnClickListener { sprawdzLacze() }

        napisAdresRtmp = findViewById(R.id.napisAdresRtmp)
        kartaPodpowiedzi = findViewById(R.id.kartaPodpowiedzi)
        napisPodpowiedzi = findViewById(R.id.napisPodpowiedzi)
        findViewById<MaterialButton>(R.id.klawiszKopiuj).setOnClickListener { skopiujAdres() }
        findViewById<MaterialButton>(R.id.klawiszPilot).setOnClickListener { otworzPilota() }
        findViewById<MaterialButton>(R.id.klawiszDrugaDroga).setOnClickListener {
            // Jedno naciśnięcie robi komplet: kopiuje adres i otwiera Pilota.
            // Gdy zrzut właśnie zawiódł, operator nie ma czasu na trzy kroki.
            skopiujAdres()
            otworzPilota()
        }

        wyborJakosci.check(idJakosci())
        wyborJakosci.addOnButtonCheckedListener { _, id, zaznaczony ->
            if (!zaznaczony) return@addOnButtonCheckedListener
            // ⛔ W trakcie nadawania ustawień nie ruszamy: zmiana w locie znaczyłaby
            // zerwanie i odtworzenie łącza w najgorszym możliwym momencie.
            if (Stan.nadaje) {
                powiedz("Najpierw wstrzymaj obraz — w trakcie nadawania ustawienia są zamknięte.")
                wyborJakosci.check(idJakosci())
                return@addOnButtonCheckedListener
            }
            jakosci[id]?.let { fps = it.fps; podzialka = it.skala; kbs = it.kbs }
            zapisz()
            odmalujJakosc()
        }

        val kafelek = findViewById<MaterialButton>(R.id.klawiszKafelek)
        val wskazowka = findViewById<TextView>(R.id.wskazowkaKafelka)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            kafelek.visibility = View.VISIBLE
            wskazowka.visibility = View.GONE
            kafelek.setOnClickListener { dodajKafelek() }
            // Android 13+ nie pokaże powiadomienia bez zgody, a powiadomienie jest
            // drugą drogą obsługi w locie — pytamy od razu, nie przy pierwszym starcie.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 33)
        }

        odmalujJakosc()
        odmaluj()
    }

    /**
     * Usuwa aplikację z widoku, **nie zatrzymując nadawania**.
     *
     * ### Dlaczego `moveTaskToBack`, a nie zamknięcie
     *
     * Android nie ma zasobnika systemowego, a zamknięcie aktywności zabiłoby usługę
     * razem ze zgodą na przechwytywanie — i wznowienie w locie znów wymagałoby
     * okienka. `moveTaskToBack` zostawia wszystko żywe i **wraca do tego, co było
     * pod spodem**, czyli zwykle do DJI Pilot 2.
     *
     * ⛔ Dlatego też nie robimy pływającego klawisza nad Pilotem: przechwytujemy
     * **cały ekran**, więc każda nasza nakładka trafiłaby do obrazu wysyłanego na
     * stację. Sterowanie w locie zostaje tam, gdzie obrazu nie zasłania — w kafelku
     * szybkich ustawień i w powiadomieniu.
     */
    private fun ukryj(powod: String) {
        powiedz("Ukryte ($powod) — obraz leci dalej.")
        moveTaskToBack(true)
    }

    private fun idJakosci(): Int =
        jakosci.entries
            .firstOrNull { (_, j) -> j.fps == fps && j.skala == podzialka && j.kbs == kbs }
            ?.key ?: R.id.jakoscZwykla

    private fun odmalujJakosc() {
        val j = jakosci[idJakosci()] ?: return
        val mb = if (j.kbs % 1000 == 0) "${j.kbs / 1000}" else "${j.kbs / 1000f}"
        podsumowanieJakosci.text = "${j.fps} kl./s  ·  skala ${j.skala} %  ·  $mb Mb/s  ·  ${j.opis}"
    }

    // ---- wygląd zależny od stanu ------------------------------------------------

    private fun odmaluj() {
        // ⛔ Zielone jest WYŁĄCZNIE „obraz idzie". Gdy operator włączył nadawanie,
        // ale łącze leży, karta jest pomarańczowa i mówi „ŁĄCZY SIĘ" — inaczej
        // pilot odchodziłby od aparatury przekonany, że stacja ma obraz.
        val idzie = Stan.nadaje && Stan.plynie
        val akcent = when {
            idzie -> getColor(R.color.zielony)
            Stan.gotowy -> getColor(R.color.pomarancz)
            else -> getColor(R.color.tekst_slaby)
        }
        val tlo = when {
            idzie -> getColor(R.color.zielony_ciemny)
            Stan.gotowy -> getColor(R.color.pomarancz_ciemny)
            else -> getColor(R.color.karta)
        }
        napisStanu.text = when {
            idzie -> "NADAJE"
            Stan.nadaje -> "ŁĄCZY SIĘ"
            Stan.gotowy -> "WSTRZYMANE"
            else -> "NIE NADAJE"
        }
        napisStanu.setTextColor(akcent)
        kartaStanu.setCardBackgroundColor(tlo)
        kartaStanu.strokeColor = akcent
        kartaStanu.strokeWidth = resources.getDimensionPixelSize(
            if (Stan.gotowy) R.dimen.obwodka_gruba else R.dimen.obwodka_cienka
        )

        wartoscPrzeplyw.text = if (idzie) "${Stan.kbs} kb/s" else "—"
        wartoscCzas.text = if (Stan.nadaje || Stan.sekund > 0) "${Stan.sekund} s" else "—"

        klawiszGlowny.text = when {
            Stan.nadaje -> "WSTRZYMAJ OBRAZ"
            Stan.gotowy -> "WZNÓW OBRAZ"
            else -> "START — NADAWAJ EKRAN"
        }
        // Zielone wypełnienie zaprasza do startu. Gdy obraz już idzie, klawisz
        // przestaje być zachętą i staje się wyjściem — stąd pomarańczowa obwódka
        // zamiast wypełnienia. Kolor nigdy nie kłamie o tym, co się stanie.
        if (Stan.nadaje) {
            klawiszGlowny.backgroundTintList = ColorStateList.valueOf(getColor(R.color.karta_jasna))
            klawiszGlowny.setTextColor(getColor(R.color.pomarancz))
            klawiszGlowny.strokeColor = ColorStateList.valueOf(getColor(R.color.pomarancz))
            klawiszGlowny.strokeWidth = resources.getDimensionPixelSize(R.dimen.obwodka_gruba)
        } else {
            klawiszGlowny.backgroundTintList = ColorStateList.valueOf(getColor(R.color.zielony))
            klawiszGlowny.setTextColor(getColor(R.color.tlo))
            klawiszGlowny.strokeWidth = 0
        }
        klawiszKoniec.visibility = if (Stan.gotowy) View.VISIBLE else View.GONE

        // Ustawienia gasną i przestają reagować, gdy obraz leci.
        val wolno = !Stan.nadaje
        for (v in listOf<View>(poleAdres, poleHaslo, wyborJakosci)) {
            v.isEnabled = wolno
            v.alpha = if (wolno) 1f else 0.45f
        }
        for (i in 0 until wyborJakosci.childCount) wyborJakosci.getChildAt(i).isEnabled = wolno

        if (Stan.ponowien > 0 && Stan.nadaje && System.currentTimeMillis() > komunikatDo) {
            szczegol.text = "${Stan.opis}  ·  ponowień łącza: ${Stan.ponowien}"
        }

        odmalujDrugaDroge()

        // Chowamy się dopiero, gdy obraz NAPRAWDĘ poszedł — inaczej pilot zostałby
        // z pustym ekranem i bez wiedzy, czy start się udał.
        // ⛔ Warunkiem jest `plynie`, nie `nadaje`: samo naciśnięcie START nie znaczy,
        // że stacja cokolwiek dostała. Gdyby aplikacja chowała się już wtedy, pilot
        // zostałby z przekonaniem, że obraz leci, podczas gdy usługa dopiero ponawia
        // próby połączenia.
        if (czekamNaStart && Stan.nadaje && Stan.plynie) {
            czekamNaStart = false
            if (przelacznikChowania.isChecked) {
                zegar.postDelayed({ if (Stan.plynie) ukryj("po starcie") }, 1200)
            }
        }
    }

    /**
     * Zdanie dla operatora w karcie stanu.
     *
     * ⛔ Bez tego meldunek ginął: przy zrywającym się łączu odmalowanie co pół
     * sekundy wpisywało z powrotem „ponawiam za N s", więc odpowiedź na WŁASNE
     * naciśnięcie znikała, zanim dało się ją przeczytać. Świeży komunikat wygrywa
     * przez [waznyPrzez] milisekund.
     */
    private fun powiedz(tekst: String, waznyPrzez: Long = 4000) {
        szczegol.text = tekst
        komunikatDo = System.currentTimeMillis() + waznyPrzez
    }

    // ---- druga droga: natywny RTMP z DJI Pilot 2 ---------------------------------

    /**
     * Adres nadawania dla Pilota 2 — złożony z **tych samych dwóch pól**, które
     * operator wypełnił dla zrzutu.
     *
     * Przeliczany przy każdym odmalowaniu (dwa razy na sekundę), więc nadąża za
     * pisaniem w polach bez podpinania nasłuchu do każdego znaku. Koszt: złożenie
     * jednego napisu.
     */
    private fun adresRtmp(): String =
        DrogaPilota.adres(DrogaPilota.host(poleAdres.text.toString()), poleHaslo.text.toString().trim())

    private fun odmalujDrugaDroge() {
        napisAdresRtmp.text = adresRtmp()

        // Podpowiedź wychodzi tylko wtedy, gdy zrzut naprawdę zawodzi. Dwa powody,
        // dwa różne zdania — operator ma wiedzieć, CO poszło źle, a nie tylko że coś.
        val powod = when {
            Stan.czern -> "Obraz wychodzi pusty (${Stan.kbs} kb/s). DJI mogło zablokować zrzut ekranu " +
                "— tego nie da się obejść z aplikacji. Czysty obraz weźmiesz drugą drogą."
            Stan.ponowien >= 3 -> "Łącze zrywa się (ponowień: ${Stan.ponowien}). " +
                "Natywna transmisja z Pilota 2 bywa odporniejsza — adres masz gotowy."
            else -> null
        }
        // Podpowiedź wjeżdża pod kartę stanu, więc bez tego wystawałaby samą krawędzią
        // — a rzecz, której nie widać, nie jest podpowiedzią. Przewijamy RAZ, w chwili
        // pojawienia się: powtarzanie co pół sekundy blokowałoby operatorowi ruch.
        val bylaWidoczna = kartaPodpowiedzi.visibility == View.VISIBLE
        kartaPodpowiedzi.visibility = if (powod == null) View.GONE else View.VISIBLE
        if (powod != null && !bylaWidoczna) {
            findViewById<View>(R.id.przewijanieStanu).post {
                (findViewById<View>(R.id.przewijanieStanu) as androidx.core.widget.NestedScrollView)
                    .fullScroll(View.FOCUS_DOWN)
            }
        }
        // Lewa kolumna nie przewija się (klawisz główny ma stać zawsze w tym samym
        // miejscu), więc miejsce na podpowiedź trzeba skądś wziąć. Bierzemy je ze
        // wskazówki na dole: ona jest poradą, a podpowiedź — stanem.
        findViewById<View>(R.id.wskazowkaWLocie).visibility =
            if (powod == null) View.VISIBLE else View.GONE
        if (powod != null) {
            napisPodpowiedzi.text = powod
            val barwa = getColor(if (Stan.czern) R.color.czerwony else R.color.pomarancz)
            kartaPodpowiedzi.strokeColor = barwa
            kartaPodpowiedzi.strokeWidth = resources.getDimensionPixelSize(R.dimen.obwodka_gruba)
        }
    }

    private fun skopiujAdres() {
        if (poleHaslo.text.isNullOrBlank()) {
            powiedz("Najpierw wpisz hasło urządzenia — bez niego adres jest bezużyteczny.")
            return
        }
        powiedz(
            if (DrogaPilota.skopiuj(this, adresRtmp()))
                "Adres w schowku. W Pilocie 2: Transmisja na żywo → RTMP → wklej."
            else "Nie udało się skopiować — adres jest wypisany obok, da się go zaznaczyć."
        )
    }

    private fun otworzPilota() {
        val pakiet = DrogaPilota.otworzPilota(this)
        powiedz(
            if (pakiet != null) "Otwieram $pakiet…"
            else "Nie znalazłem aplikacji DJI — otwórz ją ręcznie, adres masz w schowku.",
            // Dłużej niż zwykle: to jedyna informacja, że klawisz nie miał co otworzyć.
            waznyPrzez = 7000,
        )
    }

    // ---- działania --------------------------------------------------------------

    private fun nacisnietoGlowny() {
        if (Stan.gotowy) {
            val co = if (Stan.nadaje) UslugaZrzutu.AKCJA_PAUZA else UslugaZrzutu.AKCJA_WZNOW
            startService(Intent(this, UslugaZrzutu::class.java).setAction(co))
            return
        }
        zapisz()
        if (poleHaslo.text.isNullOrBlank()) {
            powiedz("Wpisz hasło urządzenia — stacja bez niego nie przyjmie obrazu.")
            return
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), PROSBA_O_EKRAN)
    }

    /**
     * Próba łącza przed lotem — samo połączenie, bez przechwytywania ekranu.
     * Sprawdzenie na ziemi jest tanie; odkrycie w powietrzu, że adres jest zły,
     * kosztuje lot.
     */
    private fun sprawdzLacze() {
        zapisz()
        val (host, port) = adresIPort()
        powiedz("Sprawdzam $host:$port…")
        thread {
            val wynik = try {
                Socket().use {
                    it.connect(InetSocketAddress(host, port), 4000)
                    "Stacja odpowiada — łącze jest."
                }
            } catch (e: Exception) {
                "Stacja nie odpowiada: ${e.message}"
            }
            runOnUiThread { powiedz(wynik) }
        }
    }

    private fun dodajKafelek() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(android.app.StatusBarManager::class.java)?.requestAddTileService(
            ComponentName(this, KafelekZrzutu::class.java),
            "Zrzut ekranu",
            android.graphics.drawable.Icon.createWithResource(
                this, android.R.drawable.presence_video_online
            ),
            {}, {},
        )
    }

    private fun adresIPort(): Pair<String, Int> {
        val cz = poleAdres.text.toString().trim().split(":")
        return cz.getOrElse(0) { "192.168.88.30" } to (cz.getOrElse(1) { "5601" }.toIntOrNull() ?: 5601)
    }

    private fun zapisz() {
        prefy.edit()
            .putString("adres", poleAdres.text.toString().trim())
            .putString("haslo", poleHaslo.text.toString().trim())
            .putInt("fps", fps)
            .putInt("bitrate", kbs)
            .putInt("podzialka", podzialka)
            .apply()
    }

    @Deprecated("Zgoda na przechwytywanie ekranu przychodzi tą drogą także dziś.")
    override fun onActivityResult(prosba: Int, wynik: Int, dane: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(prosba, wynik, dane)
        if (prosba != PROSBA_O_EKRAN) return
        if (wynik != RESULT_OK || dane == null) {
            powiedz("Bez zgody na przechwytywanie ekranu nie ma czego wysyłać.")
            return
        }
        val (host, port) = adresIPort()
        val i = Intent(this, UslugaZrzutu::class.java)
            .putExtra(UslugaZrzutu.DODATEK_KOD, wynik)
            .putExtra(UslugaZrzutu.DODATEK_DANE, dane)
            .putExtra(UslugaZrzutu.DODATEK_ADRES, host)
            .putExtra(UslugaZrzutu.DODATEK_PORT, port)
            .putExtra(UslugaZrzutu.DODATEK_HASLO, poleHaslo.text.toString().trim())
            .putExtra(UslugaZrzutu.DODATEK_FPS, fps)
            .putExtra(UslugaZrzutu.DODATEK_BITRATE, kbs * 1000)
            .putExtra(UslugaZrzutu.DODATEK_PODZIALKA, podzialka)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        czekamNaStart = true
        powiedz("Uruchamiam…")
    }

    override fun onResume() {
        super.onResume()
        val filtr = IntentFilter(UslugaZrzutu.AKCJA_STAN)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(odbiorca, filtr, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(odbiorca, filtr)
        }
        zegar.post(odswiezanie)
    }

    override fun onPause() {
        super.onPause()
        zegar.removeCallbacks(odswiezanie)
        try { unregisterReceiver(odbiorca) } catch (_: Exception) {}
    }

    private companion object {
        const val PROSBA_O_EKRAN = 21
    }
}
