package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import com.themoon.y1.reader.BookIndex;
import com.themoon.y1.reader.ReaderEngine;
import com.themoon.y1.reader.WordTimer;

/**
 * Экран «Читалки» одним плоским вью: слово с буквой-якорем (RSVP), абзац на паузе, капсула
 * прогресса и скорость. Координаты те же, что в утверждённых макетах work/np-variants/reader-*.png
 * (480x360, 1 dp = 1 px на Y1), от левого верхнего угла экрана; строку состояния и заголовок
 * «Читалка» рисует приложение, вью их не трогает.
 *
 * Один вью вместо десятков TextView: на Cortex-A7 с Android 4.2 иерархия вью стоит измерения и
 * раскладки на каждое слово, а при 600 словах в минуту слово меняется каждые 100 мс. Раскладка слова и
 * абзаца считается в сеттерах (когда значение действительно изменилось), onDraw ничего не выделяет и
 * не измеряет. Строки чисел собираются в заготовленных StringBuilder и только при смене значения.
 *
 * Палитра фиксирована (брендбук zeny) и от темы не зависит. От темы берутся шрифты:
 * {@link #setTypefaces} (Unbounded для крупного числа, Onest для остального).
 */
public final class RsvpView extends View implements ReaderEngine.SplitOracle {

    public static final int MODE_READING = 0;
    public static final int MODE_PAUSED = 1;

    private static final int INK = 0xFF181E26;
    private static final int LIFE = 0xFF00DB4D;
    private static final int AIR = 0xFFEFEBE2;
    private static final int EARTH = 0xFFA4A3A0;
    private static final int WATER = 0xFF334351;
    /** Прочитанная часть абзаца: earth на 70% поверх ink, как в макете. */
    private static final int READ_DIM = mix(EARTH, INK, 0.7f);

    // Макет, 480x360.
    private static final float MARGIN_L = 16f;
    private static final float MARGIN_R = 464f;
    private static final float ANCHOR_X = 196f;
    private static final float WORD_CY = 172f;
    private static final float TITLE_CY = 56f;
    private static final float BAR_CY = 300f;
    private static final float TEXT_CY = 328f;
    private static final float PAR_TOP = 100f;
    private static final float PAR_LINE = 30f;
    private static final float PAR_PX = 19f;
    private static final int PAR_LINES = 6;
    private static final float WORD_PX = 52f;
    private static final float THIN_BAR = 8f;
    private static final float THICK_BAR = 18f;
    private static final float HANDLE_R = 14f;
    /** Ширина названия главы, пока справа ничего нет: от поля до поля. */
    private static final float TITLE_FULL_W = MARGIN_R - MARGIN_L;
    /** С крупным числом скорости справа название ужимается, чтобы не наезжать на него. */
    private static final float TITLE_UNDER_OVERLAY_W = 330f;
    /** Скорость показывается столько мс, потом число исчезает. */
    private static final long OVERLAY_MS = 1000;
    /** Абзац без разметки (вся глава одним куском) раскладывается окном: полный разбор стоил бы секунд. */
    private static final int PAR_WINDOW = 300;
    private static final long MARQUEE_STEP_MS = 100;
    private static final long MARQUEE_HOLD_MS = 1500;
    /** Скорость бегущей строки названия: px в мс (40 px/с читается без усилий). */
    private static final float MARQUEE_SPEED = 0.04f;

    private final float d;
    private final TextPaint pTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pBold = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pReg = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final RectF rect = new RectF();

    private BookIndex book;
    private int mode = MODE_READING;
    private int word;
    private int part;
    private float wordPx = WORD_PX;

    // Раскладка слова (в пикселях экрана), считается в сеттерах.
    private int wordFrom;
    private int wordTo;
    private int pivot;
    private float wordSize;
    private float xLeftEnd;
    private float xPivot;
    private float xRightStart;

    // Раскладка абзаца.
    private int parFrom = -1;
    private int parTo = -1;
    private final float[] parX = new float[PAR_WINDOW + 1];
    private final float[] parW = new float[PAR_WINDOW + 1];
    private final int[] parLine = new int[PAR_WINDOW + 1];
    private final int[] lineFirst = new int[PAR_WINDOW + 2];
    private int lineCount;
    private int firstLine;

