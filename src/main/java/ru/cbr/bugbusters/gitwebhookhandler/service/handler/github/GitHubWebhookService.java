package ru.cbr.bugbusters.gitwebhookhandler.service.handler.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubWebhookService {

    @Value("${webhook.github.secret-token:}")
    private String secretToken;

    private final List<GitHubEventHandler> handlers;

    public void process(String eventType, String signature, String rawBody, JsonNode payload) {
        validateSignature(signature, rawBody);

        if (eventType == null || eventType.isBlank()) {
            log.warn("[GitHub] Received webhook without event type header");
            return;
        }

        handlers.stream()
                .filter(h -> h.supports(eventType))
                .forEach(h -> {
                    log.info("[GitHub] Handling event '{}' with {}", eventType, h.getClass().getSimpleName());
                    h.handle(payload);
                });
    }

    /**
     * GitHub signs the payload with HMAC-SHA256.
     * Header: X-Hub-Signature-256: sha256=<hex>
     */
    private void validateSignature(String signature, String rawBody) {
        if (secretToken == null || secretToken.isBlank()) return;

        if (signature == null || !signature.startsWith("sha256="))
            throw new SecurityException("Missing or malformed X-Hub-Signature-256 header");

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
            if (!expected.equals(signature)) {
                throw new SecurityException("Invalid GitHub webhook signature");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("Failed to verify GitHub webhook signature");
        }
    }
}
