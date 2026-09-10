# syntax=docker/dockerfile:1
FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable
# Browser OTLP collector endpoint (T028, research.md §21): collector OTLP HTTP
# ingress, baked into the JS bundle by Vite (import.meta.env.VITE_*).
# Public by definition (shipped to every browser) — not a secret (FR-014).
ARG VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
ENV VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=${VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}
COPY package.json pnpm-lock.yaml .npmrc ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1-alpine
COPY --from=build /app/dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
