"""Анализ пар (вход, выход) цифрового тракта Zeny: шумовые передаточные функции, каналы, Opus, Java/натив.

Запуск: analyze.py <work_dir> | analyze.py --selftest. Тоны/свип и музыка - в tones.py и music.py.
Анализ ведётся по нативному ядру (оно работает на устройстве); Java сравнивается с ним побитово.
"""

import json
import sys
from pathlib import Path
from typing import Final

import matplotlib as mpl

mpl.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

import labutil as lu
import music
import tones

FINE: Final = 20.0 * 2 ** (np.arange(0, 125) / 12)  # 1/12 октавы, 20 Гц - 23 кГц (выше 22 кГц только для 48 кГц)
CORE: Final = "native"
# Полосы выше 22.05 кГц у выхода 44.1 кГц и пустой канал при входе только в L дают 0/0 -> NaN; это ожидаемо
np.seterr(invalid="ignore", divide="ignore")


def chains(work: Path) -> dict[str, tuple[Path, Path, float]]:
    """Стадия -> (каталог входа, каталог выхода, известное усиление для компенсации)."""
    o = work / "out" / CORE
    sig, dec = work / "sig", work / "dec"
    c = {"T1": (sig, dec, 1.0)}
    for t in ("T2", "T3"):  # T3 = «Воздух» шаг 3 (+12 дБ): форма полки, статическое усиление то же -1.2 дБ
        g = lu.GAIN
        c[f"{t}L"] = (sig, o / f"{t}L", g)  # DSP на входе без потерь
        c[f"{t}C"] = (dec, o / f"{t}C", g)  # DSP после Opus, относительно своего входа
        c[f"{t}E"] = (sig, o / f"{t}C", g)  # весь тракт Opus + DSP относительно истины
    for s in (1, 2, 3):
        c[f"STAGE{s}"] = (sig, o / f"STAGE{s}", lu.GAIN)
    for s in (1, 2, 3, 4, 5):
        c[f"AIR{s}"] = (sig, o / f"AIR{s}", lu.GAIN)
    return c


def pair(work: Path, chain: str, stim: str) -> lu.Pair:
    ref, out, g = chains(work)[chain]
    return lu.load_pair(ref / f"{stim}.wav", out / f"{stim}.wav", g)


def noise_metrics(p: lu.Pair, ch_in: int, ch_out: int, dsp: bool, gain: float = lu.GAIN) -> dict[str, list[float]]:
    s = lu.spectra(p.fs, p.ref[:, ch_in], p.out[:, ch_out])
    _, syy, noise = lu.residual_psd(p.fs, p.ref, p.out[:, ch_out])
    m = {
        "gain": lu.band_gain_db(s),
        "fine": lu.band_gain_db(s, FINE, 12),
        "fine_rta": lu.db(lu.band_sum(s.f, s.pyy, FINE, 12) / lu.band_sum(s.f, s.pxx, FINE, 12)),
        "rta": lu.db(lu.band_sum(s.f, s.pyy) / lu.band_sum(s.f, s.pxx)),
        "coh_min": lu.db(1 - np.array([s.coh[mk].min() for mk in lu.band_masks(s.f)])),
        "noncoh": lu.db(lu.band_sum(s.f, noise) / lu.band_sum(s.f, syy)),
        "gd_us": lu.band_gd_us(s),
    }
    if dsp:
        # Идеальный 16-бит TPDF: дизер 1/6 + округление 1/12 МЗР^2, белый; выход поделён на известное усиление цепочки
        ideal = 2 * 0.25 * lu.LSB**2 / gain**2 / p.fs
        cnt = np.array([mk.sum() for mk in lu.band_masks(s.f)])
        m["excess_noise"] = lu.db(lu.band_sum(s.f, noise) / (ideal * cnt))
    return {k: [round(float(v), 4) for v in a] for k, a in m.items()}


def pp(a: list[float], lo: float = 20, hi: float = 20000, f: np.ndarray = lu.BANDS) -> float:
    sel = np.array(a)[(f >= lo * 0.99) & (f <= hi * 1.01)]
    return round(float(np.nanmax(sel) - np.nanmin(sel)), 3)


