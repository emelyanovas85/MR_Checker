#!/usr/bin/env bash
# =============================================================================
# deploy-gitlab-mcp.sh — разворачивание GitLab MR MCP Server на удалённой машине
#
# Использование:
# ./deploy-gitlab-mcp.sh [ОПЦИИ]
#
# Опции:
# -h, --host SSH-хост удалённой машины (по умолчанию: 10.1.5.97)
# -u, --user SSH-пользователь (по умолчанию: svc-local-adm)
# -p, --port SSH-порт (по умолчанию: 22)
# -i, --identity Путь к приватному SSH-ключу (необязательно)
# --image Имя итогового Docker-образа (по умолчанию: mcp/gitlab-mr:latest)
# --app-dir Каталог на удалённой машине (по умолчанию: ~/gitlab-mcp)
# --mcp-port Порт MCP-сервера на удалённой машине (по умолчанию: 8083)
# --gitlab-host Базовый URL GitLab (по умолчанию: http://10.1.5.6)
# --token GitLab Personal Access Token (или через GITLAB_PERSONAL_ACCESS_TOKEN)
# --min-access Минимальный уровень доступа для фильтрации проектов (необязатеньно)
# --project-search Строка поиска для фильтрации проектов (необязательно)
# --help Показать справку
#
# Примеры:
# ./deploy-gitlab-mcp.sh --token glpat-xxx
# ./deploy-gitlab-mcp.sh -h 192.168.1.100 -u deploy --token glpat-xxx
# ./deploy-gitlab-mcp.sh --gitlab-host http://10.1.5.6 --token glpat-xxx
# ./deploy-gitlab-mcp.sh --project-search my-project --token glpat-xxx
#
# Источник MCP-сервера:
# https://github.com/kopfrechner/gitlab-mr-mcp
#
# Инструменты (поддерживает GitLab 13+):
# - list_open_merge_requests      — список открытых MR
# - get_merge_request_details     — детали MR
# - get_merge_request_comments    — получить комментарии MR
# - add_merge_request_comment     — добавить комментарий к MR
# - add_merge_request_diff_comment — комментарий к конкретной строке в диффе
# - get_merge_request_diff        — получить diff MR
# - set_merge_request_title       — обновить заголовок MR
# - set_merge_request_description — обновить описание MR
#
# Примечание: удалённый хост не имеет доступа в интернет.
# Исходники kopfrechner/gitlab-mr-mcp скачиваются локально с GitHub.
# Образ собирается локально поверх официального supergateway
# и передаётся на удалённую машину через SSH (docker save | ssh docker load).
#
# Supergateway поднимает Streamable HTTP транспорт (требуется Open WebUI v0.9.6+):
# GET  /health  — health check (возвращает "ok")
# POST /mcp     — Streamable HTTP MCP endpoint (JSON-RPC, отвечает SSE-потоком)
#
# ВАЖНО: --stateful флаг ОБЯЗАТЕЛЕН.
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
BUILT_IMAGE_NAME="mcp/gitlab-mr:latest"
SUPERGATEWAY_IMAGE="supercorp/supergateway:3.2.0"
APP_DIR="~/gitlab-mcp"
MCP_PORT="8083"
GITLAB_HOST="http://10.1.5.6"
GITLAB_PERSONAL_ACCESS_TOKEN="${GITLAB_PERSONAL_ACCESS_TOKEN:-}"
MIN_ACCESS_LEVEL=""
PROJECT_SEARCH_TERM=""
# Папка open-webui-deploy на удалённом хосте (см. emelyanovas85/open-webui-deploy)
OPENWEBUI_DEPLOY_DIR="~/open-webui-deploy"

SOURCE_ARCHIVE_URL="https://github.com/kopfrechner/gitlab-mr-mcp/archive/refs/heads/main.tar.gz"

usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,2\}//'
  exit 0
}

# ── Разбор аргументов ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--host)          REMOTE_HOST="$2";                    shift 2 ;;
    -u|--user)          REMOTE_USER="$2";                    shift 2 ;;
    -p|--port)          REMOTE_PORT="$2";                    shift 2 ;;
    -i|--identity)      SSH_KEY="$2";                        shift 2 ;;
    --image)            BUILT_IMAGE_NAME="$2";               shift 2 ;;
    --app-dir)          APP_DIR="$2";                        shift 2 ;;
    --mcp-port)         MCP_PORT="$2";                       shift 2 ;;
    --gitlab-host)      GITLAB_HOST="$2";                    shift 2 ;;
    --token)            GITLAB_PERSONAL_ACCESS_TOKEN="$2";   shift 2 ;;
    --min-access)       MIN_ACCESS_LEVEL="$2";               shift 2 ;;
    --project-search)   PROJECT_SEARCH_TERM="$2";            shift 2 ;;
    --help)             usage ;;
    *) error "Неизвестный аргумент: $1. Используйте --help для справки." ;;
  esac
