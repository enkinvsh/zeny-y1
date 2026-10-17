package com.themoon.y1.reader;

import java.io.File;
import java.io.IOException;

/** Книга в списке библиотеки: что показать в строке и чем она отличается от соседей. */
public final class LibraryEntry {

    public static final int STARTED = 0;
    public static final int NEW = 1;
    public static final int READ = 2;

    public final File file;
    public final String path;
    public final long size;
    public final long mtime;
    public final String title;
    public final String author;
    public int pct;
    public int state = NEW;
    public long lastReadMs;
    private String id;

    LibraryEntry(File file, String title, String author, String id) {
        this.file = file;
        this.path = file.getPath();
        this.size = file.length();
        this.mtime = file.lastModified();
        this.title = title;
        this.author = author;
        this.id = id;
    }

    /**
     * Ключ книги по содержимому (BookId). Считается один раз: хэш читает 128 КБ с карты, а список
     * перерисовывается при каждом повороте колеса. null, если файл не читается.
     */
    public synchronized String id() {
        if (id == null) {
            try {
                id = BookId.of(file);
            } catch (IOException e) {
                return null;
            }
        }
        return id;
    }
}
