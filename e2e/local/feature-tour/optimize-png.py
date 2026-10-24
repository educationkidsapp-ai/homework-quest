#!/usr/bin/env python3
"""Shrinks the feature-tour screenshots in place: a 256-colour palette (median cut, no dithering) and zlib's best.

UI screenshots are flat colour and text, so the palette is invisible at 100% and the files drop to roughly a third.
An image that would not get smaller is left alone, and running it twice changes nothing.

    python3 e2e/local/feature-tour/optimize-png.py docs/screenshots/feature-tour/dashboard
"""
import os
import sys
import tempfile

from PIL import Image

root = sys.argv[1] if len(sys.argv) > 1 else "docs/screenshots/feature-tour/dashboard"
before = after = 0
for folder, _, files in os.walk(root):
    for name in sorted(files):
        if not name.endswith(".png"):
            continue
        path = os.path.join(folder, name)
        size = os.path.getsize(path)
        with Image.open(path) as image:
            if image.mode == "P":  # already a palette image: optimised on an earlier run
                before += size
                after += size
                continue
            quantised = image.convert("RGB").quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
        handle, scratch = tempfile.mkstemp(suffix=".png", dir=folder)
        os.close(handle)
        quantised.save(scratch, optimize=True)
        if os.path.getsize(scratch) < size:
            os.replace(scratch, path)
        else:
            os.remove(scratch)
        before += size
        after += os.path.getsize(path)
print(f"{root}: {before / 1e6:.1f} MB -> {after / 1e6:.1f} MB")
