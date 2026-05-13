package ru.cbr.bugbusters.gitwebhookhandler.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Common interface for all webhook event handlers.
 * Implementations are auto-discovered by Spring via @Component.
 *
 * @param <S> the type of source/platform discriminator (e.g. String event type)
 */
public interface WebhookEventHandler {

    /**
     * Returns true if this handler can process the given event type.
     */
    boolean supports(String eventType);

    /**
     * Processes the event payload.
     */
    void handle(JsonNode payload);
}
