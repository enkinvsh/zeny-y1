#!/usr/bin/env python3
"""Пересобирает logo.bin (раздел LOGO загрузчика LK) в стиле zeny: work/logo-zeny.bin.

Формат проверен на стоковом дампе (round-trip байт-в-байт): заголовок 0x58881688, размер тела, 'LOGO' и нули
до 0x200; тело = [n][общий размер][n смещений], дальше zlib-потоки RGB565 little-endian. Размер раздела
3 МБ, хвост после тела копируется из стока, чтобы длина файла не менялась.

Что меняем (номера картинок из стока):
  0 и 38 - логотип загрузки (был «innioasis» на синем): колода zeny с роботом на чёрном, как финал bootanimation;
  1, 2, 25..35, 37 - батарея: синий -> зелёный zeny, оранжевый -> жёлтый zeny (геометрия не меняется:
  цифры процентов LK рисует поверх по фиксированным координатам);
  3 - ошибка зарядки: та же иконка, подпись по-русски.
Цифровые спрайты 4..24 и 36 не трогаем.
Запуск (нужны numpy и pillow): python3 scripts/make-logo.py; путь к стоковому logo.bin - в ZENY_Y1_STOCK_LOGO.
"""
import colorsys
import importlib.util
import os
import struct
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
# Стоковый дамп раздела LOGO снимается с самого плеера (docs/DEVICE.md) и в репозиторий не входит.
STOCK = os.environ.get(
    "ZENY_Y1_STOCK_LOGO",
    os.path.join(os.path.dirname(ROOT), "zeny-y1-backup/stock-2026-10-16/logo.bin"),
)
OUT = os.path.join(ROOT, "work/logo-zeny.bin")
PREVIEW = os.path.join(ROOT, "work/logo-zeny-preview")
SW, SH = 480, 360
FULL = SW * SH * 2

MAGIC = 0x58881688
BODY_AT = 0x200

BATTERY_IMAGES = [1, 2] + list(range(25, 36)) + [37]
BOOT_IMAGES = [0, 38]
ERROR_IMAGE = 3

ONEST = os.path.join(ROOT, "theme/zeny/Onest-SemiBold.ttf")


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, filename))
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def parse(data):
    magic, size = struct.unpack_from("<II", data, 0)
    if magic != MAGIC or data[8:12] != b"LOGO":
        raise SystemExit("это не logo.bin LK: magic=%#x" % magic)
    body = data[BODY_AT:]
    n, total = struct.unpack_from("<II", body, 0)
    if total != size:
        raise SystemExit("размер в заголовке (%d) не равен размеру тела (%d)" % (size, total))
    offsets = struct.unpack_from("<%dI" % n, body, 8)
    raws = [zlib.decompressobj().decompress(body[o:]) for o in offsets]
    return raws


def build(raws, stock):
    """Собирает файл раздела из набора несжатых картинок; длину раздела сохраняет."""
    blobs = [zlib.compress(raw, 9) for raw in raws]
    n = len(blobs)
    head = 8 + 4 * n
    offsets, pos = [], head
    for blob in blobs:
        offsets.append(pos)
        pos += len(blob)
    body = struct.pack("<II", n, pos) + struct.pack("<%dI" % n, *offsets) + b"".join(blobs)
    # заполнитель между 'LOGO' и телом в стоке не нулевой (местами 0xFF), поэтому берём его как есть
    out = struct.pack("<II", MAGIC, len(body)) + stock[8:BODY_AT] + body
    if len(out) > len(stock):
        raise SystemExit("новый logo.bin (%d) не влезает в раздел (%d)" % (len(out), len(stock)))
    return out + stock[len(out):]


def to_image(raw):
    v = np.frombuffer(raw, dtype="<u2").reshape(SH, SW).astype(np.uint32)
    r, g, b = (v >> 11) & 31, (v >> 5) & 63, v & 31
    rgb = np.stack([(r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)], axis=-1)
    return Image.fromarray(rgb.astype(np.uint8), "RGB")


