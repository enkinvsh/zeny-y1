package com.themoon.y1.views;

import android.content.Context;
import android.util.AttributeSet;

// Обложка Now Playing: всегда квадрат независимо от пропорций исходника и контейнера;
// вместе с scaleType=centerCrop любой источник обрезается ровно под квадрат.
// Углы в плоских темах скругляет RoundedImageView.
public class SquareImageView extends RoundedImageView {
    public SquareImageView(Context context) {
        super(context);
    }

    public SquareImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SquareImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int size = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(size, size);
    }
}
