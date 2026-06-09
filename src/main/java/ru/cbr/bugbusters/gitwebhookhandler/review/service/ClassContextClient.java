package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.cbr.bugbusters.gitwebhookhandler.common.config.AppProperties;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;

import java.util.List;

/**
 * Клиент к сервису java-class-context (порт 8084).
 *
 * <p>Реализует трёхшаговый flow:
 * <ol>
 *   <li>{@link #createSession} — создаёт сессию ревью, фиксирует SHA коммитов.</li>
 *   <li>{@link #fetchStructures} — возвращает markdown-контексты изменённых файлов (depth=1).</li>
 *   <li>{@link #deleteSession} — терминирует сессию после завершения ревью.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassContextClient {

    private final RestClient restClient;
    private final AppProperties appProperties;

    // -------------------------------------------------------------------------
    // Шаг 1: создание сессии
    // -------------------------------------------------------------------------

    /**
     * Создаёт сессию ревью в сервисе 8084.
     *
     * @param command данные MR из webhook
     * @return sessionId, который используется во всех последующих запросах
     */
    public String createSession(ReviewTriggerCommand command) {
        String url = appProperties.classContext().url() + "/api/review-sessions";
        String gitlabToken = appProperties.classContext().gitlabToken();
        String gitlabUrl = appProperties.gitlab().url();

        CreateSessionRequest request = new CreateSessionRequest(
                gitlabUrl,
                String.valueOf(command.projectId()),
                gitlabToken,
                command.mrIid()
        );

        try {
            CreateSessionResponse response = restClient.post()
                    .uri(url)
                    .body(request)
                    .retrieve()
                    .body(CreateSessionResponse.class);

            if (response == null || response.sessionId() == null) {
                throw new IllegalStateException("class-context-service вернул null sessionId");
            }
            log.info("Сессия создана: sessionId={}, sourceSha={}, targetSha={}",
                    response.sessionId(), response.sourceSha(), response.targetSha());
            return response.sessionId();
        } catch (Exception e) {
            log.error("Ошибка создания сессии для MR project={}, mrIid={}: {}",
                    command.projectId(), command.mrIid(), e.getMessage(), e);
            throw new RuntimeException("Не удалось создать сессию в class-context-service", e);
        }
    }

    // -------------------------------------------------------------------------
    // Шаг 2: получение структуры файлов в markdown
    // -------------------------------------------------------------------------

    /**
     * Запрашивает markdown-контексты изменённых файлов MR.
     * depth=1 — изменённые файлы + их прямые зависимости.
     *
     * @param sessionId из {@link #createSession}
     * @return список строк — каждая строка представляет один .java-файл
     */
    public List<String> fetchStructures(String sessionId) {
        String url = appProperties.classContext().url() + "/api/structure/markdown";

        SessionRequest request = new SessionRequest(sessionId, 1, null);

        try {
            List<String> structures = restClient.post()
                    .uri(url)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (structures == null || structures.isEmpty()) {
                log.warn("class-context-service вернул пустую структуру для sessionId={}", sessionId);
                return List.of();
            }
            log.info("Получено {} файловых контекстов для sessionId={}", structures.size(), sessionId);
            return structures;
        } catch (Exception e) {
            log.error("Ошибка получения структуры для sessionId={}: {}", sessionId, e.getMessage(), e);
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Шаг 3: удаление сессии
    // -------------------------------------------------------------------------

    /**
     * Терминирует сессию. Идемпотентен — игнорирует ошибки если сессия уже удалена.
     *
     * @param sessionId сессия для удаления
     */
    public void deleteSession(String sessionId) {
        String url = appProperties.classContext().url() + "/api/review-sessions";
        try {
            restClient.delete()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .retrieve()
                    .toBodilessEntity();
            // DELETE с телом через RestClient — используем exchange
            restClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(url)
                    .body(new SessionIdRequest(sessionId))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Сессия терминирована: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("Ошибка терминирования сессии sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Внутренние DTO (соответствуют API сервиса 8084)
    // -------------------------------------------------------------------------

    public record CreateSessionRequest(
            String gitlabUrl,
            String projectId,
            String token,
            Long mergeRequestIid
    ) {}

    public record CreateSessionResponse(
            String sessionId,
            String sourceSha,
            String targetSha,
            String baseSha,
            String expiresAt
    ) {}

    public record SessionRequest(
            String sessionId,
            int depth,
            Object names  // null = все изменённые файлы MR
    ) {}

    public record SessionIdRequest(
            String sessionId
    ) {}
}
