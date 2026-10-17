package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.themoon.y1.ThemeManager;

/**
 * Обложка со скруглёнными углами для плоских тем (Zeny: радиус 26dp у любого прямоугольника).
 * Скругление рисуется шейдером, а не clipPath: на Android 4.2 с аппаратным ускорением clipPath
 * по непрямоугольной области не работает. Радиус берётся из темы на каждой отрисовке, поэтому
 * смена темы действует без пересоздания видов; в неплоских темах рисуется как обычный ImageView.
 */
public class RoundedImageView extends ImageView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap shaderBitmap;
    private BitmapShader shader;

    public RoundedImageView(Context context) {
        super(context);
    }

    public RoundedImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RoundedImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = getDrawable();
        if (!ThemeManager.isFlatTheme() || !(d instanceof BitmapDrawable)) {
            super.onDraw(canvas);
            return;
        }
        Bitmap bmp = ((BitmapDrawable) d).getBitmap();
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        float vw = getWidth() - getPaddingLeft() - getPaddingRight();
        float vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (bmp == null || bmp.isRecycled() || dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) {
            super.onDraw(canvas);
            return;
        }

        // getImageMatrix() на Android 4.2 отдаёт только пользовательскую матрицу (ScaleType.MATRIX),
        // а не ту, по которой ImageView рисует, поэтому масштаб и сдвиг считаются здесь сами.
        float sx;
        float sy;
        switch (getScaleType()) {
            case CENTER_CROP:
                sx = sy = Math.max(vw / dw, vh / dh);
                break;
            case FIT_XY:
                sx = vw / dw;
                sy = vh / dh;
                break;
            case CENTER:
                sx = sy = 1f;
                break;
            case CENTER_INSIDE:
                sx = sy = Math.min(1f, Math.min(vw / dw, vh / dh));
                break;
            default:
                sx = sy = Math.min(vw / dw, vh / dh);
                break;
        }
        float dx = (vw - dw * sx) / 2f;
        float dy = (vh - dh * sy) / 2f;

        if (bmp != shaderBitmap) {
            shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaderBitmap = bmp;
        }
        // Размер BitmapDrawable в dp может отличаться от размера битмапа в пикселях (плотность ресурса).
        matrix.setScale(sx * dw / bmp.getWidth(), sy * dh / bmp.getHeight());
        matrix.postTranslate(dx, dy);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);

        // Видимая часть: картинка может выходить за вид (CENTER_CROP), а углы должны быть у вида.
        dst.set(dx, dy, dx + dw * sx, dy + dh * sy);
        dst.intersect(0, 0, vw, vh);
        float radius = Math.min(ThemeManager.getButtonRadius() * getResources().getDisplayMetrics().density,
                Math.min(dst.width(), dst.height()) / 2f);

        int save = canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        canvas.drawRoundRect(dst, radius, radius, paint);
        canvas.restoreToCount(save);
    }
}
