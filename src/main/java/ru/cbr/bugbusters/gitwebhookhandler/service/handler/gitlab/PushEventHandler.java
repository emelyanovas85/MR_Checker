package ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PushEventHandler implements GitLabEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "Push Hook".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        String ref         = payload.path("ref").asText("unknown");
        String projectName = payload.path("project").path("name").asText("unknown");
        String userName    = payload.path("user_name").asText("unknown");
        int    commitCount = payload.path("total_commits_count").asInt(0);

        log.info("[GitLab PUSH] Project: {}, Branch: {}, Author: {}, Commits: {}",
                projectName, ref, userName, commitCount);

        // TODO: add your business logic here
    }
}
