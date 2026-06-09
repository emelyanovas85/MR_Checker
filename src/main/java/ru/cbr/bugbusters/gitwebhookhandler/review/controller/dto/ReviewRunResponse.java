package ru.cbr.bugbusters.gitwebhookhandler.review.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.ReviewRunEntity;

import java.time.Instant;

/**
 * DTO для отображения информации о запуске AI-ревью.
 */
@Schema(description = "Информация о запуске AI-ревью")
public record ReviewRunResponse(

        @Schema(description = "UUID запуска", example = "550e8400-e29b-41d4-a716-446655440000")
        String id,

        @Schema(description = "ID проекта GitLab", example = "42")
        Long projectId,

        @Schema(description = "IID merge request-а", example = "7")
        Long mrIid,

        @Schema(description = "Ветка-источник", example = "feature/TASK-123")
        String sourceBranch,

        @Schema(description = "Целевая ветка", example = "develop")
        String targetBranch,

        @Schema(description = "SHA последнего коммита", example = "a1b2c3d")
        String lastCommit,

        @Schema(description = "Кто запустил ревью", example = "webhook")
        String triggeredBy,

        @Schema(description = "Статус: RUNNING, SUCCESS, ERROR")
        ReviewRunEntity.ReviewStatus status,

        @Schema(description = "Время начала")
        Instant startedAt,

        @Schema(description = "Время завершения (null если ещё выполняется)")
        Instant finishedAt,

        @Schema(description = "Финальный markdown-комментарий, опубликованный в GitLab (null если не завершено)")
        String finalCommentMarkdown,

        @Schema(description = "Сообщение об ошибке (null если успешно или ещё выполняется)")
        String errorMessage
) {
    /**
     * Создаёт DTO из JPA-сущности.
     */
    public static ReviewRunResponse from(ReviewRunEntity entity) {
        return new ReviewRunResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getMrIid(),
                entity.getSourceBranch(),
                entity.getTargetBranch(),
                entity.getLastCommit(),
                entity.getTriggeredBy(),
                entity.getStatus(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getFinalCommentMarkdown(),
                entity.getErrorMessage()
        );
    }
}
