"""python3 -m unittest chrome/store/test_icons.py

The extension's icons have no background (docs/brand/README.md, scripts/brand_transparent.py): Chrome draws them on its
toolbar, light or dark, and an opaque square shows as a cream tile (the owner saw it, 23 Nov). The check reads the PNGs
with the standard library alone: each is 8-bit RGBA, its corners are transparent and its middle is solid.
"""
import struct
import unittest
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
SIZES = (16, 32, 48, 128)


def read_png(path: Path):
    """(width, height, rows of RGBA bytes) of an 8-bit RGBA PNG that isn't interlaced; raises for anything else."""
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"{path.name} is no PNG"
    at, idat, header = 8, b"", None
    while at < len(data):
        length, kind = struct.unpack(">I4s", data[at : at + 8])
        body = data[at + 8 : at + 8 + length]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        at += 12 + length
    width, height, depth, colour, _, _, interlace = header
    assert (depth, colour, interlace) == (8, 6, 0), f"{path.name}: 8-bit RGBA, not interlaced; it is depth {depth}, colour type {colour}, interlace {interlace}"
    raw = zlib.decompress(idat)
    stride = width * 4
    rows, previous = [], bytearray(stride)
    for y in range(height):
        kind = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1 : (y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - 4] if i >= 4 else 0
            b = previous[i]
            c = previous[i - 4] if i >= 4 else 0
            if kind == 1:
                line[i] = (line[i] + a) & 255
            elif kind == 2:
                line[i] = (line[i] + b) & 255
            elif kind == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif kind == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        previous = line
    return width, height, rows


def alpha(rows, x, y):
    return rows[y][x * 4 + 3]


class IconsHaveNoBackground(unittest.TestCase):
    def check(self, path: Path, size: int):
        width, height, rows = read_png(path)
        self.assertEqual((width, height), (size, size), path.name)
        for x, y in ((0, 0), (size - 1, 0), (0, size - 1), (size - 1, size - 1)):
            # A corner is empty, give or take what resizing leaves in the smallest.
            self.assertLessEqual(alpha(rows, x, y), 8, f"{path.name}: the pixel at {x},{y} is not transparent")
        # The character is solid where it is: a pixel at the middle of its face.
        self.assertGreaterEqual(alpha(rows, size * 6 // 10, size * 4 // 10), 250, f"{path.name}: nothing solid at the character's face")
        # And a good part of the square is empty: not a tile with the character on it.
        clear = sum(1 for row in rows for x in range(size) if row[x * 4 + 3] < 8)
        self.assertGreater(clear / (size * size), 0.15, f"{path.name}: it is nearly all solid, as a square is")

    def test_the_extensions_icons(self):
        for size in SIZES:
            with self.subTest(size=size):
                self.check(ROOT / "chrome" / "icons" / f"icon-{size}.png", size)

    def test_the_master_they_are_made_from(self):
        self.check(ROOT / "docs" / "brand" / "tafs-icon-transparent-1024.png", 1024)

    def test_the_manifest_names_the_four_icons(self):
        manifest = (ROOT / "chrome" / "manifest.json").read_text()
        for size in SIZES:
            self.assertIn(f"icons/icon-{size}.png", manifest)


if __name__ == "__main__":
    unittest.main()
