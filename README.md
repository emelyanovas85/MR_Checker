# GitLab Webhook Handler — Async MR Review Bot

Spring Boot-приложение, которое принимает GitLab webhook-события, запускает асинхронный AI code review по группам изменений и публикует единый структурированный комментарий к Merge Request.

## Как это работает

```
GitLab Note Hook (MR comment: "review")
        │
        ▼
POST /api/v1/webhooks/gitlab
        │
        ▼
NoteHookHandler — проверяет команду
        │
        ▼
MrReviewOrchestrator
        │
        ├── MrRagContextClient → java-mr-rag (POST /api/review)
        │       получает группы изменений с контекстом
        │
        ├── LlmReviewService → OpenAI ChatClient
        │       параллельные запросы по каждой группе (virtual threads)
        │
        ├── MarkdownCommentFormatter
        │       собирает единый комментарий с <details>/<summary>
        │
        └── GitLabNotesPublisher → GitLab Notes API
                POST /api/v4/projects/:id/merge_requests/:iid/notes
```

### Запуск review

Напишите комментарий к MR в GitLab:
```
review
```
Приложение получит webhook, запустит анализ и опубликует AI-ответ комментарием к тому же MR.

## Стек

| Компонент | Версия |
|---|---|
| Java | 21 (virtual threads) |
| Spring Boot | 3.5.6 |
| Spring AI | 1.0.0 GA |
| Spring Data JPA | — (managed by Boot) |
| H2 | in-memory |
| Flyway | schema migrations |
| GitLab4J | 6.0.0 |
| Gradle Wrapper | 8.14+ |

## Структура проекта

```
src/main/java/ru/cbr/bugbusters/gitwebhookhandler/
├── GitlabWebhookHandlerApplication.java
├── common/
│   └── config/
│       ├── AppProperties.java          # @ConfigurationProperties
│       ├── AsyncConfig.java            # Virtual threads executor
│       └── ClientConfig.java           # GitLabApi, ChatClient, RestClient beans
├── controllers/
│   └── GitLabWebhookController.java    # POST /api/v1/webhooks/gitlab
├── exceptions/
│   └── GlobalExceptionHandler.java     # @RestControllerAdvice, ProblemDetail
├── review/
│   ├── api/                            # DTO: ReviewGroupContext, GroupAnalysisResult
│   ├── domain/                         # ReviewEvent entity
│   ├── persistence/                    # ReviewEventRepository
│   └── service/
│       ├── MrReviewOrchestrator.java   # главный оркестратор (async)
│       ├── MrRagContextClient.java     # HTTP-клиент к java-mr-rag
│       ├── LlmReviewService.java       # параллельные запросы в LLM
│       ├── MarkdownCommentFormatter.java # форматирование <details>/<summary>
│       └── GitLabNotesPublisher.java   # публикация комментария в GitLab
├── webhook/
│   ├── domain/                         # MergeRequestEvent, NoteEvent DTO
│   └── service/
│       ├── GitLabWebhookDispatcher.java # маршрутизация webhook-событий
│       └── NoteHookHandler.java        # обработка note_events
src/main/resources/
├── application.yml
└── db/migration/
    └── V1__create_review_event.sql
```

## Конфигурация

Все параметры задаются через переменные окружения:

| Переменная | Описание | По умолчанию |
|---|---|---|
| `GITLAB_URL` | Базовый URL GitLab-инстанса | `http://localhost` |
| `GITLAB_TOKEN` | Personal Access Token для GitLab API | `changeme` |
| `GITLAB_WEBHOOK_TOKEN` | Secret token из настроек webhook | `changeme` |
| `OPENAI_API_KEY` | API-ключ OpenAI | `dummy` (только для старта) |
| `OPENAI_MODEL` | Модель OpenAI | `gpt-4o-mini` |
| `MR_RAG_URL` | URL сервиса java-mr-rag | `http://localhost:8081` |
| `REVIEW_TRIGGER_COMMAND` | Команда для запуска review | `review` |

## Запуск

### Локально

```bash
export GITLAB_URL=https://your.gitlab.com
export GITLAB_TOKEN=glpat-xxxxxxxxxxxx
export GITLAB_WEBHOOK_TOKEN=my-secret
export OPENAI_API_KEY=sk-xxxxxxxxxxxx
export MR_RAG_URL=http://localhost:8081

./gradlew bootRun
```

### Docker Compose

```yaml
services:
  webhook-handler:
    image: webhook-handler:latest
    ports:
      - "8081:8081"
    environment:
      GITLAB_URL: https://your.gitlab.com
      GITLAB_TOKEN: glpat-xxxxxxxxxxxx
      GITLAB_WEBHOOK_TOKEN: my-secret
      OPENAI_API_KEY: sk-xxxxxxxxxxxx
      MR_RAG_URL: http://mr-rag:8081
```

## Настройка webhook в GitLab

1. Перейдите в **Settings → Webhooks** вашего GitLab-проекта.
2. URL: `http://<your-host>:8081/api/v1/webhooks/gitlab`
3. Secret Token: значение `GITLAB_WEBHOOK_TOKEN`
4. Включите событие: **Comments** (Note events)
5. Сохраните.

Теперь любой комментарий `review` к MR запустит анализ.

## Формат комментария в MR

Ответ публикуется единым комментарием с раскрываемыми секциями:

```markdown
## 🤖 AI Code Review

> Автоматический анализ по **3 группам** изменений.

⚠️ **Обнаружено замечаний: 1 из 3 групп**

---

<details>
<summary>🟢 <b>auth-module</b> — ОК</summary>

Изменения корректны. Замечаний нет.

</details>

<details>
<summary>🟡 <b>payment-service</b> — Есть замечания</summary>

⚠️ Метод `processPayment()` не обрабатывает timeout-сценарий.

</details>
```

## Сборка и тесты

```bash
# Сборка
./gradlew build

# Только тесты
./gradlew test

# Запуск
./gradlew bootRun

# Swagger UI
http://localhost:8081/swagger-ui.html
```

## Зависимые сервисы

- **java-mr-rag** — сервис, который получает контекст изменений из GitLab и возвращает группы для анализа. Ожидается на `MR_RAG_URL`. Интеграция через `MrRagContextClient` (`POST /api/review`).
