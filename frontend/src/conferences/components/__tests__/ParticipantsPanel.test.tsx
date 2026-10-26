import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ParticipantsPanel, { restrictionMarks } from '../ParticipantsPanel'
import { ParticipantProfilesContext } from '../../profiles'
import { colorOf } from '../../../ui/avatar'

/**
 * 009, US3, T041/T107 — ОБЪЕДИНЁННАЯ панель участников (FR-008):
 * единый список «Участники (N)» из медиа-плоскости LiveKit
 * (useParticipants: [local, ...remote]) — строка = аватар + имя
 * (+«вы») + роль ТОЛЬКО пометками «гость»/«модератор» (обычный
 * участник — без пометки; лексика пометок тайлов T100), identity-
 * строка и текстовые заметки ограничений из строки убраны (T107;
 * истинное состояние источников несут индикаторы T108). Действия
 * модератора (№49–№51) — кебаб-кнопка строки → ui/ContextMenu;
 * инструменты панели (гостевая ссылка №52/№53, «Завершить
 * конференцию» №48) — футер только модератору, вне прокрутки
 * списка. Примитивы @livekit/components-react и API-клиент
 * заменены фейками: предмет теста — проекция состояния LiveKit и
 * видимость инструментов по роли локального (metadata, §3).
 */

/** Wire-константы TrackSource (@livekit/protocol) — как в компоненте. */
const CAMERA = 1
const MICROPHONE = 2
const SCREEN_SHARE = 3
const SCREEN_SHARE_AUDIO = 4

const ALL_SOURCES = [CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO]

/** Минимальный контракт участника LiveKit, используемый панелью. */
interface FakeParticipant {
  readonly identity: string
  readonly name?: string
  readonly metadata?: string
  readonly isLocal: boolean
  readonly permissions?: { readonly canPublish: boolean; readonly canPublishSources: number[] }
}

const lkc = vi.hoisted(() => ({
  useParticipants: vi.fn(),
  useLocalParticipant: vi.fn(),
}))

vi.mock('@livekit/components-react', () => ({
  useParticipants: lkc.useParticipants,
  useLocalParticipant: lkc.useLocalParticipant,
}))

const api = vi.hoisted(() => ({
  endConference: vi.fn(),
  kickParticipant: vi.fn(),
  updateParticipantRestrictions: vi.fn(),
  updateParticipantRole: vi.fn(),
  createGuestInvite: vi.fn(),
  revokeGuestInvite: vi.fn(),
}))

vi.mock('../../api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/conferences')>()),
  endConference: api.endConference,
  kickParticipant: api.kickParticipant,
  updateParticipantRestrictions: api.updateParticipantRestrictions,
  updateParticipantRole: api.updateParticipantRole,
  createGuestInvite: api.createGuestInvite,
  revokeGuestInvite: api.revokeGuestInvite,
}))

function participant(overrides: Partial<FakeParticipant> & { identity: string }): FakeParticipant {
  return { isLocal: false, name: 'Имя', ...overrides }
}

/** Метаданные члена чата/гостя по контракту §3. */
function memberMetadata(role: 'moderator' | 'participant', guest = false): string {
  return JSON.stringify(guest ? { role, guest, inviteId: 'i-1' } : { role, guest })
}

const ALICE_ID = 'u:11111111-1111-1111-1111-111111111111:conn-1'
const BOB_ID = 'u:22222222-2222-4222-8222-222222222222:conn-2'

/** Комната по умолчанию: локальный модератор alice + удалённый участник bob. */
function moderatorRoom(): FakeParticipant[] {
  return [
    participant({
      identity: ALICE_ID,
      name: 'alice',
      metadata: memberMetadata('moderator'),
      isLocal: true,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }),
    participant({
      identity: BOB_ID,
      name: 'bob',
      metadata: memberMetadata('participant'),
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }),
  ]
}

