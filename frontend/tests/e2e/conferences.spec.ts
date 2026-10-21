import {
  expect,
  request,
  test,
  type APIRequestContext,
  type BrowserContext,
  type Page,
} from '@playwright/test'

/**
 * 009, US1, T029 (+ US2, T034) — e2e звонка двух вкладок против
 * локального LiveKit (quickstart §3.1): fake media
 * (`--use-fake-device-for-media-stream`), SC-001 — каноничная форма
 * «от "Войти" на pre-join» до медиа ≤ 5 c (T034 встроил единый pre-join
 * для всех точек входа; обе вкладки открывают /conference/{id} без
 * ?mode= — дефолты по №45 mode=video: камера+микрофон); устойчивость к
 * деградации сети (FR-004): CDP network throttling на вкладке —
 * подключение сохраняется без обрыва, после снятия троттлинга звонок
 * продолжается.
 *
 * 009, US1, T075 — e2e каркаса конференц-страницы: на широком окне
 * конференция занимает всё окно браузера (computed max-width:none у
 * #root + секция ≈ innerWidth) в фазах pre-join и joined.
 *
 * 009, US3, T043 — e2e модерации (quickstart §3.3): мьют микрофона
 * модератором (№49) блокирует тумблер ЦЕЛИ с пояснением и виден всем
 * ≤ 2 c (SC-005); снятие ограничения разблокирует тумбель обратно
 * (SET-семантика №49, live-обновление permission-событием + refetch №46).
 *
 * 009, US4, T052 — e2e гостевого доступа (quickstart §3.4): приватное
 * окно БЕЗ аккаунта по инвайт-ссылке №52 (SC-003 — ≤ 3 действий от
 * открытия ссылки до конференции: имя → «Продолжить» → «Войти»; pre-join
 * «только микрофон», Q1=C), пометка «гость» и имя видны модератору
 * (T051), чат-поверхность гостю недоступна (FR-010: `/` → /login),
 * подделанный nonce — единообразный отказ без раскрытия причин (edge).
 *
 * 009, US5, T061 — e2e индикатора «звонок идёт» (quickstart §3.5,
 * T059): carol — член группового чата, НЕ входя в звонок, видит бейдж
 * по №18 conference.started (фактический вход первого участника —
 * US5-1) и снятие по завершении №48 (вебхук room_finished → finalize →
 * №18 conference.ended); участник звонка отключается терминально
 * (DeleteRoom → «Вы отключены»). Группа — чтобы у звонка был зритель
 * вне конференции (в личном чате оба члена — участники звонка).
 *
 * Стенд (quickstart §2): docker compose postgres redis mailpit livekit,
 * backend :8080 (LIVEKIT_URL/KEY/SECRET), SPA :5173 (webServer конфига).
 * Аккаунты готовятся через REST (register → письмо Mailpit → confirm →
 * password → login): e2e не зависит от состояния БД, имена уникальны за
 * прогон. Сессия страницы — localStorage `webchat.auth.refreshToken`
 * (свежий login на каждый тест: refresh-токен одноразовый при ротации).
 */

const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080'
const MAILPIT_URL = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025'
const RUN_ID = process.env.E2E_RUN_ID ?? Date.now().toString(36)
const PASSWORD = `E2e-conference-${RUN_ID}`
/** SC-001: от «Войти» на pre-join до медиа (каноничная форма — после T034). */
const SC001_MS = 5_000
/** SC-005: модерационное действие применяется и становится видно ≤ 2 c. */
const SC005_MS = 2_000

interface TestAccount {
  readonly username: string
  readonly email: string
  readonly userId: string
  /** Bearer для REST-вызовов настройки (короткоживущий access JWT). */
  readonly accessToken: string
  /** localStorage-сессия вкладки (`webchat.auth.refreshToken`). */
  readonly refreshToken: string
}

interface ConferenceView {
  readonly id: string
  readonly status: string
}

