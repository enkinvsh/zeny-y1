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
 * 3) По умолчанию тракт прозрачен (замер: АЧХ в пределах ±0.003 дБ). Единственная окраска тембра, опция
 *    «Воздух» (шаги 0..4), это один очень широкий плавный полюс-нулевой фильтр первого порядка (6 дБ/окт)
 *    без резонанса и без изменения баса; нелинейных добавок и скрытого кроссфида нет. Опция «Сцена»
 *    (шаги 0..3) подмешивает в каждое ухо низкочастотную часть соседнего канала; центр и моно при этом
 *    остаются на единичном уровне на всех частотах.
 * 4) На выходе мягкий рациональный лимитер защищает пики, а TPDF-дизеринг убирает ступеньки квантования 16 бит.
 */
public class ZenyAudioProcessor implements AudioProcessor {

    public static final int TARGET_SAMPLE_RATE = 44100;

    private AudioFormat pendingInputFormat = AudioFormat.NOT_SET;
    private AudioFormat pendingOutputFormat = AudioFormat.NOT_SET;
    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private AudioFormat outputFormat = AudioFormat.NOT_SET;

    private ByteBuffer buffer = EMPTY_BUFFER;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded = false;

    private volatile int airStep = 0;
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

    // Полка «Воздух»: один фильтр первого порядка на канал (в биквад-структуре с b2 = a2 = 0)
    private final Biquad[] airFilters = new Biquad[2];
    // Плато полки по шагам 0..5, дБ. Шаг 0 пропускает ступень целиком (побитово как без неё).
    // Ряд 6..18 дБ подобран владельцем по слуху, он же определяет верхнюю границу (+18 дБ).
    private static final float[] AIR_GAIN_DB = {0f, 6f, 9f, 12f, 15f, 18f};
    // Геометрический центр полки. У полки первого порядка прирост на нём ровно половина плато в дБ,
    // а 8 кГц оставляет 2-5 кГц почти нетронутыми (нет «носового» горба пикового эквалайзера).
    private static final float AIR_MID_HZ = 8000f;

