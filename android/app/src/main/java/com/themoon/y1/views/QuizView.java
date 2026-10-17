package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import com.themoon.y1.managers.QuizGame;

/**
 * Все экраны викторины одним плоским вью: старт, раунд, ответ и итоги. Координаты те же, что в
 * утверждённых макетах (480x360, 1 dp = 1 px на Y1), от левого верхнего угла экрана.
 *
 * Один вью вместо десятков TextView и ImageView: на Cortex-A7 с Android 4.2 каждый вью в иерархии
 * стоит измерения и раскладки, а тут экран рисуется одним проходом и только когда что-то изменилось.
 * Кисти, прямоугольники и метрики шрифта созданы заранее, onDraw ничего не выделяет; строки (в том числе
 * обрезанные многоточием и разбитые на строки) готовятся в сеттерах, а не при рисовании.
 *
 * Палитра фиксирована (брендбук zeny) и от темы не зависит: цвета светлых тем пропали бы на тёмном поле.
 * От темы берутся только шрифты, их задаёт setTypefaces до первого сеттера с текстом.
 */
public final class QuizView extends View {

    public static final int SCREEN_START = 0;
    public static final int SCREEN_ROUND = 1;
    public static final int SCREEN_REVEAL = 2;
    public static final int SCREEN_OVER = 3;

    public static final int ROW_NORMAL = 0;
    public static final int ROW_CORRECT = 1;
    public static final int ROW_WRONG = 2;

    /** Центр колеса на выбранном элементе: index = номер ряда ответа, OPTIONS = «Не знаю». */
    public interface Listener {
        void onActivate(int screen, int index);
    }

    private static final int INK = 0xFF181E26;
    private static final int LIFE = 0xFF00DB4D;
    private static final int ON_LIFE = 0xFF08130B;
    private static final int AIR = 0xFFEFEBE2;
    private static final int EARTH = 0xFFA4A3A0;
    private static final int WATER = 0xFF334351;
    private static final int POSSIBLE = 0xFFF7CE46;
    private static final int FIRE = 0xFF971D1C;

    private static final int OPTIONS = QuizGame.OPTIONS;
    private static final int LIVES = QuizGame.START_LIVES;

    private final float d;
    private final TextPaint pTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pBold = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pReg = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final RectF rect = new RectF();
    private final RectF coverRect = new RectF();
    private final Matrix coverMatrix = new Matrix();
    private final HugeGlyph heartFull = new HugeGlyph(HugeGlyph.HEART, FIRE, 0f, true);
    private final HugeGlyph heartLost = new HugeGlyph(HugeGlyph.HEART, EARTH, 0f, false);
    private final HugeGlyph flash = new HugeGlyph(HugeGlyph.FLASH, POSSIBLE, 0f, false);
    private final int glyphSize;

    private Listener listener;
    private int screen = SCREEN_START;
    private int selected;
    private boolean locked;

    // HUD.
    private int lives = LIVES;
    private String scoreText = "0";
    private int streak;
    private String streakText = "";
    private String multText;
    private float multX;

    // Раунд.
    private final String[] titles = new String[OPTIONS];
    private final String[] artists = new String[OPTIONS];
    private final int[] rowStates = new int[OPTIONS];
    private String skipLabel = "";
    private String skipMore;
    private String points = "";

    // Ответ.
    private boolean revealCorrect;
    private String resultText = "";
    private String revealTitle1 = "";
    private String revealTitle2;
    private String revealArtist = "";
    private boolean hasCover;

    // Старт и итоги.
    private String headline = "";
    private String hint;
    private String labelA = "";
    private String valueA = "";
    private String labelB = "";
    private String valueB = "";
    private String recordText;
    private String buttonText = "";

    public QuizView(Context context) {
        this(context, null);
    }

    public QuizView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        glyphSize = Math.round(22 * d);
        heartFull.setBounds(0, 0, glyphSize, glyphSize);
        heartLost.setBounds(0, 0, glyphSize, glyphSize);
        flash.setBounds(0, 0, glyphSize, glyphSize);
        coverRect.set(16 * d, 92 * d, (16 + 168) * d, (92 + 168) * d);
        setFocusable(false);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Заголовки (Unbounded), полужирный и обычный текст (Onest): те же шрифты, что у остальных экранов темы. */
    public void setTypefaces(Typeface title, Typeface bold, Typeface regular) {
        pTitle.setTypeface(title);
        pBold.setTypeface(bold);
        pReg.setTypeface(regular);
        invalidate();
    }

