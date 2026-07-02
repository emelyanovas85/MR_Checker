#!/usr/bin/env bash
# =============================================================================
# deploy-gitlab-mcp.sh — разворачивание GitLab MCP Server на удалённой машине
#
# Использование:
# ./deploy-gitlab-mcp.sh [ОПЦИИ]
#
# Опции:
# -h, --host        SSH-хост удалённой машины (по умолчанию: 10.1.5.97)
# -u, --user        SSH-пользователь (по умолчанию: svc-local-adm)
# -p, --port        SSH-порт (по умолчанию: 22)
# -i, --identity    Путь к приватному SSH-ключу (необязательно)
# --image           Docker-образ MCP-сервера (по умолчанию: mcp/gitlab:latest)
#                   Игнорируется в режиме --build-from-source.
# --app-dir         Каталог на удалённой машине (по умолчанию: ~/gitlab-mcp)
# --mcp-port        Порт MCP-сервера на удалённой машине (по умолчанию: 8083)
# --gitlab-url      URL GitLab API v4 (по умолчанию: http://10.1.5.6/api/v4)
# --token           GitLab Personal Access Token (или через GITLAB_PERSONAL_ACCESS_TOKEN)
# --project         ID или namespace/name проекта по умолчанию (например, 'mygroup/myrepo' или '42').
#                   Если задан — LLM не обязан указывать project_id в каждом инструменте.
#                   Передаётся в контейнер как MR_MCP_GITLAB_PROJECT_ID.
# --read-only       Запустить сервер в read-only режиме
# --use-wiki        Включить инструменты для wiki
# --no-pull         Не выполнять docker pull локально (использовать уже существующий локальный образ).
#                   Игнорируется в режиме --build-from-source.
# --build-from-source  Собрать образ из исходников github.com/zereight/gitlab-mcp.
#                      Не требует docker pull mcp/gitlab и supercorp/supergateway.
#                      Нативный Streamable HTTP — supergateway не нужен.
#                      Включает все 50+ инструментов, в том числе для комментариев к MR.
#                      Скрипт автоматически скачивает исходники через curl (tar.gz),
#                      git не требуется и учётные данные GitHub не нужны.
#                      Удалённый хост по-прежнему без интернета — образ передаётся через SSH.
# --local-tarball   Путь к локальному .tar.gz архиву исходников zereight/gitlab-mcp.
#                   Используйте если хотите явно указать уже скачанный архив.
#                   Скачайте архив вручную:
#                     https://github.com/zereight/gitlab-mcp/archive/refs/heads/main.tar.gz
#                   Пример: --local-tarball ~/Downloads/gitlab-mcp-main.tar.gz
#                   Автоматически включает --build-from-source.
# --mcp-ref         Ветка/тег/SHA репозитория zereight/gitlab-mcp для --build-from-source
#                   (по умолчанию: main). Пример: --mcp-ref v2.1.28
#                   Игнорируется если указан --local-tarball.
# --help            Показать справку
#
# Примеры:
# ./deploy-gitlab-mcp.sh --token glpat-xxx
# ./deploy-gitlab-mcp.sh --token glpat-xxx --project mygroup/myrepo
# ./deploy-gitlab-mcp.sh --token glpat-xxx --project 42
# ./deploy-gitlab-mcp.sh -h 192.168.1.100 -u deploy --token glpat-xxx
# ./deploy-gitlab-mcp.sh -i ~/.ssh/id_rsa --gitlab-url http://10.1.5.6/api/v4 --token glpat-xxx
# ./deploy-gitlab-mcp.sh --read-only --use-wiki --token glpat-xxx
# ./deploy-gitlab-mcp.sh --build-from-source --token glpat-xxx
# ./deploy-gitlab-mcp.sh --build-from-source --mcp-ref v2.1.28 --token glpat-xxx
# ./deploy-gitlab-mcp.sh --local-tarball ~/Downloads/gitlab-mcp-main.tar.gz --token glpat-xxx
#
# ─── РЕЖИМЫ СБОРКИ ОБРАЗА ──────────────────────────────────────────────────
#
# Режим 1 (по умолчанию): сборка из исходников zereight/gitlab-mcp
# ──────────────────────────────────────────────────────────────────────────
# Скачивает архив исходников через curl (tar.gz) с github.com — git и
# учётные данные GitHub не нужны. Если указан --local-tarball, использует
# локальный файл вместо скачивания. Компилирует TypeScript (npm ci + tsc),
# строит production-образ.
# Supergateway не нужен — Streamable HTTP встроен в zereight/gitlab-mcp
# нативно через env STREAMABLE_HTTP=true.
# Включает все 50+ инструментов, в том числе для комментариев к MR:
#   create_note, create_merge_request_thread, mr_discussions,
#   resolve_merge_request_thread, add_merge_request_thread_note.
# Готовый образ zereight-gitlab-mcp:local передаётся на сервер через SSH.
#
# Режим 2 (--no-build-from-source): mcp/gitlab + supergateway
# ──────────────────────────────────────────────────
# Образ mcp/gitlab содержит только stdio-транспорт.
# Поверх него добавляется supercorp/supergateway:3.2.0 — официальный образ
# с Docker Hub, который транслирует stdio ↔ Streamable HTTP.
# Готовый образ mcp/gitlab-http:latest передаётся на сервер через SSH.
#
# ВАЖНО — --stateful флаг ОБЯЗАТЕЛЕН:
# MCP-протокол требует 3 последовательных запроса в рамках одной сессии:
#   1. POST initialize        → Mcp-Session-Id
#   2. POST notifications/initialized (тот же Mcp-Session-Id)
#   3. POST tools/list / tools/call   (тот же Mcp-Session-Id)
# Без --stateful supergateway запускает новый дочерний процесс на каждый
# POST — сессия теряется, tools/list возвращает пустой список.
#
# ВАЖНО — Accept header порядок (text/event-stream ПЕРВЫМ):
# Open WebUI и Spring AI проверяют наличие text/event-stream первым.
#
# Для Open WebUI укажите в настройках Tool Servers:
# URL:  http://<host>:<port>/mcp
# Type: mcp
# =============================================================================

