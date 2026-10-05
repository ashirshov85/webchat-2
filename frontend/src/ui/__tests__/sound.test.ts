import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { IncomingMessageEvent } from '../sound'

/** «динь-динь» = 2 ноты × 3 парциала (research §F) — 6 осцилляторов на сигнал. */
const CHIME_OSCILLATOR_COUNT = 6
const FIRST_NOTE_HZ = 1050
const SECOND_NOTE_HZ = 1400
const SECOND_NOTE_DELAY_S = 0.1
const PARTIAL_RATIOS = [1, 2.76, 5.4]

const ME = '11111111-1111-1111-1111-111111111111'
const PEER = '22222222-2222-2222-2222-222222222222'

class FakeAudioParam {
  value: number
  readonly exponentialRampCalls: number[] = []
  readonly targetCalls: number[] = []

  constructor(initialValue: number) {
    this.value = initialValue
  }

  setValueAtTime(value: number): void {
    this.value = value
  }

  exponentialRampToValueAtTime(value: number): void {
    this.exponentialRampCalls.push(value)
  }

  setTargetAtTime(value: number): void {
    this.targetCalls.push(value)
  }
}

class FakeOscillatorNode {
  type = ''
  readonly frequency = new FakeAudioParam(440)
  readonly connections: unknown[] = []
  readonly startCalls: number[] = []

  connect(destination: unknown): unknown {
    this.connections.push(destination)
    return destination
  }

  disconnect(): void {}

  start(when = 0): void {
    this.startCalls.push(when)
  }

  stop(): void {}
}

class FakeGainNode {
  readonly gain = new FakeAudioParam(1)
  readonly connections: unknown[] = []

  connect(destination: unknown): unknown {
    this.connections.push(destination)
    return destination
  }

  disconnect(): void {}
}

class FakeAudioContext {
  static readonly instances: FakeAudioContext[] = []

  readonly oscillators: FakeOscillatorNode[] = []
  readonly gains: FakeGainNode[] = []
  readonly destination: Record<string, never> = {}
  resumeCalls = 0
  state = 'suspended'
  currentTime = 0

  constructor() {
    FakeAudioContext.instances.push(this)
  }

  resume(): Promise<void> {
    this.resumeCalls += 1
    this.state = 'running'
    return Promise.resolve()
  }

  close(): Promise<void> {
    return Promise.resolve()
  }

  createOscillator(): FakeOscillatorNode {
    const oscillator = new FakeOscillatorNode()
    this.oscillators.push(oscillator)
    return oscillator
  }

  createGain(): FakeGainNode {
    const gain = new FakeGainNode()
    this.gains.push(gain)
    return gain
  }
}

type SoundModule = typeof import('../sound')

let sound: SoundModule

