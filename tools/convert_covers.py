#!/usr/bin/env python3
"""
tools/convert_covers.py

Converts every JPEG/PNG in app/src/main/res/drawable/ to WebP and moves the
result into app/src/main/res/drawable-nodpi/, keeping the same base file name so
R.drawable.* references stay valid.

- JPEG (.jpg/.jpeg) -> lossy WebP, quality 80
- PNG               -> lossless WebP (preserves alpha, e.g. halftone_dot.png)
- GIF and XML vector drawables are left untouched.

Run from the project root:  python tools/convert_covers.py
"""
from pathlib import Path
try:
    from PIL import Image
except ImportError:
    raise SystemExit("Pillow is required: pip install Pillow")

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main" / "res" / "drawable"
DST = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
DST.mkdir(parents=True, exist_ok=True)

converted, skipped = 0, 0
for path in sorted(SRC.iterdir()):
    ext = path.suffix.lower()
    if ext not in (".jpg", ".jpeg", ".png"):
        skipped += 1
        continue
    out = DST / (path.stem + ".webp")
    with Image.open(path) as im:
        if ext == ".png":
            im.save(out, "WEBP", lossless=True, method=6)
        else:
            im.convert("RGB").save(out, "WEBP", quality=80, method=6)
    path.unlink()  # remove original so there is no duplicate resource name
    converted += 1
    print(f"  {path.name} -> drawable-nodpi/{out.name}")

print(f"\nConverted {converted} image(s); left {skipped} non-raster file(s) untouched.")
