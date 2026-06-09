package ru.cbr.bugbusters.gitwebhookhandler.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.WebhookEventEntity;

import java.time.Instant;
import java.util.List;

/**
 * Репозиторий Spring Data JPA для аудита входящих webhook-событий.
 *
 * <p>Позволяет по ID проекта и MR IID получать историю всех входящих событий.
 * Используется для отладки, replay и будущего UI.
 *
 * @see WebhookEventEntity
 */
@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, String> {

    /**
     * Возвращает все события по проекту и MR IID,
     * отсортированные по времени получения (новейшие первыми).
     *
     * @param projectId ID проекта GitLab
     * @param mrIid     IID merge request
     * @return список событий
     */
    List<WebhookEventEntity> findByProjectIdAndMrIidOrderByReceivedAtDesc(Long projectId, Long mrIid);

    /**
     * Возвращает все события, полученные после указанного момента времени.
     *
     * @param since граница времени (включительно)
     * @return список событий
     */
    List<WebhookEventEntity> findByReceivedAtAfterOrderByReceivedAtDesc(Instant since);
}
