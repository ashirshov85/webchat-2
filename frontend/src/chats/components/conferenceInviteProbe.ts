/**
 * Сессионный кэш фонового №45-чека карточки-приглашения (009, US5,
 * T070): «не более одного №45 на конференцию за сессию» — карта
 * conferenceId → исход чека модульного уровня (живёт всю SPA-сессию,
 * переживает размонтирования ленты и переключения чатов). T076
 * расширил охват чека с новейшей карточки на ВСЕ карточки окна ленты
 * (кроме tombstone) — бюджет «≤1 на конференцию за сессию» держит
 * этот же кэш без правок: повторные монтажи, переключения чатов и
 * пагинация восстанавливают исход+span без запроса.
 *
 * Исходы: `ended` — конференция завершена (терминальное состояние
 * карточки восстанавливается при повторном монтаже БЕЗ нового
 * запроса); `missing` — Problem `conference_not_found` («Конференция
 * недоступна», как клик-ветка); `live` — `active`/`created`/транспортный
 * отказ (кнопка остаётся, клик-проверка №45 — страховка; ретрай-цикл
 * не вводится, поэтому и отказ кэшируется — строго один запрос на id
 * за сессию). Живые конференции дальше сходятся кадрами №18 (T069).
 *
 * T071: исход `ended` несёт span (startedAt/endedAt №45-ответа) —
 * длительность терминальной подписи карточки; повторный монтаж
 * восстанавливает её из кэша тем же значением. T076: span получают и
 * деривационные карточки (терминальные «одна активная на чат» БЕЗ
 * запроса) — их собственный фоновый №45 нужен только ради длительности.
 *
 * Файл без компонентов: только кэш; карточка (ConferenceInviteCard)
 * спрашивает кэш при монтаже и remembers исход по завершении чека.
 */
import type { DurationSpan } from '../../ui/duration'

export type ConferenceInviteProbeOutcome = 'ended' | 'missing' | 'live'

/** Осевший исход чека: outcome + span завершённой конференции (T071). */
export interface ConferenceInviteProbeRecord {
  readonly outcome: ConferenceInviteProbeOutcome
  /** startedAt/endedAt №45-ответа (только outcome='ended'). */
  readonly span?: DurationSpan
}

const probedConferences = new Map<string, ConferenceInviteProbeRecord>()

/**
 * Осевший исход чека конференции (`undefined` — чека ещё не было).
 * Только settled-значения: ожидающийся запрос сюда не попадает.
 */
export function cachedInviteProbeOutcome(
  conferenceId: string,
): ConferenceInviteProbeRecord | undefined {
  return probedConferences.get(conferenceId)
}

/** Зафиксировать исход фонового чека (идемпотентно). */
export function rememberInviteProbeOutcome(
  conferenceId: string,
  record: ConferenceInviteProbeRecord,
): void {
  probedConferences.set(conferenceId, record)
}

/** Сброс кэша — только тестам (изоляция сессии между наборами). */
export function resetInviteProbeCache(): void {
  probedConferences.clear()
}
