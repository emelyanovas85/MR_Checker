# MR Checker — Async AI Code Review Bot

Spring Boot-сервис, который автоматически запускает **двухэтапный AI code review** при открытии/переоткрытии/апруве Merge Request в GitLab. Ревью публикуется структурированным markdown-комментарием прямо в MR.  
Все события и результаты **сохраняются в файловой H2-базе данных** и переживают перезапуск контейнера.

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
      │                       ↓ audit: WebhookEventEntity (ACCEPTED/IGNORED)
      ▼
MrReviewOrchestrator (@Async)
      │                       ↓ audit: ReviewRunEntity (RUNNING → SUCCESS/ERROR)
      ├─1─ ClassContextClient   →  java-class-context (port 8084)
      │        POST /api/review-sessions        → sessionId
      │        POST /api/structure/markdown     → List<String> file contexts
      │        DELETE /api/review-sessions      → terminate session
      │
      ├─2─ LlmGroupingService   →  LLM (первый промпт: grouping-prompt.md)
      │        группирует изменённые файлы в List<RefactoringGroup>
      │        ↓ audit: LlmMessageEntity (GROUPING / USER)
      │
      ├─3─ LlmReviewService     →  LLM (второй промпт: system-prompt.md)
      │        параллельные запросы по каждой группе
      │        LLM может вызывать Spring AI тулы ClassContextToolsProvider:
      │            getSourceLines(qualifiedName, rows)   → POST /api/source-lines/gitlab
      │            getSourceFile(className)              → POST /api/source-file
      │        ↓ audit: LlmMessageEntity (REVIEW / ASSISTANT)
      │        ↓ audit: ReviewGroupResultEntity
      │
      ├─4─ MarkdownCommentFormatter  ← собирает единый <details>/<summary> комментарий
      │
      └─5─ GitLabNotesPublisher  →  GitLab Notes API
               POST /api/v4/projects/:id/merge_requests/:iid/notes
               ↓ audit: ReviewRunEntity (finalCommentMarkdown, finishedAt)
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
| Spring Data JPA | (входит в Spring Boot) |
| H2 Database | (входит в Spring Boot) |
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
│   ├── persistence/                           # ← модуль аудита
│   │   ├── config/
│   │   │   └── H2TcpServerConfig.java        # TCP-сервер H2 для DBeaver/IntelliJ (профиль !test)
│   │   ├── entity/
│   │   │   ├── WebhookEventEntity.java        # JPA: аудит входящих webhook-событий
│   │   │   ├── ReviewRunEntity.java           # JPA: жизненный цикл запуска ревью
│   │   │   ├── ReviewGroupResultEntity.java   # JPA: результат ревью одной группы
│   │   │   └── LlmMessageEntity.java          # JPA: trace сообщений с LLM
│   │   ├── repository/
│   │   │   ├── WebhookEventRepository.java
│   │   │   ├── ReviewRunRepository.java
│   │   │   ├── ReviewGroupResultRepository.java
│   │   │   └── LlmMessageRepository.java
│   │   └── service/
│   │       └── ReviewAuditService.java        # фасад аудита; ошибки БД не прерывают ревью
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
│           └── MergeRequestHookHandler.java  # фильтрация и запуск ревью + аудит
└── resources/
    ├── application.yml          # основная конфигурация (H2 file-based)
    ├── application-local.yml    # для локальной разработки (H2 console включена)
    └── prompts/
        ├── grouping-prompt.md   # первый промпт: группировка файлов → RefactoringGroup[]
        └── system-prompt.md     # второй промпт: ревью одной группы с тулами

src/test/
├── java/ru/cbr/bugbusters/gitwebhookhandler/
│   ├── persistence/
│   │   ├── WebhookEventRepositoryTest.java   # @DataJpaTest: поиск по projectId/mrIid, время
│   │   ├── ReviewRunRepositoryTest.java       # @DataJpaTest: сортировка, findTop, обновление
│   │   └── ReviewAuditServiceTest.java        # unit: маппинг сущностей, статусы, cascade
│   ├── review/
│   │   ├── MarkdownCommentFormatterTest.java
│   │   ├── LlmGroupingServiceTest.java
│   │   └── MrReviewOrchestratorTest.java
│   └── webhook/
│       └── MergeRequestHookHandlerTest.java
└── resources/
    └── application-test.yml     # in-memory H2 для тестов
