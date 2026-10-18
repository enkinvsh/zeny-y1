package com.themoon.y1.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.themoon.y1.managers.ClickSynth.ClickKind;
import com.themoon.y1.managers.ClickSynth.ClickVariant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClickSynthTest {

    private static final int[] RATES = { 44100, 48000 };
    private static final double[] AMPLITUDE = { 0.20, 0.40, 0.70 };

    private static List<ClickVariant> rendered() {
        List<ClickVariant> list = new ArrayList<ClickVariant>();
        for (ClickVariant v : ClickVariant.values()) {
            if (v.isRendered()) list.add(v);
        }
        return list;
    }

    private static int peak(short[] pcm) {
        int p = 0;
        for (short s : pcm) p = Math.max(p, Math.abs((int) s));
        return p;
    }

    @Test
    public void renderedSetIsFourVariants() {
        assertEquals(4, rendered().size());
    }

    @Test
    public void edgesAreExactlyZero() {
        for (int rate : RATES) {
            for (ClickVariant v : rendered()) {
                for (ClickKind k : ClickKind.values()) {
                    for (int level = 1; level <= 3; level++) {
                        short[] pcm = ClickSynth.render(v, k, level, rate);
                        String tag = v + "/" + k + "/" + level + "/" + rate;
                        assertEquals(tag + " first", 0, pcm[0]);
                        assertEquals(tag + " last", 0, pcm[pcm.length - 1]);
                    }
                }
            }
        }
    }

    @Test
    public void peakMatchesLevelAmplitude() {
        for (int rate : RATES) {
            for (ClickVariant v : rendered()) {
                for (ClickKind k : ClickKind.values()) {
                    for (int level = 1; level <= 3; level++) {
                        short[] pcm = ClickSynth.render(v, k, level, rate);
                        double expected = 32767.0 * AMPLITUDE[level - 1];
                        assertEquals(v + "/" + k + "/" + level + "/" + rate, expected, peak(pcm), 1.0);
                    }
                }
            }
        }
    }

    @Test
    public void meanStaysNearZero() {
        for (int rate : RATES) {
            for (ClickVariant v : rendered()) {
                for (ClickKind k : ClickKind.values()) {
                    for (int level = 1; level <= 3; level++) {
                        short[] pcm = ClickSynth.render(v, k, level, rate);
                        double sum = 0;
                        for (short s : pcm) sum += s;
                        double mean = sum / pcm.length;
                        assertTrue(v + "/" + k + "/" + level + "/" + rate + " mean=" + mean,
                                Math.abs(mean) <= 0.03 * peak(pcm));
                    }
                }
            }
        }
    }

    @Test
    public void lengthIsBetweenOneAndAHalfAndHundredTwentyMs() {
        for (int rate : RATES) {
            for (ClickVariant v : rendered()) {
                for (ClickKind k : ClickKind.values()) {
                    short[] pcm = ClickSynth.render(v, k, 2, rate);
                    double ms = pcm.length * 1000.0 / rate;
                    assertTrue(v + "/" + k + "/" + rate + " ms=" + ms, ms >= 1.5 && ms <= 120.0);
                }
            }
        }
    }

    @Test
    public void tailOfLongSoundsIsBelowMinusSixtyDb() {
        for (int rate : RATES) {
            int fadeOut = (int) Math.round(0.0003 * rate);
            for (ClickVariant v : rendered()) {
                for (ClickKind k : ClickKind.values()) {
                    for (int level = 1; level <= 3; level++) {
                        short[] pcm = ClickSynth.render(v, k, level, rate);
                        double ms = pcm.length * 1000.0 / rate;
                        if (ms < 12.0) continue;
                        int from = (int) (pcm.length * 0.8);
                        int to = pcm.length - fadeOut;
                        double acc = 0;
                        for (int i = from; i < to; i++) acc += (double) pcm[i] * pcm[i];
                        double rms = Math.sqrt(acc / (to - from));
                        assertTrue(v + "/" + k + "/" + level + "/" + rate + " rms=" + rms + " peak=" + peak(pcm),
                                rms <= peak(pcm) * 0.001);
                    }
                }
            }
        }
    }

    @Test
    public void renderIsDeterministic() {
        for (ClickVariant v : rendered()) {
            for (ClickKind k : ClickKind.values()) {
                assertTrue(v + "/" + k,
                        Arrays.equals(ClickSynth.render(v, k, 2, 44100), ClickSynth.render(v, k, 2, 44100)));
            }
        }
    }

    @Test
    public void pressDiffersFromStep() {
        for (ClickVariant v : rendered()) {
            assertFalse(v.toString(), Arrays.equals(ClickSynth.render(v, ClickKind.STEP, 2, 44100),
                    ClickSynth.render(v, ClickKind.PRESS, 2, 44100)));
        }
    }

    @Test
    public void louderLevelHasHigherPeak() {
        for (ClickVariant v : rendered()) {
            for (ClickKind k : ClickKind.values()) {
                int p1 = peak(ClickSynth.render(v, k, 1, 44100));
                int p2 = peak(ClickSynth.render(v, k, 2, 44100));
                int p3 = peak(ClickSynth.render(v, k, 3, 44100));
                assertTrue(v + "/" + k, p3 > p2 && p2 > p1);
            }
        }
    }

    @Test
    public void rockboxStepIsEightWholeCyclesAtFourKilohertz() {
        for (int rate : RATES) {
            short[] pcm = ClickSynth.render(ClickVariant.ROCKBOX, ClickKind.STEP, 2, rate);
            // Восемь периодов прямоугольника дают 8 положительных и 8 отрицательных полуволн, то есть
            // 15 смен знака между ненулевыми отсчётами (нули у краёв от фейдов не считаются).
            int changes = 0;
            int prev = 0;
            for (short s : pcm) {
                if (s == 0) continue;
                int sign = s > 0 ? 1 : -1;
                if (prev != 0 && sign != prev) changes++;
                prev = sign;
            }
            assertEquals("rate " + rate, 15, changes);
            double ms = pcm.length * 1000.0 / rate;
            assertEquals("rate " + rate, 2.0, ms, 0.1);
        }
    }

    @Test
    public void variantsProduceDifferentBuffers() {
        List<ClickVariant> all = rendered();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                for (ClickKind k : ClickKind.values()) {
                    assertFalse(all.get(i) + " vs " + all.get(j) + "/" + k,
                            Arrays.equals(ClickSynth.render(all.get(i), k, 2, 44100),
                                    ClickSynth.render(all.get(j), k, 2, 44100)));
                }
            }
        }
    }

    @Test
    public void offAndSystemAreRejected() {
        for (ClickVariant v : new ClickVariant[] { ClickVariant.OFF, ClickVariant.SYSTEM }) {
            assertFalse(v.isRendered());
            try {
                ClickSynth.render(v, ClickKind.STEP, 2, 44100);
                fail(v + " must be rejected");
            } catch (IllegalArgumentException expected) {
                // нужное исключение
            }
        }
    }

    @Test
    public void levelOutsideOneToThreeIsRejected() {
        for (int level : new int[] { 0, 4, -1 }) {
            try {
                ClickSynth.render(ClickVariant.CLASSIC, ClickKind.STEP, level, 44100);
                fail("level " + level + " must be rejected");
            } catch (IllegalArgumentException expected) {
                // нужное исключение
            }
        }
    }

    @Test
    public void nullKindAndAbsurdSampleRateAreRejected() {
        try {
            ClickSynth.render(ClickVariant.CLASSIC, null, 2, 44100);
            fail("null kind must be rejected");
        } catch (IllegalArgumentException expected) {
            // нужное исключение
        }
        try {
            ClickSynth.render(ClickVariant.CLASSIC, ClickKind.STEP, 2, 100);
            fail("absurd sample rate must be rejected");
        } catch (IllegalArgumentException expected) {
            // нужное исключение
        }
    }

    @Test
    public void idsRoundTripAndUnknownIdIsNull() {
        for (ClickVariant v : ClickVariant.values()) {
            assertSame(v, ClickVariant.fromId(v.id()));
        }
        assertNull(ClickVariant.fromId("nonsense"));
        assertNull(ClickVariant.fromId(null));
        assertEquals("off", ClickVariant.OFF.id());
        assertEquals("system", ClickVariant.SYSTEM.id());
        assertEquals("mech", ClickVariant.MECH.id());
        // Убранные варианты (мягкий, капля, стекло): их сохранённый id не должен оживать.
        assertNull(ClickVariant.fromId("soft"));
        assertNull(ClickVariant.fromId("drop"));
        assertNull(ClickVariant.fromId("glass"));
    }
}
