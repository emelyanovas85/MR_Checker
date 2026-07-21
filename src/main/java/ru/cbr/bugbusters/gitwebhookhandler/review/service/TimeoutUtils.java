package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.util.concurrent.TimeoutException;

/**
 * Утилита для определения timeout-исключений по цепочке cause.
 * Перехватывает: {@link InterruptedIOException}("timeout"),
 * {@link TimeoutException}, {@link SocketException}("socket closed").
 */
public final class TimeoutUtils {

    private TimeoutUtils() {}

    public static boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof InterruptedIOException ioe
                    && "timeout".equalsIgnoreCase(ioe.getMessage())) return true;
            if (current instanceof TimeoutException) return true;
            if (current instanceof SocketException se
                    && se.getMessage() != null
                    && se.getMessage().toLowerCase().contains("socket closed")) return true;
            current = current.getCause();
        }
        return false;
    }
}
