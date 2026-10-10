/**
 * T025 — 008a US1 visual regression (SC-004 in the US1 scope, 2% budget
 * of the shared config): the two 008a form surfaces — the «Имя» field of
 * «Мой профиль» (T021, ui-behavior §1.1) and the «Имя контакта» rename
 * form behind the contact kebab «Редактировать» (T022, ui-behavior §1.2)
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
 *  * the contact kebab menu with «Редактировать» FIRST (ui-behavior
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
 *    state, pinned as `008a-direct-chat-head-alias`;
 *  * the US2 typing row (T040, ui-behavior §2.2/§5): a live №18
 *    `typing.started` frame for the open dialog's peer drives the
 *    feed tail — the `.typing-row.msg.them` bubble with three
 *    `lampBlink`-cancelled `.tlamp` lamps and the italic
 *    «Alex Carter печатает…» label, the typist's avatar carrying the
 *    US1 initials/colour split — delivered by a parked-then-one-shot
 *    SSE interception whose post-delivery aborts keep the T038
 *    reconnect-reset from eating the row mid-capture.
 *  * the US3 lastSeen statuses (T050, ui-behavior §3): a №36 override
 *    disclosing `lastSeenAt` drives BOTH US3 surfaces — the direct
 *    header `.status-row` («Был в сети — 19 сентября, 18:55» over the
 *    `.lamp.off` mark) and the «Контакты» `.c-prev` previews (the
 *    disclosed James row, the neutral «Был в сети — давно» of the
 *    no-field offline, «В сети» online) — over the frozen page clock
 *    of the us4 outbox precedent, so `lastSeenFormat` (T046) takes
 *    its «D месяца, HH:MM» branch deterministically on every run
 *    date, including a run on the demo day itself.
 *  * the US4 bell (T060, ui-behavior §4.1/§5): the №42 click path —
 *    the muted toast «Звуковые оповещения отключены — Alex Carter»,
 *    the `.off` bell (opacity .45) in the header region and the
 *    unmute toast — and the №18 `chat.sound.updated` e2e frame of the
 *    caller's OWN channel (another device, SC-006): the parked-then-
 *    one-shot SSE delivery of the US2 recipe flips the bell to «выкл»
 *    WITHOUT №42 or a toast, the whole page quiescent around it.
 *
 * Determinism mirrors T026/T039: `animations: 'disabled'` cancels the
 * infinite lamps and fast-forwards the finite pop/transitions,
 * `caret: 'hide'` removes the text caret; the fixture wall-clock is the
 * fixed +03:00 demo day under the ru-RU / Europe/Moscow locale of the
 * shared config. The toast auto-hides on its 3000 ms JS timer
 * (ui/Toast) — the header capture waits it out, so no timing residue
 * enters the shot.
 *
 * T050 appends the US3 scenarios — the «Был в сети — …» statuses of
 * the direct header and the «Контакты» previews; T060 appends the US4
 * bell scenarios to the same file (see the coverage list above).
 */
import { expect } from '@playwright/test'
import type { Locator, Page, Route } from '@playwright/test'
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

/**
 * The stateful №42 backend of the US4 bell scenario (the
 * installAliasBackend recipe — registered AFTER the harness dataset,
 * the later route wins): PUT /chats/{chatId}/sound is answered with
 * the honest echo of the requested value (`ChatSoundResponse
 * {chatId, soundEnabled}`), and every call is recorded so the spec
 * can assert the page really sent №42 with the TARGET value of the
 * button (T057: «вкл» → {enabled:false}, «выкл» → {enabled:true}).
 * No №12/№13 override follows the PUT: the page converges from the
 * №42 answer alone (the echo semantics of MessengerPage — no
 * refetch), so the pixels stay fixture-stable.
 */
