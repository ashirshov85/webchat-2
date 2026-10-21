/**
 * Гостевая ссылка конференции (009, US4, T081 — UI модератора №52/№53):
 * подпись срока действия из expiresAt GuestInviteView локальным
 * временем наблюдателя — сегодня → «до ЧЧ:ММ», иначе «до {дата}, ЧЧ:ММ»
 * (+год при отличии от текущего); форматы — общие ui/time (Intl ru-RU,
 * паттерн lastSeenFormat). Невалидное значение — null: подпись не
 * выдумывается (паритет T071 ui/duration). Новых API/полей нет —
 * expiresAt уже в контракте №52.
 */
import { formatDate, formatTime } from '../ui/time'

/**
 * Подпись срока «до …» для блока «Гостевая ссылка» (полный текст
 * «Действует до …» собирает компонент): null — невалидный expiresAt,
 * строка срока не выводится.
 */
export function inviteExpiresLabel(expiresAt: string, now: Date = new Date()): string | null {
  const date = new Date(expiresAt)
  if (Number.isNaN(date.getTime())) {
    return null
  }
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate()
  return sameDay ? `до ${formatTime(date)}` : `до ${formatDate(date, now)}, ${formatTime(date)}`
}
