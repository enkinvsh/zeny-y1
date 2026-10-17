package com.themoon.y1.managers;

import java.util.List;

/**
 * Перенос строк текста песни по ширине. Чистая Java: ширину даёт интерфейс {@link TextMeasurer},
 * поэтому логика проверяется юнит-тестами с измерителем «1 px на символ», без Android и шрифтов.
 *
 * Все методы вызываются при смене строки или трека, а не при рисовании кадра: подстроки выделяются,
 * зато onDraw вью остаётся без аллокаций.
 */
public final class LyricsWrap {

    public static final String ELLIPSIS = "\u2026";

    public interface TextMeasurer {
        float width(String s);
    }

    private LyricsWrap() {}

    /**
     * Переносит текст максимум на maxLines строк шириной не больше maxWidth и пишет их в out.
     * Если текст занимает две строки, ширина подбирается двоичным поиском так, чтобы строки вышли
     * почти равными: жадный перенос оставляет длинную строку и короткий хвост, а по центру экрана
     * такой хвост выглядит как ошибка вёрстки. Если текст не помещается, последняя строка
     * обрезается многоточием. Возвращает число заполненных строк (0 для пустого текста).
     */
    public static int wrapBalanced(String text, float maxWidth, int maxLines, TextMeasurer m, String[] out) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty() || maxLines < 1) return 0;
        if (m.width(s) <= maxWidth) {
            out[0] = s;
            return 1;
        }
        int[] ends = new int[maxLines];
        int n = breakLines(s, maxWidth, maxLines, m, ends);
        if (n > maxLines) {
            int start = 0;
            for (int i = 0; i < maxLines - 1; i++) {
                out[i] = s.substring(start, ends[i]);
                start = skipSpaces(s, ends[i]);
            }
            out[maxLines - 1] = ellipsize(s.substring(start), maxWidth, m);
            return maxLines;
        }
        if (n == 2) {
            // Наименьшая ширина, при которой текст всё ещё укладывается в две строки; меньше половины
            // общей длины она быть не может, поэтому поиск идёт от неё. 12 шагов дают точность в доли пикселя.
            float lo = m.width(s) / 2f;
            float hi = maxWidth;
            for (int i = 0; i < 12; i++) {
                float mid = (lo + hi) / 2f;
                if (breakLines(s, mid, 2, m, ends) <= 2) hi = mid;
                else lo = mid;
            }
            breakLines(s, hi, 2, m, ends);
        }
        int start = 0;
        for (int i = 0; i < n; i++) {
            out[i] = s.substring(start, ends[i]);
            start = skipSpaces(s, ends[i]);
        }
        return n;
    }

    /** Режет строку до ширины w вместе с многоточием; влезающая строка возвращается как есть. */
    public static String ellipsize(String s, float w, TextMeasurer m) {
        if (s == null) return "";
        if (m.width(s) <= w) return s;
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (m.width(trimEnd(s.substring(0, mid)) + ELLIPSIS) <= w) lo = mid;
            else hi = mid - 1;
        }
        // Суррогатную пару не рвём: половинка эмодзи рисуется квадратом.
        if (lo > 0 && Character.isHighSurrogate(s.charAt(lo - 1))) lo--;
        return trimEnd(s.substring(0, lo)) + ELLIPSIS;
    }

    /**
     * Разбивает весь простой текст по ширине и добавляет строки в out. Пустые строки исходника
     * сохраняются как пустые. Вызывается один раз при установке текста.
     */
    public static void wrapAll(String text, float maxWidth, TextMeasurer m, List<String> out) {
        if (text == null) return;
        int len = text.length();
        int pos = 0;
        while (pos <= len) {
            int eol = text.indexOf('\n', pos);
            if (eol < 0) eol = len;
            String line = text.substring(pos, eol).trim();
            if (line.isEmpty()) {
                out.add("");
            } else {
                int p = 0;
                while (p < line.length()) {
                    int e = lineEnd(line, p, maxWidth, m);
                    out.add(line.substring(p, e));
                    p = skipSpaces(line, e);
                }
            }
            pos = eol + 1;
        }
    }

    /** Число строк, нужных для s при ширине w; limit + 1, если за limit строк текст не кончился. */
    private static int breakLines(String s, float w, int limit, TextMeasurer m, int[] ends) {
        int n = 0;
        int pos = 0;
        int len = s.length();
        while (pos < len) {
            if (n == limit) return limit + 1;
            int e = lineEnd(s, pos, w, m);
            ends[n++] = e;
            pos = skipSpaces(s, e);
        }
        return n;
    }

    /** Конец первой строки, начинающейся с pos: самый длинный префикс по границам слов, влезающий в w. */
    private static int lineEnd(String s, int pos, float w, TextMeasurer m) {
        int len = s.length();
        int best = -1;
        int i = pos;
        while (i < len) {
            int e = i;
            while (e < len && !isSpace(s.charAt(e))) e++;
            if (m.width(s.substring(pos, e)) > w) break;
            best = e;
            i = skipSpaces(s, e);
        }
        if (best >= 0) return best;
        // Первое слово шире строки: рвём его по символам, но не меньше одного символа, чтобы не зациклиться.
        int e = pos + 1;
        while (e < len && !isSpace(s.charAt(e)) && m.width(s.substring(pos, e + 1)) <= w) e++;
        if (e < len && Character.isHighSurrogate(s.charAt(e - 1)) && Character.isLowSurrogate(s.charAt(e))) e++;
        return e;
    }

    private static int skipSpaces(String s, int from) {
        int p = from;
        while (p < s.length() && isSpace(s.charAt(p))) p++;
        return p;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    private static String trimEnd(String s) {
        int e = s.length();
        while (e > 0 && isSpace(s.charAt(e - 1))) e--;
        return e == s.length() ? s : s.substring(0, e);
    }
}
