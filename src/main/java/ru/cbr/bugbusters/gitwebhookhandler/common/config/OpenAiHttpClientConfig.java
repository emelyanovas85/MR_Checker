package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Конфигурация OkHttpClient для Spring AI OpenAI-клиента.
 *
 * <p>Spring AI (SpringAiOpenAiHttpClient) использует OkHttp напрямую, НЕ через
 * spring.http.client.*. Дефолтный readTimeout в openai-java-core = 240 секунд,
 * что убивает длинные LLM-вызовы (группировка, tool-calling цепочки).
 *
 * <p>Этот бин подхватывается Spring AI автоматически и заменяет дефолтный клиент.
 */
@Configuration
public class OpenAiHttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                // Подключение к серверу — оставляем разумным
                .connectTimeout(30, TimeUnit.SECONDS)
                // Ключевой фикс: заменяет дефолтные 240s openai-java-core.
                // Позволяет длинным LLM-вызовам (группировка, tool-calling) жить до 1 часа.
                .readTimeout(1, TimeUnit.HOURS)
                // Отправка тела запроса (prompt) — достаточно минуты
                .writeTimeout(60, TimeUnit.SECONDS)
                // 0 = без общего лимита на весь call (connect + write + server + read).
                // Защита от случайного выставления callTimeout автоконфигурацией Spring AI.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }
}
