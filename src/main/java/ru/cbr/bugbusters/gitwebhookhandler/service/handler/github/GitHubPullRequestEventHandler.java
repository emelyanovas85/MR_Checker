package ru.cbr.bugbusters.gitwebhookhandler.service.handler.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubPullRequestEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "pull_request".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        String action     = payload.path("action").asText("unknown");
        String title      = payload.path("pull_request").path("title").asText("unknown");
        String state      = payload.path("pull_request").path("state").asText("unknown");
        String sourceBranch = payload.path("pull_request").path("head").path("ref").asText("unknown");
        String targetBranch = payload.path("pull_request").path("base").path("ref").asText("unknown");
        String author     = payload.path("pull_request").path("user").path("login").asText("unknown");

        log.info("[GitHub PR] Action: {}, Title: '{}', State: {}, {}->{}, Author: {}",
                action, title, state, sourceBranch, targetBranch, author);

        // TODO: add your business logic here
    }
}
