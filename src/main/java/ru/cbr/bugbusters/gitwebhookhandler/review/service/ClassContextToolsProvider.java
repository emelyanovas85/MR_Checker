package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.cbr.bugbusters.gitwebhookhandler.common.config.AppProperties;

import java.util.List;

/**
 * Spring AI тулы сервиса java-class-context, доступные LLM во время ревью группы.
 *
 * <p>Каждый экземпляр создаётся под конкретный сеанс ревью ({@code scope=prototype})
 * и привязан к конкретному {@code sessionId} сессии 8084.
 *
 * <p>LLM может вызывать тулы для получения исходного кода:
 * <ul>
 *   <li>{@link #getSourceLines} — фрагменты кода по диапазонам строк (быстро, точечно)</li>
 *   <li>{@link #getSourceFile}  — полный исходник файла по имени класса</li>
 * </ul>
 */
@Slf4j
@Component
@Scope("prototype")
public class ClassContextToolsProvider {

    private final RestClient restClient;
    private final AppProperties appProperties;
    private String sessionId;

    public ClassContextToolsProvider(RestClient restClient, AppProperties appProperties) {
        this.restClient = restClient;
        this.appProperties = appProperties;
    }

    /**
     * Устанавливает sessionId перед передачей тула в ChatClient.
     * Вызывается из {@link LlmReviewService} после получения прототипа через ObjectProvider.
     */
    public ClassContextToolsProvider withSession(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    // -------------------------------------------------------------------------
    // Тул 1: получить фрагменты кода по диапазонам строк
    // -------------------------------------------------------------------------

    @Tool(description = """
            Получить исходный код указанного Java-класса по диапазонам строк из репозитория GitLab.
            Используй, когда нужно посмотреть конкретный метод или блок кода.
            Пример rows: ["28-168"] или ["17", "45-67"].
            """)
    public String getSourceLines(
            @ToolParam(description = "Fully qualified имя класса, например com.example.service.UserService")
            String qualifiedName,

            @ToolParam(description = "Диапазоны строк в формате JSON-массива строк: [\"28-168\"] или [\"17\", \"45-67\"]")
            List<String> rows
    ) {
        log.info("[Tool] getSourceLines: class={}, rows={}, session={}", qualifiedName, rows, sessionId);
        String url = appProperties.classContext().url() + "/api/source-lines/gitlab";
        try {
            GitLabLinesRequest request = new GitLabLinesRequest(
                    new SessionRef(sessionId),
                    List.of(new ClassLines(qualifiedName, "main", rows))
            );
            return restClient.post()
                    .uri(url)
                    .body(request)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("[Tool] getSourceLines failed: class={}, error={}", qualifiedName, e.getMessage());
            return "Не удалось получить строки для класса: " + qualifiedName + ". Ошибка: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Тул 2: получить полный исходник файла по имени класса
    // -------------------------------------------------------------------------

    @Tool(description = """
            Получить полный исходный код Java-файла по имени класса (simple или qualified).
            Используй, когда нужен весь контекст файла, а не отдельные строки.
            Пример: "UserService" или "com.example.service.UserService".
            """)
    public String getSourceFile(
            @ToolParam(description = "Simple или fully qualified имя класса, например UserService или com.example.UserService")
            String className
    ) {
        log.info("[Tool] getSourceFile: class={}, session={}", className, sessionId);
        String url = appProperties.classContext().url() + "/api/source-file";
        try {
            SourceFileRequest request = new SourceFileRequest(sessionId, List.of(className));
            return restClient.post()
                    .uri(url)
                    .body(request)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("[Tool] getSourceFile failed: class={}, error={}", className, e.getMessage());
            return "Не удалось получить исходник для класса: " + className + ". Ошибка: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Внутренние DTO для запросов к 8084
    // -------------------------------------------------------------------------

    record GitLabLinesRequest(SessionRef session, List<ClassLines> classes) {}
    record SessionRef(String sessionId) {}
    record ClassLines(String qualifiedName, String source, List<String> rows) {}
    record SourceFileRequest(String sessionId, List<String> names) {}
}
