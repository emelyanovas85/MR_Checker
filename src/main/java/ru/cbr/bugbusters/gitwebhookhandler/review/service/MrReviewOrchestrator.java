package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.RefactoringGroup;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

/**
 * Оркестрирует полный процесс ревью MR.
 *
 * <h3>Flow:</h3>
 * <ol>
 *   <li>Получаем webhook → создаём сессию в сервисе 8084 ({@code POST /api/review-sessions}).</li>
 *   <li>Запрашиваем структуру изменённых файлов ({@code POST /api/structure/markdown}, depth=1).</li>
 *   <li>LLM-группировка (первый промпт): файлы → {@code List<RefactoringGroup>}.</li>
 *   <li>Параллельное LLM-ревью каждой группы (второй промпт) с тулами сервиса 8084.</li>
 *   <li>Агрегация результатов и публикация markdown-комментария в GitLab MR.</li>
 *   <li>Терминирование сессии 8084.</li>
 * </ol>
 *
 * <p>При повторном webhook для того же MR предыдущая сессия заменяется новой
 * (хранилище {@link #activeSessionByMrKey}).
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

    @Qualifier("reviewExecutor")
    private final Executor reviewExecutor;

    /**
     * Ключ: "projectId::mrIid" → sessionId сессии 8084.
     * Позволяет при повторном событии для того же MR сначала удалить старую сессию.
     */
    private final ConcurrentHashMap<String, String> activeSessionByMrKey = new ConcurrentHashMap<>();

    @Async("reviewExecutor")
    public void runReview(ReviewTriggerCommand command) {
        log.info("Запуск ревью: project={}, mrIid={}", command.projectId(), command.mrIid());

        String mrKey = command.projectId() + "::" + command.mrIid();
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
            log.info("Сессия создана: sessionId={}", sessionId);

            // --- Шаг 3: получить структуру изменённых файлов (depth=1) ---
            List<String> fileStructures = classContextClient.fetchStructures(sessionId);
            if (fileStructures.isEmpty()) {
                gitLabNotesPublisher.postNote(
                        command.projectId(), command.mrIid(),
                        "**AI Review**: сервис структуры не вернул данных для анализа.");
                return;
            }
            log.info("Получено {} файловых контекстов", fileStructures.size());

            // --- Шаг 4: LLM группировка (первый промпт) ---
            List<RefactoringGroup> groups = llmGroupingService.groupFiles(fileStructures);
            if (groups.isEmpty()) {
                gitLabNotesPublisher.postNote(
                        command.projectId(), command.mrIid(),
                        "**AI Review**: не удалось сформировать группы для анализа.");
                return;
            }
            log.info("Сформировано {} групп(ы) рефакторинга", groups.size());

            // --- Шаг 5: параллельное LLM-ревью каждой группы (второй промпт) ---
            final String finalSessionId = sessionId;
            List<CompletableFuture<GroupReviewResult>> futures = IntStream
                    .range(0, groups.size())
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> llmReviewService.review(i, groups.get(i), finalSessionId),
                            reviewExecutor))
                    .toList();

            List<GroupReviewResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // --- Шаг 6: публикуем markdown-комментарий в GitLab MR ---
            String comment = markdownCommentFormatter.format(command, results);
            gitLabNotesPublisher.postNote(command.projectId(), command.mrIid(), comment);
            log.info("Ревью завершено и опубликовано для project={}, mrIid={}",
                    command.projectId(), command.mrIid());

        } catch (Exception e) {
            log.error("Критическая ошибка ревью для project={}, mrIid={}: {}",
                    command.projectId(), command.mrIid(), e.getMessage(), e);
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
