package ru.cbr.bugbusters.gitwebhookhandler.persistence.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

/**
 * JPA-сущность результата ревью одной группы рефакторинга.
 *
 * <p>Каждая запись соответствует одному параллельному вызову LLM второго этапа.
 * Связана с {@link ReviewRunEntity} отношением Many-to-One.
 *
 * <p>Данные доступны для построения UI: можно показать
 * детальный результат по каждой группе отдельно.
 *
 * @see ReviewRunEntity
 */
@Entity
@Table(name = "review_group_results", indexes = {
        @Index(name = "idx_rgr_run_id",      columnList = "review_run_id"),
        @Index(name = "idx_rgr_group_index", columnList = "group_index")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Результат ревью одной группы рефакторинга")
public class ReviewGroupResultEntity {

    /** UUID результата группы. */
    @Id
    @Column(nullable = false, length = 36)
    @Schema(description = "UUID результата группы")
    private String id;

    /** Ссылка на родительский запуск ревью. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_run_id", nullable = false)
    @Schema(description = "Запуск ревью")
    private ReviewRunEntity reviewRun;

    /** Порядковый номер группы (0-based). */
    @Column(name = "group_index", nullable = false)
    @Schema(description = "Порядковый номер группы (0-based)")
    private int groupIndex;

    /** Название группы из LLM-группировки. */
    @Column(name = "group_name", length = 512)
    @Schema(description = "Название группы рефакторинга")
    private String groupName;

    /** Признак успешного завершения ревью группы. */
    @Column(name = "success", nullable = false)
    @Schema(description = "true — ревью группы прошло успешно")
    private boolean success;

    /** Полный текст ревью от LLM по данной группе (markdown). */
    @Lob
    @Column(name = "review_text", columnDefinition = "TEXT")
    @Schema(description = "Текст ревью по группе (markdown)")
    private String reviewText;
}
