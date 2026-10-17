package com.themoon.y1.managers;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Единый 32-битный float мастеринг-процессор Zeny для Innioasis Y1.
 *
 * Зачем нужен отдельный конвейер:
 * 1) Декодер libopus в режиме float отдаёт сэмплы на 48 кГц с интерсемпловыми пиками > 0 dBFS,
 *    которые в 16-битном режиме жёстко срезались ещё внутри декодера.
 * 2) Аудиовыход Y1 (AudioFlinger primary и A2DP) работает только на 44.1 кГц; собственный
 *    4-точечный кубический ресемплер Эрмита переводит 48 -> 44.1 кГц в 32-bit float,
 *    избавляя тракт от примитивного линейного ресемплера Android 4.2.
 * 3) Профиль Zeny Natural компенсирует психоакустические потери сжатого Opus (~130 кбит/с):
 *    возвращает чётную гармонику суббаса (< 95 Гц), убирает цифровую колкость на 3.85/6.2 кГц,
 *    добавляет воздух выше 13.5 кГц и строит бинауральную сцену Бауэра с межуншной задержкой 250 мкс.
 * 4) На выходе мягкий Padé-tanh лимитер защищает пики, а TPDF-дизеринг убирает ступеньки квантования 16 бит.
 */
public class ZenyAudioProcessor implements AudioProcessor {

    public static final int TARGET_SAMPLE_RATE = 44100;

    private static final float[] EQ_FREQS = {
        31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    };

    // Межуншная задержка по модели Бауэра (~250 мкс = 12 отсчётов на 48 кГц, 11 на 44.1 кГц)
    private static final int DELAY_RING_MASK = 15;

    private AudioFormat pendingInputFormat = AudioFormat.NOT_SET;
    private AudioFormat pendingOutputFormat = AudioFormat.NOT_SET;
    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private AudioFormat outputFormat = AudioFormat.NOT_SET;

    private ByteBuffer buffer = EMPTY_BUFFER;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded = false;

    private volatile boolean zenyNaturalEnabled = true;
    private volatile boolean softwareEqEnabled = false;
    private volatile boolean soundCheckEnabled = false;
    private volatile int crossfeedStep = 0;

    // Усиление текущего трека по тегу R128/ReplayGain (в дБ относительно -17 LUFS); Float.NaN если тега нет
    private volatile float taggedGainDb = Float.NaN;
    private volatile float targetStaticGainLinear = 0.871f; // -1.2 дБ запаса под интерсемпловые пики Opus

    // Медленный гейтированный измеритель громкости для треков без тега R128
    private float runningPower = 0.025f;
    private float agcGainLinear = 0.89f;
    // Коэффициенты однополюсных сглаживателей: мощность 3 с вверх / 6 с вниз, усиление 1.5 с (значения для 48 кГц)
    private float agcAttackCoeff = 7.0e-6f;
    private float agcReleaseCoeff = 3.5e-6f;
    private float agcGainCoeff = 1.4e-5f;

    // 10-полосный графический эквалайзер (по 2 канала)
    private final float[] eqGainsDb = new float[10];
    private final boolean[] eqBandActive = new boolean[10];
    private final Biquad[][] eqFilters = new Biquad[2][10];
    private volatile boolean anyEqBandActive = false;

    // Фильтры Zeny Natural: 0 = фундамент 62 Гц, 1 = de-harsh 3.85 кГц, 2 = сибилянты 6.2 кГц, 3 = воздух 13.5 кГц
    private final Biquad[][] naturalFilters = new Biquad[2][4];

    // Однополюсный фильтр выделения суббаса (< 95 Гц) и DC-блокер (18 Гц) для чётного гармонического насыщения
    private float subLpCoeff = 0.012f;
    private float dcHpCoeff = 0.9975f;
    private float subStateL = 0.0f, subStateR = 0.0f;
    private float dcInL = 0.0f, dcOutL = 0.0f, dcInR = 0.0f, dcOutR = 0.0f;

    // Бинауральный Crossfeed Бауэра с линией задержки и ВЧ-компенсацией прямого сигнала
    private float cfLpCoeff = 0.085f;
    private float cfLpL = 0.0f, cfLpR = 0.0f;
    private final float[] delayL = new float[16];
    private final float[] delayR = new float[16];
    private int delayWriteIdx = 0;
    private int delaySamples = 12;

    // Ресемплер на выходную частоту 44.1 кГц. Основной путь: полифазный оконный sinc с точными строками
    // для рационального отношения частот (48000 -> 44100 даёт ровно 147 фаз). Кубический Эрмит нужен
    // только как запасной вариант для нестандартных частот, где таблица получилась бы слишком большой.
    private boolean resampleActive = false;
    private double resampleStep = 1.0;
    private double resamplePhase = 0.0;
    private float h0L, h1L, h2L, h3L;
    private float h0R, h1R, h2R, h3R;

