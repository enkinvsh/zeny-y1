package com.themoon.y1.managers;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import com.themoon.y1.managers.ClickSynth.ClickKind;
import com.themoon.y1.managers.ClickSynth.ClickVariant;
import java.util.Arrays;

/**
 * Проигрывает щелчки колеса из {@link ClickSynth}. Два статических AudioTrack (шаг и нажатие) на
 * текущий вариант и уровень, один отдельный поток. Дорожка идёт в STREAM_MUSIC: AudioFlinger сам
 * смешивает её с музыкой, фокус не берётся, ExoPlayer не приглушается и не ставится на паузу.
 *
 * <p>Задержка щелчка складывается из очереди микшера (кадр 1024 отсчёта, 23 мс), буфера драйвера и
 * пробуждения железа: на этой прошивке звук уходит в standby через 3 с тишины, и первый щелчок после
 * паузы ждёт включения кодека. Поэтому: (1) дорожка заранее взведена (stop и reloadStaticData сделаны
 * после прошлого щелчка), и на горячем пути остаётся один play(); (2) на окно {@link #WARM_MS} после
 * действий пользователя включается беззвучная зацикленная дорожка, она не даёт железу уснуть между
 * щелчками. Окно короткое и привязано к активности, в простое батарею не ест.
 *
 * <p>Вся работа с AudioTrack идёт только в потоке "zeny-click"; из остальных потоков вызываются
 * лишь методы, которые кладут сообщение в очередь.
 */
public final class ClickPlayer {
    private static final String TAG = "ClickPlayer";
    private static final int MSG_STEP = 1;
    private static final int MSG_PRESS = 2;
    private static final int MSG_CONFIG = 3;
    private static final int MSG_RELEASE = 4;
    private static final int MSG_REARM_STEP = 5;
    private static final int MSG_REARM_PRESS = 6;
    private static final int MSG_WARM_START = 7;
    private static final int MSG_WARM_STOP = 8;

    // Колесо крутят до 20 шагов в секунду. Щелчок следующего шага ближе 70 мс обрывает хвост
    // предыдущего, и на полной громкости это даёт очередь, а не отдельные тики.
    private static final long FAST_SPIN_MS = 70;
    private static final float FAST_SPIN_VOLUME = 0.6f;
    private static final int FALLBACK_SAMPLE_RATE = 44100;
    // Окно, в котором железо удерживается от standby (штатный таймаут 3 с). Восемь секунд покрывают паузу
    // между прокрутками меню и при этом заметно короче, чем «вечно».
    private static final long WARM_MS = 8000;
    // Дорожка взводится заново после доигрывания щелчка плюс запас на микшер и буфер драйвера: раньше
    // stop() мог бы обрубить щелчок, который микшер ещё не успел забрать.
    private static final long REARM_MARGIN_MS = 80;
    private static final int WARM_FRAMES = 2048;

    private volatile Handler handler;
    private HandlerThread thread;
    private boolean released;

    // Ниже поля потока "zeny-click".
    private ClickVariant variant = ClickVariant.OFF;
    private int level = 2;
    private boolean dirty;
    private boolean disabled;
    private AudioTrack stepTrack;
    private AudioTrack pressTrack;
    private AudioTrack warmTrack;
    private boolean warmPlaying;
    private boolean armedStep;
    private boolean armedPress;
    private long stepMs;
    private long pressMs;
    private int sampleRate;
    private long lastPlayMs;
    private boolean latencyLogged;

    /** Меняет вариант и уровень; дорожки пересобираются заранее, чтобы первый щелчок не ждал синтеза. */
    public void configure(ClickVariant v, int newLevel) {
        Handler h = ensureStarted();
        if (h == null) return;
        h.removeMessages(MSG_CONFIG);
        h.sendMessage(h.obtainMessage(MSG_CONFIG, v.ordinal(), newLevel));
    }

