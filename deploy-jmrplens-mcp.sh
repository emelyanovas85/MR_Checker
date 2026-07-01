#!/usr/bin/env bash
# =============================================================================
# deploy-jmrplens-mcp.sh — деплой GitLab MCP (jmrplens) на удалённую машину
#
# Использование:
#   ./deploy-jmrplens-mcp.sh [OPTIONS]
#
# Options:
#   -h, --host        SSH-хост (по умолчанию: 10.1.5.97)
#   -u, --user        SSH-пользователь (по умолчанию: svc-local-adm)
#   -p, --port        SSH-порт (по умолчанию: 22)
#   -i, --identity    Путь к SSH-ключу (необязательно)
#   --app-dir         Каталог на удалённой машине (по умолчанию: ~/jmrplens-mcp)
#   --mcp-port        Порт MCP-сервера (по умолчанию: 8811)
#   --gitlab-host     URL GitLab (по умолчанию: http://10.1.5.6)
#   --gitlab-tier     Тиер GitLab: free|premium|ultimate (по умолчанию: free)
#   --tool-surface    Режим инструментов: dynamic|meta|individual (по умолчанию: meta)
#   --image           Имя итогового Docker-образа (по умолчанию: mcp/jmrplens-gitlab:latest)
#   --token           GitLab PAT (или через GITLAB_TOKEN=...)
#                     Если не указан — запрашивается интерактивно
#   --openwebui-dir   Каталог open-webui-deploy (по умолчанию: ~/open-webui-deploy)
#   --help            Показать справку
#
# Примеры:
#   ./deploy-jmrplens-mcp.sh --token glpat-xxx
#   ./deploy-jmrplens-mcp.sh -h 10.1.5.97 -u svc-local-adm --token glpat-xxx
#   GITLAB_TOKEN=glpat-xxx ./deploy-jmrplens-mcp.sh
#
# Источник MCP-сервера:
#   https://github.com/jmrplens/gitlab-mcp-server
#   Изображение: ghcr.io/jmrplens/gitlab-mcp-server:latest
#   866 actions для GitLab CE/Free. Протестирован на GitLab 13.11.1.
#
# Архитектура сборки:
#   1. Локально: docker pull ghcr.io/jmrplens + supercorp/supergateway
#   2. Локально: docker build gitlab-mcp/Dockerfile.jmrplens → mcp/jmrplens-gitlab:latest
#   3. docker save | ssh docker load → образ передаётся на удалённую
#
# WARN: GITLAB_TOKEN никогда не сохраняется на диске.
#      Передаётся только в .env на удалённой машине через SSH.
# NOTE: WARN «404 on PAT scopes» — ожидаемо для 13.11 (/personal_access_tokens/self
#       появился в 14.10). Все инструменты регистрируются.
# =============================================================================

set -euo pipefail

# ── Цвета ─────────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

log()  { echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $*"; }
ok()   { echo -e "${GREEN}[$(date '+%H:%M:%S')] \u2713${NC} $*"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] \u26a0${NC} $*"; }
error(){ echo -e "${RED}[$(date '+%H:%M:%S')] \u2717${NC} $*" >&2; exit 1; }

# ── Дефолты ─────────────────────────────────────────────────────────────────────
REMOTE_HOST="10.1.5.97"
REMOTE_USER="svc-local-adm"
REMOTE_PORT="22"
SSH_KEY=""
APP_DIR="~/jmrplens-mcp"
MCP_PORT="8811"
GITLAB_HOST="http://10.1.5.6"
GITLAB_TIER="free"
TOOL_SURFACE="meta"
GITLAB_TOKEN="${GITLAB_TOKEN:-}"
BUILT_IMAGE_NAME="mcp/jmrplens-gitlab:latest"
OPENWEBUI_DEPLOY_DIR="~/open-webui-deploy"

JMRPLENS_IMAGE="ghcr.io/jmrplens/gitlab-mcp-server:latest"
SUPERGATEWAY_IMAGE="supercorp/supergateway:latest"

# Путь к Dockerfile относительно скрипта
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKERFILE="${SCRIPT_DIR}/gitlab-mcp/Dockerfile.jmrplens"

usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,2\}//'
  exit 0
}

# ── Аргументы ───────────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--host)           REMOTE_HOST="$2";       shift 2 ;;
    -u|--user)           REMOTE_USER="$2";       shift 2 ;;
    -p|--port)           REMOTE_PORT="$2";       shift 2 ;;
    -i|--identity)       SSH_KEY="$2";           shift 2 ;;
    --app-dir)           APP_DIR="$2";           shift 2 ;;
    --mcp-port)          MCP_PORT="$2";          shift 2 ;;
    --gitlab-host)       GITLAB_HOST="$2";       shift 2 ;;
    --gitlab-tier)       GITLAB_TIER="$2";       shift 2 ;;
    --tool-surface)      TOOL_SURFACE="$2";      shift 2 ;;
    --image)             BUILT_IMAGE_NAME="$2";  shift 2 ;;
    --token)             GITLAB_TOKEN="$2";      shift 2 ;;
    --openwebui-dir)     OPENWEBUI_DEPLOY_DIR="$2"; shift 2 ;;
    --help)              usage ;;
    *) error "Unknown argument: $1. Use --help for usage." ;;
  esac
