"""Тоновые замеры: ступенчатые синусы (THD, THD+N, линейность), свип Фарины (гармоники, ГВЗ), IMD,
тоны у Найквиста (ресемплер) и тишина (пол дизера)."""

from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Final

import matplotlib.pyplot as plt
import numpy as np
from scipy import signal

import labutil as lu

type Axes = Callable[..., None]
type Num = float | int | str | list[float] | Mapping[str, Num]
DSP: Final = ("T2L", "T3L", "T2C", "T3C")
SEG: Final = 1 << 17  # ~3 с окно анализа тона


def outfile(work: Path, chain: str, stim: str) -> Path:
    d = {"T1": work / "dec", "T2E": work / "out/native/T2C", "T3E": work / "out/native/T3C"}
    return d.get(chain, work / "out/native" / chain) / f"{stim}.wav"


def steady(work: Path, chain: str, stim: str) -> tuple[int, np.ndarray]:
    fs, x = lu.read(outfile(work, chain, stim))
    a = fs // 2
    return fs, x[a:a + SEG, 0]


type Row = dict[str, float | dict[str, float]]


def sines(work: Path, manifest: dict[str, dict[str, float]]) -> dict[str, dict[str, Row]]:
    res: dict[str, dict[str, Row]] = {}
    err: dict[tuple[str, str], float] = {}
    for stim, p in manifest.items():
        if p["kind"] != "sine":
            continue
        for ch in ("T1", *DSP):
            fs, x = steady(work, ch, stim)
            st = lu.tone_stats(fs, x, p["freq"])
            expect = p["level"] - (0.0 if ch == "T1" else 1.2)
            err[stim, ch] = st.level_dbfs - expect
            res.setdefault(stim, {})[ch] = {
                "level_err_db": round(err[stim, ch], 3), "thdn_db": round(st.thdn_db, 2),
                "nd_dbfs": round(st.nd_dbfs, 2), "harm_dbc": {str(k): round(v, 1) for k, v in st.harmonics_dbc.items()},
                "floor_dbc": round(st.floor_dbc, 1), "spur_dbc": round(st.spur_dbc, 1), "spur_hz": round(st.spur_hz),
            }
    for ch in ("T1", *DSP):  # линейность: усиление на уровне минус усиление на -20 dBFS (1 кГц)
        for lvl in (3, 12, 40, 60, 80):
            stim = f"sine_1000_m{lvl}"
            res[stim][ch]["linearity_db"] = round(err[stim, ch] - err["sine_1000_m20", ch], 3)
    return res


def sweep(work: Path, manifest: dict) -> tuple[dict[str, Num], dict[str, dict[str, np.ndarray]]]:  # noqa: ANN001
    pts = 20.0 * 2 ** (np.arange(0, 121) / 12)
    nums: dict[str, Num] = {}
    curves: dict[str, dict[str, np.ndarray]] = {}
    for ch in ("T1", "T2L", "T3L", "T2E", "T3E"):
        fs_r, ref = lu.read(work / "sig/sweep.wav")
        fs, out = lu.read(outfile(work, ch, "sweep"))
        ref = lu.to_rate(ref, fs_r, fs)
        g = 1.0 if ch == "T1" else lu.GAIN
        ir = lu.sweep_ir(fs, ref[:, 0], out[:len(ref), 0] / g, manifest["sweep"])
        h1 = lu.smooth_at(ir.f, np.abs(ir.h1) ** 2, pts)
        hk = {k: lu.smooth_at(ir.f, m**2, pts * k) / h1 for k, m in ir.hk.items()}
        for k in hk:
            hk[k][pts * k > 20000] = np.nan  # noqa: PLR2004
        thd = np.nansum(np.stack(list(hk.values())), axis=0)
        thd[pts * 2 > 20000] = np.nan  # noqa: PLR2004
        w = 2 * np.pi * ir.f
        gd = -np.gradient(np.unwrap(np.angle(ir.h1)), w) * 1e6
        gds = lu.smooth_at(ir.f, gd, pts)
        curves[ch] = {"h1_db": lu.db(h1), "thd_db": lu.db(thd), "h2_db": lu.db(hk[2]), "h3_db": lu.db(hk[3]),
                      "gd_us": gds}
        at = {f: int(np.argmin(np.abs(pts - f))) for f in (31.5, 62, 100, 1000, 5000)}
        lo, mid = (pts >= 20) & (pts <= 200), (pts >= 40) & (pts <= 16000)  # noqa: PLR2004
        nums[ch] = {
            "h1_db_at": {str(f): round(float(lu.db(h1[i])), 2) for f, i in at.items()},
            "thd_db_at": {str(f): round(float(lu.db(thd[i])), 1) for f, i in at.items()},
            "h2_dbc_at": {str(f): round(float(lu.db(hk[2][i])), 1) for f, i in at.items()},
            "h3_dbc_at": {str(f): round(float(lu.db(hk[3][i])), 1) for f, i in at.items()},
            # выше ~1 кГц метод свипа упирается в шум дизера (энергия свипа на Гц падает 3 дБ/окт),
            # поэтому максимум THD берём только в басу, где нелинейность видна лучше всего
            "thd_max_db_20_200": round(float(np.nanmax(lu.db(thd[lo]))), 1),
            "gd_us_at": {str(f): round(float(gds[i]), 1) for f, i in at.items()},
            "gd_us_10k": round(float(gds[int(np.argmin(np.abs(pts - 10000)))]), 1),
            "gd_span_us_40_16k": round(float(np.nanmax(gds[mid]) - np.nanmin(gds[mid])), 1),
        }
    curves["_pts"] = {"f": pts}
    return nums, curves


