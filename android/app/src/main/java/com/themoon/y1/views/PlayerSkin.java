package com.themoon.y1.views;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.themoon.y1.MainActivity;
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
    // water из брендбука zeny: дорожка прогресса и громкости; в config.json темы такого цвета нет.
    private static final int WATER = 0xFF334351;
    private static final String FONT_KEEP_TAG = MainActivity.FONT_KEEP_TAG;

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
        text(a, R.id.tv_player_artist, flat ? accent : secondary);
        text(a, R.id.tv_player_album, secondary);
        text(a, R.id.tv_player_track_count, tertiary);
        text(a, R.id.tv_player_time_current, primary);
        text(a, R.id.tv_player_time_total, primary);
        text(a, R.id.tv_flat_time_elapsed, primary);
        text(a, R.id.tv_flat_track_pos, secondary);
        text(a, R.id.tv_flat_time_left, secondary);

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

        applyVolume(a, flat, primary, accent);
        applyGeometry(a, flat, bg, primary, accent);
    }

    /**
     * Раскладка экрана. Плоская тема: сетка 66 px (поля и зазоры 12, контент ниже строки
     * состояния с y 44), обложка 222 слева, колонка текста x 252..468, внизу капсула прогресса.
     * Остальные темы получают обратно значения из activity_main.xml: тему можно сменить на ходу,
     * и без возврата классика унаследовала бы плоскую геометрию.
     */
    private static void applyGeometry(Activity a, boolean flat, int bg, int primary, int accent) {
        float d = a.getResources().getDisplayMetrics().density;

        View content = a.findViewById(R.id.layout_player_content);
        if (content instanceof LinearLayout) {
            ((LinearLayout) content).setGravity(flat ? Gravity.TOP : Gravity.CENTER_VERTICAL);
            if (flat) content.setPadding(px(12, d), px(44, d), px(12, d), 0);
            else content.setPadding(px(14, d), px(16, d), px(14, d), px(46, d));
        }
        margins(a, R.id.layout_player_cover_column, flat ? 0 : 10, flat ? 18 : 14, d);
        size(a, R.id.frame_album_art, flat ? 222 : 220, d);
        margins(a, R.id.layout_player_text_column, flat ? 0 : 28, 0, d);
        // Высота колонки равна обложке: спектр с weight=1 заполняет остаток, и его основание
        // совпадает с нижним краем обложки (y 266), как на утверждённом макете.
        View column = a.findViewById(R.id.layout_player_text_column);
        if (column != null) {
            ViewGroup.LayoutParams clp = column.getLayoutParams();
            clp.height = flat ? px(222, d) : ViewGroup.LayoutParams.WRAP_CONTENT;
            column.setLayoutParams(clp);
        }
        View spectrum = a.findViewById(R.id.view_flat_spectrum);
        if (spectrum instanceof AudioVisualizerView) {
            AudioVisualizerView sv = (AudioVisualizerView) spectrum;
            sv.setVisibility(flat ? View.VISIBLE : View.GONE);
            sv.setBarColor(accent);
            sv.setMaxBarHeight(108 * d);
        }

        // Текст песни в плоской теме показывает MainActivity (строка под альбомом или весь экран);
        // классика рисует свой старый вид, поэтому плоские вью в ней всегда скрыты, а содержимое на месте.
        if (!flat) {
            visible(a, R.id.view_lyrics_snippet, false);
            visible(a, R.id.view_lyrics_full, false);
            if (content != null) content.setVisibility(View.VISIBLE);
        }

        Typeface regular = orDefault(ThemeManager.getCustomFont());
        Typeface semi = orDefault(ThemeManager.getCustomFontBold());
        View title = a.findViewById(R.id.tv_player_title);
        if (title instanceof MarqueeTextView) {
            MarqueeTextView t = (MarqueeTextView) title;
            // До двух строк в плоской теме, длиннее бежит; в классике всегда одна строка с бегом.
            t.setWrapLines(flat ? 2 : 1);
            font(t, flat ? orDefault(ThemeManager.getCustomFontTitle()) : null, Typeface.BOLD, flat);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        }
        TextView artist = textView(a, R.id.tv_player_artist);
        if (artist != null) {
            font(artist, flat ? semi : null, Typeface.NORMAL, flat);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            topMargin(artist, flat ? 6 : 4, d);
        }
        TextView album = textView(a, R.id.tv_player_album);
        if (album != null) {
            font(album, flat ? regular : null, Typeface.NORMAL, flat);
            album.setTextSize(TypedValue.COMPLEX_UNIT_SP, flat ? 13f : 15f);
            topMargin(album, flat ? 2 : 4, d);
        }
        TextView elapsed = textView(a, R.id.tv_flat_time_elapsed);
        if (elapsed != null) font(elapsed, semi, Typeface.NORMAL, true);
        TextView pos = textView(a, R.id.tv_flat_track_pos);
        if (pos != null) font(pos, regular, Typeface.NORMAL, true);
        TextView left = textView(a, R.id.tv_flat_time_left);
        if (left != null) font(left, semi, Typeface.NORMAL, true);

        visible(a, R.id.row_player_track_count, !flat);
        visible(a, R.id.row_player_progress, !flat);
        visible(a, R.id.layout_player_flat_bottom, flat);
        visible(a, R.id.iv_cover_like, flat);
        if (!flat) visible(a, R.id.view_cover_dim, false);

        View progress = a.findViewById(R.id.pv_player_progress);
        if (progress instanceof PlayerProgressView) {
            PlayerProgressView p = (PlayerProgressView) progress;
            p.setColors(WATER, accent, primary);
            p.setBubbleColors(primary, bg | 0xFF000000);
            p.setBubbleTypeface(semi);
        }

        View dim = a.findViewById(R.id.view_cover_dim);
        if (dim != null && flat) {
            // ~60 % ink поверх обложки с тем же радиусом, что у RoundedImageView, иначе углы светятся.
            GradientDrawable g = new GradientDrawable();
            g.setColor(((bg | 0xFF000000) & 0x00FFFFFF) | 0x99000000);
            g.setCornerRadius(ThemeManager.getButtonRadius() * d);
            dim.setBackgroundDrawable(g);
        }
        View pause = a.findViewById(R.id.iv_pause_overlay);
        if (pause instanceof ImageView) {
            ImageView iv = (ImageView) pause;
            ViewGroup.LayoutParams lp = iv.getLayoutParams();
            lp.width = lp.height = px(flat ? 46 : 56, d);
            iv.setLayoutParams(lp);
            if (flat) iv.setImageDrawable(new HugeGlyph(HugeGlyph.PAUSE, primary));
            else iv.setImageResource(android.R.drawable.ic_media_pause);
        }

        applyVolumeRowGeometry(a, flat, d);
    }

    /**
     * Строка громкости. В плоской теме она на 2 с встаёт на место строки времени под полосой
     * прогресса (y 315), полоса остаётся видна; в классике заменяет весь низ, как в iPod.
     */
    private static void applyVolumeRowGeometry(Activity a, boolean flat, float d) {
        View row = a.findViewById(R.id.row_player_volume);
        if (row == null || !(row.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) row.getLayoutParams();
        lp.gravity = flat ? Gravity.TOP : Gravity.BOTTOM;
        lp.topMargin = flat ? px(315, d) : 0;
        row.setLayoutParams(lp);
        if (flat) row.setPadding(px(16, d), 0, px(16, d), 0);
        else row.setPadding(px(26, d), 0, px(26, d), px(14, d));

        int icon = flat ? 18 : 22;
        int gap = flat ? 8 : 10;
        View low = a.findViewById(R.id.iv_player_volume_low);
        if (low != null) {
            LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) low.getLayoutParams();
            l.width = l.height = px(icon, d);
            l.rightMargin = px(gap, d);
            low.setLayoutParams(l);
        }
        View high = a.findViewById(R.id.iv_player_volume_high);
        if (high != null) {
            LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) high.getLayoutParams();
            l.width = l.height = px(icon, d);
            l.leftMargin = px(gap, d);
            high.setLayoutParams(l);
        }
        View bar = a.findViewById(R.id.player_volume_progress);
        if (bar instanceof ProgressBar) {
            ViewGroup.LayoutParams l = bar.getLayoutParams();
            l.height = px(flat ? 8 : 16, d);
            bar.setLayoutParams(l);
            Drawable pd = ((ProgressBar) bar).getProgressDrawable();
            if (flat && pd instanceof LayerDrawable) {
                Drawable track = ((LayerDrawable) pd).findDrawableByLayerId(android.R.id.background);
                if (track != null) track.setColorFilter(WATER, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    private static int px(float dp, float density) {
        return Math.round(dp * density);
    }

    private static Typeface orDefault(Typeface t) {
        return t != null ? t : Typeface.DEFAULT;
    }

    /**
     * Шрифт и метка keep_font. Плоские подписи помечаются, чтобы общий проход applyFontToAllViews
     * не заменил Unbounded/Onest шрифтом темы и не добавил искусственный жирный; в классике метка
     * снимается и стиль из разметки возвращается.
     */
    private static void font(TextView v, Typeface face, int style, boolean keep) {
        if (face != null) v.setTypeface(face, Typeface.NORMAL);
        else v.setTypeface(null, style);
        v.setTag(keep ? FONT_KEEP_TAG : null);
    }

    private static TextView textView(Activity a, int id) {
        View v = a.findViewById(id);
        return v instanceof TextView ? (TextView) v : null;
    }

    private static void visible(Activity a, int id, boolean show) {
        View v = a.findViewById(id);
        if (v != null) v.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static void margins(Activity a, int id, int topDp, int rightDp, float d) {
        View v = a.findViewById(id);
        if (v == null || !(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = px(topDp, d);
        lp.rightMargin = px(rightDp, d);
        v.setLayoutParams(lp);
    }

    private static void topMargin(View v, int topDp, float d) {
        if (!(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = px(topDp, d);
        v.setLayoutParams(lp);
    }

    private static void size(Activity a, int id, int sideDp, float d) {
        View v = a.findViewById(id);
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = lp.height = px(sideDp, d);
        v.setLayoutParams(lp);
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
