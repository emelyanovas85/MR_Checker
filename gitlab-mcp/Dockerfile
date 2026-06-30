# =============================================================================
# GitLab MCP Server — Dockerfile
#
# Базовый образ: supercorp/supergateway:3.2.0
# GitLab MCP файлы копируются из mcp/gitlab:latest (локальный образ).
#
# ВАЖНО: @modelcontextprotocol/sdk зафиксирован на 1.10.2
# Начиная с 1.11.0 SDK сменил API server.tool():
#   z.object({...}).describe(desc) больше не распознаётся как валидная схема
#   (isZodRawShapeCompat возвращает false), что вызывает краш при старте.
# SDK 1.10.2 принимает существующий index.js без изменений.
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

# Фиксируем SDK на 1.10.2 — последняя версия совместимая с текущим index.js.
# 1.11.0+ сменили server.tool() API: z.object().describe() больше не работает.
RUN npm install @modelcontextprotocol/sdk@1.10.2 --save --no-fund --no-audit

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