def section_noise(work: Path) -> dict[str, dict[str, dict[str, list[float] | float]]]:
    res: dict[str, dict[str, dict[str, list[float] | float]]] = {}
    for stim in ("white", "pink", "white_mono", "pink_mono"):
        res[stim] = {}
        for ch in ("T1", "T2L", "T3L", "T2C", "T3C", "T2E", "T3E"):
            nm = noise_metrics(pair(work, ch, stim), 0, 0, ch != "T1", chains(work)[ch][2])
            m: dict[str, list[float] | float] = dict(nm)
            m["pp_20_18k_fine"] = pp(nm["fine"], 20, 18000, FINE)
            m["pp_20_20k_fine"] = pp(nm["fine"], 20, 20000, FINE)
            m["max_abs_20_18k_fine"] = round(float(np.nanmax(np.abs(np.array(nm["fine"])[FINE <= 18000]))), 3)
            res[stim][ch] = m
    return res


def section_channels(work: Path) -> dict[str, dict[str, list[float] | float]]:
    """Перекрёстная утечка, симметрия L/R, межканальная задержка, моно против стерео."""
    res: dict[str, dict[str, list[float] | float]] = {}
    for ch in ("T2L", "T3L", "STAGE1", "STAGE2", "STAGE3"):
        lft, rgt = pair(work, ch, "pink_left"), pair(work, ch, "pink_right")
        direct_l, cross_l = noise_metrics(lft, 0, 0, dsp=True), noise_metrics(lft, 0, 1, dsp=True)
        direct_r, cross_r = noise_metrics(rgt, 1, 1, dsp=True), noise_metrics(rgt, 1, 0, dsp=True)
        mono, st = noise_metrics(pair(work, ch, "pink_mono"), 0, 0, True), noise_metrics(pair(work, ch, "pink"), 0, 0, True)
        s_l = lu.spectra(lft.fs, lft.out[:, 0], lft.out[:, 1])  # уровни выходных каналов между собой
        xt = lu.db(lu.band_sum(s_l.f, s_l.pyy) / lu.band_sum(s_l.f, s_l.pxx))
        res[ch] = {
            "crosstalk_db": [round(float(v), 3) for v in xt],
            "cross_gain": cross_l["gain"],
            "direct_gain": direct_l["gain"],
            "sym_direct_db": [round(a - b, 4) for a, b in zip(direct_l["gain"], direct_r["gain"], strict=True)],
            "sym_cross_db": [round(a - b, 4) for a, b in zip(cross_l["gain"], cross_r["gain"], strict=True)],
            "gd_direct_us": direct_l["gd_us"],
            "gd_cross_us": cross_l["gd_us"],
            "mono_minus_stereo_db": [round(a - b, 4) for a, b in zip(mono["rta"], st["rta"], strict=True)],
        }
    return res


def section_air(work: Path) -> dict[str, dict[str, object]]:
    """Шаги «Воздуха» 1..5: АЧХ по розовому моно, THD+N и гармоники 62 Гц и 1 кГц, IMD 60 Гц + 7 кГц."""
    res: dict[str, dict[str, object]] = {}
    for s in (1, 2, 3, 4, 5):
        ch = f"AIR{s}"
        row: dict[str, object] = {"gain": noise_metrics(pair(work, ch, "pink_mono"), 0, 0, dsp=True, gain=chains(work)[ch][2])["gain"]}
        for stim, freq in (("sine_62_m6", 62), ("sine_1000_m3", 1000)):
            fs, x = tones.steady(work, ch, stim)
            st = lu.tone_stats(fs, x, freq)
            row[stim] = {"thdn_db": round(st.thdn_db, 2),
                         "harm_dbc": {str(k): round(v, 1) for k, v in st.harmonics_dbc.items()}}
        fs, x = tones.steady(work, ch, "imd")
        f, p = lu.power_spectrum(fs, x)
        p7 = lu.tone_power(f, p, 7000)
        side = sum(lu.tone_power(f, p, 7000 + sg * 60 * n) for n in (1, 2, 3, 4) for sg in (-1, 1))
        row["imd_db"] = round(float(lu.db(side / p7)), 1)
        res[ch] = row
    return res


