package ru.cbr.bugbusters.gitwebhookhandler.webhook.domain;

/**
 * Информация о пользователе GitLab из webhook payload.
 */
public record UserInfo(
        String username
) {
}
