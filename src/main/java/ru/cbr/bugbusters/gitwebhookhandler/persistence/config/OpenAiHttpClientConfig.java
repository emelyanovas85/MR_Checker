package ru.cbr.bugbusters.gitwebhookhandler.persistence.config;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Кастомная конфигурация OkHttpClient для Spring AI OpenAI-клиента.
 *
 * <p>По умолчанию Spring AI выставляет callTimeout, который убивает
 * длинные запросы с tool-calling цепочкой (~4–5 мин). Здесь:
 * <ul>
 *   <li>{@code callTimeout(0)} — снимает общий лимит на весь HTTP-вызов</li>
 *   <li>{@code readTimeout(60 мин)} — разрешает читать ответ до 1 часа</li>
 *   <li>{@code connectTimeout(30 с)} — оставляем разумный лимит на connect</li>
 * </ul>
 *
 * <p>Spring AI 2.x автоматически подхватывает бин {@link OkHttpClient}
 * из контекста через {@code SpringAiOpenAiHttpClient}.
 */
@Slf4j
@Configuration
public class OpenAiHttpClientConfig {

    @Bean
    public OkHttpClient openAiOkHttpClient() {
        log.info("[OpenAiHttpClientConfig] callTimeout=0 (без лимита), readTimeout=60min, connectTimeout=30s");
        return new OkHttpClient.Builder()
                .callTimeout(0, TimeUnit.MILLISECONDS)   // 0 = без общего таймаута на весь вызов
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.MINUTES)        // ответ может читаться до 1 часа
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }
}
