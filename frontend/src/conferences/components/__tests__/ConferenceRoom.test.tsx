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
 *
 * 009, US1, T090 — инвариант «камера выкл + без демонстрации экрана =
 * аватар»: выключение камеры — это mute трека, НЕ unpublish (с
 * публикации LiveKit снимает только демонстрация экрана), поэтому
 * published-but-muted camera-реф — НЕ видеоисточник: тайл рендерит
 * аватар-заглушку (репродукция «тёмный фон вместо аватара после
 * вкл/выкл камеры»); published+unmuted — VideoTrack; живая
 * демонстрация при замученной камере — экран-видео с бейджем
 * (приоритет T084 сохраняется); переход muted→unmuted — бесшовный
 * возврат видео в том же DOM-узле тайла; placeholder-реф (камера
 * никогда не публиковалась) и симметрия local/remote — аватар у всех.
 *
 * 009, US1, T100 — индикатор микрофона и пометки ролей тайла по
 * прототипу tmp/conference-tile-mic.html: латунный бейдж-микрофон в
 * правом нижнем углу КАЖДОГО тайла (видео и idle-заглушка) ровно с
 * ДВУМЯ состояниями вкл/выкл (никакого замка и tooltip), состояние —
 * проекция микрофонной публикации (local — isMicrophoneEnabled
 * useLocalParticipant, remote — microphone-реф publication.isMuted,
 * зеркало T090 для камеры; серверный мьют №49 отражается сам);
 * пульс при речи — ТОЛЬКО у бейджа (speaking + mic on), текстовый
 * индикатор «говорит» демонтирован; имя — в собственном спане
 * (Playwright getByText exact), пометки «гость»/«модератор» из
 * participant.metadata (§3, парсер ParticipantsPanel T041) рядом с
 * именем приглушённым курсивом, живо следуют UpdateParticipant №50.
 *
 * 009, US1/US5, T114 — уведомления комнаты под рабочей областью:
 * conference-room-status (фазы T027/потеря связи T060),
 * conference-room-media (отказ устройств T032) и тост
 * conference-warning-toast (T060 ← T056) — дети flex-колонки ПОД
 * .conference-main (когда медиа-слой смонтирован) и НАД слотом
 * controls: DOM-порядок детей комнаты — toggle-row → main →
 * статус/media/тост → controls (compareDocumentPosition); фазы без
 * main (failed) — статус остаётся над всегда смонтированным
 * controls; пин-оверлей T079 DOM-позицию не меняет (absolute — CSS).
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
  // publication.isMuted — зеркало TrackPublication.isMuted livekit-client
  // (T090: published-but-muted camera-трек от настоящего рефа неотличим);
  // participant.metadata — пометки ролей тайла T100 (тот же JSON §3)
  trackRefs: [] as ReadonlyArray<{
    participant: { identity: string; name?: string; metadata?: string }
    source: string
    publication?: { trackSid: string; isMuted?: boolean }
  }>,
  speakers: [] as ReadonlyArray<{ identity: string }>,
  // useLocalParticipant (T100): identity тикета + собственный микрофон
  // (isMicrophoneEnabled — канонический источник local-состояния бейджа)
  local: {
    localParticipant: { identity: 'u:11111111-1111-1111-1111-111111111111:conn-1' },
    isMicrophoneEnabled: true,
  },
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
  // T100: local-участник + состояние собственного микрофона
  useLocalParticipant: () => lkc.local,
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

  it('индикатор речи тайла без камеры — пульс бейджа микрофона, текста «говорит» больше нет (T100)', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      // микрофон bob опубликован и незамучен — пульс у бейджа (T100)
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'microphone',
        publication: { trackSid: 'mic-b' },
      },
    ]
    lkc.speakers = [{ identity: 'u:2:b' }]
    renderRoom()

    const bobTile = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveClass('conference-tile-idle')
      return tile
    })
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    // пульс — ТОЛЬКО у бейджа микрофона (говорит + mic on, T100);
    // текстовый индикатор «говорит» демонтирован
    const badge = bobTile.querySelector<HTMLElement>('.conference-tile-mic')
    expect(badge).not.toBeNull()
    expect(badge).toHaveAttribute('data-mic', 'on')
    expect(badge).toHaveClass('conference-tile-mic-pulse')
    expect(bobTile).not.toHaveTextContent('говорит')
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
    // аватаром и именем; индикатор речи — пульс бейджа микрофона
    // (T100: текстовый «говорит» демонтирован), микрофон bob жив
    lkc.speakers = [{ identity: 'u:2:b' }]
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'microphone',
        publication: { trackSid: 'mic-b' },
      },
    ]
    rerenderRoom(view)

    expect(tileByLabel('bob')).toBe(bobTile)
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    expect(bobTile.querySelector('video')).toBeNull()
    expect(bobTile.querySelector('.conference-tile-mic')).toHaveClass('conference-tile-mic-pulse')
    expect(bobTile).not.toHaveTextContent('говорит')
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