    // «Сцена»: ФНЧ с единичным усилением на постоянной составляющей (700 Гц), общий для вычитания из
    // прямого пути и для подмешивания в соседний канал. Отдельной линии задержки нет: групповая задержка
    // самого однополюсного фильтра (около 217 мкс на НЧ) и есть межуншная задержка.
    private float stageLpCoeff = 0.085f;
    private float stageLpL = 0.0f, stageLpR = 0.0f;
    // Подача соседнего канала по шагам 1..3, дБ (700 Гц / 3, 4.5, 6 дБ: открытые пресеты libbs2b)
    private static final float[] STAGE_FEED_DB = {0f, 3.0f, 4.5f, 6.0f};

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
            airFilters[ch] = new Biquad();
        }
    }

    /** Шаг «Воздуха» 0..5 (выкл, +6, +9, +12, +15, +18 дБ); 0 = ступень выключена. */
    public void setAirStep(int step) {
        this.airStep = Math.max(0, Math.min(AIR_GAIN_DB.length - 1, step));
        int fs = inputFormat.sampleRate > 0 ? inputFormat.sampleRate : 48000;
        configureAir(fs);
        recomputeStaticGain();
    }

    /** Разрешает нативное ядро; решение применяется при следующем flush(). Нужен тестам для сравнения с Java. */
    public void setNativeEnabled(boolean enabled) {
        this.nativeWanted = enabled;
    }

    public boolean isNativeActive() {
        return nativeActive;
    }

    public void setSoundCheckEnabled(boolean enabled) {
        this.soundCheckEnabled = enabled;
        recomputeStaticGain();
    }

    /** Шаг «Сцены» 0..3; имя метода и ключ настройки crossfeed_step сохранены ради совместимости. */
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

    private void recomputeStaticGain() {
        // Запас 1.2 дБ под интерсемпловые пики сжатого аудио
        float db = -1.2f;
        if (soundCheckEnabled && !Float.isNaN(taggedGainDb)) {
            float clampedTag = Math.max(-9.5f, Math.min(4.0f, taggedGainDb));
            db += clampedTag;
        }
        targetStaticGainLinear = (float) Math.pow(10.0, db / 20.0);
    }

    private void configureFilters(int fs) {
        if (fs <= 0) return;
        configureAir(fs);

        stageLpCoeff = onePoleLpCoeff(fs, 700.0f);
        agcAttackCoeff = (float) (1.0 - Math.exp(-1.0 / (3.0 * fs)));
        agcReleaseCoeff = (float) (1.0 - Math.exp(-1.0 / (6.0 * fs)));
        agcGainCoeff = (float) (1.0 - Math.exp(-1.0 / (1.5 * fs)));
    }

    /**
     * Аналоговый прототип H(s) = (1 + s/wz)/(1 + s/wp), усиление на постоянной составляющей 1, плато K = wp/wz,
     * центр sqrt(wz*wp) = 8 кГц. Билинейное преобразование здесь не годится: при плато +12 дБ оно уводит
     * частоты полюса и нуля (4 и 16 кГц) так, что на 12-20 кГц ошибка 3-7 дБ (на +18 дБ ещё больше). Поэтому ноль берётся по
     * согласованному z-преобразованию (exp(-wz*T)), а полюс подбирается делением отрезка пополам так, чтобы
     * цифровое усиление совпало с аналоговым на 0.8 от Найквиста. Остаточное отклонение от прототипа
     * на 1-20 кГц не больше 0.2 дБ для 44.1 и 48 кГц.
     */
    private void configureAir(int fs) {
        if (fs <= 0) return;
        float gainDb = AIR_GAIN_DB[airStep];
        for (int ch = 0; ch < 2; ch++) {
            airFilters[ch].setFirstOrderShelf(fs, AIR_MID_HZ, gainDb);
        }
    }

    private static float onePoleLpCoeff(int fs, float fc) {
        double x = Math.exp(-2.0 * Math.PI * fc / fs);
        return (float) (1.0 - x);
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

        final boolean doAir = airStep > 0;
        final boolean doAgc = soundCheckEnabled && Float.isNaN(taggedGainDb);
        final float baseGain = targetStaticGainLinear;

        // Доля подмешиваемого НЧ: G = 1/(1 + 10^(feed/20)). На НЧ прямой путь 1-G, перекрёстный G, их сумма
        // ровно 1, поэтому центр и моно не меняют уровень; выше 700 Гц прямой путь равен 1, перекрёстного нет.
        // Шаг 0 пропускает ступень целиком, без нормировки и без пересчёта уровня.
        final int stageIdx = Math.max(0, Math.min(3, crossfeedStep));
        final float stageG = stageIdx == 0 ? 0.0f
                : (float) (1.0 / (1.0 + Math.pow(10.0, STAGE_FEED_DB[stageIdx] / 20.0)));
        final boolean doStage = (channels == 2 && stageIdx > 0);

        final float stageCoeff = stageLpCoeff;
        int rng = ditherRng;

        if (nativeActive) {
            queueNative(inputBuffer, inFrames, bytesPerFrame, maxOutFrames, baseGain, stageG, doAir,
                    doAgc, doStage);
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

            // 2. «Воздух»: одна полка первого порядка (шаг 0 = ступень пропускается)
            if (doAir) {
                L = airFilters[0].process(L);
                if (channels == 2) R = airFilters[1].process(R);
            }

            // 3. «Сцена»: out = in + G*(LP(соседний) - LP(свой)). Запись через разность даёт L=R ровно на выходе
            // (без остаточной ошибки округления в моно) и тот же порядок операций, что в нативном ядре
            if (doStage) {
                stageLpL += stageCoeff * (L - stageLpL);
                stageLpR += stageCoeff * (R - stageLpR);
                float outL = L + stageG * (stageLpR - stageLpL);
                float outR = R + stageG * (stageLpL - stageLpR);
                L = outL;
                R = outR;
            }

            // 4. Вывод: полифазный sinc-ресемплер (48 -> 44.1 кГц), запасной Эрмит или напрямую (44.1 кГц)
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
                             float baseGain, float stageG, boolean doAir, boolean doAgc,
                             boolean doStage) {
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
        fp[ZenyNative.FP_STAGE_G] = stageG;
        fp[ZenyNative.FP_AGC_ATTACK] = agcAttackCoeff;
        fp[ZenyNative.FP_AGC_RELEASE] = agcReleaseCoeff;
        fp[ZenyNative.FP_AGC_GAIN_COEFF] = agcGainCoeff;
        fp[ZenyNative.FP_STAGE_COEFF] = stageLpCoeff;
        fp[ZenyNative.FP_RUNNING_POWER] = runningPower;
        fp[ZenyNative.FP_AGC_GAIN] = agcGainLinear;
        airFilters[0].copyCoefficients(fp, ZenyNative.FP_AIR_COEF);

        ip[ZenyNative.IP_CHANNELS] = channels;
        ip[ZenyNative.IP_ENCODING] = inputFormat.encoding;
        ip[ZenyNative.IP_DO_AIR] = doAir ? 1 : 0;
        ip[ZenyNative.IP_DO_AGC] = doAgc ? 1 : 0;
        ip[ZenyNative.IP_DO_STAGE] = doStage ? 1 : 0;
        ip[ZenyNative.IP_RES_MODE] = !resampleActive ? 0 : (polyTaps != null ? 1 : 2);
        ip[ZenyNative.IP_POLY_NUM] = polyNum;
        ip[ZenyNative.IP_POLY_DEN] = polyDen;
        ip[ZenyNative.IP_POLY_HALF] = polyHalf;
        ip[ZenyNative.IP_POLY_TAPS] = polyTapCount;
        ip[ZenyNative.IP_RING_MASK] = ringMask;
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

        stageLpL = stageLpR = 0.0f;

        for (int ch = 0; ch < 2; ch++) {
            airFilters[ch].reset();
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

        /**
         * Полка первого порядка (ноль и полюс вещественные), записанная как биквад с b2 = a2 = 0.
         * Параметры и способ подбора полюса описаны у ZenyAudioProcessor.configureAir.
         */
        void setFirstOrderShelf(float fs, float midHz, float gainDb) {
            if (gainDb < 0.01f) {
                b0 = 1.0f; b1 = 0.0f; b2 = 0.0f; a1 = 0.0f; a2 = 0.0f;
                return;
            }
            double k = Math.pow(10.0, gainDb / 20.0);
            double fz = midHz / Math.sqrt(k);
            double fp = midHz * Math.sqrt(k);
            double zd = Math.exp(-2.0 * Math.PI * fz / fs);
            double fn = 0.8 * 0.5 * fs;
            double w = 2.0 * Math.PI * fn / fs;
            // Целевое аналоговое усиление на fn
            double target = Math.sqrt((1.0 + (fn / fz) * (fn / fz)) / (1.0 + (fn / fp) * (fn / fp)));
            // |H|^2 цифрового фильтра на fn при ДК-усилении 1; монотонно убывает с ростом полюса
            double numMag2 = 1.0 - 2.0 * zd * Math.cos(w) + zd * zd;
            // Нижняя граница отрицательная: при плато 18 дБ на 44.1 кГц полюс прототипа (22.5 кГц) выше Найквиста,
            // и даже с полюсом в нуле усиление на fn недобирает 0.3 дБ. Полюс слева от нуля (до -0.9) добирает
            // его и остаётся устойчивым; монотонность g2 по полюсу на (-1, 1) сохраняется.
            double lo = -0.9, hi = 0.99;
            for (int i = 0; i < 60; i++) {
                double pd = 0.5 * (lo + hi);
                double g2 = (1.0 - pd) * (1.0 - pd) / ((1.0 - zd) * (1.0 - zd))
                        * numMag2 / (1.0 - 2.0 * pd * Math.cos(w) + pd * pd);
                if (g2 > target * target) lo = pd; else hi = pd;
            }
            double pd = 0.5 * (lo + hi);
            double g0 = (1.0 - pd) / (1.0 - zd);
            b0 = (float) g0;
            b1 = (float) (-zd * g0);
            b2 = 0.0f;
            a1 = (float) -pd;
            a2 = 0.0f;
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
