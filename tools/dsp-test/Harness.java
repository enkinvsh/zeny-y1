import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat;
import com.themoon.y1.managers.ZenyAudioProcessor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Офлайн-стенд для ZenyAudioProcessor: гоняет настоящий класс на JVM без устройства. */
public class Harness {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static ZenyAudioProcessor make(int rate, int ch, int enc) throws Exception {
        ZenyAudioProcessor p = new ZenyAudioProcessor();
        p.setNativeEnabled(!"0".equals(System.getProperty("zeny.native")));
        p.configure(new AudioFormat(rate, ch, enc));
        p.flush();
        if ("1".equals(System.getProperty("zeny.native")) && !p.isNativeActive()) {
            throw new IllegalStateException("нативное ядро не загрузилось");
        }
        return p;
    }

    /** Синус в интерливе: одинаковый в обоих каналах. */
    static float[] sine(int rate, int ch, double freq, double amp, double seconds) {
        int n = (int) Math.round(rate * seconds);
        float[] x = new float[n * ch];
        for (int i = 0; i < n; i++) {
            float v = (float) (amp * Math.sin(2 * Math.PI * freq * i / rate));
            for (int c = 0; c < ch; c++) x[i * ch + c] = v;
        }
        return x;
    }

    static short[] run(ZenyAudioProcessor p, float[] in, int ch, int chunkFrames) {
        List<short[]> parts = new ArrayList<>();
        int total = 0;
        int frames = in.length / ch;
        for (int f = 0; f < frames; f += chunkFrames) {
            int n = Math.min(chunkFrames, frames - f);
            ByteBuffer b = ByteBuffer.allocateDirect(n * ch * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < n * ch; i++) b.putFloat(in[f * ch + i]);
            b.flip();
            p.queueInput(b);
            ByteBuffer out = p.getOutput();
            short[] s = new short[out.remaining() / 2];
            out.order(ByteOrder.nativeOrder()).asShortBuffer().get(s);
            parts.add(s);
            total += s.length;
        }
        short[] all = new short[total];
        int o = 0;
        for (short[] s : parts) {
            System.arraycopy(s, 0, all, o, s.length);
            o += s.length;
        }
        return all;
    }

    /** Амплитуда и SNR синуса известной частоты на окне ровно в целое число периодов (1 с). */
    static double[] fit(short[] y, int ch, int rate, double freq, int startFrame, int lenFrames) {
        double ci = 0, cq = 0;
        for (int i = 0; i < lenFrames; i++) {
            double v = y[(startFrame + i) * ch] / 32768.0;
            double ph = 2 * Math.PI * freq * (startFrame + i) / rate;
            ci += v * Math.cos(ph);
            cq += v * Math.sin(ph);
        }
        ci *= 2.0 / lenFrames;
        cq *= 2.0 / lenFrames;
        double amp = Math.sqrt(ci * ci + cq * cq);
        double res = 0, sig = 0;
        for (int i = 0; i < lenFrames; i++) {
            double v = y[(startFrame + i) * ch] / 32768.0;
            double ph = 2 * Math.PI * freq * (startFrame + i) / rate;
            double fitv = ci * Math.cos(ph) + cq * Math.sin(ph);
            res += (v - fitv) * (v - fitv);
            sig += fitv * fitv;
        }
        double snr = 10 * Math.log10(sig / Math.max(res, 1e-30));
        return new double[] {amp, snr};
    }

    /** Амплитуда синуса и SNR в выбранном канале интерлива. */
    static double[] fitCh(short[] y, int ch, int chIdx, int rate, double freq, int startFrame, int lenFrames) {
        short[] mono = new short[y.length / ch];
        for (int i = 0; i < mono.length; i++) mono[i] = y[i * ch + chIdx];
        return fit(mono, 1, rate, freq, startFrame, lenFrames);
    }

    /** Среднеквадратичный уровень канала на окне, в долях полной шкалы. */
    static double rmsCh(short[] y, int ch, int chIdx, int startFrame, int lenFrames) {
        double acc = 0;
        for (int i = 0; i < lenFrames; i++) {
            double v = y[(startFrame + i) * ch + chIdx] / 32768.0;
            acc += v * v;
        }
        return Math.sqrt(acc / lenFrames);
    }

