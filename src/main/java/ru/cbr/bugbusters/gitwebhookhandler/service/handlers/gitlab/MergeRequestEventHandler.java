package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MergeRequestEventHandler implements GitLabEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "Merge Request Hook".equals(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return;
        JsonNode attrs = payload.path("object_attributes");
        String title = attrs.path("title").asText(null);
        String state = attrs.path("state").asText(null);
        String sourceBranch = attrs.path("source_branch").asText(null);
        String targetBranch = attrs.path("target_branch").asText(null);
        log.info("[GitLab] MR event state={} title='{}' {}→{}", state, title, sourceBranch, targetBranch);
    }
}
