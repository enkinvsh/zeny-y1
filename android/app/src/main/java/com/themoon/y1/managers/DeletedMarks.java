package com.themoon.y1.managers;

import com.themoon.y1.reader.MiniJson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Метки «этот трек удалён с плеера»: Y1_Deleted/deleted.json на карте. Файл читает программа
 * на компьютере, чтобы не закачивать удалённый трек снова, поэтому формат и относительные пути
 * (от корня карты, с прямыми слэшами) нельзя менять без согласования с ней.
 *
 * Чужой или битый файл не затираем: если разобрать не удалось, перед первой записью он копируется
 * в deleted.json.bak. Неизвестные ключи верхнего уровня и неизвестные поля записей сохраняются
 * при перезаписи, чтобы более новая версия файла не теряла данные после старой версии плеера.
 */
public final class DeletedMarks {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Item {
        public final String path;
        public final String artist;
        public final String title;
        public final long at;
        /** Неизвестные поля записи, их возвращаем в файл как есть. */
        final Map<String, Object> extra = new LinkedHashMap<String, Object>();

        Item(String path, String artist, String title, long at) {
            this.path = path;
            this.artist = artist;
            this.title = title;
            this.at = at;
        }
    }

    private final LinkedHashMap<String, Item> items = new LinkedHashMap<String, Item>();
    /** Неизвестные ключи верхнего уровня (кроме v и items). */
    private final Map<String, Object> extraTop = new LinkedHashMap<String, Object>();
    private boolean corrupt;

    /** Разбор текста файла. Мусор, пустая строка и null дают пустой список с флагом corrupt. */
    public static DeletedMarks parse(String json) {
        DeletedMarks m = new DeletedMarks();
        if (json == null || json.trim().isEmpty()) {
            m.corrupt = json != null && json.length() > 0;
            return m;
        }
        try {
            Object root = MiniJson.parse(json);
            if (!(root instanceof Map)) {
                m.corrupt = true;
                return m;
            }
            for (Map.Entry<?, ?> e : ((Map<?, ?>) root).entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.equals("items")) {
                    if (!(e.getValue() instanceof List)) {
                        m.corrupt = true;
                        continue;
                    }
                    for (Object o : (List<?>) e.getValue()) m.readItem(o);
                } else if (!key.equals("v")) {
                    m.extraTop.put(key, e.getValue());
                }
            }
        } catch (IOException | RuntimeException ex) {
            DeletedMarks bad = new DeletedMarks();
            bad.corrupt = true;
            return bad;
        }
        return m;
    }

    private void readItem(Object o) {
        if (!(o instanceof Map)) return;
        Map<?, ?> src = (Map<?, ?>) o;
        Object p = src.get("path");
        if (!(p instanceof String) || ((String) p).isEmpty()) return;
        Object a = src.get("artist");
        Object t = src.get("title");
        Object at = src.get("at");
        Item it = new Item((String) p, a instanceof String ? (String) a : "",
                t instanceof String ? (String) t : "",
                at instanceof Number ? ((Number) at).longValue() : 0L);
        for (Map.Entry<?, ?> e : src.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!k.equals("path") && !k.equals("artist") && !k.equals("title") && !k.equals("at")) {
                it.extra.put(k, e.getValue());
            }
        }
        items.remove(it.path);
        items.put(it.path, it);
    }

    /** Читает файл; нет файла или ошибка чтения дают пустые метки (при ошибке чтения с флагом corrupt). */
    public static DeletedMarks load(File f) {
        if (f == null || !f.exists()) return new DeletedMarks();
        try {
            return parse(new String(readAll(f), UTF8));
        } catch (IOException e) {
            DeletedMarks m = new DeletedMarks();
            m.corrupt = true;
            return m;
        }
    }

    /** Повторная метка того же пути заменяет прежнюю и уходит в конец (последняя побеждает). */
    public void add(String path, String artist, String title, long atMs) {
        if (path == null || path.isEmpty()) return;
        Item old = items.remove(path);
        Item it = new Item(path, artist == null ? "" : artist, title == null ? "" : title, atMs);
        if (old != null) it.extra.putAll(old.extra);
        items.put(path, it);
    }

    public boolean remove(String path) {
        return items.remove(path) != null;
    }

    public boolean contains(String path) {
        return items.containsKey(path);
    }

    public int size() {
        return items.size();
    }

    /** Порядок добавления. */
    public List<Item> items() {
        return new ArrayList<Item>(items.values());
    }

    /** Исходный файл не удалось разобрать: перед записью его надо сохранить в .bak. */
    public boolean isCorrupt() {
        return corrupt;
    }

    public String toJson() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("v", Long.valueOf(1));
        List<Object> list = new ArrayList<Object>();
        for (Item it : items.values()) {
            LinkedHashMap<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("path", it.path);
            o.put("artist", it.artist);
            o.put("title", it.title);
            o.put("at", Long.valueOf(it.at));
            o.putAll(it.extra);
            list.add(o);
        }
        root.put("items", list);
        root.putAll(extraTop);
        return MiniJson.write(root);
    }

    /**
     * Атомарная запись: временный файл рядом, sync, переименование. Обрыв питания на FAT-карте
     * не оставит обрезанный файл, который плеер потом принял бы за пустой список.
     */
    public void save(File target) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Нет папки " + dir);
        }
        if (corrupt && target.exists()) {
            File bak = new File(dir, target.getName() + ".bak");
            copy(target, bak);
            corrupt = false;
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(toJson().getBytes(UTF8));
            fos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("Не удалось переименовать " + tmp);
            }
        }
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void copy(File from, File to) throws IOException {
        FileOutputStream out = new FileOutputStream(to);
        try {
            out.write(readAll(from));
            out.getFD().sync();
        } finally {
            out.close();
        }
    }
}
