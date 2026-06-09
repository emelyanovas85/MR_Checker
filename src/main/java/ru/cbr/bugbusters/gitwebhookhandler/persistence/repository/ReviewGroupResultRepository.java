package ru.cbr.bugbusters.gitwebhookhandler.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.ReviewGroupResultEntity;

import java.util.List;

/**
 * Репозиторий Spring Data JPA для результатов ревью по группам.
 *
 * @see ReviewGroupResultEntity
 */
@Repository
public interface ReviewGroupResultRepository extends JpaRepository<ReviewGroupResultEntity, String> {

    /**
     * Возвращает все результаты групп для заданного запуска ревью,
     * отсортированные по индексу группы.
     *
     * @param reviewRunId UUID запуска ревью
     * @return список результатов по группам
     */
    List<ReviewGroupResultEntity> findByReviewRunIdOrderByGroupIndex(String reviewRunId);
}