    private static final int POLY_MAX_DEN = 1024;
    private static final int POLY_MAX_TABLE = 262144;
    private static final int POLY_MAX_RING = 512;
    private float[] polyTaps = null; // строки фаз подряд: [фаза * polyTapCount + отвод]
    private int polyNum = 1, polyDen = 1, polyHalf = 0, polyTapCount = 0;
    private int polyBuiltIn = -1, polyBuiltOut = -1;
    private int polyPhase = 0;
    private long polyInIndex = 0;  // индекс входного кадра слева от текущей выходной точки
    private long polyNewest = -1;  // индекс последнего принятого входного кадра
    private float[] ringL = new float[0], ringR = new float[0];
    private int ringMask = 0;

    // Состояние генератора треугольного дизеринга (TPDF)
    private int ditherRng = 0x6D2B79F5;

    // Нативное ядро (cpp/zenydsp.c). Java-цикл остаётся эталоном и запасным путём (нет .so, чужая
    // архитектура): замер на Y1 показал ~70% ядра на Dalvik, для батареи это неприемлемо.
    private boolean nativeWanted = true;
    private boolean nativeActive = false;
    private ByteBuffer nativeState = null;
    private ByteBuffer polyTapsBuf = null;
    private ByteBuffer nativeIn = null;
    private final float[] nativeFp = new float[ZenyNative.FP_LEN];
    private final int[] nativeIp = new int[ZenyNative.IP_LEN];

    public ZenyAudioProcessor() {
        for (int ch = 0; ch < 2; ch++) {
            for (int b = 0; b < 10; b++) {
                eqFilters[ch][b] = new Biquad();
            }
            for (int i = 0; i < 4; i++) {
                naturalFilters[ch][i] = new Biquad();
            }
        }
    }

    public void setZenyNaturalEnabled(boolean enabled) {
        this.zenyNaturalEnabled = enabled;
        recomputeStaticGain();
    }

    public boolean isZenyNaturalEnabled() {
        return zenyNaturalEnabled;
    }

    /** Разрешает нативное ядро; решение применяется при следующем flush(). Нужен тестам для сравнения с Java. */
    public void setNativeEnabled(boolean enabled) {
        this.nativeWanted = enabled;
    }

    public boolean isNativeActive() {
        return nativeActive;
    }

    public void setSoftwareEqEnabled(boolean enabled) {
        this.softwareEqEnabled = enabled;
        recomputeStaticGain();
    }

    public void setSoundCheckEnabled(boolean enabled) {
        this.soundCheckEnabled = enabled;
        recomputeStaticGain();
    }

    public void setCrossfeedStep(int step) {
        this.crossfeedStep = Math.max(0, Math.min(3, step));
    }

    /**
     * Передаёт измеренное по тегу усиление трека в дБ (или null, если тега в файле нет).
     */
    public void setTrackGainDb(Float gainDb) {
        this.taggedGainDb = (gainDb != null && !Float.isNaN(gainDb) && !Float.isInfinite(gainDb))
                ? gainDb : Float.NaN;
        this.runningPower = 0.025f;
        this.agcGainLinear = 0.89f;
        recomputeStaticGain();
    }

    public void setBandLevel(int bandIndex, float dbGain) {
        if (bandIndex < 0 || bandIndex >= 10) return;
        eqGainsDb[bandIndex] = dbGain;
        eqBandActive[bandIndex] = Math.abs(dbGain) >= 0.05f;
        boolean any = false;
        for (int i = 0; i < 10; i++) {
            if (eqBandActive[i]) {
                any = true;
                break;
            }
        }
        anyEqBandActive = any;
        int fs = inputFormat.sampleRate > 0 ? inputFormat.sampleRate : 48000;
        configureEqBand(bandIndex, fs);
        recomputeStaticGain();
    }

    private void recomputeStaticGain() {
        // Запас 1.2 дБ под интерсемпловые пики сжатого аудио. Профиль Zeny Natural сознательно не меняет
        // уровень середины (1-2 кГц): включая его, слушатель сравнивает тембр, а не «кто громче».
        float db = -1.2f;
        if (softwareEqEnabled && anyEqBandActive) {
            float maxBoost = 0.0f;
            for (int i = 0; i < 10; i++) {
                if (eqGainsDb[i] > maxBoost) maxBoost = eqGainsDb[i];
            }
            if (maxBoost > 1.5f) {
                db -= Math.min(6.0f, (maxBoost - 1.5f) * 0.55f);
            }
        }
        if (soundCheckEnabled && !Float.isNaN(taggedGainDb)) {
            float clampedTag = Math.max(-9.5f, Math.min(4.0f, taggedGainDb));
            db += clampedTag;
        }
        targetStaticGainLinear = (float) Math.pow(10.0, db / 20.0);
    }

