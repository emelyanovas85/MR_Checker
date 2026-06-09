# MR Checker — Async AI Code Review Bot

Spring Boot-сервис, который автоматически запускает **двухэтапный AI code review** при открытии/переоткрытии/апруве Merge Request в GitLab. Ревью публикуется структурированным markdown-комментарием прямо в MR.

---

## Как это работает

```
GitLab MR event
      │
      ▼
webhook-distributor (SSE)  ← GitLabWebhookSubscriber
      │
      ▼
GitLabWebhookDispatcher    ← маршрутизация по типу события
      │
      ▼
MergeRequestHookHandler    ← фильтр: open / reopen / approved
      │
      ▼
MrReviewOrchestrator (@Async)
      │
      ├─1─ ClassContextClient   →  java-class-context (port 8084)
      │        POST /api/review-sessions        → sessionId
      │        POST /api/structure/markdown     → List<String> file contexts
      │        DELETE /api/review-sessions      → terminate session
      │
      ├─2─ LlmGroupingService   →  LLM (первый промпт: grouping-prompt.md)
      │        группирует изменённые файлы в List<RefactoringGroup>
      │
      ├─3─ LlmReviewService     →  LLM (второй промпт: system-prompt.md)
      │        параллельные запросы по каждой группе
      │        LLM может вызывать Spring AI тулы ClassContextToolsProvider:
      │            getSourceLines(qualifiedName, rows)   → POST /api/source-lines/gitlab
      │            getSourceFile(className)              → POST /api/source-file
      │
      ├─4─ MarkdownCommentFormatter  ← собирает единый <details>/<summary> комментарий
      │
      └─5─ GitLabNotesPublisher  →  GitLab Notes API
               POST /api/v4/projects/:id/merge_requests/:iid/notes
```

### Когда запускается ревью

Ревью запускается **автоматически** при следующих событиях в GitLab:

- MR **открыт** (`open`)
- MR **переоткрыт** (`reopen`)
- MR **апрувнут** (`approved`)

Никаких ручных команд писать не нужно.

### Управление сессиями

Каждый запуск ревью создаёт сессию в сервисе `java-class-context`. Сессия привязана к `projectId::mrIid`. При повторном webhook для того же MR **предыдущая сессия принудительно завершается** перед созданием новой. Сессия гарантированно удаляется в блоке `finally`, даже при ошибке.

---

## Стек

| Компонент | Версия |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.x |
| Spring AI | 1.0.0 GA |
| GitLab4J | 6.0.0 |
| webhook-distributor-client | internal |
| Gradle Wrapper | 8.14+ |

---

## Структура проекта

```
src/main/
├── java/ru/cbr/bugbusters/gitwebhookhandler/
│   ├── GitlabWebhookHandlerApplication.java
│   ├── common/
│   │   └── config/
│   │       ├── AppProperties.java            # @ConfigurationProperties: gitlab, classContext, ai
│   │       ├── AsyncConfig.java              # reviewExecutor (thread pool)
│   │       └── ClientConfig.java             # GitLabApi, ChatClient, RestClient
│   ├── exceptions/
│   │   └── GlobalExceptionHandler.java       # @RestControllerAdvice, ProblemDetail
│   ├── review/
│   │   ├── api/
│   │   │   └── ReviewTriggerCommand.java     # данные MR из webhook
│   │   ├── domain/
│   │   │   ├── GroupReviewResult.java        # результат ревью одной группы
│   │   │   └── RefactoringGroup.java         # DTO группы файлов из LLM
│   │   └── service/
│   │       ├── MrReviewOrchestrator.java     # главный оркестратор (@Async), flow 1-5
│   │       ├── ClassContextClient.java       # HTTP-клиент к java-class-context (port 8084)
│   │       ├── ClassContextToolsProvider.java# Spring AI тулы для LLM (prototype scope)
│   │       ├── LlmGroupingService.java       # этап 1: группировка файлов
│   │       ├── LlmReviewService.java         # этап 2: параллельное ревью групп
│   │       ├── MarkdownCommentFormatter.java # форматирование итогового комментария
│   │       └── GitLabNotesPublisher.java     # публикация комментария в GitLab
│   ├── swagger/
│   │   └── OpenApiConfig.java               # Springdoc OpenAPI конфигурация
│   └── webhook/
│       ├── domain/
│       │   └── MergeRequestHookPayload.java  # DTO входящего webhook-события
│       └── service/
│           ├── GitLabWebhookSubscriber.java  # SSE-подписка на webhook-distributor
│           ├── GitLabWebhookDispatcher.java  # маршрутизация по типу события
│           └── MergeRequestHookHandler.java  # фильтрация и запуск ревью
└── resources/
    ├── application.yml
    ├── application-local.yml
    └── prompts/
        ├── grouping-prompt.md   # первый промпт: группировка файлов → RefactoringGroup[]
        └── system-prompt.md     # второй промпт: ревью одной группы с тулами
```

---

## Конфигурация

Все параметры задаются через переменные окружения:

