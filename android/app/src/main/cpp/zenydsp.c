/*
 * Нативное ядро ZenyAudioProcessor.
 *
 * Зачем нативный код: на Innioasis Y1 (MT6572, Cortex-A7 1 ГГц, Android 4.2 / Dalvik) тот же цикл на Java
 * занимал ~70% одного ядра при воспроизведении (замер на устройстве, 10 с стерео 48 кГц). Процессор
 * весь трек держал максимальную частоту, что бьёт по батарее. В C тот же цикл укладывается в единицы процентов.
 *
 * Ограничения, которые определяют вид файла:
 *  - Библиотека грузится на API 17, а NDK 28 собирает с минимумом 21, поэтому здесь нет вызовов libc/libm
 *    (ни memset, ни sqrtf, ни floorf): только арифметика и JNI. `nm -u` должен быть пустым.
 *  - Порядок операций и формулы повторяют Java-реализацию ZenyAudioProcessor построчно. Java остаётся
 *    эталоном и запасным путём, тест сравнивает оба результата.
 *  - Раскладка параметров (FP_*, IP_*) обязана совпадать с константами в ZenyNative.java.
 */
#include <jni.h>
#include <stddef.h>
#include <stdint.h>

#define FP_BASE_GAIN 0
#define FP_STAGE_G 1
#define FP_AGC_ATTACK 2
#define FP_AGC_RELEASE 3
#define FP_AGC_GAIN_COEFF 4
#define FP_STAGE_COEFF 5
#define FP_RUNNING_POWER 6 /* FP_RUNNING_POWER и FP_AGC_GAIN идут подряд: возвращаются в Java одной записью */
#define FP_AGC_GAIN 7
#define FP_AIR_COEF 8   /* один фильтр первого порядка в биквад-форме: b0 b1 b2 a1 a2 */
#define FP_LEN 13

#define IP_CHANNELS 0
#define IP_ENCODING 1
#define IP_DO_AIR 2
#define IP_DO_AGC 3
#define IP_DO_STAGE 4
#define IP_RES_MODE 5   /* 0 напрямую, 1 полифазный sinc, 2 кубический Эрмит */
#define IP_POLY_NUM 6
#define IP_POLY_DEN 7
#define IP_POLY_HALF 8
#define IP_POLY_TAPS 9
#define IP_RING_MASK 10
#define IP_RNG 11
#define IP_FRAMES 12
#define IP_IN_RATE 13
#define IP_OUT_RATE 14
#define IP_MAX_OUT 15   /* ёмкость выходного буфера в кадрах: запись за её пределы в нативном коде не отловит никто */
#define IP_LEN 16

/* Значения совпадают с C.ENCODING_PCM_* из ExoPlayer 2.13 */
#define ENC_16 2
#define ENC_8 3
#define ENC_24 536870912
#define ENC_32 805306368
#define ENC_FLOAT 4

#define RING_MAX 512 /* максимум кадров в кольце входа; хранится в двойном размере для линейного чтения */

typedef struct {
    float z1, z2;
} Bq;

typedef struct {
    Bq air[2];
    float stageLpL, stageLpR;
    float hist[2][4];
    double resPhase;
    int32_t polyPhase;
    int64_t polyIn, polyNewest;
    float ringL[RING_MAX * 2], ringR[RING_MAX * 2];
} State;

static State *state_of(void *addr) {
    /* Память direct-буфера выровнена только гарантированно на 8 байт; double и NEON-загрузки удобнее на 16 */
    return (State *)(((uintptr_t)addr + 15u) & ~(uintptr_t)15u);
}

static inline float bq(const float *c, Bq *s, float x) {
    float y = c[0] * x + s->z1;
    s->z1 = c[1] * x - c[3] * y + s->z2;
    s->z2 = c[2] * x - c[4] * y;
    return y;
}

static inline float hermite4(float t, float y0, float y1, float y2, float y3) {
    float c0 = y1;
    float c1 = 0.5f * (y2 - y0);
    float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
    float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
    return ((c3 * t + c2) * t + c1) * t + c0;
}