    private void configureFilters(int fs) {
        if (fs <= 0) return;
        for (int b = 0; b < 10; b++) {
            configureEqBand(b, fs);
        }
        // Профиль Zeny Natural:
        // 0) Тёплый фундамент низа 62 Гц (+1.5 дБ, Q=0.72) без раздувания мидбаса 140-200 Гц
        // 1) Снятие цифровой рези кодека в зоне максимальной чувствительности уха 3850 Гц (-1.5 дБ, Q=1.65)
        // 2) Мягкое приглаживание жёстких сибилянтов 6200 Гц (-0.8 дБ, Q=2.0)
        // 3) Возврат «воздуха» и затуханий тарелок выше 13.5 кГц (+1.7 дБ, Q=0.707)
        float airFreq = Math.min(13500f, fs * 0.44f);
        for (int ch = 0; ch < 2; ch++) {
            naturalFilters[ch][0].setPeakingEQ(fs, 62f, 0.72f, 1.5f);
            naturalFilters[ch][1].setPeakingEQ(fs, 3850f, 1.65f, -1.5f);
            naturalFilters[ch][2].setPeakingEQ(fs, 6200f, 2.0f, -0.8f);
            naturalFilters[ch][3].setHighShelf(fs, airFreq, 0.707f, 1.7f);
        }

        // Коэффициенты однополюсных фильтров суббаса (95 Гц), DC-блокера (18 Гц) и кроссфида Бауэра (700 Гц)
        subLpCoeff = onePoleLpCoeff(fs, 95.0f);
        dcHpCoeff = (float) Math.exp(-2.0 * Math.PI * 18.0 / fs);
        cfLpCoeff = onePoleLpCoeff(fs, 700.0f);
        delaySamples = Math.max(1, Math.min(15, Math.round(fs * 0.00025f))); // 250 мкс
        agcAttackCoeff = (float) (1.0 - Math.exp(-1.0 / (3.0 * fs)));
        agcReleaseCoeff = (float) (1.0 - Math.exp(-1.0 / (6.0 * fs)));
        agcGainCoeff = (float) (1.0 - Math.exp(-1.0 / (1.5 * fs)));
    }

    private static float onePoleLpCoeff(int fs, float fc) {
        double x = Math.exp(-2.0 * Math.PI * fc / fs);
        return (float) (1.0 - x);
    }

