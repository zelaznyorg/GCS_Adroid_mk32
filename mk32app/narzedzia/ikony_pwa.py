#!/usr/bin/env python3
# Ikony aplikacji podglądu na telefon.
#
# Rysuje sylwetkę Quad X — dokładnie tę maszynę, cztery ramiona, nie osiem
# (patrz ..\..\CLAUDE.md §1, korekta FRAME_CLASS z 2026-08-15). Ikona ma być
# rozpoznawalna jako kwadrat 32 px na pasku zadań i jako 512 px na pulpicie
# telefonu, więc jest tylko z grubych kresek, bez cieni i gradientów.
#
# Kolory z systemu projektowego (dok/UI.md §2): tło #07090b, akcent cyan #35C7E8.
# Cyan wyłącznie dlatego, że jest to zaznaczenie/tożsamość, a nie stan — bursztyn
# i czerwień są zarezerwowane dla ostrzeżenia i blokady i w ikonie ich nie ma.
#
# Wynik trafia do serwer/web/public i stamtąd Vite kopiuje go do dist.
#
#   python narzedzia\ikony_pwa.py
#
# Rysujemy 8x większe i zmniejszamy filtrem LANCZOS — Pillow nie ma wygładzania
# krawędzi przy rysowaniu, a bez tego ramiona pod 45° są schodkowe.
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("Brak biblioteki Pillow. Zainstaluj: pip install Pillow")
    sys.exit(1)

KATALOG = Path(__file__).resolve().parent.parent / "serwer" / "web" / "public"

TLO = (7, 9, 11, 255)
AKCENT = (53, 199, 232, 255)
NADPROBKOWANIE = 8


def rysuj(bok: int, margines: float) -> Image.Image:
    """Kwadratowa ikona o boku `bok`. `margines` to ułamek boku zostawiony pusty
    dookoła — dla ikony maskowalnej Android obcina do 80 %, więc glif musi się
    zmieścić w kole o średnicy 80 % boku."""
    b = bok * NADPROBKOWANIE
    obraz = Image.new("RGBA", (b, b), TLO)
    d = ImageDraw.Draw(obraz)

    srodek = b / 2
    zasieg = srodek * (1 - 2 * margines)      # od środka do osi wirnika
    promien = zasieg * 0.30                   # promień pierścienia wirnika
    kresba = max(2.0, b * 0.026)              # grubość linii

    przekatna = zasieg * 0.7071               # składowa dla kąta 45°
    ramiona = [(+1, -1), (-1, -1), (-1, +1), (+1, +1)]

    for zx, zy in ramiona:
        x = srodek + zx * przekatna
        y = srodek + zy * przekatna
        d.line([srodek, srodek, x, y], fill=AKCENT, width=int(kresba))
        d.ellipse(
            [x - promien, y - promien, x + promien, y + promien],
            outline=AKCENT,
            width=int(kresba),
        )

    # Piasta — pełny kwadrat, bo płyta FC jest kwadratowa (30,5 × 30,5 mm).
    piasta = zasieg * 0.13
    d.rectangle(
        [srodek - piasta, srodek - piasta, srodek + piasta, srodek + piasta],
        fill=AKCENT,
    )

    # Znacznik przodu. Bez niego ikona jest symetryczna i nie widać, gdzie nos —
    # a na tej maszynie kierunek jest tematem osobnym (mapowanie silników).
    dziob = zasieg * 0.42
    d.line(
        [
            srodek - dziob * 0.55, srodek - dziob,
            srodek, srodek - dziob * 1.42,
            srodek + dziob * 0.55, srodek - dziob,
        ],
        fill=AKCENT,
        width=int(kresba),
        joint="curve",
    )

    return obraz.resize((bok, bok), Image.LANCZOS)


def svg() -> str:
    """Ta sama sylwetka jako SVG — favicon i ikona w zakładkach przeglądarki."""
    s, z, p, k = 256, 256 / 2 * 0.78, 0, 6.6
    r = z * 0.30
    d = z * 0.7071
    c = 128
    kola, linie = [], []
    for zx, zy in ((1, -1), (-1, -1), (-1, 1), (1, 1)):
        x, y = c + zx * d, c + zy * d
        linie.append(f'<line x1="{c}" y1="{c}" x2="{x:.1f}" y2="{y:.1f}"/>')
        kola.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}"/>')
    piasta = z * 0.13
    dziob = z * 0.42
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {s} {s}" width="{s}" height="{s}">\n'
        f'  <rect width="{s}" height="{s}" fill="#07090b"/>\n'
        f'  <g stroke="#35C7E8" stroke-width="{k}" fill="none" stroke-linecap="round">\n'
        f'    {"".join(linie)}\n    {"".join(kola)}\n'
        f'    <polyline points="{c - dziob * 0.55:.1f},{c - dziob:.1f} {c},{c - dziob * 1.42:.1f} '
        f'{c + dziob * 0.55:.1f},{c - dziob:.1f}" stroke-linejoin="round"/>\n'
        f'  </g>\n'
        f'  <rect x="{c - piasta:.1f}" y="{c - piasta:.1f}" width="{piasta * 2:.1f}" '
        f'height="{piasta * 2:.1f}" fill="#35C7E8"/>\n'
        f'</svg>\n'
    )


def main() -> int:
    KATALOG.mkdir(parents=True, exist_ok=True)

    # 0,11 marginesu dla ikony zwykłej — glif prawie na krawędź.
    # 0,22 dla maskowalnej — Android obcina do 80 % i zaokrągla, patrz
    # https://w3c.github.io/manifest/#icon-masks (strefa bezpieczna 40 % promienia).
    plany = [
        ("ikona-192.png", 192, 0.11),
        ("ikona-512.png", 512, 0.11),
        ("ikona-maskowalna-512.png", 512, 0.22),
        ("ikona-180.png", 180, 0.11),   # apple-touch-icon
    ]
    for nazwa, bok, margines in plany:
        sciezka = KATALOG / nazwa
        rysuj(bok, margines).save(sciezka, "PNG")
        print(f"zapisano {sciezka.relative_to(KATALOG.parents[3])} ({bok}x{bok})")

    favicon = KATALOG / "favicon.svg"
    favicon.write_text(svg(), encoding="utf-8")
    print(f"zapisano {favicon.relative_to(KATALOG.parents[3])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
