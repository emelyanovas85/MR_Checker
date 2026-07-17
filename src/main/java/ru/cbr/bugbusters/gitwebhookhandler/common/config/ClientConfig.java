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
import org.springframework.web.client.RestClient;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

/**
 * Конфигурация HTTP-клиентов и вспомогательных бинов.
 *
 * Проблема: Spring AI 2.0 использует внутренний OkHttpClient, создаваемый
 * через закрытый SpringAiOpenAiHttpClient. Простая замена SSLContext
 * невозможна без доступа к внутренним классам.
 *
 * Решение: SSLContext.setDefault() + HttpsURLConnection.setDefaultSSLSocketFactory()
 * подменяют SSLContext для всей JVM целиком. OkHttp читает
 * SSLContext.getDefault() при создании сокетов, если не передан
 * явный SSLSocketFactory. Корпоративный CA (Kaspersky root) должен
 * быть импортирован в cacerts:
 *   keytool -importcert -cacerts -alias kaspersky-root -file kaspersky.crt
 */
@Configuration
public class ClientConfig {

    /**
     * Устанавливает корпоративный SSLContext как дефолтный для всей JVM.
     *
     * OkHttp внутри Spring AI вызывает SSLContext.getDefault() при
     * построении сокета — поэтому SSLContext.setDefault() достаточно
     * чтобы TLS handshake прошёл через Kaspersky TLS inspection proxy.
     */
    @Bean
    public SSLContext corporateSslContext() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null); // загружает JVM cacerts, включая импортированный Kaspersky root

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        // Устанавливаем как дефолт для всей JVM — OkHttp, JDK HttpClient, HttpURLConnection
        SSLContext.setDefault(sslContext);
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

        return sslContext;
    }

    /**
     * GitLabApi для публикации комментариев в MR через gitlab4j.
     * DependsOn corporateSslContext чтобы SSLContext был установлен до первого HTTPS-запроса.
     */
    @Bean
    @org.springframework.context.annotation.DependsOn("corporateSslContext")
    public GitLabApi gitLabApi(AppProperties properties) {
        GitLabApi api = new GitLabApi(properties.gitlab().url(), properties.gitlab().token());
        api.setRequestTimeout(5000, 30000);
        return api;
    }

    /**
     * RestClient для HTTP-запросов к сервису java-class-context (порт 8084).
     */
    @Bean
    @org.springframework.context.annotation.DependsOn("corporateSslContext")
    public RestClient restClient(AppProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.classContext().url())
                .build();
    }

    /**
     * ObjectMapper: ISO-8601 даты, java.time.*, не падает на неизвестные поля.
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
     * @Lazy позволяет стартовать без реального API key.
     */
    @Bean
    @Lazy
    public ChatClient.Builder chatClientBuilder(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
