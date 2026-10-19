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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Проигрывает щелчки колеса из {@link ClickSynth}. Карусель из готовых статических AudioTrack (4 на
 * шаг и 2 на нажатие) на текущий вариант и уровень, один отдельный поток. Дорожка идёт в
 * STREAM_MUSIC: AudioFlinger сам смешивает её с музыкой, фокус не берётся, ExoPlayer не
 * приглушается и не ставится на паузу.
 *
 * <p>Задержка щелчка складывается из очереди микшера (кадр 1024 отсчёта, 23 мс), буфера драйвера и
 * пробуждения железа: на этой прошивке звук уходит в standby через 3 с тишины, и первый щелчок после
 * паузы ждёт включения кодека. Поэтому: (1) для шага колеса держим карусель из {@link #STEP_POOL_SIZE}
 * независимых статических дорожек — каждый щелчок запускает уже взведённую дорожку одним play();
 * (2) на окно {@link #WARM_MS} после действий пользователя включается беззвучная зацикленная дорожка,
 * она не даёт железу уснуть между щелчками. Окно короткое и привязано к активности, в простое батарею
 * не ест.
 *
 * <p>Взвод (stop()+reloadStaticData()) безопасен только для дорожки, которую AudioFlinger уже убрал из
 * активных. Отыгравшая статическая дорожка остаётся в микшере ещё на задержку HAL (~92 мс на Y1) плюс
 * пару циклов микшера. Если взвести её раньше, микшер Android 4.2 доигрывает свежий буфер остановленной
 * дорожки (лишний щелчок через ~100 мс), а при выбросе из активных обнуляет его — следующий play() на
 * «взведённой» дорожке молчит. Отсюда симптом «щелчка нет, потом два подряд». Поэтому взвод ждёт
 * {@link #rearmDelayMs}, а при нехватке взведённых дорожек щелчок пропускается, но живой буфер не
 * трогаем.
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
    private static final int MSG_WARM_START = 7;
    private static final int MSG_WARM_STOP = 8;
    private static final int MSG_REARM_STEP_BASE = 10;
    private static final int MSG_REARM_PRESS_BASE = 20;

    // Дорожка возвращается в строй через ~300 мс (см. rearmDelayMs): восемь дорожек держат прокрутку
    // до ~25 шагов/с, быстрее UI-поток Y1 шаги всё равно не отдаёт (40–75 мс на шаг по замеру).
    private static final int STEP_POOL_SIZE = 8;
    private static final int PRESS_POOL_SIZE = 3;
    // Не даём очереди расти бесконечно при лаге binder, но сохраняем парные тики быстрой прокрутки.
    private static final int MAX_PENDING_STEPS = 3;

    // Колесо крутят до 20 шагов в секунду. Щелчок следующего шага ближе 70 мс обрывает хвост
    // предыдущего, и на полной громкости это даёт очередь, а не отдельные тики.
    private static final long FAST_SPIN_MS = 70;
    private static final float FAST_SPIN_VOLUME = 0.6f;
    private static final int FALLBACK_SAMPLE_RATE = 44100;
    // Окно, в котором железо удерживается от standby (штатный таймаут 3 с). Восемь секунд покрывают паузу
    // между прокрутками меню и при этом заметно короче, чем «вечно».
    private static final long WARM_MS = 8000;
    // AudioFlinger убирает отыгравшую статическую дорожку через задержку HAL после первого недобора.
    // Две задержки HAL плюс 100 мс на циклы микшера и опоздание потока — с запасом; меньше 250 мс не
    // берём, даже если платформа занижает задержку.
    private static final long MIN_REARM_MS = 250;
    private static final long REARM_EXTRA_MS = 100;
    private static final int FALLBACK_LATENCY_MS = 100;
    private static final int WARM_FRAMES = 2048;

    private volatile Handler handler;
    private HandlerThread thread;
    private boolean released;
    private final AtomicInteger pendingSteps = new AtomicInteger();

    // Ниже поля потока "zeny-click".
    private ClickVariant variant = ClickVariant.OFF;
    private int level = 2;
    private boolean dirty;
    private boolean disabled;
    private final AudioTrack[] stepTracks = new AudioTrack[STEP_POOL_SIZE];
    private final AudioTrack[] pressTracks = new AudioTrack[PRESS_POOL_SIZE];
    private final boolean[] armedStep = new boolean[STEP_POOL_SIZE];
    private final boolean[] armedPress = new boolean[PRESS_POOL_SIZE];
    private int nextStepSlot;
    private int nextPressSlot;
    private AudioTrack warmTrack;
    private boolean warmPlaying;
    private long stepMs;
    private long pressMs;
    private long rearmStepMs;
    private long rearmPressMs;
    private int builtFrames;
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

    /** Щелчок текущего варианта. До {@link #MAX_PENDING_STEPS} шагов подряд сохраняются в очереди без потерь. */
    public void play(ClickKind kind) {
        Handler h = ensureStarted();
        if (h == null) return;
        if (kind == ClickKind.STEP) {
            if (pendingSteps.get() >= MAX_PENDING_STEPS) return;
            pendingSteps.incrementAndGet();
            if (!h.sendMessage(h.obtainMessage(MSG_STEP))) {
                pendingSteps.decrementAndGet();
            }
        } else {
            if (h.hasMessages(MSG_PRESS)) return;
            h.sendMessage(h.obtainMessage(MSG_PRESS));
        }
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
        pendingSteps.set(0);
        h.removeMessages(MSG_PRESS);
        h.removeMessages(MSG_CONFIG);
        h.sendMessage(h.obtainMessage(MSG_CONFIG, v.ordinal(), previewLevel));
        if (kind == ClickKind.STEP) {
            pendingSteps.incrementAndGet();
            if (!h.sendMessage(h.obtainMessage(MSG_STEP))) {
                pendingSteps.decrementAndGet();
            }
        } else {
            h.sendMessage(h.obtainMessage(MSG_PRESS));
        }
    }

    /** Освобождает дорожки и останавливает поток; после вызова все методы молча ничего не делают. */
    public synchronized void release() {
        released = true;
        pendingSteps.set(0);
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
        if (msg.what >= MSG_REARM_STEP_BASE && msg.what < MSG_REARM_STEP_BASE + STEP_POOL_SIZE) {
            int slot = msg.what - MSG_REARM_STEP_BASE;
            armedStep[slot] = rearm(stepTracks[slot]);
            return;
        }
        if (msg.what >= MSG_REARM_PRESS_BASE && msg.what < MSG_REARM_PRESS_BASE + PRESS_POOL_SIZE) {
            int slot = msg.what - MSG_REARM_PRESS_BASE;
            armedPress[slot] = rearm(pressTracks[slot]);
            return;
        }
        switch (msg.what) {
            case MSG_CONFIG:
                ClickVariant wanted = ClickVariant.values()[msg.arg1];
                // Тот же вариант и уровень не пересобираем: создание дорожек это вызовы binder в
                // AudioFlinger, и предпрослушивание отставало бы от настоящего щелчка с готовыми дорожками.
                if (wanted == variant && msg.arg2 == level && !dirty
                        && (disabled || stepTracks[0] != null || !wanted.isRendered())) {
                    break;
                }
                variant = wanted;
                level = msg.arg2;
                dirty = true;
                rebuildIfNeeded();
                break;
            case MSG_STEP:
                if (pendingSteps.decrementAndGet() < 0) pendingSteps.set(0);
                playTrack(ClickKind.STEP);
                break;
            case MSG_PRESS:
                playTrack(ClickKind.PRESS);
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
        boolean ok = true;
        try {
            short[] step = ClickSynth.render(variant, ClickKind.STEP, level, sampleRate);
            short[] press = ClickSynth.render(variant, ClickKind.PRESS, level, sampleRate);
            stepMs = step.length * 1000L / sampleRate;
            pressMs = press.length * 1000L / sampleRate;
            int latencyMs = outputLatencyMs();
            for (int i = 0; i < STEP_POOL_SIZE; i++) {
                stepTracks[i] = buildTrack(step);
                armedStep[i] = stepTracks[i] != null;
                if (stepTracks[i] == null) ok = false;
            }
            // Считаем по фактической длине буфера: прошивка могла добить его тишиной до минимума.
            rearmStepMs = rearmDelayMs(builtFrames * 1000L / sampleRate, latencyMs);
            for (int i = 0; i < PRESS_POOL_SIZE; i++) {
                pressTracks[i] = buildTrack(press);
                armedPress[i] = pressTracks[i] != null;
                if (pressTracks[i] == null) ok = false;
            }
            rearmPressMs = rearmDelayMs(builtFrames * 1000L / sampleRate, latencyMs);
            nextStepSlot = 0;
            nextPressSlot = 0;
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            releaseTracks();
            disable("AudioTrack is not initialized");
        } else {
            logLatencyOnce();
        }
    }

    private void playTrack(ClickKind kind) {
        if (disabled) return;
        rebuildIfNeeded();
        boolean step = kind == ClickKind.STEP;
        AudioTrack[] pool = step ? stepTracks : pressTracks;
        boolean[] armedPool = step ? armedStep : armedPress;
        int start = step ? nextStepSlot : nextPressSlot;
        int slot = -1;
        for (int i = 0; i < pool.length; i++) {
            int candidate = (start + i) % pool.length;
            if (pool[candidate] != null && armedPool[candidate]) {
                slot = candidate;
                break;
            }
        }
        if (slot < 0) {
            // Все дорожки ещё в микшере: лучше пропустить один тик сверхбыстрой прокрутки, чем
            // перевзвести живую дорожку и получить тишину сейчас и лишний щелчок позже.
            startWarm();
            return;
        }
        if (step) nextStepSlot = (slot + 1) % pool.length;
        else nextPressSlot = (slot + 1) % pool.length;
        AudioTrack track = pool[slot];
        long now = SystemClock.uptimeMillis();
        float volume = now - lastPlayMs < FAST_SPIN_MS ? FAST_SPIN_VOLUME : 1.0f;
        lastPlayMs = now;
        boolean played = tryPlay(track, volume);
        if (!played) {
            // Один пересбор на случай, когда AudioFlinger убил дорожку (например после смены аудиоустройства).
            dirty = true;
            rebuildIfNeeded();
            slot = 0;
            if (step) {
                nextStepSlot = 1 % STEP_POOL_SIZE;
                track = stepTracks[0];
            } else {
                nextPressSlot = 1 % PRESS_POOL_SIZE;
                track = pressTracks[0];
            }
            played = !disabled && track != null && tryPlay(track, volume);
            if (!played) {
                disable("playback failed twice");
                return;
            }
        }
        if (step) armedStep[slot] = false;
        else armedPress[slot] = false;
        // Всё, что не нужно для звука, идёт после play(): взвод использованного слота и продление окна прогрева.
        Handler h = handler;
        if (h != null) {
            int what = (step ? MSG_REARM_STEP_BASE : MSG_REARM_PRESS_BASE) + slot;
            h.removeMessages(what);
            h.sendMessageDelayed(h.obtainMessage(what), step ? rearmStepMs : rearmPressMs);
        }
        startWarm();
    }

    private boolean tryPlay(AudioTrack track, float volume) {
        try {
            track.setStereoVolume(volume, volume);
            track.play();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long rearmDelayMs(long clickMs, int latencyMs) {
        return Math.max(MIN_REARM_MS, clickMs + 2L * latencyMs + REARM_EXTRA_MS);
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
    private void logLatencyOnce() {
        if (latencyLogged) return;
        latencyLogged = true;
        Log.i(TAG, "variant=" + variant.id() + " rate=" + sampleRate + " step=" + stepMs + "ms press=" + pressMs
                + "ms hal output latency=" + outputLatencyMs() + "ms rearm=" + rearmStepMs + "/" + rearmPressMs + "ms");
    }

    // AudioSystem.getOutputLatency скрыт в SDK, поэтому через рефлексию. Это та же задержка HAL, по
    // которой AudioFlinger решает, когда выкинуть отыгравшую дорожку из микшера.
    private static int outputLatencyMs() {
        try {
            Object ms = Class.forName("android.media.AudioSystem")
                    .getMethod("getOutputLatency", int.class).invoke(null, AudioManager.STREAM_MUSIC);
            if (ms instanceof Integer && (Integer) ms > 0 && (Integer) ms < 1000) return (Integer) ms;
        } catch (Exception e) {
            // скрытого метода нет: берём запасную оценку
        }
        return FALLBACK_LATENCY_MS;
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
                builtFrames = data.length;
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
        for (int i = 0; i < STEP_POOL_SIZE; i++) {
            if (handler != null) handler.removeMessages(MSG_REARM_STEP_BASE + i);
            releaseQuietly(stepTracks[i]);
            stepTracks[i] = null;
            armedStep[i] = false;
        }
        for (int i = 0; i < PRESS_POOL_SIZE; i++) {
            if (handler != null) handler.removeMessages(MSG_REARM_PRESS_BASE + i);
            releaseQuietly(pressTracks[i]);
            pressTracks[i] = null;
            armedPress[i] = false;
        }
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