    // ---------------------------------------------------------------- выбор и ввод

    private int itemCount() {
        return screen == SCREEN_ROUND ? OPTIONS + 1 : 1;
    }

    /** Колесо по элементам экрана по кругу. */
    public boolean moveSelection(int dir) {
        int n = itemCount();
        int next = ((selected + dir) % n + n) % n;
        if (next == selected) return false;
        selected = next;
        invalidate();
        return true;
    }

    /** Центр колеса: сообщить, что выбранный элемент нажат. */
    public void activate() {
        if (listener != null) listener.onActivate(screen, selected);
    }

    /** Пока подсвечивается верный ответ, капсула фокуса не рисуется и колесо не двигает выбор. */
    public void setLocked(boolean value) {
        if (locked == value) return;
        locked = value;
        invalidate();
    }

    public int getScreen() {
        return screen;
    }

    // ---------------------------------------------------------------- HUD

    /** Жизни, счёт и серия; серия видна только при streak > 0, множитель только при mult > 1. */
    public void setHud(int livesLeft, String score, int streakNow, int mult) {
        lives = livesLeft;
        scoreText = score;
        streak = streakNow;
        if (streakNow > 0) {
            streakText = String.valueOf(streakNow);
            if (mult > 1) {
                multText = "x" + mult;
                pTitle.setTextSize(17 * d);
                // Множитель встаёт сразу за числом серии; число измеряется здесь, а не в onDraw.
                multX = 172 * d + pTitle.measureText(streakText);
            } else {
                multText = null;
            }
        }
        invalidate();
    }

    // ---------------------------------------------------------------- экраны

    private void enter(int newScreen) {
        screen = newScreen;
        selected = 0;
        locked = false;
    }

    public void showStart(String title, String hintLine, String bestScoreLabel, String bestScore,
                          String bestStreakLabel, String bestStreak, String button) {
        enter(SCREEN_START);
        headline = title;
        pReg.setTextSize(15 * d);
        hint = TextUtils.ellipsize(hintLine, pReg, 448 * d, TextUtils.TruncateAt.END).toString();
        labelA = bestScoreLabel;
        valueA = bestScore;
        labelB = bestStreakLabel;
        valueB = bestStreak;
        recordText = null;
        buttonText = button;
        invalidate();
    }

    public void showOver(String title, String scoreLabel, String score, String bestStreakLabel,
                         String bestStreak, String record, String button) {
        enter(SCREEN_OVER);
        headline = title;
        hint = null;
        labelA = scoreLabel;
        valueA = score;
        labelB = bestStreakLabel;
        valueB = bestStreak;
        recordText = record;
        buttonText = button;
        invalidate();
    }

    /** Новый раунд: четыре ответа и «Не знаю»; текст «Не знаю» и очки задаёт setRoundInfo. */
    public void showRound(String[] answerTitles, String[] answerArtists) {
        enter(SCREEN_ROUND);
        pBold.setTextSize(16 * d);
        pReg.setTextSize(12 * d);
        for (int i = 0; i < OPTIONS; i++) {
            titles[i] = TextUtils.ellipsize(answerTitles[i], pBold, 436 * d, TextUtils.TruncateAt.END).toString();
            artists[i] = TextUtils.ellipsize(answerArtists[i], pReg, 436 * d, TextUtils.TruncateAt.END).toString();
            rowStates[i] = ROW_NORMAL;
        }
        invalidate();
    }

    /**
     * Подписи ряда «Не знаю» и очки на кону для текущей ступени.
     * @param more подсказка справа («ещё 3 с») или null на последней ступени
     */
    public void setRoundInfo(String skip, String more, String pointsAtStake) {
        skipLabel = skip;
        skipMore = more;
        points = pointsAtStake;
        invalidate();
    }

