package ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab;

import ru.cbr.bugbusters.gitwebhookhandler.service.WebhookEventHandler;

/**
 * Marker interface for GitLab-specific event handlers.
 * Extend WebhookEventHandler with GitLab semantics.
 */
public interface GitLabEventHandler extends WebhookEventHandler {
}
