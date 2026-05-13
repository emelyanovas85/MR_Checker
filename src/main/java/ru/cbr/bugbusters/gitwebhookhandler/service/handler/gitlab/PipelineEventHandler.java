package ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PipelineEventHandler implements GitLabEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "Pipeline Hook".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        JsonNode attrs    = payload.path("object_attributes");
        String   status   = attrs.path("status").asText("unknown");
        String   ref      = attrs.path("ref").asText("unknown");
        long     duration = attrs.path("duration").asLong(0);
        String   project  = payload.path("project").path("name").asText("unknown");

        log.info("[GitLab PIPELINE] Project: {}, Branch: {}, Status: {}, Duration: {}s",
                project, ref, status, duration);

        // TODO: add your business logic here
    }
}
