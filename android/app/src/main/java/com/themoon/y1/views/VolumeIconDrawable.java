package com.themoon.y1.views;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Динамик для полосы громкости. Эмодзи 🔈/🔊 в строке громкости Now Playing на Android 4.2
 * рисовались системным запасным шрифтом и обрезались у нижнего края, поэтому значок строится путём.
 * Сетка 24x24, контур с круглыми концами — в одном стиле с остальными иконками темы.
 */
public final class VolumeIconDrawable extends Drawable {
    private static final float GRID = 24f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path speaker = new Path();
    private final Path waveNear = new Path();
    private final Path waveFar = new Path();
    private final boolean loud;
    private final float stroke;

    /**
     * @param loud   true — две волны («громко»), false — одна («тихо»)
     * @param stroke толщина контура в пикселях
     */
    public VolumeIconDrawable(boolean loud, int color, float stroke) {
        this.loud = loud;
        this.stroke = stroke;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);

        speaker.moveTo(3f, 9.5f);
        speaker.lineTo(6.5f, 9.5f);
        speaker.lineTo(11.5f, 5.5f);
        speaker.lineTo(11.5f, 18.5f);
        speaker.lineTo(6.5f, 14.5f);
        speaker.lineTo(3f, 14.5f);
        speaker.close();

        waveNear.moveTo(14.8f, 9.4f);
        waveNear.quadTo(16.8f, 12f, 14.8f, 14.6f);

        waveFar.moveTo(17.8f, 6.8f);
        waveFar.quadTo(22.2f, 12f, 17.8f, 17.2f);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        // Рисуем в сетке 24x24, а толщину линии берём в пикселях экрана, а не сетки.
        float side = Math.min(b.width(), b.height());
        float scale = side / GRID;
        paint.setStrokeWidth(stroke / scale);
        canvas.save();
        canvas.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f);
        canvas.scale(scale, scale);
        canvas.drawPath(speaker, paint);
        canvas.drawPath(waveNear, paint);
        if (loud) canvas.drawPath(waveFar, paint);
        canvas.restore();
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
