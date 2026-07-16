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
 * ⚠️ ВАРИАНТ Б — НЕ АКТИВЕН (резерв для GitLab-MCP flow).
 *
 * <p>Этот класс <strong>не подключён</strong> к {@link MrReviewOrchestrator}.
 * Активный сервис ревью — {@link LlmReviewService} (Вариант А).
 *
 * <p>Сервис LLM-ревью с полной интеграцией GitLab MCP инструментов.
 *
 * <h3>Отличия от активного LlmReviewService (Вариант А):</h3>
 * <ul>
 *   <li>Использует {@link GitLabToolsProvider} вместо {@link ClassContextToolsProvider}</li>
 *   <li>Системный промпт {@code gitlab-system-prompt.md} с полным списком GitLab MCP инструментов</li>
 *   <li>LLM сам создаёт сессию ревью через {@code createReviewSession} для каждой группы</li>
 *   <li>LLM может создавать inline-треды в MR через {@code createMergeRequestThread}</li>
 *   <li>Получает диффы и структуру MR через GitLab MCP вместо java-class-context (8084)</li>
 * </ul>
 *
 * <h3>Чтобы активировать Вариант Б:</h3>
 * <ol>
 *   <li>Инжектировать этот класс в {@link MrReviewOrchestrator} вместо {@link LlmReviewService}</li>
 *   <li>Убедиться, что GitLab MCP сервер доступен (переменная {@code MCP_GITLAB_URL})</li>
 *   <li>Подготовить промпт {@code classpath:prompts/gitlab-system-prompt.md}</li>
 * </ol>
 *
 * @see LlmReviewService активный аналог (Вариант А)
 * @see GitLabToolsProvider инструменты для LLM в этом варианте
 * @see GitLabMcpClient HTTP-клиент к GitLab MCP серверу
 *
 * @implNote Конфигурация: {@code app.ai.gitlab-review-prompt-file} — путь к системному промпту
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabReviewService {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectProvider<GitLabToolsProvider> gitLabToolsProviderFactory;
    private final LlmRateLimiter rateLimiter;

    @Value("${app.ai.gitlab-review-prompt-file:classpath:prompts/gitlab-system-prompt.md}")
    private Resource reviewPromptResource;

    private String reviewPrompt;

    @PostConstruct
    void loadPrompt() throws IOException {
        reviewPrompt = reviewPromptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("GitLab review prompt загружен из: {}", reviewPromptResource.getDescription());
    }

    /**
     * Выполняет ревью одной группы рефакторинга с использованием GitLab MCP инструментов.
     *
     * @param index     индекс группы (0-based)
     * @param group     описание группы файлов для ревью
     * @param projectId ID проекта GitLab
     * @param mrIid     IID Merge Request
     * @return результат ревью группы
     */
    public GroupReviewResult review(int index, RefactoringGroup group, int projectId, int mrIid) {
        String groupName = (group.groupName() != null && !group.groupName().isBlank())
                ? group.groupName()
                : "Group #" + (index + 1);

        log.info("[GitLabReview] Начало ревью группы '{}' (index={}, projectId={}, mrIid={})",
                groupName, index, projectId, mrIid);

        try {
            GitLabToolsProvider tools = gitLabToolsProviderFactory.getObject();

            rateLimiter.acquire();

            String response = chatClientBuilder.build()
                    .prompt()
                    .system(reviewPrompt)
                    .user(buildUserMessage(index, group, projectId, mrIid))
                    .tools(tools)
                    .call()
                    .content();

            String result = (response == null || response.isBlank())
                    ? "Замечаний не обнаружено."
                    : response;

            log.info("[GitLabReview] Ревью группы '{}' завершено (index={})", groupName, index);
            return GroupReviewResult.success(index, groupName, result);

        } catch (Exception e) {
            log.error("[GitLabReview] Ошибка ревью группы '{}' (index={}): {}", groupName, index, e.getMessage(), e);
            return GroupReviewResult.failure(index, groupName, e.getMessage());
        }
    }

    private String buildUserMessage(int index, RefactoringGroup group, int projectId, int mrIid) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Группа рефакторинга #").append(index + 1);
        if (group.groupName() != null) {
            sb.append(": ").append(group.groupName());
        }
        sb.append("\n\n");

        sb.append("**projectId:** ").append(projectId).append("\n");
        sb.append("**mrIid:** ").append(mrIid).append("\n\n");

        if (group.reason() != null) {
            sb.append("**Причина группировки:** ").append(group.reason()).append("\n\n");
        }
        if (group.refactoringGoal() != null) {
            sb.append("**Цель рефакторинга:** ").append(group.refactoringGoal()).append("\n\n");
        }
        if (group.priority() != null) {
            sb.append("**Приоритет:** ").append(group.priority()).append("\n\n");
        }

        if (group.files() != null && !group.files().isEmpty()) {
            sb.append("**Файлы группы:**\n");
            for (RefactoringGroup.GroupFile file : group.files()) {
                sb.append("- `").append(file.path()).append("`");
                if (file.status() != null) sb.append(" [").append(file.status()).append("]");
                if (file.responsibility() != null) sb.append(" — ").append(file.responsibility());
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("Выполни ревью этой группы.\n");
        sb.append("1. Вызови `createReviewSession(projectId=" + projectId + ", mrIid=" + mrIid + ")` для получения sessionId.\n");
        sb.append("2. Получи структуру через `getStructureMarkdown(sessionId, depth=1)`.\n");
        sb.append("3. Для каждого файла группы получи исходник через `getSourceFile` или диф через `getMergeRequestFileDiff`.\n");
        sb.append("4. При критических проблемах создай тред через `createMergeRequestThread`.\n");
        sb.append("5. Завершай сессию через `terminateReviewSession` после анализа всех файлов.\n");

        return sb.toString();
    }
}
