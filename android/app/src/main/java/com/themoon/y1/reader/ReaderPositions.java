package com.themoon.y1.reader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Положения в книгах и закладки: positions.json на карте.
 *
 * Книга узнаётся по хэшу содержимого (BookId), положение хранится как глава + слово в главе + якорь
 * из 8 слов. Если файл книги отредактировали и индекс сдвинулся, положение находится по якорю,
 * потом по главе, и только потом сбрасывается в начало.
 *
 * Запись атомарная с копией .bak. Пока карта недоступна, всё живёт в памяти и пишется при первой
 * возможности. Все методы синхронизированы: читалка пишет с таймера интерфейса, а библиотека
 * читает из фонового потока.
 */
public final class ReaderPositions {

    /** Раз в столько мс сбрасываем изменения на карту, пока человек читает. */
    public static final long FLUSH_PERIOD_MS = 5000;
    public static final int MAX_MARKS = 20;
    /** Длина якоря в словах: достаточно, чтобы почти не совпасть случайно, и коротко для файла. */
    public static final int ANCHOR_WORDS = 8;
    /** Радиус быстрого поиска якоря вокруг прежней позиции, дальше ищется по всей книге. */
    private static final int ANCHOR_RADIUS = 5000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Mark {
        public int word;
        public String anchor = "";
        public String chapterTitle = "";
        public int pct;
        public long ms;
    }

    public static final class Entry {
        public String id;
        public int chapter;
        public int wordInChapter;
        public int word;
        public int pct;
        public int wpm;
        public long updatedMs;
        public boolean read;
        public String anchor = "";
        public String title = "";
        public String author = "";
        public String lastPath = "";
        public boolean hasReturn;
        public int retWord;
        public String retAnchor = "";
        public final ArrayList<Mark> marks = new ArrayList<Mark>();

        /** Индекс, по которому посчитано слово: якорь берётся из него лениво, а не на каждом слове. */
        private BookIndex liveIndex;
    }

    public static final class Position {
        /** Слово на месте, текст не менялся. */
        public static final int EXACT = 0;
        public static final int BY_ANCHOR = 1;
        public static final int BY_CHAPTER = 2;
        public static final int LOST = 3;

        public final int word;
        public final int source;
        /** Для поиска по названию: ключ записи, из которой взято положение. */
        public final String fromId;

        Position(int word, int source, String fromId) {
            this.word = word;
            this.source = source;
            this.fromId = fromId;
        }
    }

    private final File file;
    private final LinkedHashMap<String, Entry> books = new LinkedHashMap<String, Entry>();
    private boolean dirty;
    private boolean everAttempted;
    private long lastAttemptMs;

    public ReaderPositions(File file) {
        this.file = file;
    }

    // ------------------------------------------------------------------ чтение с карты

    /**
     * Читает файл, при порче берёт .bak. Битый основной файл уходит в .corrupt: иначе первая же
     * запись превратила бы целую копию .bak в копию битого файла.
     */
    public synchronized void load() {
        books.clear();
        dirty = false;
        File bak = FileIo.sibling(file, ".bak");
        boolean mainExists = file.exists();
        if (mainExists && parseInto(file)) return;
        if (mainExists) {
            File corrupt = FileIo.sibling(file, ".corrupt");
            corrupt.delete();
            file.renameTo(corrupt);
        }
        if (bak.exists()) parseInto(bak);
    }

