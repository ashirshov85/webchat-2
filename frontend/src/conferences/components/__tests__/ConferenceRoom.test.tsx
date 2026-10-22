import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { JoinTicket } from '../../api/conferences'

/**
 * 009, US1, T029 (+ T032, US2, Q6=A) — ConferenceRoom (T027,
 * FR-004/FR-006): подключение по JoinTicket (wsUrl+token), стартовая
 * публикация по mode/initialMedia, отказ устройств не рушит вход
 * («только слушаю») — понятное предупреждение и снятие при успешном
 * повторном включении уже в звонке (LocalTrackPublished, тумблеры T028);
 * серверные разрывы — терминальные без авто-возврата, полный разрыв
 * после успешного подключения — цепочка реконнекта в окне
 * reconnect-window со свежими тикетами №47 (T060, FR-014); livekit-client и примитивы
 * @livekit/components-react заменены фейками: предмет теста — связка
 * нашей комнаты с SDK-контрактом (connect/disconnect/события/сеттеры).
 *
 * 009, US5, T061 — полные сценарии окна реконнекта T060 (Q15=B):
 * индикация потери связи «переподключение…» и КОНТРОЛИРУЕМОЕ
 * завершение по истечении reconnect-window («lost», ретраи минта
 * прекращаются); тост предупреждения о лимите длительности по
 * data-пакету topic `conference.warning` (T056 → T060, FR-015,
 * Q16=B) с ручным закрытием и нейтральным игнором чужих тем/битого
 * payload.
 *
 * 009, US1, T078 — автопин тайла демонстрации экрана поверх пин-
 * раскладки T074: появление НОВОГО screen-ref'а при пустом пине
 * разворачивает тайл на всю медиа-зону без ручного клика (у всех
 * участников, включая собственную демонстрацию); правило «только
 * при пустом пине»: ручной пин не перебивается, вторая демонстрация
 * не переключает первую, ручное сворачивание не зацикливает автопин.
 *
 * 009, US1, T079 — полноэкранный пин-оверлей: живой пин навешивает
 * маркер-класс conference-room-pinned на корневой div комнаты
 * (поднятие pinnedTileKey в контур комнаты), сворачивание/уход цели/
 * завершение демонстрации снимают; инварианты T074/T078 не меняются,
 * children (MediaControls) остаются в DOM под RoomContext —
 * позиционирование поверх тайла — CSS (conferences.css).
 *
 * 009, US1, T082 — клавиатурный выход из пин-оверлея: keydown Escape
 * при живом пине сворачивает закреплённый тайл (семантика кнопки
 * «Свернуть»); слушатель на window живёт ТОЛЬКО в пин-состоянии и
 * снимается при сворачивании/размонтировании (пустой пин — Esc no-op);
 * Esc-сворачивание автопина T078 при живой демонстрации не повторяет
 * автопин, ручной пин после Esc работает; Esc не завершает звонок.
 *
 * 009, US1, T084 — слитый тайл участника: камера/демонстрация/аватар
 * одного участника — ОДИН элемент (React-ключ identity, приоритет
 * screen > camera > placeholder): живое переключение камера↔экран↔
 * заглушка без размонтирования тайла и перестройки сетки; пин-ключ
 * identity переживает переключения (сброс — ТОЛЬКО ручное сворачивание
 * или уход участника; прежний сброс завершением демонстрации T074/T078
 * отменён); автопин T078 ключом identity; подпись — имя + видимый бейдж
 * «демонстрирует экран» (формат «Экран — имя» упразднён).
 */

const lk = vi.hoisted(() => {
  const ConnectionState = { Disconnected: 0, Connected: 2 } as const
  const DisconnectReason = {
    UNKNOWN: 0,
    CLIENT_INITIATED: 1,
    PARTICIPANT_REMOVED: 11,
    ROOM_DELETED: 12,
    ROOM_CLOSED: 13,
    DUPLICATE_IDENTITY: 14,
    USER_REJECTED: 15,
  } as const
  const RoomEvent = {
    ParticipantConnected: 'participantConnected',
    Reconnecting: 'reconnecting',
    Reconnected: 'reconnected',
    Disconnected: 'disconnected',
    DataReceived: 'dataReceived',
    LocalTrackPublished: 'localTrackPublished',
    ActiveDeviceChanged: 'activeDeviceChanged',
  } as const
  const Track = {
    Source: { Camera: 'camera', Microphone: 'microphone', ScreenShare: 'screen_share' },
  }

  /** Fake LiveKit Room: контракт методов/событий, используемых ConferenceRoom. */
  class FakeRoom {
    static readonly instances: FakeRoom[] = []
    /** Поведение connect по умолчанию для НОВЫХ вызовов (задаётся тестом до монтирования). */
    static defaultConnectImpl: ((wsUrl: string, token: string) => Promise<void>) | null = null
    readonly constructorOptions: unknown
    state: number = ConnectionState.Disconnected
    readonly connectCalls: { wsUrl: string; token: string }[] = []
    readonly micCalls: boolean[] = []
    readonly cameraCalls: boolean[] = []
    /** switchActiveDevice (useDevices — стартовый выбор pre-join, T034). */
    readonly switchCalls: { kind: string; deviceId: string }[] = []
    disconnectCalls = 0
    /** Управляемое тестом поведение connect конкретной комнаты (приоритет над default). */
    connectImpl: ((wsUrl: string, token: string) => Promise<void>) | null = null
    micImpl: ((enabled: boolean) => Promise<void>) | null = null
    cameraImpl: ((enabled: boolean) => Promise<void>) | null = null
    private readonly listeners = new Map<string, Set<(...args: unknown[]) => void>>()

    constructor(options: unknown) {
      this.constructorOptions = options
      FakeRoom.instances.push(this)
    }

    on(event: string, listener: (...args: unknown[]) => void): this {
      let set = this.listeners.get(event)
      if (set === undefined) {
        set = new Set()
        this.listeners.set(event, set)
      }
      set.add(listener)
      return this
    }

    off(event: string, listener: (...args: unknown[]) => void): this {
      this.listeners.get(event)?.delete(listener)
      return this
    }

    switchActiveDevice(kind: string, deviceId: string): Promise<boolean> {
      this.switchCalls.push({ kind, deviceId })
      return Promise.resolve(true)
    }

    /** Вариадический emit: DataReceived несёт (payload, participant, kind, topic). */
    emit(event: string, ...args: unknown[]): void {
      this.listeners.get(event)?.forEach((listener) => {
        listener(...args)
      })
    }

    removeAllListeners(): void {
      this.listeners.clear()
    }

    async connect(wsUrl: string, token: string): Promise<void> {
      this.connectCalls.push({ wsUrl, token })
      const impl = this.connectImpl ?? FakeRoom.defaultConnectImpl
      if (impl !== null) {
        await impl(wsUrl, token)
      }
      this.state = ConnectionState.Connected
    }

    disconnect(): Promise<void> {
      this.disconnectCalls++
      this.state = ConnectionState.Disconnected
      return Promise.resolve()
    }

    get localParticipant(): {
      setMicrophoneEnabled: (enabled: boolean) => Promise<void>
      setCameraEnabled: (enabled: boolean) => Promise<void>
    } {
      return {
        setMicrophoneEnabled: async (enabled) => {
          this.micCalls.push(enabled)
          if (this.micImpl !== null) {
            await this.micImpl(enabled)
          }
        },
        setCameraEnabled: async (enabled) => {
          this.cameraCalls.push(enabled)
          if (this.cameraImpl !== null) {
            await this.cameraImpl(enabled)
          }
        },
      }
    }
  }

  return { ConnectionState, DisconnectReason, RoomEvent, Track, FakeRoom }
})

