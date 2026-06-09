package ru.cbr.bugbusters.gitwebhookhandler.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.service.ReviewAuditService;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.MrReviewOrchestrator;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.domain.MergeRequestHookPayload;

/**
 * Обрабатывает Merge Request Hook от GitLab.
 *
 * <p>Запускает ревью для событий: {@code opened}, {@code reopened}, {@code approved}.
 * Все остальные события игнорируются (action {@code close}, {@code merge} и т.д.).
 *
 * <p>Каждое входящее событие (включая игнорируемые) сохраняется в БД
 * через {@link ReviewAuditService} для аудита и возможного replay.
 *
 * @see ReviewAuditService
 * @see MrReviewOrchestrator
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MergeRequestHookHandler {

    private final MrReviewOrchestrator mrReviewOrchestrator;
    private final ReviewAuditService   reviewAuditService;
    private final ObjectMapper         objectMapper;

    /**
     * Обрабатывает входящий payload Merge Request Hook.
     *
     * <p>Алгоритм:
     * <ol>
     *   <li>Сохраняет событие в БД (аудит).</li>
     *   <li>Проверяет action: если не reviewable — игнорирует.</li>
     *   <li>Формирует {@link ReviewTriggerCommand} и запускает оркестратор.</li>
     * </ol>
     *
     * @param payload   десериализованный payload от GitLab
     * @param rawJson   исходная JSON-строка payload (для сохранения в БД)
     */
    public void handle(MergeRequestHookPayload payload, String rawJson) {
        if (payload.objectAttributes() == null) {
            log.debug("Пропуск MR hook: отсутствует object_attributes");
            reviewAuditService.saveWebhookEvent(payload, rawJson, false);
            return;
        }

        String action = payload.objectAttributes().action();
        String state  = payload.objectAttributes().state();

        boolean reviewable = isReviewableAction(action, state);
        reviewAuditService.saveWebhookEvent(payload, rawJson, reviewable);

        if (!reviewable) {
            log.debug("Пропуск MR hook: action={}, state={}", action, state);
            return;
        }

        Long projectId = payload.projectId();
        Long mrIid     = payload.objectAttributes().iid();

        if (projectId == null || mrIid == null) {
            log.warn("MR hook не содержит projectId или mrIid, пропускается");
            return;
        }

        String lastCommit = payload.objectAttributes().lastCommit() != null
                ? payload.objectAttributes().lastCommit().id()
                : null;

        ReviewTriggerCommand command = new ReviewTriggerCommand(
                projectId,
                mrIid,
                payload.objectAttributes().sourceBranch(),
                payload.objectAttributes().targetBranch(),
                lastCommit,
                payload.objectAttributes().title(),
                payload.objectAttributes().url(),
                payload.user() != null ? payload.user().username() : "gitlab"
        );

        log.info("Запуск ревью: project={}, mrIid={}, action={}", projectId, mrIid, action);
        mrReviewOrchestrator.runReview(command);
    }

    /**
     * Определяет, должно ли событие инициировать ревью.
     *
     * @param action действие MR (open, reopen, approved, ...)
     * @param state  состояние MR
     * @return {@code true} если необходимо запустить ревью
     */
    private boolean isReviewableAction(String action, String state) {
        if (action == null) return false;
        return switch (action.toLowerCase()) {
            case "open", "reopen", "approved" -> true;
            default -> false;
        };
    }
}
