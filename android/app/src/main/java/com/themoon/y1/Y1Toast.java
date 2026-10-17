package com.themoon.y1;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.themoon.y1.managers.LanguageManager;

/**
 * Тост в стиле zeny вместо системного.
 *
 * На Android 4.2 системный тост - серый прямоугольник с мелким радиусом и шрифтом Roboto, он выбивался из плоской
 * темы и перекрывал список. Здесь плита elevated с обводкой, радиус 26 (на одной строке выходит капсула), зелёная
 * точка-акцент и шрифт темы. В неплоских темах остаётся обычный тост, чтобы не ломать чужие оформления.
 * Подпись проходит через словарь языка, поэтому непереведённые по месту английские фразы тоже локализуются.
 */
public final class Y1Toast {
    private static final int ELEVATED = 0xFF232C37;
    private static final int BORDER = 0xFF334351;
    private static final int TEXT = 0xFFEFEBE2;
    private static final int ACCENT = 0xFF00DB4D;

    private Y1Toast() {}

    // Экран заблокирован или «притворно» погашен: на нём тост только мусорит, а карточка блокировки
    // уже говорит всё нужное. Проверяем и при создании, и при показе: тост могли создать до блокировки.
    private static boolean screenIsOff() {
        MainActivity a = MainActivity.instance;
        return a != null && (a.isInputLocked() || a.isFakeScreenOff);
    }

    private static final class QuietToast extends Toast {
        QuietToast(Context context) {
            super(context);
        }

        @Override
        public void show() {}
    }

    private static final class GuardedToast extends Toast {
        GuardedToast(Context context) {
            super(context);
        }

        @Override
        public void show() {
            if (!screenIsOff())
                super.show();
        }
    }

    public static Toast makeText(Context context, CharSequence text, int duration) {
        if (screenIsOff())
            return new QuietToast(context);
        String shown = text == null ? "" : LanguageManager.getInstance(context).t(text.toString());
        if (!ThemeManager.isFlatTheme()) {
            return Toast.makeText(context, shown, duration);
        }
        float d = context.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (16 * d), (int) (10 * d), (int) (20 * d), (int) (10 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ELEVATED);
        bg.setStroke(Math.max(1, (int) d), BORDER);
        bg.setCornerRadius(ThemeManager.getButtonRadius() * d);
        row.setBackground(bg);

        View dot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(ACCENT);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) (8 * d), (int) (8 * d));
        dlp.rightMargin = (int) (10 * d);
        row.addView(dot, dlp);

        TextView tv = new TextView(context);
        tv.setText(shown);
        tv.setTextColor(TEXT);
        tv.setTextSize(14);
        tv.setTypeface(ThemeManager.getCustomFontBold());
        tv.setMaxWidth((int) (360 * d));
        row.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Toast toast = new GuardedToast(context);
        toast.setView(row);
        toast.setDuration(duration);
        // Над нижней кромкой с запасом: у Y1 физический экран 360 px, и системный отступ тоста съедал последний пункт списка.
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, (int) (18 * d));
        return toast;
    }

    public static Toast makeText(Context context, int resId, int duration) {
        return makeText(context, context.getResources().getText(resId), duration);
    }
}
