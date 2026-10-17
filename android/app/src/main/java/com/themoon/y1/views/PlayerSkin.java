package com.themoon.y1.views;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;

/**
 * Перекрашивает экран Now Playing под тему. В activity_main.xml он залит белым и цвета текста
 * зашиты прямо в разметке (светлый iPod), поэтому для flat-тем (Zeny) всё переопределяется кодом,
 * а для остальных тем возвращаются исходные значения из разметки.
 */
public final class PlayerSkin {
    // Исходные цвета разметки: белый фон, чёрный основной текст, серый вторичный, синий акцент iPod.
    private static final int LIGHT_BG = 0xFFFFFFFF;
    private static final int LIGHT_PRIMARY = 0xFF000000;
    private static final int LIGHT_SECONDARY = 0xFF3C3C43;
    private static final int LIGHT_TERTIARY = 0xFF8E8E93;
    private static final int LIGHT_ACCENT = 0xFF6194D1;

    private PlayerSkin() {}

    public static void apply(Activity a) {
        boolean flat = ThemeManager.isFlatTheme();
        int bg = flat ? ThemeManager.getOverlayBackgroundColor() : LIGHT_BG;
        int primary = flat ? ThemeManager.getTextColorPrimary() : LIGHT_PRIMARY;
        int secondary = flat ? ThemeManager.getTextColorSecondary() : LIGHT_SECONDARY;
        int tertiary = flat ? ThemeManager.getTextColorSecondary() : LIGHT_TERTIARY;
        int accent = flat ? (ThemeManager.getListButtonFocusedBg() | 0xFF000000) : LIGHT_ACCENT;

        View root = a.findViewById(R.id.layout_player_mode);
        if (root != null) root.setBackgroundColor(bg);

        text(a, R.id.tv_player_title, primary);
        text(a, R.id.tv_player_artist, secondary);
        text(a, R.id.tv_player_album, secondary);
        text(a, R.id.tv_player_track_count, tertiary);
        text(a, R.id.tv_player_rating, accent);
        text(a, R.id.tv_player_time_current, primary);
        text(a, R.id.tv_player_time_total, primary);
        text(a, R.id.tv_player_seek_current, primary);
        text(a, R.id.tv_player_seek_total, primary);
        text(a, R.id.tv_scrub_shuffle_value, accent);
        text(a, R.id.tv_scrub_repeat_value, accent);
        int[] stars = {R.id.tv_scrub_star_1, R.id.tv_scrub_star_2, R.id.tv_scrub_star_3,
                R.id.tv_scrub_star_4, R.id.tv_scrub_star_5};
        for (int id : stars) text(a, id, accent);

        tint(a, R.id.iv_scrub_shuffle_icon, accent);
        tint(a, R.id.iv_scrub_repeat_icon, accent);

        // Серая подложка обложки из разметки торчала бы квадратными углами из-под скруглённой картинки.
        View art = a.findViewById(R.id.iv_album_art);
        if (art != null) art.setBackgroundDrawable(flat ? null : new ColorDrawable(0xFFDDDDDD));

        // Затухание отражения обложки зашито белым и на тёмном фоне даёт светлое пятно под картинкой.
        View reflection = a.findViewById(R.id.frame_album_reflection);
        // В плоской теме отражение прячется: на тёмной обложке оно читается как «сломанная полоса».
        if (reflection != null) reflection.setVisibility(flat ? View.GONE : View.VISIBLE);
        if (reflection instanceof ViewGroup && ((ViewGroup) reflection).getChildCount() > 1) {
            View fade = ((ViewGroup) reflection).getChildAt(1);
            int solid = bg | 0xFF000000;
            fade.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{solid & 0x00FFFFFF, solid}));
        }

        int drawableRes = flat ? R.drawable.progress_bar_flat : R.drawable.progress_bar_now_playing;
        bar(a, R.id.player_progress, drawableRes, flat, accent);
        bar(a, R.id.player_volume_progress, drawableRes, flat, accent);
        bar(a, R.id.seek_player_scrub, drawableRes, flat, accent);

        applyVolume(a, flat, primary, accent);
    }

    /** Значки динамика у полос громкости и карточка плавающей громкости вне Now Playing. */
    private static void applyVolume(Activity a, boolean flat, int primary, int accent) {
        float d = a.getResources().getDisplayMetrics().density;
        float stroke = 1.8f * d;
        icon(a, R.id.iv_player_volume_low, new VolumeIconDrawable(false, primary, stroke));
        icon(a, R.id.iv_player_volume_high, new VolumeIconDrawable(true, primary, stroke));

        // Плавающая громкость живёт на тёмном фоне при любой теме, поэтому иконки светлые.
        int onDark = flat ? ThemeManager.getTextColorPrimary() : 0xFFFFFFFF;
        icon(a, R.id.iv_volume_overlay_low, new VolumeIconDrawable(false, onDark, stroke));
        icon(a, R.id.iv_volume_overlay_high, new VolumeIconDrawable(true, onDark, stroke));

        View card = a.findViewById(R.id.layout_volume_overlay);
        if (card == null) return;
        if (flat) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF232C37);
            g.setStroke(Math.round(d), 0xFF2C3744);
            // Skia зажимает радиус половиной высоты: низкая карточка с радиусом 26dp выходит капсулой.
            g.setCornerRadius(ThemeManager.getButtonRadius() * d);
            card.setBackgroundDrawable(g);
            bar(a, R.id.volume_progress, R.drawable.progress_bar_flat, true, accent);
        } else {
            card.setBackgroundColor(0xCC000000);
        }
    }

    private static void icon(Activity a, int id, Drawable drawable) {
        View v = a.findViewById(id);
        if (v instanceof ImageView) ((ImageView) v).setImageDrawable(drawable);
    }

    private static void text(Activity a, int id, int color) {
        View v = a.findViewById(id);
        if (v instanceof TextView) ((TextView) v).setTextColor(color);
    }

    private static void tint(Activity a, int id, int color) {
        View v = a.findViewById(id);
        if (v instanceof ImageView) ((ImageView) v).setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
    }

    private static void bar(Activity a, int id, int drawableRes, boolean flat, int accent) {
        View v = a.findViewById(id);
        if (!(v instanceof ProgressBar)) return;
        ProgressBar bar = (ProgressBar) v;
        // setProgressDrawable сбрасывает позицию отрисовки, поэтому значение запоминается и возвращается.
        int progress = bar.getProgress();
        Drawable d = a.getResources().getDrawable(drawableRes);
        bar.setProgressDrawable(d);
        bar.setProgress(0);
        bar.setProgress(progress);
        if (flat && d instanceof LayerDrawable) {
            Drawable fill = ((LayerDrawable) d).findDrawableByLayerId(android.R.id.progress);
            if (fill != null) fill.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        }
    }
}