def stage_h(f: np.ndarray, fs: float, feed_db: float) -> tuple[np.ndarray, np.ndarray]:
    """Независимая от кода плеера модель «Сцены»: H_lp = a / (1 - x z^-1), x = exp(-2*pi*700/fs),
    прямой путь 1 - G*H_lp, перекрёстный G*H_lp, G = 1 / (1 + 10^(feed/20))."""
    x = np.exp(-2 * np.pi * 700.0 / fs)
    z1 = np.exp(-1j * 2 * np.pi * f / fs)
    lp = (1 - x) / (1 - x * z1)
    g = 1 / (1 + 10 ** (feed_db / 20))
    return 1 - g * lp, g * lp


def air_h_db(f: np.ndarray, plateau_db: float) -> np.ndarray:
    """Аналоговый прототип полки: H(s) = (1 + s/wz)/(1 + s/wp), центр 8 кГц, плато K."""
    k = 10 ** (plateau_db / 20)
    fz, fp = 8000 / np.sqrt(k), 8000 * np.sqrt(k)
    return 10 * np.log10((1 + (f / fz) ** 2) / (1 + (f / fp) ** 2))


def section_ref(work: Path) -> dict[str, dict[str, object]]:
    """Сверка измеренных АЧХ с формулами, посчитанными в numpy отдельно от плеера.

    Модельная АЧХ усредняется в полосе с тем же весом (спектр входа), что и измерение band_gain_db."""
    res: dict[str, dict[str, object]] = {}
    for s, feed in ((1, 3.0), (2, 4.5), (3, 6.0)):
        p = pair(work, f"STAGE{s}", "pink_left")
        sp = lu.spectra(p.fs, p.ref[:, 0], p.out[:, 0])
        d_mod, c_mod = stage_h(sp.f, 48000.0, feed)  # ядро считает на частоте входа 48 кГц
        meas_d = noise_metrics(p, 0, 0, dsp=True)["gain"]
        meas_c = noise_metrics(p, 0, 1, dsp=True)["gain"]
        row: dict[str, object] = {}
        for name, mod, meas in (("direct", d_mod, meas_d), ("cross", c_mod, meas_c)):
            model = lu.db(lu.band_sum(sp.f, np.abs(mod) ** 2 * sp.pxx) / lu.band_sum(sp.f, sp.pxx))
            dev = np.array(meas) - model
            sel = (lu.BANDS >= 20 * 0.99) & (lu.BANDS <= 16000 * 1.01)
            i = int(np.argmax(np.abs(dev) * sel))
            row[name] = {"max_dev_db": round(float(abs(dev[i])), 3), "at_hz": round(float(lu.BANDS[i]), 1),
                         "model_db": [round(float(v), 3) for v in model]}
        res[f"STAGE{s}"] = row
    for s, plateau in ((1, 6.0), (2, 9.0), (3, 12.0), (4, 15.0), (5, 18.0)):
        p = pair(work, f"AIR{s}", "pink_mono")
        sp = lu.spectra(p.fs, p.ref[:, 0], p.out[:, 0])
        model = lu.db(lu.band_sum(sp.f, 10 ** (air_h_db(sp.f, plateau) / 10) * sp.pxx) / lu.band_sum(sp.f, sp.pxx))
        meas = np.array(noise_metrics(p, 0, 0, dsp=True, gain=chains(work)[f"AIR{s}"][2])["gain"])
        dev = meas - model
        sel = (lu.BANDS >= 20 * 0.99) & (lu.BANDS <= 16000 * 1.01)
        i = int(np.argmax(np.abs(dev) * sel))
        res[f"AIR{s}"] = {"max_dev_db": round(float(abs(dev[i])), 3), "at_hz": round(float(lu.BANDS[i]), 1),
                          "model_db": [round(float(v), 3) for v in model]}
    return res


