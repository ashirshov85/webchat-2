# Aethergram

Веб-мессенджер: личные диалоги, групповые чаты и присутствие пользователей в реальном времени.
Монорепозиторий: Kotlin/Spring Boot backend, React frontend, OpenAPI-контракт, Kubernetes-деплой.

Проект развивается по спецификациям (Spec Kit): каждая фича проходит цикл
spec → clarify → plan → tasks → implement. Текущий статус и планы — в
[specs/ROADMAP.md](specs/ROADMAP.md).

## Реализованные возможности

- **Аккаунты и вход** — регистрация с подтверждением по email, вход по паролю,
  сброс и смена пароля, блокировка слабых паролей.
- **SSO** — вход через внешние IdP по OIDC/OAuth2, привязка/отвязка аккаунтов,
  Just-In-Time User Provisioning.
- **Личные сообщения** — диалоги 1:1, история с курсорной пагинацией,
  отметки о прочтении, контакты, точный поиск пользователей, блокировка.
- **Доставка без потерь** — дедупликация по клиентскому ID сообщения
  (эффект exactly-once), reconnect-синхронизация по курсорам, счётчики непрочитанного,
  offline-outbox на клиенте, backpressure с `Retry-After` при перегрузке.
- **Групповые чаты** — роли owner/admin/member, управление участниками,
  передача владения, удаление чата, лимит 200 участников.
- **Присутствие** — статусы online/offline через SSE + Redis TTL,
  режим «невидимка», защита от «дребезга» статуса (гистерезис).
- **Социальные сигналы** — индикатор набора текста, «был(а) недавно»,
  отображаемые имена с псевдонимами, отдельные звуковые уведомления для чатов.
- **Realtime через SSE** — `GET /api/v1/users/me/events`; фан-аут между
  инстансами через Redis pub/sub.

## Технические особенности

- **Stateless-архитектура.** Ни HTTP-сессий, ни состояния в памяти — всё в
  Postgres/Redis. Перезапуск или масштабирование пода не требует изменений кода.
- **Безопасность.** Короткоживущие JWT + ротация refresh-токенов, rate-limiting
  (Bucket4j + Redis) по IP/email/флуду сообщений, сканирование секретов gitleaks,
  никаких секретов в манифестах и репозитории.
- **API-first.** Единственный источник истины — `contracts/openapi.yaml` (OpenAPI 3.1).
  Типы фронтенда генерируются из контракта; CI ловит дрейф типов и
  breaking-changes (`oasdiff`), осознанные ломающие изменения — только через
  маркер `contracts/BREAKING.md`.
- **Наблюдаемость end-to-end.** Трейсы по OTLP с обеих сторон: Micrometer на
  backend и OTel Web SDK в браузере (`traceparent` в каждом fetch). Один
  `traceId` связывает логи (Loki), трейсы (Tempo) и метрики (Prometheus).
- **Качество под контролем CI.** Интеграционные тесты на Testcontainers
  (реальный Postgres), visual regression и E2E на Playwright, нагрузочные
  сценарии k6, ktlint/detekt/SonarQube на backend.

## Стек

| Слой | Технологии |
| --- | --- |
| Backend | Kotlin, JVM 21, Spring Boot 3 (Security/OAuth2, JDBC, Flyway), виртуальные потоки |
| Frontend | React 19, TypeScript (strict), Vite 8, OpenTelemetry Web SDK |
| Данные | PostgreSQL 17 (основное хранилище), Redis 7 (rate-limit, presence, pub/sub) |
| Realtime | Server-Sent Events + Redis pub/sub |
| Инфраструктура | Docker, Kustomize, GitHub Actions, LGTM-стек (Grafana/Loki/Tempo/Prometheus) |
