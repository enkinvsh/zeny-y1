package com.themoon.y1.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

public class ReaderFormatTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void lastTimeTodayShowsWordAndClock() {
        long now = at(2026, 10, 17, 14, 0);
        long then = at(2026, 10, 17, 2, 10);
        assertEquals("сегодня 02:10", ReaderFormat.lastTime(now, then, UTC, "сегодня", "вчера"));
    }

    @Test
    public void lastTimeYesterdayAcrossMidnight() {
        long now = at(2026, 10, 17, 0, 5);
        long then = at(2026, 10, 16, 23, 59);
        assertEquals("вчера 23:59", ReaderFormat.lastTime(now, then, UTC, "сегодня", "вчера"));
    }

    @Test
    public void lastTimeOlderShowsDateWithoutYearInSameYear() {
        long now = at(2026, 10, 17, 9, 0);
        long then = at(2026, 3, 5, 18, 7);
        assertEquals("05.03 18:07", ReaderFormat.lastTime(now, then, UTC, "сегодня", "вчера"));
    }

    @Test
    public void lastTimeOtherYearShowsYear() {
        long now = at(2026, 1, 2, 9, 0);
        long then = at(2025, 12, 30, 8, 30);
        assertEquals("30.12.2025 08:30", ReaderFormat.lastTime(now, then, UTC, "сегодня", "вчера"));
    }

    @Test
    public void lastTimeInTheFutureCountsAsToday() {
        // Часы переведены назад: «завтра» человеку ничего не скажет.
        long now = at(2026, 10, 17, 9, 0);
        long then = at(2026, 10, 18, 3, 0);
        assertEquals("сегодня 03:00", ReaderFormat.lastTime(now, then, UTC, "сегодня", "вчера"));
    }

    @Test
    public void lastTimeUsesGivenTimeZone() {
        // 22:30 UTC 16 октября это уже 01:30 17 октября в UTC+3, то есть «сегодня».
        TimeZone msk = TimeZone.getTimeZone("GMT+03:00");
        long now = at(2026, 10, 17, 10, 0);
        long then = at(2026, 10, 16, 22, 30);
        assertEquals("сегодня 01:30", ReaderFormat.lastTime(now, then, msk, "сегодня", "вчера"));
    }

    @Test
    public void minutesLeftRoundsUpAndHidesZero() {
        assertEquals(0, ReaderFormat.minutesLeft(0));
        assertEquals(0, ReaderFormat.minutesLeft(-5));
        assertEquals(1, ReaderFormat.minutesLeft(1));
        assertEquals(1, ReaderFormat.minutesLeft(60000));
        assertEquals(2, ReaderFormat.minutesLeft(60001));
        assertEquals(134, ReaderFormat.minutesLeft(134L * 60000 - 10));
    }

    @Test
    public void fragmentShortTextIsKept() {
        assertEquals("Тихая гавань", ReaderFormat.fragment("Тихая гавань", 40));
    }

    @Test
    public void fragmentLongTextIsCutAtWordBoundary() {
        String f = ReaderFormat.fragment("Нора и Кай шли вдоль берега и считали чаек над водой", 20);
        assertEquals("Нора и Кай шли...", f);
        assertTrue(f.length() <= 20);
    }

    @Test
    public void fragmentWithoutSpacesIsCutHard() {
        assertEquals("абвгдежз...", ReaderFormat.fragment("абвгдежзийклмн", 11));
    }

    @Test
    public void fragmentNullIsEmpty() {
        assertEquals("", ReaderFormat.fragment(null, 10));
    }

    @Test
    public void fragmentCollapsesNbspAndSpaces() {
        assertEquals("а б в", ReaderFormat.fragment("а\u00A0б  в", 40));
    }

    @Test
    public void wheelTurnedFastJumpsBySentence() {
        assertTrue(ReaderFormat.isFastTurn(40));
        assertFalse(ReaderFormat.isFastTurn(ReaderFormat.FAST_TURN_GAP_MS + 1));
        assertFalse(ReaderFormat.isFastTurn(Long.MAX_VALUE));
    }

    @Test
    public void seekStepGrowsAfterTwoSeconds() {
        assertEquals(10, ReaderFormat.seekWords(0));
        assertEquals(10, ReaderFormat.seekWords(1999));
        assertEquals(30, ReaderFormat.seekWords(2000));
        assertEquals(30, ReaderFormat.seekWords(60000));
    }

    @Test
    public void seekStepDueEveryHundredFiftyMs() {
        assertTrue(ReaderFormat.isSeekStepDue(1000, 0));
        assertFalse(ReaderFormat.isSeekStepDue(1100, 1000));
        assertTrue(ReaderFormat.isSeekStepDue(1150, 1000));
    }

    @Test
    public void wordSizeIndexMapsToPixels() {
        assertEquals(44, ReaderFormat.WORD_SIZES_PX[0]);
        assertEquals(52, ReaderFormat.WORD_SIZES_PX[1]);
        assertEquals(60, ReaderFormat.WORD_SIZES_PX[2]);
        assertEquals(1, ReaderFormat.wordSizeIndex(52));
        assertEquals(1, ReaderFormat.wordSizeIndex(777));
        assertEquals(2, ReaderFormat.wordSizeIndex(60));
    }
}
