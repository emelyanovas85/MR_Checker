package ru.cbr.bugbusters.gitwebhookhandler.service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab.PipelineEventHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PipelineEventHandlerTest {

    private PipelineEventHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new PipelineEventHandler();
    }

    @Test
    void shouldSupportPipelineHook() {
        assertThat(handler.supports("Pipeline Hook")).isTrue();
    }

    @Test
    void shouldNotSupportOtherEvents() {
        assertThat(handler.supports("Push Hook")).isFalse();
        assertThat(handler.supports("Issue Hook")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldHandleFullPayload() {
        ObjectNode attrs = mapper.createObjectNode()
                .put("status", "success")
                .put("ref", "main")
                .put("duration", 120);
        ObjectNode project = mapper.createObjectNode().put("name", "my-project");
        ObjectNode payload = mapper.createObjectNode();
        payload.set("object_attributes", attrs);
        payload.set("project", project);

        assertThatCode(() -> handler.handle(payload)).doesNotThrowAnyException();
    }

    @Test
    void shouldHandleEmptyPayloadGracefully() {
        assertThatCode(() -> handler.handle(mapper.createObjectNode())).doesNotThrowAnyException();
    }
}
