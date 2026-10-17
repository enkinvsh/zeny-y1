package com.themoon.y1.managers;

/**
 * Превращает FFT от android.media.audiofx.Visualizer в уровни логарифмических полос 0..1 для столбиков.
 *
 * <p>Формат Visualizer: fft[0] постоянная составляющая, fft[1] Найквист, далее пары (re, im) для
 * бинов 1..N/2-1, байты со знаком. Чистая Java без Android, чтобы проверять юнит-тестами.
 *
 * <p>Наклон +3 дБ на октаву нужен потому, что у музыки и розового шума амплитуда бина падает на 3 дБ
 * на октаву: без компенсации басы занимают всю высоту, а верх остаётся точками.
 */
public final class SpectrumBands {
    // Динамический диапазон в дБ над магнитудой бина (8 бит даёт максимум около 181 -> 45 дБ).
    private static final float FLOOR_DB = 10f;
    private static final float CEIL_DB = 44f;
    // Частота, на которой наклон равен нулю: середина слышимого диапазона.
    private static final float TILT_PIVOT_HZ = 1000f;

    private final int count;
    private final float[] edgesHz;
    private final float[] tiltDb;

    // Раскладка полос по бинам зависит только от размера FFT и частоты дискретизации, поэтому
    // пересчитывается при их смене, а не на каждом кадре (кадры идут около 20 раз в секунду).
    private int cachedSize = -1;
    private int cachedRate = -1;
    private final int[] first;
    private final int[] last;
    private final int[] interpBase;
    private final float[] interpFrac;

    public SpectrumBands(int count, float minHz, float maxHz, float tiltDbPerOctave) {
        this.count = count;
        edgesHz = new float[count + 1];
        tiltDb = new float[count];
        double ratio = Math.pow(maxHz / (double) minHz, 1.0 / count);
        for (int i = 0; i <= count; i++) edgesHz[i] = (float) (minHz * Math.pow(ratio, i));
        for (int i = 0; i < count; i++) {
            double center = Math.sqrt(edgesHz[i] * (double) edgesHz[i + 1]);
            tiltDb[i] = (float) (tiltDbPerOctave * Math.log(center / TILT_PIVOT_HZ) / Math.log(2.0));
        }
        first = new int[count];
        last = new int[count];
        interpBase = new int[count];
        interpFrac = new float[count];
    }

    public int count() {
        return count;
    }

    /** Заполняет out[0..count) уровнями 0..1; при пустом или битом входе нули. */
    public void compute(byte[] fft, int sampleRateMilliHz, float[] out) {
        for (int i = 0; i < count && i < out.length; i++) out[i] = 0f;
        if (fft == null || fft.length < 8 || sampleRateMilliHz <= 0) return;
        if (fft.length != cachedSize || sampleRateMilliHz != cachedRate) layout(fft.length, sampleRateMilliHz);

        int bins = fft.length / 2;
        for (int i = 0; i < count && i < out.length; i++) {
            float m;
            if (first[i] <= last[i]) {
                m = 0f;
                for (int k = first[i]; k <= last[i]; k++) {
                    float v = magnitude(fft, k);
                    if (v > m) m = v;
                }
            } else {
                // Полоса уже одного бина: берём линейную интерполяцию, иначе соседние нижние столбики совпали бы.
                int k = interpBase[i];
                float a = magnitude(fft, k);
                float b = k + 1 < bins ? magnitude(fft, k + 1) : a;
                m = a + (b - a) * interpFrac[i];
            }
            // Ниже единицы байт-кванта сигнала нет; без этой отсечки наклон поднимал бы тишину на верхних полосах.
            if (m < 1f) continue;
            float db = (float) (20.0 * Math.log10(m + 1.0));
            float level = (db + tiltDb[i] - FLOOR_DB) / (CEIL_DB - FLOOR_DB);
            out[i] = level < 0f ? 0f : (level > 1f ? 1f : level);
        }
    }

    private static float magnitude(byte[] fft, int bin) {
        float re = fft[2 * bin];
        float im = fft[2 * bin + 1];
        return (float) Math.sqrt(re * re + im * im);
    }

    private void layout(int size, int rateMilliHz) {
        cachedSize = size;
        cachedRate = rateMilliHz;
        int bins = size / 2;
        float binHz = rateMilliHz / 1000f / size;
        for (int i = 0; i < count; i++) {
            float kLo = edgesHz[i] / binHz;
            float kHi = edgesHz[i + 1] / binHz;
            int f = Math.max(1, (int) Math.ceil(kLo));
            int l = Math.min(bins - 1, (int) Math.floor(kHi));
            first[i] = f;
            last[i] = l;
            float kc = (float) Math.sqrt(kLo * (double) kHi);
            kc = Math.max(1f, Math.min(bins - 1f, kc));
            interpBase[i] = (int) kc;
            interpFrac[i] = kc - (int) kc;
        }
    }
}
