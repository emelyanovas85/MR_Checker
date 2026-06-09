package ru.cbr.bugbusters.gitwebhookhandler.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.ReviewRunEntity;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий Spring Data JPA для запусков AI-ревью.
 *
 * <p>Основная точка чтения истории ревью для будущего UI:
 * можно получить все прогоны по MR или последний прогон для конкретного MR.
 *
 * @see ReviewRunEntity
 */
@Repository
public interface ReviewRunRepository extends JpaRepository<ReviewRunEntity, String> {

    /**
     * Возвращает все запуски ревью для заданного MR,
     * отсортированные по времени начала (новейшие первыми).
     *
     * @param projectId ID проекта GitLab
     * @param mrIid     IID merge request
     * @return список запусков ревью
     */
    List<ReviewRunEntity> findByProjectIdAndMrIidOrderByStartedAtDesc(Long projectId, Long mrIid);

    /**
     * Возвращает последний запуск ревью для заданного MR.
     *
     * @param projectId ID проекта GitLab
     * @param mrIid     IID merge request
     * @return Optional с последним запуском или пустой
     */
    Optional<ReviewRunEntity> findTopByProjectIdAndMrIidOrderByStartedAtDesc(Long projectId, Long mrIid);

    /**
     * Возвращает все запуски ревью с указанным статусом.
     *
     * @param status статус (RUNNING, SUCCESS, ERROR)
     * @return список запусков
     */
    List<ReviewRunEntity> findByStatusOrderByStartedAtDesc(ReviewRunEntity.ReviewStatus status);
}
