package com.themoon.y1.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

public final class QuizGameTest {

    static final class Song {
        final String title, artist, genre;

        Song(String title, String artist, String genre) {
            this.title = title;
            this.artist = artist;
            this.genre = genre;
        }

        @Override
        public String toString() {
            return title + " / " + artist + " / " + genre;
        }
    }

    static final QuizGame.Tags<Song> TAGS = new QuizGame.Tags<Song>() {
        @Override public String title(Song s) { return s.title; }
        @Override public String artist(Song s) { return s.artist; }
        @Override public String genre(Song s) { return s.genre; }
    };

    static List<Song> pool(int n) {
        List<Song> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new Song("Track " + i, "Artist " + (i % 3), "Genre " + (i % 2)));
        return list;
    }

    static QuizGame<Song> game(List<Song> pool, long seed) {
        return new QuizGame<>(pool, TAGS, new Random(seed));
    }

    @Test
    public void stageTableMatchesRules() {
        assertEquals(5, QuizGame.STAGES);
        int[] ms = {1000, 3000, 6000, 10000, 15000};
        int[] pts = {100, 70, 50, 30, 15};
        for (int i = 0; i < 5; i++) {
            assertEquals(ms[i], QuizGame.stageMs(i));
            assertEquals(pts[i], QuizGame.basePoints(i));
        }
    }

    @Test
    public void multiplierGrowsEveryThreeAndCapsAtFive() {
        assertEquals(1, QuizGame.multiplier(0));
        assertEquals(1, QuizGame.multiplier(2));
        assertEquals(2, QuizGame.multiplier(3));
        assertEquals(2, QuizGame.multiplier(5));
        assertEquals(3, QuizGame.multiplier(6));
        assertEquals(4, QuizGame.multiplier(9));
        assertEquals(5, QuizGame.multiplier(12));
        assertEquals(5, QuizGame.multiplier(100));
    }

    @Test
    public void startOffsetStaysInWindowForLongTracks() {
        Random rnd = new Random(7);
        long[] durations = {30000, 31000, 45000, 180000, 600000};
        for (long d : durations) {
            for (int i = 0; i < 500; i++) {
                long s = QuizGame.pickStartMs(d, rnd);
                assertTrue("start " + s + " of " + d, s >= d * 10 / 100);
                assertTrue("start " + s + " of " + d, s <= d * 45 / 100);
                assertTrue("15 s must fit after " + s + " of " + d, s + 15000 <= d);
            }
        }
    }

    @Test
    public void startOffsetIsZeroForShortOrUnknownTracks() {
        Random rnd = new Random(1);
        assertEquals(0, QuizGame.pickStartMs(29999, rnd));
        assertEquals(0, QuizGame.pickStartMs(1000, rnd));
        assertEquals(0, QuizGame.pickStartMs(0, rnd));
        assertEquals(0, QuizGame.pickStartMs(-1, rnd));
    }

    @Test
    public void deckPlaysEveryTrackOnceBeforeRepeating() {
        List<Song> p = pool(9);
        QuizGame<Song> g = game(p, 3);
        Set<Song> seen = new HashSet<>();
        for (int i = 0; i < 9; i++) assertTrue(seen.add(g.nextTrack()));
        assertEquals(9, seen.size());
        Set<Song> second = new HashSet<>();
        for (int i = 0; i < 9; i++) assertTrue(second.add(g.nextTrack()));
        assertEquals(9, second.size());
    }

    @Test
    public void reshuffleNeverRepeatsTheLastTrackImmediately() {
        for (long seed = 0; seed < 200; seed++) {
            QuizGame<Song> g = game(pool(4), seed);
            Song prev = null;
            for (int i = 0; i < 40; i++) {
                Song s = g.nextTrack();
                assertNotSame(prev, s);
                prev = s;
            }
        }
    }

    private static void assertNotSame(Object a, Object b) {
        assertFalse("same track twice in a row", a == b);
    }

    @Test
    public void optionsContainCorrectTrackAndDistinctTitles() {
        List<Song> p = pool(12);
        p.add(new Song("track 3", "Other", "Genre 1")); // тот же заголовок другим регистром
        for (long seed = 0; seed < 100; seed++) {
            QuizGame<Song> g = game(p, seed);
            for (int r = 0; r < 13; r++) {
                Song correct = g.nextTrack();
                List<Song> opts = g.options();
                assertEquals(QuizGame.OPTIONS, opts.size());
                assertTrue(opts.contains(correct));
                Set<String> titles = new HashSet<>();
                for (Song s : opts) assertTrue("dup title " + opts, titles.add(s.title.trim().toLowerCase(Locale.ROOT)));
            }
        }
    }

    @Test
    public void distractorsPreferSameGenreWhenEnough() {
        List<Song> p = new ArrayList<>();
        for (int i = 0; i < 4; i++) p.add(new Song("Rock " + i, "R" + i, "Rock"));
        for (int i = 0; i < 10; i++) p.add(new Song("Jazz " + i, "J" + i, "Jazz"));
        for (long seed = 0; seed < 50; seed++) {
            QuizGame<Song> g = game(p, seed);
            for (int r = 0; r < 14; r++) {
                Song correct = g.nextTrack();
                for (Song s : g.options()) assertEquals(correct.genre, s.genre);
            }
        }
    }

    @Test
    public void distractorsFillRandomlyWhenGenreIsRare() {
        List<Song> p = new ArrayList<>();
        p.add(new Song("Only Rock", "R", "Rock"));
        p.add(new Song("Rock Two", "R2", "Rock"));
        for (int i = 0; i < 6; i++) p.add(new Song("Jazz " + i, "J" + i, "Jazz"));
        QuizGame<Song> g = game(p, 5);
        for (int r = 0; r < 8; r++) {
            Song correct = g.nextTrack();
            assertEquals(4, g.options().size());
            if (correct.genre.equals("Rock")) {
                int rock = 0;
                for (Song s : g.options()) if (s.genre.equals("Rock")) rock++;
                assertEquals("both rock tracks preferred", 2, rock);
            }
        }
    }

    @Test
    public void unknownGenreGivesNoPreferenceButStillFourOptions() {
        List<Song> p = new ArrayList<>();
        for (int i = 0; i < 6; i++) p.add(new Song("T" + i, "A", null));
        QuizGame<Song> g = game(p, 9);
        g.nextTrack();
        assertEquals(4, g.options().size());
    }

    @Test
    public void tinyPoolWithDuplicateTitlesShrinksOptionsInsteadOfDuplicating() {
        List<Song> p = new ArrayList<>();
        p.add(new Song("Same", "A", "x"));
        p.add(new Song("same ", "B", "x"));
        p.add(new Song("Other", "C", "x"));
        p.add(new Song("Third", "D", "x"));
        QuizGame<Song> g = game(p, 2);
        for (int r = 0; r < 8; r++) {
            Song correct = g.nextTrack();
            List<Song> opts = g.options();
            assertTrue(opts.contains(correct));
            assertEquals(3, opts.size());
        }
    }

    @Test
    public void unlockAdvancesStagesUntilTheLast() {
        QuizGame<Song> g = game(pool(5), 1);
        g.nextTrack();
        assertEquals(0, g.stage());
        assertFalse(g.isLastStage());
        for (int i = 1; i < 5; i++) {
            assertTrue(g.unlockNextStage());
            assertEquals(i, g.stage());
        }
        assertTrue(g.isLastStage());
        assertFalse(g.unlockNextStage());
        assertEquals(4, g.stage());
    }

    @Test
    public void correctAnswerScoresByStageAndStreakMultiplier() {
        QuizGame<Song> g = game(pool(8), 4);
        Song c = g.nextTrack();
        QuizGame.Result r = g.answer(c);
        assertTrue(r.correct);
        assertEquals(100, r.points);
        assertEquals(1, r.multiplier);
        assertEquals(0, r.stage);
        assertEquals(100, g.score());
        assertEquals(1, g.streak());

        c = g.nextTrack();
        g.unlockNextStage();
        r = g.answer(c);
        assertEquals(70, r.points);
        assertEquals(170, g.score());

        c = g.nextTrack();
        g.unlockNextStage();
        g.unlockNextStage();
        r = g.answer(c);
        assertEquals(50, r.points);
        assertEquals(3, g.streak());

        // серия 3 до ответа: множитель x2
        c = g.nextTrack();
        r = g.answer(c);
        assertEquals(2, r.multiplier);
        assertEquals(200, r.points);
        assertEquals(420, g.score());
        assertEquals(4, g.streak());
        assertEquals(3, g.lives());
    }

    @Test
    public void wrongAnswerCostsLifeAndResetsStreak() {
        QuizGame<Song> g = game(pool(8), 4);
        g.answer(g.nextTrack());
        g.answer(g.nextTrack());
        assertEquals(2, g.streak());
        Song c = g.nextTrack();
        Song wrong = null;
        for (Song s : g.options()) if (s != c) wrong = s;
        assertNotNull(wrong);
        QuizGame.Result r = g.answer(wrong);
        assertFalse(r.correct);
        assertEquals(0, r.points);
        assertEquals(0, g.streak());
        assertEquals(2, g.bestStreak());
        assertEquals(2, g.lives());
        assertEquals(200, g.score());
    }

    @Test
    public void giveUpIsAMiss() {
        QuizGame<Song> g = game(pool(8), 4);
        g.answer(g.nextTrack());
        g.nextTrack();
        QuizGame.Result r = g.giveUp();
        assertFalse(r.correct);
        assertEquals(0, g.streak());
        assertEquals(2, g.lives());
    }

    @Test
    public void resolvedRoundIgnoresSecondAnswer() {
        QuizGame<Song> g = game(pool(8), 4);
        Song c = g.nextTrack();
        assertNotNull(g.answer(c));
        assertNull(g.answer(c));
        assertNull(g.giveUp());
        assertFalse(g.unlockNextStage());
        assertEquals(100, g.score());
    }

    @Test
    public void threeMissesEndTheRun() {
        QuizGame<Song> g = game(pool(8), 4);
        for (int i = 0; i < 3; i++) {
            assertFalse(g.isOver());
            g.nextTrack();
            g.giveUp();
        }
        assertEquals(0, g.lives());
        assertTrue(g.isOver());
    }

    @Test
    public void potentialPointsReflectStageAndMultiplier() {
        QuizGame<Song> g = game(pool(8), 4);
        g.nextTrack();
        assertEquals(100, g.potentialPoints());
        g.unlockNextStage();
        assertEquals(70, g.potentialPoints());
        for (int i = 0; i < 3; i++) g.answer(g.nextTrack());
        g.nextTrack();
        assertEquals(200, g.potentialPoints());
    }

    @Test
    public void recordOnlyWhenStrictlyBetterAndPositive() {
        assertTrue(QuizGame.isRecord(10, 0));
        assertTrue(QuizGame.isRecord(11, 10));
        assertFalse(QuizGame.isRecord(10, 10));
        assertFalse(QuizGame.isRecord(0, 0));
        assertFalse(QuizGame.isRecord(5, 10));
    }

    @Test
    public void currentTrackIsStableWithinRound() {
        QuizGame<Song> g = game(pool(6), 11);
        Song c = g.nextTrack();
        assertSame(c, g.current());
        g.unlockNextStage();
        assertSame(c, g.current());
        assertNotEquals(-1, g.options().indexOf(c));
    }
}
