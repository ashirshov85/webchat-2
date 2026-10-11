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
 * (переименование пользователя не перекрашивает). Инициалы — графемные
 * кластеры (`Intl.Segmenter`): два+ слова — по первому кластеру первых
 * двух слов; одиночное слово — первые ДВА кластера (T069: аватар всегда
 * из двух символов при достатке материала; кириллица/латиница/эмодзи,
 * «тофу» недопустимо — 008a ui-behavior §1).
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
  const [first] = GRAPHEME_SEGMENTER.segment(word)
  return first?.segment ?? ''
}

/**
 * Первые `count` графемных кластеров слова целиком (T069: одиночное
 * слово отдаёт два кластера; меньше — только при недостатке материала).
 */
function firstGraphemes(word: string, count: number): string {
  let result = ''
  let taken = 0
  for (const { segment } of GRAPHEME_SEGMENTER.segment(word)) {
    if (taken >= count) break
    result += segment
    taken += 1
  }
  return result
}

/**
 * Инициалы (008a T069): два+ слова — первый графемный кластер каждого
 * из первых двух слов, uppercase («ada lovelace» → «AL»); одиночное
 * слово — первые ДВА графемных кластера этого слова, оба в верхнем
 * регистре («Мария» → «МА», «olkot» → «OL») — аватар всегда из двух
 * символов, когда материала достаточно. Недостаток материала — символов
 * меньше двух: слово из одного кластера («a», «😀», «👨‍💻») — один
 * кластер; пустые части и артикль «the» пропускаются (алгоритм
 * прототипа, каскадно даёт «The North» → «NO»). Пустой источник —
 * пустая строка (аватар-компонент покажет заглушку; в цепочке FR-003
 * вход всегда непуст — фолбэк `username`).
 */
export function initialsOf(source: string): string {
  const words = source.split(' ').filter((word) => word.length > 0 && word.toLowerCase() !== 'the')
  const [first = '', second] = words
  const initials =
    second === undefined ? firstGraphemes(first, 2) : firstGrapheme(first) + firstGrapheme(second)
  return initials.toUpperCase()
}

/** Цвет: `PALETTE[fnv1a32(source) mod 6]` — детерминирован источником. */
export function colorOf(source: string): AvatarColor {
  const index = fnv1a32(source) % AVATAR_PALETTE.length
  return AVATAR_PALETTE[index] ?? AVATAR_PALETTE[0]
}
