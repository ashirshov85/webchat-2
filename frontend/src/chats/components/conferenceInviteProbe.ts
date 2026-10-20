/**
 * Сессионный кэш фонового №45-чека карточки-приглашения (009, US5,
 * T070): «не более одного №45 на конференцию за сессию» — карта
 * conferenceId → исход чека модульного уровня (живёт всю SPA-сессию,
 * переживает размонтирования ленты и переключения чатов).
 *
 * Исходы: `ended` — конференция завершена (терминальное состояние
 * карточки восстанавливается при повторном монтаже БЕЗ нового
 * запроса); `missing` — Problem `conference_not_found` («Конференция
 * недоступна», как клик-ветка); `live` — `active`/`created`/транспортный
 * отказ (кнопка остаётся, клик-проверка №45 — страховка; ретрай-цикл
 * не вводится, поэтому и отказ кэшируется — строго один запрос на id
 * за сессию). Живые конференции дальше сходятся кадрами №18 (T069).
 *
 * Файл без компонентов: только кэш; карточка (ConferenceInviteCard)
 * спрашивает кэш при монтаже и remembers исход по завершении чека.
 */
export type ConferenceInviteProbeOutcome = 'ended' | 'missing' | 'live'

const probedConferences = new Map<string, ConferenceInviteProbeOutcome>()

/**
 * Осевший исход чека конференции (`undefined` — чека ещё не было).
 * Только settled-значения: ожидающийся запрос сюда не попадает.
 */
export function cachedInviteProbeOutcome(
  conferenceId: string,
): ConferenceInviteProbeOutcome | undefined {
  return probedConferences.get(conferenceId)
}

/** Зафиксировать исход фонового чека (идемпотентно). */
export function rememberInviteProbeOutcome(
  conferenceId: string,
  outcome: ConferenceInviteProbeOutcome,
): void {
  probedConferences.set(conferenceId, outcome)
}

/** Сброс кэша — только тестам (изоляция сессии между наборами). */
export function resetInviteProbeCache(): void {
  probedConferences.clear()
}
