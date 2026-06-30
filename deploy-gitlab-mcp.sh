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
# --min-access Минимальный уровень доступа для фильтрации проектов (необязательно)
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
# ВАЖНО: "node" и "index.js" — отдельные аргументы CMD (не "node index.js").
# ВАЖНО: kopfrechner/gitlab-mr-mcp (ветка main, апрель 2025) содержит баг:
#        index.js использует устаревший 4-арг. API:
#          server.tool(name, description, zodSchema, handler)
#        Тогда как SDK @modelcontextprotocol/sdk@^1.29.0 требует 3-арг. API:
#          server.tool(name, zodSchemaWithDescription, handler)
#        Инструменты регистрируются с пустым списком (tools/list возвращает []).
#        Фикс: patch-index.mjs при сборке переписывает index.js через AST-парсер,
#        преобразуя все вызовы server.tool(n,d,s,fn) → server.tool(n,s.describe(d),fn).
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

# kopfrechner/gitlab-mr-mcp (main) содержит баг: index.js вызывает
# server.tool(name, description, zodSchema, handler) — 4-арг. API устаревшего SDK.
# SDK @modelcontextprotocol/sdk@^1.29.0 требует 3-арг. форму:
#   server.tool(name, zodSchema.describe(description), handler)
# patch-index.mjs читает index.js как текст и переписывает
# все вызовы server.tool() через посимвольный обход с подсчётом вложенности.
#
# ВАЖНО: depth отслеживает ТОЛЬКО круглые скобки ( ).
# Фигурные { } и квадратные [ ] отслеживаются в nestDepth.
# Это нужно потому что закрывающая } тела async-handler-а
# встречается ВНУТРИ аргументов server.tool() и не должна
# сбрасывать depth до нуля раньше финальной ) вызова.
cat > "${BUILD_CTX}/patch-index.mjs" <<'PATCH_EOF'
import { readFileSync, writeFileSync } from 'fs';

const src = readFileSync('/app/index.js', 'utf8');

// Match: server.tool(
//   "name",
//   "description string",
//   z.object({...}),
//   async (...) => {...}
// );
//
// Transform to:
// server.tool(
//   "name",
//   z.object({...}).describe("description string"),
//   async (...) => {...}
// );
//
// Strategy: find server.tool( calls and rewrite 4-arg -> 3-arg using
// a stateful bracket-counting pass so we handle nested objects correctly.
//
// KEY INVARIANT: `parenDepth` counts only ( and ).
// `nestDepth` counts { } and [ ] separately.
// This ensures that the closing } of an async handler body does NOT
// decrement parenDepth to 0 — only the final ) of server.tool(...) does.

function patch(source) {
  const MARKER = 'server.tool(';
  let result = '';
  let i = 0;

  while (i < source.length) {
    const idx = source.indexOf(MARKER, i);
    if (idx === -1) {
      result += source.slice(i);
      break;
    }

    // Copy everything up to and including 'server.tool('
    result += source.slice(i, idx + MARKER.length);
    i = idx + MARKER.length;

    // Parse the argument list by tracking parens/brackets/braces/strings
    const args = parseArgs(source, i);
    if (args === null || args.list.length !== 4) {
      // Not a 4-arg call — leave as-is
      continue;
    }

    const [nameArg, descArg, schemaArg, handlerArg] = args.list;
    // Rewrite: name, schema.describe(desc), handler
    result += `${nameArg},\n  ${schemaArg}.describe(${descArg}),\n  ${handlerArg}`;
    i = args.end; // skip past closing ')' of outer server.tool(...)
  }

  return result;
}

