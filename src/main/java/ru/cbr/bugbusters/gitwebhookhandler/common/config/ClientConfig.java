package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.TlsVersion;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

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
     * Jackson-конвертер добавляется автоматически Spring Boot auto-configuration,
     * поэтому явная настройка messageConverters не требуется.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    /**
     * ObjectMapper для парсинга JSON-ответов LLM (группировка).
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * OkHttpClient с явным TLS 1.2 и расширенным списком cipher suites.
     * Необходимо для работы через корпоративный Kaspersky TLS inspection,
     * который выбирает TLS_RSA_WITH_AES_256_GCM_SHA384 (без ECDHE).
     * HTTP/2 отключён — сервер не поддерживает ALPN h2.
     */
    @Bean
    public OkHttpClient okHttpClient() {
        ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .cipherSuites(
                        // Kaspersky inspection выбирает именно этот suite (AES256-GCM-SHA384):
                        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA256,
                        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                        // ECDHE как запасные варианты:
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
                )
                .build();

        return new OkHttpClient.Builder()
                // Только HTTP/1.1 — убирает ALPN h2, который сервер не принимает
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectionSpecs(List.of(spec))
                .build();
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
