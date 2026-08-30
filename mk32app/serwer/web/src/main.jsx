import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import OknoOsobne from './OknoOsobne.jsx'
import { czegoDotyczy } from './oknoOsobneAdres.js'

// Adres `?okno=mapa` albo `?okno=obraz` znaczy: to jest okno oddokowane na inny
// monitor, nie pełna aplikacja. Rozgałęzienie jest tutaj, a nie w App, żeby okno
// nie ciągnęło ze sobą HUD-u, paska i paneli, których nie pokazuje.
const osobne = czegoDotyczy()

createRoot(document.getElementById('root')).render(
  <StrictMode>
    {osobne ? <OknoOsobne okno={osobne.okno} zrodlo={osobne.zrodlo} /> : <App />}
  </StrictMode>,
)

// Service worker — tylko powłoka aplikacji, patrz public/sw.js i dok/TELEFON.md §4.
//
// isSecureContext jest tu warunkiem, nie ostrożnością: stacja chodzi po http://
// (dok/SERWER_PODGLADU.md §9 — świadoma decyzja, bariera stoi na WireGuardzie),
// a przeglądarka odmawia rejestracji poza HTTPS i localhostem. Bez tego warunku
// w konsoli telefonu w polu leżałby błąd wyglądający na awarię, którą nie jest.
if ('serviceWorker' in navigator && window.isSecureContext) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((e) => {
      console.warn('Nie udało się zarejestrować service workera:', e)
    })
  })
}
