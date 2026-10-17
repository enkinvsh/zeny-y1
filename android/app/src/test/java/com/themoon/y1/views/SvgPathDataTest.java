package com.themoon.y1.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

public class SvgPathDataTest {

    @Test
    public void absoluteLineAndClose() {
        List<float[]> s = SvgPathData.parse("M5 14L8.5 17.5L19 6.5Z");
        assertEquals(4, s.size());
        assertEquals(SvgPathData.MOVE, (int) s.get(0)[0]);
        assertEquals(5f, s.get(0)[1], 0f);
        assertEquals(14f, s.get(0)[2], 0f);
        assertEquals(SvgPathData.LINE, (int) s.get(2)[0]);
        assertEquals(19f, s.get(2)[1], 0f);
        assertEquals(SvgPathData.CLOSE, (int) s.get(3)[0]);
    }

    @Test
    public void horizontalAndVerticalUseCurrentPoint() {
        List<float[]> s = SvgPathData.parse("M3 18H4.5V10");
        assertEquals(3, s.size());
        // H оставляет y, V оставляет x
        assertEquals(4.5f, s.get(1)[1], 0f);
        assertEquals(18f, s.get(1)[2], 0f);
        assertEquals(4.5f, s.get(2)[1], 0f);
        assertEquals(10f, s.get(2)[2], 0f);
    }

    @Test
    public void cubicHasSixCoordinates() {
        List<float[]> s = SvgPathData.parse("M2 8C2 5 4 3 7 3.5");
        assertEquals(2, s.size());
        assertEquals(SvgPathData.CUBIC, (int) s.get(1)[0]);
        assertEquals(7, s.get(1).length);
        assertEquals(3.5f, s.get(1)[6], 0f);
    }

    @Test
    public void twoGluedNegativeNumbers() {
        // "-6.00081 17.9992M17.9992 18" и "1-2" без разделителя встречаются в наборе иконок
        List<float[]> s = SvgPathData.parse("M18 6L6.00081 17.9992M17.9992 18L6 6.00085");
        assertEquals(4, s.size());
        List<float[]> t = SvgPathData.parse("M1-2L3-4");
        assertEquals(-2f, t.get(0)[2], 0f);
        assertEquals(-4f, t.get(1)[2], 0f);
    }

    @Test
    public void implicitLineToAfterMove() {
        List<float[]> s = SvgPathData.parse("M0 0 4 4 8 0");
        assertEquals(3, s.size());
        assertEquals(SvgPathData.LINE, (int) s.get(1)[0]);
        assertEquals(SvgPathData.LINE, (int) s.get(2)[0]);
    }

    @Test
    public void relativeCommandsAreMadeAbsolute() {
        List<float[]> s = SvgPathData.parse("M10 10l5 5h5v-5");
        assertEquals(15f, s.get(1)[1], 0f);
        assertEquals(15f, s.get(1)[2], 0f);
        assertEquals(20f, s.get(2)[1], 0f);
        assertEquals(10f, s.get(3)[2], 0f);
    }

    @Test
    public void arcIsRejectedLoudly() {
        assertThrows(IllegalArgumentException.class, () -> SvgPathData.parse("M0 0A5 5 0 0 1 10 10"));
    }

    @Test
    public void everyGlyphInSetParses() {
        String[][] all = {
            HugeGlyph.HEART, HugeGlyph.SHUFFLE, HugeGlyph.REPEAT, HugeGlyph.REPEAT_ONE,
            HugeGlyph.PAUSE, HugeGlyph.PLAY, HugeGlyph.TICK, HugeGlyph.CROSS, HugeGlyph.FLASH,
        HugeGlyph.MOON, HugeGlyph.BOOK
        };
        for (String[] glyph : all) {
            for (String d : glyph) {
                List<float[]> s = SvgPathData.parse(d);
                assertEquals(SvgPathData.MOVE, (int) s.get(0)[0]);
            }
        }
    }
}
