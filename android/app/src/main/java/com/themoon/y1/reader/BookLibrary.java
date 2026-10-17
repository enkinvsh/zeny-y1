package com.themoon.y1.reader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Список книг в папке Books: поиск файлов, названия и авторы без полного разбора, состояние из
 * positions.json, порядок, обложки.
 */
public final class BookLibrary {

    public static final int ORDER_RECENT = 0;
    public static final int ORDER_TITLE = 1;

    /** Обложка крупнее не берётся: распаковка в память на 512 МБ не должна стать причиной вылета. */
    public static final int MAX_COVER_BYTES = 6 * 1024 * 1024;
    /** Глубже папок книг не ищем: на карте могут лежать целые архивы загрузок. */
    private static final int MAX_DEPTH = 2;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Название и автор книги без полного разбора. Подменяется в тестах, чтобы считать открытия файлов. */
    public interface MetaSource {
        /**
         * @return {название, автор} (строки могут быть пустыми) или null, если файл не книга
         *         (архив без .fb2)
         * @throws IOException файл не читается: в списке он остаётся с именем файла
         */
        String[] read(File file) throws IOException;
    }

    private final MetaSource meta;

    public BookLibrary() {
        this(new FileMetaSource());
    }

    public BookLibrary(MetaSource meta) {
        this.meta = meta;
    }

    // ------------------------------------------------------------------ поиск файлов

    /**
     * Список книг в порядке «последняя читанная первой». Неизменённые файлы (путь, размер, время) не
     * открываются: названия и ключи берутся из Books/.cache/library.json.
     */
    public List<LibraryEntry> scan(File booksDir, ReaderPositions positions, Progress progress) {
        ArrayList<File> files = new ArrayList<File>();
        collect(booksDir, 0, files);
        File cacheFile = new File(new File(booksDir, ".cache"), "library.json");
        Map<String, Map<String, Object>> cache = loadCache(cacheFile);
        Map<String, Object> fresh = new LinkedHashMap<String, Object>();
        ArrayList<LibraryEntry> result = new ArrayList<LibraryEntry>();
        boolean changed = false;
        boolean cancelled = false;
        int n = files.size();
        for (int i = 0; i < n; i++) {
            if (progress != null) {
                if (progress.isCancelled()) {
                    cancelled = true;
                    break;
                }
                progress.onProgress(i * 100 / n);
            }
            File f = files.get(i);
            Map<String, Object> c = cache.get(f.getPath());
            if (c == null || MiniJson.getLong(c, "size", -1) != f.length()
                    || MiniJson.getLong(c, "mtime", -1) != f.lastModified()) {
                c = describe(f);
                changed = true;
            }
            fresh.put(f.getPath(), c);
            if (MiniJson.getBool(c, "skip", false)) continue;
            String title = MiniJson.getString(c, "title", "");
            LibraryEntry e = new LibraryEntry(f, title.length() > 0 ? title : baseName(f),
                    MiniJson.getString(c, "author", ""), MiniJson.getString(c, "id", null));
            if (e.id() == null) continue;
            c.put("id", e.id());
            applyState(e, positions);
            result.add(e);
        }
        if (!cancelled && (changed || fresh.size() != cache.size())) saveCache(cacheFile, fresh);
        sort(result, ORDER_RECENT);
        if (progress != null && !cancelled) progress.onProgress(100);
        return result;
    }

