package com.themoon.y1.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SpectrumBandsTest {
    private static final int SIZE = 1024;
    private static final int RATE = 44100000; // миллигерцы, как отдаёт android.media.audiofx.Visualizer

    /** Формат Visualizer: fft[0] постоянная составляющая, fft[1] Найквист, дальше пары (re, im) для бинов 1..N/2-1. */
    private static byte[] spectrum(double amplitudeAtBin1, double exponent) {
        byte[] fft = new byte[SIZE];
        for (int k = 1; k < SIZE / 2; k++) {
            double a = amplitudeAtBin1 * Math.pow(k, exponent);
            fft[2 * k] = (byte) Math.max(-128, Math.min(127, Math.round(a)));
        }
        return fft;
    }

    @Test
    public void silenceGivesZeroEverywhere() {
        SpectrumBands bands = new SpectrumBands(24, 80f, 14000f, 3f);
        float[] out = new float[24];
        java.util.Arrays.fill(out, 0.7f);
        bands.compute(new byte[SIZE], RATE, out);
        for (float v : out) assertEquals(0f, v, 0f);
    }

    @Test
    public void nullAndTooShortInputDoNotThrow() {
        SpectrumBands bands = new SpectrumBands(24, 80f, 14000f, 3f);
        float[] out = new float[24];
        bands.compute(null, RATE, out);
        bands.compute(new byte[2], RATE, out);
        bands.compute(new byte[SIZE], 0, out);
        for (float v : out) assertEquals(0f, v, 0f);
    }

    @Test
    public void levelsStayInsideUnitRange() {
        SpectrumBands bands = new SpectrumBands(24, 80f, 14000f, 3f);
        byte[] loud = new byte[SIZE];
        java.util.Arrays.fill(loud, (byte) 127);
        float[] out = new float[24];
        bands.compute(loud, RATE, out);
        for (float v : out) assertTrue(v >= 0f && v <= 1f);
    }

    /**
     * Розовый шум: амплитуда бина падает как 1/sqrt(f), то есть на 3 дБ за октаву. С наклоном +3 дБ/окт
     * столбики должны выровняться; без наклона басы давили бы верх.
     */
    @Test
    public void tiltFlattensPinkNoise() {
        byte[] pink = spectrum(120, -0.5);
        float[] tilted = new float[24];
        float[] flat = new float[24];
        new SpectrumBands(24, 80f, 14000f, 3f).compute(pink, RATE, tilted);
        new SpectrumBands(24, 80f, 14000f, 0f).compute(pink, RATE, flat);

        float spreadTilted = spread(tilted, 2, 22);
        float spreadFlat = spread(flat, 2, 22);
        assertTrue("с наклоном разброс " + spreadTilted + " должен быть меньше 0.15", spreadTilted < 0.15f);
        assertTrue("без наклона разброс " + spreadFlat + " заметно больше", spreadFlat > spreadTilted * 2f);
    }

    @Test
    public void neighbouringLowBandsDifferWhenBinsAreWiderThanBands() {
        // Нижние полосы уже одного бина (43 Гц); без интерполяции они показали бы одно и то же число.
        byte[] fft = new byte[SIZE];
        fft[2 * 2] = 90; // бин 2, около 86 Гц
        fft[2 * 3] = 30; // бин 3, около 129 Гц
        float[] out = new float[24];
        new SpectrumBands(24, 80f, 14000f, 0f).compute(fft, RATE, out);
        boolean differs = false;
        for (int i = 1; i < 6; i++) if (Math.abs(out[i] - out[i - 1]) > 0.01f) differs = true;
        assertTrue(differs);
    }

    @Test
    public void sampleRateMovesBandsAcrossBins() {
        // На 22,05 кГц бин вдвое уже: тот же сигнал в бине 40 попадает в более низкую полосу, чем на 44,1 кГц.
        byte[] fft = new byte[SIZE];
        fft[2 * 40] = 100;
        float[] a = new float[24];
        float[] b = new float[24];
        SpectrumBands bands = new SpectrumBands(24, 80f, 14000f, 0f);
        bands.compute(fft, 44100000, a);
        bands.compute(fft, 22050000, b);
        assertTrue(peak(b) < peak(a));
    }

    private static float spread(float[] v, int from, int to) {
        float lo = 1f, hi = 0f;
        for (int i = from; i <= to; i++) {
            lo = Math.min(lo, v[i]);
            hi = Math.max(hi, v[i]);
        }
        return hi - lo;
    }

    private static int peak(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }
}
