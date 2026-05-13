package ru.cbr.bugbusters.gitwebhookhandler.service;

import ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab.GitLabEventHandler;
import ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab.GitLabWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private GitLabEventHandler handlerA;

    @Mock
    private GitLabEventHandler handlerB;

    private GitLabWebhookService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new GitLabWebhookService(List.of(handlerA, handlerB));
    }

    @Test
    void shouldDelegateToMatchingHandler() {
        ObjectNode payload = mapper.createObjectNode();
        when(handlerA.supports("Push Hook")).thenReturn(true);
        when(handlerB.supports("Push Hook")).thenReturn(false);

        service.process("Push Hook", null, payload);

        verify(handlerA).handle(payload);
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldDelegateToAllMatchingHandlers() {
        ObjectNode payload = mapper.createObjectNode();
        when(handlerA.supports("Push Hook")).thenReturn(true);
        when(handlerB.supports("Push Hook")).thenReturn(true);

        service.process("Push Hook", null, payload);

        verify(handlerA).handle(payload);
        verify(handlerB).handle(payload);
    }

    @Test
    void shouldNotCallAnyHandlerWhenEventTypeIsNull() {
        service.process(null, null, mapper.createObjectNode());
        verify(handlerA, never()).handle(any());
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldNotCallAnyHandlerWhenEventTypeIsBlank() {
        service.process("  ", null, mapper.createObjectNode());
        verify(handlerA, never()).handle(any());
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldThrowSecurityExceptionWhenTokenMismatch() {
        ReflectionTestUtils.setField(service, "secretToken", "correct-token");

        assertThatThrownBy(() -> service.process("Push Hook", "wrong-token", mapper.createObjectNode()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Invalid GitLab webhook token");
    }

    @Test
    void shouldPassWhenTokenMatchesSecret() {
        ReflectionTestUtils.setField(service, "secretToken", "correct-token");
        when(handlerA.supports("Push Hook")).thenReturn(true);

        service.process("Push Hook", "correct-token", mapper.createObjectNode());

        verify(handlerA).handle(any());
    }

    @Test
    void shouldSkipTokenValidationWhenSecretNotConfigured() {
        when(handlerA.supports("Push Hook")).thenReturn(false);
        when(handlerB.supports("Push Hook")).thenReturn(false);

        // должно не бросать исключений
        service.process("Push Hook", "any-token", mapper.createObjectNode());
    }
}
