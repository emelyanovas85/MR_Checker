package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class ClientConfig {

    /**
     * GitLabApi для публикации комментариев в MR через gitlab4j.
     */
    @Bean
    public GitLabApi gitLabApi(AppProperties properties) {
        return new GitLabApi(properties.gitlab().url(), properties.gitlab().token());
    }

    /**
     * RestClient для HTTP-запросов к сервису java-class-context (порт 8084).
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder
                .messageConverters(converters -> {
                    // Убеждаемся что Jackson-конвертер есть — по умолчанию он присутствует
                    boolean hasJackson = converters.stream()
                            .anyMatch(c -> c instanceof MappingJackson2HttpMessageConverter);
                    if (!hasJackson) {
                        converters.add(new MappingJackson2HttpMessageConverter());
                    }
                })
                .build();
    }

    /**
     * ObjectMapper для парсинга JSON-ответов LLM (группировка).
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * ChatClient.Builder для создания изолированных ChatClient под каждую группу ревью.
     * {@code @Lazy} позволяет стартовать без реального OPENAI_API_KEY.
     */
    @Bean
    @Lazy
    public ChatClient.Builder chatClientBuilder(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
