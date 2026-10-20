/**
 * Догрузка профилей участников чата-владельца конференции (009,
 * T073) — источник цепочки инициалов `alias → displayName → username`
 * (ui/names, паритет 008b T049) для аватаров конференции. Только
 * СУЩЕСТВУЮЩИЕ API — контракт/backend не меняются:
 * - группа — №27 getGroup: ростер №28 `GroupMember.user` несёт
 *   alias/displayName/username каждого (включая вызывающего) — ОДИН
 *   запрос на всех;
 * - direct — №13 getChat (peer с displayName + персональный peerAlias)
 *   + собственный профиль №10 getCurrentUser; direct-chat id на №27
 *   отвечает единым 404 group_not_found — им и различаем ветки.
 *
 * Кэш на монтаж: один запрос на chatId (из уже загруженного №45-вида),
 * рефетч-цикла нет — сбой догрузки и участник, появившийся позже
 * снимка, уходят в фолбэк инициалов из participant.name (FR-024 008:
 * без ошибок UI). Гость-наблюдатель (GuestJoinPage) хук не вызывает
 * вовсе — API чатов недоступны (FR-010).
 */
import { useEffect, useState } from 'react'
import { getCurrentUser } from '../../api/auth'
import type { ApiProblem } from '../../api/auth'
import { getChat } from '../../api/chats'
import type { ChatView } from '../../api/chats'
import { getGroup } from '../../api/groups'
import type { GroupView } from '../../api/groups'

/** Профиль члена чата для цепочки отображаемого имени (008a, ui-behavior §1). */
export interface ParticipantProfile {
  readonly username: string
  readonly displayName?: string | null
  readonly alias?: string | null
}

/** Map userId → профиль: снимок членов чата на момент догрузки. */
export type ParticipantProfiles = ReadonlyMap<string, ParticipantProfile>

const EMPTY_PROFILES: ParticipantProfiles = new Map<string, ParticipantProfile>()

/** Первая ошибка Problem (`errors: {field: [code]}` — конвенция 002); null — не Problem. */
function problemCode(value: unknown): string | null {
  if (typeof value !== 'object' || value === null) {
    return null
  }
  const errors = (value as Partial<ApiProblem>).errors
  if (errors === undefined) {
    return null
  }
  return Object.values(errors).flat()[0] ?? null
}

/** Ростер №28 → Map: один ответ №27 покрывает всех участников группы. */
function groupRosterProfiles(group: GroupView): ParticipantProfiles {
  const map = new Map<string, ParticipantProfile>()
  for (const { user } of group.members) {
    map.set(user.id, { username: user.username, displayName: user.displayName, alias: user.alias })
  }
  return map
}

/** Direct-ветка: peer №13 (+peerAlias) + собственный профиль №10. */
function directProfiles(
  chat: ChatView,
  me: { readonly id: string; readonly username: string; readonly displayName?: string },
): ParticipantProfiles {
  const map = new Map<string, ParticipantProfile>([
    [me.id, { username: me.username, displayName: me.displayName }],
  ])
  if (chat.peer !== null) {
    map.set(chat.peer.id, {
      username: chat.peer.username,
      displayName: chat.peer.displayName,
      alias: chat.peerAlias,
    })
  }
  return map
}

/** Direct-ветка догрузки: любой сбой — пустая Map (фолбэк инициалов из name). */
async function loadDirectProfiles(chatId: string): Promise<ParticipantProfiles> {
  try {
    const [chat, me] = await Promise.all([getChat(chatId), getCurrentUser()])
    return directProfiles(chat, me)
  } catch {
    return EMPTY_PROFILES
  }
}

/**
 * Разовая догрузка профилей чата: группа — один №27; единый
 * `404 group_not_found` уводит в direct-ветку (№13 + №10); прочие сбои
 * и сбои веток — пустая Map. Никогда не reject'ит.
 */
async function loadParticipantProfiles(chatId: string): Promise<ParticipantProfiles> {
  try {
    const group = await getGroup(chatId)
    return groupRosterProfiles(group)
  } catch (cause) {
    if (problemCode(cause) !== 'group_not_found') {
      return EMPTY_PROFILES
    }
    return loadDirectProfiles(chatId)
  }
}

/**
 * Профили участников чата с кэшем на монтаж (T073): грузит один раз
 * на chatId (null — №45 ещё не загружен — запросов нет); результат —
 * Map для проведения вниз через ParticipantProfilesContext.
 */
export function useParticipantProfiles(chatId: string | null): ParticipantProfiles {
  const [profiles, setProfiles] = useState<ParticipantProfiles>(EMPTY_PROFILES)

  useEffect(() => {
    if (chatId === null) {
      return
    }
    let cancelled = false
    void loadParticipantProfiles(chatId).then((loaded) => {
      if (!cancelled) {
        setProfiles(loaded)
      }
    })
    return () => {
      cancelled = true
    }
  }, [chatId])

  return profiles
}
