package ru.cbr.bugbusters.gitwebhookhandler.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.ReviewRunEntity;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.repository.ReviewRunRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты репозитория запусков ревью.
 * Используют in-memory H2 через @DataJpaTest (профиль test).
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("ReviewRunRepository — JPA тесты")
class ReviewRunRepositoryTest {

    @Autowired
    private ReviewRunRepository repository;

    @Test
    @DisplayName("Сохранение и поиск по projectId/mrIid — сортировка новейшие первыми")
    void findByProjectIdAndMrIid_sortedByStartedAtDesc() {
        Instant t1 = Instant.now().minusSeconds(100);
        Instant t2 = Instant.now().minusSeconds(10);

        repository.save(buildRun(10L, 1L, ReviewRunEntity.ReviewStatus.SUCCESS, t1));
        repository.save(buildRun(10L, 1L, ReviewRunEntity.ReviewStatus.ERROR,   t2));

        List<ReviewRunEntity> runs =
                repository.findByProjectIdAndMrIidOrderByStartedAtDesc(10L, 1L);

        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).getStartedAt()).isAfter(runs.get(1).getStartedAt());
    }

    @Test
    @DisplayName("findTopByProjectIdAndMrIid — возвращает последний запуск")
    void findTopByProjectIdAndMrIid_returnsLatest() {
        repository.save(buildRun(20L, 2L, ReviewRunEntity.ReviewStatus.SUCCESS,
                Instant.now().minusSeconds(200)));
        ReviewRunEntity latest = buildRun(20L, 2L, ReviewRunEntity.ReviewStatus.RUNNING,
                Instant.now());
        repository.save(latest);

        Optional<ReviewRunEntity> top =
                repository.findTopByProjectIdAndMrIidOrderByStartedAtDesc(20L, 2L);

        assertThat(top).isPresent();
        assertThat(top.get().getId()).isEqualTo(latest.getId());
        assertThat(top.get().getStatus()).isEqualTo(ReviewRunEntity.ReviewStatus.RUNNING);
    }

    @Test
    @DisplayName("findByStatus — возвращает только запуски с заданным статусом")
    void findByStatus_filtersCorrectly() {
        repository.save(buildRun(30L, 3L, ReviewRunEntity.ReviewStatus.SUCCESS, Instant.now()));
        repository.save(buildRun(30L, 4L, ReviewRunEntity.ReviewStatus.ERROR,   Instant.now()));
        repository.save(buildRun(30L, 5L, ReviewRunEntity.ReviewStatus.RUNNING, Instant.now()));

        List<ReviewRunEntity> errors =
                repository.findByStatusOrderByStartedAtDesc(ReviewRunEntity.ReviewStatus.ERROR);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getMrIid()).isEqualTo(4L);
    }

    @Test
    @DisplayName("Обновление статуса и finalCommentMarkdown")
    void updateStatusAndMarkdown_persists() {
        ReviewRunEntity run = buildRun(40L, 6L, ReviewRunEntity.ReviewStatus.RUNNING, Instant.now());
        repository.save(run);

        run.setStatus(ReviewRunEntity.ReviewStatus.SUCCESS);
        run.setFinalCommentMarkdown("## AI Review\n\nAll good.");
        run.setFinishedAt(Instant.now());
        repository.save(run);

        ReviewRunEntity loaded = repository.findById(run.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ReviewRunEntity.ReviewStatus.SUCCESS);
        assertThat(loaded.getFinalCommentMarkdown()).contains("All good.");
        assertThat(loaded.getFinishedAt()).isNotNull();
    }

    // ───────────────────────── helpers ─────────────────────────

    private ReviewRunEntity buildRun(Long projectId, Long mrIid,
                                     ReviewRunEntity.ReviewStatus status, Instant startedAt) {
        return ReviewRunEntity.builder()
                .id(UUID.randomUUID().toString())
                .projectId(projectId)
                .mrIid(mrIid)
                .sourceBranch("feat")
                .targetBranch("main")
                .triggeredBy("gitlab")
                .status(status)
                .startedAt(startedAt)
                .build();
    }
}
