package ru.cbr.bugbusters.gitwebhookhandler.service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab.PushEventHandler;

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
        assertThat(handler.supports("Merge Request Hook")).isFalse();
        assertThat(handler.supports("Pipeline Hook")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldHandleFullPayload() {
        ObjectNode project = mapper.createObjectNode().put("name", "my-repo");
        ObjectNode payload = mapper.createObjectNode()
                .put("ref", "refs/heads/main")
                .put("user_name", "alice")
                .put("total_commits_count", 5);
        payload.set("project", project);

        assertThatCode(() -> handler.handle(payload)).doesNotThrowAnyException();
    }

    @Test
    void shouldHandleEmptyPayloadGracefully() {
        ObjectNode payload = mapper.createObjectNode();
        assertThatCode(() -> handler.handle(payload)).doesNotThrowAnyException();
    }
}
