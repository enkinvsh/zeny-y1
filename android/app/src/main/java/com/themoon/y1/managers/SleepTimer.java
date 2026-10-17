package com.themoon.y1.managers;

/**
 * Состояние таймера сна: выбранный вариант и дедлайн. Время передаётся параметром
 * (в проде это SystemClock.elapsedRealtime(): он идёт и пока CPU спит, в отличие от
 * uptimeMillis), поэтому класс детерминирован и проверяется юнит-тестами без Android.
 * Последние {@link #FADE_MS} перед дедлайном громкость плавно уходит в ноль, чтобы музыка
 * не обрывалась на полной громкости.
 */
public final class SleepTimer {
    public static final int OFF = 0;
    public static final int MIN_15 = 1;
    public static final int MIN_30 = 2;
    public static final int MIN_45 = 3;
    public static final int MIN_60 = 4;
    public static final int MIN_90 = 5;
    public static final int END_OF_TRACK = 6;

    public static final long FADE_MS = 30_000L;
    private static final long MINUTE_MS = 60_000L;
    private static final int[] MINUTES = {0, 15, 30, 45, 60, 90, 0};

    private int option = OFF;
    private long deadlineMs;

    /** Следующий вариант по кругу; для минутных вариантов дедлайн отсчитывается от nowMs. */
    public void cycle(long nowMs) {
        option = (option + 1) % MINUTES.length;
        deadlineMs = MINUTES[option] > 0 ? nowMs + MINUTES[option] * MINUTE_MS : 0;
    }

    public void cancel() {
        option = OFF;
        deadlineMs = 0;
    }

    public int option() {
        return option;
    }

    public boolean isActive() {
        return option != OFF;
    }

    /** Минуты выбранного варианта для подписи строки меню; 0 у OFF и END_OF_TRACK. */
    public int labelMinutes() {
        return MINUTES[option];
    }

    private boolean isTimed() {
        return MINUTES[option] > 0;
    }

    /** Сколько осталось до дедлайна; 0 у вариантов без дедлайна и после его прохождения. */
    public long remainingMs(long nowMs) {
        if (!isTimed()) return 0;
        long left = deadlineMs - nowMs;
        return left > 0 ? left : 0;
    }

    /** Остаток в минутах с округлением вверх: «ещё 1 мин» держится до самого конца. */
    public int remainingMinutes(long nowMs) {
        return (int) ((remainingMs(nowMs) + MINUTE_MS - 1) / MINUTE_MS);
    }

    /** Множитель громкости: 1.0 вне последних 30 с, дальше линейно до 0.0 в момент дедлайна. */
    public float fadeFactor(long nowMs) {
        if (!isTimed()) return 1.0f;
        long left = remainingMs(nowMs);
        if (left >= FADE_MS) return 1.0f;
        return (float) left / FADE_MS;
    }

    public boolean isExpired(long nowMs) {
        return isTimed() && nowMs >= deadlineMs;
    }
}
