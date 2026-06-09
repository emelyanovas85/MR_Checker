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

/**
 * Второй этап ревью: LLM анализирует одну группу рефакторинга.
 *
 * <p>Каждый вызов создаёт изолированный ChatClient с тулами сервиса 8084:
 * LLM может запрашивать исходный код через {@link ClassContextToolsProvider}.
 *
 * <p>Перед вызовом LLM применяется глобальный rate-limit через
 * инжектируемый {@link LlmRateLimiter#acquire()} (0,45 req/s ≈ 2,2с между вызовами).
 * Rate-limit общий для всех LLM-вызовов в приложении (группировка + ревью).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmReviewService {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectProvider<ClassContextToolsProvider> toolsProviderFactory;
    private final LlmRateLimiter rateLimiter;

    @Value("${app.ai.review-prompt-file:classpath:prompts/system-prompt.md}")
    private Resource reviewPromptResource;

    private String reviewPrompt;

    @PostConstruct
    void loadPrompt() throws IOException {
        reviewPrompt = reviewPromptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("Review prompt загружен из: {}", reviewPromptResource.getDescription());
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
        try {
            ClassContextToolsProvider tools = toolsProviderFactory.getObject().withSession(sessionId);

            rateLimiter.acquire(); // 0.45 req/s — общий лимит для всего приложения
            String response = chatClientBuilder.build()
                    .prompt()
                    .system(reviewPrompt)
                    .user(buildUserMessage(index, group))
                    .tools(tools)
                    .call()
                    .content();

            String result = (response == null || response.isBlank()) ? "No issues found." : response;
            return GroupReviewResult.success(index, groupName, result);
        } catch (Exception e) {
            log.error("Ошибка LLM-ревью для группы '{}' (index={})", groupName, index, e);
            return GroupReviewResult.failure(index, groupName, e.getMessage());
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