    /** Аналитический отклик полки первого порядка в дБ: центр 8 кГц, плато gainDb. */
    static double airAnalyticDb(double f, double gainDb) {
        double k = Math.pow(10, gainDb / 20);
        double fz = 8000 / Math.sqrt(k), fp = 8000 * Math.sqrt(k);
        return 10 * Math.log10((1 + (f / fz) * (f / fz)) / (1 + (f / fp) * (f / fp)));
    }

    /** Комплексный отклик однополюсного ФНЧ «Сцены» (700 Гц) на частоте f при частоте дискретизации fs. */
    static double[] stageLp(double f, double fs) {
        double x = Math.exp(-2 * Math.PI * 700.0 / fs);
        double a = 1 - x, w = 2 * Math.PI * f / fs;
        double dr = 1 - x * Math.cos(w), di = x * Math.sin(w);
        double den = dr * dr + di * di;
        return new double[] {a * dr / den, -a * di / den};
    }

    /** Аналитический модуль прямого и перекрёстного пути «Сцены» при подаче в один канал, дБ. */
    static double[] stageAnalyticDb(double f, double fs, double feedDb) {
        double g = 1.0 / (1.0 + Math.pow(10, feedDb / 20));
        double[] lp = stageLp(f, fs);
        double dRe = 1 - g * lp[0], dIm = -g * lp[1];
        double cRe = g * lp[0], cIm = g * lp[1];
        return new double[] {10 * Math.log10(dRe * dRe + dIm * dIm), 10 * Math.log10(cRe * cRe + cIm * cIm)};
    }

    /** Фаза синуса частоты freq в канале интерлива относительно синуса с нулевой фазой, рад (окно целое число периодов). */
    static double phaseCh(short[] y, int ch, int chIdx, int rate, double freq, int startFrame, int lenFrames) {
        double ci = 0, cq = 0;
        for (int i = 0; i < lenFrames; i++) {
            double v = y[(startFrame + i) * ch + chIdx] / 32768.0;
            double ph = 2 * Math.PI * freq * (startFrame + i) / rate;
            ci += v * Math.cos(ph);
            cq += v * Math.sin(ph);
        }
        return Math.atan2(ci, cq);
    }

    static double wrap(double x) {
        while (x > Math.PI) x -= 2 * Math.PI;
        while (x < -Math.PI) x += 2 * Math.PI;
        return x;
    }

    /** Фазы прямого и перекрёстного пути «Сцены» по формуле, рад (для расчёта групповой задержки). */
    static double[] stagePhase(double f, double fs, double feedDb) {
        double g = 1.0 / (1.0 + Math.pow(10, feedDb / 20));
        double[] lp = stageLp(f, fs);
        return new double[] {Math.atan2(-g * lp[1], 1 - g * lp[0]), Math.atan2(g * lp[1], g * lp[0])};
    }

    static double db(double x) {
        return 20 * Math.log10(x);
    }

