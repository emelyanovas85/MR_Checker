package ru.cbr.bugbusters.gitwebhookhandler.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.cbr.bugbusters.gitwebhookhandler.common.config.AppProperties;
import ru.cbr.bugbusters.gitwebhookhandler.controllers.GitLabWebhookController;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.service.GitLabWebhookDispatcher;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GitLabWebhookController.class)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GitLabWebhookDispatcher dispatcher;

    @MockitoBean
    private AppProperties appProperties;

    @Test
    void shouldReturn202AndDelegateForNoteHook() throws Exception {
        AppProperties.GitLabProperties gitlabProps = new AppProperties.GitLabProperties(
                "http://localhost", "token", "changeme");
        when(appProperties.gitlab()).thenReturn(gitlabProps);

        String payload = objectMapper.writeValueAsString(Map.of(
                "object_kind", "note",
                "event_type", "note"
        ));

        mockMvc.perform(post("/api/v1/webhooks/gitlab")
                        .header("X-Gitlab-Event", "Note Hook")
                        .header("X-Gitlab-Token", "changeme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());

        verify(dispatcher).dispatch(eq("Note Hook"), any());
    }

    @Test
    void shouldReturn403WhenTokenInvalid() throws Exception {
        AppProperties.GitLabProperties gitlabProps = new AppProperties.GitLabProperties(
                "http://localhost", "token", "changeme");
        when(appProperties.gitlab()).thenReturn(gitlabProps);

        mockMvc.perform(post("/api/v1/webhooks/gitlab")
                        .header("X-Gitlab-Event", "Note Hook")
                        .header("X-Gitlab-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn403WhenTokenMissing() throws Exception {
        AppProperties.GitLabProperties gitlabProps = new AppProperties.GitLabProperties(
                "http://localhost", "token", "changeme");
        when(appProperties.gitlab()).thenReturn(gitlabProps);

        mockMvc.perform(post("/api/v1/webhooks/gitlab")
                        .header("X-Gitlab-Event", "Note Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
