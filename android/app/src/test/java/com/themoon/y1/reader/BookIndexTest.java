package com.themoon.y1.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

public final class BookIndexTest {

    /**
     * Слова: Утром0 Нора1 вышла2 к3 причалу.4 | Кай5 ждал6 у7 лодки.8 || Он9 молчал,10 потому11 что12 устал!13 |
     * Море14 тихо15 дышало.16 || глава 2: Вечером17 они18 ушли.19.
     * Начала предложений: 0, 5, 9, 14, 17. Начала абзацев: 0, 9, 17.
     */
    static BookIndex sample() {
        BookBuilder b = new BookBuilder();
        b.meta("Тихая гавань", "Нора Вест");
        b.startChapter("Утро");
        b.text("Утром Нора вышла к причалу. Кай ждал у лодки.");
        b.endParagraph();
        b.text("Он молчал, потому что устал! Море тихо дышало.");
        b.endParagraph();
        b.startChapter("Вечер");
        b.text("Вечером они ушли.");
        b.endParagraph();
        return b.build();
    }

    /** Длинная книга из уникальных слов w0 w1 w2 ..., чтобы якорь находился однозначно. */
    static BookIndex numbered(int n, String prefix) {
        BookBuilder b = new BookBuilder();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(prefix).append(i).append(' ');
        b.text(sb);
        return b.build();
    }

    @Test
    public void sampleHasExpectedShape() {
        BookIndex idx = sample();
        assertEquals(20, idx.wordCount());
        assertEquals("Тихая гавань", idx.title);
        assertArrayEquals(new String[]{"Утро", "Вечер"}, idx.chapterTitles);
        assertArrayEquals(new int[]{0, 17}, idx.chapterFirstWord);
    }

    @Test
    public void sentenceStartOnBoundaries() {
        BookIndex idx = sample();
        assertEquals(0, idx.sentenceStart(0));
        assertEquals(0, idx.sentenceStart(3));
        assertEquals(5, idx.sentenceStart(5));
        assertEquals(5, idx.sentenceStart(8));
        assertEquals(9, idx.sentenceStart(9));
        assertEquals(17, idx.sentenceStart(19));
    }

    @Test
    public void nextSentenceMovesToNextStartAndClampsAtEnd() {
        BookIndex idx = sample();
        assertEquals(5, idx.nextSentence(0));
        assertEquals(5, idx.nextSentence(4));
        assertEquals(9, idx.nextSentence(5));
        assertEquals(17, idx.nextSentence(16));
        assertEquals(19, idx.nextSentence(19));
        assertEquals(19, idx.nextSentence(18));
    }

    @Test
    public void prevSentenceGoesToStartOfCurrentThenPrevious() {
        BookIndex idx = sample();
        assertEquals(0, idx.prevSentence(0));
        assertEquals(0, idx.prevSentence(3));
        assertEquals(0, idx.prevSentence(5));
        assertEquals(5, idx.prevSentence(6));
        assertEquals(5, idx.prevSentence(9));
        assertEquals(14, idx.prevSentence(17));
    }

    @Test
    public void paragraphNavigation() {
        BookIndex idx = sample();
        assertEquals(0, idx.paragraphStart(4));
        assertEquals(9, idx.paragraphStart(16));
        assertEquals(17, idx.paragraphStart(19));
        assertEquals(9, idx.nextParagraph(0));
        assertEquals(9, idx.nextParagraph(8));
        assertEquals(17, idx.nextParagraph(9));
        assertEquals(19, idx.nextParagraph(17));
        assertEquals(19, idx.nextParagraph(19));
        assertEquals(9, idx.prevParagraph(10));
        assertEquals(0, idx.prevParagraph(9));
        assertEquals(0, idx.prevParagraph(0));
    }

    @Test
    public void chapterOfUsesBinarySearch() {
        BookIndex idx = sample();
        assertEquals(0, idx.chapterOf(0));
        assertEquals(0, idx.chapterOf(16));
        assertEquals(1, idx.chapterOf(17));
        assertEquals(1, idx.chapterOf(19));
    }

    @Test
    public void anchorAroundTakesEightWordsAroundTheWord() {
        BookIndex idx = sample();
        assertEquals("причалу. Кай ждал у лодки. Он молчал, потому", idx.anchorAround(8, 8));
    }

    @Test
    public void anchorAroundClampsToBookEdgesKeepingFullWindow() {
        BookIndex idx = sample();
        assertEquals("Утром Нора вышла к причалу. Кай ждал у", idx.anchorAround(0, 8));
        assertEquals("что устал! Море тихо дышало. Вечером они ушли.", idx.anchorAround(19, 8));
    }

    @Test
    public void anchorAroundOnTinyBookReturnsWholeText() {
        BookBuilder b = new BookBuilder();
        b.text("раз два три");
        assertEquals("раз два три", b.build().anchorAround(1, 8));
        assertEquals("", new BookBuilder().build().anchorAround(0, 8));
    }

    @Test
    public void writeThenReadGivesIdenticalIndex() throws IOException {
        BookBuilder b = new BookBuilder();
        b.meta("Тихая гавань \uD83D\uDE00", "Нора Вест");
        b.startChapter("Утро");
        b.text("Утром Нора вышла к причалу. Кай ждал у лодки — тихо, как всегда…");
        b.endParagraph();
        b.startChapter("Вечер");
        b.text("Вечером они ушли. English words too, ĳ ß €");
        BookIndex a = b.build();
        BookIndex r = roundTrip(a);
        assertEquals(a.title, r.title);
        assertEquals(a.author, r.author);
        assertArrayEquals(a.text, r.text);
        assertArrayEquals(a.wordStart, r.wordStart);
        assertArrayEquals(a.flags, r.flags);
        assertArrayEquals(a.chapterTitles, r.chapterTitles);
        assertArrayEquals(a.chapterFirstWord, r.chapterFirstWord);
        assertEquals(a.avgDelayFactor, r.avgDelayFactor, 0f);
    }