    public static void main(String[] args) throws Exception {
        // 1. Чистый путь: Air 0, SoundCheck выключен -> только -1.2 дБ и ресемплер 48 -> 44.1
        System.out.println("[1] Ресемплер и статический гейн (Pure)");
        for (double f : new double[] {100, 1000, 5000, 10000, 15000}) {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            float[] in = sine(48000, 2, f, 0.5, 2.0);
            short[] out = run(p, in, 2, 4096);
            int frames = out.length / 2;
            double[] r = fit(out, 2, 44100, f, 22050, 44100);
            double gainDb = db(r[0] / 0.5);
            System.out.printf("  f=%6.0f Гц  кадров=%d  усиление=%.3f дБ  SNR=%.1f дБ%n", f, frames, gainDb, r[1]);
            check(frames >= 88200 - 30 && frames <= 88200, "длина вывода 88200 минус латентность фильтра ~22 кадра (получено " + frames + ")");
            check(Math.abs(gainDb - (-1.2)) < 0.1, "усиление -1.2 дБ ±0.1 (получено " + String.format("%.3f", gainDb) + ")");
            check(r[1] > 70, "SNR > 70 дБ");
        }


        // 1b. АЧХ ресемплера отдельно (Pure): плоско до 19 кГц, выше 23 кГц подавление
        System.out.println("[1b] АЧХ ресемплера 48 -> 44.1");
        for (double f : new double[] {8000, 12000, 16000, 18000, 19000, 20000}) {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            short[] out = run(p, sine(48000, 2, f, 0.5, 2.0), 2, 4096);
            double[] r = fit(out, 2, 44100, f, 22050, 44100);
            double dev = db(r[0] / 0.5) + 1.2;
            System.out.printf("  %6.0f Гц: отклонение %+.2f дБ  SNR=%.0f%n", f, dev, r[1]);
            if (f <= 19000) check(Math.abs(dev) < 0.15, "плоско ±0.15 дБ");
            else check(Math.abs(dev) < 0.8, "на 20 кГц спад не больше 0.8 дБ");
        }
        for (double f : new double[] {23000, 23500}) {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            short[] out = run(p, sine(48000, 2, f, 0.5, 2.0), 2, 4096);
            // тон выше выходного Найквиста складывается в 44100 - f; оцениваем его амплитуду
            double[] r = fit(out, 2, 44100, 44100 - f, 22050, 44100);
            System.out.printf("  вход %6.0f Гц -> отражение на %.0f Гц: %.1f дБ отн. входа%n", f, 44100 - f, db(r[0] / 0.5));
            check(db(r[0] / 0.5) < -45, "подавление выше 23 кГц > 45 дБ");
        }

        // 1c. Загрузка CPU: время обработки 60 с стерео-float 48 кГц на Маке (грубая оценка, не на Dalvik)
        {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            float[] in = sine(48000, 2, 440, 0.3, 60.0);
            long t0 = System.nanoTime();
            run(p, in, 2, 4096);
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf("  60 с аудио обработано за %.0f мс (x%.0f реального времени на HotSpot)%n", ms, 60000.0 / ms);
        }

        // 2. Лимитер: монотонность, непрерывность, колено
        System.out.println("[2] softClip");
        Method sc = ZenyAudioProcessor.class.getDeclaredMethod("softClip", float.class);
        sc.setAccessible(true);
        float prev = -1;
        boolean mono = true;
        float maxOut = 0;
        for (int i = 0; i <= 5000; i++) {
            float x = i * 0.001f;
            float y = (float) sc.invoke(null, x);
            if (y < prev - 1e-7f) mono = false;
            prev = y;
            maxOut = Math.max(maxOut, y);
        }
        float below = (float) sc.invoke(null, 0.8199f), above = (float) sc.invoke(null, 0.8201f);
        float slope = ((float) sc.invoke(null, 0.8203f) - (float) sc.invoke(null, 0.8201f)) / 0.0002f;
        check(mono, "монотонно неубывает на [0,5]");
        check(maxOut < 1.0f, "выход < 1.0 (макс " + maxOut + ")");
        check(Math.abs(above - below) < 1e-3f, "непрерывность в 0.82");
        check(slope > 0.99f && slope <= 1.001f, "наклон у колена ~1 (" + slope + ")");
        check(((float) sc.invoke(null, -2.0f)) < 0 && Math.abs((float) sc.invoke(null, -2.0f) + (float) sc.invoke(null, 2.0f)) < 1e-6f, "нечётная симметрия");

        // 3. Перегруженный вход, шум полной шкалы, NaN/Inf
        System.out.println("[3] Жёсткие входы");
        {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            float[] in = sine(48000, 2, 997, 3.0, 1.0);
            in[100] = Float.NaN;
            in[101] = Float.POSITIVE_INFINITY;
            short[] out = run(p, in, 2, 1024);
            int maxAbs = 0;
            for (short s : out) maxAbs = Math.max(maxAbs, Math.abs((int) s));
            check(maxAbs <= 32767, "вывод в пределах int16, пик " + maxAbs);
            check(maxAbs > 20000, "лимитер не душит до тишины (пик " + maxAbs + ")");
        }

        // 4. Чанки: выход не должен зависеть от размера кусков
        System.out.println("[4] Независимость от размера чанка");
        {
            float[] in = sine(48000, 2, 1234, 0.6, 1.5);
            for (int i = 0; i < in.length; i += 7) in[i] += (float) (0.1 * Math.sin(i * 0.013));
            ZenyAudioProcessor a = make(48000, 2, C.ENCODING_PCM_FLOAT);
            ZenyAudioProcessor b = make(48000, 2, C.ENCODING_PCM_FLOAT);
            ZenyAudioProcessor c = make(48000, 2, C.ENCODING_PCM_FLOAT);
            short[] o1 = run(a, in, 2, 1);
            short[] o2 = run(b, in, 2, 7);
            short[] o3 = run(c, in, 2, 4096);
            check(o1.length == o2.length && o2.length == o3.length, "одинаковая длина (" + o1.length + "/" + o2.length + "/" + o3.length + ")");
            boolean same = true;
            for (int i = 0; i < Math.min(o1.length, Math.min(o2.length, o3.length)); i++) {
                if (o1[i] != o2[i] || o2[i] != o3[i]) {
                    same = false;
                    break;
                }
            }
            check(same, "побитово идентичный вывод для чанков 1/7/4096");
        }

        // 5. «Воздух»: АЧХ полки по шагам против аналогового прототипа H(s) = (1 + s/wz)/(1 + s/wp),
        // плато K = 10^(G/20), центр sqrt(wz*wp) = 8 кГц. Измерение относительно Pure на той же частоте
        // входа: так сокращается спад ресемплера у 20 кГц.
        System.out.println("[5] «Воздух»: АЧХ против аналитики (вход 44.1 и 48 кГц)");
        double[] airFreqs = {1000, 3000, 5000, 8000, 12000, 16000, 20000};
        double[] airGains = {0, 6, 9, 12, 15, 18};
        for (int rate : new int[] {44100, 48000}) {
            double[] pure = new double[airFreqs.length];
            for (int i = 0; i < airFreqs.length; i++) {
                ZenyAudioProcessor p = make(rate, 2, C.ENCODING_PCM_FLOAT);
                pure[i] = fit(run(p, sine(rate, 2, airFreqs[i], 0.05, 2.0), 2, 4096), 2, 44100, airFreqs[i], 22050, 44100)[0];
            }
            for (int step = 1; step <= 5; step++) {
                double maxDev = 0;
                StringBuilder row = new StringBuilder();
                for (int i = 0; i < airFreqs.length; i++) {
                    ZenyAudioProcessor p = make(rate, 2, C.ENCODING_PCM_FLOAT);
                    p.setAirStep(step);
                    double a = fit(run(p, sine(rate, 2, airFreqs[i], 0.05, 2.0), 2, 4096), 2, 44100, airFreqs[i], 22050, 44100)[0];
                    double meas = db(a / pure[i]);
                    double dev = meas - airAnalyticDb(airFreqs[i], airGains[step]);
                    maxDev = Math.max(maxDev, Math.abs(dev));
                    row.append(String.format(" %5.0f:%+5.2f(%+.2f)", airFreqs[i], meas, dev));
                }
                System.out.printf("  %.1f кГц вход, шаг %d (+%.0f дБ):%s%n", rate / 1000.0, step, airGains[step], row);
                check(maxDev <= 0.3, "шаг " + step + " на входе " + rate + ": макс. отклонение от прототипа " + String.format("%.2f", maxDev) + " дБ (допуск 0.3)");
            }
        }

        // 5b. Полка первого порядка с центром 8 кГц бас не трогает
        System.out.println("[5b] «Воздух»: бас не меняется");
        for (double f : new double[] {62, 100}) {
            ZenyAudioProcessor pure = make(48000, 2, C.ENCODING_PCM_FLOAT);
            double a0 = fit(run(pure, sine(48000, 2, f, 0.1, 2.0), 2, 2048), 2, 44100, f, 22050, 44100)[0];
            for (int step = 1; step <= 5; step++) {
                ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                p.setAirStep(step);
                double a1 = fit(run(p, sine(48000, 2, f, 0.1, 2.0), 2, 2048), 2, 44100, f, 22050, 44100)[0];
                double d = db(a1 / a0);
                check(Math.abs(d) <= 0.05, String.format("%.0f Гц, шаг %d: %+.3f дБ относительно Pure (допуск 0.05)", f, step, d));
            }
        }

        // 5c. Шаг 0 побитово как Pure, в том числе после возврата с другого шага
        System.out.println("[5c] Шаг 0 = Pure");
        {
            float[] in = sine(48000, 2, 3333, 0.4, 1.0);
            short[] ref = run(make(48000, 2, C.ENCODING_PCM_FLOAT), in, 2, 4096);
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            p.setAirStep(5);
            p.setAirStep(0);
            short[] got = run(p, in, 2, 4096);
            check(java.util.Arrays.equals(ref, got), "шаг 0 после шага 5 даёт тот же вывод, что Pure");
        }

        // 6. Тег R128: +3 дБ при SoundCheck, клэмп +4
        System.out.println("[6] Sound Check с тегом");
        {
            double ref;
            {
                ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                ref = fit(run(p, sine(48000, 2, 1000, 0.1, 2.0), 2, 2048), 2, 44100, 1000, 22050, 44100)[0];
            }
            for (float tag : new float[] {3.0f, -6.0f, 10.0f, -20.0f}) {
                ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                p.setSoundCheckEnabled(true);
                p.setTrackGainDb(tag);
                double a = fit(run(p, sine(48000, 2, 1000, 0.1, 2.0), 2, 2048), 2, 44100, 1000, 22050, 44100)[0];
                double exp = Math.max(-9.5, Math.min(4.0, tag));
                check(Math.abs(db(a / ref) - exp) < 0.1, "тег " + tag + " дБ -> применено " + String.format("%.2f", db(a / ref)) + " (ожидалось " + exp + ")");
            }
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            p.setSoundCheckEnabled(false);
            p.setTrackGainDb(3.0f);
            double a = fit(run(p, sine(48000, 2, 1000, 0.1, 2.0), 2, 2048), 2, 44100, 1000, 22050, 44100)[0];
            check(Math.abs(db(a / ref)) < 0.05, "SoundCheck OFF игнорирует тег");
        }

        // 7. AGC без тега: плавность (нет качания внутри трека) и направление
        System.out.println("[7] AGC для треков без тега");
        {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            p.setSoundCheckEnabled(true);
            p.setTrackGainDb(null);
            // 20 с синуса -20 dBFS: усиление должно плавно подниматься к потолку +2 дБ без ряби
            float[] in = sine(48000, 2, 440, 0.1, 20.0);
            short[] out = run(p, in, 2, 4096);
            double minDb = 1e9, maxDb = -1e9, firstDb = 0, lastDb = 0, maxStep = 0, prevDb = Double.NaN;
            int blockFrames = 4410;
            for (int k = 1; k * blockFrames + blockFrames < out.length / 2; k++) {
                double peak = 0;
                for (int i = 0; i < blockFrames; i++) peak = Math.max(peak, Math.abs(out[(k * blockFrames + i) * 2] / 32768.0));
                double g = db(peak / 0.1);
                if (k == 1) firstDb = g;
                lastDb = g;
                if (!Double.isNaN(prevDb)) maxStep = Math.max(maxStep, Math.abs(g - prevDb));
                prevDb = g;
                minDb = Math.min(minDb, g);
                maxDb = Math.max(maxDb, g);
            }
            System.out.printf("  усиление: старт %.2f дБ -> конец %.2f дБ, макс. скачок между блоками 100 мс %.3f дБ%n", firstDb, lastDb, maxStep);
            check(maxStep < 0.25, "скачки между блоками < 0.25 дБ (нет качания)");
            check(lastDb > firstDb + 0.5 && lastDb <= 0.9, "тихий трек поднимается, но не выше потолка (+2 дБ -1.2 дБ)");
            // Пауза (тишина) не должна поднимать усиление: после тишины громкий сигнал не должен улетать
            ZenyAudioProcessor q = make(48000, 2, C.ENCODING_PCM_FLOAT);
            q.setSoundCheckEnabled(true);
            q.setTrackGainDb(null);
            float[] silence = new float[48000 * 2 * 20];
            run(q, silence, 2, 4096);
            short[] burst = run(q, sine(48000, 2, 440, 0.9, 1.0), 2, 4096);
            int pk = 0;
            for (short s : burst) pk = Math.max(pk, Math.abs((int) s));
            check(pk < 32767 && pk / 32768.0 < 0.95, "после 20 с тишины громкий сигнал не клипует (пик " + String.format("%.3f", pk / 32768.0) + ")");
        }

        // 8. Входные форматы и моно
        System.out.println("[8] Форматы: 16 бит, моно");
        {
            ZenyAudioProcessor p = make(44100, 2, C.ENCODING_PCM_16BIT);
            int n = 44100 * 2;
            ByteBuffer b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < n; i++) {
                short v = (short) (16000 * Math.sin(2 * Math.PI * 1000 * i / 44100.0));
                b.putShort(v);
                b.putShort(v);
            }
            b.flip();
            p.queueInput(b);
            ByteBuffer o = p.getOutput();
            check(o.remaining() == n * 2 * 2 / 2 * 1 || o.remaining() >= (n - 4) * 4, "44.1 кГц 16 бит: без ресемплера, длина " + o.remaining() / 4 + " кадров из " + n);
            ZenyAudioProcessor m = make(48000, 1, C.ENCODING_PCM_FLOAT);
            short[] mo = run(m, sine(48000, 1, 1000, 0.3, 1.0), 1, 1000);
            check(mo.length >= 44100 - 30 && mo.length <= 44100, "моно: 44100 минус латентность (" + mo.length + ")");
        }

