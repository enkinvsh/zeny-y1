#!/usr/bin/env python3
"""Вырезает дополнительные диорамы роботов для карточек Y1 тем же конвейером, что и сайт zeny.

Берёт cut_r22.process_one из ~/Documents/zeny-refs/artefacts (BiRefNet + маска фетрового коврика + мягкая
тень в альфе) и пишет результат в theme/robots, ничего не добавляя в папку владельца.
Запуск ТОЛЬКО интерпретатором из venv артефактов (в нём rembg, модель birefnet-general лежит в ~/.rembg):
  ~/Documents/zeny-refs/artefacts/.venv/bin/python scripts/cut-robots.py [имя ...]
"""
import os
import sys
from pathlib import Path

ART = Path.home() / "Documents/zeny-refs/artefacts"
sys.path.insert(0, str(ART))
import cut_r22 as cut  # noqa: E402
from rembg import new_session  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "theme" / "robots"
OUT.mkdir(parents=True, exist_ok=True)

# имя результата -> (правило конвейера, исходник, цвет плиты для превью, fit_w, fit_h).
# Правила маски коврика в cut_r22 завязаны на имя: czeef чистит зелёный рефлекс кромки, focus/tariffs/dreamy
# имеют свою геометрию под конкретные кадры. Для новых кадров берём czeef (зелёный фон) или нейтральное имя.
ITEMS = {
    "target": ("target", ART / "r22/focus_2.png", "#971d1c", 0.88, 0.88),
    "signal": ("signal", ART / "r22/cntrl_2.png", "#efebe2", 0.88, 0.88),
    "wires": ("wires", ART / "r22/cntrl_0.png", "#efebe2", 0.88, 0.88),
    "office": ("czeef", ART / "r22/czeef_1.png", "#00db4d", 0.88, 0.88),
    # Экраны ожидания и ошибок: песочные часы (веб-сервер выключен), «ошибка» на красном (нет Wi-Fi),
    # робот за столом с наушниками (веб-сервер работает), спиной к камере (экран блокировки).
    # Для focus_1 берём нейтральное правило: правило focus зеркалит коврик под геометрию кадра focus_0.
    "hourglass": ("tariffs", ART / "r22/tariffs_1.png", "#f7ce46", 0.88, 0.88),
    "filing": ("filing", ART / "r22/focus_1.png", "#971d1c", 0.88, 0.88),
    "uploader": ("czeef", ART / "r22/czeef_0.png", "#00db4d", 0.88, 0.88),
    "sleeper": ("tariffs", ART / "r22/tariffs_2.png", "#f7ce46", 0.88, 0.88),
}

if __name__ == "__main__":
    only = set(sys.argv[1:])
    session = new_session("birefnet-general")
    for name, (rule, src, color, fw, fh) in ITEMS.items():
        if only and name not in only:
            continue
        # process_one называет файлы по правилу, поэтому каждому кадру свой рабочий каталог, потом переименование.
        work = OUT / ("_work_" + name)
        work.mkdir(exist_ok=True)
        cut.MASK_DIR = work
        cut.OUT_DIR = work
        cut.process_one(rule, src, color, fw, fh, session)
        (work / (rule + ".webp")).replace(OUT / (name + ".webp"))
        (work / (rule + "_preview.png")).replace(OUT / (name + "_preview.png"))
