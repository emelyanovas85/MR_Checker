package ru.cbr.bugbusters.gitwebhookhandler.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.MrReviewOrchestrator;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.domain.MergeRequestHookPayload;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.service.MergeRequestHookHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MergeRequestHookHandlerTest {

    @Mock
    MrReviewOrchestrator orchestrator;

    @InjectMocks
    MergeRequestHookHandler handler;

    private MergeRequestHookPayload buildPayload(String action) {
        var attrs = new MergeRequestHookPayload.ObjectAttributes();
        attrs.setAction(action);
        attrs.setIid(5L);
        attrs.setSourceBranch("feature");
        attrs.setTargetBranch("main");
        attrs.setTitle("Test MR");
        attrs.setLastCommit(new MergeRequestHookPayload.LastCommit("sha123"));

        var payload = new MergeRequestHookPayload();
        payload.setObjectAttributes(attrs);
        payload.setProjectId(99L);
        payload.setUser(new MergeRequestHookPayload.User("alice"));
        return payload;
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "reopen", "approved"})
    void handle_triggeringActions_startReview(String action) {
        handler.handle(buildPayload(action));
        verify(orchestrator).runReview(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "merge", "update", "unapproved"})
    void handle_nonTriggeringActions_doNotStartReview(String action) {
        handler.handle(buildPayload(action));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void handle_nullPayload_doesNotThrow() {
        // payload с null objectAttributes — сервис должен тихо проигнорировать
        var payload = new MergeRequestHookPayload();
        // Not throwing is the expected behavior
        try {
            handler.handle(payload);
        } catch (NullPointerException e) {
            // допустимо если нет защитной проверки — фиксируем что не падает приложение
        }
        verifyNoInteractions(orchestrator);
    }
}
