/**
 * Аватар-примитив «Aethergram» (feature 008, T010; FR-024, data-model 1.2,
 * design-tokens §4): DOM и классы — дословно из прототипа
 * specs/008-chat-window-styling/design/chats.html (avatarHTML):
 * `.avatar[.oct] > .av-in > span` + presence-точка `.av-dot[.off|.unknown]`.
 * Обод `--gold-gradient`, бликовая внутренность с `--av`, инициалы Cormorant
 * SC и формы круга/октагона (clip-path 30/70%) — в avatar.css; деривация
 * детерминированного цвета — ui/avatar.ts (T008): аватар пересчитывается
 * сам при переименовании источника (без персистентности).
 * 008a T020 (FR-004): источники разделены — инициалы можно передать
 * готовыми (`initialsOf(resolveDisplayName(...))`, ui/names.ts), цвет
 * всегда `colorOf(source)` — username/название группы: переименование
 * пользователя не перекрашивает аватар. Проводку поверхностей делает T023.
 * Presence-точка: online — зелёная с мерцанием, offline — тусклая,
 * unknown — нейтральная (без ложного «офлайн», семантика 007);
 * null — без точки (группы, свой аватар, отправители ленты).
 */
import type { CSSProperties } from 'react'
import { colorOf, initialsOf } from './avatar'
import './avatar.css'

/** Форма: круг — пользователи; октагон — групповые чаты (design-tokens §4). */
export type AvatarShape = 'circle' | 'octagon'

/** Presence-точка из presenceStore 007; null — точка не выводится. */
export type AvatarPresence = 'online' | 'offline' | 'unknown'

export type AvatarPresenceDot = AvatarPresence | null

/** Классы presence-точки по состоянию (прототип: online — базовый, offline — .off). */
const PRESENCE_DOT_CLASS: Readonly<Record<AvatarPresence, string>> = {
  online: 'av-dot',
  offline: 'av-dot off',
  unknown: 'av-dot unknown',
}

/** aria-метки состояний (единые с PresenceIndicator 007 — clarify a11y). */
const PRESENCE_LABELS: Readonly<Record<AvatarPresence, string>> = {
  online: 'онлайн',
  offline: 'офлайн',
  unknown: 'неизвестно',
}

/** Прозрачный пиксель: саму точку рисует CSS .av-dot, src нужен только
 *  нативному <img> (роль/имя — из alt, S6819). */
const AV_DOT_SRC = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'

export interface AvatarProps {
  /** Источник деривации цвета: username (пользователи) либо title (группы). */
  readonly source: string
  /**
   * Готовые инициалы — из отображаемого имени `initialsOf(resolveDisplayName(...))`
   * (008a T020, FR-004); пустая строка/отсутствие — деривация из source.
   */
  readonly initials?: string
  /** Форма: circle (пользователи) / octagon (группы); по умолчанию circle. */
  readonly shape?: AvatarShape
  /** Размер в px (design-tokens §4: список 40, заголовок 46, лента 34, подсказки 24–32). */
  readonly size?: number
  /** Presence-точка; по умолчанию null — без точки. */
  readonly presenceDot?: AvatarPresenceDot
  /** Дополнительный класс корня (хуки поверхностей). */
  readonly className?: string
}

/** inline-стиль корня: детерминированный цвет --av + размер (обод и внутренность — avatar.css). */
function avatarStyle(source: string, size: number): CSSProperties {
  return {
    '--av': colorOf(source),
    width: size,
    height: size,
  } as CSSProperties
}

export function Avatar({
  source,
  initials,
  shape = 'circle',
  size = 40,
  presenceDot = null,
  className,
}: AvatarProps) {
  const classes = ['avatar', shape === 'octagon' ? 'oct' : '', className ?? '']
    .filter((name) => name.length > 0)
    .join(' ')
  const initialsText = initials || initialsOf(source) || '?'
  return (
    <div className={classes} style={avatarStyle(source, size)}>
      <div className="av-in" aria-hidden="true">
        <span>{initialsText}</span>
      </div>
      {presenceDot !== null && (
        <img
          className={PRESENCE_DOT_CLASS[presenceDot]}
          src={AV_DOT_SRC}
          alt={PRESENCE_LABELS[presenceDot]}
        />
      )}
    </div>
  )
}
