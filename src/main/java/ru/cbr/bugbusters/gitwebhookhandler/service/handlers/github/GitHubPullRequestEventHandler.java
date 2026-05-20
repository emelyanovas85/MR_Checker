package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubPullRequestEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "pull_request".equals(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return;
        String action = payload.path("action").asText(null);
        String title = payload.path("pull_request").path("title").asText(null);
        log.info("[GitHub] PR event action={} title={}", action, title);
    }
}
