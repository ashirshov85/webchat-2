/**
 * Время и даты «Aethergram» (feature 008, T009; FR-016, FR-019, research §J,
 * design-tokens §6): время сообщений ЧЧ:ММ и разделители дат «19 сентября»
 * (+год при отличии от текущего) через Intl ru-RU; русская плюрализация —
 * дословно из прототипа specs/008-chat-window-styling/design/chats.html
 * (pluralRu).
 */

const timeFormatter = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' })
const dateFormatter = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' })
const dateFormatterWithYear = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
})

/** Аргумент времени/даты: Date, ISO-строка или epoch-миллисекунды. */
type DateInput = Date | string | number

function toDate(value: DateInput): Date {
  return value instanceof Date ? value : new Date(value)
}

/** Время ЧЧ:ММ (24-часовое, Intl ru-RU) из createdAt; невалидная дата — ''. */
export function formatTime(value: DateInput): string {
  const date = toDate(value)
  if (Number.isNaN(date.getTime())) return ''
  return timeFormatter.format(date)
}

/**
 * Разделитель дат — длинный формат «19 сентября» (day:'numeric',
 * month:'long'); год добавляется при отличии от текущего (design-tokens §6).
 */
export function formatDate(value: DateInput, now: Date = new Date()): string {
  const date = toDate(value)
  if (Number.isNaN(date.getTime())) return ''
  const formatter = date.getFullYear() === now.getFullYear() ? dateFormatter : dateFormatterWithYear
  return formatter.format(date)
}

/**
 * Русская плюрализация (алгоритм прототипа):
 * pluralRu(2, 'участник', 'участника', 'участников') → 'участника'.
 */
export function pluralRu(n: number, one: string, few: string, many: string): string {
  const m10 = n % 10
  const m100 = n % 100
  if (m10 === 1 && m100 !== 11) return one
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few
  return many
}
