package com.themoon.y1.reader;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;

/**
 * Терпимый однопроходный сканер XML/XHTML/FB2 для всех форматов книги.
 *
 * Свой сканер, а не XML-парсер: старые EPUB содержат {@code &nbsp;} и незакрытые теги, на которых
 * строгий парсер падает, а {@code android.util.Xml} нельзя проверить JUnit на JVM. Дерево не строится:
 * обработчик получает поток событий, а тело {@code script}, {@code style} и (по запросу) {@code binary}
 * пропускается посимвольно без выделения памяти (обложка FB2 это сотни килобайт base64).
 *
 * Имена тегов и атрибутов приводятся к нижнему регистру, префикс пространства имён отрезается
 * ({@code fb:p} это {@code p}, {@code l:href} это {@code href}). Пустые элементы ({@code br}, {@code img}
 * и подобные) и теги вида {@code <x/>} дают start и сразу end, а лишний {@code </br>} игнорируется.
 */
public final class MarkupScanner {

    public interface Handler {
        /** @param attrs плоский массив имя, значение, имя, значение... */
        void start(String name, String[] attrs, boolean selfClosing);

        void end(String name);

        void text(char[] buf, int off, int len);
    }

    /** Обработчик бросает это, когда нужного уже достаточно (например, закрылся title-info). */
    public static final class StopScan extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StopScan() {
            super("stop");
        }

        /** Стек не нужен: исключение служит только для выхода из цикла. */
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final String[] EMPTY = new String[0];
    private static final int NONE = -2;
    private static final String[] ENT_NAMES = {"amp", "lt", "gt", "quot", "apos", "nbsp", "mdash", "ndash",
            "hellip", "laquo", "raquo", "lsquo", "rsquo", "ldquo", "rdquo", "shy"};
    /** -2 значит «выбросить» (мягкий перенос в тексте книги только мешает токенизатору). */
    private static final int[] ENT_VALUES = {'&', '<', '>', '"', '\'', 0xA0, 0x2014, 0x2013,
            0x2026, 0xAB, 0xBB, 0x2018, 0x2019, 0x201C, 0x201D, -2};

    private final Reader in;
    private final Handler h;
    private final boolean skipBinary;
    private final char[] buf = new char[8192];
    private int pos;
    private int lim;
    private int pushed = NONE;
    private final char[] text = new char[4096];
    private int tlen;
    private final char[] ent = new char[16];
    private int litLen;
    private final StringBuilder nameSb = new StringBuilder(32);
    private final StringBuilder attrSb = new StringBuilder(32);
    private final StringBuilder valSb = new StringBuilder(64);
    private final ArrayList<String> attrs = new ArrayList<String>();

    private MarkupScanner(Reader in, Handler h, boolean skipBinary) {
        this.in = in;
        this.h = h;
        this.skipBinary = skipBinary;
    }

    public static void scan(Reader in, Handler h) throws IOException {
        scan(in, h, false);
    }

    /** @param skipBinary не отдавать содержимое {@code binary} (FB2: обложка и картинки) */
    public static void scan(Reader in, Handler h, boolean skipBinary) throws IOException {
        new MarkupScanner(in, h, skipBinary).run();
    }

    private void run() throws IOException {
        try {
            int c;
            while ((c = read()) >= 0) {
                if (c == '<') {
                    tag();
                } else if (c == '&') {
                    entityInText();
                } else {
                    addText((char) c);
                }
            }
            flushText();
        } catch (StopScan stop) {
            // Обработчик получил всё нужное; недоставленный хвост текста не нужен.
        }
    }

    private int read() throws IOException {
        if (pushed != NONE) {
            int c = pushed;
            pushed = NONE;
            return c;
        }
        if (pos >= lim) {
            lim = in.read(buf, 0, buf.length);
            pos = 0;
            if (lim <= 0) {
                lim = 0;
                return -1;
            }
        }
        return buf[pos++];
    }

    private void addText(char c) {
        if (tlen == text.length) flushText();
        text[tlen++] = c;
    }

    private void addCp(int cp) {
        if (cp >= 0x10000) {
            // Character.highSurrogate/lowSurrogate появились только в API 19, а Y1 живёт на API 17.
            int v = cp - 0x10000;
            addText((char) (0xD800 + (v >> 10)));
            addText((char) (0xDC00 + (v & 0x3FF)));
        } else {
            addText((char) cp);
        }
    }

