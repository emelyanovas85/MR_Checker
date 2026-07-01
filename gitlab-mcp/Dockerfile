# Dockerfile для сборки zereight/gitlab-mcp из исходников.
#
# Почему не используем zereight050/gitlab-mcp:latest с Docker Hub?
# Этот образ — сторонний форк, который отстаёт от upstream и не содержит
# тулы для комментариев: create_note, create_merge_request_thread,
# mr_discussions, resolve_merge_request_thread и др.
#
# Собираем напрямую из GitHub: github.com/zereight/gitlab-mcp
#
# Стадии:
#   builder  — node:22-alpine, клонирует репозиторий, компилирует TypeScript
#   release  — чистый node:22-alpine, только production-зависимости + build/
#
# Переменные сборки:
#   BUILD_REF — ветка/тег/SHA для клонирования (по умолчанию: main)

ARG BUILD_REF=main

# ─── Стадия 1: сборка ────────────────────────────────────────────────────────
FROM node:22-alpine AS builder

ARG BUILD_REF

# git нужен только на этапе сборки
RUN apk add --no-cache git

WORKDIR /build

# Клонируем конкретный ref — не тянем всю историю
RUN git clone --depth 1 --branch "${BUILD_REF}" \
    https://github.com/zereight/gitlab-mcp.git .

# Устанавливаем ВСЕ зависимости (включая devDependencies — нужны для tsc)
RUN npm ci --ignore-scripts

# Компилируем TypeScript → build/
RUN npm run build

# ─── Стадия 2: production-образ ──────────────────────────────────────────────
FROM node:22-alpine AS release

WORKDIR /app

# Копируем только результат сборки и манифесты зависимостей
COPY --from=builder /build/build        ./build
COPY --from=builder /build/package.json ./package.json
COPY --from=builder /build/package-lock.json ./package-lock.json

# Устанавливаем только production-зависимости
RUN npm ci --ignore-scripts --omit=dev

ENV NODE_ENV=production

# Upstream по умолчанию слушает на 3002 в Streamable HTTP режиме
EXPOSE 3002

USER node

ENTRYPOINT ["node", "build/index.js"]
