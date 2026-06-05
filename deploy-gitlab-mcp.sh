#!/usr/bin/env bash
# =============================================================================
# deploy-gitlab-mcp.sh — разворачивание GitLab MCP Server на удалённой машине
#
# Использование:
# ./deploy-gitlab-mcp.sh [ОПЦИИ]
#
# Опции:
# -h, --host SSH-хост удалённой машины (по умолчанию: 10.1.5.97)
# -u, --user SSH-пользователь (по умолчанию: svc-local-adm)
# -p, --port SSH-порт (по умолчанию: 22)
# -i, --identity Путь к приватному SSH-ключу (необязательно)
# --image Docker-образ MCP-сервера (по умолчанию: mcp/gitlab:latest)
# --app-dir Каталог на удалённой машине (по умолчанию: ~/gitlab-mcp)
# --mcp-port Порт MCP-сервера на удалённой машине (по умолчанию: 8083)
# --gitlab-url URL GitLab API v4 (по умолчанию: http://10.1.5.6/api/v4)
# --token GitLab Personal Access Token (или через GITLAB_PERSONAL_ACCESS_TOKEN)
# --read-only Запустить сервер в read-only режиме
# --use-wiki Включить инструменты для wiki
# --no-pull Не выполнять docker pull локально (использовать уже существующий локальный образ)
# --help Показать справку
#
# Примеры:
# ./deploy-gitlab-mcp.sh --token glpat-xxx
# ./deploy-gitlab-mcp.sh -h 192.168.1.100 -u deploy --token glpat-xxx
# ./deploy-gitlab-mcp.sh -i ~/.ssh/id_rsa --gitlab-url http://10.1.5.6/api/v4 --token glpat-xxx
# ./deploy-gitlab-mcp.sh --read-only --use-wiki --token glpat-xxx
#
# Примечание: удалённый хост не имеет доступа в интернет.
# Docker-образ mcp/gitlab:latest поддерживает только stdio-транспорт.
# Для работы со Spring AI (HTTP/SSE) используется supergateway —
# npx-обёртка, которая проксирует stdio MCP-сервер в HTTP на указанный порт.
# Образ скачивается/проверяется локально, затем передаётся через SSH
# (docker save | ssh docker load) без промежуточного файла.
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
APP_DIR="~/gitlab-mcp"
MCP_PORT="8083"
GITLAB_API_URL="http://10.1.5.6/api/v4"
GITLAB_PERSONAL_ACCESS_TOKEN="${GITLAB_PERSONAL_ACCESS_TOKEN:-}"
GITLAB_READ_ONLY_MODE="false"
USE_GITLAB_WIKI="false"
NO_PULL=false

usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,2\}//'
  exit 0
}

# ── Разбор аргументов ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--host)      REMOTE_HOST="$2";                       shift 2 ;;
    -u|--user)      REMOTE_USER="$2";                       shift 2 ;;
    -p|--port)      REMOTE_PORT="$2";                       shift 2 ;;
    -i|--identity)  SSH_KEY="$2";                           shift 2 ;;
    --image)        IMAGE_NAME="$2";                        shift 2 ;;
    --app-dir)      APP_DIR="$2";                           shift 2 ;;
    --mcp-port)     MCP_PORT="$2";                          shift 2 ;;
    --gitlab-url)   GITLAB_API_URL="$2";                    shift 2 ;;
    --token)        GITLAB_PERSONAL_ACCESS_TOKEN="$2";      shift 2 ;;
    --read-only)    GITLAB_READ_ONLY_MODE="true";           shift   ;;
    --use-wiki)     USE_GITLAB_WIKI="true";                 shift   ;;
    --no-pull)      NO_PULL=true;                           shift   ;;
    --help)         usage ;;
    *) error "Неизвестный аргумент: $1. Используйте --help для справки." ;;
  esac
done

[[ -z "${GITLAB_PERSONAL_ACCESS_TOKEN}" ]] && \
  error "Не задан GitLab token. Используйте --token или переменную окружения GITLAB_PERSONAL_ACCESS_TOKEN"
[[ ! "${MCP_PORT}" =~ ^[0-9]+$ ]] && error "Некорректный порт: ${MCP_PORT}"

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

# ── Получение образа локально ─────────────────────────────────────────────────
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

# ── Передача образа на сервер ─────────────────────────────────────────────────
log "Передача образа на ${REMOTE_HOST} (docker save | ssh docker load)..."
docker save "${IMAGE_NAME}" \
  | ssh ${SSH_BASE_OPTS} -p "${REMOTE_PORT}" "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
ok "Образ загружен на ${REMOTE_HOST}"

