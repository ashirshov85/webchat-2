/**
 * Клиентский парсер identity участников конференции (009, T073) —
 * зеркало ConferenceIdentity backend (research §4): LiveKit-участник
 * несёт per-connection identity минта — `u:{userId}:{connId}` (член
 * чата, №47; каждый минт — свежий connId) или `g:{connId}` (гость,
 * №55). Сегменты обязаны быть UUID (identity минтится сервером);
 * префикс-принципал `u:{userId}` сворачивается к тому же члену.
 * Чужие/битые значения → unknown, без выброса: перечисления LiveKit —
 * внешний ввод (как parsePrincipal backend).
 */
export type ParticipantIdentity =
  | { readonly kind: 'member'; readonly userId: string }
  | { readonly kind: 'guest' }
  | { readonly kind: 'unknown' }

/** UUID-сегмент identity: 8-4-4-4-12 hex (версия/вариант не проверяются — как UUID backend). */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value)
}

/**
 * Разобрать identity LiveKit-участника: член чата (userId для
 * догрузки профилей T073), гость или unknown. Никогда не бросает.
 */
export function parseParticipantIdentity(raw: string): ParticipantIdentity {
  const parts = raw.split(':')
  if (parts.length === 2) {
    if (parts[0] === 'u' && isUuid(parts[1] ?? '')) {
      return { kind: 'member', userId: parts[1] as string }
    }
    if (parts[0] === 'g' && isUuid(parts[1] ?? '')) {
      return { kind: 'guest' }
    }
    return { kind: 'unknown' }
  }
  // u:{userId}:{connId} — оба сегмента валидируются (битый connId — не наш минт)
  if (parts.length === 3 && parts[0] === 'u' && isUuid(parts[1] ?? '') && isUuid(parts[2] ?? '')) {
    return { kind: 'member', userId: parts[1] as string }
  }
  return { kind: 'unknown' }
}