    /** Щелчок текущего варианта. Если такой же щелчок ещё ждёт в очереди, новый не ставится. */
    public void play(ClickKind kind) {
        Handler h = ensureStarted();
        if (h == null) return;
        int what = kind == ClickKind.STEP ? MSG_STEP : MSG_PRESS;
        if (h.hasMessages(what)) return;
        h.sendMessage(h.obtainMessage(what));
    }

    /** Пользователь вот-вот начнёт крутить колесо (экран включён, разблокировка): будим железо заранее. */
    public void prewarm() {
        Handler h = ensureStarted();
        if (h == null) return;
        h.sendMessage(h.obtainMessage(MSG_WARM_START));
    }

    /** Экран гаснет: окно прогрева закрывается сразу, чтобы не держать звук включённым впустую. */
    public void cooldown() {
        Handler h = ensureStarted();
        if (h == null) return;
        h.removeMessages(MSG_WARM_START);
        h.sendMessage(h.obtainMessage(MSG_WARM_STOP));
    }

    /**
     * Предпрослушивание варианта, который ещё не сохранён. Очередь очищается: из быстро пролистанных
     * строк должен прозвучать вариант последней, а не накопленный хвост. Сохранённый вариант потом
     * возвращают через {@link #configure}.
     */
    public void preview(ClickVariant v, ClickKind kind, int previewLevel) {
        Handler h = ensureStarted();
        if (h == null) return;
        h.removeMessages(MSG_STEP);
        h.removeMessages(MSG_PRESS);
        h.removeMessages(MSG_CONFIG);
        h.sendMessage(h.obtainMessage(MSG_CONFIG, v.ordinal(), previewLevel));
        h.sendMessage(h.obtainMessage(kind == ClickKind.STEP ? MSG_STEP : MSG_PRESS));
    }

    /** Освобождает дорожки и останавливает поток; после вызова все методы молча ничего не делают. */
    public synchronized void release() {
        released = true;
        if (handler == null) return;
        handler.removeCallbacksAndMessages(null);
        handler.sendMessage(handler.obtainMessage(MSG_RELEASE));
        handler = null;
        thread = null;
    }

    private synchronized Handler ensureStarted() {
        if (released) return null;
        if (handler == null) {
            thread = new HandlerThread("zeny-click", Process.THREAD_PRIORITY_AUDIO);
            thread.start();
            handler = new Handler(thread.getLooper(), new Handler.Callback() {
                @Override
                public boolean handleMessage(Message msg) {
                    onMessage(msg);
                    return true;
                }
            });
        }
        return handler;
    }

    private void onMessage(Message msg) {
        switch (msg.what) {
            case MSG_CONFIG:
                ClickVariant wanted = ClickVariant.values()[msg.arg1];
                // Тот же вариант и уровень не пересобираем: создание двух AudioTrack это вызовы binder в
                // AudioFlinger, и предпрослушивание отставало бы от настоящего щелчка с готовыми дорожками.
                if (wanted == variant && msg.arg2 == level && !dirty
                        && (disabled || stepTrack != null || !wanted.isRendered())) {
                    break;
                }
                variant = wanted;
                level = msg.arg2;
                dirty = true;
                rebuildIfNeeded();
                break;
            case MSG_STEP:
                playTrack(ClickKind.STEP);
                break;
            case MSG_PRESS:
                playTrack(ClickKind.PRESS);
                break;
            case MSG_REARM_STEP:
                armedStep = rearm(stepTrack);
                break;
            case MSG_REARM_PRESS:
                armedPress = rearm(pressTrack);
                break;
            case MSG_WARM_START:
                startWarm();
                break;
            case MSG_WARM_STOP:
                stopWarm();
                break;
            case MSG_RELEASE:
                releaseTracks();
                releaseWarm();
                Looper.myLooper().quit();
                break;
            default:
                break;
        }
    }

