package ru.cbr.bugbusters.gitwebhookhandler.service.handler.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubWorkflowRunEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "workflow_run".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        String action  = payload.path("action").asText("unknown");
        String name    = payload.path("workflow_run").path("name").asText("unknown");
        String status  = payload.path("workflow_run").path("status").asText("unknown");
        String conclusion = payload.path("workflow_run").path("conclusion").asText("unknown");
        String branch  = payload.path("workflow_run").path("head_branch").asText("unknown");
        String repo    = payload.path("repository").path("full_name").asText("unknown");

        log.info("[GitHub WORKFLOW_RUN] Repo: {}, Workflow: '{}', Branch: {}, Status: {}, Conclusion: {}, Action: {}",
                repo, name, branch, status, conclusion, action);

        // TODO: add your business logic here
    }
}