/** Строка списка по имени участника. */
function rowOf(name: string): HTMLElement {
  const row = screen.getByText(name).closest('li')
  if (row === null) {
    throw new Error(`строка участника «${name}» не найдена`)
  }
  return row
}

/** Открыть кебаб-меню строки (кнопка «Действия с участником {имя}»). */
function openRowMenu(name: string): void {
  const row = rowOf(name)
  fireEvent.click(within(row).getByRole('button', { name: `Действия с участником ${name}` }))
}

describe('ParticipantsPanel — единый список (009 US3 T041/T107)', () => {
  beforeEach(() => {
    lkc.useParticipants.mockReset()
    lkc.useLocalParticipant.mockReset()
    api.endConference.mockReset()
    api.kickParticipant.mockReset()
    api.updateParticipantRestrictions.mockReset()
    api.updateParticipantRole.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('строка = аватар + имя + «вы» + роль пометками «гость»/«модератор»: обычный участник БЕЗ пометки; identity/online/заметки ограничений убраны', () => {
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: moderatorRoom()[0] })
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: ALICE_ID,
        name: 'alice',
        metadata: memberMetadata('moderator'),
        isLocal: true,
      }),
      participant({
        identity: BOB_ID,
        name: 'bob',
        metadata: memberMetadata('participant'),
      }),
      participant({
        // гость №55 — имя введено на GuestJoinPage
        identity: 'g:conn-guest-1',
        name: 'Виктор',
        metadata: memberMetadata('participant', true),
      }),
    ])

    render(<ParticipantsPanel conferenceId="c-1" />)

    expect(screen.getByText('Участники (3)')).toBeInTheDocument()
    expect(screen.getByText('alice')).toBeInTheDocument()
    expect(screen.getByText('вы')).toBeInTheDocument()
    expect(screen.getByText('bob')).toBeInTheDocument()
    expect(screen.getByText('Виктор')).toBeInTheDocument()
    // роль — ТОЛЬКО пометки «гость»/«модератор» (лексика тайлов T100)
    expect(screen.getByText('модератор')).toBeInTheDocument()
    expect(screen.getAllByText('гость')).toHaveLength(1)
    // обычный участник — без пометки роли
    expect(screen.queryByText('участник')).not.toBeInTheDocument()
    // identity-строки в строках больше нет (T107; ключ — data-атрибут)
    expect(screen.queryByText(BOB_ID)).not.toBeInTheDocument()
    expect(screen.queryByText('g:conn-guest-1')).not.toBeInTheDocument()
    // текстовые заметки ограничений из строки убраны (индикаторы — T108)
    expect(screen.queryByText('в сети')).not.toBeInTheDocument()
    expect(screen.queryByText('микрофон отключён')).not.toBeInTheDocument()
    expect(screen.queryByText('все источники отключены')).not.toBeInTheDocument()
    expect(rowOf('bob')).toHaveAttribute('data-participant-identity', BOB_ID)
  })

  it('роль по умолчанию — участник: битый/отсутствующий metadata не рушит список и не открывает инструменты (fail-closed)', () => {
    const local = participant({ identity: 'u:1:c1', name: 'no-meta', isLocal: true })
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: local })
    lkc.useParticipants.mockReturnValue([
      local,
      participant({ identity: 'u:2:c2', name: 'broken', metadata: '{role":' }),
    ])

    render(<ParticipantsPanel conferenceId="c-1" />)

    expect(screen.getByText('Участники (2)')).toBeInTheDocument()
    expect(screen.queryByText('модератор')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Действия с участником/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Завершить конференцию' })).not.toBeInTheDocument()
  })

  it('счётчик «Участники (N)» живой: приход/уход участников меняет заголовок без перемонтирования', () => {
    const local = participant({
      identity: ALICE_ID,
      name: 'alice',
      metadata: memberMetadata('participant'),
      isLocal: true,
    })
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: local })
    lkc.useParticipants.mockReturnValue([local])
    const { rerender } = render(<ParticipantsPanel conferenceId="c-1" />)
    expect(screen.getByText('Участники (1)')).toBeInTheDocument()

    lkc.useParticipants.mockReturnValue([local, participant({ identity: BOB_ID, name: 'bob' })])
    rerender(<ParticipantsPanel conferenceId="c-1" />)
    expect(screen.getByText('Участники (2)')).toBeInTheDocument()

    lkc.useParticipants.mockReturnValue([local])
    rerender(<ParticipantsPanel conferenceId="c-1" />)
    expect(screen.getByText('Участники (1)')).toBeInTheDocument()
  })

  it('у обычного участника и гостя кебаба и футера нет — панель внешне та же (паритет прототипа участника)', () => {
    const local = participant({
      identity: BOB_ID,
      name: 'bob',
      metadata: memberMetadata('participant'),
      isLocal: true,
    })
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: local })
    lkc.useParticipants.mockReturnValue([
      local,
      participant({ identity: ALICE_ID, name: 'alice', metadata: memberMetadata('moderator') }),
    ])

    const { container } = render(<ParticipantsPanel conferenceId="c-1" />)

    // кебаба нет ни у своей строки, ни у строки модератора
    expect(screen.queryByRole('button', { name: /Действия с участником/ })).not.toBeInTheDocument()
    expect(container.querySelector('.c-menu')).toBeNull()
    // футера нет: инструменты панели — только модератору
    expect(screen.queryByRole('button', { name: 'Пригласить гостя' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Завершить конференцию' })).not.toBeInTheDocument()
    expect(container.querySelector('.conference-participants-divider')).toBeNull()
    expect(api.updateParticipantRestrictions).not.toHaveBeenCalled()
    expect(api.endConference).not.toHaveBeenCalled()
  })

  it('у модератора кебаб у КАЖДОЙ строки — включая свою и другого модератора (равноправие Q21=A/№50)', () => {
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: moderatorRoom()[0] })
    lkc.useParticipants.mockReturnValue([
      ...moderatorRoom(),
      participant({
        identity: 'u:33333333-3333-4333-8333-333333333333:conn-3',
        name: 'carol',
        metadata: memberMetadata('moderator'),
        permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
      }),
    ])

    render(<ParticipantsPanel conferenceId="c-1" />)

    for (const name of ['alice', 'bob', 'carol']) {
      expect(
        within(rowOf(name)).getByRole('button', { name: `Действия с участником ${name}` }),
      ).toBeInTheDocument()
    }
  })

  it('футер модератора: разделитель + блок гостевой ссылки + «Завершить конференцию» (№52/№48)', () => {
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: moderatorRoom()[0] })
    lkc.useParticipants.mockReturnValue(moderatorRoom())

    const { container } = render(<ParticipantsPanel conferenceId="c-1" />)

    expect(container.querySelector('.conference-participants-divider')).not.toBeNull()
    expect(screen.getByRole('button', { name: 'Пригласить гостя' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Завершить конференцию' })).toBeInTheDocument()
  })

  it('состав меню от текущих ограничений: подписи «Отключить …» без ограничений, «Исключить» danger', () => {
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: moderatorRoom()[0] })
    lkc.useParticipants.mockReturnValue(moderatorRoom())

    render(<ParticipantsPanel conferenceId="c-1" />)
    openRowMenu('bob')

    const menu = screen.getByRole('menu')
    const labels = within(menu)
      .getAllByRole('menuitem')
      .map((item) => item.textContent)
    expect(labels).toEqual([
      'Отключить микрофон',
      'Отключить камеру',
      'Отключить экран',
      'Отключить все источники',
      'Назначить модератором',
      'Исключить',
    ])
    // №51 — danger-пункт с целью в доступном имени
    expect(within(menu).getByRole('menuitem', { name: 'Исключить bob' })).toHaveClass('danger')
  })

  it('подписи от SET-состояния: срезанный микрофон → «Включить микрофон»; мастер → точки «Включить …» и «Включить все источники»; цель-модератор → «Снять модератора»', () => {
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: moderatorRoom()[0] })
    lkc.useParticipants.mockReturnValue([
      moderatorRoom()[0],
      participant({
        identity: BOB_ID,
        name: 'bob',
        metadata: memberMetadata('participant'),
        // микрофон и экран срезаны точечно, камера разрешена
        permissions: { canPublish: true, canPublishSources: [CAMERA] },
      }),
    ])

    render(<ParticipantsPanel conferenceId="c-1" />)
    openRowMenu('bob')

    const menu = screen.getByRole('menu')
    const labels = within(menu)
      .getAllByRole('menuitem')
      .map((item) => item.textContent)
    expect(labels).toEqual([
      'Включить микрофон',
      'Отключить камеру',
      'Включить экран',
      'Отключить все источники',
      'Назначить модератором',
      'Исключить',
    ])

    // цель-модератор (своя строка) — снятие роли
    fireEvent.keyDown(document, { key: 'Escape' })
    openRowMenu('alice')
    const selfLabels = within(screen.getByRole('menu'))
      .getAllByRole('menuitem')
      .map((item) => item.textContent)
    expect(selfLabels).toContain('Снять модератора')
  })

  it('нажатый мастер (canPublish=false): все пункты источников — «Включить …», мастер — «Включить все источники»', () => {
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: moderatorRoom()[0] })
    lkc.useParticipants.mockReturnValue([
      moderatorRoom()[0],
      participant({
        identity: BOB_ID,
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])

    render(<ParticipantsPanel conferenceId="c-1" />)
    openRowMenu('bob')

    const labels = within(screen.getByRole('menu'))
      .getAllByRole('menuitem')
      .map((item) => item.textContent)
    expect(labels).toEqual([
      'Включить микрофон',
      'Включить камеру',
      'Включить экран',
      'Включить все источники',
      'Назначить модератором',
      'Исключить',
    ])
  })

  it('выбор пункта дергает api-клиент: №49 точечный — полное SET-состояние с переключённым полем; №50 — противоположная роль', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    api.updateParticipantRole.mockResolvedValue({})
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: moderatorRoom()[0] })
    lkc.useParticipants.mockReturnValue(moderatorRoom())

    render(<ParticipantsPanel conferenceId="c-1" />)

    // №49 точечный (мастер отжат — проекция точна)
    openRowMenu('bob')
    fireEvent.click(
      within(screen.getByRole('menu')).getByRole('menuitem', { name: 'Отключить микрофон' }),
    )
    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith('c-1', BOB_ID, {
        micRestricted: true,
        cameraRestricted: false,
        screenRestricted: false,
        speechRestricted: false,
      })
    })
    expect(api.updateParticipantRestrictions).toHaveBeenCalledWith('c-1', BOB_ID, {
      micRestricted: true,
      cameraRestricted: false,
      screenRestricted: false,
      speechRestricted: false,
    })

    // №50: назначение модератором
    openRowMenu('bob')
    fireEvent.click(
      within(screen.getByRole('menu')).getByRole('menuitem', { name: 'Назначить модератором' }),
    )
    await waitFor(() => {
      expect(api.updateParticipantRole).toHaveBeenCalledWith('c-1', BOB_ID, 'moderator')
    })
  })

  it('№51 «Исключить» — деструктивное: подтверждение обязательно, kick только после «Подтвердить»; «Отмена» — без вызова', async () => {
    api.kickParticipant.mockResolvedValue(undefined)
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: moderatorRoom()[0] })
    lkc.useParticipants.mockReturnValue(moderatorRoom())

    render(<ParticipantsPanel conferenceId="c-1" />)
    openRowMenu('bob')
    fireEvent.click(
      within(screen.getByRole('menu')).getByRole('menuitem', { name: 'Исключить bob' }),
    )

    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByText(/Исключить bob/)).toBeInTheDocument()
    expect(api.kickParticipant).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(api.kickParticipant).not.toHaveBeenCalled()

    openRowMenu('bob')
    fireEvent.click(
      within(screen.getByRole('menu')).getByRole('menuitem', { name: 'Исключить bob' }),
    )
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Подтвердить' }),
    )
    await waitFor(() => {
      expect(api.kickParticipant).toHaveBeenCalledWith('c-1', BOB_ID)
    })
  })

  it('панель следует broadcast-у роли live (Q9=A): снятие роли скрывает кебаби и футер, назначение возвращает', () => {
    const room = moderatorRoom()
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: room[0] })
    lkc.useParticipants.mockReturnValue(room)
    const { rerender } = render(<ParticipantsPanel conferenceId="c-1" />)
    expect(screen.getByRole('button', { name: 'Завершить конференцию' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /Действия с участником/ })).toHaveLength(2)

    // UpdateParticipant §4: broadcast metadata снятия роли самому себе —
    // список остаётся, инструменты исчезают
    const demoted = { ...room[0], metadata: memberMetadata('participant') }
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: demoted })
    lkc.useParticipants.mockReturnValue([demoted, room[1]])
    rerender(<ParticipantsPanel conferenceId="c-1" />)
    expect(screen.queryByRole('button', { name: /Действия с участником/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Завершить конференцию' })).not.toBeInTheDocument()
    expect(screen.getByText('Участники (2)')).toBeInTheDocument()

    // повторное назначение — инструменты возвращаются
    const promoted = { ...room[0], metadata: memberMetadata('moderator') }
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: promoted })
    lkc.useParticipants.mockReturnValue([promoted, room[1]])
    rerender(<ParticipantsPanel conferenceId="c-1" />)
    expect(screen.getByRole('button', { name: 'Завершить конференцию' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /Действия с участником/ })).toHaveLength(2)
  })
})

