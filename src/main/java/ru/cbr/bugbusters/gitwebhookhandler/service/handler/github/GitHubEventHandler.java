package ru.cbr.bugbusters.gitwebhookhandler.service.handler.github;

import ru.cbr.bugbusters.gitwebhookhandler.service.WebhookEventHandler;

/**
 * Marker interface for GitHub-specific event handlers.
 * Extend WebhookEventHandler with GitHub semantics.
 */
public interface GitHubEventHandler extends WebhookEventHandler {
}