/** Обёртка REST-вызова с Bearer; бросает тело Problem при не-2xx. */
async function apiCall<T>(
  api: APIRequestContext,
  method: 'get' | 'post',
  path: string,
  options: { readonly data?: unknown; readonly token?: string } = {},
): Promise<T> {
  const response = await api.fetch(path, {
    method,
    headers: {
      ...(options.data !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token !== undefined ? { Authorization: `Bearer ${options.token}` } : {}),
    },
    data: options.data !== undefined ? JSON.stringify(options.data) : undefined,
  })
  if (!response.ok()) {
    throw new Error(
      `${method.toUpperCase()} ${path} → ${response.status()}: ${await response.text()}`,
    )
  }
  if (response.status() === 204) {
    return undefined as T
  }
  // 202/204 с пустым телом (register, password) не парсим как JSON
  const raw = await response.text()
  return raw === '' ? (undefined as T) : (JSON.parse(raw) as T)
}

/** Одноразовый токен из письма Mailpit (ссылка …?token=…, 43 символа base64url). */
async function tokenFromMailpit(api: APIRequestContext, email: string): Promise<string> {
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline) {
    const search = await api.get(`${MAILPIT_URL}/api/v1/search`, {
      params: { query: `to:${email}` },
    })
    if (search.ok()) {
      const { messages } = (await search.json()) as {
        messages: ReadonlyArray<{ ID: string }>
      }
      const latest = messages[0]
      if (latest !== undefined) {
        // NB: детали письма — /message/{ID} (единственное число), не /messages
        const details = await api.get(`${MAILPIT_URL}/api/v1/message/${latest.ID}`)
        const { Text, HTML } = (await details.json()) as { Text: string; HTML: string }
        const token = /token=([A-Za-z0-9_-]{43})/.exec(`${Text}\n${HTML}`)
        if (token !== null) {
          return token[1] as string
        }
      }
    }
    await new Promise((resolve) => {
      setTimeout(resolve, 500)
    })
  }
  throw new Error(`письмо для ${email} не пришло в Mailpit за 15 c`)
}

/** Полный цикл 002: register → подтверждение email → пароль → активный аккаунт. */
async function ensureAccount(api: APIRequestContext, username: string): Promise<TestAccount> {
  const email = `${username}@e2e.local`
  await apiCall(api, 'post', '/api/v1/auth/register', { data: { username, email } })
  const emailToken = await tokenFromMailpit(api, email)
  const confirm = await apiCall<{ setupToken: string }>(
    api,
    'post',
    '/api/v1/auth/register/confirm',
    {
      data: { token: emailToken },
    },
  )
  await apiCall(api, 'post', '/api/v1/auth/register/password', {
    data: { setupToken: confirm.setupToken, password: PASSWORD, confirmPassword: PASSWORD },
  })
  return login(api, username)
}

/** Свежая пара токенов (refresh одноразовый — логинимся на каждый тест). */
async function login(api: APIRequestContext, username: string): Promise<TestAccount> {
  const answer = await apiCall<{
    accessToken: string
    refreshToken: string
    user: { id: string }
  }>(api, 'post', '/api/v1/auth/login', {
    data: { identifier: username, password: PASSWORD },
  })
  return {
    username,
    email: `${username}@e2e.local`,
    userId: answer.user.id,
    accessToken: answer.accessToken,
    refreshToken: answer.refreshToken,
  }
}

/** №11: личный диалог пары (идемпотентный ensure). */
async function ensureDirectChat(
  api: APIRequestContext,
  owner: TestAccount,
  peer: TestAccount,
): Promise<string> {
  const chat = await apiCall<{ chatId: string }>(api, 'post', '/api/v1/chats/ensure', {
    data: { peerUserId: peer.userId },
    token: owner.accessToken,
  })
  return chat.chatId
}

/** №44: старт конференции из чата (201 новой; 200 активной — без различения). */
async function createConference(
  api: APIRequestContext,
  owner: TestAccount,
  chatId: string,
): Promise<ConferenceView> {
  return apiCall<ConferenceView>(api, 'post', `/api/v1/chats/${chatId}/conferences`, {
    data: { mode: 'video' },
    token: owner.accessToken,
  })
}

function tilesSection(page: Page) {
  return page.getByRole('region', { name: 'Участники конференции' })
}

/**
 * T075: конференц-поверхность занимает окно — раскрытая корневая
 * секция (маркер data-conference-id) почти на всю ширину окна
 * (body-паттерн машины оставляет 16px полей с боков, допуск 64px);
 * до правки секция жила в служебной колонке auth-страниц (480px).
 */
