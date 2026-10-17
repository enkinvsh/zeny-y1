package com.themoon.y1.reader;

/** Ход долгой операции чтения книги: проценты наружу, отмена внутрь. Вызывается из фонового потока. */
public interface Progress {
    void onProgress(int percent);

    boolean isCancelled();
}
