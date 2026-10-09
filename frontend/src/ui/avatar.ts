/**
 * Аватар-деривация «Aethergram» (feature 008, T008; FR-024, data-model 1.2,
 * research §E): инициалы и детерминированный цвет вычисляются на лету из
 * единственного источника — username для пользователей либо title для групп,
 * без персистентности (переименование группы пересчитывает аватар само).
 * Палитра — дословно из прототипа specs/008-chat-window-styling/design/
 * chats.html (GROUP_COLORS); норматив констант — contracts/design-tokens.md §4.
 *
 * 008a T020 (FR-004, SC-006): источники аватара разделены — инициалы
 * вычисляются из отображаемого имени (`initialsOf(resolveDisplayName(...))`,
 * names.ts), цвет — по-прежнему `colorOf(username)`/title группы
 * (переименование пользователя не перекрашивает). Инициалы — первый
 * графемный кластер (`Intl.Segmenter`) каждого из первых двух слов
 * (кириллица/латиница/эмодзи, «тофу» недопустимо — 008a ui-behavior §1).
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
 * FNV-1a 32-bit по code points строки (research §E): стабилен, быстр,
 * беззнаковый результат 0..0xFFFFFFFF — основа выбора цвета.
 */
export function fnv1a32(source: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < source.length; i++) {
    h ^= source.codePointAt(i) ?? 0
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/**
 * Графемная сегментация (008a ui-behavior §1): кластеры Unicode, а не
 * UTF-16 code units — суррогатные пары/ZWJ-эмодзи/диакритика не рвутся.
 */
const GRAPHEME_SEGMENTER = new Intl.Segmenter('ru', { granularity: 'grapheme' })

/** Первый графемный кластер слова целиком (без «тофу» из одиночных суррогатов). */
function firstGrapheme(word: string): string {
  for (const { segment } of GRAPHEME_SEGMENTER.segment(word)) {
    return segment
  }
  return ''
}

/**
 * Инициалы: первые графемные кластеры первых двух слов, uppercase; одиночное
 * слово — один кластер. Пустые части и артикль «the» пропускаются (алгоритм
 * прототипа). Пустой источник — пустая строка (аватар-компонент покажет
 * заглушку; в цепочке FR-003 вход всегда непуст — фолбэк `username`).
 */
export function initialsOf(source: string): string {
  const words = source.split(' ').filter((word) => word.length > 0 && word.toLowerCase() !== 'the')
  const first = firstGrapheme(words[0] ?? '')
  const second = firstGrapheme(words[1] ?? '')
  return (first + second).toUpperCase()
}

/** Цвет: `PALETTE[fnv1a32(source) mod 6]` — детерминирован источником. */
export function colorOf(source: string): AvatarColor {
  const index = fnv1a32(source) % AVATAR_PALETTE.length
  return AVATAR_PALETTE[index] ?? AVATAR_PALETTE[0]
}
