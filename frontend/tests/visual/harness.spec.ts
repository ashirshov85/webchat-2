/**
 * T015 self-check: proves the Playwright infrastructure serves the
 * prototype demo dataset deterministically — the messenger boots on the
 * fixture API, renders the catalogue in the prototype's №12 order with
 * the demo unread badges, and opens a dialog with its full history.
 *
 * Runs in every config project (1440×900 / 900×700 / 480×800) because it
 * carries no pixel baseline — it guards the harness the screenshot specs
 * of T016/T026+ are built on.
 */
import { expect } from '@playwright/test'
import { visualTest } from './fixtures/app'

/**
 * Last messages of the demo chats, newest first — the prototype's chat
 * order. Long texts are matched by their ≤64-char prefixes: the №12
 * preview is clamped to 64 code points by the row (a client render
 * decision of the contract).
 */
const ORDERED_PREVIEWS = [
  "Jolly good! It's going to be a night to remember.",
  'Exactly! Come by the workshop tomorrow',
  'Come to the laboratory tonight',
  'Count me in for the test run!',
  'He promised. You know how he is with promises, dear.',
  'Friday it is. Inform the guild.',
  'A fascinating subject, as always.',
  'Next week, once the fabric arrives from Manchester.',
]

visualTest.describe('aethergram fixture harness', () => {
  visualTest('serves the demo chat catalogue in the prototype order', async ({ messenger }) => {
    const rows = await messenger.chatItems.allInnerTexts()
    expect(rows).toHaveLength(ORDERED_PREVIEWS.length)

    let previousPosition = -1
    for (const preview of ORDERED_PREVIEWS) {
      const position = rows.findIndex((row) => row.includes(preview))
      expect(position, `preview must render: ${preview}`).toBeGreaterThanOrEqual(0)
      expect(position, `order breaks at: ${preview}`).toBeGreaterThan(previousPosition)
      previousPosition = position
    }

    const badges = await messenger.page.locator('.chat-item-badge').allInnerTexts()
    expect(badges.sort()).toEqual(['1', '2', '3'])
  })

  visualTest('opens the demo dialog and renders its history', async ({ messenger }) => {
    await messenger.openChat('night to remember')
    await expect(messenger.page.getByText('Hey! Are you free this evening?')).toBeVisible()
    await expect(messenger.page.getByText('Will do. See you at the tower!')).toBeVisible()
    await expect(
      messenger.page.getByText('The old observatory? Sounds intriguing! Count me in.'),
    ).toBeVisible()
  })

  visualTest('serves the same catalogue after a reload', async ({ messenger }) => {
    const before = await messenger.chatItems.allInnerTexts()
    await messenger.page.reload()
    await expect(messenger.chatItems).toHaveCount(ORDERED_PREVIEWS.length)
    const after = await messenger.chatItems.allInnerTexts()
    expect(after).toEqual(before)
  })
})