    private void rebuildIfNeeded() {
        if (!dirty) return;
        dirty = false;
        releaseTracks();
        if (disabled || !variant.isRendered()) return;
        if (sampleRate == 0) sampleRate = nativeSampleRate();
        try {
            short[] step = ClickSynth.render(variant, ClickKind.STEP, level, sampleRate);
            short[] press = ClickSynth.render(variant, ClickKind.PRESS, level, sampleRate);
            stepMs = step.length * 1000L / sampleRate;
            pressMs = press.length * 1000L / sampleRate;
            stepTrack = buildTrack(step);
            pressTrack = buildTrack(press);
        } catch (RuntimeException e) {
            stepTrack = null;
            pressTrack = null;
        }
        // Свежая статическая дорожка уже стоит на нуле, взводить её не нужно.
        armedStep = true;
        armedPress = true;
        if (stepTrack == null || pressTrack == null) {
            disable("AudioTrack is not initialized");
        } else {
            logLatencyOnce();
        }
    }

    private void playTrack(ClickKind kind) {
        if (disabled) return;
        rebuildIfNeeded();
        boolean step = kind == ClickKind.STEP;
        AudioTrack track = step ? stepTrack : pressTrack;
        if (track == null) return;
        long now = SystemClock.uptimeMillis();
        float volume = now - lastPlayMs < FAST_SPIN_MS ? FAST_SPIN_VOLUME : 1.0f;
        lastPlayMs = now;
        boolean played = tryPlay(track, volume, step ? armedStep : armedPress);
        if (!played) {
            // Один пересбор на случай, когда AudioFlinger убил дорожку (например после смены аудиоустройства).
            dirty = true;
            rebuildIfNeeded();
            track = step ? stepTrack : pressTrack;
            played = !disabled && track != null && tryPlay(track, volume, true);
            if (!played) {
                disable("playback failed twice");
                return;
            }
        }
        if (step) armedStep = false;
        else armedPress = false;
        // Всё, что не нужно для звука, идёт после play(): взвод дорожки и продление окна прогрева.
        Handler h = handler;
        if (h != null) {
            int what = step ? MSG_REARM_STEP : MSG_REARM_PRESS;
            h.removeMessages(what);
            h.sendMessageDelayed(h.obtainMessage(what), (step ? stepMs : pressMs) + REARM_MARGIN_MS);
        }
        startWarm();
    }

