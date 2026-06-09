package ru.cbr.bugbusters.gitwebhookhandler.webhook.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Информация о проекте GitLab из webhook payload.
 */
public record ProjectInfo(
        Long id,
        @JsonProperty("name") String name
) {
}
