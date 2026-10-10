/**
 * Чистые примитивы псевдо-поиска по сообщениям открытого чата
 * (feature 008a, Phase 11, T068; прототип chats.html renderSearchResults
 * :858-900): совпадение — подстрока без регистра по `message.text`,
 * сниппет — окно ~96 символов вокруг ПЕРВОГО вхождения с ведущим/
 * хвостовым многоточием. Единый модуль по образцу ui/names.ts: без
 * состояния и без API-зависимостей, поиск живой по вводу. Скоуп —
 * только загруженное окно ленты (№13-страница + подгруженные
 * №14-страницы useChatMessages): серверной операции нет, сообщение за
 * пределами окна не находится. Экранирование — React-инвариант 008
 * FR-033: функции возвращают сырой текст, HTML-вставки рендерятся как
 * текст (никакой разметки не рождается и не вырезается).
 */

/** Сколько символов контекста брать до первого вхождения (прототип: pos − 24). */
export const SNIPPET_CONTEXT_BEFORE = 24

/** Длина окна сниппета (прототип: slice(start, start + 96)). */
export const SNIPPET_MAX_LENGTH = 96

/**
 * Индекс ПЕРВОГО вхождения `query` в `text` без регистра; −1 — совпадения
 * нет. Пустой запрос никогда не совпадает (режим подсказки «Введите
 * запрос…» живёт на стороне компонента).
 */
export function findMatch(text: string, query: string): number {
  if (query === '') {
    return -1
  }
  return text.toLowerCase().indexOf(query.toLowerCase())
}

/**
 * Сниппет вокруг вхождения (прототип :866-870): окно открывается за
 * SNIPPET_CONTEXT_BEFORE до `matchIndex` (но не левее нуля) и тянется
 * SNIPPET_MAX_LENGTH символов; обрезанное начало/конец отмечаются
 * многоточием. Возвращает сырой текст — экранирует React при рендере.
 */
export function snippetAround(text: string, matchIndex: number): string {
  const start = Math.max(0, matchIndex - SNIPPET_CONTEXT_BEFORE)
  const end = start + SNIPPET_MAX_LENGTH
  const prefix = start > 0 ? '…' : ''
  const suffix = text.length > end ? '…' : ''
  return `${prefix}${text.slice(start, end)}${suffix}`
}