set -euo pipefail

# ── Цвета ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

log()  { echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $*"; }
ok()   { echo -e "${GREEN}[$(date '+%H:%M:%S')] ✓${NC} $*"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] ⚠${NC} $*"; }
error(){ echo -e "${RED}[$(date '+%H:%M:%S')] ✗${NC} $*" >&2; exit 1; }

# ── Значения по умолчанию ─────────────────────────────────────────────────────
REMOTE_HOST="10.1.5.97"
REMOTE_USER="svc-local-adm"
REMOTE_PORT="22"
SSH_KEY=""
IMAGE_NAME="mcp/gitlab:latest"
BUILT_IMAGE_NAME="mcp/gitlab-http:latest"
# Официальный образ supergateway v3.2.0 с Docker Hub — стабильная версия.
# Docker Hub доступен через корпоративный прокси; ghcr.io заблокирован.
SUPERGATEWAY_IMAGE="supercorp/supergateway:3.2.0"
APP_DIR="~/gitlab-mcp"
MCP_PORT="8083"
GITLAB_API_URL="http://10.1.5.6/api/v4"
GITLAB_PERSONAL_ACCESS_TOKEN="${GITLAB_PERSONAL_ACCESS_TOKEN:-}"
GITLAB_PROJECT_ID="${GITLAB_PROJECT_ID:-}"
GITLAB_READ_ONLY_MODE="false"
USE_GITLAB_WIKI="false"
NO_PULL=false

# ── Переменные режима --build-from-source ────────────────────────────────────
# По умолчанию включён режим сборки из исходников zereight/gitlab-mcp
BUILD_FROM_SOURCE=true
MCP_REF="main"
LOCAL_TARBALL=""
# Имя локального образа при сборке из исходников
BUILT_SOURCE_IMAGE_NAME="zereight-gitlab-mcp:local"
# Внутренний порт контейнера (upstream default для zereight/gitlab-mcp)
CONTAINER_PORT="3002"

usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,2\}//'
  exit 0
}

# ── Разбор аргументов ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--host)              REMOTE_HOST="$2";                       shift 2 ;;
    -u|--user)              REMOTE_USER="$2";                       shift 2 ;;
    -p|--port)              REMOTE_PORT="$2";                       shift 2 ;;
    -i|--identity)          SSH_KEY="$2";                           shift 2 ;;
    --image)                IMAGE_NAME="$2";                        shift 2 ;;
    --app-dir)              APP_DIR="$2";                           shift 2 ;;
    --mcp-port)             MCP_PORT="$2";                          shift 2 ;;
    --gitlab-url)           GITLAB_API_URL="$2";                    shift 2 ;;
    --token)                GITLAB_PERSONAL_ACCESS_TOKEN="$2";      shift 2 ;;
    --project)              GITLAB_PROJECT_ID="$2";                 shift 2 ;;
    --read-only)            GITLAB_READ_ONLY_MODE="true";           shift   ;;
    --use-wiki)             USE_GITLAB_WIKI="true";                 shift   ;;
    --no-pull)              NO_PULL=true;                           shift   ;;
    --build-from-source)    BUILD_FROM_SOURCE=true;                 shift   ;;
    --mcp-ref)              MCP_REF="$2";                           shift 2 ;;
    --local-tarball)        LOCAL_TARBALL="$2"; BUILD_FROM_SOURCE=true; shift 2 ;;
    --help)                 usage ;;
    *) error "Неизвестный аргумент: $1. Используйте --help для справки." ;;
  esac
done

# ── Запрос токена если не задан ─────────────────────────────────────────────
if [[ -z "${GITLAB_PERSONAL_ACCESS_TOKEN}" ]]; then
  echo -e "${YELLOW}Введите GitLab Personal Access Token:${NC} "
  read -r -s GITLAB_PERSONAL_ACCESS_TOKEN
  echo ""
  [[ -z "${GITLAB_PERSONAL_ACCESS_TOKEN}" ]] && error "Токен не может быть пустым"
fi

[[ ! "${MCP_PORT}" =~ ^[0-9]+$ ]] && error "Некорректный порт: ${MCP_PORT}"

if [[ -n "${GITLAB_PROJECT_ID}" ]]; then
  log "Дефолтный проект: ${GITLAB_PROJECT_ID} (будет передан как MR_MCP_GITLAB_PROJECT_ID)"
fi

# ── Раскрываем тильду в APP_DIR ───────────────────────────────────────────────
# Тильда внутри строковой переменной не раскрывается bash'ом автоматически.
# Раскрываем один раз здесь, до любых mkdir/scp/heredoc операций.
if [[ "${APP_DIR}" == "~/"* ]]; then
  REMOTE_APP_DIR="/home/${REMOTE_USER}/${APP_DIR#~/}"
elif [[ "${APP_DIR}" == "~" ]]; then
  REMOTE_APP_DIR="/home/${REMOTE_USER}"
else
  REMOTE_APP_DIR="${APP_DIR}"
fi

if [[ "${BUILD_FROM_SOURCE}" == "true" ]]; then
  if [[ -n "${LOCAL_TARBALL}" ]]; then
    # Проверяем что файл существует и читаем его размер
    [[ -f "${LOCAL_TARBALL}" ]] || error "Файл не найден: ${LOCAL_TARBALL}"
    TARBALL_SIZE=$(du -sh "${LOCAL_TARBALL}" 2>/dev/null | cut -f1)
    log "Режим: --local-tarball (${LOCAL_TARBALL}, ${TARBALL_SIZE}, без github.com и git)"
  else
    log "Режим: --build-from-source (zereight/gitlab-mcp@${MCP_REF}, без supergateway)"
    log "Исходники будут скачаны через curl (tar.gz) — git и учётные данные GitHub не нужны"
  fi
else
  log "Режим: mcp/gitlab + supergateway:3.2.0 (--stateful)"
fi

