package ru.cbr.bugbusters.gitwebhookhandler.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.cbr.bugbusters.gitwebhookhandler.controllers.GitHubWebhookController;
import ru.cbr.bugbusters.gitwebhookhandler.service.handlers.github.GitHubWebhookService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GitHubWebhookController.class)
class GitHubWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GitHubWebhookService gitHubWebhookService;

    private static final String PAYLOAD = "{\"ref\":\"refs/heads/main\",\"pusher\":{\"name\":\"alice\"}}";

    @Test
    void shouldReturn200AndDelegateForPushEvent() throws Exception {
        mockMvc.perform(post("/api/webhook/github")
                        .header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", "sha256=abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook processed"));

        verify(gitHubWebhookService).process(eq("push"), eq("sha256=abc"), eq(PAYLOAD), any());
    }

    @Test
    void shouldReturn200WhenNoEventTypeHeader() throws Exception {
        mockMvc.perform(post("/api/webhook/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturn401WhenSignatureInvalid() throws Exception {
        doThrow(new SecurityException("Invalid GitHub webhook signature"))
                .when(gitHubWebhookService).process(any(), any(), any(), any());

        mockMvc.perform(post("/api/webhook/github")
                        .header("X-GitHub-Event", "push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn400ForInvalidJson() throws Exception {
        mockMvc.perform(post("/api/webhook/github")
                        .header("X-GitHub-Event", "push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }
}
