#!/usr/bin/env python3
"""Собирает тему Zeny: config.json + шрифты -> android/app/src/main/assets/themes/Zeny.zip.

Раскладка (список слева 242dp + анимированная обложка справа) берётся у встроенной
iPod Classic Dark, поверх неё ложится палитра zeny-dark и шрифты Onest/Unbounded.
Запуск: python3 scripts/make-zeny-theme.py
"""
import json
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "android/app/src/main/assets/themes")
THEME_DIR = os.path.join(ROOT, "theme/zeny")
BASE_ZIP = os.path.join(ASSETS, "iPod Classic Dark.zip")
OUT_ZIP = os.path.join(ASSETS, "Zeny.zip")

# Палитра zeny-dark (harness/brand/themes/zeny-dark.json), формат ARGB для Color.parseColor.
BG = "#FF181E26"          # фон
SURFACE = "#FF1B222B"     # статус-бар, плашки
PRIMARY = "#FF00DB4D"     # акцент, фокус
ON_PRIMARY = "#FF08130B"  # текст на зелёной плашке: почти чёрный с зелёным оттенком
FG = "#FFEFEBE2"
FG_MUTED = "#FFA4A3A0"

FONTS = ["Onest-Regular.ttf", "Onest-SemiBold.ttf", "Unbounded-SemiBold.ttf"]

# Пункт меню -> карточка справа (рисует scripts/make-slabs.py). У «Сейчас играет» карточка с логотипом
# показывается только пока очередь пуста; когда есть трек, приложение убирает её и видна живая обложка.
SLABS = {
    "btn_now": "slab_now.png",
    "btn_music": "slab_music.png",
    "btn_videos": "slab_videos.png",
    "btn_musicquiz": "slab_quiz.png",
    "btn_podcasts": "slab_podcasts.png",
    "btn_radio": "slab_radio.png",
    "btn_bt": "slab_bt.png",
    "btn_wifi": "slab_wifi.png",
    "btn_set": "slab_settings.png",
    "btn_web": "slab_web.png",
}


def load_base():
    with zipfile.ZipFile(BASE_ZIP) as z:
        return json.loads(z.read("config.json").decode("utf-8-sig"))


def build_config():
    cfg = load_base()
    cfg.update({
        "name": "Zeny",
        "font": "Onest-Regular.ttf",
        "fontBold": "Onest-SemiBold.ttf",
        # Расширения темы: читаются ThemeManager, в оригинальном InniClassic их нет.
        "fontTitle": "Unbounded-SemiBold.ttf",
        "menuTitle": "zeny",
        "titleColor": PRIMARY,
        "classic_layout": True,
        "flat": True,
        "textPrimary": FG,
        "textSecondary": FG_MUTED,
        "bgOverlay": BG,
        "statusBarBg": SURFACE,
        "btnNormal": "#00FFFFFF",
        "btnFocused": PRIMARY,
        "btnFocusedText": ON_PRIMARY,
        # Единственный радиус zeny. Строка списка ниже 52 px высотой, Android сам сводит радиус к капсуле.
        "button_radius": 26,
    })
    for el in cfg["main_menu"]:
        if el.get("type") == "button":
            el["text_right_color"] = FG_MUTED
            el["text_right_focused_color"] = ON_PRIMARY
            if el["id"] in SLABS:
                el["preview_image"] = SLABS[el["id"]]
    # Карточка кладётся поверх живой обложки той же площади; без preview_image (Now Playing) прозрачна.
    cfg["main_menu"].append({
        "id": "widget_slab",
        "type": "widget_focus_image",
        "parent_id": "",
        "visible_on_focus": "",
        "x": 242,
        "y": 0,
        "width": 238,
        "height": 325,
        "action": "NONE",
        "focus_index": -1,
    })
    return cfg


def main():
    cfg = build_config()
    with open(os.path.join(THEME_DIR, "config.json"), "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("config.json", json.dumps(cfg, ensure_ascii=False, indent=2))
        for name in FONTS + sorted(SLABS.values()):
            z.write(os.path.join(THEME_DIR, name), name)
    print("OK", OUT_ZIP, os.path.getsize(OUT_ZIP), "bytes")


if __name__ == "__main__":
    main()