# ── SSH ControlMaster: одно подключение — один ввод пароля ────────────────────
SSH_CTRL_DIR="$(mktemp -d /tmp/ssh-ctrl-XXXXXX)"
SSH_CTRL_SOCK="${SSH_CTRL_DIR}/master"

cleanup() {
  ssh -o ControlPath="${SSH_CTRL_SOCK}" -O exit "${REMOTE_HOST}" 2>/dev/null || true
  rm -rf "${SSH_CTRL_DIR}"
}
trap cleanup EXIT

SSH_BASE_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10 \
  -o ControlMaster=auto -o ControlPath=${SSH_CTRL_SOCK} -o ControlPersist=300"
[[ -n "$SSH_KEY" ]] && SSH_BASE_OPTS="${SSH_BASE_OPTS} -i ${SSH_KEY}"

SSH_CMD="ssh ${SSH_BASE_OPTS} -p ${REMOTE_PORT} ${REMOTE_USER}@${REMOTE_HOST}"
SCP_CMD="scp ${SSH_BASE_OPTS} -P ${REMOTE_PORT}"

# ── Первое подключение ────────────────────────────────────────────────────────
log "Подключение к ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PORT} (единственный ввод пароля)..."
$SSH_CMD "echo ok" > /dev/null 2>&1 || error "Не удалось подключиться к ${REMOTE_HOST}"
ok "Соединение установлено (дальнейшие шаги — без пароля)"

# ── Проверка зависимостей локально ────────────────────────────────────────────
log "Проверка локальных зависимостей..."
command -v docker >/dev/null 2>&1 || error "Локально не найден docker"
ok "Локальный docker найден"

# ── Проверка зависимостей на удалённой машине ─────────────────────────────────
log "Проверка зависимостей на удалённой машине..."
$SSH_CMD bash << 'REMOTE_CHECK'
set -e
if ! docker info > /dev/null 2>&1; then
  echo "ERROR: docker daemon не запущен или недоступен"; exit 1
fi
if docker compose version >/dev/null 2>&1; then
  echo "INFO: найден docker compose v2"
elif command -v docker-compose >/dev/null 2>&1; then
  echo "INFO: найден docker-compose v1"
  echo "WARN: docker-compose v1 достиг EOL. Рекомендуем docker compose v2"
else
  echo "ERROR: не найден ни 'docker compose', ни 'docker-compose'"; exit 1
fi
echo "ALL_OK"
REMOTE_CHECK
ok "Зависимости в порядке"

DOCKER_COMPOSE=$($SSH_CMD \
  'if docker compose version >/dev/null 2>&1; then echo "docker compose"; else echo "docker-compose"; fi')
log "Используем compose: ${DOCKER_COMPOSE}"

# =============================================================================
# ── ВЕТКА 1: --build-from-source ─────────────────────────────────────────────
# =============================================================================
if [[ "${BUILD_FROM_SOURCE}" == "true" ]]; then

  BUILD_CTX="$(mktemp -d /tmp/zereight-mcp-build-XXXXXX)"
  trap 'cleanup; rm -rf "${BUILD_CTX}"' EXIT

  # ── Получение исходников ─────────────────────────────────────────────────────
  if [[ -n "${LOCAL_TARBALL}" ]]; then
    # ── Режим: явно указанный локальный архив ───────────────────────────────
    log "Распаковка локального архива ${LOCAL_TARBALL}..."
    mkdir -p "${BUILD_CTX}/src"
    tar -xzf "${LOCAL_TARBALL}" -C "${BUILD_CTX}/src" --strip-components=1 \
      || error "Не удалось распаковать архив ${LOCAL_TARBALL}"
    if [[ ! -f "${BUILD_CTX}/src/package.json" ]]; then
      error "Архив не содержит package.json — убедитесь что это исходники zereight/gitlab-mcp"
    fi
    ACTUAL_REF="local-tarball:$(basename "${LOCAL_TARBALL}")"
    ok "Исходники распакованы из локального архива ($(ls "${BUILD_CTX}/src" | wc -l) файлов)"
  else
    # ── Режим: скачивание архива через curl (без git, без учётных данных) ───
    # GitHub отдаёт публичный tar.gz архив без авторизации.
    # Для веток:  https://github.com/OWNER/REPO/archive/refs/heads/BRANCH.tar.gz
    # Для тегов:  https://github.com/OWNER/REPO/archive/refs/tags/TAG.tar.gz
    # Для коммитов (первые 40 символов SHA): https://github.com/OWNER/REPO/archive/SHA.tar.gz
    MCP_TARBALL_URL="https://github.com/zereight/gitlab-mcp/archive/refs/heads/${MCP_REF}.tar.gz"
    # Если MCP_REF похож на тег (начинается с 'v' и содержит точку) — используем /refs/tags/
    if [[ "${MCP_REF}" =~ ^v[0-9]+\. ]]; then
      MCP_TARBALL_URL="https://github.com/zereight/gitlab-mcp/archive/refs/tags/${MCP_REF}.tar.gz"
    fi
    # Если MCP_REF — это полный SHA (40 hex-символов) — используем прямой путь
    if [[ "${MCP_REF}" =~ ^[0-9a-f]{40}$ ]]; then
      MCP_TARBALL_URL="https://github.com/zereight/gitlab-mcp/archive/${MCP_REF}.tar.gz"
    fi

    log "Скачивание исходников zereight/gitlab-mcp@${MCP_REF} через curl..."
    log "URL: ${MCP_TARBALL_URL}"

    TARBALL_DEST="${BUILD_CTX}/gitlab-mcp.tar.gz"
    mkdir -p "${BUILD_CTX}/src"

    # curl: -L следует редиректам (GitHub делает редирект на codeload.github.com)
    #        --fail завершается с ошибкой при HTTP 4xx/5xx
    #        --retry 3 повторяет при временных сбоях сети
    if ! curl -L --fail --retry 3 --retry-delay 2 \
         -o "${TARBALL_DEST}" \
         --connect-timeout 30 \
         --max-time 120 \
         --progress-bar \
         "${MCP_TARBALL_URL}"; then
      error "Не удалось скачать исходники с ${MCP_TARBALL_URL}.
