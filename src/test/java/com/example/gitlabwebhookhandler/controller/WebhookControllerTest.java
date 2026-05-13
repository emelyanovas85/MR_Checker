package com.example.gitlabwebhookhandler.controller;

import com.example.gitlabwebhookhandler.service.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private WebhookService webhookService;

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

        verify(webhookService).process(eq("Push Hook"), eq("secret"), any());
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
        org.mockito.Mockito.doThrow(new SecurityException("Invalid GitLab webhook token"))
                .when(webhookService).process(any(), any(), any());

        mockMvc.perform(post("/api/webhook/gitlab")
                        .header("X-Gitlab-Event", "Push Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