        // 9. flush() в середине потока (seek)
        System.out.println("[9] flush при перемотке");
        {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            run(p, sine(48000, 2, 1000, 0.5, 0.3), 2, 1024);
            p.flush();
            short[] o = run(p, sine(48000, 2, 1000, 0.5, 2.0), 2, 1024);
            double[] r = fit(o, 2, 44100, 1000, 22050, 44100);
            check(r[1] > 40, "после flush тон чистый, SNR=" + String.format("%.0f", r[1]));
        }


        // 10. Другие входные частоты (mp3/flac бывают не 48 кГц) и запасной путь Эрмита для нестандартной частоты
        System.out.println("[10] Другие входные частоты");
        for (int rate : new int[] {22050, 32000, 88200, 96000, 192000, 47000, 47123}) {
            ZenyAudioProcessor p = make(rate, 2, C.ENCODING_PCM_FLOAT);
            short[] out = run(p, sine(rate, 2, 1000, 0.5, 2.0), 2, 4096);
            int frames = out.length / 2;
            double[] r = fit(out, 2, 44100, 1000, 22050, 44100);
            double gainDb = db(r[0] / 0.5);
            System.out.printf("  %6d Гц -> кадров %d, усиление %.3f дБ, SNR %.0f дБ%n", rate, frames, gainDb, r[1]);
            check(frames >= 88200 - 120 && frames <= 88200 + 2, "длина ~88200 (получено " + frames + ")");
            check(Math.abs(gainDb + 1.2) < 0.15, "усиление -1.2 дБ");
            check(r[1] > (rate >= 47000 ? 60 : 70), "SNR на 1 кГц");
        }

