package ru.cbr.bugbusters.gitwebhookhandler.service.handlers.github;

import com.fasterxml.jackson.databind.JsonNode;
import ru.cbr.bugbusters.gitwebhookhandler.service.WebhookEventHandler;

public interface GitHubEventHandler extends WebhookEventHandler {

    @Override
    void handle(JsonNode payload);
}