done

if [[ -z "${GITLAB_PERSONAL_ACCESS_TOKEN}" ]]; then
  echo -e "${YELLOW}Введите GitLab Personal Access Token:${NC} "
  read -r -s GITLAB_PERSONAL_ACCESS_TOKEN
  echo ""
  [[ -z "${GITLAB_PERSONAL_ACCESS_TOKEN}" ]] && error "Токен не может быть пустым"
fi

[[ ! "${MCP_PORT}" =~ ^[0-9]+$ ]] && error "Некорректный порт: ${MCP_PORT}"

# ── SSH ControlMaster ─────────────────────────────────────────────────────────
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

log "Подключение к ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PORT}..."
$SSH_CMD "echo ok" > /dev/null 2>&1 || error "Не удалось подключиться к ${REMOTE_HOST}"
ok "Соединение установлено"

log "Проверка локальных зависимостей..."
command -v docker >/dev/null 2>&1 || error "Локально не найден docker"
command -v curl   >/dev/null 2>&1 || error "Локально не найден curl"
ok "Локальные зависимости в порядке"

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
else
  echo "ERROR: не найден ни 'docker compose', ни 'docker-compose'"; exit 1
fi
echo "ALL_OK"
REMOTE_CHECK
ok "Зависимости в порядке"

DOCKER_COMPOSE=$($SSH_CMD \
  'if docker compose version >/dev/null 2>&1; then echo "docker compose"; else echo "docker-compose"; fi')
log "Используем compose: ${DOCKER_COMPOSE}"

log "Скачивание исходников gitlab-mr-mcp с GitHub..."
SOURCE_ARCHIVE="$(mktemp /tmp/gitlab-mr-mcp-XXXXXX.tar.gz)"
curl -sL "${SOURCE_ARCHIVE_URL}" -o "${SOURCE_ARCHIVE}" \
  || error "Не удалось скачать исходники с GitHub: ${SOURCE_ARCHIVE_URL}"
ok "Исходники скачаны: ${SOURCE_ARCHIVE}"

log "Скачивание официального образа supergateway ${SUPERGATEWAY_IMAGE}..."
docker pull "${SUPERGATEWAY_IMAGE}" \
  || error "Не удалось скачать образ ${SUPERGATEWAY_IMAGE}"
ok "Образ ${SUPERGATEWAY_IMAGE} готов"

log "Сборка образа ${BUILT_IMAGE_NAME}..."

BUILD_CTX="$(mktemp -d /tmp/gitlab-mr-mcp-build-XXXXXX)"
tar -xzf "${SOURCE_ARCHIVE}" -C "${BUILD_CTX}" --strip-components=1
rm -f "${SOURCE_ARCHIVE}"

cat > "${BUILD_CTX}/Dockerfile" <<DOCKERFILE
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY . .
RUN [ -f tsconfig.json ] && npm run build 2>/dev/null || true

FROM ${SUPERGATEWAY_IMAGE}
WORKDIR /app
COPY --from=builder /app /app
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
ok "Образ ${BUILT_IMAGE_NAME} собран"

log "Передача образа ${BUILT_IMAGE_NAME} на ${REMOTE_HOST}..."
docker save "${BUILT_IMAGE_NAME}" \
  | ssh ${SSH_BASE_OPTS} -p "${REMOTE_PORT}" "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
ok "Образ загружен на ${REMOTE_HOST}"

WORK_DIR="$(mktemp -d /tmp/gitlab-mcp-deploy-XXXXXX)"
ENV_FILE="${WORK_DIR}/.env"
COMPOSE_FILE="${WORK_DIR}/docker-compose.yml"

cat > "${ENV_FILE}" <<EOF_ENV
MR_MCP_GITLAB_HOST=${GITLAB_HOST}
MR_MCP_GITLAB_TOKEN=${GITLAB_PERSONAL_ACCESS_TOKEN}
MR_MCP_MIN_ACCESS_LEVEL=${MIN_ACCESS_LEVEL}
MR_MCP_PROJECT_SEARCH_TERM=${PROJECT_SEARCH_TERM}
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
      MR_MCP_GITLAB_HOST: ${MR_MCP_GITLAB_HOST}
      MR_MCP_GITLAB_TOKEN: ${MR_MCP_GITLAB_TOKEN}
      MR_MCP_MIN_ACCESS_LEVEL: ${MR_MCP_MIN_ACCESS_LEVEL}
      MR_MCP_PROJECT_SEARCH_TERM: ${MR_MCP_PROJECT_SEARCH_TERM}
      PORT: ${MCP_PORT}
EOF_COMPOSE

ok ".env и docker-compose.yml подготовлены"

