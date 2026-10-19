/**
 * Бейдж «Звонок идёт» (009, US5, T059; FR-013, US5-1,
 * contracts/realtime-conference-events.md §1 «действие клиента»):
 * индикатор активной конференции чата — строки «Чатов»
 * (ChatListItem) и заголовок открытого окна (ChatHeader).
 * Компонент — чистая проекция: рендерится ТОЛЬКО когда звонок
 * идёт (родитель решает по срезу useConferenceRealtime), режим
 * №44 задаёт иконку (камера/трубка). Пульс иконки — состояние
 * дублируется не только цветом (текст при компоненте всегда,
 * конвенция 008); в историю чата индикатор не пишется (Q20=A).
 */
import type { ConferenceMode } from '../api/conferences'
import '../conferences.css'

export interface ConferenceBadgeProps {
  /** Стартовые дефолты №44 — иконка индикатора (камера/трубка). */
  readonly mode?: ConferenceMode
}

/** Тултип строки: полный текст индикатора (конвенция 008). */
const BADGE_TITLE: Record<ConferenceMode, string> = {
  video: 'Идёт видеозвонок',
  audio: 'Идёт аудиозвонок',
}

export function ConferenceBadge({ mode = 'video' }: ConferenceBadgeProps) {
  return (
    <span className="conference-badge" title={BADGE_TITLE[mode]}>
      {mode === 'video' ? (
        <svg viewBox="0 0 24 24" aria-hidden="true" className="conference-badge-icon">
          <rect x="2" y="6" width="13" height="12" rx="2" />
          <path d="M15 10.5 20.5 7.5v9L15 13.5z" />
        </svg>
      ) : (
        <svg viewBox="0 0 24 24" aria-hidden="true" className="conference-badge-icon">
          <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6A19.79 19.79 0 0 1 2.08 4.18 2 2 0 0 1 4.06 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z" />
        </svg>
      )}
      <span className="conference-badge-text">Звонок идёт</span>
    </span>
  )
}