        // 11. Pure по умолчанию: без «Воздуха» и без кроссфида тракт прозрачен по уровню и без скрытых добавок
        System.out.println("[11] Pure по умолчанию (Air 0, кроссфид 0)");
        {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            for (double f : new double[] {62, 1000, 10000}) {
                p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                double[] r = fit(run(p, sine(48000, 2, f, 0.5, 2.0), 2, 4096), 2, 44100, f, 22050, 44100);
                double dev = db(r[0] / 0.5) + 1.2;
                System.out.printf("  %6.0f Гц: отклонение %+.3f дБ  SNR=%.1f дБ%n", f, dev, r[1]);
                check(Math.abs(dev) < 0.05, "Pure: отклонение от -1.2 дБ < 0.05 дБ на " + f + " Гц");
            }
        }

        // 12. «Воздух» не добавляет гармоник: линейный фильтр. SNR = THD+N с дизером
        System.out.println("[12] «Воздух»: нет новых гармоник (вход 48 кГц, -6 dBFS)");
        for (double f : new double[] {62, 1000}) {
            for (int step = 1; step <= 5; step++) {
                ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                p.setAirStep(step);
                double[] r = fit(run(p, sine(48000, 2, f, 0.5, 2.0), 2, 4096), 2, 44100, f, 22050, 44100);
                check(r[1] > 80, String.format("%.0f Гц, шаг %d: THD+N %.1f дБ (порог -80)", f, step, -r[1]));
            }
        }

