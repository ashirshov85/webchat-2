import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ParticipantsPanel, { restrictionMarks } from '../ParticipantsPanel'
import { ParticipantProfilesContext } from '../../profiles'
import { colorOf } from '../../../ui/avatar'

/**
 * 009, US3, T041 — панель участников (FR-008): список подключённых из
 * медиа-плоскости LiveKit (useParticipants: [local, ...remote]) —
 * identity/name, роль/«гость» из metadata токена (livekit-integration
 * §3), online, индикация ограничений по фактическим grants (canPublish/
 * canPublishSources — broadcast UpdateParticipant, §4). Примитивы
 * @livekit/components-react заменены фейком: предмет теста — проекция
 * состояния участника LiveKit в строку панели (критерий T041 — Vitest,
 * набор T043). Пометка «гость» — T051 (US4): бейдж по
 * metadata.guest=true токена №55 и отображение гостевого имени.
 */

/** Wire-константы TrackSource (@livekit/protocol) — как в компоненте. */
const CAMERA = 1
const MICROPHONE = 2
const SCREEN_SHARE = 3
const SCREEN_SHARE_AUDIO = 4

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
}))

vi.mock('@livekit/components-react', () => ({
  useParticipants: lkc.useParticipants,
}))

const ALL_SOURCES = [CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO]

function participant(overrides: Partial<FakeParticipant> & { identity: string }): FakeParticipant {
  return { isLocal: false, name: 'Имя', ...overrides }
}

/** Метаданные члена чата/гостя по контракту §3. */
function memberMetadata(role: 'moderator' | 'participant', guest = false): string {
  return JSON.stringify(guest ? { role, guest, inviteId: 'i-1' } : { role, guest })
}

describe('ParticipantsPanel (009 US3 T041)', () => {
  beforeEach(() => {
    lkc.useParticipants.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('отражает identity/name/роль/online участников из LiveKit: локальный — «вы», удалённые — с identity', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'u:11111111-1111-1111-1111-111111111111:conn-1',
        name: 'alice',
        metadata: memberMetadata('moderator'),
        isLocal: true,
        permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
      }),
      participant({
        identity: 'u:22222222-2222-4222-8222-222222222222:conn-2',
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
      }),
      participant({
        // без имени — метка строки сам identity (как тайлы комнаты, T027)
        identity: 'u:33333333-3333-4333-8333-333333333333:conn-3',
        name: '',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
      }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.getByText('Участники (3)')).toBeInTheDocument()
    // локальный: имя + «вы», БЕЗ identity (блок joined уже показывает его)
    expect(screen.getByText('alice')).toBeInTheDocument()
    expect(screen.getByText('вы')).toBeInTheDocument()
    expect(
      screen.queryByText('u:11111111-1111-1111-1111-111111111111:conn-1'),
    ).not.toBeInTheDocument()
    // удалённые: имя/identity-метка + identity (ключ модерации №49–№51)
    expect(screen.getByText('bob')).toBeInTheDocument()
    expect(screen.getByText('u:22222222-2222-4222-8222-222222222222:conn-2')).toBeInTheDocument()
    expect(screen.getByText('u:33333333-3333-4333-8333-333333333333:conn-3')).toBeInTheDocument()
    // роли из metadata и online-индикация каждой строки
    expect(screen.getByText('модератор')).toBeInTheDocument()
    expect(screen.getAllByText('участник')).toHaveLength(2)
    expect(screen.getAllByText('в сети')).toHaveLength(3)
  })

  it('роль по умолчанию — участник: metadata отсутствует или битый JSON не рушат список', () => {
    lkc.useParticipants.mockReturnValue([
      participant({ identity: 'u:1:c1', name: 'no-meta' }),
      participant({ identity: 'u:2:c2', name: 'broken-meta', metadata: '{role":' }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.getAllByText('участник')).toHaveLength(2)
    expect(screen.queryByText('модератор')).not.toBeInTheDocument()
  })

  it('бейдж «гость» у участников с metadata.guest=true, у членов чата бейджа нет (T051)', () => {
    lkc.useParticipants.mockReturnValue([
      participant({ identity: 'u:1:c1', name: 'alice', metadata: memberMetadata('moderator') }),
      participant({
        // §3: {"role":"participant","guest":true,"inviteId"} — токен №55
        identity: 'g:conn-guest-1',
        name: 'Виктор',
        metadata: memberMetadata('participant', true),
      }),
    ])

    render(<ParticipantsPanel />)

    // ровно один бейдж — у гостя; член чата (guest:false) без пометки
    expect(screen.getAllByText('гость')).toHaveLength(1)
    // имя гостя — name токена №55 (введено на GuestJoinPage), метка строки
    expect(screen.getByText('Виктор')).toBeInTheDocument()
    // identity гостя — ключ модерации №49–№51, как у членов чата
    expect(screen.getByText('g:conn-guest-1')).toBeInTheDocument()
  })

  it('гость без имени: метка строки — identity g:{connId} (fallback как у тайлов, T051)', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'g:conn-guest-2',
        name: '',
        metadata: memberMetadata('participant', true),
      }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.getAllByText('гость')).toHaveLength(1)
    expect(screen.getByText('g:conn-guest-2')).toBeInTheDocument()
  })

  it('«Все источники» (canPublish=false) — единая индикация без публикаций, точечные источники не помечаются (Q4=A)', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'u:1:c1',
        name: 'silenced',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.getByText('все источники отключены')).toBeInTheDocument()
    expect(screen.queryByText('микрофон отключён')).not.toBeInTheDocument()
    expect(screen.queryByText('камера отключена')).not.toBeInTheDocument()
    expect(screen.queryByText('экран отключён')).not.toBeInTheDocument()
  })

  it('точечные ограничения: отсутствующие источники в canPublishSources помечаются (Q22=A)', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'u:1:c1',
        name: 'partial',
        metadata: memberMetadata('participant'),
        // разрешена только камера — микрофон и экран срезаны модератором
        permissions: { canPublish: true, canPublishSources: [CAMERA] },
      }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.getByText('микрофон отключён')).toBeInTheDocument()
    expect(screen.getByText('экран отключён')).toBeInTheDocument()
    expect(screen.queryByText('камера отключена')).not.toBeInTheDocument()
    expect(screen.queryByText('все источники отключены')).not.toBeInTheDocument()
  })

  it('без ограничений индикаций нет: полный набор источников, пустой набор (все разрешены) и отсутствие permissions', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'u:1:c1',
        name: 'full',
        permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
      }),
      participant({
        identity: 'u:2:c2',
        name: 'empty-is-all',
        permissions: { canPublish: true, canPublishSources: [] },
      }),
      participant({ identity: 'u:3:c3', name: 'no-permissions' }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.queryByText('все источники отключены')).not.toBeInTheDocument()
    expect(screen.queryByText('микрофон отключён')).not.toBeInTheDocument()
    expect(screen.queryByText('камера отключена')).not.toBeInTheDocument()
    expect(screen.queryByText('экран отключён')).not.toBeInTheDocument()
  })

  it('обновляется живьём: broadcast роли (UpdateParticipant metadata) меняет строку без перемонтирования', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'u:9:c9',
        name: 'bob',
        metadata: memberMetadata('participant'),
        isLocal: true,
      }),
    ])
    const { rerender } = render(<ParticipantsPanel />)
    expect(screen.getByText('участник')).toBeInTheDocument()

    // UpdateParticipant §4: metadata-обновление → новый массив от хука
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'u:9:c9',
        name: 'bob',
        metadata: memberMetadata('moderator'),
        isLocal: true,
      }),
    ])
    rerender(<ParticipantsPanel />)

    expect(screen.getByText('модератор')).toBeInTheDocument()
    expect(screen.queryByText('участник')).not.toBeInTheDocument()
  })
})

