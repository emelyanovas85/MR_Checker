package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Provider инструментов GitLab MCP для LLM-ревью.
 *
 * Каждый экземпляр создаётся per-review через ObjectProvider,
 * что обеспечивает изолированный контекст на группу.
 *
 * LLM может использовать эти инструменты для:
 * - Создания/завершения сессии ревью (create_review_session, terminate_review_session)
 * - Получения структуры изменённых файлов (get_structure_markdown)
 * - Запроса полных исходников файлов (get_source_file)
 * - Запроса конкретных строк кода (get_source_lines_gitlab)
 * - Получения диффов файлов (get_merge_request_file_diff)
 * - Создания inline-тредов в коде (create_merge_request_thread)
 */
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class GitLabToolsProvider {

    private final GitLabMcpClient gitLabMcpClient;

    @Tool(description = """
            Создать сессию ревью для Merge Request.
            Это первый шаг: загружает MR, фиксирует версии кода (sha) и строит индекс файлов.
            Возвращает sessionId, необходимый для всех последующих анализов.
            """)
    public String createReviewSession(
            @ToolParam(description = "ID проекта GitLab (числовой)") int projectId,
            @ToolParam(description = "IID Merge Request (числовой)") int mrIid) {
        return gitLabMcpClient.createReviewSession(projectId, mrIid);
    }

    @Tool(description = """
            Получить структурный контекст изменённых классов в формате Markdown.
            Показывает метаданные MR, список файлов и зависимости.
            depth=1 — только прямые зависимости, depth=2 — транзитивные.
            """)
    public String getStructureMarkdown(
            @ToolParam(description = "sessionId, полученный из createReviewSession") String sessionId,
            @ToolParam(description = "Глубина обхода зависимостей (1 или 2)") int depth) {
        return gitLabMcpClient.getStructureMarkdown(sessionId, depth);
    }

    @Tool(description = """
            Получить полный текст Java-файла по имени класса.
            Ищет как в репозитории MR, так и в зависимостях (sources.jar).
            Используй simple name (например 'OrderService') или qualified name.
            """)
    public String getSourceFile(
            @ToolParam(description = "sessionId, полученный из createReviewSession") String sessionId,
            @ToolParam(description = "Имя класса (simple или qualified)") String className) {
        return gitLabMcpClient.getSourceFile(sessionId, className);
    }

    @Tool(description = """
            Получить конкретные диапазоны строк из Java-файлов репозитория MR.
            Требует активную сессию.
            Примеры rows: [\"28-168\"] или [\"17\", \"45-67\"].
            """)
    public String getSourceLinesGitlab(
            @ToolParam(description = "sessionId, полученный из createReviewSession") String sessionId,
            @ToolParam(description = "Qualified name класса (пакет.ИмяКласса)") String qualifiedName,
            @ToolParam(description = "Диапазоны строк, например [\"28-168\"] или [\"17\", \"45-67\"]") String[] rows) {
        return gitLabMcpClient.getSourceLinesGitlab(sessionId, qualifiedName, rows);
    }

    @Tool(description = """
            Получить диффы для конкретного файла из MR.
            Возвращает unified diff с контекстом изменений.
            """)
    public String getMergeRequestFileDiff(
            @ToolParam(description = "ID проекта GitLab (числовой)") int projectId,
            @ToolParam(description = "IID Merge Request (числовой)") int mrIid,
            @ToolParam(description = "Путь к файлу относительно корня репозитория") String filePath) {
        return gitLabMcpClient.getMergeRequestFileDiff(projectId, mrIid, filePath);
    }

    @Tool(description = """
            Создать новую ветку обсуждения (thread) в MR, привязанную к конкретному месту в коде (diff).
            Используй при обнаружении критических или значимых проблем в коде.
            newLine — номер строки в новой версии файла (after-state).
            """)
    public String createMergeRequestThread(
            @ToolParam(description = "ID проекта GitLab (числовой)") int projectId,
            @ToolParam(description = "IID Merge Request (числовой)") int mrIid,
            @ToolParam(description = "Путь к файлу относительно корня репозитория") String filePath,
            @ToolParam(description = "Номер строки в новой версии файла (newLine)") int newLine,
            @ToolParam(description = "Текст комментария на русском языке") String comment) {
        return gitLabMcpClient.createMergeRequestThread(projectId, mrIid, filePath, newLine, comment);
    }

    @Tool(description = """
            Завершить сессию ревью, освобождая ресурсы.
            Рекомендуется вызывать после завершения анализа всех файлов группы.
            """)
    public void terminateReviewSession(
            @ToolParam(description = "sessionId, полученный из createReviewSession") String sessionId) {
        gitLabMcpClient.terminateReviewSession(sessionId);
    }
}