    private void flushText() {
        if (tlen > 0) {
            int n = tlen;
            tlen = 0;
            h.text(text, 0, n);
        }
    }

    private static boolean isSpace(int c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f';
    }

    private static boolean isNameStart(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':';
    }

    private static boolean isAlnum(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    // ------------------------------------------------------------------ теги

    private void tag() throws IOException {
        int c = read();
        if (c < 0) {
            addText('<');
        } else if (c == '!') {
            bang();
        } else if (c == '?') {
            skipUntilGt();
        } else if (c == '/') {
            endTag();
        } else if (isNameStart(c)) {
            startTag(c);
        } else {
            // «a < b» и подобное: не тег, а обычный символ.
            addText('<');
            pushed = c;
        }
    }

    private void skipUntilGt() throws IOException {
        int c;
        while ((c = read()) >= 0 && c != '>') {
            // пропуск
        }
    }

    private void bang() throws IOException {
        int c = read();
        if (c == '-') {
            int c2 = read();
            if (c2 == '-') {
                int dashes = 0;
                while ((c = read()) >= 0) {
                    if (c == '-') {
                        dashes++;
                    } else {
                        if (c == '>' && dashes >= 2) return;
                        dashes = 0;
                    }
                }
            } else if (c2 != '>' && c2 >= 0) {
                skipUntilGt();
            }
        } else if (c == '[') {
            if (matchCdata()) {
                cdata();
            } else {
                skipDecl(1);
            }
        } else if (c >= 0 && c != '>') {
            skipDecl(0);
        }
    }

    private boolean matchCdata() throws IOException {
        final String want = "CDATA[";
        for (int i = 0; i < want.length(); i++) {
            int c = read();
            if (c != want.charAt(i)) {
                if (c >= 0) pushed = c;
                return false;
            }
        }
        return true;
    }

    private void cdata() throws IOException {
        int br = 0;
        int c;
        while ((c = read()) >= 0) {
            if (c == ']') {
                br++;
            } else {
                if (c == '>' && br >= 2) {
                    for (int k = 2; k < br; k++) addText(']');
                    return;
                }
                for (int k = 0; k < br; k++) addText(']');
                br = 0;
                addText((char) c);
            }
        }
        for (int k = 0; k < br; k++) addText(']');
    }

    /** DOCTYPE может нести внутренний набор в квадратных скобках, внутри которого есть «>». */
    private void skipDecl(int depth) throws IOException {
        int c;
        while ((c = read()) >= 0) {
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return;
            }
        }
    }

    private void appendNameChar(StringBuilder sb, int c) {
        if (c == ':') {
            sb.setLength(0);
        } else {
            sb.append(Character.toLowerCase((char) c));
        }
    }

    /** Читает имя, начиная с first, в sb; возвращает символ, на котором имя кончилось (он уже прочитан). */
    private int readName(StringBuilder sb, int first) throws IOException {
        sb.setLength(0);
        appendNameChar(sb, first);
        int c;
        while ((c = read()) >= 0 && !isSpace(c) && c != '/' && c != '>' && c != '<' && c != '=') {
            appendNameChar(sb, c);
        }
        return c;
    }

    private void endTag() throws IOException {
        int c = read();
        if (c < 0) return;
        if (!isNameStart(c)) {
            if (c != '>') skipUntilGt();
            return;
        }
        int term = readName(nameSb, c);
        String name = nameSb.toString();
        if (term != '>') skipUntilGt();
        if (isVoid(name)) return;
        flushText();
        h.end(name);
    }