describe('restrictionMarks — проекция grants (009 US3 T094)', () => {
  const NO_MARKS = {
    micRestricted: false,
    cameraRestricted: false,
    screenRestricted: false,
    speechRestricted: false,
  }

  beforeEach(() => {
    lkc.useParticipants.mockReset()
  })

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

  it('строка панели при canPublish=false — единая пометка «все источники отключены» (регресс-пин T094: all-four-true не плодит точечные пометки)', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'u:1:c1',
        name: 'all-points',
        metadata: memberMetadata('participant'),
        // все три точечных → после фикса grants canPublish=false
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.getByText('все источники отключены')).toBeInTheDocument()
    expect(screen.queryByText('микрофон отключён')).not.toBeInTheDocument()
    expect(screen.queryByText('камера отключена')).not.toBeInTheDocument()
    expect(screen.queryByText('экран отключён')).not.toBeInTheDocument()
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
        isLocal: true,
      }),
    ])
    const profiles = new Map([
      [ALICE, { username: 'alice', displayName: 'Мария Соколова', alias: 'Маша' }],
    ])

    render(
      <ParticipantProfilesContext.Provider value={profiles}>
        <ParticipantsPanel />
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
        <ParticipantsPanel />
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
        <ParticipantsPanel />
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

    render(<ParticipantsPanel />)

    expect(avatarInitials(rowAvatar('alice'))).toBe('AL')
  })

  it('регресс: аватар не ломает пометки строки — «гость»/роль/identity-ключ модерации на месте', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'g:22222222-2222-4222-8222-222222222222',
        name: 'Виктор',
        metadata: memberMetadata('participant', true),
      }),
    ])

    render(<ParticipantsPanel />)

    const row = screen.getByText('Виктор').closest('li')
    expect(row).toHaveAttribute(
      'data-participant-identity',
      'g:22222222-2222-4222-8222-222222222222',
    )
    expect(screen.getByText('гость')).toBeInTheDocument()
    expect(screen.getByText('участник')).toBeInTheDocument()
    expect(screen.getByText('g:22222222-2222-4222-8222-222222222222')).toBeInTheDocument()
    expect(row?.querySelector('.avatar')).not.toBeNull()
  })
})
