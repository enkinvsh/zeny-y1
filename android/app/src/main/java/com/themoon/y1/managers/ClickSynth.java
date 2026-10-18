package com.themoon.y1.managers;

/**
 * Синтезатор щелчков колеса: мономный 16-битный PCM целиком из математики, без файлов и семплов.
 * Чистая Java без Android, чтобы форма и уровни проверялись юнит-тестами на любой машине.
 *
 * <p>Общие правила для каждого буфера: первый и последний отсчёт ровно 0 (иначе щелчок на
 * границе включения динамика), линейный фейд-ин 0,15 мс и фейд-аут 0,3 мс, пик равен
 * амплитуде уровня, постоянная составляющая вычтена. Длина считается как 9 постоянных
 * времени самого медленного затухания: так хвост опускается ниже -60 дБ и звук не обрывается
 * на слышимом уровне. Шум берётся из LCG с фиксированным зерном, поэтому буферы побитово
 * одинаковы от запуска к запуску.
 */
public final class ClickSynth {

    // Три уровня громкости щелчка. Подобраны на слух вслепую, поэтому живут в одном месте: правятся
    // здесь, остальной код ссылается только на номер уровня 1..3.
    public static final double AMPLITUDE_QUIET = 0.20;
    public static final double AMPLITUDE_NORMAL = 0.40;
    public static final double AMPLITUDE_LOUD = 0.70;

    public enum ClickVariant {
        // Порядок это и порядок строк в настройках: лучшие варианты сверху, «Выкл» внизу.
        CLASSIC("classic", true),
        ROCKBOX("rockbox", true),
        WOOD("wood", true),
        MECH("mech", true),
        SYSTEM("system", false),
        OFF("off", false);

        private final String id;
        private final boolean rendered;

        ClickVariant(String id, boolean rendered) {
            this.id = id;
            this.rendered = rendered;
        }

        /** Стабильный идентификатор для SharedPreferences. */
        public String id() {
            return id;
        }

        /** false для OFF и SYSTEM: у них нет собственного звука, их буфер не рисуется. */
        public boolean isRendered() {
            return rendered;
        }

        /** Вариант по id; null, если id неизвестен или null. */
        public static ClickVariant fromId(String id) {
            if (id == null) return null;
            for (ClickVariant v : values()) {
                if (v.id.equals(id)) return v;
            }
            return null;
        }
    }

    public enum ClickKind {
        /** Шаг колеса. */
        STEP,
        /** Нажатие центра: тот же тембр, ниже и тяжелее. */
        PRESS
    }

    private static final int MIN_SAMPLE_RATE = 8000;
    private static final int MAX_SAMPLE_RATE = 192000;
    private static final double FADE_IN_MS = 0.15;
    private static final double FADE_OUT_MS = 0.3;
    // ln(1000) = 6,9 даёт -60 дБ ровно в конце; RMS последней пятой должен быть ниже порога с запасом,
    // а пятая начинается на 80 % длины, поэтому берём 9 постоянных времени.
    private static final double TAIL_TAU_FACTOR = 9.0;
    private static final int NOISE_SEED = 0x5EED1234;

    private ClickSynth() {}

    /** Амплитуда уровня 1..3 как доля полной шкалы. */
    public static double amplitude(int level) {
        switch (level) {
            case 1:
                return AMPLITUDE_QUIET;
            case 2:
                return AMPLITUDE_NORMAL;
            case 3:
                return AMPLITUDE_LOUD;
            default:
                throw new IllegalArgumentException("level must be 1..3: " + level);
        }
    }

