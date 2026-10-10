/**
 * T060 — US4 visual regression (SC-001 in the US4 scope): the group-
 * management and delivery-resilience surfaces of the running app.
 *
 * Two baseline provenances (the split the task itself sanctions):
 *
 *  * PROTOTYPE states — compared against the committed T016(б)/T060
 *    captures of `design/chats.html`:
 *     - the gear menu over the open «phoenix» group
 *       (`us4-gear-menu-group.png`): the owner item set
 *       («Участники»/«Редактировать чат»/«Удалить чат») is the one gear
 *       variant ui-behavior §4 renders verbatim from #chatMenu, anchor
 *       formulas included (`align: 'end'`, r.bottom + 6, 8px clamp);
 *     - the members modal (`us4-group-members-modal.png`): the 006 role
 *       labels ride the prototype's pick-row lexicon. T095 (bug 17)
 *       moved the roster actions into the per-row «⋯» ContextMenu —
 *       the derived ctc-row lexicon (always-visible kebab, no text
 *       buttons, no .add-ctc-btn) brought the surface CLOSER to the
 *       reference: the kebab glyphs stay inside the ~2% budget on the
 *       phoenix roster, so the implemented-snapshot exception the task
 *       sanctioned (`us4-group-members-modal-impl`) is NOT needed —
 *       the prototype comparison remains the stronger guarantee.
 *
 *  * IMPLEMENTED states — where the static prototype carries no
 *    comparable pixels, the baseline is fixed by the implemented
 *    snapshot in the design tokens (T060's explicit exception):
 *     - the outbox delivery states (T050/T051: «отправляется»,
 *       «не отправлено» + Повторить/Удалить, the composer flood line
 *       «Повтор через N с») and the catch-up sync indicator (T052).
 *       Since T085 (bug 7) the open seat of the fully-read «alex»
 *       dialog folds at the last read row, leaving the seeded outbox
 *       rows below the fold — the capture parks the feed at the very
 *       bottom (see the test body) so both states stay in frame and
 *       the shot is warm-up-race-free.
 *
 *    The edit modal USED to ride this exception (T056/T060): the
 *    contract-mandated «Описание» field (ui-behavior §3, validation
 *    006) was absent from the static #grpEditForm, and its inserted
 *    block shifted every row below (~3% pixels, structural) — the app
 *    kept its own `us4-group-edit-modal-impl` baseline. T094 (bug 16)
 *    closed the gap from the reference side: the prototype was
 *    amended to carry the field (the ui-behavior §3 decision), the
 *    T016(б) baseline re-captured, and the app form compares against
 *    it below like every other prototype-derived surface; the
 *    implemented snapshot is retired.
 *
 * Determinism of the implemented states: the outbox records are seeded
 * straight into `webchat.chats.outbox.<userId>` localStorage by an init
 * script (the harness wipe runs first — init scripts execute in
 * registration order), the page clock is frozen on the demo day
 * (19 сентября 2026, 19:30 +03:00 — after the last demo message), so
 * the seeded `retryAt` (+45 s) reads a constant countdown and the
 * deferred auto-retry never fires inside the shot; the sync indicator
 * is held lit by opening the №18 stream with a completed keep-alive
 * body (every (re)open fires `onOpen` → the №26 catch-up) while the
 * sync POST itself is routed to hang — the single-flight cycle never
 * completes, the strip stays steady. `animations: 'disabled'` cancels
 * the strip's flick lamp; capture options mirror the T016 recipe.
 */
import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { visualTest } from './fixtures/app'
import type { MessengerHarness } from './fixtures/app'
import { AETHERGRAM, ME } from './fixtures/aethergram'

const DESKTOP = 'chromium-1440x900'

/** Restricts a surface to the viewport project it is normative for. */
function onlyProject(project: string): void {
  visualTest.skip(visualTest.info().project.name !== project, `${project} viewport only`)
}

/** Shared capture options — the T016 recipe (see the file header). */
const SHOT = { animations: 'disabled', caret: 'hide' } as const

/** The demo-day evening the frozen clock stands on (after 18:50, +03:00). */
const FROZEN_MS = Date.parse('2026-09-19T19:30:00.000+03:00')

