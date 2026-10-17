package com.themoon.y1.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

import org.junit.Test;

public final class BookIdTest {

    private static File dir() throws IOException {
        return Files.createTempDirectory("bookid").toFile();
    }

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static File file(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), data);
        return f;
    }

    @Test
    public void idIsFortyHexCharacters() throws IOException {
        String id = BookId.of(file(dir(), "a.fb2", bytes(1000, 1)));
        assertEquals(40, id.length());
        assertTrue(id.matches("[0-9a-f]{40}"));
    }

    @Test
    public void renamingDoesNotChangeId() throws IOException {
        File d = dir();
        File a = file(d, "Тихая гавань.fb2", bytes(300000, 2));
        String before = BookId.of(a);
        File b = new File(d, "harbour_v2.fb2");
        assertTrue(a.renameTo(b));
        assertEquals(before, BookId.of(b));
    }

    @Test
    public void reuploadOfSameBytesGivesSameId() throws IOException {
        byte[] data = bytes(500000, 3);
        assertEquals(BookId.of(file(dir(), "a.epub", data)), BookId.of(file(dir(), "copy.epub", data)));
    }

    @Test
    public void differentContentGivesDifferentId() throws IOException {
        File d = dir();
        assertNotEquals(BookId.of(file(d, "a", bytes(5000, 4))), BookId.of(file(d, "b", bytes(5000, 5))));
    }

    @Test
    public void sizeIsPartOfId() throws IOException {
        File d = dir();
        byte[] a = bytes(400000, 6);
        byte[] b = new byte[a.length + 1];
        System.arraycopy(a, 0, b, 0, a.length);
        b[a.length] = a[a.length - 1];
        assertNotEquals(BookId.of(file(d, "a", a)), BookId.of(file(d, "b", b)));
    }

    @Test
    public void smallFileIsHashedWhole() throws IOException {
        File d = dir();
        byte[] a = bytes(100000, 7);
        byte[] b = a.clone();
        b[50000] ^= 1;
        assertNotEquals(BookId.of(file(d, "a", a)), BookId.of(file(d, "b", b)));
    }

    @Test
    public void editsInTheFirstOrLastBlockChangeIdOfBigFile() throws IOException {
        File d = dir();
        byte[] a = bytes(1000000, 8);
        String id = BookId.of(file(d, "a", a));
        byte[] head = a.clone();
        head[100] ^= 1;
        byte[] tail = a.clone();
        tail[a.length - 100] ^= 1;
        assertNotEquals(id, BookId.of(file(d, "h", head)));
        assertNotEquals(id, BookId.of(file(d, "t", tail)));
    }

    @Test
    public void middleOfBigFileIsNotReadOnPurpose() throws IOException {
        // Сознательный компромисс: id считается за миллисекунды даже на медленной карте.
        File d = dir();
        byte[] a = bytes(1000000, 9);
        byte[] b = a.clone();
        b[500000] ^= 1;
        assertEquals(BookId.of(file(d, "a", a)), BookId.of(file(d, "b", b)));
    }

    @Test
    public void emptyFileHasId() throws IOException {
        assertFalse(BookId.of(file(dir(), "e", new byte[0])).isEmpty());
    }

    @Test
    public void exactlyBlockBoundarySizesWork() throws IOException {
        File d = dir();
        for (int n : new int[]{65535, 65536, 65537, 131071, 131072, 131073}) {
            assertEquals(40, BookId.of(file(d, "f" + n, bytes(n, n))).length());
        }
    }
}
