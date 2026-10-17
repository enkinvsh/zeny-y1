package com.themoon.y1.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WordTimerTest {

    private static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('к');
        return sb.toString();
    }

    private static int anchor(String w) {
        char[] t = w.toCharArray();
        return WordTimer.anchorIndex(t, 0, t.length);
    }

    /** Задержка второго слова книги: первое слово всегда открывает неявную главу и несло бы паузу главы. */
    private static long delay(String word, boolean sentenceEnd, boolean paraEnd, boolean chapter, int wpm) {
        BookBuilder b = new BookBuilder();
        b.text("я ");
        if (chapter) b.startChapter("Гл");
        b.text(word);
        if (sentenceEnd) b.forceSentenceEnd();
        if (paraEnd) b.endParagraph();
        return WordTimer.delayMs(b.build(), 1, wpm);
    }

    @Test
    public void anchorLetterTableMatchesDesign() {
        int[] expected = {0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4};
        for (int len = 1; len <= expected.length; len++) {
            assertEquals("длина " + len, expected[len - 1], anchor(letters(len)));
        }
    }

    @Test
    public void leadingDashAndQuotesDoNotShiftAnchor() {
        String glued = "\u2014\u00A0\u041F\u0440\u0438\u0432\u0435\u0442";
        assertEquals(2 + 2, anchor(glued));
        assertEquals(1 + 2, anchor("\u00AB" + letters(6)));
        assertEquals(2, anchor(letters(6) + "\u2026"));
    }

    @Test
    public void anchorOfPunctuationOnlyTokenIsFirstChar() {
        assertEquals(0, anchor("\u2014"));
        assertEquals(0, anchor("..."));
    }

    @Test
    public void anchorIndexIsRelativeToStart() {
        char[] t = ("xx " + letters(6)).toCharArray();
        assertEquals(2, WordTimer.anchorIndex(t, 3, t.length));
    }

    @Test
    public void factorTableFollowsDesign() {
        assertEquals(1.0f, WordTimer.factor(8, 0), 1e-6);
        assertEquals(1.3f, WordTimer.factor(9, 0), 1e-6);
        assertEquals(1.3f, WordTimer.factor(12, 0), 1e-6);
        assertEquals(1.6f, WordTimer.factor(13, 0), 1e-6);
        assertEquals(1.8f, WordTimer.factor(3, BookIndex.F_PAUSE_MID), 1e-6);
        assertEquals(2.5f, WordTimer.factor(3, BookIndex.F_SENT_END), 1e-6);
        assertEquals(3.0f, WordTimer.factor(3, BookIndex.F_PARA_END | BookIndex.F_SENT_END), 1e-6);
    }

    @Test
    public void factorsDoNotAddUp() {
        assertEquals(2.5f, WordTimer.factor(13, BookIndex.F_SENT_END), 1e-6);
        assertEquals(1.8f, WordTimer.factor(9, BookIndex.F_PAUSE_MID), 1e-6);
    }

    @Test
    public void delaysAt300Wpm() {
        assertEquals(200, delay(letters(5), false, false, false, 300));
        assertEquals(320, delay(letters(13), false, false, false, 300));
        assertEquals(260, delay(letters(9), false, false, false, 300));
        assertEquals(500, delay(letters(5) + ".", true, false, false, 300));
        assertEquals(600, delay(letters(5) + ".", true, true, false, 300));
        assertEquals(360, delay(letters(5) + ",", false, false, false, 300));
    }

    @Test
    public void chapterStartAddsFixedPause() {
        assertEquals(200 + 1500, delay(letters(5), false, false, true, 300));
    }

    @Test
    public void punctuationDoesNotCountAsLetters() {
        assertEquals(200, delay("\u00AB" + letters(8) + "\u00BB", false, false, false, 300));
    }

    @Test
    public void wpmIsGuardedAgainstZero() {
        assertTrue(delay(letters(5), false, false, false, 0) > 0);
    }

    @Test
    public void fitScaleKeepsFittingWordsAtFullSize() {
        assertEquals(1.0f, WordTimer.fitScale(100, 120, 180, 260, 0.6f), 1e-6);
    }

    @Test
    public void fitScaleShrinksBySideThatOverflowsMore() {
        assertEquals(0.9f, WordTimer.fitScale(200, 100, 180, 260, 0.6f), 1e-6);
        assertEquals(0.8f, WordTimer.fitScale(100, 325, 180, 260, 0.6f), 1e-6);
    }

    @Test
    public void fitScaleNeverGoesBelowMinimum() {
        assertEquals(0.6f, WordTimer.fitScale(900, 900, 180, 260, 0.6f), 1e-6);
    }

    @Test
    public void fitScaleToleratesZeroWidths() {
        assertEquals(1.0f, WordTimer.fitScale(0, 0, 180, 260, 0.6f), 1e-6);
    }

    @Test
    public void splitPointPrefersHyphen() {
        char[] t = "северо-восточный".toCharArray();
        assertEquals(7, WordTimer.splitPoint(t, 0, t.length));
    }

    @Test
    public void splitPointChoosesHyphenNearestToMiddle() {
        char[] t = "а-бвгдеж-зийкл".toCharArray();
        assertEquals(9, WordTimer.splitPoint(t, 0, t.length));
    }

    @Test
    public void splitPointWithoutHyphenCutsInMiddle() {
        char[] t = letters(10).toCharArray();
        assertEquals(5, WordTimer.splitPoint(t, 0, t.length));
        char[] odd = letters(9).toCharArray();
        assertEquals(5, WordTimer.splitPoint(odd, 0, odd.length));
    }

    @Test
    public void splitPointIgnoresLeadingDialogueDash() {
        char[] t = ("\u2014\u00A0" + letters(8)).toCharArray();
        int cut = WordTimer.splitPoint(t, 0, t.length);
        assertTrue(cut > 2 && cut < t.length);
    }

    @Test
    public void splitPointRespectsStartOffset() {
        char[] t = ("xx " + "северо-восточный").toCharArray();
        assertEquals(3 + 7, WordTimer.splitPoint(t, 3, t.length));
    }
}
