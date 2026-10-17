import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat;
import com.themoon.y1.managers.ZenyAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Сравнивает нативное ядро с Java-реализацией на одинаковом входе: разница должна быть в пределах пары младших бит. */
public class Parity {
    static int failures = 0;

    static ZenyAudioProcessor make(boolean nat, int rate, int ch, int enc) throws Exception {
        ZenyAudioProcessor p = new ZenyAudioProcessor();
        p.setNativeEnabled(nat);
        p.configure(new AudioFormat(rate, ch, enc));
        p.flush();
        if (nat && !p.isNativeActive()) throw new IllegalStateException("нативное ядро не загрузилось");
        if (!nat && p.isNativeActive()) throw new IllegalStateException("Java-путь выбрал нативное ядро");
        return p;
    }

    /** Музыкоподобный сигнал: сумма синусов, шум и редкие выбросы до 0 dBFS, чтобы задеть мягкий клиппер. */
    static float[] signal(int rate, int ch, double seconds, long seed) {
        Random rnd = new Random(seed);
        int n = (int) Math.round(rate * seconds);
        float[] x = new float[n * ch];
        double[] f = {55, 220, 880, 3300, 7000, 12000};
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < ch; c++) {
                double v = 0;
                for (int k = 0; k < f.length; k++) {
                    v += Math.sin(2 * Math.PI * f[k] * (1 + 0.01 * c) * i / rate + k) * (0.25 / (1 + k * 0.4));
                }
                v += (rnd.nextDouble() - 0.5) * 0.08;
                if ((i % 20000) < 300) v *= 3.0;
                x[i * ch + c] = (float) Math.max(-1.2, Math.min(1.2, v));
            }
        }
        return x;
    }

    static ByteBuffer encode(float[] in, int from, int frames, int ch, int enc) {
        int bps = enc == C.ENCODING_PCM_16BIT ? 2 : enc == C.ENCODING_PCM_24BIT ? 3 : enc == C.ENCODING_PCM_8BIT ? 1 : 4;
        ByteBuffer b = ByteBuffer.allocateDirect(frames * ch * bps).order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames * ch; i++) {
            float v = in[from * ch + i];
            switch (enc) {
                case C.ENCODING_PCM_FLOAT: b.putFloat(v); break;
                case C.ENCODING_PCM_16BIT: b.putShort((short) Math.round(Math.max(-1f, Math.min(0.99997f, v)) * 32768f)); break;
                case C.ENCODING_PCM_24BIT: {
                    int s = Math.round(Math.max(-1f, Math.min(0.9999998f, v)) * 8388608f);
                    b.put((byte) s).put((byte) (s >> 8)).put((byte) (s >> 16));
                    break;
                }
                case C.ENCODING_PCM_32BIT: b.putInt((int) Math.max(-2147483648.0, Math.min(2147483647.0, v * 2147483648.0))); break;
                default: b.put((byte) (Math.round(Math.max(-1f, Math.min(0.99f, v)) * 128f) + 128));
            }
        }
        b.flip();
        return b;
    }

    static short[] run(ZenyAudioProcessor p, float[] in, int ch, int enc, int chunk) {
        List<short[]> parts = new ArrayList<>();
        int total = 0;
        int frames = in.length / ch;
        for (int f = 0; f < frames; f += chunk) {
            int n = Math.min(chunk, frames - f);
            p.queueInput(encode(in, f, n, ch, enc));
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

    interface Cfg { void apply(ZenyAudioProcessor p); }

    static void compare(String name, int rate, int ch, int enc, int chunk, Cfg cfg) throws Exception {
        float[] in = signal(rate, ch, 3.0, 42);
        ZenyAudioProcessor a = make(false, rate, ch, enc);
        ZenyAudioProcessor b = make(true, rate, ch, enc);
        cfg.apply(a);
        cfg.apply(b);
        short[] ja = run(a, in, ch, enc, chunk);
        short[] nb = run(b, in, ch, enc, chunk);
        boolean sameLen = ja.length == nb.length;
        int maxDiff = 0;
        long bigger = 0;
        double sumAbs = 0;
        int n = Math.min(ja.length, nb.length);
        for (int i = 0; i < n; i++) {
            int d = Math.abs(ja[i] - nb[i]);
            if (d > maxDiff) maxDiff = d;
            if (d > 2) bigger++;
            sumAbs += d;
        }
        boolean ok = sameLen && maxDiff <= 3 && bigger == 0;
        System.out.printf("  %s %-46s длина java=%d nat=%d  макс.разница=%d  средняя=%.4f%n",
                ok ? "OK  " : "FAIL", name, ja.length, nb.length, maxDiff, n > 0 ? sumAbs / n : 0.0);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[паритет] нативное ядро против Java");
        compare("48k float стерео, Natural", 48000, 2, C.ENCODING_PCM_FLOAT, 4096, p -> {});
        compare("48k float стерео, чанк 777", 48000, 2, C.ENCODING_PCM_FLOAT, 777, p -> {});
        compare("48k float стерео, чанк 1", 48000, 2, C.ENCODING_PCM_FLOAT, 1, p -> {});
        compare("48k float стерео, Natural OFF", 48000, 2, C.ENCODING_PCM_FLOAT, 4096, p -> p.setZenyNaturalEnabled(false));
        compare("48k float моно, Natural", 48000, 1, C.ENCODING_PCM_FLOAT, 4096, p -> {});
        compare("44.1k 16бит стерео, без ресемплинга", 44100, 2, C.ENCODING_PCM_16BIT, 4096, p -> {});
        compare("44.1k 16бит стерео, Natural OFF", 44100, 2, C.ENCODING_PCM_16BIT, 4096, p -> p.setZenyNaturalEnabled(false));
        compare("22.05k 16бит стерео (х2 вверх)", 22050, 2, C.ENCODING_PCM_16BIT, 1000, p -> {});
        compare("96k float стерео", 96000, 2, C.ENCODING_PCM_FLOAT, 4096, p -> {});
        compare("32k 16бит моно", 32000, 1, C.ENCODING_PCM_16BIT, 1500, p -> {});
        compare("48k 24бит стерео", 48000, 2, C.ENCODING_PCM_24BIT, 4096, p -> {});
        compare("48k 32бит стерео", 48000, 2, C.ENCODING_PCM_32BIT, 4096, p -> {});
        compare("48k 8бит стерео", 48000, 2, C.ENCODING_PCM_8BIT, 4096, p -> {});
        compare("47123 Гц float (запасной путь Эрмита?)", 47123, 2, C.ENCODING_PCM_FLOAT, 4096, p -> {});
        compare("48k EQ полосы +6/-4", 48000, 2, C.ENCODING_PCM_FLOAT, 4096, p -> {
            p.setSoftwareEqEnabled(true);
            p.setBandLevel(1, 6f);
            p.setBandLevel(5, -4f);
            p.setBandLevel(8, 3f);
        });
        compare("48k crossfeed шаг 2", 48000, 2, C.ENCODING_PCM_FLOAT, 4096, p -> p.setCrossfeedStep(2));
        compare("48k SoundCheck с тегом +2.5 дБ", 48000, 2, C.ENCODING_PCM_FLOAT, 4096, p -> {
            p.setSoundCheckEnabled(true);
            p.setTrackGainDb(2.5f);
        });
        compare("48k SoundCheck без тега (AGC)", 48000, 2, C.ENCODING_PCM_FLOAT, 4096, p -> p.setSoundCheckEnabled(true));
        compare("44.1k AGC + EQ + crossfeed 3", 44100, 2, C.ENCODING_PCM_16BIT, 2048, p -> {
            p.setSoundCheckEnabled(true);
            p.setSoftwareEqEnabled(true);
            p.setBandLevel(0, 8f);
            p.setBandLevel(9, -6f);
            p.setCrossfeedStep(3);
        });
        System.out.println(failures == 0 ? "ВСЕ СОВПАЛИ" : ("РАСХОЖДЕНИЙ: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }
}
