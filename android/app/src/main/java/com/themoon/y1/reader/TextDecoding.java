package com.themoon.y1.reader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Открывает поток байт книги как поток символов: кодировка берётся из BOM или из XML-пролога.
 *
 * FB2 в windows-1251 до сих пор частая находка в старых библиотеках; без пролога файл считается UTF-8.
 */
public final class TextDecoding {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Pattern PROLOG = Pattern.compile(
            "^\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._:\\-]+)[\"']");
    /** Пролог всегда в самом начале; 256 байт хватает с запасом, а mark/reset на таком объёме дёшев. */
    private static final int PEEK = 256;

    private TextDecoding() {
    }

    public static Reader open(InputStream in) throws IOException {
        BufferedInputStream b = new BufferedInputStream(in, 8192);
        b.mark(PEEK);
        byte[] head = new byte[PEEK];
        int n = 0;
        while (n < PEEK) {
            int r = b.read(head, n, PEEK - n);
            if (r < 0) break;
            n += r;
        }
        b.reset();

        Charset cs = UTF8;
        int skip = 0;
        if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            skip = 3;
        } else if (n >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            cs = Charset.forName("UTF-16BE");
            skip = 2;
        } else if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            cs = Charset.forName("UTF-16LE");
            skip = 2;
        } else {
            Matcher m = PROLOG.matcher(new String(head, 0, n, LATIN1));
            if (m.find()) cs = charsetOrUtf8(m.group(1));
        }
        while (skip > 0) {
            long s = b.skip(skip);
            if (s <= 0) break;
            skip -= (int) s;
        }
        return new InputStreamReader(b, cs);
    }

    /** Неизвестное или кривое имя кодировки не должно ронять открытие книги. */
    static Charset charsetOrUtf8(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return UTF8;
        }
    }
}