Проверьте доступность github.com с локальной машины.
Или скачайте архив вручную и укажите: --local-tarball /path/to/gitlab-mcp.tar.gz"
    fi

    TARBALL_SIZE=$(du -sh "${TARBALL_DEST}" 2>/dev/null | cut -f1)
    log "Архив скачан (${TARBALL_SIZE}), распаковка..."

    tar -xzf "${TARBALL_DEST}" -C "${BUILD_CTX}/src" --strip-components=1 \
      || error "Не удалось распаковать скачанный архив"

    rm -f "${TARBALL_DEST}"

    if [[ ! -f "${BUILD_CTX}/src/package.json" ]]; then
      error "Архив не содержит package.json — возможно неверный MCP_REF: ${MCP_REF}"
    fi

    ACTUAL_REF="${MCP_REF}"
    ok "Исходники zereight/gitlab-mcp@${MCP_REF} скачаны и распакованы ($(ls "${BUILD_CTX}/src" | wc -l) файлов)"
  fi

  # Пишем Dockerfile прямо в BUILD_CTX (не в src/, чтобы не конфликтовать)
  cat > "${BUILD_CTX}/Dockerfile" <<'DOCKERFILE'
# ─── Стадия 1: сборка TypeScript ─────────────────────────────────────────────
FROM node:22-alpine AS builder

WORKDIR /build

# Копируем исходники из локального контекста (папка src/)
COPY src/package.json src/package-lock.json ./

# Устанавливаем ВСЕ зависимости (включая devDependencies — нужны для tsc)
RUN npm ci --ignore-scripts

COPY src/ ./

# Компилируем TypeScript → build/
# package.json: "build": "tsc && node -e \"require('fs').chmodSync('build/index.js', '755')\""
RUN npm run build

# ─── Стадия 2: production-образ ──────────────────────────────────────────────
FROM node:22-alpine AS release

WORKDIR /app

COPY --from=builder /build/build        ./build
COPY --from=builder /build/package.json ./package.json
COPY --from=builder /build/package-lock.json ./package-lock.json

# Только production-зависимости
RUN npm ci --ignore-scripts --omit=dev

ENV NODE_ENV=production

# Upstream default: Streamable HTTP слушает на 3002
EXPOSE 3002

USER node

ENTRYPOINT ["node", "build/index.js"]
DOCKERFILE

  log "Сборка образа ${BUILT_SOURCE_IMAGE_NAME} из исходников (npm ci + tsc)..."
  docker build --no-cache -t "${BUILT_SOURCE_IMAGE_NAME}" "${BUILD_CTX}" \
    || error "Не удалось собрать образ ${BUILT_SOURCE_IMAGE_NAME}"
  rm -rf "${BUILD_CTX}"
  ok "Образ ${BUILT_SOURCE_IMAGE_NAME} собран локально (ref=${ACTUAL_REF}, все 50+ инструментов, без supergateway)"

  # ── Передача образа на сервер ────────────────────────────────────────────────
  log "Передача образа ${BUILT_SOURCE_IMAGE_NAME} на ${REMOTE_HOST} (docker save | ssh docker load)..."
  docker save "${BUILT_SOURCE_IMAGE_NAME}" \
    | ssh ${SSH_BASE_OPTS} -p "${REMOTE_PORT}" "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
  ok "Образ загружен на ${REMOTE_HOST}"

  # ── Подготовка env и compose ─────────────────────────────────────────────────
  WORK_DIR="$(mktemp -d /tmp/gitlab-mcp-deploy-XXXXXX)"
  ENV_FILE="${WORK_DIR}/.env"
  COMPOSE_FILE="${WORK_DIR}/docker-compose.yml"

  cat > "${ENV_FILE}" <<EOF_ENV
GITLAB_API_URL=${GITLAB_API_URL}
GITLAB_PERSONAL_ACCESS_TOKEN=${GITLAB_PERSONAL_ACCESS_TOKEN}
GITLAB_PROJECT_ID=${GITLAB_PROJECT_ID}
GITLAB_READ_ONLY_MODE=${GITLAB_READ_ONLY_MODE}
USE_GITLAB_WIKI=${USE_GITLAB_WIKI}
MCP_PORT=${MCP_PORT}
BUILT_SOURCE_IMAGE_NAME=${BUILT_SOURCE_IMAGE_NAME}
EOF_ENV

  # В compose используем переменные через shell-подстановку (одинарные кавычки НЕ используем)
  cat > "${COMPOSE_FILE}" <<EOF_COMPOSE
