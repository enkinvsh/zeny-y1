package com.themoon.y1.managers;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Движок отрывков викторины: один плеер на трек, подготовленный заранее и заранее перемотанный на точку
 * старта, поэтому секундный кусок начинается сразу, а не через полсекунды разбора файла. Все ступени
 * играют с одной и той же точки; «стоп» по длине кусочка проверяется по позиции плеера, а не по таймеру:
 * у Opus перемотка в ExoPlayer занимает разное время, и таймер от нажатия съедал бы начало секунды.
 *
 * Вызывать только из главного потока: SimpleExoPlayer привязан к looper, в котором создан.
 */
public final class QuizClipPlayer {

    public interface Listener {
        /** Плеер готов; длительность нужна, чтобы выбрать точку старта. */
        void onPrepared(long durationMs);

        /** Кусок доиграл до своей границы (или до конца файла). */
        void onClipEnded();

        /** Файл не играется: вызывающий решает, что делать с раундом. */
        void onError();
    }

    private static final String TAG = "QuizClipPlayer";
    // 16 мс ≈ кадр: граница секундного куска не уплывает дальше, чем на кадр.
    private static final long POLL_MS = 16;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SimpleExoPlayer exo;
    private MediaPlayer media;
    // Один экземпляр на всё время игры: прежний плеер к моменту следующего prepare() уже освобождён.
    private ZenyAudioProcessor dsp;
    private boolean dspNatural = true;
    private boolean dspSoundCheck = true;
    private Listener listener;
    // Каждая подготовка получает свой номер: колбэки уже отпущенного плеера сравнивают его и молчат.
    private int generation;
    private boolean prepared;
    private long durationMs;
    private long startMs;
    private int clipLengthMs;
    private int pendingClipMs = -1;
    private boolean playing;
    private boolean mediaSeeking;

    public QuizClipPlayer(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Opus системный MediaPlayer Android 4.2 не умеет вовсе, FLAC/OGG играет ненадёжно. */
    public static boolean needsExo(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".opus") || name.endsWith(".flac") || name.endsWith(".ogg");
    }

    public void prepare(File file, Listener l) {
        release();
        listener = l;
        final int gen = ++generation;
        try {
            if (needsExo(file)) prepareExo(file, gen);
            else prepareMedia(file, gen);
        } catch (Exception e) {
            Log.w(TAG, "prepare failed: " + file, e);
            failLater(gen);
        }
    }

    /**
     * Настройки звука основного плеера: отрывок идёт через ту же обработку, иначе он звучит иначе, чем музыка.
     * Без неё Opus декодируется в 16 бит с жёстким срезом пиков и без выравнивания громкости: при включённом
     * Sound Check трек с тегом R128 -10 дБ играл бы на ~10 дБ громче обычного прослушивания и хрипел бы.
     */
    public void setDspSettings(boolean zenyNatural, boolean soundCheck) {
        dspNatural = zenyNatural;
        dspSoundCheck = soundCheck;
    }

    private static Float trackGainOf(File file) {
        AudioPlayerManager am = AudioPlayerManager.getInstance();
        String name = file.getName().toLowerCase(Locale.US);
        try {
            if (name.endsWith(".opus")) return AudioPlayerManager.trackGainOf(am.extractOpusMetadata(file, false));
            if (name.endsWith(".flac")) return AudioPlayerManager.trackGainOf(am.extractFlacMetadata(file, false));
            if (name.endsWith(".ogg")) return AudioPlayerManager.trackGainOf(am.extractOggVorbisMetadata(file, false));
        } catch (RuntimeException e) {
            Log.w(TAG, "tag gain read failed: " + file, e);
        }
        return null;
    }