def imd(work: Path) -> dict[str, Num]:
    res: dict[str, Num] = {}
    for ch in ("T1", "T2L", "T3L", "T2E", "T3E"):
        fs, x = steady(work, ch, "imd")
        f, p = lu.power_spectrum(fs, x)
        p7 = lu.tone_power(f, p, 7000)
        side = {n: lu.tone_power(f, p, 7000 + s * 60 * n) for n in (1, 2, 3, 4) for s in (-1, 1)}
        res[ch] = {"imd_db": round(float(lu.db(sum(side.values()) / p7)), 1),
                   "imd_percent": round(float(np.sqrt(sum(side.values()) / p7) * 100), 4)}
    return res


def hf(work: Path, manifest: dict, pair: Callable[..., lu.Pair]) -> tuple[dict[str, Num], dict[str, np.ndarray]]:  # noqa: ANN001
    """Ресемплер: тоны у Найквиста по МНК-подгонке на выходе 44.1 кГц и тонкая АЧХ по белому шуму."""
    p = manifest["hf_tones"]
    res: dict[str, Num] = {}
    for ch in ("T2L", "T3L"):
        fs, x = lu.read(outfile(work, ch, "hf_tones"))
        row = {}
        for i, f in enumerate(p["freqs"]):
            a = int((i * p["seg"] + 0.25) * fs)
            seg = x[a:a + fs, 0]
            fo = f if f < fs / 2 else fs - f
            t = np.arange(a, a + fs) / fs
            m = np.stack([np.cos(2 * np.pi * fo * t), np.sin(2 * np.pi * fo * t)], axis=1)
            c = np.linalg.lstsq(m, seg, rcond=None)[0]
            row[str(int(f))] = round(float(lu.db(np.sum(c**2) / (10 ** (p["level"] / 10) * lu.GAIN**2))), 2)
        res[ch] = row
    s = lu.spectra(*_w(pair(work, "T2L", "white")))
    lin = np.arange(15000, 22100, 100.0)
    mag = np.array([np.mean(np.abs(s.h[(s.f >= c - 50) & (s.f < c + 50)]) ** 2) for c in lin])
    mdb = lu.db(mag)
    below = lin[mdb < -3]
    res["white_T2L"] = {"minus3db_hz": float(below[0]) if below.size else float("nan"),
                        "at_19k_db": round(float(mdb[lin == 19000][0]), 3),
                        "at_20k_db": round(float(mdb[lin == 20000][0]), 3),
                        "at_21k_db": round(float(mdb[lin == 21000][0]), 3)}
    return res, {"f": lin, "db": mdb}


def _w(p: lu.Pair) -> tuple[int, np.ndarray, np.ndarray]:
    return p.fs, p.ref[:, 0], p.out[:, 0]


def a_weight_db(f: np.ndarray) -> np.ndarray:
    f2 = f**2
    ra = 12194**2 * f2**2 / ((f2 + 20.6**2) * np.sqrt((f2 + 107.7**2) * (f2 + 737.9**2)) * (f2 + 12194**2))
    return 20 * np.log10(np.maximum(ra, 1e-300)) + 2.0


