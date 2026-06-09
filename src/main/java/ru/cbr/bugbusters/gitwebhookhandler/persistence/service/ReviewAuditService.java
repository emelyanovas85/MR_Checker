package ru.cbr.bugbusters.gitwebhookhandler.persistence.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.*;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.repository.*;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.domain.MergeRequestHookPayload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Сервис аудита AI-ревью и входящих webhook-событий.
 *
 * <p>Обеспечивает персистентное хранение:
 * <ul>
 *   <li>входящих GitLab webhook-событий (все, включая игнорируемые);</li>
 *   <li>запусков AI-ревью с полным жизненным циклом;</li>
 *   <li>результатов ревью по группам рефакторинга;</li>
 *   <li>промежуточных сообщений LLM (system/user/assistant/tool trace).</li>
 * </ul>
 *
 * <p>Все ошибки записи в БД логируются и <b>не пробрасываются</b> наружу,
 * чтобы сбой аудита не прерывал основной процесс ревью.
 *
 * @see WebhookEventRepository
 * @see ReviewRunRepository
 * @see ReviewGroupResultRepository
 * @see LlmMessageRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAuditService {

    private final WebhookEventRepository webhookEventRepository;
    private final ReviewRunRepository    reviewRunRepository;
    private final ReviewGroupResultRepository groupResultRepository;
    private final LlmMessageRepository   llmMessageRepository;
    private final ObjectMapper            objectMapper;

    // ────────────────────────────────────────────────────
    // Webhook audit
    // ────────────────────────────────────────────────────

    /**
     * Сохраняет входящее GitLab webhook-событие в БД.
     *
     * <p>Вызывается в {@code MergeRequestHookHandler} до начала обработки.
     * Статус ACCEPTED означает, что событие принято на ревью;
     * IGNORED — пропущено из-за неподдерживаемого action.
     *
     * @param payload   десериализованный payload от GitLab
     * @param rawJson   сырой JSON-строки (для хранения и replay)
     * @param accepted  {@code true} — событие принято на ревью
     */
    @Transactional
    public void saveWebhookEvent(MergeRequestHookPayload payload,
                                 String rawJson,
                                 boolean accepted) {
        try {
            WebhookEventEntity entity = WebhookEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .eventType("Merge Request Hook")
                    .action(payload.objectAttributes() != null
                            ? payload.objectAttributes().action() : null)
                    .projectId(payload.projectId())
                    .mrIid(payload.objectAttributes() != null
                            ? payload.objectAttributes().iid() : null)
                    .lastCommit(payload.objectAttributes() != null
                            && payload.objectAttributes().lastCommit() != null
                            ? payload.objectAttributes().lastCommit().id() : null)
                    .receivedAt(Instant.now())
                    .processingStatus(accepted
                            ? WebhookEventEntity.ProcessingStatus.ACCEPTED
                            : WebhookEventEntity.ProcessingStatus.IGNORED)
                    .rawPayload(rawJson)
                    .build();

            webhookEventRepository.save(entity);
            log.debug("Webhook событие сохранено: id={}, action={}, status={}",
                    entity.getId(), entity.getAction(), entity.getProcessingStatus());
        } catch (Exception e) {
            log.error("Ошибка сохранения webhook события в БД: {}", e.getMessage(), e);
        }
    }

    // ────────────────────────────────────────────────────
    // Review run lifecycle
    // ────────────────────────────────────────────────────

    /**
     * Создаёт новую запись запуска ревью в статусе RUNNING.
     *
     * <p>Вызывается в начале {@code MrReviewOrchestrator.runReview()}.
     *
     * @param command команда запуска ревью (из webhook)
     * @return UUID созданного запуска ревью
     */
    @Transactional
    public String createReviewRun(ReviewTriggerCommand command) {
        String runId = UUID.randomUUID().toString();
        try {
            ReviewRunEntity entity = ReviewRunEntity.builder()
                    .id(runId)
                    .projectId(command.projectId())
                    .mrIid(command.mrIid())
                    .sourceBranch(command.sourceBranch())
                    .targetBranch(command.targetBranch())
                    .lastCommit(command.lastCommit())
                    .triggeredBy(command.triggeredBy())
                    .status(ReviewRunEntity.ReviewStatus.RUNNING)
                    .startedAt(Instant.now())
                    .build();
            reviewRunRepository.save(entity);
            log.debug("ReviewRun создан: id={}, project={}, mrIid={}",
                    runId, command.projectId(), command.mrIid());
        } catch (Exception e) {
            log.error("Ошибка создания ReviewRun в БД: {}", e.getMessage(), e);
        }
        return runId;
    }

    /**
     * Обновляет sessionId после создания сессии в сервисе java-class-context.
     *
     * @param runId     UUID запуска ревью
     * @param sessionId sessionId из сервиса 8084
     */
    @Transactional
    public void updateSessionId(String runId, String sessionId) {
        try {
            reviewRunRepository.findById(runId).ifPresent(entity -> {
                entity.setSessionId(sessionId);
                reviewRunRepository.save(entity);
            });
        } catch (Exception e) {
            log.error("Ошибка обновления sessionId в ReviewRun {}: {}", runId, e.getMessage(), e);
        }
    }

    /**
     * Финализирует запуск ревью со статусом SUCCESS.
     *
     * <p>Сохраняет финальный markdown-комментарий и результаты по группам.
     * Вызывается после успешной публикации комментария в GitLab.
     *
     * @param runId                 UUID запуска ревью
     * @param finalCommentMarkdown  финальный markdown-комментарий
     * @param groupResults          результаты по группам рефакторинга
     */
    @Transactional
    public void completeReviewRun(String runId,
                                  String finalCommentMarkdown,
                                  List<GroupReviewResult> groupResults) {
        try {
            reviewRunRepository.findById(runId).ifPresent(entity -> {
                entity.setStatus(ReviewRunEntity.ReviewStatus.SUCCESS);
                entity.setFinishedAt(Instant.now());
                entity.setFinalCommentMarkdown(finalCommentMarkdown);
                reviewRunRepository.save(entity);

                // Сохраняем результаты по группам
                for (GroupReviewResult gr : groupResults) {
                    ReviewGroupResultEntity grEntity = ReviewGroupResultEntity.builder()
                            .id(UUID.randomUUID().toString())
                            .reviewRun(entity)
                            .groupIndex(gr.index())
                            .groupName(gr.groupName())
                            .success(gr.success())
                            .reviewText(gr.reviewText())
                            .build();
                    groupResultRepository.save(grEntity);
                }

                log.debug("ReviewRun завершён успешно: id={}, groups={}",
                        runId, groupResults.size());
            });
        } catch (Exception e) {
            log.error("Ошибка финализации ReviewRun {}: {}", runId, e.getMessage(), e);
        }
    }

    /**
     * Финализирует запуск ревью со статусом ERROR.
     *
     * <p>Вызывается в блоке catch {@code MrReviewOrchestrator.runReview()}.
     *
     * @param runId        UUID запуска ревью
     * @param errorMessage сообщение об ошибке
     */
    @Transactional
    public void failReviewRun(String runId, String errorMessage) {
        try {
            reviewRunRepository.findById(runId).ifPresent(entity -> {
                entity.setStatus(ReviewRunEntity.ReviewStatus.ERROR);
                entity.setFinishedAt(Instant.now());
                entity.setErrorMessage(errorMessage);
                reviewRunRepository.save(entity);
                log.debug("ReviewRun завершён с ошибкой: id={}", runId);
            });
        } catch (Exception e) {
            log.error("Ошибка записи ошибки в ReviewRun {}: {}", runId, e.getMessage(), e);
        }
    }

    // ────────────────────────────────────────────────────
    // LLM message trace
    // ────────────────────────────────────────────────────

    /**
     * Сохраняет одно сообщение промежуточного чата с LLM.
     *
     * <p>Вызывается вручную из сервисов группировки и ревью
     * для сохранения system/user/assistant сообщений и tool-calls.
     *
     * @param runId      UUID запуска ревью
     * @param stage      этап (GROUPING / REVIEW)
     * @param groupIndex индекс группы (null для этапа GROUPING)
     * @param role       роль отправителя
     * @param toolName   имя инструмента (null для не-tool сообщений)
     * @param content    содержимое сообщения
     */
    @Transactional
    public void saveLlmMessage(String runId,
                               LlmMessageEntity.Stage stage,
                               Integer groupIndex,
                               LlmMessageEntity.MessageRole role,
                               String toolName,
                               String content) {
        try {
            reviewRunRepository.findById(runId).ifPresent(run -> {
                LlmMessageEntity entity = LlmMessageEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .reviewRun(run)
                        .stage(stage)
                        .groupIndex(groupIndex)
                        .role(role)
                        .toolName(toolName)
                        .content(content != null ? content : "")
                        .createdAt(Instant.now())
                        .build();
                llmMessageRepository.save(entity);
            });
        } catch (Exception e) {
            log.error("Ошибка сохранения LLM сообщения (run={}, stage={}, role={}): {}",
                    runId, stage, role, e.getMessage(), e);
        }
    }
}
