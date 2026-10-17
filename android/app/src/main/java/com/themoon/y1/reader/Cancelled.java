package com.themoon.y1.reader;

/**
 * Выход из обработчика сканера при отмене чтения: сканер не умеет пробрасывать проверяемые исключения.
 * Снаружи читалка превращает его в {@link java.io.InterruptedIOException}.
 */
final class Cancelled extends RuntimeException {
    private static final long serialVersionUID = 1L;

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
