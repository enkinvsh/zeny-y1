package com.themoon.y1.managers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Полное удаление трека с карты: сам файл, текст рядом, кэш обложки и строки в плейлистах.
 *
 * Работает строго внутри папки Music: удаление необратимо и идёт по пути, пришедшему из
 * библиотеки, поэтому всё, что лежит снаружи (книги, подкасты, чужие папки), отклоняется
 * до первого удаления. Пути сравниваются как обычные абсолютные строки, без canonicalize:
 * на карте нет симлинков, а в тестах временная папка macOS (/var, а на деле /private/var)
 * иначе не совпала бы сама с собой.
 */
public final class TrackEraser {

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final int playlistsChanged;
        public final int filesDeleted;
        public final int dirsRemoved;

        Result(boolean ok, String error, int playlistsChanged, int filesDeleted, int dirsRemoved) {
            this.ok = ok;
            this.error = error;
            this.playlistsChanged = playlistsChanged;
            this.filesDeleted = filesDeleted;
            this.dirsRemoved = dirsRemoved;
        }

        static Result fail(String why) {
            return new Result(false, why, 0, 0, 0);
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** M3U читаем как ISO-8859-1: каждый байт ровно один символ, поэтому остальные строки возвращаются в файл байт в байт. */
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private TrackEraser() {
    }

    /** Путь внутри musicRoot (строго глубже самой папки) без отрезков «..». */
    static boolean isInside(File file, File musicRoot) {
        if (file == null || musicRoot == null) return false;
        String root = stripSlash(musicRoot.getAbsolutePath());
        String p = file.getAbsolutePath();
        if (!p.startsWith(root + "/")) return false;
        String rel = p.substring(root.length() + 1);
        if (rel.isEmpty()) return false;
        for (String part : rel.split("/")) {
            if (part.equals("..") || part.equals(".")) return false;
        }
        return true;
    }

    private static String stripSlash(String s) {
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Те же условия, что и у erase, но без удаления: вызывающий может проверить заранее. */
    public static boolean canErase(File track, File musicRoot) {
        return isInside(track, musicRoot) && track.isFile();
    }

    public static Result erase(File track, File musicRoot, File playlistsDir, File coversDir) {
        if (track == null || musicRoot == null) return Result.fail("no args");
        if (!isInside(track, musicRoot)) return Result.fail("outside music root");
        if (!track.isFile()) return Result.fail("not a regular file");

        String absPath = track.getAbsolutePath();
        String root = stripSlash(musicRoot.getAbsolutePath());
        File cardRoot = musicRoot.getAbsoluteFile().getParentFile();
        String relToCard = null;
        if (cardRoot != null) {
            String cr = stripSlash(cardRoot.getAbsolutePath());
            if (absPath.startsWith(cr + "/")) relToCard = absPath.substring(cr.length() + 1);
        }

        if (!track.delete() && track.exists()) return Result.fail("delete failed");
        int files = 1;

        String name = track.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File parent = track.getParentFile();

        File lrc = new File(parent, base + ".lrc");
        if (lrc.isFile() && lrc.delete()) files++;
        if (coversDir != null) {
            File cover = new File(coversDir, base + ".jpg");
            if (cover.isFile() && cover.delete()) files++;
        }

        int changed = 0;
        if (playlistsDir != null && playlistsDir.isDirectory()) {
            File[] lists = playlistsDir.listFiles();
            if (lists != null) {
                for (File pl : lists) {
                    String n = pl.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!pl.isFile() || !(n.endsWith(".m3u") || n.endsWith(".m3u8"))) continue;
                    try {
                        if (dropFromPlaylist(pl, absPath, relToCard)) changed++;
                    } catch (IOException ignored) {
                        // Плейлист не переписался: трек уже стёрт, остальные плейлисты всё равно чистим.
                    }
                }
            }
        }

        int dirs = 0;
        File dir = parent;
        while (dir != null && isInside(dir, musicRoot)) {
            String[] left = dir.list();
            if (left == null || left.length != 0 || !dir.delete()) break;
            dirs++;
            dir = dir.getParentFile();
        }
        return new Result(true, null, changed, files, dirs);
    }

    /** Убирает строку с путём и #EXTINF прямо над ней. Файл без этого трека не трогается. */
    static boolean dropFromPlaylist(File pl, String absPath, String relPath) throws IOException {
        String text = new String(readAll(pl), LATIN1);
        String abs = new String(absPath.getBytes(UTF8), LATIN1);
        String rel = relPath == null ? null : new String(relPath.getBytes(UTF8), LATIN1);

        List<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) lines.add(text.substring(start));

        boolean[] drop = new boolean[lines.size()];
        boolean any = false;
        for (int i = 0; i < lines.size(); i++) {
            String body = lines.get(i);
            int end = body.length();
            while (end > 0 && (body.charAt(end - 1) == '\n' || body.charAt(end - 1) == '\r')) end--;
            body = body.substring(0, end);
            if (body.equals(abs) || (rel != null && (body.equals(rel) || body.equals("/" + rel)))) {
                drop[i] = true;
                any = true;
                if (i > 0 && lines.get(i - 1).startsWith("#EXTINF")) drop[i - 1] = true;
            }
        }
        if (!any) return false;

        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < lines.size(); i++) {
            if (!drop[i]) out.write(lines.get(i).getBytes(LATIN1));
        }
        writeAtomic(pl, out.toByteArray());
        return true;
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

    /** Временный файл рядом + sync + rename: обрыв питания не оставит обрезанный плейлист. */
    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(data);
            fos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("rename failed " + tmp);
            }
        }
    }
}
