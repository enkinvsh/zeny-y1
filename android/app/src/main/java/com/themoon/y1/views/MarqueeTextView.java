package com.themoon.y1.views;

import android.content.Context;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * Текст, который сам решает, как себя показать: влез в заданное число строк, значит просто
 * переносится; не влез, значит одна строка с бегущей строкой (marquee).
 *
 * Режим пересчитывается на любой setText, а не только на setSmartText: заголовок трека пишут
 * напрямую из AudioPlayerManager. Бегущая строка в TextView работает только у выделенного (или
 * сфокусированного) вида, а у экрана без касаний фокуса нет, поэтому вид выделяется сам.
 * Перезапуск делается через post: сразу после setText layout ещё старый, и marquee стартовал бы
 * с прежней шириной текста.
 */
public class MarqueeTextView extends TextView {
    private int wrapLines = 1;
    private boolean marquee;
    /** TextView зовёт setText из своего конструктора, когда поля этого класса ещё не готовы. */
    private boolean ready;
    /** setSingleLine внутри переключения режима сам вызывает setText: без флага была бы рекурсия. */
    private boolean applying;

    private final Runnable restartMarquee = new Runnable() {
        @Override
        public void run() {
            if (!marquee) return;
            setSelected(false);
            setSelected(true);
        }
    };

    public MarqueeTextView(Context context) {
        this(context, null);
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ready = true;
        applyMode();
    }

    /**
     * @param lines сколько строк разрешено переносу; 1 значит всегда одна строка (при нехватке
     *              места бежит), 2 и больше значит переносить, а бежать только если и так не влезло
     */
    public void setWrapLines(int lines) {
        int value = Math.max(1, lines);
        if (value == wrapLines) return;
        wrapLines = value;
        applyMode();
    }

    /** То же, что setText: режим всё равно выбирается в onTextChanged. */
    public void setSmartText(CharSequence text) {
        setText(text == null ? "" : text);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (ready) applyMode();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Ширина стала известна позже текста (первый показ экрана): режим считаем заново.
        if (w != oldw) applyMode();
    }

    private void applyMode() {
        if (applying) return;
        applying = true;
        try {
            if (wrapLines <= 1 || overflowsWrap()) {
                enterMarquee();
            } else {
                enterWrap();
            }
        } finally {
            applying = false;
        }
    }

    /** Не влезет ли текст в wrapLines строк при текущей ширине. До первой раскладки считаем, что влезет. */
    private boolean overflowsWrap() {
        int width = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (width <= 0) return false;
        // Одноразовый StaticLayout на смену трека: дешевле, чем гонять relayout ради getLineCount().
        Layout probe = new StaticLayout(getText(), getPaint(), width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        return probe.getLineCount() > wrapLines;
    }

    private void enterWrap() {
        removeCallbacks(restartMarquee);
        if (!marquee && getMaxLines() == wrapLines) return;
        marquee = false;
        setSelected(false);
        setSingleLine(false);
        setMaxLines(wrapLines);
        setEllipsize(TextUtils.TruncateAt.END);
    }

    private void enterMarquee() {
        if (!marquee) {
            marquee = true;
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
            setMarqueeRepeatLimit(-1);
        }
        removeCallbacks(restartMarquee);
        post(restartMarquee);
    }
}
