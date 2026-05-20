package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PipelineEventHandler implements GitLabEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "Pipeline Hook".equals(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return;
        JsonNode attrs = payload.path("object_attributes");
        long pipelineId = attrs.path("id").asLong(0);
        String status = attrs.path("status").asText(null);
        log.info("[GitLab] Pipeline event id={} status={}", pipelineId, status);
    }
}
