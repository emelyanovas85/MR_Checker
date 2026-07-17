package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
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
        // connectTimeout=5s, readTimeout=30s
        api.setRequestTimeout(5000, 30000);
        return api;
    }

    /**
     * JDK HttpClient с корпоративным SSLContext.
     * Использует системный TrustStore JVM — там должн лежать
     * корпоративный CA (Kaspersky root cert), добавленный через
     * -Djavax.net.ssl.trustStore или импорт в cacerts JVM.
     */
    @Bean
    public HttpClient httpClient() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null); // загружает cacerts JVM

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
     * Используется как транспорт для всех RestClient в контексте,
     * включая внутренний Spring AI OpenAI-клиент.
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
     * ChatClient.Builder для создания изолированных ChatClient под каждую группу ревью.
     * {@code @Lazy} позволяет стартовать без реального OPENAI_API_KEY.
     */
    @Bean
    @Lazy
    public ChatClient.Builder chatClientBuilder(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
