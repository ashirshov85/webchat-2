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
 * продолжается; корректный выход — «Покинуть» возвращает в мессенджер
 * с открытым чатом конференции (T095/FR-019: deep-link /chat?chat=…).
 *
 * 009, US1, T075 — e2e каркаса конференц-страницы: на широком окне
 * конференция занимает всё окно браузера (computed max-width:none у
 * #root + секция ≈ innerWidth) в фазах pre-join и joined.
 *
 * 009, US1, T078/T084 — e2e автопина демонстрации экрана: старт
 * демонстрации одной вкладкой разворачивает СЛИТЫЙ тайл участника
 * (бейдж «демонстрирует экран», T084) на всю медиа-зону у ВСЕХ
 * участников без ручного клика (пин-раскладка T074 поверх
 * MediaControls «Экран»; завершение демонстрации тайл не роняет —
 * пин остаётся на камере/аватаре, сброс сворачиванием).
 *
 * 009, US1, T079 — e2e полноэкранного пин-оверлея: ручной пин
 * разворачивает закреплённый тайл на весь вьюпорт БЕЗ скроллбаров
 * (scrollHeight/scrollWidth === окну), controls остаются видимы
 * поверх тайла снизу по центру, служебные панели скрыты; сворачивание
 * возвращает обычную раскладку со всеми панелями.
 *
 * 009, US1, T106 — e2e каркаса экрана конференции: панель участников —
 * правая колонка .conference-side рядом с сеткой тайлов (bounding box),
 * сворачивается/разворачивается иконкой-кнопкой conference-panel-toggle
 * (тайлы занимают освободившуюся ширину), controls — всегда внизу по
 * центру экрана в обоих состояниях панели, скроллбаров страницы нет.
 *
 * 009, US1, T098 — e2e паритета панели управления (пересмотр скрытия
 * T089): в пин-оверлее T079 панель одинакова со стандартным режимом —
 * все 4 кнопки-иконки (селекторы по aria-label — текста внутри кнопок
 * нет), «Покинуть» кликабельна прямо из развёрнутого тайла, выход
 * одношаговый с возвратом в чат (T095). Точечные отключения
 * модератора поясняются латунным tooltip'ом при наведении (замена
 * нативного title T097).
 *
 * 009, US1, T100 — e2e индикатора микрофона тайла (прототип
 * tmp/conference-tile-mic.html): КАЖДЫЙ тайл несёт латунный бейдж
 * ровно с двумя состояниями; мьют модератором (№49) гасит бейдж цели
 * у всех вкладок ≤ 2 c (SC-005, серверный мьют отражается isMuted
 * сам); пульс при речи — только у бейджа (fake-audio тон микрофона
 * зажигает ActiveSpeakersChanged, visual assert по классу); пометка
 * «модератор» стоит рядом с именем создателя (metadata §3, T017).
 *
 * 009, US3, T043/T107 — e2e модерации (quickstart §3.3) поверх
 * кебаб-меню объединённой панели участников: мьют микрофона
 * модератором (№49) блокирует тумблер ЦЕЛИ с пояснением и виден всем
 * ≤ 2 c (SC-005); снятие ограничения разблокирует тумбель обратно
 * (SET-семантика №49, live-обновление permission-событием + refetch №46).
 * T094/T107: подписи пунктов меню — от текущего SET-состояния;
 * сценарий «три точечных подряд» — всё остаётся отключено, мастер
 * показывает «Включить все источники» (фикс кодирования
 * canPublish=false), его отжатие включает всё. Футер панели (T107):
 * гостевая ссылка №52 и «Завершить конференцию» №48 видны при
 * прокручивающемся списке — инструменты вне прокрутки.
 *
 * 009, US4, T052 — e2e гостевого доступа (quickstart §3.4): приватное
 * окно БЕЗ аккаунта по инвайт-ссылке №52 (SC-003 — ≤ 3 действий от
 * открытия ссылки до конференции: имя → «Продолжить» → «Войти»; pre-join
 * «только микрофон», Q1=C), пометка «гость» и имя видны модератору
 * (T051), чат-поверхность гостю недоступна (FR-010: `/` → /login),
 * подделанный nonce — единообразный отказ без раскрытия причин (edge).
 *
 * 009, US1, T105 — гостевая нога SC-005: гость «только микрофон» без
 * снимка №46 — кнопки НЕопубликованных источников (экран/камера)
 * блокируются permission-событием сами (bump-эпоха прав в
 * MediaControls): mic→экран→камера подряд → все три кнопки-иконки
 * disabled ≤ 2 c, «Камера» некликабельна (регресс бага залипания).
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
 * T107: действие модератора №49/№50/№51 поверх кебаб-меню строки
 * объединённой панели — открыть кебаб участника и выбрать пункт
 * (меню — портал в body, пункты role=menuitem). Возвращает момент
 * времени ПЕРЕД кликом по пункту — бюджет SC-005 (≤ 2 c) считает
 * применение действия, а не навигацию по меню.
 */