describe('ConferenceRoom — аватар при выключенной камере после её включения (009 US1 T090)', () => {
  /** Собственный identity тикета — собственный и чужие тайлы симметричны. */
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

  /** Живое обновление track-рефов (мьют-переходы камеры useTracks). */
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

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) камера published+muted, экрана нет — аватар-заглушка с именем, VideoTrack не монтируется; речь — пульс бейджа (репродукция бага)', async () => {
    lkc.speakers = [{ identity: 'u:2:b' }]
    lkc.trackRefs = [
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-a', isMuted: true },
      },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b', isMuted: true },
      },
      // микрофон bob жив — «говорит» отражается пульсом бейджа (T100)
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'microphone',
        publication: { trackSid: 'mic-b' },
      },
    ]
    renderRoom()

    // «включил камеру → выключил»: трек published-but-muted — реф
    // НАСТОЯЩИЙ (не placeholder), но видео из него не рендерится —
    // тайл в аватар-заглушке T073, как при аудиозвонке
    const bobTile = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveClass('conference-tile-idle')
      return tile
    })
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    expect(bobTile.querySelector('video')).toBeNull()
    expect(bobTile).toHaveTextContent('bob')
    expect(bobTile.querySelector('.conference-tile-mic')).toHaveClass('conference-tile-mic-pulse')
    expect(bobTile).not.toHaveTextContent('говорит')
  })

  it('(б) камера published+unmuted — VideoTrack (регресс базового видео)', async () => {
    lkc.trackRefs = [
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-a', isMuted: false },
      },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b', isMuted: false },
      },
    ]
    renderRoom()

    const bobTile = await waitFor(() => tileByLabel('bob'))
    expect(bobTile.querySelector('video')).not.toBeNull()
    expect(bobTile.querySelector('.avatar')).toBeNull()
    expect(bobTile).not.toHaveClass('conference-tile-idle')
  })

  it('(в) камера published+muted + живая демонстрация — экран-видео с бейджем «демонстрирует экран», аватар не рендерится (приоритет T084)', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b', isMuted: true },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    renderRoom()

    // чужой клиент публикует оба трека (для собственного сочетание
    // недостижимо — T077/T085): тайл детерминированно показывает ЭКРАН
    const bobTile = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(tile).toHaveTextContent('демонстрирует экран')
      return tile
    })
    expect(bobTile.querySelector('video')).not.toBeNull()
    expect(bobTile.querySelector('.avatar')).toBeNull()
  })

  it('(г) переход muted→unmuted в одном тайле — тот же DOM-узел (ключ identity), видео бесшовно возвращается', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b', isMuted: true },
      },
    ]
    const view = renderRoom()
    const bobTile = await waitFor(() => tileByLabel('bob'))
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    expect(bobTile.querySelector('video')).toBeNull()

    // повторное включение — unmute того же published-трека: слитый тайл
    // жив, DOM-узел переиспользуется (T084), видео возвращается
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b', isMuted: false },
      },
    ]
    rerenderRoom(view)

    expect(tileByLabel('bob')).toBe(bobTile)
    expect(bobTile.querySelector('video')).not.toBeNull()
    expect(bobTile.querySelector('.avatar')).toBeNull()
  })

  it('(д) placeholder-реф (камера никогда не публиковалась) — аватар (регресс T073/T084-«в»)', async () => {
    lkc.trackRefs = [
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-a', isMuted: true },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    renderRoom()

    const bobTile = await waitFor(() => tileByLabel('bob'))
    expect(bobTile).toHaveClass('conference-tile-idle')
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    expect(bobTile.querySelector('video')).toBeNull()
  })

  it('(е) симметрия local/remote: замученная камера — аватар и в собственном тайле, и в чужом', async () => {
    lkc.trackRefs = [
      {
        participant: { identity: OWN_IDENTITY, name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-own', isMuted: true },
      },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b', isMuted: true },
      },
    ]
    renderRoom()

    // мьют локальной камеры — тот же published-but-muted трек:
    // собственный self-view тайл уходит в аватар симметрично чужим
    const ownTile = await waitFor(() => tileByLabel('alice'))
    expect(ownTile.querySelector('.avatar')).not.toBeNull()
    expect(ownTile.querySelector('video')).toBeNull()
    const bobTile = tileByLabel('bob')
    expect(bobTile.querySelector('.avatar')).not.toBeNull()
    expect(bobTile.querySelector('video')).toBeNull()
  })
})

