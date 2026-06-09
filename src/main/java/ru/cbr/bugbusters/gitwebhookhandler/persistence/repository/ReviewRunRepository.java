package ru.cbr.bugbusters.gitwebhookhandler.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.ReviewRunEntity;

import java.util.List;

/**
 * Репозиторий для работы с историей запусков AI-ревью.
 */
@Repository
public interface ReviewRunRepository extends JpaRepository<ReviewRunEntity, String> {

    /** Запуски для конкретного MR, новые первые. */
    List<ReviewRunEntity> findByProjectIdAndMrIidOrderByStartedAtDesc(Long projectId, Long mrIid);

    /** Запуски по проекту, новые первые. */
    List<ReviewRunEntity> findByProjectIdOrderByStartedAtDesc(Long projectId);

    /** Запуски по статусу, новые первые. */
    List<ReviewRunEntity> findByStatusOrderByStartedAtDesc(ReviewRunEntity.ReviewStatus status);

    /** Последние 50 запусков без фильтра. */
    List<ReviewRunEntity> findTop50ByOrderByStartedAtDesc();
}