    @Test
    public void emptyIndexRoundTrips() throws IOException {
        BookIndex r = roundTrip(new BookBuilder().build());
        assertEquals(0, r.wordCount());
        assertArrayEquals(new int[]{1}, r.wordStart);
    }

    @Test
    public void bigIndexRoundTrips() throws IOException {
        BookIndex a = numbered(30000, "слово");
        BookIndex r = roundTrip(a);
        assertEquals(a.wordCount(), r.wordCount());
        assertArrayEquals(a.text, r.text);
        assertArrayEquals(a.wordStart, r.wordStart);
    }

    @Test
    public void truncatedStreamThrowsIOException() throws IOException {
        byte[] bytes = serialize(sample());
        for (int cut : new int[]{0, 3, 6, bytes.length / 2, bytes.length - 1}) {
            try {
                BookIndex.read(new ByteArrayInputStream(Arrays.copyOf(bytes, cut)));
                fail("обрезка на " + cut + " байтах должна давать IOException");
            } catch (IOException expected) {
                // ожидаемо
            }
        }
    }

    @Test
    public void foreignVersionThrowsIOException() throws IOException {
        byte[] bytes = serialize(sample());
        bytes[7] = 2;
        try {
            BookIndex.read(new ByteArrayInputStream(bytes));
            fail("версия 2 должна отвергаться");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }

    @Test
    public void wrongMagicThrowsIOException() throws IOException {
        byte[] bytes = serialize(sample());
        bytes[0] = 'X';
        try {
            BookIndex.read(new ByteArrayInputStream(bytes));
            fail();
        } catch (IOException expected) {
            // ожидаемо
        }
    }

    @Test
    public void damagedEndMarkerThrowsIOException() throws IOException {
        byte[] bytes = serialize(sample());
        bytes[bytes.length - 1] ^= 0x55;
        try {
            BookIndex.read(new ByteArrayInputStream(bytes));
            fail();
        } catch (IOException expected) {
            // ожидаемо
        }
    }

    @Test
    public void cacheFileRoundTripAndMissingFile() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("zidx").toFile();
        File f = new File(dir, "a.zidx");
        assertTrue(BookIndex.saveCached(f, sample()));
        BookIndex r = BookIndex.loadCached(f);
        assertEquals(20, r.wordCount());
        assertEquals(null, BookIndex.loadCached(new File(dir, "none.zidx")));
        java.nio.file.Files.write(f.toPath(), new byte[]{1, 2, 3});
        assertEquals(null, BookIndex.loadCached(f));
    }

    @Test
    public void findAnchorAfterInsertingWordsAtStart() {
        BookIndex old = numbered(600, "w");
        String anchor = old.anchorAround(300, 8);
        BookBuilder b = new BookBuilder();
        b.text("новое начало вставлено ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append("w").append(i).append(' ');
        b.text(sb);
        BookIndex edited = b.build();
        assertEquals(303, edited.findAnchor(anchor, 300, 5000));
    }

    @Test
    public void findAnchorAfterDeletingWords() {
        BookIndex old = numbered(600, "w");
        String anchor = old.anchorAround(300, 8);
        BookBuilder b = new BookBuilder();
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < 600; i++) sb.append("w").append(i).append(' ');
        b.text(sb);
        assertEquals(297, b.build().findAnchor(anchor, 300, 5000));
    }

    @Test
    public void findAnchorFallsBackToWholeBookBeyondRadius() {
        BookIndex idx = numbered(600, "w");
        String anchor = idx.anchorAround(500, 8);
        assertEquals(500, idx.findAnchor(anchor, 100, 50));
    }

    @Test
    public void findAnchorPicksMatchClosestToNear() {
        BookBuilder b = new BookBuilder();
        StringBuilder sb = new StringBuilder();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 40; i++) sb.append("a").append(i).append(' ');
        }
        b.text(sb);
        BookIndex idx = b.build();
        String anchor = idx.anchorAround(20, 8);
        assertEquals(60, idx.findAnchor(anchor, 58, 5000));
        assertEquals(20, idx.findAnchor(anchor, 22, 5000));
        assertEquals(100, idx.findAnchor(anchor, 130, 5000));
    }

    @Test
    public void findAnchorReturnsMinusOneWhenAbsent() {
        BookIndex idx = numbered(100, "w");
        assertEquals(-1, idx.findAnchor("нет такого якоря вообще тут", 10, 5000));
        assertEquals(-1, idx.findAnchor("", 10, 5000));
        assertEquals(-1, idx.findAnchor(null, 10, 5000));
    }

    @Test
    public void findAnchorDoesNotMatchInsideWords() {
        BookBuilder b = new BookBuilder();
        b.text("xabc def ghi");
        assertEquals(-1, b.build().findAnchor("abc def", 0, 5000));
    }

    @Test
    public void findAnchorAtBookEdgeKeepsNearWithinAmbiguousRange() {
        BookIndex idx = numbered(100, "w");
        String head = idx.anchorAround(1, 8);
        assertEquals(1, idx.findAnchor(head, 1, 5000));
        String tail = idx.anchorAround(98, 8);
        assertEquals(98, idx.findAnchor(tail, 98, 5000));
    }

    private static byte[] serialize(BookIndex idx) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        idx.write(out);
        return out.toByteArray();
    }

    private static BookIndex roundTrip(BookIndex idx) throws IOException {
        return BookIndex.read(new ByteArrayInputStream(serialize(idx)));
    }
}
