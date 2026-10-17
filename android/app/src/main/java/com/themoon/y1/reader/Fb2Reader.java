package com.themoon.y1.reader;

import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * FB2 и fb2.zip. Кодировка берётся из пролога ({@link TextDecoding}), обложка и картинки не читаются.
 *
 * Читаются только {@code body} без атрибута {@code name}: тело {@code name="notes"} (сноски) и ссылки
 * {@code a type="note"} выбрасываются, иначе номер сноски сливался бы со словом, а текст сноски торчал бы
 * посреди главы. Название главы берётся из {@code title} секции и читается как отдельное предложение.
 */
public final class Fb2Reader implements BookReader {

    /** Название для текста до первого заголовка секции: в интерфейс книги оно попадает как есть. */
    private static final String FIRST_CHAPTER = "Начало";
    static final String ERR_OPEN = "Не удалось открыть книгу";
    static final String ERR_EMPTY = "В книге нет текста";

    @Override
    public BookIndex read(File file, Progress progress) throws IOException {
        Source src = Source.open(file);
        try {
            return readStream(src.in, src.size, progress);
        } finally {
            src.close();
        }
    }

    /** @param totalBytes размер потока для процентов; 0 или меньше значит «неизвестен» */
    public BookIndex readStream(InputStream in, long totalBytes, Progress progress) throws IOException {
        Counting counting = new Counting(in);
        Body body = new Body(counting, totalBytes, progress);
        try {
            MarkupScanner.scan(TextDecoding.open(counting), body, true);
        } catch (Cancelled c) {
            throw new InterruptedIOException("Чтение отменено");
        } catch (EOFException truncated) {
            // Обрезанный файл или архив: читателю отдаём то, что успели прочитать.
        } catch (ZipException truncated) {
            // То же для битого сжатого потока в fb2.zip.
        }
        if (progress != null) progress.onProgress(100);
        BookIndex idx = body.finish();
        if (idx.wordCount() == 0) throw new IOException(ERR_EMPTY);
        return idx;
    }

    /** Название и автор из title-info без чтения всей книги; массив {название, автор}, строки могут быть пустыми. */
    public static String[] readMeta(InputStream in) throws IOException {
        final TitleInfo ti = new TitleInfo();
        MarkupScanner.scan(TextDecoding.open(in), new MarkupScanner.Handler() {
            @Override
            public void start(String name, String[] attrs, boolean selfClosing) {
                ti.start(name);
            }

            @Override
            public void end(String name) {
                ti.end(name);
                if (ti.done) throw new MarkupScanner.StopScan();
            }

            @Override
            public void text(char[] buf, int off, int len) {
                ti.text(buf, off, len);
            }
        }, true);
        return new String[]{ti.title(), ti.author()};
    }

    /** Открытый вход книги: сам файл или первая запись {@code .fb2} архива. */
    static final class Source {
        final InputStream in;
        final long size;
        private final ZipFile zip;

        private Source(InputStream in, long size, ZipFile zip) {
            this.in = in;
            this.size = size;
            this.zip = zip;
        }