        // 13. «Сцена», моно: L=R на любой частоте не меняется (формула out = in + G*(LP(R)-LP(L)) даёт ровно in)
        System.out.println("[13] «Сцена»: моно плоско (L=R)");
        {
            double[] mf = {20, 50, 100, 200, 400, 700, 1000, 2000, 4000, 8000, 10000, 16000};
            float[] noise = new float[48000 * 2 * 2];
            java.util.Random rnd = new java.util.Random(7);
            for (int i = 0; i < noise.length; i += 2) noise[i] = noise[i + 1] = (float) (0.25 * rnd.nextGaussian());
            short[] ref0 = run(make(48000, 2, C.ENCODING_PCM_FLOAT), noise, 2, 4096);
            for (int step = 1; step <= 3; step++) {
                ZenyAudioProcessor pn = make(48000, 2, C.ENCODING_PCM_FLOAT);
                pn.setCrossfeedStep(step);
                check(java.util.Arrays.equals(ref0, run(pn, noise, 2, 4096)), "шаг " + step + ": моно-шум побитово равен шагу 0");
                double maxDev = 0, at = mf[0];
                for (double f : mf) {
                    ZenyAudioProcessor p0 = make(48000, 2, C.ENCODING_PCM_FLOAT);
                    ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                    p.setCrossfeedStep(step);
                    double a0 = fit(run(p0, sine(48000, 2, f, 0.1, 2.0), 2, 4096), 2, 44100, f, 22050, 44100)[0];
                    double a1 = fit(run(p, sine(48000, 2, f, 0.1, 2.0), 2, 4096), 2, 44100, f, 22050, 44100)[0];
                    double d = db(a1 / a0);
                    if (Math.abs(d) > Math.abs(maxDev)) { maxDev = d; at = f; }
                }
                System.out.printf("  шаг %d: макс. отклонение моно %+.4f дБ на %.0f Гц%n", step, maxDev, at);
                check(Math.abs(maxDev) <= 0.15, "шаг " + step + ": моно ±0.15 дБ на 20 Гц - 16 кГц");
            }
        }

