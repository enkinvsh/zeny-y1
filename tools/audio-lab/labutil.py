"""Оценщики стенда: чтение WAV, приведение пары (вход, выход) к одной частоте, H1/когерентность, 1/3 октавы,
спектр тона и гармоники, деконволюция свипа Фарины.

Соглашения: уровни в dBFS по AES17 (полномасштабный синус = 0 dBFS); выход плеера int16 делим на 32767,
потому что ZenyAudioProcessor.writeDitheredShort умножает на 32767.
"""

from dataclasses import dataclass
from pathlib import Path
from typing import Final

import numpy as np
from numpy.typing import NDArray
from scipy import signal
from scipy.io import wavfile

type Arr = NDArray[np.float64]

GAIN: Final = float(np.float32(10 ** (-1.2 / 20)))  # targetStaticGainLinear без Sound Check


LSB: Final = 1 / 32767
NPERSEG: Final = 65536  # 0.67 Гц на 44.1 кГц: даже в 1/12 октавы у 20 Гц есть бины
# Центры третьоктавных полос 20 Гц - 20 кГц (точные 1000*2^(k/3), подписи - номинальные)
BANDS: Final = 1000.0 * 2.0 ** (np.arange(-17, 14) / 3)
TRIM: Final = 1.0  # секунды по краям: переходные процессы фильтров и края FFT-ресемплинга


def read(path: Path) -> tuple[int, Arr]:
    fs, x = wavfile.read(path)
    y = x.astype(np.float64) * LSB if x.dtype == np.int16 else x.astype(np.float64)
    return fs, y.reshape(len(y), -1)


def to_rate(x: Arr, fs_from: int, fs_to: int) -> Arr:
    """Идеальный (FFT) ресемплинг: кирпичный срез ровно на Найквисте без своей АЧХ в полосе.

    Этим приводим эталон к 44.1 кГц, чтобы ресемплер стенда не подмешивал собственный спад к спаду
    ресемплера плеера у 20-22 кГц (scipy.resample_poly дал бы -6 дБ на 22.05 кГц сам по себе).
    """
    if fs_from == fs_to:
        return x
    return signal.resample(x, round(len(x) * fs_to / fs_from), axis=0)


def align(ref: Arr, out: Arr, max_lag: int = 4000) -> tuple[Arr, Arr, int]:
    """Целочисленное выравнивание по максимуму взаимной корреляции суммы каналов."""
    a, b = ref.sum(axis=1), out.sum(axis=1)
    n = min(len(a), len(b))
    xc = signal.correlate(b[:n], a[:n], mode="full", method="fft")
    mid = n - 1
    lag = int(np.argmax(np.abs(xc[mid - max_lag:mid + max_lag + 1]))) - max_lag
    r, o = (ref, out[lag:]) if lag >= 0 else (ref[-lag:], out)
    m = min(len(r), len(o))
    return r[:m], o[:m], lag


@dataclass(frozen=True, slots=True)
class Pair:
    """Вход и выход стадии на одной частоте дискретизации, выровненные и обрезанные по краям."""

    fs: int
    ref: Arr
    out: Arr
    lag: int


def load_pair(ref_path: Path, out_path: Path, gain: float = 1.0) -> Pair:
    fs_r, ref = read(ref_path)
    fs_o, out = read(out_path)
    ref = to_rate(ref, fs_r, fs_o)
    r, o, lag = align(ref, out / gain)
    t = int(TRIM * fs_o)
    return Pair(fs_o, r[t:-t], o[t:-t], lag)


@dataclass(frozen=True, slots=True)
class Spectra:
    f: Arr
    pxx: Arr
    pyy: Arr
    pxy: Arr

    @property
    def h(self) -> NDArray[np.complex128]:
        return self.pxy / np.maximum(self.pxx, 1e-300)

    @property
    def coh(self) -> Arr:
        return np.abs(self.pxy) ** 2 / np.maximum(self.pxx * self.pyy, 1e-300)


