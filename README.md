# Webhook Handler

Spring Boot приложение для приёма и обработки webhook-событий от **GitLab** и **GitHub**.

## Технологии

- **Java 21** + **Spring Boot 4.0.6** + **Spring Framework 7.0.7**
- **Gradle 8.14** (Kotlin DSL)
- Spring Web MVC, Validation, Actuator, Lombok, Jackson

## Архитектура
 
```
┌─────────────────────────────────────────────────────────┐
│                    REST Controllers                     │
│  POST /api/webhook/gitlab   POST /api/webhook/github    │
└──────────────┬──────────────────────────┬──────────────┘
               │                          │
               ▼                          ▼
  GitLabWebhookService          GitHubWebhookService
  (X-Gitlab-Token)              (HMAC-SHA256 signature)
               │                          │
               ▼                          ▼
  [GitLabEventHandler]          [GitHubEventHandler]
   ├─ PushEventHandler           ├─ GitHubPushEventHandler
   ├─ MergeRequestEventHandler   ├─ GitHubPullRequestEventHandler
   ├─ PipelineEventHandler       ├─ GitHubWorkflowRunEventHandler
   └─ IssueEventHandler          └─ GitHubIssuesEventHandler
```

Оба набора хендлеров реализуют общий интерфейс `WebhookEventHandler`.

## Эндпоинты

| Метод | URL | Источник |
|-------|-----|----------|
| POST  | `/api/webhook/gitlab`  | GitLab  |
| POST  | `/api/webhook/github`  | GitHub  |
| GET   | `/actuator/health`     | —       |

## Запуск
 
```bash
# Без токенов (только для разработки)
./gradlew bootRun

# С токенами
WEBHOOK_GITLAB_SECRET_TOKEN=gl-secret \
WEBHOOK_GITHUB_SECRET_TOKEN=gh-secret \
./gradlew bootRun
```

## Настройка GitLab Webhook

1. Project → **Settings → Webhooks → Add new webhook**
2. URL: `https://your-server:8080/api/webhook/gitlab`
3. Secret token: значение `WEBHOOK_GITLAB_SECRET_TOKEN`
4. Триггеры: Push, Merge Request, Pipeline, Issues

## Настройка GitHub Webhook

1. Repository → **Settings → Webhooks → Add webhook**
2. Payload URL: `https://your-server:8080/api/webhook/github`
3. Content type: `application/json`
4. Secret: значение `WEBHOOK_GITHUB_SECRET_TOKEN`
5. Триггеры: Push, Pull requests, Workflow runs, Issues

> GitHub подписывает тело запроса через **HMAC-SHA256** и передаёт подпись
> в заголовке `X-Hub-Signature-256`. Приложение автоматически проверяет её.

## Поддерживаемые события

### GitLab (`X-Gitlab-Event`)
| Заголовок | Хендлер |
|-----------|--------|
| `Push Hook` | `PushEventHandler` |
| `Merge Request Hook` | `MergeRequestEventHandler` |
| `Pipeline Hook` | `PipelineEventHandler` |
| `Issue Hook` | `IssueEventHandler` |

### GitHub (`X-GitHub-Event`)
| Заголовок | Хендлер |
|-----------|--------|
| `push` | `GitHubPushEventHandler` |
| `pull_request` | `GitHubPullRequestEventHandler` |
| `workflow_run` | `GitHubWorkflowRunEventHandler` |
| `issues` | `GitHubIssuesEventHandler` |

## Добавление нового хендлера

```java
@Slf4j
@Component
public class GitHubReleaseEventHandler implements GitHubEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "release".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(JsonNode payload) {
        // ваша логика
    }
}
```

Spring автоматически подхватит новый хендлер — ничего больше менять не нужно.

## Тесты

```bash
./gradlew test
```
