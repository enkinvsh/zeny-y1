#!/usr/bin/env python3
"""Рисует карточки-плиты (slab) для правой панели главного меню темы Zeny.

Схема взята с лендинга zeny: огромное слово сверху слева (Unbounded, строчными), предмет с роботом
по центру, подпись снизу справа, радиус 26. Плита слегка повёрнута, из-за неё выглядывает следующая:
колода, как на сайте. Роботы лежат в theme/robots (копии storefront/public/landing + доп. вырезки).
Результат: theme/zeny/slab_<id>.png (238x360, RGBA), их подхватывает make-zeny-theme.py.
Запуск: python3 scripts/make-slabs.py [id ...]; без аргументов рисует все карточки.
"""
import math
import os
import re
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THEME_DIR = os.path.join(ROOT, "theme/zeny")
ROBOTS = os.path.join(ROOT, "theme/robots")  # вырезки с альфой: 5 с сайта + доп. кадры scripts/cut-robots.py

# Размер правой панели на экране Y1 (480x360, список слева 242): 238x360.
W, H = 238, 360
S = 3  # суперсэмплинг, потом LANCZOS вниз: края с поворотом без ступенек
RADIUS = 26  # единственный радиус zeny (решение владельца 2026-10-11)

# Поверхности сайта (globals.css): заливка и цвет текста на ней.
INK = (0x18, 0x1E, 0x26)
INK_DEEP = (0x0F, 0x14, 0x1A)
AIR = (0xEF, 0xEB, 0xE2)
SURFACES = {
    "life": ((0x00, 0xDB, 0x4D), INK),
    "fire": ((0x97, 0x1D, 0x1C), AIR),
    "air": (AIR, INK),
    "water": ((0x33, 0x43, 0x51), AIR),
    "earth": ((0x82, 0x7F, 0x7D), INK_DEEP),
    "possible": ((0xF7, 0xCE, 0x46), INK),
}

# id пункта меню -> (слово плиты, подпись, робот, поверхность). Соседние пункты не повторяют робота.
CARDS = [
    ("music", "музыка", "Твоя коллекция с карты памяти", "czeef", "life"),
    ("videos", "видео", "Фильмы и клипы", "dreamy", "water"),
    ("quiz", "викторина", "Угадай трек по кусочку", "target", "possible"),
    ("podcasts", "подкасты", "Слушай и качай выпуски", "focus", "fire"),
    ("radio", "радио", "Эфир без интернета", "wires", "air"),
    ("bt", "bluetooth", "Наушники без проводов", "cntrl", "earth"),
    ("wifi", "wi-fi", "Сеть для подкастов", "signal", "water"),
    ("settings", "настройки", "Звук, экран, сеть", "tariffs", "air"),
    ("web", "веб-сервер", "Закинь музыку по Wi-Fi", "hourglass", "life"),
]

UNBOUNDED = os.path.join(THEME_DIR, "Unbounded-SemiBold.ttf")
ONEST = os.path.join(THEME_DIR, "Onest-SemiBold.ttf")

# Карточка (в логических px): отступы от краёв панели оставлены под поворот.
CARD_W, CARD_H = 214, 330
PAD = 16


def fit_font(path, text, max_width, max_size, min_size=10):
    """Самый крупный кегль, при котором слово влезает в max_width (как GiantWord на сайте)."""
    size = max_size
    while size > min_size:
        font = ImageFont.truetype(path, int(round(size * S)))
        if font.getlength(text) <= max_width * S:
            return font
        size -= 1
    return ImageFont.truetype(path, min_size * S)


def wrap(font, text, max_width):
    words, lines, cur = text.split(), [], ""
    for word in words:
        trial = (cur + " " + word).strip()
        if font.getlength(trial) <= max_width * S or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def robot_scene(name):
    """Вырезка робота с обрезкой по альфе, без пустых полей."""
    img = Image.open(os.path.join(ROBOTS, name + ".webp")).convert("RGBA")
    return img.crop(img.getchannel("A").getbbox())


