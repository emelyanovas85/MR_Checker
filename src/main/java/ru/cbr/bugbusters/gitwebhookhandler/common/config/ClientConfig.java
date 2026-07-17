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
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
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
     * TrustManagerFactory, инициализированный из cacerts JVM.
     * Корпоративный CA (Kaspersky root) должен быть там импортирован:
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
     * SSLContext на базе корпоративного TrustStore.
     * Используется как для OkHttpClient (Spring AI), так и для JDK HttpClient.
     */
    @Bean
    public SSLContext sslContext(TrustManagerFactory tmf) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * OkHttpClient для Spring AI OpenAI-клиента.
     * Spring AI 2.0 использует okhttp3 внутри SpringAiOpenAiHttpClient.
     * Инжектируем SSLSocketFactory с корпоративным CA, чтобы
     * TLS handshake проходил через Kaspersky TLS inspection proxy.
     *
     * TODO: удалить TLS_RSA_* suites (без forward secrecy), когда Kaspersky
     *       inspection будет обновлён до ECDHE-compatible конфигурации.
     */
    @Bean
    public OkHttpClient okHttpClient(SSLContext sslContext,
                                     TrustManagerFactory tmf) {
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
     * OpenAiApi с кастомным OkHttpClient — подменяет внутренний HTTP-клиент
     * Spring AI на тот, что проходит через Kaspersky TLS inspection.
     */
    @Bean
    public OpenAiApi openAiApi(AppProperties properties, OkHttpClient okHttpClient) {
        return OpenAiApi.builder()
                .baseUrl(properties.ai() != null ? System.getenv().getOrDefault("OPENAI_BASE_URL", "https://chat.ehd-zr.cbr.ru") : "https://chat.ehd-zr.cbr.ru")
                .apiKey(System.getenv().getOrDefault("OPENAI_API_KEY", ""))
                .httpClient(okHttpClient)
                .build();
    }

    /**
     * JDK HttpClient для RestClient (запросы к java-class-context, порт 8084).
     * HTTP — без TLS, но используем тот же SSLContext если вдруг endpoint перейдёт на HTTPS.
     */
    @Bean
    public HttpClient httpClient(SSLContext sslContext) throws Exception {
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * JdkClientHttpRequestFactory — адаптер поверх JDK HttpClient для RestClient.
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
     * ObjectMapper с настройками:
     * - не падает на неизвестные поля
     * - даты в ISO-8601
     * - поддержка java.time.*
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
