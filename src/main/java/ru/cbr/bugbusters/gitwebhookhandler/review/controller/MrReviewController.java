package ru.cbr.bugbusters.gitwebhookhandler.review.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.ReviewRunEntity;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.repository.ReviewRunRepository;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.controller.dto.ManualReviewRequest;
import ru.cbr.bugbusters.gitwebhookhandler.review.controller.dto.ReviewRunResponse;
import ru.cbr.bugbusters.gitwebhookhandler.review.controller.dto.TriggerResponse;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.MrReviewOrchestrator;

import java.util.List;

/**
 * REST-контроллер для управления AI-ревью Merge Request-ов.
 *
 * <h3>Группы эндпоинтов:</h3>
 * <ul>
 *   <li>{@code POST /api/review/trigger} — ручной запуск AI-ревью для конкретного MR;</li>
 *   <li>{@code GET  /api/review/runs}    — история запусков ревью с фильтрами;</li>
 *   <li>{@code GET  /api/review/runs/{runId}} — детали конкретного запуска ревью.</li>
 * </ul>
 *
 * <p>Все операции асинхронны: {@code POST /trigger} немедленно возвращает {@code 202 Accepted},
 * фактическое ревью выполняется в фоне через {@link MrReviewOrchestrator#runReview}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
@Tag(name = "AI Review", description = "Управление AI-ревью Merge Request-ов: ручной запуск и история")
public class MrReviewController {

    private final MrReviewOrchestrator orchestrator;
    private final ReviewRunRepository  reviewRunRepository;

    // ──────────────────────────────────────────────────────────────
    // POST /api/review/trigger — ручной запуск ревью
    // ──────────────────────────────────────────────────────────────

    /**
     * Запускает AI-ревью для указанного MR вручную (без webhook).
     *
     * <p>Полезно при отладке или повторном ревью после исправлений.
     * Ревью выполняется асинхронно; ответ 202 означает, что задача принята.
     *
     * @param request параметры MR для ревью
     * @return 202 Accepted с runId для отслеживания статуса
     */
    @PostMapping("/trigger")
    @Operation(
            summary = "Ручной запуск AI-ревью",
            description = """
                    Принимает параметры MR и запускает полный цикл AI-ревью асинхронно.
                    Возвращает 202 Accepted немедленно; ревью выполняется в фоне.
                    Для отслеживания статуса используйте GET /api/review/runs/{runId}.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202",
                    description = "Ревью принято в работу",
                    content = @Content(schema = @Schema(implementation = TriggerResponse.class))),
            @ApiResponse(responseCode = "400", description = "Невалидный запрос — projectId или mrIid не указаны")
    })
    public ResponseEntity<TriggerResponse> triggerReview(
            @Valid @RequestBody ManualReviewRequest request) {

        log.info("Ручной запуск ревью: projectId={}, mrIid={}, triggeredBy={}",
                request.projectId(), request.mrIid(), request.triggeredBy());

        ReviewTriggerCommand command = new ReviewTriggerCommand(
                request.projectId(),
                request.mrIid(),
                request.sourceBranch(),
                request.targetBranch(),
                request.lastCommit(),
                request.mrTitle(),
                request.mrUrl(),
                request.triggeredBy() != null ? request.triggeredBy() : "manual-api"
        );

        orchestrator.runReview(command);

        return ResponseEntity.accepted()
                .body(new TriggerResponse(
                        "ACCEPTED",
                        "Ревью запущено асинхронно для MR !" + request.mrIid()
                                + " (project " + request.projectId() + ")"
                ));
    }

    // ──────────────────────────────────────────────────────────────
    // GET /api/review/runs — история запусков
    // ──────────────────────────────────────────────────────────────

    /**
     * Возвращает список запусков ревью с необязательной фильтрацией.
     *
     * <p>Если параметры не указаны — возвращает последние 50 запусков.
     *
     * @param projectId фильтр по ID проекта GitLab (опционально)
     * @param mrIid     фильтр по IID merge request-а (опционально)
     * @param status    фильтр по статусу: RUNNING, SUCCESS, ERROR (опционально)
     * @return список запусков
     */
    @GetMapping("/runs")
    @Operation(
            summary = "История запусков ревью",
            description = """
                    Возвращает список запусков AI-ревью.
                    Фильтры projectId, mrIid и status — необязательные и комбинируются через AND.
                    Если фильтры не указаны — возвращаются последние 50 запусков.
                    """)
    @ApiResponse(responseCode = "200", description = "Список запусков")
    public ResponseEntity<List<ReviewRunResponse>> listRuns(
            @Parameter(description = "ID проекта GitLab")
            @RequestParam(required = false) Long projectId,

            @Parameter(description = "IID merge request-а")
            @RequestParam(required = false) Long mrIid,

            @Parameter(description = "Статус запуска: RUNNING, SUCCESS, ERROR")
            @RequestParam(required = false) ReviewRunEntity.ReviewStatus status) {

        List<ReviewRunEntity> entities;

        if (projectId != null && mrIid != null) {
            entities = reviewRunRepository.findByProjectIdAndMrIidOrderByStartedAtDesc(projectId, mrIid);
        } else if (projectId != null) {
            entities = reviewRunRepository.findByProjectIdOrderByStartedAtDesc(projectId);
        } else if (status != null) {
            entities = reviewRunRepository.findByStatusOrderByStartedAtDesc(status);
        } else {
            entities = reviewRunRepository.findTop50ByOrderByStartedAtDesc();
        }

        List<ReviewRunResponse> response = entities.stream()
                .map(ReviewRunResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }

    // ──────────────────────────────────────────────────────────────
    // GET /api/review/runs/{runId} — детали запуска
    // ──────────────────────────────────────────────────────────────

    /**
     * Возвращает детальную информацию о конкретном запуске ревью,
     * включая финальный markdown-комментарий опубликованный в GitLab.
     *
     * @param runId UUID запуска ревью
     * @return детали запуска или 404 если не найден
     */
    @GetMapping("/runs/{runId}")
    @Operation(
            summary = "Детали запуска ревью",
            description = """
                    Возвращает полную информацию о запуске:
                    статус, метаданные MR, финальный markdown-комментарий,
                    сообщение об ошибке (если статус ERROR), время выполнения.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Детали запуска",
                    content = @Content(schema = @Schema(implementation = ReviewRunResponse.class))),
            @ApiResponse(responseCode = "404", description = "Запуск с таким runId не найден")
    })
    public ResponseEntity<ReviewRunResponse> getRunById(
            @Parameter(description = "UUID запуска ревью", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String runId) {

        return reviewRunRepository.findById(runId)
                .map(ReviewRunResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