/** The alex chat — the open dialog of every desktop capture (T016(б)). */
function alexChatId(): string {
  const alex = AETHERGRAM.chatList.find((chat) => chat.peer?.username === 'Alex Carter')
  if (alex === undefined) {
    throw new Error('fixture: alex chat not found')
  }
  return alex.chatId
}

/** Boots into the prototype boot state: «alex» is the open dialog. */
async function openBootChat({ page, chatItems }: MessengerHarness): Promise<void> {
  await chatItems.filter({ hasText: 'night to remember' }).first().click()
  await expect(page.locator('.message-list').getByText('Jolly good!')).toBeVisible()
}

/** Opens the «phoenix» group — the subject of every T060 capture. */
async function openPhoenix({ page, chatItems }: MessengerHarness): Promise<void> {
  await chatItems.filter({ hasText: 'Project Phoenix' }).first().click()
  await expect(page.locator('.chat-name')).toHaveText('Project Phoenix')
}

/** Opens the chat gear menu over the currently open group (#btnGear ≙ .ch-btn). */
async function openGear(page: Page): Promise<void> {
  await page.locator('.ch-btn').click()
  await expect(page.locator('.ctx-menu.show')).toBeVisible()
}

/** Picks an item of the currently open gear menu by label substring. */
async function chooseGearItem(page: Page, label: string): Promise<void> {
  await page.locator('.ctx-menu.show .ctx-item', { hasText: label }).first().click()
}

