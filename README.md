# MR Checker — Async AI Code Review Bot

Spring Boot-приложение, которое подписывается на GitLab webhook-события через **webhook-distributor** (SSE), автоматически запускает асинхронный AI code review при открытии/переоткрытии/апруве Merge Request и публикует структурированный комментарий прямо в MR.

## Как это работает

```
GitLab → webhook-distributor (SSE)
        │
        ▼
GitLabWebhookSubscriber  — подписка через SSE
        │
        ▼
GitLabWebhookDispatcher  — маршрутизация по типу события
        │
        ▼
MergeRequestHookHandler  — фильтрует: open / reopen / approved
        │
        ▼
MrReviewOrchestrator  (@Async)
        │
        ├── GraphServiceClient  → граф-сервис (POST /api/graph/contexts)
        │       получает список контекстов (фрагментов кода) для ревью
        │
        ├── LlmReviewService  → OpenAI ChatClient
        │       параллельные запросы по каждому контексту
        │
        ├── MarkdownCommentFormatter
        │       собирает единый комментарий с <details>/<summary>
        │
        └── GitLabNotesPublisher  → GitLab Notes API
                POST /api/v4/projects/:id/merge_requests/:iid/notes
```

### Когда запускается ревью

Ревью запускается **автоматически** при следующих событиях в GitLab:
- MR **открыт** (`open`)
- MR **переоткрыт** (`reopen`)
- MR **апрувнут** (`approved`)

Никаких ручных команд писать не нужно.

## Стек

| Компонент | Версия |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.x |
| Spring AI | 1.0.0 GA |
| GitLab4J | 6.0.0 |
| webhook-distributor-client | internal |
| Gradle Wrapper | 8.14+ |

## Структура проекта

```
src/main/
├── java/ru/cbr/bugbusters/gitwebhookhandler/
│   ├── GitlabWebhookHandlerApplication.java
│   ├── common/
│   │   └── config/
│   │       ├── AppProperties.java           # @ConfigurationProperties
│   │       ├── AsyncConfig.java             # reviewExecutor (thread pool)
│   │       └── ClientConfig.java            # GitLabApi, ChatClient, RestClient
│   ├── exceptions/
│   │   └── GlobalExceptionHandler.java      # @RestControllerAdvice, ProblemDetail
│   ├── review/
│   │   ├── api/                             # DTO: ReviewTriggerCommand, GroupReviewResult
│   │   ├── domain/                          # GroupReviewResult и другие domain-объекты
│   │   └── service/
│   │       ├── MrReviewOrchestrator.java    # главный оркестратор (@Async)
│   │       ├── GraphServiceClient.java      # HTTP-клиент к граф-сервису
│   │       ├── GraphServiceToolsProvider.java # Spring AI tools для LLM
│   │       ├── LlmReviewService.java        # параллельные запросы в LLM
│   │       ├── MarkdownCommentFormatter.java # форматирование комментария
│   │       └── GitLabNotesPublisher.java    # публикация комментария в GitLab
│   └── webhook/
│       ├── domain/                          # MergeRequestHookPayload DTO
│       └── service/
│           ├── GitLabWebhookSubscriber.java # SSE-подписка на webhook-distributor
│           ├── GitLabWebhookDispatcher.java # маршрутизация по типу события
│           └── MergeRequestHookHandler.java # обработка Merge Request Hook
└── resources/
    ├── application.yml
    ├── application-local.yml
    └── prompts/
        └── system-prompt.md                 # system prompt для LLM (редактируется без пересборки)
```

## Конфигурация

Все параметры задаются через переменные окружения:

| Переменная | Описание | По умолчанию |
|---|---|---|
| `GITLAB_URL` | Базовый URL GitLab-инстанса | `http://localhost` |
| `GITLAB_TOKEN` | Personal Access Token для GitLab API | `changeme` |
| `OPENAI_API_KEY` | API-ключ OpenAI | `dummy` (только для старта) |
| `OPENAI_BASE_URL` | Базовый URL OpenAI-совместимого API | `https://api.openai.com` |
| `OPENAI_MODEL` | Модель OpenAI | `gpt-4o-mini` |
| `OPENAI_COMPLETIONS_PATH` | Путь к completions endpoint | `/v1/chat/completions` |
| `GRAPH_SERVICE_URL` | URL граф-сервиса | `http://localhost:8090` |
| `WEBHOOK_DISTRIBUTOR_URL` | URL webhook-distributor | `http://localhost:8080` |
| `APP_AI_PROMPT_FILE` | Путь к файлу system prompt | `classpath:prompts/system-prompt.md` |

### Переопределение system prompt

Промпт хранится в `src/main/resources/prompts/system-prompt.md` и загружается при старте.
Чтобы использовать внешний файл без пересборки — смонтируйте его в контейнер и передайте путь:

```yaml
environment:
  APP_AI_PROMPT_FILE: file:/etc/mr-checker/system-prompt.md
volumes:
  - ./my-prompt.md:/etc/mr-checker/system-prompt.md
```

## Запуск

### Локально

```bash
export GITLAB_URL=https://your.gitlab.com
export GITLAB_TOKEN=glpat-xxxxxxxxxxxx
export OPENAI_API_KEY=sk-xxxxxxxxxxxx
export GRAPH_SERVICE_URL=http://localhost:8090
export WEBHOOK_DISTRIBUTOR_URL=http://localhost:8080

./gradlew bootRun
```

### Docker Compose

```yaml
services:
  mr-checker:
    image: mr-checker:latest
    ports:
      - "8081:8081"
    environment:
      GITLAB_URL: https://your.gitlab.com
      GITLAB_TOKEN: glpat-xxxxxxxxxxxx
      OPENAI_API_KEY: sk-xxxxxxxxxxxx
      GRAPH_SERVICE_URL: http://graph-service:8090
      WEBHOOK_DISTRIBUTOR_URL: http://webhook-distributor:8080
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

- **webhook-distributor** — принимает webhook-события от GitLab и раздаёт их подписчикам через SSE. Ожидается на `WEBHOOK_DISTRIBUTOR_URL`.
- **граф-сервис** — принимает данные MR и возвращает список контекстов (фрагментов кода) для ревью. Ожидается на `GRAPH_SERVICE_URL`.
