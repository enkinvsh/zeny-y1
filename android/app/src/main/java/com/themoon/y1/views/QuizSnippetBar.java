package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.themoon.y1.managers.QuizGame;

/**
 * Шкала отрывка викторины: пять плоских капсул высотой 10 dp, ширина пропорциональна приросту ступени
 * (1, 2, 3, 4, 5 с из стадий 1/3/6/10/15 с). Услышанное жёлтое, открытое но ещё не сыгранное earth,
 * закрытые ступени water. Ни подписей, ни бегунка: только цвет.
 *
 * Услышанная часть растёт монотонно в пределах трека (heardMs): повтор отрывка с нуля не должен
 * «стирать» жёлтое, иначе после «Не знаю» шкала мигала бы серым.
 *
 * На Cortex-A7 шкала перерисовывается каждый кадр, пока играет отрывок, поэтому в onDraw нет ни одного
 * выделения памяти: прямоугольники считаются в onSizeChanged, кисти созданы заранее.
 */
public final class QuizSnippetBar extends View {

    /** Откуда брать позицию в отрывке: обычно QuizClipPlayer. */
    public interface Source {
        boolean isPlaying();

        long positionInClipMs();
    }

    private static final int N = QuizGame.STAGES;
    private static final int HEARD = 0xFFF7CE46;
    private static final int OPEN = 0xFFA4A3A0;
    private static final int LOCKED = 0xFF334351;

    /** Высота капсулы в dp; снаружи нужна, чтобы раскладка отвела ей ровно столько. */
    public static final int HEIGHT_DP = 10;

    private final float barHeight;
    private final float gap;
    private final RectF[] segs = new RectF[N];
    private final float[] segStartMs = new float[N];
    private final float[] segEndMs = new float[N];

    private final Paint lockedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint openPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint heardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Source source;
    private int stage;
    private long heardMs;

    public QuizSnippetBar(Context context) {
        this(context, null);
    }

    public QuizSnippetBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        barHeight = HEIGHT_DP * density;
        gap = 4 * density;
        for (int i = 0; i < N; i++) {
            segs[i] = new RectF();
            segStartMs[i] = i == 0 ? 0 : QuizGame.stageMs(i - 1);
            segEndMs[i] = QuizGame.stageMs(i);
        }
        lockedPaint.setColor(LOCKED);
        openPaint.setColor(OPEN);
        heardPaint.setColor(HEARD);
    }

    public void setSource(Source s) {
        source = s;
    }

    /** Новый трек: открыта только первая ступень, ничего не услышано. */
    public void reset() {
        stage = 0;
        heardMs = 0;
        invalidate();
    }

    public void setStage(int newStage) {
        stage = Math.max(0, Math.min(N - 1, newStage));
        invalidate();
    }

    /** Отрывок начал играть заново: перерисовка включает покадровое слежение за позицией. */
    public void onClipStarted() {
        invalidate();
    }

    /** Показать всю открытую часть услышанной (после окончания отрывка). */
    public void fillOpenStages() {
        heardMs = Math.max(heardMs, QuizGame.stageMs(stage));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((int) Math.ceil(barHeight), heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float left = getPaddingLeft();
        float usable = w - getPaddingLeft() - getPaddingRight() - gap * (N - 1);
        float total = QuizGame.stageMs(N - 1);
        float top = getPaddingTop();
        for (int i = 0; i < N; i++) {
            float sw = usable * (segEndMs[i] - segStartMs[i]) / total;
            segs[i].set(left, top, left + sw, top + barHeight);
            left += sw + gap;
        }
    }

    private float xForMs(long ms) {
        for (int i = 0; i < N; i++) {
            if (ms <= segEndMs[i]) {
                RectF r = segs[i];
                float f = (ms - segStartMs[i]) / (segEndMs[i] - segStartMs[i]);
                return r.left + Math.max(0f, f) * r.width();
            }
        }
        return segs[N - 1].right;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean playing = source != null && source.isPlaying();
        if (playing) heardMs = Math.max(heardMs, source.positionInClipMs());

        float radius = barHeight / 2f;
        float headX = xForMs(heardMs);
        for (int i = 0; i < N; i++) {
            RectF r = segs[i];
            canvas.drawRoundRect(r, radius, radius, i <= stage ? openPaint : lockedPaint);
            if (i > stage || heardMs <= 0 || headX <= r.left) continue;
            if (headX >= r.right) {
                canvas.drawRoundRect(r, radius, radius, heardPaint);
            } else {
                // Край услышанной части ровный, как у обычного прогресса; скругление остаётся у капсулы.
                canvas.save();
                canvas.clipRect(r.left, r.top, headX, r.bottom);
                canvas.drawRoundRect(r, radius, radius, heardPaint);
                canvas.restore();
            }
        }

        if (playing) postInvalidateOnAnimation();
    }
}
