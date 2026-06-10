package ru.cbr.bugbusters.gitwebhookhandler.webhook.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload вебхука "Merge Request Hook" от GitLab.
 * Приходит через webhook-distributor-client на эндпоинт /api/v1/webhooks/gitlab.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MergeRequestHookPayload(
        @JsonProperty("object_kind") String objectKind,
        UserInfo user,
        @JsonProperty("project") ProjectInfo project,
        @JsonProperty("object_attributes") MergeRequestAttributes objectAttributes
) {
    public Long projectId() {
        return project != null ? project.id() : null;
    }
}
