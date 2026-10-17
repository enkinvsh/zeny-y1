package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import com.themoon.y1.managers.SpectrumBands;

/**
 * Спектр: 24 закруглённых столбика от нижнего края вида. Плоская отрисовка без градиентов и свечения.
 *
 * <p>Данные приходят из потока захвата Visualizer примерно 20 раз в секунду, а плавность даёт
 * интерполяция в onDraw (30 кадров/с, быстрый подъём и медленное падение). Кадры идут только пока
 * столбики движутся, чтобы на паузе процессор не просыпался ради перерисовки.
 */
public class AudioVisualizerView extends View {
    private static final int BARS = 24;
    private static final long FRAME_MS = 33;

    private final SpectrumBands bands = new SpectrumBands(BARS, 80f, 14000f, 3f);
    // target пишет поток захвата, shown читает и двигает UI-поток; гонка допустима, это просто картинка.
    private final float[] target = new float[BARS];
    private final float[] shown = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float maxBarPx = Float.MAX_VALUE;

    public AudioVisualizerView(Context context) {
        super(context);
        init();
    }

    public AudioVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
    }

    public void setBarColor(int color) {
        paint.setColor(color);
    }

    /** Предельная высота столбика в пикселях: вид может быть выше, но тянуться на всю высоту не должен. */
    public void setMaxBarHeight(float px) {
        maxBarPx = px;
    }

    /** Вызывается из потока захвата Visualizer. */
    public void updateVisualizer(byte[] fft, int sampleRateMilliHz) {
        bands.compute(fft, sampleRateMilliHz, target);
        postInvalidate();
    }

    /** Обнуляет столбики до точек, когда захват остановлен: иначе последний кадр завис бы на экране. */
    public void reset() {
        for (int i = 0; i < BARS; i++) target[i] = 0f;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float step = w / (float) BARS;
        float barW = step * 0.55f;
        float top = Math.min(h, maxBarPx);
        float radius = barW / 2f;
        boolean moving = false;
        for (int i = 0; i < BARS; i++) {
            float diff = target[i] - shown[i];
            // Подъём резкий, чтобы бит читался, падение плавное, чтобы столбики не дёргались.
            shown[i] += diff * (diff > 0 ? 0.55f : 0.2f);
            if (diff > 0.004f || diff < -0.004f) moving = true;
            float bar = Math.max(barW, shown[i] * top);
            float left = i * step + (step - barW) / 2f;
            rect.set(left, h - bar, left + barW, h);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        if (moving && getVisibility() == VISIBLE) postInvalidateDelayed(FRAME_MS);
    }
}
