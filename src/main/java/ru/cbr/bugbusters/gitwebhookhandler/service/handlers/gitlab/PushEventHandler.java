package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PushEventHandler implements GitLabEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "Push Hook".equals(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return;
        String ref = payload.path("ref").asText(null);
        String userName = payload.path("user_name").asText(null);
        int totalCommits = payload.path("total_commits_count").asInt(0);
        log.info("[GitLab] Push event on ref={} by {} ({} commits)", ref, userName, totalCommits);
    }
}