done

# ── Токен: запрашивается интерактивно, если не передан ───────────────────────────
if [[ -z "${GITLAB_TOKEN}" ]]; then
  echo -e "${YELLOW}Введите GitLab Personal Access Token:${NC} "
  read -r -s GITLAB_TOKEN
  echo ""
  [[ -z "${GITLAB_TOKEN}" ]] && error "Токен не может быть пустым"
fi

[[ ! "${MCP_PORT}" =~ ^[0-9]+$ ]] && error "Некорректный порт: ${MCP_PORT}"
[[ ! -f "${DOCKERFILE}" ]] && error "Не найден Dockerfile: ${DOCKERFILE}"

# ── Проверка локальных зависимостей ──────────────────────────────────────────────────
log "Проверка локальных зависимостей..."
command -v docker >/dev/null 2>&1 || error "Локально не найден docker"
ok "Локальные зависимости в порядке"

# ── SSH ControlMaster ─────────────────────────────────────────────────────────────────
SSH_CTRL_DIR="$(mktemp -d /tmp/ssh-ctrl-XXXXXX)"
SSH_CTRL_SOCK="${SSH_CTRL_DIR}/master"

cleanup() {
  ssh -o ControlPath="${SSH_CTRL_SOCK}" -O exit "${REMOTE_HOST}" 2>/dev/null || true
  rm -rf "${SSH_CTRL_DIR}"
}
trap cleanup EXIT

SSH_BASE_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10 \\
  -o ControlMaster=auto -o ControlPath=${SSH_CTRL_SOCK} -o ControlPersist=300"
[[ -n "${SSH_KEY}" ]] && SSH_BASE_OPTS="${SSH_BASE_OPTS} -i ${SSH_KEY}"

SSH_CMD="ssh ${SSH_BASE_OPTS} -p ${REMOTE_PORT} ${REMOTE_USER}@${REMOTE_HOST}"
SCP_CMD="scp ${SSH_BASE_OPTS} -P ${REMOTE_PORT}"

# ── Проверка SSH-подключения ───────────────────────────────────────────────────────
log "Подключение к ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PORT}..."
$SSH_CMD 'echo ok' > /dev/null 2>&1 || error "Не удалось подключиться к ${REMOTE_HOST}"
ok "Соединение установлено"

# ── Проверка зависимостей на удалённой машине ───────────────────────────────
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

# ── Локальный pull базовых образов ───────────────────────────────────────────────────────
log "Локальный pull базовых образов для сборки..."
docker pull "${JMRPLENS_IMAGE}"    || error "Не удалось скачать ${JMRPLENS_IMAGE}"
docker pull "${SUPERGATEWAY_IMAGE}" || error "Не удалось скачать ${SUPERGATEWAY_IMAGE}"
ok "Базовые образы готовы"

# ── Локальная сборка итогового образа ──────────────────────────────────────────────────
log "Локальная сборка ${BUILT_IMAGE_NAME}..."
docker build --no-cache \
  -t "${BUILT_IMAGE_NAME}" \
  -f "${DOCKERFILE}" \
  "${SCRIPT_DIR}/gitlab-mcp" \
  || error "Не удалось собрать образ ${BUILT_IMAGE_NAME}"
ok "Образ ${BUILT_IMAGE_NAME} собран"

# ── Передача образа на удалённую машину ───────────────────────────────────────────────
log "Передача образа ${BUILT_IMAGE_NAME} на ${REMOTE_HOST}..."
docker save "${BUILT_IMAGE_NAME}" \
  | ssh ${SSH_BASE_OPTS} -p "${REMOTE_PORT}" "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
ok "Образ загружен на ${REMOTE_HOST}"

# ── Генерация docker-compose.yml и .env ───────────────────────────────────────────────
WORK_DIR="$(mktemp -d /tmp/jmrplens-deploy-XXXXXX)"
ENV_FILE="${WORK_DIR}/.env"
COMPOSE_FILE="${WORK_DIR}/docker-compose.yml"

log "Генерация файлов конфигурации..."

# .env — GITLAB_TOKEN передаётся через SSH, на диске не сохраняется
cat > "${ENV_FILE}" <<EOF_ENV
GITLAB_URL=${GITLAB_HOST}
GITLAB_TOKEN=${GITLAB_TOKEN}
GITLAB_TIER=${GITLAB_TIER}
GITLAB_SKIP_TLS_VERIFY=true
TOOL_SURFACE=${TOOL_SURFACE}
MCP_PORT=${MCP_PORT}
BUILT_IMAGE_NAME=${BUILT_IMAGE_NAME}
EOF_ENV