    /**
     * Рисует один щелчок.
     *
     * @throws IllegalArgumentException для OFF и SYSTEM (рисовать нечего), null-вида, уровня вне 1..3
     *         и частоты дискретизации вне 8000..192000
     */
    public static short[] render(ClickVariant variant, ClickKind kind, int level, int sampleRate) {
        if (variant == null || !variant.isRendered()) {
            throw new IllegalArgumentException("variant is not rendered: " + variant);
        }
        if (kind == null) throw new IllegalArgumentException("kind is null");
        double amp = amplitude(level);
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) {
            throw new IllegalArgumentException("sampleRate out of range: " + sampleRate);
        }
        boolean press = kind == ClickKind.PRESS;
        double[] buf;
        switch (variant) {
            case CLASSIC:
                buf = classic(press, sampleRate);
                break;
            case ROCKBOX:
                buf = rockbox(press, sampleRate);
                break;
            case WOOD:
                buf = wood(press, sampleRate);
                break;
            case MECH:
                buf = mech(press, sampleRate);
                break;
            default:
                throw new IllegalArgumentException("variant is not rendered: " + variant);
        }
        removeDc(buf);
        applyFades(buf, sampleRate);
        return toPcm(buf, amp);
    }

    // Пьезо-щелчок старого iPod: два быстро гаснущих тона. Тяжёлое нажатие добавляет глухой толчок 180 Гц.
    private static double[] classic(boolean press, int sr) {
        if (!press) {
            double[] b = new double[samples(tailMs(0.9), sr)];
            addDampedSine(b, sr, 4200, 0.9, 1.0);
            addDampedSine(b, sr, 7900, 0.6, 0.35);
            return b;
        }
        double[] b = new double[samples(tailMs(2.5), sr)];
        addDampedSine(b, sr, 3000, 1.4, 1.0);
        addDampedSine(b, sr, 180, 2.5, 0.4);
        return b;
    }

    // Целое число периодов прямоугольника: любой обрыв посреди периода дал бы постоянную составляющую и
    // лишний треск. 8 периодов 4 кГц это ровно 2 мс, нажатие 8 периодов 2,5 кГц (3,2 мс).
    private static double[] rockbox(boolean press, int sr) {
        double freq = press ? 2500 : 4000;
        int n = (int) Math.round(8.0 * sr / freq);
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            double phase = i * freq / sr + 1e-9;
            phase -= Math.floor(phase);
            b[i] = phase < 0.5 ? 1.0 : -1.0;
        }
        return b;
    }

    // Деревянный блок: три моды с разным затуханием и 0,5 мс шума на атаке, он даёт «стук», а не звон.
    private static double[] wood(boolean press, int sr) {
        double k = press ? 0.7 : 1.0;
        double[] b = new double[samples(tailMs(6.0), sr)];
        addDampedSine(b, sr, 820 * k, 6.0, 1.0);
        addDampedSine(b, sr, 1330 * k, 4.0, 0.6);
        addDampedSine(b, sr, 2210 * k, 2.5, 0.3);
        int attack = samples(0.5, sr);
        int state = NOISE_SEED;
        for (int i = 0; i < attack && i < b.length; i++) {
            state = state * 1664525 + 1013904223;
            double x = (state >>> 8) / 8388608.0 - 1.0;
            b[i] += x * 0.6 * (1.0 - (double) i / attack);
        }
        return b;
    }

    // Клавиатурный переключатель: шум через резонатор около 3 кГц (щелчок пластика) и низкий толчок.
    // При нажатии шум ниже и дольше, а толчок сильнее: клавиша упирается в основание.
    private static double[] mech(boolean press, int sr) {
        double noiseTau = press ? 2.0 : 1.2;
        double thumpTau = press ? 6.0 : 5.0;
        double[] b = new double[samples(tailMs(Math.max(noiseTau, thumpTau)), sr)];
        double[] noise = resonatedNoise(b.length, sr, press ? 2200 : 3000, 2.0, noiseTau);
        for (int i = 0; i < b.length; i++) b[i] += noise[i];
        addDampedSine(b, sr, press ? 110 : 140, thumpTau, press ? 1.0 : 0.7);
        return b;
    }

    private static double tailMs(double slowestTauMs) {
        return TAIL_TAU_FACTOR * slowestTauMs;
    }

    private static int samples(double ms, int sr) {
        return Math.max(3, (int) Math.round(ms * sr / 1000.0));
    }

    private static void addDampedSine(double[] b, int sr, double freq, double tauMs, double amp) {
        double tau = tauMs / 1000.0;
        for (int i = 0; i < b.length; i++) {
            double t = (double) i / sr;
            b[i] += amp * Math.exp(-t / tau) * Math.sin(2.0 * Math.PI * freq * t);
        }
    }

    // Белый шум из LCG через двухполюсный резонатор (Q задаёт ширину полосы), затем огибающая e^(-t/tau).
    // Нормируется к пику 1, чтобы вес шума в смеси не зависел от усиления фильтра.
    private static double[] resonatedNoise(int n, int sr, double centerHz, double q, double tauMs) {
        double r = Math.exp(-Math.PI * (centerHz / q) / sr);
        double a1 = 2.0 * r * Math.cos(2.0 * Math.PI * centerHz / sr);
        double a2 = -r * r;
        double tau = tauMs / 1000.0;
        double[] out = new double[n];
        int state = NOISE_SEED;
        double y1 = 0, y2 = 0, peak = 0;
        for (int i = 0; i < n; i++) {
            state = state * 1664525 + 1013904223;
            double x = (state >>> 8) / 8388608.0 - 1.0;
            double y = x + a1 * y1 + a2 * y2;
            y2 = y1;
            y1 = y;
            out[i] = y * Math.exp(-(double) i / sr / tau);
            peak = Math.max(peak, Math.abs(out[i]));
        }
        if (peak > 0) {
            for (int i = 0; i < n; i++) out[i] /= peak;
        }
        return out;
    }

    // Затухающая синусоида конечной длины всегда оставляет небольшое смещение. Вычитаем его под окном
    // Ханна, занимающим только первую половину буфера: константа вернула бы ступеньку на краях, а окно
    // на всю длину подмешало бы смещение в хвост, который обязан быть тише -60 дБ.
    private static void removeDc(double[] b) {
        int n = b.length;
        int m = Math.max(3, n / 2);
        double sum = 0;
        for (double v : b) sum += v;
        double mean = sum / n;
        double wSum = 0;
        for (int i = 0; i < m; i++) wSum += 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (m - 1)));
        double k = mean * n / wSum;
        for (int i = 0; i < m; i++) b[i] -= k * 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (m - 1)));
    }

    private static void applyFades(double[] b, int sr) {
        int n = b.length;
        int fadeIn = Math.max(1, (int) Math.round(FADE_IN_MS * sr / 1000.0));
        int fadeOut = Math.max(1, (int) Math.round(FADE_OUT_MS * sr / 1000.0));
        for (int i = 0; i < fadeIn && i < n; i++) b[i] *= (double) i / fadeIn;
        for (int j = 0; j < fadeOut && j < n; j++) b[n - 1 - j] *= (double) j / fadeOut;
    }

    private static short[] toPcm(double[] b, double amp) {
        double peak = 0;
        for (double v : b) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0) throw new IllegalStateException("silent buffer");
        double scale = 32767.0 * amp / peak;
        short[] out = new short[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (short) Math.round(b[i] * scale);
        return out;
    }
}
