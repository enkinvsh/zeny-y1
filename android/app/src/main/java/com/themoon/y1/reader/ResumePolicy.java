package com.themoon.y1.reader;

/**
 * Откат при возобновлении: чем дольше читатель не был в книге, тем больше контекста ему нужно, чтобы
 * вспомнить, на чём остановился. Пороги утверждены владельцем: до 2 минут три слова, до 6 часов начало
 * предложения, дальше начало абзаца.
 */
public final class ResumePolicy {

    static final long SHORT_MS = 2 * 60 * 1000L;
    static final long LONG_MS = 6 * 60 * 60 * 1000L;
    static final int SHORT_ROLLBACK_WORDS = 3;

    private ResumePolicy() {
    }

    /** @param ageMs сколько прошло с последней записи позиции; отрицательное (часы переведены) считается нулём */
    public static int rollback(BookIndex index, int word, long ageMs) {
        int n = index.wordCount();
        if (n == 0) return 0;
        int w = Math.max(0, Math.min(word, n - 1));
        if (ageMs < SHORT_MS) return Math.max(0, w - SHORT_ROLLBACK_WORDS);
        if (ageMs < LONG_MS) return index.sentenceStart(w);
        return index.paragraphStart(w);
    }
}
