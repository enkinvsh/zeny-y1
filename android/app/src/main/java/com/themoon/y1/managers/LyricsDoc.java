package com.themoon.y1.managers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedMap;

/**
 * Неизменяемый текст песни: либо строки с отметками времени (LRC, главы m4b), либо простой текст.
 * Чистая Java без android.*, чтобы разбор и поиск строки проверялись обычными юнит-тестами.
 *
 * Пустая строка с отметкой остаётся в документе: это метка паузы (проигрыш), экран в этот момент
 * не должен держать на виду уже допетую строку.
 */
public final class LyricsDoc {

    /** Предел простого текста: длиннее на 360x480 не прочитать, а разбиение по ширине стоит времени на MT6572. */
    public static final int MAX_PLAIN_CHARS = 20000;

    /** Времена строк в миллисекундах по возрастанию; у простого текста пусто. */
    public final int[] timesMs;
    public final String[] lines;
    public final boolean synced;
    /** Простой текст одной строкой с переводами строк; у синхронного документа пустая строка. */
    public final String plain;

    private LyricsDoc(int[] timesMs, String[] lines, boolean synced, String plain) {
        this.timesMs = timesMs;
        this.lines = lines;
        this.synced = synced;
        this.plain = plain;
    }

    public int size() {
        return lines.length;
    }

    /**
     * Индекс последней строки, чьё время не позже позиции; -1 до первой строки и у простого текста.
     * Двоичный поиск без выделения памяти: вызывается из таймера каждые 250 мс.
     */
    public int indexAt(int posMs) {
        int lo = 0;
        int hi = timesMs.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (timesMs[mid] <= posMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Главы m4b и старый разбор дают карту время -> текст; для TreeMap порядок уже по возрастанию. */
    public static LyricsDoc fromMap(SortedMap<Integer, String> map) {
        if (map == null || map.isEmpty()) return null;
        int n = map.size();
        int[] times = new int[n];
        String[] text = new String[n];
        int i = 0;
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            times[i] = e.getKey();
            text[i] = e.getValue() == null ? "" : e.getValue().trim();
            i++;
        }
        return new LyricsDoc(times, text, true, "");
    }

    /**
     * Разбор содержимого .lrc или встроенного текста: если нашлась хоть одна отметка времени, это
     * синхронный документ, иначе простой. null, если показывать нечего.
     */
    public static LyricsDoc parse(String text) {
        if (text == null || text.isEmpty()) return null;
        LyricsDoc synced = parseSynced(text);
        if (synced != null) return synced;
        if (hasAnyStamp(text)) return null; // отметки были, но ни в одной строке нет слов
        return parsePlain(text);
    }

    private static boolean hasAnyStamp(String text) {
        int len = text.length();
        int pos = 0;
        while (pos < len) {
            int eol = lineEnd(text, pos);
            if (stampsEnd(text, pos, eol) > pos) return true;
            pos = eol + 1;
        }
        return false;
    }

    private static LyricsDoc parseSynced(String text) {
        ArrayList<String> texts = new ArrayList<String>();
        // Ключ сортировки: время в старших 32 битах, порядковый номер в файле в младших, поэтому
        // равные времена сохраняют порядок файла, а сортировка идёт без компаратора и без объектов.
        ArrayList<Long> keys = new ArrayList<Long>();
        boolean anyText = false;
        int len = text.length();
        int pos = 0;
        while (pos < len) {
            int eol = lineEnd(text, pos);
            int bodyStart = stampsEnd(text, pos, eol);
            if (bodyStart > pos) {
                String body = text.substring(bodyStart, eol).trim();
                if (!body.isEmpty()) anyText = true;
                int p = pos;
                while (p < bodyStart) {
                    int close = text.indexOf(']', p);
                    int t = parseStamp(text, p + 1, close);
                    keys.add(((long) t << 32) | texts.size());
                    texts.add(body);
                    p = close + 1;
                }
            }
            pos = eol + 1;
        }
        if (keys.isEmpty() || !anyText) return null;
        long[] sorted = new long[keys.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = keys.get(i);
        Arrays.sort(sorted);
        int[] times = new int[sorted.length];
        String[] lines = new String[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            times[i] = (int) (sorted[i] >>> 32);
            lines[i] = texts.get((int) (sorted[i] & 0xFFFFFFFFL));
        }
        return new LyricsDoc(times, lines, true, "");
    }

    private static LyricsDoc parsePlain(String text) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder joined = new StringBuilder();
        boolean pendingBlank = false;
        int len = text.length();
        int pos = 0;
        while (pos < len) {
            int eol = lineEnd(text, pos);
            String line = text.substring(pos, eol).trim();
            pos = eol + 1;
            if (line.isEmpty()) {
                // Пустые строки подряд схлопываются в одну, ведущие и хвостовые отбрасываются.
                pendingBlank = !out.isEmpty();
                continue;
            }
            int add = line.length() + (out.isEmpty() ? 0 : 1) + (pendingBlank ? 1 : 0);
            if (joined.length() + add > MAX_PLAIN_CHARS) break;
            if (pendingBlank) {
                out.add("");
                joined.append('\n');
                pendingBlank = false;
            }
            if (!out.isEmpty()) joined.append('\n');
            out.add(line);
            joined.append(line);
        }
        if (out.isEmpty()) return null;
        return new LyricsDoc(new int[0], out.toArray(new String[0]), false, joined.toString());
    }

    /** Конец строки: позиция '\n' или длина текста; '\r' перед ним отрезается trim(), а в разборе отметок не мешает. */
    private static int lineEnd(String s, int from) {
        int e = s.indexOf('\n', from);
        return e < 0 ? s.length() : e;
    }

    /**
     * Позиция сразу за подряд идущими отметками времени в начале строки [from, end); равна from,
     * если строка не начинается с отметки. Теги вроде [ar:..] и [offset:..] отметками не считаются.
     */
    private static int stampsEnd(String s, int from, int end) {
        int p = from;
        while (p < end && s.charAt(p) == '[') {
            int close = s.indexOf(']', p);
            if (close < 0 || close >= end) break;
            if (parseStamp(s, p + 1, close) < 0) break;
            p = close + 1;
        }
        return p;
    }

    /**
     * Время в мс из текста между скобками: минуты:секунды[.доли]. Доли из одной цифры это десятые,
     * из двух сотые, из трёх тысячные. -1, если текст не отметка времени.
     */
    private static int parseStamp(String s, int from, int to) {
        int p = from;
        int minutes = 0;
        int digits = 0;
        while (p < to && isDigit(s.charAt(p))) {
            minutes = minutes * 10 + (s.charAt(p) - '0');
            digits++;
            p++;
            if (digits > 4) return -1;
        }
        if (digits == 0 || p >= to || s.charAt(p) != ':') return -1;
        p++;
        int seconds = 0;
        digits = 0;
        while (p < to && isDigit(s.charAt(p)) && digits < 2) {
            seconds = seconds * 10 + (s.charAt(p) - '0');
            digits++;
            p++;
        }
        if (digits == 0) return -1;
        int frac = 0;
        if (p < to) {
            char sep = s.charAt(p);
            if (sep != '.' && sep != ':') return -1;
            p++;
            int fd = 0;
            while (p < to && isDigit(s.charAt(p)) && fd < 3) {
                frac = frac * 10 + (s.charAt(p) - '0');
                fd++;
                p++;
            }
            if (fd == 0 || p != to) return -1;
            if (fd == 1) frac *= 100;
            else if (fd == 2) frac *= 10;
        }
        return (minutes * 60 + seconds) * 1000 + frac;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
