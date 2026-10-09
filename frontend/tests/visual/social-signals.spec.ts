/**
 * T025 — 008a US1 visual regression (SC-004 in the US1 scope, 2% budget
 * of the shared config): the two 008a form surfaces — the «Имя» field of
 * «Мой профиль» (T021, ui-behavior §1.1) and the «Имя контакта» rename
 * form behind the contact kebab «Переименовать» (T022, ui-behavior §1.2)
 * — plus the chain convergence they drive on the owner's surfaces.
 *
 * The scenarios ride the visual-suite harness (fixtures/app.ts) on the
 * Aethergram demo dataset: u1–u5 carry their display names verbatim in
 * `username` (fixtures/aethergram.ts, research §B), so the pristine US1
 * surfaces render the very names the committed T016 baselines depict —
 * the full T026/T039 runs stay green through 008a US1 (verified in the
 * T025 run: the US1 chain/initials derivation is pixel-identical within
 * the budget, `us1-direct-chat-head` et al. need no re-capture). What
 * the prototype dataset has NO state for is the 008a mutations — a
 * typed profile name, a set alias — so those states pin their OWN
 * `008a-*` baselines here (the `-impl` exception class of
 * us3-members-tip/us5-burger-drawer: app-owned snapshots for states the
 * static prototype truth does not carry).
 *
 * Coverage (desktop 1440×900 only — the reference viewport, the
 * us1-regions/us2-fullscreens convention):
 *  * the contact kebab menu with «Переименовать» FIRST (ui-behavior
 *    §1.2) against the committed prototype baseline `us2-contact-menu`
 *    — the PNG had no app-side consumer yet;
 *  * the «Имя» field over the incognito row: placeholder = username,
 *    maxlength 64, typed Cyrillic «Мария» (FR-001) and the client
 *    whitespace validation `Укажите имя без пробелов в начале и
 *    конце` (the mirror of 400 invalid_display_name, T021);
 *  * the rename form «Имя контакта» prefilled with the current chain,
 *    readonly username/email, shell title «Редактировать контакт»
 *    (FR-003) and its whitespace error «Укажите имя контакта»;
 *  * the №40 success path: a stateful PUT/№20/№12 interception renames
 *    «Alex Carter» to «Маша» — the modal row resort («Маша» rides to
 *    the top of the `ru` chain sort, FR-005), the toast «Контакт
 *    переименован — Маша» (ui-behavior §5) and, after the shell
 *    closes, the direct-chat header carrying the alias: title «Маша»,
 *    initials «М» from the chain while the avatar KEEPS the username
 *    colour (FR-004) — the `us1-direct-chat-head` surface in its alias
 *    state, pinned as `008a-direct-chat-head-alias`.
 *
 * Determinism mirrors T026/T039: `animations: 'disabled'` cancels the
 * infinite lamps and fast-forwards the finite pop/transitions,
 * `caret: 'hide'` removes the text caret; the fixture wall-clock is the
 * fixed +03:00 demo day under the ru-RU / Europe/Moscow locale of the
 * shared config. The toast auto-hides on its 3000 ms JS timer
 * (ui/Toast) — the header capture waits it out, so no timing residue
 * enters the shot.
 *
 * T040/T050/T060 will append the US2–US4 scenarios (typing row,
 * lastSeen statuses, bell on/off) to this same file.
 */
import { expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { visualTest } from './fixtures/app'
import { AETHERGRAM } from './fixtures/aethergram'
import type { ChatListItem, ContactView } from '../../src/api/chats'

const DESKTOP = 'chromium-1440x900'

/** Restricts a scenario to the viewport project it is normative for. */
function onlyProject(project: string): void {
  visualTest.skip(visualTest.info().project.name !== project, `${project} viewport only`)
}

/** Shared capture options — the T016 recipe (see the file headers). */
const SHOT = { animations: 'disabled', caret: 'hide' } as const

/** Boots the messenger into the prototype boot state: «alex» (Alex
 *  Carter) is the open dialog of every scenario, so the rename
 *  convergence lands on the live header. The settle text is scoped to
 *  the feed — the catalogue preview of the same row matches it too. */
async function openAlexChat({ page, chatItems }: import('./fixtures/app').MessengerHarness) {
  await chatItems.filter({ hasText: 'night to remember' }).first().click()
  await expect(page.locator('.message-list').getByText('Jolly good!')).toBeVisible()
}

/** Opens the «three stripes» main menu and picks an item by label
 *  (the us2-fullscreens helpers): menu button → .ctx-menu.show → item. */
async function chooseMainMenu(page: Page, label: string): Promise<void> {
  await page.locator('.chat-panel .menu-btn').click()
  await expect(page.locator('.ctx-menu.show')).toBeVisible()
  await page.locator('.ctx-menu.show .ctx-item', { hasText: label }).first().click()
}

/**
 * The stateful №40/№20/№12 backend of the rename-success scenario: the
 * pages' own mutation (PUT №40) is answered honestly and the read
 * surfaces (№20 book, №12 list) start carrying the alias — the refetch
 * semantics of ui-behavior §1.2 without a realtime rename event.
 * Registered AFTER the harness dataset, so the later routes win
 * (Playwright consults page.route handlers latest-first).
 */
function installAliasBackend(page: Page): void {
  const alex = AETHERGRAM.contacts('')
    .map((contact) => contact.user)
    .find((user) => user.username === 'Alex Carter')
  if (alex === undefined) {
    throw new Error('social-signals fixture: Alex Carter is not in the demo book')
  }
  let alias: string | null = null

  const aliasedRow = (): ContactView => {
    const row = AETHERGRAM.contacts('').find((contact) => contact.user.id === alex.id)
    if (row === undefined) {
      throw new Error('social-signals fixture: the alex contact row is missing')
    }
    return alias === null ? row : { ...row, alias }
  }
  void page.route(/\/api\/v1\/contacts\/[^/]+\/alias$/, async (route: Route) => {
    const body = (await route.request().postDataJSON()) as { alias?: string | null }
    alias = body.alias ?? null
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(aliasedRow()),
    })
  })
  void page.route('**/api/v1/contacts', async (route: Route) => {
    const contacts = AETHERGRAM.contacts('').map((contact) =>
      contact.user.id === alex.id ? aliasedRow() : contact,
    )
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ contacts }),
    })
  })
  void page.route('**/api/v1/chats', async (route: Route) => {
    const chats = AETHERGRAM.chatList.map((item): ChatListItem =>
      item.peer?.id === alex.id ? { ...item, peerAlias: alias ?? undefined } : item,
    )
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ chats }),
    })
  })
}