visualTest.describe('T060 — US4 surfaces', () => {
  visualTest('group gear menu against the T016(б) baseline', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openPhoenix(messenger)
    await openGear(messenger.page)
    await expect(messenger.page.locator('.ctx-menu.show .ctx-item')).toHaveCount(3)
    await expect(messenger.page).toHaveScreenshot('us4-gear-menu-group.png', SHOT)
  })

  visualTest('group members modal against the T016(б) baseline', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openPhoenix(messenger)
    await openGear(messenger.page)
    await chooseGearItem(messenger.page, 'Участники')
    await expect(messenger.page.locator('.member-row')).toHaveCount(3)
    await expect(messenger.page).toHaveScreenshot('us4-group-members-modal.png', SHOT)
  })

  /**
   * T094 (bug 16) closed the historic ~3% gap from the reference side:
   * the prototype's #grpEditForm was amended to carry the
   * contract-mandated «Описание» field (ui-behavior §3 decision —
   * label + input with the «Описание группового чата» placeholder,
   * the .modal input lexicon, between «Название» and «Участники»),
   * and the T016(б) baseline was re-captured over the amended
   * prototype. The app form — same DOM shape, same lexica, the
   * phoenix owner state with a null description (the placeholder
   * shows) — compares against that reference within the SC-001
   * budget, exactly like the members modal above; the interim
   * `us4-group-edit-modal-impl` implemented snapshot is retired.
   */
  visualTest('group edit modal against the T016(б) baseline', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openPhoenix(messenger)
    await openGear(messenger.page)
    await chooseGearItem(messenger.page, 'Редактировать чат')
    await expect(messenger.page.locator('.grp-edit-members .pick-row')).toHaveCount(3)
    await expect(messenger.page.locator('.grp-edit-add .pick-row')).toHaveCount(7)
    await expect(messenger.page.locator('.grp-edit-description')).toBeVisible()
    await expect(messenger.page).toHaveScreenshot('us4-group-edit-modal.png', SHOT)
  })

  visualTest('outbox delivery states (implemented baseline)', async ({ messenger }) => {
    onlyProject(DESKTOP)
    const { page } = messenger
    // Seed the two outbox rows of the open «alex» dialog: a terminal
    // `failed` record (plain «не отправлено» + Повторить/Удалить) and a
    // flood-deferred `sending` one («отправляется» bubble + the composer
    // countdown line). The harness wipe init script runs first.
    await page.addInitScript(
      ({ userId, chatId, retryAt }) => {
        const records = [
          {
            clientMessageId: 'us4-visual-outbox-failed-0001',
            chatId,
            text: 'The boiler gauge reads seven atmospheres — shut the valve!',
            state: 'failed',
          },
          {
            clientMessageId: 'us4-visual-outbox-flood-0002',
            chatId,
            text: 'Mind the pressure valves, we are boarding the gondola.',
            state: 'sending',
            retryAt,
          },
        ]
        window.localStorage.setItem(`webchat.chats.outbox.${userId}`, JSON.stringify(records))
      },
      { userId: ME.id, chatId: alexChatId(), retryAt: FROZEN_MS + 45_000 },
    )
    // Freeze the page clock on the demo day: the countdown reads a
    // constant «Повтор через 45 с» and the deferred retry never fires.
    await page.clock.setFixedTime(FROZEN_MS)
    await page.reload()
    await expect(messenger.chatItems).toHaveCount(AETHERGRAM.chatList.length)
    await openBootChat(messenger)
    await expect(page.locator('.message-list').getByText('не отправлено')).toBeVisible()
    await expect(page.locator('.message-input-retry')).toHaveText('Повтор через 45 с')
    expect(await page.evaluate(() => Date.now())).toBe(FROZEN_MS)
    // The catch-up strip of the sidebar stays out of the shot by
    // construction (the aborted №18 never fires onOpen, so no №26
    // cycle runs); the shot waits it dark regardless — a cheap guard
    // against any lingering cycle shifting the sidebar below.
    await expect(page.locator('.sync-indicator')).toHaveCount(0)
    // T085 (bug 7): the open seat folds «alex» at its last read row —
    // the seeded outbox rows land right below the fold, out of frame.
    // The capture's subject is the outbox states, so the feed parks
    // at the very bottom (the same bottom seat T079 gives an
    // own-send). Two warm-up hazards stand between the click and a
    // reproducible seat, and the capture neutralises each explicitly
    // (the T050 fix — the original one-shot park raced them into a
    // bimodal ~11% diff / an out-of-frame row):
    //  * the T086 open-seat settle loop (MessageList SCROLL_SETTLE_*)
    //    re-anchors its seat row via `scrollIntoView` on every rAF
    //    for its whole 90-frame budget — for the alex dialog the
    //    corrections never reach sustained convergence, and any park
    //    issued mid-storm is undone by the next frame's correction
    //    (scrollTop even sits CONSTANT while the loop runs, so plain
    //    quiescence sampling cannot see it). The park therefore
    //    RETRIES until the outbox row is verifiably in view — only a
    //    park issued after the budget is spent sticks, and that is
    //    the state the baseline is captured against;
    //  * the browser virtualisation of message-list.css (research §G,
    //    `content-visibility: auto` + `contain-intrinsic-size:
    //    auto 64px`) keeps off-screen rows at the 64px estimate until
    //    they once intersect the viewport, then remembers the real
    //    height — whether the upper rows had been warmed depends on
    //    timing, and the remembered-vs-estimated delta above shifts
    //    the bottom seat's content slice. The test-only style renders
    //    the list fully (the same determinism lever as
    //    `animations: 'disabled'` — the visible window is
    //    pixel-identical to a fully warmed list).
    await page.addStyleTag({
      content: '.message-list > li { content-visibility: visible; }',
    })
    await expect(async () => {
      await page.evaluate(() => {
        const list = document.querySelector('.message-list')
        if (list !== null) {
          list.scrollTop = list.scrollHeight
        }
      })
      await expect(page.locator('.message-list').getByText('не отправлено')).toBeInViewport({
        timeout: 500,
      })
    }).toPass({ timeout: 20_000 })
    await expect(page).toHaveScreenshot('us4-outbox-states.png', SHOT)
  })

  visualTest('sync indicator during catch-up (implemented baseline)', async ({ messenger }) => {
    onlyProject(DESKTOP)
    const { page } = messenger
    // The №26 catch-up never completes (its POST hangs), so the
    // single-flight `syncing` flag — and the strip — stay lit; the №18
    // stream opens with a completed keep-alive body, firing `onOpen`
    // on every reconnect attempt (each joins the in-flight cycle).
    await page.route('**/api/v1/users/me/sync', () => {})
    await page.route('**/api/v1/users/me/events', (route) =>
      route.fulfill({
        status: 200,
        headers: { 'content-type': 'text/event-stream' },
        body: ': keep-alive\n\n',
      }),
    )
    await page.reload()
    await expect(page.locator('.sync-indicator')).toBeVisible()
    await expect(messenger.chatItems).toHaveCount(AETHERGRAM.chatList.length)
    await openBootChat(messenger)
    await expect(page).toHaveScreenshot('us4-sync-indicator.png', SHOT)
  })
})
