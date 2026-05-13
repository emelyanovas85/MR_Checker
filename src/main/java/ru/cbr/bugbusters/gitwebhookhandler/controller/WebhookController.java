package ru.cbr.bugbusters.gitwebhookhandler.controller;

import ru.cbr.bugbusters.gitwebhookhandler.service.handler.gitlab.GitLabWebhookService;
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
@Tag(name = "GitLab Webhook", description = "Приём и обработка webhook-ов от GitLab")
public class WebhookController {

    private final GitLabWebhookService gitLabWebhookService;

    @Operation(
            summary = "Принять GitLab webhook",
            description = """
                    Принимает события от GitLab: Push, Merge Request, Pipeline, Issue.

                    Аутентификация: GitLab отправляет секретный токен в заголовке `X-Gitlab-Token`.
                    Если токен не совпадает — возвращается `401 Unauthorized`.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook успешно обработан",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
                            examples = @ExampleObject(value = "Webhook processed"))),
            @ApiResponse(responseCode = "401", description = "Неверный токен X-Gitlab-Token",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
                            examples = @ExampleObject(value = "Invalid GitLab webhook token"))),
            @ApiResponse(responseCode = "400", description = "Некорректный JSON в теле запроса")
    })
    @PostMapping("/gitlab")
    public ResponseEntity<String> handleGitLabWebhook(
            @Parameter(description = "Тип события GitLab", example = "Push Hook")
            @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,

            @Parameter(description = "Секретный токен для верификации")
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Payload от GitLab",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = {
                                    @ExampleObject(name = "Push Hook", value = """
                                            {
                                              "ref": "refs/heads/main",
                                              "user_name": "john.doe",
                                              "total_commits_count": 3
                                            }"""),
                                    @ExampleObject(name = "Merge Request Hook", value = """
                                            {
                                              "object_attributes": {
                                                "title": "Fix bug",
                                                "state": "opened",
                                                "source_branch": "feature/fix",
                                                "target_branch": "main"
                                              }
                                            }""")
                            }))
            @RequestBody ObjectNode payload) {

        log.info("Received GitLab webhook. Event: {}", eventType);
        try {
            gitLabWebhookService.process(eventType, token, payload);
            return ResponseEntity.ok("Webhook processed");
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }
    }
}
