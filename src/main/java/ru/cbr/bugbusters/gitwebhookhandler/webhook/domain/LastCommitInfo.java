package ru.cbr.bugbusters.gitwebhookhandler.webhook.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Информация о последнем коммите в MR из webhook payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LastCommitInfo(
        String id,
        String message,
        String title,
        String url
) {
}