def section_opus(noise: dict[str, dict[str, dict[str, list[float] | float]]]) -> dict[str, float]:
    """Полоса кодека: где 1/12-октавный спектр мощности декодированного относительно истины падает на 3 и 20 дБ.

    Берём мощность, а не H1: CELT сохраняет энергию полос и на ВЧ подменяет часть сигнала шумом, поэтому
    когерентная часть (H1) спадает раньше, чем уровень."""
    out: dict[str, float] = {}
    for stim in ("white", "pink"):
        fine = np.array(noise[stim]["T1"]["fine_rta"])
        h1 = np.array(noise[stim]["T1"]["fine"])
        out[f"{stim}_h1_minus3db_hz"] = round(float(FINE[np.nonzero((h1 < -3) & (FINE > 1000))[0][0]]), 0)
        for thr in (3, 20):
            below = np.nonzero((fine < -thr) & (FINE > 1000))[0]
            out[f"{stim}_minus{thr}db_hz"] = round(float(FINE[below[0]]), 0) if below.size else float("nan")
    return out


def section_parity(work: Path) -> dict[str, float]:
    """Java-эталон против нативного ядра: максимум расхождения в МЗР по всем файлам матрицы."""
    worst, files, differ, total = 0, 0, 0, 0
    for f in sorted((work / "out" / "java").rglob("*.wav")):
        _, a = lu.read(f)
        _, b = lu.read(work / "out" / "native" / f.relative_to(work / "out" / "java"))
        d = np.abs(np.round((a - b) / lu.LSB))
        worst, files, differ = max(worst, int(d.max())), files + 1, differ + int((d > 0).sum())
        total += d.size
    return {"files": files, "max_diff_lsb": worst, "differing_samples": differ, "total_samples": total}


def axes(ax: plt.Axes, title: str, ylab: str = "дБ") -> None:
    ax.set_xscale("log")
    ax.set_xlim(20, 22000)
    ax.set_xticks([20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000],
                  ["20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k"])
    ax.grid(visible=True, which="both", alpha=0.3)
    ax.set_title(title, fontsize=10)
    ax.set_ylabel(ylab)
    ax.legend(fontsize=7)


def plots(work: Path, noise: dict, chan: dict) -> None:  # noqa: ANN001 - словари из json-совместимых секций
    out = work / "results" / "plots"
    lab = {"T1": "T1 Opus", "T2L": "T2 прозрачный", "T3L": "T3 Air +12", "T2E": "Opus+T2", "T3E": "Opus+T3"}
    fig, axs = plt.subplots(2, 1, figsize=(9, 8))
    for ch in ("T1", "T2L", "T3L"):
        axs[0].semilogx(FINE, noise["pink"][ch]["fine"], label=lab[ch] + " (стерео, L)")
    axs[0].semilogx(FINE, noise["pink_mono"]["T3L"]["fine"], "--", label="T3 Air +12 (моно L=R)")
    axes(axs[0], "Передаточная функция H1 по розовому шуму, 1/12 окт., -1.2 дБ компенсировано")
    # Срез Opus выше 16 кГц растягивал ось до -90 дБ и прятал различия в ±2 дБ
    axs[0].set_ylim(-4, 4)
    for ch in ("T1", "T2E", "T3E"):
        axs[1].plot(lu.BANDS, noise["pink"][ch]["rta"], "o-", ms=3, label=lab[ch] + " розовый")
        axs[1].plot(lu.BANDS, noise["white"][ch]["rta"], "x:", ms=3, label=lab[ch] + " белый")
    axes(axs[1], "RTA: уровень выхода минус уровень истины, 1/3 октавы")
    fig.tight_layout()
    fig.savefig(out / "transfer_rta.png", dpi=110)
    plt.close(fig)

    fig, axs = plt.subplots(2, 1, figsize=(9, 8))
    for ch in ("T1", "T2L", "T3L", "T2E", "T3E"):
        axs[0].plot(lu.BANDS, noise["pink"][ch]["noncoh"], "o-", ms=3, label=lab[ch])
    axes(axs[0], "Остаток выхода вне линейной модели от L и R (шум + искажения), розовый -20 dBFS")
    for ch in ("T2L", "T3L", "T2C", "T3C"):
        axs[1].plot(lu.BANDS, noise["pink"][ch]["excess_noise"], "o-", ms=3, label=ch)
    axes(axs[1], "Добавленный шум стадии относительно идеального 16-бит TPDF (0 дБ = идеал)")
    fig.tight_layout()
    fig.savefig(out / "coherence_noise.png", dpi=110)
    plt.close(fig)

    fig, axs = plt.subplots(3, 1, figsize=(9, 11))
    for ch in ("T3L", "STAGE1", "STAGE2", "STAGE3"):
        axs[0].plot(lu.BANDS, chan[ch]["crosstalk_db"], "o-", ms=3, label=ch)
    axs[0].plot(lu.BANDS, chan["T2L"]["crosstalk_db"], "k:", label="T2L (пол дизера)")
    axes(axs[0], "Перетекание L->R: уровень R минус уровень L, вход только в L")
    for ch in ("T3L", "STAGE1", "STAGE3"):
        d = np.array(chan[ch]["gd_cross_us"]) - np.array(chan[ch]["gd_direct_us"])
        axs[1].plot(lu.BANDS, d, "o-", ms=3, label=ch)
    axes(axs[1], "Межканальная задержка: групп. задержка перекрёстного минус прямого пути", "мкс")
    axs[1].set_ylim(150, 550)
    for ch in ("T2L", "T3L", "STAGE1", "STAGE3"):
        axs[2].plot(lu.BANDS, chan[ch]["mono_minus_stereo_db"], "o-", ms=3, label=ch)
    axes(axs[2], "Моно (L=R) минус стерео (декоррелированный): тональный сдвиг центра")
    fig.tight_layout()
    fig.savefig(out / "channels.png", dpi=110)
    plt.close(fig)


