package com.themoon.y1.managers;

import android.content.Context;
import android.os.storage.StorageManager;

import java.lang.reflect.Method;

/**
 * Режим USB-накопителя карты памяти. Методы StorageManager скрыты из публичного SDK, поэтому
 * вызываем их рефлексией; включать и выключать общий доступ может только системное приложение
 * (MOUNT_UNMOUNT_FILESYSTEMS выдаётся лишь программам из /system/app), у обычной установки
 * enable()/disable() вернут false.
 */
public final class UsbStorage {
    private UsbStorage() {
    }

    private static Object call(Context ctx, String name) throws Exception {
        StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
        Method m = StorageManager.class.getMethod(name);
        return m.invoke(sm);
    }

    /** Кабель вставлен в компьютер (от зарядки из розетки это false: данных там нет). */
    public static boolean isConnected(Context ctx) {
        try {
            return Boolean.TRUE.equals(call(ctx, "isUsbMassStorageConnected"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Карта сейчас отдана компьютеру. */
    public static boolean isEnabled(Context ctx) {
        try {
            return Boolean.TRUE.equals(call(ctx, "isUsbMassStorageEnabled"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Запрос выполняется асинхронно: итог видно по isEnabled() через секунду-другую. */
    public static boolean enable(Context ctx) {
        return run(ctx, "enableUsbMassStorage");
    }

    public static boolean disable(Context ctx) {
        return run(ctx, "disableUsbMassStorage");
    }

    private static boolean run(Context ctx, String name) {
        try {
            call(ctx, name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
