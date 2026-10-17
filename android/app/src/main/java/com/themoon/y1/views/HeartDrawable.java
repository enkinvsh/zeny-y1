package com.themoon.y1.views;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Сердце «в избранном» для Now Playing. Рисуется путём, а не глифом ♡/♥: в шрифте темы этих
 * символов нет, и системный запасной глиф на Android 4.2 имел чужие метрики и обрезался.
 */
public final class HeartDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean filled;
    private final float stroke;

    /** @param stroke толщина контура в пикселях; у залитого сердца не используется */
    public HeartDrawable(boolean filled, int color, float stroke) {
        this.filled = filled;
        this.stroke = stroke;
        paint.setColor(color);
        paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        // Контур не должен выходить за bounds: половина толщины линии уходит наружу от пути.
        float inset = filled ? 0f : stroke / 2f;
        float x = b.left + inset;
        float y = b.top + inset;
        float w = b.width() - 2 * inset;
        float h = b.height() - 2 * inset;

        // Форма задана в единичном квадрате, поэтому одинакова при любой плотности экрана.
        path.reset();
        path.moveTo(x + 0.50f * w, y + 0.94f * h);
        path.cubicTo(x + 0.50f * w, y + 0.94f * h, x + 0.02f * w, y + 0.62f * h, x + 0.02f * w, y + 0.30f * h);
        path.cubicTo(x + 0.02f * w, y + 0.12f * h, x + 0.16f * w, y + 0.02f * h, x + 0.28f * w, y + 0.02f * h);
        path.cubicTo(x + 0.40f * w, y + 0.02f * h, x + 0.50f * w, y + 0.10f * h, x + 0.50f * w, y + 0.22f * h);
        path.cubicTo(x + 0.50f * w, y + 0.10f * h, x + 0.60f * w, y + 0.02f * h, x + 0.72f * w, y + 0.02f * h);
        path.cubicTo(x + 0.84f * w, y + 0.02f * h, x + 0.98f * w, y + 0.12f * h, x + 0.98f * w, y + 0.30f * h);
        path.cubicTo(x + 0.98f * w, y + 0.62f * h, x + 0.50f * w, y + 0.94f * h, x + 0.50f * w, y + 0.94f * h);
        path.close();
        canvas.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
