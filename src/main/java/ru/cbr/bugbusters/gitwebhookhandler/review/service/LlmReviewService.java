package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Запускает LLM-ревью для одного контекста.
 * Каждый вызов создаёт изолированный ChatClient с тулами граф-сервиса.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmReviewService {

    private final ChatClient.Builder chatClientBuilder;
    // ObjectProvider позволяет получать prototype-бины (по одному на каждый контекст)
    private final ObjectProvider<GraphServiceToolsProvider> toolsProviderFactory;

    @Value("${app.ai.prompt-file:classpath:prompts/system-prompt.md}")
    private Resource promptResource;

    private String systemPrompt;

    @PostConstruct
    void loadPrompt() throws IOException {
        systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("System prompt загружен из: {}", promptResource.getDescription());
    }

    /**
     * Выполняет ревью одного контекста.
     *
     * @param index   порядковый номер контекста
     * @param context текст контекста (код для ревью), полученный от граф-сервиса
     * @return результат ревью
     */
    public GroupReviewResult review(int index, String context) {
        try {
            GraphServiceToolsProvider tools = toolsProviderFactory.getObject();
            String response = chatClientBuilder.build()
                    .prompt()
                    .system(systemPrompt)
                    .user(buildPrompt(index, context))
                    .tools(tools)
                    .call()
                    .content();
            return GroupReviewResult.success(index, response == null ? "No issues found." : response);
        } catch (Exception e) {
            log.error("Ошибка LLM-запроса для контекста index={}", index, e);
            return GroupReviewResult.failure(index, e.getMessage());
        }
    }

    private String buildPrompt(int index, String context) {
        return """
                ## Context group #%d

                %s
                """.formatted(index + 1, context);
    }
}