def spectra(fs: int, x: Arr, y: Arr) -> Spectra:
    """Welch: окно Ханна, 50% перекрытие. scipy.csd(x, y) = E[conj(X) Y], поэтому H1 = Pxy / Pxx."""
    kw = {"fs": fs, "nperseg": NPERSEG, "window": "hann"}
    f, pxx = signal.welch(x, **kw)
    _, pyy = signal.welch(y, **kw)
    _, pxy = signal.csd(x, y, **kw)
    return Spectra(f, pxx, pyy, pxy)


def residual_psd(fs: int, x: Arr, y: Arr) -> tuple[Arr, Arr, Arr]:
    """Остаток выхода после лучшей линейной модели от ОБОИХ входных каналов: y = H1 x0 + H2 x1 + e.

    Одновходовая когерентность записала бы кроссфид (доля соседнего канала, с ним не коррелированного)
    в «шум», поэтому шум/искажения стадии считаем по частичной когерентности. Возвращает (f, Syy, See).
    """
    kw = {"fs": fs, "nperseg": NPERSEG, "window": "hann"}
    f, s11 = signal.welch(x[:, 0], **kw)
    _, s22 = signal.welch(x[:, 1], **kw)
    _, syy = signal.welch(y, **kw)
    _, s12 = signal.csd(x[:, 0], x[:, 1], **kw)
    _, s1y = signal.csd(x[:, 0], y, **kw)
    _, s2y = signal.csd(x[:, 1], y, **kw)
    det = s11 * s22 - np.abs(s12) ** 2
    two = det > 1e-6 * np.maximum(s11 * s22, 1e-300)  # моно (L=R) или один пустой канал -> один вход
    h1 = np.where(two, (s22 * s1y - s12 * s2y) / np.where(two, det, 1), 0)
    h2 = np.where(two, (s11 * s2y - np.conj(s12) * s1y) / np.where(two, det, 1), 0)
    expl2 = np.real(h1 * np.conj(s1y) + h2 * np.conj(s2y))
    s, sy = (s11, s1y) if s11.sum() >= s22.sum() else (s22, s2y)
    expl1 = np.abs(sy) ** 2 / np.maximum(s, 1e-300)
    return f, syy, np.maximum(syy - np.where(two, expl2, expl1), 0)


def band_masks(f: Arr, frac: int = 3, centres: Arr = BANDS) -> list[NDArray[np.bool_]]:
    half = 2 ** (1 / (2 * frac))
    return [(f >= c / half) & (f < c * half) for c in centres]


def band_sum(f: Arr, p: Arr, centres: Arr = BANDS, frac: int = 3) -> Arr:
    return np.array([p[m].sum() for m in band_masks(f, frac, centres)])


def db(x: Arr | float) -> Arr:
    return 10 * np.log10(np.maximum(x, 1e-300))


def band_gain_db(s: Spectra, centres: Arr = BANDS, frac: int = 3) -> Arr:
    """Модуль H1 в полосе, взвешенный по мощности входа: sqrt(sum Pxx|H|^2 / sum Pxx).

    Комплексное среднее Pxy не годится: задержка кроссфида 250 мкс поворачивает фазу внутри полосы
    на ВЧ на радианы, и сумма бы гасилась.
    """
    num = band_sum(s.f, np.abs(s.pxy) ** 2 / np.maximum(s.pxx, 1e-300), centres, frac)
    return db(num / band_sum(s.f, s.pxx, centres, frac))


def band_gd_us(s: Spectra, centres: Arr = BANDS) -> Arr:
    """Групповая задержка в полосе: наклон развёрнутой фазы H1 по МНК."""
    ph = np.unwrap(np.angle(s.h))
    out = []
    for m in band_masks(s.f, 3, centres):
        w = 2 * np.pi * s.f[m]
        out.append(-np.polyfit(w, ph[m], 1)[0] * 1e6 if m.sum() >= 2 else np.nan)  # noqa: PLR2004
    return np.array(out)


def power_spectrum(fs: int, x: Arr) -> tuple[Arr, Arr]:
    """Мощность на бин (сумма по бинам = дисперсия) с окном Кайзера beta=38: боковые лепестки ниже -250 дБ,
    чтобы утечка основного тона не маскировала гармоники и шум 16 бит."""
    w = signal.windows.kaiser(len(x), 38)
    spec = np.fft.rfft(x * w)
    return np.fft.rfftfreq(len(x), 1 / fs), 2 * np.abs(spec) ** 2 / (len(x) * np.sum(w**2))


