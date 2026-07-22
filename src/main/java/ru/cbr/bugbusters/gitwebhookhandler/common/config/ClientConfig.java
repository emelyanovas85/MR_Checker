package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openai.core.Timeout;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.LlmRateLimiter;

import java.time.Duration;

/**
 * Конфигурация HTTP-клиентов.
 *
 * TLS-проблема с корпоративным сервером chat.ehd-zr.cbr.ru решена
 * на уровне JVM, а не на уровне кода:
 *
 * 1. Сертификат cbr-S02D-CBRDC01-CA импортирован в cacerts Temurin 21:
 *    keytool -importcert -keystore $JAVA_HOME/lib/security/cacerts \
 *      -alias cbr-root-ca -storepass changeit -file cbr-root-ca.pem
 *
 * 2. Удалён запрет TLS_RSA_* из файла conf/security/java.security:
 *    было: jdk.tls.disabledAlgorithms=... TLS_RSA_*, ...
 *    стало: jdk.tls.disabledAlgorithms=... (без TLS_RSA_*)
 *
 *    Eclipse Temurin 21 добавляет TLS_RSA_* в disabledAlgorithms
 *    (жёстче чем Oracle JDK). Корпоративный сервер требует
 *    TLS_RSA_WITH_AES_256_GCM_SHA384 (RSA key exchange, TLS 1.2).
 *
 * Таймаут OpenAI:
 *    SpringAiOpenAiHttpClient.Builder предоставляет метод .timeout(Duration),
 *    который устанавливает общий callTimeout OkHttp поверх OkHttpClient-бина.
 *    Передаём Duration.ZERO чтобы снять ограничение: конкретные connect/read/write
 *    таймауты управляются через OkHttpClient bean в OpenAiHttpClientConfig.
 */
@Configuration
public class ClientConfig {

    /**
     * Кастомизация SpringAiOpenAiHttpClient.Builder: таймауты + rate-limiter.
     *
     * <p>Без этого бина Spring AI применяет дефолт openai-java-core (240s),
     * который убивает длинные LLM-вызовы (группировка, tool-calling цепочки).
     * Duration.ZERO = OkHttp callTimeout(0) = без общего лимита на весь call.
     *
     * <p>Интерцептор {@link OkHttpRateLimiterInterceptor} перехватывает **каждый** HTTP-запрос
     * к LLM API, включая tool-calling петли внутри Spring AI ToolCallingAdvisor.
     * Это гарантирует, что лимит 7 запросов/5сек не будет превышен даже при параллельных
     * группах ревью.
     */
    @Bean
    public OpenAiHttpClientBuilderCustomizer openAiHttpClientBuilderCustomizer(LlmRateLimiter rateLimiter) {
        return builder -> builder
                .timeout(Timeout.builder()
                        .request(Duration.ZERO)          // callTimeout = без лимита
                        .connect(Duration.ofHours(1))
                        .read(Duration.ofHours(1))
                        .write(Duration.ofHours(1))
                        .build())
                .interceptor(new OkHttpRateLimiterInterceptor(rateLimiter));
    }

    @Bean
    public GitLabApi gitLabApi(AppProperties properties) {
        GitLabApi api = new GitLabApi(properties.gitlab().url(), properties.gitlab().token());
        api.setRequestTimeout(5000, 30000);
        return api;
    }

    @Bean
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
