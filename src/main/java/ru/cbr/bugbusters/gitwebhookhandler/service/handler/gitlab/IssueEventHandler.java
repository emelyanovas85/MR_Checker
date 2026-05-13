package ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IssueEventHandler implements GitLabEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "Issue Hook".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        JsonNode attrs  = payload.path("object_attributes");
        String   title  = attrs.path("title").asText("unknown");
        String   action = attrs.path("action").asText("unknown");
        String   author = payload.path("user").path("name").asText("unknown");

        log.info("[GitLab ISSUE] Action: {}, Title: '{}', Author: {}", action, title, author);

        // TODO: add your business logic here
    }
}