    private static void collect(File dir, int depth, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        Arrays.sort(list);
        for (File f : list) {
            String name = f.getName();
            // Точечные папки служебные (.cache), а чужие скрытые файлы не книги.
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH) collect(f, depth + 1, out);
            } else if (isBookName(name)) {
                out.add(f);
            }
        }
    }

    static boolean isBookName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".epub") || n.endsWith(".fb2") || n.endsWith(".zip");
    }

    private Map<String, Object> describe(File f) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("size", Long.valueOf(f.length()));
        c.put("mtime", Long.valueOf(f.lastModified()));
        String[] m;
        try {
            m = meta.read(f);
        } catch (IOException e) {
            // Метаданные не прочитались: книга остаётся в списке под именем файла, причину человек увидит при открытии.
            m = new String[]{"", ""};
        }
        if (m == null) {
            c.put("skip", Boolean.TRUE);
            return c;
        }
        c.put("title", m[0] == null ? "" : m[0]);
        c.put("author", m[1] == null ? "" : m[1]);
        return c;
    }

    /** Имя файла без расширения; у «книга.fb2.zip» отбрасываются оба. */
    static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        if (n.toLowerCase(Locale.ROOT).endsWith(".fb2") && n.length() > 4) n = n.substring(0, n.length() - 4);
        return n;
    }

    // ------------------------------------------------------------------ кэш библиотеки

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> loadCache(File f) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        if (!f.isFile()) return out;
        try {
            Object root = MiniJson.parse(new String(FileIo.readAll(f), UTF8));
            if (!(root instanceof Map)) return out;
            Object files = ((Map<?, ?>) root).get("files");
            if (!(files instanceof Map)) return out;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) files).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() instanceof Map) {
                    out.put((String) e.getKey(), (Map<String, Object>) e.getValue());
                }
            }
        } catch (IOException e) {
            out.clear();
        }
        return out;
    }

    private static void saveCache(File f, Map<String, Object> files) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("v", Long.valueOf(1));
        root.put("files", files);
        try {
            FileIo.writeAtomic(f, false, MiniJson.write(root).getBytes(UTF8));
        } catch (IOException ignored) {
            // Кэш нужен только для скорости; на недоступной карте список просто соберётся заново.
        }
    }

    // ------------------------------------------------------------------ состояние и порядок

    public static void applyState(LibraryEntry e, ReaderPositions positions) {
        e.state = LibraryEntry.NEW;
        e.pct = 0;
        e.lastReadMs = 0;
        String id = e.id();
        ReaderPositions.Entry p = positions == null || id == null ? null : positions.get(id);
        if (p == null) return;
        if (p.read) {
            e.state = LibraryEntry.READ;
            e.pct = 100;
            e.lastReadMs = p.updatedMs;
        } else if (p.word > 0) {
            e.state = LibraryEntry.STARTED;
            e.pct = p.pct;
            e.lastReadMs = p.updatedMs;
        }
    }

    /**
     * ORDER_RECENT: начатые, последняя читанная первой; затем новые по названию; прочитанные внизу.
     * ORDER_TITLE: все по названию.
     */
    public static void sort(List<LibraryEntry> list, final int order) {
        Collections.sort(list, new Comparator<LibraryEntry>() {
            @Override
            public int compare(LibraryEntry a, LibraryEntry b) {
                if (order == ORDER_RECENT) {
                    if (a.state != b.state) return a.state < b.state ? -1 : 1;
                    if (a.state != LibraryEntry.NEW && a.lastReadMs != b.lastReadMs) {
                        return a.lastReadMs > b.lastReadMs ? -1 : 1;
                    }
                }
                int c = String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title);
                return c != 0 ? c : a.path.compareTo(b.path);
            }
        });
    }

    public static void markRead(ReaderPositions positions, LibraryEntry e) {
        String id = e.id();
        if (id == null) return;
        positions.ensure(id, e.title, e.author, e.path);
        positions.setRead(id, true);
        applyState(e, positions);
    }

    public static void markUnread(ReaderPositions positions, LibraryEntry e) {
        String id = e.id();
        if (id == null) return;
        positions.ensure(id, e.title, e.author, e.path);
        positions.setRead(id, false);
        applyState(e, positions);
    }

    /** «Читать сначала»: позиция в начало, закладки остаются. */
    public static void restart(ReaderPositions positions, LibraryEntry e) {
        String id = e.id();
        if (id == null) return;
        positions.ensure(id, e.title, e.author, e.path);
        positions.restart(id);
        applyState(e, positions);
    }

    // ------------------------------------------------------------------ обложки

    /**
     * Байты картинки обложки (как лежит в книге) или null. Декодируется только выбранная книга, а
     * уменьшает и кэширует картинку вызывающий.
     */
    public byte[] coverFor(LibraryEntry e) {
        try {
            String n = e.file.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".epub")) return epubCover(e.file);
            return fb2Cover(e.file);
        } catch (IOException ex) {
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static byte[] epubCover(File f) throws IOException {
        ZipFile zip = new ZipFile(f);
        try {
            EpubReader.Pkg pkg = EpubReader.readPackage(zip);
            if (pkg.coverPath == null) return null;
            ZipEntry ze = zip.getEntry(pkg.coverPath);
            if (ze == null || ze.isDirectory() || ze.getSize() > MAX_COVER_BYTES) return null;
            InputStream in = zip.getInputStream(ze);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    // Размер в заголовке архива можно подделать или не знать: считаем реально прочитанное.
                    if (out.size() > MAX_COVER_BYTES) return null;
                }
                return out.size() == 0 ? null : out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    private static byte[] fb2Cover(File f) throws IOException {
        Fb2Reader.Source src = Fb2Reader.Source.open(f);
        try {
            CoverScan scan = new CoverScan();
            MarkupScanner.scan(TextDecoding.open(src.in), scan, false);
            return scan.result();
        } finally {
            src.close();
        }
    }

    /** Ищет в FB2 ссылку на обложку в coverpage и декодирует base64 одного нужного binary. */
    private static final class CoverScan implements MarkupScanner.Handler {
        private boolean inCover;
        private String coverId;
        private boolean capturing;
        private boolean overflow;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int acc;
        private int bits;

        byte[] result() {
            if (overflow || out.size() == 0) return null;
            // Хвост без «=» тоже допустим: дописываем недобранные байты.
            if (bits == 2) out.write(acc >> 4);
            else if (bits == 3) {
                out.write(acc >> 10);
                out.write(acc >> 2);
            }
            return out.toByteArray();
        }

        @Override
        public void start(String name, String[] attrs, boolean selfClosing) {
            if (name.equals("coverpage")) {
                inCover = true;
            } else if (inCover && coverId == null && name.equals("image")) {
                String href = attr(attrs, "href");
                if (href != null) coverId = href.startsWith("#") ? href.substring(1) : href;
            } else if (name.equals("binary") && coverId != null && coverId.equals(attr(attrs, "id"))) {
                capturing = true;
            }
        }

        @Override
        public void end(String name) {
            if (name.equals("coverpage")) {
                inCover = false;
            } else if (name.equals("binary") && capturing) {
                throw new MarkupScanner.StopScan();
            }
        }

        @Override
        public void text(char[] buf, int off, int len) {
            if (!capturing) return;
            for (int i = off; i < off + len; i++) {
                int v = sextet(buf[i]);
                if (v < 0) continue;
                acc = (acc << 6) | v;
                if (++bits == 4) {
                    out.write(acc >> 16);
                    out.write(acc >> 8);
                    out.write(acc);
                    acc = 0;
                    bits = 0;
                    if (out.size() > MAX_COVER_BYTES) {
                        overflow = true;
                        throw new MarkupScanner.StopScan();
                    }
                }
            }
        }

        private static int sextet(char c) {
            if (c >= 'A' && c <= 'Z') return c - 'A';
            if (c >= 'a' && c <= 'z') return c - 'a' + 26;
            if (c >= '0' && c <= '9') return c - '0' + 52;
            if (c == '+' || c == '-') return 62;
            if (c == '/' || c == '_') return 63;
            return -1;
        }

        private static String attr(String[] attrs, String name) {
            for (int i = 0; i + 1 < attrs.length; i += 2) {
                if (attrs[i].equals(name)) return attrs[i + 1];
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ чтение метаданных из файлов

    private static final class FileMetaSource implements MetaSource {
        @Override
        public String[] read(File f) throws IOException {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".epub")) {
                ZipFile zip = new ZipFile(f);
                try {
                    EpubReader.Pkg pkg = EpubReader.readPackage(zip);
                    return new String[]{pkg.title, pkg.author};
                } finally {
                    zip.close();
                }
            }
            if (n.endsWith(".zip") && !hasFb2(f)) return null;
            Fb2Reader.Source src = Fb2Reader.Source.open(f);
            try {
                return Fb2Reader.readMeta(src.in);
            } finally {
                src.close();
            }
        }

        private static boolean hasFb2(File f) {
            ZipFile zip;
            try {
                zip = new ZipFile(f);
            } catch (IOException e) {
                return false;
            }
            try {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".fb2")) return true;
                }
                return false;
            } finally {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // архив только просмотрен
                }
            }
        }
    }
}
