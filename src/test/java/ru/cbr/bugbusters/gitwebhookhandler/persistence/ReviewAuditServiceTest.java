package ru.cbr.bugbusters.gitwebhookhandler.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.*;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.repository.*;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.service.ReviewAuditService;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.domain.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты сервиса аудита ревью.
 * Проверяют логику маппинга и сохранения сущностей без обращения к реальной БД.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewAuditService — unit тесты")
class ReviewAuditServiceTest {

    @Mock WebhookEventRepository    webhookEventRepository;
    @Mock ReviewRunRepository        reviewRunRepository;
    @Mock ReviewGroupResultRepository groupResultRepository;
    @Mock LlmMessageRepository       llmMessageRepository;
    @Mock ObjectMapper               objectMapper;

    @InjectMocks
    ReviewAuditService service;

    // ─────────────────────────────────────────────────────────
    // saveWebhookEvent
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("saveWebhookEvent ACCEPTED — сохраняет сущность со статусом ACCEPTED")
    void saveWebhookEvent_accepted_savesWithAcceptedStatus() {
        MergeRequestHookPayload payload = buildPayload("open");

        service.saveWebhookEvent(payload, "{}", true);

        ArgumentCaptor<WebhookEventEntity> captor =
                ArgumentCaptor.forClass(WebhookEventEntity.class);
        verify(webhookEventRepository).save(captor.capture());
        assertThat(captor.getValue().getProcessingStatus())
                .isEqualTo(WebhookEventEntity.ProcessingStatus.ACCEPTED);
        assertThat(captor.getValue().getAction()).isEqualTo("open");
        assertThat(captor.getValue().getProjectId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("saveWebhookEvent IGNORED — сохраняет сущность со статусом IGNORED")
    void saveWebhookEvent_ignored_savesWithIgnoredStatus() {
        MergeRequestHookPayload payload = buildPayload("close");

        service.saveWebhookEvent(payload, "{}", false);

        ArgumentCaptor<WebhookEventEntity> captor =
                ArgumentCaptor.forClass(WebhookEventEntity.class);
        verify(webhookEventRepository).save(captor.capture());
        assertThat(captor.getValue().getProcessingStatus())
                .isEqualTo(WebhookEventEntity.ProcessingStatus.IGNORED);
    }

    // ─────────────────────────────────────────────────────────
    // createReviewRun
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("createReviewRun — сохраняет запуск со статусом RUNNING")
    void createReviewRun_savesWithRunningStatus() {
        ReviewTriggerCommand command = buildCommand();

        String runId = service.createReviewRun(command);

        assertThat(runId).isNotBlank();
        ArgumentCaptor<ReviewRunEntity> captor =
                ArgumentCaptor.forClass(ReviewRunEntity.class);
        verify(reviewRunRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(ReviewRunEntity.ReviewStatus.RUNNING);
        assertThat(captor.getValue().getProjectId()).isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────
    // completeReviewRun
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("completeReviewRun — обновляет статус SUCCESS, сохраняет группы")
    void completeReviewRun_updatesStatusAndSavesGroups() {
        ReviewRunEntity run = buildRunEntity("run-1");
        when(reviewRunRepository.findById("run-1")).thenReturn(Optional.of(run));

        List<GroupReviewResult> groups = List.of(
                GroupReviewResult.success(0, "Security", "Looks good."),
                GroupReviewResult.success(1, "Performance", "Optimal.")
        );

        service.completeReviewRun("run-1", "## AI Review", groups);

        verify(reviewRunRepository, times(1)).save(run);
        verify(groupResultRepository, times(2)).save(any(ReviewGroupResultEntity.class));
        assertThat(run.getStatus()).isEqualTo(ReviewRunEntity.ReviewStatus.SUCCESS);
        assertThat(run.getFinalCommentMarkdown()).isEqualTo("## AI Review");
    }

    // ─────────────────────────────────────────────────────────
    // failReviewRun
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("failReviewRun — обновляет статус ERROR с сообщением об ошибке")
    void failReviewRun_updatesStatusError() {
        ReviewRunEntity run = buildRunEntity("run-2");
        when(reviewRunRepository.findById("run-2")).thenReturn(Optional.of(run));

        service.failReviewRun("run-2", "network timeout");

        verify(reviewRunRepository).save(run);
        assertThat(run.getStatus()).isEqualTo(ReviewRunEntity.ReviewStatus.ERROR);
        assertThat(run.getErrorMessage()).isEqualTo("network timeout");
    }

    @Test
    @DisplayName("failReviewRun — если runId не найден, метод не бросает исключение")
    void failReviewRun_notFound_noException() {
        when(reviewRunRepository.findById("missing")).thenReturn(Optional.empty());

        // не должно бросить исключение
        service.failReviewRun("missing", "error");

        verify(reviewRunRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────
    // saveLlmMessage
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("saveLlmMessage — сохраняет сообщение LLM с правильными полями")
    void saveLlmMessage_savesCorrectEntity() {
        ReviewRunEntity run = buildRunEntity("run-3");
        when(reviewRunRepository.findById("run-3")).thenReturn(Optional.of(run));

        service.saveLlmMessage("run-3",
                LlmMessageEntity.Stage.REVIEW,
                0,
                LlmMessageEntity.MessageRole.ASSISTANT,
                null,
                "Всё выглядит хорошо.");

        ArgumentCaptor<LlmMessageEntity> captor =
                ArgumentCaptor.forClass(LlmMessageEntity.class);
        verify(llmMessageRepository).save(captor.capture());
        LlmMessageEntity saved = captor.getValue();
        assertThat(saved.getStage()).isEqualTo(LlmMessageEntity.Stage.REVIEW);
        assertThat(saved.getRole()).isEqualTo(LlmMessageEntity.MessageRole.ASSISTANT);
        assertThat(saved.getGroupIndex()).isEqualTo(0);
        assertThat(saved.getContent()).isEqualTo("Всё выглядит хорошо.");
    }

    // ─────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────

    private MergeRequestHookPayload buildPayload(String action) {
        LastCommitInfo commit = new LastCommitInfo("abc123");
        MergeRequestAttributes attrs = new MergeRequestAttributes(
                1L, 10L, "Test MR", "opened", action, "feat", "main", commit, "http://gitlab/mr/10");
        UserInfo user = new UserInfo("testuser");
        ProjectInfo project = new ProjectInfo(42L, "test-project");
        return new MergeRequestHookPayload("merge_request", user, project, attrs);
    }

    private ReviewTriggerCommand buildCommand() {
        return new ReviewTriggerCommand(1L, 10L, "feat", "main", "abc123",
                "Test MR", "http://gitlab/mr/10", "testuser");
    }

    private ReviewRunEntity buildRunEntity(String id) {
        return ReviewRunEntity.builder()
                .id(id)
                .projectId(1L)
                .mrIid(10L)
                .status(ReviewRunEntity.ReviewStatus.RUNNING)
                .startedAt(java.time.Instant.now())
                .build();
    }
}
