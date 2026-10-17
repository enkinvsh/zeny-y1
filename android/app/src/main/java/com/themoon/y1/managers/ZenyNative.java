package com.themoon.y1.managers;

import java.nio.ByteBuffer;

/**
 * Мост к нативному ядру DSP (cpp/zenydsp.c). Если библиотека не загрузилась (эмулятор другой архитектуры,
 * не доложенный в /system/lib файл), {@link #AVAILABLE} остаётся false и ZenyAudioProcessor работает на Java.
 *
 * Константы FP_* и IP_* обязаны совпадать с #define в zenydsp.c.
 */
final class ZenyNative {
    static final boolean AVAILABLE;

    static final int FP_BASE_GAIN = 0;
    static final int FP_CF_MIX = 1;
    static final int FP_AGC_ATTACK = 2;
    static final int FP_AGC_RELEASE = 3;
    static final int FP_AGC_GAIN_COEFF = 4;
    static final int FP_SUB_COEFF = 5;
    static final int FP_DC_COEFF = 6;
    static final int FP_CF_COEFF = 7;
    static final int FP_RUNNING_POWER = 8;
    static final int FP_AGC_GAIN = 9;
    static final int FP_EQ_COEF = 10;
    static final int FP_NAT_COEF = 60;
    static final int FP_LEN = 80;

    static final int IP_CHANNELS = 0;
    static final int IP_ENCODING = 1;
    static final int IP_DO_NATURAL = 2;
    static final int IP_DO_EQ = 3;
    static final int IP_DO_AGC = 4;
    static final int IP_DO_CROSSFEED = 5;
    static final int IP_RES_MODE = 6;
    static final int IP_POLY_NUM = 7;
    static final int IP_POLY_DEN = 8;
    static final int IP_POLY_HALF = 9;
    static final int IP_POLY_TAPS = 10;
    static final int IP_RING_MASK = 11;
    static final int IP_DELAY = 12;
    static final int IP_RNG = 13;
    static final int IP_FRAMES = 14;
    static final int IP_IN_RATE = 15;
    static final int IP_OUT_RATE = 16;
    static final int IP_EQ_ACTIVE = 17;
    static final int IP_MAX_OUT = 27;
    static final int IP_LEN = 28;

    static {
        boolean ok;
        try {
            System.loadLibrary("zenydsp");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private ZenyNative() {}

    static native int stateBytes();

    static native void reset(ByteBuffer state);

    /** Обрабатывает кадры из in (с байтового смещения inOffset) и пишет 16-битный выход в out; возвращает число кадров. */
    static native int process(ByteBuffer state, ByteBuffer in, int inOffset, ByteBuffer out, ByteBuffer taps,
                              float[] fp, int[] ip);
}
