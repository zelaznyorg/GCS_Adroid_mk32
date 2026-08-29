from __future__ import annotations

import importlib.machinery
import importlib.util
import io
import sys
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SCIEZKA = ROOT / "pulpit" / "rpi" / "gcs-siec"


def wczytaj_modul():
    loader = importlib.machinery.SourceFileLoader("gcs_siec", str(SCIEZKA))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    assert spec is not None
    modul = importlib.util.module_from_spec(spec)
    loader.exec_module(modul)
    return modul


class GcsSiecTest(unittest.TestCase):
    def setUp(self) -> None:
        self.modul = wczytaj_modul()

    def test_zwykla_siec_nie_dostaje_flagi_hidden(self) -> None:
        with (
            mock.patch.object(sys, "argv", ["gcs-siec", "connect", "Siec polowa"]),
            mock.patch.object(sys, "stdin", io.StringIO("tajne-haslo\n")),
            mock.patch.object(self.modul, "uruchom", return_value=0) as uruchom,
        ):
            self.assertEqual(self.modul.main(), 0)

        uruchom.assert_called_once_with(
            ["device", "wifi", "connect", "Siec polowa", "password", "tajne-haslo"]
        )

    def test_ukryta_siec_dostaje_flage_hidden(self) -> None:
        with (
            mock.patch.object(sys, "argv", ["gcs-siec", "connect-hidden", "Siec ukryta"]),
            mock.patch.object(sys, "stdin", io.StringIO("tajne-haslo\n")),
            mock.patch.object(self.modul, "uruchom", return_value=0) as uruchom,
        ):
            self.assertEqual(self.modul.main(), 0)

        uruchom.assert_called_once_with(
            [
                "device",
                "wifi",
                "connect",
                "Siec ukryta",
                "password",
                "tajne-haslo",
                "hidden",
                "yes",
            ]
        )

    def test_puste_ssid_jest_odrzucane(self) -> None:
        with mock.patch.object(sys, "stderr", io.StringIO()):
            with self.assertRaises(SystemExit):
                self.modul.sprawdz_ssid("   ")


if __name__ == "__main__":
    unittest.main()