LOBE_HZ: Final = 6.0  # полуширина главного лепестка Кайзера 38 при окне ~3 с


def tone_power(f: Arr, p: Arr, freq: float) -> float:
    return float(p[np.abs(f - freq) <= LOBE_HZ].sum())


@dataclass(frozen=True, slots=True)
class ToneStats:
    level_dbfs: float
    harmonics_dbc: dict[int, float]
    thdn_db: float
    nd_dbfs: float
    floor_dbc: float  # шум в полосе одного лепестка: ниже этого гармоники не видны
    spur_dbc: float  # самый сильный негармонический пик
    spur_hz: float


def tone_stats(fs: int, x: Arr, freq: float) -> ToneStats:
    """Уровень основного тона, H2..H9 (только ниже 20 кГц) и THD+N в полосе 20 Гц - 20 кГц."""
    f, p = power_spectrum(fs, x)
    p1 = tone_power(f, p, freq)
    harm = {k: float(db(tone_power(f, p, k * freq) / p1)) for k in range(2, 10) if k * freq < 20000}  # noqa: PLR2004
    band = (f >= 20) & (f <= 20000) & (np.abs(f - freq) > LOBE_HZ)  # noqa: PLR2004
    nd = float(p[band].sum())
    lobe = int(2 * LOBE_HZ / (f[1] - f[0])) + 1
    free = band & np.all([np.abs(f - k * freq) > LOBE_HZ for k in range(2, 10)], axis=0)
    floor = float(np.median(p[free])) * lobe
    win = np.convolve(np.where(free, p, 0), np.ones(lobe), mode="same")
    i = int(np.argmax(win))
    return ToneStats(float(db(p1 / 0.5)), harm, float(db(nd / p1)), float(db(nd / 0.5)),
                     float(db(floor / p1)), float(db(win[i] / p1)), float(f[i]))


@dataclass(frozen=True, slots=True)
class SweepIR:
    """Спектры линейной ИХ и гармонических ИХ свипа Фарины, нормированные на эталонную деконволюцию."""

    f: Arr
    h1: NDArray[np.complex128]
    hk: dict[int, Arr]  # |H_k(f)| относительно эталона на той же частоте


def sweep_ir(fs: int, ref: Arr, out: Arr, p: dict[str, float]) -> SweepIR:
    """Деконволюция обратным фильтром (свип наоборот с огибающей exp(-t/L)), окна гармоник по L*ln(k)."""
    start, n = int(p["pre"] * fs), int(p["dur"] * fs)
    big_l = p["dur"] / np.log(p["f2"] / p["f1"])
    inv = ref[start:start + n][::-1] * np.exp(-np.arange(n) / fs / big_l)
    href, hout = signal.fftconvolve(ref, inv), signal.fftconvolve(out, inv)
    i0 = int(np.argmax(np.abs(href)))
    nfft = 1 << 17
    f = np.fft.rfftfreq(nfft, 1 / fs)

    def win(h: Arr, k: int) -> NDArray[np.complex128]:
        c = i0 - round(big_l * np.log(k) * fs)
        before = 0.3 * big_l * np.log((k + 1) / k)
        after = 0.7 * big_l * np.log(k / (k - 1)) if k > 1 else 0.5
        a, b = c - int(before * fs), c + int(after * fs)
        seg = h[a:b] * signal.windows.tukey(b - a, 0.1)
        # Сдвиг начала окна возвращаем фазой, чтобы фаза H1 отражала задержку относительно входа
        return np.fft.rfft(seg, nfft) * np.exp(-2j * np.pi * f * (a - c) / fs)

    r1 = win(href, 1)
    hk = {k: np.abs(win(hout, k)) / np.maximum(np.abs(r1), 1e-300) for k in range(2, 10)}
    return SweepIR(f, win(hout, 1) / r1, hk)


def smooth_at(f: Arr, mag2: Arr, points: Arr, frac: int = 12) -> Arr:
    """Среднее |.|^2 в окне 1/frac октавы вокруг каждой точки (для кривых свипа)."""
    return np.array([mag2[m].mean() if m.any() else np.nan for m in band_masks(f, frac, points)])
