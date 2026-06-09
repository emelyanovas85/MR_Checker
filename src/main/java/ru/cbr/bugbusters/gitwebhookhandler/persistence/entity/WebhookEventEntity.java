package ru.cbr.bugbusters.gitwebhookhandler.persistence.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * JPA-сущность аудита входящего GitLab webhook-события.
 *
 * <p>Сохраняется при каждом получении события от webhook-distributor,
 * до запуска бизнес-логики. Позволяет отслеживать все входящие события,
 * в том числе те, по которым ревью не запускалось (неподдерживаемый action).
 *
 * <p>Используется для:
 * <ul>
 *   <li>отладки и анализа входящего трафика;</li>
 *   <li>replay событий при ручном разборе инцидентов;</li>
 *   <li>будущего UI — экрана «Входящие события».</li>
 * </ul>
 *
 * @see ru.cbr.bugbusters.gitwebhookhandler.persistence.service.ReviewAuditService
 */
@Entity
@Table(name = "webhook_events", indexes = {
        @Index(name = "idx_we_event_type",  columnList = "event_type"),
        @Index(name = "idx_we_project_mr",  columnList = "project_id, mr_iid"),
        @Index(name = "idx_we_received_at", columnList = "received_at"),
        @Index(name = "idx_we_status",      columnList = "processing_status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Аудит входящего GitLab webhook-события")
public class WebhookEventEntity {

    /** UUID события, генерируется при получении. */
    @Id
    @Column(nullable = false, length = 36)
    @Schema(description = "UUID события")
    private String id;

    /** Тип события GitLab, например 'Merge Request Hook'. */
    @Column(name = "event_type", length = 128)
    @Schema(description = "Тип события GitLab", example = "Merge Request Hook")
    private String eventType;

    /** Action из object_attributes, например 'open', 'approved'. */
    @Column(name = "action", length = 64)
    @Schema(description = "Action из object_attributes", example = "open")
    private String action;

    /** ID проекта GitLab. */
    @Column(name = "project_id")
    @Schema(description = "ID проекта GitLab")
    private Long projectId;

    /** IID merge request в проекте. */
    @Column(name = "mr_iid")
    @Schema(description = "IID merge request")
    private Long mrIid;

    /** SHA последнего коммита в MR. */
    @Column(name = "last_commit", length = 64)
    @Schema(description = "SHA последнего коммита в MR")
    private String lastCommit;

    /** Временная метка получения события сервисом. */
    @Column(name = "received_at", nullable = false)
    @Schema(description = "Время получения события сервисом")
    private Instant receivedAt;

    /**
     * Статус обработки события.
     * ACCEPTED — передано на ревью, IGNORED — пропущено (неподдерживаемый action).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", length = 16, nullable = false)
    @Schema(description = "Статус обработки: ACCEPTED или IGNORED")
    private ProcessingStatus processingStatus;

    /** Полный JSON payload от GitLab (для replay и отладки). */
    @Lob
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    @Schema(description = "Полный JSON payload от GitLab")
    private String rawPayload;

    /**
     * Статус обработки входящего webhook-события.
     */
    public enum ProcessingStatus {
        /** Событие передано на запуск ревью. */
        ACCEPTED,
        /** Событие проигнорировано (неподдерживаемый action или отсутствующий payload). */
        IGNORED
    }
}
