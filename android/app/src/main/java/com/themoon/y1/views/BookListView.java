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
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import com.themoon.y1.reader.LibraryEntry;

import java.util.List;

/**
 * Библиотека читалки одним плоским вью: список книг слева, обложка выбранной книги и полоса её
 * прогресса справа, а также пустое состояние и «занято» (загрузка списка, подготовка книги).
 * Координаты те же, что в утверждённом макете work/np-variants/reader-library-v3-1.png
 * (480x360, 1 dp = 1 px на Y1), от левого верхнего угла экрана.
 *
 * Один вью вместо строк-кнопок: на Cortex-A7 с Android 4.2 список из сотен книг не должен строить
 * иерархию вью. Рисуются только пять видимых строк, их тексты (в том числе обрезанные многоточием)
 * готовятся в relayout() при смене выбора или данных, а onDraw ничего не выделяет. Вью не берёт
 * фокус, поэтому центр колеса приходит из MainActivity.handleCenterShortClick в {@link #activate()}.
 *
 * Палитра фиксирована (брендбук zeny) и от темы не зависит; от темы берутся только шрифты.
 */
public final class BookListView extends View {

    public interface Listener {
        /** Центр колеса на книге с номером index. */
        void onActivate(int index);

        /** Выбор сменился: приложению пора подгрузить обложку этой книги. */
        void onSelect(int index);
    }

    private static final int MODE_LIST = 0;
    private static final int MODE_MESSAGE = 1;
    private static final int MODE_BUSY = 2;

    private static final int INK = 0xFF181E26;
    private static final int LIFE = 0xFF00DB4D;
    private static final int ON_LIFE = 0xFF08130B;
    private static final int AIR = 0xFFEFEBE2;
    private static final int EARTH = 0xFFA4A3A0;
    private static final int WATER = 0xFF334351;
    /** Автор на зелёной капсуле: on-life на 75% поверх life, как в макете. */
    private static final int ON_LIFE_SOFT = mix(ON_LIFE, LIFE, 0.75f);

    // Макет, 480x360.
    private static final int VISIBLE = 5;
    private static final float TOP = 38f;
    private static final float ROW_H = 54f;
    private static final float GAP = 2f;
    private static final float LIST_W = 312f;
    private static final float CAPSULE_LEFT = -30f;
    private static final float CAPSULE_R = 27f;
    private static final float TEXT_X = 16f;
    private static final float STATE_RIGHT = LIST_W - 22f;
    private static final float TICK_SIZE = 20f;
    private static final float COVER_L = 322f;
    private static final float COVER_T = 44f;
    private static final float COVER_R = 468f;
    private static final float COVER_B = 263f;
    private static final float COVER_RADIUS = 26f;
    private static final float BAR_CY = 286f;
    private static final float BAR_H = 8f;
    private static final float MSG_TITLE_CY = 150f;
    private static final float MSG_LINE_CY = 190f;
    private static final float MSG_LINE_STEP = 22f;
    private static final float MSG_BAR_CY = 210f;
    private static final float MSG_BAR_L = 120f;
    private static final float MSG_BAR_R = 360f;
    private static final float MSG_HINT_WIDTH = 400f;
    private static final int MSG_HINT_LINES = 3;

    private static final long MARQUEE_STEP_MS = 100;
    private static final long MARQUEE_HOLD_MS = 1500;
    /** Скорость бегущей строки названия: px в мс (40 px/с читается без усилий). */
    private static final float MARQUEE_SPEED = 0.04f;

    private final float d;
    private final TextPaint pTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pBold = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pReg = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final RectF rect = new RectF();
    private final Matrix coverMatrix = new Matrix();
    private final HugeGlyph tick = new HugeGlyph(HugeGlyph.TICK, EARTH);

    private Listener listener;
    private int mode = MODE_LIST;
    private List<LibraryEntry> books;
    private int selected;
    private int top;
    private String newLabel = "Новая";

