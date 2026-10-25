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
 * `--use-file-for-fake-audio-capture` подменяет бип дефолтного fake-
 * устройства НЕПРЕРЫВНЫМ громким тоном (WAV генерируется в tmp):
 * бип маргинален против порога speaking-детекции LiveKit (active
 * level) — пульс бейджа микрофона T100 у говорящего приезжал
 * нестабильно; непрерывный тон делает «речь» детерминированной.
 * Запуск: `pnpm --dir frontend exec playwright test tests/e2e/conferences.spec.ts`.
 */
import { writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { defineConfig } from '@playwright/test'

const appPort = 5173
const appUrl = `http://localhost:${appPort}`

/**
 * WAV 440 Гц 0.8 амплитуды (16 кГц mono, 5 с — Chromium зациклит):
 * непрерывный RMS ~0.57 заведомо выше порога active_level LiveKit.
 */
function fakeToneWavPath(): string {
  const path = join(tmpdir(), 'webchat-e2e-tone-440hz.wav')
  const sampleRate = 16_000
  const samples = sampleRate * 5
  const data = Buffer.alloc(samples * 2)
  for (let i = 0; i < samples; i += 1) {
    const value = Math.round(Math.sin((2 * Math.PI * 440 * i) / sampleRate) * 0.8 * 0x7fff)
    data.writeInt16LE(value, i * 2)
  }
  const header = Buffer.alloc(44)
  header.write('RIFF', 0)
  header.writeUInt32LE(36 + data.length, 4)
  header.write('WAVE', 8)
  header.write('fmt ', 12)
  header.writeUInt32LE(16, 16)
  header.writeUInt16LE(1, 20)
  header.writeUInt16LE(1, 22)
  header.writeUInt32LE(sampleRate, 24)
  header.writeUInt32LE(sampleRate * 2, 28)
  header.writeUInt16LE(2, 32)
  header.writeUInt16LE(16, 34)
  header.write('data', 36)
  header.writeUInt32LE(data.length, 40)
  writeFileSync(path, Buffer.concat([header, data]))
  return path
}

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
        `--use-file-for-fake-audio-capture=${fakeToneWavPath()}`,
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