    // Прогресс и скорость.
    private int percent;
    private int minutes = -1;
    private int wpm;
    private String percentText = "";
    private String wpmText = "";
    private String remainText = "";
    private String overlayNumber = "";
    private boolean overlayVisible;
    private final StringBuilder sb = new StringBuilder(32);
    private String wpmLabel = "слов в минуту";
    private String leftLabel = "ещё";
    private String hourUnit = "ч";
    private String minuteUnit = "мин";

    // Название главы и подпись «прошлый раз».
    private String chapterTitle = "";
    private float titleWidth;
    private String resumeCaption;
    private float captionWidth;
    private boolean marqueeRunning;
    private long marqueeStart;

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            overlayVisible = false;
            updateMarquee();
            invalidate();
        }
    };

    private final Runnable marqueeStep = new Runnable() {
        @Override
        public void run() {
            marqueeRunning = false;
            if (!titleOverflows()) return;
            invalidate();
            marqueeRunning = true;
            postDelayed(this, MARQUEE_STEP_MS);
        }
    };

    public RsvpView(Context context) {
        this(context, null);
    }

    public RsvpView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        setFocusable(false);
    }

    private static int mix(int fg, int bg, float a) {
        int r = Math.round(((fg >> 16) & 0xFF) * a + ((bg >> 16) & 0xFF) * (1f - a));
        int g = Math.round(((fg >> 8) & 0xFF) * a + ((bg >> 8) & 0xFF) * (1f - a));
        int b = Math.round((fg & 0xFF) * a + (bg & 0xFF) * (1f - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- сеттеры

    /** Заголовки (Unbounded), полужирный и обычный текст (Onest): те же шрифты, что у остальных экранов темы. */
    public void setTypefaces(Typeface title, Typeface bold, Typeface regular) {
        pTitle.setTypeface(title);
        pBold.setTypeface(bold);
        pReg.setTypeface(regular);
        measureTitle();
        measureCaption();
        layoutWord();
        parFrom = -1;
        ensureParagraph();
        updateMarquee();
        invalidate();
    }

    /** Подписи под капсулой; по умолчанию русские, приложение передаёт переведённые. */
    public void setLabels(String wordsPerMinute, String left, String hours, String minutesUnit) {
        wpmLabel = wordsPerMinute;
        leftLabel = left;
        hourUnit = hours;
        minuteUnit = minutesUnit;
        int m = minutes;
        int w = wpm;
        minutes = -1;
        wpm = -1;
        setProgress(percent, m, w);
    }

    public void setBook(BookIndex index) {
        book = index;
        word = 0;
        part = 0;
        parFrom = -1;
        layoutWord();
        ensureParagraph();
        invalidate();
    }

    public void setWord(int i) {
        setWord(i, 0);
    }

    /** part: 0 или 1, какая половина разрезанного слова показана (для обычных слов всегда 0). */
    public void setWord(int i, int partOfWord) {
        if (i == word && partOfWord == part) return;
        word = i;
        part = partOfWord;
        if (mode == MODE_READING) layoutWord();
        else ensureParagraph();
        invalidate();
    }

    public void setMode(int newMode) {
        if (newMode == mode) return;
        mode = newMode;
        if (mode == MODE_READING) layoutWord();
        else ensureParagraph();
        updateMarquee();
        invalidate();
    }

    /** Размер слова в px макета: 44, 52 или 60. */
    public void setWordSize(float px) {
        if (px == wordPx) return;
        wordPx = px;
        layoutWord();
        invalidate();
    }

    /** Абзац для паузы: слова от first до last включительно. Обычно выбирается сам по текущему слову. */
    public void setParagraph(int first, int last) {
        layoutParagraph(first, last);
        placeParagraph();
        invalidate();
    }

    public void setChapterTitle(String title) {
        String t = title == null ? "" : title;
        if (t.equals(chapterTitle)) return;
        chapterTitle = t;
        measureTitle();
        updateMarquee();
        invalidate();
    }

    /** «Прошлый раз: сегодня 02:10» справа в строке названия; null убирает подпись (после старта). */
    public void setResumeCaption(String text) {
        resumeCaption = text;
        measureCaption();
        updateMarquee();
        invalidate();
    }

    /**
     * Процент прочитанного, оставшиеся минуты (0 и меньше: оценки нет, подпись пустая) и скорость.
     * Перерисовывает вью, только если что-то из трёх изменилось.
     */
    public void setProgress(int percentRead, int remainingMinutes, int wordsPerMinute) {
        boolean changed = false;
        int p = Math.max(0, Math.min(percentRead, 100));
        if (p != percent || percentText.length() == 0) {
            percent = p;
            sb.setLength(0);
            percentText = sb.append(p).append('%').toString();
            changed = true;
        }
        if (remainingMinutes != minutes || remainText.length() == 0 && remainingMinutes > 0) {
            minutes = remainingMinutes;
            remainText = remainingMinutes <= 0 ? "" : formatRemaining(remainingMinutes);
            changed = true;
        }
        if (wordsPerMinute != wpm || wpmText.length() == 0) {
            wpm = wordsPerMinute;
            sb.setLength(0);
            sb.append(wordsPerMinute);
            overlayNumber = sb.toString();
            wpmText = sb.append(' ').append(wpmLabel).toString();
            changed = true;
        }
        if (changed) invalidate();
    }

    private String formatRemaining(int total) {
        sb.setLength(0);
        sb.append(leftLabel).append(' ');
        int h = total / 60;
        int m = total % 60;
        if (h > 0) sb.append(h).append(' ').append(hourUnit).append(' ');
        if (m > 0 || h == 0) sb.append(m).append(' ').append(minuteUnit);
        else sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /** Крупное число скорости справа сверху на секунду; повторный вызов продлевает показ. */
    public void showWpmOverlay() {
        overlayVisible = true;
        removeCallbacks(hideOverlay);
        postDelayed(hideOverlay, OVERLAY_MS);
        updateMarquee();
        invalidate();
    }

    // ---------------------------------------------------------------- раскладка слова

    /**
     * Нужно ли резать слово на две показываемые части: даже в минимальном масштабе оно не влезает
     * по ширине. Тот же расчёт, что у раскладки, чтобы движок и вью не расходились.
     */
    @Override
    public boolean needsSplit(BookIndex index, int w) {
        if (index == null || w < 0 || w >= index.wordCount()) return false;
        int s = index.wordStart[w];
        int e = s + index.wordLength(w);
        return !fits(index.text, s, e);
    }

    private boolean fits(char[] t, int s, int e) {
        int i = WordTimer.anchorIndex(t, s, e);
        pBold.setTextSize(wordPx * d);
        float pw = pBold.measureText(t, s + i, 1);
        float lw = pBold.measureText(t, s, i);
        float rw = pBold.measureText(t, s + i + 1, e - (s + i + 1));
        float maxLeft = (ANCHOR_X - MARGIN_L) * d - pw / 2f;
        float maxRight = (MARGIN_R - ANCHOR_X) * d - pw / 2f;
        float scale = WordTimer.fitScale(lw, rw, maxLeft, maxRight, WordTimer.MIN_SCALE);
        return lw * scale <= maxLeft + 0.5f && rw * scale <= maxRight + 0.5f;
    }

    private void layoutWord() {
        if (book == null || word < 0 || word >= book.wordCount()) {
            wordFrom = 0;
            wordTo = 0;
            return;
        }
        int s = book.wordStart[word];
        int e = s + book.wordLength(word);
        char[] t = book.text;
        if (!fits(t, s, e)) {
            int cut = WordTimer.splitPoint(t, s, e);
            if (part == 0) e = cut;
            else s = cut;
        }
        wordFrom = s;
        wordTo = e;
        pivot = WordTimer.anchorIndex(t, s, e);
        pBold.setTextSize(wordPx * d);
        float pw = pBold.measureText(t, s + pivot, 1);
        float lw = pBold.measureText(t, s, pivot);
        float rw = pBold.measureText(t, s + pivot + 1, e - (s + pivot + 1));
        float maxLeft = (ANCHOR_X - MARGIN_L) * d - pw / 2f;
        float maxRight = (MARGIN_R - ANCHOR_X) * d - pw / 2f;
        float scale = WordTimer.fitScale(lw, rw, maxLeft, maxRight, WordTimer.MIN_SCALE);
        wordSize = wordPx * d * scale;
        xPivot = ANCHOR_X * d;
        xLeftEnd = xPivot - pw * scale / 2f;
        xRightStart = xPivot + pw * scale / 2f;
    }

    // ---------------------------------------------------------------- раскладка абзаца

    private void ensureParagraph() {
        if (book == null || mode != MODE_PAUSED || book.wordCount() == 0) return;
        int w = Math.max(0, Math.min(word, book.wordCount() - 1));
        if (parFrom < 0 || w < parFrom || w > parTo) {
            int from = book.paragraphStart(w);
            int to = book.paragraphEnd(w);
            if (to - from + 1 > PAR_WINDOW) {
                from = Math.max(from, w - PAR_WINDOW / 3);
                to = Math.min(to, from + PAR_WINDOW - 1);
            }
            layoutParagraph(from, to);
        }
        placeParagraph();
    }

    private void layoutParagraph(int from, int to) {
        if (book == null) return;
        int n = Math.min(to - from + 1, PAR_WINDOW);
        if (n <= 0) return;
        parFrom = from;
        parTo = from + n - 1;
        pReg.setTextSize(PAR_PX * d);
        float space = pReg.measureText(" ");
        float width = (MARGIN_R - MARGIN_L) * d;
        float x = 0f;
        int line = 0;
        lineFirst[0] = 0;
        for (int i = 0; i < n; i++) {
            int wi = from + i;
            float ww = pReg.measureText(book.text, book.wordStart[wi], book.wordLength(wi));
            if (x > 0f && x + ww > width) {
                line++;
                lineFirst[line] = i;
                x = 0f;
            }
            parX[i] = x;
            parW[i] = ww;
            parLine[i] = line;
            x += ww + space;
        }
        lineCount = line + 1;
        lineFirst[lineCount] = n;
    }

    /** Окно из шести строк: текущая строка третья, пока абзац длиннее окна. */
    private void placeParagraph() {
        if (parFrom < 0 || word < parFrom || word > parTo) {
            firstLine = 0;
            return;
        }
        int cur = parLine[word - parFrom];
        firstLine = Math.max(0, Math.min(cur - 2, lineCount - PAR_LINES));
    }

    // ---------------------------------------------------------------- название главы

    private void measureTitle() {
        pReg.setTextSize(13f * d);
        titleWidth = pReg.measureText(chapterTitle) / d;
    }

    private void measureCaption() {
        if (resumeCaption == null) {
            captionWidth = 0f;
            return;
        }
        pReg.setTextSize(13f * d);
        captionWidth = pReg.measureText(resumeCaption) / d;
    }

    private float titleMaxWidth() {
        if (overlayVisible) return TITLE_UNDER_OVERLAY_W;
        if (resumeCaption != null) return TITLE_FULL_W - captionWidth - 12f;
        return TITLE_FULL_W;
    }

    private boolean titleOverflows() {
        return isShown() && titleWidth > titleMaxWidth();
    }

    /**
     * Длинное название идёт бегущей строкой, а не обрезается: по нему человек узнаёт главу. Перерисовка
     * раз в 100 мс нужна только пока название шире поля, иначе вью рисуется лишь при смене слова.
     */
    private void updateMarquee() {
        if (titleOverflows()) {
            if (!marqueeRunning) {
                marqueeStart = SystemClock.uptimeMillis();
                marqueeRunning = true;
                postDelayed(marqueeStep, MARQUEE_STEP_MS);
            }
        } else if (marqueeRunning) {
            removeCallbacks(marqueeStep);
            marqueeRunning = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateMarquee();
    }

    /** Скрытому экрану бегущая строка не нужна: лишние перерисовки сажают батарею. */
    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateMarquee();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(hideOverlay);
        removeCallbacks(marqueeStep);
        marqueeRunning = false;
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- рисование

    private float midBaseline(float cy, Paint p) {
        p.getFontMetrics(fm);
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    /** Строка с центром по вертикали в cy (в px макета); align относительно x. */
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
        drawTitle(c);
        if (resumeCaption != null && mode == MODE_PAUSED) {
            mid(c, resumeCaption, MARGIN_R, TITLE_CY, pReg, 13f, EARTH, Paint.Align.RIGHT);
        }
        if (mode == MODE_READING) drawWord(c);
        else drawParagraph(c);
        drawBar(c);
        drawBottomTexts(c);
        if (overlayVisible) {
            mid(c, overlayNumber, MARGIN_R, 58f, pTitle, 26f, LIFE, Paint.Align.RIGHT);
            mid(c, wpmLabel, MARGIN_R, 82f, pReg, 13f, EARTH, Paint.Align.RIGHT);
        }
    }

    private void drawTitle(Canvas c) {
        if (chapterTitle.length() == 0) return;
        float max = titleMaxWidth();
        if (titleWidth <= max) {
            mid(c, chapterTitle, MARGIN_L, TITLE_CY, pReg, 13f, EARTH, Paint.Align.LEFT);
            return;
        }
        long t = SystemClock.uptimeMillis() - marqueeStart;
        float travel = titleWidth - max + 16f;
        float run = travel / MARQUEE_SPEED;
        long cycle = (long) (MARQUEE_HOLD_MS + run + MARQUEE_HOLD_MS);
        long k = t % cycle;
        float off = k < MARQUEE_HOLD_MS ? 0f : Math.min(travel, (k - MARQUEE_HOLD_MS) * MARQUEE_SPEED);
        c.save();
        c.clipRect(MARGIN_L * d, (TITLE_CY - 16f) * d, (MARGIN_L + max) * d, (TITLE_CY + 16f) * d);
        mid(c, chapterTitle, MARGIN_L - off, TITLE_CY, pReg, 13f, EARTH, Paint.Align.LEFT);
        c.restore();
    }

    private void drawWord(Canvas c) {
        if (book == null || wordTo <= wordFrom) return;
        float cy = WORD_CY * d;
        float half = wordPx * 0.5f;
        capsule(c, ANCHOR_X - 1.5f, WORD_CY - half - 22f, ANCHOR_X + 1.5f, WORD_CY - half - 8f, 1.5f, EARTH);
        capsule(c, ANCHOR_X - 1.5f, WORD_CY + half + 8f, ANCHOR_X + 1.5f, WORD_CY + half + 22f, 1.5f, EARTH);
        char[] t = book.text;
        pBold.setTextSize(wordSize);
        float base = midBaseline(cy, pBold);
        pBold.setColor(AIR);
        pBold.setTextAlign(Paint.Align.RIGHT);
        if (pivot > 0) c.drawText(t, wordFrom, pivot, xLeftEnd, base, pBold);
        pBold.setTextAlign(Paint.Align.LEFT);
        int rest = wordTo - (wordFrom + pivot + 1);
        if (rest > 0) c.drawText(t, wordFrom + pivot + 1, rest, xRightStart, base, pBold);
        pBold.setColor(LIFE);
        pBold.setTextAlign(Paint.Align.CENTER);
        c.drawText(t, wordFrom + pivot, 1, xPivot, base, pBold);
    }

    private void drawParagraph(Canvas c) {
        if (book == null || parFrom < 0) return;
        pReg.setTextSize(PAR_PX * d);
        pBold.setTextSize(PAR_PX * d);
        pReg.setTextAlign(Paint.Align.LEFT);
        pBold.setTextAlign(Paint.Align.LEFT);
        int lastLine = Math.min(lineCount, firstLine + PAR_LINES);
        for (int line = firstLine; line < lastLine; line++) {
            float base = midBaseline((PAR_TOP + (line - firstLine) * PAR_LINE) * d, pReg);
            for (int i = lineFirst[line]; i < lineFirst[line + 1]; i++) {
                int wi = parFrom + i;
                int s = book.wordStart[wi];
                int len = book.wordLength(wi);
                float x = MARGIN_L * d + parX[i];
                if (wi == word) {
                    pBold.setColor(LIFE);
                    // Полужирное слово шире обычного: центрируем в своём месте, чтобы не липло к соседу.
                    float bw = pBold.measureText(book.text, s, len);
                    c.drawText(book.text, s, len, x - (bw - parW[i]) / 2f, base, pBold);
                } else {
                    pReg.setColor(wi < word ? READ_DIM : AIR);
                    c.drawText(book.text, s, len, x, base, pReg);
                }
            }
        }
    }

    private void drawBar(Canvas c) {
        boolean thick = mode == MODE_PAUSED;
        float bh = thick ? THICK_BAR : THIN_BAR;
        float fx = MARGIN_L + (MARGIN_R - MARGIN_L) * percent / 100f;
        capsule(c, MARGIN_L, BAR_CY - bh / 2f, MARGIN_R, BAR_CY + bh / 2f, bh / 2f, WATER);
        capsule(c, MARGIN_L, BAR_CY - bh / 2f, Math.max(fx, MARGIN_L + bh), BAR_CY + bh / 2f, bh / 2f, LIFE);
        if (thick) {
            fill.setColor(AIR);
            c.drawCircle(fx * d, BAR_CY * d, HANDLE_R * d, fill);
        }
    }

    private void drawBottomTexts(Canvas c) {
        mid(c, percentText, MARGIN_L, TEXT_CY, pBold, 14f, AIR, Paint.Align.LEFT);
        mid(c, wpmText, 240f, TEXT_CY, pReg, 13f, EARTH, Paint.Align.CENTER);
        if (remainText.length() > 0) {
            mid(c, remainText, MARGIN_R, TEXT_CY, pBold, 14f, EARTH, Paint.Align.RIGHT);
        }
    }
}
