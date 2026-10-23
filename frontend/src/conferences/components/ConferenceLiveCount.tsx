/**
 * Живой счётчик подключённых участников (009, US1, T080): строка
 * «Участники: N / maxParticipants» joined-состояния — живая
 * проекция присутствия медиа-плоскости (useParticipants: [local,
 * ...remote] — тот же источник истины, что панель «Участники (N)»
 * T041; конституция III, без дублирования состояния), а не застывший
 * снимок №45 на момент монтирования (пакет №45+№46 грузится один раз
 * — приход/уход участников его не меняет, две цифры на одном экране
 * расходились). Семантика числа совпадает с activeParticipants №45 —
 * подключения медиа-плоскости (квота per-connection: две вкладки
 * одного пользователя = 2 слота, edge spec.md); maxParticipants —
 * пропс из уже загруженного №45-вида, новый запрос не нужен.
 *
 * Рендерится в слоте children ConferenceRoom (внутри RoomContext —
 * хуку нужна живая комната), первым элементом слота — над панелями.
 * До входа (pre-join) и после выхода строки нет вовсе (T087: шапка
 * со статическим снимком №45 удалена): реалтайм-канала у
 * не-подключённого нет (SSE №18 несёт только
 * conference.started/conference.ended, livekit-integration §7),
 * поллинг №45 сознательно не вводится — после «Войти» счёт сразу
 * точен из LiveKit. Гостевая страница счётчик не монтирует
 * (№45-вид гостю недоступен до №54/№55): гость видит живое число в
 * ParticipantsPanel (T041). Полноэкранный пин-оверлей T079 скрывает
 * строку вместе с другими служебными панелями (conferences.css,
 * body:has(.conference-room-pinned)).
 */
import { useParticipants } from '@livekit/components-react'
import '../conferences.css'

export interface ConferenceLiveCountProps {
  /** Потолок квоты конференции (№45 view.maxParticipants — без нового запроса). */
  readonly maxParticipants: number
}

/** Чистая проекция useParticipants — без локальных состояний (конституция III). */
export default function ConferenceLiveCount({ maxParticipants }: ConferenceLiveCountProps) {
  const participants = useParticipants()
  return (
    <p className="conference-live-count">
      Участники: {participants.length} / {maxParticipants}
    </p>
  )
}