static inline float soft_clip(float x) {
    float ax = x < 0.0f ? -x : x;
    if (ax <= 0.82f) return x;
    float excess = (ax - 0.82f) * (1.0f / 0.18f);
    float sat = 0.82f + 0.18f * (excess / (1.0f + excess));
    return x < 0.0f ? -sat : sat;
}

/* Квантование в 16 бит с TPDF-дизерингом; округление как у Math.round: floor(x + 0.5) */
static inline int16_t quantize(float sample, uint32_t *rng) {
    *rng = *rng * 1664525u + 1013904223u;
    int32_t r1 = ((int32_t)*rng) >> 16;
    *rng = *rng * 1664525u + 1013904223u;
    int32_t r2 = ((int32_t)*rng) >> 16;
    float dither = (float)(r1 - r2) * (1.0f / 65536.0f);
    float t = sample * 32767.0f + dither + 0.5f;
    int32_t q = (int32_t)t;
    if ((float)q > t) q--;
    if (q > 32767) q = 32767;
    else if (q < -32768) q = -32768;
    return (int16_t)q;
}

static inline float read_sample(const uint8_t *p, int enc) {
    switch (enc) {
    case ENC_FLOAT: {
        union { uint32_t u; float f; } v;
        v.u = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        float f = v.f;
        if (f != f || f > 3.0e38f || f < -3.0e38f) return 0.0f;
        return f;
    }
    case ENC_16: {
        int16_t s = (int16_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8));
        return (float)s * (1.0f / 32768.0f);
    }
    case ENC_24: {
        int32_t s = (int32_t)(((uint32_t)p[0] << 8) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 24)) >> 8;
        return (float)s * (1.0f / 8388608.0f);
    }
    case ENC_32: {
        int32_t s = (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24));
        return (float)s * (1.0f / 2147483648.0f);
    }
    case ENC_8:
        return (float)((int)p[0] - 128) * (1.0f / 128.0f);
    default:
        return 0.0f;
    }
}

static inline int bytes_per_sample(int enc) {
    switch (enc) {
    case ENC_8: return 1;
    case ENC_16: return 2;
    case ENC_24: return 3;
    default: return 4;
    }
}

JNIEXPORT jint JNICALL Java_com_themoon_y1_managers_ZenyNative_stateBytes(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jint)(sizeof(State) + 16);
}

JNIEXPORT void JNICALL Java_com_themoon_y1_managers_ZenyNative_reset(JNIEnv *env, jclass cls, jobject stateBuf) {
    (void)cls;
    State *s = state_of((*env)->GetDirectBufferAddress(env, stateBuf));
    /* memset недоступен (нет libc), поэтому обнуляем словами */
    uint32_t *w = (uint32_t *)s;
    for (size_t i = 0; i < sizeof(State) / 4; i++) w[i] = 0;
    /* Индекс последнего принятого кадра: до первого входа его нет, как и в Java-реализации */
    s->polyNewest = -1;
}

