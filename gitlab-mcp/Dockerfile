# =============================================================================
# GitLab MCP Server — Dockerfile
#
# Базовый образ: node:20-alpine (сборка собственного index.js)
# Финальный образ: supercorp/supergateway:3.2.0
#
# Отличия от предыдущей версии (zereight/kopfrechner):
#   - Нет зависимости от локального образа mcp/gitlab:latest
#   - Собственный index.js — совместим с GitLab 13.11 API v4
#   - @modelcontextprotocol/sdk зафиксирован на 1.10.2
#   - process.stdin.resume() держит node живым в --stateful режиме
#
# ВАЖНО: --stateful ОБЯЗАТЕЛЕН
# Без него supergateway запускает новый процесс на каждый POST.
# MCP требует 3 запроса в одной сессии:
#   1. POST initialize        → Mcp-Session-Id
#   2. POST notifications/initialized
#   3. POST tools/list / tools/call
# В stateless-режиме шаги 2-3 попадают в новый процесс без инициализации
# и возвращают пустой список или ошибку "No valid session ID provided".
#
# ВАЖНО: "node" и "index.js" — ОТДЕЛЬНЫЕ элементы массива CMD
# supergateway передаёт CMD-аргументы напрямую в execvp().
# Если написать ["--stdio", "node index.js", ...], execvp ищет бинарь
# с именем «node index.js» (со пробелом) — и сразу падает с ENOENT.
# =============================================================================

# --- Stage 1: установка зависимостей ---
FROM node:20-alpine AS builder

WORKDIR /app

COPY package.json ./

# Устанавливаем зависимости без devDependencies
RUN npm install --omit=dev --no-fund --no-audit

COPY index.js ./

# --- Stage 2: финальный образ на базе supergateway ---
FROM supercorp/supergateway:3.2.0

# Устанавливаем Node.js в образ supergateway (который на базе Alpine)
RUN apk add --no-cache nodejs

# Копируем приложение из builder-стадии
COPY --from=builder /app /app

WORKDIR /app

# supergateway запускается как ENTRYPOINT базового образа.
# --stateful: сохраняет дочерний процесс между запросами одной MCP-сессии.
# --outputTransport streamableHttp: требуется для Open WebUI v0.9.6+.
# ВАЖНО: "node" и "index.js" — строго отдельные элементы, не "node index.js".
CMD ["--stdio", "node", "index.js", \
     "--port", "8083", \
     "--outputTransport", "streamableHttp", \
     "--streamableHttpPath", "/mcp", \
     "--healthEndpoint", "/health", \
     "--stateful"]