visualTest.describe('T025 — 008a US1: profile «Имя» field and the rename form', () => {
  visualTest('contact kebab menu carries «Переименовать» first', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openAlexChat(messenger)
    await chooseMainMenu(messenger.page, 'Контакты')
    await expect(messenger.page.locator('.ctc-row')).toHaveCount(10)
    await messenger.page.locator('.ctc-row .c-menu').first().click()
    const menu = messenger.page.locator('.ctx-menu.show')
    await expect(menu).toBeVisible()
    // ui-behavior §1.2: «Переименовать» is the FIRST item, before
    // «Заблокировать»/«Удалить чат»/«Удалить контакт».
    await expect(menu.locator('.ctx-item').first()).toHaveText('Переименовать')
    await expect(messenger.page).toHaveScreenshot('us2-contact-menu.png', SHOT)
  })

  visualTest('profile «Имя» field with a typed name', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openAlexChat(messenger)
    await chooseMainMenu(messenger.page, 'Мой профиль')
    const field = messenger.page.locator('.profile-form .profile-name')
    await expect(messenger.page.locator('.profile-form .modal-ro b').first()).toHaveText('ada')
    // ui-behavior §1.1: the field sits over the incognito row,
    // placeholder = username, maxlength 64; №10 carries no displayName
    // for ada, so the pristine field is empty.
    await expect(field).toBeVisible()
    await expect(field).toHaveAttribute('placeholder', 'ada')
    await expect(field).toHaveAttribute('maxlength', '64')
    await expect(messenger.page.locator('.tgl-row')).toBeVisible()
    await field.fill('Мария')
    await expect(messenger.page).toHaveScreenshot('008a-profile-name-field.png', SHOT)
  })

  visualTest('profile «Имя» whitespace-only validation error', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openAlexChat(messenger)
    await chooseMainMenu(messenger.page, 'Мой профиль')
    const field = messenger.page.locator('.profile-form .profile-name')
    await expect(field).toBeVisible()
    // The client mirror of 400 invalid_display_name (T021): a
    // non-empty whitespace-only value is rejected WITHOUT a request —
    // the fixture API has no №40-style PUT mock here, so the exact
    // error text also proves nothing hit the network (a request would
    // have answered 404 and rendered a different message).
    await field.fill('   ')
    await messenger.page.locator('.profile-form button[type="submit"]').click()
    await expect(messenger.page.locator('.profile-form .modal-err')).toHaveText(
      'Укажите имя без пробелов в начале и конце',
    )
    await expect(messenger.page).toHaveScreenshot('008a-profile-name-error.png', SHOT)
  })

  visualTest(
    'contact rename form «Имя контакта» prefilled with the chain',
    async ({ messenger }) => {
      onlyProject(DESKTOP)
      await openAlexChat(messenger)
      await chooseMainMenu(messenger.page, 'Контакты')
      await expect(messenger.page.locator('.ctc-row')).toHaveCount(10)
      await messenger.page
        .locator('.ctc-row', { hasText: 'Alex Carter' })
        .locator('.c-menu')
        .click()
      await messenger.page.locator('.ctx-menu.show .ctx-item').first().click()
      // The shell keeps one backdrop; the form switches inside it with
      // the «Редактировать контакт» title (T022/MessengerPage).
      await expect(messenger.page.locator('.modal-back.show')).toBeVisible()
      await expect(
        messenger.page.getByRole('dialog', { name: 'Редактировать контакт' }),
      ).toBeVisible()
      const field = messenger.page.locator('.rename-form .rename-input')
      await expect(field).toBeVisible()
      await expect(field).toHaveValue('Alex Carter')
      await expect(field).toHaveAttribute('maxlength', '64')
      await expect(messenger.page.locator('.rename-form .modal-ro b').first()).toHaveText(
        'Alex Carter',
      )
      await expect(messenger.page).toHaveScreenshot('008a-rename-form.png', SHOT)
    },
  )

  visualTest('rename form whitespace-only validation error', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openAlexChat(messenger)
    await chooseMainMenu(messenger.page, 'Контакты')
    await expect(messenger.page.locator('.ctc-row')).toHaveCount(10)
    await messenger.page.locator('.ctc-row', { hasText: 'Alex Carter' }).locator('.c-menu').click()
    await messenger.page.locator('.ctx-menu.show .ctx-item').first().click()
    const field = messenger.page.locator('.rename-form .rename-input')
    await expect(field).toHaveValue('Alex Carter')
    // The client mirror of 400 invalid_alias (T022): whitespace-only is
    // rejected WITHOUT a request — the exact prototype string
    // «Укажите имя контакта» (chats.html:1648).
    await field.fill(' ')
    await messenger.page.locator('.rename-form button[type="submit"]').click()
    await expect(messenger.page.locator('.rename-form .modal-err')).toHaveText(
      'Укажите имя контакта',
    )
    await expect(messenger.page).toHaveScreenshot('008a-rename-error.png', SHOT)
  })

  visualTest(
    '№40 success — row resort, toast and the aliased chat header',
    async ({ messenger }) => {
      onlyProject(DESKTOP)
      await openAlexChat(messenger)
      installAliasBackend(messenger.page)
      await chooseMainMenu(messenger.page, 'Контакты')
      await expect(messenger.page.locator('.ctc-row')).toHaveCount(10)
      await messenger.page
        .locator('.ctc-row', { hasText: 'Alex Carter' })
        .locator('.c-menu')
        .click()
      await messenger.page.locator('.ctx-menu.show .ctx-item').first().click()
      const field = messenger.page.locator('.rename-form .rename-input')
      await expect(field).toHaveValue('Alex Carter')
      await field.fill('Маша')
      await messenger.page.locator('.rename-form button[type="submit"]').click()

      // The modal list converges from the №40 answer: the row carries
      // «Маша» and — the `ru` chain sort of FR-005 — rides to the TOP of
      // the book (Cyrillic sorts before the Latin names); the toast
      // quotes the chain (ui-behavior §5).
      await expect(messenger.page.locator('.ctc-row .c-name').first()).toHaveText('Маша')
      await expect(messenger.page.locator('.toast.show')).toHaveText('Контакт переименован — Маша')
      await expect(messenger.page).toHaveScreenshot('008a-rename-success-toast.png', SHOT)

      // The owner's surfaces converge by refetch (ui-behavior §1.2): the
      // №12 answer carries peerAlias, the sidebar row and the OPEN
      // dialog header flip to «Маша» — initials from the chain («М»),
      // the avatar colour stays derived from the username (FR-004).
      await messenger.page.keyboard.press('Escape')
      await expect(messenger.page.locator('.modal-back.show')).toHaveCount(0)
      await expect(messenger.page.locator('.chat-head .chat-name')).toHaveText('Маша')
      await expect(messenger.page.locator('.chat-head .head-av .av-in span')).toHaveText('М')
      await expect(messenger.page.locator('.chat-item', { hasText: 'Маша' })).toHaveCount(1)
      // The toast auto-hides on its 3000 ms timer — wait it out so no
      // residue enters the header capture.
      await expect(messenger.page.locator('.toast.show')).toHaveCount(0)
      await expect(messenger.page.locator('.chat-head')).toHaveScreenshot(
        '008a-direct-chat-head-alias.png',
        SHOT,
      )
    },
  )
})