services:
  gitlab-mcp:
    image: \${BUILT_SOURCE_IMAGE_NAME}
    container_name: gitlab-mcp
    restart: unless-stopped
    ports:
      - "\${MCP_PORT}:${CONTAINER_PORT}"
    environment:
      # GitLab Personal Access Token (права: api, read_repository)
      # Используется напрямую сервером — REMOTE_AUTHORIZATION НЕ задаётся,
      # чтобы сервер брал токен из env, а не требовал его в каждом HTTP-запросе.
      GITLAB_PERSONAL_ACCESS_TOKEN: \${GITLAB_PERSONAL_ACCESS_TOKEN}
      # URL GitLab API v4
      GITLAB_API_URL: \${GITLAB_API_URL}
      # Нативный Streamable HTTP (supergateway не нужен)
      STREAMABLE_HTTP: "true"
      HOST: "0.0.0.0"
      PORT: "${CONTAINER_PORT}"
      # Проект по умолчанию (опционально)
      MR_MCP_GITLAB_PROJECT_ID: \${GITLAB_PROJECT_ID}
      # Режим только-чтение (опционально)
      GITLAB_READ_ONLY_MODE: \${GITLAB_READ_ONLY_MODE}
      # Wiki-инструменты (опционально)
      USE_GITLAB_WIKI: \${USE_GITLAB_WIKI}
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${CONTAINER_PORT}/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 30s
EOF_COMPOSE

  ok "Конфигурация подготовлена (Streamable HTTP на порту ${CONTAINER_PORT} → хост ${MCP_PORT})"

  # ── Передача файлов на сервер ─────────────────────────────────────────────────
  log "Передача конфигурации на ${REMOTE_HOST} (${REMOTE_APP_DIR})..."
  $SSH_CMD "mkdir -p ${REMOTE_APP_DIR}"
  $SCP_CMD "${ENV_FILE}"     "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_APP_DIR}/.env"
  $SCP_CMD "${COMPOSE_FILE}" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_APP_DIR}/docker-compose.yml"
  rm -rf "${WORK_DIR}"
  ok "Конфигурация передана"

  # ── Деплой на сервере ─────────────────────────────────────────────────────────
  log "Начало деплоя (zereight/gitlab-mcp, нативный Streamable HTTP) на ${REMOTE_HOST}:${REMOTE_APP_DIR}"

  $SSH_CMD bash <<REMOTE_DEPLOY
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { echo -e "\${BLUE}[\$(date '+%H:%M:%S')]\${NC} \$*"; }
ok()   { echo -e "\${GREEN}[\$(date '+%H:%M:%S')] ✓\${NC} \$*"; }
warn() { echo -e "\${YELLOW}[\$(date '+%H:%M:%S')] ⚠\${NC} \$*"; }
fail() { echo -e "\${RED}[\$(date '+%H:%M:%S')] ✗\${NC} \$*" >&2; exit 1; }

APP_DIR="${REMOTE_APP_DIR}"
MCP_PORT="${MCP_PORT}"
CONTAINER_PORT="${CONTAINER_PORT}"
DOCKER_COMPOSE="${DOCKER_COMPOSE}"
# Токен нужен только для smoke-test — передаём его как обычный заголовок.
# REMOTE_AUTHORIZATION=true НЕ используется: сервер читает токен из env напрямую.
GITLAB_TOKEN="${GITLAB_PERSONAL_ACCESS_TOKEN}"

cd "\${APP_DIR}"

[[ ! -f .env               ]] && fail "Не найден .env в \${APP_DIR}"
[[ ! -f docker-compose.yml ]] && fail "Не найден docker-compose.yml в \${APP_DIR}"

# Проверка занятости порта
PORT_IN_USE=false
if ss -tln "( sport = :\${MCP_PORT} )" 2>/dev/null | grep -q LISTEN; then
  PORT_IN_USE=true
fi

if [[ "\${PORT_IN_USE}" == "true" ]]; then
  if docker ps --format '{{.Ports}}' | grep -q ":\${MCP_PORT}->"; then
    warn "Порт \${MCP_PORT} занят Docker-контейнером — будет освобождён через compose down"
  else
    warn "Порт \${MCP_PORT} занят процессом вне Docker"
    if command -v lsof >/dev/null 2>&1; then
      PIDS=\$(lsof -i :\${MCP_PORT} -sTCP:LISTEN -t 2>/dev/null || true)
      [[ -n "\${PIDS}" ]] && warn "PID занявшего процесса: \${PIDS}"
    fi
    fail "Отмена деплоя. Освободите порт \${MCP_PORT} вручную и повторите запуск"
  fi
fi

log "Остановка предыдущего контейнера (если есть)..."
eval "\${DOCKER_COMPOSE} down --remove-orphans" || true
ok "Предыдущий контейнер остановлен"

log "Запуск GitLab MCP сервера (zereight/gitlab-mcp, нативный Streamable HTTP)..."
eval "\${DOCKER_COMPOSE} up -d"
ok "Контейнер запущен"

log "Проверка статуса контейнера..."
sleep 5
if ! docker ps --filter 'name=gitlab-mcp' --format '{{.Names}}' | grep -q '^gitlab-mcp\$'; then
  warn "Контейнер не перешёл в состояние running. Последние логи:"
  eval "\${DOCKER_COMPOSE} logs --tail=100"
  fail "GitLab MCP не запущен"
fi

log "Ожидание доступности порта \${MCP_PORT}..."
MAX_WAIT=30; ELAPSED=0; READY=false
while [[ \${ELAPSED} -lt \${MAX_WAIT} ]]; do
  if ss -tln "( sport = :\${MCP_PORT} )" 2>/dev/null | grep -q LISTEN; then
    READY=true; break
  fi
  printf "."; sleep 2; ELAPSED=\$((ELAPSED + 2))
done
echo ""

if [[ "\${READY}" != "true" ]]; then
  warn "Порт не открылся за \${MAX_WAIT} сек. Последние логи:"
  eval "\${DOCKER_COMPOSE} logs --tail=100"
  fail "GitLab MCP не стал доступен"
fi

