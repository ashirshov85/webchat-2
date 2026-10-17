import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ParticipantsPanel from '../ParticipantsPanel'

/**
 * 009, US3, T041 — панель участников (FR-008): список подключённых из
 * медиа-плоскости LiveKit (useParticipants: [local, ...remote]) —
 * identity/name, роль/«гость» из metadata токена (livekit-integration
 * §3), online, индикация ограничений по фактическим grants (canPublish/
 * canPublishSources — broadcast UpdateParticipant, §4). Примитивы
 * @livekit/components-react заменены фейком: предмет теста — проекция
 * состояния участника LiveKit в строку панели (критерий T041 — Vitest,
 * набор T043).
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

  it('помечает гостя по metadata.guest (поле готово — наполняется в US4/T051)', () => {
    lkc.useParticipants.mockReturnValue([
      participant({ identity: 'u:1:c1', name: 'alice', metadata: memberMetadata('moderator') }),
      participant({
        identity: 'g:conn-guest-1',
        name: 'Виктор',
        metadata: memberMetadata('participant', true),
      }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.getByText('гость')).toBeInTheDocument()
    expect(screen.getByText('Виктор')).toBeInTheDocument()
  })

  it('«право слова» (canPublish=false) — единая индикация без публикаций, точечные источники не помечаются (Q4=A)', () => {
    lkc.useParticipants.mockReturnValue([
      participant({
        identity: 'u:1:c1',
        name: 'silenced',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])

    render(<ParticipantsPanel />)

    expect(screen.getByText('право слова')).toBeInTheDocument()
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
    expect(screen.queryByText('право слова')).not.toBeInTheDocument()
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

    expect(screen.queryByText('право слова')).not.toBeInTheDocument()
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