log "Передача конфигурации на ${REMOTE_HOST}..."
$SSH_CMD "mkdir -p ${APP_DIR}"
$SCP_CMD "${ENV_FILE}"     "${REMOTE_USER}@${REMOTE_HOST}:${APP_DIR}/.env"
$SCP_CMD "${COMPOSE_FILE}" "${REMOTE_USER}@${REMOTE_HOST}:${APP_DIR}/docker-compose.yml"
rm -rf "${WORK_DIR}"
ok "Конфигурация передана"

log "Деплой GitLab MR MCP на ${REMOTE_HOST}:${APP_DIR}"

$SSH_CMD bash <<REMOTE_DEPLOY
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { echo -e "\${BLUE}[\$(date '+%H:%M:%S')]\${NC} \$*"; }
ok()   { echo -e "\${GREEN}[\$(date '+%H:%M:%S')] ✓\${NC} \$*"; }
warn() { echo -e "\${YELLOW}[\$(date '+%H:%M:%S')] ⚠\${NC} \$*"; }
fail() { echo -e "\${RED}[\$(date '+%H:%M:%S')] ✗\${NC} \$*" >&2; exit 1; }

APP_DIR="${APP_DIR}"
MCP_PORT="${MCP_PORT}"
DOCKER_COMPOSE="${DOCKER_COMPOSE}"
# Папка open-webui-deploy (репо emelyanovas85/open-webui-deploy)
OPENWEBUI_DIR="${OPENWEBUI_DEPLOY_DIR}"

[[ "\${APP_DIR}" == ~* ]] && APP_DIR="\${HOME}/\${APP_DIR#\~/}"
[[ "\${OPENWEBUI_DIR}" == ~* ]] && OPENWEBUI_DIR="\${HOME}/\${OPENWEBUI_DIR#\~/}"
cd "\${APP_DIR}"

[[ ! -f .env               ]] && fail "Не найден .env в \${APP_DIR}"
[[ ! -f docker-compose.yml ]] && fail "Не найден docker-compose.yml в \${APP_DIR}"

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

log "Запуск GitLab MR MCP сервера (supergateway, Streamable HTTP, --stateful)..."
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

# ── Проверка 2: MCP handshake ──────────────────────────────────────────────────
log "Проверка 2/3: MCP handshake (initialize → notifications/initialized → tools/list)..."

INIT_RESPONSE_FILE=\$(mktemp /tmp/mcp-init-XXXXXX)
curl -s --noproxy localhost,127.0.0.1 \
  -X POST "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  -D "\${INIT_RESPONSE_FILE}.headers" \
  -d '{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"deploy-check","version":"1.0"}}}' \
  --max-time 8 -o "\${INIT_RESPONSE_FILE}.body" 2>/dev/null || true

SESSION_ID=""
INIT_OK=false

if [[ -f "\${INIT_RESPONSE_FILE}.headers" ]]; then
  SESSION_ID=\$(grep -i '^mcp-session-id:' "\${INIT_RESPONSE_FILE}.headers" \
    | tr -d '\r' | awk '{print \$2}' | head -1 || true)
fi

if [[ -f "\${INIT_RESPONSE_FILE}.body" ]]; then
  INIT_DATA=\$(grep '^data:' "\${INIT_RESPONSE_FILE}.body" | sed 's/^data: //' | head -5 || true)
  if echo "\${INIT_DATA}" | grep -qiE 'serverInfo|protocolVersion'; then
    INIT_OK=true
  fi
fi

rm -f "\${INIT_RESPONSE_FILE}" "\${INIT_RESPONSE_FILE}.headers" "\${INIT_RESPONSE_FILE}.body"

if [[ "\${INIT_OK}" == "true" ]]; then
  ok "initialize: OK (session=\${SESSION_ID:-none})"
else
  warn "initialize: ответ сервера не содержит serverInfo (SSE-тело пустое или таймаут)"
fi

NOTIF_EXTRA_HEADERS=""
[[ -n "\${SESSION_ID}" ]] && NOTIF_EXTRA_HEADERS="-H 'Mcp-Session-Id: \${SESSION_ID}'"
eval curl -s --noproxy localhost,127.0.0.1 \
  -X POST "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  \${NOTIF_EXTRA_HEADERS} \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  --max-time 5 >/dev/null 2>&1 || true
ok "notifications/initialized: отправлено"

LIST_EXTRA_HEADERS=""
[[ -n "\${SESSION_ID}" ]] && LIST_EXTRA_HEADERS="-H 'Mcp-Session-Id: \${SESSION_ID}'"

LIST_RESPONSE_FILE=\$(mktemp /tmp/mcp-list-XXXXXX)
eval curl -s --noproxy localhost,127.0.0.1 \
  -X POST "http://localhost:\${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream, application/json' \
  \${LIST_EXTRA_HEADERS} \
  -d '{"jsonrpc":"2.0","id":"list-1","method":"tools/list","params":{}}' \
  --max-time 10 -o "\${LIST_RESPONSE_FILE}" 2>/dev/null || true

