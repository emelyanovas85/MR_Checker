package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Spring-бин: глобальный rate-limiter для запросов к LLM.
 *
 * <p>Ограничение: 0,45 запроса/сек → минимальный интервал ~2,2 секунды между вызовами.
 * Singleton-бин (дефолт Spring), поэтому ограничение работает независимо
 * от того, через какой сервис идёт запрос (группировка или ревью).
 *
 * <p>Использование: инжектируется через конструктор в {@link LlmGroupingService}
 * и {@link LlmReviewService}. В тестах заменяется через {@code @MockBean}.
 */
@Slf4j
@Component
public class LlmRateLimiter {

    /**
     * 0.45 req/s = одно обращение каждые ~2.2 секунды.
     */
    @SuppressWarnings("UnstableApiUsage")
    private final RateLimiter rateLimiter = RateLimiter.create(0.45);

    /**
     * Блокирует вызывающий поток до тех пор, пока не истечёт минимальный интервал
     * с момента последнего вызова. Логирует время ожидания, если оно превышает 100 мс.
     */
    @SuppressWarnings("UnstableApiUsage")
    public void acquire() {
        double waited = rateLimiter.acquire();
        if (waited > 0.1) {
            log.debug("LLM rate-limit: ожидание {:.2f}с перед запросом к LLM", waited);
        }
    }
}
