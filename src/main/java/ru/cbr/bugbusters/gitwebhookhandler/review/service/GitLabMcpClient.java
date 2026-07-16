package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * ⚠️ ВАРИАНТ Б — НЕ АКТИВЕН (резерв для GitLab-MCP flow).
 *
 * <p>Этот компонент используется только через {@link GitLabToolsProvider} →
 * {@link GitLabReviewService}, который <strong>не подключён</strong> к
 * {@link MrReviewOrchestrator}. Активный Вариант А ({@link LlmReviewService})
 * работает с {@link ClassContextToolsProvider} и не использует этот клиент.
 *
 * <p>HTTP-клиент для вызова GitLab MCP сервера через JSON-RPC over Streamable HTTP ({@code POST /mcp}).
 *
 * <h3>Методы, которые вызывает LLM через {@link GitLabToolsProvider}:</h3>
 * <ul>
 *   <li>{@link #createReviewSession} — инициирует сессию для MR</li>
 *   <li>{@link #getStructureMarkdown} — структура изменённых файлов</li>
 *   <li>{@link #getSourceFile} — полный исходник Java-класса</li>
 *   <li>{@link #getSourceLinesGitlab} — конкретные строки кода</li>
 *   <li>{@link #getMergeRequestFileDiff} — unified diff файла из MR</li>
 *   <li>{@link #createMergeRequestThread} — создание inline-треда в коде MR ✨</li>
 *   <li>{@link #terminateReviewSession} — завершение сессии</li>
 * </ul>
 *
 * @see GitLabToolsProvider Spring AI @Tool-обёртки для этого клиента
 * @see GitLabReviewService сервис ревью Варианта Б
 *
 * @implNote Конфигурация:
 *   {@code MCP_GITLAB_URL} — URL GitLab MCP сервера (default: {@code http://10.1.5.97:8083}),
 *   {@code app.gitlab.token} — токен авторизации GitLab
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitLabMcpClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${MCP_GITLAB_URL:http://10.1.5.97:8083}")
    private String mcpGitlabUrl;

    @Value("${app.gitlab.token:}")
    private String gitLabToken;

    /**
     * Универсальный вызов GitLab MCP метода через JSON-RPC over HTTP.
     */
    private String executeMcpCall(String method, Object params) {
        try {
            String response = webClientBuilder.build()
                    .post()
                    .uri(mcpGitlabUrl + "/mcp")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + gitLabToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "jsonrpc", "2.0",
                            "id", 1,
                            "method", method,
                            "params", params
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(response);
            if (json.has("error")) {
                String errorMsg = json.get("error").toString();
                log.error("GitLab MCP error for method '{}': {}", method, errorMsg);
                throw new RuntimeException("GitLab MCP error: " + errorMsg);
            }
            JsonNode result = json.get("result");
            return result != null ? result.toString() : "{}";
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GitLab MCP call failed for method '" + method + "'", e);
        }
    }

    public String createReviewSession(int projectId, int mrIid) {
        log.debug("createReviewSession projectId={} mrIid={}", projectId, mrIid);
        return executeMcpCall("create_review_session", Map.of(
                "projectId", projectId,
                "mrIid", mrIid
        ));
    }

    public String getStructureMarkdown(String sessionId, int depth) {
        log.debug("getStructureMarkdown sessionId={} depth={}", sessionId, depth);
        return executeMcpCall("get_structure_markdown", Map.of(
                "sessionId", sessionId,
                "depth", depth
        ));
    }

    public String getSourceFile(String sessionId, String className) {
        log.debug("getSourceFile sessionId={} className={}", sessionId, className);
        return executeMcpCall("get_source_file", Map.of(
                "sessionId", sessionId,
                "className", className
        ));
    }

    public String getSourceLinesGitlab(String sessionId, String qualifiedName, String[] rows) {
        log.debug("getSourceLinesGitlab sessionId={} qualifiedName={}", sessionId, qualifiedName);
        return executeMcpCall("get_source_lines_gitlab", Map.of(
                "sessionId", sessionId,
                "qualifiedName", qualifiedName,
                "rows", rows
        ));
    }

    public String getMergeRequestFileDiff(int projectId, int mrIid, String filePath) {
        log.debug("getMergeRequestFileDiff projectId={} mrIid={} filePath={}", projectId, mrIid, filePath);
        return executeMcpCall("get_merge_request_file_diff", Map.of(
                "projectId", projectId,
                "mrIid", mrIid,
                "filePath", filePath
        ));
    }

    public String createMergeRequestThread(int projectId, int mrIid, String filePath, int newLine, String comment) {
        log.info("createMergeRequestThread projectId={} mrIid={} filePath={} line={}", projectId, mrIid, filePath, newLine);
        return executeMcpCall("create_merge_request_thread", Map.of(
                "projectId", projectId,
                "mrIid", mrIid,
                "filePath", filePath,
                "newLine", newLine,
                "comment", comment
        ));
    }

    public void terminateReviewSession(String sessionId) {
        log.debug("terminateReviewSession sessionId={}", sessionId);
        executeMcpCall("terminate_review_session", Map.of("sessionId", sessionId));
    }
}
