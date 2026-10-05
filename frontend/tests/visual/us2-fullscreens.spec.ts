/**
 * T039 — US2 fullscreen visual regression (SC-001 in the US2 scope): the
 * tabless surfaces of the running app are compared against the committed
 * T016(б) baselines — the very PNGs `prototype-baselines.spec.ts` keeps
 * capturing from the normative prototype `design/chats.html` on its demo
 * dataset, which `fixtures/aethergram.ts` projects 1:1 onto the API 0.7.0
 * answers (same catalogue, same contacts book, research §B).
 *
 * Surfaces (T016(б), desktop 1440×900 only — the reference viewport of
 * the machine window). The states became comparable only after US2: the
 * «Чаты/Контакты» tabs are gone (T029) and the main-menu button stands
 * in the search row (T030), so the sidebar top row — excluded from the
 * US1 regions of T026 — now enters the fullscreen comparison:
 *  * the sidebar FULLSCREEN together with the open «alex» window — the
 *    whole machine of the boot state (`#sidebar` ≙ `.chat-panel .sidebar`,
 *    `main.chat` ≙ `section.chat`);
 *  * the OPEN MAIN MENU over that state (`.ctx-menu.show` ≙ `#mainMenu`,
 *    anchored at the «three stripes» button — r.left / r.bottom + 6);
 *  * the «Контакты» modal (`.modal-back.show` ≙ `#modalBack`, rows
 *    `.pick-row.ctc-row` ≙ `.ctc-row`);
 *  * the «Мой профиль» modal (readonly username/email ≙
 *    `#profileUsername`/`#profileEmail`);
 *  * the «Создать групповой чат» modal (`.grp-members .pick-row` ≙
 *    `#grpMembers .pick-row`).
 *
 * State parity with the T016(б) captures: «alex» is the open chat at
 * prototype boot, so the app opens the same dialog before every shot
 * (the row keeps its active highlight exactly as the baseline depicts);
 * the menu/modals are reached through the very click path the prototype
 * harness used — menu button, then the menu item — so hover/focus
 * residue matches the baseline too.
 *
 * Determinism mirrors T016/T026: `animations: 'disabled'` cancels the
 * infinite `flick` lamps and fast-forwards the finite `pop`/transitions,
 * `caret: 'hide'` removes the text caret; the fixture wall-clock is the
 * fixed 19 сентября 2026 demo day under the ru-RU / Europe/Moscow
 * locale of the shared config, so ЧЧ:ММ and the date divider read
 * verbatim. The pixel budget is the SC-001 `maxDiffPixelRatio ≈ 0.02`
 * of the config.
 */
import { expect } from '@playwright/test'
import { visualTest } from './fixtures/app'

const DESKTOP = 'chromium-1440x900'

/** Restricts a surface to the viewport project it is normative for. */
function onlyProject(project: string): void {
  visualTest.skip(visualTest.info().project.name !== project, `${project} viewport only`)
}

/** Shared capture options — the T016 recipe (see the file header). */
const SHOT = { animations: 'disabled', caret: 'hide' } as const

/** Boots the messenger into the prototype boot state: «alex» is the open
 *  dialog of every T016(б) capture, so the machine behind the menu and
 *  the modals must render the same window. The settle text is scoped to
 *  the feed — the catalogue preview of the same «alex» row matches it
 *  too (the strict-mode locator would see both). */
async function openBootChat({ page, chatItems }: import('./fixtures/app').MessengerHarness) {
  await chatItems.filter({ hasText: 'night to remember' }).first().click()
  await expect(page.locator('.message-list').getByText('Jolly good!')).toBeVisible()
}

/** Opens the «three stripes» main menu of the search row (openMainMenu of
 *  the prototype harness): the anchored .ctx-menu.show must appear. */
async function openMainMenu(page: import('@playwright/test').Page) {
  await page.locator('.chat-panel .menu-btn').click()
  await expect(page.locator('.ctx-menu.show')).toBeVisible()
}

/** Picks an item of the currently open main menu by label substring
 *  (chooseMainMenu of the prototype harness). */
async function chooseMainMenu(page: import('@playwright/test').Page, label: string) {
  await page.locator('.ctx-menu.show .ctx-item', { hasText: label }).first().click()
}

visualTest.describe('T039 — US2 fullscreens against the T016(б) baselines', () => {
  visualTest('sidebar fullscreen with the main-menu button', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openBootChat(messenger)
    await expect(messenger.page).toHaveScreenshot('us2-sidebar.png', SHOT)
  })

  visualTest('open main menu', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openBootChat(messenger)
    await openMainMenu(messenger.page)
    await expect(messenger.page).toHaveScreenshot('us2-main-menu-open.png', SHOT)
  })

  visualTest('contacts modal', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openBootChat(messenger)
    await openMainMenu(messenger.page)
    await chooseMainMenu(messenger.page, 'Контакты')
    await expect(messenger.page.locator('.ctc-row')).toHaveCount(10)
    await expect(messenger.page).toHaveScreenshot('us2-contacts-modal.png', SHOT)
  })

  visualTest('my profile modal', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openBootChat(messenger)
    await openMainMenu(messenger.page)
    await chooseMainMenu(messenger.page, 'Мой профиль')
    // The readonly username of #profileUsername ≙ the first .modal-ro <b>.
    await expect(messenger.page.locator('.profile-form .modal-ro b').first()).toHaveText('ada')
    await expect(messenger.page).toHaveScreenshot('us2-profile-modal.png', SHOT)
  })

  visualTest('create-group modal', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openBootChat(messenger)
    await openMainMenu(messenger.page)
    await chooseMainMenu(messenger.page, 'Создать групповой чат')
    await expect(messenger.page.locator('.grp-members .pick-row')).toHaveCount(10)
    await expect(messenger.page).toHaveScreenshot('us2-create-group-modal.png', SHOT)
  })
})
