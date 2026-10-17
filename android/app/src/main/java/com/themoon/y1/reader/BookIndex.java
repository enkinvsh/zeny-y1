package com.themoon.y1.reader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Компактная модель книги: весь текст одним {@code char[]} (слова через один пробел), смещения слов
 * и байт флагов на слово. Объектов на слово нет: так индекс толстой книги помещается в память Y1.
 *
 * Неизменяем после сборки; для переноса между запусками пишется в {@code .zidx} (см. {@link #write}).
 */
public final class BookIndex {

    /** Слово заканчивает предложение (. ! ? … и закрывающие кавычки или скобки после них). */
    public static final int F_SENT_END = 1;
    /** Слово заканчивает абзац (абзац всегда заканчивает и предложение). */
    public static final int F_PARA_END = 2;
    /** Слово открывает главу. */
    public static final int F_CHAP_START = 4;
    /** После слова запятая, двоеточие или точка с запятой. */
    public static final int F_PAUSE_MID = 8;

    private static final int FORMAT_VERSION = 1;
    private static final int END_MARK = 0x5A454E44;
    /** Предел, после которого размер в файле считается порчей, а не книгой: защита от OutOfMemory на битом кэше. */
    private static final int MAX_CHARS = 1 << 28;

    public final String title;
    public final String author;
    /** Слова подряд, между соседними ровно один пробел. */
    public final char[] text;
    /** Длина wordCount + 1, последний элемент равен text.length + 1. */
    public final int[] wordStart;
    public final byte[] flags;
    public final String[] chapterTitles;
    public final int[] chapterFirstWord;
    /** Среднее по книге значение множителя задержки: нужно, чтобы честно оценить «ещё N мин». */
    public final float avgDelayFactor;

    public BookIndex(String title, String author, char[] text, int[] wordStart, byte[] flags,
                     String[] chapterTitles, int[] chapterFirstWord, float avgDelayFactor) {
        this.title = title;
        this.author = author;
        this.text = text;
        this.wordStart = wordStart;
        this.flags = flags;
        this.chapterTitles = chapterTitles;
        this.chapterFirstWord = chapterFirstWord;
        this.avgDelayFactor = avgDelayFactor;
    }

    public int wordCount() {
        return flags.length;
    }

    /** Процент прочитанного для слова: первое слово 0, последнее 100. */
    public int percentOf(int word) {
        int n = wordCount();
        if (n <= 1) return 0;
        return (int) (100L * Math.max(0, Math.min(word, n - 1)) / (n - 1));
    }

    /**
     * Дочитана ли книга: позиция не меньше 99% с округлением к ближайшему. На книге в 100 слов
     * предпоследнее слово даёт 98,99%: с отбрасыванием дроби оно бы не считалось концом.
     */
    public boolean isEndReached(int word) {
        int n = wordCount();
        if (n <= 1) return true;
        long w = Math.max(0, Math.min(word, n - 1));
        return (200L * w + (n - 1)) / (2L * (n - 1)) >= 99;
    }

    public int wordLength(int i) {
        return wordStart[i + 1] - wordStart[i] - 1;
    }

    /** Номер главы, к которой относится слово (двоичный поиск по началам глав). */
    public int chapterOf(int word) {
        int lo = 0;
        int hi = chapterFirstWord.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (chapterFirstWord[mid] <= word) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private int clamp(int word) {
        return Math.max(0, Math.min(word, wordCount() - 1));
    }

    private int startBefore(int word, int flag) {
        int i = clamp(word);
        while (i > 0 && (flags[i - 1] & flag) == 0) i--;
        return i;
    }

    private int startAfter(int word, int flag) {
        int n = wordCount();
        int i = clamp(word);
        for (; i < n; i++) {
            if ((flags[i] & flag) != 0) return Math.min(i + 1, n - 1);
        }
        return n - 1;
    }

    public int sentenceStart(int word) {
        return startBefore(word, F_SENT_END);
    }

    public int paragraphStart(int word) {
        return startBefore(word, F_PARA_END);
    }

    /** Последнее слово абзаца, в котором стоит word. */
    public int paragraphEnd(int word) {
        int n = wordCount();
        for (int i = clamp(word); i < n; i++) {
            if ((flags[i] & F_PARA_END) != 0) return i;
        }
        return n - 1;
    }

    /** Начало следующего предложения; в последнем предложении книги остаётся на последнем слове. */
    public int nextSentence(int word) {
        return startAfter(word, F_SENT_END);
    }

    /**
     * Назад: первое нажатие возвращает к началу текущего предложения, следующее к началу предыдущего.
     * Так читатель, пропустивший фразу, не перепрыгивает сразу через две.
     */
    public int prevSentence(int word) {
        int s = sentenceStart(word);
        if (s < word || s == 0) return s;
        return sentenceStart(s - 1);
    }

    public int nextParagraph(int word) {
        return startAfter(word, F_PARA_END);
    }

    public int prevParagraph(int word) {
        int s = paragraphStart(word);
        if (s < word || s == 0) return s;
        return paragraphStart(s - 1);
    }

    /**
     * Окно в {@code words} слов вокруг слова, склеенное пробелом. Окно сдвигается внутрь книги у краёв,
     * чтобы якорь всегда был полной длины и надёжно искался.
     */
    public String anchorAround(int word, int words) {
        int n = wordCount();
        if (n == 0) return "";
        int a = Math.max(0, Math.min(word - words / 2, n - words));
        int b = Math.min(n, a + words);
        return new String(text, wordStart[a], wordStart[b] - 1 - wordStart[a]);
    }

    /**
     * Ищет якорь, возвращает индекс слова, вокруг которого он был взят, или -1. Сначала окно
     * {@code near ± radius} слов, потом вся книга; из нескольких совпадений берётся ближайшее к near.
     */
    public int findAnchor(String anchor, int near, int radius) {
        int n = wordCount();
        if (anchor == null || anchor.length() == 0 || n == 0) return -1;
        char[] a = anchor.toCharArray();
        int aw = 1;
        for (char c : a) {
            if (c == ' ') aw++;
        }
        int nearC = clamp(near);
        int lo = Math.max(0, nearC - radius);
        int hi = (int) Math.min((long) n, (long) nearC + radius);
        int w = search(a, aw, lo, hi, nearC);
        if (w < 0 && (lo > 0 || hi < n)) w = search(a, aw, 0, n, nearC);
        if (w < 0) return -1;
        int half = aw / 2;
        int from = w + half;
        int to = from;
        // Окно у края книги сдвинуто внутрь, поэтому слово в нём известно лишь с точностью до края.
        if (w == 0) {
            from = 0;
            to = Math.min(half, n - 1);
        }
        if (w + aw >= n) {
            to = n - 1;
            if (w != 0) from = Math.max(0, n - half);
        }
        return Math.max(from, Math.min(nearC, to));
    }

    private int search(char[] a, int aw, int lo, int hi, int near) {
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        int half = aw / 2;
        for (int w = lo; w < hi; w++) {
            int p = wordStart[w];
            if (p + a.length > text.length || text[p] != a[0]) continue;
            int end = p + a.length;
            if (end < text.length && text[end] != ' ') continue;
            boolean ok = true;
            for (int k = 1; k < a.length; k++) {
                if (text[p + k] != a[k]) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            int dist = Math.abs(w + half - near);
            if (dist < bestDist) {
                bestDist = dist;
                best = w;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ формат .zidx

    /**
     * Пишет индекс в формате .zidx: заголовок ZIDX, версия, строки, текст в UTF-8, длины слов дельтами
     * (varint), флаги, главы, среднее, контрольная метка конца. Поток не закрывается.
     */
    public void write(OutputStream out) throws IOException {
        Dst d = new Dst(out);
        d.u8('Z');
        d.u8('I');
        d.u8('D');
        d.u8('X');
        d.i32(FORMAT_VERSION);
        d.chars(title.toCharArray());
        d.chars(author.toCharArray());
        int n = wordCount();
        d.varint(n);
        d.chars(text);
        for (int i = 0; i < n; i++) d.varint(wordStart[i + 1] - wordStart[i]);
        d.bytes(flags, 0, n);
        d.varint(chapterTitles.length);
        for (int i = 0; i < chapterTitles.length; i++) {
            d.chars(chapterTitles[i].toCharArray());
            d.varint(chapterFirstWord[i]);
        }
        d.i32(Float.floatToIntBits(avgDelayFactor));
        d.i32(END_MARK);
        d.flush();
    }

    /** @throws IOException чужая версия, обрыв или порча: вызывающий пересоберёт индекс из книги */
    public static BookIndex read(InputStream in) throws IOException {
        Src s = new Src(in);
        if (s.u8() != 'Z' || s.u8() != 'I' || s.u8() != 'D' || s.u8() != 'X') {
            throw new IOException("Это не индекс книги");
        }
        int version = s.i32();
        if (version != FORMAT_VERSION) throw new IOException("Версия индекса " + version + " не поддерживается");
        String title = new String(s.chars());
        String author = new String(s.chars());
        int n = s.varint();
        char[] text = s.chars();
        if (n < 0 || n > text.length) throw new IOException("Число слов не сходится с текстом");
        int[] ws = new int[n + 1];
        if (n == 0) {
            if (text.length != 0) throw new IOException("Текст без слов");
            ws[0] = 1;
        } else {
            int sum = 0;
            for (int i = 0; i < n; i++) {
                int delta = s.varint();
                if (delta < 2) throw new IOException("Пустое слово в индексе");
                ws[i] = sum;
                sum += delta;
                if (sum < 0) throw new IOException("Переполнение смещений");
            }
            ws[n] = sum;
            if (sum != text.length + 1) throw new IOException("Смещения слов не сходятся с текстом");
        }
        byte[] flags = new byte[n];
        s.bytes(flags, 0, n);
        int chapters = s.varint();
        if (chapters < 0 || chapters > n || (n > 0 && chapters == 0)) throw new IOException("Главы повреждены");
        String[] titles = new String[chapters];
        int[] first = new int[chapters];
        for (int i = 0; i < chapters; i++) {
            titles[i] = new String(s.chars());
            first[i] = s.varint();
            if (first[i] < 0 || first[i] >= n || (i == 0 ? first[i] != 0 : first[i] <= first[i - 1])) {
                throw new IOException("Начала глав повреждены");
            }
        }
        float avg = Float.intBitsToFloat(s.i32());
        if (s.i32() != END_MARK) throw new IOException("Нет метки конца индекса");
        return new BookIndex(title, author, text, ws, flags, titles, first, avg);
    }

    /** Читает кэш индекса; null, если файла нет, он битый или другой версии. */
    public static BookIndex loadCached(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return read(in);
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // файл уже прочитан, закрытие ничего не меняет
                }
            }
        }
    }

    /** Атомарно пишет кэш индекса; false, если карта недоступна (чтение книги от этого не страдает). */
    public static boolean saveCached(File f, final BookIndex index) {
        try {
            FileIo.writeAtomic(f, false, new FileIo.Sink() {
                @Override
                public void write(OutputStream out) throws IOException {
                    index.write(out);
                }
            });
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Буферизованная запись примитивов без синхронизации потоков (индекс на миллион слов пишется часто по байту). */
    private static final class Dst {
        private final OutputStream out;
        private final byte[] buf = new byte[1 << 16];
        private int n;

        Dst(OutputStream out) {
            this.out = out;
        }

        void u8(int b) throws IOException {
            if (n == buf.length) flush();
            buf[n++] = (byte) b;
        }

        void i32(int v) throws IOException {
            u8(v >>> 24);
            u8(v >>> 16);
            u8(v >>> 8);
            u8(v);
        }

        void varint(int v) throws IOException {
            while ((v & ~0x7F) != 0) {
                u8((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            u8(v);
        }

        void bytes(byte[] b, int off, int len) throws IOException {
            flush();
            out.write(b, off, len);
        }

        /** Длина в символах, длина в байтах, затем UTF-8 (одиночные суррогаты кодируются тремя байтами и возвращаются как были). */
        void chars(char[] c) throws IOException {
            long bytes = 0;
            for (char ch : c) bytes += ch < 0x80 ? 1 : ch < 0x800 ? 2 : 3;
            varint(c.length);
            varint((int) bytes);
            for (char ch : c) {
                if (ch < 0x80) {
                    u8(ch);
                } else if (ch < 0x800) {
                    u8(0xC0 | (ch >> 6));
                    u8(0x80 | (ch & 0x3F));
                } else {
                    u8(0xE0 | (ch >> 12));
                    u8(0x80 | ((ch >> 6) & 0x3F));
                    u8(0x80 | (ch & 0x3F));
                }
            }
        }

        void flush() throws IOException {
            if (n > 0) {
                out.write(buf, 0, n);
                n = 0;
            }
        }
    }

    private static final class Src {
        private final InputStream in;
        private final byte[] buf = new byte[1 << 16];
        private int pos;
        private int lim;

        Src(InputStream in) {
            this.in = in;
        }

        int u8() throws IOException {
            if (pos >= lim) {
                lim = in.read(buf, 0, buf.length);
                pos = 0;
                if (lim <= 0) {
                    lim = 0;
                    throw new IOException("Индекс оборван");
                }
            }
            return buf[pos++] & 0xFF;
        }

        int i32() throws IOException {
            return (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
        }

        int varint() throws IOException {
            int v = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = u8();
                v |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return v;
            }
            throw new IOException("Битое число в индексе");
        }

        void bytes(byte[] out, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) out[off + i] = (byte) u8();
        }

        char[] chars() throws IOException {
            int len = varint();
            int bytes = varint();
            if (len < 0 || len > MAX_CHARS || bytes < len || bytes > 3L * len) {
                throw new IOException("Битая длина текста в индексе");
            }
            char[] out = new char[len];
            int o = 0;
            int used = 0;
            while (o < len) {
                int b = u8();
                used++;
                if (b < 0x80) {
                    out[o++] = (char) b;
                } else if ((b & 0xE0) == 0xC0) {
                    int b2 = u8();
                    used++;
                    if ((b2 & 0xC0) != 0x80) throw new IOException("Битый UTF-8 в индексе");
                    out[o++] = (char) (((b & 0x1F) << 6) | (b2 & 0x3F));
                } else if ((b & 0xF0) == 0xE0) {
                    int b2 = u8();
                    int b3 = u8();
                    used += 2;
                    if ((b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) throw new IOException("Битый UTF-8 в индексе");
                    out[o++] = (char) (((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F));
                } else {
                    throw new IOException("Битый UTF-8 в индексе");
                }
            }
            if (used != bytes) throw new IOException("Длина текста в байтах не сходится");
            return out;
        }
    }
}
