package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubIssuesEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "issues".equals(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return;
        String action = payload.path("action").asText(null);
        String title = payload.path("issue").path("title").asText(null);
        log.info("[GitHub] Issue event action={} title={}", action, title);
    }
}
