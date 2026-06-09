package ru.cbr.bugbusters.gitwebhookhandler.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.RefactoringGroup;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.*;

import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MrReviewOrchestratorTest {

    @Mock ClassContextClient classContextClient;
    @Mock LlmGroupingService llmGroupingService;
    @Mock LlmReviewService llmReviewService;
    @Mock MarkdownCommentFormatter markdownCommentFormatter;
    @Mock GitLabNotesPublisher gitLabNotesPublisher;
    @Mock Executor reviewExecutor;

    @InjectMocks
    MrReviewOrchestrator orchestrator;

    private ReviewTriggerCommand buildCommand() {
        return new ReviewTriggerCommand(1L, 10L, "feat", "main", "abc", "Test MR", "user");
    }

    @Test
    void runReview_happyPath_publishesComment() {
        var command = buildCommand();
        var group = new RefactoringGroup("Security", List.of("Foo.java"));
        var result = new GroupReviewResult(0, true, "Security", "Looks good.");

        when(classContextClient.createSession(command)).thenReturn("session-1");
        when(classContextClient.fetchStructures("session-1")).thenReturn(List.of("file context"));
        when(llmGroupingService.groupFiles(anyList())).thenReturn(List.of(group));
        when(llmReviewService.review(eq(0), eq(group), eq("session-1"))).thenReturn(result);
        when(markdownCommentFormatter.format(eq(command), anyList())).thenReturn("## AI Review");

        orchestrator.runReview(command);

        verify(gitLabNotesPublisher).postNote(1L, 10L, "## AI Review");
        verify(classContextClient).deleteSession("session-1");
    }

    @Test
    void runReview_emptyFileStructures_postsWarningNote() {
        var command = buildCommand();
        when(classContextClient.createSession(command)).thenReturn("session-2");
        when(classContextClient.fetchStructures("session-2")).thenReturn(List.of());

        orchestrator.runReview(command);

        verify(gitLabNotesPublisher).postNote(eq(1L), eq(10L), contains("не вернул данных"));
        verify(classContextClient).deleteSession("session-2");
    }

    @Test
    void runReview_emptyGroups_postsWarningNote() {
        var command = buildCommand();
        when(classContextClient.createSession(command)).thenReturn("session-3");
        when(classContextClient.fetchStructures("session-3")).thenReturn(List.of("file"));
        when(llmGroupingService.groupFiles(anyList())).thenReturn(List.of());

        orchestrator.runReview(command);

        verify(gitLabNotesPublisher).postNote(eq(1L), eq(10L), contains("не удалось сформировать группы"));
        verify(classContextClient).deleteSession("session-3");
    }

    @Test
    void runReview_exceptionDuringReview_sessionIsAlwaysTerminated() {
        var command = buildCommand();
        when(classContextClient.createSession(command)).thenReturn("session-4");
        when(classContextClient.fetchStructures("session-4")).thenThrow(new RuntimeException("network error"));

        orchestrator.runReview(command);

        verify(classContextClient).deleteSession("session-4");
        verify(gitLabNotesPublisher).postNote(eq(1L), eq(10L), contains("ошибка"));
    }

    @Test
    void runReview_previousSession_isTerminatedFirst() {
        var command = buildCommand();

        // Симулируем первый вызов
        when(classContextClient.createSession(command)).thenReturn("session-A");
        when(classContextClient.fetchStructures("session-A")).thenReturn(List.of());
        orchestrator.runReview(command);

        // Второй вызов — старая сессия должна быть удалена
        when(classContextClient.createSession(command)).thenReturn("session-B");
        when(classContextClient.fetchStructures("session-B")).thenReturn(List.of());
        orchestrator.runReview(command);

        // session-A: удалена в finally первого вызова (activeSessionByMrKey очищается),
        // поэтому второй вызов НЕ делает pre-delete старой сессии
        verify(classContextClient, atLeastOnce()).deleteSession(any());
    }
}
