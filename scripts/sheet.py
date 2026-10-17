#!/usr/bin/env python3
"""Склеивает скриншоты shots/<префикс>-*.png в одну контактную карту: sheet.py <префикс> [колонки]"""
import glob, sys
from PIL import Image
prefix = sys.argv[1]; cols = int(sys.argv[2]) if len(sys.argv) > 2 else 2
files = sorted(glob.glob(f"shots/{prefix}-*.png"))
imgs = [Image.open(f).convert("RGB") for f in files]
w, h = imgs[0].size; rows = (len(imgs) + cols - 1) // cols
sheet = Image.new("RGB", (cols * w + (cols - 1) * 6, rows * h + (rows - 1) * 6), (60, 60, 60))
for i, im in enumerate(imgs):
    sheet.paste(im, ((i % cols) * (w + 6), (i // cols) * (h + 6)))
out = f"shots/_sheet-{prefix}.png"; sheet.save(out); print(out, len(imgs))
