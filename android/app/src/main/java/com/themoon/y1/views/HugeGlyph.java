package com.themoon.y1.views;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * Голый глиф Hugeicons (@hugeicons/core-free-icons, MIT): путь взят из набора как есть, сетка
 * 24x24, линия 1.5, круглые концы. Никаких кругов и плиток вокруг иконки (правило брендбука zeny).
 * Цвет меняется без пересоздания, а Path строится один раз: рисование не аллоцирует.
 */
public final class HugeGlyph extends Drawable {
    private static final float GRID = 24f;
    /** Толщина линии набора в единицах сетки. */
    private static final float GRID_STROKE = 1.5f;

    public static final String[] HEART = {
        "M10.4107 19.9677C7.58942 17.858 2 13.0348 2 8.69444C2 5.82563 4.10526 3.5 7 3.5C8.5 3.5 10 4 12 6C14 4 15.5 3.5 17 3.5C19.8947 3.5 22 5.82563 22 8.69444C22 13.0348 16.4106 17.858 13.5893 19.9677C12.6399 20.6776 11.3601 20.6776 10.4107 19.9677Z"};
    public static final String[] SHUFFLE = {
        "M19.5576 4L20.4551 4.97574C20.8561 5.41165 21.0566 5.62961 20.9861 5.81481C20.9155 6 20.632 6 20.0649 6C18.7956 6 17.2771 5.79493 16.1111 6.4733C15.3903 6.89272 14.8883 7.62517 14.0392 9M3 18H4.58082C6.50873 18 7.47269 18 8.2862 17.5267C9.00708 17.1073 9.50904 16.3748 10.3582 15",
        "M19.5576 20L20.4551 19.0243C20.8561 18.5883 21.0566 18.3704 20.9861 18.1852C20.9155 18 20.632 18 20.0649 18C18.7956 18 17.2771 18.2051 16.1111 17.5267C15.2976 17.0534 14.7629 16.1815 13.6935 14.4376L10.7038 9.5624C9.63441 7.81853 9.0997 6.9466 8.2862 6.4733C7.47269 6 6.50873 6 4.58082 6H3"};
    public static final String[] REPEAT = {
        "M16.3884 3L17.3913 3.97574C17.8393 4.41165 18.0633 4.62961 17.9844 4.81481C17.9056 5 17.5888 5 16.9552 5H9.19422C5.22096 5 2 8.13401 2 12C2 13.4872 2.47668 14.8662 3.2895 16",
        "M7.61156 21L6.60875 20.0243C6.16074 19.5883 5.93673 19.3704 6.01557 19.1852C6.09441 19 6.4112 19 7.04478 19H14.8058C18.779 19 22 15.866 22 12C22 10.5128 21.5233 9.13383 20.7105 8"};
    public static final String[] REPEAT_ONE = {
        REPEAT[0], REPEAT[1],
        "M13 15L13 9.31633C13 9.05613 12.7178 8.90761 12.52 9.06373L11 10.2636"};
    public static final String[] PAUSE = {
        "M4 7C4 5.58579 4 4.87868 4.43934 4.43934C4.87868 4 5.58579 4 7 4C8.41421 4 9.12132 4 9.56066 4.43934C10 4.87868 10 5.58579 10 7V17C10 18.4142 10 19.1213 9.56066 19.5607C9.12132 20 8.41421 20 7 20C5.58579 20 4.87868 20 4.43934 19.5607C4 19.1213 4 18.4142 4 17V7Z",
        "M14 7C14 5.58579 14 4.87868 14.4393 4.43934C14.8787 4 15.5858 4 17 4C18.4142 4 19.1213 4 19.5607 4.43934C20 4.87868 20 5.58579 20 7V17C20 18.4142 20 19.1213 19.5607 19.5607C19.1213 20 18.4142 20 17 20C15.5858 20 14.8787 20 14.4393 19.5607C14 19.1213 14 18.4142 14 17V7Z"};
    public static final String[] PLAY = {
        "M18.8906 12.846C18.5371 14.189 16.8667 15.138 13.5257 17.0361C10.296 18.8709 8.6812 19.7884 7.37983 19.4196C6.8418 19.2671 6.35159 18.9776 5.95624 18.5787C5 17.6139 5 15.7426 5 12C5 8.2574 5 6.3861 5.95624 5.42132C6.35159 5.02245 6.8418 4.73288 7.37983 4.58042C8.6812 4.21165 10.296 5.12907 13.5257 6.96393C16.8667 8.86197 18.5371 9.811 18.8906 11.154C19.0365 11.7084 19.0365 12.2916 18.8906 12.846Z"};
    public static final String[] TICK = {"M5 14L8.5 17.5L19 6.5"};
    public static final String[] CROSS = {"M18 6L6.00081 17.9992M17.9992 18L6 6.00085"};
    public static final String[] FLASH = {
        "M5.22576 11.3294L12.224 2.34651C12.7713 1.64397 13.7972 2.08124 13.7972 3.01707V9.96994C13.7972 10.5305 14.1995 10.985 14.6958 10.985H18.0996C18.8729 10.985 19.2851 12.0149 18.7742 12.6706L11.776 21.6535C11.2287 22.356 10.2028 21.9188 10.2028 20.9829V14.0301C10.2028 13.4695 9.80048 13.015 9.3042 13.015H5.90035C5.12711 13.015 4.71494 11.9851 5.22576 11.3294Z"};
    public static final String[] BOOK = {
        "M7.99978 3.5H6.60021C4.43183 3.5 3.34764 3.5 2.67399 4.17362C2.00034 4.84724 2.00029 5.93144 2.00021 8.09982L2 13.3998C1.99992 15.5684 1.99987 16.6526 2.67353 17.3263C3.34719 18 4.43146 18 6.6 18H8.95042C10.4329 18 11.7092 19.0464 11.9999 20.5V5.5C11.0556 4.24097 9.99989 3.5 7.99978 3.5Z",
        "M16.0001 3.5H17.3997C19.5681 3.5 20.6523 3.5 21.3259 4.17362C21.9996 4.84724 21.9996 5.93144 21.9997 8.09982L21.9999 13.3998C22 15.5684 22 16.6526 21.3264 17.3263C20.6527 18 19.5684 18 17.3999 18H15.0495C13.567 18 12.2907 19.0464 12 20.5V5.5C12.9443 4.24097 14 3.5 16.0001 3.5Z"};
    public static final String[] MOON = {
        "M17.7422 2.25L18.037 3.0466C18.4235 4.09117 18.6167 4.61345 18.9977 4.99445C19.3787 5.37545 19.901 5.56871 20.9456 5.95523L21.7422 6.25L20.9456 6.54477C19.901 6.93129 19.3787 7.12455 18.9977 7.50555C18.6167 7.88655 18.4235 8.40883 18.037 9.4534L17.7422 10.25L17.4474 9.4534C17.0609 8.40883 16.8676 7.88655 16.4866 7.50555C16.1056 7.12455 15.5834 6.93129 14.5388 6.54477L13.7422 6.25L14.5388 5.95523C15.5834 5.56871 16.1056 5.37545 16.4866 4.99445C16.8676 4.61345 17.0609 4.09117 17.4474 3.0466L17.7422 2.25Z",
        "M21.2422 14.3284C20.0425 14.9689 18.6723 15.3321 17.2173 15.3321C12.4913 15.3321 8.66011 11.5009 8.66011 6.77485C8.66011 5.31986 9.02324 3.94968 9.66382 2.75C5.40984 3.74698 2.24219 7.56513 2.24219 12.1231C2.24219 17.4399 6.55229 21.75 11.8691 21.75C16.4271 21.75 20.2452 18.5824 21.2422 14.3284Z"};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] paths;
    private boolean filled;
    private final float strokePx;
    private int color;

    /**
     * @param glyph    один из HugeGlyph.* (пути набора)
     * @param color    цвет линии (и заливки, если filled)
     * @param strokePx толщина линии в пикселях экрана; 0 или меньше: как в наборе (1.5 от 24)
     * @param filled   заливать контур тем же цветом (сердце «в любимом»)
     */
    public HugeGlyph(String[] glyph, int color, float strokePx, boolean filled) {
        this.filled = filled;
        this.strokePx = strokePx;
        this.color = color;
        paths = new Path[glyph.length];
        for (int i = 0; i < glyph.length; i++) paths[i] = build(SvgPathData.parse(glyph[i]));
        paint.setColor(color);
        paint.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public HugeGlyph(String[] glyph, int color) {
        this(glyph, color, 0f, false);
    }

    private static Path build(List<float[]> segs) {
        Path p = new Path();
        for (float[] s : segs) {
            switch ((int) s[0]) {
                case SvgPathData.MOVE: p.moveTo(s[1], s[2]); break;
                case SvgPathData.LINE: p.lineTo(s[1], s[2]); break;
                case SvgPathData.CUBIC: p.cubicTo(s[1], s[2], s[3], s[4], s[5], s[6]); break;
                case SvgPathData.QUAD: p.quadTo(s[1], s[2], s[3], s[4]); break;
                default: p.close(); break;
            }
        }
        return p;
    }

    /** Залитый или контурный вариант того же пути (сердце: в любимом или нет) без нового Drawable. */
    public void setFilled(boolean value) {
        if (value == filled) return;
        filled = value;
        paint.setStyle(value ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        invalidateSelf();
    }

    public void setColor(int newColor) {
        if (newColor == color) return;
        color = newColor;
        paint.setColor(newColor);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float side = Math.min(b.width(), b.height());
        float scale = side / GRID;
        // Толщину считаем в сетке: после scale() линия получается нужной в пикселях экрана.
        paint.setStrokeWidth((strokePx > 0 ? strokePx : GRID_STROKE * scale) / scale);
        canvas.save();
        canvas.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f);
        canvas.scale(scale, scale);
        for (Path p : paths) canvas.drawPath(p, paint);
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
