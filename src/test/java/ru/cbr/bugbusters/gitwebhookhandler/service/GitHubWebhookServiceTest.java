package ru.cbr.bugbusters.gitwebhookhandler.service;

import ru.cbr.bugbusters.gitwebhookhandler.service.handler.github.GitHubEventHandler;
import ru.cbr.bugbusters.gitwebhookhandler.service.handler.github.GitHubWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookServiceTest {

    @Mock
    private GitHubEventHandler handlerA;

    @Mock
    private GitHubEventHandler handlerB;

    private GitHubWebhookService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new GitHubWebhookService(List.of(handlerA, handlerB));
    }

    @Test
    void shouldDelegateToMatchingHandler() {
        ObjectNode payload = mapper.createObjectNode();
        when(handlerA.supports("push")).thenReturn(true);
        when(handlerB.supports("push")).thenReturn(false);

        service.process("push", null, "{}", payload);

        verify(handlerA).handle(payload);
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldNotCallAnyHandlerWhenEventTypeIsNull() {
        service.process(null, null, "{}", mapper.createObjectNode());
        verify(handlerA, never()).handle(any());
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldThrowWhenSignatureMissing() {
        ReflectionTestUtils.setField(service, "secretToken", "my-secret");

        assertThatThrownBy(() -> service.process("push", null, "{}", mapper.createObjectNode()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Missing or malformed");
    }

    @Test
    void shouldThrowWhenSignatureInvalid() {
        ReflectionTestUtils.setField(service, "secretToken", "my-secret");

        assertThatThrownBy(() -> service.process("push", "sha256=wrong", "{}", mapper.createObjectNode()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid GitHub webhook signature");
    }

    @Test
    void shouldPassWhenSignatureValid() throws Exception {
        String secret = "my-secret";
        String body   = "{\"ref\":\"refs/heads/main\"}";
        ReflectionTestUtils.setField(service, "secretToken", secret);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        when(handlerA.supports("push")).thenReturn(true);

        ObjectNode payload = (ObjectNode) mapper.readTree(body);
        service.process("push", sig, body, payload);

        verify(handlerA).handle(payload);
    }

    @Test
    void shouldSkipSignatureValidationWhenSecretNotConfigured() {
        when(handlerA.supports("push")).thenReturn(false);
        when(handlerB.supports("push")).thenReturn(false);

        // должно не бросать исключений
        service.process("push", null, "{}", mapper.createObjectNode());
    }
}
