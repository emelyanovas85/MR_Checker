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
                        .title("""
                                Webhook Handler API
                                """)
                        .description("""
                                 <img src="/images/img.png" alt="А шо ты хотел от тракториста?" width="100"/>
                                
                                
                                ## Универсальный обработчик webhook-ов от GitLab и GitHub.

                                **GitLab** использует plain-text токен в заголовке `X-Gitlab-Token`.

                                **GitHub** использует HMAC-SHA256 подпись в заголовке `X-Hub-Signature-256`.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Webhook Handler")
                                .url("https://github.com/emelyanovas85/git-webhook-handler"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local development")
                ));
    }
}
