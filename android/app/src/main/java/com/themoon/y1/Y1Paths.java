package com.themoon.y1;

import java.io.File;

/**
 * Корень пользовательского хранилища.
 * На самом плеере (Y1, Android 4.2.2) это всегда /storage/sdcard0 - поведение не меняется.
 * На эмуляторе такого пути нет (там /storage/emulated/0), поэтому стенд падает на
 * Environment.getExternalStorageDirectory(); без этого темы/музыка/обложки в эмуляторе не работают.
 */
public final class Y1Paths {
    private Y1Paths() {}

    public static final String SD = resolve();

    private static String resolve() {
        File y1 = new File("/storage/sdcard0");
        if (y1.exists()) return y1.getAbsolutePath();
        return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
    }
}