function installSoundBackend(page: Page): { sent: boolean[] } {
  const sent: boolean[] = []
  void page.route(/\/api\/v1\/chats\/[^/]+\/sound$/, async (route: Route) => {
    const body = (await route.request().postDataJSON()) as { enabled?: boolean }
    sent.push(body.enabled === true)
    const chatId = decodeURIComponent(
      new URL(route.request().url()).pathname.split('/').at(-2) ?? '',
    )
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ chatId, soundEnabled: body.enabled === true }),
    })
  })
  return { sent }
}

/** The №18 opening frames every connect must answer (realtime-events.md §1). */
function sseOpening(): string {
  return 'retry: 3000\n\nevent: connected\ndata: {"connectionId":"aethergram-typing-fixture"}\n\n'
}

/** A №18 typing frame (realtime-events.md §1.1/§1.2 — the shared `{chatId, userId}` payload). */
function typingFrame(started: boolean, chatId: string, userId: string): string {
  const event = started ? 'typing.started' : 'typing.stopped'
  return `event: ${event}\ndata: ${JSON.stringify({ chatId, userId })}\n\n`
}

/** A №18 chat.sound.updated frame (realtime-events.md §1.3 — the `{chatId, soundEnabled}` payload). */
function soundFrame(chatId: string, soundEnabled: boolean): string {
  return `event: chat.sound.updated\ndata: ${JSON.stringify({ chatId, soundEnabled })}\n\n`
}

/**
 * The №18 parked-then-one-shot backend of the US2/US4 scenarios (the
 * presence-e2e recipe; T040 typing frames, T060 chat.sound.updated):
 * the stream route PARKS the connect it takes over until the spec
 * publishes a frame (drain poll — no abort, so the client's backoff
 * stays cold and the delivery lands milliseconds after `publish()`
 * instead of on a jittery backoff boundary), answers it ONCE with the
 * opening frames + everything queued, then aborts every later attempt.
 *
 * The one-shot discipline is the point: a route-fulfilled SSE body
 * always ENDS, so the client would reconnect on its backoff within
 * ~1 s and that reconnect's `onOpen` would reset the page's typing
 * map (T038: the ephemeral state is never replayed — the row would
 * blink out mid-capture). Aborts never fire `onOpen`, so after the
 * delivery the row rides its full 10 s observer window
 * (TYPING_SAFETY_TIMEOUT_MS) — a stable capture target. The US4 bell
 * has no such window (the №18 frame state is durable in the page), so
 * the same one-shot merely keeps the stream quiescent afterwards.
 */
function installTypingStream(page: Page): { publish(frame: string): void } {
  const queued: string[] = []
  let delivered = false
  void page.route('**/api/v1/users/me/events', async (route: Route) => {
    if (!delivered && queued.length === 0) {
      const deadline = Date.now() + 25_000
      while (queued.length === 0 && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 25))
      }
    }
    if (queued.length === 0) {
      await route.abort()
      return
    }
    delivered = true
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'text/event-stream' },
      body: sseOpening() + queued.splice(0).join(''),
    })
  })
  return {
    publish(frame: string): void {
      queued.push(frame)
    },
  }
}

/**
 * The frozen wall-clock of the US3 scenarios (the us4 outbox
 * precedent): a fixed instant a week AFTER the demo day, so
 * `lastSeenFormat` (T046) takes its «D месяца, HH:MM» branch for the
 * demo-day `lastSeenAt` instants on EVERY run date — including a run
 * on 19 сентября itself — and the rendered label stays byte-stable.
 */
const US3_FROZEN_MS = Date.parse('2026-09-26T12:00:00.000+03:00')

/**
 * The №36 backend of the US3 scenarios (the installAliasBackend
 * recipe — registered AFTER the harness dataset, the later route wins
 * every snapshot request): answers the demo truth PLUS the disclosed
 * `lastSeenAt` entries of the map, the honest backend filter of
 * research.md B2 modelled per-user (offline ∧ audience ∧ not
 * incognito ∧ key exists → the field; otherwise the field is simply
 * absent). Disclosed items carry rev 2 — the boot №36 of the harness
 * already stored the demo statuses at rev 1, and the
 * strictly-greater-rev merge of presenceStore (T047) must let the
 * disclosed offline LAND; the untouched users keep rev 1 answers —
 * idempotent no-ops against their stored entries.
 */
