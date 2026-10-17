package com.themoon.y1.reader;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Мелкие чистые расчёты экрана читалки: подпись «прошлый раз», подпись закладки, шаги перемотки.
 * Вынесены из MainActivity, чтобы проверяться JUnit на JVM без Android.
 */
public final class ReaderFormat {

    /** Размеры слова из меню «Размер слова» (px макета 480x360): малый, средний, крупный. */
    public static final int[] WORD_SIZES_PX = {44, 52, 60};
    public static final int DEFAULT_WORD_SIZE_INDEX = 1;

    /**
     * Повороты колеса чаще этого интервала на паузе считаются быстрыми и прыгают по предложениям:
     * по одному слову книгу в 30 тысяч слов не перекрутить.
     */
    public static final long FAST_TURN_GAP_MS = 80;

    private static final long SEEK_STEP_PERIOD_MS = 150;
    private static final long SEEK_FAST_AFTER_MS = 2000;
    private static final int SEEK_SLOW_WORDS = 10;
    private static final int SEEK_FAST_WORDS = 30;

    private ReaderFormat() {
    }

    /**
     * «сегодня 02:10», «вчера 23:59», «05.03 18:07» (в этом году) или «30.12.2025 08:30».
     * Слова «сегодня» и «вчера» приходят из переводов приложения.
     */
    public static String lastTime(long nowMs, long thenMs, TimeZone tz, String today, String yesterday) {
        Calendar now = Calendar.getInstance(tz);
        now.setTimeInMillis(nowMs);
        Calendar then = Calendar.getInstance(tz);
        then.setTimeInMillis(thenMs);
        StringBuilder sb = new StringBuilder(24);
        long dayDiff = dayNumber(nowMs, tz) - dayNumber(thenMs, tz);
        if (dayDiff <= 0) {
            // Отрицательная разница: часы перевели назад, «завтра» человеку ничего не скажет.
            sb.append(today);
        } else if (dayDiff == 1) {
            sb.append(yesterday);
        } else {
            two(sb, then.get(Calendar.DAY_OF_MONTH));
            sb.append('.');
            two(sb, then.get(Calendar.MONTH) + 1);
            if (then.get(Calendar.YEAR) != now.get(Calendar.YEAR)) sb.append('.').append(then.get(Calendar.YEAR));
        }
        sb.append(' ');
        two(sb, then.get(Calendar.HOUR_OF_DAY));
        sb.append(':');
        two(sb, then.get(Calendar.MINUTE));
        return sb.toString();
    }

    /** Номер суток по местному времени: разность двух номеров считает календарные сутки, а не 24 часа. */
    private static long dayNumber(long ms, TimeZone tz) {
        long local = ms + tz.getOffset(ms);
        return local >= 0 ? local / 86400000L : -((-local + 86399999L) / 86400000L);
    }

    private static void two(StringBuilder sb, int v) {
        if (v < 10) sb.append('0');
        sb.append(v);
    }

    /** Минуты до конца для подписи «ещё 2 ч 14 мин», вверх; 0 (подпись пустая), когда читать нечего. */
    public static int minutesLeft(long remainingMs) {
        if (remainingMs <= 0) return 0;
        return (int) ((remainingMs + 59999) / 60000);
    }

    /**
     * Кусок текста для строки закладки: пробелы и неразрывные пробелы схлопнуты, длинный текст
     * обрезан по слову с «...» так, что итог не длиннее max.
     */
    public static String fragment(String text, int max) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(text.length(), max + 4));
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00A0') {
                space = sb.length() > 0;
            } else {
                if (space) sb.append(' ');
                space = false;
                sb.append(c);
            }
        }
        if (sb.length() <= max) return sb.toString();
        int room = Math.max(1, max - 3);
        int cut = sb.lastIndexOf(" ", room);
        if (cut <= 0) cut = room;
        return sb.substring(0, cut) + "...";
    }

    public static boolean isFastTurn(long gapMs) {
        return gapMs <= FAST_TURN_GAP_MS;
    }

    /** Шаг быстрой перемотки удержанием NEXT/PREV: 10 слов, после двух секунд удержания 30. */
    public static int seekWords(long heldMs) {
        return heldMs >= SEEK_FAST_AFTER_MS ? SEEK_FAST_WORDS : SEEK_SLOW_WORDS;
    }

    public static boolean isSeekStepDue(long nowMs, long lastStepMs) {
        return nowMs - lastStepMs >= SEEK_STEP_PERIOD_MS;
    }

    /** Индекс размера по сохранённым пикселям; незнакомое значение даёт средний. */
    public static int wordSizeIndex(int px) {
        for (int i = 0; i < WORD_SIZES_PX.length; i++) {
            if (WORD_SIZES_PX[i] == px) return i;
        }
        return DEFAULT_WORD_SIZE_INDEX;
    }
}
