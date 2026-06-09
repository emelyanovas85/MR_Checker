package ru.cbr.bugbusters.gitwebhookhandler.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.cbr.bugbusters.gitwebhookhandler.persistence.entity.LlmMessageEntity;

import java.util.List;

/**
 * Репозиторий Spring Data JPA для сообщений промежуточных чатов с LLM.
 *
 * <p>Позволяет воспроизвести полный трейс взаимодействия с моделью
 * по каждому запуску ревью и каждой группе.
 *
 * @see LlmMessageEntity
 */
@Repository
public interface LlmMessageRepository extends JpaRepository<LlmMessageEntity, String> {

    /**
     * Возвращает все сообщения заданного запуска ревью, упорядоченные по времени создания.
     *
     * @param reviewRunId UUID запуска ревью
     * @return список сообщений LLM
     */
    List<LlmMessageEntity> findByReviewRunIdOrderByCreatedAt(String reviewRunId);

    /**
     * Возвращает сообщения по этапу ({@code GROUPING} или {@code REVIEW}) для запуска ревью.
     *
     * @param reviewRunId UUID запуска ревью
     * @param stage       этап (GROUPING / REVIEW)
     * @return список сообщений
     */
    List<LlmMessageEntity> findByReviewRunIdAndStageOrderByCreatedAt(
            String reviewRunId, LlmMessageEntity.Stage stage);

    /**
     * Возвращает сообщения конкретной группы на этапе REVIEW.
     *
     * @param reviewRunId UUID запуска ревью
     * @param stage       этап (обычно REVIEW)
     * @param groupIndex  индекс группы
     * @return список сообщений группы
     */
    List<LlmMessageEntity> findByReviewRunIdAndStageAndGroupIndexOrderByCreatedAt(
            String reviewRunId, LlmMessageEntity.Stage stage, int groupIndex);
}
