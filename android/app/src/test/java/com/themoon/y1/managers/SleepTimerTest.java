package com.themoon.y1.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SleepTimerTest {
    private static final long T0 = 1_000_000L;
    private static final long MIN = 60_000L;

    private static SleepTimer armed(int option) {
        SleepTimer t = new SleepTimer();
        while (t.option() != option) t.cycle(T0);
        return t;
    }

    @Test
    public void startsOff() {
        SleepTimer t = new SleepTimer();
        assertEquals(SleepTimer.OFF, t.option());
        assertFalse(t.isActive());
        assertEquals(0, t.labelMinutes());
    }

    @Test
    public void cycleOrderAndWraparound() {
        SleepTimer t = new SleepTimer();
        int[] expected = {
            SleepTimer.MIN_15, SleepTimer.MIN_30, SleepTimer.MIN_45, SleepTimer.MIN_60,
            SleepTimer.MIN_90, SleepTimer.END_OF_TRACK, SleepTimer.OFF, SleepTimer.MIN_15
        };
        for (int e : expected) {
            t.cycle(T0);
            assertEquals(e, t.option());
        }
    }

    @Test
    public void labelMinutesMatchOption() {
        assertEquals(15, armed(SleepTimer.MIN_15).labelMinutes());
        assertEquals(30, armed(SleepTimer.MIN_30).labelMinutes());
        assertEquals(45, armed(SleepTimer.MIN_45).labelMinutes());
        assertEquals(60, armed(SleepTimer.MIN_60).labelMinutes());
        assertEquals(90, armed(SleepTimer.MIN_90).labelMinutes());
        assertEquals(0, armed(SleepTimer.END_OF_TRACK).labelMinutes());
    }

    @Test
    public void timedOptionIsActiveWithRemainingTime() {
        SleepTimer t = armed(SleepTimer.MIN_15);
        assertTrue(t.isActive());
        assertEquals(15 * MIN, t.remainingMs(T0));
        assertEquals(10 * MIN, t.remainingMs(T0 + 5 * MIN));
    }

    @Test
    public void remainingMsNeverNegative() {
        SleepTimer t = armed(SleepTimer.MIN_15);
        assertEquals(0, t.remainingMs(T0 + 20 * MIN));
    }

    @Test
    public void remainingMinutesRoundsUp() {
        SleepTimer t = armed(SleepTimer.MIN_15);
        assertEquals(15, t.remainingMinutes(T0));
        assertEquals(15, t.remainingMinutes(T0 + 1));
        assertEquals(14, t.remainingMinutes(T0 + MIN));
        assertEquals(1, t.remainingMinutes(T0 + 14 * MIN));
        assertEquals(1, t.remainingMinutes(T0 + 15 * MIN - 1));
        assertEquals(0, t.remainingMinutes(T0 + 15 * MIN));
    }

    @Test
    public void fadeFactorBoundaries() {
        SleepTimer t = armed(SleepTimer.MIN_15);
        long deadline = T0 + 15 * MIN;
        assertEquals(1.0f, t.fadeFactor(T0), 0f);
        assertEquals(1.0f, t.fadeFactor(deadline - 30001), 0f);
        assertEquals(1.0f, t.fadeFactor(deadline - 30000), 0f);
        assertEquals(0.5f, t.fadeFactor(deadline - 15000), 1e-6f);
        assertEquals(0.0f, t.fadeFactor(deadline), 0f);
        assertEquals(0.0f, t.fadeFactor(deadline + 5000), 0f);
    }

    @Test
    public void fadeFactorDecreasesMonotonically() {
        SleepTimer t = armed(SleepTimer.MIN_15);
        long deadline = T0 + 15 * MIN;
        float prev = 1.0f;
        for (long ms = deadline - 30000; ms <= deadline; ms += 500) {
            float f = t.fadeFactor(ms);
            assertTrue(f <= prev);
            prev = f;
        }
    }

    @Test
    public void expiryBoundaries() {
        SleepTimer t = armed(SleepTimer.MIN_30);
        long deadline = T0 + 30 * MIN;
        assertFalse(t.isExpired(T0));
        assertFalse(t.isExpired(deadline - 1));
        assertTrue(t.isExpired(deadline));
        assertTrue(t.isExpired(deadline + 1));
    }

    @Test
    public void endOfTrackHasNoDeadline() {
        SleepTimer t = armed(SleepTimer.END_OF_TRACK);
        assertTrue(t.isActive());
        assertFalse(t.isExpired(T0 + 1000 * MIN));
        assertEquals(1.0f, t.fadeFactor(T0 + 1000 * MIN), 0f);
        assertEquals(0, t.remainingMs(T0));
        assertEquals(0, t.remainingMinutes(T0));
    }

    @Test
    public void offNeverExpiresAndDoesNotFade() {
        SleepTimer t = new SleepTimer();
        assertFalse(t.isExpired(Long.MAX_VALUE / 2));
        assertEquals(1.0f, t.fadeFactor(Long.MAX_VALUE / 2), 0f);
        assertEquals(0, t.remainingMs(T0));
    }

    @Test
    public void cancelResetsToOff() {
        SleepTimer t = armed(SleepTimer.MIN_45);
        t.cancel();
        assertEquals(SleepTimer.OFF, t.option());
        assertFalse(t.isActive());
        assertFalse(t.isExpired(T0 + 100 * MIN));
        assertEquals(1.0f, t.fadeFactor(T0 + 45 * MIN), 0f);
    }

    @Test
    public void rearmingStartsFromNewNow() {
        SleepTimer t = armed(SleepTimer.MIN_15);
        t.cancel();
        long later = T0 + 7 * MIN;
        while (t.option() != SleepTimer.MIN_15) t.cycle(later);
        assertEquals(15 * MIN, t.remainingMs(later));
    }
}
