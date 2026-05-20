package ru.cbr.bugbusters.gitwebhookhandler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.cbr.bugbusters.gitwebhookhandler.service.handlers.github.GitHubEventHandler;
import ru.cbr.bugbusters.gitwebhookhandler.service.handlers.github.GitHubWebhookService;

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
    void shouldDelegateToMatchingHandler() throws Exception {
        JsonNode payload = mapper.createObjectNode();
        when(handlerA.supports("push")).thenReturn(true);
        when(handlerB.supports("push")).thenReturn(false);

        service.process("push", null, "{}", payload);

        verify(handlerA).handle(payload);
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldNotCallAnyHandlerWhenEventTypeIsNull() {
        JsonNode payload = mapper.createObjectNode();
        service.process(null, null, "{}", payload);
        verify(handlerA, never()).handle(any());
        verify(handlerB, never()).handle(any());
    }

    @Test
    void shouldThrowWhenSignatureMissing() {
        ReflectionTestUtils.setField(service, "secretToken", "my-secret");
        JsonNode payload = mapper.createObjectNode();

        assertThatThrownBy(() -> service.process("push", null, "{}", payload))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Missing or malformed");
    }

    @Test
    void shouldThrowWhenSignatureInvalid() {
        ReflectionTestUtils.setField(service, "secretToken", "my-secret");
        JsonNode payload = mapper.createObjectNode();

        assertThatThrownBy(() -> service.process("push", "sha256=wrong", "{}", payload))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid GitHub webhook signature");
    }

    @Test
    void shouldPassWhenSignatureValid() throws Exception {
        String secret = "my-secret";
        String body = "{\"ref\":\"refs/heads/main\"}";
        ReflectionTestUtils.setField(service, "secretToken", secret);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        when(handlerA.supports("push")).thenReturn(true);

        JsonNode payload = mapper.readTree(body);
        service.process("push", sig, body, payload);

        verify(handlerA).handle(payload);
    }

    @Test
    void shouldSkipSignatureValidationWhenSecretNotConfigured() {
        when(handlerA.supports("push")).thenReturn(false);
        when(handlerB.supports("push")).thenReturn(false);
        JsonNode payload = mapper.createObjectNode();

        service.process("push", null, "{}", payload);
    }
}
