package com.example.gitlabwebhookhandler.service;

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

    private WebhookService webhookService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        webhookService = new WebhookService(List.of(handlerA, handlerB));
    }

    @Test
    void shouldDelegateToMatchingHandler() {
        ObjectNode payload = mapper.createObjectNode();
        when(handlerA.supports("Push Hook")).thenReturn(true);
        when(handlerB.supports("Push Hook")).thenReturn(false);

        webhookService.process("Push Hook", null, payload);

        verify(handlerA).handle(payload);
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldDelegateToAllMatchingHandlers() {
        ObjectNode payload = mapper.createObjectNode();
        when(handlerA.supports("Push Hook")).thenReturn(true);
        when(handlerB.supports("Push Hook")).thenReturn(true);

        webhookService.process("Push Hook", null, payload);

        verify(handlerA).handle(payload);
        verify(handlerB).handle(payload);
    }

    @Test
    void shouldNotCallAnyHandlerWhenEventTypeIsNull() {
        ObjectNode payload = mapper.createObjectNode();

        webhookService.process(null, null, payload);

        verify(handlerA, never()).handle(any());
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldNotCallAnyHandlerWhenEventTypeIsBlank() {
        ObjectNode payload = mapper.createObjectNode();

        webhookService.process("  ", null, payload);

        verify(handlerA, never()).handle(any());
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldThrowSecurityExceptionWhenTokenMismatch() {
        ReflectionTestUtils.setField(webhookService, "secretToken", "correct-token");
        ObjectNode payload = mapper.createObjectNode();

        assertThatThrownBy(() -> webhookService.process("Push Hook", "wrong-token", payload))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Invalid GitLab webhook token");
    }

    @Test
    void shouldPassWhenTokenMatchesSecret() {
        ReflectionTestUtils.setField(webhookService, "secretToken", "correct-token");
        ObjectNode payload = mapper.createObjectNode();
        when(handlerA.supports("Push Hook")).thenReturn(true);

        webhookService.process("Push Hook", "correct-token", payload);

        verify(handlerA).handle(payload);
    }

    @Test
    void shouldSkipTokenValidationWhenSecretNotConfigured() {
        // secretToken is empty by default
        ObjectNode payload = mapper.createObjectNode();
        when(handlerA.supports("Push Hook")).thenReturn(false);
        when(handlerB.supports("Push Hook")).thenReturn(false);

        // Should not throw
        webhookService.process("Push Hook", "any-token", payload);
    }
}
