package ru.cbr.bugbusters.gitwebhookhandler.controller;

import ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab.GitLabWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GitLabWebhookService gitLabWebhookService;

    @Test
    void shouldReturn200AndDelegateToServiceForPushHook() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "ref", "refs/heads/main",
                "user_name", "john.doe",
                "total_commits_count", 3
        ));

        mockMvc.perform(post("/api/webhook/gitlab")
                        .header("X-Gitlab-Event", "Push Hook")
                        .header("X-Gitlab-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook processed"));

        verify(gitLabWebhookService).process(eq("Push Hook"), eq("secret"), any());
    }

    @Test
    void shouldReturn200ForMergeRequestHook() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "object_attributes", Map.of(
                        "title", "Fix bug",
                        "state", "opened",
                        "source_branch", "feature/fix",
                        "target_branch", "main"
                )
        ));

        mockMvc.perform(post("/api/webhook/gitlab")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook processed"));
    }

    @Test
    void shouldReturn200WhenNoEventTypeHeader() throws Exception {
        mockMvc.perform(post("/api/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturn401WhenServiceThrowsSecurityException() throws Exception {
        doThrow(new SecurityException("Invalid GitLab webhook token"))
                .when(gitLabWebhookService).process(any(), any(), any());

        mockMvc.perform(post("/api/webhook/gitlab")
                        .header("X-Gitlab-Event", "Push Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
