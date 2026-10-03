/**
 * Visual regression infrastructure (feature 008, T015; research §B, SC-001).
 *
 * Chromium-only matrix with the prototype's reference viewport (1440×900 —
 * the «machine» window is max-width 1360px) plus the two mobile states of
 * the spec (900×700 — open burger-drawer, 480×800 — chat window). The
 * pixel budget of SC-001 («≤ ~2% differing pixels») maps to Playwright's
 * `maxDiffPixelRatio` and applies to every screenshot assertion of the
 * suite; baselines are captured from the normative prototype
 * `specs/008-chat-window-styling/design/chats.html` (T016) and committed
 * to the repository under `tests/visual/__screenshots__/`.
 *
 * Determinism levers (fixed for every project): locale ru-RU and timezone
 * Europe/Moscow (fixture wall-clock times are +03:00 instants, so ЧЧ:ММ
 * renders verbatim), deviceScaleFactor 1 (the prototype is authored at 1x).
 *
 * The app is served by `vite preview` of a fresh production build — no dev
 * server, no HMR variance. All `/api/v1` traffic (REST + the SSE stream)
 * never reaches the network: specs feed the deterministic Aethergram
 * fixture dataset through `page.route` interception (fixtures/api.ts).
 */
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from '@playwright/test'

const configDir = dirname(fileURLToPath(import.meta.url))
const frontendRoot = resolve(configDir, '../..')
const previewPort = 4173
const previewUrl = `http://localhost:${previewPort}`

export default defineConfig({
  testDir: '.',
  outputDir: './test-results',
  snapshotPathTemplate: '{testDir}/__screenshots__/{testFileDir}/{arg}{-projectName}{ext}',
  timeout: 30_000,
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  reporter: [['list']],
  use: {
    baseURL: previewUrl,
    browserName: 'chromium',
    locale: 'ru-RU',
    timezoneId: 'Europe/Moscow',
    deviceScaleFactor: 1,
    trace: 'retain-on-failure',
  },
  expect: {
    timeout: 10_000,
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.02,
    },
  },
  projects: [
    {
      name: 'chromium-1440x900',
      use: { viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'chromium-900x700',
      use: { viewport: { width: 900, height: 700 } },
    },
    {
      name: 'chromium-480x800',
      use: { viewport: { width: 480, height: 800 } },
    },
  ],
  webServer: {
    command: `pnpm -C "${frontendRoot}" build && pnpm -C "${frontendRoot}" exec vite preview --port ${previewPort} --strictPort`,
    url: previewUrl,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
})