// Parse comma-separated top-level arguments starting at position `start`
// (just after the opening paren of server.tool). Returns:
//   { list: string[], end: number }  where end points just after the closing ')'
//
// parenDepth: tracks ( and ) only — reaches 0 at the closing ) of server.tool(...)
// nestDepth:  tracks { } and [ ] — never triggers end-of-arglist
function parseArgs(src, start) {
  const list = [];
  let parenDepth = 1; // we're inside the outer '(' of server.tool already
  let nestDepth  = 0; // tracks { } and [ ] nesting inside arguments
  let argStart = start;
  let i = start;

  while (i < src.length) {
    const ch = src[i];

    if (ch === '"' || ch === "'" || ch === '`') {
      i = skipString(src, i);
      continue;
    }

    if (ch === '/' && src[i + 1] === '/') {
      // line comment
      const nl = src.indexOf('\n', i);
      i = nl === -1 ? src.length : nl + 1;
      continue;
    }

    if (ch === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end === -1 ? src.length : end + 2;
      continue;
    }

    // Round parens: only these control top-level argument boundary
    if (ch === '(') { parenDepth++; i++; continue; }
    if (ch === ')') {
      parenDepth--;
      if (parenDepth === 0 && nestDepth === 0) {
        // This is the closing ) of server.tool(...)
        list.push(src.slice(argStart, i).trim());
        return { list, end: i + 1 };
      }
      i++; continue;
    }

    // Curly and square brackets: tracked separately, never end the arg list
    if (ch === '{' || ch === '[') { nestDepth++; i++; continue; }
    if (ch === '}' || ch === ']') { nestDepth--; i++; continue; }

    // Comma at top level (parenDepth===1, nestDepth===0) separates arguments
    if (ch === ',' && parenDepth === 1 && nestDepth === 0) {
      list.push(src.slice(argStart, i).trim());
      argStart = i + 1;
      i++; continue;
    }

    i++;
  }

  return null; // unterminated
}

function skipString(src, i) {
  const quote = src[i];
  i++;
  while (i < src.length) {
    if (src[i] === '\\') { i += 2; continue; }
    if (src[i] === quote) return i + 1;
    // template literal: handle ${ ... }
    if (quote === '`' && src[i] === '$' && src[i + 1] === '{') {
      i += 2;
      let depth = 1;
      while (i < src.length && depth > 0) {
        if (src[i] === '{') depth++;
        else if (src[i] === '}') depth--;
        i++;
      }
      continue;
    }
    i++;
  }
  return i;
}