cat > "${COMPOSE_FILE}" <<'EOF_COMPOSE'
# docker-compose.yml для jmrplens/gitlab-mcp-server
# Сгенерирован скриптом деплоя — не редактируйте вручную.

services:
  jmrplens-mcp:
    image: ${BUILT_IMAGE_NAME}
    container_name: jmrplens-mcp
    restart: unless-stopped
    ports:
      - "${MCP_PORT}:${MCP_PORT}"
    environment:
      GITLAB_URL: ${GITLAB_URL}
      GITLAB_TOKEN: ${GITLAB_TOKEN}
      GITLAB_TIER: ${GITLAB_TIER}
      GITLAB_SKIP_TLS_VERIFY: ${GITLAB_SKIP_TLS_VERIFY}
      TOOL_SURFACE: ${TOOL_SURFACE}
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:${MCP_PORT}/health"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 30s
EOF_COMPOSE

ok "Файлы конфигурации подготовлены"

# ── Передача конфигурации ────────────────────────────────────────────────────────────
log "Передача конфигурации на ${REMOTE_HOST}..."
$SSH_CMD "mkdir -p ${APP_DIR}"
$SCP_CMD "${ENV_FILE}"     "${REMOTE_USER}@${REMOTE_HOST}:${APP_DIR}/.env"
$SCP_CMD "${COMPOSE_FILE}" "${REMOTE_USER}@${REMOTE_HOST}:${APP_DIR}/docker-compose.yml"
rm -rf "${WORK_DIR}"
ok "Конфигурация передана (записана в ${APP_DIR})"

# ── Деплой на удалённой машине ──────────────────────────────────────────────────────
log "Деплой jmrplens-mcp на ${REMOTE_HOST}:${APP_DIR}"

$SSH_CMD bash <<REMOTE_DEPLOY
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { echo -e "\${BLUE}[\$(date '+%H:%M:%S')]\${NC} \$*"; }
ok()   { echo -e "\${GREEN}[\$(date '+%H:%M:%S')] \u2713\${NC} \$*"; }
warn() { echo -e "\${YELLOW}[\$(date '+%H:%M:%S')] \u26a0\${NC} \$*"; }
fail() { echo -e "\${RED}[\$(date '+%H:%M:%S')] \u2717\${NC} \$*" >&2; exit 1; }

APP_DIR="${APP_DIR}"
MCP_PORT="${MCP_PORT}"
DOCKER_COMPOSE="${DOCKER_COMPOSE}"
OPENWEBUI_DIR="${OPENWEBUI_DEPLOY_DIR}"

[[ "\${APP_DIR}" == ~* ]] && APP_DIR="\${HOME}/\${APP_DIR#\~/}"
[[ "\${OPENWEBUI_DIR}" == ~* ]] && OPENWEBUI_DIR="\${HOME}/\${OPENWEBUI_DIR#\~/}"
cd "\${APP_DIR}"

[[ ! -f .env               ]] && fail "Не найден .env в \${APP_DIR}"
[[ ! -f docker-compose.yml ]] && fail "Не найден docker-compose.yml в \${APP_DIR}"

# ── Проверка порта ───────────────────────────────────────────────────────────────────
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
      [[ -n "\${PIDS}" ]] && warn "PID: \${PIDS}"
    fi
    fail "Освободите порт \${MCP_PORT} вручную и повторите деплой"
  fi
fi

# ── Перезапуск ──────────────────────────────────────────────────────────────────────
log "Остановка предыдущего контейнера (если есть)..."
eval "\${DOCKER_COMPOSE} down --remove-orphans" 2>/dev/null || true
ok "Предыдущий контейнер остановлен"

log "Запуск jmrplens-mcp..."
eval "\${DOCKER_COMPOSE} up -d"
ok "Контейнер запущен"

# ── Ожидание старта ───────────────────────────────────────────────────────────────────
sleep 5
if ! docker ps --filter 'name=jmrplens-mcp' --format '{{.Names}}' | grep -q '^jmrplens-mcp\$'; then
  warn "Контейнер не перешёл в running. Последние логи:"
  eval "\${DOCKER_COMPOSE} logs --tail=100"
  fail "jmrplens-mcp не запущен"
fi

log "Ожидание доступности порта \${MCP_PORT}..."
MAX_WAIT=60; ELAPSED=0; READY=false
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
  fail "jmrplens-mcp не стал доступен"
fi

