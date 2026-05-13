package ru.cbr.bugbusters.gitwebhookhandler.controller;

import ru.cbr.bugbusters.gitwebhookhandler.service.handler.github.GitHubWebhookService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
@Tag(name = "GitHub Webhook", description = "Приём и обработка webhook-ов от GitHub")
public class GitHubWebhookController {

    private final GitHubWebhookService gitHubWebhookService;

    @Operation(
            summary = "Принять GitHub webhook",
            description = """
                    Принимает события от GitHub: push, pull_request, issues, workflow_run.

                    Аутентификация: GitHub вычисляет HMAC-SHA256 от тела запроса с секретным токеном
                    и передаёт результат в заголовке `X-Hub-Signature-256: sha256=<hex>`.
                    Если подпись не совпадает — возвращается `401 Unauthorized`.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook успешно обработан",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
                            examples = @ExampleObject(value = "Webhook processed"))),
            @ApiResponse(responseCode = "401", description = "Неверная подпись X-Hub-Signature-256",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
                            examples = @ExampleObject(value = "Invalid GitHub webhook signature"))),
            @ApiResponse(responseCode = "400", description = "Некорректный JSON в теле запроса")
    })
    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubWebhook(
            @Parameter(description = "Тип события GitHub", example = "push")
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,

            @Parameter(description = "HMAC-SHA256 подпись тела запроса", example = "sha256=abc123...")
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Payload от GitHub",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = {
                                    @ExampleObject(name = "push", value = """
                                            {
                                              "ref": "refs/heads/main",
                                              "pusher": { "name": "alice" },
                                              "commits": []
                                            }"""),
                                    @ExampleObject(name = "pull_request", value = """
                                            {
                                              "action": "opened",
                                              "pull_request": {
                                                "title": "Add feature",
                                                "state": "open",
                                                "head": { "ref": "feature/xyz" },
                                                "base": { "ref": "main" }
                                              }
                                            }""")
                            }))
            @RequestBody String rawBody,

            @org.springframework.web.bind.annotation.RequestBody(required = false) ObjectNode ignored
    ) {
        log.info("Received GitHub webhook. Event: {}", eventType);
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            ObjectNode payload = (ObjectNode) mapper.readTree(rawBody);
            gitHubWebhookService.process(eventType, signature, rawBody, payload);
            return ResponseEntity.ok("Webhook processed");
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid JSON payload");
        }
    }
}
