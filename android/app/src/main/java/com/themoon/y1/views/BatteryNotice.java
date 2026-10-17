package com.themoon.y1.views;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;

/**
 * Карточка «мало заряда» и «неверная зарядка» вместо белых английских окон SystemUI (в нём вырезан
 * показ обоих, см. tools/systemui). Любая кнопка закрывает карточку; сама она тоже уходит через
 * {@link #AUTO_DISMISS_MS}, чтобы не висеть над плеером, если владелец не смотрит на экран.
 */
public final class BatteryNotice {
    public interface Host {
        String t(String key);

        /** Фальшивое «выключение экрана» должно оставаться чёрным, поверх него ничего не рисуем. */
        boolean screenIsBlanked();
    }

    public static final int LOW = 0, CHARGER = 1;

    private static final long AUTO_DISMISS_MS = 9000;
    private static final int CREAM = 0xFFEFEBE2;
    private static final int RAISED = 0xFF232C37;
    private static final int BORDER = 0xFF2C3744;

    private final Activity act;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoDismiss = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    private Dialog dialog;
    private int shownKind = -1;
    private TextView percentView;

    public BatteryNotice(Activity act, Host host) {
        this.act = act;
        this.host = host;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** Показывает карточку; если она уже на экране с тем же видом, обновляет только процент. */
    public void show(int kind, int percent) {
        if (host.screenIsBlanked() || act.isFinishing()) return;
        if (isShowing() && shownKind == kind) {
            if (percentView != null) percentView.setText(percent + "%");
            handler.removeCallbacks(autoDismiss);
            handler.postDelayed(autoDismiss, AUTO_DISMISS_MS);
            return;
        }
        dismiss();
        try {
            dialog = build(kind, percent);
            shownKind = kind;
            dialog.show();
        } catch (RuntimeException e) {
            // Активность могла уйти в фон именно сейчас: карточка не стоит падения плеера.
            dialog = null;
            shownKind = -1;
            return;
        }
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS);
    }

    public void dismiss() {
        handler.removeCallbacks(autoDismiss);
        Dialog d = dialog;
        dialog = null;
        shownKind = -1;
        percentView = null;
        if (d != null && d.isShowing()) {
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
                // окно уже убрано системой
            }
        }
    }

    private Dialog build(int kind, int percent) {
        final float dp = act.getResources().getDisplayMetrics().density;
        boolean flat = ThemeManager.isFlatTheme();
        boolean low = kind == LOW;

        Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface di, int keyCode, KeyEvent event) {
                // Закрываем по отпусканию, чтобы нажатие, начатое до карточки, не доехало до экрана под ней.
                if (event.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            }
        });

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);

        // Баннер: плита с роботом из запечённой картинки, заголовок рисует Android (перевод через t()).
        FrameLayout bannerBox = new FrameLayout(act);
        TextView title = new TextView(act);
        String titleText = host.t(low ? "Low battery" : "Wrong charger");
        if (flat) {
            ImageView banner = new ImageView(act);
            banner.setScaleType(ImageView.ScaleType.FIT_XY);
            banner.setImageResource(low ? R.drawable.battery_low : R.drawable.usb_banner_error);
            bannerBox.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            title.setText(titleText.toLowerCase());
            title.setTypeface(ThemeManager.getCustomFontTitle());
            title.setTextColor(CREAM);
            fit(title, 30, 190);
            title.setSingleLine(true);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
            tp.leftMargin = (int) (22 * dp);
            tp.topMargin = (int) (18 * dp);
            bannerBox.addView(title, tp);
        } else {
            title.setText(titleText);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            title.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (6 * dp));
            bannerBox.addView(title);
        }
        root.addView(bannerBox, new LinearLayout.LayoutParams((int) (452 * dp), flat ? (int) (158 * dp)
                : ViewGroup.LayoutParams.WRAP_CONTENT));

        // Нижняя плашка: крупный процент слева, подсказка справа.
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (22 * dp), (int) (12 * dp), (int) (22 * dp), (int) (12 * dp));
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius((flat ? ThemeManager.getButtonRadius() : 12) * dp);
        card.setColor(flat ? RAISED : 0xDD000000);
        if (flat) card.setStroke((int) Math.max(1, dp), BORDER);
        row.setBackgroundDrawable(card);

        if (low) {
            percentView = new TextView(act);
            percentView.setText(percent + "%");
            percentView.setTypeface(flat ? ThemeManager.getCustomFontTitle() : Typeface.DEFAULT_BOLD);
            percentView.setTextColor(flat ? CREAM : Color.WHITE);
            percentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
            percentView.setSingleLine(true);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            pp.rightMargin = (int) (18 * dp);
            row.addView(percentView, pp);
        }
        TextView hint = new TextView(act);
        hint.setText(host.t(low ? "Plug in the charger." : "Unplug it and use a charger made for this player."));
        hint.setTypeface(flat ? ThemeManager.getCustomFont() : Typeface.DEFAULT);
        hint.setTextColor(flat ? ThemeManager.getTextColorSecondary() : Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams((int) (452 * dp),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = (int) (8 * dp);
        root.addView(row, rp);

        d.setContentView(root);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setDimAmount(0.65f);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        return d;
    }

    // Подбирает самый крупный кегль, при котором слово влезает в widthDp (как заголовки карточек на сайте).
    private void fit(TextView tv, float maxSp, float widthDp) {
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tv.getTypeface());
        String text = tv.getText().toString();
        float size = maxSp;
        while (size > 10) {
            p.setTextSize(size * dm.scaledDensity);
            if (p.measureText(text) <= widthDp * dm.density) break;
            size -= 1;
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
    }
}
