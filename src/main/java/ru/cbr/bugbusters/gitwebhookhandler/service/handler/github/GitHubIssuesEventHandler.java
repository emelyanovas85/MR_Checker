package ru.cbr.bugbusters.gitwebhookhandler.service.handler.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubIssuesEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "issues".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        String action = payload.path("action").asText("unknown");
        String title  = payload.path("issue").path("title").asText("unknown");
        String author = payload.path("issue").path("user").path("login").asText("unknown");
        String repo   = payload.path("repository").path("full_name").asText("unknown");

        log.info("[GitHub ISSUES] Repo: {}, Action: {}, Title: '{}', Author: {}",
                repo, action, title, author);

        // TODO: add your business logic here
    }
}