async function expectConferenceFillsWindow(page: Page): Promise<void> {
  const box = await page.getByRole('region', { name: 'Конференция' }).boundingBox()
  expect(box).not.toBeNull()
  const innerWidth = page.viewportSize()?.width ?? 0
  expect(
    box?.width,
    `ширина секции ${box?.width}px ≈ окну ${innerWidth}px (колонка 480px снята)`,
  ).toBeGreaterThanOrEqual(innerWidth - 64)
}

/** Вкладка участника: localStorage-сессия + экран /conference/:id
 *  (T075: опциональный широкий viewport — геометрия растянутой страницы). */
async function openConferencePage(
  browser: import('@playwright/test').Browser,
  account: TestAccount,
  conferenceId: string,
  viewport?: { width: number; height: number },
): Promise<Page> {
  const context = await browser.newContext(viewport === undefined ? undefined : { viewport })
  await context.addInitScript((refreshToken) => {
    window.localStorage.setItem('webchat.auth.refreshToken', refreshToken)
  }, account.refreshToken)
  const page = await context.newPage()
  await page.goto(`/conference/${conferenceId}`)
  return page
}

/** Клик «Войти» на pre-join (T034) и ожидание полного медиа: свой статус + удалённый тайл. */
async function connectAndAwaitMedia(
  page: Page,
  ownStatus: string,
  remoteUsername: string,
): Promise<number> {
  const startedAt = Date.now()
  await page.getByRole('button', { name: 'Войти' }).click()
  await expect(page.getByText(ownStatus)).toBeVisible({ timeout: 15_000 })
  // медиа обеих сторон: тайл собеседника (ParticipantName; имя может
  // встретиться в нескольких тайлах метаданных — достаточно первого) +
  // прикреплённый video
  await expect(tilesSection(page).getByText(remoteUsername, { exact: true }).first()).toBeVisible({
    timeout: SC001_MS,
  })
  await expect(tilesSection(page).locator('video').first()).toBeAttached({ timeout: SC001_MS })
  return Date.now() - startedAt
}

