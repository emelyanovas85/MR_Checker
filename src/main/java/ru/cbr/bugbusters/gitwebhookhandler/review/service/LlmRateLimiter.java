package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;

/**
 * Глобальный rate-limiter для запросов к LLM.
 *
 * <p>Ограничение: 0,45 запроса/сек → минимальный интервал ~2,2 секунды между вызовами.
 * Один экземпляр на всё приложение (static), поэтому ограничение работает
 * независимо от того, через какой сервис идёт запрос (группировка или ревью).
 *
 * <p>Использование:
 * <pre>
 *   LlmRateLimiter.acquire(); // блокирует поток до получения разрешения
 *   // ... вызов LLM ...
 * </pre>
 */
@Slf4j
public final class LlmRateLimiter {

    /**
     * 0.45 req/s = одно обращение каждые ~2.2 секунды.
     * RateLimiter.create() потокобезопасен и является singleton через static final.
     */
    @SuppressWarnings("UnstableApiUsage")
    private static final RateLimiter LIMITER = RateLimiter.create(0.45);

    private LlmRateLimiter() {}

    /**
     * Блокирует вызывающий поток до тех пор, пока не истечёт минимальный интервал
     * с момента последнего вызова. Логирует время ожидания, если оно превышает 100 мс.
     */
    @SuppressWarnings("UnstableApiUsage")
    public static void acquire() {
        double waited = LIMITER.acquire();
        if (waited > 0.1) {
            log.debug("LLM rate-limit: ожидание {:.2f}с перед запросом к LLM", waited);
        }
    }
}