    private boolean parseInto(File f) {
        Map<String, Entry> loaded = new LinkedHashMap<String, Entry>();
        try {
            Object root = MiniJson.parse(new String(FileIo.readAll(f), UTF8));
            if (!(root instanceof Map)) return false;
            Object bs = ((Map<?, ?>) root).get("books");
            if (!(bs instanceof Map)) return false;
            for (Map.Entry<?, ?> me : ((Map<?, ?>) bs).entrySet()) {
                // Одна испорченная запись не должна уносить остальные книги.
                if (!(me.getValue() instanceof Map) || !(me.getKey() instanceof String)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) me.getValue();
                loaded.put((String) me.getKey(), entryFrom((String) me.getKey(), m));
            }
        } catch (IOException e) {
            return false;
        }
        books.putAll(loaded);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Entry entryFrom(String id, Map<String, Object> m) {
        Entry e = new Entry();
        e.id = id;
        e.word = MiniJson.getInt(m, "word", 0);
        e.chapter = MiniJson.getInt(m, "chapter", 0);
        e.wordInChapter = MiniJson.getInt(m, "wic", 0);
        e.pct = MiniJson.getInt(m, "pct", 0);
        e.wpm = MiniJson.getInt(m, "wpm", 0);
        e.updatedMs = MiniJson.getLong(m, "ms", 0);
        e.read = MiniJson.getBool(m, "read", false);
        e.anchor = MiniJson.getString(m, "anchor", "");
        e.title = MiniJson.getString(m, "title", "");
        e.author = MiniJson.getString(m, "author", "");
        e.lastPath = MiniJson.getString(m, "path", "");
        e.hasReturn = MiniJson.getBool(m, "ret", false);
        e.retWord = MiniJson.getInt(m, "retWord", 0);
        e.retAnchor = MiniJson.getString(m, "retAnchor", "");
        Object ms = m.get("marks");
        if (ms instanceof List) {
            for (Object o : (List<?>) ms) {
                if (!(o instanceof Map) || e.marks.size() >= MAX_MARKS) continue;
                Map<String, Object> mm = (Map<String, Object>) o;
                Mark k = new Mark();
                k.word = MiniJson.getInt(mm, "w", 0);
                k.anchor = MiniJson.getString(mm, "a", "");
                k.chapterTitle = MiniJson.getString(mm, "c", "");
                k.pct = MiniJson.getInt(mm, "p", 0);
                k.ms = MiniJson.getLong(mm, "ms", 0);
                e.marks.add(k);
            }
        }
        return e;
    }

    // ------------------------------------------------------------------ запись на карту

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Пишет сразу, если есть что писать. true, если файл записан. */
    public synchronized boolean flushNow() {
        if (!dirty) return false;
        return writeOut();
    }

    /**
     * Пишет, если есть изменения и прошло не меньше 5 с с прошлой попытки (удачной или нет): обрыв
     * питания теряет несколько секунд чтения, а неисправная карта не получает запись на каждом слове.
     */
    public synchronized boolean flushIfDue(long nowMs) {
        if (!dirty) return false;
        if (everAttempted && nowMs - lastAttemptMs < FLUSH_PERIOD_MS) return false;
        everAttempted = true;
        lastAttemptMs = nowMs;
        return writeOut();
    }

    public synchronized boolean save() {
        return flushNow();
    }

    private boolean writeOut() {
        Map<String, Object> bs = new LinkedHashMap<String, Object>();
        for (Entry e : books.values()) {
            refreshAnchor(e);
            bs.put(e.id, entryToMap(e));
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("v", Long.valueOf(1));
        root.put("books", bs);
        try {
            FileIo.writeAtomic(file, true, MiniJson.write(root).getBytes(UTF8));
        } catch (IOException e) {
            return false;
        }
        dirty = false;
        return true;
    }

    private static Map<String, Object> entryToMap(Entry e) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("word", Long.valueOf(e.word));
        m.put("chapter", Long.valueOf(e.chapter));
        m.put("wic", Long.valueOf(e.wordInChapter));
        m.put("pct", Long.valueOf(e.pct));
        m.put("wpm", Long.valueOf(e.wpm));
        m.put("ms", Long.valueOf(e.updatedMs));
        m.put("read", Boolean.valueOf(e.read));
        m.put("anchor", e.anchor);
        m.put("title", e.title);
        m.put("author", e.author);
        m.put("path", e.lastPath);
        m.put("ret", Boolean.valueOf(e.hasReturn));
        m.put("retWord", Long.valueOf(e.retWord));
        m.put("retAnchor", e.retAnchor);
        List<Object> ms = new ArrayList<Object>();
        for (Mark k : e.marks) {
            Map<String, Object> mm = new LinkedHashMap<String, Object>();
            mm.put("w", Long.valueOf(k.word));
            mm.put("a", k.anchor);
            mm.put("c", k.chapterTitle);
            mm.put("p", Long.valueOf(k.pct));
            mm.put("ms", Long.valueOf(k.ms));
            ms.add(mm);
        }
        m.put("marks", ms);
        return m;
    }

    // ------------------------------------------------------------------ запись положения

    public synchronized Entry get(String id) {
        Entry e = books.get(id);
        if (e != null) refreshAnchor(e);
        return e;
    }

    public synchronized Entry ensure(String id, String title, String author, String path) {
        Entry e = books.get(id);
        if (e == null) {
            e = new Entry();
            e.id = id;
            e.title = title == null ? "" : title;
            e.author = author == null ? "" : author;
            e.lastPath = path == null ? "" : path;
            books.put(id, e);
            dirty = true;
        }
        return e;
    }

    private Entry entryOrNew(String id) {
        Entry e = books.get(id);
        if (e == null) {
            e = new Entry();
            e.id = id;
            books.put(id, e);
            dirty = true;
        }
        return e;
    }

    /**
     * Запоминает положение на каждом слове, поэтому без выделения памяти: якорь (строка) строится
     * только при записи на карту и при чтении записи.
     */
    public synchronized void track(String id, BookIndex idx, int word, int wpm, long nowMs, String path) {
        Entry e = books.get(id);
        if (e == null) e = entryOrNew(id);
        boolean moved = e.word != word || e.liveIndex != idx || e.wpm != wpm;
        if (!moved) return;
        int n = idx.wordCount();
        int w = n == 0 ? 0 : Math.max(0, Math.min(word, n - 1));
        e.word = w;
        e.liveIndex = idx;
        int c = idx.chapterOf(w);
        e.chapter = c;
        e.wordInChapter = w - (c < idx.chapterFirstWord.length ? idx.chapterFirstWord[c] : 0);
        e.pct = idx.percentOf(w);
        e.wpm = wpm;
        e.updatedMs = nowMs;
        e.title = idx.title == null ? "" : idx.title;
        e.author = idx.author == null ? "" : idx.author;
        if (path != null) e.lastPath = path;
        // Отметка липкая: перемотка назад не отменяет «прочитано».
        if (idx.isEndReached(w)) e.read = true;
        dirty = true;
    }

    private static void refreshAnchor(Entry e) {
        if (e.liveIndex != null) {
            e.anchor = e.liveIndex.anchorAround(e.word, ANCHOR_WORDS);
            e.liveIndex = null;
        }
    }

    public synchronized void setRead(String id, boolean read) {
        Entry e = books.get(id);
        if (e == null) return;
        e.read = read;
        dirty = true;
    }

    /** «Читать сначала»: закладки остаются, это решение владельца, чтобы отметки не пропадали случайно. */
    public synchronized void restart(String id) {
        Entry e = books.get(id);
        if (e == null) return;
        e.word = 0;
        e.chapter = 0;
        e.wordInChapter = 0;
        e.pct = 0;
        e.read = false;
        e.anchor = "";
        e.liveIndex = null;
        e.hasReturn = false;
        e.retWord = 0;
        e.retAnchor = "";
        dirty = true;
    }

    // ------------------------------------------------------------------ поиск положения в индексе

    /** null, если такой книги не знаем. */
    public synchronized Position resolve(String id, BookIndex idx) {
        Entry e = books.get(id);
        if (e == null) return null;
        return resolveEntry(e, idx, null);
    }

    private Position resolveEntry(Entry e, BookIndex idx, String fromId) {
        refreshAnchor(e);
        int n = idx.wordCount();
        if (n == 0) return new Position(0, Position.LOST, fromId);
        int w = Math.max(0, Math.min(e.word, n - 1));
        if (e.anchor.length() == 0) return new Position(w, Position.EXACT, fromId);
        if (e.word < n && idx.anchorAround(e.word, ANCHOR_WORDS).equals(e.anchor)) {
            return new Position(e.word, Position.EXACT, fromId);
        }
        int found = idx.findAnchor(e.anchor, w, ANCHOR_RADIUS);
        if (found >= 0) return new Position(found, Position.BY_ANCHOR, fromId);
        int chapters = idx.chapterFirstWord.length;
        if (e.chapter < chapters) {
            int first = idx.chapterFirstWord[e.chapter];
            int last = e.chapter + 1 < chapters ? idx.chapterFirstWord[e.chapter + 1] - 1 : n - 1;
            int cw = Math.max(first, Math.min(first + e.wordInChapter, last));
            return new Position(Math.min(cw, n - 1), Position.BY_CHAPTER, fromId);
        }
        return new Position(0, Position.LOST, fromId);
    }

    /**
     * Книгу подменили файлом с другим содержимым (исправили опечатки): ищем запись с тем же названием
     * и автором, самую свежую.
     */
    public synchronized Position resolveByTitle(String title, String author, BookIndex idx) {
        String t = normalize(title);
        if (t.length() == 0) return null;
        String a = normalize(author);
        Entry best = null;
        for (Entry e : books.values()) {
            if (!t.equals(normalize(e.title))) continue;
            String ea = normalize(e.author);
            // Автор в одной из копий мог быть не заполнен: тогда совпадения по названию достаточно.
            if (a.length() > 0 && ea.length() > 0 && !a.equals(ea)) continue;
            if (best == null || e.updatedMs > best.updatedMs) best = e;
        }
        return best == null ? null : resolveEntry(best, idx, best.id);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || BookBuilder.isSeparator(c)) {
                space = sb.length() > 0;
            } else {
                if (space) sb.append(' ');
                space = false;
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Известный id или, если файл заменён, запись по названию, переписанная на новый id. */
    public synchronized Position resolveOrAdopt(String newId, BookIndex idx) {
        Position p = resolve(newId, idx);
        if (p != null) return p;
        p = resolveByTitle(idx.title, idx.author, idx);
        if (p == null) return null;
        adopt(newId, p.fromId);
        return new Position(p.word, p.source, p.fromId);
    }

    /** Копия записи (вместе с закладками) под новым ключом; старая остаётся. */
    public synchronized void adopt(String newId, String fromId) {
        Entry from = books.get(fromId);
        if (from == null || books.containsKey(newId)) return;
        refreshAnchor(from);
        Entry e = entryFrom(newId, entryToMap(from));
        books.put(newId, e);
        dirty = true;
    }

    // ------------------------------------------------------------------ точка возврата

    public synchronized void setReturnPoint(String id, BookIndex idx, int word) {
        Entry e = entryOrNew(id);
        e.hasReturn = true;
        e.retWord = word;
        e.retAnchor = idx.anchorAround(word, ANCHOR_WORDS);
        dirty = true;
    }

    public synchronized void clearReturnPoint(String id) {
        Entry e = books.get(id);
        if (e == null || !e.hasReturn) return;
        e.hasReturn = false;
        e.retAnchor = "";
        e.retWord = 0;
        dirty = true;
    }

    public synchronized Position resolveReturnPoint(String id, BookIndex idx) {
        Entry e = books.get(id);
        if (e == null || !e.hasReturn) return null;
        int w = locate(idx, e.retWord, e.retAnchor);
        return new Position(w, Position.EXACT, null);
    }

    private static int locate(BookIndex idx, int word, String anchor) {
        int n = idx.wordCount();
        if (n == 0) return 0;
        int w = Math.max(0, Math.min(word, n - 1));
        if (anchor == null || anchor.length() == 0) return w;
        if (idx.anchorAround(w, ANCHOR_WORDS).equals(anchor)) return w;
        int found = idx.findAnchor(anchor, w, ANCHOR_RADIUS);
        return found >= 0 ? found : w;
    }

    // ------------------------------------------------------------------ закладки

    /** @return false, если в книге нет слов. */
    public synchronized boolean addMark(String id, BookIndex idx, int word, long nowMs) {
        int n = idx.wordCount();
        if (n == 0) return false;
        int w = Math.max(0, Math.min(word, n - 1));
        Entry e = entryOrNew(id);
        Mark m = null;
        for (Mark k : e.marks) {
            if (k.word == w) m = k;
        }
        if (m == null) {
            if (e.marks.size() >= MAX_MARKS) {
                int oldest = 0;
                for (int i = 1; i < e.marks.size(); i++) {
                    if (e.marks.get(i).ms < e.marks.get(oldest).ms) oldest = i;
                }
                e.marks.remove(oldest);
            }
            m = new Mark();
            e.marks.add(m);
        }
        m.word = w;
        m.anchor = idx.anchorAround(w, ANCHOR_WORDS);
        int c = idx.chapterOf(w);
        m.chapterTitle = c < idx.chapterTitles.length ? idx.chapterTitles[c] : "";
        m.pct = idx.percentOf(w);
        m.ms = nowMs;
        dirty = true;
        return true;
    }

    public synchronized void removeMark(String id, int index) {
        Entry e = books.get(id);
        if (e == null || index < 0 || index >= e.marks.size()) return;
        e.marks.remove(index);
        dirty = true;
    }

    /** Слово закладки в текущем индексе: по якорю, а если текст переписан, по доле книги. */
    public synchronized int resolveMark(Mark m, BookIndex idx) {
        int n = idx.wordCount();
        if (n == 0) return 0;
        int w = Math.max(0, Math.min(m.word, n - 1));
        if (m.anchor.length() > 0) {
            if (idx.anchorAround(w, ANCHOR_WORDS).equals(m.anchor)) return w;
            int found = idx.findAnchor(m.anchor, w, ANCHOR_RADIUS);
            if (found >= 0) return found;
        }
        return (int) ((long) m.pct * (n - 1) / 100);
    }
}