test.describe.serial('конференции US1 e2e (009 T029)', () => {
  let api: APIRequestContext
  let alice: TestAccount
  let bob: TestAccount
  let chatId: string
  /** Страницы теста — закрываются в afterEach (контексты LiveKit-вкладок). */
  let openedPages: Page[]

  test.beforeAll(async () => {
    api = await request.newContext({ baseURL: API_URL })
    alice = await ensureAccount(api, `alice${RUN_ID}`)
    bob = await ensureAccount(api, `bob${RUN_ID}`)
    chatId = await ensureDirectChat(api, alice, bob)
  })

  // api-контекст suite-scope уничтожается самим Playwright: ручной dispose()
  // в afterAll конфликтует с авто-диспозом и ломает следующий suite (T064)

  test.beforeEach(() => {
    openedPages = []
  })

  test.afterEach(async () => {
    for (const page of openedPages) {
      await page.context().close()
    }
  })

  test('SC-001: звонок двух вкладок — обе стороны видят медиа ≤ 5 c от «Войти» на pre-join', async ({
    browser,
  }) => {
    // свежие refresh-токены: ротация одноразовая, контексты стартуют с действующим
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    const conference = await createConference(api, aliceNow, chatId)
    expect(conference.status).toMatch(/created|active/)

    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id),
      openConferencePage(browser, bobNow, conference.id),
    ])
    openedPages.push(alicePage, bobPage)

    // защищённый маршрут грузит экран и пакет №45+№46 (ленивый чанк
    // conferences); pre-join (T034) — до «Войти» слот не занимается
    // (FR-007), дефолты тумблеров — по №45 mode=video
    await expect(alicePage.getByRole('region', { name: 'Конференция' })).toBeVisible()
    await expect(bobPage.getByRole('region', { name: 'Конференция' })).toBeVisible()
    await expect(alicePage.getByRole('button', { name: 'Войти' })).toBeVisible()
    await expect(bobPage.getByRole('button', { name: 'Войти' })).toBeVisible()

    // обе вкладки подключаются параллельно: каждая — от своего клика
    // «Войти» на pre-join до медиа (свой статус + тайл собеседника) ≤ 5 c
    const [aliceElapsed, bobElapsed] = await Promise.all([
      connectAndAwaitMedia(alicePage, 'Вы в конференции', bobNow.username),
      connectAndAwaitMedia(bobPage, 'Вы в конференции', aliceNow.username),
    ])

    expect(
      aliceElapsed,
      `SC-001: alice от «Войти» до медиа ${aliceElapsed} мс`,
    ).toBeLessThanOrEqual(SC001_MS)
    expect(bobElapsed, `SC-001: bob от «Войти» до медиа ${bobElapsed} мс`).toBeLessThanOrEqual(
      SC001_MS,
    )

    // корректный выход (Q10=A): «Покинуть» возвращает на pre-join без следов
    await bobPage.getByRole('button', { name: 'Покинуть' }).click()
    await expect(bobPage.getByRole('button', { name: 'Войти' })).toBeVisible()
    await expect(bobPage.getByText('Вы в конференции')).toHaveCount(0)
  })

  test('T075: на широком окне конференция занимает всё окно браузера (pre-join и звонок)', async ({
    browser,
  }) => {
    const aliceNow = await login(api, alice.username)
    const conference = await createConference(api, aliceNow, chatId)

    // широкое окно (дефолт конфига 1280×720 — узкая подача): явный
    // 1600×900 — растянутая геометрия видна без прокрутки
    const page = await openConferencePage(browser, aliceNow, conference.id, {
      width: 1600,
      height: 900,
    })
    openedPages.push(page)

    // pre-join — та же геометрия: служебная колонка #root снята,
    // секция ≈ окну (правило body:has([data-conference-id], …) #root)
    await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible()
    const rootMaxWidth = await page.evaluate(() => {
      const root = document.getElementById('root')
      return root === null ? null : getComputedStyle(root).maxWidth
    })
    expect(rootMaxWidth).toBe('none')
    await expectConferenceFillsWindow(page)

    // joined — медиа-зона в той же растянутой геометрии
    await page.getByRole('button', { name: 'Войти' }).click()
    await expect(page.getByText('Вы в конференции')).toBeVisible({ timeout: 15_000 })
    await expectConferenceFillsWindow(page)
  })

  test('FR-004: деградация сети одной вкладки (CDP throttling) не рвёт звонок; после снятия — продолжается', async ({
    browser,
  }) => {
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    const conference = await createConference(api, aliceNow, chatId)

    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id),
      openConferencePage(browser, bobNow, conference.id),
    ])
    openedPages.push(alicePage, bobPage)
    await Promise.all([
      connectAndAwaitMedia(alicePage, 'Вы в конференции', bobNow.username),
      connectAndAwaitMedia(bobPage, 'Вы в конференции', aliceNow.username),
    ])

    // деградация сети на вкладке bob: CDP network throttling (quickstart §3.1
    // шаг 4 — DevTools throttle; e2e — программно через CDP-сессию)
    const cdp = await bobPage.context().newCDPSession(bobPage)
    await cdp.send('Network.enable')
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 400,
      downloadThroughput: (60 * 1024) / 8, // ~60 kbps
      uploadThroughput: (30 * 1024) / 8,
    })

    await bobPage.waitForTimeout(4_000)

    // подключение сохраняется без обрыва: статус подключения и тайлы живы,
    // терминальных/аварийных строк нет
    await expect(bobPage.getByText('Вы в конференции')).toBeVisible()
    await expect(
      tilesSection(bobPage).getByText(aliceNow.username, { exact: true }).first(),
    ).toBeVisible()
    for (const failure of [
      'Вы отключены от конференции',
      'Соединение потеряно — переподключение',
      'Не удалось подключиться',
    ]) {
      await expect(bobPage.getByText(failure)).toHaveCount(0)
    }

    // снятие троттлинга — звонок продолжается
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 0,
      downloadThroughput: -1,
      uploadThroughput: -1,
    })
    await bobPage.waitForTimeout(2_000)

    await expect(bobPage.getByText('Вы в конференции')).toBeVisible()
    await expect(
      tilesSection(bobPage).getByText(aliceNow.username, { exact: true }).first(),
    ).toBeVisible()
    await expect(alicePage.getByText('Вы в конференции')).toBeVisible()
    await expect(
      tilesSection(alicePage).getByText(bobNow.username, { exact: true }).first(),
    ).toBeVisible()
    await cdp.detach()
  })
})