async function kebabAction(page: Page, participantName: string, item: string): Promise<number> {
  const row = page.locator('.conference-participants-item').filter({ hasText: participantName })
  await row.getByRole('button', { name: `Действия с участником ${participantName}` }).click()
  const actedAt = Date.now()
  await page.getByRole('menuitem', { name: item, exact: true }).click()
  return actedAt
}

/** T107: состав пунктов кебаб-меню строки (подписи открытого меню). */
async function kebabLabels(page: Page, participantName: string): Promise<string[]> {
  const row = page.locator('.conference-participants-item').filter({ hasText: participantName })
  await row.getByRole('button', { name: `Действия с участником ${participantName}` }).click()
  const labels = await page.getByRole('menuitem').allTextContents()
  await page.keyboard.press('Escape')
  return labels
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
 * (T075: опциональный широкий viewport — геометрия растянутой страницы).
 * Токен сеется ТОЛЬКО при отсутствии: addInitScript выполняется на
 * КАЖДОЙ навигации контекста, а безусловный setItem затирал бы
 * ротированный токен (PresenceKeeper ProtectedRoute открывает №18
 * уже на конференц-странице → refresh R0→R1; возврат в чат T095
 * подставил бы погашенный R0 → reuse → 401 «Not authenticated»). */
async function openConferencePage(
  browser: import('@playwright/test').Browser,
  account: TestAccount,
  conferenceId: string,
  viewport?: { width: number; height: number },
): Promise<Page> {
  const context = await browser.newContext(viewport === undefined ? undefined : { viewport })
  await context.addInitScript((refreshToken) => {
    if (window.localStorage.getItem('webchat.auth.refreshToken') === null) {
      window.localStorage.setItem('webchat.auth.refreshToken', refreshToken)
    }
  }, account.refreshToken)
  const page = await context.newPage()
  await page.goto(`/conference/${conferenceId}`)
  return page
}

/** Клик «Войти» на pre-join (T034) и ожидание полного медиа: joined-сигнал (корневой .conference-room — ранний маркер joined-рендера, T092) + удалённый тайл. */
async function connectAndAwaitMedia(page: Page, remoteUsername: string): Promise<number> {
  const startedAt = Date.now()
  await page.getByRole('button', { name: 'Войти' }).click()
  await expect(page.locator('.conference-room')).toBeVisible({ timeout: 15_000 })
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
    // синтетический клиентский IP (TEST-NET-2) — изолированные бакеты
    // register/login (паттерн сьютов US3/US4/US5 после пересобора их
    // бакетов в T107-отладке): повторы прогона не исчерпывают per-IP
    // бакет дефолтного IP (5/1ч)
    api = await request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { 'X-Forwarded-For': process.env.E2E_US1_IP ?? '198.51.100.51' },
    })
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
    // «Войти» на pre-join до медиа (joined-сигнал .conference-room + тайл собеседника) ≤ 5 c
    const [aliceElapsed, bobElapsed] = await Promise.all([
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    expect(
      aliceElapsed,
      `SC-001: alice от «Войти» до медиа ${aliceElapsed} мс`,
    ).toBeLessThanOrEqual(SC001_MS)
    expect(bobElapsed, `SC-001: bob от «Войти» до медиа ${bobElapsed} мс`).toBeLessThanOrEqual(
      SC001_MS,
    )

    // корректный выход (Q10=A + FR-019, T095): «Покинуть» корректно рвёт
    // подключение (слот освобождён, комната выгружается) и возвращает в
    // мессенджер с открытым чатом конференции — deep-link
    // /chat?chat={chatId}; возврат на pre-join отменён
    await bobPage.getByRole('button', { name: 'Покинуть' }).click()
    await expect(bobPage).toHaveURL(new RegExp(`/chat\\?chat=${chatId}$`))
    await expect(bobPage.getByRole('heading', { level: 2, name: aliceNow.username })).toBeVisible()
    await expect(bobPage.locator('.conference-room')).toHaveCount(0)
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
    await expect(page.locator('.conference-room')).toBeVisible({ timeout: 15_000 })
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
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
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
    await expect(bobPage.locator('.conference-room')).toBeVisible()
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

    await expect(bobPage.locator('.conference-room')).toBeVisible()
    await expect(
      tilesSection(bobPage).getByText(aliceNow.username, { exact: true }).first(),
    ).toBeVisible()
    await expect(alicePage.locator('.conference-room')).toBeVisible()
    await expect(
      tilesSection(alicePage).getByText(bobNow.username, { exact: true }).first(),
    ).toBeVisible()
    await cdp.detach()
  })

  test('T078/T084: старт демонстрации экрана — слитый тайл участника разворачивается сам у всех вкладок без ручного клика', async ({
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
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    // до демонстрации пин-раскладки нет ни у кого
    await expect(alicePage.locator('.conference-tile-pinned')).toHaveCount(0)
    await expect(bobPage.locator('.conference-tile-pinned')).toHaveCount(0)

    // alice включает демонстрацию (MediaControls «Экран»; Chromium
    // auto-select-desktop-capture-source отдаёт поток без пикера —
    // конфиг): появление screen-трека при пустом пине разворачивает
    // СЛИТЫЙ тайл alice (T084: экран в тайле участника, имя + бейдж
    // «демонстрирует экран») на всю медиа-зону БЕЗ клика по «Развернуть»
    // (T094: скоуп группы controls — у модератора тот же «Экран»
    // есть и в тумблерах строки панели модерации)
    const aliceControls = (name: string) =>
      alicePage
        .getByRole('group', { name: 'Управление конференцией' })
        .getByRole('button', { name, exact: true })
    await aliceControls('Экран').click()

    // зритель bob: единственный развёрнутый тайл — слитый тайл alice
    const bobPinned = bobPage.locator('.conference-tile-pinned')
    await expect(bobPinned).toHaveCount(1, { timeout: 10_000 })
    await expect(bobPinned).toContainText(aliceNow.username)
    await expect(bobPinned).toContainText('демонстрирует экран')
    // собственная вкладка демонстрирующего — тот же автопин (self-view):
    // свой тайл бесшовно переключился на экран-видео (T077 гасит камеру)
    const alicePinned = alicePage.locator('.conference-tile-pinned')
    await expect(alicePinned).toHaveCount(1, { timeout: 10_000 })
    await expect(alicePinned).toContainText('демонстрирует экран')

    // завершение демонстрации — пин ОСТАЁТСЯ на слитом тайле alice
    // (T084): тайл жив с камерой/аватаром, бейдж ушёл
    await aliceControls('Экран').click()
    await expect(bobPinned).not.toContainText('демонстрирует экран', { timeout: 10_000 })
    await expect(bobPinned).toHaveCount(1)
    await expect(bobPinned).toContainText(aliceNow.username)
    await expect(alicePinned).not.toContainText('демонстрирует экран', { timeout: 10_000 })
    await expect(alicePinned).toHaveCount(1)

    // сворачивание возвращает сетку (кнопка «Свернуть» у зрителя,
    // Esc T082 у демонстрирующего); звонок продолжается обычной сеткой
    await bobPinned.getByRole('button', { name: 'Свернуть' }).click()
    await expect(bobPage.locator('.conference-tile-pinned')).toHaveCount(0)
    await alicePage.keyboard.press('Escape')
    await expect(alicePage.locator('.conference-tile-pinned')).toHaveCount(0)
    await expect(bobPage.locator('.conference-room')).toBeVisible()
    await expect(alicePage.locator('.conference-room')).toBeVisible()
  })

  test('T079: пин — тайл на весь экран без скроллбаров, controls поверх тайла; сворачивание возвращает раскладку', async ({
    browser,
  }) => {
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    const conference = await createConference(api, aliceNow, chatId)

    // широкое окно (паритет T075): полноэкранная геометрия видна
    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id, { width: 1600, height: 900 }),
      openConferencePage(browser, bobNow, conference.id, { width: 1600, height: 900 }),
    ])
    openedPages.push(alicePage, bobPage)
    await Promise.all([
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    // обычная раскладка: маркера полноэкранного оверлея нет
    await expect(bobPage.locator('.conference-room-pinned')).toHaveCount(0)

    // bob разворачивает тайл собеседника — комната уходит в оверлей
    // на весь вьюпорт (маркер на корневом div комнаты)
    await tilesSection(bobPage).getByRole('button', { name: 'Развернуть' }).first().click()
    await expect(bobPage.locator('.conference-room-pinned')).toHaveCount(1)
    await expect(bobPage.locator('.conference-tile-pinned')).toHaveCount(1)

    // контента выше вьюпорта нет — скроллбаров нет (суть T079)
    const fitsViewport = await bobPage.evaluate(() => {
      const doc = document.documentElement
      return doc.scrollHeight === window.innerHeight && doc.scrollWidth === window.innerWidth
    })
    expect(fitsViewport, 'полноэкранный пин: scrollHeight/scrollWidth === вьюпорту').toBe(true)

    // controls видимы поверх тайла — снизу по центру, в границах окна
    const controls = bobPage.getByRole('group', { name: 'Управление конференцией' })
    await expect(controls).toBeVisible()
    const controlsBox = await controls.boundingBox()
    const viewport = bobPage.viewportSize()
    expect(controlsBox).not.toBeNull()
    expect(controlsBox?.x, 'controls не выходят за левый край').toBeGreaterThanOrEqual(0)
    expect(controlsBox?.y, 'controls — в нижней половине окна').toBeGreaterThan(
      (viewport?.height ?? 0) / 2,
    )
    expect(
      (controlsBox?.x ?? 0) + (controlsBox?.width ?? 0),
      'controls не выходят за правый край',
    ).toBeLessThanOrEqual(viewport?.width ?? 0)

    // служебные панели страницы скрыты (панель участников); шапка
    // .conference-header (T087), output-подпись и живой счётчик (T092)
    // удалены целиком — ассертов на них нет
    await expect(bobPage.locator('.conference-participants')).toBeHidden()

    // свернул — обычная раскладка со всеми панелями вернулась
    await bobPage
      .locator('.conference-tile-pinned')
      .getByRole('button', { name: 'Свернуть' })
      .click()
    await expect(bobPage.locator('.conference-room-pinned')).toHaveCount(0)
    await expect(bobPage.locator('.conference-participants')).toBeVisible()
    // звонок продолжается обычной сеткой
    await expect(
      tilesSection(bobPage).getByText(aliceNow.username, { exact: true }).first(),
    ).toBeVisible()
  })

  test('T106: панель участников — правая колонка от сетки тайлов; сворачивание/разворачивание иконкой-кнопкой; controls всегда внизу по центру', async ({
    browser,
  }) => {
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    // ghost-подключения предыдущих тестов сьюта живут в активной комнате
    // чата (№44 «одна активная») — завершаю её (создатель = alice) и
    // звоню в свежей комнате (паритет T100)
    const stale = await createConference(api, aliceNow, chatId)
    await apiCall(api, 'post', `/api/v1/conferences/${stale.id}/end`, {
      token: aliceNow.accessToken,
    }).catch(() => {})
    const conference = await createConference(api, aliceNow, chatId)

    // широкое окно (паритет T075/T079): геометрия сайдбара видна
    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id, { width: 1600, height: 900 }),
      openConferencePage(browser, bobNow, conference.id, { width: 1600, height: 900 }),
    ])
    openedPages.push(alicePage, bobPage)
    await Promise.all([
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    // bob — обычный участник: чистый сайдбар (панель участников, без
    // инструментов модерации). Панель — ПРАВЕЕ сетки тайлов (bounding box:
    // колонка начинается после правой кромки тайлов, в пределах окна)
    const side = bobPage.locator('.conference-side')
    await expect(side).toBeVisible()
    await expect(side.locator('.conference-participants')).toBeVisible()
    const tilesBox = await tilesSection(bobPage).boundingBox()
    const sideBox = await side.boundingBox()
    const viewport = bobPage.viewportSize()
    expect(tilesBox).not.toBeNull()
    expect(sideBox).not.toBeNull()
    expect(sideBox!.x, 'сайдбар правее сетки тайлов').toBeGreaterThan(tilesBox!.x)
    expect(
      sideBox!.x,
      'сайдбар начинается у правой кромки тайлов (gap 12px)',
    ).toBeGreaterThanOrEqual(tilesBox!.x + tilesBox!.width - 1)
    expect(sideBox!.x + sideBox!.width, 'сайдбар в пределах окна').toBeLessThanOrEqual(
      viewport?.width ?? 0,
    )
    // скроллбаров страницы нет: переполнение гасится внутри (сетка/список)
    const fitsViewport = await bobPage.evaluate(
      () =>
        document.documentElement.scrollHeight <= window.innerHeight &&
        document.documentElement.scrollWidth <= window.innerWidth,
    )
    expect(fitsViewport, 'каркас T106: контент не выше/шире вьюпорта').toBe(true)

    // controls — всегда внизу по центру ЭКРАНА (в обоих состояниях панели)
    const controls = bobPage.getByRole('group', { name: 'Управление конференцией' })
    await expect(controls).toBeVisible()
    const controlsCentered = async (): Promise<void> => {
      const box = await controls.boundingBox()
      expect(box).not.toBeNull()
      const centerX = box!.x + box!.width / 2
      expect(
        Math.abs(centerX - (viewport?.width ?? 0) / 2),
        `controls по центру: ${centerX} ≈ ${(viewport?.width ?? 0) / 2}`,
      ).toBeLessThanOrEqual(24)
      expect(box!.y, 'controls — в нижней половине окна').toBeGreaterThan(
        (viewport?.height ?? 0) / 2,
      )
    }
    await controlsCentered()

    // сворачивание иконкой-кнопкой (без текста/рамки — селектор по
    // aria-label): панель скрыта, тайлы занимают освободившуюся ширину
    await bobPage.getByRole('button', { name: 'Свернуть панель участников' }).click()
    await expect(side).toBeHidden()
    const collapsedBox = await tilesSection(bobPage).boundingBox()
    expect(collapsedBox!.width, 'тайлы заняли ширину сайдбара').toBeGreaterThan(
      tilesBox!.width + 300,
    )
    await controlsCentered()

    // разворачивание обратно
    await bobPage.getByRole('button', { name: 'Развернуть панель участников' }).click()
    await expect(side).toBeVisible()
    const reopenedBox = await tilesSection(bobPage).boundingBox()
    expect(reopenedBox!.width).toBeLessThan(collapsedBox!.width - 300)
    await controlsCentered()

    // комнату завершаю сам (создатель): активная комната не достанется
    // следующему сюиту с ghost-подключениями закрытых вкладок
    await apiCall(api, 'post', `/api/v1/conferences/${conference.id}/end`, {
      token: aliceNow.accessToken,
    }).catch(() => {})
  })

  test('T098: пин-оверлей — «Покинуть» видна и кликабельна прямо из развёрнутого тайла; выход возвращает в чат', async ({
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
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    // bob разворачивает тайл собеседника — пин-оверлей на весь вьюпорт
    await tilesSection(bobPage).getByRole('button', { name: 'Развернуть' }).first().click()
    await expect(bobPage.locator('.conference-room-pinned')).toHaveCount(1)

    // ПАРИТЕТ двух режимов (T098 — пересмотр скрытия T089): панель
    // управления в оверлее — та же оболочка machine со ВСЕМИ 4
    // кнопками-иконками (иконки без текста — селекторы по aria-label),
    // «Покинуть» доступна прямо из развёрнутого тайла
    const overlayControls = bobPage
      .locator('.conference-room-pinned')
      .getByRole('group', { name: 'Управление конференцией' })
    await expect(overlayControls).toBeVisible()
    for (const name of ['Микрофон', 'Камера', 'Экран', 'Покинуть']) {
      await expect(overlayControls.getByRole('button', { name, exact: true })).toBeVisible()
    }
    const overlayLeave = overlayControls.getByRole('button', { name: 'Покинуть' })
    await expect(overlayLeave).toBeEnabled()

    // выход из развёрнутого тайла одношаговый: «Покинуть» рвёт слот и
    // возвращает в мессенджер с открытым чатом конференции (T095)
    await overlayLeave.click()
    await expect(bobPage).toHaveURL(new RegExp(`/chat\\?chat=${chatId}$`))
    await expect(bobPage.getByRole('heading', { level: 2, name: aliceNow.username })).toBeVisible()
    await expect(bobPage.locator('.conference-room')).toHaveCount(0)
  })

  test('T100: бейдж микрофона на КАЖДОМ тайле — мьют модератором гасит бейдж цели ≤ 2 c; пульс у говорящего; «модератор» у создателя', async ({
    browser,
  }) => {
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    // ghost-подключения предыдущих тестов сьюта живут в активной
    // комнате чата (№44 «одна активная») и дали бы дубли тайлов —
    // завершаю её (создатель = alice) и звоню в свежей комнате
    const stale = await createConference(api, aliceNow, chatId)
    await apiCall(api, 'post', `/api/v1/conferences/${stale.id}/end`, {
      token: aliceNow.accessToken,
    }).catch(() => {})
    const conference = await createConference(api, aliceNow, chatId)

    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id),
      openConferencePage(browser, bobNow, conference.id),
    ])
    openedPages.push(alicePage, bobPage)
    await Promise.all([
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    // КАЖДЫЙ тайл несёт индикатор микрофона (T100): бейджи зажжены —
    // дефолты mode=video публикуют микрофоны обеих сторон
    const bobTileAtAlice = tilesSection(alicePage)
      .locator('figure')
      .filter({ hasText: bobNow.username })
    const bobTileAtBob = tilesSection(bobPage)
      .locator('figure')
      .filter({ hasText: bobNow.username })
    await expect(bobTileAtAlice.locator('.conference-tile-mic')).toHaveAttribute('data-mic', 'on')
    await expect(bobTileAtBob.locator('.conference-tile-mic')).toHaveAttribute('data-mic', 'on')

    // пометка роли в строке имени (metadata §3): создатель конференции
    // — модератор (T017); собственный тайл члена — без пометок
    await expect(
      tilesSection(bobPage).locator('figure').filter({ hasText: aliceNow.username }),
    ).toContainText('· модератор')
    await expect(bobTileAtBob).not.toContainText('· ')

    // пульс при речи — ТОЛЬКО у бейджа (visual assert по классу):
    // непрерывный тон fake-микрофона (WAV в playwright.config) держит
    // speaking включённого микрофона — бейдж говорящего bob
    // пульсирует у зрителя; ничего больше не анимируется (рамка
    // тайла/аватар без свечения — классов пульса у них нет)
    await expect(bobTileAtAlice.locator('.conference-tile-mic')).toHaveClass(
      /conference-tile-mic-pulse/,
      { timeout: 15_000 },
    )

    // №49 (SC-005): alice глушит bob'у микрофон из кебаб-меню строки
    // (T107) — бейдж цели гаснет у ОБЕИХ вкладок ≤ 2 c (серверный
    // мьют отражается isMuted сам)
    const startedAt = await kebabAction(alicePage, bobNow.username, 'Отключить микрофон')
    await expect(bobTileAtAlice.locator('.conference-tile-mic')).toHaveAttribute(
      'data-mic',
      'off',
      {
        timeout: SC005_MS,
      },
    )
    await expect(bobTileAtBob.locator('.conference-tile-mic')).toHaveAttribute('data-mic', 'off', {
      timeout: SC005_MS,
    })
    expect(
      Date.now() - startedAt,
      `SC-005: гашение бейджа микрофона ${Date.now() - startedAt} мс`,
    ).toBeLessThanOrEqual(SC005_MS)

    // комнату завершаю сам (создатель): активная комната не достанется
    // следующему сюиту с ghost-подключениями закрытых вкладок
    await apiCall(api, 'post', `/api/v1/conferences/${conference.id}/end`, {
      token: aliceNow.accessToken,
    }).catch(() => {})
  })
})

