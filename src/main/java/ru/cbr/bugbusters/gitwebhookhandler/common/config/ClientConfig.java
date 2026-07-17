package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Конфигурация HTTP-клиентов.
 *
 * ПРОБЛЕМА: Kaspersky TLS inspection proxy отклоняет TLS handshake
 * потому что OkHttp 4.12 по умолчанию предлагает только ECDHE cipher suites
 * (ConnectionSpec.MODERN_TLS). Корпоративный proxy требует RSA key exchange
 * (TLS_RSA_WITH_AES_256_GCM_SHA384 и подобные).
 *
 * ДИАГНОСТИКА ИЗ ЛОГОВ:
 *   Caused by: javax.net.ssl.SSLHandshakeException: (handshake_failure)
 *   Received fatal alert: handshake_failure
 *   at okhttp3.internal.connection.RealConnection.connectTls
 *
 * Это НЕ проблема доверия сертификату (иначе была бы PKIX path building failed).
 * Это несовместимость cipher suites.
 *
 * РЕШЕНИЕ: OkHttpClient bean с connectionSpecs = [COMPATIBLE_TLS, CLEARTEXT]
 * COMPATIBLE_TLS включает TLS_RSA_* суиты наряду с ECDHE_RSA_*,
 * что позволяет Kaspersky proxy выбрать поддерживаемый шифр.
 *
 * Spring AI 2.0 подхватывает OkHttpClient bean из контекста автоматически
 * в SpringAiOpenAiHttpClient через @ConditionalOnMissingBean.
 *
 * ПРЕДВАРИТЕЛЬНОЕ УСЛОВИЕ: Kaspersky root CA должен быть в JVM cacerts:
 *   keytool -importcert -cacerts -alias kaspersky-root -file kaspersky.crt
 */
@Configuration
public class ClientConfig {

    /**
     * SSLContext с TrustManager из JVM cacerts (включая Kaspersky root).
     * Используется явно в OkHttpClient — в отличие от SSLContext.setDefault(),
     * который OkHttp игнорирует после инициализации клиента.
     */
    @Bean
    public SSLContext corporateSslContext() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null); // читает JVM cacerts
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * X509TrustManager из JVM cacerts — требуется OkHttp для явной передачи
     * вместе с SSLSocketFactory (иначе OkHttp выбрасывает IllegalStateException).
     */
    @Bean
    public X509TrustManager corporateTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return Arrays.stream(tmf.getTrustManagers())
                .filter(tm -> tm instanceof X509TrustManager)
                .map(tm -> (X509TrustManager) tm)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No X509TrustManager in cacerts"));
    }

    /**
     * OkHttpClient с COMPATIBLE_TLS — включает RSA key exchange cipher suites
     * (TLS_RSA_WITH_AES_256_GCM_SHA384 и др.), которые требует Kaspersky proxy.
     *
     * Spring AI 2.0 (SpringAiOpenAiHttpClient) подхватывает этот бин
     * автоматически через механизм кастомизации OkHttpClient в контексте.
     *
     * ConnectionSpec.COMPATIBLE_TLS vs MODERN_TLS:
     *   MODERN_TLS   = только ECDHE_RSA + ECDHE_ECDSA (TLS 1.2/1.3)
     *   COMPATIBLE_TLS = + TLS_RSA_WITH_AES_* (TLS 1.0-1.2) — нужно для корп. proxy
     */
    @Bean
    @DependsOn({"corporateSslContext", "corporateTrustManager"})
    public OkHttpClient okHttpClient(SSLContext sslContext,
                                     X509TrustManager trustManager) {
        return new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .connectionSpecs(List.of(
                        ConnectionSpec.COMPATIBLE_TLS,  // RSA + ECDHE cipher suites
                        ConnectionSpec.CLEARTEXT         // для http:// эндпоинтов
                ))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * GitLabApi использует тот же OkHttpClient через DependsOn,
     * чтобы TLS настройки применились до первого запроса к GitLab.
     */
    @Bean
    @DependsOn("okHttpClient")
    public GitLabApi gitLabApi(AppProperties properties) {
        GitLabApi api = new GitLabApi(properties.gitlab().url(), properties.gitlab().token());
        api.setRequestTimeout(5000, 30000);
        return api;
    }

    /**
     * RestClient для HTTP-запросов к java-class-context (порт 8084).
     */
    @Bean
    @DependsOn("okHttpClient")
    public RestClient restClient(AppProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.classContext().url())
                .build();
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    @Lazy
    public ChatClient.Builder chatClientBuilder(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