    // Тексты видимых строк, готовятся в relayout().
    private final String[] rowTitle = new String[VISIBLE];
    private final String[] rowAuthor = new String[VISIBLE];
    private final String[] rowState = new String[VISIBLE];
    private final boolean[] rowDone = new boolean[VISIBLE];
    private final boolean[] rowNew = new boolean[VISIBLE];
    private int rowsShown;
    private String focusTitle = "";
    private float focusTitleW;
    private float focusMaxW;
    private boolean marqueeRunning;
    private long marqueeStart;

    // Обложка выбранной книги и полоса прогресса под ней.
    private Bitmap cover;
    private Bitmap placeholder;
    private boolean coverShaderValid;
    private int selPct;

    // Сообщение и «занято».
    private String headline = "";
    private final String[] hintLines = new String[MSG_HINT_LINES];
    private int hintCount;
    private int busyPercent = -1;

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

    public BookListView(Context context) {
        this(context, null);
    }

    public BookListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        coverPaint.setFilterBitmap(true);
        int g = Math.round(TICK_SIZE * d);
        tick.setBounds(0, 0, g, g);
        setFocusable(false);
    }

    private static int mix(int fg, int bg, float a) {
        int r = Math.round(((fg >> 16) & 0xFF) * a + ((bg >> 16) & 0xFF) * (1f - a));
        int g = Math.round(((fg >> 8) & 0xFF) * a + ((bg >> 8) & 0xFF) * (1f - a));
        int b = Math.round((fg & 0xFF) * a + (bg & 0xFF) * (1f - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Заголовки (Unbounded), полужирный и обычный текст (Onest): те же шрифты, что у остальных экранов темы. */
    public void setTypefaces(Typeface title, Typeface bold, Typeface regular) {
        pTitle.setTypeface(title);
        pBold.setTypeface(bold);
        pReg.setTypeface(regular);
        relayout();
        invalidate();
    }

    /** Подпись состояния непрочитанной книги («Новая»). */
    public void setNewLabel(String label) {
        newLabel = label;
        relayout();
        invalidate();
    }

    // ---------------------------------------------------------------- данные

    /** Показывает список; выбор встаёт на selectIndex (первая строка = последняя читанная книга). */
    public void setBooks(List<LibraryEntry> list, int selectIndex) {
        books = list;
        mode = MODE_LIST;
        int n = list == null ? 0 : list.size();
        selected = n == 0 ? 0 : Math.max(0, Math.min(selectIndex, n - 1));
        top = Math.max(0, Math.min(selected - VISIBLE + 1, Math.max(0, n - VISIBLE)));
        relayout();
        invalidate();
        notifySelect();
    }

    /** Состояния книг изменились (прочитана, начата сначала): перерисовать строки и полосу. */
    public void refresh() {
        relayout();
        invalidate();
    }

    /** Порядок списка поменялся: те же книги, выбор остаётся на выбранной книге. */
    public void reorder(int newSelectedIndex) {
        int n = books == null ? 0 : books.size();
        selected = n == 0 ? 0 : Math.max(0, Math.min(newSelectedIndex, n - 1));
        top = Math.max(0, Math.min(selected - VISIBLE + 1, Math.max(0, n - VISIBLE)));
        relayout();
        invalidate();
        notifySelect();
    }

    /** Пустое состояние или сообщение: крупная строка и поясняющий текст под ней (может быть null). */
    public void setMessage(String title, String hint) {
        mode = MODE_MESSAGE;
        headline = title == null ? "" : title;
        wrapHint(hint);
        busyPercent = -1;
        updateMarquee();
        invalidate();
    }

    /** «Загружаю», «Готовлю книгу 37%»: percent меньше нуля рисует только строку без полосы. */
    public void setBusy(String title, int percent) {
        mode = MODE_BUSY;
        headline = title == null ? "" : title;
        hintCount = 0;
        busyPercent = percent;
        updateMarquee();
        invalidate();
    }

    /** Возвращает список после «занято» (книга не открылась); данные и выбор остаются прежними. */
    public void showList() {
        if (mode == MODE_LIST) return;
        mode = MODE_LIST;
        relayout();
        invalidate();
    }

    public boolean isListShown() {
        return mode == MODE_LIST && books != null && !books.isEmpty();
    }

    public int getSelectedIndex() {
        return selected;
    }

    public void setCover(Bitmap bmp) {
        cover = bmp;
        coverShaderValid = false;
        invalidate();
    }

    /** Робот вместо обложки: рисуется так же, с обрезкой по центру. */
    public void setPlaceholder(Bitmap bmp) {
        placeholder = bmp;
        coverShaderValid = false;
        invalidate();
    }

    // ---------------------------------------------------------------- выбор и ввод

    /** Колесо по списку по кругу. */
    public boolean moveSelection(int dir) {
        if (mode != MODE_LIST || books == null || books.isEmpty()) return false;
        int n = books.size();
        int next = ((selected + dir) % n + n) % n;
        if (next == selected) return false;
        select(next);
        return true;
    }

    /** NEXT/PREV: страница из пяти строк, на краях список не зацикливается. */
    public boolean page(int dir) {
        if (mode != MODE_LIST || books == null || books.isEmpty()) return false;
        int next = Math.max(0, Math.min(selected + dir * VISIBLE, books.size() - 1));
        if (next == selected) return false;
        select(next);
        return true;
    }

    private void select(int index) {
        int n = books.size();
        // Окно догоняет выбор; при переходе через край (0 -> последняя) окно встаёт на конец списка.
        if (index < top) top = index;
        else if (index >= top + VISIBLE) top = index - VISIBLE + 1;
        top = Math.max(0, Math.min(top, Math.max(0, n - VISIBLE)));
        selected = index;
        relayout();
        invalidate();
        notifySelect();
    }

    private void notifySelect() {
        if (listener != null && books != null && !books.isEmpty()) listener.onSelect(selected);
    }

    /** Центр колеса: открыть выбранную книгу. */
    public void activate() {
        if (listener != null && isListShown()) listener.onActivate(selected);
    }

    // ---------------------------------------------------------------- подготовка текстов

    private void relayout() {
        if (books == null || books.isEmpty()) {
            rowsShown = 0;
            selPct = 0;
            updateMarquee();
            return;
        }
        rowsShown = Math.min(VISIBLE, books.size() - top);
        for (int i = 0; i < rowsShown; i++) {
            LibraryEntry e = books.get(top + i);
            boolean focused = top + i == selected;
            rowDone[i] = e.state == LibraryEntry.READ;
            rowNew[i] = e.state == LibraryEntry.NEW;
            if (rowDone[i]) {
                rowState[i] = null;
            } else if (e.state == LibraryEntry.NEW) {
                rowState[i] = newLabel;
            } else {
                rowState[i] = e.pct + "%";
            }
            pBold.setTextSize(13f * d);
            float stateW = rowDone[i] ? TICK_SIZE : rowState[i] == null ? 0f : pBold.measureText(rowState[i]) / d;
            float maxW = STATE_RIGHT - stateW - 20f - TEXT_X;
            pBold.setTextSize(17f * d);
            String title = e.title == null ? "" : e.title;
            float tw = pBold.measureText(title) / d;
            if (focused) {
                focusTitle = title;
                focusTitleW = tw;
                focusMaxW = maxW;
                rowTitle[i] = title;
            } else {
                rowTitle[i] = tw <= maxW ? title : fitDots(pBold, 17f, title, maxW);
            }
            pReg.setTextSize(13f * d);
            String author = e.author == null ? "" : e.author;
            rowAuthor[i] = pReg.measureText(author) / d <= maxW ? author : fitDots(pReg, 13f, author, maxW);
        }
        LibraryEntry sel = books.get(selected);
        selPct = sel.state == LibraryEntry.READ ? 100 : sel.state == LibraryEntry.NEW ? 0 : sel.pct;
        marqueeStart = SystemClock.uptimeMillis();
        updateMarquee();
    }

    /** Текст, обрезанный по слову или символу до ширины maxW (в dp макета) с «...» на конце. */
    private String fitDots(TextPaint p, float sizeDp, String text, float maxW) {
        p.setTextSize(sizeDp * d);
        float dots = p.measureText("...");
        int n = p.breakText(text, true, Math.max(0f, maxW * d - dots), null);
        if (n <= 0) return "...";
        int cut = n;
        // Обрыв по слову читается легче, но только если слово не уносит половину строки.
        int sp = text.lastIndexOf(' ', n - 1);
        if (sp > n / 2) cut = sp;
        return text.substring(0, cut).trim() + "...";
    }

    private void wrapHint(String hint) {
        hintCount = 0;
        if (hint == null || hint.length() == 0) return;
        pReg.setTextSize(15f * d);
        int pos = 0;
        int len = hint.length();
        while (pos < len && hintCount < MSG_HINT_LINES) {
            int n = pReg.breakText(hint, pos, len, true, MSG_HINT_WIDTH * d, null);
            if (n <= 0) n = 1;
            int end = pos + n;
            if (end < len) {
                int sp = hint.lastIndexOf(' ', end - 1);
                if (sp > pos) end = sp;
            }
            hintLines[hintCount++] = hint.substring(pos, end).trim();
            pos = end;
            while (pos < len && hint.charAt(pos) == ' ') pos++;
        }
    }

    // ---------------------------------------------------------------- бегущая строка

    private boolean titleOverflows() {
        return isShown() && mode == MODE_LIST && rowsShown > 0 && focusTitleW > focusMaxW;
    }

    /** Перерисовка раз в 100 мс нужна, только пока название выбранной книги шире строки. */
    private void updateMarquee() {
        if (titleOverflows()) {
            if (!marqueeRunning) {
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
        marqueeStart = SystemClock.uptimeMillis();
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
        removeCallbacks(marqueeStep);
        marqueeRunning = false;
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- рисование

    private float midBaseline(float cy, Paint p) {
        p.getFontMetrics(fm);
        return cy - (fm.ascent + fm.descent) / 2f;
    }

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
        if (mode == MODE_MESSAGE) {
            drawMessage(c);
        } else if (mode == MODE_BUSY) {
            drawBusy(c);
        } else if (rowsShown > 0) {
            drawRows(c);
            drawCover(c);
        }
    }

    private void drawMessage(Canvas c) {
        mid(c, headline, 240f, MSG_TITLE_CY, pTitle, 20f, AIR, Paint.Align.CENTER);
        for (int i = 0; i < hintCount; i++) {
            mid(c, hintLines[i], 240f, MSG_LINE_CY + i * MSG_LINE_STEP, pReg, 15f, EARTH, Paint.Align.CENTER);
        }
    }

    private void drawBusy(Canvas c) {
        mid(c, headline, 240f, MSG_TITLE_CY, pTitle, 20f, AIR, Paint.Align.CENTER);
        if (busyPercent < 0) return;
        float fx = MSG_BAR_L + (MSG_BAR_R - MSG_BAR_L) * Math.min(busyPercent, 100) / 100f;
        capsule(c, MSG_BAR_L, MSG_BAR_CY - BAR_H / 2f, MSG_BAR_R, MSG_BAR_CY + BAR_H / 2f, BAR_H / 2f, WATER);
        capsule(c, MSG_BAR_L, MSG_BAR_CY - BAR_H / 2f, Math.max(fx, MSG_BAR_L + BAR_H), MSG_BAR_CY + BAR_H / 2f,
                BAR_H / 2f, LIFE);
    }

    private void drawRows(Canvas c) {
        for (int i = 0; i < rowsShown; i++) {
            float y = TOP + i * (ROW_H + GAP);
            boolean focused = top + i == selected;
            if (focused) capsule(c, CAPSULE_LEFT, y, LIST_W, y + ROW_H, CAPSULE_R, LIFE);
            int titleColor = focused ? ON_LIFE : AIR;
            int authorColor = focused ? ON_LIFE_SOFT : EARTH;
            int stateColor = focused ? ON_LIFE : EARTH;
            float cy = y + ROW_H / 2f;
            if (rowDone[i]) {
                tick.setColor(stateColor);
                int g = Math.round(TICK_SIZE * d);
                int left = Math.round((STATE_RIGHT - 8f - TICK_SIZE / 2f) * d);
                int topPx = Math.round(cy * d - g / 2f);
                c.save();
                c.translate(left, topPx);
                tick.draw(c);
                c.restore();
            } else {
                int color = focused ? ON_LIFE : rowNew[i] ? LIFE : EARTH;
                mid(c, rowState[i], STATE_RIGHT, cy, pBold, 13f, color, Paint.Align.RIGHT);
            }
            if (focused && focusTitleW > focusMaxW) {
                drawMarqueeTitle(c, y + 19f, titleColor);
            } else {
                mid(c, rowTitle[i], TEXT_X, y + 19f, pBold, 17f, titleColor, Paint.Align.LEFT);
            }
            mid(c, rowAuthor[i], TEXT_X, y + 38f, pReg, 13f, authorColor, Paint.Align.LEFT);
        }
    }

    private void drawMarqueeTitle(Canvas c, float cy, int color) {
        long t = SystemClock.uptimeMillis() - marqueeStart;
        float travel = focusTitleW - focusMaxW + 16f;
        float run = travel / MARQUEE_SPEED;
        long cycle = (long) (MARQUEE_HOLD_MS + run + MARQUEE_HOLD_MS);
        long k = t % cycle;
        float off = k < MARQUEE_HOLD_MS ? 0f : Math.min(travel, (k - MARQUEE_HOLD_MS) * MARQUEE_SPEED);
        c.save();
        c.clipRect(TEXT_X * d, (cy - 20f) * d, (TEXT_X + focusMaxW) * d, (cy + 20f) * d);
        mid(c, focusTitle, TEXT_X - off, cy, pBold, 17f, color, Paint.Align.LEFT);
        c.restore();
    }

    private void drawCover(Canvas c) {
        Bitmap bmp = cover != null ? cover : placeholder;
        if (bmp != null) {
            if (!coverShaderValid) buildCoverShader(bmp);
            rect.set(COVER_L * d, COVER_T * d, COVER_R * d, COVER_B * d);
            c.drawRoundRect(rect, COVER_RADIUS * d, COVER_RADIUS * d, coverPaint);
        }
        capsule(c, COVER_L, BAR_CY - BAR_H / 2f, COVER_R, BAR_CY + BAR_H / 2f, BAR_H / 2f, WATER);
        if (selPct > 0) {
            float fx = COVER_L + (COVER_R - COVER_L) * selPct / 100f;
            capsule(c, COVER_L, BAR_CY - BAR_H / 2f, Math.max(fx, COVER_L + BAR_H), BAR_CY + BAR_H / 2f, BAR_H / 2f,
                    LIFE);
        }
    }

    /**
     * Масштаб по большей стороне и обрезка по центру: контейнер закрыт полностью (портрет 2:3 остаётся
     * целым, квадрат и альбомная режутся по бокам). Строится при смене картинки, а не при рисовании.
     */
    private void buildCoverShader(Bitmap bmp) {
        float w = (COVER_R - COVER_L) * d;
        float h = (COVER_B - COVER_T) * d;
        float sc = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
        coverMatrix.reset();
        coverMatrix.setScale(sc, sc);
        coverMatrix.postTranslate(COVER_L * d + (w - bmp.getWidth() * sc) / 2f,
                COVER_T * d + (h - bmp.getHeight() * sc) / 2f);
        BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(coverMatrix);
        coverPaint.setShader(shader);
        coverShaderValid = true;
    }
}
