"""Wspólne zachowania list sterowanych pokrętłem.

Przy myszy widać, gdzie się jest, bo się tam kliknęło. Przy pokrętle zaznaczenie
wędruje samo i **musi za nim wędrować widok** — inaczej po kilkunastu zaskokach
operator patrzy na listę, na której nic nie jest podświetlone, i nie wie, co
zatwierdzi klikiem.

`Gtk.ScrolledWindow` sam tego nie zrobi: przesuwa się za **ogniskiem klawiatury**,
a my ognisk nie używamy — zaznaczenie jest naszą własną klasą CSS.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import GLib, Gtk  # noqa: E402

MARGINES_PX = 24


def przewin_do(
    przewijak: Gtk.ScrolledWindow, wnetrze: Gtk.Widget, zaznaczony: Gtk.Widget
) -> None:
    """Dosuwa widok tak, żeby zaznaczona pozycja była cała widoczna.

    Wywoływane po każdej zmianie zaznaczenia. Liczenie idzie przez `idle_add`,
    bo tuż po przebudowie listy widgety nie mają jeszcze przydzielonego miejsca
    i `compute_bounds` zwróciłby nieprawdę.
    """

    def policz() -> bool:
        udalo, prostokat = zaznaczony.compute_bounds(wnetrze)
        if not udalo:
            return False
        suwak = przewijak.get_vadjustment()
        if suwak is None:
            return False

        gora = prostokat.origin.y
        dol = gora + prostokat.size.height
        widok_gora = suwak.get_value()
        widok_dol = widok_gora + suwak.get_page_size()

        if gora - MARGINES_PX < widok_gora:
            suwak.set_value(max(suwak.get_lower(), gora - MARGINES_PX))
        elif dol + MARGINES_PX > widok_dol:
            docelowe = dol + MARGINES_PX - suwak.get_page_size()
            suwak.set_value(min(suwak.get_upper() - suwak.get_page_size(), docelowe))
        return False

    GLib.idle_add(policz)
