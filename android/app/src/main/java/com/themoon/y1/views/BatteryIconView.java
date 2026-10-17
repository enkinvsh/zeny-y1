package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import com.themoon.y1.ThemeManager;

/**
 * Батарея в статус-баре: цифры заряда без «%» и значок в стиле Hugeicons (battery-full /
 * battery-low / battery-empty / battery-charging-01, сетка 24x24, обводка 1.5, скруглённые концы).
 * Контуры скопированы из free-набора, поэтому рисуем их в «единицах» сетки и масштабируем канвой:
 * обводка при этом остаётся пропорциональной значку на любом размере строки статуса.
 */
public class BatteryIconView extends View {
    // Область значка в единицах сетки: x 2..22, y 6..18 плюс половина обводки с каждой стороны.
    private static final float STROKE = 1.5f;
    private static final float ICON_W = 20f + STROKE;
    private static final float ICON_H = 12f + STROKE;
    private static final float ORIGIN_X = 2f - STROKE / 2f;
    private static final float ORIGIN_Y = 6f - STROKE / 2f;

    // Красный порог совпадает с прежним: ниже 20 % без зарядки значок и цифры предупреждают.
    private static final int LOW_LEVEL = 20;
    private static final int COLOR_LOW = 0xFFC24742;
    private static final int COLOR_ACCENT_CLASSIC = 0xFF4CAF50;

    private static final Path BODY = new Path();
    private static final Path TERMINAL = new Path();
    private static final Path BOLT = new Path();

    static {
        BODY.moveTo(2f, 12f);
        BODY.cubicTo(2f, 9.17157f, 2f, 7.75736f, 2.87868f, 6.87868f);
        BODY.cubicTo(3.75736f, 6f, 5.17157f, 6f, 8f, 6f);
        BODY.lineTo(13f, 6f);
        BODY.cubicTo(15.8284f, 6f, 17.2426f, 6f, 18.1213f, 6.87868f);
        BODY.cubicTo(19f, 7.75736f, 19f, 9.17157f, 19f, 12f);
        BODY.cubicTo(19f, 14.8284f, 19f, 16.2426f, 18.1213f, 17.1213f);
        BODY.cubicTo(17.2426f, 18f, 15.8284f, 18f, 13f, 18f);
        BODY.lineTo(8f, 18f);
        BODY.cubicTo(5.17157f, 18f, 3.75736f, 18f, 2.87868f, 17.1213f);
        BODY.cubicTo(2f, 16.2426f, 2f, 14.8284f, 2f, 12f);
        BODY.close();

        TERMINAL.moveTo(19f, 9.5f);
        TERMINAL.lineTo(20.0272f, 9.6712f);
        TERMINAL.cubicTo(20.7085f, 9.78475f, 21.0491f, 9.84152f, 21.3076f, 10.0067f);
        TERMINAL.cubicTo(21.5618f, 10.1691f, 21.7612f, 10.4044f, 21.8796f, 10.6819f);
        TERMINAL.cubicTo(22f, 10.964f, 22f, 11.3093f, 22f, 12f);
        TERMINAL.cubicTo(22f, 12.6907f, 22f, 13.036f, 21.8796f, 13.3181f);
        TERMINAL.cubicTo(21.7612f, 13.5956f, 21.5618f, 13.8309f, 21.3076f, 13.9933f);
        TERMINAL.cubicTo(21.0491f, 14.1585f, 20.7085f, 14.2153f, 20.0272f, 14.3288f);
        TERMINAL.lineTo(19f, 14.5f);

        BOLT.moveTo(10.8282f, 9f);
        BOLT.lineTo(9.08572f, 11.1749f);
        BOLT.cubicTo(8.89899f, 11.4079f, 9.03283f, 11.7433f, 9.33733f, 11.8053f);
        BOLT.lineTo(11.1627f, 12.1773f);
        BOLT.cubicTo(11.4873f, 12.2434f, 11.6111f, 12.6147f, 11.3842f, 12.8413f);
        BOLT.lineTo(9.22216f, 15f);
    }

    private int level = 100;
    private boolean isCharging = false;
    private int color = 0xFFFFFFFF;

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BatteryIconView(Context context) {
        super(context);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeCap(Paint.Cap.ROUND);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setStrokeWidth(STROKE);
        text.setTextAlign(Paint.Align.RIGHT);
    }

    public void setBatteryLevel(int level, boolean isCharging) {
        int clamped = Math.max(0, Math.min(100, level));
        boolean widthChanges = String.valueOf(clamped).length() != String.valueOf(this.level).length();
        this.level = clamped;
        this.isCharging = isCharging;
        if (widthChanges) requestLayout();
        invalidate();
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    private int accent() {
        return ThemeManager.isFlatTheme()
                ? (ThemeManager.getListButtonFocusedBg() | 0xFF000000)
                : COLOR_ACCENT_CLASSIC;
    }

    private float iconWidthFor(int height) {
        return height * ICON_W / ICON_H;
    }

    private float gap(int height) {
        return height * 0.22f;
    }

    private void prepareText(int height) {
        text.setTextSize(height * 0.8f);
        Typeface face = ThemeManager.getCustomFontBold();
        text.setTypeface(face != null ? face : Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (h <= 0) h = (int) (16 * getResources().getDisplayMetrics().density);
        prepareText(h);
        float digits = text.measureText(String.valueOf(level));
        setMeasuredDimension((int) Math.ceil(digits + gap(h) + iconWidthFor(h)), h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        boolean low = level <= LOW_LEVEL && !isCharging;
        int tone = low ? COLOR_LOW : color;

        // Цифры: базовая линия по центру строки, прижаты к значку справа.
        prepareText(h);
        text.setColor(tone);
        float iconW = iconWidthFor(h);
        float baseline = h / 2f - (text.ascent() + text.descent()) / 2f;
        canvas.drawText(String.valueOf(level), w - iconW - gap(h), baseline, text);

        float s = h / ICON_H;
        canvas.save();
        canvas.translate(w - iconW, 0f);
        canvas.scale(s, s);
        canvas.translate(-ORIGIN_X, -ORIGIN_Y);

        outline.setColor(tone);
        canvas.drawPath(BODY, outline);
        canvas.drawPath(TERMINAL, outline);

        if (isCharging) {
            outline.setColor(accent());
            canvas.drawPath(BOLT, outline);
        } else {
            // Секции — как в наборе: четыре штриха x = 6, 9, 12, 15 на высоте 10..14.
            int bars = (level + 24) / 25;
            outline.setColor(low ? COLOR_LOW : accent());
            for (int i = 0; i < bars; i++) {
                float x = 6f + 3f * i;
                canvas.drawLine(x, 10f, x, 14f, outline);
            }
        }
        canvas.restore();
    }
}