describe('ConferenceRoom — индикатор микрофона и пометки ролей тайла (009 US1 T100)', () => {
  /** Собственный identity тикета — local-источник isMicrophoneEnabled. */
  const OWN_IDENTITY = 'u:11111111-1111-1111-1111-111111111111:conn-1'

  /** Дефолт local-состояния (восстанавливается вокруг каждого теста). */
  const OWN_MIC_ON = { localParticipant: { identity: OWN_IDENTITY }, isMicrophoneEnabled: true }

  /** Тайл по подписи-имени участника (спан имени в figcaption). */
  function tileByLabel(name: string): HTMLElement {
    const label = screen.getByText(name, { exact: false })
    const tile = label.closest('figure')
    if (tile === null) {
      throw new Error(`тайл участника «${name}» не найден`)
    }
    return tile
  }

  /** Бейдж микрофона тайла (T100: ровно один на КАЖДОМ тайле). */
  function micBadge(tile: HTMLElement): HTMLElement {
    const badge = tile.querySelector<HTMLElement>('.conference-tile-mic')
    if (badge === null) {
      throw new Error('бейдж микрофона тайла не найден')
    }
    return badge
  }

  /** metadata-JSON участника (§3: {"role":…,"guest":…} токена/№50). */
  function memberMetadata(role: 'moderator' | 'participant', guest: boolean): string {
    return JSON.stringify({ role, guest })
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

  /** Живое обновление track-рефов/спикеров/local-микрофона (rerender). */
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

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = []
    lkc.speakers = []
    lkc.local = OWN_MIC_ON
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    lkc.local = OWN_MIC_ON
  })

  it('(а) ровно ДВА состояния в обоих вариантах тайла: публикация незамучена — бейдж-вкл; isMuted/нет публикации — бейдж-выкл', async () => {
    // alice — видео-вариант (камера published), bob — idle-заглушка;
    // микрофонные публикации незамучены → бейдж-вкл у обоих
    lkc.trackRefs = [
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-a' },
      },
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'microphone',
        publication: { trackSid: 'mic-a' },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'microphone',
        publication: { trackSid: 'mic-b' },
      },
    ]
    const view = renderRoom()

    const aliceTile = await waitFor(() => {
      const tile = tileByLabel('alice')
      expect(tile.querySelector('video')).not.toBeNull()
      return tile
    })
    const bobTile = tileByLabel('bob')
    expect(bobTile).toHaveClass('conference-tile-idle')
    for (const tile of [aliceTile, bobTile]) {
      expect(micBadge(tile)).toHaveAttribute('data-mic', 'on')
      expect(within(tile).getByText('микрофон включён')).toBeTruthy()
    }

    // мьют удалённого микрофона (тумблер цели / серверный №49 — один
    // путь isMuted) и снятие публикации (мьют своим тумблером) —
    // бейдж гаснет в ОБОИХ вариантах тайла
    lkc.trackRefs = [
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'camera',
        publication: { trackSid: 'cam-a' },
      },
      {
        participant: { identity: 'u:1:a', name: 'alice' },
        source: 'microphone',
        publication: { trackSid: 'mic-a', isMuted: true },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    rerenderRoom(view)

    expect(micBadge(aliceTile)).toHaveAttribute('data-mic', 'off')
    expect(within(aliceTile).getByText('микрофон выключен')).toBeTruthy()
    expect(micBadge(bobTile)).toHaveAttribute('data-mic', 'off')
  })

  it('(б) собственный тайл — isMicrophoneEnabled useLocalParticipant (local-источник),remote — микрофонная публикация', async () => {
    // у собственного тайла микрофонная публикация незамучена, НО
    // isMicrophoneEnabled=false (тумблер MediaControls T028) —
    // local-состояние берётся из useLocalParticipant
    lkc.local = { localParticipant: { identity: OWN_IDENTITY }, isMicrophoneEnabled: false }
    lkc.trackRefs = [
      { participant: { identity: OWN_IDENTITY, name: 'alice' }, source: 'camera' },
      {
        participant: { identity: OWN_IDENTITY, name: 'alice' },
        source: 'microphone',
        publication: { trackSid: 'mic-own' },
      },
    ]
    const view = renderRoom()

    const ownTile = await waitFor(() => tileByLabel('alice'))
    expect(micBadge(ownTile)).toHaveAttribute('data-mic', 'off')
    expect(within(ownTile).getByText('микрофон выключен')).toBeTruthy()

    // повторное включение (тумблер) — бейдж зажигается живым рендером
    lkc.local = { localParticipant: { identity: OWN_IDENTITY }, isMicrophoneEnabled: true }
    rerenderRoom(view)

    expect(micBadge(tileByLabel('alice'))).toHaveAttribute('data-mic', 'on')
  })

  it('(в) пульс при речи — ТОЛЬКО у бейджа (speaking + mic on); «говорит» в DOM нет; свечение рамки не вводится', async () => {
    // bob говорит с включённым микрофоном — пульс у бейджа
    lkc.trackRefs = [
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'microphone',
        publication: { trackSid: 'mic-b' },
      },
    ]
    lkc.speakers = [{ identity: 'u:2:b' }]
    const view = renderRoom()

    const bobTile = await waitFor(() => {
      const tile = tileByLabel('bob')
      expect(micBadge(tile)).toHaveClass('conference-tile-mic-pulse')
      return tile
    })
    // текстовый индикатор «говорит» демонтирован целиком (DOM-level)
    expect(screen.queryByText('говорит')).toBeNull()
    expect(bobTile).not.toHaveTextContent('говорит')

    // speaking + микрофон ВЫКЛ — бейдж не пульсирует (два состояния,
    // речи без включённого микрофона визуально нет)
    lkc.trackRefs = [
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'microphone',
        publication: { trackSid: 'mic-b', isMuted: true },
      },
    ]
    rerenderRoom(view)

    const badge = micBadge(tileByLabel('bob'))
    expect(badge).toHaveAttribute('data-mic', 'off')
    expect(badge).not.toHaveClass('conference-tile-mic-pulse')
    expect(screen.queryByText('говорит')).toBeNull()
  })

  it('(г) пометки ролей в строке имени из metadata: «гость», «модератор», обе, обычный участник — без пометок; смена роли живая (№50)', async () => {
    // guest:true → «гость»; role:moderator → «модератор»; карол без
    // metadata — без пометок; alice-видео-вариант несёт пометку тоже
    lkc.trackRefs = [
      {
        participant: {
          identity: 'u:1:a',
          name: 'alice',
          metadata: memberMetadata('moderator', false),
        },
        source: 'camera',
        publication: { trackSid: 'cam-a' },
      },
      {
        participant: {
          identity: 'g:2:b',
          name: 'bob',
          metadata: memberMetadata('participant', true),
        },
        source: 'camera',
      },
      { participant: { identity: 'u:3:c', name: 'carol' }, source: 'camera' },
    ]
    const view = renderRoom()

    const aliceTile = await waitFor(() => {
      const tile = tileByLabel('alice')
      expect(tile.querySelector('video')).not.toBeNull()
      return tile
    })
    // формат прототипа «Имя · модератор» — и в видео-варианте тайла
    expect(aliceTile).toHaveTextContent('alice · модератор')
    const bobTile = tileByLabel('bob')
    expect(bobTile).toHaveTextContent('bob · гость')
    expect(bobTile).not.toHaveTextContent('модератор')
    // обычный участник без metadata — никаких пометок в строке имени
    const carolTile = tileByLabel('carol')
    expect(carolTile.textContent).not.toContain('·')
    expect(within(carolTile).queryByText('гость')).toBeNull()
    expect(within(carolTile).queryByText('модератор')).toBeNull()

    // гость-модератор — обе пометки рядом с именем
    lkc.trackRefs = [
      {
        participant: {
          identity: 'g:2:b',
          name: 'bob',
          metadata: memberMetadata('moderator', true),
        },
        source: 'camera',
      },
      { participant: { identity: 'u:3:c', name: 'carol' }, source: 'camera' },
    ]
    rerenderRoom(view)

    expect(tileByLabel('bob')).toHaveTextContent('bob · гость · модератор')

    // смена роли приезжает UpdateParticipant'ом метаданных (№50) —
    // тайл перерисовывается тем же participants/tracks-стримом
    lkc.trackRefs = [
      {
        participant: {
          identity: 'g:2:b',
          name: 'bob',
          metadata: memberMetadata('participant', true),
        },
        source: 'camera',
      },
      { participant: { identity: 'u:3:c', name: 'carol' }, source: 'camera' },
    ]
    rerenderRoom(view)

    const bobAfter = tileByLabel('bob')
    expect(bobAfter).toHaveTextContent('bob · гость')
    expect(bobAfter).not.toHaveTextContent('модератор')
  })

  it('(д) регресс слитой механики: бейдж не трогает «демонстрирует экран», пин и аватары T073/T090', async () => {
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      // bob: замученная камера + живая демонстрация (T090/T084) + микрофон
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'camera',
        publication: { trackSid: 'cam-b', isMuted: true },
      },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
      {
        participant: { identity: 'u:2:b', name: 'bob' },
        source: 'microphone',
        publication: { trackSid: 'mic-b' },
      },
    ]
    renderRoom()

    // автопин T078 разворачивает слитый тайл bob: экран-видео,
    // бейдж «демонстрирует экран» и бейдж микрофона — вместе
    const pinned = await waitFor(() => {
      const tile = document.querySelector<HTMLElement>('.conference-tile-pinned')
      expect(tile).not.toBeNull()
      return tile as HTMLElement
    })
    expect(pinned).toHaveTextContent('демонстрирует экран')
    expect(pinned.querySelector('video')).not.toBeNull()
    expect(micBadge(pinned)).toHaveAttribute('data-mic', 'on')

    // alice — idle-заглушка с аватаром T073 и бейджем (микрофона нет — выкл)
    const aliceTile = tileByLabel('alice')
    expect(aliceTile.querySelector('.avatar')).not.toBeNull()
    expect(micBadge(aliceTile)).toHaveAttribute('data-mic', 'off')

    // пин-механика T074 не перехватывается бейджем (pointer-events):
    // «Свернуть» развёрнутого тайла возвращает обычную сетку
    fireEvent.click(within(pinned).getByRole('button', { name: 'Свернуть' }))
    await waitFor(() => {
      expect(document.querySelector('.conference-tile-pinned')).toBeNull()
    })
    // двойной клик по чужому тайлу — пин работает (T074)
    fireEvent.dblClick(aliceTile)
    await waitFor(() => {
      expect(tileByLabel('alice')).toHaveClass('conference-tile-pinned')
    })
  })
})

