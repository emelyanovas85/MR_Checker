package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.RefactoringGroup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Второй этап ревью: LLM анализирует одну группу рефакторинга.
 *
 * <p>Каждый вызов создаёт изолированный ChatClient с тулами сервиса 8084:
 * LLM может запрашивать исходный код через {@link ClassContextToolsProvider}.
 *
 * <h3>Защита от timeout и перегрузки</h3>
 * <ol>
 *   <li><b>RateLimiter</b> ({@link LlmRateLimiter}): сглаживает частоту старта запросов
 *       (~2,2 с между вызовами). Общий для всех LLM-вызовов приложения.</li>
 *   <li><b>Semaphore</b> (concurrency cap): не более
 *       {@code app.ai.max-concurrent-reviews} одновременных LLM-вызовов ревью.
 *       Предотвращает ситуацию, когда все потоки висят в долгих OpenAI-запросах.
 *       По умолчанию — {@value #DEFAULT_MAX_CONCURRENT} слота.</li>
 *   <li><b>Context truncation</b>: user-message обрезается до
 *       {@code app.ai.context-limit.max-total-chars} символов ({@link ContextLimiter}).
 *       Ответы tool calls обрезаются на уровне {@link ClassContextToolsProvider}.</li>
 * </ol>
 */
@Slf4j
@Service
public class LlmReviewService {

    private static final int DEFAULT_MAX_CONCURRENT = 2;

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectProvider<ClassContextToolsProvider> toolsProviderFactory;
    private final LlmRateLimiter rateLimiter;

    @Value("${app.ai.review-prompt-file:classpath:prompts/system-prompt.md}")
    private Resource reviewPromptResource;

    @Value("${app.ai.max-concurrent-reviews:" + DEFAULT_MAX_CONCURRENT + "}")
    private int maxConcurrentReviews;

    @Value("${app.ai.context-limit.max-total-chars:" + ContextLimiter.DEFAULT_MAX_TOTAL_CHARS + "}")
    private int maxTotalChars;

    /** Инициализируется в {@link #init()} после чтения конфига. */
    private Semaphore concurrencyLimiter;
    private String reviewPrompt;

    public LlmReviewService(ChatClient.Builder chatClientBuilder,
                            ObjectProvider<ClassContextToolsProvider> toolsProviderFactory,
                            LlmRateLimiter rateLimiter) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolsProviderFactory = toolsProviderFactory;
        this.rateLimiter = rateLimiter;
    }

    @PostConstruct
    void init() throws IOException {
        reviewPrompt = reviewPromptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("Review prompt загружен из: {}", reviewPromptResource.getDescription());
        concurrencyLimiter = new Semaphore(maxConcurrentReviews, true);
        log.info("LlmReviewService: concurrency cap={}, maxTotalChars={}",
                maxConcurrentReviews, maxTotalChars);
    }

    /**
     * Выполняет ревью одной группы рефакторинга.
     *
     * @param index     порядковый номер группы
     * @param group     группа рефакторинга из первого этапа
     * @param sessionId sessionId сессии 8084
     * @return результат ревью
     */
    public GroupReviewResult review(int index, RefactoringGroup group, String sessionId) {
        String groupName = group.groupName() != null ? group.groupName() : "Group #" + (index + 1);
        boolean acquired = false;
        try {
            // Concurrency cap: не более maxConcurrentReviews одновременных LLM-запросов ревью.
            // tryAcquire с таймаутом вместо бесконечного acquire() — избегаем вечной очереди.
            acquired = concurrencyLimiter.tryAcquire(90, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[Review] concurrency cap exceeded, skipping group '{}' (index={})",
                        groupName, index);
                return GroupReviewResult.failure(index, groupName,
                        "Skipped: concurrency cap exceeded (max " + maxConcurrentReviews + " concurrent reviews)");
            }

            ClassContextToolsProvider tools = toolsProviderFactory.getObject().withSession(sessionId);

            rateLimiter.acquire(); // 0.45 req/s — общий лимит для всего приложения

            String userMessage = ContextLimiter.truncateTotal(buildUserMessage(index, group), maxTotalChars);
            if (userMessage.endsWith(ContextLimiter.TRUNCATION_MARKER + "")) {
                log.info("[Review] user-message truncated for group '{}' to {} chars", groupName, maxTotalChars);
            }

            String response = chatClientBuilder.build()
                    .prompt()
                    .system(reviewPrompt)
                    .user(userMessage)
                    .tools(tools)
                    .call()
                    .content();

            String result = (response == null || response.isBlank()) ? "No issues found." : response;
            return GroupReviewResult.success(index, groupName, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Review] interrupted waiting for semaphore, group '{}' (index={})", groupName, index, e);
            return GroupReviewResult.failure(index, groupName, "Interrupted while waiting for concurrency slot");
        } catch (Exception e) {
            log.error("Ошибка LLM-ревью для группы '{}' (index={})", groupName, index, e);
            return GroupReviewResult.failure(index, groupName, e.getMessage());
        } finally {
            if (acquired) {
                concurrencyLimiter.release();
            }
        }
    }

    private String buildUserMessage(int index, RefactoringGroup group) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Группа рефакторинга #").append(index + 1).append(": ").append(group.groupName()).append("\n\n");
        if (group.reason() != null) sb.append("**Причина:** ").append(group.reason()).append("\n\n");
        if (group.refactoringGoal() != null) sb.append("**Цель:** ").append(group.refactoringGoal()).append("\n\n");
        if (group.priority() != null) sb.append("**Приоритет:** ").append(group.priority()).append("\n\n");
        if (group.files() != null && !group.files().isEmpty()) {
            sb.append("**Файлы:**\n");
            for (RefactoringGroup.GroupFile file : group.files()) {
                sb.append("- `").append(file.path()).append("`");
                if (file.status() != null) sb.append(" [").append(file.status()).append("]");
                if (file.responsibility() != null) sb.append(" — ").append(file.responsibility());
                sb.append("\n");
            }
            sb.append("\n");
        }
        sb.append("Выполни ревью. Используй доступные инструменты для получения исходного кода.");
        return sb.toString();
    }
}
