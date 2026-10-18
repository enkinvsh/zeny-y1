import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat;
import com.themoon.y1.managers.ZenyAudioProcessor;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Прогоняет WAV через настоящий ZenyAudioProcessor и пишет 16-битный 44.1 кГц WAV ровно так, как его
 * получил бы AudioTrack плеера. Задания читаются пачкой из файла, чтобы не поднимать JVM на каждый стимул.
 *
 * Строка задания: in.wav out.wav air(0..5) crossfeedStep(0..3) soundCheck(0|1) tagDb(число или -)
 * Ядро выбирается -Dzeny.native=0|1, как в tools/dsp-test/Harness.java.
 */
public class Render {
    // Декодер Opus в ExoPlayer отдаёт пакеты по 20 мс; выход от размера куска не зависит (Harness [4]),
    // но берём тот же размер, что на устройстве
    static final int CHUNK_FRAMES = 960;

    public static void main(String[] args) throws Exception {
        boolean wantNative = "1".equals(System.getProperty("zeny.native"));
        int jobs = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\s+");
                render(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4].equals("1"),
                        f[5].equals("-") ? null : Float.valueOf(f[5]), wantNative);
                jobs++;
            }
        }
        System.out.println("Render: заданий " + jobs + ", ядро " + (wantNative ? "native" : "java"));
    }

    static void render(String in, String out, int air, int xf, boolean sc, Float tagDb, boolean wantNative)
            throws Exception {
        Wav w = Wav.read(in);
        ZenyAudioProcessor p = new ZenyAudioProcessor();
        p.setNativeEnabled(wantNative);
        p.setAirStep(air);
        p.setCrossfeedStep(xf);
        p.setSoundCheckEnabled(sc);
        p.setTrackGainDb(tagDb);
        p.configure(new AudioFormat(w.rate, w.channels, C.ENCODING_PCM_FLOAT));
        p.flush();
        if (p.isNativeActive() != wantNative) {
            throw new IllegalStateException("ядро не совпало с заказанным: native=" + p.isNativeActive());
        }
        int ch = w.channels;
        int frames = w.samples.length / ch;
        ByteBuffer acc = ByteBuffer.allocate(frames * ch * 2 + 4096).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer b = ByteBuffer.allocateDirect(CHUNK_FRAMES * ch * 4).order(ByteOrder.nativeOrder());
        for (int f0 = 0; f0 < frames; f0 += CHUNK_FRAMES) {
            int n = Math.min(CHUNK_FRAMES, frames - f0);
            b.clear();
            for (int i = 0; i < n * ch; i++) b.putFloat(w.samples[f0 * ch + i]);
            b.flip();
            p.queueInput(b);
            ByteBuffer o = p.getOutput().order(ByteOrder.nativeOrder());
            while (o.hasRemaining()) acc.putShort(o.getShort());
        }
        acc.flip();
        Wav.write16(out, acc, ZenyAudioProcessor.TARGET_SAMPLE_RATE, ch);
    }

    /** Минимальный WAV: чтение float32/int16 (в т.ч. EXTENSIBLE), запись int16. */
    static final class Wav {
        int rate, channels;
        float[] samples;

        static Wav read(String path) throws IOException {
            ByteBuffer d = ByteBuffer.wrap(Files.readAllBytes(Paths.get(path))).order(ByteOrder.LITTLE_ENDIAN);
            Wav w = new Wav();
            int fmt = -1, bits = 0;
            d.position(12);
            while (d.remaining() >= 8) {
                byte[] id = new byte[4];
                d.get(id);
                int size = d.getInt();
                int start = d.position();
                String tag = new String(id, "US-ASCII");
                if (tag.equals("fmt ")) {
                    fmt = d.getShort() & 0xFFFF;
                    w.channels = d.getShort();
                    w.rate = d.getInt();
                    d.getInt();
                    d.getShort();
                    bits = d.getShort();
                    if (fmt == 0xFFFE) {
                        d.position(start + 24); // GUID подформата: первые 2 байта = код формата
                        fmt = d.getShort() & 0xFFFF;
                    }
                } else if (tag.equals("data")) {
                    boolean isFloat = fmt == 3 && bits == 32;
                    if (!isFloat && !(fmt == 1 && bits == 16)) throw new IOException("формат не поддержан: " + path);
                    int n = size / (bits / 8);
                    w.samples = new float[n];
                    for (int i = 0; i < n; i++) {
                        w.samples[i] = isFloat ? d.getFloat(start + i * 4) : d.getShort(start + i * 2) / 32768.0f;
                    }
                    return w;
                }
                d.position(start + size + (size & 1));
            }
            throw new IOException("нет data-блока: " + path);
        }

        static void write16(String path, ByteBuffer pcm, int rate, int ch) throws IOException {
            int bytes = pcm.remaining();
            ByteBuffer h = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes("US-ASCII")).putInt(36 + bytes).put("WAVE".getBytes("US-ASCII"));
            h.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) ch)
                    .putInt(rate).putInt(rate * ch * 2).putShort((short) (ch * 2)).putShort((short) 16);
            h.put("data".getBytes("US-ASCII")).putInt(bytes).put(pcm);
            Files.write(Paths.get(path), h.array());
        }
    }
}
