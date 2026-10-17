package com.themoon.y1.managers;

/**
 * Ручной сдвиг синхронного текста песни. Знак выбран так, чтобы совпадал с надписью на экране:
 * «+» значит текст появляется позже (к позиции плеера прибавляется минус сдвиг), «-» значит раньше.
 * Поэтому, если строка загорается раньше голоса, человек крутит колесо вправо, к плюсу.
 */
public final class LyricsShift {
    public static final int MIN_MS = -10000;
    public static final int MAX_MS = 10000;
    /** Один щелчок колеса: меньше 0,1 с на слух не отличить, а длинный сдвиг иначе набирать слишком долго. */
    public static final int STEP_MS = 100;

    private LyricsShift() {
    }

    public static int clamp(int ms) {
        return Math.max(MIN_MS, Math.min(MAX_MS, ms));
    }

    /** dir больше нуля: +0,1 с, иначе -0,1 с; результат остаётся в диапазоне. */
    public static int step(int current, int dir) {
        return clamp(current + (dir > 0 ? STEP_MS : -STEP_MS));
    }

    /**
     * Позиция, по которой выбирается строка. Положительный сдвиг откладывает текст,
     * то есть строка выбирается по более ранней позиции плеера.
     */
    public static long effectivePosition(long playerPosMs, int shiftMs) {
        return playerPosMs - shiftMs;
    }

    /** "+0,3 с", "-1,5 с", "0 с": нулевое значение без знака и без дроби. */
    public static String format(int ms, char decimalSep, String unit) {
        int v = clamp(ms);
        if (v == 0) return "0 " + unit;
        int abs = Math.abs(v);
        StringBuilder sb = new StringBuilder();
        sb.append(v > 0 ? '+' : '-');
        sb.append(abs / 1000);
        int tenths = (abs % 1000) / 100;
        if (tenths != 0) sb.append(decimalSep).append(tenths);
        sb.append(' ').append(unit);
        return sb.toString();
    }
}
