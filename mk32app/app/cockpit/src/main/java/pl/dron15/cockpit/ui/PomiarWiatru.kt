package pl.dron15.cockpit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import pl.dron15.cockpit.domain.StanMaszyny
import pl.dron15.cockpit.domain.Wiatr

/**
 * Uśrednianie przechyłu do oceny wiatru — osobno, żeby ekran LOT zyskał na tym
 * **jedną linijkę**, a nie bufor, efekt i pół ekranu logiki.
 *
 * Bufor mieszka w `remember`, więc przeżywa rekompozycje, a znika razem z ekranem.
 * Zerujemy go przy rozbrojeniu: przechył maszyny stojącej na ziemi to trym i nierówny
 * grunt, nie wiatr, i nie ma po co mieszać tego do średniej z lotu.
 *
 * ⚠ Próbkujemy w `LaunchedEffect(teraz)`, czyli w rytmie zegara aplikacji (1 Hz),
 * a **nie** w kompozycji. Mutowanie wspólnego bufora w trakcie kompozycji było już
 * raz błędem w tym projekcie — detektor spadku satelitów, `dok/AUDYT_M3.md` S5.
 */
@Composable
fun pamietajWiatr(stan: StanMaszyny, teraz: Long): Wiatr.Ocena {
    val bufor = remember { Wiatr.Bufor() }
    var ocena by remember { mutableStateOf(Wiatr.Ocena()) }

    LaunchedEffect(teraz) {
        val s = Wiatr.skladowe(stan)
        if (s == null) {
            // Maszyna leci albo stoi — w obu przypadkach przechył nie mówi o wietrze.
            if (!stan.uzbrojony) {
                bufor.wyczysc()
                ocena = Wiatr.Ocena()
            }
        } else {
            bufor.dodaj(teraz, s.first, s.second)
            bufor.srednia()?.let { (w, n) -> ocena = Wiatr.ocen(w, n) }
        }
    }

    return ocena
}
