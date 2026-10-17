package com.themoon.y1.reader;

/**
 * Автомат чтения: какое слово показано и когда его менять.
 *
 * Время подаётся снаружи (nowMs), своих потоков и Handler нет: так весь темп проверяется JUnit на JVM, а
 * приложение держит один Handler и ставит его на {@link #nextDueMs()}.
 */
public final class ReaderEngine {

    public static final int MIN_WPM = 100;
    public static final int MAX_WPM = 1000;
    public static final int WPM_STEP = 50;
    /** Прыжок больше этого числа слов оставляет за собой точку возврата. */
    public static final int RETURN_POINT_JUMP = 150;
    /** После лага (например, пробуждения процессора) догоняем не больше стольких слов за вызов. */
    private static final int MAX_CATCH_UP = 3;

    /** Решает, что слово не помещается даже в минимальном масштабе и показывается двумя частями. */
    public interface SplitOracle {
        boolean needsSplit(BookIndex index, int word);
    }

    private final BookIndex index;
    private final int last;
    private SplitOracle oracle;
    private int wpm;
    private int word;
    private int part;
    private boolean playing;
    private long due;
    /** Последнее время, которое нам сообщили: от него перепланируется смена слова после перемотки. */
    private long clock;
    private boolean hasReturn;
    private int returnWord;

    public ReaderEngine(BookIndex index, int startWord, int wpm) {
        this.index = index;
        this.last = Math.max(0, index.wordCount() - 1);
        this.word = Math.max(0, Math.min(startWord, last));
        this.wpm = normalizeWpm(wpm);
    }

    private static int normalizeWpm(int wpm) {
        int v = (wpm + WPM_STEP / 2) / WPM_STEP * WPM_STEP;
        return Math.max(MIN_WPM, Math.min(MAX_WPM, v));
    }

    public void setSplitOracle(SplitOracle oracle) {
        this.oracle = oracle;
    }

    // ------------------------------------------------------------------ ход чтения

    public void play(long nowMs) {
        if (index.wordCount() == 0 || word >= last) return;
        playing = true;
        clock = nowMs;
        due = nowMs + currentDelay();
    }

    public void pause() {
        playing = false;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Задержка показа текущего слова или его части: у разрезанного слова обе части по базовой, итог вдвое. */
    private long currentDelay() {
        return WordTimer.delayMs(index, word, wpm);
    }

    /** @return true, если показанное слово или его часть сменились */
    public boolean tick(long nowMs) {
        if (!playing) return false;
        clock = nowMs;
        boolean changed = false;
        int steps = 0;
        while (playing && nowMs >= due && steps < MAX_CATCH_UP) {
            advance();
            changed = true;
            steps++;
            if (playing) due += currentDelay();
        }
        // Долг сверх трёх слов прощается: иначе после долгого лага слова мелькали бы подряд.
        if (playing && nowMs >= due) due = nowMs + currentDelay();
        return changed;
    }

    private void advance() {
        if (part == 0 && oracle != null && oracle.needsSplit(index, word)) {
            part = 1;
            return;
        }
        part = 0;
        if (word < last) word++;
        if (word >= last) playing = false;
    }

    /** Абсолютное время следующей смены; пока чтение стоит, Long.MAX_VALUE. */
    public long nextDueMs() {
        return playing ? due : Long.MAX_VALUE;
    }

    // ------------------------------------------------------------------ состояние

    public int word() {
        return word;
    }

    /** 0 или 1: какая часть разрезанного слова показана. */
    public int part() {
        return part;
    }

    public int wordCount() {
        return index.wordCount();
    }

    public int percent() {
        return index.percentOf(word);
    }

    public boolean isFinished() {
        return index.isEndReached(word);
    }

    /** Время до конца книги с учётом пауз на знаках: среднее по книге, а не по оставшемуся хвосту. */
    public long remainingMs() {
        int left = Math.max(0, last - word);
        float factor = index.avgDelayFactor < 1f ? 1f : index.avgDelayFactor;
        return (long) (left * 60000.0 / wpm * factor);
    }

    public int wpm() {
        return wpm;
    }

    public void setWpm(int wpm) {
        this.wpm = normalizeWpm(wpm);
    }

    public void stepWpm(int dir) {
        setWpm(wpm + dir * WPM_STEP);
    }

    // ------------------------------------------------------------------ перемещение

    private void moveTo(int target, boolean leaveReturnPoint) {
        int t = Math.max(0, Math.min(target, last));
        if (leaveReturnPoint && Math.abs(t - word) > RETURN_POINT_JUMP) {
            hasReturn = true;
            returnWord = word;
        }
        word = t;
        part = 0;
        if (playing) {
            if (word >= last) {
                playing = false;
            } else {
                due = clock + currentDelay();
            }
        }
    }

    /** Колесо на паузе: небольшие шаги, точку возврата не оставляют. */
    public void stepWords(int n) {
        moveTo(word + n, false);
    }

    public void stepSentence(int dir) {
        moveTo(dir >= 0 ? index.nextSentence(word) : index.prevSentence(word), false);
    }

    public void stepParagraph(int dir) {
        moveTo(dir >= 0 ? index.nextParagraph(word) : index.prevParagraph(word), false);
    }

    public void seekRelativeWords(int n) {
        moveTo(word + n, true);
    }

    public void jumpToPercent(int p) {
        int pc = Math.max(0, Math.min(p, 100));
        moveTo((int) ((long) pc * last / 100), true);
    }

    public void jumpToChapter(int c) {
        int chapters = index.chapterFirstWord.length;
        if (chapters == 0) return;
        moveTo(index.chapterFirstWord[Math.max(0, Math.min(c, chapters - 1))], true);
    }

    public void jumpToWord(int w) {
        moveTo(w, true);
    }

    // ------------------------------------------------------------------ точка возврата

    public boolean hasReturnPoint() {
        return hasReturn;
    }

    public int returnPointWord() {
        return returnWord;
    }

    public int returnPointPercent() {
        return index.percentOf(returnWord);
    }

    /** Восстанавливает точку возврата, сохранённую в positions.json. */
    public void setReturnPoint(int w) {
        hasReturn = true;
        returnWord = Math.max(0, Math.min(w, last));
    }

    public void clearReturnPoint() {
        hasReturn = false;
    }

    /** Обмен: человек уходит на точку, а место, где он был, становится новой точкой. */
    public void returnToPoint() {
        if (!hasReturn) return;
        int target = returnWord;
        returnWord = word;
        moveTo(target, false);
    }
}
