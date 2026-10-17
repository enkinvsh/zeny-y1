package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import com.themoon.y1.managers.LyricsDoc;
import com.themoon.y1.managers.LyricsWrap;

import java.util.ArrayList;

/**
 * Текст песни одним плоским вью в трёх режимах: строка под альбомом (снимок), полный экран с
 * синхронным текстом и полный экран с простым текстом. Координаты полного экрана те же, что в
 * утверждённых макетах (480x360, 1 dp = 1 px на Y1) от левого верхнего угла экрана.
 *
 * Слабый MT6572: onDraw только рисует готовые строки и ничего не выделяет. Переносы и обрезка
 * многоточием считаются в setIndex/setDoc/setHeader (раз в смену строки, а не в кадр), результат
 * лежит в заранее созданных массивах; перерисовка запрашивается, только если что-то изменилось.
 * Никаких градиентов, теней и размытия.
 *
 * Палитра фиксирована (брендбук zeny) и от темы не зависит; от темы берутся только шрифты.
 */
public final class LyricsView extends View {

    public static final int MODE_SNIPPET = 0;
    public static final int MODE_FULL_SYNCED = 1;
    public static final int MODE_FULL_PLAIN = 2;

    private static final int AIR = 0xFFEFEBE2;
    private static final int EARTH = 0xFFA4A3A0;
    private static final int LIFE = 0xFF00DB4D;
    private static final int WATER = 0xFF334351;
    private static final int POSSIBLE = 0xFFF7CE46;
    // Смесь ink 181E26 и earth A4A3A0 в пропорции 0.45, посчитанная заранее: сплошной цвет вместо
    // альфы, потому что полупрозрачный текст на Cortex-A7 без GPU смешивается дороже.
    private static final int DIM = 0xFF575A5D;

    // Полный экран, координаты центров строк по макету.
    private static final float HEAD_TITLE_Y = 58f;
    private static final float HEAD_ARTIST_Y = 78f;
    private static final float CURRENT_Y = 190f;
    private static final float CURRENT_LINE_STEP = 33f;
    private static final float[] NEAR_Y = {112f, 135f, 244f, 267f};
    private static final int[] NEAR_OFFSET = {-2, -1, 1, 2};
    private static final int[] NEAR_COLOR = {DIM, EARTH, EARTH, DIM};
    private static final float SIDE_MARGIN = 28f;
    // Подгонка текста: значение сдвига и подсказка лежат ниже ближайших строк (последняя кончается около y 275).
    private static final float SHIFT_Y = 306f;
    private static final float HINT_Y = 338f;

    // Простой текст: область списка y 96..285, шаг строки 29 даёт шесть строк.
    private static final float LIST_TOP = 96f;
    private static final float LIST_BOTTOM = 285f;
    private static final float PLAIN_STEP = 29f;
    private static final float PLAIN_MARGIN = 36f;
    // Полоса прокрутки: капсула 6 px у правого края, высота как у шести строк.
    private static final float BAR_LEFT = 465f;
    private static final float BAR_WIDTH = 6f;
    private static final float BAR_TOP = 98f;
    private static final float BAR_BOTTOM = 272f;
    private static final float BAR_MIN_THUMB = 16f;

    // Снимок под альбомом.
    private static final float SNIP_STEP_CUR = 22f;
    private static final float SNIP_STEP_NEXT = 18f;
    private static final float SNIP_GAP = 6f;
    // Строки текста длинные, а колонка рядом с обложкой всего около 216 px: обрезка многоточием
    // съедала слова, ради которых текст и включают. Поэтому текущая строка занимает до трёх рядов,
    // следующая до двух, а если колонка ниже (название в две строки), сначала сжимается следующая.
    private static final int SNIP_MAX_CUR_LINES = 3;
    private static final int SNIP_MAX_NEXT_LINES = 2;

    private final float d;
    private final TextPaint pBig = paint(LIFE);
    private final TextPaint pLine = paint(AIR);
    private final TextPaint pSnipCur = paint(AIR);
    private final TextPaint pSnipNext = paint(EARTH);
    private final TextPaint pHeadTitle = paint(AIR);
    private final TextPaint pHeadArtist = paint(EARTH);
    private final TextPaint pShift = paint(POSSIBLE);
    private final TextPaint pHint = paint(EARTH);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Paint.FontMetrics fm = new Paint.FontMetrics();

