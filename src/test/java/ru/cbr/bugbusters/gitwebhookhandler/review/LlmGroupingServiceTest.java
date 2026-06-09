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

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
        // Inject grouping prompt via reflection (bypasses @PostConstruct / @Value)
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
        String json = """
                {
                  "refactoring_groups": [
                    {"groupName": "Auth", "files": ["AuthService.java"]},
                    {"groupName": "DB",   "files": ["UserRepo.java"]}
                  ]
                }
                """;
        mockLlmResponse(json);

        List<RefactoringGroup> groups = service.groupFiles(List.of("file1 content"));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).groupName()).isEqualTo("Auth");
        assertThat(groups.get(1).groupName()).isEqualTo("DB");
    }

    @Test
    void groupFiles_parsesJsonWrappedInMarkdownFence() {
        String response = """
                Sure! Here is the grouping:
                ```json
                {"refactoring_groups": [{"groupName": "Core", "files": ["Main.java"]}]}
                ```
                """;
        mockLlmResponse(response);

        List<RefactoringGroup> groups = service.groupFiles(List.of("some file"));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).groupName()).isEqualTo("Core");
    }

    @Test
    void groupFiles_emptyInput_returnsEmptyList() {
        List<RefactoringGroup> groups = service.groupFiles(List.of());
        assertThat(groups).isEmpty();
        verifyNoInteractions(chatClientBuilder);
    }

    @Test
    void groupFiles_emptyLlmResponse_returnsEmptyList() {
        mockLlmResponse("");
        List<RefactoringGroup> groups = service.groupFiles(List.of("file content"));
        assertThat(groups).isEmpty();
    }

    @Test
    void groupFiles_invalidJson_returnsEmptyList() {
        mockLlmResponse("not a json at all");
        List<RefactoringGroup> groups = service.groupFiles(List.of("file content"));
        assertThat(groups).isEmpty();
    }
}