test.describe.serial('модерация US3 e2e (009 T043)', () => {
  let api: APIRequestContext
  let alice: TestAccount
  let bob: TestAccount
  let chatId: string
  /** Страницы теста — закрываются в afterEach (контексты LiveKit-вкладок). */
  let openedPages: Page[]

  test.beforeAll(async () => {
    api = await request.newContext({ baseURL: API_URL })
    alice = await ensureAccount(api, `mod${RUN_ID}a`)
    bob = await ensureAccount(api, `mod${RUN_ID}b`)
    chatId = await ensureDirectChat(api, alice, bob)
  })

  // api-контекст suite-scope уничтожается самим Playwright: ручной dispose()
  // в afterAll конфликтует с авто-диспозом и ломает следующий suite (T064)

  test.beforeEach(() => {
    openedPages = []
  })

  test.afterEach(async () => {
    for (const page of openedPages) {
      await page.context().close()
    }
  })

  test('SC-005: мьют микрофона модератором блокирует тумблер цели с пояснением ≤ 2 c и виден всем; снятие разблокирует', async ({
    browser,
  }) => {
    // alice — создатель = модератор (T017); обе стороны в звонке,
    // микрофон bob опубликован (дефолты mode=video)
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    const conference = await createConference(api, aliceNow, chatId)
    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id),
      openConferencePage(browser, bobNow, conference.id),
    ])
    openedPages.push(alicePage, bobPage)
    await Promise.all([
      connectAndAwaitMedia(alicePage, 'Вы в конференции', bobNow.username),
      connectAndAwaitMedia(bobPage, 'Вы в конференции', aliceNow.username),
    ])

    // №49: alice глушит bob'у микрофон (строка панели модерации цели)
    const startedAt = Date.now()
    await alicePage
      .getByRole('group', { name: bobNow.username })
      .getByRole('button', { name: 'Отключить микрофон' })
      .click()

    // у bob тумблер заблокирован с пояснением (T043) ≤ 2 c от клика
    await expect(bobPage.getByText('Микрофон отключён модератором')).toBeVisible({
      timeout: SC005_MS,
    })
    expect(
      Date.now() - startedAt,
      `SC-005: применение мьюта ${Date.now() - startedAt} мс`,
    ).toBeLessThanOrEqual(SC005_MS)
    await expect(bobPage.getByRole('button', { name: 'Микрофон' })).toBeDisabled()
    // состояние видно всем: строка bob в панели участников alice
    await expect(alicePage.getByText('микрофон отключён', { exact: true }).first()).toBeVisible({
      timeout: SC005_MS,
    })

    // снятие ограничения (SET false, Q22=A): блокировка у цели снята ≤ 2 c
    await alicePage
      .getByRole('group', { name: bobNow.username })
      .getByRole('button', { name: 'Включить микрофон' })
      .click()
    await expect(bobPage.getByText('Микрофон отключён модератором')).toHaveCount(0, {
      timeout: SC005_MS,
    })
    await expect(bobPage.getByRole('button', { name: 'Микрофон' })).toBeEnabled({
      timeout: SC005_MS,
    })
  })
})

