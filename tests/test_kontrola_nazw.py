from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from narzedzia.kontrola_nazw import nieznane_nazwy


class KontrolaNazwTest(unittest.TestCase):
    def sprawdz(self, kod: str) -> dict[str, int]:
        with tempfile.TemporaryDirectory() as katalog:
            sciezka = Path(katalog) / "modul.py"
            sciezka.write_text(kod, encoding="utf-8")
            return nieznane_nazwy(str(sciezka))

    def test_standardowe_atrybuty_modulu_sa_znane(self) -> None:
        self.assertEqual(self.sprawdz("print(__file__, __name__)\n"), {})

    def test_niezdefiniowana_nazwa_jest_wykrywana(self) -> None:
        self.assertEqual(self.sprawdz("print(BRAKUJE)\n"), {"BRAKUJE": 1})


if __name__ == "__main__":
    unittest.main()
