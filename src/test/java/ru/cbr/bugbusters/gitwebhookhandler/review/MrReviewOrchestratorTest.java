package ru.cbr.bugbusters.gitwebhookhandler.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.service.ReviewAuditService;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.RefactoringGroup;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.*;

import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты оркестратора AI-ревью.
 * Проверяют бизнес-логику без запуска Spring-контекста.
 *
 * <p><b>Важно про Executor:</b> нельзя использовать @Mock для Executor, т.к.
 * MrReviewOrchestrator передаёт его в CompletableFuture.supplyAsync().
 * Mock молча игнорирует execute(runnable), CompletableFuture никогда не завершается,
 * и .join() вешает тест навсегда. Вместо этого используется inline-executor
 * (Runnable::run), который выполняет задачу синхронно в том же потоке.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MrReviewOrchestrator — unit тесты")
class MrReviewOrchestratorTest {

    @Mock ClassContextClient        classContextClient;
    @Mock LlmGroupingService        llmGroupingService;
    @Mock LlmReviewService          llmReviewService;
    @Mock MarkdownCommentFormatter  markdownCommentFormatter;
    @Mock GitLabNotesPublisher      gitLabNotesPublisher;
    @Mock ReviewAuditService        reviewAuditService;

    // НЕ мок — синхронный inline executor, чтобы CompletableFuture.join() не висел
    private final Executor reviewExecutor = Runnable::run;

    private MrReviewOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new MrReviewOrchestrator(
                classContextClient,
                llmGroupingService,
                llmReviewService,
                markdownCommentFormatter,
                gitLabNotesPublisher,
                reviewAuditService,
                reviewExecutor
        );
    }

    /** Создаёт стандартную тестовую команду запуска ревью. */
    private ReviewTriggerCommand buildCommand() {
        return new ReviewTriggerCommand(
                1L, 10L, "feat", "main", "abc123",
                "Test MR", "http://gitlab/mr/10", "testuser");
    }

    @Test
    @DisplayName("Успешный сценарий: публикует комментарий, финализирует ревью")
    void runReview_happyPath_publishesCommentAndCompletesRun() {
        var command = buildCommand();
        var group = new RefactoringGroup(
                "Security",
                "security concern",
                List.of(new RefactoringGroup.GroupFile("Foo.java", "modified", "business logic", null)),
                "improve security",
                "high"
        );
        var result = GroupReviewResult.success(0, "Security", "Looks good.");

        when(reviewAuditService.createReviewRun(command)).thenReturn("run-1");
        when(classContextClient.createSession(command)).thenReturn("session-1");
        when(classContextClient.fetchStructures("session-1")).thenReturn(List.of("file context"));
        when(llmGroupingService.groupFiles(anyList())).thenReturn(List.of(group));
        when(llmReviewService.review(eq(0), eq(group), eq("session-1"))).thenReturn(result);
        when(markdownCommentFormatter.format(eq(command), anyList())).thenReturn("## AI Review");

        orchestrator.runReview(command);

        verify(gitLabNotesPublisher).postNote(1L, 10L, "## AI Review");
        verify(reviewAuditService).completeReviewRun(eq("run-1"), eq("## AI Review"), anyList());
        verify(classContextClient).deleteSession("session-1");
    }

    @Test
    @DisplayName("Пустые файловые структуры: публикует предупреждение, статус ERROR")
    void runReview_emptyFileStructures_postsWarningNoteAndFailsRun() {
        var command = buildCommand();
        when(reviewAuditService.createReviewRun(command)).thenReturn("run-2");
        when(classContextClient.createSession(command)).thenReturn("session-2");
        when(classContextClient.fetchStructures("session-2")).thenReturn(List.of());

        orchestrator.runReview(command);

        verify(gitLabNotesPublisher).postNote(eq(1L), eq(10L), contains("не вернул данных"));
        verify(reviewAuditService).failReviewRun(eq("run-2"), anyString());
        verify(classContextClient).deleteSession("session-2");
    }

    @Test
    @DisplayName("Пустые группы: публикует предупреждение, статус ERROR")
    void runReview_emptyGroups_postsWarningNoteAndFailsRun() {
        var command = buildCommand();
        when(reviewAuditService.createReviewRun(command)).thenReturn("run-3");
        when(classContextClient.createSession(command)).thenReturn("session-3");
        when(classContextClient.fetchStructures("session-3")).thenReturn(List.of("file"));
        when(llmGroupingService.groupFiles(anyList())).thenReturn(List.of());

        orchestrator.runReview(command);

        verify(gitLabNotesPublisher).postNote(eq(1L), eq(10L), contains("не удалось сформировать группы"));
        verify(reviewAuditService).failReviewRun(eq("run-3"), anyString());
        verify(classContextClient).deleteSession("session-3");
    }

    @Test
    @DisplayName("Исключение в процессе: сессия всегда терминируется, статус ERROR")
    void runReview_exceptionDuringReview_sessionTerminatedAndRunFailed() {
        var command = buildCommand();
        when(reviewAuditService.createReviewRun(command)).thenReturn("run-4");
        when(classContextClient.createSession(command)).thenReturn("session-4");
        when(classContextClient.fetchStructures("session-4"))
                .thenThrow(new RuntimeException("network error"));

        orchestrator.runReview(command);

        verify(classContextClient).deleteSession("session-4");
        verify(reviewAuditService).failReviewRun(eq("run-4"), contains("network error"));
        verify(gitLabNotesPublisher).postNote(eq(1L), eq(10L), contains("ошибка"));
    }

    @Test
    @DisplayName("Повторный webhook: предыдущая сессия терминируется перед созданием новой")
    void runReview_previousSession_isTerminatedFirst() {
        var command = buildCommand();

        when(reviewAuditService.createReviewRun(command))
                .thenReturn("run-A", "run-B");
        when(classContextClient.createSession(command))
                .thenReturn("session-A", "session-B");
        when(classContextClient.fetchStructures(any()))
                .thenReturn(List.of());

        orchestrator.runReview(command); // создаёт session-A
        orchestrator.runReview(command); // создаёт session-B

        // Обе сессии должны быть удалены в finally
        verify(classContextClient, atLeastOnce()).deleteSession(any());
    }
}
