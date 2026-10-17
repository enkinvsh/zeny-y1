package com.themoon.y1.managers;

/**
 * Разгон перемотки при удержании NEXT/PREV: первые ~1.2 с шаг 5 с (точно попасть в начало
 * куплета), до 3 с шаг 10 с, дальше 20 с (за 10 секунд удержания проходится больше 6 минут).
 * Шаг делается не чаще раза в {@link #TICK_MS}: автоповтор клавиш идёт примерно каждые 50 мс,
 * и без ограничения перемотка зависела бы от частоты автоповтора конкретного устройства.
 * Чистая Java без Android, чтобы расписание проверялось юнит-тестами.
 */
public final class SeekRamp {
    public static final long TICK_MS = 250;

    private static final long SLOW_UNTIL_MS = 1200;
    private static final long MEDIUM_UNTIL_MS = 3000;
    private static final int SLOW_STEP_MS = 5000;
    private static final int MEDIUM_STEP_MS = 10000;
    private static final int FAST_STEP_MS = 20000;

    private SeekRamp() {}

    /** Шаг перемотки в мс для того, сколько кнопка уже удерживается. */
    public static int stepForHoldMs(long holdMs) {
        if (holdMs < SLOW_UNTIL_MS) return SLOW_STEP_MS;
        if (holdMs < MEDIUM_UNTIL_MS) return MEDIUM_STEP_MS;
        return FAST_STEP_MS;
    }

    /** Пора ли делать следующий шаг: с прошлого шага прошёл хотя бы один тик. */
    public static boolean isStepDue(long nowMs, long lastStepMs) {
        return nowMs - lastStepMs >= TICK_MS;
    }

    /**
     * Новая цель перемотки, зажатая в границы трека.
     *
     * @param durationMs длина трека; 0 или меньше, если неизвестна (тогда вперёд не ограничиваем)
     */
    public static int nextTarget(int targetMs, boolean forward, long holdMs, int durationMs) {
        long step = stepForHoldMs(holdMs);
        long next = (long) targetMs + (forward ? step : -step);
        if (next < 0) next = 0;
        if (durationMs > 0 && next > durationMs) next = durationMs;
        return (int) next;
    }
}
