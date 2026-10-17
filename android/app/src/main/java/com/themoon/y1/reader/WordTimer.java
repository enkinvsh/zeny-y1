package com.themoon.y1.reader;

/**
 * Темп чтения и раскладка слова: задержки, буква-якорь, подгонка длинных слов по ширине.
 * Всё считается из чисел и символов, без вызовов Android, поэтому проверяется JUnit на JVM.
 */
public final class WordTimer {

    /** Пауза в начале главы добавляется поверх задержки слова, а не множится: это время «вдохнуть». */
    public static final long CHAPTER_PAUSE_MS = 1500;
    /** Ниже этого масштаба шрифт читать трудно, дальше слово режется на две части. */
    public static final float MIN_SCALE = 0.6f;

    private WordTimer() {
    }

    /** Число букв и цифр слова (без знаков и тире): именно оно определяет, насколько слово «тяжёлое». */
    public static int letterCount(char[] t, int start, int end) {
        int n = 0;
        for (int i = start; i < end; i++) {
            if (Character.isLetterOrDigit(t[i])) n++;
        }
        return n;
    }

    /**
     * Множитель задержки. Паузы на знаках и длина слова не складываются, берётся наибольший:
     * после точки глаз и так отдыхает, длинное слово там ещё затягивать незачем.
     */
    public static float factor(int length, int flags) {
        float f = length >= 13 ? 1.6f : length >= 9 ? 1.3f : 1.0f;
        float pause = (flags & BookIndex.F_PARA_END) != 0 ? 3.0f
                : (flags & BookIndex.F_SENT_END) != 0 ? 2.5f
                : (flags & BookIndex.F_PAUSE_MID) != 0 ? 1.8f : 1.0f;
        return pause > f ? pause : f;
    }

    public static long delayMs(BookIndex b, int word, int wpm) {
        int start = b.wordStart[word];
        int letters = letterCount(b.text, start, start + b.wordLength(word));
        int flags = b.flags[word];
        long ms = Math.round(60000.0 / Math.max(1, wpm) * factor(letters, flags));
        if ((flags & BookIndex.F_CHAP_START) != 0) ms += CHAPTER_PAUSE_MS;
        return ms;
    }

    /**
     * Индекс буквы-якоря относительно start. Ведущие кавычки, скобки и тире (в том числе приклеенное
     * через NBSP) пропускаются: якорь ставится по буквам слова, а не по знакам перед ним.
     */
    public static int anchorIndex(char[] t, int start, int end) {
        int first = start;
        while (first < end && !Character.isLetterOrDigit(t[first])) first++;
        if (first >= end) return 0;
        int last = end - 1;
        while (last > first && !Character.isLetterOrDigit(t[last])) last--;
        int len = last - first + 1;
        int orp = len <= 1 ? 0 : len <= 5 ? 1 : len <= 9 ? 2 : len <= 13 ? 3 : 4;
        return Math.min(first - start + orp, end - start - 1);
    }

    /**
     * Масштаб шрифта, при котором обе половины слова влезают в свои стороны от якоря (якорь левее
     * центра, поэтому справа места больше). Не ниже minScale; ширины считает вызывающий.
     */
    public static float fitScale(float leftWidth, float rightWidth, float maxLeft, float maxRight, float minScale) {
        float s = 1f;
        if (leftWidth > maxLeft && leftWidth > 0) s = Math.min(s, maxLeft / leftWidth);
        if (rightWidth > maxRight && rightWidth > 0) s = Math.min(s, maxRight / rightWidth);
        return Math.max(s, minScale);
    }

    /**
     * Индекс разреза слова на две показываемые части: первая [start, cut), вторая [cut, end).
     * Дефис между буквами предпочтительнее (ближайший к середине), иначе режем пополам.
     */
    public static int splitPoint(char[] t, int start, int end) {
        int len = end - start;
        if (len < 2) return end;
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        float mid = start + len / 2f;
        for (int i = start + 1; i < end - 1; i++) {
            if (isHyphen(t[i]) && Character.isLetterOrDigit(t[i - 1]) && Character.isLetterOrDigit(t[i + 1])) {
                float dist = Math.abs(i + 0.5f - mid);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
        }
        return best >= 0 ? best + 1 : start + (len + 1) / 2;
    }

    private static boolean isHyphen(char c) {
        return c == '-' || c == '\u2010' || c == '\u2011' || c == '\u2013' || c == '\u2014';
    }
}
