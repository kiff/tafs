#!/usr/bin/env python3
"""The tafs icon with no background: docs/brand/tafs-icon-transparent-1024.png, and the icons made from it.

The brand artwork (docs/brand/tafs-icon-source.png) is the character on a flat cream (251, 247, 238), and has no
transparent master. This makes one from it by difference matting, not by keying a colour out:

  1. The exterior is the near-cream pixels (within 7 of the cream) connected to the image's border. Only those: a
     near-cream pixel the border can't reach (the character's white face) is the character.
  2. A pixel within 3 px of the exterior is partly cream: its alpha is how far it is from the cream, over how far the
     solid colour next to it is, and its colour is the solid colour next to it spread outwards, so no cream is left in
     the edge (unmixing the cream out instead leaves a dark line where the white face ends).
  3. The character, cropped to its pixels, is centred on a square 1024 canvas with a 12 px margin all round.

The icons are the master resized with premultiplied alpha, so a resized edge carries no fringe. Run from the repo's
root (needs Pillow and numpy; nothing else): python3 scripts/brand_transparent.py. It writes the master, the four
chrome/icons/icon-<size>.png, and, with --cloud DIR, DIR/tafs-icon-512.png (kiff/cloud's
web/internal/assets/static/img/tafs/). The same input gives the same files, byte for byte.
"""
import argparse
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

CREAM = np.array([251.0, 247.0, 238.0])
NEAR = 7.0  # how far from the cream a pixel is still the background
BAND = 3  # px of partly covered edge
MARGIN = 12  # px of the 1024 master left empty all round
SIZES = (16, 32, 48, 128)


def matte(source: Image.Image) -> Image.Image:
    a = np.asarray(source.convert("RGB")).astype(np.float64)
    h, w, _ = a.shape
    d = np.sqrt(((a - CREAM) ** 2).sum(axis=2))
    near = d < NEAR
    exterior = np.zeros((h, w), bool)
    queue = deque()
    for x in range(w):
        for y in (0, h - 1):
            if near[y, x] and not exterior[y, x]:
                exterior[y, x] = True
                queue.append((y, x))
    for y in range(h):
        for x in (0, w - 1):
            if near[y, x] and not exterior[y, x]:
                exterior[y, x] = True
                queue.append((y, x))
    while queue:
        y, x = queue.popleft()
        for ny, nx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
            if 0 <= ny < h and 0 <= nx < w and near[ny, nx] and not exterior[ny, nx]:
                exterior[ny, nx] = True
                queue.append((ny, nx))
    grown = np.asarray(Image.fromarray((exterior * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(2 * BAND + 1))) > 0
    band = grown & ~exterior
    solid = np.where(~grown, d, 0).astype(np.float32)
    reference = np.asarray(Image.fromarray(solid, "F").filter(ImageFilter.MaxFilter(11)))
    alpha = np.ones((h, w))
    alpha[exterior] = 0
    alpha[band] = np.clip(d[band] / np.maximum(reference[band], 8.0), 0, 1)
    # The edge takes its colour from the solid colour next to it, spread outwards a pixel at a time: unmixing the cream
    # out of a pixel that is mostly cream divides by almost nothing and leaves a dark line where the white face ends.
    colour = a.copy()
    known = ~grown
    for _ in range(BAND + 1):
        weight = box(known.astype(np.float64))
        total = box(colour * known[..., None])
        fill = (~known) & (weight > 0)
        colour[fill] = total[fill] / weight[fill][:, None]
        known = known | fill
    return Image.fromarray(np.dstack([colour, alpha * 255]).round().astype(np.uint8), "RGBA")


def box(plane: np.ndarray) -> np.ndarray:
    """The sum of a plane (or a stack of planes) over each pixel's 3x3 neighbourhood."""
    padded = np.pad(plane, ((1, 1), (1, 1)) + ((0, 0),) * (plane.ndim - 2))
    h, w = plane.shape[:2]
    return sum(padded[dy : dy + h, dx : dx + w] for dy in range(3) for dx in range(3))


def master(matted: Image.Image) -> Image.Image:
    art = matted.crop(matted.getchannel("A").getbbox())
    fit = 1024 - 2 * MARGIN
    scale = fit / max(art.size)
    art = resize(art, (round(art.width * scale), round(art.height * scale)))
    canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    canvas.alpha_composite(art, ((1024 - art.width) // 2, (1024 - art.height) // 2))
    return canvas


def resize(image: Image.Image, size) -> Image.Image:
    """Resized in premultiplied alpha: a transparent pixel's colour never leaks into an edge."""
    return image.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cloud", help="also write tafs-icon-512.png into this directory (kiff/cloud's web/internal/assets/static/img/tafs)")
    args = ap.parse_args()
    root = Path(__file__).resolve().parent.parent
    brand = root / "docs" / "brand"
    top = master(matte(Image.open(brand / "tafs-icon-source.png")))
    top.save(brand / "tafs-icon-transparent-1024.png", optimize=True)
    for size in SIZES:
        resize(top, (size, size)).save(root / "chrome" / "icons" / f"icon-{size}.png", optimize=True)
    if args.cloud:
        resize(top, (512, 512)).save(Path(args.cloud) / "tafs-icon-512.png", optimize=True)


if __name__ == "__main__":
    main()