        // 14. «Сцена», только левый канал: прямой и перекрёстный путь против формулы
        System.out.println("[14] «Сцена»: левый вход, прямой L->L и перекрёстный L->R");
        {
            double[] feed = {0, 3.0, 4.5, 6.0};
            double[] hf = {100, 700, 3000, 10000};
            for (int step = 1; step <= 3; step++) {
                double maxDev = 0;
                for (double f : hf) {
                    int n = 48000 * 2;
                    float[] in = new float[n * 2];
                    for (int i = 0; i < n; i++) in[i * 2] = (float) (0.3 * Math.sin(2 * Math.PI * f * i / 48000.0));
                    ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                    p.setCrossfeedStep(step);
                    short[] out = run(p, in, 2, 4096);
                    double dir = db(fitCh(out, 2, 0, 44100, f, 22050, 44100)[0] / 0.3) + 1.2;
                    double crs = db(fitCh(out, 2, 1, 44100, f, 22050, 44100)[0] / 0.3) + 1.2;
                    double[] an = stageAnalyticDb(f, 48000, feed[step]);
                    double dev = Math.max(Math.abs(dir - an[0]), Math.abs(crs - an[1]));
                    maxDev = Math.max(maxDev, dev);
                    System.out.printf("  шаг %d, %5.0f Гц: L->L %+6.2f (формула %+6.2f)  L->R %+6.2f (формула %+6.2f)  разделение %.2f дБ%n",
                            step, f, dir, an[0], crs, an[1], dir - crs);
                    if (f == 100) {
                        check(Math.abs((dir - crs) - feed[step]) <= 0.3, "шаг " + step + ": на 100 Гц разделение " + String.format("%.2f", dir - crs) + " дБ = подача " + feed[step] + " ±0.3");
                    }
                    if (f == 10000) check(crs < -30, "шаг " + step + ": на 10 кГц уровень в R ниже -30 дБ относительно входа (" + String.format("%.2f", crs) + "; относительно L->L " + String.format("%.2f", crs - dir) + ")");
                }
                check(maxDev <= 0.1, "шаг " + step + ": измерение против формулы ≤ 0.1 дБ (макс " + String.format("%.3f", maxDev) + ")");
            }
        }

