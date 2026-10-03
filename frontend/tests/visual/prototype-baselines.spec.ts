/**
 * T016 — normative baselines of the «Aethergram» prototype (SC-001, research
 * §B): every PNG committed under `tests/visual/__screenshots__/` is captured
 * from the static prototype `specs/008-chat-window-styling/design/chats.html`
 * on its demo dataset and is the reference the implementation specs compare
 * against — T026 (US1 regions), T039/T047/T060/T068 (fullscreens).
 *
 * (а) US1 regions @1440×900: the chat catalogue clipped below the top row
 * (the app keeps its tabs until T029 and receives the menu button only in
 * T030 — the comparable region starts at the list), the header/feed/composer
 * of the direct chat, the group chat window.
 *
 * (б) US2+ fullscreens: the sidebar with the main-menu button (no tabs), the
 * open main menu, the «Контакты»/«Мой профиль»/group-creation modals, context
 * menus (chat «gear», contact «kebab»), the toast, the group members tip
 * (consumed by T047), the open burger-drawer @900×700 and the chat window
 * @480×800.
 *
 * Determinism: `animations: 'disabled'` cancels the infinite `flick` lamps to
 * their initial state and fast-forwards the finite `pop`/transitions;
 * `caret: 'hide'` removes the text caret; fonts resolve to the inlined
 * @fontsource binaries (see fixtures/prototype.ts); all captured states are
 * static prototype data. Baselines from the prototype double as its own
 * regression guard: any later change to chats.html fails here until the
 * reference is consciously re-captured.
 */
import { expect } from '@playwright/test'
import { prototypeTest } from './fixtures/prototype'

const DESKTOP = 'chromium-1440x900'
const TABLET = 'chromium-900x700'
const PHONE = 'chromium-480x800'

/** Restricts a baseline to the viewport project it is normative for. */
function onlyProject(project: string): void {
  prototypeTest.skip(prototypeTest.info().project.name !== project, `${project} viewport only`)
}

/** Shared capture options — see the file header. */
const SHOT = { animations: 'disabled', caret: 'hide' } as const

prototypeTest.describe('T016(а) — US1 regions', () => {
  prototypeTest('chat catalogue below the top row', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await expect(prototype.page.locator('#chatList')).toHaveScreenshot('us1-chat-list.png', SHOT)
  })

  prototypeTest('direct chat header', async ({ prototype }) => {
    onlyProject(DESKTOP) // «alex» is the open chat at startup
    await expect(prototype.page.locator('.chat-head')).toHaveScreenshot(
      'us1-direct-chat-head.png',
      SHOT,
    )
  })

  prototypeTest('direct chat feed', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await expect(prototype.page.locator('#chatScroll')).toHaveScreenshot(
      'us1-direct-chat-feed.png',
      SHOT,
    )
  })

  prototypeTest('direct chat composer', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await expect(prototype.page.locator('.chat-input')).toHaveScreenshot(
      'us1-direct-chat-composer.png',
      SHOT,
    )
  })

  prototypeTest('group chat window', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.openChat('phoenix')
    await expect(prototype.page.locator('.chat-name')).toHaveText('Project Phoenix')
    await expect(prototype.page.locator('main.chat')).toHaveScreenshot('us1-group-chat.png', SHOT)
  })
})

prototypeTest.describe('T016(б) — US2+ fullscreens', () => {
  prototypeTest('sidebar with the main-menu button', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await expect(prototype.page).toHaveScreenshot('us2-sidebar.png', SHOT)
  })

  prototypeTest('open main menu', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.openMainMenu()
    await expect(prototype.page).toHaveScreenshot('us2-main-menu-open.png', SHOT)
  })

  prototypeTest('contacts modal', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.openMainMenu()
    await prototype.chooseMainMenu('Контакты')
    await expect(prototype.page.locator('.ctc-row')).toHaveCount(10)
    await expect(prototype.page).toHaveScreenshot('us2-contacts-modal.png', SHOT)
  })

  prototypeTest('my profile modal', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.openMainMenu()
    await prototype.chooseMainMenu('Мой профиль')
    await expect(prototype.page.locator('#profileUsername')).toHaveText('ada')
    await expect(prototype.page).toHaveScreenshot('us2-profile-modal.png', SHOT)
  })

  prototypeTest('create-group modal', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.openMainMenu()
    await prototype.chooseMainMenu('Создать групповой чат')
    await expect(prototype.page.locator('#grpMembers .pick-row')).toHaveCount(10)
    await expect(prototype.page).toHaveScreenshot('us2-create-group-modal.png', SHOT)
  })

  prototypeTest('chat gear context menu', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.page.locator('#btnGear').click()
    await expect(prototype.page.locator('#chatMenu.show')).toBeVisible()
    await expect(prototype.page).toHaveScreenshot('us2-gear-menu.png', SHOT)
  })

  prototypeTest('contact kebab context menu over the contacts modal', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.openMainMenu()
    await prototype.chooseMainMenu('Контакты')
    await expect(prototype.page.locator('.ctc-row')).toHaveCount(10)
    await prototype.page.locator('.ctc-row .c-menu').first().click()
    await expect(prototype.page.locator('#ctxMenu.show')).toBeVisible()
    await expect(prototype.page).toHaveScreenshot('us2-contact-menu.png', SHOT)
  })

  prototypeTest('toast', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.openMainMenu()
    await prototype.chooseMainMenu('Мой профиль')
    await prototype.page.locator('#profileForm button[type="submit"]').click()
    await expect(prototype.page.locator('#toast.show')).toHaveText(
      'Профиль обновлён — Ada Lovelace',
    )
    await expect(prototype.page).toHaveScreenshot('us2-toast.png', SHOT)
  })

  prototypeTest('group members tip', async ({ prototype }) => {
    onlyProject(DESKTOP)
    await prototype.openChat('phoenix')
    await prototype.page.locator('#statusRow').hover()
    await expect(prototype.page.locator('#membersTip.show')).toBeVisible()
    await expect(prototype.page).toHaveScreenshot('us3-members-tip.png', SHOT)
  })

  prototypeTest('open burger drawer', async ({ prototype }) => {
    onlyProject(TABLET)
    await prototype.page.locator('#burger').click()
    await expect(prototype.page.locator('.sidebar.open')).toBeVisible()
    await expect(prototype.page.locator('#backdrop.show')).toBeVisible()
    await expect(prototype.page).toHaveScreenshot('us5-burger-drawer.png', SHOT)
  })

  prototypeTest('chat window on a narrow screen', async ({ prototype }) => {
    onlyProject(PHONE)
    await expect(prototype.page.locator('.chat-name')).toHaveText('Alex Carter')
    await expect(prototype.page).toHaveScreenshot('us5-chat-window.png', SHOT)
  })
})
