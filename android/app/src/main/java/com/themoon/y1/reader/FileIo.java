package com.themoon.y1.reader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Атомарная запись и чтение файлов читалки (индекс, закладки, кэш библиотеки).
 *
 * Запись идёт во временный файл рядом, с принудительной выгрузкой на носитель (sync) и переименованием:
 * на FAT-карте Y1 обрыв питания посреди обычной записи оставляет обрезанный файл, а закладки терять нельзя.
 */
final class FileIo {

    interface Sink {
        void write(OutputStream out) throws IOException;
    }

    private FileIo() {
    }

    static File sibling(File f, String suffix) {
        return new File(f.getParentFile(), f.getName() + suffix);
    }

    static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(f.length(), 16), 1 << 24));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static void writeAtomic(File target, boolean keepBackup, byte[] data) throws IOException {
        writeAtomic(target, keepBackup, new BytesSink(data));
    }

    /**
     * @param keepBackup прежний файл остаётся рядом как «имя.bak»: если новый окажется битым,
     *                   читающий берёт копию
     */
    static void writeAtomic(File target, boolean keepBackup, Sink sink) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Нет папки " + dir);
        }
        File tmp = sibling(target, ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(fos, 16384);
            sink.write(out);
            out.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (keepBackup && target.exists()) {
            File bak = sibling(target, ".bak");
            bak.delete();
            if (!target.renameTo(bak)) throw new IOException("Не удалось сохранить копию " + bak);
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) throw new IOException("Не удалось переименовать " + tmp);
        }
    }

    private static final class BytesSink implements Sink {
        private final byte[] data;

        BytesSink(byte[] data) {
            this.data = data;
        }

        @Override
        public void write(OutputStream out) throws IOException {
            out.write(data);
        }
    }
}
