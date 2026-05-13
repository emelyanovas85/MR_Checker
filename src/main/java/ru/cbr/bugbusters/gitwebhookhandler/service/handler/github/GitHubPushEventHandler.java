package ru.cbr.bugbusters.gitwebhookhandler.service.handler.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubPushEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "push".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        String ref        = payload.path("ref").asText("unknown");
        String repoName   = payload.path("repository").path("full_name").asText("unknown");
        String pusher     = payload.path("pusher").path("name").asText("unknown");
        int    commits    = payload.path("commits").size();

        log.info("[GitHub PUSH] Repo: {}, Branch: {}, Pusher: {}, Commits: {}",
                repoName, ref, pusher, commits);

        // TODO: add your business logic here
    }
}