```

---

## Persistence — H2 база данных

Сервис использует **file-based H2** для хранения аудита. Данные переживают перезапуск контейнера.

### Схема (4 таблицы)

| Таблица | JPA-сущность | Описание |
|---|---|---|
| `webhook_events` | `WebhookEventEntity` | Все входящие webhook-события (ACCEPTED / IGNORED) |
| `review_runs` | `ReviewRunEntity` | Один запуск ревью (RUNNING → SUCCESS / ERROR) |
| `review_group_results` | `ReviewGroupResultEntity` | Результат ревью одной группы рефакторинга |
| `llm_messages` | `LlmMessageEntity` | Trace сообщений с LLM (SYSTEM/USER/ASSISTANT/TOOL_*) |

### Подключение к БД из DBeaver / IntelliJ

H2 TCP-сервер запускается на порту `9092` (активен вне профиля `test`):

```
Тип:      H2 Server
JDBC URL: jdbc:h2:tcp://localhost:9092/~/.mr-checker/db/mr-checker   ← локально
          jdbc:h2:tcp://localhost:9092//data/h2/mr-checker           ← в Docker
User:     sa
Password: (пусто)
```

### Docker volume

Данные хранятся в named volume `mr-checker-data` и **не удаляются** при `docker compose down` (без флага `-v`):

```bash
docker compose up -d       # данные живут в volume
docker compose down        # БД сохранена
docker compose down -v     # ⚠️ удаляет volume и все данные БД
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
| `H2_DB_PATH` | Путь к файлу H2 БД (без расширения) | `/data/h2/mr-checker` |
| `H2_TCP_PORT` | Порт H2 TCP-сервера | `9092` |
| `H2_USER` | Пользователь H2 | `sa` |
| `H2_PASSWORD` | Пароль H2 | `(пусто)` |

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
# H2 Console доступна на http://localhost:8081/h2-console
# H2 TCP (для DBeaver): jdbc:h2:tcp://localhost:9092/~/.mr-checker/db/mr-checker
```

### Docker Compose

```bash
# Запуск (данные сохраняются в named volume mr-checker-data)
docker compose up -d

# Остановка без потери данных
docker compose down

# Обновление образа без потери данных
docker compose pull && docker compose up -d

# Полная очистка (включая данные БД)
docker compose down -v
```

Сервис слушает на портах:
- `8081` — HTTP API + Swagger UI
- `9092` — H2 TCP-сервер (для DBeaver / IntelliJ Database)

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

### Тестовое покрытие (30 тестов)

| Класс | Тип теста | Тестов | Что проверяется |
|---|---|---|---|
| `WebhookEventRepositoryTest` | `@DataJpaTest` | 4 | Сохранение и поиск по projectId/mrIid, фильтр по времени, rawPayload |
| `ReviewRunRepositoryTest` | `@DataJpaTest` | 4 | Сортировка по дате, findTop (последний запуск), фильтр по статусу, обновление |
| `ReviewAuditServiceTest` | Unit / Mockito | 7 | ACCEPTED/IGNORED, createRun, completeRun + каскад групп, failRun, failRun-not-found, saveLlmMessage |
| `MrReviewOrchestratorTest` | Unit / Mockito | 5 | Happy-path, пустые файлы, пустые группы, ошибка + терминирование сессии, повторный webhook |
| `MergeRequestHookHandlerTest` | Unit / Mockito | 3 | open/reopen/approved → ACCEPTED; close/merge/update → IGNORED; null payload |
| `MarkdownCommentFormatterTest` | Unit | 4 | Заголовок, секции `<details>`, иконки ✅/❌, XSS-экранирование, fallback `Group #N` |
| `LlmGroupingServiceTest` | Unit | 3 | Парсинг JSON, markdown-fence ```json```, пустой ответ LLM, невалидный JSON |

> Все `@DataJpaTest`-тесты используют in-memory H2 через профиль `test` (`application-test.yml`).  
> Spring Boot контекст целиком при тестах **не поднимается** — тесты быстрые и изолированные.
