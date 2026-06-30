# =============================================================================
# GitLab MCP Server — Dockerfile
#
# Базовый образ: supercorp/supergateway:3.2.0
# GitLab MCP файлы копируются из mcp/gitlab:latest (локальный образ).
#
# ВАЖНО: SDK 1.29.0 использует zod-compat.js (zod/v3 + zod/v4-mini сабпути),
# которых нет в zod v3. Поэтому index.js патчится при сборке:
#   Before: server.tool(name, z.object({...}).describe(desc), handler)
#   After:  server.tool(name, desc, z.object({...}).shape, handler)
# Это соответствует isZodRawShapeCompat() в SDK 1.29.0.
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

FROM supercorp/supergateway:3.2.0

# Копируем файлы GitLab MCP Server из локального образа
COPY --from=mcp/gitlab:latest /app /app

WORKDIR /app

# Патчим index.js для совместимости с SDK 1.29.0:
# server.tool(name, schema.describe(desc), handler)
# → server.tool(name, desc, schema.shape, handler)
COPY patch-index.mjs /patch-index.mjs
RUN node /patch-index.mjs /app/index.js

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
