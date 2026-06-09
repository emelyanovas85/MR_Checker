package ru.cbr.bugbusters.gitwebhookhandler.webhook.domain;

/**
 * Информация о последнем коммите в MR из webhook payload.
 */
public record LastCommitInfo(
        String id
) {
}
