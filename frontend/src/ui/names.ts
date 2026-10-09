/**
 * Цепочка отображаемых имён «Aethergram» (feature 008a, T018; FR-001–FR-005,
 * ui-behavior §1): единственная точка разрешения отображаемого имени
 * `alias (личный) → displayName профиля → username` для всех поверхностей
 * (чаты, контакты, участники групп, поиск, typing-индикатор). Сервер хранит
 * значения после trim (1–64) — пустые и пробельные кандидаты трактуются
 * как незаданные; username — гарантированный финальный фолбэк.
 */

/**
 * Разрешить отображаемое имя: alias → displayName → username.
 * Пустые/пробельные alias/displayName пропускаются (защита от локальных
 * состояний до валидации); непустые возвращаются после trim; результат
 * рендерится как текст (React-инвариант 008 FR-033).
 */
export function resolveDisplayName(
  alias: string | null | undefined,
  displayName: string | null | undefined,
  username: string,
): string {
  const aliasValue = alias?.trim()
  if (aliasValue) return aliasValue
  const nameValue = displayName?.trim()
  if (nameValue) return nameValue
  return username
}
