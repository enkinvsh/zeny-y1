package com.themoon.y1.managers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.TreeMap;
import org.junit.Test;

/** Строки в тестах выдуманные: настоящих текстов песен в репозитории быть не должно. */
public final class LyricsDocTest {

    @Test
    public void parsesBasicSyncedLines() {
        LyricsDoc d = LyricsDoc.parse("[00:01.50] alpha\n[00:03.00] beta\n[01:02.25] gamma");
        assertNotNull(d);
        assertTrue(d.synced);
        assertArrayEquals(new int[]{1500, 3000, 62250}, d.timesMs);
        assertArrayEquals(new String[]{"alpha", "beta", "gamma"}, d.lines);
        assertEquals(3, d.size());
    }

    @Test
    public void understandsOneTwoAndThreeDigitFractions() {
        LyricsDoc d = LyricsDoc.parse("[0:05.3] a\n[00:06.25] b\n[00:07.125] c\n[00:08] d");
        assertNotNull(d);
        assertArrayEquals(new int[]{5300, 6250, 7125, 8000}, d.timesMs);
    }

    @Test
    public void severalStampsOnOneLineMakeSeveralEntries() {
        LyricsDoc d = LyricsDoc.parse("[00:10.00][00:30.00] refrain\n[00:20.00] verse");
        assertNotNull(d);
        assertArrayEquals(new int[]{10000, 20000, 30000}, d.timesMs);
        assertArrayEquals(new String[]{"refrain", "verse", "refrain"}, d.lines);
    }

    @Test
    public void ignoresTagLines() {
        LyricsDoc d = LyricsDoc.parse("[ar:Nobody]\n[ti:Nothing]\n[offset:+250]\n[00:01.00] one\n[by:me]");
        assertNotNull(d);
        assertEquals(1, d.size());
        assertEquals("one", d.lines[0]);
    }

    @Test
    public void keepsEmptyStampsAsBlankLines() {
        LyricsDoc d = LyricsDoc.parse("[00:01.00] one\n[00:05.00]\n[00:09.00]   \n[00:12.00] two");
        assertNotNull(d);
        assertArrayEquals(new String[]{"one", "", "", "two"}, d.lines);
        assertArrayEquals(new int[]{1000, 5000, 9000, 12000}, d.timesMs);
    }

    @Test
    public void sortsByTimeAndKeepsFileOrderForEqualTimes() {
        LyricsDoc d = LyricsDoc.parse("[00:20.00] late\n[00:05.00] first\n[00:05.00] second");
        assertNotNull(d);
        assertArrayEquals(new int[]{5000, 5000, 20000}, d.timesMs);
        assertArrayEquals(new String[]{"first", "second", "late"}, d.lines);
    }

    @Test
    public void trimsTextAndToleratesCarriageReturns() {
        LyricsDoc d = LyricsDoc.parse("[00:01.00]   padded text  \r\n[00:02.00]\ttab\r\n");
        assertNotNull(d);
        assertEquals("padded text", d.lines[0]);
        assertEquals("tab", d.lines[1]);
    }

    @Test
    public void plainTextWithoutStampsIsPlain() {
        LyricsDoc d = LyricsDoc.parse("first line\r\nsecond line\n\n\n\nthird line\n");
        assertNotNull(d);
        assertFalse(d.synced);
        assertEquals(0, d.timesMs.length);
        assertArrayEquals(new String[]{"first line", "second line", "", "third line"}, d.lines);
        assertEquals("first line\nsecond line\n\nthird line", d.plain);
    }

    @Test
    public void plainTextKeepsBracketedSectionNames() {
        LyricsDoc d = LyricsDoc.parse("[Verse]\nsome words\n[Chorus]\nother words");
        assertNotNull(d);
        assertFalse(d.synced);
        assertEquals("[Verse]", d.lines[0]);
    }

    @Test
    public void plainTextStripsEdgeBlankLines() {
        LyricsDoc d = LyricsDoc.parse("\n\n  \nonly line\n\n");
        assertNotNull(d);
        assertArrayEquals(new String[]{"only line"}, d.lines);
    }

    @Test
    public void plainTextIsCappedAtTwentyThousandChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append("line number ").append(i).append('\n');
        LyricsDoc d = LyricsDoc.parse(sb.toString());
        assertNotNull(d);
        assertTrue(d.plain.length() <= LyricsDoc.MAX_PLAIN_CHARS);
        assertTrue(d.lines.length < 3000);
        assertEquals("line number 0", d.lines[0]);
    }

    @Test
    public void emptyOrBlankInputGivesNull() {
        assertNull(LyricsDoc.parse(null));
        assertNull(LyricsDoc.parse(""));
        assertNull(LyricsDoc.parse("  \n\r\n "));
    }

    @Test
    public void syncedFileWithOnlyBlankTextGivesNull() {
        assertNull(LyricsDoc.parse("[00:01.00]\n[00:02.00]  "));
    }

    @Test
    public void buildsFromTreeMap() {
        TreeMap<Integer, String> chapters = new TreeMap<>();
        chapters.put(60000, "Part two");
        chapters.put(0, "Part one");
        LyricsDoc d = LyricsDoc.fromMap(chapters);
        assertNotNull(d);
        assertTrue(d.synced);
        assertArrayEquals(new int[]{0, 60000}, d.timesMs);
        assertArrayEquals(new String[]{"Part one", "Part two"}, d.lines);
        assertNull(LyricsDoc.fromMap(new TreeMap<Integer, String>()));
    }

    @Test
    public void indexAtFindsLastLineNotAfterPosition() {
        LyricsDoc d = LyricsDoc.parse("[00:10.00] a\n[00:20.00] b\n[00:30.00] c");
        assertNotNull(d);
        assertEquals(-1, d.indexAt(0));
        assertEquals(-1, d.indexAt(9999));
        assertEquals(0, d.indexAt(10000));
        assertEquals(0, d.indexAt(19999));
        assertEquals(1, d.indexAt(20000));
        assertEquals(2, d.indexAt(30000));
        assertEquals(2, d.indexAt(9_000_000));
    }

    @Test
    public void indexAtTakesLastOfEqualTimes() {
        LyricsDoc d = LyricsDoc.parse("[00:05.00] a\n[00:05.00] b\n[00:09.00] c");
        assertNotNull(d);
        assertEquals(1, d.indexAt(5000));
    }

    @Test
    public void indexAtOnPlainDocIsAlwaysMinusOne() {
        LyricsDoc d = LyricsDoc.parse("just text");
        assertNotNull(d);
        assertEquals(-1, d.indexAt(12345));
    }
}
