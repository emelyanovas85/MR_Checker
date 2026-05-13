package ru.cbr.bugbusters.gitwebhookhandler.service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab.MergeRequestEventHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MergeRequestEventHandlerTest {

    private MergeRequestEventHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new MergeRequestEventHandler();
    }

    @Test
    void shouldSupportMergeRequestHook() {
        assertThat(handler.supports("Merge Request Hook")).isTrue();
    }

    @Test
    void shouldNotSupportOtherEvents() {
        assertThat(handler.supports("Push Hook")).isFalse();
        assertThat(handler.supports("Pipeline Hook")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldHandleFullPayload() {
        ObjectNode attrs = mapper.createObjectNode()
                .put("title", "Fix NPE")
                .put("state", "opened")
                .put("source_branch", "feature/fix")
                .put("target_branch", "main");
        ObjectNode user = mapper.createObjectNode().put("name", "bob");
        ObjectNode payload = mapper.createObjectNode();
        payload.set("object_attributes", attrs);
        payload.set("user", user);

        assertThatCode(() -> handler.handle(payload)).doesNotThrowAnyException();
    }

    @Test
    void shouldHandleEmptyPayloadGracefully() {
        assertThatCode(() -> handler.handle(mapper.createObjectNode())).doesNotThrowAnyException();
    }
}
