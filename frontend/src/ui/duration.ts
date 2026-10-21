/**
 * Длительность завершённой конференции (009, US5, T071 — частичный
 * пересмотр Q20=A «без деталей, как долго прошла»; T076 — расширение
 * на все карточки окна ленты): компактная подпись «N мин» /
 * «N ч M мин» («менее минуты» при < 60 с) из уже доступных полей
 * контракта — пары startedAt/endedAt №45-вида (клик-проверка,
 * фоновый чек карточек окна — T076, ≤1 №45 на конференцию за сессию)
 * или live-среза (endedAt кадра №18 + startedAt бейджа T059; карточки
 * tombstone). Новые API/поля НЕ вводятся; ядро Q20=A неизменно —
 * история чата не дополняется и не изменяется, показ длительности —
 * клиентская надстройка терминальных состояний. Без выдумывания: нет
 * обоих timestamp'ов / невалидные / инвертированный интервал → null.
 */

/** Пара timestamp'ов терминального состояния (поля ConferenceView / tombstone live-среза). */
export interface DurationSpan {
  readonly startedAt?: string
  readonly endedAt?: string
}

/** Компактная подпись длительности из миллисекунд: «менее минуты» / «N мин» / «N ч M мин». */
export function formatDuration(totalMs: number): string {
  if (totalMs < 60_000) {
    return 'менее минуты'
  }
  const totalMinutes = Math.floor(totalMs / 60_000)
  if (totalMinutes < 60) {
    return `${totalMinutes} мин`
  }
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return `${hours} ч ${minutes} мин`
}

/**
 * Длительность по паре startedAt/endedAt (конференция началась и
 * завершилась): null — данных нет (никогда не начинавшаяся —
 * startedAt отсутствует), живая (нет endedAt), невалидные значения
 * или инвертированный интервал — подпись не выдумывается.
 */
export function formatSpanDuration(span: DurationSpan | null | undefined): string | null {
  if (span === null || span === undefined) {
    return null
  }
  const { startedAt, endedAt } = span
  if (startedAt === undefined || endedAt === undefined) {
    return null
  }
  const started = new Date(startedAt).getTime()
  const ended = new Date(endedAt).getTime()
  if (Number.isNaN(started) || Number.isNaN(ended) || ended < started) {
    return null
  }
  return formatDuration(ended - started)
}

/**
 * Терминальная подпись «Конференция завершена» с опциональной
 * длительностью (единый формат поверхностей T071): null — прежняя
 * подпись без деталей (деривация T070, живой кадр без timestamp'ов).
 */
export function endedConferenceLabel(duration: string | null): string {
  return duration === null ? 'Конференция завершена' : `Конференция завершена · ${duration}`
}
