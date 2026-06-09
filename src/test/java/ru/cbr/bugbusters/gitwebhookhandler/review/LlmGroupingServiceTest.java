package ru.cbr.bugbusters.gitwebhookhandler.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
 * LlmRateLimiter инжектируется как @Mock (теперь это Spring-бин) —
 * acquire() является no-op, тесты не ждут 2.2с.
 */
@ExtendWith(MockitoExtension.class)
class LlmGroupingServiceTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.ChatClientRequestSpec userSpec;
    @Mock private ChatClient.CallResponseSpec callSpec;
    @Mock private LlmRateLimiter rateLimiter; // no-op по умолчанию

    private LlmGroupingService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new LlmGroupingService(chatClientBuilder, new ObjectMapper(), rateLimiter);

        Field promptField = LlmGroupingService.class.getDeclaredField("groupingPrompt");
        promptField.setAccessible(true);
        promptField.set(service, "Group these files");

        Field resourceField = LlmGroupingService.class.getDeclaredField("groupingPromptResource");
        resourceField.setAccessible(true);
        resourceField.set(service, new ByteArrayResource("Group these files".getBytes()));
    }

    private void mockLlmResponse(String json) {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(userSpec);
        when(userSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(json);
    }

    @Test
    void groupFiles_parsesJsonResponse() {
        mockLlmResponse("""
                {
                  "refactoring_groups": [
                    {
                      "group_name": "Auth", "reason": "auth logic",
                      "files": [{"path": "AuthService.java", "status": "modified", "file_type": "service", "responsibility": "auth"}],
                      "refactoring_goal": "improve security", "priority": "high"
                    },
                    {
                      "group_name": "DB", "reason": "data layer",
                      "files": [{"path": "UserRepo.java", "status": "added", "file_type": "repository", "responsibility": "persistence"}],
                      "refactoring_goal": "optimize queries", "priority": "medium"
                    }
                  ]
                }
                """);

        List<RefactoringGroup> groups = service.groupFiles(List.of("file1 content"));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).groupName()).isEqualTo("Auth");
        assertThat(groups.get(1).groupName()).isEqualTo("DB");
        assertThat(groups.get(0).files().get(0).path()).isEqualTo("AuthService.java");
        verify(rateLimiter).acquire();
    }

    @Test
    void groupFiles_parsesJsonWrappedInMarkdownFence() {
        mockLlmResponse("""
                Sure!
                ```json
                {
                  "refactoring_groups": [{
                    "group_name": "Core", "reason": "core",
                    "files": [{"path": "Main.java", "status": "modified", "file_type": "main", "responsibility": "entry"}],
                    "refactoring_goal": "cleanup", "priority": "low"
                  }]
                }
                ```
                """);

        List<RefactoringGroup> groups = service.groupFiles(List.of("some file"));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).groupName()).isEqualTo("Core");
        assertThat(groups.get(0).files().get(0).path()).isEqualTo("Main.java");
        verify(rateLimiter).acquire();
    }

    @Test
    void groupFiles_emptyInput_returnsEmptyList() {
        List<RefactoringGroup> groups = service.groupFiles(List.of());
        assertThat(groups).isEmpty();
        verifyNoInteractions(chatClientBuilder, rateLimiter);
    }

    @Test
    void groupFiles_emptyLlmResponse_returnsEmptyList() {
        mockLlmResponse("");
        assertThat(service.groupFiles(List.of("file"))).isEmpty();
        verify(rateLimiter).acquire();
    }

    @Test
    void groupFiles_invalidJson_returnsEmptyList() {
        mockLlmResponse("not a json");
        assertThat(service.groupFiles(List.of("file"))).isEmpty();
        verify(rateLimiter).acquire();
    }
}
