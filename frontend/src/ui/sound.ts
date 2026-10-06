/**
 * «Латунный звоночек» приёма — WebAudio-синтез без аудиофайлов и без
 * настроек (feature 008, US5; FR-028, research §F, ui-behavior §6):
 * «динь-динь» из двух нот (1050 Гц на t=0, 1400 Гц на t+0.1 с), каждая
 * нота — три синус-парциала с отношением 1 : 2.76 : 5.4 и экспоненциальным
 * затуханием гейна (партиалы прототипа chats.html, bellTone/sndBell).
 * AudioContext создаётся лениво при первом сигнале и переиспользуется
 * дальше; при suspended вызывается resume() (отказ гасится .catch —
 * необработанного rejection нет). ЛЮБАЯ ошибка — запрет автозвука до
 * первого действия пользователя, отсутствие AudioContext (включая
 * webkit-префикс старых Safari), сбой узла — глотается молчаливым
 * try/catch: сигнал пропускается, функциональность не страдает; контекст
 * при этом не кэшируется — следующая попытка стартует заново.
 *
 * Триггеры — только приём («всегда включено», персистентности нет):
 *  - реальное время — chimeOnRealtimeIncoming: один сигнал на событие
 *    message.created с senderId ≠ me ЛЮБОГО чата, включая фоновые
 *    (T064 подписывается onMessageCreated(null, …) — демультиплексор
 *    useRealtime отдаёт события всех диалогов);
 *  - массовая доставка после разрыва — chimeOnSyncBatch: РОВНО один
 *    сигнал на пакет (коалесцирование): цикл catch-up (useSync →
 *    applySyncUpdate) считает входящие и вызывает с итогом цикла,
 *    ≥1 → сигнал;
 *  - начальная загрузка истории и пагинация (FR-020), а также отправка
 *    сигналом НЕ сопровождаются: отправка подтверждается только визуально
 *    (пузырь в ленте и штампы доставки, Clarification) — путей триггера
 *    для этих путей модуль не выставляет.
 *
 * Контракт закреплён тестами __tests__/sound.test.ts (T061, TDD-красные
 * до реализации); подключение к событиям 005 — T064.
 */

/** Минимальная структура события message.created (№18), достаточная звуку. */
export interface IncomingMessageEvent {
  /** Чат события: звуку не фильтруется — сигнал звучит и для фоновых чатов. */
  readonly chatId: string
  readonly message: {
    /** Отправитель: собственные сообщения (senderId === me) сигналом не сопровождаются. */
    readonly senderId: string
  }
}

/** Партиалы колокольчика: множитель частоты × доля громкости (research §F). */
const PARTIALS: ReadonlyArray<readonly [number, number]> = [
  [1, 1],
  [2.76, 0.4],
  [5.4, 0.2],
]

/** «Динь-динь»: частота Гц × сдвиг от t (с) × длительность (с) × громкость. */
const NOTES: ReadonlyArray<readonly [number, number, number, number]> = [
  [1050, 0, 0.9, 0.06],
  [1400, 0.1, 1.1, 0.045],
]

/** Экспоненциальный «пол» затухания парциала и хвост после него (прототип). */
const DECAY_FLOOR = 0.0008
const STOP_TAIL_S = 0.02

type AudioContextCtor = new () => AudioContext

/** Ленивый единственный контекст — создаётся при первом сигнале (T061). */
let audioCtx: AudioContext | null = null

const createAudioContext = (): AudioContext | null => {
  const legacy = (window as Window & { webkitAudioContext?: AudioContextCtor }).webkitAudioContext
  const Ctor: AudioContextCtor | undefined =
    typeof AudioContext !== 'undefined' ? AudioContext : legacy
  return Ctor === undefined ? null : new Ctor()
}

/** Нота = три синус-парциала 1 : 2.76 : 5.4 с экспоненциальным затуханием (bellTone прототипа). */
const bellTone = (
  ctx: AudioContext,
  freqHz: number,
  startS: number,
  durS: number,
  volume: number,
): void => {
  for (const [ratio, amplitude] of PARTIALS) {
    const oscillator = ctx.createOscillator()
    const gain = ctx.createGain()
    oscillator.type = 'sine'
    oscillator.frequency.value = freqHz * ratio
    gain.gain.setValueAtTime(volume * amplitude, startS)
    gain.gain.exponentialRampToValueAtTime(DECAY_FLOOR, startS + durS)
    oscillator.connect(gain).connect(ctx.destination)
    oscillator.start(startS)
    oscillator.stop(startS + durS + STOP_TAIL_S)
  }
}

/** Сыграть «динь-динь» — чистый WebAudio-синтез; все ошибки молчаливы (FR-028). */
export const playBellTone = (): void => {
  try {
    audioCtx ??= createAudioContext()
    if (audioCtx === null) return
    if (audioCtx.state === 'suspended') {
      void audioCtx.resume().catch(() => undefined)
    }
    const now = audioCtx.currentTime
    for (const [freqHz, delayS, durS, volume] of NOTES) {
      bellTone(audioCtx, freqHz, now + delayS, durS, volume)
    }
  } catch {
    /* запрет автозвука / сбой WebAudio-узла — молчаливый пропуск (FR-028) */
  }
}

/** Реальное время: ровно один сигнал на входящее событие любого чата (FR-028). */
export const chimeOnRealtimeIncoming = (event: IncomingMessageEvent, meUserId: string): void => {
  if (event.message.senderId === meUserId) return
  playBellTone()
}

/** Массовая доставка: ровно один сигнал на пакет при incomingCount ≥ 1 (FR-028). */
export const chimeOnSyncBatch = (incomingCount: number): void => {
  if (incomingCount < 1) return
  playBellTone()
}
