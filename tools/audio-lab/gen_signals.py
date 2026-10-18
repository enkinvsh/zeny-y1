"""Эталонные стимулы для цифрового стенда: 48 кГц стерео float WAV (истина) и их Opus-версии.

Всё детерминировано: шумы из фиксированных зёрен, отрывки музыки выбираются правилом, а не руками.
Запуск: gen_signals.py <work_dir>; пишет sig/, opus/, dec/ и manifest.json.
"""

import json
import subprocess
import sys
from pathlib import Path
from typing import Final

import numpy as np
from numpy.typing import NDArray
from scipy.io import wavfile

FS: Final = 48000
SEED: Final = 20261019
MUSIC: Final = Path.home() / "Music/Y1/Music"
# Жанровое правило выбора: первый по пути трек исполнителя, отрывок 30 с с 40% длительности.
# Трэп нужен как самый громкий мастер для проверки пиков, рок и соул дают живые инструменты и вокал.
EXCERPTS: Final = {"trap": "Travis Scott", "rock": "Led Zeppelin", "soul": "Charles Bradley"}

type Arr = NDArray[np.float64]


def db2amp(db: float) -> float:
    return float(10 ** (db / 20))


def shaped_noise(n: int, rng: np.random.Generator, pink: bool) -> Arr:
    """Шум, синтезированный в частотной области: точный 1/f (розовый) или плоский (белый).

    Синтез через FFT делает сигнал периодическим, поэтому идеальный FFT-ресемплинг в анализе не даёт
    скачка на стыке. Розовый ограничен снизу 10 Гц: чистый 1/f до 1/60 Гц унёс бы половину энергии
    в инфразвук, который тракт всё равно не воспроизводит.
    """
    f = np.fft.rfftfreq(n, 1 / FS)
    spec = rng.standard_normal(f.size) + 1j * rng.standard_normal(f.size)
    if pink:
        mag = np.zeros_like(f)
        mask = f >= 10.0  # noqa: PLR2004
        mag[mask] = 1 / np.sqrt(f[mask])
    else:
        mag = np.ones_like(f)
        mag[0] = 0
    x = np.fft.irfft(spec * mag, n)
    return x / np.sqrt(np.mean(x**2)) * db2amp(-20.0)


def fade(x: Arr, fin: float, fout: float) -> Arr:
    """Косинусные края, чтобы скачок на старте/стопе тона не давал широкополосных щелчков."""
    y = x.copy()
    a, b = int(fin * FS), int(fout * FS)
    if a:
        y[:a] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, a))
    if b:
        y[-b:] *= 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, b))
    return y


def tone(freq: float, peak_db: float, seconds: float) -> Arr:
    t = np.arange(int(seconds * FS)) / FS
    return fade(db2amp(peak_db) * np.sin(2 * np.pi * freq * t), 0.05, 0.05)


def stereo(left: Arr, right: Arr | None = None) -> Arr:
    return np.stack([left, left if right is None else right], axis=1)


