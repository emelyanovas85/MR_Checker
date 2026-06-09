package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.LlmMessageEntity;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.service.ReviewAuditService;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.RefactoringGroup;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

/**
 * Оркестрирует полный процесс AI-ревью MR.
 *
 * <h3>Flow (7 шагов):</h3>
 * <ol>
 *   <li>Создать запись запуска ревью в БД (статус RUNNING).</li>
 *   <li>Завершить предыдущую сессию для этого MR (если есть).</li>
 *   <li>Создать новую сессию в сервисе java-class-context (порт 8084).</li>
 *   <li>Получить структуру изменённых файлов {@code POST /api/structure/markdown}, depth=1.</li>
 *   <li>LLM-группировка (первый промпт): файлы → {@code List<RefactoringGroup>}.</li>
 *   <li>Параллельное LLM-ревью каждой группы (второй промпт) с тулами сервиса 8084.</li>
 *   <li>Агрегация, публикация markdown-комментария в GitLab, финализация в БД.</li>
 * </ol>
 *
 * <p>При повторном webhook для того же MR предыдущая сессия заменяется новой
 * (хранилище {@link #activeSessionByMrKey}).
 *
 * <p>Все ошибки аудита (запись в БД) не прерывают основной процесс ревью.
 *
 * @see ReviewAuditService
 * @see ClassContextClient
 * @see LlmGroupingService
 * @see LlmReviewService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MrReviewOrchestrator {

    private final ClassContextClient classContextClient;
    private final LlmGroupingService llmGroupingService;
    private final LlmReviewService llmReviewService;
    private final MarkdownCommentFormatter markdownCommentFormatter;
    private final GitLabNotesPublisher gitLabNotesPublisher;
    private final ReviewAuditService reviewAuditService;

    @Qualifier("reviewExecutor")
    private final Executor reviewExecutor;

    /**
     * Ключ: "projectId::mrIid" → sessionId активной сессии в сервисе 8084.
     * Позволяет при повторном событии для того же MR сначала удалить старую сессию.
     */
    private final ConcurrentHashMap<String, String> activeSessionByMrKey = new ConcurrentHashMap<>();

    /**
     * Выполняет полный цикл AI-ревью MR асинхронно.
     *
     * <p>Метод аннотирован {@code @Async} и выполняется в пуле {@code reviewExecutor},
     * поэтому не блокирует поток обработки webhook.
     *
     * @param command команда запуска ревью с метаданными MR
     */
    @Async("reviewExecutor")
    public void runReview(ReviewTriggerCommand command) {
        log.info("Запуск ревью: project={}, mrIid={}", command.projectId(), command.mrIid());

        String mrKey  = command.projectId() + "::" + command.mrIid();
        String runId  = reviewAuditService.createReviewRun(command);
        String sessionId = null;

        try {
            // --- Шаг 1: завершить предыдущую сессию для этого MR (если есть) ---
            String previousSession = activeSessionByMrKey.get(mrKey);
            if (previousSession != null) {
                log.info("Терминируем предыдущую сессию {} для MR {}", previousSession, mrKey);
                classContextClient.deleteSession(previousSession);
            }

            // --- Шаг 2: создать новую сессию в сервисе 8084 ---
            sessionId = classContextClient.createSession(command);
            activeSessionByMrKey.put(mrKey, sessionId);
            reviewAuditService.updateSessionId(runId, sessionId);
            log.info("Сессия создана: sessionId={}", sessionId);

            // --- Шаг 3: получить структуру изменённых файлов (depth=1) ---
            List<String> fileStructures = classContextClient.fetchStructures(sessionId);
            if (fileStructures.isEmpty()) {
                String note = "**AI Review**: сервис структуры не вернул данных для анализа.";
                gitLabNotesPublisher.postNote(command.projectId(), command.mrIid(), note);
                reviewAuditService.failReviewRun(runId, "Сервис структуры не вернул данных");
                return;
            }
            log.info("Получено {} файловых контекстов", fileStructures.size());

            // --- Шаг 4: LLM группировка (первый промпт) ---
            reviewAuditService.saveLlmMessage(runId, LlmMessageEntity.Stage.GROUPING, null,
                    LlmMessageEntity.MessageRole.USER,
                    null, String.join("\n---\n", fileStructures));

            List<RefactoringGroup> groups = llmGroupingService.groupFiles(fileStructures);

            if (groups.isEmpty()) {
                String note = "**AI Review**: не удалось сформировать группы для анализа.";
                gitLabNotesPublisher.postNote(command.projectId(), command.mrIid(), note);
                reviewAuditService.failReviewRun(runId, "LLM не сформировал группы");
                return;
            }
            log.info("Сформировано {} групп(ы) рефакторинга", groups.size());

            // --- Шаг 5: параллельное LLM-ревью каждой группы (второй промпт) ---
            final String finalSessionId = sessionId;
            final String finalRunId = runId;
            List<CompletableFuture<GroupReviewResult>> futures = IntStream
                    .range(0, groups.size())
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> {
                                GroupReviewResult result =
                                        llmReviewService.review(i, groups.get(i), finalSessionId);
                                // Сохраняем ответ LLM по группе
                                reviewAuditService.saveLlmMessage(
                                        finalRunId,
                                        LlmMessageEntity.Stage.REVIEW,
                                        i,
                                        result.success()
                                                ? LlmMessageEntity.MessageRole.ASSISTANT
                                                : LlmMessageEntity.MessageRole.ASSISTANT,
                                        null,
                                        result.reviewText());
                                return result;
                            },
                            reviewExecutor))
                    .toList();

            List<GroupReviewResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // --- Шаг 6: публикуем markdown-комментарий в GitLab MR ---
            String comment = markdownCommentFormatter.format(command, results);
            gitLabNotesPublisher.postNote(command.projectId(), command.mrIid(), comment);
            reviewAuditService.completeReviewRun(runId, comment, results);

            log.info("Ревью завершено и опубликовано для project={}, mrIid={}",
                    command.projectId(), command.mrIid());

        } catch (Exception e) {
            log.error("Критическая ошибка ревью для project={}, mrIid={}: {}",
                    command.projectId(), command.mrIid(), e.getMessage(), e);
            reviewAuditService.failReviewRun(runId, e.getMessage());
            try {
                gitLabNotesPublisher.postNote(
                        command.projectId(), command.mrIid(),
                        "**AI Review**: ошибка при выполнении ревью — `" + e.getMessage() + "`");
            } catch (Exception pub) {
                log.error("Не удалось опубликовать ошибку в GitLab: {}", pub.getMessage());
            }
        } finally {
            // --- Шаг 7: всегда терминируем сессию ---
            if (sessionId != null) {
                activeSessionByMrKey.remove(mrKey, sessionId);
                classContextClient.deleteSession(sessionId);
                log.info("Сессия {} терминирована", sessionId);
            }
        }
    }
}
