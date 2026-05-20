package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubWorkflowRunEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "workflow_run".equals(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return;
        String name = payload.path("workflow").path("name").asText(null);
        String conclusion = payload.path("workflow_run").path("conclusion").asText(null);
        log.info("[GitHub] Workflow '{}' finished with {}", name, conclusion);
    }
}
