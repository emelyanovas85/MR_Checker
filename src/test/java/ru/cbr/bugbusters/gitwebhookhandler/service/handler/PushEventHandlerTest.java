package ru.cbr.bugbusters.gitwebhookhandler.service.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.cbr.bugbusters.gitwebhookhandler.service.handlers.gitlab.PushEventHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PushEventHandlerTest {

    private PushEventHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new PushEventHandler();
    }

    @Test
    void shouldSupportPushHook() {
        assertThat(handler.supports("Push Hook")).isTrue();
    }

    @Test
    void shouldNotSupportOtherEvents() {
        assertThat(handler.supports("Issue Hook")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldHandleFullPayload() {
        ObjectNode payload = mapper.createObjectNode()
                .put("ref", "refs/heads/main")
                .put("user_name", "alice")
                .put("total_commits_count", 2);

        assertThatCode(() -> handler.handle(payload)).doesNotThrowAnyException();
    }

    @Test
    void shouldHandleEmptyPayloadGracefully() {
        JsonNode empty = mapper.createObjectNode();
        assertThatCode(() -> handler.handle(empty)).doesNotThrowAnyException();
    }
}
