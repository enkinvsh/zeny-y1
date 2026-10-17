package com.themoon.y1.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * EPUB: {@code container.xml} → OPF → главы в порядке {@code spine} → текст из XHTML.
 *
 * Название главы берётся из первого заголовка h1..h3, с которого начинается документ, иначе из
 * {@code title} головы, иначе «Глава N». Оглавление NCX/nav не читается: у большинства EPUB файл
 * равен главе, и эвристика даёт то же без второго прохода по архиву.
 */
public final class EpubReader implements BookReader {

    static final String ERR_OPEN = "Не удалось открыть книгу";
    static final String ERR_EMPTY = "В книге нет текста";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Доля символов-«дырок» в тексте, после которой зашифрованная книга считается нечитаемой. */
    private static final int GARBAGE_PERCENT = 20;

    /** Разобранный OPF: метаданные, порядок документов и обложка. Пути уже приведены к виду записей архива. */
    static final class Pkg {
        String title = "";
        String author = "";
        final ArrayList<String> spine = new ArrayList<String>();
        String coverPath;
    }

    @Override
    public BookIndex read(File file, Progress progress) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(file);
        } catch (ZipException e) {
            throw new IOException(ERR_OPEN);
        }
        try {
            return readZip(zip, progress);
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
                // архив уже прочитан
            }
        }
    }

    private BookIndex readZip(ZipFile zip, Progress progress) throws IOException {
        Pkg pkg = readPackage(zip);
        BookBuilder builder = new BookBuilder();
        builder.meta(pkg.title, pkg.author);
        Stats stats = new Stats();
        int n = pkg.spine.size();
        try {
            for (int i = 0; i < n; i++) {
                if (progress != null) {
                    if (progress.isCancelled()) throw new Cancelled();
                    progress.onProgress(i * 100 / n);
                }
                ZipEntry e = zip.getEntry(pkg.spine.get(i));
                if (e == null || e.isDirectory()) continue;
                InputStream in = zip.getInputStream(e);
                try {
                    MarkupScanner.scan(TextDecoding.open(in),
                            new DocHandler(builder, pkg.title, progress, stats), false);
                } catch (ZipException damaged) {
                    // Одна битая запись не должна отнимать у читателя остальную книгу.
                } finally {
                    in.close();
                }
            }
        } catch (Cancelled c) {
            throw new InterruptedIOException("Чтение отменено");
        }
        if (progress != null) progress.onProgress(100);
        BookIndex idx = builder.build();
        boolean encrypted = zip.getEntry("META-INF/encryption.xml") != null;
        if (idx.wordCount() == 0) throw new IOException(encrypted ? ERR_OPEN : ERR_EMPTY);
        if (encrypted && stats.garbage * 100L > (stats.garbage + stats.letters) * GARBAGE_PERCENT) {
            throw new IOException(ERR_OPEN);
        }
        return idx;
    }

    /** OPF книги: название, автор, документы по порядку spine и обложка. */
    static Pkg readPackage(ZipFile zip) throws IOException {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) throw new IOException(ERR_OPEN);
        final String[] opfPath = {null};
        scanEntry(zip, container, new MarkupScanner.Handler() {
            @Override
            public void start(String name, String[] attrs, boolean selfClosing) {
                if (opfPath[0] == null && name.equals("rootfile")) {
                    opfPath[0] = attr(attrs, "full-path");
                }
            }

            @Override
            public void end(String name) {
            }

            @Override
            public void text(char[] buf, int off, int len) {
            }
        });
        if (opfPath[0] == null || opfPath[0].length() == 0) throw new IOException(ERR_OPEN);
        String opfName = decodePercent(opfPath[0]);
        ZipEntry opf = zip.getEntry(opfName);
        if (opf == null) throw new IOException(ERR_OPEN);

        final String dir = opfName.lastIndexOf('/') >= 0 ? opfName.substring(0, opfName.lastIndexOf('/') + 1) : "";
        final Pkg pkg = new Pkg();
        final HashMap<String, String[]> items = new HashMap<String, String[]>();
        final ArrayList<String[]> manifestOrder = new ArrayList<String[]>();
        final ArrayList<String> itemrefs = new ArrayList<String>();
        final String[] coverId = {null};
        final String[] coverByProperty = {null};
        final StringBuilder titleSb = new StringBuilder();
        final StringBuilder authorSb = new StringBuilder();
        scanEntry(zip, opf, new MarkupScanner.Handler() {
            private boolean inMetadata;
            private StringBuilder target;
            private boolean titleTaken;
            private boolean authorTaken;

            @Override
            public void start(String name, String[] attrs, boolean selfClosing) {
                if (name.equals("metadata")) {
                    inMetadata = true;
                } else if (inMetadata && name.equals("title") && !titleTaken) {
                    target = titleSb;
                } else if (inMetadata && name.equals("creator") && !authorTaken) {
                    target = authorSb;
                } else if (name.equals("meta") && "cover".equals(attr(attrs, "name"))) {
                    coverId[0] = attr(attrs, "content");
                } else if (name.equals("item")) {
                    String id = attr(attrs, "id");
                    String href = attr(attrs, "href");
                    if (id == null || href == null) return;
                    String type = attr(attrs, "media-type");
                    String props = attr(attrs, "properties");
                    String[] item = {id, resolve(dir, href), type == null ? "" : type.toLowerCase(Locale.ROOT),
                            props == null ? "" : props};
                    items.put(id, item);
                    manifestOrder.add(item);
                    if (coverByProperty[0] == null && (" " + item[3] + " ").contains(" cover-image ")) {
                        coverByProperty[0] = item[1];
                    }
                } else if (name.equals("itemref")) {
                    String idref = attr(attrs, "idref");
                    if (idref != null) itemrefs.add(idref);
                }
            }

            @Override
            public void end(String name) {
                if (name.equals("metadata")) {
                    inMetadata = false;
                } else if (target != null && (name.equals("title") || name.equals("creator"))) {
                    if (target == titleSb) titleTaken = true;
                    else authorTaken = true;
                    target = null;
                }
            }

            @Override
            public void text(char[] buf, int off, int len) {
                if (target != null) target.append(buf, off, len);
            }
        });
        pkg.title = BookBuilder.cleanTitle(titleSb);
        pkg.author = BookBuilder.cleanTitle(authorSb);
        for (String idref : itemrefs) {
            String[] item = items.get(idref);
            if (item != null && isDocument(item[2])) pkg.spine.add(item[1]);
        }
        if (pkg.spine.isEmpty()) {
            // Битый spine встречается в самодельных EPUB; порядок манифеста лучше, чем пустая книга.
            for (String[] item : manifestOrder) {
                if (isDocument(item[2])) pkg.spine.add(item[1]);
            }
        }
        String[] cover = coverId[0] == null ? null : items.get(coverId[0]);
        pkg.coverPath = cover != null ? cover[1] : coverByProperty[0];
        return pkg;
    }

    private static boolean isDocument(String mediaType) {
        return mediaType.equals("application/xhtml+xml") || mediaType.equals("text/html");
    }

    private static void scanEntry(ZipFile zip, ZipEntry e, MarkupScanner.Handler h) throws IOException {
        InputStream in = zip.getInputStream(e);
        try {
            MarkupScanner.scan(TextDecoding.open(in), h, false);
        } finally {
            in.close();
        }
    }

    private static String attr(String[] attrs, String name) {
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            if (attrs[i].equals(name)) return attrs[i + 1];
        }
        return null;
    }

    /** Путь из OPF относительно его папки → имя записи в архиве: без фрагмента, с раскрытыми %XX и «..». */
    static String resolve(String dir, String href) {
        int hash = href.indexOf('#');
        if (hash >= 0) href = href.substring(0, hash);
        String path = decodePercent(href);
        if (path.startsWith("/")) {
            path = path.substring(1);
        } else {
            path = dir + path;
        }
        ArrayList<String> parts = new ArrayList<String>();
        for (String seg : path.split("/")) {
            if (seg.length() == 0 || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** %XX как UTF-8; плюс остаётся плюсом: URLDecoder превратил бы его в пробел, а в именах файлов он бывает. */
    static String decodePercent(String s) {
        if (s.indexOf('%') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
                bytes.write(Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16));
                i += 2;
            } else {
                if (bytes.size() > 0) {
                    out.append(new String(bytes.toByteArray(), UTF8));
                    bytes.reset();
                }
                out.append(c);
            }
        }
        if (bytes.size() > 0) out.append(new String(bytes.toByteArray(), UTF8));
        return out.toString();
    }

    private static final class Stats {
        long letters;
        long garbage;
    }

    private static final class DocHandler implements MarkupScanner.Handler {
        private final BookBuilder builder;
        private final String bookTitle;
        private final Progress progress;
        private final Stats stats;
        private final StringBuilder headTitle = new StringBuilder();
        private final StringBuilder heading = new StringBuilder();
        private boolean inHead;
        private boolean inHeadTitle;
        private boolean inHeading;
        private boolean started;
        private int svgDepth;

        DocHandler(BookBuilder builder, String bookTitle, Progress progress, Stats stats) {
            this.builder = builder;
            this.bookTitle = bookTitle;
            this.progress = progress;
            this.stats = stats;
        }

        private static boolean isBlock(String n) {
            switch (n) {
                case "p":
                case "div":
                case "h1":
                case "h2":
                case "h3":
                case "h4":
                case "h5":
                case "h6":
                case "li":
                case "blockquote":
                case "br":
                case "tr":
                case "td":
                case "th":
                case "ul":
                case "ol":
                case "table":
                case "section":
                case "article":
                case "pre":
                case "hr":
                case "dt":
                case "dd":
                    return true;
                default:
                    return false;
            }
        }

        private static boolean isTopHeading(String n) {
            return n.equals("h1") || n.equals("h2") || n.equals("h3");
        }

        @Override
        public void start(String name, String[] attrs, boolean selfClosing) {
            if (svgDepth > 0) {
                if (name.equals("svg")) svgDepth++;
                return;
            }
            if (name.equals("svg")) {
                svgDepth = 1;
            } else if (name.equals("head")) {
                inHead = true;
            } else if (name.equals("body")) {
                inHead = false;
            } else if (name.equals("title")) {
                if (inHead) inHeadTitle = true;
            } else if (isBlock(name)) {
                if (isTopHeading(name) && !started && !inHeading) {
                    inHeading = true;
                    heading.setLength(0);
                } else if (inHeading) {
                    heading.append(' ');
                } else {
                    builder.endParagraph();
                }
            }
        }

        @Override
        public void end(String name) {
            if (svgDepth > 0) {
                if (name.equals("svg")) svgDepth--;
                return;
            }
            if (name.equals("head")) {
                inHead = false;
            } else if (name.equals("title")) {
                inHeadTitle = false;
            } else if (isBlock(name)) {
                if (inHeading && isTopHeading(name)) {
                    inHeading = false;
                    finishHeading();
                } else if (inHeading) {
                    heading.append(' ');
                } else {
                    builder.endParagraph();
                }
            }
        }

        private void finishHeading() {
            String title = BookBuilder.cleanTitle(heading);
            if (title.length() == 0) return;
            builder.startChapter(title);
            started = true;
            builder.text(title);
            builder.forceSentenceEnd();
            builder.endParagraph();
            count(title);
        }

        @Override
        public void text(char[] buf, int off, int len) {
            if (progress != null && progress.isCancelled()) throw new Cancelled();
            if (svgDepth > 0) return;
            if (inHeadTitle) {
                headTitle.append(buf, off, len);
                return;
            }
            if (inHead) return;
            if (inHeading) {
                heading.append(buf, off, len);
                return;
            }
            if (!started) {
                boolean content = false;
                for (int i = off; i < off + len && !content; i++) content = !BookBuilder.isSeparator(buf[i]);
                if (!content) return;
                builder.startChapter(fallbackTitle());
                started = true;
            }
            builder.text(buf, off, len);
            count(buf, off, len);
        }

        /** Название головы часто просто название книги на каждой главе: такое не годится в оглавление. */
        private String fallbackTitle() {
            String t = BookBuilder.cleanTitle(headTitle);
            if (t.length() == 0 || t.equalsIgnoreCase(bookTitle)) return "Глава " + (builder.chapterCount() + 1);
            return t;
        }

        private void count(String s) {
            count(s.toCharArray(), 0, s.length());
        }

        private void count(char[] buf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                char c = buf[i];
                if (c == '\uFFFD') stats.garbage++;
                else if (Character.isLetterOrDigit(c)) stats.letters++;
            }
        }
    }
}
