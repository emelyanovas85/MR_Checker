package ru.cbr.bugbusters.gitwebhookhandler.service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab.IssueEventHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class IssueEventHandlerTest {

    private IssueEventHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new IssueEventHandler();
    }

    @Test
    void shouldSupportIssueHook() {
        assertThat(handler.supports("Issue Hook")).isTrue();
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
                .put("title", "Bug in login")
                .put("action", "open");
        ObjectNode user = mapper.createObjectNode().put("name", "charlie");
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
