"""Uruchomienie pulpitu GCS.

    python3 -m gcs_pulpit                 # pełny ekran
    GCS_PELNY_EKRAN=0 python3 -m gcs_pulpit   # w oknie, do prób
"""

from __future__ import annotations

import logging
import sys

from .okno import Aplikacja


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    return Aplikacja().run(None)


if __name__ == "__main__":
    sys.exit(main())
