package ru.cbr.bugbusters.gitwebhookhandler.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.WebhookEventEntity;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.repository.WebhookEventRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты репозитория webhook-событий.
 * Используют in-memory H2 через @DataJpaTest (профиль test).
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("WebhookEventRepository — JPA тесты")
class WebhookEventRepositoryTest {

    @Autowired
    private WebhookEventRepository repository;

    @Test
    @DisplayName("Сохранение и поиск события по projectId и mrIid")
    void save_andFindByProjectIdAndMrIid() {
        WebhookEventEntity event = buildEvent(100L, 5L, WebhookEventEntity.ProcessingStatus.ACCEPTED);
        repository.save(event);

        List<WebhookEventEntity> found =
                repository.findByProjectIdAndMrIidOrderByReceivedAtDesc(100L, 5L);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getAction()).isEqualTo("open");
        assertThat(found.get(0).getProcessingStatus())
                .isEqualTo(WebhookEventEntity.ProcessingStatus.ACCEPTED);
    }

    @Test
    @DisplayName("Поиск по projectId/mrIid — события других MR не возвращаются")
    void findByProjectIdAndMrIid_differentMr_notReturned() {
        repository.save(buildEvent(1L, 1L, WebhookEventEntity.ProcessingStatus.ACCEPTED));
        repository.save(buildEvent(1L, 2L, WebhookEventEntity.ProcessingStatus.IGNORED));

        List<WebhookEventEntity> result =
                repository.findByProjectIdAndMrIidOrderByReceivedAtDesc(1L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMrIid()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Поиск событий после заданного времени")
    void findByReceivedAtAfter_returnsOnlyNewer() {
        Instant past   = Instant.now().minusSeconds(120);
        Instant recent = Instant.now().minusSeconds(10);

        WebhookEventEntity old = buildEventWithTime(200L, 1L, past);
        WebhookEventEntity fresh = buildEventWithTime(200L, 2L, recent);
        repository.saveAll(List.of(old, fresh));

        List<WebhookEventEntity> result =
                repository.findByReceivedAtAfterOrderByReceivedAtDesc(
                        Instant.now().minusSeconds(60));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMrIid()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Сохранение события с rawPayload — данные не теряются")
    void save_withRawPayload_persistsCorrectly() {
        String json = "{\"object_kind\":\"merge_request\"}";
        WebhookEventEntity event = buildEvent(300L, 7L, WebhookEventEntity.ProcessingStatus.ACCEPTED);
        event.setRawPayload(json);
        repository.save(event);

        WebhookEventEntity loaded = repository.findById(event.getId()).orElseThrow();
        assertThat(loaded.getRawPayload()).isEqualTo(json);
    }

    // ─────────────────────── helpers ─────────────────────────

    private WebhookEventEntity buildEvent(Long projectId, Long mrIid,
                                          WebhookEventEntity.ProcessingStatus status) {
        return buildEventWithTime(projectId, mrIid, Instant.now());
    }

    private WebhookEventEntity buildEventWithTime(Long projectId, Long mrIid, Instant receivedAt) {
        return WebhookEventEntity.builder()
                .id(UUID.randomUUID().toString())
                .eventType("Merge Request Hook")
                .action("open")
                .projectId(projectId)
                .mrIid(mrIid)
                .receivedAt(receivedAt)
                .processingStatus(WebhookEventEntity.ProcessingStatus.ACCEPTED)
                .build();
    }
}
