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
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
     * RestClient для HTTP-запросов к сервису java-class-context (порт 8084).
     * Использует тот же OkHttpClient, чтобы работать через Kaspersky TLS inspection.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder, OkHttpClient okHttpClient) {
        return builder
                .requestFactory(new OkHttp3ClientHttpRequestFactory(okHttpClient))
                .build();
    }

    /**
     * RestClient.Builder с OkHttp transport — подхватывается Spring AI OpenAI-клиентом
     * автоматически через auto-configuration, что решает проблему TLS handshake
     * при работе через корпоративный Kaspersky TLS inspection proxy.
     */
    @Bean
    public RestClient.Builder restClientBuilder(OkHttpClient okHttpClient) {
        return RestClient.builder()
                .requestFactory(new OkHttp3ClientHttpRequestFactory(okHttpClient));
    }

    /**
     * OkHttpClient с явным TLS 1.2 и расширенным списком cipher suites.
     * Необходимо для работы через корпоративный Kaspersky TLS inspection,
     * который выбирает AES256-GCM-SHA384 без ECDHE.
     * HTTP/2 отключён — сервер не поддерживает ALPN h2.
     *
     * TODO: убрать TLS_RSA_* suites (без forward secrecy), когда Kaspersky
     *       inspection будет обновлён до ECDHE-compatible конфигурации.
     */
    @Bean
    public OkHttpClient okHttpClient() {
        ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .cipherSuites(
                        // Kaspersky inspection выбирает именно этот suite (AES256-GCM-SHA384):
                        // TODO: удалить после обновления Kaspersky — нет forward secrecy
                        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                        // ECDHE как предпочтительные варианты (forward secrecy):
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
                )
                .build();

        return new OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectionSpecs(List.of(spec))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
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