describe('restrictionMarks — проекция grants (009 US3 T094)', () => {
  const NO_MARKS = {
    micRestricted: false,
    cameraRestricted: false,
    screenRestricted: false,
    speechRestricted: false,
  }

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('canPublish=false → ВСЕ ЧЕТЫРЕ метки true: «право слова» и все-три-точечных grants неразличимы — эффективное состояние одинаково', () => {
    expect(restrictionMarks({ canPublish: false, canPublishSources: [] })).toEqual({
      micRestricted: true,
      cameraRestricted: true,
      screenRestricted: true,
      speechRestricted: true,
    })
  })

  it('canPublish=true + пустой набор источников → ограничений нет (защитная ветка: пустой canPublishSources в LiveKit означает «все разрешены»)', () => {
    expect(restrictionMarks({ canPublish: true, canPublishSources: [] })).toEqual(NO_MARKS)
  })

  it('точечные кейсы без регресса: отсутствующие источники помечаются (Q22=A)', () => {
    expect(restrictionMarks({ canPublish: true, canPublishSources: [CAMERA] })).toEqual({
      micRestricted: true,
      cameraRestricted: false,
      screenRestricted: true,
      speechRestricted: false,
    })
  })

  it('permissions нет — ограничений нет (нейтральный дефолт)', () => {
    expect(restrictionMarks(undefined)).toEqual(NO_MARKS)
  })
})

