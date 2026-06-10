package ru.cbr.bugbusters.gitwebhookhandler.webhook.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Информация о проекте GitLab из webhook payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectInfo(
        Long id,
        @JsonProperty("name") String name
) {
}