        static Source open(File file) throws IOException {
            if (!isZip(file)) return new Source(new java.io.FileInputStream(file), file.length(), null);
            ZipFile zip;
            try {
                zip = new ZipFile(file);
            } catch (ZipException e) {
                throw new IOException(ERR_OPEN);
            }
            try {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!e.isDirectory() && e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".fb2")) {
                        long size = e.getSize() > 0 ? e.getSize() : file.length();
                        return new Source(zip.getInputStream(e), size, zip);
                    }
                }
            } catch (IOException e) {
                closeQuietly(zip);
                throw e;
            }
            closeQuietly(zip);
            throw new IOException(ERR_OPEN);
        }

        void close() {
            try {
                in.close();
            } catch (IOException ignored) {
                // читать больше нечего
            }
            if (zip != null) closeQuietly(zip);
        }

        private static void closeQuietly(ZipFile z) {
            try {
                z.close();
            } catch (IOException ignored) {
                // закрытие архива после чтения ничего не меняет
            }
        }

        /** Архив определяется по сигнатуре, а не по имени: переименованный файл остаётся читаемым. */
        static boolean isZip(File f) throws IOException {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                return raf.length() >= 4 && raf.readInt() == 0x504B0304;
            } finally {
                raf.close();
            }
        }
    }

    private static final class Counting extends FilterInputStream {
        long count;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    /** Собирает название и автора из title-info (первый автор; имя и фамилия через пробел, иначе ник). */
    static final class TitleInfo {
        boolean done;
        private boolean in;
        private boolean inAuthor;
        private boolean authorTaken;
        private StringBuilder target;
        private final StringBuilder title = new StringBuilder();
        private final StringBuilder first = new StringBuilder();
        private final StringBuilder last = new StringBuilder();
        private final StringBuilder nick = new StringBuilder();

        void start(String name) {
            if (done) return;
            if (name.equals("title-info")) {
                in = true;
            } else if (!in) {
                return;
            } else if (name.equals("book-title") && title.length() == 0) {
                target = title;
            } else if (name.equals("author") && !authorTaken) {
                inAuthor = true;
            } else if (inAuthor && name.equals("first-name")) {
                target = first;
            } else if (inAuthor && name.equals("last-name")) {
                target = last;
            } else if (inAuthor && name.equals("nickname")) {
                target = nick;
            }
        }

        void end(String name) {
            if (done || !in) return;
            if (name.equals("title-info")) {
                in = false;
                done = true;
            } else if (name.equals("author") && inAuthor) {
                inAuthor = false;
                authorTaken = true;
            } else if (name.equals("book-title") || name.equals("first-name")
                    || name.equals("last-name") || name.equals("nickname")) {
                target = null;
            }
        }

        void text(char[] buf, int off, int len) {
            if (target != null) target.append(buf, off, len);
        }

        String title() {
            return BookBuilder.cleanTitle(title);
        }

        String author() {
            String name = BookBuilder.cleanTitle(first + " " + last);
            return name.length() > 0 ? name : BookBuilder.cleanTitle(nick);
        }
    }

    private static final class Body implements MarkupScanner.Handler {
        private final Counting in;
        private final long total;
        private final Progress progress;
        private final BookBuilder builder = new BookBuilder();
        private final TitleInfo info = new TitleInfo();
        private final ArrayList<String> stack = new ArrayList<String>();
        private final StringBuilder titleBuf = new StringBuilder();
        private boolean inBody;
        private boolean skippingBody;
        private boolean inTitle;
        private boolean started;
        private int noteDepth;
        private int lastPercent = -1;

        Body(Counting in, long total, Progress progress) {
            this.in = in;
            this.total = total;
            this.progress = progress;
        }

        BookIndex finish() {
            builder.meta(info.title(), info.author());
            return builder.build();
        }

        private static String attr(String[] attrs, String name) {
            for (int i = 0; i + 1 < attrs.length; i += 2) {
                if (attrs[i].equals(name)) return attrs[i + 1];
            }
            return null;
        }

        /** Элементы, после которых текст не должен слипаться со следующим абзацем. */
        private static boolean breaksParagraph(String n) {
            switch (n) {
                case "p":
                case "v":
                case "subtitle":
                case "text-author":
                case "cite":
                case "epigraph":
                case "poem":
                case "stanza":
                case "annotation":
                case "section":
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void start(String name, String[] attrs, boolean selfClosing) {
            info.start(name);
            stack.add(name);
            if (name.equals("body")) {
                if (attr(attrs, "name") != null) skippingBody = true;
                else inBody = true;
                return;
            }
            if (skippingBody || !inBody) return;
            if (name.equals("a")) {
                if ("note".equals(attr(attrs, "type"))) noteDepth++;
            } else if (name.equals("title")) {
                String parent = stack.size() >= 2 ? stack.get(stack.size() - 2) : "";
                // Заголовки стихов и таблиц — обычные абзацы; главой становится только title секции или тела.
                if (parent.equals("section") || parent.equals("body")) {
                    builder.endParagraph();
                    inTitle = true;
                    titleBuf.setLength(0);
                }
            } else if (inTitle) {
                if (name.equals("p") && titleBuf.length() > 0) titleBuf.append(' ');
            } else if (breaksParagraph(name)) {
                builder.endParagraph();
            }
        }

        @Override
        public void end(String name) {
            info.end(name);
            int at = stack.lastIndexOf(name);
            if (at >= 0) {
                while (stack.size() > at) stack.remove(stack.size() - 1);
            }
            if (name.equals("body")) {
                if (skippingBody) skippingBody = false;
                else inBody = false;
                return;
            }
            if (skippingBody || !inBody) return;
            if (name.equals("a")) {
                if (noteDepth > 0) noteDepth--;
            } else if (name.equals("title")) {
                if (inTitle) {
                    inTitle = false;
                    finishTitle();
                }
            } else if (!inTitle && breaksParagraph(name)) {
                builder.endParagraph();
            }
        }

        private void finishTitle() {
            String title = BookBuilder.cleanTitle(titleBuf);
            if (title.length() == 0) return;
            builder.startChapter(title);
            started = true;
            builder.text(title);
            builder.forceSentenceEnd();
            builder.endParagraph();
        }

        @Override
        public void text(char[] buf, int off, int len) {
            info.text(buf, off, len);
            if (skippingBody || !inBody || noteDepth > 0) return;
            report();
            if (inTitle) {
                titleBuf.append(buf, off, len);
                return;
            }
            if (!started) {
                if (!hasContent(buf, off, len)) return;
                builder.startChapter(FIRST_CHAPTER);
                started = true;
            }
            builder.text(buf, off, len);
        }

        private static boolean hasContent(char[] buf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                if (!BookBuilder.isSeparator(buf[i])) return true;
            }
            return false;
        }

        private void report() {
            if (progress == null) return;
            if (progress.isCancelled()) throw new Cancelled();
            if (total <= 0) return;
            int p = (int) Math.min(99, in.count * 100 / total);
            if (p != lastPercent) {
                lastPercent = p;
                progress.onProgress(p);
            }
        }
    }
}