    // Смещение базовой линии от центра строки для каждой кисти: считается раз при смене шрифта.
    private float offBig, offLine, offSnipCur, offSnipNext, offHeadTitle, offHeadArtist, offShift, offHint;

    // Подпись подгонки: null значит режим выключен. Подсказка укорачивается заранее, не в onDraw.
    private String shiftValue;
    private String hintSrc;
    private String hintShown = "";

    private TextPaint measurePaint = pLine;
    private final LyricsWrap.TextMeasurer measurer = new LyricsWrap.TextMeasurer() {
        @Override
        public float width(String s) {
            return measurePaint.measureText(s);
        }
    };

    private int mode = MODE_SNIPPET;
    private LyricsDoc doc;
    private int index = -2;

    private final String[] curLines = new String[SNIP_MAX_CUR_LINES];
    private int curCount;
    private final String[] snipNextLines = new String[SNIP_MAX_NEXT_LINES];
    private int snipNextCount;
    private final String[] nearLines = {"", "", "", ""};

    private CharSequence headTitleSrc;
    private CharSequence headArtistSrc;
    private String headTitle = "";
    private String headArtist = "";

    private String[] plainLines = new String[0];
    private int scrollPx;
    private int maxScrollPx;

    public LyricsView(Context context) {
        this(context, null);
    }