beforeEach(async () => {
  vi.resetModules()
  FakeAudioContext.instances.length = 0
  vi.stubGlobal('AudioContext', FakeAudioContext)
  sound = await import('../sound')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const incomingEvent = (chatId: string, senderId: string = PEER): IncomingMessageEvent => ({
  chatId,
  message: { senderId },
})

const singleContext = (): FakeAudioContext => {
  expect(FakeAudioContext.instances).toHaveLength(1)
  const context = FakeAudioContext.instances[0]
  expect(context).toBeDefined()
  return context as FakeAudioContext
}

const frequenciesOf = (oscillators: FakeOscillatorNode[]): number[] =>
  oscillators.map((oscillator) => oscillator.frequency.value).sort((a, b) => a - b)

/** Базы нот раскладываются в партиалы 1 : 2.76 : 5.4 (сортировка по частоте). */
const expectPartialFrequencies = (oscillators: FakeOscillatorNode[], noteBases: number[]): void => {
  expect(oscillators).toHaveLength(noteBases.length * PARTIAL_RATIOS.length)
  const expected = PARTIAL_RATIOS.flatMap((ratio) => noteBases.map((base) => base * ratio)).sort(
    (a, b) => a - b,
  )
  const actual = frequenciesOf(oscillators)
  expected.forEach((wanted, index) => {
    expect(actual[index]).toBeCloseTo(wanted, 3)
  })
}

const gainOf = (oscillator: FakeOscillatorNode): FakeGainNode => {
  const gain = oscillator.connections.find(
    (node): node is FakeGainNode => node instanceof FakeGainNode,
  )
  expect(gain, 'каждый парциал проходит через gain').toBeDefined()
  return gain as FakeGainNode
}

const oscillatorsStartingNear = (
  context: FakeAudioContext,
  startTime: number,
): FakeOscillatorNode[] =>
  context.oscillators.filter((oscillator) =>
    oscillator.startCalls.some((when) => Math.abs(when - startTime) < 1e-3),
  )

describe('синтез «динь-динь» — WebAudio без файлов (FR-028, research §F)', () => {
  it('AudioContext ленив — создаётся только при первом сигнале и переиспользуется', () => {
    expect(FakeAudioContext.instances).toHaveLength(0)
    sound.playBellTone()
    sound.playBellTone()
    expect(FakeAudioContext.instances).toHaveLength(1)
    expect(singleContext().oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT * 2)
  })

  it('три синус-парциала 1 : 2.76 : 5.4 на ноту — 6 осцилляторов на сигнал', () => {
    sound.playBellTone()
    const context = singleContext()
    expect(context.oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT)
    for (const oscillator of context.oscillators) {
      expect(oscillator.type).toBe('sine')
    }
    expectPartialFrequencies(context.oscillators, [FIRST_NOTE_HZ, SECOND_NOTE_HZ])
  })

  it('ноты 1050 Гц на t=0 и 1400 Гц на t+0.1 с', () => {
    sound.playBellTone()
    const context = singleContext()
    const firstNote = oscillatorsStartingNear(context, 0)
    const secondNote = oscillatorsStartingNear(context, SECOND_NOTE_DELAY_S)
    expectPartialFrequencies(firstNote, [FIRST_NOTE_HZ])
    expectPartialFrequencies(secondNote, [SECOND_NOTE_HZ])
  })

  it('каждый парциал гасится экспоненциально и сводится в destination', () => {
    sound.playBellTone()
    const context = singleContext()
    for (const oscillator of context.oscillators) {
      const gain = gainOf(oscillator)
      const decayCalls = gain.gain.exponentialRampCalls.length + gain.gain.targetCalls.length
      expect(decayCalls, 'экспоненциальное затухание парциала').toBeGreaterThan(0)
    }
    expect(
      context.gains.some((gain) => gain.connections.includes(context.destination)),
      'граф сигнала сходится в destination',
    ).toBe(true)
  })

  it('suspended-контекст будится resume(); в running повторного вызова нет', () => {
    sound.playBellTone()
    const context = singleContext()
    expect(context.state).toBe('suspended')
    expect(context.resumeCalls).toBe(1)
    sound.playBellTone()
    expect(context.resumeCalls).toBe(1)
  })
})

describe('реальное время — один сигнал на входящее событие любого чата (FR-028)', () => {
  it('входящее от собеседника — ровно один сигнал', () => {
    sound.chimeOnRealtimeIncoming(incomingEvent('chat-a'), ME)
    expect(singleContext().oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT)
  })

  it('фоновые чаты звучат одинаково — chatId не фильтруется', () => {
    sound.chimeOnRealtimeIncoming(incomingEvent('chat-background-1'), ME)
    sound.chimeOnRealtimeIncoming(incomingEvent('chat-background-2'), ME)
    sound.chimeOnRealtimeIncoming(incomingEvent('chat-background-3'), ME)
    expect(singleContext().oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT * 3)
  })

  it('1:1 — подряд в одном чате три события это три сигнала, коалесцирования нет', () => {
    for (let i = 0; i < 3; i += 1) {
      sound.chimeOnRealtimeIncoming(incomingEvent('chat-a'), ME)
    }
    expect(singleContext().oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT * 3)
  })

  it('собственное сообщение (senderId === me) — сигнала нет (отправка молчит)', () => {
    sound.chimeOnRealtimeIncoming(incomingEvent('chat-a', ME), ME)
    expect(FakeAudioContext.instances).toHaveLength(0)
  })
})

describe('массовая доставка — ровно один сигнал на пакет (FR-028, коалесцирование)', () => {
  it('батч из 37 пропущенных входящих — ровно один сигнал', () => {
    sound.chimeOnSyncBatch(37)
    expect(singleContext().oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT)
  })

  it('батч из одного входящего — тоже один сигнал', () => {
    sound.chimeOnSyncBatch(1)
    expect(singleContext().oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT)
  })

  it('пустой цикл синхронизации (0 входящих) — сигнала нет', () => {
    sound.chimeOnSyncBatch(0)
    expect(FakeAudioContext.instances).toHaveLength(0)
  })

  it('два последовательных цикла доставки — по одному сигналу на цикл', () => {
    sound.chimeOnSyncBatch(12)
    sound.chimeOnSyncBatch(4)
    expect(singleContext().oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT * 2)
  })
})

describe('история/пагинация/отправка — без сигнала (FR-028, Clarification)', () => {
  it('поверхность модуля — только приём: триггеров истории/пагинации/отправки нет', () => {
    expect(Object.keys(sound).sort()).toEqual([
      'chimeOnRealtimeIncoming',
      'chimeOnSyncBatch',
      'playBellTone',
    ])
  })
})

describe('запрет автозвука — молчаливый пропуск, функциональность не страдает (FR-028)', () => {
  it('AudioContext недоступен — тихий пропуск без исключения', () => {
    vi.stubGlobal('AudioContext', undefined)
    expect(() => sound.playBellTone()).not.toThrow()
    expect(() => sound.chimeOnRealtimeIncoming(incomingEvent('chat-a'), ME)).not.toThrow()
    expect(() => sound.chimeOnSyncBatch(5)).not.toThrow()
  })

  it('создание контекста падает (autoplay policy) — исключение не распространяется', () => {
    class BlockedAudioContext {
      constructor() {
        throw new DOMException('autoplay blocked', 'NotAllowedError')
      }
    }
    vi.stubGlobal('AudioContext', BlockedAudioContext)
    expect(() => sound.playBellTone()).not.toThrow()
    expect(() => sound.chimeOnRealtimeIncoming(incomingEvent('chat-a'), ME)).not.toThrow()
  })

  it('resume() отклонён — молчаливый пропуск без необработанного отказа', async () => {
    class SuspendedAudioContext extends FakeAudioContext {
      override resume(): Promise<void> {
        this.resumeCalls += 1
        return Promise.reject(new DOMException('resume denied', 'NotAllowedError'))
      }
    }
    vi.stubGlobal('AudioContext', SuspendedAudioContext)
    expect(() => sound.playBellTone()).not.toThrow()
    await new Promise((resolve) => setTimeout(resolve, 0))
  })

  it('ошибка WebAudio-узла (start бросает) — молчаливый try/catch', () => {
    class ThrowingStartAudioContext extends FakeAudioContext {
      override createOscillator(): FakeOscillatorNode {
        const oscillator = super.createOscillator()
        oscillator.start = (): void => {
          throw new Error('node boom')
        }
        return oscillator
      }
    }
    vi.stubGlobal('AudioContext', ThrowingStartAudioContext)
    expect(() => sound.playBellTone()).not.toThrow()
  })

  it('после молчаливого пропуска следующий сигнал звучит', async () => {
    class BlockedAudioContext {
      constructor() {
        throw new Error('blocked')
      }
    }
    vi.stubGlobal('AudioContext', BlockedAudioContext)
    expect(() => sound.playBellTone()).not.toThrow()
    vi.resetModules()
    vi.stubGlobal('AudioContext', FakeAudioContext)
    const fresh = await import('../sound')
    fresh.playBellTone()
    expect(singleContext().oscillators).toHaveLength(CHIME_OSCILLATOR_COUNT)
  })
})
