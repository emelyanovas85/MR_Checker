package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        GitLabProperties gitlab,
        GraphServiceProperties graphService,
        AiProperties ai
) {
    /**
     * Настройки GitLab API.
     * token — Personal Access Token для публикации комментариев в MR.
     */
    public record GitLabProperties(String url, String token) {}

    /**
     * Базовый URL граф-сервиса.
     * Пример: http://graph-service:8090
     */
    public record GraphServiceProperties(String url) {}

    /**
     * Настройки AI.
     * promptFile — путь к файлу с system prompt (classpath-ресурс).
     * По умолчанию: classpath:prompts/system-prompt.md
     */
    public record AiProperties(String promptFile) {}
}