# ── Подготовка env и compose локально ─────────────────────────────────────────
# Образ mcp/gitlab:latest поддерживает только stdio.
# supergateway (npx -y supergateway) проксирует stdio MCP -> HTTP.
WORK_DIR="$(mktemp -d /tmp/gitlab-mcp-deploy-XXXXXX)"
ENV_FILE="${WORK_DIR}/.env"
COMPOSE_FILE="${WORK_DIR}/docker-compose.yml"

cat > "${ENV_FILE}" <<EOF_ENV
GITLAB_API_URL=${GITLAB_API_URL}
GITLAB_PERSONAL_ACCESS_TOKEN=${GITLAB_PERSONAL_ACCESS_TOKEN}
GITLAB_READ_ONLY_MODE=${GITLAB_READ_ONLY_MODE}
USE_GITLAB_WIKI=${USE_GITLAB_WIKI}
MCP_PORT=${MCP_PORT}
IMAGE_NAME=${IMAGE_NAME}
EOF_ENV

cat > "${COMPOSE_FILE}" <<'EOF_COMPOSE'
services:
  gitlab-mcp:
    image: ${IMAGE_NAME}
    container_name: gitlab-mcp
    restart: unless-stopped
    ports:
      - "${MCP_PORT}:${MCP_PORT}"
    environment:
      GITLAB_API_URL: ${GITLAB_API_URL}
      GITLAB_PERSONAL_ACCESS_TOKEN: ${GITLAB_PERSONAL_ACCESS_TOKEN}
      GITLAB_READ_ONLY_MODE: ${GITLAB_READ_ONLY_MODE}
      USE_GITLAB_WIKI: ${USE_GITLAB_WIKI}
      PORT: ${MCP_PORT}
    entrypoint:
      - npx
      - -y
      - supergateway
      - --stdio
      - node dist/index.js
      - --port
      - "${MCP_PORT}"
EOF_COMPOSE

ok "Локальные .env и docker-compose.yml подготовлены (с supergateway)"

# ── Передача файлов на сервер ─────────────────────────────────────────────────
log "Передача конфигурации на ${REMOTE_HOST}..."
$SSH_CMD "mkdir -p ${APP_DIR}"
$SCP_CMD "${ENV_FILE}"     "${REMOTE_USER}@${REMOTE_HOST}:${APP_DIR}/.env"
$SCP_CMD "${COMPOSE_FILE}" "${REMOTE_USER}@${REMOTE_HOST}:${APP_DIR}/docker-compose.yml"
rm -rf "${WORK_DIR}"
ok "Конфигурация передана"

# ── Деплой на сервере ──────────────────────────────────────────────────────────
log "Начало деплоя GitLab MCP на ${REMOTE_HOST}:${APP_DIR}"

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

[[ "\${APP_DIR}" == ~* ]] && APP_DIR="\${HOME}/\${APP_DIR#\~/}"
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

log "Запуск GitLab MCP сервера через supergateway (stdio → HTTP)..."
eval "\${DOCKER_COMPOSE} up -d"
ok "Контейнер запущен"

log "Проверка статуса контейнера..."
sleep 5
if ! docker ps --filter 'name=gitlab-mcp' --format '{{.Names}}' | grep -q '^gitlab-mcp$'; then
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

# Проверка MCP initialize endpoint
log "Проверка MCP HTTP endpoint..."
MCP_RESPONSE=\$(curl -s -X POST http://localhost:\${MCP_PORT}/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"deploy-check","version":"1.0"}}}' \
  --max-time 5 2>/dev/null || echo "CURL_FAILED")

if echo "\${MCP_RESPONSE}" | grep -q '"result"'; then
  ok "MCP endpoint отвечает корректно"
else
  warn "MCP endpoint не ответил ожидаемым образом. Ответ: \${MCP_RESPONSE}"
  warn "Проверьте логи: docker logs gitlab-mcp --tail=50"
fi

SERVER_IP=\$(hostname -I | awk '{print \$1}')
echo ""
eval "\${DOCKER_COMPOSE} ps"
echo ""
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${GREEN} MCP URL (локально):  http://localhost:\${MCP_PORT}\${NC}"
echo -e "\${GREEN} MCP URL (по сети):   http://\${SERVER_IP}:\${MCP_PORT}\${NC}"
echo -e "\${GREEN} Spring AI config:\${NC}"
echo -e "\${GREEN}   spring.ai.mcp.client.streamable-http.connections.gitlab.url=http://localhost:\${MCP_PORT}\${NC}"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
REMOTE_DEPLOY

ok "Деплой GitLab MCP на ${REMOTE_HOST} завершён успешно"