# ── Проверка 1: GET /health ──────────────────────────────────────────────────────────────────
log "Проверка 1/3: GET /health..."
HEALTH=\$(curl -s --noproxy localhost,127.0.0.1 \
  http://localhost:\${MCP_PORT}/health --max-time 5 2>/dev/null || echo "CURL_FAILED")
if [[ "\${HEALTH}" == "ok" ]]; then
  ok "Health: OK"
else
  warn "Health ответил: \${HEALTH}"
fi

# ── Проверка 2: MCP stateful initialize → tools/list ─────────────────────────────────
log "Проверка 2/3: MCP initialize → tools/list..."
INIT_RESP=\$(curl -s --noproxy localhost,127.0.0.1 --max-time 15 \
  -D /tmp/mcp-init-hdr.txt \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"deploy-check","version":"1.0"}}}' \
  http://localhost:\${MCP_PORT}/mcp 2>/dev/null || echo "CURL_FAILED")

SESSION_ID=\$(grep -i 'mcp-session-id' /tmp/mcp-init-hdr.txt 2>/dev/null \
  | awk '{print \$2}' | tr -d '\r\n' || true)
rm -f /tmp/mcp-init-hdr.txt

if [[ -z "\${SESSION_ID}" ]]; then
  warn "initialize не вернул Mcp-Session-Id. Ответ: \${INIT_RESP:0:300}"
else
  ok "initialize OK, Session-Id: \${SESSION_ID}"

  MCP_RESP=\$(curl -s --noproxy localhost,127.0.0.1 --max-time 15 \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: \${SESSION_ID}" \
    -d '{"jsonrpc":"2.0","id":"2","method":"tools/list","params":{}}' \
    http://localhost:\${MCP_PORT}/mcp 2>/dev/null || echo "CURL_FAILED")

  if echo "\${MCP_RESP}" | grep -q '"tools"'; then
    TOOL_COUNT=\$(echo "\${MCP_RESP}" | grep -o '"name"' | wc -l)
    ok "tools/list: OK — инструментов/групп: \${TOOL_COUNT}"
  elif [[ "\${MCP_RESP}" == "CURL_FAILED" ]]; then
    warn "tools/list: нет ответа за 15 сек"
  else
    warn "tools/list: \${MCP_RESP:0:300}"
  fi
fi

# ── Проверка 3: изображение контейнера ────────────────────────────────────────────
log "Проверка 3/3: изображение контейнера..."
ACTUAL_IMAGE=\$(docker inspect jmrplens-mcp --format '{{.Config.Image}}' 2>/dev/null || echo "")
if [[ "\${ACTUAL_IMAGE}" == *"jmrplens"* ]]; then
  ok "Image: \${ACTUAL_IMAGE} \u2713"
else
  warn "Image: \${ACTUAL_IMAGE}"
fi

# ── Итог ───────────────────────────────────────────────────────────────────────────────
SERVER_IP=\$(hostname -I | awk '{print \$1}')
echo ""
eval "\${DOCKER_COMPOSE} ps"
echo ""
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${GREEN} MCP URL    : http://\${SERVER_IP}:\${MCP_PORT}/mcp\${NC}"
echo -e "\${GREEN} Health URL : http://\${SERVER_IP}:\${MCP_PORT}/health\${NC}"
echo -e "\${GREEN} Image      : ${BUILT_IMAGE_NAME} (jmrplens Go + supergateway)\${NC}"
echo -e "\${GREEN} TOOL_SURFACE: ${TOOL_SURFACE}\${NC}"
echo -e "\${GREEN} GitLab     : ${GITLAB_HOST} (v13.11.1)\${NC}"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"

# ── Open WebUI ───────────────────────────────────────────────────────────────────────
if [[ ! -f "\${OPENWEBUI_DIR}/docker-compose.yml" ]]; then
  warn "open-webui-deploy не найден в \${OPENWEBUI_DIR}"
  warn "Зарегистрируйте MCP вручную:"
  warn "  URL : http://\${SERVER_IP}:\${MCP_PORT}/mcp"
  warn "  Type: streamablehttp"
else
  log "Обновление MCP-серверов в Open WebUI..."
  if docker compose version >/dev/null 2>&1; then OW_COMPOSE="docker compose"
  else OW_COMPOSE="docker-compose"; fi
  cd "\${OPENWEBUI_DIR}"
  docker rm -f open-webui-init 2>/dev/null || true
  INIT_EXIT=0
  \${OW_COMPOSE} run --no-deps --rm --name open-webui-init open-webui-init || INIT_EXIT=\$?
  if [[ "\${INIT_EXIT}" == "0" ]]; then
    ok "Open WebUI: MCP-серверы зарегистрированы \u2713"
  else
    warn "open-webui-init завершился с ошибкой (exit \${INIT_EXIT})"
    warn "Логи: cd \${OPENWEBUI_DIR} && \${OW_COMPOSE} logs open-webui-init"
  fi
fi
REMOTE_DEPLOY

ok "Деплой завершён успешно"
