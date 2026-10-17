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

    static double db(double x) {
        return 20 * Math.log10(x);
    }

    public static void main(String[] args) throws Exception {
        // 1. Чистый путь: Natural выключен, SoundCheck выключен -> только -1.2 дБ и ресемплер 48 -> 44.1
        System.out.println("[1] Ресемплер и статический гейн (Natural OFF)");
        for (double f : new double[] {100, 1000, 5000, 10000, 15000}) {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            p.setZenyNaturalEnabled(false);
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


        // 1b. АЧХ ресемплера отдельно (Natural OFF): плоско до 19 кГц, выше 23 кГц подавление
        System.out.println("[1b] АЧХ ресемплера 48 -> 44.1");
        for (double f : new double[] {8000, 12000, 16000, 18000, 19000, 20000}) {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            p.setZenyNaturalEnabled(false);
            short[] out = run(p, sine(48000, 2, f, 0.5, 2.0), 2, 4096);
            double[] r = fit(out, 2, 44100, f, 22050, 44100);
            double dev = db(r[0] / 0.5) + 1.2;
            System.out.printf("  %6.0f Гц: отклонение %+.2f дБ  SNR=%.0f%n", f, dev, r[1]);
            if (f <= 19000) check(Math.abs(dev) < 0.15, "плоско ±0.15 дБ");
            else check(Math.abs(dev) < 0.8, "на 20 кГц спад не больше 0.8 дБ");
        }
        for (double f : new double[] {23000, 23500}) {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            p.setZenyNaturalEnabled(false);
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

        // 5. АЧХ профиля Zeny Natural (L=R, статически)
        System.out.println("[5] АЧХ Zeny Natural ON (минус базовые -1.6 дБ)");
        double[] fr = {31, 62, 100, 250, 1000, 2000, 3850, 6200, 10000, 13500, 18000};
        for (double f : fr) {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            float[] in = sine(48000, 2, f, 0.1, 2.0);
            short[] out = run(p, in, 2, 2048);
            double[] r = fit(out, 2, 44100, f, 22050, 44100);
            System.out.printf("  %6.0f Гц: %+6.2f дБ (относительно входа)  SNR=%.0f%n", f, db(r[0] / 0.1), r[1]);
        }


        // 5b. Громкость середины: Natural ON не должен менять уровень 1-2 кГц относительно OFF
        System.out.println("[5b] Середина ON против OFF");
        for (double f : new double[] {700, 1000, 2000}) {
            ZenyAudioProcessor off = make(48000, 2, C.ENCODING_PCM_FLOAT);
            off.setZenyNaturalEnabled(false);
            ZenyAudioProcessor on = make(48000, 2, C.ENCODING_PCM_FLOAT);
            double a0 = fit(run(off, sine(48000, 2, f, 0.1, 2.0), 2, 2048), 2, 44100, f, 22050, 44100)[0];
            double a1 = fit(run(on, sine(48000, 2, f, 0.1, 2.0), 2, 2048), 2, 44100, f, 22050, 44100)[0];
            double d = db(a1 / a0);
            System.out.printf("  %5.0f Гц: ON - OFF = %+.2f дБ%n", f, d);
            check(Math.abs(d) < 0.5, "разница уровня < 0.5 дБ");
        }

        // 6. Тег R128: +3 дБ при SoundCheck, клэмп +4
        System.out.println("[6] Sound Check с тегом");
        {
            double ref;
            {
                ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                p.setZenyNaturalEnabled(false);
                ref = fit(run(p, sine(48000, 2, 1000, 0.1, 2.0), 2, 2048), 2, 44100, 1000, 22050, 44100)[0];
            }
            for (float tag : new float[] {3.0f, -6.0f, 10.0f, -20.0f}) {
                ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
                p.setZenyNaturalEnabled(false);
                p.setSoundCheckEnabled(true);
                p.setTrackGainDb(tag);
                double a = fit(run(p, sine(48000, 2, 1000, 0.1, 2.0), 2, 2048), 2, 44100, 1000, 22050, 44100)[0];
                double exp = Math.max(-9.5, Math.min(4.0, tag));
                check(Math.abs(db(a / ref) - exp) < 0.1, "тег " + tag + " дБ -> применено " + String.format("%.2f", db(a / ref)) + " (ожидалось " + exp + ")");
            }
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            p.setZenyNaturalEnabled(false);
            p.setSoundCheckEnabled(false);
            p.setTrackGainDb(3.0f);
            double a = fit(run(p, sine(48000, 2, 1000, 0.1, 2.0), 2, 2048), 2, 44100, 1000, 22050, 44100)[0];
            check(Math.abs(db(a / ref)) < 0.05, "SoundCheck OFF игнорирует тег");
        }

        // 7. AGC без тега: плавность (нет качания внутри трека) и направление
        System.out.println("[7] AGC для треков без тега");
        {
            ZenyAudioProcessor p = make(48000, 2, C.ENCODING_PCM_FLOAT);
            p.setZenyNaturalEnabled(false);
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
            q.setZenyNaturalEnabled(false);
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
            p.setZenyNaturalEnabled(false);
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
            p.setZenyNaturalEnabled(false);
            short[] out = run(p, sine(rate, 2, 1000, 0.5, 2.0), 2, 4096);
            int frames = out.length / 2;
            double[] r = fit(out, 2, 44100, 1000, 22050, 44100);
            double gainDb = db(r[0] / 0.5);
            System.out.printf("  %6d Гц -> кадров %d, усиление %.3f дБ, SNR %.0f дБ%n", rate, frames, gainDb, r[1]);
            check(frames >= 88200 - 120 && frames <= 88200 + 2, "длина ~88200 (получено " + frames + ")");
            check(Math.abs(gainDb + 1.2) < 0.15, "усиление -1.2 дБ");
            check(r[1] > (rate >= 47000 ? 60 : 70), "SNR на 1 кГц");
        }

        System.out.println(failures == 0 ? "\nВСЕ ПРОВЕРКИ ПРОШЛИ" : "\nПРОВАЛОВ: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