function installLastSeenSnapshot(
  page: Page,
  disclosures: ReadonlyMap<string, { status: 'online' | 'offline'; lastSeenAt: string }>,
): void {
  // The glob would miss the `?userIds=` query — №36 is matched by
  // regex; the settings/heartbeat paths under /presence/ stay with
  // the harness (the presence-e2e recipe).
  void page.route(/\/api\/v1\/users\/me\/presence(\?|$)/, async (route: Route) => {
    const url = new URL(route.request().url())
    const requested = (url.searchParams.get('userIds') ?? '').split(',').filter(Boolean)
    const items = requested.map((userId) => {
      const disclosed = disclosures.get(userId)
      return disclosed === undefined
        ? { userId, status: AETHERGRAM.presenceOf(userId), rev: 1 }
        : { userId, rev: 2, status: disclosed.status, lastSeenAt: disclosed.lastSeenAt }
    })
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ items }),
    })
  })
}

visualTest.describe('T025 — 008a US1: profile «Имя» field and the rename form', () => {
  visualTest('contact kebab menu carries «Редактировать» first', async ({ messenger }) => {
    onlyProject(DESKTOP)
    await openAlexChat(messenger)
    await chooseMainMenu(messenger.page, 'Контакты')
    await expect(messenger.page.locator('.ctc-row')).toHaveCount(10)
    await messenger.page.locator('.ctc-row .c-menu').first().click()
    const menu = messenger.page.locator('.ctx-menu.show')
    await expect(menu).toBeVisible()
    // ui-behavior §1.2: «Редактировать» is the FIRST item, before
    // «Заблокировать»/«Удалить чат»/«Удалить контакт».
    await expect(menu.locator('.ctx-item').first()).toHaveText('Редактировать')
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

visualTest.describe('T040 — 008a US2: the typing row', () => {
  visualTest(
    '№18 typing.started renders «{имя} печатает…» at the feed tail',
    async ({ messenger }) => {
      onlyProject(DESKTOP)
      await openAlexChat(messenger)

      // The typist is the open dialog's №11 peer (Alex Carter): the
      // typing.started fanout addresses every active participant but
      // the sender, so ada — the observer — is its audience.
      const alex = AETHERGRAM.userByUsername('Alex Carter')
      const dialog = AETHERGRAM.chatList.find((item) => item.peer?.id === alex?.id)
      if (alex === undefined || dialog === undefined) {
        throw new Error('social-signals fixture: the Alex Carter dialog is missing')
      }

      const stream = installTypingStream(messenger.page)
      stream.publish(typingFrame(true, dialog.chatId, alex.id))

      // §2.2/§5: the row is the feed's last `.msg.them` — three
      // aria-hidden lamps, the italic label in the peer's CHAIN name
      // (the demo set carries no alias/displayName → username), the
      // avatar split of T020 (initials from the name, colour from the
      // username — FR-004).
      const row = messenger.page.locator('.message-list .typing-row')
      await expect(row).toBeVisible()
      await expect(row.locator('.typing-txt')).toHaveText('Alex Carter печатает…')
      await expect(row.locator('.tlamp')).toHaveCount(3)
      await expect(row.locator('.av-in span')).toHaveText('AC')

      // The delivering connect's `onOpen` re-runs the №12/№36/sync
      // convergence refetches (all fixture-answered, pixel-neutral) —
      // wait out the SyncIndicator strip before the shot.
      await expect(messenger.page.locator('.sync-indicator')).toHaveCount(0)

      // The open seat parked the feed at its last row and the row
      // mounts BELOW it (no re-seat: the T079 autoscroll keys on
      // message ids), so the capture scrolls the row into the fold
      // first — the region baseline of the `us1-direct-chat-feed`
      // convention, lamps frozen by `animations: 'disabled'`.
      await row.scrollIntoViewIfNeeded()
      await expect(messenger.page.locator('.message-list.chat-scroll')).toHaveScreenshot(
        '008a-typing-row.png',
        SHOT,
      )
    },
  )
})

visualTest.describe('T050 — 008a US3: the «Был в сети — …» statuses', () => {
  visualTest(
    '№36-disclosed lastSeen renders «Был в сети — {дата, время}» in the direct header',
    async ({ messenger }) => {
      onlyProject(DESKTOP)
      const { page } = messenger
      // The frozen clock pins the `lastSeenFormat` date branch; the
      // №36 override turns the ONLINE demo peer into the honest
      // offline+disclosure answer the moment the open dialog
      // registers its presence surface (usePresenceEntry T047 → the
      // batched №36 heal of every displayed surface, T075).
      await page.clock.setFixedTime(US3_FROZEN_MS)
      const alex = AETHERGRAM.userByUsername('Alex Carter')
      if (alex === undefined) {
        throw new Error('social-signals fixture: Alex Carter is not in the demo book')
      }
      // 18:55 — five minutes after his last demo message (18:50): the
      // disclosed activity time stays consistent with the feed.
      installLastSeenSnapshot(
        page,
        new Map([[alex.id, { status: 'offline', lastSeenAt: '2026-09-19T18:55:00.000+03:00' }]]),
      )
      await openAlexChat(messenger)

      // §3/§5: the header row keeps the prototype lamp — now `.off` —
      // and the label quotes the disclosed time (ui-behavior §3.1:
      // not today → «19 сентября, 18:55»).
      await expect(page.locator('.chat-head .status-row .status-txt')).toHaveText(
        'Был в сети — 19 сентября, 18:55',
      )
      await expect(page.locator('.chat-head .status-row .lamp.off')).toBeVisible()
      await expect(page.locator('.chat-head')).toHaveScreenshot(
        '008a-direct-chat-head-lastseen.png',
        SHOT,
      )
    },
  )

  visualTest('«Контакты» previews carry the full lastSeen projection', async ({ messenger }) => {
    onlyProject(DESKTOP)
    const { page } = messenger
    await page.clock.setFixedTime(US3_FROZEN_MS)
    // James — an offline catalogue contact — gets the disclosure
    // (14:06, his own last demo message); Thomas stays a plain
    // offline (no field → the neutral «давно» of SC-002); Maria is
    // online — the three §3 label variants share one frame.
    const james = AETHERGRAM.userByUsername('James Whitmore')
    if (james === undefined) {
      throw new Error('social-signals fixture: James Whitmore is not in the demo book')
    }
    installLastSeenSnapshot(
      page,
      new Map([[james.id, { status: 'offline', lastSeenAt: '2026-09-19T14:06:00.000+03:00' }]]),
    )
    await openAlexChat(messenger)
    await chooseMainMenu(page, 'Контакты')
    await expect(page.locator('.ctc-row')).toHaveCount(10)

    const previewOf = (name: string): Locator =>
      page.locator('.ctc-row', { hasText: name }).locator('.c-prev')
    await expect(previewOf('James Whitmore')).toHaveText('Был в сети — 19 сентября, 14:06')
    await expect(previewOf('Thomas Reed')).toHaveText('Был в сети — давно')
    await expect(previewOf('Maria Lopez')).toHaveText('В сети')
    await expect(page).toHaveScreenshot('008a-contacts-lastseen.png', SHOT)
  })
})

visualTest.describe('T060 — 008a US4: the bell on/off', () => {
  /** Bell-колокол заголовка по title прототипа #btnBell (ui-behavior §4.1). */
  function bellOf(page: Page): Locator {
    return page.getByRole('button', { name: 'Звуковые оповещения' })
  }

  visualTest(
    '№42 toggle — the muted toast and the `.off` bell, then back on',
    async ({ messenger }) => {
      onlyProject(DESKTOP)
      const { page } = messenger
      const sound = installSoundBackend(page)
      await openAlexChat(messenger)

      // §4.1: the pristine №12 answer carries no soundEnabled — the
      // contract default renders the bell «вкл»: full opacity, pressed
      // (aria-pressed — the first use in the project).
      const bell = bellOf(page)
      await expect(bell).toBeVisible()
      await expect(bell).toHaveAttribute('aria-pressed', 'true')
      await expect(bell).toHaveClass('ch-btn')

      // Mute: №42 carries the TARGET value of the click (false); the
      // echo answer converges the button (.off + aria-pressed) and the
      // toast quotes the CHAIN name of the direct dialog (ui-behavior
      // §5 — the demo set carries no alias/displayName → username).
      await bell.click()
      await expect(page.locator('.toast.show')).toHaveText(
        'Звуковые оповещения отключены — Alex Carter',
      )
      // The toast already proves the №42 round-trip completed — the
      // recorded target value is stable to read synchronously.
      expect(sound.sent).toEqual([false])
      await expect(bell).toHaveClass('ch-btn off')
      await expect(bell).toHaveAttribute('aria-pressed', 'false')
      await expect(page).toHaveScreenshot('008a-bell-off-toast.png', SHOT)

      // The toast auto-hides on its 3000 ms timer — wait it out so no
      // residue enters the header capture (the alias-scenario recipe).
      await expect(page.locator('.toast.show')).toHaveCount(0)
      await expect(page.locator('.chat-head')).toHaveScreenshot('008a-bell-off-head.png', SHOT)

      // Unmute: the target flips to true — the bell returns to the
      // full view and the toast quotes the chain again (§4.2's
      // «динь-динь»/«щелчок» response sounds are WebAudio — no pixels).
      await bell.click()
      await expect(page.locator('.toast.show')).toHaveText(
        'Звуковые оповещения включены — Alex Carter',
      )
      expect(sound.sent).toEqual([false, true])
      await expect(bell).toHaveClass('ch-btn')
      await expect(bell).toHaveAttribute('aria-pressed', 'true')
      await expect(page).toHaveScreenshot('008a-bell-on-toast.png', SHOT)
    },
  )

  visualTest(
    '№18 chat.sound.updated of another device flips the bell without №42 or a toast (SC-006)',
    async ({ messenger }) => {
      onlyProject(DESKTOP)
      const { page } = messenger
      const alex = AETHERGRAM.userByUsername('Alex Carter')
      const dialog = AETHERGRAM.chatList.find((item) => item.peer?.id === alex?.id)
      if (alex === undefined || dialog === undefined) {
        throw new Error('social-signals fixture: the Alex Carter dialog is missing')
      }

      // The parked №18 route of the US2 recipe, now carrying the US4
      // frame: the own-channel chat.sound.updated is the ONLY muting
      // source here — no №42 backend is installed (a PUT would 404 and
      // toast an error), so the silent flip below also proves the
      // frame path drove the state, not a request.
      const stream = installTypingStream(page)
      await openAlexChat(messenger)
      const bell = bellOf(page)
      await expect(bell).toHaveAttribute('aria-pressed', 'true')

      stream.publish(soundFrame(dialog.chatId, false))

      // §4.1/SC-006: the multidevice frame converges the bell ≤ 2 s —
      // no toast (the switch happened on ANOTHER device), no №42. The
      // delivering connect's `onOpen` re-runs the №12/№36/sync
      // convergence refetches (all fixture-answered, pixel-neutral) —
      // wait out the SyncIndicator strip before the shot.
      await expect(bell).toHaveClass('ch-btn off')
      await expect(bell).toHaveAttribute('aria-pressed', 'false')
      await expect(page.locator('.sync-indicator')).toHaveCount(0)
      await expect(page.locator('.toast.show')).toHaveCount(0)
      await expect(page).toHaveScreenshot('008a-bell-frame-off.png', SHOT)
    },
  )
})