    private boolean tryPlay(AudioTrack track, float volume, boolean armed) {
        try {
            if (!armed) {
                track.stop();
                track.reloadStaticData();
            }
            track.setStereoVolume(volume, volume);
            track.play();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // Возвращает true, если дорожка стоит на нуле и следующий щелчок обойдётся одним play().
    private boolean rearm(AudioTrack track) {
        if (track == null || disabled) return false;
        try {
            track.stop();
            track.reloadStaticData();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void startWarm() {
        if (disabled || !variant.isRendered()) return;
        Handler h = handler;
        if (h == null) return;
        if (!warmPlaying) {
            if (sampleRate == 0) sampleRate = nativeSampleRate();
            if (warmTrack == null) warmTrack = buildWarmTrack();
            if (warmTrack == null) return;
            try {
                warmTrack.play();
                warmPlaying = true;
            } catch (RuntimeException e) {
                releaseQuietly(warmTrack);
                warmTrack = null;
                return;
            }
        }
        h.removeMessages(MSG_WARM_STOP);
        h.sendMessageDelayed(h.obtainMessage(MSG_WARM_STOP), WARM_MS);
    }

    private void stopWarm() {
        if (warmTrack == null || !warmPlaying) return;
        warmPlaying = false;
        try {
            warmTrack.pause();
        } catch (RuntimeException e) {
            releaseQuietly(warmTrack);
            warmTrack = null;
        }
    }

    // Беззвучная зацикленная дорожка: нули не слышны, но микшер и кодек остаются включёнными.
    private AudioTrack buildWarmTrack() {
        AudioTrack track = null;
        try {
            short[] silence = new short[WARM_FRAMES];
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, silence.length * 2, AudioTrack.MODE_STATIC);
            if (track.getState() != AudioTrack.STATE_UNINITIALIZED && track.write(silence, 0, silence.length) > 0
                    && track.getState() == AudioTrack.STATE_INITIALIZED
                    && track.setLoopPoints(0, WARM_FRAMES, -1) == AudioTrack.SUCCESS) {
                return track;
            }
        } catch (RuntimeException e) {
            // дорожка прогрева не критична: щелчки работают и без неё, просто первый после паузы позже
        }
        if (track != null) releaseQuietly(track);
        return null;
    }

    // Задержка выходного потока в логе один раз за сборку дорожек: по ней видно, чего ждать от платформы.
    // Метод скрытый, поэтому через рефлексию; не вышло, значит строки в логе не будет, и всё.
    private void logLatencyOnce() {
        if (latencyLogged) return;
        latencyLogged = true;
        try {
            Object ms = Class.forName("android.media.AudioSystem")
                    .getMethod("getOutputLatency", int.class).invoke(null, AudioManager.STREAM_MUSIC);
            Log.i(TAG, "variant=" + variant.id() + " rate=" + sampleRate + " step=" + stepMs + "ms press=" + pressMs
                    + "ms hal output latency=" + ms + "ms");
        } catch (Exception e) {
            Log.i(TAG, "variant=" + variant.id() + " rate=" + sampleRate + " (output latency unavailable)");
        }
    }

    private AudioTrack buildTrack(short[] pcm) {
        AudioTrack track = newTrack(pcm, pcm.length * 2);
        if (track != null) return track;
        // Некоторые прошивки не принимают статический буфер короче минимального размера для потока:
        // добиваем тишиной один раз. Лишняя тишина в конце не слышна, но дорожка успевает доиграть.
        int minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= pcm.length * 2) return null;
        short[] padded = Arrays.copyOf(pcm, (minBytes + 1) / 2);
        return newTrack(padded, padded.length * 2);
    }

    private AudioTrack newTrack(short[] data, int sizeBytes) {
        AudioTrack track = null;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, sizeBytes, AudioTrack.MODE_STATIC);
            // До write() статическая дорожка в состоянии NO_STATIC_DATA, а не INITIALIZED: проверка на
            // INITIALIZED до записи отвергала каждую дорожку, и щелчок молчал на любом устройстве.
            if (track.getState() != AudioTrack.STATE_UNINITIALIZED && track.write(data, 0, data.length) > 0
                    && track.getState() == AudioTrack.STATE_INITIALIZED) {
                return track;
            }
        } catch (RuntimeException e) {
            // Падение конструктора или write тоже значит «дорожка недоступна»: разбираемся ниже.
        }
        if (track != null) {
            try {
                track.release();
            } catch (RuntimeException ignored) {
                // дорожка уже негодная, освобождать нечего
            }
        }
        return null;
    }

    private void releaseTracks() {
        releaseQuietly(stepTrack);
        releaseQuietly(pressTrack);
        stepTrack = null;
        pressTrack = null;
    }

    // Дорожка прогрева живёт отдельно от щелчков: смена варианта при предпрослушивании её не трогает.
    private void releaseWarm() {
        releaseQuietly(warmTrack);
        warmTrack = null;
        warmPlaying = false;
    }

    private static void releaseQuietly(AudioTrack track) {
        if (track == null) return;
        try {
            track.stop();
        } catch (RuntimeException ignored) {
            // дорожка могла быть не запущена
        }
        try {
            track.release();
        } catch (RuntimeException ignored) {
            // уже освобождена
        }
    }

    private void disable(String reason) {
        if (disabled) return;
        disabled = true;
        releaseTracks();
        releaseWarm();
        // Один раз: повторных попыток нет, иначе при сломанном аудиотракте щелчок молотил бы по системе.
        Log.w(TAG, "click sound disabled: " + reason);
    }

    private static int nativeSampleRate() {
        try {
            int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
            if (rate >= 8000 && rate <= 192000) return rate;
        } catch (RuntimeException ignored) {
            // берём запасную частоту
        }
        return FALLBACK_SAMPLE_RATE;
    }
}