vi.mock('livekit-client', () => ({
  Room: lk.FakeRoom,
  RoomEvent: lk.RoomEvent,
  ConnectionState: lk.ConnectionState,
  DisconnectReason: lk.DisconnectReason,
  Track: lk.Track,
}))

/** Состояние треков/спикеров useTracks/useSpeakingParticipants (управляется тестом). */
const lkc = vi.hoisted(() => ({
  trackRefs: [] as ReadonlyArray<{
    participant: { identity: string; name?: string }
    source: string
    publication?: { trackSid: string }
  }>,
  speakers: [] as ReadonlyArray<{ identity: string }>,
}))

vi.mock('@livekit/components-react', () => ({
  RoomContext: {
    Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  },
  useTracks: (selectors: unknown) => {
    const list = Array.isArray(selectors) ? selectors : [selectors]
    const wanted = new Set(
      list.map((selector) =>
        typeof selector === 'string' ? selector : (selector as { source: string }).source,
      ),
    )
    return lkc.trackRefs.filter((ref) => wanted.has(ref.source))
  },
  useSpeakingParticipants: () => lkc.speakers,
  // зеркало реального гварда ядра: placeholder-рефы не несут publication
  isTrackReference: (ref: { publication?: unknown }) => ref.publication !== undefined,
  RoomAudioRenderer: () => <div data-testid="lk-audio-renderer" />,
  VideoTrack: () => <video />,
}))

import ConferenceRoom from '../ConferenceRoom'

function ticket(token: string, overrides: Partial<JoinTicket> = {}): JoinTicket {
  return {
    token,
    wsUrl: 'wss://media.example.com',
    identity: 'u:11111111-1111-1111-1111-111111111111:conn-1',
    name: 'alice',
    expiresAt: '2026-10-17T12:15:00.000Z',
    ...overrides,
  }
}

function lastRoom(): InstanceType<typeof lk.FakeRoom> {
  const room = lk.FakeRoom.instances[lk.FakeRoom.instances.length - 1]
  if (room === undefined) {
    throw new Error('FakeRoom не создан — ConferenceRoom не монтировался')
  }
  return room
}

