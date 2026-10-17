import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat;
import com.themoon.y1.managers.ZenyAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Замер стоимости DSP на устройстве (запуск через dalvikvm) и на JVM: во сколько раз быстрее реального времени. */
public class Bench {
    static float[] noise(int rate, double seconds) {
        int n = (int) (rate * seconds);
        float[] x = new float[n * 2];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < n; i++) {
            float v = (float) (0.25 * Math.sin(2 * Math.PI * 220 * i / rate) + 0.1 * (r.nextFloat() - 0.5));
            x[i * 2] = v;
            x[i * 2 + 1] = v * 0.9f;
        }
        return x;
    }

    static double run(ZenyAudioProcessor p, ByteBuffer all, int frames, int chunk) throws Exception {
        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f += chunk) {
            int n = Math.min(chunk, frames - f);
            all.limit((f + n) * 8);
            all.position(f * 8);
            p.queueInput(all.slice().order(ByteOrder.nativeOrder()));
            p.getOutput();
        }
        return (System.nanoTime() - t0) / 1e9;
    }

    static boolean useNative = true;

    static void bench(String name, int rate, boolean natural, boolean soundCheck, boolean eq) throws Exception {
        ZenyAudioProcessor p = new ZenyAudioProcessor();
        p.setNativeEnabled(useNative);
        p.configure(new AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT));
        p.flush();
        p.setZenyNaturalEnabled(natural);
        p.setSoundCheckEnabled(soundCheck);
        if (soundCheck) p.setTrackGainDb(null);
        if (eq) {
            p.setSoftwareEqEnabled(true);
            for (int b = 0; b < 10; b++) p.setBandLevel(b, (b % 2 == 0) ? 3.0f : -2.0f);
        }
        float[] in = noise(rate, 10.0);
        // Вход кодируется один раз, чтобы цикл упаковки на Java не попадал в замер
        ByteBuffer all = ByteBuffer.allocateDirect(in.length * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < in.length; i++) all.putFloat(in[i]);
        StringBuilder sb = new StringBuilder(String.format("%-34s", name));
        for (int pass = 0; pass < 4; pass++) {
            double sec = run(p, all, in.length / 2, 4096);
            double load = sec / 10.0 * 100.0;
            sb.append(String.format("  %5.1f%%", load));
        }
        System.out.println(sb + (p.isNativeActive() ? "  [native]" : "  [java]"));
    }

    public static void main(String[] args) throws Exception {
        useNative = args.length == 0 || !"java".equals(args[0]);
        System.out.println("доля одного ядра по проходам 1..4, 10 с аудио; режим " + (useNative ? "native" : "java"));
        bench("44.1k, без ресемплера, Natural OFF", 44100, false, false, false);
        bench("48k sinc, Natural OFF", 48000, false, false, false);
        bench("48k sinc, Natural ON", 48000, true, false, false);
        bench("48k sinc, Natural ON + AGC", 48000, true, true, false);
        bench("48k sinc, Natural ON + EQ 10 полос", 48000, true, false, true);
        bench("47123 Hermite, Natural OFF", 47123, false, false, false);
    }
}
