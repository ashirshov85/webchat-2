/**
 * Accessible presence indicator (feature 007, T021; research.md §E1,
 * clarify a11y 2026-10-01): one shared dot for all three surfaces —
 * «Чаты» direct rows (T022), the 1:1 dialog header (T022) and the
 * «Контакты» list (T022). The dot ALWAYS carries an aria-label of the
 * state («онлайн» / «офлайн» / «неизвестно») so screen readers and
 * color-blind users get the signal; a visible TEXT label is rendered
 * only in the 1:1 dialog header mode (`showLabel`).
 *
 * The neutral «unknown» is used BOTH before the first №36 snapshot
 * and for №36 `unknown` («нет доступа», FR-006/FR-007): «нет доступа»
 * must stay indistinguishable from «no data yet», and a false
 * «офлайн» before data is a spec violation. `null`/`undefined`
 * userId (group chats, which carry no presence UI) also renders
 * neutral.
 *
 * The status comes from `usePresenceStatus` (T020): the surface is
 * registered for №36 backfill there and re-renders on every
 * `presence.updated` merge — this component owns only presentation.
 */
import { usePresenceStatus } from './usePresence'
import type { PresenceStatus } from './presenceStore'

/** aria/текстовые метки состояний (единые для точки и подписи). */
const PRESENCE_LABELS: Readonly<Record<PresenceStatus, string>> = {
  online: 'онлайн',
  offline: 'офлайн',
  unknown: 'неизвестно',
}

export interface PresenceIndicatorProps {
  /** Собеседник поверхности; null/undefined — нейтрально (группы). */
  readonly userId: string | null | undefined
  /** Видимая текстовая метка рядом с точкой — только заголовок 1:1 (T022). */
  readonly showLabel?: boolean
}

export function PresenceIndicator({ userId, showLabel = false }: PresenceIndicatorProps) {
  const status = usePresenceStatus(userId)
  const label = PRESENCE_LABELS[status]
  return (
    <span className={`presence-indicator presence-${status}`}>
      <span role="img" aria-label={label} className="presence-indicator-dot" />
      {showLabel && <span className="presence-indicator-label">{label}</span>}
    </span>
  )
}