        // 15. «Сцена» линейна: тихий вход даёт пропорционально тихий выход; шум и гармоники те же, что при шаге 0
        System.out.println("[15] «Сцена»: линейность и THD+N");
        {
            for (int step = 1; step <= 3; step++) {
                for (double f : new double[] {62, 1000}) {
                    int n = 48000 * 2;
                    float[] in = new float[n * 2];
                    for (int i = 0; i < n; i++) in[i * 2] = (float) (0.5 * Math.sin(2 * Math.PI * f * i / 48000.0));
                    ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                    p.setCrossfeedStep(step);
                    double[] r = fitCh(run(p, in, 2, 4096), 2, 0, 44100, f, 22050, 44100);
                    check(r[1] > 80, String.format("шаг %d, %.0f Гц, левый канал: THD+N %.1f дБ (порог -80)", step, f, -r[1]));
                }
                // -20 дБ на входе даёт ровно -20 дБ на выходе в обоих путях
                double aLoud = 0, aQuiet = 0, cLoud = 0, cQuiet = 0;
                for (int pass = 0; pass < 2; pass++) {
                    double amp = pass == 0 ? 0.5 : 0.05;
                    int n = 48000 * 2;
                    float[] in = new float[n * 2];
                    for (int i = 0; i < n; i++) in[i * 2] = (float) (amp * Math.sin(2 * Math.PI * 200 * i / 48000.0));
                    ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                    p.setCrossfeedStep(step);
                    short[] out = run(p, in, 2, 4096);
                    double d = fitCh(out, 2, 0, 44100, 200, 22050, 44100)[0] / amp, c = fitCh(out, 2, 1, 44100, 200, 22050, 44100)[0] / amp;
                    if (pass == 0) { aLoud = d; cLoud = c; } else { aQuiet = d; cQuiet = c; }
                }
                check(Math.abs(db(aQuiet / aLoud)) < 0.05 && Math.abs(db(cQuiet / cLoud)) < 0.05,
                        String.format("шаг %d: -20 дБ на входе = -20 дБ на выходе (прямой %+.3f, перекрёстный %+.3f дБ)", step, db(aQuiet / aLoud), db(cQuiet / cLoud)));
            }
        }

        // 16. Межуншная задержка = групповая задержка перекрёстного пути минус прямого. Фаза снимается с настоящего
        // процессора на частотах f±10 Гц (окно ровно 1 с), задержка ресемплера входит в оба пути одинаково
        System.out.println("[16] «Сцена»: межуншная задержка (перекрёстный минус прямой), мкс");
        {
            double[] feed = {0, 3.0, 4.5, 6.0};
            for (int step = 1; step <= 3; step++) {
                StringBuilder row = new StringBuilder();
                double worst = 0;
                for (double f : new double[] {100, 200, 400, 700}) {
                    double[] th = new double[4];
                    int idx = 0;
                    for (double ff : new double[] {f - 10, f + 10}) {
                        int n = 48000 * 2;
                        float[] in = new float[n * 2];
                        for (int i = 0; i < n; i++) in[i * 2] = (float) (0.3 * Math.sin(2 * Math.PI * ff * i / 48000.0));
                        ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                        p.setCrossfeedStep(step);
                        short[] out = run(p, in, 2, 4096);
                        th[idx++] = phaseCh(out, 2, 0, 44100, ff, 22050, 44100);
                        th[idx++] = phaseCh(out, 2, 1, 44100, ff, 22050, 44100);
                    }
                    double w = 2 * Math.PI * 20;
                    double gdDir = -wrap(th[2] - th[0]) / w * 1e6, gdCross = -wrap(th[3] - th[1]) / w * 1e6;
                    double[] pa = stagePhase(f - 10, 48000, feed[step]), pb = stagePhase(f + 10, 48000, feed[step]);
                    double gdRel = gdCross - gdDir;
                    double gdRelAn = (-(pb[1] - pa[1]) + (pb[0] - pa[0])) / w * 1e6;
                    worst = Math.max(worst, Math.abs(gdRel - gdRelAn));
                    row.append(String.format("  %.0f Гц: %.0f мкс (формула %.0f; прямой %.0f, перекрёстный %.0f)", f, gdRel, gdRelAn, gdDir, gdCross));
                }
                System.out.println("  шаг " + step + ":" + row);
                check(worst <= 15, "шаг " + step + ": задержка совпадает с формулой в пределах 15 мкс (макс " + String.format("%.1f", worst) + ")");
            }
        }

        System.out.println(failures == 0 ? "\nВСЕ ПРОВЕРКИ ПРОШЛИ" : "\nПРОВАЛОВ: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
