package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IssueEventHandler implements GitLabEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "Issue Hook".equals(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return;
        JsonNode attrs = payload.path("object_attributes");
        String title = attrs.path("title").asText(null);
        String action = attrs.path("action").asText(null);
        String userName = payload.path("user").path("name").asText(null);
        log.info("[GitLab] Issue event action={} title='{}' by {}", action, title, userName);
    }
}