test.describe.serial('гостевой доступ US4 e2e (009 T052)', () => {
  let api: APIRequestContext
  let alice: TestAccount
  let bob: TestAccount
  let chatId: string
  /** Контексты теста (модератор + приватные гостевые) — закрываются в afterEach. */
  let openedContexts: BrowserContext[]

  /**
   * Гость входит БЕЗ аккаунта — собственная пара аккаунтов сценарию не
   * нужна: в полном прогоне файла переиспользуем alice/bob сьюта US1
   * (per-IP бакет регистраций auth 5/1ч из 002 не оставляет места
   * третьей паре за прогон); standalone-запуск (--grep гостевой)
   * регистрирует свою пару — login падает 401 до register.
   */
  async function ensureLogin(username: string): Promise<TestAccount> {
    try {
      return await login(api, username)
    } catch {
      return await ensureAccount(api, username)
    }
  }

  test.beforeAll(async () => {
    // Синтетический клиентский IP (TEST-NET-2) в X-Forwarded-For:
    // ClientIpResolver 002 бакетит per-IP — суиты файла получают
    // изолированные register/login-бакеты (полный прогон файла иначе
    // упирается в auth 5/1ч регистраций: US1+US3 уже занимают 4)
    api = await request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { 'X-Forwarded-For': '198.51.100.52' },
    })
    alice = await ensureLogin(`alice${RUN_ID}`)
    bob = await ensureLogin(`bob${RUN_ID}`)
    chatId = await ensureDirectChat(api, alice, bob)
  })

  // api-контекст suite-scope уничтожается самим Playwright: ручной dispose()
  // в afterAll конфликтует с авто-диспозом и ломает следующий suite (T064)

  test.beforeEach(() => {
    openedContexts = []
  })

  test.afterEach(async () => {
    for (const context of openedContexts) {
      await context.close()
    }
  })

  /**
   * Приватный контекст гостя: без localStorage-сессии (браузер без
   * аккаунта — quickstart §1 «дейв»), fake media наследуются от
   * launchOptions браузера; разрешения выдаём контексту явно.
   */
  async function newGuestContext(
    browser: import('@playwright/test').Browser,
  ): Promise<BrowserContext> {
    const context = await browser.newContext({ permissions: ['camera', 'microphone'] })
    openedContexts.push(context)
    return context
  }

  test('SC-003: гость без аккаунта входит по инвайт-ссылке ≤ 3 действий; «гость» виден модератору; чат недоступен (FR-010)', async ({
    browser,
  }) => {
    const aliceNow = await login(api, alice.username)
    const conference = await createConference(api, aliceNow, chatId)

    // №52: генерация гостевой ссылки модератором (создателем) — в чат не постится
    const invite = await apiCall<{ inviteUrl: string; expiresAt: string }>(
      api,
      'post',
      `/api/v1/conferences/${conference.id}/guest-invite`,
      { token: aliceNow.accessToken },
    )
    expect(invite.inviteUrl).toContain('/join/')

    // alice (модератор) в звонке — наблюдатель входа гостя (T051)
    const alicePage = await openConferencePage(browser, aliceNow, conference.id)
    openedContexts.push(alicePage.context())
    await alicePage.getByRole('button', { name: 'Войти' }).click()
    await expect(alicePage.getByText('Вы в конференции')).toBeVisible({ timeout: 15_000 })

    // приватное окно без аккаунта открывает ссылку №52 (путь — из
    // inviteUrl; хостом остаётся baseURL стенда — app.public-base-url
    // per-env, тест не зависит от его значения)
    const guestName = `Гость${RUN_ID}`
    const guestContext = await newGuestContext(browser)
    const guestPage = await guestContext.newPage()
    await guestPage.goto(new URL(invite.inviteUrl).pathname)

    // №54 валиден → шаг имени; маршрут публичный — перенаправления на
    // login нет (FR-009), chat-поверхность не мелькает
    await expect(
      guestPage.getByRole('heading', { name: 'Подключение к конференции' }),
    ).toBeVisible()
    expect(guestPage.url()).toContain('/join/')
    expect(guestPage.url()).not.toContain('/login')

    // SC-003 ≤ 3 действий: (1) имя → (2) «Продолжить» → (3) «Войти»
    await guestPage.getByLabel('Ваше имя').fill(guestName)
    await guestPage.getByRole('button', { name: 'Продолжить' }).click()

    // pre-join «только микрофон» (Q1=C/Q3=A): тумблер камеры доступен
    await expect(guestPage.getByRole('button', { name: 'Микрофон' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await expect(guestPage.getByRole('button', { name: 'Камера' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
    await expect(guestPage.getByRole('button', { name: 'Камера' })).toBeEnabled()

    await guestPage.getByRole('button', { name: 'Войти' }).click()

    // гость в конференции с пометкой «гость» (SC-003 завершён — 3 действия)
    await expect(guestPage.getByText('Вы в конференции как гость')).toBeVisible({
      timeout: 15_000,
    })
    await expect(guestPage.getByText(guestName, { exact: true }).first()).toBeVisible()

    // модератор видит имя и бейдж «гость» (metadata №55, T051)
    await expect(alicePage.getByText(guestName, { exact: true }).first()).toBeVisible({
      timeout: 15_000,
    })
    await expect(alicePage.getByText('гость', { exact: true }).first()).toBeVisible()

    // FR-010: чат-поверхность гостю недоступна — `/` без сессии
    // перенаправляет на /login (ProtectedRoute), мессенджер не рендерится
    await guestPage.goto('/')
    await expect(guestPage).toHaveURL(/\/login/)
  })

  test('подделанный nonce — единообразный отказ «Ссылка недействительна» без ввода имени (edge, минимум раскрытия)', async ({
    browser,
  }) => {
    const guestContext = await newGuestContext(browser)
    const guestPage = await guestContext.newPage()
    await guestPage.goto(`/join/garbage-${RUN_ID}`)

    await expect(guestPage.getByText('Ссылка недействительна')).toBeVisible()
    await expect(guestPage.getByLabel('Ваше имя')).toHaveCount(0)
    expect(guestPage.url()).not.toContain('/login')
  })
})

test.describe.serial('индикатор «звонок идёт» US5 e2e (009 T061)', () => {
  let api: APIRequestContext
  let alice: TestAccount
  let carol: TestAccount
  let chatId: string
  let groupTitle: string
  /** Контексты теста (наблюдатель + вкладка звонка) — закрываются в afterEach. */
  let openedContexts: BrowserContext[]

  async function ensureLogin(username: string): Promise<TestAccount> {
    try {
      return await login(api, username)
    } catch {
      return await ensureAccount(api, username)
    }
  }

  test.beforeAll(async () => {
    // синтетический клиентский IP (TEST-NET-2) — изолированные бакеты
    // register/login (паттерн сьюта US4; регистрации US1/US3 уже заняли 4
    // из 5/1ч дефолтного IP)
    api = await request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { 'X-Forwarded-For': '198.51.100.61' },
    })
    alice = await ensureLogin(`alice${RUN_ID}`)
    carol = await ensureAccount(api, `carol${RUN_ID}`)
    // группа alice+carol: у звонка есть член-зритель вне конференции;
    // начальный состав — только из контактов создателя (№27, 422 иначе)
    await apiCall(api, 'post', '/api/v1/contacts', {
      data: { userId: carol.userId },
      token: alice.accessToken,
    })
    groupTitle = `Индикатор${RUN_ID}`
    const group = await apiCall<{ chatId: string }>(api, 'post', '/api/v1/groups', {
      data: { title: groupTitle, memberUserIds: [carol.userId] },
      token: alice.accessToken,
    })
    chatId = group.chatId
  })

  // api-контекст suite-scope уничтожается самим Playwright: ручной dispose()
  // в afterAll конфликтует с авто-диспозом и ломает следующий suite (T064)

  test.beforeEach(() => {
    openedContexts = []
  })

  test.afterEach(async () => {
    for (const context of openedContexts) {
      await context.close()
    }
  })

  test('carol не в звонке: бейдж «Звонок идёт» по started и снятие по завершении №48 (T059/T061)', async ({
    browser,
  }) => {
    const aliceNow = await login(api, alice.username)

    // carol в мессенджере ДО старта: кадр conference.started долетает
    // только по её живому SSE-каналу (потерянный кадр компенсируется
    // REST-fallback лишь на ПЕРЕподключении)
    const carolContext = await browser.newContext()
    openedContexts.push(carolContext)
    await carolContext.addInitScript((refreshToken) => {
      window.localStorage.setItem('webchat.auth.refreshToken', refreshToken)
    }, carol.refreshToken)
    const carolPage = await carolContext.newPage()
    await carolPage.goto('/')
    await expect(carolPage.getByText(groupTitle, { exact: true })).toBeVisible({
      timeout: 15_000,
    })
    // №12 загружен — даём SSE-каналу подняться до входа первого участника
    await carolPage.waitForTimeout(1_000)

    // alice (создатель = модератор) стартует и входит первой —
    // conference.started публикуется именно входом (US5-1)
    const conference = await createConference(api, aliceNow, chatId)
    const alicePage = await openConferencePage(browser, aliceNow, conference.id)
    openedContexts.push(alicePage.context())
    await alicePage.getByRole('button', { name: 'Войти' }).click()
    await expect(alicePage.getByText('Вы в конференции')).toBeVisible({ timeout: 15_000 })

    // №18 conference.started → бейдж «Звонок идёт» у строки чата carol
    await expect(carolPage.getByText('Звонок идёт').first()).toBeVisible({ timeout: 10_000 })

    // №48 завершение модератором (DeleteRoom → вебхук → finalize → №18
    // conference.ended): бейдж снят, участник отключён терминально
    await apiCall(api, 'post', `/api/v1/conferences/${conference.id}/end`, {
      token: aliceNow.accessToken,
    })

    await expect(carolPage.getByText('Звонок идёт')).toHaveCount(0, { timeout: 10_000 })
    await expect(alicePage.getByText('Вы отключены от конференции')).toBeVisible({
      timeout: 15_000,
    })
  })
})
