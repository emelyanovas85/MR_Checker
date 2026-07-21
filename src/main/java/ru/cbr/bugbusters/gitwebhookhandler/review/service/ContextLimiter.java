package ru.cbr.bugbusters.gitwebhookhandler.review.service;

/**
 * Утилита безопасного ограничения размера контекста, передаваемого в LLM.
 *
 * <p>Помогает избежать prompt bloat — основной причины timeout'ов при ревью
 * больших MR. Логика:
 * <ul>
 *   <li>Каждый отдельный source-ответ (один файл / один фрагмент)
 *       обрезается до {@code maxFileChars} символов.</li>
 *   <li>Суммарный контекст одной review-группы обрезается до
 *       {@code maxTotalChars} символов.</li>
 *   <li>К обрезанному тексту явно дописывается маркер {@code ...[truncated]}.</li>
 * </ul>
 *
 * <p>Значения по умолчанию:
 * <ul>
 *   <li>{@value #DEFAULT_MAX_FILE_CHARS} символов на файл (~150–250 строк Java)</li>
 *   <li>{@value #DEFAULT_MAX_TOTAL_CHARS} символов на всю group (~600–800 строк)</li>
 * </ul>
 */
public final class ContextLimiter {

    public static final int DEFAULT_MAX_FILE_CHARS  = 12_000;
    public static final int DEFAULT_MAX_TOTAL_CHARS = 30_000;

    private static final String TRUNCATION_MARKER = "\n...[truncated]";

    private ContextLimiter() {}

    /**
     * Обрезает один source-ответ (файл или фрагмент строк) до лимита.
     *
     * @param text         исходный текст ответа от ClassContext
     * @param maxFileChars максимально допустимое число символов
     * @return текст, не превышающий {@code maxFileChars}
     */
    public static String truncateFile(String text, int maxFileChars) {
        if (text == null) return "";
        if (text.length() <= maxFileChars) return text;
        return text.substring(0, maxFileChars) + TRUNCATION_MARKER;
    }

    /**
     * Обрезает суммарный контекст review-группы (user-message) до лимита.
     *
     * @param text          полный user-message
     * @param maxTotalChars максимально допустимое число символов
     * @return текст, не превышающий {@code maxTotalChars}
     */
    public static String truncateTotal(String text, int maxTotalChars) {
        if (text == null) return "";
        if (text.length() <= maxTotalChars) return text;
        return text.substring(0, maxTotalChars) + TRUNCATION_MARKER;
    }
}
