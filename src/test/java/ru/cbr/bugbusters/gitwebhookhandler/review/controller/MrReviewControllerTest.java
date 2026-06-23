package ru.cbr.bugbusters.gitwebhookhandler.review.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.ReviewRunEntity;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.repository.ReviewRunRepository;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.controller.dto.ManualReviewRequest;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.MrReviewOrchestrator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты REST API контроллера MrReviewController.
 * Используют @WebMvcTest — поднимается только слой MVC, без Spring context целиком.
 */
@WebMvcTest(MrReviewController.class)
@DisplayName("MrReviewController — REST API")
class MrReviewControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean MrReviewOrchestrator orchestrator;
    @MockitoBean ReviewRunRepository   reviewRunRepository;

    // ─── POST /api/review/trigger ────────────────────────────────────────

    @Test
    @DisplayName("POST /trigger — валидный запрос возвращает 202 и передаёт команду оркестратору")
    void triggerReview_validRequest_returns202() throws Exception {
        ManualReviewRequest request = new ManualReviewRequest(
                42L, 7L,
                "feature/TASK-1", "develop",
                "abc123", "Add feature",
                "https://gitlab.example.com/-/merge_requests/7",
                "john.doe"
        );

        mockMvc.perform(post("/api/review/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.message").exists());

        ArgumentCaptor<ReviewTriggerCommand> captor =
                ArgumentCaptor.forClass(ReviewTriggerCommand.class);
        verify(orchestrator, times(1)).runReview(captor.capture());

        ReviewTriggerCommand cmd = captor.getValue();
        assertThat(cmd.projectId()).isEqualTo(42L);
        assertThat(cmd.mrIid()).isEqualTo(7L);
        assertThat(cmd.triggeredBy()).isEqualTo("john.doe");
    }

    @Test
    @DisplayName("POST /trigger — triggeredBy null заменяется на 'manual-api'")
    void triggerReview_nullTriggeredBy_defaultsToManualApi() throws Exception {
        ManualReviewRequest request = new ManualReviewRequest(
                1L, 1L, null, null, null, null, null, null
        );

        mockMvc.perform(post("/api/review/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        ArgumentCaptor<ReviewTriggerCommand> captor =
                ArgumentCaptor.forClass(ReviewTriggerCommand.class);
        verify(orchestrator).runReview(captor.capture());
        assertThat(captor.getValue().triggeredBy()).isEqualTo("manual-api");
    }

    @Test
    @DisplayName("POST /trigger — projectId null возвращает 400")
    void triggerReview_nullProjectId_returns400() throws Exception {
        String json = "{\"mrIid\": 7}";

        mockMvc.perform(post("/api/review/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orchestrator);
    }

    // ─── GET /api/review/runs ────────────────────────────────────────────

    @Test
    @DisplayName("GET /runs — без фильтров возвращает last 50")
    void listRuns_noFilters_returnsLast50() throws Exception {
        ReviewRunEntity run = buildRunEntity("run-1", 42L, 7L, ReviewRunEntity.ReviewStatus.SUCCESS);
        when(reviewRunRepository.findTop50ByOrderByStartedAtDesc()).thenReturn(List.of(run));

        mockMvc.perform(get("/api/review/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("run-1"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].projectId").value(42));
    }

    @Test
    @DisplayName("GET /runs?projectId=42&mrIid=7 — фильтр по projectId и mrIid")
    void listRuns_filterByProjectAndMr_delegatesToRepository() throws Exception {
        ReviewRunEntity run = buildRunEntity("run-2", 42L, 7L, ReviewRunEntity.ReviewStatus.RUNNING);
        when(reviewRunRepository.findByProjectIdAndMrIidOrderByStartedAtDesc(42L, 7L))
                .thenReturn(List.of(run));

        mockMvc.perform(get("/api/review/runs").param("projectId", "42").param("mrIid", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mrIid").value(7));
    }

    @Test
    @DisplayName("GET /runs?status=ERROR — фильтр по статусу ERROR")
    void listRuns_filterByStatus_delegatesToRepository() throws Exception {
        ReviewRunEntity run = buildRunEntity("run-3", 1L, 1L, ReviewRunEntity.ReviewStatus.ERROR);
        when(reviewRunRepository.findByStatusOrderByStartedAtDesc(ReviewRunEntity.ReviewStatus.ERROR))
                .thenReturn(List.of(run));

        mockMvc.perform(get("/api/review/runs").param("status", "ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ERROR"));
    }

    // ─── GET /api/review/runs/{runId} ──────────────────────────────────────

    @Test
    @DisplayName("GET /runs/{runId} — существующий runId возвращает 200 с деталями")
    void getRunById_found_returns200() throws Exception {
        ReviewRunEntity run = buildRunEntity("run-99", 10L, 3L, ReviewRunEntity.ReviewStatus.SUCCESS);
        run.setFinalCommentMarkdown("## AI Review\nLooks good!");
        when(reviewRunRepository.findById("run-99")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/review/runs/run-99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-99"))
                .andExpect(jsonPath("$.finalCommentMarkdown").value("## AI Review\nLooks good!"));
    }

    @Test
    @DisplayName("GET /runs/{runId} — несуществующий runId возвращает 404")
    void getRunById_notFound_returns404() throws Exception {
        when(reviewRunRepository.findById("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/review/runs/unknown"))
                .andExpect(status().isNotFound());
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private ReviewRunEntity buildRunEntity(String id, Long projectId, Long mrIid,
                                           ReviewRunEntity.ReviewStatus status) {
        ReviewRunEntity entity = new ReviewRunEntity();
        entity.setId(id);
        entity.setProjectId(projectId);
        entity.setMrIid(mrIid);
        entity.setStatus(status);
        entity.setStartedAt(Instant.now());
        return entity;
    }
}
