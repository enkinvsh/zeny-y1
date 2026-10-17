package com.themoon.y1.views;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.managers.UsbStorage;

/**
 * Экран «плеер подключён к компьютеру» вместо заводского окна SystemUI. Ведёт карту памяти по
 * фазам: кабель есть, накопитель включается, включён, выключается, не получилось.
 */
public final class UsbPanel {
    public interface Host {
        String t(String key);

        /** Остановить всё, что держит файлы карты: иначе vold при размонтировании убьёт приложение. */
        void releaseStorage();

        /** Карта снова доступна плееру; с компьютера могли добавить файлы, библиотеку надо пересканировать. */
        void storageReturned();
    }

    private static final int IDLE = 0, TURNING_ON = 1, ON = 2, TURNING_OFF = 3, FAILED = 4;
    // vold отдаёт и забирает том за 2-5 с; всё, что дольше, считаем зависанием.
    private static final long SWITCH_TIMEOUT_MS = 15000;
    private static final long CARD_WAIT_MS = 30000;
    // Поверхности сайта: air (кабель есть), life (накопитель включён), fire (ошибка).
    private static final int[] FILL = { 0xFFEFEBE2, 0xFF00DB4D, 0xFF971D1C };
    private static final int[] ON_FILL = { 0xFF181E26, 0xFF181E26, 0xFFEFEBE2 };
    private static final int[] BANNER = { R.drawable.usb_banner_idle, R.drawable.usb_banner_on,
            R.drawable.usb_banner_error };

