package ru.cbr.bugbusters.gitwebhookhandler.persistence.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * JPA-сущность одного сообщения в промежуточном чате с LLM.
 *
 * <p>Позволяет хранить полный трейс взаимодействия с языковой моделью по каждому этапу:
 * <ul>
 *   <li>GROUPING — первый этап (группировка файлов);</li>
 *   <li>REVIEW — второй этап (ревью одной группы).</li>
 * </ul>
 *
 * <p>Роли сообщений (поле {@code role}):
 * <ul>
 *   <li>SYSTEM — системный промпт;</li>
 *   <li>USER — запрос пользователя/оркестратора к LLM;</li>
 *   <li>ASSISTANT — ответ LLM;</li>
 *   <li>TOOL_REQUEST — запрос LLM к инструменту (tool call);</li>
 *   <li>TOOL_RESPONSE — ответ инструмента LLM.</li>
 * </ul>
 *
 * <p>Данные предназначены для будущего UI:
 * экрана «Как агент пришёл к замечанию» / «Трейс ревью».
 *
 * @see ReviewRunEntity
 */
@Entity
@Table(name = "llm_messages", indexes = {
        @Index(name = "idx_lm_run_id",    columnList = "review_run_id"),
        @Index(name = "idx_lm_stage",     columnList = "stage"),
        @Index(name = "idx_lm_created_at",columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Промежуточное сообщение в чате с LLM в рамках одного запуска ревью")
public class LlmMessageEntity {

    /** UUID сообщения. */
    @Id
    @Column(nullable = false, length = 36)
    @Schema(description = "UUID сообщения")
    private String id;

    /** Ссылка на запуск ревью, которому принадлежит сообщение. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_run_id", nullable = false)
    @Schema(description = "Запуск ревью")
    private ReviewRunEntity reviewRun;

    /**
     * Этап ревью, на котором создано сообщение.
     * GROUPING — первый этап, REVIEW — второй этап.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 16, nullable = false)
    @Schema(description = "Этап ревью: GROUPING или REVIEW")
    private Stage stage;

    /**
     * Индекс группы рефакторинга (только для этапа REVIEW).
     * Для этапа GROUPING = null.
     */
    @Column(name = "group_index")
    @Schema(description = "Индекс группы (только для этапа REVIEW, иначе null)")
    private Integer groupIndex;

    /**
     * Роль отправителя сообщения.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    @Schema(description = "Роль отправителя: SYSTEM, USER, ASSISTANT, TOOL_REQUEST, TOOL_RESPONSE")
    private MessageRole role;

    /** Имя инструмента (только для ролей TOOL_REQUEST / TOOL_RESPONSE). */
    @Column(name = "tool_name", length = 256)
    @Schema(description = "Имя инструмента (для tool call/response)")
    private String toolName;

    /** Содержимое сообщения. */
    @Lob
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    @Schema(description = "Содержимое сообщения")
    private String content;

    /** Временная метка создания сообщения. */
    @Column(name = "created_at", nullable = false)
    @Schema(description = "Время создания сообщения")
    private Instant createdAt;

    /**
     * Этап ревью, на котором генерируются сообщения LLM.
     */
    public enum Stage {
        /** Первый этап — группировка изменённых файлов. */
        GROUPING,
        /** Второй этап — ревью одной группы рефакторинга. */
        REVIEW
    }

    /**
     * Роль отправителя в диалоге с LLM.
     */
    public enum MessageRole {
        /** Системный промпт. */
        SYSTEM,
        /** Запрос от оркестратора к LLM. */
        USER,
        /** Ответ LLM. */
        ASSISTANT,
        /** Запрос LLM к инструменту (tool call). */
        TOOL_REQUEST,
        /** Ответ инструмента обратно в LLM. */
        TOOL_RESPONSE
    }
}