# ── Проверка 1: GET /health ──────────────────────────────────────────────────
log "Проверка 1/3: GET /health..."
HEALTH_RESPONSE=\$(curl -s --noproxy localhost,127.0.0.1 \
  http://localhost:\${MCP_PORT}/health --max-time 5 2>/dev/null || echo "CURL_FAILED")
if [[ "\${HEALTH_RESPONSE}" == "ok" ]]; then
  ok "Health endpoint отвечает корректно (→ ok)"
else
  warn "Health endpoint ответил неожиданно: \${HEALTH_RESPONSE}"
fi

# ── Проверка 2: MCP handshake ────────────────────────────────────────────────
# Токен передаётся через env GITLAB_PERSONAL_ACCESS_TOKEN внутри контейнера.
# В smoke-test для smoke-теста передаём его и в заголовке (на случай если
# upstream когда-нибудь потребует), но основной путь — через env.
log "Проверка 2/3: MCP handshake (initialize → notifications/initialized → tools/list)..."

INIT_OUT=\$(curl -si --noproxy localhost,127.0.0.1 -X POST \
  "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  -d '{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"deploy-check","version":"1.0"}}}' \
  --max-time 10 2>/dev/null || echo "CURL_FAILED")

if [[ "\${INIT_OUT}" == "CURL_FAILED" ]]; then
  warn "initialize: curl ошибка"
else
  SESSION_ID=\$(echo "\${INIT_OUT}" | grep -i '^Mcp-Session-Id:' | tr -d '\r' | awk '{print \$2}')
  if echo "\${INIT_OUT}" | grep -qiE 'serverInfo|protocolVersion'; then
    ok "initialize: OK (session=\${SESSION_ID:-none})"
  else
    warn "initialize: неожиданный ответ: \${INIT_OUT:0:200}"
    SESSION_ID=""
  fi
fi

NOTIF_HEADERS=""
[[ -n "\${SESSION_ID:-}" ]] && NOTIF_HEADERS="-H 'Mcp-Session-Id: \${SESSION_ID}'"
eval curl -s --noproxy localhost,127.0.0.1 -X POST \
  "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  \${NOTIF_HEADERS} \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  --max-time 5 >/dev/null 2>&1 || true
ok "notifications/initialized: отправлено"

LIST_HEADERS=""
[[ -n "\${SESSION_ID:-}" ]] && LIST_HEADERS="-H 'Mcp-Session-Id: \${SESSION_ID}'"
LIST_OUT=\$(eval curl -s --noproxy localhost,127.0.0.1 -X POST \
  "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  \${LIST_HEADERS} \
  -d '{"jsonrpc":"2.0","id":"list-1","method":"tools/list","params":{}}' \
  --max-time 10 2>/dev/null || echo "CURL_FAILED")

if [[ "\${LIST_OUT}" == "CURL_FAILED" ]]; then
  warn "tools/list: curl ошибка"
elif echo "\${LIST_OUT}" | grep -q '"tools"'; then
  TOOL_COUNT=\$(echo "\${LIST_OUT}" | grep -o '"name"' | wc -l)
  ok "tools/list: OK — обнаружено инструментов: \${TOOL_COUNT}"
  # Проверяем наличие инструментов для комментариев
  if echo "\${LIST_OUT}" | grep -q 'create_note\|mr_discussions\|merge_request_thread'; then
    ok "Инструменты для комментариев к MR: присутствуют ✓"
  else
    warn "Инструменты для комментариев к MR не обнаружены в tools/list"
  fi
elif echo "\${LIST_OUT}" | grep -q '"error"'; then
  warn "tools/list вернул ошибку: \${LIST_OUT:0:300}"
else
  warn "tools/list: неожиданный ответ: \${LIST_OUT:0:200}"
fi

# ── Проверка 3: логи контейнера ───────────────────────────────────────────────
log "Проверка 3/3: логи запуска контейнера..."
if docker logs gitlab-mcp --tail=20 2>&1 | grep -qiE 'listening|started|streamable|ready|port'; then
  ok "Сервер успешно стартовал (ключевые слова найдены в логах)"
else
  warn "Ключевые слова старта не найдены в логах. Проверьте вручную: docker logs gitlab-mcp --tail=50"
fi

SERVER_IP=\$(hostname -I | awk '{print \$1}')
echo ""
eval "\${DOCKER_COMPOSE} ps"
echo ""
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${GREEN} Режим      : zereight/gitlab-mcp (нативный Streamable HTTP)\${NC}"
echo -e "\${GREEN} MCP URL    : http://\${SERVER_IP}:\${MCP_PORT}/mcp\${NC}"
echo -e "\${GREEN} Health URL : http://\${SERVER_IP}:\${MCP_PORT}/health\${NC}"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${YELLOW} Open WebUI → Tool Servers → Add:\${NC}"
echo -e "   URL:  http://\${SERVER_IP}:\${MCP_PORT}/mcp"
echo -e "   Type: mcp"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
REMOTE_DEPLOY

  ok "Деплой GitLab MCP (zereight/gitlab-mcp) на ${REMOTE_HOST} завершён успешно"
  exit 0
fi

# =============================================================================
# ── ВЕТКА 2: классический режим (mcp/gitlab + supergateway) ──────────────────
# =============================================================================

# ── Получение базового образа mcp/gitlab локально ─────────────────────────────
if [[ "${NO_PULL}" == "false" ]]; then
  log "Проверка/скачивание локального образа ${IMAGE_NAME}..."
  docker pull "${IMAGE_NAME}" || error "Не удалось скачать образ ${IMAGE_NAME} локально"
  ok "Локальный образ ${IMAGE_NAME} готов"
else
  log "Проверка существования локального образа ${IMAGE_NAME}..."
  docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1 || \
    error "Локальный образ ${IMAGE_NAME} не найден. Уберите --no-pull или выполните docker pull"
  ok "Локальный образ ${IMAGE_NAME} найден"
fi

# ── Получение официального образа supergateway локально ───────────────────────
log "Проверка/скачивание официального образа supergateway ${SUPERGATEWAY_IMAGE}..."
docker pull "${SUPERGATEWAY_IMAGE}" \
  || error "Не удалось скачать образ ${SUPERGATEWAY_IMAGE} локально"
ok "Локальный образ ${SUPERGATEWAY_IMAGE} готов"

# ── Сборка нового образа на базе официального supergateway ────────────────────
log "Сборка образа ${BUILT_IMAGE_NAME} на базе официального supergateway ${SUPERGATEWAY_IMAGE} (локально)..."

BUILD_CTX="$(mktemp -d /tmp/gitlab-mcp-build-XXXXXX)"

# Извлекаем файлы GitLab MCP Server из исходного образа во временный контекст сборки.
docker create --name "gitlab-mcp-extract" "${IMAGE_NAME}"
docker cp gitlab-mcp-extract:/app/. "${BUILD_CTX}/app/" 2>/dev/null \
  || docker cp gitlab-mcp-extract:/usr/local/lib/node_modules/. "${BUILD_CTX}/app/" 2>/dev/null \
  || true
docker rm gitlab-mcp-extract

cat > "${BUILD_CTX}/Dockerfile" <<DOCKERFILE
# Официальный образ supergateway v3.2.0 с Docker Hub — стабильная версия.
FROM ${SUPERGATEWAY_IMAGE}

COPY --from=${IMAGE_NAME} /app /app

WORKDIR /app

# --stateful ОБЯЗАТЕЛЕН: сохраняет дочерний процесс между запросами одной MCP-сессии.
CMD ["--stdio", "node dist/index.js", \
     "--port", "${MCP_PORT}", \
     "--outputTransport", "streamableHttp", \
     "--streamableHttpPath", "/mcp", \
     "--healthEndpoint", "/health", \
     "--stateful"]
DOCKERFILE

docker build -t "${BUILT_IMAGE_NAME}" "${BUILD_CTX}" \
  || error "Не удалось собрать образ ${BUILT_IMAGE_NAME}"

rm -rf "${BUILD_CTX}"
ok "Образ ${BUILT_IMAGE_NAME} собран локально на базе официального supergateway (--stateful)"

# ── Передача собранного образа на сервер ──────────────────────────────────────
log "Передача образа ${BUILT_IMAGE_NAME} на ${REMOTE_HOST} (docker save | ssh docker load)..."
docker save "${BUILT_IMAGE_NAME}" \
  | ssh ${SSH_BASE_OPTS} -p "${REMOTE_PORT}" "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
ok "Образ загружен на ${REMOTE_HOST}"

# ── Подготовка env и compose локально ─────────────────────────────────────────
WORK_DIR="$(mktemp -d /tmp/gitlab-mcp-deploy-XXXXXX)"
ENV_FILE="${WORK_DIR}/.env"
COMPOSE_FILE="${WORK_DIR}/docker-compose.yml"

cat > "${ENV_FILE}" <<EOF_ENV
GITLAB_API_URL=${GITLAB_API_URL}
GITLAB_PERSONAL_ACCESS_TOKEN=${GITLAB_PERSONAL_ACCESS_TOKEN}
GITLAB_PROJECT_ID=${GITLAB_PROJECT_ID}
GITLAB_READ_ONLY_MODE=${GITLAB_READ_ONLY_MODE}
USE_GITLAB_WIKI=${USE_GITLAB_WIKI}
MCP_PORT=${MCP_PORT}
BUILT_IMAGE_NAME=${BUILT_IMAGE_NAME}
EOF_ENV

cat > "${COMPOSE_FILE}" <<'EOF_COMPOSE'
services:
  gitlab-mcp:
    image: ${BUILT_IMAGE_NAME}
    container_name: gitlab-mcp
    restart: unless-stopped
    ports:
      - "${MCP_PORT}:${MCP_PORT}"
    environment:
      GITLAB_API_URL: ${GITLAB_API_URL}
      GITLAB_PERSONAL_ACCESS_TOKEN: ${GITLAB_PERSONAL_ACCESS_TOKEN}
      MR_MCP_GITLAB_PROJECT_ID: ${GITLAB_PROJECT_ID}
      GITLAB_READ_ONLY_MODE: ${GITLAB_READ_ONLY_MODE}
      USE_GITLAB_WIKI: ${USE_GITLAB_WIKI}
      PORT: ${MCP_PORT}
EOF_COMPOSE

ok "Локальные .env и docker-compose.yml подготовлены"

# ── Передача файлов на сервер ─────────────────────────────────────────────────
log "Передача конфигурации на ${REMOTE_HOST} (${REMOTE_APP_DIR})..."
$SSH_CMD "mkdir -p ${REMOTE_APP_DIR}"
$SCP_CMD "${ENV_FILE}"     "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_APP_DIR}/.env"
$SCP_CMD "${COMPOSE_FILE}" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_APP_DIR}/docker-compose.yml"
rm -rf "${WORK_DIR}"
ok "Конфигурация передана"

# ── Деплой на сервере ──────────────────────────────────────────────────────────
log "Начало деплоя GitLab MCP на ${REMOTE_HOST}:${REMOTE_APP_DIR}"

$SSH_CMD bash <<REMOTE_DEPLOY
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { echo -e "\${BLUE}[\$(date '+%H:%M:%S')]\${NC} \$*"; }
ok()   { echo -e "\${GREEN}[\$(date '+%H:%M:%S')] ✓\${NC} \$*"; }
warn() { echo -e "\${YELLOW}[\$(date '+%H:%M:%S')] ⚠\${NC} \$*"; }
fail() { echo -e "\${RED}[\$(date '+%H:%M:%S')] ✗\${NC} \$*" >&2; exit 1; }

APP_DIR="${REMOTE_APP_DIR}"
MCP_PORT="${MCP_PORT}"
DOCKER_COMPOSE="${DOCKER_COMPOSE}"

cd "\${APP_DIR}"

[[ ! -f .env               ]] && fail "Не найден .env в \${APP_DIR}"
[[ ! -f docker-compose.yml ]] && fail "Не найден docker-compose.yml в \${APP_DIR}"

# Проверка занятости порта
PORT_IN_USE=false
if ss -tln "( sport = :\${MCP_PORT} )" 2>/dev/null | grep -q LISTEN; then
  PORT_IN_USE=true
fi

if [[ "\${PORT_IN_USE}" == "true" ]]; then
  if docker ps --format '{{.Ports}}' | grep -q ":\${MCP_PORT}->"; then
    warn "Порт \${MCP_PORT} занят Docker-контейнером — будет освобождён через compose down"
  else
    warn "Порт \${MCP_PORT} занят процессом вне Docker"
    if command -v lsof >/dev/null 2>&1; then
      PIDS=\$(lsof -i :\${MCP_PORT} -sTCP:LISTEN -t 2>/dev/null || true)
      [[ -n "\${PIDS}" ]] && warn "PID занявшего процесса: \${PIDS}"
    fi
    fail "Отмена деплоя. Освободите порт \${MCP_PORT} вручную и повторите запуск"
  fi
fi

log "Остановка предыдущего контейнера (если есть)..."
eval "\${DOCKER_COMPOSE} down --remove-orphans" || true
ok "Предыдущий контейнер остановлен"

log "Запуск GitLab MCP сервера (supergateway:3.2.0, Streamable HTTP, --stateful)..."
eval "\${DOCKER_COMPOSE} up -d"
ok "Контейнер запущен"

log "Проверка статуса контейнера..."
sleep 5
if ! docker ps --filter 'name=gitlab-mcp' --format '{{.Names}}' | grep -q '^gitlab-mcp\$'; then
  warn "Контейнер не перешёл в состояние running. Последние логи:"
  eval "\${DOCKER_COMPOSE} logs --tail=100"
  fail "GitLab MCP не запущен"
fi

log "Ожидание доступности порта \${MCP_PORT}..."
MAX_WAIT=30; ELAPSED=0; READY=false
while [[ \${ELAPSED} -lt \${MAX_WAIT} ]]; do
  if ss -tln "( sport = :\${MCP_PORT} )" 2>/dev/null | grep -q LISTEN; then
    READY=true; break
  fi
  printf "."; sleep 2; ELAPSED=\$((ELAPSED + 2))
done
echo ""

if [[ "\${READY}" != "true" ]]; then
  warn "Порт не открылся за \${MAX_WAIT} сек. Последние логи:"
  eval "\${DOCKER_COMPOSE} logs --tail=100"
  fail "GitLab MCP не стал доступен"
fi

# ── Проверка 1: GET /health ──────────────────────────────────────────────────
log "Проверка 1/3: GET /health..."
HEALTH_RESPONSE=\$(curl -s --noproxy localhost,127.0.0.1 \
  http://localhost:\${MCP_PORT}/health --max-time 5 2>/dev/null || echo "CURL_FAILED")
if [[ "\${HEALTH_RESPONSE}" == "ok" ]]; then
  ok "Health endpoint отвечает корректно (→ ok)"
else
  warn "Health endpoint ответил неожиданно: \${HEALTH_RESPONSE}"
fi

# ── Проверка 2: MCP handshake ────────────────────────────────────────────────
log "Проверка 2/3: MCP handshake (initialize → notifications/initialized → tools/list)..."

INIT_OUT=\$(curl -si --noproxy localhost,127.0.0.1 -X POST \
  "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  -d '{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"deploy-check","version":"1.0"}}}' \
  --max-time 10 2>/dev/null || echo "CURL_FAILED")

if [[ "\${INIT_OUT}" == "CURL_FAILED" ]]; then
  warn "initialize: curl ошибка"
else
  SESSION_ID=\$(echo "\${INIT_OUT}" | grep -i '^Mcp-Session-Id:' | tr -d '\r' | awk '{print \$2}')
  if echo "\${INIT_OUT}" | grep -qiE 'serverInfo|protocolVersion'; then
    ok "initialize: OK (session=\${SESSION_ID:-none})"
  else
    warn "initialize: неожиданный ответ: \${INIT_OUT:0:200}"
    SESSION_ID=""
  fi
fi

NOTIF_HEADERS=""
[[ -n "\${SESSION_ID:-}" ]] && NOTIF_HEADERS="-H 'Mcp-Session-Id: \${SESSION_ID}'"
eval curl -s --noproxy localhost,127.0.0.1 -X POST \
  "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  \${NOTIF_HEADERS} \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  --max-time 5 >/dev/null 2>&1 || true
ok "notifications/initialized: отправлено"

LIST_HEADERS=""
[[ -n "\${SESSION_ID:-}" ]] && LIST_HEADERS="-H 'Mcp-Session-Id: \${SESSION_ID}'"
LIST_OUT=\$(eval curl -s --noproxy localhost,127.0.0.1 -X POST \
  "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  \${LIST_HEADERS} \
  -d '{"jsonrpc":"2.0","id":"list-1","method":"tools/list","params":{}}' \
  --max-time 10 2>/dev/null || echo "CURL_FAILED")

if [[ "\${LIST_OUT}" == "CURL_FAILED" ]]; then
  warn "tools/list: curl ошибка"
elif echo "\${LIST_OUT}" | grep -q '"tools"'; then
  TOOL_COUNT=\$(echo "\${LIST_OUT}" | grep -o '"name"' | wc -l)
  ok "tools/list: OK — обнаружено инструментов: \${TOOL_COUNT}"
elif echo "\${LIST_OUT}" | grep -q '"error"'; then
  warn "tools/list вернул ошибку: \${LIST_OUT:0:300}"
  warn "Проверьте что supergateway запущен с --stateful"
else
  warn "tools/list: неожиданный ответ: \${LIST_OUT:0:200}"
fi

# ── Проверка 3: --stateful в логах контейнера ──────────────────────────────
log "Проверка 3/3: --stateful в логах..."
if docker logs gitlab-mcp --tail=30 2>&1 | grep -q 'stateful\|Stateful'; then
  ok "--stateful режим подтверждён в логах"
else
  warn "--stateful не найден в логах. Проверьте: docker logs gitlab-mcp --tail=50"
fi

SERVER_IP=\$(hostname -I | awk '{print \$1}')
echo ""
eval "\${DOCKER_COMPOSE} ps"
echo ""
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${GREEN} Режим      : supergateway:3.2.0 (--stateful)\${NC}"
echo -e "\${GREEN} MCP URL    : http://\${SERVER_IP}:\${MCP_PORT}/mcp\${NC}"
echo -e "\${GREEN} Health URL : http://\${SERVER_IP}:\${MCP_PORT}/health\${NC}"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${YELLOW} Open WebUI → Tool Servers → Add:\${NC}"
echo -e "   URL:  http://\${SERVER_IP}:\${MCP_PORT}/mcp"
echo -e "   Type: mcp"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${YELLOW} Следующий шаг — пересобрать Open WebUI:\${NC}"
echo -e "  cd ~/open-webui && docker compose up -d --force-recreate open-webui-init"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
REMOTE_DEPLOY

ok "Деплой GitLab MCP на ${REMOTE_HOST} завершён успешно"
