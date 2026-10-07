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
 * T060 (US4) extension — the three group-management states of the «phoenix»
 * demo group (mine:true): the gear menu with the owner item set
 * («Участники»/«Редактировать чат»/«Удалить чат» — the one gear variant the
 * implementation renders verbatim, ui-behavior §4), the #membersForm roster
 * and the #grpEditForm editor; all three modals compare against their
 * captures in us4-fullscreens. The grpEdit form used to lack the
 * contract-mandated «Описание» field (ui-behavior §3, validation 006) —
 * T094 (bug 16) amended #grpEditForm to carry it (label + input with the
 * «Описание группового чата» placeholder) and re-captured this baseline,
 * so the app assertion compares against the reference directly and the
 * interim implemented snapshot is retired.
 *
 * Determinism: `animations: 'disabled'` cancels the infinite `flick` lamps to
 * their initial state and fast-forwards the finite `pop`/transitions;
 * `caret: 'hide'` removes the text caret; fonts resolve to the inlined
 * @fontsource binaries (see fixtures/prototype.ts); all captured states are
 * static prototype data. The two mobile captures (T068) additionally replay
 * the prototype's own scrollBottom() after the fonts settle — its boot-time
 * call rides the font-swap race and lands short of the bottom by whatever
 * the layout grew, so the feed position of those captures is pinned to the
 * canonical newest-messages state instead. Baselines from the prototype
 * double as its own regression guard: any later change to chats.html fails
 * here until the reference is consciously re-captured.
 */
import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'
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

/** Replays the prototype's own scrollBottom() — a window global of its
 *  classic inline script (the boot path of selectChat). Typed locally:
 *  the page window is not the app's DOM lib. */
function replayScrollBottom(page: Page): Promise<void> {
  return page.evaluate(() => {
    const scrollBottom = (window as { scrollBottom?: (smooth?: boolean) => void }).scrollBottom
    scrollBottom?.()
  })
}

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

  prototypeTest.describe('T060 — US4 group-management states (phoenix)', () => {
    /** Opens the gear menu of the open «phoenix» group (#btnGear ≙ .ch-btn). */
    async function openPhoenixGear(page: import('@playwright/test').Page): Promise<void> {
      await page.locator('#btnGear').click()
      await expect(page.locator('#chatMenu.show')).toBeVisible()
    }

    prototypeTest('group gear context menu (owner item set)', async ({ prototype }) => {
      onlyProject(DESKTOP)
      await prototype.openChat('phoenix')
      await openPhoenixGear(prototype.page)
      await expect(prototype.page.locator('#chatMenu .ctx-item')).toHaveCount(3)
      await expect(prototype.page).toHaveScreenshot('us4-gear-menu-group.png', SHOT)
    })

    prototypeTest('group members modal', async ({ prototype }) => {
      onlyProject(DESKTOP)
      await prototype.openChat('phoenix')
      await openPhoenixGear(prototype.page)
      await prototype.page.locator('#chatMenu .ctx-item', { hasText: 'Участники' }).click()
      await expect(prototype.page.locator('#membersList .pick-row')).toHaveCount(3)
      await expect(prototype.page).toHaveScreenshot('us4-group-members-modal.png', SHOT)
    })

    prototypeTest('group edit modal', async ({ prototype }) => {
      onlyProject(DESKTOP)
      await prototype.openChat('phoenix')
      await openPhoenixGear(prototype.page)
      await prototype.page.locator('#chatMenu .ctx-item', { hasText: 'Редактировать чат' }).click()
      await expect(prototype.page.locator('#grpEditMembers .pick-row')).toHaveCount(3)
      await expect(prototype.page.locator('#grpEditAdd .pick-row')).toHaveCount(7)
      await expect(prototype.page).toHaveScreenshot('us4-group-edit-modal.png', SHOT)
    })
  })

  prototypeTest('open burger drawer', async ({ prototype }) => {
    onlyProject(TABLET)
    // Feed determinism (T068): the prototype's selectChat scrolls the feed
    // to its newest messages at boot, but its scrollBottom runs in the
    // font-swap race — the layout grows a few px once the data-URI faces
    // settle, leaving the boot scroll short of the true bottom by exactly
    // that growth. Replaying the prototype's own scrollBottom() gesture
    // AFTER document.fonts.ready (the harness settle point) seats the
    // capture at the canonical «newest messages, clamped to bottom»
    // state, immune to the race. The same gesture is replayed on the app
    // side of the T068 comparison.
    await replayScrollBottom(prototype.page)
    await prototype.page.locator('#burger').click()
    await expect(prototype.page.locator('.sidebar.open')).toBeVisible()
    await expect(prototype.page.locator('#backdrop.show')).toBeVisible()
    await expect(prototype.page).toHaveScreenshot('us5-burger-drawer.png', SHOT)
  })

  prototypeTest('chat window on a narrow screen', async ({ prototype }) => {
    onlyProject(PHONE)
    await expect(prototype.page.locator('.chat-name')).toHaveText('Alex Carter')
    // Feed determinism (T068): see the burger-drawer capture above — the
    // same post-fonts scrollBottom replay; at 480×800 the demo history
    // no longer fits the window, so the boot scroll position is visible
    // in the capture and must not depend on the font-swap race.
    await replayScrollBottom(prototype.page)
    await expect(prototype.page).toHaveScreenshot('us5-chat-window.png', SHOT)
  })
})