describe('ParticipantsPanel — аватары строк (009 US1 T073, «как в контактах»)', () => {
  const ALICE = '11111111-1111-4111-8111-111111111111'

  /** Аватар строки по метке имени (первая строка списка). */
  function rowAvatar(name: string): HTMLElement {
    const row = screen.getByText(name).closest('li')
    if (row === null) {
      throw new Error(`строка участника «${name}» не найдена`)
    }
    const avatar = row.querySelector<HTMLElement>('.avatar')
    if (avatar === null) {
      throw new Error('аватар строки отсутствует')
    }
    return avatar
  }

  function avatarInitials(avatar: HTMLElement): string {
    return avatar.querySelector('span')?.textContent ?? ''
  }

  beforeEach(() => {
    lkc.useParticipants.mockReset()
    lkc.useLocalParticipant.mockReset()
    lkc.useLocalParticipant.mockReturnValue({
      localParticipant: participant({
        identity: `u:${ALICE}:22222222-2222-4222-8222-222222222222`,
        name: 'zoe',
        metadata: memberMetadata('participant'),
        isLocal: true,
      }),
    })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('член с профилем — инициалы цепочки alias → displayName → username, цвет colorOf(username)', () => {
    // identity минта №47: connId — серверный UUID (парсер — зеркало ConferenceIdentity)
    const connId = '22222222-2222-4222-8222-222222222222'
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: `u:${ALICE}:${connId}`,
        name: 'alice',
        metadata: memberMetadata('moderator'),
      }),
    ])
    const profiles = new Map([
      [ALICE, { username: 'alice', displayName: 'Мария Соколова', alias: 'Маша' }],
    ])

    render(
      <ParticipantProfilesContext.Provider value={profiles}>
        <ParticipantsPanel conferenceId="c-1" />
      </ParticipantProfilesContext.Provider>,
    )

    const avatar = rowAvatar('alice')
    // цепочка: alias «Маша» → инициалы «МА» (как в «Контактах»)
    expect(avatarInitials(avatar)).toBe('МА')
    // цвет — детерминированный от username (переименование не перекрашивает)
    expect(avatar.style.getPropertyValue('--av')).toBe(colorOf('alice'))
  })

  it('гость — инициалы из введённого имени №55, цвет от имени', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'g:22222222-2222-4222-8222-222222222222',
        name: 'Виктор',
        metadata: memberMetadata('participant', true),
      }),
    ])

    render(
      <ParticipantProfilesContext.Provider value={new Map()}>
        <ParticipantsPanel conferenceId="c-1" />
      </ParticipantProfilesContext.Provider>,
    )

    const avatar = rowAvatar('Виктор')
    expect(avatarInitials(avatar)).toBe('ВИ')
    expect(avatar.style.getPropertyValue('--av')).toBe(colorOf('Виктор'))
  })

  it('член вне снимка профилей — фолбэк инициалов из participant.name (сбой догрузки/позже снимка)', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: `u:${ALICE}:22222222-2222-4222-8222-222222222222`,
        name: 'alice',
        metadata: memberMetadata('participant'),
      }),
    ])

    // провайдер без записи об участнике (догрузка не удалась/не сошлась)
    render(
      <ParticipantProfilesContext.Provider value={new Map()}>
        <ParticipantsPanel conferenceId="c-1" />
      </ParticipantProfilesContext.Provider>,
    )

    const avatar = rowAvatar('alice')
    expect(avatarInitials(avatar)).toBe('AL')
    expect(avatar.style.getPropertyValue('--av')).toBe(colorOf('alice'))
  })

  it('без провайдера (гость-наблюдатель GuestJoinPage, FR-010) — все аватары фолбэком из name', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: `u:${ALICE}:22222222-2222-4222-8222-222222222222`,
        name: 'alice',
        metadata: memberMetadata('participant'),
      }),
    ])

    render(<ParticipantsPanel conferenceId="c-1" />)

    expect(avatarInitials(rowAvatar('alice'))).toBe('AL')
  })

  it('регресс: аватар не ломает строку — пометка «гость» и ключ модерации data-participant-identity на месте', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'g:22222222-2222-4222-8222-222222222222',
        name: 'Виктор',
        metadata: memberMetadata('participant', true),
      }),
    ])

    render(
      <ParticipantProfilesContext.Provider value={new Map()}>
        <ParticipantsPanel conferenceId="c-1" />
      </ParticipantProfilesContext.Provider>,
    )

    const row = screen.getByText('Виктор').closest('li')
    expect(row).toHaveAttribute(
      'data-participant-identity',
      'g:22222222-2222-4222-8222-222222222222',
    )
    expect(screen.getByText('гость')).toBeInTheDocument()
    expect(row?.querySelector('.avatar')).not.toBeNull()
  })
})
