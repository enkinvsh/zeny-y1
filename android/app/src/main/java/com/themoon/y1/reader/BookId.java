package com.themoon.y1.reader;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Идентичность книги по содержимому: SHA-1 от размера, первых и последних 64 КБ.
 *
 * Имя и папка в id не входят, поэтому переименование и повторная заливка того же файла место в книге
 * не сбрасывают. Середина большого файла не читается сознательно: на медленной карте Y1 id должен
 * считаться за миллисекунды при каждом сканировании библиотеки. Файл до 128 КБ хэшируется целиком.
 */
public final class BookId {

    private static final int BLOCK = 64 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BookId() {
    }

    public static String of(File file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 обязателен в любой Java-платформе: сюда не попасть.
            throw new IOException(e.getMessage());
        }
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            long size = raf.length();
            for (int shift = 56; shift >= 0; shift -= 8) md.update((byte) (size >>> shift));
            byte[] buf = new byte[BLOCK];
            if (size <= 2L * BLOCK) {
                feed(raf, md, buf, 0, (int) size);
            } else {
                feed(raf, md, buf, 0, BLOCK);
                feed(raf, md, buf, size - BLOCK, BLOCK);
            }
        } finally {
            raf.close();
        }
        byte[] d = md.digest();
        char[] out = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            out[i * 2] = HEX[(d[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[d[i] & 0xF];
        }
        return new String(out);
    }

    private static void feed(RandomAccessFile raf, MessageDigest md, byte[] buf, long from, int len) throws IOException {
        raf.seek(from);
        int done = 0;
        while (done < len) {
            int n = raf.read(buf, 0, Math.min(buf.length, len - done));
            if (n <= 0) throw new IOException("Файл изменился во время чтения");
            md.update(buf, 0, n);
            done += n;
        }
    }
}