    private void startTag(int first) throws IOException {
        int c = readName(nameSb, first);
        String name = nameSb.toString();
        attrs.clear();
        boolean selfClosing = false;
        for (;;) {
            while (c >= 0 && isSpace(c)) c = read();
            if (c < 0) return;
            if (c == '>') break;
            if (c == '/') {
                int n = read();
                if (n == '>') {
                    selfClosing = true;
                    break;
                }
                c = n;
                continue;
            }
            if (c == '<') {
                // Тег не закрыт, а уже начался следующий: этот считаем законченным.
                pushed = c;
                break;
            }
            if (c == '=') {
                c = read();
                continue;
            }
            c = readName(attrSb, c);
            String attrName = attrSb.toString();
            while (c >= 0 && isSpace(c)) c = read();
            valSb.setLength(0);
            if (c == '=') {
                c = read();
                while (c >= 0 && isSpace(c)) c = read();
                if (c == '"' || c == '\'') {
                    int quote = c;
                    while ((c = read()) >= 0 && c != quote) {
                        appendValueChar(c);
                    }
                    if (c < 0) return;
                    c = read();
                } else {
                    while (c >= 0 && !isSpace(c) && c != '>') {
                        appendValueChar(c);
                        c = read();
                    }
                }
            }
            attrs.add(attrName);
            attrs.add(valSb.toString());
        }
        String[] arr = attrs.isEmpty() ? EMPTY : attrs.toArray(new String[attrs.size()]);
        flushText();
        h.start(name, arr, selfClosing);
        if (selfClosing || isVoid(name)) {
            h.end(name);
        } else if (name.equals("script") || name.equals("style") || (skipBinary && name.equals("binary"))) {
            skipRaw(name);
            h.end(name);
        }
    }

    private void appendValueChar(int c) throws IOException {
        if (c != '&') {
            valSb.append((char) c);
            return;
        }
        int cp = parseEntity();
        if (cp >= 0) {
            valSb.appendCodePoint(cp);
        } else if (cp == -1) {
            valSb.append('&').append(ent, 0, litLen);
        }
    }

    /** Содержимое пропускается до {@code </name}, ничего не выделяя: на обложке FB2 это сотни КБ. */
    private void skipRaw(String name) throws IOException {
        int plen = name.length() + 2;
        int j = 0;
        int c;
        while ((c = read()) >= 0) {
            char lower = c < 128 ? Character.toLowerCase((char) c) : (char) c;
            char want = j == 0 ? '<' : j == 1 ? '/' : name.charAt(j - 2);
            if (lower == want) {
                j++;
                if (j == plen) {
                    skipUntilGt();
                    return;
                }
            } else {
                j = c == '<' ? 1 : 0;
            }
        }
    }

    private static boolean isVoid(String n) {
        switch (n) {
            case "br":
            case "hr":
            case "img":
            case "meta":
            case "link":
            case "input":
            case "col":
            case "area":
            case "base":
            case "wbr":
            case "embed":
            case "param":
            case "source":
            case "track":
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ сущности

    private void entityInText() throws IOException {
        int cp = parseEntity();
        if (cp >= 0) {
            addCp(cp);
        } else if (cp == -1) {
            addText('&');
            for (int i = 0; i < litLen; i++) addText(ent[i]);
        }
    }

    /**
     * Читает сущность после «&amp;». Возвращает кодпоинт, -2 если символ надо выбросить, -1 если
     * сущность неизвестна: тогда её буквальный текст (без «&amp;») лежит в ent[0..litLen).
     */
    private int parseEntity() throws IOException {
        int n = 0;
        int c = read();
        while (c >= 0 && n < 10 && (isAlnum(c) || (n == 0 && c == '#'))) {
            ent[n++] = (char) c;
            c = read();
        }
        if (c == ';' && n > 0) {
            int cp = lookup(n);
            if (cp != -1) return cp;
            ent[n++] = ';';
            litLen = n;
            return -1;
        }
        litLen = n;
        pushed = c;
        return -1;
    }

    private int lookup(int n) {
        if (ent[0] == '#') {
            if (n < 2) return -1;
            boolean hex = ent[1] == 'x' || ent[1] == 'X';
            int from = hex ? 2 : 1;
            if (from >= n) return -1;
            long v = 0;
            for (int i = from; i < n; i++) {
                int d = Character.digit(ent[i], hex ? 16 : 10);
                if (d < 0) return -1;
                v = v * (hex ? 16 : 10) + d;
                if (v > 0x10FFFF) return -1;
            }
            return v == 0 ? -1 : (int) v;
        }
        for (int i = 0; i < ENT_NAMES.length; i++) {
            String nm = ENT_NAMES[i];
            if (nm.length() != n) continue;
            boolean ok = true;
            for (int k = 0; k < n; k++) {
                if (nm.charAt(k) != ent[k]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return ENT_VALUES[i];
        }
        return -1;
    }
}