describe('ConferenceRoom (009 US1 T027/T029)', () => {
  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('подключается по JoinTicket (wsUrl+token) и публикует микрофон+камеру для mode=video', async () => {
    const refreshTicket = vi.fn()
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
      />,
    )

    await waitFor(() => {
      expect(lastRoom().connectCalls).toEqual([
        { wsUrl: 'wss://media.example.com', token: 'jwt-1' },
      ])
    })
    await waitFor(() => {
      expect(lastRoom().micCalls).toEqual([true])
    })
    await waitFor(() => {
      expect(lastRoom().cameraCalls).toEqual([true])
    })
    // connected — статус-строка подключения исчезает
    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull()
    })
  })

  it('mode=audio: старт — только микрофон (камера не публикуется, Q3=A soft)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="audio"
        refreshTicket={vi.fn()}
      />,
    )

    await waitFor(() => {
      expect(lastRoom().micCalls).toEqual([true])
    })
    await waitFor(() => {
      expect(lastRoom().cameraCalls).toEqual([false])
    })
  })

  it('initialMedia {оба выкл} — вход «только слушаю», публикаций нет (FR-006)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        initialMedia={{ microphone: false, camera: false }}
        refreshTicket={vi.fn()}
      />,
    )

    await waitFor(() => {
      expect(lastRoom().connectCalls).toHaveLength(1)
    })
    await waitFor(() => {
      expect(lastRoom().micCalls).toEqual([false])
    })
    await waitFor(() => {
      expect(lastRoom().cameraCalls).toEqual([false])
    })
  })

  it('стартовый выбор устройств pre-join применяется при появлении комнаты (T034, useDevices initial)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        initialMedia={{ microphone: true, camera: false }}
        initialDevices={{
          audioInput: 'mic-2',
          videoInput: 'cam-2',
          audioOutput: 'spk-2',
        }}
        refreshTicket={vi.fn()}
      />,
    )

    // комната появилась — выбор применён ДО первой публикации (null-слоты
    // не адресуются: «По умолчанию» остаётся браузерному дефолту)
    await waitFor(() => {
      expect(lastRoom().switchCalls).toEqual([
        { kind: 'audioinput', deviceId: 'mic-2' },
        { kind: 'videoinput', deviceId: 'cam-2' },
        { kind: 'audiooutput', deviceId: 'spk-2' },
      ])
    })
    await waitFor(() => {
      expect(lastRoom().micCalls).toEqual([true])
    })
    await waitFor(() => {
      expect(lastRoom().cameraCalls).toEqual([false])
    })
  })

  it('отказ браузера в доступе к устройствам не рушит вход — слушатель + понятное предупреждение (Q6=A, T032)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    room.micImpl = () => Promise.reject(new Error('NotAllowedError'))
    room.cameraImpl = () => Promise.reject(new Error('NotFoundError'))

    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })
    await waitFor(() => {
      expect(room.micCalls).toEqual([true])
    })
    await waitFor(() => {
      expect(room.cameraCalls).toEqual([true])
    })
    // вход сохранён: подключение не разорвано; отказ — предупреждение
    // «только слушаю» с подсказкой повторного запроса в звонке
    expect(
      await screen.findByText('Нет доступа к камере и микрофону — можно включить их в звонке'),
    ).toBeVisible()
    expect(room.disconnectCalls).toBe(0)
  })

  it('отказ только микрофона (mode=audio) — предупреждение про микрофон, вход сохранён (T032)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="audio"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    room.micImpl = () => Promise.reject(new Error('NotAllowedError'))

    await waitFor(() => {
      expect(room.micCalls).toEqual([true])
    })
    expect(
      await screen.findByText('Нет доступа к микрофону — можно включить его в звонке'),
    ).toBeVisible()
    // камера не запрашивалась (mode=audio) — её части в предупреждении нет
    expect(screen.queryByText(/Нет доступа к камере/)).toBeNull()
    expect(room.disconnectCalls).toBe(0)
  })

  it('намеренно выключенный старт не считается отказом — предупреждения нет (FR-006)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        initialMedia={{ microphone: false, camera: false }}
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()

    await waitFor(() => {
      expect(room.micCalls).toEqual([false])
    })
    await waitFor(() => {
      expect(room.cameraCalls).toEqual([false])
    })
    await waitFor(() => {
      expect(screen.queryByText(/Нет доступа/)).toBeNull()
    })
  })

  it('повторный запрос уже в звонке: успешная публикация (LocalTrackPublished) снимает предупреждение слота (Q6=A, T032)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    room.micImpl = () => Promise.reject(new Error('NotAllowedError'))
    room.cameraImpl = () => Promise.reject(new Error('NotAllowedError'))

    await waitFor(() => {
      expect(room.cameraCalls).toEqual([true])
    })
    // оба слота недоступны — объединённое предупреждение
    expect(
      await screen.findByText('Нет доступа к камере и микрофону — можно включить их в звонке'),
    ).toBeVisible()

    // пользователь повторно запросил микрофон тумблером (T028) — успех:
    // LocalTrackPublished микрофона снимает только свою часть
    room.emit(lk.RoomEvent.LocalTrackPublished, { source: lk.Track.Source.Microphone })
    expect(
      await screen.findByText('Нет доступа к камере — можно включить её в звонке'),
    ).toBeVisible()

    // успешное включение камеры убирает предупреждение полностью
    room.emit(lk.RoomEvent.LocalTrackPublished, { source: lk.Track.Source.Camera })
    await waitFor(() => {
      expect(screen.queryByText(/Нет доступа/)).toBeNull()
    })

    // публикация экрана предупреждений не порождает и не трогает
    room.emit(lk.RoomEvent.LocalTrackPublished, { source: lk.Track.Source.ScreenShare })
    expect(screen.queryByText(/Нет доступа/)).toBeNull()
  })

  it('серверный разрыв (PARTICIPANT_REMOVED) — терминальный «Вы отключены», без refreshTicket', async () => {
    const refreshTicket = vi.fn()
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })
    await waitFor(() => {
      expect(room.micCalls).toHaveLength(1)
    })

    room.emit(lk.RoomEvent.Disconnected, lk.DisconnectReason.PARTICIPANT_REMOVED)

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('Вы отключены от конференции')
    expect(refreshTicket).not.toHaveBeenCalled()
    expect(room.connectCalls).toHaveLength(1)
  })

  it('полный разрыв после подключения: refreshTicket (№47) → свежая комната с новым тикетом (FR-014)', async () => {
    const ticketTwo = ticket('jwt-2', {
      identity: 'u:11111111-1111-1111-1111-111111111111:conn-2',
    })
    const refreshTicket = vi.fn().mockResolvedValue(ticketTwo)
    const view = render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
      />,
    )
    const first = lastRoom()
    await waitFor(() => {
      expect(first.connectCalls).toHaveLength(1)
    })

    first.state = lk.ConnectionState.Disconnected
    first.emit(lk.RoomEvent.Disconnected)

    await waitFor(() => {
      expect(refreshTicket).toHaveBeenCalledTimes(1)
    })
    await screen.findByText('Соединение потеряно — переподключение…')

    // повторный разрыв той же мёртвой комнаты — дубликат цепочки
    // (окно реконнекта T060): тикет уже получен, второго минта нет,
    // фаза остаётся «переподключение…» до смены тикета родителем
    first.emit(lk.RoomEvent.Disconnected)
    expect(refreshTicket).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('Соединение потеряно — переподключение…')

    // родитель перевыдал тикет (смена ticket.token) — СВЕЖАЯ комната
    // подключается новым токеном, прежняя корректно разорвана
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticketTwo}
        mode="video"
        refreshTicket={refreshTicket}
      />,
    )

    const second = lastRoom()
    expect(second).not.toBe(first)
    await waitFor(() => {
      expect(second.connectCalls).toEqual([{ wsUrl: 'wss://media.example.com', token: 'jwt-2' }])
    })
    expect(first.disconnectCalls).toBeGreaterThanOrEqual(1)
    await waitFor(() => {
      expect(screen.queryByRole('status')).toBeNull()
    })
  })

  it('сбой подключения до успеха — «Не удалось подключиться к медиа-серверу», без refreshTicket', async () => {
    lk.FakeRoom.defaultConnectImpl = () => Promise.reject(new Error('signal failed'))
    try {
      const refreshTicket = vi.fn()
      render(
        <ConferenceRoom
          conferenceId="c-1"
          ticket={ticket('jwt-1')}
          mode="video"
          refreshTicket={refreshTicket}
        />,
      )
      const room = lastRoom()

      const status = await screen.findByRole('status')
      expect(status).toHaveTextContent('Не удалось подключиться к медиа-серверу')
      expect(refreshTicket).not.toHaveBeenCalled()
      expect(room.connectCalls).toHaveLength(1)
    } finally {
      lk.FakeRoom.defaultConnectImpl = null
    }
  })

  it('смена тикета при живой комнате — полное переподключение: свежая комната, прежняя разорвана', async () => {
    const view = render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const first = lastRoom()
    await waitFor(() => {
      expect(first.connectCalls).toHaveLength(1)
    })

    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-2')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )

    const second = lastRoom()
    expect(second).not.toBe(first)
    await waitFor(() => {
      expect(second.connectCalls).toEqual([{ wsUrl: 'wss://media.example.com', token: 'jwt-2' }])
    })
    expect(first.disconnectCalls).toBeGreaterThanOrEqual(1)
    expect(first.connectCalls).toHaveLength(1)
  })

  it('размонтирование рвёт подключение (best-effort disconnect)', async () => {
    const view = render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })

    view.unmount()

    expect(room.disconnectCalls).toBeGreaterThanOrEqual(1)
  })

  it('медиа-слой: единая сетка слитых тайлов — экран участника в его же тайле (T078 автопин сохранён)', async () => {
    lkc.trackRefs = [
      // камера не опубликована — тайл-заглушка с аватаром и именем (T073)
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )

    const tiles = await screen.findByRole('region', { name: 'Участники конференции' })
    // подпись тайла — отображаемое имя участника (participantLabel)
    expect(tiles).toHaveTextContent('alice')
    expect(tiles.querySelector('.avatar')).not.toBeNull()
    // демонстрация с момента появления развёрнута автопином (T078) в
    // СЛИТОМ тайле bob (T084): имя + бейдж «демонстрирует экран»,
    // видео прикреплено, аватара нет (T073); второго тайла bob нет
    const pinned = await waitFor(() => {
      const tile = document.querySelector<HTMLElement>('.conference-tile-pinned')
      expect(tile).not.toBeNull()
      return tile
    })
    expect(pinned).toHaveTextContent('bob')
    expect(pinned).toHaveTextContent('демонстрирует экран')
    expect(pinned?.querySelector('video')).not.toBeNull()
    expect(pinned?.querySelector('.avatar')).toBeNull()
    expect(document.querySelectorAll('.conference-tile')).toHaveLength(2)
    // после автопина секция перерендерена компактной полосой — живой запрос
    expect(screen.getByRole('region', { name: 'Участники конференции' })).toHaveClass(
      'conference-tiles-compact',
    )
  })
})