    public void setRowState(int index, int state) {
        rowStates[index] = state;
        invalidate();
    }

    public void showReveal(boolean correct, String result, String title, String artist, String button) {
        enter(SCREEN_REVEAL);
        revealCorrect = correct;
        resultText = result;
        buttonText = button;
        pBold.setTextSize(20 * d);
        wrapTitle(title, 252 * d);
        pBold.setTextSize(15 * d);
        revealArtist = TextUtils.ellipsize(artist, pBold, 252 * d, TextUtils.TruncateAt.END).toString();
        hasCover = false;
        invalidate();
    }

    /** Две строки названия: перенос по пробелу, остаток второй строки обрезается многоточием. */
    private void wrapTitle(String text, float width) {
        int n = pBold.breakText(text, true, width, null);
        if (n >= text.length()) {
            revealTitle1 = text;
            revealTitle2 = null;
            return;
        }
        int cut = text.lastIndexOf(' ', n);
        int next;
        if (cut > 0) {
            next = cut + 1;
        } else {
            cut = n;
            next = n;
        }
        revealTitle1 = text.substring(0, cut);
        revealTitle2 = TextUtils.ellipsize(text.substring(next), pBold, width, TextUtils.TruncateAt.END).toString();
    }

    /** Обложка 168x168 со скруглением 26: центр-кроп, бесшовная заливка кисти без клипа. */
    public void setCover(Bitmap bmp) {
        if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return;
        BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float side = coverRect.width();
        float scale = Math.max(side / bmp.getWidth(), side / bmp.getHeight());
        coverMatrix.setScale(scale, scale);
        coverMatrix.postTranslate(coverRect.left + (side - bmp.getWidth() * scale) / 2f,
                coverRect.top + (side - bmp.getHeight() * scale) / 2f);
        shader.setLocalMatrix(coverMatrix);
        coverPaint.setShader(shader);
        hasCover = true;
        invalidate();
    }

    // ---------------------------------------------------------------- рисование