def draw_card(word, caption, robot, surface):
    fill, on = SURFACES[surface]
    cw, ch = CARD_W * S, CARD_H * S
    card = Image.new("RGBA", (cw, ch), fill + (255,))

    # Предмет: чуть шире карточки, обрезается её краями; ставим в нижнюю половину над подписью.
    scene = robot_scene(robot)
    target_w = int(cw * 1.32)
    scale = target_w / scene.width
    scene = scene.resize((target_w, int(scene.height * scale)), Image.LANCZOS)
    art_cy = int(ch * 0.52)
    # alpha_composite не принимает отрицательные смещения, поэтому сцену кладём через временный слой.
    layer = Image.new("RGBA", card.size, (0, 0, 0, 0))
    layer.paste(scene, (int((cw - scene.width) / 2), art_cy - scene.height // 2))
    card = Image.alpha_composite(card, layer)

    d = ImageDraw.Draw(card)
    # Огромное слово: Unbounded, строчными, сверху слева.
    giant = fit_font(UNBOUNDED, word, CARD_W - 2 * PAD, 40)
    d.text((PAD * S, 12 * S), word, font=giant, fill=on + (255,))

    # Подпись снизу справа, по правому краю.
    cap_font = fit_font(ONEST, "Пример подписи", 200, 15.4)  # +10% к прежним 14: на экране Y1 мелко
    lines = wrap(cap_font, caption, 164)
    line_h = int(cap_font.size * 1.22)
    y = ch - PAD * S - line_h * len(lines)
    for line in lines:
        d.text((cw - PAD * S - cap_font.getlength(line), y), line, font=cap_font, fill=on + (255,))
        y += line_h

    # Скругление 26 по маске карточки.
    mask = Image.new("L", (cw, ch), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, cw - 1, ch - 1), radius=RADIUS * S, fill=255)
    card.putalpha(mask)
    return card


def plain_card(surface):
    fill, _ = SURFACES[surface]
    cw, ch = CARD_W * S, CARD_H * S
    card = Image.new("RGBA", (cw, ch), fill + (255,))
    mask = Image.new("L", (cw, ch), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, cw - 1, ch - 1), radius=RADIUS * S, fill=255)
    card.putalpha(mask)
    return card


def place(canvas, card, angle, dx, dy):
    rotated = card.rotate(angle, resample=Image.BICUBIC, expand=True)
    x = int(W * S / 2 + dx * S - rotated.width / 2)
    y = int(H * S / 2 + dy * S - rotated.height / 2)
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    layer.paste(rotated, (x, y))
    canvas.alpha_composite(layer)


LOGO_SVG = os.path.join(THEME_DIR, "logo.svg")  # копия harness/brand/logo.svg: «zen» в currentColor, «y» всегда #00DB4D
LOGO_VIEWBOX = (286.9, 76.7)
GREEN = (0x00, 0xDB, 0x4D)
BLACK = (0x05, 0x07, 0x0A)  # чернее ink-deep: карточка должна читаться чёрной на панели ink
ELEVATED = (0x23, 0x2C, 0x37)
DEFAULT_ALBUM = os.path.join(ROOT, "android/app/src/main/res/drawable/default_album.png")
DEFAULT_PANEL = os.path.join(ROOT, "android/app/src/main/res/drawable-nodpi/default_cover_panel.png")


