package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.TlsVersion;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

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
     * TrustManagerFactory из JVM cacerts.
     * Kaspersky root cert должен быть импортирован:
     *   keytool -importcert -cacerts -alias kaspersky-root -file kaspersky.crt
     */
    @Bean
    public TrustManagerFactory trustManagerFactory() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return tmf;
    }

    /**
     * SSLContext TLSv1.2 на базе корпоративного TrustStore.
     * Общий для OkHttpClient (Spring AI) и JDK HttpClient (RestClient).
     */
    @Bean
    public SSLContext sslContext(TrustManagerFactory tmf) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * OkHttpClient с SSLSocketFactory из корпоративного TrustStore.
     * Используется как transport для Spring AI (через RestClientCustomizer)
     * и для прямых HTTP-запросов.
     */
    @Bean
    public OkHttpClient okHttpClient(SSLContext sslContext, TrustManagerFactory tmf) {
        X509TrustManager trustManager = (X509TrustManager) tmf.getTrustManagers()[0];
        SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

        ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build();

        return new OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustManager)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectionSpecs(Collections.singletonList(spec))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * RestClientCustomizer — перехватывает все бины RestClient.Builder в контексте,
     * включая тот, что Spring AI использует внутри SpringAiOpenAiHttpClient.
     *
     * Примечание: в Spring AI 2.0.0 внутренний HTTP-клиент перешёл на okhttp3,
     * но создаётся через RestClient.Builder с OkHttp3ClientHttpRequestFactory.
     * RestClientCustomizer — стандартный механизм Spring Boot для кастомизации
     * всех бинов типа RestClient.Builder до их разрешения.
     */
    @Bean
    public RestClientCustomizer restClientCustomizer(OkHttpClient okHttpClient) {
        return builder -> builder.requestFactory(
                new OkHttp3ClientHttpRequestFactory(okHttpClient));
    }

    /**
     * JDK HttpClient для RestClient java-class-context (HTTP, порт 8084).
     * Получает SSLContext на случай если endpoint перейдёт на HTTPS.
     * Используем отдельный HttpClient чтобы не переписывать requestFactory
     * у RestClientCustomizer (который затрагивает все бины Builder​-а).
     */
    @Bean
    public RestClient restClient(AppProperties properties, OkHttpClient okHttpClient) {
        return RestClient.builder()
                .baseUrl(properties.classContext().url())
                .requestFactory(new OkHttp3ClientHttpRequestFactory(okHttpClient))
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