JNIEXPORT jint JNICALL Java_com_themoon_y1_managers_ZenyNative_process(JNIEnv *env, jclass cls, jobject stateBuf,
                                                                       jobject inBuf, jint inOffset, jobject outBuf,
                                                                       jobject tapsBuf, jfloatArray fpArr,
                                                                       jintArray ipArr) {
    (void)cls;
    float fp[FP_LEN];
    jint ip[IP_LEN];
    (*env)->GetFloatArrayRegion(env, fpArr, 0, FP_LEN, fp);
    (*env)->GetIntArrayRegion(env, ipArr, 0, IP_LEN, ip);

    State *st = state_of((*env)->GetDirectBufferAddress(env, stateBuf));
    const uint8_t *in = (const uint8_t *)(*env)->GetDirectBufferAddress(env, inBuf) + inOffset;
    int16_t *out = (int16_t *)(*env)->GetDirectBufferAddress(env, outBuf);
    const float *taps = tapsBuf ? (const float *)(*env)->GetDirectBufferAddress(env, tapsBuf) : (const float *)0;

    const int channels = ip[IP_CHANNELS];
    const int enc = ip[IP_ENCODING];
    const int bps = bytes_per_sample(enc);
    const int frames = ip[IP_FRAMES];
    const int doAir = ip[IP_DO_AIR];
    const int doAgc = ip[IP_DO_AGC];
    const int doStage = ip[IP_DO_STAGE];
    const int resMode = ip[IP_RES_MODE];
    const int polyNum = ip[IP_POLY_NUM];
    const int polyDen = ip[IP_POLY_DEN];
    const int half = ip[IP_POLY_HALF];
    const int tapCount = ip[IP_POLY_TAPS];
    const int mask = ip[IP_RING_MASK];
    const int ringSize = mask + 1;
    const double hermiteStep = (ip[IP_OUT_RATE] > 0) ? (double)ip[IP_IN_RATE] / (double)ip[IP_OUT_RATE] : 1.0;
    const int maxOut = ip[IP_MAX_OUT];
    uint32_t rng = (uint32_t)ip[IP_RNG];

    const float baseGain = fp[FP_BASE_GAIN];
    const float stageG = fp[FP_STAGE_G];
    const float agcAttack = fp[FP_AGC_ATTACK];
    const float agcRelease = fp[FP_AGC_RELEASE];
    const float agcGainCoeff = fp[FP_AGC_GAIN_COEFF];
    const float stageCoeff = fp[FP_STAGE_COEFF];
    float runningPower = fp[FP_RUNNING_POWER];
    float agcGain = fp[FP_AGC_GAIN];

    int outFrames = 0;

    for (int f = 0; f < frames; f++) {
        const uint8_t *fr = in + (size_t)f * (size_t)channels * (size_t)bps;
        float L = read_sample(fr, enc);
        float R = (channels == 2) ? read_sample(fr + bps, enc) : L;

        float g = baseGain;
        if (doAgc) {
            float p = 0.5f * (L * L + R * R);
            float alpha = (p > runningPower) ? agcAttack : agcRelease;
            runningPower += alpha * (p - runningPower);
            if (runningPower > 0.00003f) {
                float desired = __builtin_sqrtf(0.022f / runningPower);
                if (desired < 0.56f) desired = 0.56f;
                else if (desired > 1.26f) desired = 1.26f;
                agcGain += agcGainCoeff * (desired - agcGain);
            }
            g *= agcGain;
        }
        L *= g;
        R *= g;

        if (doAir) {
            L = bq(&fp[FP_AIR_COEF], &st->air[0], L);
            if (channels == 2) R = bq(&fp[FP_AIR_COEF], &st->air[1], R);
        }

        if (doStage) {
            /* Запись через разность: при L=R фильтры равны, и выход совпадает с входом побитово */
            st->stageLpL += stageCoeff * (L - st->stageLpL);
            st->stageLpR += stageCoeff * (R - st->stageLpR);
            float outL = L + stageG * (st->stageLpR - st->stageLpL);
            float outR = R + stageG * (st->stageLpL - st->stageLpR);
            L = outL;
            R = outR;
        }

        if (resMode == 1) {
            /* Кольцо хранится дважды подряд, поэтому окно из tapCount отсчётов читается линейно без маски */
            int64_t newest = st->polyNewest + 1;
            int wi = (int)(newest & mask);
            float rr = (channels == 2) ? R : L;
            st->ringL[wi] = L;
            st->ringL[wi + ringSize] = L;
            st->ringR[wi] = rr;
            st->ringR[wi + ringSize] = rr;
            st->polyNewest = newest;
            while (st->polyIn + half <= st->polyNewest && outFrames < maxOut) {
                const float *row = taps + (size_t)st->polyPhase * (size_t)tapCount;
                int start = (int)((st->polyIn - (half - 1)) & mask);
                const float *xl = &st->ringL[start];
                float a0 = 0.0f, a1 = 0.0f, a2 = 0.0f, a3 = 0.0f;
                if (channels == 2) {
                    const float *xr = &st->ringR[start];
                    float b0 = 0.0f, b1 = 0.0f, b2 = 0.0f, b3 = 0.0f;
                    int k = 0;
                    for (; k + 4 <= tapCount; k += 4) {
                        a0 += row[k] * xl[k];
                        a1 += row[k + 1] * xl[k + 1];
                        a2 += row[k + 2] * xl[k + 2];
                        a3 += row[k + 3] * xl[k + 3];
                        b0 += row[k] * xr[k];
                        b1 += row[k + 1] * xr[k + 1];
                        b2 += row[k + 2] * xr[k + 2];
                        b3 += row[k + 3] * xr[k + 3];
                    }
                    for (; k < tapCount; k++) {
                        a0 += row[k] * xl[k];
                        b0 += row[k] * xr[k];
                    }
                    out[outFrames * 2] = quantize(soft_clip((a0 + a1) + (a2 + a3)), &rng);
                    out[outFrames * 2 + 1] = quantize(soft_clip((b0 + b1) + (b2 + b3)), &rng);
                } else {
                    int k = 0;
                    for (; k + 4 <= tapCount; k += 4) {
                        a0 += row[k] * xl[k];
                        a1 += row[k + 1] * xl[k + 1];
                        a2 += row[k + 2] * xl[k + 2];
                        a3 += row[k + 3] * xl[k + 3];
                    }
                    for (; k < tapCount; k++) a0 += row[k] * xl[k];
                    out[outFrames] = quantize(soft_clip((a0 + a1) + (a2 + a3)), &rng);
                }
                outFrames++;
                /* Вычитание вместо деления: на базовом ARMv7 деление int превращается в вызов
                   __aeabi_idiv, а в библиотеке без libc такого символа нет */
                st->polyPhase += polyNum;
                while (st->polyPhase >= polyDen) {
                    st->polyPhase -= polyDen;
                    st->polyIn++;
                }
            }
        } else if (resMode == 2) {
            st->hist[0][0] = st->hist[0][1];
            st->hist[0][1] = st->hist[0][2];
            st->hist[0][2] = st->hist[0][3];
            st->hist[0][3] = L;
            st->hist[1][0] = st->hist[1][1];
            st->hist[1][1] = st->hist[1][2];
            st->hist[1][2] = st->hist[1][3];
            st->hist[1][3] = R;
            while (st->resPhase < 1.0 && outFrames < maxOut) {
                float t = (float)st->resPhase;
                float oL = hermite4(t, st->hist[0][0], st->hist[0][1], st->hist[0][2], st->hist[0][3]);
                if (channels == 2) {
                    float oR = hermite4(t, st->hist[1][0], st->hist[1][1], st->hist[1][2], st->hist[1][3]);
                    out[outFrames * 2] = quantize(soft_clip(oL), &rng);
                    out[outFrames * 2 + 1] = quantize(soft_clip(oR), &rng);
                } else {
                    out[outFrames] = quantize(soft_clip(oL), &rng);
                }
                outFrames++;
                st->resPhase += hermiteStep;
            }
            st->resPhase -= 1.0;
        } else if (outFrames < maxOut) {
            if (channels == 2) {
                out[outFrames * 2] = quantize(soft_clip(L), &rng);
                out[outFrames * 2 + 1] = quantize(soft_clip(R), &rng);
            } else {
                out[outFrames] = quantize(soft_clip(L), &rng);
            }
            outFrames++;
        }
    }

    fp[FP_RUNNING_POWER] = runningPower;
    fp[FP_AGC_GAIN] = agcGain;
    ip[IP_RNG] = (jint)rng;
    (*env)->SetFloatArrayRegion(env, fpArr, FP_RUNNING_POWER, 2, &fp[FP_RUNNING_POWER]);
    (*env)->SetIntArrayRegion(env, ipArr, IP_RNG, 1, &ip[IP_RNG]);
    return (jint)outFrames;
}