def parse_path(d, steps=14):
    """Контуры SVG-пути (M L H V Q Z, только абсолютные) в списки точек; кривые Безье режем на отрезки."""
    tokens = re.findall(r"[MLHVQZ]|-?\d*\.?\d+", d)
    subpaths, cur, pos, i = [], [], (0.0, 0.0), 0
    cmd = None
    while i < len(tokens):
        if tokens[i] in "MLHVQZ":
            cmd = tokens[i]
            i += 1
            if cmd == "Z":
                if cur:
                    subpaths.append(cur)
                cur = []
                continue
        if cmd == "M":
            if cur:
                subpaths.append(cur)
            pos = (float(tokens[i]), float(tokens[i + 1]))
            cur = [pos]
            i += 2
            cmd = "L"
        elif cmd == "L":
            pos = (float(tokens[i]), float(tokens[i + 1]))
            cur.append(pos)
            i += 2
        elif cmd == "H":
            pos = (float(tokens[i]), pos[1])
            cur.append(pos)
            i += 1
        elif cmd == "V":
            pos = (pos[0], float(tokens[i]))
            cur.append(pos)
            i += 1
        elif cmd == "Q":
            cx, cy, x, y = (float(t) for t in tokens[i:i + 4])
            x0, y0 = pos
            for k in range(1, steps + 1):
                t = k / steps
                cur.append(((1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t * t * x,
                            (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy + t * t * y))
            pos = (x, y)
            i += 4
        else:
            raise ValueError("команда пути не поддержана: %s" % cmd)
    if cur:
        subpaths.append(cur)
    return subpaths


def logo_layer(width, zen_rgb, y_rgb):
    """Логотип zeny шириной width (уже в масштабе S) на прозрачном слое. Дырки букв (e) через even-odd."""
    svg = open(LOGO_SVG, encoding="utf-8").read()
    paths = re.findall(r'<path[^>]*d="([^"]+)"', svg)
    k = width / LOGO_VIEWBOX[0]
    size = (int(round(LOGO_VIEWBOX[0] * k)), int(round(LOGO_VIEWBOX[1] * k)))
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    for d, rgb in zip(paths, (zen_rgb, y_rgb)):
        mask = Image.new("1", size, 0)
        for sub in parse_path(d):
            part = Image.new("1", size, 0)
            ImageDraw.Draw(part).polygon([(x * k, y * k) for x, y in sub], fill=1)
            mask = ImageChops.logical_xor(mask, part)
        layer.paste(Image.new("RGBA", size, rgb + (255,)), (0, 0), mask.convert("L"))
    return layer


def draw_logo_card():
    """Карточка «Сейчас играет» без музыки: чёрная плита, «zen» светлым, «y» зелёным (правило логотипа на тёмном)."""
    fill, on = BLACK, AIR
    cw, ch = CARD_W * S, CARD_H * S
    card = Image.new("RGBA", (cw, ch), fill + (255,))
    logo = logo_layer(int(cw * 0.78), on, GREEN)
    layer = Image.new("RGBA", card.size, (0, 0, 0, 0))
    layer.paste(logo, ((cw - logo.width) // 2, int(ch * 0.46) - logo.height // 2))
    card = Image.alpha_composite(card, layer)
    mask = Image.new("L", (cw, ch), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, cw - 1, ch - 1), radius=RADIUS * S, fill=255)
    card.putalpha(mask)
    return card


def build_default_album():
    """Заглушка обложки без картинки в треке: логотип на плите elevated 360x360 (logo-on-dark из брендбука)."""
    size = 360 * S
    img = Image.new("RGBA", (size, size), ELEVATED + (255,))
    logo = logo_layer(int(size * 0.72), AIR, GREEN)
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    layer.paste(logo, ((size - logo.width) // 2, (size - logo.height) // 2))
    img = Image.alpha_composite(img, layer).resize((360, 360), Image.LANCZOS).convert("RGB")
    img.save(DEFAULT_ALBUM, optimize=True)
    print("OK", DEFAULT_ALBUM, os.path.getsize(DEFAULT_ALBUM))


def build_default_panel():
    """Заглушка правой панели главного меню (238x360 в 1.5x): логотип узкий, чтобы не резался панорамой обложки.

    widget_animated_cover растягивает картинку в 1.35 раза и ведёт окном 238 px по её ширине, так что надёжно
    видна лишь средняя половина: на квадратной default_album логотип обрезался по краям.
    """
    w, h = 357 * 2, 540 * 2
    img = Image.new("RGBA", (w, h), ELEVATED + (255,))
    logo = logo_layer(int(w * 0.48), AIR, GREEN)
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    layer.paste(logo, ((w - logo.width) // 2, (h - logo.height) // 2))
    img = Image.alpha_composite(img, layer).resize((357, 540), Image.LANCZOS).convert("RGB")
    os.makedirs(os.path.dirname(DEFAULT_PANEL), exist_ok=True)
    img.save(DEFAULT_PANEL, optimize=True)
    print("OK", DEFAULT_PANEL, os.path.getsize(DEFAULT_PANEL))


# Широкие карточки для полноэкранных экранов 480x360: заголовок и статус рисует сам Android (перевод через t()),
# в картинку запекаются только плита и робот, поэтому подпись можно менять без пересборки арта.
DRAWABLE_NODPI = os.path.join(ROOT, "android/app/src/main/res/drawable-nodpi")
BANNER_W, BANNER_H = 452, 158  # экран 480 минус по 14 px полей; нижние 10 px занимает выглядывающая плита
BANNER_CARD_H = 148
# имя файла -> (робот, поверхность карточки, поверхность выглядывающей плиты)
BANNERS = {
    "web_banner_stopped": ("office", "possible", "water"),
    "web_banner_running": ("uploader", "life", "water"),
    "web_banner_error": ("filing", "fire", "earth"),
    # экран USB: кабель подключён (cntrl: самолётики в папки = перекладывание файлов), накопитель включён
    # (wires: провода), ошибка включения (тот же filing на красном, что и у веб-сервера)
    "usb_banner_idle": ("cntrl", "air", "water"),
    "usb_banner_on": ("wires", "life", "water"),
    "usb_banner_error": ("filing", "fire", "earth"),
    # оверлей загрузки: сканирование карты (песочные часы) и FM-радио (антенна)
    "loading_banner_scan": ("hourglass", "possible", "earth"),
    "loading_banner_radio": ("signal", "water", "earth"),
    # карточка низкого заряда: робот, уснувший за столом (sleeper) на красной плите; ошибку зарядки рисует тот же
    # usb_banner_error (filing на красном)
    "battery_low": ("sleeper", "fire", "earth"),
}


def shaded(surface, size, alpha=70):
    """Плита поверхности, притемнённая, чтобы читаться как нижняя карточка колоды."""
    card = plain_rect(surface, size)
    shade = Image.new("RGBA", size, (0, 0, 0, alpha))
    card = Image.alpha_composite(card, shade)
    card.putalpha(plain_rect(surface, size).getchannel("A"))
    return card


def plain_rect(surface, size):
    fill, _ = SURFACES[surface]
    rect = Image.new("RGBA", size, fill + (255,))
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=RADIUS * S, fill=255)
    rect.putalpha(mask)
    return rect


def put_robot(card, robot, width, right_bleed, center_y):
    """Кладёт робота шириной width прижатым к правому краю карточки; лишнее обрезается маской скругления."""
    scene = robot_scene(robot)
    scale = width / scene.width
    scene = scene.resize((width, int(scene.height * scale)), Image.LANCZOS)
    layer = Image.new("RGBA", card.size, (0, 0, 0, 0))
    layer.paste(scene, (card.width - scene.width + right_bleed, center_y - scene.height // 2))
    return Image.alpha_composite(card, layer)


def build_banner(name):
    robot, surface, plate = BANNERS[name]
    cw, ch = BANNER_W * S, BANNER_CARD_H * S
    card = plain_rect(surface, (cw, ch))
    card = put_robot(card, robot, 258 * S, 2 * S, int(ch * 0.52))
    card.putalpha(plain_rect(surface, (cw, ch)).getchannel("A"))
    canvas = Image.new("RGBA", (BANNER_W * S, BANNER_H * S), (0, 0, 0, 0))
    plate_img = shaded(plate, (cw - 28 * S, ch))
    canvas.alpha_composite(plate_img, (14 * S, (BANNER_H - BANNER_CARD_H) * S))
    canvas.alpha_composite(card, (0, 0))
    out = canvas.resize((BANNER_W, BANNER_H), Image.LANCZOS)
    path = os.path.join(DRAWABLE_NODPI, name + ".png")
    os.makedirs(DRAWABLE_NODPI, exist_ok=True)
    out.save(path, optimize=True)
    print("OK", path, os.path.getsize(path))


def build_lock_card():
    """Экран блокировки: карточка water с роботом, спиной к камере, и наклонённая плита из-под неё."""
    sw, sh = 480, 360
    cw, ch = 440 * S, 320 * S
    card = plain_rect("water", (cw, ch))
    card = put_robot(card, "sleeper", 416 * S, -4 * S, int(ch * 0.63))
    card.putalpha(plain_rect("water", (cw, ch)).getchannel("A"))
    canvas = Image.new("RGBA", (sw * S, sh * S), INK_DEEP + (255,))
    plate = shaded("earth", (cw, ch)).rotate(2.4, resample=Image.BICUBIC, expand=True)
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    layer.paste(plate, ((sw * S - plate.width) // 2 + 3 * S, (sh * S - plate.height) // 2 + 2 * S))
    canvas.alpha_composite(layer)
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    layer.paste(card, ((sw * S - cw) // 2, (sh * S - ch) // 2))
    canvas.alpha_composite(layer)
    out = canvas.resize((sw, sh), Image.LANCZOS).convert("RGB")
    path = os.path.join(DRAWABLE_NODPI, "lock_card.png")
    os.makedirs(DRAWABLE_NODPI, exist_ok=True)
    out.save(path, optimize=True)
    print("OK", path, os.path.getsize(path))


def compose(front, back_surface, name):
    """Колода: задняя плита выглядывает справа снизу, передняя чуть наклонена влево."""
    # фон панели непрозрачный: иначе по краям карточек просвечивает анимированная обложка из widget_animated_cover
    canvas = Image.new("RGBA", (W * S, H * S), INK + (255,))
    back = plain_card(back_surface)
    shade = Image.new("RGBA", back.size, (0, 0, 0, 70))
    back = Image.alpha_composite(back, shade)
    back.putalpha(plain_card(back_surface).getchannel("A"))
    place(canvas, back, 3.2, 4, 3)
    place(canvas, front, -2.0, -1, 0)
    out = canvas.resize((W, H), Image.LANCZOS)
    path = os.path.join(THEME_DIR, "slab_%s.png" % name)
    out.save(path, optimize=True)
    print("OK", path, os.path.getsize(path))


def build(index):
    cid, word, caption, robot, surface = CARDS[index]
    nxt_surface = CARDS[(index + 1) % len(CARDS)][4]
    compose(draw_card(word, caption, robot, surface), nxt_surface, cid)


def main():
    only = set(sys.argv[1:])
    for i, card in enumerate(CARDS):
        if not only or card[0] in only:
            build(i)
    if not only or "now" in only:
        compose(draw_logo_card(), CARDS[0][4], "now")
    if not only or "album" in only:
        build_default_album()
    if not only or "panel" in only:
        build_default_panel()
    for banner in BANNERS:
        if not only or "banners" in only or banner in only:
            build_banner(banner)
    if not only or "lock" in only:
        build_lock_card()


if __name__ == "__main__":
    main()