describe('ConferenceRoom — аватары тайлов (009 US1 T073, «как в контактах»)', () => {
  const ALICE = '11111111-1111-4111-8111-111111111111'

  /** Тайл по подписи-имени участника (figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('тайл без камеры — аватар + имя; с опубликованной камерой — видео без аватара', async () => {
    lkc.trackRefs = [
      // bob: камера выключена — placeholder-реф без publication
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      // alice: камера опубликована — реальный track-реф
      {
        participant: { identity: `u:${ALICE}:c1`, name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-1' },
      },
    ]
    renderRoom()

    const bobTile = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveClass('conference-tile-idle')
      return tile
    })
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    expect(bobTile.querySelector('video')).toBeNull()

    const aliceTile = tileByLabel('alice')
    expect(aliceTile.classList.contains('conference-tile-idle')).toBe(false)
    expect(aliceTile.querySelector('video')).not.toBeNull()
    expect(aliceTile.querySelector('.avatar')).toBeNull()
  })

  it('live-переход камеры: публикация убирает аватар с тайла, выключение возвращает', async () => {
    lkc.trackRefs = [{ participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' }]
    const view = renderRoom()

    await waitFor(() => {
      expect(tileByLabel('bob').querySelector('.avatar')).not.toBeNull()
    })

    // камера включилась — track-реф с publication
    lkc.trackRefs = [
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-1' },
      },
    ]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    await waitFor(() => {
      expect(tileByLabel('bob').querySelector('.avatar')).toBeNull()
    })
    expect(tileByLabel('bob').querySelector('video')).not.toBeNull()

    // камера выключилась — аватар вернулся
    lkc.trackRefs = [{ participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' }]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    await waitFor(() => {
      expect(tileByLabel('bob').querySelector('.avatar')).not.toBeNull()
    })
  })

  it('индикатор речи на тайле без камеры — текстом «говорит» (008: не только цветом)', async () => {
    lkc.trackRefs = [{ participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' }]
    lkc.speakers = [{ identity: 'u:2:b' }]
    renderRoom()

    const bobTile = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveTextContent('говорит')
      return tile
    })
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
  })

  it('без аватара в профиле участника — инициалы из name (фолбэк догрузки), тире-подпись не меняется', async () => {
    // гость без провайдера профилей: инициалы из введённого имени №55
    lkc.trackRefs = [
      {
        participant: { identity: 'g:22222222-2222-4222-8222-222222222222', name: 'Виктор' },
        source: 'camera',
      },
    ]
    renderRoom()

    const tile = await waitFor(() => tileByLabel('Виктор'))
    const avatar = tile.querySelector<HTMLElement>('.avatar')
    expect(avatar).not.toBeNull()
    expect(avatar?.querySelector('span')?.textContent).toBe('ВИ')
  })
})

describe('ConferenceRoom — окно реконнекта T060 (009 US5 T061, Q15=B)', () => {
  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('потеря связи после подключения: индикация «переподключение…» и цепочка ретраев минта до конца окна', async () => {
    // null — минт не даёт свежего тикета (сбой/устаревание): ретраи
    // продолжаются, пока окно живо
    const refreshTicket = vi.fn().mockResolvedValue(null)
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
        reconnectWindowMs={150}
        reconnectRetryDelayMs={20}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.micCalls).toHaveLength(1)
    })

    room.state = lk.ConnectionState.Disconnected
    room.emit(lk.RoomEvent.Disconnected)

    // индикация потери связи (T060): понятный статус с первой потери
    expect(await screen.findByText('Соединение потеряно — переподключение…')).toBeVisible()
    await waitFor(() => {
      expect(refreshTicket.mock.calls.length).toBeGreaterThanOrEqual(1)
    })
  })

  it('истечение reconnect-window — контролируемое завершение «lost», ретраи минта прекращаются', async () => {
    const refreshTicket = vi.fn().mockResolvedValue(null)
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={refreshTicket}
        reconnectWindowMs={150}
        reconnectRetryDelayMs={20}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.micCalls).toHaveLength(1)
    })

    room.state = lk.ConnectionState.Disconnected
    room.emit(lk.RoomEvent.Disconnected)
    expect(await screen.findByText('Соединение потеряно — переподключение…')).toBeVisible()

    // окно истекло — понятное сообщение контролируемого завершения
    const lost = await screen.findByText(
      'Не удалось восстановить связь с конференцией',
      {},
      { timeout: 2_000 },
    )
    expect(lost).toBeVisible()

    // цепочка остановлена: новых вызовов минта после «lost» нет
    const callsAtLoss = refreshTicket.mock.calls.length
    expect(callsAtLoss).toBeGreaterThanOrEqual(1)
    await new Promise((resolve) => {
      setTimeout(resolve, 80)
    })
    expect(refreshTicket.mock.calls).toHaveLength(callsAtLoss)
    expect(room.connectCalls).toHaveLength(1)
  })
})

describe('ConferenceRoom — тост conference.warning (009 US5 T061 ← T056/T060, FR-015 Q16=B)', () => {
  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('data-пакет topic conference.warning — тост о лимите длительности, закрытие вручную', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })

    // серверный SendData шедулера T056: payload {"type","endsAt"} — ВСЕМ
    // подключённым (включая гостей без SSE), тема conference.warning
    const payload = new TextEncoder().encode(
      JSON.stringify({ type: 'conference.warning', endsAt: '2026-10-17T15:55:00.000Z' }),
    )
    room.emit(lk.RoomEvent.DataReceived, payload, undefined, undefined, 'conference.warning')

    const toast = await screen.findByText('Конференция завершится по лимиту длительности')
    expect(toast).toBeVisible()

    // событие одно — тост живёт до ручного закрытия (до завершения
    // остаётся warning-before-end)
    fireEvent.click(screen.getByRole('button', { name: 'Закрыть' }))
    await waitFor(() => {
      expect(screen.queryByText('Конференция завершится по лимиту длительности')).toBeNull()
    })
  })

  it('чужая тема и битый payload — тост не показывается (нейтральный игнор)', async () => {
    render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      />,
    )
    const room = lastRoom()
    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })

    const encode = (value: string) => new TextEncoder().encode(value)
    // чужая тема: payload правильный, но topic не conference.warning
    room.emit(
      lk.RoomEvent.DataReceived,
      encode(JSON.stringify({ type: 'conference.warning' })),
      undefined,
      undefined,
      'chat.typing',
    )
    // битый JSON нужной темы
    room.emit(
      lk.RoomEvent.DataReceived,
      encode('не-json'),
      undefined,
      undefined,
      'conference.warning',
    )
    // чужой payload.type нужной темы
    room.emit(
      lk.RoomEvent.DataReceived,
      encode(JSON.stringify({ type: 'other.event' })),
      undefined,
      undefined,
      'conference.warning',
    )

    await new Promise((resolve) => {
      setTimeout(resolve, 30)
    })
    expect(screen.queryByText(/лимитом длительности/)).toBeNull()
  })
})

describe('ConferenceRoom — пин-раскладка тайлов (009 US1 T074)', () => {
  /** Собственный identity тикета — self-view тайл тоже закрепляем. */
  const OWN_IDENTITY = 'u:11111111-1111-1111-1111-111111111111:conn-1'

  /** Тайл по подписи-имени участника (figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  /** Сетка/полоса участников (region «Участники конференции»). */
  function tilesSection(): HTMLElement {
    return screen.getByRole('region', { name: 'Участники конференции' })
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Пин тайла кнопкой-тумблером «Развернуть» (явный путь, 008). */
  function pinTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Развернуть' }))
  }

  /** Свернуть закреплённый тайл кнопкой-тумблером «Свернуть». */
  function collapseTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Свернуть' }))
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) клик «Развернуть» — закреплённый тайл несёт пин-класс, остальные — компактную разметку', async () => {
    lkc.trackRefs = [
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-1' },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()

    const aliceTile = await waitFor(() => tileByLabel('alice'))
    const expand = within(aliceTile).getByRole('button', { name: 'Развернуть' })
    expect(expand).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(expand)

    // закреплённый тайл — большой на всю медиа-зону (пин-класс), тумблер
    // перевернулся: «Свернуть» + aria-pressed=true
    const pinnedTile = tileByLabel('alice')
    expect(pinnedTile).toHaveClass('conference-tile-pinned')
    const collapse = within(pinnedTile).getByRole('button', { name: 'Свернуть' })
    expect(collapse).toHaveAttribute('aria-pressed', 'true')

    // остальные — компактная полоса; их тумблеры неактивны
    expect(tilesSection()).toHaveClass('conference-tiles-compact')
    const bobTile = tileByLabel('bob')
    expect(bobTile).not.toHaveClass('conference-tile-pinned')
    expect(within(bobTile).getByRole('button', { name: 'Развернуть' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('(б) повторный клик по закреплённому тайлу — сворачивание в обычную сетку', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')

    fireEvent.click(within(tileByLabel('alice')).getByRole('button', { name: 'Свернуть' }))

    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
  })

  it('(в) пин другого участника — переключение: закреплён всегда ≤1', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')

    pinTile(tileByLabel('bob'))

    // переключение: закреплён bob, alice вернулась компактной сеткой
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    expect(tileByLabel('alice')).not.toHaveClass('conference-tile-pinned')
    expect(document.querySelectorAll('.conference-tile-pinned')).toHaveLength(1)
  })

  it('(г) закреплённый покидает комнату — автоматический возврат обычной сетки', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    expect(document.querySelectorAll('.conference-tile-pinned')).toHaveLength(1)

    // alice ушла из useTracks/useParticipants — пин-цель не существует
    lkc.trackRefs = [{ participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' }]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )

    await waitFor(() => {
      expect(screen.queryByText('alice')).toBeNull()
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    expect(within(tileByLabel('bob')).getByRole('button', { name: 'Развернуть' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('(д) собственный тайл и демонстрация экрана закрепляемы; пин переживает завершение демонстрации (T084)', async () => {
    lkc.trackRefs = [
      // self-view: собственный тайл (камера выключена — аватар)
      { participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    const view = renderRoom()

    // собственный тайл закрепляем как любой другой
    const ownTile = await waitFor(() => tileByLabel('alice'))
    pinTile(ownTile)
    expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')
    expect(tileByLabel('alice').querySelector('.avatar')).not.toBeNull()

    // слитый тайл демонстрации bob (T084: имя + бейдж) закрепляем —
    // переключение с собственного тайла
    pinTile(tileByLabel('bob'))
    const pinnedScreen = document.querySelector('.conference-tile-pinned')
    expect(pinnedScreen).not.toBeNull()
    expect(pinnedScreen).toHaveTextContent('bob')
    expect(pinnedScreen).toHaveTextContent('демонстрирует экран')
    expect(pinnedScreen?.querySelector('video')).not.toBeNull()
    // прежний закреплённый вернулся компактной полосой
    expect(tileByLabel('alice')).not.toHaveClass('conference-tile-pinned')

    // завершение демонстрации у закреплённого — пин ОСТАЁТСЯ на слитом
    // тайле bob (T084 отменяет сброс T074/T078): тайл жив с аватаром
    lkc.trackRefs = [
      { participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
    const stillPinned = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveClass('conference-tile-pinned')
      return tile
    })
    expect(stillPinned.querySelector('.avatar')).not.toBeNull()
    expect(stillPinned).not.toHaveTextContent('демонстрирует экран')

    // возврат сетки — ручное сворачивание (кнопка «Свернуть»)
    collapseTile(stillPinned)
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
  })

  it('(е) регресс T074 без демонстраций: обычная сетка и ручной пин, аудио-рендер и children не тронуты', async () => {
    // T078 автопинит любую появившуюся демонстрацию — регресс «широких
    // секций без пина» проверяется на чистых камерах (демонстраций нет)
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()

    // до пина — обычная сетка (не компактная), развёрнутых тайлов нет
    await waitFor(() => {
      expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    // аудио удалённых участников смонтировано; слот MediaControls/панелей жив
    expect(screen.getByTestId('lk-audio-renderer')).not.toBeNull()
    expect(screen.getByTestId('controls-slot')).not.toBeNull()

    // и в пин-раскладке аудио-рендер и children остаются на месте,
    // остальные тайлы уходят в компактную полосу
    pinTile(tileByLabel('alice'))
    expect(screen.getByTestId('lk-audio-renderer')).not.toBeNull()
    expect(screen.getByTestId('controls-slot')).not.toBeNull()
    expect(tilesSection()).toHaveClass('conference-tiles-compact')
    expect(tilesSection()).toHaveTextContent('bob')
  })
})

describe('ConferenceRoom — автопин демонстрации экрана (009 US1 T078, T084)', () => {
  /** Собственный identity тикета — собственная демонстрация тоже автопинится. */
  const OWN_IDENTITY = 'u:11111111-1111-1111-1111-111111111111:conn-1'

  /** Тайл по подписи-имени участника (figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  /** Закреплённый слитый тайл участника по имени (ожидание автопина T078). */
  async function pinnedTileOf(name: string): Promise<HTMLElement> {
    return waitFor(() => {
      const target = tileByLabel(name)
      expect(target).toHaveClass('conference-tile-pinned')
      return target
    })
  }

  /** Сетка/полоса участников (region «Участники конференции»). */
  function tilesSection(): HTMLElement {
    return screen.getByRole('region', { name: 'Участники конференции' })
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Живое обновление track-рефов (переходы «не было → появился» и уход). */
  function rerenderRoom(view: ReturnType<typeof render>): void {
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Пин тайла кнопкой-тумблером «Развернуть» (явный путь, 008). */
  function pinTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Развернуть' }))
  }

  /** Свернуть закреплённый тайл кнопкой-тумблером «Свернуть». */
  function collapseTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Свернуть' }))
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) старт демонстрации при пустом пине — слитый тайл демонстрирующего развёрнут без клика, остальные — компактная полоса', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('bob'))
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()

    // bob включил демонстрацию — появился НОВЫЙ screen-ключ при пустом
    // пине: СЛИТЫЙ тайл bob (T084) разворачивается тем же элементом
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)

    const pinned = await pinnedTileOf('bob')
    // тумблер закреплённого тайла — «Свернуть» (ручное сворачивание доступно)
    expect(pinned).toHaveTextContent('демонстрирует экран')
    expect(pinned.querySelector('video')).not.toBeNull()
    expect(pinned.querySelector('.avatar')).toBeNull()
    expect(within(pinned).getByRole('button', { name: 'Свернуть' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    // второго тайла bob не рождается; тайлы камер — компактная полоса T074
    expect(document.querySelectorAll('.conference-tile')).toHaveLength(2)
    expect(tilesSection()).toHaveClass('conference-tiles-compact')
    expect(tilesSection()).toHaveTextContent('alice')
    expect(document.querySelectorAll('.conference-tile-pinned')).toHaveLength(1)
  })

  it('(б) ручной пин зрителя стартом демонстрации НЕ перебивается', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')

    // демонстрация пошла при ЖИВОМ ручном пине — правило «только при
    // пустом пине»: закреплена камера alice, слитый тайл bob — в полосе
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)

    expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')
    expect(tilesSection()).toHaveTextContent('bob')
    expect(tilesSection()).toHaveTextContent('демонстрирует экран')
    expect(document.querySelectorAll('.conference-tile-pinned')).toHaveLength(1)
  })

  it('(в) вторая одновременная демонстрация не переключает уже автозакреплённую первую', async () => {
    lkc.trackRefs = [{ participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' }]
    const view = renderRoom()
    await waitFor(() => tileByLabel('alice'))

    // первая демонстрация — автопин (пин пуст)
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await pinnedTileOf('bob')

    // вторая демонстрация при живом (авто)пине — раскладка не дёргается
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:3:c', name: 'carol' }, source: 'screen_share' },
    ]
    rerenderRoom(view)

    await waitFor(() => {
      expect(tilesSection()).toHaveTextContent('carol')
    })
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    expect(document.querySelectorAll('.conference-tile-pinned')).toHaveLength(1)
  })

  it('(г) завершение автозакреплённой демонстрации — пин остаётся на слитом тайле (T084); сворачивание возвращает сетку', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('bob'))

    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await pinnedTileOf('bob')

    // bob завершил демонстрацию — слитый тайл жив (камера-заглушка):
    // пин НЕ сбрасывается (T084 отменяет автосброс T074/T078)
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    rerenderRoom(view)

    const stillPinned = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveClass('conference-tile-pinned')
      return tile
    })
    expect(stillPinned.querySelector('.avatar')).not.toBeNull()
    expect(stillPinned).not.toHaveTextContent('демонстрирует экран')

    // возврат сетки — ручное сворачивание
    collapseTile(stillPinned)
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
  })

  it('(д) ручное «Свернуть» автопина при живой демонстрации — сетка БЕЗ повторного автопина', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('bob'))

    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    const pinned = await pinnedTileOf('bob')

    // осознанное сворачивание при живой демонстрации — обычная сетка:
    // слитый тайл bob с видео и бейджем (широкой секции демонстраций
    // больше нет — T084)
    collapseTile(pinned)
    await waitFor(() => {
      expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    })
    expect(tileByLabel('bob')).toHaveTextContent('демонстрирует экран')
    expect(tileByLabel('bob').querySelector('video')).not.toBeNull()

    // повторного автопина НЕТ: эффект срабатывает на появление НОВОГО
    // screen-ключа, а не на инвариант «пустой пин + живые экраны» —
    // иначе цикл; перерендёры и пауза подтвержают устойчивость
    rerenderRoom(view)
    await new Promise((resolve) => {
      setTimeout(resolve, 50)
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tileByLabel('bob')).toHaveTextContent('демонстрирует экран')
  })

  it('(е) собственная демонстрация автопинится в слитом self-view тайле (T084)', async () => {
    lkc.trackRefs = [
      { participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('alice'))
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()

    // собственный screen-ref (self-view) — тот же переход «не было →
    // появился» при пустом пине: СЛИТЫЙ собственный тайл показывает
    // экран-видео (T077 гасит камеру — аватар-заглушки нет)
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'screen_share' },
    ]
    rerenderRoom(view)

    const pinned = await pinnedTileOf('alice')
    expect(pinned).toHaveTextContent('демонстрирует экран')
    expect(pinned.querySelector('video')).not.toBeNull()
    expect(pinned.querySelector('.avatar')).toBeNull()
    expect(within(pinned).getByRole('button', { name: 'Свернуть' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    // второй тайл alice не рождается; компактная полоса — тайл bob
    expect(document.querySelectorAll('.conference-tile')).toHaveLength(2)
    expect(tilesSection()).toHaveClass('conference-tiles-compact')
    expect(tilesSection()).toHaveTextContent('bob')
  })

  it('(ж) регресс ручного пина T074: двойной клик, сброс выходом участника — уже замеченная демонстрация не автопинится', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('bob'))

    // двойной клик — допустимый путь ручного пина (T074, без изменений)
    fireEvent.dblClick(tileByLabel('bob'))
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')

    // демонстрация пошла при живом ручном пине — замечена, не закреплена
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:3:c', name: 'carol' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tilesSection()).toHaveTextContent('carol')
    })
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')

    // закреплённый bob покинул комнату — возврат сетки; демонстрация
    // carol уже замечена эффектом — автопина НЕ происходит (правило
    // «на появление нового ключа», не инвариант)
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:3:c', name: 'carol' }, source: 'screen_share' },
    ]
    rerenderRoom(view)

    await waitFor(() => {
      expect(screen.queryByText('bob')).toBeNull()
    })
    await new Promise((resolve) => {
      setTimeout(resolve, 50)
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tileByLabel('carol')).toHaveTextContent('демонстрирует экран')
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
  })
})

describe('ConferenceRoom — полноэкранный пин-оверлей (009 US1 T079)', () => {
  /** Тайл по подписи-имени участника (figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  /** Корневой div комнаты — несёт маркер оверлея в пин-состоянии (T079). */
  function roomRoot(): HTMLElement {
    const root = document.querySelector<HTMLElement>('.conference-room')
    if (root === null) {
      throw new Error('корневой div комнаты не найден')
    }
    return root
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Живое обновление track-рефов (переходы «не было → появился» и уход). */
  function rerenderRoom(view: ReturnType<typeof render>): void {
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Пин тайла кнопкой-тумблером «Развернуть» (явный путь, 008). */
  function pinTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Развернуть' }))
  }

  /** Свернуть закреплённый тайл кнопкой-тумблером «Свернуть». */
  function collapseTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Свернуть' }))
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) ручной пин навешивает маркер-класс conference-room-pinned на комнату, сворачивание снимает', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    await waitFor(() => tileByLabel('bob'))
    // обычная сетка — маркера оверлея нет
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(false)

    pinTile(tileByLabel('bob'))
    // живой пин → корневой div комнаты несёт маркер (CSS разворачивает
    // полноэкранный оверлей), закреплённый тайл на месте
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(true)
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')

    // сворачивание — маркер снят тем же рендером, что вернул сетку
    collapseTile(tileByLabel('bob'))
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(false)
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
  })

  it('(б) автопин демонстрации T078 навешивает маркер; завершение демонстрации пин НЕ снимает (T084) — снимает сворачивание', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('bob'))
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(false)

    // старт демонстрации при пустом пине — автопин разворачивает оверлей
    // БЕЗ ручного клика
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(roomRoot().classList.contains('conference-room-pinned')).toBe(true)
    })
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')

    // завершение демонстрации — слитый тайл bob жив (аватар), пин и
    // маркер оверлея ОСТАЮТСЯ (T084: сброс завершением демонстрации
    // отменён)
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tileByLabel('bob').querySelector('.avatar')).not.toBeNull()
    })
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(true)
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')

    // сворачивание — маркер снят тем же рендером, что вернул сетку
    collapseTile(tileByLabel('bob'))
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(false)
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
  })

  it('(в) закреплённый покидает комнату — маркер снимается (цель ушла из живых рефов)', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    pinTile(await waitFor(() => tileByLabel('bob')))
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(true)

    lkc.trackRefs = [{ participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' }]
    rerenderRoom(view)
    await waitFor(() => {
      expect(roomRoot().classList.contains('conference-room-pinned')).toBe(false)
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
  })

  it('(г) в оверлее children (MediaControls) и аудио остаются в DOM — позиционирование CSS', async () => {
    // T079 (3): MediaControls НЕ переносится — хуки livekit живут в
    // RoomContext; полноэкранная геометрия и показ поверх тайла — CSS
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    pinTile(await waitFor(() => tileByLabel('bob')))
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(true)
    expect(screen.getByTestId('controls-slot')).not.toBeNull()
    expect(screen.getByTestId('lk-audio-renderer')).not.toBeNull()
  })
})

describe('ConferenceRoom — Esc-выход из пин-оверлея (009 US1 T082)', () => {
  /** Тайл по подписи-имени участника (figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  /** Корневой div комнаты — несёт маркер оверлея в пин-состоянии (T079). */
  function roomRoot(): HTMLElement {
    const root = document.querySelector<HTMLElement>('.conference-room')
    if (root === null) {
      throw new Error('корневой div комнаты не найден')
    }
    return root
  }

  /** Сетка/полоса участников (region «Участники конференции»). */
  function tilesSection(): HTMLElement {
    return screen.getByRole('region', { name: 'Участники конференции' })
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Живое обновление track-рефов (переходы «не было → появился» и уход). */
  function rerenderRoom(view: ReturnType<typeof render>): void {
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Пин тайла кнопкой-тумблером «Развернуть» (явный путь, 008). */
  function pinTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Развернуть' }))
  }

  /** Клавиатурный путь T082 — прикладной keydown Escape (не браузерный fullscreen). */
  function pressEscape(): void {
    fireEvent.keyDown(window, { key: 'Escape' })
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.clearAllMocks()
  })

  it('(а) keydown Escape при закреплённом тайле — обычная сетка, маркер-класс оверлея снят', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    pinTile(await waitFor(() => tileByLabel('bob')))
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(true)

    // Esc — та же семантика, что кнопка «Свернуть»: сетка возвращается,
    // маркер полноэкранного оверлея снят, звонок продолжается
    pressEscape()
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(false)
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    expect(tilesSection()).toHaveTextContent('bob')
  })

  it('(б) Escape при обычной сетке — no-op: слушателя keydown нет, сетка не меняется', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    await waitFor(() => tileByLabel('bob'))

    // слушатель навешивается эффектом ТОЛЬКО в пин-состоянии
    expect(addSpy.mock.calls.filter(([type]) => type === 'keydown')).toHaveLength(0)

    // Esc без пина — пустой no-op, раскладка не меняется
    pressEscape()
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    expect(screen.queryByText('Вы отключены от конференции')).toBeNull()
  })

  it('(в) Esc-сворачивание автопина демонстрации при живой демонстрации — сетка БЕЗ повторного автопина', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('bob'))

    // старт демонстрации при пустом пине — автопин T078
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    })

    // Esc сворачивает автопин при живой демонстрации — обычная сетка:
    // слитый тайл bob с видео и бейджем (широкой секции больше нет, T084)
    pressEscape()
    await waitFor(() => {
      expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    })
    expect(tileByLabel('bob')).toHaveTextContent('демонстрирует экран')

    // повторного автопина НЕТ (паритет T078: решение на появление НОВОГО
    // screen-ключа, не на инвариант «пустой пин + живые экраны»)
    rerenderRoom(view)
    await new Promise((resolve) => {
      setTimeout(resolve, 50)
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tileByLabel('bob')).toHaveTextContent('демонстрирует экран')
  })

  it('(г) ручной пин после Esc работает — клик «Развернуть» снова разворачивает тайл', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()
    pinTile(await waitFor(() => tileByLabel('bob')))
    pressEscape()
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()

    // повторный пин после Esc — обычный путь T074 без изменений
    pinTile(tileByLabel('bob'))
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    expect(roomRoot().classList.contains('conference-room-pinned')).toBe(true)
  })

  it('(д) размонтирование комнаты снимает слушатель (unmount без утечки)', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    pinTile(await waitFor(() => tileByLabel('bob')))

    // живой пин — слушатель keydown навешан (ровно один)
    const added = addSpy.mock.calls
      .filter(([type]) => type === 'keydown')
      .map(([, listener]) => listener)
    expect(added).toHaveLength(1)

    view.unmount()

    // размонтирование снимает слушатель — по ссылке, без утечки
    const removed = removeSpy.mock.calls
      .filter(([type]) => type === 'keydown')
      .map(([, listener]) => listener)
    expect(removed).toEqual(added)
  })
})

