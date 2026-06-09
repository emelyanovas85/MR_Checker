package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.RefactoringGroup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Первый этап ревью: LLM группирует изменённые файлы MR по refactoring_groups.
 *
 * <p>Принимает список markdown-контекстов файлов от сервиса 8084,
 * отправляет их в LLM с промптом группировки и парсит JSON-ответ.
 *
 * <p>Перед каждым вызовом LLM применяется глобальный rate-limit через
 * инжектируемый {@link LlmRateLimiter#acquire()} (0,45 req/s ≈ 2,2с между вызовами).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGroupingService {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final LlmRateLimiter rateLimiter;

    @Value("${app.ai.grouping-prompt-file:classpath:prompts/grouping-prompt.md}")
    private Resource groupingPromptResource;

    private String groupingPrompt;

    @PostConstruct
    void loadPrompt() throws IOException {
        groupingPrompt = groupingPromptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("Grouping prompt загружен из: {}", groupingPromptResource.getDescription());
    }

    /**
     * Группирует изменённые файлы MR по refactoring_groups через LLM.
     *
     * @param fileStructures markdown-контексты файлов от POST /api/structure/markdown
     * @return список групп рефакторинга; пустой список при ошибке или отсутствии данных
     */
    public List<RefactoringGroup> groupFiles(List<String> fileStructures) {
        if (fileStructures.isEmpty()) {
            log.warn("Нет файловых контекстов для группировки");
            return List.of();
        }

        String userMessage = buildUserMessage(fileStructures);
        log.info("Запускаем LLM группировку для {} файлов", fileStructures.size());

        try {
            rateLimiter.acquire(); // 0.45 req/s — общий лимит для всего приложения
            String response = chatClientBuilder.build()
                    .prompt()
                    .system(groupingPrompt)
                    .user(userMessage)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("LLM вернул пустой ответ при группировке");
                return List.of();
            }

            return parseGroupingResponse(response);
        } catch (Exception e) {
            log.error("Ошибка LLM-группировки: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String buildUserMessage(List<String> fileStructures) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Структуры изменённых файлов MR\n\n");
        for (int i = 0; i < fileStructures.size(); i++) {
            sb.append("### Файл ").append(i + 1).append("\n\n");
            sb.append(fileStructures.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    private List<RefactoringGroup> parseGroupingResponse(String response) {
        String json = extractJson(response);
        try {
            GroupingResponse parsed = objectMapper.readValue(json, GroupingResponse.class);
            if (parsed.refactoringGroups() == null || parsed.refactoringGroups().isEmpty()) {
                log.warn("LLM вернул пустой список refactoring_groups");
                return List.of();
            }
            log.info("LLM сформировал {} групп(ы) рефакторинга", parsed.refactoringGroups().size());
            return parsed.refactoringGroups();
        } catch (JsonProcessingException e) {
            log.error("Не удалось распарсить JSON ответа группировки: {}", e.getMessage());
            log.debug("Сырой ответ LLM: {}", response);
            return List.of();
        }
    }

    private static String extractJson(String response) {
        Pattern fence = Pattern.compile("```(?:json)?\\s*(\\{.*}|\\[.*])", Pattern.DOTALL);
        Matcher m = fence.matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        int start = response.indexOf('{');
        if (start < 0) start = response.indexOf('[');
        return start >= 0 ? response.substring(start).trim() : response.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroupingResponse(
            @com.fasterxml.jackson.annotation.JsonAlias({"refactoring_groups", "refactoringGroups"})
            List<RefactoringGroup> refactoringGroups
    ) {}
}
