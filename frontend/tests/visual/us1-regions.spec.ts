/**
 * T026 — US1 visual regression (SC-001 in the US1 scope): the reskinned
 * surfaces of the running app are compared against the committed T016(а)
 * baselines — the very PNGs `prototype-baselines.spec.ts` keeps capturing
 * from the normative prototype `design/chats.html` on its demo dataset,
 * which `fixtures/aethergram.ts` projects 1:1 onto the API 0.7.0 answers
 * (same catalogue, same previews/times/badges, research §B).
 *
 * Regions (T016(а), desktop 1440×900 only — the reference viewport of
 * the machine window):
 *  * the chat catalogue BELOW the sidebar top row — the comparable region
 *    starts at the list itself (`.chat-list` ≙ prototype `#chatList`):
 *    the app keeps its «Чаты/Контакты» tabs until T029 and receives the
 *    main-menu button only in T030, so everything above the list is out
 *    of the US1 comparison (tasks.md T026);
 *  * the direct dialog — header / feed / composer (`.chat-head` ≙
 *    `.chat-head`, `.message-list.chat-scroll` ≙ `#chatScroll`,
 *    `.chat-input` ≙ `.chat-input`); «alex» is the open chat at boot in
 *    the prototype, so the app opens the same dialog;
 *  * the group chat window (`.chat` panel ≙ `main.chat`, «Project
 *    Phoenix»).
 *
 * Determinism mirrors T016: `animations: 'disabled'` cancels the infinite
 * `flick` lamps and fast-forwards the finite `pop`/transitions, `caret:
 * 'hide'` removes the text caret; the fixture wall-clock is a fixed
 * +03:00 instant of 19 сентября 2026 rendered under the ru-RU /
 * Europe/Moscow locale of the shared config, so ЧЧ:ММ reads verbatim.
 * The pixel budget is the SC-001 `maxDiffPixelRatio ≈ 0.02` of the
 * config; the avatar colour/initial derivation from usernames (FR-024 —
 * the API carries no display colours) stays within it by design
 * (fixtures/aethergram.ts, research §E).
 */
import { expect } from '@playwright/test'
import { visualTest } from './fixtures/app'

const DESKTOP = 'chromium-1440x900'

/** Restricts a region to the viewport project it is normative for. */
function onlyProject(project: string): void {
  visualTest.skip(visualTest.info().project.name !== project, `${project} viewport only`)
}

/** Shared capture options — the T016 recipe (see the file header). */
const SHOT = { animations: 'disabled', caret: 'hide' } as const

visualTest.describe('T026 — US1 regions against the T016(а) baselines', () => {
  visualTest('chat catalogue below the top row', async ({ messenger }) => {
    onlyProject(DESKTOP)
    // State parity with the prototype boot: «alex» is the open chat at
    // startup there, so its row carries the active highlight in the
    // baseline — the app opens the same dialog before the shot.
    await messenger.openChat('night to remember')
    await expect(messenger.page.locator('.chat-panel .chat-list')).toHaveScreenshot(
      'us1-chat-list.png',
      SHOT,
    )
  })

  visualTest('direct chat header', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await messenger.openChat('night to remember')
    await expect(messenger.page.getByText('Hey! Are you free this evening?')).toBeVisible()
    await expect(messenger.page.locator('.chat-head')).toHaveScreenshot(
      'us1-direct-chat-head.png',
      SHOT,
    )
  })

  visualTest('direct chat feed', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await messenger.openChat('night to remember')
    await expect(messenger.page.locator('.message-list').getByText('Jolly good!')).toBeVisible()
    await expect(messenger.page.locator('.message-list.chat-scroll')).toHaveScreenshot(
      'us1-direct-chat-feed.png',
      SHOT,
    )
  })

  visualTest('direct chat composer', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await messenger.openChat('night to remember')
    await expect(messenger.page.locator('.chat-input')).toHaveScreenshot(
      'us1-direct-chat-composer.png',
      SHOT,
    )
  })

  visualTest('group chat window', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await messenger.openChat('Project Phoenix')
    await expect(messenger.page.locator('.chat-name')).toHaveText('Project Phoenix')
    await expect(messenger.page.locator('section.chat')).toHaveScreenshot(
      'us1-group-chat.png',
      SHOT,
    )
  })
})
