package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubPushEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "push".equals(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return;
        String ref = payload.path("ref").asText(null);
        String pusher = payload.path("pusher").path("name").asText(null);
        log.info("[GitHub] Push event on ref={} by {}", ref, pusher);
    }
}