describe('ConferenceRoom — каркас экрана: сайдбар участников справа (009 US1 T106)', () => {
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

  /** Кнопка сворачивания/разворачивания панели участников. */
  function panelToggle(): HTMLElement {
    return screen.getByRole('button', { name: /панель участников/ })
  }

  /** Рабочая область (flex-row: тайлы слева + сайдбар справа). */
  function mainArea(): HTMLElement {
    const main = document.querySelector<HTMLElement>('.conference-main')
    if (main === null) {
      throw new Error('.conference-main не смонтирована')
    }
    return main
  }

  /** Правая колонка сайдбара (контейнер слота). */
  function sideColumn(): HTMLElement {
    const side = document.querySelector<HTMLElement>('.conference-side')
    if (side === null) {
      throw new Error('.conference-side не смонтирована')
    }
    return side
  }

  function renderRoom(sidebar?: React.ReactNode): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
        sidebar={sidebar}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = [{ participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' }]
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) каркас: рабочая область .conference-main несёт сетку тайлов и колонку сайдбара; кнопка — aria-expanded/aria-controls/динамический aria-label', async () => {
    renderRoom(<div data-testid="side-slot" />)

    await waitFor(() => {
      expect(tileByLabel('alice')).not.toBeNull()
    })
    // сетка и сайдбар — дети ОДНОЙ рабочей области, сайдбар правее
    // (после сетки в DOM-порядке flex-row); controls — вне её, ниже
    // колонки комнаты (всегда внизу по центру, align-self:center)
    const main = mainArea()
    expect(main).toContainElement(tilesSection())
    expect(main).toContainElement(sideColumn())
    expect(main).toContainElement(screen.getByTestId('side-slot'))
    expect(tilesSection().compareDocumentPosition(sideColumn())).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    )
    expect(main).not.toContainElement(screen.getByTestId('controls-slot'))

    // полоса над рабочей областью: иконка-кнопка сшила состояния панелью
    const toggle = panelToggle()
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle.getAttribute('aria-controls')).toBe(sideColumn().id)
    expect(toggle).toHaveAttribute('aria-label', 'Свернуть панель участников')
    // панель по умолчанию развёрнута — hidden-гварда нет
    expect(sideColumn()).not.toHaveAttribute('hidden')
  })

  it('(б) кнопка сворачивает панель ([hidden] — тайлы занимают всю ширину) и разворачивает обратно; состояние живёт в комнате', async () => {
    renderRoom(<div data-testid="side-slot" />)
    await waitFor(() => {
      expect(tileByLabel('alice')).not.toBeNull()
    })

    fireEvent.click(panelToggle())

    const collapsed = panelToggle()
    expect(collapsed).toHaveAttribute('aria-expanded', 'false')
    expect(collapsed).toHaveAttribute('aria-label', 'Развернуть панель участников')
    expect(collapsed.getAttribute('aria-controls')).toBe(sideColumn().id)
    // свёрнутое состояние — колонка скрыта нативным [hidden] (гвард CSS
    // гасит flex-переопределение), сетка растягивается на всю ширину;
    // содержимое слота остаётся смонтированным (DOM жив)
    expect(sideColumn()).toHaveAttribute('hidden')
    expect(screen.getByTestId('side-slot')).toBeInTheDocument()

    // повторный клик — разворачивает обратно
    fireEvent.click(panelToggle())
    expect(panelToggle()).toHaveAttribute('aria-expanded', 'true')
    expect(panelToggle()).toHaveAttribute('aria-label', 'Свернуть панель участников')
    expect(sideColumn()).not.toHaveAttribute('hidden')
  })

  it('(в) свёрнутое состояние переживает живые изменения тайлов (стейт комнаты, не сетки)', async () => {
    const view = renderRoom(<div data-testid="side-slot" />)
    await waitFor(() => {
      expect(tileByLabel('alice')).not.toBeNull()
    })
    fireEvent.click(panelToggle())
    expect(sideColumn()).toHaveAttribute('hidden')

    // приход/уход участников не разворачивает панель обратно
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'camera' },
    ]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn()}
        sidebar={<div data-testid="side-slot" />}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
    await waitFor(() => {
      expect(tileByLabel('bob')).not.toBeNull()
    })
    expect(sideColumn()).toHaveAttribute('hidden')
    expect(panelToggle()).toHaveAttribute('aria-expanded', 'false')
  })

  it('(г) без слота сайдбара каркас деградирует: полосы и колонки нет, сетка одна в рабочей области', async () => {
    renderRoom()
    await waitFor(() => {
      expect(tileByLabel('alice')).not.toBeNull()
    })
    expect(document.querySelector('.conference-side')).toBeNull()
    expect(document.querySelector('.conference-toggle-row')).toBeNull()
    expect(screen.queryByRole('button', { name: /панель участников/ })).toBeNull()
    expect(mainArea()).toContainElement(tilesSection())
  })
})