    private void prepareExo(File file, final int gen) {
        if (dsp == null) dsp = new ZenyAudioProcessor();
        dsp.setZenyNaturalEnabled(dspNatural);
        dsp.setSoundCheckEnabled(dspSoundCheck);
        dsp.setTrackGainDb(trackGainOf(file));
        final ZenyAudioProcessor clipDsp = dsp;
        // Та же цепочка, что у основного плеера (AudioPlayerManager.initPlayer): float-выход Opus и ZenyAudioProcessor
        // на месте штатного ресемплера, но свой экземпляр, чтобы состояние фильтров не делилось с музыкой.
        DefaultRenderersFactory factory = new DefaultRenderersFactory(appContext) {
            @Override
            protected void buildAudioRenderers(Context context, int extensionRendererMode,
                    MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback, AudioSink audioSink,
                    Handler eventHandler, AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
                DefaultAudioSink sink = new DefaultAudioSink(new AudioCapabilities(new int[] {2}, 2),
                        new AudioProcessor[0]);
                try {
                    Field f = DefaultAudioSink.class.getDeclaredField("toIntPcmAvailableAudioProcessors");
                    f.setAccessible(true);
                    AudioProcessor[] chain = (AudioProcessor[]) f.get(sink);
                    if (chain != null && chain.length > 0) chain[0] = clipDsp;
                } catch (Throwable e) {
                    Log.w(TAG, "dsp chain hook failed", e);
                }
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                        sink, eventHandler, eventListener, out);
                AudioPlayerManager.installFloatOpusRenderer(eventHandler, eventListener, sink, out);
            }
        };
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        final SimpleExoPlayer player = new SimpleExoPlayer.Builder(appContext, factory).build();
        exo = player;
        player.addListener(new Player.EventListener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (gen != generation) return;
                if (state == Player.STATE_READY && !prepared) {
                    long d = player.getDuration();
                    onReady(d > 0 ? d : 0);
                } else if (state == Player.STATE_ENDED && playing) {
                    finishClip();
                }
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                if (gen != generation) return;
                Log.w(TAG, "exo error", error);
                fail();
            }
        });
        player.setPlayWhenReady(false);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
    }

    private void prepareMedia(File file, final int gen) throws Exception {
        final MediaPlayer mp = new MediaPlayer();
        media = mp;
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setDataSource(file.getAbsolutePath());
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer p) {
                if (gen != generation) return;
                int d = p.getDuration();
                onReady(d > 0 ? d : 0);
            }
        });
        mp.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
            @Override
            public void onSeekComplete(MediaPlayer p) {
                if (gen != generation) return;
                mediaSeeking = false;
                if (playing && !p.isPlaying()) p.start();
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer p) {
                if (gen != generation) return;
                if (playing) finishClip();
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer p, int what, int extra) {
                if (gen != generation) return true;
                Log.w(TAG, "media error " + what + "/" + extra);
                fail();
                return true;
            }
        });
        mp.prepareAsync();
    }

    private void onReady(long d) {
        prepared = true;
        durationMs = d;
        if (listener != null) listener.onPrepared(d);
        if (pendingClipMs > 0) {
            int len = pendingClipMs;
            pendingClipMs = -1;
            playClip(len);
        }
    }

    /** Точку старта выбирают по длительности из onPrepared; сразу перематываем, чтобы первый кусок не ждал. */
    public void setStartMs(long ms) {
        startMs = Math.max(0, ms);
        if (prepared && !playing) seekToStart();
    }

    public long getStartMs() {
        return startMs;
    }

    /** Сыграть кусок длиной lengthMs от точки старта; до готовности плеера запрос откладывается. */
    public void playClip(int lengthMs) {
        clipLengthMs = lengthMs;
        if (!prepared) {
            pendingClipMs = lengthMs;
            return;
        }
        playing = true;
        seekToStart();
        if (exo != null) {
            exo.setPlayWhenReady(true);
        } else if (media != null && !mediaSeeking) {
            media.start();
        }
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    public void replay() {
        if (clipLengthMs > 0) playClip(clipLengthMs);
    }

    /** Экран ответа: трек играет дальше от точки старта как награда, но не дольше capMs. */
    public void continuePlaying(int capMs) {
        playClip(capMs);
    }

    private void seekToStart() {
        long target = Math.min(startMs, Math.max(0, durationMs - 1));
        if (exo != null) {
            exo.seekTo(target);
        } else if (media != null) {
            try {
                mediaSeeking = true;
                media.seekTo((int) target);
            } catch (IllegalStateException e) {
                mediaSeeking = false;
                Log.w(TAG, "seek in wrong state", e);
            }
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;
            long end = startMs + clipLengthMs;
            if (durationMs > 0) end = Math.min(end, durationMs);
            if (!mediaSeeking && currentPosition() >= end) {
                finishClip();
                return;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private long currentPosition() {
        try {
            if (exo != null) return exo.getCurrentPosition();
            if (media != null) return media.getCurrentPosition();
        } catch (IllegalStateException e) {
            Log.w(TAG, "position in wrong state", e);
        }
        return startMs;
    }

    private void finishClip() {
        playing = false;
        handler.removeCallbacks(poll);
        pauseOutput();
        if (listener != null) listener.onClipEnded();
    }

    private void pauseOutput() {
        if (exo != null) {
            exo.setPlayWhenReady(false);
        } else if (media != null) {
            try {
                if (media.isPlaying()) media.pause();
            } catch (IllegalStateException e) {
                Log.w(TAG, "pause in wrong state", e);
            }
        }
    }

    public void stop() {
        pendingClipMs = -1;
        if (!playing) return;
        playing = false;
        handler.removeCallbacks(poll);
        pauseOutput();
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPrepared() {
        return prepared;
    }

    public int getClipLengthMs() {
        return clipLengthMs;
    }

    /** Позиция внутри текущего куска для бегунка: 0..clipLength. Пока идёт перемотка, держит 0. */
    public long getPositionInClipMs() {
        if (!prepared) return 0;
        if (mediaSeeking) return 0;
        long p = currentPosition() - startMs;
        if (p < 0) return 0;
        return Math.min(p, clipLengthMs);
    }

    private void failLater(final int gen) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) fail();
            }
        });
    }

    private void fail() {
        Listener l = listener;
        release();
        if (l != null) l.onError();
    }

    public void release() {
        generation++;
        handler.removeCallbacks(poll);
        playing = false;
        prepared = false;
        mediaSeeking = false;
        pendingClipMs = -1;
        durationMs = 0;
        startMs = 0;
        clipLengthMs = 0;
        listener = null;
        if (exo != null) {
            try {
                exo.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "exo release", e);
            }
            exo = null;
        }
        if (media != null) {
            try {
                media.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "media release", e);
            }
            media = null;
        }
    }
}
