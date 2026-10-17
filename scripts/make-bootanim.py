#!/usr/bin/env python3
"""Собирает загрузочную анимацию zeny для Y1: /system/media/bootanimation.zip.

Сюжет: на чёрном экране одна за другой «въезжают» три плиты колоды с лендинга (fire, life, потом кремовая
air с логотипом и роботом czeef), затем плита лениво покачивается, пока Android грузится.
part0 играется один раз (въезд), part1 крутится до конца загрузки (покачивание и точки-индикатор).

Фон строго чёрный: тот же цвет у логотипа загрузчика (logo.bin) и у системной подложки, поэтому переход
между этапами загрузки не мигает. Кадры JPEG 480x360, zip без сжатия (method 0) - так требует bootanimation.
Запуск: python3 scripts/make-bootanim.py  ->  work/bootanimation-zeny.zip + work/bootanim-preview/
"""
import importlib.util
import math
import os
import shutil
import sys
import zipfile

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# make-slabs.py с дефисом в имени - обычным import не подключить.
_spec = importlib.util.spec_from_file_location("make_slabs", os.path.join(HERE, "make-slabs.py"))
slabs = importlib.util.module_from_spec(_spec)
sys.modules["make_slabs"] = slabs
_spec.loader.exec_module(slabs)

SW, SH = 480, 360
FPS = 20
SS = 2  # суперсэмплинг кадра: поворот плит без ступенек
CARD_W, CARD_H = 296, 220
INTRO_FRAMES = 32
LOOP_FRAMES = 24
BG = (0, 0, 0)
OUT_ZIP = os.path.join(ROOT, "work/bootanimation-zeny.zip")
PREVIEW = os.path.join(ROOT, "work/bootanim-preview")


def ease_out_back(t, overshoot=1.4):
    t = min(max(t, 0.0), 1.0) - 1.0
    return 1.0 + (overshoot + 1.0) * t ** 3 + overshoot * t ** 2


def card_image(surface, shade=0):
    """Скруглённая плита поверхности surface размером CARD_W x CARD_H (в масштабе SS)."""
    size = (CARD_W * SS, CARD_H * SS)
    fill, _ = slabs.SURFACES[surface]  # slabs.plain_rect считает радиус под свой S, поэтому маска своя
    card = Image.new("RGBA", size, fill + (255,))
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=slabs.RADIUS * SS, fill=255)
    if shade:
        card = Image.alpha_composite(card, Image.new("RGBA", size, (0, 0, 0, shade)))
    card.putalpha(mask)
    return card


def front_card():
    """Кремовая плита: логотип zeny сверху слева (как гигантское слово на лендинге) и робот czeef внизу."""
    card = card_image("air")
    size = card.size
    # логотип: «zen» ink, «y» зелёный; ширина в масштабе slabs.S -> приводим к SS
    logo = slabs.logo_layer(int(size[0] * 0.46 * slabs.S / SS), slabs.INK, slabs.GREEN)
    logo = logo.resize((int(logo.width * SS / slabs.S), int(logo.height * SS / slabs.S)), Image.LANCZOS)
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    layer.paste(logo, (22 * SS, 20 * SS))
    card = Image.alpha_composite(card, layer)

    scene = slabs.robot_scene("czeef")
    width = int(size[0] * 1.02)
    scene = scene.resize((width, int(scene.height * width / scene.width)), Image.LANCZOS)
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    layer.paste(scene, ((size[0] - scene.width) // 2 + 6 * SS, size[1] - int(scene.height * 0.80)))
    card = Image.alpha_composite(card, layer)
    # маска скругления ещё раз: робот выходит за край плиты
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=slabs.RADIUS * SS, fill=255)
    card.putalpha(mask)
    return card


def paste_rotated(canvas, card, angle, cx, cy):
    rotated = card.rotate(angle, resample=Image.BICUBIC, expand=True)
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    layer.paste(rotated, (int(cx * SS - rotated.width / 2), int(cy * SS - rotated.height / 2)))
    canvas.alpha_composite(layer)


def dots(canvas, phase):
    """Три точки-индикатора под колодой: активная зелёная, остальные цвета water."""
    active = int(phase * 3) % 3
    cy = 336
    for i in range(3):
        cx = SW / 2 + (i - 1) * 22
        r = 6 if i == active else 4.5
        color = slabs.GREEN if i == active else (0x33, 0x43, 0x51)
        ImageDraw.Draw(canvas).ellipse(
            ((cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS), fill=color + (255,))


def render(cards, intro_t, bob, dot_phase):
    """Один кадр. intro_t - прогресс въезда в кадрах (None = колода стоит), bob - покачивание в px."""
    canvas = Image.new("RGBA", (SW * SS, SH * SS), BG + (255,))
    back_fire, back_life, front = cards
    # (карточка, финальный угол, финальный центр, кадр старта, длительность, угол старта, откуда ехать)
    plan = [
        (back_fire, -7.0, (222, 168), 0, 14, -26.0, 300),
        (back_life, 6.0, (262, 172), 5, 14, 24.0, 300),
        (front, -1.8, (240, 160), 11, 16, 10.0, 330),
    ]
    for card, angle, (cx, cy), start, dur, a0, drop in plan:
        if intro_t is None:
            k = 1.0
        elif intro_t < start:
            continue
        else:
            k = ease_out_back((intro_t - start) / dur)
        a = a0 + (angle - a0) * k
        y = cy + drop * (1.0 - k) + bob
        paste_rotated(canvas, card, a, cx, y)
    if dot_phase is not None:  # статичный кадр для logo.bin обходится без индикатора
        dots(canvas, dot_phase)
    return canvas.resize((SW, SH), Image.LANCZOS).convert("RGB")


def main():
    cards = (card_image("fire", 40), card_image("life", 30), front_card())
    for sub in ("part0", "part1"):
        shutil.rmtree(os.path.join(ROOT, "work/bootanim-build", sub), ignore_errors=True)
        os.makedirs(os.path.join(ROOT, "work/bootanim-build", sub), exist_ok=True)
    shutil.rmtree(PREVIEW, ignore_errors=True)
    os.makedirs(PREVIEW, exist_ok=True)

    names = []
    for n in range(INTRO_FRAMES):
        frame = render(cards, n, 0.0, n / (INTRO_FRAMES * 1.0))
        names.append(("part0", "zeny_%03d.jpg" % n, frame))
    for n in range(LOOP_FRAMES):
        phase = n / LOOP_FRAMES
        frame = render(cards, None, 3.0 * math.sin(2 * math.pi * phase), phase)
        names.append(("part1", "zeny_%03d.jpg" % (INTRO_FRAMES + n), frame))

    os.makedirs(os.path.dirname(OUT_ZIP), exist_ok=True)
    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_STORED) as zf:
        zf.writestr("desc.txt", "%d %d %d\np 1 0 part0\np 0 0 part1\n" % (SW, SH, FPS))
        for part, name, frame in names:
            path = os.path.join(ROOT, "work/bootanim-build", part, name)
            frame.save(path, quality=88, optimize=True)
            zf.write(path, "%s/%s" % (part, name))
            frame.save(os.path.join(PREVIEW, "%s-%s.png" % (part, name[:-4])))
    print("OK", OUT_ZIP, os.path.getsize(OUT_ZIP), "кадров", len(names))


if __name__ == "__main__":
    main()
