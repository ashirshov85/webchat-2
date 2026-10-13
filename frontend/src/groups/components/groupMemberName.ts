/**
 * Цепочка отображаемого имени контакта/участника (T049; 008a T022/T023,
 * ui-behavior §1): единая точка `alias → displayName → username` для
 * поверхностей №20 `ContactView` (alias — персональное поле вызывающего
 * РЯДОМ с user) и №28 `GroupMember.user` (alias живёт ВНУТРИ
 * пользовательского объекта, контракт 0.9.0). Дубли цепочки из
 * ContactsModal/GroupMembersModal сведены сюда; сами звенья разрешает
 * `resolveDisplayName` (ui/names) — пустые/пробельные значения
 * пропускаются, username — гарантированный фолбэк.
 */
import type { ContactView } from '../../api/chats'
import type { GroupMember } from '../../api/groups'
import { resolveDisplayName } from '../../ui/names'

/** Цепочка имени контакта №20: `alias → user.displayName → user.username`. */
export function chainOf(contact: ContactView): string {
  return resolveDisplayName(contact.alias, contact.user.displayName, contact.user.username)
}

/** Цепочка имени участника №28: `user.alias → user.displayName → user.username`. */
export function memberChainOf(member: GroupMember): string {
  return resolveDisplayName(member.user.alias, member.user.displayName, member.user.username)
}
