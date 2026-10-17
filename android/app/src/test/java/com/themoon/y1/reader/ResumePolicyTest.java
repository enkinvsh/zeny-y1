package com.themoon.y1.reader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ResumePolicyTest {

    /** Два абзаца по два предложения: слова 0..2 | 3..5 || 6..8 | 9..11, середина предложения везде. */
    private static BookIndex book() {
        BookBuilder b = new BookBuilder();
        b.text("Раз два три. Четыре пять шесть.");
        b.endParagraph();
        b.text("Семь восемь девять. Десять одиннадцать двенадцать.");
        b.endParagraph();
        return b.build();
    }

    private static final long MIN = 60000L;
    private static final long HOUR = 60 * MIN;

    @Test
    public void justPausedRollsBackThreeWords() {
        assertEquals(7, ResumePolicy.rollback(book(), 10, 0));
        assertEquals(7, ResumePolicy.rollback(book(), 10, 119000));
    }

    @Test
    public void threeWordRollbackNeverGoesBelowZero() {
        assertEquals(0, ResumePolicy.rollback(book(), 2, 1000));
        assertEquals(0, ResumePolicy.rollback(book(), 0, 1000));
    }

    @Test
    public void afterTwoMinutesRollsBackToSentenceStart() {
        assertEquals(9, ResumePolicy.rollback(book(), 10, 121000));
        assertEquals(9, ResumePolicy.rollback(book(), 10, 5 * HOUR + 59 * MIN));
    }

    @Test
    public void afterSixHoursRollsBackToParagraphStart() {
        assertEquals(6, ResumePolicy.rollback(book(), 10, 6 * HOUR + MIN));
        assertEquals(6, ResumePolicy.rollback(book(), 10, 100 * HOUR));
    }

    @Test
    public void thresholdsAreExactlyTwoMinutesAndSixHours() {
        assertEquals(9, ResumePolicy.rollback(book(), 10, 2 * MIN));
        assertEquals(6, ResumePolicy.rollback(book(), 10, 6 * HOUR));
    }

    @Test
    public void negativeAgeFromClockChangeCountsAsFresh() {
        assertEquals(7, ResumePolicy.rollback(book(), 10, -5000));
    }

    @Test
    public void wordBeyondBookIsClamped() {
        assertEquals(9, ResumePolicy.rollback(book(), 500, 10 * MIN));
    }
}
