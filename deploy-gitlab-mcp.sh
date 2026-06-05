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
# в качестве base image используется официальный образ с Docker Hub:
# supercorp/supergateway:3.2.0 (ghcr.io заблокирован корпоративным прокси)
# Поверх него добавляются файлы GitLab MCP Server.
# Готовый образ передаётся на удалённую машину через SSH
# (docker save | ssh docker load) без промежуточного файла.
# Это позволяет работать без доступа в интернет на удалённом хосте.
#
# Supergateway поднимает SSE-транспорт со следующими endpoint-ами:
# GET  /health  — health check (возвращает "ok")
# GET  /sse     — открытие SSE-сессии, возвращает sessionId
# POST /message?sessionId=<id> — отправка JSON-RPC сообщений
#
# Для Spring AI MCP client используйте:
# spring.ai.mcp.client.sse.connections.gitlab.url: http://<host>:<port>/sse
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
# Используется как base image вместо npm install -g supergateway.
SUPERGATEWAY_IMAGE="supercorp/supergateway:3.2.0"
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

# ── Запрос токена если не задан ─────────────────────────────────────────────
if [[ -z "${GITLAB_PERSONAL_ACCESS_TOKEN}" ]]; then
  echo -e "${YELLOW}Введите GitLab Personal Access Token:${NC} "
  read -r -s GITLAB_PERSONAL_ACCESS_TOKEN
  echo ""
  [[ -z "${GITLAB_PERSONAL_ACCESS_TOKEN}" ]] && error "Токен не может быть пустым"
fi

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
# Имя контейнера должно начинаться с буквы или цифры (Docker запрещает имена с подчёркивания).
docker create --name "gitlab-mcp-extract" "${IMAGE_NAME}"
docker cp gitlab-mcp-extract:/app/. "${BUILD_CTX}/app/" 2>/dev/null \
  || docker cp gitlab-mcp-extract:/usr/local/lib/node_modules/. "${BUILD_CTX}/app/" 2>/dev/null \
  || true
docker rm gitlab-mcp-extract

cat > "${BUILD_CTX}/Dockerfile" <<DOCKERFILE
# Официальный образ supergateway v3.2.0 с Docker Hub — стабильная версия.
# Используем как base image: supergateway уже установлен и настроен,
# npm install -g supergateway не требуется.
FROM ${SUPERGATEWAY_IMAGE}

# Копируем содержимое GitLab MCP Server из исходного образа.
COPY --from=${IMAGE_NAME} /app /app

WORKDIR /app

# supergateway запускается как ENTRYPOINT официального образа.
# Передаём параметры: stdio-команда, порт, SSE/message пути, health endpoint.
CMD ["--stdio", "node dist/index.js", \
     "--port", "${MCP_PORT}", \
     "--outputTransport", "sse", \
     "--ssePath", "/sse", \
     "--messagePath", "/message", \
     "--healthEndpoint", "/health"]
DOCKERFILE

docker build -t "${BUILT_IMAGE_NAME}" "${BUILD_CTX}" \
  || error "Не удалось собрать образ ${BUILT_IMAGE_NAME}"

rm -rf "${BUILD_CTX}"
ok "Образ ${BUILT_IMAGE_NAME} собран локально на базе официального supergateway"

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
      GITLAB_READ_ONLY_MODE: ${GITLAB_READ_ONLY_MODE}
      USE_GITLAB_WIKI: ${USE_GITLAB_WIKI}
      PORT: ${MCP_PORT}
EOF_COMPOSE

ok "Локальные .env и docker-compose.yml подготовлены"

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

log "Запуск GitLab MCP сервера (официальный supergateway:3.2.0 встроен в образ)..."
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
  warn "Это нормально если supergateway старше v2.x — переходим к проверке /sse"
fi

# ── Проверка 2: GET /sse — получение sessionId ──────────────────────────────
log "Проверка 2/3: GET /sse (получение sessionId)..."
SSE_RESPONSE=\$(curl -s --noproxy localhost,127.0.0.1 \
  http://localhost:\${MCP_PORT}/sse --max-time 5 2>/dev/null || echo "CURL_FAILED")

if [[ "\${SSE_RESPONSE}" == "CURL_FAILED" ]]; then
  warn "GET /sse не ответил — проверьте логи: docker logs gitlab-mcp --tail=50"
else
  ok "SSE endpoint отвечает"
fi

SESSION_ID=\$(printf '%s' "\${SSE_RESPONSE}" \
  | sed -n 's#.*sessionId=\([^[:space:]]\+\).*#\1#p' | head -n1)

if [[ -n "\${SESSION_ID}" ]]; then
  ok "sessionId получен: \${SESSION_ID}"
else
  warn "sessionId не найден в ответе /sse. Ответ: \${SSE_RESPONSE}"
fi

# ── Проверка 3: POST /message?sessionId=... — initialize ────────────────────
if [[ -n "\${SESSION_ID}" ]]; then
  log "Проверка 3/3: POST /message?sessionId=\${SESSION_ID} (initialize)..."
  INIT_RESPONSE=\$(curl -s --noproxy localhost,127.0.0.1 -X POST \
    "http://localhost:\${MCP_PORT}/message?sessionId=\${SESSION_ID}" \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"deploy-check","version":"1.0"}}}' \
    --max-time 5 2>/dev/null || echo "CURL_FAILED")

  if [[ "\${INIT_RESPONSE}" == "CURL_FAILED" ]]; then
    warn "POST /message не ответил"
  elif echo "\${INIT_RESPONSE}" | grep -qiE '<html|Cannot POST|503'; then
    warn "POST /message вернул ошибку: \${INIT_RESPONSE}"
  else
    ok "POST /message принят сервером (ответ в SSE-потоке)"
  fi
else
  warn "Проверка 3/3 пропущена — sessionId не был получен"
fi

SERVER_IP=\$(hostname -I | awk '{print \$1}')
echo ""
eval "\${DOCKER_COMPOSE} ps"
echo ""
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${GREEN} SSE URL    : http://\${SERVER_IP}:\${MCP_PORT}/sse\${NC}"
echo -e "\${GREEN} Health URL : http://\${SERVER_IP}:\${MCP_PORT}/health\${NC}"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
echo -e "\${YELLOW} Spring AI config:\${NC}"
echo -e "  spring.ai.mcp.client.sse.connections.gitlab.url: http://\${SERVER_IP}:\${MCP_PORT}/sse\${NC}"
echo -e "\${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\${NC}"
REMOTE_DEPLOY

ok "Деплой GitLab MCP на ${REMOTE_HOST} завершён успешно"
