package com.themoon.y1.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReaderEngineTest {

    /** N одинаковых слов «волна» без знаков: каждое показывается ровно 60000/wpm мс. */
    private static BookIndex flat(int n) {
        BookBuilder b = new BookBuilder();
        b.startChapter("Тихая гавань");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("волна ");
        b.text(sb);
        return b.build();
    }

    @Test
    public void wordsChangeEveryTwoHundredMsAtThreeHundredWpm() {
        ReaderEngine e = new ReaderEngine(flat(1000), 1, 300);
        e.play(10_000);
        assertEquals(10_200, e.nextDueMs());
        assertFalse(e.tick(10_199));
        assertEquals(1, e.word());
        assertTrue(e.tick(10_200));
        assertEquals(2, e.word());
        assertEquals(10_400, e.nextDueMs());
        for (int i = 2; i <= 50; i++) {
            assertTrue(e.tick(10_000 + 200L * i));
        }
        assertEquals(51, e.word());
    }

    @Test
    public void firstWordOfChapterStaysLonger() {
        ReaderEngine e = new ReaderEngine(flat(10), 0, 300);
        e.play(0);
        assertEquals(200 + WordTimer.CHAPTER_PAUSE_MS, e.nextDueMs());
    }

    @Test
    public void lagOfOneSecondCatchesUpAtMostThreeWords() {
        ReaderEngine e = new ReaderEngine(flat(1000), 1, 300);
        e.play(0);
        assertTrue(e.tick(1200));
        assertEquals(4, e.word());
        assertTrue("долг сверх трёх слов прощается", e.nextDueMs() > 1200);
        assertEquals(1400, e.nextDueMs());
        assertFalse(e.tick(1300));
        assertTrue(e.tick(1400));
        assertEquals(5, e.word());
    }

    @Test
    public void pausedEngineDoesNotMove() {
        ReaderEngine e = new ReaderEngine(flat(100), 5, 300);
        assertEquals(Long.MAX_VALUE, e.nextDueMs());
        assertFalse(e.tick(1_000_000));
        e.play(0);
        e.pause();
        assertFalse(e.isPlaying());
        assertFalse(e.tick(1_000_000));
        assertEquals(5, e.word());
        assertEquals(Long.MAX_VALUE, e.nextDueMs());
    }

    @Test
    public void reachingLastWordStopsReading() {
        ReaderEngine e = new ReaderEngine(flat(5), 2, 300);
        e.play(0);
        for (int i = 1; i < 20; i++) e.tick(200L * i);
        assertEquals(4, e.word());
        assertFalse(e.isPlaying());
        assertTrue(e.isFinished());
        assertEquals(100, e.percent());
        assertEquals(Long.MAX_VALUE, e.nextDueMs());
    }

    @Test
    public void playOnTheLastWordDoesNothing() {
        ReaderEngine e = new ReaderEngine(flat(5), 4, 300);
        e.play(0);
        assertFalse(e.isPlaying());
    }

    @Test
    public void wpmIsRoundedToStepAndClamped() {
        ReaderEngine e = new ReaderEngine(flat(10), 0, 330);
        assertEquals(350, e.wpm());
        e.setWpm(10);
        assertEquals(100, e.wpm());
        e.setWpm(5000);
        assertEquals(1000, e.wpm());
        e.setWpm(374);
        assertEquals(350, e.wpm());
        e.setWpm(375);
        assertEquals(400, e.wpm());
        e.setWpm(100);
        e.stepWpm(-1);
        assertEquals(100, e.wpm());
        e.stepWpm(1);
        assertEquals(150, e.wpm());
        e.setWpm(1000);
        e.stepWpm(1);
        assertEquals(1000, e.wpm());
    }

    @Test
    public void percentAtStartMiddleAndEnd() {
        ReaderEngine e = new ReaderEngine(flat(101), 0, 300);
        assertEquals(0, e.percent());
        e.jumpToWord(50);
        assertEquals(50, e.percent());
        e.jumpToWord(100);
        assertEquals(100, e.percent());
        assertEquals(101, e.wordCount());
    }

    @Test
    public void remainingTimeGrowsWhenSpeedDrops() {
        ReaderEngine e = new ReaderEngine(flat(1001), 0, 300);
        long atFast = e.remainingMs();
        e.setWpm(150);
        long atSlow = e.remainingMs();
        assertEquals(1000 * 200L, atFast);
        assertEquals(1000 * 400L, atSlow);
        assertTrue(atSlow > atFast);
        e.jumpToWord(1000);
        assertEquals(0, e.remainingMs());
    }

    @Test
    public void remainingTimeUsesAverageDelayFactorOfTheBook() {
        BookBuilder b = new BookBuilder();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("Кай сказал. ");
        b.text(sb);
        BookIndex idx = b.build();
        ReaderEngine e = new ReaderEngine(idx, 0, 300);
        long plain = (idx.wordCount() - 1) * 200L;
        assertTrue("паузы на точках удлиняют оценку", e.remainingMs() > plain);
    }

    // ------------------------------------------------------------------ предложения и абзацы

    private static BookIndex story() {
        BookBuilder b = new BookBuilder();
        b.startChapter("Одна");
        b.text("Нора вышла к воде. Кай ждал на пирсе. ");
        b.endParagraph();
        b.text("Чайки кричали над бухтой. Ветер стих. ");
        b.endParagraph();
        b.startChapter("Две");
        b.text("Утро пришло тихо.");
        return b.build();
    }

    @Test
    public void sentenceStepsStopAtBoundaries() {
        BookIndex idx = story();
        ReaderEngine e = new ReaderEngine(idx, 1, 300);
        e.stepSentence(1);
        assertEquals(4, e.word());
        e.stepSentence(1);
        assertEquals(8, e.word());
        e.stepSentence(-1);
        assertEquals(4, e.word());
        e.stepSentence(-1);
        assertEquals(0, e.word());
        e.stepSentence(-1);
        assertEquals(0, e.word());
    }

    @Test
    public void sentenceStepsAtBookEndStayOnLastWord() {
        BookIndex idx = story();
        ReaderEngine e = new ReaderEngine(idx, idx.wordCount() - 2, 300);
        e.stepSentence(1);
        assertEquals(idx.wordCount() - 1, e.word());
        e.stepSentence(1);
        assertEquals(idx.wordCount() - 1, e.word());
    }

    @Test
    public void paragraphStepsMoveBetweenParagraphs() {
        BookIndex idx = story();
        ReaderEngine e = new ReaderEngine(idx, 1, 300);
        e.stepParagraph(1);
        assertEquals(idx.nextParagraph(1), e.word());
        int second = e.word();
        e.stepParagraph(-1);
        assertEquals(0, e.word());
        assertTrue(second > 0);
    }

    @Test
    public void wheelStepsAreClampedToBook() {
        ReaderEngine e = new ReaderEngine(flat(10), 3, 300);
        e.stepWords(-50);
        assertEquals(0, e.word());
        e.stepWords(500);
        assertEquals(9, e.word());
    }

    @Test
    public void chapterJumpGoesToChapterStart() {
        BookIndex idx = story();
        ReaderEngine e = new ReaderEngine(idx, 0, 300);
        e.jumpToChapter(1);
        assertEquals(idx.chapterFirstWord[1], e.word());
        e.jumpToChapter(99);
        assertEquals(idx.chapterFirstWord[1], e.word());
        e.jumpToChapter(-5);
        assertEquals(0, e.word());
    }

    // ------------------------------------------------------------------ точка возврата

    @Test
    public void jumpOfFivePercentLeavesReturnPoint() {
        ReaderEngine e = new ReaderEngine(flat(5001), 50, 300);
        assertFalse(e.hasReturnPoint());
        e.jumpToPercent(5);
        assertEquals(250, e.word());
        assertTrue(e.hasReturnPoint());
        assertEquals(50, e.returnPointWord());
        assertEquals(1, e.returnPointPercent());
    }

    @Test
    public void shortSeekDoesNotLeaveReturnPoint() {
        ReaderEngine e = new ReaderEngine(flat(5001), 100, 300);
        e.seekRelativeWords(10);
        e.seekRelativeWords(150);
        e.jumpToWord(110);
        assertFalse(e.hasReturnPoint());
        e.seekRelativeWords(151);
        assertTrue(e.hasReturnPoint());
    }

    @Test
    public void wheelAndSentenceStepsNeverLeaveReturnPoint() {
        ReaderEngine e = new ReaderEngine(flat(5001), 100, 300);
        e.stepWords(1000);
        assertFalse(e.hasReturnPoint());
    }

    @Test
    public void returnToPointSwapsPlaces() {
        ReaderEngine e = new ReaderEngine(flat(5001), 100, 300);
        e.jumpToWord(1000);
        e.returnToPoint();
        assertEquals(100, e.word());
        assertEquals(1000, e.returnPointWord());
        e.returnToPoint();
        assertEquals(1000, e.word());
        assertEquals(100, e.returnPointWord());
    }

    @Test
    public void returnToPointWithoutPointDoesNothing() {
        ReaderEngine e = new ReaderEngine(flat(50), 7, 300);
        e.returnToPoint();
        assertEquals(7, e.word());
    }

    @Test
    public void returnPointCanBeRestoredAndCleared() {
        ReaderEngine e = new ReaderEngine(flat(5001), 100, 300);
        e.setReturnPoint(4000);
        assertTrue(e.hasReturnPoint());
        assertEquals(80, e.returnPointPercent());
        e.clearReturnPoint();
        assertFalse(e.hasReturnPoint());
    }

    // ------------------------------------------------------------------ перемотка во время чтения

    @Test
    public void seekWhilePlayingReschedulesFromLastKnownClock() {
        ReaderEngine e = new ReaderEngine(flat(1000), 1, 300);
        e.play(0);
        e.tick(200);
        e.seekRelativeWords(300);
        assertEquals(302, e.word());
        assertEquals(400, e.nextDueMs());
        assertTrue(e.tick(400));
        assertEquals(303, e.word());
    }

    @Test
    public void seekToTheEndWhilePlayingStops() {
        ReaderEngine e = new ReaderEngine(flat(100), 1, 300);
        e.play(0);
        e.jumpToPercent(100);
        assertFalse(e.isPlaying());
        assertEquals(99, e.word());
    }

    // ------------------------------------------------------------------ разрез длинных слов

    @Test
    public void splitWordIsShownAsTwoPartsWithDoubledTime() {
        ReaderEngine e = new ReaderEngine(flat(100), 1, 300);
        e.setSplitOracle(new ReaderEngine.SplitOracle() {
            @Override
            public boolean needsSplit(BookIndex index, int word) {
                return word == 2;
            }
        });
        e.play(0);
        assertTrue(e.tick(200));
        assertEquals(2, e.word());
        assertEquals(0, e.part());
        assertTrue(e.tick(400));
        assertEquals("вторая часть того же слова", 2, e.word());
        assertEquals(1, e.part());
        assertTrue(e.tick(600));
        assertEquals(3, e.word());
        assertEquals(0, e.part());
        assertEquals(800, e.nextDueMs());
    }

    @Test
    public void seekResetsSplitPart() {
        ReaderEngine e = new ReaderEngine(flat(100), 1, 300);
        e.setSplitOracle(new ReaderEngine.SplitOracle() {
            @Override
            public boolean needsSplit(BookIndex index, int word) {
                return true;
            }
        });
        e.play(0);
        e.tick(200);
        assertEquals(1, e.part());
        e.stepWords(1);
        assertEquals(0, e.part());
    }

    @Test
    public void emptyBookIsHarmless() {
        BookBuilder b = new BookBuilder();
        ReaderEngine e = new ReaderEngine(b.build(), 0, 300);
        e.play(0);
        assertFalse(e.isPlaying());
        assertEquals(0, e.word());
        assertEquals(0, e.percent());
        assertEquals(0, e.remainingMs());
        e.stepSentence(1);
        e.jumpToChapter(0);
        e.jumpToPercent(50);
        assertEquals(0, e.word());
    }
}
