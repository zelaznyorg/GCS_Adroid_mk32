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
    private val odbiorca = object : BroadcastReceiver() {
        override fun onReceive(k: Context?, i: Intent?) {
            i?.getStringExtra(UslugaZrzutu.DODATEK_STAN)?.let { szczegol.text = it }
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
            szczegol.text = "Zakończone — kolejny start znów poprosi o zgodę."
        }
        findViewById<MaterialButton>(R.id.klawiszProba).setOnClickListener { sprawdzLacze() }

        wyborJakosci.check(idJakosci())
        wyborJakosci.addOnButtonCheckedListener { _, id, zaznaczony ->
            if (!zaznaczony) return@addOnButtonCheckedListener
            // ⛔ W trakcie nadawania ustawień nie ruszamy: zmiana w locie znaczyłaby
            // zerwanie i odtworzenie łącza w najgorszym możliwym momencie.
            if (Stan.nadaje) {
                szczegol.text = "Najpierw wstrzymaj obraz — w trakcie nadawania ustawienia są zamknięte."
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
        szczegol.text = "Ukryte ($powod) — obraz leci dalej."
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
        val akcent = when {
            Stan.nadaje -> getColor(R.color.zielony)
            Stan.gotowy -> getColor(R.color.pomarancz)
            else -> getColor(R.color.tekst_slaby)
        }
        val tlo = when {
            Stan.nadaje -> getColor(R.color.zielony_ciemny)
            Stan.gotowy -> getColor(R.color.pomarancz_ciemny)
            else -> getColor(R.color.karta)
        }
        napisStanu.text = when {
            Stan.nadaje -> "NADAJE"
            Stan.gotowy -> "WSTRZYMANE"
            else -> "NIE NADAJE"
        }
        napisStanu.setTextColor(akcent)
        kartaStanu.setCardBackgroundColor(tlo)
        kartaStanu.strokeColor = akcent
        kartaStanu.strokeWidth = resources.getDimensionPixelSize(
            if (Stan.gotowy) R.dimen.obwodka_gruba else R.dimen.obwodka_cienka
        )

        wartoscPrzeplyw.text = if (Stan.nadaje) "${Stan.kbs} kb/s" else "—"
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

        if (Stan.ponowien > 0 && Stan.nadaje) {
            szczegol.text = "${Stan.opis}  ·  ponowień łącza: ${Stan.ponowien}"
        }

        // Chowamy się dopiero, gdy obraz NAPRAWDĘ poszedł — inaczej pilot zostałby
        // z pustym ekranem i bez wiedzy, czy start się udał.
        if (czekamNaStart && Stan.nadaje) {
            czekamNaStart = false
            if (przelacznikChowania.isChecked) {
                zegar.postDelayed({ if (Stan.nadaje) ukryj("po starcie") }, 1200)
            }
        }
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
            szczegol.text = "Wpisz hasło urządzenia — stacja bez niego nie przyjmie obrazu."
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
        szczegol.text = "Sprawdzam $host:$port…"
        thread {
            val wynik = try {
                Socket().use {
                    it.connect(InetSocketAddress(host, port), 4000)
                    "Stacja odpowiada — łącze jest."
                }
            } catch (e: Exception) {
                "Stacja nie odpowiada: ${e.message}"
            }
            runOnUiThread { szczegol.text = wynik }
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
            szczegol.text = "Bez zgody na przechwytywanie ekranu nie ma czego wysyłać."
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
        szczegol.text = "Uruchamiam…"
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
