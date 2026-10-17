package com.themoon.y1.reader;

import java.io.File;
import java.io.IOException;

/** Читалка одного формата книги: файл на входе, готовый индекс на выходе. */
public interface BookReader {
    /**
     * @param progress может быть null
     * @throws IOException файл не открылся или в нём нет текста (сообщение годится для показа читателю);
     *                     при отмене {@link java.io.InterruptedIOException}
     */
    BookIndex read(File file, Progress progress) throws IOException;
}
