package ru.cbr.bugbusters.gitwebhookhandler.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.service.ReviewAuditService;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.MrReviewOrchestrator;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.domain.*;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.service.MergeRequestHookHandler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты хендлера Merge Request Hook.
 * Проверяют маршрутизацию по action и интеграцию с аудитом.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MergeRequestHookHandler — unit тесты")
class MergeRequestHookHandlerTest {

    @Mock MrReviewOrchestrator orchestrator;
    @Mock ReviewAuditService   reviewAuditService;
    @Mock ObjectMapper         objectMapper;

    @InjectMocks
    MergeRequestHookHandler handler;

    @Test
    @DisplayName("open/reopen/approved — запускают ревью и сохраняются как ACCEPTED")
    void reviewableActions_triggerReviewAndSavedAsAccepted() {
        for (String action : new String[]{"open", "reopen", "approved"}) {
            MergeRequestHookPayload payload = buildPayload(action, 1L, 10L);

            handler.handle(payload, "{}");

            verify(orchestrator, atLeastOnce()).runReview(any());
            verify(reviewAuditService, atLeastOnce())
                    .saveWebhookEvent(any(), eq("{}"), eq(true));

            reset(orchestrator, reviewAuditService);
        }
    }

    @Test
    @DisplayName("close/merge/update/unapproved — игнорируются, IGNORED в аудит")
    void nonReviewableActions_ignoredAndSavedAsIgnored() {
        for (String action : new String[]{"close", "merge", "update", "unapproved"}) {
            MergeRequestHookPayload payload = buildPayload(action, 1L, 10L);

            handler.handle(payload, "{}");

            verify(orchestrator, never()).runReview(any());
            verify(reviewAuditService, atLeastOnce())
                    .saveWebhookEvent(any(), eq("{}"), eq(false));

            reset(orchestrator, reviewAuditService);
        }
    }

    @Test
    @DisplayName("null payload objectAttributes — игнорируется, IGNORED в аудит")
    void nullObjectAttributes_ignoredAndSavedAsIgnored() {
        MergeRequestHookPayload payload =
                new MergeRequestHookPayload("merge_request", null, null, null);

        handler.handle(payload, "{}");

        verify(orchestrator, never()).runReview(any());
        verify(reviewAuditService).saveWebhookEvent(any(), eq("{}"), eq(false));
    }

    // ───────────────────────── helpers ─────────────────────────

    private MergeRequestHookPayload buildPayload(String action, Long projectId, Long mrIid) {
        LastCommitInfo commit = new LastCommitInfo("abc123");
        MergeRequestAttributes attrs = new MergeRequestAttributes(
                1L, mrIid, "Test MR", "opened", action,
                "feat", "main", commit, "http://gitlab/mr/" + mrIid);
        UserInfo user     = new UserInfo("testuser");
        ProjectInfo project = new ProjectInfo(projectId, "test-project");
        return new MergeRequestHookPayload("merge_request", user, project, attrs);
    }
}
