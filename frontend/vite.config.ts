import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // Local dev routing that mirrors the k8s ingress: SPA on :5173,
  // /api/* forwarded to the backend on :8080 (no CORS in the backend by design)
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Unit/component suites live under src/**; tests/visual/** is the
    // Playwright domain (package script test:visual, own playwright.config)
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})