| Переменная | Описание | По умолчанию |
|---|---|---|
| `GITLAB_URL` | Базовый URL GitLab-инстанса | `http://10.1.5.6/` |
| `GITLAB_TOKEN` | Personal Access Token для GitLab API (публикация комментариев) | `changeme` |
| `OPENAI_API_KEY` | API-ключ OpenAI-совместимого провайдера | `dummy` |
| `OPENAI_BASE_URL` | Базовый URL OpenAI-совместимого API | `https://chat.ehd-zr.cbr.ru` |
| `OPENAI_MODEL` | Модель | `Qwen/Qwen3.5-397B-A17B-GPTQ-Int4` |
| `OPENAI_COMPLETIONS_PATH` | Путь к completions endpoint | `/openai/chat/completions` |
| `CLASS_CONTEXT_URL` | URL сервиса java-class-context | `http://10.1.5.97:8084` |
| `CLASS_CONTEXT_GITLAB_TOKEN` | GitLab PAT для создания сессии в class-context | `= GITLAB_TOKEN` |
| `WEBHOOK_DISTRIBUTOR_URL` | URL webhook-distributor | `http://10.1.5.97:8081` |
| `APP_AI_GROUPING_PROMPT_FILE` | Путь к промпту группировки | `classpath:prompts/grouping-prompt.md` |
| `APP_AI_REVIEW_PROMPT_FILE` | Путь к промпту ревью | `classpath:prompts/system-prompt.md` |

### Переопределение промптов без пересборки

Промпты хранятся в `src/main/resources/prompts/` и загружаются при старте.
Чтобы использовать внешний файл — смонтируйте его в контейнер и передайте путь:

```yaml
environment:
  APP_AI_GROUPING_PROMPT_FILE: file:/etc/mr-checker/grouping-prompt.md
  APP_AI_REVIEW_PROMPT_FILE:   file:/etc/mr-checker/system-prompt.md
volumes:
  - ./my-grouping-prompt.md:/etc/mr-checker/grouping-prompt.md
  - ./my-system-prompt.md:/etc/mr-checker/system-prompt.md
```

---

## Зависимые сервисы

| Сервис | Описание | Конфиг |
|---|---|---|
| **webhook-distributor** | Принимает webhook-события от GitLab, раздаёт через SSE | `WEBHOOK_DISTRIBUTOR_URL` |
| **java-class-context** | Создаёт сессии ревью; отдаёт структуру и исходники изменённых файлов | `CLASS_CONTEXT_URL` |
| **LLM API** | OpenAI-совместимый API для группировки и ревью кода | `OPENAI_BASE_URL` |
| **GitLab** | Источник webhook-событий и цель для публикации комментариев | `GITLAB_URL` |

### API сервиса java-class-context (port 8084)

| Метод | Путь | Описание |
|---|---|---|
| `POST` | `/api/review-sessions` | Создать сессию ревью для MR |
| `POST` | `/api/structure/markdown` | Получить markdown-структуру изменённых файлов (depth=1) |
| `DELETE` | `/api/review-sessions` | Удалить сессию |
| `POST` | `/api/source-lines/gitlab` | Получить фрагменты исходного кода по диапазонам строк |
| `POST` | `/api/source-file` | Получить полный исходник файла по имени класса |

---

## Запуск

### Локально (профиль `local`)

```bash
export GITLAB_URL=https://your.gitlab.com
export GITLAB_TOKEN=glpat-xxxxxxxxxxxx
export OPENAI_API_KEY=sk-xxxxxxxxxxxx
export OPENAI_BASE_URL=https://api.openai.com
export CLASS_CONTEXT_URL=http://localhost:8084
export WEBHOOK_DISTRIBUTOR_URL=http://localhost:8081

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
      OPENAI_BASE_URL: https://api.openai.com
      OPENAI_MODEL: gpt-4o
      CLASS_CONTEXT_URL: http://java-class-context:8084
      WEBHOOK_DISTRIBUTOR_URL: http://webhook-distributor:8081
```

---

## Сборка и тесты

```bash
# Сборка JAR
./gradlew build

# Запуск тестов
./gradlew test

# Запуск приложения
./gradlew bootRun

# Swagger UI (при запущенном сервисе)
http://localhost:8081/swagger-ui.html

# OpenAPI JSON
http://localhost:8081/api-docs
```

### Тестовое покрытие

| Класс | Тест | Что проверяется |
|---|---|---|
| `MarkdownCommentFormatter` | `MarkdownCommentFormatterTest` | Формат заголовка, секции `<details>`, экранирование HTML |
| `LlmGroupingService` | `LlmGroupingServiceTest` | Парсинг JSON из LLM-ответа, обработка пустого ответа |
| `MrReviewOrchestrator` | `MrReviewOrchestratorTest` | Полный happy-path flow, терминирование сессии при ошибке |
| `ClassContextClient` | `ClassContextClientTest` | createSession, fetchStructures, deleteSession |
| `MergeRequestHookHandler` | `MergeRequestHookHandlerTest` | Фильтрация событий: open/reopen/approved пропускаются, остальные игнорируются |