test.describe.serial('модерация US3 e2e (009 T043)', () => {
  let api: APIRequestContext
  let alice: TestAccount
  let bob: TestAccount
  let chatId: string
  /** Страницы теста — закрываются в afterEach (контексты LiveKit-вкладок). */
  let openedPages: Page[]
  /**
   * Конференция текущего теста — завершается №48 в afterEach: №44
   * идемпотентен («одна активная на чат»), без чистки следующий тест
   * унаследовал бы активную комнату со «свисающими» подключениями
   * закрытых вкладок предыдущего (строгий локатор строки цели
   * разрешался бы в несколько элементов).
   */
  let activeConference: ConferenceView | null = null
  let creatorToken: string | null = null

  test.beforeAll(async () => {
    // синтетический клиентский IP (TEST-NET-2) — изолированные бакеты
    // register/login (паттерн сьютов US4/US5): повторы прогона сьюта
    // при отладке не исчерпывают per-IP бакет дефолтного IP (5/1ч),
    // поделённый с сьютом US1; E2E_US3_IP переопределяет IP для
    // повторных отладочных прогонов (бакет 1ч)
    api = await request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { 'X-Forwarded-For': process.env.E2E_US3_IP ?? '198.51.100.53' },
    })
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
    if (activeConference !== null && creatorToken !== null) {
      // 409 conference_ended (уже завершена) чисткой не считается
      await apiCall(api, 'post', `/api/v1/conferences/${activeConference.id}/end`, {
        token: creatorToken,
      }).catch(() => {})
    }
    activeConference = null
    creatorToken = null
  })

  test('SC-005: мьют микрофона модератором из кебаб-меню блокирует кнопку цели с латунной tooltip-подсказкой ≤ 2 c и виден всем; снятие разблокирует', async ({
    browser,
  }) => {
    // alice — создатель = модератор (T017); обе стороны в звонке,
    // микрофон bob опубликован (дефолты mode=video)
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    const conference = await createConference(api, aliceNow, chatId)
    activeConference = conference
    creatorToken = aliceNow.accessToken
    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id),
      openConferencePage(browser, bobNow, conference.id),
    ])
    openedPages.push(alicePage, bobPage)
    await Promise.all([
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    // №49 через кебаб-меню строки (T107): alice глушит bob'у микрофон
    // (бюджут SC-005 — от клика по пункту, навигация по меню не в счёт)
    const startedAt = await kebabAction(alicePage, bobNow.username, 'Отключить микрофон')

    // у bob кнопка-иконка заблокирована (disabled по aria-label —
    // текстовых тумблеров больше нет, T098), точечное ограничение
    // поясняется ЛАТУННЫМ tooltip'ом при наведении (T098 — замена
    // нативного title T097) ≤ 2 c от клика
    const bobMicToggle = bobPage.getByRole('button', { name: 'Микрофон', exact: true })
    await expect(bobMicToggle).toBeDisabled({ timeout: SC005_MS })
    await bobMicToggle.hover()
    await expect(bobMicToggle.locator('.conference-controls-tooltip')).toHaveText(
      'Микрофон отключён модератором',
      { timeout: SC005_MS },
    )
    expect(
      Date.now() - startedAt,
      `SC-005: применение мьюта ${Date.now() - startedAt} мс`,
    ).toBeLessThanOrEqual(SC005_MS)
    // состояние видно модератору (SC-005): подпись пункта №49 следует
    // SET-состоянию — меню строки цели перевернулось в «Включить …»
    await expect.poll(() => kebabLabels(alicePage, bobNow.username)).toContain('Включить микрофон')

    // снятие ограничения (SET false, Q22=A): блокировка снята, tooltip
    // возвращается к имени ≤ 2 c
    await kebabAction(alicePage, bobNow.username, 'Включить микрофон')
    await expect(bobMicToggle).toBeEnabled({
      timeout: SC005_MS,
    })
    await bobMicToggle.hover()
    await expect(bobMicToggle.locator('.conference-controls-tooltip')).toHaveText('Микрофон', {
      timeout: SC005_MS,
    })
  })

  test('SC-005/T094: три точечных подряд из кебаб-меню — всё остаётся отключено, мастер показывает «Включить все источники»; его отжатие включает всё', async ({
    browser,
  }) => {
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    const conference = await createConference(api, aliceNow, chatId)
    activeConference = conference
    creatorToken = aliceNow.accessToken
    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id),
      openConferencePage(browser, bobNow, conference.id),
    ])
    openedPages.push(alicePage, bobPage)
    await Promise.all([
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    // регресс бага T094: третья точечная не сбрасывает отключения —
    // после mic+camera+screen grants цели canPublish=false, меню
    // держит «Включить …» по всем точкам И мастером ≤ 2 c (SC-005)
    // (бюджет SC-005 — от клика по ТРЕТЬЕЙ точечной, навигация по меню
    // и ожидание применения первых двух не в счёт)
    let thirdActedAt = 0
    for (const item of ['Отключить микрофон', 'Отключить камеру', 'Отключить экран']) {
      thirdActedAt = await kebabAction(alicePage, bobNow.username, item)
    }
    const startedAt = thirdActedAt

    // состав меню цели (SET-состояние Q4=A): все точки — «Включить …»,
    // мастер — «Включить все источники» (сброса ограничений нет)
    await expect
      .poll(() => kebabLabels(alicePage, bobNow.username))
      .toEqual([
        'Включить микрофон',
        'Включить камеру',
        'Включить экран',
        'Включить все источники',
        'Назначить модератором',
        'Исключить',
      ])
    expect(
      Date.now() - startedAt,
      `SC-005: применение третьей точечной ${Date.now() - startedAt} мс`,
    ).toBeLessThanOrEqual(SC005_MS)

    // у цели фактическое «всё заблокировано» (canPublish=false, Q4=A):
    // сигналом становятся три недоступные кнопки-иконки, tooltip'ы
    // несут только имена и в DOM нет текстовых пояснений (T097/T098 —
    // состояние «все источники отключены модератором» в
    // conference-controls не выводится вовсе)
    for (const label of ['Микрофон', 'Камера', 'Экран']) {
      const toggle = bobPage.getByRole('button', { name: label, exact: true })
      await expect(toggle).toBeDisabled({ timeout: SC005_MS })
      await expect(toggle.locator('.conference-controls-tooltip')).toHaveText(label)
      await expect(toggle).not.toHaveAttribute('title')
    }
    await expect(bobPage.getByText('Публикация отключена модератором (право слова)')).toHaveCount(0)
    await expect(bobPage.getByText(/отключё[нн]а? модератором/)).toHaveCount(0)

    // отжатие мастера (пункт меню) включает всё: точечные кнопки цели
    // доступны ≤ 2 c, подпись мастера вернулась в «Отключить …»
    await kebabAction(alicePage, bobNow.username, 'Включить все источники')
    await expect(bobPage.getByRole('button', { name: 'Микрофон', exact: true })).toBeEnabled({
      timeout: SC005_MS,
    })
    await expect
      .poll(() => kebabLabels(alicePage, bobNow.username))
      .toContain('Отключить все источники')
  })

  test('T107: футер панели модератора всегда видим — при прокручивающемся списке инструменты на месте, скроллбаров страницы нет', async ({
    browser,
  }) => {
    const [aliceNow, bobNow] = [await login(api, alice.username), await login(api, bob.username)]
    const conference = await createConference(api, aliceNow, chatId)
    activeConference = conference
    creatorToken = aliceNow.accessToken
    const [alicePage, bobPage] = await Promise.all([
      openConferencePage(browser, aliceNow, conference.id),
      openConferencePage(browser, bobNow, conference.id),
    ])
    openedPages.push(alicePage, bobPage)
    await Promise.all([
      connectAndAwaitMedia(alicePage, bobNow.username),
      connectAndAwaitMedia(bobPage, aliceNow.username),
    ])

    // низкое окно: список участников (2 строки + футер) перестаёт
    // помещаться — прокрутка появляется ТОЛЬКО у списка (flex:1/
    // min-height:0), футер flex:none остаётся видимым
    await alicePage.setViewportSize({ width: 1280, height: 320 })
    await expect(alicePage.getByRole('button', { name: 'Завершить конференцию' })).toBeVisible()
    await expect(alicePage.getByRole('button', { name: 'Пригласить гостя' })).toBeVisible()
    const listScrolls = await alicePage.evaluate(() => {
      const list = document.querySelector('.conference-participants-list')
      return list !== null && list.scrollHeight > list.clientHeight
    })
    expect(listScrolls, 'низкое окно: список участников прокручивается').toBe(true)
    const fitsViewport = await alicePage.evaluate(
      () =>
        document.documentElement.scrollHeight <= window.innerHeight &&
        document.documentElement.scrollWidth <= window.innerWidth,
    )
    expect(fitsViewport, 'футер на месте — скроллбары страницы не заводятся').toBe(true)

    // у обычного участника футера нет вовсе (панель без инструментов,
    // паритет прототипа участника)
    await bobPage.setViewportSize({ width: 1280, height: 320 })
    await expect(bobPage.getByRole('button', { name: 'Завершить конференцию' })).toHaveCount(0)
    await expect(bobPage.getByRole('button', { name: 'Пригласить гостя' })).toHaveCount(0)
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

    // alice (модератор) в звонке — наблюдатель входа гостя (T051)
    const alicePage = await openConferencePage(browser, aliceNow, conference.id)
    openedContexts.push(alicePage.context())
    await alicePage.getByRole('button', { name: 'Войти' }).click()
    await expect(alicePage.locator('.conference-room')).toBeVisible({ timeout: 15_000 })

    // №52 через UI блока «Гостевая ссылка» в футере панели (T081/T107):
    // «Пригласить гостя» → inviteUrl в readonly-поле (в чат не постится);
    // прямая №52-нога apiCall удалена — ссылка берётся из приложения
    await alicePage.getByRole('button', { name: 'Пригласить гостя' }).click()
    const inviteField = alicePage.getByRole('textbox', { name: 'Ссылка-приглашение' })
    await expect(inviteField).toBeVisible({ timeout: 10_000 })
    const inviteUrl = await inviteField.inputValue()
    expect(inviteUrl).toContain('/join/')

    // приватное окно без аккаунта открывает ссылку №52 (путь — из
    // inviteUrl; хостом остаётся baseURL стенда — app.public-base-url
    // per-env, тест не зависит от его значения)
    const guestName = `Гость${RUN_ID}`
    const guestContext = await newGuestContext(browser)
    const guestPage = await guestContext.newPage()
    await guestPage.goto(new URL(inviteUrl).pathname)

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

    // гость в конференции (SC-003 завершён — 3 действия): joined-маркер —
    // комната (T092: output-подпись «как гость» удалена)
    await expect(guestPage.locator('.conference-room')).toBeVisible({ timeout: 15_000 })
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

  test('T105: гостевая нога SC-005 — mic→экран→камера гостю «только микрофон»: кнопки блокируются ≤ 2 c, «Камера» некликабельна', async ({
    browser,
  }) => {
    const aliceNow = await login(api, alice.username)
    const conference = await createConference(api, aliceNow, chatId)

    // alice (создатель = модератор) в звонке — будет срезать гостю
    // источники по одному (№49, SC-005)
    const alicePage = await openConferencePage(browser, aliceNow, conference.id)
    openedContexts.push(alicePage.context())
    await alicePage.getByRole('button', { name: 'Войти' }).click()
    await expect(alicePage.locator('.conference-room')).toBeVisible({ timeout: 15_000 })

    // инвайт-ссылка через UI футера панели (как в SC-003, T107)
    await alicePage.getByRole('button', { name: 'Пригласить гостя' }).click()
    const inviteField = alicePage.getByRole('textbox', { name: 'Ссылка-приглашение' })
    await expect(inviteField).toBeVisible({ timeout: 10_000 })
    const inviteUrl = await inviteField.inputValue()

    // гость входит «только микрофон» (Q1=C/Q3=A): камера/экран
    // НЕ опубликованы — у их кнопок нет трек-событий, единственная
    // нога перерисовки панели — permission-событие (bump-эпоха T105)
    const guestName = `Гость105${RUN_ID}`
    const guestContext = await newGuestContext(browser)
    const guestPage = await guestContext.newPage()
    await guestPage.goto(new URL(inviteUrl).pathname)
    await guestPage.getByLabel('Ваше имя').fill(guestName)
    await guestPage.getByRole('button', { name: 'Продолжить' }).click()
    await guestPage.getByRole('button', { name: 'Войти' }).click()
    await expect(guestPage.locator('.conference-room')).toBeVisible({ timeout: 15_000 })
    await expect(guestPage.getByRole('button', { name: 'Микрофон', exact: true })).toBeEnabled()

    // строка цели в объединённой панели (T107): действия №49 — кебаб
    const guestRowName = guestName

    // (1) mic: живой трек — сервер снимает публикацию, блокировка
    // приезжает трек-событием ≤ 2 c
    await kebabAction(alicePage, guestRowName, 'Отключить микрофон')
    await expect(guestPage.getByRole('button', { name: 'Микрофон', exact: true })).toBeDisabled({
      timeout: SC005_MS,
    })

    // (2) экран: источник НЕ опубликован — трек-события нет; регресс
    // бага T105 (гость без №46): bump-эпоха перерисовывает панель по
    // permission-событию, кнопка «Экран» блокируется ≤ 2 c
    const screenStartedAt = await kebabAction(alicePage, guestRowName, 'Отключить экран')
    await expect(guestPage.getByRole('button', { name: 'Экран', exact: true })).toBeDisabled({
      timeout: SC005_MS,
    })
    expect(
      Date.now() - screenStartedAt,
      `SC-005: блокировка экрана гостя ${Date.now() - screenStartedAt} мс`,
    ).toBeLessThanOrEqual(SC005_MS)

    // (3) камера: третий точечный → grants canPublish=false (T094) —
    // ВСЕ три кнопки-иконки disabled ≤ 2 c, «Камера» некликабельна
    // (регресс бага репорта: кнопка оставалась доступной, клик давал
    // «Не удалось включить камеру»)
    const cameraStartedAt = await kebabAction(alicePage, guestRowName, 'Отключить камеру')
    const guestCamera = guestPage.getByRole('button', { name: 'Камера', exact: true })
    await expect(guestCamera).toBeDisabled({ timeout: SC005_MS })
    expect(
      Date.now() - cameraStartedAt,
      `SC-005: блокировка камеры гостя ${Date.now() - cameraStartedAt} мс`,
    ).toBeLessThanOrEqual(SC005_MS)
    for (const label of ['Микрофон', 'Камера', 'Экран']) {
      await expect(guestPage.getByRole('button', { name: label, exact: true })).toBeDisabled()
    }

    // комнату завершаю сам (создатель): активная комната не достанется
    // следующему сюиту с ghost-подключениями закрытых вкладок
    await apiCall(api, 'post', `/api/v1/conferences/${conference.id}/end`, {
      token: aliceNow.accessToken,
    }).catch(() => {})
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
    // register/login (паттерн сьютов US1/US3/US4 после пересобора их
    // бакетов в T107-отладке; E2E_US5_IP переопределяет IP для
    // повторных отладочных прогонов — бакет 1ч)
    api = await request.newContext({
      baseURL: API_URL,
      extraHTTPHeaders: { 'X-Forwarded-For': process.env.E2E_US5_IP ?? '198.51.100.61' },
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
    await expect(alicePage.locator('.conference-room')).toBeVisible({ timeout: 15_000 })

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
