package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        GitLabProperties gitlab,
        ClassContextProperties classContext,
        AiProperties ai
) {
    /**
     * Настройки GitLab API (для публикации комментариев через gitlab4j).
     */
    public record GitLabProperties(String url, String token) {}

    /**
     * Настройки сервиса java-class-context (порт 8084).
     * url         — базовый URL, например http://10.1.5.97:8084
     * gitlabToken — PAT для создания сессии ревью (передаётся в CreateSessionRequest)
     */
    public record ClassContextProperties(String url, String gitlabToken) {}

    /**
     * Настройки AI.
     * groupingPromptFile — промпт группировки (первый этап).
     * reviewPromptFile   — промпт ревью группы (второй этап).
     */
    public record AiProperties(String groupingPromptFile, String reviewPromptFile) {}
}
