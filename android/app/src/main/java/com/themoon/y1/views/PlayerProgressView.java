package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Толстая полоса прогресса экрана воспроизведения: капсула-трек, заливка и круглая ручка без
 * кольца, а над ручкой во время перемотки капсула со временем цели. Плоско, без свечения и теней
 * (брендбук zeny). Полоса прижата к низу вида, место над ней отдано пузырю, поэтому он не
 * требует отдельного вида и не двигает соседей.
 *
 * Рисует только по изменению: пока ручка не сдвинулась на пиксель, invalidate() не зовётся.
 * Paint, RectF и буфер текста созданы заранее, поэтому onDraw ничего не аллоцирует (слабое железо Y1).
 */
public class PlayerProgressView extends View {
    private static final float BAR_NORMAL_DP = 12f;
    private static final float BAR_SEEK_DP = 18f;
    private static final float KNOB_NORMAL_DP = 11f;
    private static final float KNOB_SEEK_DP = 14f;
    private static final float BUBBLE_H_DP = 26f;
    private static final float BUBBLE_GAP_DP = 6f;
    private static final float BUBBLE_PAD_DP = 10f;
    private static final float BUBBLE_TEXT_SP = 15f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** «mm:ss» или «mmm:ss» для очень длинных записей. */
    private final char[] bubbleText = new char[8];
    /** Сюда форматируется новое время, чтобы сравнить с показанным и не перерисовывать зря. */
    private final char[] scratch = new char[8];
    private int bubbleLen;
    private final float density;

    private float fraction;
    private boolean thick;
    private boolean bubble;
    /** Позиция ручки в пикселях при последней отрисовке: по ней отсекаем ненужные invalidate(). */
    private int lastKnobPx = -1;

    public PlayerProgressView(Context context) {
        this(context, null);
    }

    public PlayerProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setColors(0xFF334351, 0xFF00DB4D, 0xFFEFEBE2);
        bubblePaint.setColor(0xFFEFEBE2);
        bubbleTextPaint.setColor(0xFF181E26);
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setTextSize(BUBBLE_TEXT_SP * context.getResources().getDisplayMetrics().scaledDensity);
    }

    public void setColors(int track, int fill, int knob) {
        trackPaint.setColor(track);
        fillPaint.setColor(fill);
        knobPaint.setColor(knob);
        invalidate();
    }

    /** Цвета пузыря перемотки: фон-капсула и текст на нём. */
    public void setBubbleColors(int background, int text) {
        bubblePaint.setColor(background);
        bubbleTextPaint.setColor(text);
        if (bubble) invalidate();
    }

    public void setBubbleTypeface(Typeface typeface) {
        bubbleTextPaint.setTypeface(typeface);
        if (bubble) invalidate();
    }

    /** @param value доля 0..1 */
    public void setFraction(float value) {
        float f = value < 0f ? 0f : (value > 1f ? 1f : value);
        fraction = f;
        if (knobPx(f) == lastKnobPx) return;
        invalidate();
    }

    /** Режим перемотки: полоса и ручка крупнее, чтобы было видно, что идёт перемотка. */
    public void setThick(boolean value) {
        if (thick == value) return;
        thick = value;
        invalidate();
    }

    /** Показывает над ручкой время цели перемотки. */
    public void showBubble(int ms) {
        int len = formatTime(ms);
        if (bubble && len == bubbleLen && sameDigits(len)) return;
        bubbleLen = len;
        System.arraycopy(scratch, 0, bubbleText, 0, len);
        bubble = true;
        invalidate();
    }

    public void hideBubble() {
        if (!bubble) return;
        bubble = false;
        invalidate();
    }

    private boolean sameDigits(int len) {
        for (int i = 0; i < len; i++) if (scratch[i] != bubbleText[i]) return false;
        return true;
    }

    /** Пишет mm:ss в scratch и возвращает длину. */
    private int formatTime(int ms) {
        int total = Math.max(0, ms) / 1000;
        int m = total / 60;
        int s = total % 60;
        int i = 0;
        if (m >= 100) scratch[i++] = (char) ('0' + (m / 100) % 10);
        scratch[i++] = (char) ('0' + (m / 10) % 10);
        scratch[i++] = (char) ('0' + m % 10);
        scratch[i++] = ':';
        scratch[i++] = (char) ('0' + s / 10);
        scratch[i++] = (char) ('0' + s % 10);
        return i;
    }

    private int knobPx(float f) {
        float knobR = (thick ? KNOB_SEEK_DP : KNOB_NORMAL_DP) * density;
        float span = Math.max(0f, getWidth() - 2f * knobR);
        return Math.round(knobR + span * f);
    }

    /** Центр полосы: она прижата к низу, над ней место под пузырь. */
    private float barCenterY() {
        return getHeight() - (KNOB_SEEK_DP + 1f) * density;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = Math.round((KNOB_SEEK_DP * 2f + 2f + BUBBLE_H_DP + BUBBLE_GAP_DP) * density);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float cy = barCenterY();
        float barH = (thick ? BAR_SEEK_DP : BAR_NORMAL_DP) * density;
        float knobR = (thick ? KNOB_SEEK_DP : KNOB_NORMAL_DP) * density;
        float r = barH / 2f;

        rect.set(0f, cy - r, w, cy + r);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        int kx = knobPx(fraction);
        lastKnobPx = kx;
        rect.set(0f, cy - r, Math.max(kx, barH), cy + r);
        canvas.drawRoundRect(rect, r, r, fillPaint);

        canvas.drawCircle(kx, cy, knobR, knobPaint);

        if (bubble && bubbleLen > 0) {
            float bh = BUBBLE_H_DP * density;
            float bw = bubbleTextPaint.measureText(bubbleText, 0, bubbleLen) + 2f * BUBBLE_PAD_DP * density;
            float left = Math.max(0f, Math.min(w - bw, kx - bw / 2f));
            float bottom = cy - knobR - BUBBLE_GAP_DP * density;
            rect.set(left, bottom - bh, left + bw, bottom);
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, bubblePaint);
            // Базовая линия по центру капсулы: середина между ascent и descent шрифта.
            float baseline = rect.centerY() - (bubbleTextPaint.ascent() + bubbleTextPaint.descent()) / 2f;
            canvas.drawText(bubbleText, 0, bubbleLen, rect.centerX(), baseline, bubbleTextPaint);
        }
    }
}