    public LyricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        setSizes(pBig, 22f);
        setSizes(pLine, 16f);
        setSizes(pSnipCur, 17f);
        setSizes(pSnipNext, 14f);
        setSizes(pHeadTitle, 15f);
        setSizes(pHeadArtist, 13f);
        setSizes(pShift, 22f);
        setSizes(pHint, 12f);
        updateMetrics();
        setFocusable(false);
    }

    private static TextPaint paint(int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    private void setSizes(TextPaint p, float sizeDp) {
        p.setTextSize(sizeDp * d);
    }

    /** Unbounded для крупной текущей строки, Onest SemiBold и Regular для остального: шрифты общей темы. */
    public void setTypefaces(Typeface title, Typeface bold, Typeface regular) {
        Typeface t = title != null ? title : Typeface.DEFAULT;
        Typeface b = bold != null ? bold : Typeface.DEFAULT;
        Typeface r = regular != null ? regular : Typeface.DEFAULT;
        pBig.setTypeface(t);
        pShift.setTypeface(t);
        pHint.setTypeface(r);
        pLine.setTypeface(b);
        pSnipCur.setTypeface(b);
        pHeadTitle.setTypeface(b);
        pSnipNext.setTypeface(r);
        pHeadArtist.setTypeface(r);
        updateMetrics();
        relayout();
    }

    private void updateMetrics() {
        offBig = baselineOffset(pBig);
        offLine = baselineOffset(pLine);
        offSnipCur = baselineOffset(pSnipCur);
        offSnipNext = baselineOffset(pSnipNext);
        offHeadTitle = baselineOffset(pHeadTitle);
        offHeadArtist = baselineOffset(pHeadArtist);
        offShift = baselineOffset(pShift);
        offHint = baselineOffset(pHint);
    }

    /**
     * Крупное значение сдвига и строка подсказки внизу полного синхронного экрана; (null, null)
     * выключает. Перерисовка только если значение или подсказка действительно сменились.
     */
    public void setSyncOverlay(String value, String hint) {
        boolean sameValue = value == null ? shiftValue == null : value.equals(shiftValue);
        boolean sameHint = hint == null ? hintSrc == null : hint.equals(hintSrc);
        if (sameValue && sameHint) return;
        shiftValue = value;
        if (!sameHint) {
            hintSrc = hint;
            layoutHint();
        }
        invalidate();
    }

    private void layoutHint() {
        float w = getWidth() - 2 * SIDE_MARGIN * d;
        if (hintSrc == null || w <= 0) {
            hintShown = "";
            return;
        }
        measurePaint = pHint;
        hintShown = LyricsWrap.ellipsize(hintSrc, w, measurer);
    }

    private float baselineOffset(TextPaint p) {
        p.getFontMetrics(fm);
        return -(fm.ascent + fm.descent) / 2f;
    }

    public void setMode(int newMode) {
        if (mode == newMode) return;
        mode = newMode;
        relayout();
    }

    /** Новый документ: индекс и прокрутка сбрасываются, строки готовятся заново. null очищает вью. */
    public void setDoc(LyricsDoc newDoc) {
        doc = newDoc;
        index = -2;
        scrollPx = 0;
        relayout();
    }

    /**
     * Заголовок полного экрана. Принимает ссылки на текст из TextView: пока ссылка та же или
     * строка равна прежней, ничего не пересчитывается, поэтому вызывать можно из таймера.
     */
    public void setHeader(CharSequence title, CharSequence artist) {
        if (sameText(title, headTitleSrc) && sameText(artist, headArtistSrc)) return;
        headTitleSrc = title;
        headArtistSrc = artist;
        layoutHeader();
        invalidate();
    }

    private static boolean sameText(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null || a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) return false;
        }
        return true;
    }

    /** Номер текущей строки; перерисовка и пересчёт переносов только если он действительно сменился. */
    public void setIndex(int newIndex) {
        if (newIndex == index) return;
        index = newIndex;
        if (mode == MODE_FULL_PLAIN) return;
        layoutLines();
        invalidate();
    }

    /** Доля прокрутки простого текста 0..1; перерисовка только при сдвиге хотя бы на пиксель. */
    public void setScrollFraction(float fraction) {
        float f = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        int px = (int) (f * maxScrollPx);
        if (px == scrollPx) return;
        scrollPx = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    // ---------------------------------------------------------------- подготовка строк

    private void relayout() {
        layoutHint();
        layoutHeader();
        layoutLines();
        layoutPlain();
        invalidate();
    }

    private void layoutHeader() {
        float w = getWidth() - 2 * SIDE_MARGIN * d;
        if (w <= 0 || mode == MODE_SNIPPET) {
            headTitle = "";
            headArtist = "";
            return;
        }
        measurePaint = pHeadTitle;
        headTitle = headTitleSrc == null ? "" : LyricsWrap.ellipsize(headTitleSrc.toString(), w, measurer);
        measurePaint = pHeadArtist;
        headArtist = headArtistSrc == null ? "" : LyricsWrap.ellipsize(headArtistSrc.toString(), w, measurer);
    }

    private void layoutLines() {
        curCount = 0;
        snipNextCount = 0;
        for (int i = 0; i < nearLines.length; i++) nearLines[i] = "";
        if (doc == null || !doc.synced || getWidth() <= 0 || mode == MODE_FULL_PLAIN) return;
        int n = doc.size();
        String cur = index >= 0 && index < n ? doc.lines[index] : "";
        if (mode == MODE_SNIPPET) {
            float w = getWidth();
            String next = index + 1 >= 0 && index + 1 < n ? doc.lines[index + 1] : "";
            float room = getHeight() / d;
            int curMax = SNIP_MAX_CUR_LINES;
            int nextMax = SNIP_MAX_NEXT_LINES;
            // Уменьшаем лимиты, пока блок не влезет в высоту вью: сперва следующая строка, потом текущая.
            while (true) {
                measurePaint = pSnipCur;
                curCount = LyricsWrap.wrapBalanced(cur, w, curMax, measurer, curLines);
                measurePaint = pSnipNext;
                snipNextCount = LyricsWrap.wrapBalanced(next, w, nextMax, measurer, snipNextLines);
                float need = curCount * SNIP_STEP_CUR + snipNextCount * SNIP_STEP_NEXT
                        + (curCount > 0 && snipNextCount > 0 ? SNIP_GAP : 0f);
                if (need <= room || room <= 0f) break;
                if (nextMax > 1) nextMax--;
                else if (curMax > 2) curMax--;
                else if (nextMax > 0) nextMax = 0;
                else break;
            }
        } else {
            float w = getWidth() - 2 * SIDE_MARGIN * d;
            measurePaint = pBig;
            curCount = LyricsWrap.wrapBalanced(cur, w, 2, measurer, curLines);
            measurePaint = pLine;
            for (int k = 0; k < nearLines.length; k++) {
                int i = index + NEAR_OFFSET[k];
                String s = i >= 0 && i < n ? doc.lines[i] : "";
                nearLines[k] = LyricsWrap.ellipsize(s, w, measurer);
            }
        }
    }

    private void layoutPlain() {
        plainLines = new String[0];
        maxScrollPx = 0;
        if (mode != MODE_FULL_PLAIN || doc == null || doc.synced || getWidth() <= 0) return;
        ArrayList<String> out = new ArrayList<String>();
        measurePaint = pLine;
        LyricsWrap.wrapAll(doc.plain, getWidth() - 2 * PLAIN_MARGIN * d, measurer, out);
        plainLines = out.toArray(new String[0]);
        float content = plainLines.length * PLAIN_STEP * d;
        float area = (LIST_BOTTOM - LIST_TOP) * d;
        maxScrollPx = content > area ? (int) (content - area) : 0;
        if (scrollPx > maxScrollPx) scrollPx = maxScrollPx;
    }

    // ---------------------------------------------------------------- рисование

    @Override
    protected void onDraw(Canvas canvas) {
        if (doc == null) return;
        switch (mode) {
            case MODE_SNIPPET:
                drawSnippet(canvas);
                break;
            case MODE_FULL_SYNCED:
                drawHeader(canvas);
                drawSynced(canvas);
                break;
            default:
                drawHeader(canvas);
                drawPlain(canvas);
                break;
        }
    }

    private void drawSnippet(Canvas canvas) {
        float curH = curCount * SNIP_STEP_CUR * d;
        float nextH = snipNextCount * SNIP_STEP_NEXT * d;
        float gap = curCount > 0 && snipNextCount > 0 ? SNIP_GAP * d : 0f;
        float top = (getHeight() - (curH + gap + nextH)) / 2f;
        float cx = getWidth() / 2f;
        for (int i = 0; i < curCount; i++) {
            float cy = top + (i + 0.5f) * SNIP_STEP_CUR * d;
            canvas.drawText(curLines[i], cx, cy + offSnipCur, pSnipCur);
        }
        for (int i = 0; i < snipNextCount; i++) {
            float cy = top + curH + gap + (i + 0.5f) * SNIP_STEP_NEXT * d;
            canvas.drawText(snipNextLines[i], cx, cy + offSnipNext, pSnipNext);
        }
    }

    private void drawHeader(Canvas canvas) {
        float cx = getWidth() / 2f;
        canvas.drawText(headTitle, cx, HEAD_TITLE_Y * d + offHeadTitle, pHeadTitle);
        canvas.drawText(headArtist, cx, HEAD_ARTIST_Y * d + offHeadArtist, pHeadArtist);
    }

    private void drawSynced(Canvas canvas) {
        float cx = getWidth() / 2f;
        for (int k = 0; k < nearLines.length; k++) {
            if (nearLines[k].isEmpty()) continue;
            pLine.setColor(NEAR_COLOR[k]);
            canvas.drawText(nearLines[k], cx, NEAR_Y[k] * d + offLine, pLine);
        }
        for (int i = 0; i < curCount; i++) {
            float cy = (CURRENT_Y + (i - (curCount - 1) / 2f) * CURRENT_LINE_STEP) * d;
            canvas.drawText(curLines[i], cx, cy + offBig, pBig);
        }
        if (shiftValue != null) {
            canvas.drawText(shiftValue, cx, SHIFT_Y * d + offShift, pShift);
            canvas.drawText(hintShown, cx, HINT_Y * d + offHint, pHint);
        }
    }

    private void drawPlain(Canvas canvas) {
        float cx = getWidth() / 2f;
        float step = PLAIN_STEP * d;
        float top = LIST_TOP * d;
        float bottom = LIST_BOTTOM * d;
        pLine.setColor(AIR);
        canvas.save();
        canvas.clipRect(0f, top, getWidth(), bottom);
        int first = (int) (scrollPx / step);
        for (int i = first; i < plainLines.length; i++) {
            float cy = top + (i + 0.5f) * step - scrollPx;
            if (cy - step > bottom) break;
            String s = plainLines[i];
            if (!s.isEmpty()) canvas.drawText(s, cx, cy + offLine, pLine);
        }
        canvas.restore();
        if (maxScrollPx > 0) drawScrollBar(canvas);
    }

    private void drawScrollBar(Canvas canvas) {
        float left = BAR_LEFT * d;
        float right = left + BAR_WIDTH * d;
        float trackTop = BAR_TOP * d;
        float trackH = (BAR_BOTTOM - BAR_TOP) * d;
        float radius = BAR_WIDTH * d / 2f;
        fill.setColor(WATER);
        rect.set(left, trackTop, right, trackTop + trackH);
        canvas.drawRoundRect(rect, radius, radius, fill);

        float visible = (LIST_BOTTOM - LIST_TOP) * d;
        float content = visible + maxScrollPx;
        float thumbH = Math.max(BAR_MIN_THUMB * d, trackH * visible / content);
        float y = trackTop + (trackH - thumbH) * scrollPx / maxScrollPx;
        fill.setColor(LIFE);
        rect.set(left, y, right, y + thumbH);
        canvas.drawRoundRect(rect, radius, radius, fill);
    }
}