    private float midBaseline(float cy, Paint p) {
        p.getFontMetrics(fm);
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    private float topBaseline(float top, Paint p) {
        p.getFontMetrics(fm);
        return top - fm.ascent;
    }

    /** Строка с центром по вертикали в cy; align: LEFT, CENTER или RIGHT относительно x. */
    private void mid(Canvas c, String s, float x, float cy, TextPaint p, float sizeDp, int color, Paint.Align align) {
        p.setTextSize(sizeDp * d);
        p.setColor(color);
        p.setTextAlign(align);
        c.drawText(s, x * d, midBaseline(cy * d, p), p);
    }

    private void capsule(Canvas c, float l, float t, float r, float b, float radius, int color) {
        fill.setColor(color);
        rect.set(l * d, t * d, r * d, b * d);
        c.drawRoundRect(rect, radius * d, radius * d, fill);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(INK);
        switch (screen) {
            case SCREEN_ROUND:
                drawHud(c);
                drawRound(c);
                break;
            case SCREEN_REVEAL:
                drawHud(c);
                drawReveal(c);
                break;
            case SCREEN_OVER:
                drawOver(c);
                break;
            default:
                drawStart(c);
                break;
        }
    }

    private void drawHud(Canvas c) {
        for (int i = 0; i < LIVES; i++) {
            c.save();
            c.translate((28 + 30 * i) * d - glyphSize / 2f, 58 * d - glyphSize / 2f);
            (i < lives ? heartFull : heartLost).draw(c);
            c.restore();
        }
        mid(c, scoreText, 464, 58, pTitle, 26, POSSIBLE, Paint.Align.RIGHT);
        if (streak > 0) {
            c.save();
            c.translate(150 * d - glyphSize / 2f, 58 * d - glyphSize / 2f);
            flash.draw(c);
            c.restore();
            mid(c, streakText, 166, 58, pTitle, 17, POSSIBLE, Paint.Align.LEFT);
            if (multText != null) {
                pBold.setTextSize(14 * d);
                pBold.setColor(EARTH);
                pBold.setTextAlign(Paint.Align.LEFT);
                c.drawText(multText, multX, midBaseline(58 * d, pBold), pBold);
            }
        }
    }

    private void drawRound(Canvas c) {
        mid(c, points, 464, 118, pBold, 14, POSSIBLE, Paint.Align.RIGHT);
        for (int i = 0; i <= OPTIONS; i++) {
            float y = 128 + 46 * i;
            boolean skip = i == OPTIONS;
            int state = skip ? ROW_NORMAL : rowStates[i];
            boolean focus = selected == i && !locked;
            int titleColor;
            int subColor;
            if (state == ROW_CORRECT || (state == ROW_NORMAL && focus)) {
                capsule(c, -30, y, 468, y + 44, 22, LIFE);
                titleColor = ON_LIFE;
                subColor = ON_LIFE;
            } else if (state == ROW_WRONG) {
                capsule(c, -30, y, 468, y + 44, 22, FIRE);
                titleColor = AIR;
                subColor = AIR;
            } else if (locked) {
                titleColor = WATER;
                subColor = WATER;
            } else {
                titleColor = skip ? EARTH : AIR;
                subColor = EARTH;
            }
            if (skip) {
                mid(c, skipLabel, 16, y + 22, pBold, 16, titleColor, Paint.Align.LEFT);
                if (skipMore != null) mid(c, skipMore, 452, y + 22, pBold, 14, titleColor, Paint.Align.RIGHT);
            } else {
                mid(c, titles[i], 16, y + 16, pBold, 16, titleColor, Paint.Align.LEFT);
                mid(c, artists[i], 16, y + 33, pReg, 12, subColor, Paint.Align.LEFT);
            }
        }
    }

    private void drawReveal(Canvas c) {
        if (hasCover) c.drawRoundRect(coverRect, 26 * d, 26 * d, coverPaint);
        mid(c, resultText, 208, 118, pTitle, 38, revealCorrect ? LIFE : AIR, Paint.Align.LEFT);
        pBold.setTextSize(20 * d);
        pBold.setColor(AIR);
        pBold.setTextAlign(Paint.Align.LEFT);
        float y = 156;
        c.drawText(revealTitle1, 208 * d, topBaseline(y * d, pBold), pBold);
        y += 27;
        if (revealTitle2 != null) {
            c.drawText(revealTitle2, 208 * d, topBaseline(y * d, pBold), pBold);
            y += 27;
        }
        pBold.setTextSize(15 * d);
        pBold.setColor(EARTH);
        c.drawText(revealArtist, 208 * d, topBaseline((y + 4) * d, pBold), pBold);
        button(c, 288, 336, 24, 18);
    }

    /** Зелёная капсула 288 dp по центру экрана; selected всегда 0, поэтому она всегда в фокусе. */
    private void button(Canvas c, float top, float bottom, float radius, float textSp) {
        capsule(c, 96, top, 384, bottom, radius, LIFE);
        mid(c, buttonText, 240, (top + bottom) / 2f, pBold, textSp, ON_LIFE, Paint.Align.CENTER);
    }

    private void drawStats(Canvas c, float labelY, float valueY) {
        mid(c, labelA, 140, labelY, pReg, 14, EARTH, Paint.Align.CENTER);
        mid(c, valueA, 140, valueY, pTitle, 38, POSSIBLE, Paint.Align.CENTER);
        mid(c, labelB, 340, labelY, pReg, 14, EARTH, Paint.Align.CENTER);
        mid(c, valueB, 340, valueY, pTitle, 38, AIR, Paint.Align.CENTER);
    }

    private void drawStart(Canvas c) {
        mid(c, headline, 240, 100, pTitle, 30, AIR, Paint.Align.CENTER);
        mid(c, hint, 240, 138, pReg, 15, EARTH, Paint.Align.CENTER);
        drawStats(c, 186, 226);
        button(c, 280, 328, 24, 18);
    }

    private void drawOver(Canvas c) {
        mid(c, headline, 240, 90, pTitle, 28, AIR, Paint.Align.CENTER);
        drawStats(c, 154, 194);
        if (recordText != null) mid(c, recordText, 140, 228, pBold, 14, POSSIBLE, Paint.Align.CENTER);
        button(c, 270, 314, 22, 17);
    }
}
