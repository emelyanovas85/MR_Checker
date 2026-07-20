package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

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
 *    spring.ai.openai.http-client не влияет на OkHttp внутри openai-java SDK.
 *    Таймаут задаётся явно через бин OpenAiApi с кастомным OkHttpClient.
 */
@Configuration
public class ClientConfig {

    /**
     * Переопределяет авто-конфигурацию Spring AI для OpenAiApi.
     * Устанавливает увеличенные таймауты OkHttp для длинных LLM-запросов
     * с tool calling (модель может думать 5-10+ минут).
     */
    @Bean
    public OpenAiApi openAiApi(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey) {

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.MINUTES)
                .readTimeout(10, TimeUnit.MINUTES)
                .callTimeout(15, TimeUnit.MINUTES)
                .build();

        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .httpClient(SpringAiOpenAiHttpClient.builder()
                        .okHttpClient(httpClient)
                        .build())
                .build();
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