describe('ConferenceRoom — слитый тайл участника (009 US1 T084)', () => {
  /** Собственный identity тикета — self-view тайл тоже слитый. */
  const OWN_IDENTITY = 'u:11111111-1111-1111-1111-111111111111:conn-1'

  /** Тайл по подписи-имени участника (figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  /** Сетка/полоса участников (region «Участники конференции»). */
  function tilesSection(): HTMLElement {
    return screen.getByRole('region', { name: 'Участники конференции' })
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Живое обновление track-рефов (переходы «не было → появился» и уход). */
  function rerenderRoom(view: ReturnType<typeof render>): void {
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Пин тайла кнопкой-тумблером «Развернуть» (явный путь, 008). */
  function pinTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Развернуть' }))
  }

  /** Свернуть закреплённый тайл кнопкой-тумблером «Свернуть». */
  function collapseTile(tile: HTMLElement): void {
    fireEvent.click(within(tile).getByRole('button', { name: 'Свернуть' }))
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) живое переключение камера→экран: тот же DOM-узел тайла, экран-видео + бейдж, без второго тайла и перестройки сетки', async () => {
    lkc.trackRefs = [
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-a' },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    // ручной пин bob — живой пин гасит автопин T078: переключение alice
    // наблюдается в компактной полосе БЕЗ перестройки раскладки
    pinTile(await waitFor(() => tileByLabel('bob')))
    const aliceTile = tileByLabel('alice')
    expect(aliceTile.querySelector('video')).not.toBeNull()
    const figuresBefore = document.querySelectorAll('.conference-tile').length

    // у alice появился экран, камера снята (T077) — тот же DOM-узел
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'screen_share' },
    ]
    rerenderRoom(view)

    expect(tileByLabel('alice')).toBe(aliceTile)
    expect(aliceTile.querySelector('video')).not.toBeNull()
    expect(aliceTile).toHaveTextContent('демонстрирует экран')
    expect(aliceTile.querySelector('.avatar')).toBeNull()
    // второго тайла alice нет, раскладка не перестроена
    expect(document.querySelectorAll('.conference-tile')).toHaveLength(figuresBefore)
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    expect(tilesSection()).toHaveClass('conference-tiles-compact')
  })

  it('(б) обратное экран→камера: демонстрация завершена, камера published — тот же тайл показывает камеру-видео без бейджа', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    const bobTile = tileByLabel('bob')

    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tileByLabel('bob')).toHaveTextContent('демонстрирует экран')
    })

    // демонстрация завершена, камера включена — тот же узел, камера-видео
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b' },
      },
    ]
    rerenderRoom(view)

    expect(tileByLabel('bob')).toBe(bobTile)
    expect(bobTile.querySelector('video')).not.toBeNull()
    expect(bobTile).not.toHaveTextContent('демонстрирует экран')
  })

  it('(в) экран→ничего: заглушка — аватар T073 с именем и индикатором речи (регресс T073)', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    pinTile(await waitFor(() => tileByLabel('alice')))
    const bobTile = tileByLabel('bob')

    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tileByLabel('bob')).toHaveTextContent('демонстрирует экран')
    })

    // демонстрация завершена, камера не включалась — тот же узел с
    // аватаром, именем и индикатором речи
    lkc.speakers = [{ identity: 'u:2:b' }]
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    rerenderRoom(view)

    expect(tileByLabel('bob')).toBe(bobTile)
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    expect(bobTile.querySelector('video')).toBeNull()
    expect(bobTile).toHaveTextContent('говорит')
    expect(bobTile).not.toHaveTextContent('демонстрирует экран')
  })

  it('(г) пин переживает переключение: завершение демонстрации НЕ сбрасывает пин; сброс — «Свернуть», Esc, уход участника', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('bob'))

    // автопин экрана bob (пин пуст)
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    })

    // завершение демонстрации — пин ОСТАЁТСЯ (камера-видео bob)
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b' },
      },
    ]
    rerenderRoom(view)
    const pinned = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveClass('conference-tile-pinned')
      expect(tile.querySelector('video')).not.toBeNull()
      return tile
    })

    // сброс №1 — кнопка «Свернуть»; повторный пин — Esc T082
    collapseTile(pinned)
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    pinTile(tileByLabel('bob'))
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()

    // сброс №2 — уход закреплённого участника из комнаты
    pinTile(tileByLabel('bob'))
    lkc.trackRefs = [{ participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' }]
    rerenderRoom(view)
    await waitFor(() => {
      expect(screen.queryByText('bob')).toBeNull()
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
  })

  it('(д) автопин T078 ключом identity: экран при пустом пине разворачивает тайл демонстрирующего; вторая демонстрация и Esc-сворачивание не репинятся; повторная публикация трека — новая демонстрация', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    await waitFor(() => tileByLabel('bob'))

    // экран bob при пустом пине — автопин слитого тайла демонстрирующего
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    })
    expect(tilesSection()).toHaveClass('conference-tiles-compact')

    // вторая одновременная демонстрация — не переключает закреплённый
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:3:c', name: 'carol' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tilesSection()).toHaveTextContent('carol')
    })
    expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    expect(document.querySelectorAll('.conference-tile-pinned')).toHaveLength(1)

    // Esc-сворачивание при живых демонстрациях — сетка без повторного автопина
    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() => {
      expect(tilesSection().classList.contains('conference-tiles-compact')).toBe(false)
    })
    rerenderRoom(view)
    await new Promise((resolve) => {
      setTimeout(resolve, 50)
    })
    expect(document.querySelector('.conference-tile-pinned')).toBeNull()

    // повторная публикация трека (переподключение демонстрирующего:
    // ключ ушёл из живых и вернулся) — новая демонстрация: пустой пин
    // → автопин снова
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(screen.queryByText('carol')).toBeNull()
    })
    lkc.trackRefs = [
      ...lkc.trackRefs,
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    rerenderRoom(view)
    await waitFor(() => {
      expect(tileByLabel('bob')).toHaveClass('conference-tile-pinned')
    })
  })

  it('(е) приоритет: участник с ОДНОВРЕМЕННО published камерой и экраном — тайл показывает экран, второй тайл не рождается', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b' },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    const view = renderRoom()

    await waitFor(() => {
      expect(tileByLabel('bob')).toHaveTextContent('демонстрирует экран')
    })
    const bobTile = tileByLabel('bob')
    expect(bobTile.querySelector('video')).not.toBeNull()
    expect(bobTile.querySelector('.avatar')).toBeNull()
    // ровно один тайл на участника: alice + bob
    expect(document.querySelectorAll('.conference-tile')).toHaveLength(2)
    rerenderRoom(view)
    expect(document.querySelectorAll('.conference-tile')).toHaveLength(2)
  })

  it('(ж) подпись «Экран — …» отсутствует во всех состояниях; широкой секции демонстраций больше нет', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    renderRoom()
    await waitFor(() => {
      expect(document.querySelector('.conference-tile-pinned')).not.toBeNull()
    })
    expect(screen.queryByText(/Экран —/)).toBeNull()
    expect(document.body.textContent).not.toContain('Экран —')
    expect(screen.queryByRole('region', { name: 'Демонстрации экрана' })).toBeNull()
    // сетка участников — единственная секция тайлов
    expect(screen.getAllByRole('region', { name: 'Участники конференции' })).toHaveLength(1)
  })

  it('(з) собственный тайл (self-view): старт демонстрации T077 — свой тайл бесшовно переключается на экран-видео', async () => {
    lkc.trackRefs = [
      {
        participant: { identity: OWN_IDENTITY, name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-own' },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    const view = renderRoom()
    const ownTile = await waitFor(() => tileByLabel('alice'))
    expect(ownTile.querySelector('video')).not.toBeNull()

    // T077 гасит камеру при старте демонстрации: собственный экран +
    // камера-заглушка — слитый self-view тайл показывает ЭКРАН
    lkc.trackRefs = [
      { participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      { participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'screen_share' },
    ]
    rerenderRoom(view)

    // автопин пустого пина разворачивает собственный слитый тайл
    const pinned = await waitFor(() => {
      const tile = tileByLabel('alice')
      expect(tile).toHaveClass('conference-tile-pinned')
      return tile
    })
    expect(pinned.querySelector('video')).not.toBeNull()
    expect(pinned.querySelector('.avatar')).toBeNull()
    expect(pinned).toHaveTextContent('демонстрирует экран')
  })
})
