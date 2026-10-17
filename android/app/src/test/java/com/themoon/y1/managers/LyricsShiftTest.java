package com.themoon.y1.managers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricsShiftTest {
    @Test
    public void stepMovesByTenthAndClamps() {
        assertEquals(100, LyricsShift.step(0, 1));
        assertEquals(-100, LyricsShift.step(0, -1));
        assertEquals(10000, LyricsShift.step(10000, 1));
        assertEquals(-10000, LyricsShift.step(-10000, -1));
        assertEquals(9900, LyricsShift.step(10000, -1));
    }

    @Test
    public void clampKeepsRange() {
        assertEquals(10000, LyricsShift.clamp(99999));
        assertEquals(-10000, LyricsShift.clamp(-99999));
        assertEquals(300, LyricsShift.clamp(300));
    }

    @Test
    public void formatUsesSignAndComma() {
        assertEquals("0 с", LyricsShift.format(0, ',', "с"));
        assertEquals("+0,3 с", LyricsShift.format(300, ',', "с"));
        assertEquals("-1,5 с", LyricsShift.format(-1500, ',', "с"));
        assertEquals("+2 с", LyricsShift.format(2000, ',', "с"));
        assertEquals("-10 с", LyricsShift.format(-10000, ',', "с"));
        assertEquals("+0.1 s", LyricsShift.format(100, '.', "s"));
    }

    @Test
    public void positiveShiftDelaysText() {
        // Строка на 10,0 с при сдвиге +0,5 с должна загореться только на 10,5 с позиции плеера.
        assertEquals(9500, LyricsShift.effectivePosition(10000, 500));
        assertEquals(10500, LyricsShift.effectivePosition(10000, -500));
    }
}
