package com.themoon.y1.managers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;

/** Измеритель с шириной 1 px на символ: тесты не зависят от Android и от шрифтов. */
public final class LyricsWrapTest {

    private static final LyricsWrap.TextMeasurer ONE_PX = new LyricsWrap.TextMeasurer() {
        @Override
        public float width(String s) {
            return s.length();
        }
    };

    private static String[] wrap(String text, float width, int maxLines) {
        String[] out = new String[maxLines];
        int n = LyricsWrap.wrapBalanced(text, width, maxLines, ONE_PX, out);
        String[] res = new String[n];
        System.arraycopy(out, 0, res, 0, n);
        return res;
    }

    @Test
    public void shortTextStaysOnOneLine() {
        assertArrayEquals(new String[]{"alpha beta"}, wrap("alpha beta", 20, 2));
    }

    @Test
    public void emptyTextGivesNoLines() {
        assertEquals(0, wrap("", 20, 2).length);
        assertEquals(0, wrap("   ", 20, 2).length);
    }

    @Test
    public void twoLinesAreBalancedNotGreedy() {
        // Жадно вышло бы "aaa bbb ccc ddd" + "eee", сбалансированно: примерно поровну.
        String[] r = wrap("aaa bbb ccc ddd eee", 16, 2);
        assertEquals(2, r.length);
        assertEquals("aaa bbb ccc", r[0]);
        assertEquals("ddd eee", r[1]);
    }

    @Test
    public void balancedLinesDifferByLessThanOneWord() {
        String[] r = wrap("one two three four five six seven eight", 30, 2);
        assertEquals(2, r.length);
        assertTrue(Math.abs(r[0].length() - r[1].length()) <= 6);
        assertEquals("one two three four five six seven eight", r[0] + " " + r[1]);
    }

    @Test
    public void everyLineFitsTheWidth() {
        String[] r = wrap("lorem ipsum dolor sit amet consectetur", 25, 2);
        for (String line : r) assertTrue(line.length() <= 25);
    }

    @Test
    public void overflowEllipsizesTheLastLine() {
        String[] r = wrap("aaaa bbbb cccc dddd eeee ffff gggg hhhh", 10, 2);
        assertEquals(2, r.length);
        assertEquals("aaaa bbbb", r[0]);
        assertTrue(r[1].endsWith("\u2026"));
        assertTrue(r[1].length() <= 10);
        assertTrue(r[1].startsWith("cccc"));
    }

    @Test
    public void singleLineLimitEllipsizes() {
        String[] r = wrap("aaaa bbbb cccc dddd", 10, 1);
        assertEquals(1, r.length);
        assertTrue(r[0].endsWith("\u2026"));
        assertTrue(r[0].length() <= 10);
    }

    @Test
    public void veryLongWordIsBrokenByCharacters() {
        String[] r = wrap("abcdefghijklmnopqrstuvwxyz", 10, 3);
        assertEquals(3, r.length);
        assertEquals("abcdefghij", r[0]);
        assertEquals("klmnopqrst", r[1]);
        assertEquals("uvwxyz", r[2]);
    }

    @Test
    public void ellipsizeKeepsFittingTextAsIs() {
        assertEquals("short", LyricsWrap.ellipsize("short", 10, ONE_PX));
    }

    @Test
    public void ellipsizeCutsToWidthIncludingTheMark() {
        String r = LyricsWrap.ellipsize("abcdefghijklmnop", 8, ONE_PX);
        assertEquals("abcdefg\u2026", r);
    }

    @Test
    public void ellipsizeDropsTrailingSpaceBeforeTheMark() {
        String r = LyricsWrap.ellipsize("abc defghijkl", 5, ONE_PX);
        assertEquals("abc\u2026", r);
    }

    @Test
    public void wrapAllKeepsBlankLinesAndWrapsLongOnes() {
        ArrayList<String> out = new ArrayList<String>();
        LyricsWrap.wrapAll("aaa bbb ccc ddd\n\nshort", 8, ONE_PX, out);
        assertEquals(4, out.size());
        assertEquals("aaa bbb", out.get(0));
        assertEquals("ccc ddd", out.get(1));
        assertEquals("", out.get(2));
        assertEquals("short", out.get(3));
    }

    @Test
    public void wrapAllHandlesCarriageReturns() {
        ArrayList<String> out = new ArrayList<String>();
        LyricsWrap.wrapAll("one\r\ntwo", 20, ONE_PX, out);
        assertEquals(2, out.size());
        assertEquals("one", out.get(0));
        assertEquals("two", out.get(1));
    }
}