LIST_DATA=\$(grep '^data:' "\${LIST_RESPONSE_FILE}" | sed 's/^data: //' | head -5 || true)
rm -f "\${LIST_RESPONSE_FILE}"

if [[ -z "\${LIST_DATA}" ]]; then
  warn "tools/list: пустой ответ (нет SSE data-строк). Session ID: '\${SESSION_ID:-не получен}'"
elif echo "\${LIST_DATA}" | grep -q '"tools"'; then
  TOOL_COUNT=\$(echo "\${LIST_DATA}" | grep -o '"name"' | wc -l)
  ok "tools/list: OK — инструментов обнаружено: \${TOOL_COUNT}"
  if echo "\${LIST_DATA}" | grep -q 'add_merge_request_comment'; then
    ok "Инструмент add_merge_request_comment: ДОСТУПЕН ✓"
  else
    warn "Инструмент add_merge_request_comment не найден в списке инструментов"
  fi
elif echo "\${LIST_DATA}" | grep -q '"error"'; then
  ERR_MSG=\$(echo "\${LIST_DATA}" | grep -o '"message":"[^"]*"' | head -1 || true)
  warn "tools/list вернул ошибку: \${ERR_MSG:-см. полный ответ выше}"
else
  warn "tools/list: неожиданный ответ: \${LIST_DATA:0:200}"
fi

# ── Проверка 3: --stateful ─────────────────────────────────────────────────────
log "Проверка 3/3: флаг --stateful в конфигурации контейнера..."
CONTAINER_CMD=\$(docker inspect gitlab-mcp --format '{{json .Config.Cmd}}' 2>/dev/null || echo "")
if echo "\${CONTAINER_CMD}" | grep -q 'stateful'; then
  ok "--stateful: присутствует в CMD контейнера ✓"
else
  warn "--stateful не найден в CMD. Конфигурация: \${CONTAINER_CMD}"
fi

SERVER_IP=\$(hostname -I | awk '{print \$1}')
echo ""
eval "\${DOCKER_COMPOSE} ps"
echo ""
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${GREEN} MCP URL    : http://\${SERVER_IP}:\${MCP_PORT}\${NC}"
echo -e "\${GREEN} Health URL : http://\${SERVER_IP}:\${MCP_PORT}/health\${NC}"
echo -e "\${GREEN} Инструменты: list_open_merge_requests, get_merge_request_details,\${NC}"
echo -e "\${GREEN}              get_merge_request_comments, add_merge_request_comment,\${NC}"
echo -e "\${GREEN}              add_merge_request_diff_comment, get_merge_request_diff,\${NC}"
echo -e "\${GREEN}              set_merge_request_title, set_merge_request_description\${NC}"
echo -e "\${GREEN} Режим      : stateful (сессия сохраняется между запросами)\${NC}"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"

# ── Обновление Open WebUI через open-webui-init ────────────────────────────────────
# open-webui-init — одноразовый контейнер (restart: "no") из emelyanovas85/open-webui-deploy.
# Регистрирует MCP-серверы через API Open WebUI.
# Безопасно — основной open-webui не трогается.

if [[ ! -f "\${OPENWEBUI_DIR}/docker-compose.yml" ]]; then
  warn "open-webui-deploy не найден в \${OPENWEBUI_DIR}"
  warn "Зарегистрируйте MCP вручную:"
  warn "  cd \${OPENWEBUI_DIR} && docker-compose run --rm open-webui-init"
else
  log "Обновление MCP-серверов в Open WebUI (через open-webui-init)..."

  if docker compose version >/dev/null 2>&1; then
    OW_COMPOSE="docker compose"
  else
    OW_COMPOSE="docker-compose"
  fi

  cd "\${OPENWEBUI_DIR}"

  # Удаляем оставшийся от предыдущего запуска
  docker rm -f open-webui-init 2>/dev/null || true

  # Запуск без пересборки всего стека: --no-deps только init-контейнер
  INIT_EXIT=0
  \${OW_COMPOSE} run --no-deps --rm --name open-webui-init open-webui-init || INIT_EXIT=\$?

  if [[ "\${INIT_EXIT}" == "0" ]]; then
    ok "Open WebUI обновлён — MCP-серверы зарегистрированы ✓"
  else
    warn "open-webui-init завершился с ошибкой (exit \${INIT_EXIT})"
    warn "Логи: cd \${OPENWEBUI_DIR} && \${OW_COMPOSE} logs open-webui-init"
  fi
fi
REMOTE_DEPLOY

ok "Деплой GitLab MR MCP на ${REMOTE_HOST} завершён успешно"
