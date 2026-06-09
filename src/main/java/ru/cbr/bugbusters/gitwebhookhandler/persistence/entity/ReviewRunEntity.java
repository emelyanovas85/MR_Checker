package ru.cbr.bugbusters.gitwebhookhandler.persistence.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA-сущность одного запуска AI-ревью MR.
 *
 * <p>Создаётся при старте оркестрации в {@code MrReviewOrchestrator},
 * обновляется в ходе выполнения всех этапов и финализируется с результатом.
 *
 * <p>Хранит:
 * <ul>
 *   <li>метаданные MR (projectId, mrIid, ветки, lastCommit);</li>
 *   <li>sessionId сессии в сервисе java-class-context (8084);</li>
 *   <li>итоговый markdown-комментарий, опубликованный в GitLab;</li>
 *   <li>статус и сообщение об ошибке (если было);</li>
 *   <li>результаты по группам рефакторинга (cascade).</li>
 * </ul>
 *
 * @see ReviewGroupResultEntity
 * @see ru.cbr.bugbusters.gitwebhookhandler.persistence.service.ReviewAuditService
 */
@Entity
@Table(name = "review_runs", indexes = {
        @Index(name = "idx_rr_project_mr",  columnList = "project_id, mr_iid"),
        @Index(name = "idx_rr_status",      columnList = "status"),
        @Index(name = "idx_rr_started_at",  columnList = "started_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Запуск AI-ревью MR")
public class ReviewRunEntity {

    /** UUID запуска ревью. */
    @Id
    @Column(nullable = false, length = 36)
    @Schema(description = "UUID запуска ревью")
    private String id;

    /** ID проекта GitLab. */
    @Column(name = "project_id", nullable = false)
    @Schema(description = "ID проекта GitLab")
    private Long projectId;

    /** IID merge request в проекте. */
    @Column(name = "mr_iid", nullable = false)
    @Schema(description = "IID merge request")
    private Long mrIid;

    /** Ветка-источник MR. */
    @Column(name = "source_branch", length = 512)
    @Schema(description = "Исходная ветка MR")
    private String sourceBranch;

    /** Целевая ветка MR. */
    @Column(name = "target_branch", length = 512)
    @Schema(description = "Целевая ветка MR")
    private String targetBranch;

    /** SHA последнего коммита в MR. */
    @Column(name = "last_commit", length = 64)
    @Schema(description = "SHA последнего коммита")
    private String lastCommit;

    /** Пользователь или действие, инициировавшее ревью. */
    @Column(name = "triggered_by", length = 128)
    @Schema(description = "Кто инициировал ревью")
    private String triggeredBy;

    /** SessionId сессии в сервисе java-class-context (8084). */
    @Column(name = "session_id", length = 128)
    @Schema(description = "SessionId в сервисе java-class-context")
    private String sessionId;

    /** Текущий статус запуска ревью. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    @Schema(description = "Статус запуска ревью")
    private ReviewStatus status;

    /** Временная метка начала ревью. */
    @Column(name = "started_at", nullable = false)
    @Schema(description = "Время начала ревью")
    private Instant startedAt;

    /** Временная метка завершения ревью (успех или ошибка). */
    @Column(name = "finished_at")
    @Schema(description = "Время завершения ревью")
    private Instant finishedAt;

    /** Финальный markdown-комментарий, опубликованный в GitLab MR. */
    @Lob
    @Column(name = "final_comment_markdown", columnDefinition = "TEXT")
    @Schema(description = "Итоговый markdown-комментарий в GitLab")
    private String finalCommentMarkdown;

    /** Сообщение об ошибке (если статус ERROR). */
    @Lob
    @Column(name = "error_message", columnDefinition = "TEXT")
    @Schema(description = "Сообщение об ошибке")
    private String errorMessage;

    /** Результаты ревью по группам рефакторинга. */
    @OneToMany(mappedBy = "reviewRun", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @Schema(description = "Результаты по группам рефакторинга")
    private List<ReviewGroupResultEntity> groupResults = new ArrayList<>();

    /**
     * Статус запуска AI-ревью.
     */
    public enum ReviewStatus {
        /** Ревью запущено и выполняется. */
        RUNNING,
        /** Ревью успешно завершено, комментарий опубликован. */
        SUCCESS,
        /** Ревью завершено с ошибкой. */
        ERROR
    }
}
