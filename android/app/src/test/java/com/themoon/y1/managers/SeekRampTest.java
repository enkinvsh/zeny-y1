package com.themoon.y1.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SeekRampTest {

    @Test
    public void firstSecondOfHoldStepsFiveSeconds() {
        assertEquals(5000, SeekRamp.stepForHoldMs(0));
        assertEquals(5000, SeekRamp.stepForHoldMs(600));
        assertEquals(5000, SeekRamp.stepForHoldMs(1199));
    }

    @Test
    public void middleOfHoldStepsTenSeconds() {
        assertEquals(10000, SeekRamp.stepForHoldMs(1200));
        assertEquals(10000, SeekRamp.stepForHoldMs(2999));
    }

    @Test
    public void longHoldStepsTwentySeconds() {
        assertEquals(20000, SeekRamp.stepForHoldMs(3000));
        assertEquals(20000, SeekRamp.stepForHoldMs(60000));
    }

    @Test
    public void negativeHoldIsTreatedAsJustPressed() {
        assertEquals(5000, SeekRamp.stepForHoldMs(-50));
    }

    @Test
    public void stepIsDueOnlyAfterTick() {
        assertFalse(SeekRamp.isStepDue(1000, 1000 - 249));
        assertTrue(SeekRamp.isStepDue(1000, 1000 - 250));
        assertTrue(SeekRamp.isStepDue(1000, 0));
    }

    @Test
    public void targetMovesForwardAndBackByStep() {
        assertEquals(15000, SeekRamp.nextTarget(10000, true, 0, 180000));
        assertEquals(5000, SeekRamp.nextTarget(10000, false, 0, 180000));
        assertEquals(30000, SeekRamp.nextTarget(10000, true, 3500, 180000));
    }

    @Test
    public void targetIsClampedToTrack() {
        assertEquals(0, SeekRamp.nextTarget(3000, false, 0, 180000));
        assertEquals(180000, SeekRamp.nextTarget(178000, true, 0, 180000));
    }

    @Test
    public void unknownDurationDoesNotClampForward() {
        assertEquals(15000, SeekRamp.nextTarget(10000, true, 0, 0));
    }
}
