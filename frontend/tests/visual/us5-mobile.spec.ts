/**
 * T068 — US5 mobile visual regression (SC-001, Clarification): the two
 * mobile states the spec sanctions for the visual comparison — the open
 * burger-drawer @900×700 and the chat window @480×800 — against the
 * committed T016(б) baselines of `design/chats.html`.
 *
 * State parity with the T016(б) captures: the prototype boots with
 * «alex» as the open dialog, so the app opens the same dialog first —
 * at 900×700 the chat window stays visible behind the drawer/backdrop
 * exactly as the baseline depicts (the row keeps its active highlight),
 * at 480×800 the drawer is closed by the very selectChat that opened
 * the dialog (closeSidebar rides selectChat, ui-behavior §7), leaving
 * the burger (aria-expanded false) over the full-bleed chat window.
 * The drawer itself is opened by the same click path the prototype
 * harness used — the burger button — so hover/focus residue matches.
 *
 * Baseline provenance (the split the measured deviations fix):
 *  * the CHAT WINDOW @480×800 compares directly against the prototype
 *    capture `us5-chat-window.png` — no drawer/backdrop is involved,
 *    and the ≤480px compact lexicon (send button, 84% bubbles — T067)
 *    is the prototype's own media block. The capture was consciously
 *    re-taken in T068 with its feed determinism pinned (see below) —
 *    the app seats its feed at the same newest-messages state and the
 *    comparison reads green inside the SC-001 budget;
 *  * the OPEN DRAWER @900×700 measures 52726 differing pixels (~9% of
 *    the viewport — far beyond any budget) against the prototype
 *    capture for a contract-mandated reason: design-tokens §7 orders
 *    «backdrop 35 → sidebar-drawer 40», but the prototype's own
 *    rendering locks the fixed z-40 sidebar inside the `.machine`
 *    z-index:2 stacking context — its open drawer rides UNDER the
 *    z-35 dimming (the latent prototype stacking bug T065 documents;
 *    its rows are unclickable, dead UI contradicting FR-029/SC-005).
 *    The app restores the token norm (`.machine{z-index:auto}` within
 *    the ≤900px block), so its drawer renders above the backdrop and
 *    every drawer pixel that carries ink differs from the dimmed
 *    prototype capture. The baseline is therefore fixed by the
 *    implemented snapshot — the T060 exception class (contract over
 *    prototype pixels); the prototype's own capture stays guarded by
 *    `prototype-baselines.spec.ts` unchanged.
 *
 * Determinism mirrors T016/T026: `animations: 'disabled'` cancels the
 * infinite `flick` lamps and fast-forwards the finite drawer
 * transition (.3s), `caret: 'hide'` removes the text caret; the fixture
 * wall-clock is the fixed 19 сентября 2026 demo day under the ru-RU /
 * Europe/Moscow locale of the shared config. The ≤900px machine height
 * folds to the same 100dvh−72px formula in both (the --vvh/--vvo pair
 * of T066 writes 800/0 and 700/0 at the capture viewports), and the
 * 540px min-height floor stays out of play at both capture points
 * (T066). The feed scroll is pinned on BOTH sides of the comparison:
 * the prototype's boot scrollBottom rides the font-swap race (its
 * layout grows once the data-URI faces settle, leaving the boot scroll
 * short of the true bottom), so the T016(б) mobile captures were
 * consciously re-taken in T068 with the same scrollBottom replayed
 * after `document.fonts.ready`, and the app side seats its feed at the
 * same newest-messages state before every shot (see
 * `seatFeedAtNewest`). The pixel budget is the SC-001
 * `maxDiffPixelRatio ≈ 0.02` of the config.
 */
import { expect } from '@playwright/test'
import { visualTest } from './fixtures/app'

const TABLET = 'chromium-900x700'
const PHONE = 'chromium-480x800'

/** Restricts a surface to the viewport project it is normative for. */
function onlyProject(project: string): void {
  visualTest.skip(visualTest.info().project.name !== project, `${project} viewport only`)
}

/** Shared capture options — the T016 recipe (see the file header). */
const SHOT = { animations: 'disabled', caret: 'hide' } as const

/** Boots the messenger into the prototype boot state: «alex» is the open
 *  dialog behind every T016(б) capture. On ≤900px the harness openChat
 *  rides the burger → row click path, which also proves the drawer
 *  closes back (selectChat carries closeSidebar). */
async function openBootChat(messenger: import('./fixtures/app').MessengerHarness): Promise<void> {
  await messenger.openChat('night to remember')
  await expect(messenger.page.locator('.message-list').getByText('Jolly good!')).toBeVisible()
}

/** Seats the open chat's feed at its newest messages — the state the
 *  prototype's selectChat produces (its scrollBottom) and the
 *  determinized T016(б) captures pin after the fonts settle. The app
 *  keeps the 004 wiring — its feed opens at the top (harness.spec.ts;
 *  SC-002 protects the 004 behavior) — so the same gesture is replayed
 *  for state parity: at 1440×900 the demo history fits and both sides
 *  already read scrollTop 0, at 480×800 it overflows (783 > 538) and
 *  the seat is what the baseline depicts. */
async function seatFeedAtNewest(page: import('@playwright/test').Page): Promise<void> {
  await page
    .locator('.message-list.chat-scroll')
    .evaluate((feed) => feed.scrollTo({ top: feed.scrollHeight }))
}

visualTest.describe('T068 — US5 mobile surfaces', () => {
  /**
   * The implemented baseline (the T060 exception class): the app keeps
   * its own regression reference over the open-drawer state because the
   * prototype's own capture depicts its latent stacking bug — the drawer
   * dimmed under the z-35 backdrop (see the file header). Everything
   * else about the state mirrors the T016(б) capture: «alex» is the
   * open dialog behind the drawer/backdrop, the drawer opens through
   * the burger click path, and the feed behind is seated at its newest
   * messages by the same scrollBottom gesture the determinized baseline
   * capture replays.
   */
  visualTest('open burger drawer (implemented baseline)', async ({ messenger }) => {
    onlyProject(TABLET)
    const burger = messenger.page.getByRole('button', { name: 'Каталог чатов' })
    await openBootChat(messenger)
    await seatFeedAtNewest(messenger.page)
    await burger.click()
    await expect(messenger.page.locator('.sidebar.open')).toBeVisible()
    await expect(messenger.page.locator('.backdrop.show')).toBeVisible()
    await expect(burger).toHaveAttribute('aria-expanded', 'true')
    await expect(messenger.page).toHaveScreenshot('us5-burger-drawer-impl.png', SHOT)
  })

  visualTest(
    'chat window on a narrow screen against the T016(б) baseline',
    async ({ messenger }) => {
      onlyProject(PHONE)
      await openBootChat(messenger)
      await expect(messenger.page.locator('.chat-name')).toHaveText('Alex Carter')
      // The selectChat that opened the dialog has closed the drawer back:
      // the burger returned to its collapsed morph and no backdrop shows.
      await expect(messenger.page.getByRole('button', { name: 'Каталог чатов' })).toHaveAttribute(
        'aria-expanded',
        'false',
      )
      await seatFeedAtNewest(messenger.page)
      await expect(messenger.page).toHaveScreenshot('us5-chat-window.png', SHOT)
    },
  )
})
