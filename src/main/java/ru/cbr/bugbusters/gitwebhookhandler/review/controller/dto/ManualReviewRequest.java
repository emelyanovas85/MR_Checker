package ru.cbr.bugbusters.gitwebhookhandler.review.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Запрос на ручной запуск AI-ревью MR.
 */
@Schema(description = "Параметры для ручного запуска AI-ревью")
public record ManualReviewRequest(

        @NotNull @Positive
        @Schema(description = "ID проекта GitLab", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
        Long projectId,

        @NotNull @Positive
        @Schema(description = "IID merge request-а в проекте", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
        Long mrIid,

        @Schema(description = "Ветка-источник", example = "feature/TASK-123-add-payment")
        String sourceBranch,

        @Schema(description = "Целевая ветка", example = "develop")
        String targetBranch,

        @Schema(description = "SHA последнего коммита", example = "a1b2c3d4e5f6")
        String lastCommit,

        @Schema(description = "Заголовок MR", example = "Add payment processing module")
        String mrTitle,

        @Schema(description = "URL MR в GitLab", example = "https://gitlab.example.com/group/project/-/merge_requests/7")
        String mrUrl,

        @Schema(description = "Кто инициировал ревью", example = "john.doe", defaultValue = "manual-api")
        String triggeredBy
) {
}
