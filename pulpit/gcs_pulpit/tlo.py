"""Tło stanowiska — to, co widać POD aplikacjami.

⛔ Powód powstania, 2026-08-29: po uruchomieniu Firefoksa spod naszego pulpitu
na ekranie było widać pasek zadań maliny (`wf-panel-pi`) i jej pulpit
(`pcmanfm --desktop`) z ikonami z `~/Desktop`. Aplikacja zewnętrzna nie zajmuje
całego ekranu, więc wszystko, czego nie zasłoni, jest widoczne — a to ma być
stanowisko naziemne, nie biurko z systemem operacyjnym.

Ustalenie Toma: **tapeta może być, reszta nie.** Więc gasimy tamte dwa procesy
(`rpi/gcs-otoczenie`), a w ich miejsce kładziemy własną warstwę tła.

Dlaczego własna, a nie `swaybg`: na tej malinie nie ma ani `swaybg`, ani `wbg`,
ani `mpvpaper` (sprawdzone). Mamy za to `gtk4-layer-shell`, którym i tak stoi
klawiatura — koszt to jedno okno, zero nowych pakietów.

Warstwa BACKGROUND leży w wlroots **pod wszystkimi zwykłymi oknami**, więc ani
nasz pulpit, ani aplikacje nie są przez nią zasłaniane.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Gtk4LayerShell", "1.0")

from gi.repository import Gdk, Gtk  # noqa: E402
from gi.repository import Gtk4LayerShell as Warstwa  # noqa: E402

log = logging.getLogger("gcs.pulpit.tlo")

# Tapeta Toma z PI5setup. Podmiana bez zmiany kodu: GCS_TAPETA=/sciezka/obraz.png
TAPETA = Path(
    os.getenv("GCS_TAPETA", "/opt/pi5setup-full/boot-splashes/start-1.png")
)

STYL = """
.tlo-gcs { background-color: #0d0f12; }
.tlo-znak {
    color: rgba(255, 255, 255, 0.10);
    font-size: 64px;
    font-weight: 800;
    letter-spacing: 12px;
}
"""


class Tlo(Gtk.Window):
    """Pełnoekranowa warstwa tła. Nie przyjmuje ani klawiatury, ani kliknięć."""

    def __init__(self) -> None:
        super().__init__(title="GCS — tło")
        self.add_css_class("tlo-gcs")
        self._jako_warstwa()
        self.set_child(self._zawartosc())

    def _jako_warstwa(self) -> None:
        Warstwa.init_for_window(self)
        # BACKGROUND, nie BOTTOM: BOTTOM leży nad tłem kompozytora, ale wciąż
        # potrafi przykryć panele. Tło ma być najniżej, jak się da.
        Warstwa.set_layer(self, Warstwa.Layer.BACKGROUND)
        for krawedz in (
            Warstwa.Edge.TOP,
            Warstwa.Edge.BOTTOM,
            Warstwa.Edge.LEFT,
            Warstwa.Edge.RIGHT,
        ):
            Warstwa.set_anchor(self, krawedz, True)
        # ⛔ NONE i strefa −1: tło nie może odbierać ogniska ani rezerwować
        # miejsca, bo wtedy okna aplikacji zostałyby zepchnięte na bok.
        Warstwa.set_keyboard_mode(self, Warstwa.KeyboardMode.NONE)
        Warstwa.set_exclusive_zone(self, -1)

    def _zawartosc(self) -> Gtk.Widget:
        if TAPETA.is_file():
            obraz = Gtk.Picture.new_for_filename(str(TAPETA))
            # COVER wypełnia ekran bez pasów; przycięcie jest tu mniejszym złem
            # niż czarne obwódki przy innym stosunku boków.
            obraz.set_content_fit(Gtk.ContentFit.COVER)
            obraz.set_can_focus(False)
            log.info("Tło: %s", TAPETA)
            return obraz

        # Brak pliku nie może zostawić dziury — wtedy widać kompozytor.
        log.warning("Brak tapety %s — kładę samo tło z napisem", TAPETA)
        znak = Gtk.Label(label="GCS")
        znak.add_css_class("tlo-znak")
        znak.set_valign(Gtk.Align.CENTER)
        znak.set_halign(Gtk.Align.CENTER)
        return znak


def wlacz() -> Tlo | None:
    """Kładzie tło i zwraca okno. `None`, gdy kompozytor nie ma layer-shell."""
    try:
        dostawca = Gtk.CssProvider()
        dostawca.load_from_data(STYL.encode("utf-8"))
        Gtk.StyleContext.add_provider_for_display(
            Gdk.Display.get_default(),
            dostawca,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )
        tlo = Tlo()
        tlo.present()
        return tlo
    except Exception as blad:  # noqa: BLE001 — brak tła nie może zabić pulpitu
        log.warning("Nie udało się położyć tła: %s", blad)
        return None
