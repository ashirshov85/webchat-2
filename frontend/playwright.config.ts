/**
 * E2E-конфигурация конференций (009, US1, T029; quickstart §4):
 * звонок двух вкладок против ЛОКАЛЬНОГО LiveKit — полноценный прогон
 * требует работающего стенда quickstart §2 (docker compose postgres redis
 * mailpit livekit + backend :8080 c LIVEKIT_* env + SPA :5173). Vite
 * dev-сервер поднимается конфигом (webServer), /api проксируется на
 * :8080; остальной стек — внешний (как в тестах 008, контейнерный
 * LiveKit здесь обязателен: проверяется реальная медиа-плоскость).
 *
 * Chromium + fake media: `--use-fake-device-for-media-stream` даёт
 * зелёную камеру/тон микрофона без железа, `--use-fake-ui-for-media-stream`
 * автоматически удовлетворяет запросы разрешений (quickstart §2);
 * `--auto-select-desktop-capture-source` отдаёт демонстрацию экрана
 * без пикера (T078 e2e автопина: livekit-client getDisplayMedia).
 * Запуск: `pnpm --dir frontend exec playwright test tests/e2e/conferences.spec.ts`.
 */
import { defineConfig } from '@playwright/test'

const appPort = 5173
const appUrl = `http://localhost:${appPort}`

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './test-results/e2e',
  // Медиа-подключение двух вкладок против LiveKit: generous per-test
  // бюджет, внутри теста окна строже (SC-001 — expect-таймаутом 5 c).
  timeout: 120_000,
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? appUrl,
    browserName: 'chromium',
    locale: 'ru-RU',
    permissions: ['camera', 'microphone'],
    launchOptions: {
      args: [
        '--use-fake-device-for-media-stream',
        '--use-fake-ui-for-media-stream',
        '--auto-select-desktop-capture-source=Entire screen',
        '--autoplay-policy=no-user-gesture-required',
      ],
    },
    trace: 'retain-on-failure',
    video: 'off',
  },
  expect: {
    timeout: 10_000,
  },
  webServer: {
    command: `pnpm exec vite --port ${appPort} --strictPort`,
    url: appUrl,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