def selftest() -> None:
    """Тождественная цепочка должна давать 0 дБ: и прямо на 48 кГц, и через идеальные 44.1 кГц + 16 бит."""
    rng = np.random.default_rng(1)
    x = rng.standard_normal((48000 * 20, 2)) * 0.1
    same = lu.Pair(48000, x, x, 0)
    s = lu.spectra(same.fs, same.ref[:, 0], same.out[:, 0])
    assert np.max(np.abs(lu.band_gain_db(s))) < 1e-9, "тождество 48 кГц не 0 дБ"
    y = lu.to_rate(x, 48000, 44100) * lu.GAIN
    q = np.round(y / lu.LSB + rng.uniform(-0.5, 0.5, y.shape) - rng.uniform(-0.5, 0.5, y.shape)) * lu.LSB
    r, o, lag = lu.align(lu.to_rate(x, 48000, 44100), q / lu.GAIN)
    m = noise_metrics(lu.Pair(44100, r[44100:-44100], o[44100:-44100], lag), 0, 0, dsp=True)
    assert lag == 0, f"лаг {lag}"
    assert max(abs(v) for v in m["gain"]) < 0.01, f"АЧХ тождества {m['gain']}"
    assert max(abs(v) for v in m["excess_noise"]) < 1.0, f"шум тождества {m['excess_noise']}"
    st = lu.tone_stats(44100, np.sin(2 * np.pi * 1000 * np.arange(1 << 17) / 44100) * 0.5, 1000)
    assert abs(st.level_dbfs + 6.02) < 0.01 and st.thdn_db < -200, f"тон {st}"  # noqa: PLR2004
    print("selftest: тождество 0.000 дБ, шум 16 бит = идеал, THD чистого синуса < -200 дБ - OK")


def main() -> None:
    if sys.argv[1] == "--selftest":
        selftest()
        return
    work = Path(sys.argv[1])
    (work / "results" / "plots").mkdir(parents=True, exist_ok=True)
    manifest = json.loads((work / "manifest.json").read_text())
    noise, chan = section_noise(work), section_channels(work)
    summary = {
        "noise": noise,
        "channels": chan,
        "air": section_air(work),
        "ref_check": section_ref(work),
        "opus": section_opus(noise),
        "tones": tones.section(work, manifest, pair, axes),
        "music": music.section(work, manifest, axes),
        "parity": section_parity(work),
        "bands_hz": [round(float(b), 1) for b in lu.BANDS],
        "fine_hz": [round(float(b), 1) for b in FINE],
    }
    plots(work, noise, chan)
    (work / "results" / "summary.json").write_text(json.dumps(summary, indent=1, ensure_ascii=False))
    print("analyze: results/summary.json и графики готовы")


if __name__ == "__main__":
    main()
