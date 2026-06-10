package ru.cbr.bugbusters.gitwebhookhandler.webhook.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Информация о пользователе GitLab из webhook payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserInfo(
        Long id,
        String username
) {
}