describe('ConferenceRoom — уведомления комнаты под рабочей областью (009 US1/US5 T114)', () => {
  /** Элемент комнаты по селектору (с внятной ошибкой, если не смонтирован). */
  function roomElement(selector: string): HTMLElement {
    const element = document.querySelector<HTMLElement>(selector)
    if (element === null) {
      throw new Error(`${selector} не смонтирован`)
    }
    return element
  }

  /** Слот controls (fieldset MediaControls обеих точек входа). */
  function controlsSlot(): HTMLElement {
    return roomElement('fieldset.conference-controls')
  }

  function renderRoom(): ReturnType<typeof render> {
    return render(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn().mockResolvedValue(null)}
        reconnectWindowMs={60_000}
        reconnectRetryDelayMs={5_000}
        sidebar={<div data-testid="side-slot" />}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
  }

  /** Data-пакет шедулера T056 — тост conference.warning (T060). */
  function emitWarning(room: InstanceType<typeof lk.FakeRoom>): void {
    room.emit(
      lk.RoomEvent.DataReceived,
      new TextEncoder().encode(JSON.stringify({ type: 'conference.warning' })),
      undefined,
      undefined,
      'conference.warning',
    )
  }

  beforeEach(() => {
    lk.FakeRoom.instances.length = 0
    lkc.trackRefs = [{ participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' }]
    lkc.speakers = []
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) DOM-порядок детей колонки: полоса → .conference-main → статус/media/тост → controls (compareDocumentPosition)', async () => {
    renderRoom()
    const room = lastRoom()
    // отказ устройств (T032) — media-предупреждение в живой фазе
    room.micImpl = () => Promise.reject(new Error('NotAllowedError'))
    room.cameraImpl = () => Promise.reject(new Error('NotFoundError'))
    expect(
      await screen.findByText('Нет доступа к камере и микрофону — можно включить их в звонке'),
    ).toBeVisible()

    // тост conference.warning (T060 ← T056, FR-015)
    emitWarning(room)
    expect(await screen.findByText('Конференция завершится по лимиту длительности')).toBeVisible()

    // потеря связи (T060): фаза reconnecting сохраняет медиа-слой —
    // статус виден ОДНОВРЕМЕННО с рабочей областью и controls, между
    // ними (не отжимает рабочую область сверху)
    room.state = lk.ConnectionState.Disconnected
    room.emit(lk.RoomEvent.Disconnected)
    expect(await screen.findByText('Соединение потеряно — переподключение…')).toBeVisible()

    const toggleRow = roomElement('.conference-toggle-row')
    const main = roomElement('.conference-main')
    const status = roomElement('.conference-room-status')
    const media = roomElement('.conference-room-media')
    const toast = roomElement('.conference-warning-toast')
    const controls = controlsSlot()
    // T114: полоса и рабочая область — выше всех уведомлений
    expect(toggleRow.compareDocumentPosition(main)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(main.compareDocumentPosition(status)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(main.compareDocumentPosition(media)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(main.compareDocumentPosition(toast)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    // уведомления — выше всегда смонтированного controls (слот children)
    expect(status.compareDocumentPosition(controls)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(media.compareDocumentPosition(controls)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(toast.compareDocumentPosition(controls)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  it('(б) фаза failed без .conference-main в DOM — статус остаётся НАД controls', async () => {
    lk.FakeRoom.defaultConnectImpl = () => Promise.reject(new Error('signal failed'))
    try {
      renderRoom()

      const status = await screen.findByText('Не удалось подключиться к медиа-серверу')
      expect(document.querySelector('.conference-main')).toBeNull()
      // controls смонтирован всегда (слот children вне mediaVisible) —
      // «под conference-main (если есть)» выполняется тривиально
      const controls = controlsSlot()
      expect(status.compareDocumentPosition(controls)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    } finally {
      lk.FakeRoom.defaultConnectImpl = null
    }
  })

  it('(в) пин-оверлей T079 не меняет порядок: уведомления в DOM между main и controls — позиционирование CSS', async () => {
    const view = renderRoom()
    const room = lastRoom()
    await waitFor(() => {
      expect(room.connectCalls).toHaveLength(1)
    })
    emitWarning(room)
    expect(await screen.findByText('Конференция завершится по лимиту длительности')).toBeVisible()

    // живая демонстрация → автопин T078 → маркер T079 на корневом div
    lkc.trackRefs = [
      { participant: { identity: 'u:1:a', name: 'alice' }, source: 'camera' },
      { participant: { identity: 'u:2:b', name: 'bob' }, source: 'screen_share' },
    ]
    view.rerender(
      <ConferenceRoom
        conferenceId="c-1"
        ticket={ticket('jwt-1')}
        mode="video"
        refreshTicket={vi.fn().mockResolvedValue(null)}
        reconnectWindowMs={60_000}
        reconnectRetryDelayMs={5_000}
        sidebar={<div data-testid="side-slot" />}
      >
        <fieldset className="conference-controls" data-testid="controls-slot" />
      </ConferenceRoom>,
    )
    await waitFor(() => {
      expect(roomElement('.conference-room-pinned')).toBeVisible()
    })

    // для absolute-оверлеев T079 DOM-позиция не важна — тост остаётся
    // между main и controls (существующие правила CSS позиционируют)
    const main = roomElement('.conference-main')
    const toast = roomElement('.conference-warning-toast')
    expect(main.compareDocumentPosition(toast)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(toast.compareDocumentPosition(controlsSlot())).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  // Ранняя оболочка (room === null, эффект ещё не создал комнату) —
  // существующий гвард T027 без изменений: статус — единственный
  // ребёнок, main/controls отсутствуют; показ/закрытие тоста и снятие
  // media-предупреждения публикацией трека — существующие тесты
  // описок T060/T032 выше (ожидания не менялись).
})
