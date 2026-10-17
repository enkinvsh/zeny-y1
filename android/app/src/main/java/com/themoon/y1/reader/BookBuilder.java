package com.themoon.y1.reader;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Собирает {@link BookIndex} из потока текста, который отдают читалки форматов.
 *
 * Токенизатор написан вручную: {@code String.split("\\s+")} не считает разделителями неразрывный и тонкие
 * пробелы, а в реальных книгах U+00A0 стоит между инициалами и после тире диалога. Слова пишутся сразу в
 * общий {@code char[]} через один пробел, отдельных строк на слово нет: на 512 МБ с Android 4.2 книга
 * в сотни тысяч слов иначе съела бы кучу.
 *
 * Один экземпляр на одну книгу: после {@link #build()} продолжать нельзя.
 */
public final class BookBuilder {

    private static final char NBSP = '\u00A0';

    private String title = "";
    private String author = "";
    private char[] text = new char[1 << 16];
    private int tlen;
    private int[] starts = new int[1 << 12];
    private byte[] flags = new byte[1 << 12];
    private int count;
    private final ArrayList<String> chapterTitles = new ArrayList<String>();
    private int[] chapterFirst = new int[16];
    private String pendingTitle;
    /** Идёт слово (его символы лежат в text хвостом, пробела после него ещё нет). */
    private boolean inWord;
    /** Слово состоит из тире и NBSP и ждёт следующее слово, чтобы приклеиться к нему. */
    private boolean glue;
    /** С последнего endParagraph пришло хотя бы одно слово. */
    private boolean paraOpen;
    /** Значение paraOpen до открытия текущего слова: нужно, чтобы выброшенное тире не «открыло» абзац. */
    private boolean paraOpenBeforeWord;

    public void meta(String title, String author) {
        this.title = title == null ? "" : cleanTitle(title);
        this.author = author == null ? "" : cleanTitle(author);
    }

    /** Название главы или имени без переносов и повторных пробелов, пустая строка вместо null. */
    public static String cleanTitle(CharSequence s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isIgnorable(c)) continue;
            if (isSeparator(c)) {
                space = sb.length() > 0;
            } else {
                if (space) sb.append(' ');
                space = false;
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Разделитель слов: все пробельные, включая неразрывный и тонкие пробелы, которых нет в isWhitespace. */
    public static boolean isSeparator(char c) {
        return c <= ' ' || c == 0x7F || c == 0x85 || c == NBSP || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029
                || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /** Невидимые символы внутри слова: мягкий перенос, нулевая ширина, BOM. Они не должны рвать слово. */
    private static boolean isIgnorable(char c) {
        return c == 0xAD || c == 0x200B || c == 0x200C || c == 0x200D || c == 0x2060 || c == 0xFEFF;
    }

    private static boolean isDash(char c) {
        return c == '-' || (c >= 0x2010 && c <= 0x2015) || c == 0x2212;
    }

    /** Закрывающие кавычки и скобки: после точки они не отменяют конец предложения. */
    private static boolean isCloser(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '}' || c == 0xBB
                || c == 0x2019 || c == 0x201C || c == 0x201D || c == 0x203A;
    }

    public void startChapter(String chapterTitle) {
        endParagraph();
        // Глава без единого слова не нужна: название следующей перекрывает её.
        pendingTitle = cleanTitle(chapterTitle);
    }

    /** Сколько глав уже открыто словами (глава без слов не считается). */
    public int chapterCount() {
        return chapterTitles.size();
    }

    public void text(CharSequence s) {
        for (int i = 0; i < s.length(); i++) feed(s.charAt(i));
    }

    public void text(char[] buf, int off, int len) {
        for (int i = off, end = off + len; i < end; i++) feed(buf[i]);
    }

    private void feed(char c) {
        if (isIgnorable(c)) return;
        if (isSeparator(c)) {
            if (inWord && !glue) finishToken();
            return;
        }
        if (!inWord) openWord();
        glue = false;
        appendChar(c);
    }

    private void appendChar(char c) {
        if (tlen == text.length) text = Arrays.copyOf(text, text.length + (text.length >> 1));
        text[tlen++] = c;
    }

    private void openWord() {
        if (pendingTitle == null && chapterTitles.isEmpty()) pendingTitle = "";
        if (count == starts.length) {
            starts = Arrays.copyOf(starts, count + (count >> 1));
            flags = Arrays.copyOf(flags, starts.length);
        }
        if (count > 0) appendChar(' ');
        starts[count] = tlen;
        byte f = 0;
        if (pendingTitle != null) {
            f |= BookIndex.F_CHAP_START;
            if (chapterTitles.size() == chapterFirst.length) {
                chapterFirst = Arrays.copyOf(chapterFirst, chapterFirst.length * 2);
            }
            chapterFirst[chapterTitles.size()] = count;
            chapterTitles.add(pendingTitle);
            pendingTitle = null;
        }
        flags[count] = f;
        count++;
        inWord = true;
        paraOpenBeforeWord = paraOpen;
        paraOpen = true;
    }

    /** Токен кончился пробелом: либо тире-одиночка ждёт следующего слова, либо слово закрывается. */
    private void finishToken() {
        int s = starts[count - 1];
        if (tlen - s == 1 && isDash(text[s])) {
            appendChar(NBSP);
            glue = true;
            return;
        }
        closeWord();
    }

    private void closeWord() {
        int s = starts[count - 1];
        int i = tlen - 1;
        while (i > s && isCloser(text[i])) i--;
        char last = text[i];
        if (last == '.' || last == '!' || last == '?' || last == '\u2026') {
            flags[count - 1] |= BookIndex.F_SENT_END;
        } else if (last == ',' || last == ':' || last == ';') {
            flags[count - 1] |= BookIndex.F_PAUSE_MID;
        }
        inWord = false;
        glue = false;
    }

    /** Закрывает открытое слово; тире, оставшееся без слова в конце абзаца, выбрасывается как знак без смысла. */
    private void finishWord() {
        if (!inWord) return;
        int w = count - 1;
        if (glue || (tlen - starts[w] == 1 && isDash(text[starts[w]]))) {
            if ((flags[w] & BookIndex.F_CHAP_START) != 0) {
                pendingTitle = chapterTitles.remove(chapterTitles.size() - 1);
            }
            tlen = starts[w] - (w > 0 ? 1 : 0);
            count = w;
            inWord = false;
            glue = false;
            paraOpen = paraOpenBeforeWord;
        } else {
            closeWord();
        }
    }

    public void endParagraph() {
        finishWord();
        if (count > 0 && paraOpen) {
            flags[count - 1] |= BookIndex.F_PARA_END | BookIndex.F_SENT_END;
            paraOpen = false;
        }
    }

    public void forceSentenceEnd() {
        finishWord();
        if (count > 0) flags[count - 1] |= BookIndex.F_SENT_END;
    }

    public BookIndex build() {
        finishWord();
        int[] ws = new int[count + 1];
        System.arraycopy(starts, 0, ws, 0, count);
        ws[count] = tlen + 1;
        char[] t = Arrays.copyOf(text, tlen);
        byte[] fl = Arrays.copyOf(flags, count);
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += WordTimer.factor(WordTimer.letterCount(t, ws[i], ws[i + 1] - 1), fl[i]);
        }
        float avg = count == 0 ? 1f : (float) (sum / count);
        String[] titles = chapterTitles.toArray(new String[chapterTitles.size()]);
        int[] first = Arrays.copyOf(chapterFirst, titles.length);
        return new BookIndex(title, author, t, ws, fl, titles, first, avg);
    }
}
