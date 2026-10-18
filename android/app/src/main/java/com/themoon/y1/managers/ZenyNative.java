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
    static final int FP_STAGE_G = 1;
    static final int FP_AGC_ATTACK = 2;
    static final int FP_AGC_RELEASE = 3;
    static final int FP_AGC_GAIN_COEFF = 4;
    static final int FP_STAGE_COEFF = 5;
    static final int FP_RUNNING_POWER = 6;
    static final int FP_AGC_GAIN = 7;
    static final int FP_AIR_COEF = 8;
    static final int FP_LEN = 13;

    static final int IP_CHANNELS = 0;
    static final int IP_ENCODING = 1;
    static final int IP_DO_AIR = 2;
    static final int IP_DO_AGC = 3;
    static final int IP_DO_STAGE = 4;
    static final int IP_RES_MODE = 5;
    static final int IP_POLY_NUM = 6;
    static final int IP_POLY_DEN = 7;
    static final int IP_POLY_HALF = 8;
    static final int IP_POLY_TAPS = 9;
    static final int IP_RING_MASK = 10;
    static final int IP_RNG = 11;
    static final int IP_FRAMES = 12;
    static final int IP_IN_RATE = 13;
    static final int IP_OUT_RATE = 14;
    static final int IP_MAX_OUT = 15;
    static final int IP_LEN = 16;

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