def silence(work: Path) -> dict[str, Num]:
    res: dict[str, Num] = {}
    for ch in ("T1", *DSP):
        fs, x = lu.read(outfile(work, ch, "silence"))
        f, pxx = signal.welch(x[fs:-fs, 0], fs=fs, nperseg=lu.NPERSEG)
        band = (f >= 20) & (f <= 20000)  # noqa: PLR2004
        df = f[1] - f[0]
        p_un = pxx[band].sum() * df
        p_a = (pxx[band] * 10 ** (a_weight_db(f[band]) / 10)).sum() * df
        if p_un == 0:  # декодер Opus отдаёт на тишину цифровой ноль
            res[ch] = {"digital_zero": 1, "max_abs_lsb": 0}
            continue
        res[ch] = {"rms_dbfs_20_20k": round(float(lu.db(p_un / 0.5)), 2), "rms_dbfs_A": round(float(lu.db(p_a / 0.5)), 2),
                   "max_abs_lsb": int(np.max(np.abs(np.round(x[:, 0] / lu.LSB)))),
                   "third_oct_spread_db": round(float(np.ptp(lu.db(lu.band_sum(f, pxx) / np.array(
                       [m.sum() for m in lu.band_masks(f)]))[3:])), 2)}
    ideal = 0.25 * lu.LSB**2 * (20000 - 20) / 22050
    res["ideal_16bit_tpdf_dbfs_20_20k"] = round(float(lu.db(ideal / 0.5)), 2)
    return res


def section(work: Path, manifest: dict, pair: Callable[..., lu.Pair], axes: Axes) -> dict[str, Num]:  # noqa: ANN001
    out = work / "results" / "plots"
    sw_nums, sw = sweep(work, manifest)
    hf_nums, hf_curve = hf(work, manifest, pair)
    res = {"sines": sines(work, manifest), "sweep": sw_nums, "imd": imd(work), "hf": hf_nums, "silence": silence(work)}

    pts = sw["_pts"]["f"]
    fig, axs = plt.subplots(3, 1, figsize=(9, 11))
    for ch in ("T1", "T2L", "T3L", "T3E"):
        axs[0].semilogx(pts, sw[ch]["thd_db"], label=f"{ch} THD (H2..H9)")
    axs[0].semilogx(pts, sw["T3L"]["h2_db"], ":", label="T3L H2")
    axs[0].semilogx(pts, sw["T3L"]["h3_db"], "--", label="T3L H3")
    axes(axs[0], "Гармоники по свипу Фарины (-12 dBFS), дБ отн. основного тона, ось = частота возбуждения", "дБc")
    for ch in ("T1", "T2L", "T3L"):
        axs[1].semilogx(pts, sw[ch]["gd_us"], label=ch)
    axes(axs[1], "Групповая задержка всей стадии относительно входа (свип, L=R)", "мкс")
    # У 20 кГц оценка ГВЗ уходит в -20000 мкс (край полосы ресемплера) и сплющивает остальное
    axs[1].set_ylim(-200, 900)
    for ch in ("T2L", "T3L"):
        f, x = lu.power_spectrum(*steady(work, ch, "sine_62_m6"))
        axs[2].semilogx(f, lu.db(x / x.max()), lw=0.7, label=f"{ch} 62 Гц -6 dBFS")
    axes(axs[2], "Спектр выхода на тоне 62 Гц: гармоники нелинейности тракта", "дБ отн. пика")
    axs[2].set_xlim(20, 2000)
    axs[2].set_ylim(-160, 0)
    fig.tight_layout()
    fig.savefig(out / "sweep_gd_62hz.png", dpi=110)
    plt.close(fig)

    fig, axs = plt.subplots(2, 1, figsize=(9, 8))
    axs[0].plot(hf_curve["f"], hf_curve["db"], label="T2L белый шум, H1 (100 Гц бины)")
    fs_t = [float(k) for k in res["hf"]["T2L"]]
    axs[0].plot(fs_t, list(res["hf"]["T2L"].values()), "o", label="T2L тоны (выше 22.05 кГц - уровень отражения)")
    axs[0].axvline(22050, color="k", lw=0.5)
    axs[0].set_xlim(15000, 24000)
    axs[0].set_ylim(-110, 3)
    axs[0].grid(alpha=0.3)
    axs[0].legend(fontsize=7)
    axs[0].set_title("Ресемплер 48->44.1 кГц: полоса пропускания и подавление отражений", fontsize=10)
    for ch in ("T2L", "T3L"):
        f, x = lu.power_spectrum(*steady(work, ch, "silence"))
        axs[1].semilogx(f, lu.db(x / 0.5), lw=0.5, label=f"{ch} тишина")
    f, x = lu.power_spectrum(*steady(work, "T2L", "sine_1000_m80"))
    axs[1].semilogx(f, lu.db(x / 0.5), lw=0.5, label="T2L 1 кГц -80 dBFS")
    axes(axs[1], "Пол шума на выходе (дизер TPDF 16 бит), dBFS на бин 0.34 Гц", "dBFS/бин")
    axs[1].set_ylim(-170, -70)
    fig.tight_layout()
    fig.savefig(out / "resampler_noise.png", dpi=110)
    plt.close(fig)
    return res
