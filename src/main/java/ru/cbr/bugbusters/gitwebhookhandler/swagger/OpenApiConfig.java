package ru.cbr.bugbusters.gitwebhookhandler.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MR Checker — AI Code Review Bot")
                        .description("""
                                ## MR Checker

                                Spring Boot-сервис автоматического AI-ревью Merge Request'ов в GitLab.

                                ### Как работает
                                1. Подписывается на события GitLab через **webhook-distributor** (SSE).
                                2. При открытии/переоткрытии/апруве MR запускает асинхронный flow:
                                   - создаёт сессию в сервисе **java-class-context** (port 8084)
                                   - получает структуру изменённых файлов
                                   - LLM группирует файлы (`grouping-prompt.md`)
                                   - параллельное LLM-ревью каждой группы (`system-prompt.md`) с тулами доступа к исходному коду
                                   - публикует структурированный markdown-комментарий в GitLab MR

                                ### Actuator
                                - `GET /actuator/health` — состояние сервиса
                                - `GET /actuator/info`   — мета-информация
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("BugBusters")
                                .url("https://github.com/emelyanovas85/MR_Checker"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local development")
                ));
    }
}
