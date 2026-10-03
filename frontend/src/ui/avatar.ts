/**
 * Аватар-деривация «Aethergram» (feature 008, T008; FR-024, data-model 1.2,
 * research §E): инициалы и детерминированный цвет вычисляются на лету из
 * единственного источника — username для пользователей либо title для групп,
 * без персистентности (переименование группы пересчитывает аватар само).
 * Алгоритм и палитра — дословно из прототипа
 * specs/008-chat-window-styling/design/chats.html (initialsOf, GROUP_COLORS);
 * норматив констант — contracts/design-tokens.md §4.
 */

/** Палитра детерминированного цвета из прототипа (design-tokens §4). */
export const AVATAR_PALETTE = [
  '#c2622a',
  '#7a5a8b',
  '#4e7a8b',
  '#5e8b7e',
  '#8b3a3a',
  '#a08830',
] as const

export type AvatarColor = (typeof AVATAR_PALETTE)[number]

/**
 * FNV-1a 32-bit по code units строки (research §E): стабилен, быстр,
 * беззнаковый результат 0..0xFFFFFFFF — основа выбора цвета.
 */
export function fnv1a32(source: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < source.length; i++) {
    h ^= source.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/**
 * Инициалы: первые буквы первых двух слов, uppercase; одиночное слово —
 * одна буква. Артикль «the» пропускается (алгоритм прототипа). Пустой
 * источник — пустая строка (аватар-компонент покажет заглушку).
 */
export function initialsOf(source: string): string {
  const words = source.split(' ').filter((word) => word.length > 0 && word.toLowerCase() !== 'the')
  const first = words[0]?.[0] ?? ''
  const second = words[1]?.[0] ?? ''
  return (first + second).toUpperCase()
}

/** Цвет: `PALETTE[fnv1a32(source) mod 6]` — детерминирован источником. */
export function colorOf(source: string): AvatarColor {
  const index = fnv1a32(source) % AVATAR_PALETTE.length
  return AVATAR_PALETTE[index] ?? AVATAR_PALETTE[0]
}