    private void configureEqBand(int bandIndex, int fs) {
        float f0 = EQ_FREQS[bandIndex];
        if (f0 >= fs * 0.47f) {
            f0 = fs * 0.47f;
        }
        float gain = eqGainsDb[bandIndex];
        for (int ch = 0; ch < 2; ch++) {
            eqFilters[ch][bandIndex].setPeakingEQ(fs, f0, 1.414f, gain);
        }
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        int enc = inputAudioFormat.encoding;
        if (enc != C.ENCODING_PCM_16BIT
                && enc != C.ENCODING_PCM_FLOAT
                && enc != C.ENCODING_PCM_24BIT
                && enc != C.ENCODING_PCM_32BIT
                && enc != C.ENCODING_PCM_8BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        if (inputAudioFormat.channelCount < 1 || inputAudioFormat.channelCount > 2) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        pendingInputFormat = inputAudioFormat;
        int outRate = (inputAudioFormat.sampleRate > 0) ? TARGET_SAMPLE_RATE : inputAudioFormat.sampleRate;
        pendingOutputFormat = new AudioFormat(outRate, inputAudioFormat.channelCount, C.ENCODING_PCM_16BIT);
        return pendingOutputFormat;
    }

    @Override
    public boolean isActive() {
        return pendingInputFormat != AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;

        int channels = inputFormat.channelCount;
        int inEnc = inputFormat.encoding;
        int bytesPerSample = bytesPerSample(inEnc);
        int bytesPerFrame = channels * bytesPerSample;
        int inFrames = remaining / bytesPerFrame;
        if (inFrames <= 0) {
            inputBuffer.position(inputBuffer.limit());
            return;
        }

        // С запасом под ресемплинг (например 44.1 -> 44.1 или 32 -> 44.1) выделяем выходной буфер
        int maxOutFrames = resampleActive
                ? (int) Math.ceil(inFrames / resampleStep) + 8
                : inFrames + 2;
        int outBytesCapacity = maxOutFrames * channels * 2;
        if (buffer.capacity() < outBytesCapacity) {
            buffer = ByteBuffer.allocateDirect(outBytesCapacity).order(ByteOrder.nativeOrder());
        } else {
            buffer.clear();
        }

        final boolean doNatural = zenyNaturalEnabled;
        final boolean doEq = softwareEqEnabled && anyEqBandActive;
        final boolean doAgc = soundCheckEnabled && Float.isNaN(taggedGainDb);
        final float baseGain = targetStaticGainLinear;

        // Интенсивность бинаурального кроссфида: явная настройка пользователя либо деликатные 8.5% внутри Zeny Natural
        float cfMix = 0.0f;
        if (crossfeedStep == 1) cfMix = 0.15f;
        else if (crossfeedStep == 2) cfMix = 0.24f;
        else if (crossfeedStep >= 3) cfMix = 0.33f;
        else if (doNatural) cfMix = 0.085f;
        final boolean doCrossfeed = (channels == 2 && cfMix > 0.0f);
        // Суммарной нормировки нет: середина остаётся на единичном уровне, а бас центра с кроссфидом
        // поднимается не больше чем на 0.7 дБ при 8.5%
        final float cfNorm = 1.0f;

        final float subCoeff = subLpCoeff;
        final float dcCoeff = dcHpCoeff;
        final float cfCoeff = cfLpCoeff;
        int rng = ditherRng;

        if (nativeActive) {
            queueNative(inputBuffer, inFrames, bytesPerFrame, maxOutFrames, baseGain, cfMix, doNatural, doEq,
                    doAgc, doCrossfeed);
            return;
        }

        int pos = inputBuffer.position();
        for (int f = 0; f < inFrames; f++) {
            float L = readSample(inputBuffer, pos, inEnc);
            pos += bytesPerSample;
            float R = (channels == 2) ? readSample(inputBuffer, pos, inEnc) : L;
            if (channels == 2) pos += bytesPerSample;

            // 1. Входное усиление: статический R128/headroom либо плавный гейтированный AGC
            float g = baseGain;
            if (doAgc) {
                // Средний квадрат считаем по каждому отсчёту (иначе пропуск тихих отсчётов завышает оценку),
                // а гейт тишины применяем уже к огибающей. Постоянные времени ~3 с на подъём и ~6 с на спад:
                // быстрее регулятор начинает «качать» динамику внутри трека.
                float p = 0.5f * (L * L + R * R);
                float alpha = (p > runningPower) ? agcAttackCoeff : agcReleaseCoeff;
                runningPower += alpha * (p - runningPower);
                if (runningPower > 0.00003f) { // Порог тишины ~ -45 dBFS, чтобы паузы не задирали усиление
                    // Целевой средний квадрат под комфортную громкость (~ -16.5 dBFS RMS)
                    float desired = (float) Math.sqrt(0.022f / runningPower);
                    if (desired < 0.56f) desired = 0.56f; // Не тише -5 дБ
                    else if (desired > 1.26f) desired = 1.26f; // Не громче +2 дБ
                    agcGainLinear += agcGainCoeff * (desired - agcGainLinear);
                }
                g *= agcGainLinear;
            }
            L *= g;
            R *= g;

            // 2. 10-полосный эквалайзер (считаются только ненулевые полосы)
            if (doEq) {
                for (int b = 0; b < 10; b++) {
                    if (eqBandActive[b]) {
                        L = eqFilters[0][b].process(L);
                        if (channels == 2) R = eqFilters[1][b].process(R);
                    }
                }
            }

            // 3. Zeny Natural: гармоническое насыщение суббаса (< 95 Гц) + 4 психоакустических фильтра
            if (doNatural) {
                subStateL += subCoeff * (L - subStateL);
                float sL = subStateL;
                // Мягкая квадратичная + кубическая гармоника даёт плотность баса без гула на середине
                float harmL = sL * (0.14f * Math.abs(sL) + 0.06f * sL);
                float dcL = harmL - dcInL + dcCoeff * dcOutL;
                dcInL = harmL;
                dcOutL = dcL;
                L += dcL;

                L = naturalFilters[0][0].process(L);
                L = naturalFilters[0][1].process(L);
                L = naturalFilters[0][2].process(L);
                L = naturalFilters[0][3].process(L);

                if (channels == 2) {
                    subStateR += subCoeff * (R - subStateR);
                    float sR = subStateR;
                    float harmR = sR * (0.14f * Math.abs(sR) + 0.06f * sR);
                    float dcR = harmR - dcInR + dcCoeff * dcOutR;
                    dcInR = harmR;
                    dcOutR = dcR;
                    R += dcR;

                    R = naturalFilters[1][0].process(R);
                    R = naturalFilters[1][1].process(R);
                    R = naturalFilters[1][2].process(R);
                    R = naturalFilters[1][3].process(R);
                }
            }

            // 4. Бинауральный Crossfeed Бауэра с межуншной задержкой 250 мкс
            if (doCrossfeed) {
                cfLpL += cfCoeff * (L - cfLpL);
                cfLpR += cfCoeff * (R - cfLpR);
                delayL[delayWriteIdx] = cfLpL;
                delayR[delayWriteIdx] = cfLpR;
                int readIdx = (delayWriteIdx - delaySamples) & DELAY_RING_MASK;
                delayWriteIdx = (delayWriteIdx + 1) & DELAY_RING_MASK;

                float delayedLowL = delayL[readIdx];
                float delayedLowR = delayR[readIdx];
                // Высокочастотная составляющая прямого канала чуть приподнимается, сохраняя ровный тональный баланс
                float highL = L - cfLpL;
                float highR = R - cfLpR;
                float outL = (L + 0.06f * highL + cfMix * delayedLowR) * cfNorm;
                float outR = (R + 0.06f * highR + cfMix * delayedLowL) * cfNorm;
                L = outL;
                R = outR;
            }

            // 5. Вывод: полифазный sinc-ресемплер (48 -> 44.1 кГц), запасной Эрмит или напрямую (44.1 кГц)
            if (resampleActive && polyTaps != null) {
                final int mask = ringMask;
                long newest = polyNewest + 1;
                ringL[(int) (newest & mask)] = L;
                ringR[(int) (newest & mask)] = (channels == 2) ? R : L;
                polyNewest = newest;
                final float[] taps = polyTaps;
                final int tapCount = polyTapCount;
                final int half = polyHalf;
                while (polyInIndex + half <= polyNewest) {
                    final int base = polyPhase * tapCount;
                    final long first = polyInIndex - (half - 1);
                    float accL = 0.0f, accR = 0.0f;
                    if (channels == 2) {
                        for (int k = 0; k < tapCount; k++) {
                            int ri = (int) ((first + k) & mask);
                            float c = taps[base + k];
                            accL += c * ringL[ri];
                            accR += c * ringR[ri];
                        }
                        rng = writeDitheredShort(buffer, softClip(accL), rng);
                        rng = writeDitheredShort(buffer, softClip(accR), rng);
                    } else {
                        for (int k = 0; k < tapCount; k++) {
                            accL += taps[base + k] * ringL[(int) ((first + k) & mask)];
                        }
                        rng = writeDitheredShort(buffer, softClip(accL), rng);
                    }
                    polyPhase += polyNum;
                    polyInIndex += polyPhase / polyDen;
                    polyPhase %= polyDen;
                }
            } else if (resampleActive) {
                h0L = h1L; h1L = h2L; h2L = h3L; h3L = L;
                h0R = h1R; h1R = h2R; h2R = h3R; h3R = R;

                while (resamplePhase < 1.0) {
                    float t = (float) resamplePhase;
                    float outL = hermite4(t, h0L, h1L, h2L, h3L);
                    rng = writeDitheredShort(buffer, softClip(outL), rng);
                    if (channels == 2) {
                        float outR = hermite4(t, h0R, h1R, h2R, h3R);
                        rng = writeDitheredShort(buffer, softClip(outR), rng);
                    }
                    resamplePhase += resampleStep;
                }
                resamplePhase -= 1.0;
            } else {
                rng = writeDitheredShort(buffer, softClip(L), rng);
                if (channels == 2) {
                    rng = writeDitheredShort(buffer, softClip(R), rng);
                }
            }
        }

        ditherRng = rng;
        inputBuffer.position(inputBuffer.limit());
        buffer.flip();
        outputBuffer = buffer;
    }

    /**
     * Тот же конвейер, что и Java-цикл в queueInput, но в нативном ядре. Коэффициенты фильтров считает Java
     * (одни и те же формулы для обоих путей), в C уходит готовый набор.
     */
    private void queueNative(ByteBuffer inputBuffer, int inFrames, int bytesPerFrame, int maxOutFrames,
                             float baseGain, float cfMix, boolean doNatural, boolean doEq, boolean doAgc,
                             boolean doCrossfeed) {
        final int channels = inputFormat.channelCount;
        ByteBuffer src = inputBuffer;
        int srcOffset = inputBuffer.position();
        // Нативное ядро читает сырые байты по адресу в little-endian; чужой буфер копируем в свой
        if (!inputBuffer.isDirect() || inputBuffer.order() != ByteOrder.LITTLE_ENDIAN) {
            int bytes = inFrames * bytesPerFrame;
            if (nativeIn == null || nativeIn.capacity() < bytes) {
                nativeIn = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
            }
            nativeIn.clear();
            ByteBuffer dup = inputBuffer.duplicate();
            dup.limit(dup.position() + bytes);
            nativeIn.put(dup);
            src = nativeIn;
            srcOffset = 0;
        }

        final float[] fp = nativeFp;
        final int[] ip = nativeIp;
        fp[ZenyNative.FP_BASE_GAIN] = baseGain;
        fp[ZenyNative.FP_CF_MIX] = cfMix;
        fp[ZenyNative.FP_AGC_ATTACK] = agcAttackCoeff;
        fp[ZenyNative.FP_AGC_RELEASE] = agcReleaseCoeff;
        fp[ZenyNative.FP_AGC_GAIN_COEFF] = agcGainCoeff;
        fp[ZenyNative.FP_SUB_COEFF] = subLpCoeff;
        fp[ZenyNative.FP_DC_COEFF] = dcHpCoeff;
        fp[ZenyNative.FP_CF_COEFF] = cfLpCoeff;
        fp[ZenyNative.FP_RUNNING_POWER] = runningPower;
        fp[ZenyNative.FP_AGC_GAIN] = agcGainLinear;
        for (int b = 0; b < 10; b++) {
            eqFilters[0][b].copyCoefficients(fp, ZenyNative.FP_EQ_COEF + b * 5);
            ip[ZenyNative.IP_EQ_ACTIVE + b] = eqBandActive[b] ? 1 : 0;
        }
        for (int i = 0; i < 4; i++) {
            naturalFilters[0][i].copyCoefficients(fp, ZenyNative.FP_NAT_COEF + i * 5);
        }

        ip[ZenyNative.IP_CHANNELS] = channels;
        ip[ZenyNative.IP_ENCODING] = inputFormat.encoding;
        ip[ZenyNative.IP_DO_NATURAL] = doNatural ? 1 : 0;
        ip[ZenyNative.IP_DO_EQ] = doEq ? 1 : 0;
        ip[ZenyNative.IP_DO_AGC] = doAgc ? 1 : 0;
        ip[ZenyNative.IP_DO_CROSSFEED] = doCrossfeed ? 1 : 0;
        ip[ZenyNative.IP_RES_MODE] = !resampleActive ? 0 : (polyTaps != null ? 1 : 2);
        ip[ZenyNative.IP_POLY_NUM] = polyNum;
        ip[ZenyNative.IP_POLY_DEN] = polyDen;
        ip[ZenyNative.IP_POLY_HALF] = polyHalf;
        ip[ZenyNative.IP_POLY_TAPS] = polyTapCount;
        ip[ZenyNative.IP_RING_MASK] = ringMask;
        ip[ZenyNative.IP_DELAY] = delaySamples;
        ip[ZenyNative.IP_RNG] = ditherRng;
        ip[ZenyNative.IP_FRAMES] = inFrames;
        ip[ZenyNative.IP_IN_RATE] = inputFormat.sampleRate;
        ip[ZenyNative.IP_OUT_RATE] = outputFormat.sampleRate;
        ip[ZenyNative.IP_MAX_OUT] = maxOutFrames;

        int outFrames = ZenyNative.process(nativeState, src, srcOffset, buffer, polyTapsBuf, fp, ip);

        runningPower = fp[ZenyNative.FP_RUNNING_POWER];
        agcGainLinear = fp[ZenyNative.FP_AGC_GAIN];
        ditherRng = ip[ZenyNative.IP_RNG];

        inputBuffer.position(inputBuffer.limit());
        buffer.clear();
        buffer.limit(outFrames * channels * 2);
        outputBuffer = buffer;
    }

    private static int bytesPerSample(int encoding) {
        switch (encoding) {
            case C.ENCODING_PCM_8BIT: return 1;
            case C.ENCODING_PCM_16BIT: return 2;
            case C.ENCODING_PCM_24BIT: return 3;
            case C.ENCODING_PCM_32BIT:
            case C.ENCODING_PCM_FLOAT:
            default: return 4;
        }
    }

    private static float readSample(ByteBuffer buf, int pos, int encoding) {
        switch (encoding) {
            case C.ENCODING_PCM_FLOAT: {
                float v = buf.getFloat(pos);
                return (Float.isNaN(v) || Float.isInfinite(v)) ? 0.0f : v;
            }
            case C.ENCODING_PCM_16BIT:
                return buf.getShort(pos) * (1.0f / 32768.0f);
            case C.ENCODING_PCM_24BIT: {
                int b0 = buf.get(pos) & 0xFF;
                int b1 = buf.get(pos + 1) & 0xFF;
                int b2 = buf.get(pos + 2); // знаковый старший байт (little-endian)
                int s24 = (b2 << 16) | (b1 << 8) | b0;
                return s24 * (1.0f / 8388608.0f);
            }
            case C.ENCODING_PCM_32BIT:
                return buf.getInt(pos) * (1.0f / 2147483648.0f);
            case C.ENCODING_PCM_8BIT:
                return ((buf.get(pos) & 0xFF) - 128) * (1.0f / 128.0f);
            default:
                return 0.0f;
        }
    }

    /**
     * 4-точечная кубическая интерполяция Эрмита (Catmull-Rom) между y1 и y2 при t в [0, 1).
     */
    private static float hermite4(float t, float y0, float y1, float y2, float y3) {
        float c0 = y1;
        float c1 = 0.5f * (y2 - y0);
        float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
        float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * t + c2) * t + c1) * t + c0;
    }

    /**
     * Линейная передача до -1.7 dBFS (0.82), выше порога монотонное рациональное насыщение e/(1+e):
     * наклон в точке стыка равен 1 (колено C1), асимптота строго 1.0, поэтому горба и спада нет.
     */
    private static float softClip(float x) {
        float ax = x < 0.0f ? -x : x;
        if (ax <= 0.82f) return x;
        float excess = (ax - 0.82f) * (1.0f / 0.18f);
        float sat = 0.82f + 0.18f * (excess / (1.0f + excess));
        return x < 0.0f ? -sat : sat;
    }

    /**
     * Квантование в 16 бит с треугольным дизерингом (TPDF) размахом ±1 МЗР.
     */
    private static int writeDitheredShort(ByteBuffer out, float sample, int rng) {
        rng = rng * 1664525 + 1013904223;
        int r1 = rng >> 16;
        rng = rng * 1664525 + 1013904223;
        int r2 = rng >> 16;
        float dither = (r1 - r2) * (1.0f / 65536.0f);
        int q = Math.round(sample * 32767.0f + dither);
        if (q > 32767) q = 32767;
        else if (q < -32768) q = -32768;
        out.putShort((short) q);
        return rng;
    }

    @Override
    public void queueEndOfStream() {
        inputEnded = true;
    }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override
    public boolean isEnded() {
        return inputEnded && outputBuffer == EMPTY_BUFFER;
    }

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        inputEnded = false;
        inputFormat = pendingInputFormat;
        outputFormat = pendingOutputFormat;

        int inRate = inputFormat.sampleRate;
        int outRate = outputFormat.sampleRate;
        resampleActive = (inRate > 0 && outRate > 0 && inRate != outRate);
        resampleStep = resampleActive ? ((double) inRate / (double) outRate) : 1.0;
        resamplePhase = 0.0;
        h0L = h1L = h2L = h3L = 0.0f;
        h0R = h1R = h2R = h3R = 0.0f;
        if (resampleActive) {
            if (inRate != polyBuiltIn || outRate != polyBuiltOut) {
                buildPolyphase(inRate, outRate);
            }
        } else {
            polyTaps = null;
            polyTapsBuf = null;
            polyBuiltIn = -1;
            polyBuiltOut = -1;
        }
        polyPhase = 0;
        polyInIndex = 0;
        polyNewest = -1;
        java.util.Arrays.fill(ringL, 0.0f);
        java.util.Arrays.fill(ringR, 0.0f);

        // Нативное состояние живёт отдельно от Java-полей, поэтому обнуляем его вместе с ними, иначе после
        // перемотки в фильтрах остаётся хвост предыдущего фрагмента
        nativeActive = nativeWanted && ZenyNative.AVAILABLE && inRate > 0;
        if (nativeActive) {
            if (nativeState == null) {
                nativeState = ByteBuffer.allocateDirect(ZenyNative.stateBytes()).order(ByteOrder.nativeOrder());
            }
            ZenyNative.reset(nativeState);
        }

        subStateL = subStateR = 0.0f;
        dcInL = dcOutL = dcInR = dcOutR = 0.0f;
        cfLpL = cfLpR = 0.0f;
        for (int i = 0; i < 16; i++) {
            delayL[i] = 0.0f;
            delayR[i] = 0.0f;
        }
        delayWriteIdx = 0;

        for (int ch = 0; ch < 2; ch++) {
            for (int b = 0; b < 10; b++) eqFilters[ch][b].reset();
            for (int i = 0; i < 4; i++) naturalFilters[ch][i].reset();
        }
        if (inRate > 0) {
            configureFilters(inRate);
        }
        recomputeStaticGain();
    }

    /**
     * Строит строки полифазного фильтра. Ядро: sinc, срезанный на ~21.2 кГц (при 48 -> 44.1), окно Кайзера beta=6.
     * Значения подобраны расчётом АЧХ: ровно до 19 кГц, -0.5 дБ на 20 кГц, выше 23 кГц подавление 50-67 дБ.
     * 48 отводов хватает, чтобы сэмплы выше 22.05 кГц (которых в Opus почти нет) не заворачивались в слышимую зону.
     */
    private void buildPolyphase(int inRate, int outRate) {
        polyBuiltIn = inRate;
        polyBuiltOut = outRate;
        int g = gcd(inRate, outRate);
        int num = inRate / g;
        int den = outRate / g;
        double scale = Math.min(1.0, (double) outRate / (double) inRate);
        int half = (int) Math.ceil(22.0 / scale);
        int taps = 2 * half;
        // Кольцо входа в нативном ядре ограничено 512 кадрами (taps + 2 должно в него влезать)
        if (den > POLY_MAX_DEN || (long) den * taps > POLY_MAX_TABLE || taps + 2 > POLY_MAX_RING) {
            polyTaps = null;
            polyTapsBuf = null;
            return;
        }
        double fc = scale * 0.9614; // 21.2 кГц при 48 -> 44.1, пропорционально выходному Найквисту при других парах
        double beta = 6.0;
        double i0Beta = besselI0(beta);
        float[] table = new float[den * taps];
        for (int p = 0; p < den; p++) {
            double frac = (double) p / (double) den;
            double sum = 0.0;
            double[] row = new double[taps];
            for (int k = 0; k < taps; k++) {
                double x = k - (half - 1) - frac;
                double r = x / half;
                double w = Math.abs(r) < 1.0 ? besselI0(beta * Math.sqrt(1.0 - r * r)) / i0Beta : 0.0;
                double arg = Math.PI * fc * x;
                double s = Math.abs(arg) < 1e-12 ? fc : fc * Math.sin(arg) / arg;
                row[k] = s * w;
                sum += row[k];
            }
            // Единичное усиление на постоянной составляющей в каждой фазе: иначе громкость «дышит» с периодом фаз
            for (int k = 0; k < taps; k++) {
                table[p * taps + k] = (float) (row[k] / sum);
            }
        }
        polyTaps = table;
        if (ZenyNative.AVAILABLE) {
            polyTapsBuf = ByteBuffer.allocateDirect(table.length * 4).order(ByteOrder.nativeOrder());
            polyTapsBuf.asFloatBuffer().put(table);
        }
        polyNum = num;
        polyDen = den;
        polyHalf = half;
        polyTapCount = taps;
        int ring = 1;
        while (ring < taps + 2) ring <<= 1;
        ringL = new float[ring];
        ringR = new float[ring];
        ringMask = ring - 1;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** Модифицированная функция Бесселя I0 (ряд сходится быстро при аргументах до ~10). */
    private static double besselI0(double x) {
        double sum = 1.0;
        double term = 1.0;
        double q = x * x * 0.25;
        for (int k = 1; k < 60; k++) {
            term *= q / ((double) k * (double) k);
            sum += term;
            if (term < 1e-12 * sum) break;
        }
        return sum;
    }

    @Override
    public void reset() {
        flush();
        buffer = EMPTY_BUFFER;
        pendingInputFormat = AudioFormat.NOT_SET;
        pendingOutputFormat = AudioFormat.NOT_SET;
        inputFormat = AudioFormat.NOT_SET;
        outputFormat = AudioFormat.NOT_SET;
    }

    /**
     * Биквадратный фильтр в транспонированной прямой форме II (DF2T) — меньше накопление ошибки float на НЧ.
     */
    private static final class Biquad {
        private float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
        private float z1 = 0.0f, z2 = 0.0f;

        void setPeakingEQ(float fs, float f0, float q, float dbGain) {
            if (Math.abs(dbGain) < 0.01f) {
                b0 = 1.0f; b1 = 0.0f; b2 = 0.0f; a1 = 0.0f; a2 = 0.0f;
                return;
            }
            double A = Math.pow(10.0, dbGain / 40.0);
            double w0 = 2.0 * Math.PI * f0 / fs;
            double cosW = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);

            double bb0 = 1.0 + alpha * A;
            double bb1 = -2.0 * cosW;
            double bb2 = 1.0 - alpha * A;
            double aa0 = 1.0 + alpha / A;
            double aa1 = -2.0 * cosW;
            double aa2 = 1.0 - alpha / A;

            b0 = (float) (bb0 / aa0);
            b1 = (float) (bb1 / aa0);
            b2 = (float) (bb2 / aa0);
            a1 = (float) (aa1 / aa0);
            a2 = (float) (aa2 / aa0);
        }

        void setHighShelf(float fs, float f0, float q, float dbGain) {
            if (Math.abs(dbGain) < 0.01f) {
                b0 = 1.0f; b1 = 0.0f; b2 = 0.0f; a1 = 0.0f; a2 = 0.0f;
                return;
            }
            double A = Math.pow(10.0, dbGain / 40.0);
            double w0 = 2.0 * Math.PI * f0 / fs;
            double cosW = Math.cos(w0);
            double sinW = Math.sin(w0);
            double alpha = sinW / (2.0 * q);
            double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

            double bb0 = A * ((A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha);
            double bb1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW);
            double bb2 = A * ((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha);
            double aa0 = (A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha;
            double aa1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW);
            double aa2 = (A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha;

            b0 = (float) (bb0 / aa0);
            b1 = (float) (bb1 / aa0);
            b2 = (float) (bb2 / aa0);
            a1 = (float) (aa1 / aa0);
            a2 = (float) (aa2 / aa0);
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void reset() {
            z1 = 0.0f;
            z2 = 0.0f;
        }

        void copyCoefficients(float[] dst, int offset) {
            dst[offset] = b0;
            dst[offset + 1] = b1;
            dst[offset + 2] = b2;
            dst[offset + 3] = a1;
            dst[offset + 4] = a2;
        }
    }
}