    private final Activity act;
    private final Host host;
    private final ImageView banner;
    private final TextView title, status, hint, footer;
    private final Button button;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int phase = IDLE;
    private long phaseSince;
    private boolean wasShared;
    private boolean awaitingCard;
    private boolean polling;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            tick();
            if (polling) handler.postDelayed(this, 600);
        }
    };

    public UsbPanel(Activity act, Host host) {
        this.act = act;
        this.host = host;
        banner = (ImageView) act.findViewById(R.id.iv_usb_banner);
        title = (TextView) act.findViewById(R.id.tv_usb_title);
        status = (TextView) act.findViewById(R.id.tv_usb_status);
        hint = (TextView) act.findViewById(R.id.tv_usb_hint);
        footer = (TextView) act.findViewById(R.id.tv_usb_footer);
        button = (Button) act.findViewById(R.id.btn_usb_toggle);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onButton();
            }
        });
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                styleButton(hasFocus);
            }
        });
    }

    public View focusTarget() {
        return button;
    }

    /** Пока идёт переключение или карта у компьютера, Назад заблокирован: иначе плеер остался бы без карты. */
    public boolean blocksBack() {
        return phase == ON || phase == TURNING_ON || phase == TURNING_OFF;
    }

    public void show() {
        // Экран мог остаться от прошлого подключения: состояние берём у системы, а не из памяти.
        phase = UsbStorage.isEnabled(act) ? ON : IDLE;
        if (phase == ON) wasShared = true;
        phaseSince = SystemClock.elapsedRealtime();
        render();
        polling = true;
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, 600);
    }

    public void hide() {
        polling = false;
        handler.removeCallbacks(poll);
    }

    /** Кабель вынули: система сама вернёт карту плееру, дожидаемся монтирования и просим пересканировать. */
    public void onCableRemoved() {
        if (phase == ON || phase == TURNING_ON || phase == TURNING_OFF) phase = IDLE;
        if (wasShared) awaitCard();
        render();
    }

    private void onButton() {
        // Передача карты компьютеру необратимо прерывает воспроизведение: след в logcat нужен, чтобы разбирать
        // случаи «накопитель включился сам».
        android.util.Log.i("zeny-usb", "нажата кнопка накопителя, фаза=" + phase);
        if (phase == IDLE || phase == FAILED) {
            host.releaseStorage();
            wasShared = true;
            phase = UsbStorage.enable(act) ? TURNING_ON : FAILED;
        } else if (phase == ON) {
            phase = UsbStorage.disable(act) ? TURNING_OFF : FAILED;
        } else {
            return;
        }
        phaseSince = SystemClock.elapsedRealtime();
        render();
    }

    private void tick() {
        boolean enabled = UsbStorage.isEnabled(act);
        long waited = SystemClock.elapsedRealtime() - phaseSince;
        int before = phase;
        switch (phase) {
            case IDLE:
                if (enabled) {
                    phase = ON;
                    wasShared = true;
                }
                break;
            case TURNING_ON:
                if (enabled) phase = ON;
                else if (waited > SWITCH_TIMEOUT_MS) phase = FAILED;
                break;
            case ON:
                if (!enabled) {
                    phase = IDLE;
                    awaitCard();
                }
                break;
            case TURNING_OFF:
                if (!enabled) {
                    phase = IDLE;
                    awaitCard();
                } else if (waited > SWITCH_TIMEOUT_MS) {
                    phase = ON;
                }
                break;
            case FAILED:
                if (enabled) phase = ON;
                break;
            default:
                break;
        }
        if (phase != before) {
            phaseSince = SystemClock.elapsedRealtime();
            render();
        }
    }

    // Размонтирование и обратное монтирование идут асинхронно в vold; до MEDIA_MOUNTED карту трогать нельзя.
    private void awaitCard() {
        if (awaitingCard) return;
        awaitingCard = true;
        final long start = SystemClock.elapsedRealtime();
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    awaitingCard = false;
                    wasShared = false;
                    host.storageReturned();
                } else if (SystemClock.elapsedRealtime() - start > CARD_WAIT_MS) {
                    awaitingCard = false;
                } else {
                    handler.postDelayed(this, 800);
                }
            }
        });
    }

    private int mode() {
        if (phase == ON || phase == TURNING_OFF) return 1;
        return phase == FAILED ? 2 : 0;
    }

    private void render() {
        boolean flat = ThemeManager.isFlatTheme();
        int m = mode();
        String statusText, buttonText, hintText;
        switch (phase) {
            case TURNING_ON:
                statusText = "TURNING ON...";
                buttonText = "PLEASE WAIT...";
                hintText = "Handing the card over to the computer.";
                break;
            case ON:
                statusText = "USB STORAGE ON";
                buttonText = "TURN OFF USB STORAGE";
                hintText = "Eject the card on the computer first, then turn the storage off.";
                break;
            case TURNING_OFF:
                statusText = "TURNING OFF...";
                buttonText = "PLEASE WAIT...";
                hintText = "Taking the card back.";
                break;
            case FAILED:
                statusText = "COULD NOT SWITCH USB STORAGE";
                buttonText = "TRY AGAIN";
                hintText = "The card is busy or the system refused. Try again or press [Back].";
                break;
            default:
                statusText = "CONNECTED TO COMPUTER";
                buttonText = "TURN ON USB STORAGE";
                hintText = "Turn it on to copy music from the computer. While it is on, the player cannot see the card.";
                break;
        }
        status.setText(host.t(statusText));
        button.setText(host.t(buttonText));
        hint.setText(host.t(hintText));
        footer.setText(blocksBack() ? "" : host.t("Press [Back] to only charge."));
        if (flat) {
            banner.setVisibility(View.VISIBLE);
            banner.setImageResource(BANNER[m]);
            // Слово «usb» гигантское строчное, как заголовки карточек сайта.
            title.setText("usb");
            title.setTypeface(ThemeManager.getCustomFontTitle());
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
            title.setTextColor(ON_FILL[m]);
            status.setTextColor(ON_FILL[m]);
            hint.setTextColor(ThemeManager.getTextColorSecondary());
            footer.setTextColor(ThemeManager.getTextColorSecondary());
        } else {
            banner.setVisibility(View.GONE);
            title.setText(host.t("USB Storage"));
            title.setTextColor(ThemeManager.getTextColorPrimary());
            status.setTextColor(ThemeManager.getTextColorPrimary());
        }
        styleButton(button.hasFocus());
    }

    // Кнопка того же цвета, что и баннер: зелёная под красной ошибкой читалась бы как «всё в порядке».
    private void styleButton(boolean focused) {
        if (!ThemeManager.isFlatTheme()) return;
        int m = mode();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ThemeManager.getButtonRadius() * act.getResources().getDisplayMetrics().density);
        bg.setColor(focused ? FILL[m] : ((FILL[m] & 0x00FFFFFF) | 0x38000000));
        button.setBackgroundDrawable(bg);
        button.setTextColor(focused ? ON_FILL[m] : ThemeManager.getTextColorPrimary());
        button.setTypeface(focused ? ThemeManager.getCustomFontBold() : ThemeManager.getCustomFont(), Typeface.NORMAL);
    }
}
