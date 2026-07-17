package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;

@Configuration
public class ClientConfig {

    /**
     * GitLabApi для публикации комментариев в MR через gitlab4j.
     */
    @Bean
    public GitLabApi gitLabApi(AppProperties properties) {
        GitLabApi api = new GitLabApi(properties.gitlab().url(), properties.gitlab().token());
        api.setRequestTimeout(5000, 30000);
        return api;
    }

    /**
     * JDK HttpClient с корпоративным SSLContext.
     * Использует системный TrustStore JVM (cacerts) — корпоративный CA
     * (Kaspersky root cert) должен быть импортирован туда через
     * keytool -importcert или -Djavax.net.ssl.trustStore.
     */
    @Bean
    public HttpClient httpClient() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * JdkClientHttpRequestFactory — Spring Framework 7.x адаптер поверх JDK HttpClient.
     * Используется как transport для RestClient и Spring AI OpenAI-клиента.
     */
    @Bean
    public JdkClientHttpRequestFactory jdkClientHttpRequestFactory(HttpClient httpClient) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return factory;
    }

    /**
     * RestClient для HTTP-запросов к сервису java-class-context (порт 8084).
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder,
                                 JdkClientHttpRequestFactory factory) {
        return builder
                .requestFactory(factory)
                .build();
    }

    /**
     * ObjectMapper с настройками по умолчанию:
     * - не падает на неизвестные поля (FAIL_ON_UNKNOWN_PROPERTIES = false)
     * - сериализует даты в ISO-8601 (не в timestamp)
     * - поддерживает java.time.* через JavaTimeModule
     *
     * {@code @Primary} обязателен, т.к. Spring Boot 4.x может также
     * регистрировать свой ObjectMapper через JacksonAutoConfiguration.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