def build_stimuli(manifest: dict[str, dict[str, float | str | list[float]]]) -> dict[str, Arr]:
    """Синтетические стимулы; параметры, нужные анализатору, уходят в manifest."""
    rng = np.random.default_rng(SEED)
    n = 60 * FS
    wl, wr = shaped_noise(n, rng, pink=False), shaped_noise(n, rng, pink=False)
    pl, pr = shaped_noise(n, rng, pink=True), shaped_noise(n, rng, pink=True)
    zero = np.zeros(n)
    sig: dict[str, Arr] = {
        "white": stereo(wl, wr),
        "pink": stereo(pl, pr),
        "white_mono": stereo(wl),
        "pink_mono": stereo(pl),
        "pink_left": stereo(pl, zero),
        "pink_right": stereo(zero, pl),
    }
    for k in sig:
        manifest[k] = {"kind": "noise"}

    # Экспоненциальный свип Фарины: 0.5 с тишины до, 2 с после (хвост импульсной характеристики)
    f1, f2, dur, pre, amp = 20.0, 20000.0, 20.0, 0.5, db2amp(-12.0)
    big_l = dur / np.log(f2 / f1)
    t = np.arange(int(dur * FS)) / FS
    sw = fade(amp * np.sin(2 * np.pi * f1 * big_l * (np.exp(t / big_l) - 1)), 0.1, 0.005)
    sig["sweep"] = stereo(np.concatenate([np.zeros(int(pre * FS)), sw, np.zeros(2 * FS)]))
    manifest["sweep"] = {"kind": "sweep", "f1": f1, "f2": f2, "dur": dur, "pre": pre, "amp": amp}

    for freq, lvl in [(62, -6), (1000, -3), (1000, -12), (1000, -20), (1000, -40), (1000, -60), (1000, -80),
                      (10000, -6)]:
        name = f"sine_{freq}_m{-lvl}"
        sig[name] = stereo(tone(freq, lvl, 4.0))
        manifest[name] = {"kind": "sine", "freq": freq, "level": lvl}

    # SMPTE IMD: 60 Гц и 7 кГц в отношении 4:1, общий пик -6 dBFS
    t = np.arange(4 * FS) / FS
    imd = 0.4 * np.sin(2 * np.pi * 60 * t) + 0.1 * np.sin(2 * np.pi * 7000 * t)
    sig["imd"] = stereo(fade(imd, 0.05, 0.05))
    manifest["imd"] = {"kind": "imd", "f_low": 60.0, "f_high": 7000.0}

    # Ступени тонов у выходного Найквиста: до 22.05 кГц проходят, выше заворачиваются в 44.1k - f
    hf = [19000.0, 20000.0, 20500.0, 21000.0, 21500.0, 22000.0, 22500.0, 23000.0, 23500.0, 23900.0]
    sig["hf_tones"] = stereo(np.concatenate([tone(f, -6, 1.5) for f in hf]))
    manifest["hf_tones"] = {"kind": "hf_tones", "freqs": hf, "seg": 1.5, "level": -6.0}

    sig["silence"] = stereo(np.zeros(10 * FS))
    manifest["silence"] = {"kind": "silence"}
    return sig


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def pick_excerpts(work: Path, manifest: dict[str, dict[str, float | str | list[float]]]) -> None:
    """Отрывки библиотеки: декод libopus в float, как в плеере (opus_decode_float)."""
    for genre, artist in EXCERPTS.items():
        track = sorted((MUSIC / artist).rglob("*.opus"))[0]
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
             "format=duration:stream_tags=R128_TRACK_GAIN", "-of", "json", str(track)],
            check=True, capture_output=True, text=True)
        info = json.loads(probe.stdout)
        start = round(0.4 * float(info["format"]["duration"]), 1)
        tags = {k.upper(): v for k, v in info["streams"][0].get("tags", {}).items()}
        # Плеер переводит R128 (Q7.8 к -23 LUFS) в цель -17 LUFS: +6 дБ (AudioPlayerManager.parseGainComment)
        tag_db = int(tags["R128_TRACK_GAIN"]) / 256 + 6 if "R128_TRACK_GAIN" in tags else None
        name = f"music_{genre}"
        ffmpeg("-c:a", "libopus", "-i", str(track), "-ss", str(start), "-t", "30", "-map", "0:a",
               "-c:a", "pcm_f32le", "-map_metadata", "-1", "-bitexact", str(work / "dec" / f"{name}.wav"))
        manifest[name] = {"kind": "music", "track": str(track.relative_to(MUSIC)), "start": start,
                          "tag_db": tag_db if tag_db is not None else "-"}


def main() -> None:
    work = Path(sys.argv[1])
    for d in ("sig", "opus", "dec"):
        (work / d).mkdir(parents=True, exist_ok=True)
    manifest: dict[str, dict[str, float | str | list[float]]] = {}
    for name, x in build_stimuli(manifest).items():
        truth = work / "sig" / f"{name}.wav"
        wavfile.write(truth, FS, x.astype(np.float32))
        opus = work / "opus" / f"{name}.opus"
        # Параметры как у библиотеки: VBR ~140 кбит/с, режим audio (CELT/гибрид без речевой оптимизации)
        ffmpeg("-i", str(truth), "-c:a", "libopus", "-b:a", "140k", "-vbr", "on", "-application", "audio",
               "-map_metadata", "-1", str(opus))
        ffmpeg("-c:a", "libopus", "-i", str(opus), "-c:a", "pcm_f32le", "-map_metadata", "-1", "-bitexact",
               str(work / "dec" / f"{name}.wav"))
    pick_excerpts(work, manifest)
    (work / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    print(f"gen_signals: {len(manifest)} стимулов в {work}")


if __name__ == "__main__":
    main()
