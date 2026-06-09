package ru.cbr.bugbusters.gitwebhookhandler.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.RefactoringGroup;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.LlmGroupingService;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.LlmRateLimiter;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты LlmGroupingService.
 * LlmRateLimiter.acquire() мокируется через MockedStatic — тесты не ждут 2.2с.
 */
@ExtendWith(MockitoExtension.class)
class LlmGroupingServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.ChatClientRequestSpec userSpec;
    @Mock
    private ChatClient.CallResponseSpec callSpec;

    private LlmGroupingService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new LlmGroupingService(chatClientBuilder, new ObjectMapper());
        // Инжекция промпта через рефлексию (обход @PostConstruct / @Value)
        Field promptField = LlmGroupingService.class.getDeclaredField("groupingPrompt");
        promptField.setAccessible(true);
        promptField.set(service, "Group these files");

        Field resourceField = LlmGroupingService.class.getDeclaredField("groupingPromptResource");
        resourceField.setAccessible(true);
        resourceField.set(service, new ByteArrayResource("Group these files".getBytes()));
    }

    /** Настраивает цепочку мок-вызовов ChatClient и отключает rate-limiter на время теста. */
    private MockedStatic<LlmRateLimiter> mockLlmResponse(String json) {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(json);

        MockedStatic<LlmRateLimiter> rateLimiterMock = mockStatic(LlmRateLimiter.class);
        rateLimiterMock.when(LlmRateLimiter::acquire).thenAnswer(inv -> null); // не ждём
        return rateLimiterMock;
    }

    @Test
    void groupFiles_parsesJsonResponse() {
        String json = """
                {
                  "refactoring_groups": [
                    {
                      "group_name": "Auth",
                      "reason": "authentication logic",
                      "files": [
                        {"path": "AuthService.java", "status": "modified", "file_type": "service", "responsibility": "auth"}
                      ],
                      "refactoring_goal": "improve security",
                      "priority": "high"
                    },
                    {
                      "group_name": "DB",
                      "reason": "data layer",
                      "files": [
                        {"path": "UserRepo.java", "status": "added", "file_type": "repository", "responsibility": "persistence"}
                      ],
                      "refactoring_goal": "optimize queries",
                      "priority": "medium"
                    }
                  ]
                }
                """;
        try (MockedStatic<LlmRateLimiter> ignored = mockLlmResponse(json)) {
            List<RefactoringGroup> groups = service.groupFiles(List.of("file1 content"));

            assertThat(groups).hasSize(2);
            assertThat(groups.get(0).groupName()).isEqualTo("Auth");
            assertThat(groups.get(1).groupName()).isEqualTo("DB");
            assertThat(groups.get(0).files()).hasSize(1);
            assertThat(groups.get(0).files().get(0).path()).isEqualTo("AuthService.java");
        }
    }

    @Test
    void groupFiles_parsesJsonWrappedInMarkdownFence() {
        String response = """
                Sure! Here is the grouping:
                ```json
                {
                  "refactoring_groups": [{
                    "group_name": "Core",
                    "reason": "core logic",
                    "files": [{"path": "Main.java", "status": "modified", "file_type": "main", "responsibility": "entry point"}],
                    "refactoring_goal": "cleanup",
                    "priority": "low"
                  }]
                }
                ```
                """;
        try (MockedStatic<LlmRateLimiter> ignored = mockLlmResponse(response)) {
            List<RefactoringGroup> groups = service.groupFiles(List.of("some file"));

            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).groupName()).isEqualTo("Core");
            assertThat(groups.get(0).files().get(0).path()).isEqualTo("Main.java");
        }
    }

    @Test
    void groupFiles_emptyInput_returnsEmptyList() {
        // RateLimiter вообще не должен вызываться — нет смысла мокировать
        List<RefactoringGroup> groups = service.groupFiles(List.of());
        assertThat(groups).isEmpty();
        verifyNoInteractions(chatClientBuilder);
    }

    @Test
    void groupFiles_emptyLlmResponse_returnsEmptyList() {
        try (MockedStatic<LlmRateLimiter> ignored = mockLlmResponse("")) {
            List<RefactoringGroup> groups = service.groupFiles(List.of("file content"));
            assertThat(groups).isEmpty();
        }
    }

    @Test
    void groupFiles_invalidJson_returnsEmptyList() {
        try (MockedStatic<LlmRateLimiter> ignored = mockLlmResponse("not a json at all")) {
            List<RefactoringGroup> groups = service.groupFiles(List.of("file content"));
            assertThat(groups).isEmpty();
        }
    }
}
