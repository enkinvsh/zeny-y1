"""Отрывки библиотеки: пики и true-peak декодированного Opus, работа мягкого клиппера, разница Air +12/Pure
и остаток вне линейной модели."""

from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Final

import matplotlib.pyplot as plt
import numpy as np
from scipy import signal

import labutil as lu

type Num = float | int | str | list[float] | Mapping[str, Num]
KNEE: Final = 0.82  # порог softClip в ZenyAudioProcessor (-1.7 dBFS)
GENRES: Final = ("trap", "rock", "soul")


def unclip(y: np.ndarray) -> np.ndarray:
    """Обратная функция softClip: на выходе монотонная s = e/(1+e), значит e = s/(1-s)."""
    a = np.abs(y)
    s = np.clip((a - KNEE) / (1 - KNEE), 0, 0.999)
    return np.where(a > KNEE, np.sign(y) * (KNEE + (1 - KNEE) * s / (1 - s)), y)


def clip_stats(y: np.ndarray) -> dict[str, float | str]:
    # 1.5 МЗР запаса: значения у порога, перескочившие его только из-за дизера, клиппером не тронуты
    hit = np.abs(y) > KNEE + 1.5 * lu.LSB
    x = unclip(y)
    err = x - y
    return {"samples_in_knee": int(hit.sum()), "fraction_percent": round(float(hit.mean() * 100), 4),
            "clip_error_db_rel_signal": round(float(lu.db(np.sum(err**2) / np.sum(y**2))), 1) if hit.any() else "нет",
            "max_preclip_dbfs": round(float(20 * np.log10(np.abs(x).max())), 2),
            "max_reduction_db": round(float(20 * np.log10(np.max(np.abs(x) / np.maximum(np.abs(y), 1e-9)))), 2)}


def section(work: Path, manifest: Mapping[str, Mapping[str, Num]], axes: Callable[..., None]) -> dict[str, Num]:
    res: dict[str, Num] = {}
    diffs: dict[str, np.ndarray] = {}
    resid: dict[str, dict[str, np.ndarray]] = {}
    for g in GENRES:
        name = f"music_{g}"
        _, dec = lu.read(work / "dec" / f"{name}.wav")
        tp = np.abs(signal.resample_poly(dec, 4, 1, axis=0)).max()  # true-peak по BS.1770: 4x передискретизация
        row: dict[str, Num] = {
            "track": manifest[name]["track"], "tag_db": manifest[name]["tag_db"],
            "sample_peak_dbfs": round(float(20 * np.log10(np.abs(dec).max())), 2),
            "true_peak_dbtp": round(float(20 * np.log10(tp)), 2),
            "samples_over_0dbfs": int((np.abs(dec) > 1).sum()),
            "samples_over_knee_after_gain": int((np.abs(dec) * lu.GAIN > KNEE).sum()),
        }
        outs = {}
        for ch in ("T2C", "T3C", "T2S", "T3S"):
            fs, y = lu.read(work / "out/native" / ch / f"{name}.wav")
            outs[ch] = y
            row[ch] = clip_stats(y)
        f, p2 = signal.welch(outs["T2C"], fs=fs, nperseg=lu.NPERSEG, axis=0)
        _, p3 = signal.welch(outs["T3C"], fs=fs, nperseg=lu.NPERSEG, axis=0)
        diffs[g] = lu.db(lu.band_sum(f, p3.sum(axis=1)) / lu.band_sum(f, p2.sum(axis=1)))
        row["air12_minus_pure_db"] = [round(float(v), 2) for v in diffs[g]]
        resid[g] = {}
        for ch in ("T2C", "T3C", "T2S", "T3S"):
            p = lu.load_pair(work / "dec" / f"{name}.wav", work / "out/native" / ch / f"{name}.wav", lu.GAIN)
            fr, syy, see = lu.residual_psd(p.fs, p.ref, p.out[:, 0])
            resid[g][ch] = lu.db(lu.band_sum(fr, see) / lu.band_sum(fr, syy))
            row[f"residual_{ch}_db"] = [round(float(v), 1) for v in resid[g][ch]]
        lt = lu.band_sum(f, signal.welch(dec, fs=48000, nperseg=lu.NPERSEG, axis=0)[1].sum(axis=1))
        ref1k = lt[np.argmin(np.abs(lu.BANDS - 1000))]
        row["spectrum_rel_1k_db"] = {str(int(round(b, -2))): round(float(lu.db(lt[i] / ref1k)), 1)
                                     for i, b in enumerate(lu.BANDS) if b > 10000}  # noqa: PLR2004
        res[name] = row

    fig, axs = plt.subplots(2, 1, figsize=(9, 8))
    for g in GENRES:
        axs[0].plot(lu.BANDS, diffs[g], "o-", ms=3, label=g)
    axes(axs[0], "Музыка: спектр Air +12 минус Pure (средний по отрывку, 1/3 окт., L+R)")
    for g in GENRES:
        axs[1].plot(lu.BANDS, resid[g]["T3C"], "o-", ms=3, label=f"{g} Air +12")
        axs[1].plot(lu.BANDS, resid[g]["T3S"], "s--", ms=3, label=f"{g} Air +12 + Sound Check")
        axs[1].plot(lu.BANDS, resid[g]["T2C"], "x:", ms=3, label=f"{g} Pure")
    axes(axs[1], "Музыка: остаток вне линейной модели (искажения + шум), дБ отн. выхода, канал L")
    fig.tight_layout()
    fig.savefig(work / "results/plots/music.png", dpi=110)
    plt.close(fig)
    return res
