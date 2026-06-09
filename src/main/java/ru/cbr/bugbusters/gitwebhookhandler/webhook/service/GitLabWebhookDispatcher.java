package ru.cbr.bugbusters.gitwebhookhandler.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.cbr.bugbusters.gitwebhookhandler.webhook.domain.MergeRequestHookPayload;

/**
 * Диспетчер GitLab webhook-ов.
 *
 * <p>Получает события от webhook-distributor-client и маршрутизирует
 * к нужному хендлеру. Передаёт rawJson в хендлер для сохранения в аудит.
 *
 * @see MergeRequestHookHandler
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabWebhookDispatcher {

    private final ObjectMapper objectMapper;
    private final MergeRequestHookHandler mergeRequestHookHandler;

    /**
     * Диспетчеризует входящее событие к нужному хендлеру.
     *
     * @param eventType  тип события GitLab ("Merge Request Hook", ...)
     * @param rawPayload сырой JSON payload от GitLab
     */
    public void dispatch(String eventType, String rawPayload) {
        try {
            if ("Merge Request Hook".equalsIgnoreCase(eventType)) {
                MergeRequestHookPayload payload =
                        objectMapper.readValue(rawPayload, MergeRequestHookPayload.class);
                mergeRequestHookHandler.handle(payload, rawPayload);
                return;
            }
            log.debug("Игнорируется неподдерживаемое событие GitLab: {}", eventType);
        } catch (Exception e) {
            log.error("Ошибка при диспетчеризации события GitLab event={}", eventType, e);
        }
    }
}