def to_raw(img):
    """RGB -> RGB565 LE с упорядоченным дизерингом: на плавных градиентах робота иначе видны полосы."""
    a = np.asarray(img.convert("RGB"), dtype=np.float32)
    bayer = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], dtype=np.float32)
    thr = (np.tile(bayer, (SH // 4, SW // 4)) + 0.5) / 16.0  # 0..1
    out = []
    for ch, bits in zip(range(3), (5, 6, 5)):
        scale = (1 << bits) - 1
        x = a[..., ch] / 255.0 * scale
        q = np.floor(x + thr).clip(0, scale)
        # чистый чёрный и чистый белый дизерить нельзя: чёрный фон должен остаться ровно 0
        out.append(q.astype(np.uint32))
    r, g, b = out
    black = (a.sum(axis=-1) == 0)
    v = (r << 11) | (g << 5) | b
    v[black] = 0
    return v.astype("<u2").tobytes()


def recolor_battery(img):
    """Синий -> зелёный zeny, оранжевый -> жёлтый zeny; серая рамка и красный остаются; фон ниже 50 -> чисто чёрный."""
    a = np.asarray(img, dtype=np.float32) / 255.0
    out = a.copy()
    h, w, _ = a.shape
    green_h = 141 / 360.0
    yellow_h = 46 / 360.0
    for y in range(h):
        for x in range(w):
            r, g, b = a[y, x]
            if max(r, g, b) < 50 / 255.0:
                out[y, x] = (0, 0, 0)
                continue
            hh, s, v = colorsys.rgb_to_hsv(r, g, b)
            if s < 0.25:
                continue
            deg = hh * 360.0
            if 180 <= deg <= 260:
                hh = green_h
            elif 10 <= deg <= 50:
                hh = yellow_h
            else:
                continue
            out[y, x] = colorsys.hsv_to_rgb(hh, s, v)
    return Image.fromarray((out * 255.0 + 0.5).astype(np.uint8), "RGB")


def error_image(stock_img):
    """Иконка и вилка остаются, английская подпись стирается и пишется по-русски."""
    img = stock_img.copy()
    d = ImageDraw.Draw(img)
    d.rectangle((90, 282, 390, 316), fill=(0, 0, 0))
    font = ImageFont.truetype(ONEST, 17)
    text = "Ошибка! Отключите зарядку"
    d.text(((SW - font.getlength(text)) / 2, 288), text, font=font, fill=(0xEF, 0xEB, 0xE2))
    return img


def boot_image(boot_mod):
    """Финальный кадр bootanimation без точек: колода с роботом на чёрном."""
    cards = (boot_mod.card_image("fire", 40), boot_mod.card_image("life", 30), boot_mod.front_card())
    return boot_mod.render(cards, None, 0.0, None)


def main():
    stock = open(STOCK, "rb").read()
    raws = parse(stock)
    # round-trip: без правок пересборка обязана дать исходный файл, иначе формат понят неверно
    if build(raws, stock) != stock:
        raise SystemExit("round-trip не сошёлся: пересборка стока отличается от оригинала")
    print("round-trip стока: байт-в-байт OK, картинок", len(raws))

    boot_mod = load_module("make_bootanim", "make-bootanim.py")
    deck = to_raw(boot_image(boot_mod))
    new = list(raws)
    for i in BOOT_IMAGES:
        new[i] = deck
    for i in BATTERY_IMAGES:
        new[i] = to_raw(recolor_battery(to_image(raws[i])))
    new[ERROR_IMAGE] = to_raw(error_image(to_image(raws[ERROR_IMAGE])))

    out = build(new, stock)
    assert len(out) == len(stock)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "wb").write(out)
    os.makedirs(PREVIEW, exist_ok=True)
    for i in sorted(set(BOOT_IMAGES + BATTERY_IMAGES + [ERROR_IMAGE])):
        to_image(new[i]).save(os.path.join(PREVIEW, "%02d.png" % i))
    print("OK", OUT, len(out), "байт; тело", struct.unpack_from("<I", out, 4)[0])


if __name__ == "__main__":
    main()
