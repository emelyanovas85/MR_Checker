package ru.cbr.bugbusters.gitwebhookhandler.review.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ответ на запрос ручного запуска ревью.
 */
@Schema(description = "Результат принятия задачи ревью")
public record TriggerResponse(

        @Schema(description = "Статус принятия", example = "ACCEPTED")
        String status,

        @Schema(description = "Описание результата",
                example = "Ревью запущено асинхронно для MR !7 (project 42)")
        String message
) {
}
