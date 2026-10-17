package com.themoon.y1.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Правила викторины «угадай трек по кусочку» без единого android-импорта: колода, ступени отрывка,
 * очки, серия и жизни. Так всю арифметику игры проверяют обычные JVM-тесты, а MainActivity остаётся
 * только склейкой экранов и кнопок.
 */
public final class QuizGame<T> {

    /** Как достать теги трека: игра не знает о модели библиотеки. Пустой жанр = жанра нет. */
    public interface Tags<T> {
        String title(T track);

        String artist(T track);

        String genre(T track);
    }

    /** Итог раунда для экрана с обложкой. */
    public static final class Result {
        public final boolean correct;
        public final int points;
        public final int multiplier;
        public final int stage;

        Result(boolean correct, int points, int multiplier, int stage) {
            this.correct = correct;
            this.points = points;
            this.multiplier = multiplier;
            this.stage = stage;
        }
    }

    public static final int STAGES = 5;
    public static final int OPTIONS = 4;
    public static final int START_LIVES = 3;
    /** Награда после раунда: трек играет дальше, но не дольше этого. */
    public static final int REVEAL_CAP_MS = 30000;

    // Каждая следующая ступень продолжает предыдущую с той же точки старта, поэтому 15 с — самый длинный кусок.
    private static final int[] STAGE_MS = {1000, 3000, 6000, 10000, 15000};
    private static final int[] BASE_POINTS = {100, 70, 50, 30, 15};
    private static final int MAX_MULTIPLIER = 5;
    private static final int STREAK_PER_STEP = 3;
    // Короче 30 с выбирать середину бессмысленно: окно 10..45 % не вмещает 15-секундный кусок.
    private static final long MIN_DURATION_FOR_OFFSET_MS = 30000;

    private final List<T> pool;
    private final Tags<T> tags;
    private final Random rnd;
    private final List<T> deck = new ArrayList<>();
    private int deckPos;

    private T current;
    private final List<T> options = new ArrayList<>();
    private int stage;
    private boolean resolved = true;

    private int score;
    private int streak;
    private int bestStreak;
    private int lives = START_LIVES;

    public QuizGame(List<T> pool, Tags<T> tags, Random rnd) {
        this.pool = new ArrayList<>(pool);
        this.tags = tags;
        this.rnd = rnd;
    }

    public static int stageMs(int stage) {
        return STAGE_MS[stage];
    }

    public static int basePoints(int stage) {
        return BASE_POINTS[stage];
    }

    public static int multiplier(int streak) {
        return Math.min(MAX_MULTIPLIER, 1 + streak / STREAK_PER_STEP);
    }

    /** Точка старта всех ступеней трека: на длинном треке узнаваемая середина, а не вступление. */
    public static long pickStartMs(long durationMs, Random rnd) {
        if (durationMs < MIN_DURATION_FOR_OFFSET_MS) return 0;
        long lo = durationMs * 10 / 100;
        long hi = Math.min(durationMs * 45 / 100, durationMs - STAGE_MS[STAGES - 1]);
        if (hi <= lo) return lo;
        return lo + (long) (rnd.nextDouble() * (hi - lo + 1));
    }

    public static boolean isRecord(int value, int stored) {
        return value > 0 && value > stored;
    }

    /** Следующий трек из колоды: без повторов, пока колода не кончится; затем перетасовка. */
    public T nextTrack() {
        if (deckPos >= deck.size()) reshuffle();
        current = deck.get(deckPos++);
        stage = 0;
        resolved = false;
        buildOptions();
        return current;
    }

    private void reshuffle() {
        T last = current;
        deck.clear();
        deck.addAll(pool);
        Collections.shuffle(deck, rnd);
        // На стыке колод тот же трек мог бы прозвучать дважды подряд.
        if (deck.size() > 1 && deck.get(0) == last) Collections.swap(deck, 0, 1 + rnd.nextInt(deck.size() - 1));
        deckPos = 0;
    }

    private void buildOptions() {
        options.clear();
        options.add(current);
        List<String> usedTitles = new ArrayList<>();
        usedTitles.add(norm(tags.title(current)));

        String genre = norm(tags.genre(current));
        List<T> sameGenre = new ArrayList<>();
        List<T> others = new ArrayList<>();
        for (T t : pool) {
            if (t == current) continue;
            if (!genre.isEmpty() && genre.equals(norm(tags.genre(t)))) sameGenre.add(t);
            else others.add(t);
        }
        Collections.shuffle(sameGenre, rnd);
        Collections.shuffle(others, rnd);
        // Сначала тот же жанр: синтвейв против синтвейва угадывать интереснее, чем против джаза.
        takeDistinct(sameGenre, usedTitles);
        takeDistinct(others, usedTitles);
        Collections.shuffle(options, rnd);
    }

    private void takeDistinct(List<T> candidates, List<String> usedTitles) {
        for (T t : candidates) {
            if (options.size() >= OPTIONS) return;
            String title = norm(tags.title(t));
            // Одинаковый текст строки сделал бы два ответа неотличимыми, даже если исполнители разные.
            if (title.isEmpty() || usedTitles.contains(title)) continue;
            usedTitles.add(title);
            options.add(t);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    public T current() {
        return current;
    }

    public List<T> options() {
        return Collections.unmodifiableList(options);
    }

    public int stage() {
        return stage;
    }

    public boolean isLastStage() {
        return stage == STAGES - 1;
    }

    public boolean isResolved() {
        return resolved;
    }

    /** «Не знаю»: открыть следующий, более длинный кусок. На последней ступени остаётся только сдаться. */
    public boolean unlockNextStage() {
        if (resolved || isLastStage()) return false;
        stage++;
        return true;
    }

    /** Сколько даст верный ответ прямо сейчас, с учётом серии. */
    public int potentialPoints() {
        return BASE_POINTS[stage] * multiplier(streak);
    }

    /** null, если раунд уже решён: повторное нажатие не должно списать вторую жизнь. */
    public Result answer(T picked) {
        if (resolved) return null;
        resolved = true;
        if (picked == current) {
            int mult = multiplier(streak);
            int points = BASE_POINTS[stage] * mult;
            score += points;
            streak++;
            if (streak > bestStreak) bestStreak = streak;
            return new Result(true, points, mult, stage);
        }
        return miss();
    }

    public Result giveUp() {
        if (resolved) return null;
        resolved = true;
        return miss();
    }

    private Result miss() {
        lives = Math.max(0, lives - 1);
        streak = 0;
        return new Result(false, 0, 1, stage);
    }

    public int score() {
        return score;
    }

    public int streak() {
        return streak;
    }

    public int bestStreak() {
        return bestStreak;
    }

    public int lives() {
        return lives;
    }

    public boolean isOver() {
        return lives <= 0;
    }
}
