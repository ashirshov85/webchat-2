/**
 * Visual suite harness (feature 008, T015): the `messenger` fixture boots
 * the SPA on the deterministic Aethergram dataset and settles it before
 * the spec runs.
 *
 * Boot recipe (research §B): a fresh Playwright context starts empty, the
 * init script plants only the refresh token the auth guard checks
 * (webchat.auth.refreshToken) and wipes every other `webchat.*` key — so
 * cursors/pendingReads/outbox never leak between navigations and renders
 * stay reproducible. All `/api/v1` traffic is fed by fixtures/api.ts.
 *
 * The settle point is the rendered №12 catalogue — the `.chat-item` hook
 * class is preserved across the reskin as a test wrapper (FR-034,
 * research §C), so the wait survives US1/US2.
 */
import { expect, test as base } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AETHERGRAM_REFRESH_TOKEN, installAethergramApi } from './api'
import { AETHERGRAM_CHAT_COUNT } from './aethergram'

export interface MessengerHarness {
  readonly page: Page
  /** All chat rows of the №12 catalogue, in rendered (demo) order. */
  readonly chatItems: Locator
  /** Opens a chat by a substring of its rendered row (preview text is stable fixture data). */
  openChat(rowText: string): Promise<void>
}

interface VisualFixtures {
  messenger: MessengerHarness
}

export const visualTest = base.extend<VisualFixtures>({
  messenger: async ({ page }, run) => {
    await page.addInitScript((refreshToken) => {
      try {
        window.localStorage.clear()
      } catch {
        // storage unavailable — the token write below degrades the same way
      }
      window.localStorage.setItem('webchat.auth.refreshToken', refreshToken)
    }, AETHERGRAM_REFRESH_TOKEN)
    await installAethergramApi(page)
    await page.goto('/')
    const chatItems = page.locator('.chat-item')
    await expect(chatItems).toHaveCount(AETHERGRAM_CHAT_COUNT)
    await run({
      page,
      chatItems,
      openChat: async (rowText) => {
        await chatItems.filter({ hasText: rowText }).first().click()
      },
    })
  },
})