const patched = patch(src);
const count = (patched.match(/\.describe\(/g) || []).length;
console.log(`patch-index: patched ${count} server.tool() call(s)`);
writeFileSync('/app/index.js', patched, 'utf8');
PATCH_EOF

cat > "${BUILD_CTX}/Dockerfile" <<DOCKERFILE
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install --no-fund --no-audit
COPY . .
# Patch index.js: rewrite 4-arg server.tool(name,desc,schema,fn)
#                 -> 3-arg server.tool(name,schema.describe(desc),fn)
# required by @modelcontextprotocol/sdk >= 1.2 (upstream bug in kopfrechner/gitlab-mr-mcp)
RUN node patch-index.mjs && rm patch-index.mjs

FROM ${SUPERGATEWAY_IMAGE}
WORKDIR /app
COPY --from=builder /app /app
CMD ["--stdio", "node", "index.js", \
     "--port", "${MCP_PORT}", \
     "--outputTransport", "streamableHttp", \
     "--streamableHttpPath", "/mcp", \
     "--healthEndpoint", "/health", \
     "--stateful"]
DOCKERFILE

docker build --no-cache -t "${BUILT_IMAGE_NAME}" "${BUILD_CTX}" \
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

# ── Проверка 2: MCP handshake через python3 ───────────────────────────────────
# В stateful-режиме supergateway выдаёт Mcp-Session-Id при initialize.
# Последующие запросы (notifications/initialized, tools/list) ОБЯЗАНЫ нести
# этот заголовок — иначе сервер возвращает пустое тело.
# bash+curl не подходит: curl с --max-time обрывает SSE-поток раньше, чем
# сервер успевает отправить ответ, и SESSION_ID остаётся пустым.
# python3 (http.client) читает поток построчно и корректно извлекает данные.
log "Проверка 2/3: MCP handshake (initialize → notifications/initialized → tools/list)..."

python3 - "\${MCP_PORT}" <<'PYEOF'
import http.client, json, sys, time

port   = int(sys.argv[1])
host   = "localhost"
TIMEOUT = 15   # секунд на каждый запрос

def sse_post(conn, path, body, extra_headers=None):
    """POST JSON-RPC, читать SSE-поток, вернуть (session_id, list[dict])."""
    headers = {
        "Content-Type": "application/json",
        "Accept":       "text/event-stream, application/json",
    }
    if extra_headers:
        headers.update(extra_headers)
    conn.request("POST", path, body=json.dumps(body), headers=headers)
    resp = conn.getresponse()

    session_id = resp.getheader("Mcp-Session-Id") or ""
    events     = []
    buf        = b""

    try:
        while True:
            chunk = resp.read(4096)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.decode("utf-8", errors="replace").rstrip("\r")
                if line.startswith("data:"):
                    payload = line[5:].strip()
                    if payload:
                        try:
                            events.append(json.loads(payload))
                        except json.JSONDecodeError:
                            pass
                # blank line = end of one SSE event; if we have data, stop
                if line == "" and events:
                    break
    except Exception:
        pass

    return session_id, events

try:
    conn = http.client.HTTPConnection(host, port, timeout=TIMEOUT)

    # ── initialize ────────────────────────────────────────────────────────────
    sid, events = sse_post(conn, "/mcp", {
        "jsonrpc": "2.0", "id": "init-1", "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "deploy-check", "version": "1.0"}
        }
    })

    has_server_info = any("serverInfo" in e or "protocolVersion" in e for e in events)
    if has_server_info:
        print(f"✓ initialize: OK  session={sid or 'stateless'}")
    else:
        print(f"⚠ initialize: serverInfo не найден в ответе  session={sid or 'нет'}")

    # ── notifications/initialized ─────────────────────────────────────────────
    extra = {"Mcp-Session-Id": sid} if sid else {}
    conn2 = http.client.HTTPConnection(host, port, timeout=TIMEOUT)
    conn2.request("POST", "/mcp",
                  body=json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}),
                  headers={
                      "Content-Type": "application/json",
                      "Accept":       "text/event-stream, application/json",
                      **({} if not sid else {"Mcp-Session-Id": sid})
                  })
    conn2.getresponse().read()
    print("✓ notifications/initialized: отправлено")

    # ── tools/list ────────────────────────────────────────────────────────────
    conn3 = http.client.HTTPConnection(host, port, timeout=TIMEOUT)
    _, list_events = sse_post(conn3, "/mcp",
        {"jsonrpc": "2.0", "id": "list-1", "method": "tools/list", "params": {}},
        extra_headers=({"Mcp-Session-Id": sid} if sid else None)
    )

    tools = []
    for ev in list_events:
        result = ev.get("result") or {}
        tools  = result.get("tools", tools)

    if tools:
        names = [t.get("name","?") for t in tools]
        print(f"✓ tools/list: OK — инструментов: {len(tools)}")
        if "add_merge_request_comment" in names:
            print("✓ add_merge_request_comment: ДОСТУПЕН ✓")
        else:
            print(f"⚠ add_merge_request_comment не найден. Список: {names}")
    else:
        err_msg = ""
        for ev in list_events:
            if "error" in ev:
                err_msg = ev["error"].get("message","")
        if err_msg:
            print(f"⚠ tools/list вернул ошибку: {err_msg}")
        else:
            print(f"⚠ tools/list: пустой ответ (session={sid or 'не получен'})")
            print("  Для ручной проверки:")
            print(f"  python3 /tmp/mcp-check.py {port}")

except Exception as e:
    print(f"⚠ MCP handshake: исключение — {e}")
    print("  Это не обязательно означает неисправность сервера.")
    print("  Проверьте вручную: docker logs gitlab-mcp | tail -20")
PYEOF

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
  docker rm -f open-webui-init 2>/dev/null || true

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
