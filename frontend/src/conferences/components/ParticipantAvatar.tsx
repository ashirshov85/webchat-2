/**
 * Аватар участника конференции (009, US1, T073; «как в контактах»):
 * ui/Avatar — генерируемые инициалы + детерминированный цвет,
 * фотографий нет (FR-024 008). Деривация по принципалу identity
 * (identity.ts — зеркало ConferenceIdentity):
 * - член чата `u:{userId}:{connId}` с профилем из догрузки
 *   (ParticipantProfilesContext ← useParticipantProfiles, T073) —
 *   инициалы цепочки `initialsOf(resolveDisplayName(alias,
 *   displayName, username))`, цвет — colorOf(username) (008a T020:
 *   переименование не перекрашивает);
 * - гость `g:{connId}` (name — введённое имя №55), участник вне
 *   снимка профилей и сбой догрузки — фолбэк инициалов из
 *   participant.name, цвет — colorOf(имени) (FR-024 008: без ошибок
 *   UI и рефетч-цикла).
 */
import { useContext } from 'react'
import { Avatar } from '../../ui/Avatar'
import { initialsOf } from '../../ui/avatar'
import { resolveDisplayName } from '../../ui/names'
import { parseParticipantIdentity } from '../identity'
import { ParticipantProfilesContext } from '../profiles'
import type { ParticipantProfile } from '../hooks/useParticipantProfiles'

/** Минимальный контракт участника LiveKit, нужный аватару. */
export interface AvatarParticipant {
  readonly identity: string
  readonly name?: string
}

export interface ParticipantAvatarProps {
  readonly participant: AvatarParticipant
  /** Размер px (design-tokens §4: строка панели 34, тайл крупнее). */
  readonly size?: number
  readonly className?: string
}

/** Отображаемое имя фолбэка: name тикета/метаданных, иначе identity (как метки тайлов T027). */
function fallbackLabel(participant: AvatarParticipant): string {
  return participant.name !== undefined && participant.name.trim() !== ''
    ? participant.name
    : participant.identity
}

/**
 * Аватар участника: читает снимок профилей из контекста (null —
 * фолбэк по name для всех, включая членов).
 */
export function ParticipantAvatar({ participant, size = 34, className }: ParticipantAvatarProps) {
  const profiles = useContext(ParticipantProfilesContext)
  const parsed = parseParticipantIdentity(participant.identity)
  const profile: ParticipantProfile | undefined =
    parsed.kind === 'member' ? profiles?.get(parsed.userId) : undefined

  if (profile !== undefined) {
    return (
      <Avatar
        source={profile.username}
        initials={initialsOf(
          resolveDisplayName(profile.alias, profile.displayName, profile.username),
        )}
        size={size}
        className={className}
      />
    )
  }
  const label = fallbackLabel(participant)
  return <Avatar source={label} initials={initialsOf(label)} size={size} className={className} />
}
