import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 009, T110 — статический пин перевода строки «Синхронизация
 * сообщений…» в семью тихих подписей. Vitest CSS-импорты — no-op,
 * jsdom не каскадирует: источники читаются через node:fs (паттерн
 * conferences-css.test.ts / MessageList.pagination.test.tsx).
 *
 * Суть T110: лексика полос доставки (status-banners.css: латунная
 * плашка + мерцающая точка, 008 T052) демонтирована вместе с
 * селекторами `output.sync-indicator*`; строка живёт в семье
 * `.messenger-empty` (states.css, FR-032) — курсив Old Standard TT
 * 12.5px по центру на --muted-3, сайдбар-масштаб `.chat-panel
 * .messenger-empty` (padding 10px) без новых селекторов.
 *
 * T116 (багфикс T110): за `<output>` просвечивала success-плашка
 * глобального element-правила `output,[role='status']` (index.css:
 * background/border-radius не были объявлены классом) — правило
 * сайдбар-масштаба закрывает КАЖДОЕ box-свойство глобуса
 * (display/border-radius/background — паттерн T051/T102, эталон
 * tmp/chat-list-state-rows.html §5). В status-banners
 * остаются ErrorBanner/QueueOverflowBanner; srFlash остаётся (его
 * использует danger-семья); keyframes flick живут в ui/avatar.css и
 * не затрагиваются.
 */
const bannersCss = readFileSync(join(import.meta.dirname, '../status-banners.css'), 'utf8')
const statesCss = readFileSync(join(import.meta.dirname, '../states.css'), 'utf8')
const avatarCss = readFileSync(join(import.meta.dirname, '../../../ui/avatar.css'), 'utf8')

/** CSS без комментариев: пин проверяет ОТСУТСТВИЕ СЕЛЕКТОРОВ, а truthful-шапка упоминает демонтированные правила прозой. */
const bannersRules = bannersCss.replace(/\/\*[\s\S]*?\*\//g, '')

describe('тихая подпись синхронизации (T110): статический пин CSS', () => {
  it('status-banners.css: селекторы .sync-indicator демонтированы целиком', () => {
    expect(bannersRules.includes('sync-indicator')).toBe(false)
  })

  it('status-banners.css: семья danger и srFlash остаются — их потребители живы', () => {
    expect(bannersCss.includes('queue-overflow-banner')).toBe(true)
    expect(bannersCss.includes('error-banner')).toBe(true)
    // srFlash остаётся: вспышка нужна danger-полосам, не статусу.
    expect(bannersCss.match(/@keyframes srFlash\s*\{/)).not.toBeNull()
  })

  it('ui/avatar.css: keyframes flick не тронуты (точки-«лампы» больше нет, кадры — у аватаров)', () => {
    expect(avatarCss.match(/@keyframes flick\s*\{/)).not.toBeNull()
  })

  it('states.css: сайдбар-масштаб .chat-panel .messenger-empty — замыкание box-свойств глобального element-стиля (T116)', () => {
    const scale = statesCss.match(/\.chat-panel \.messenger-empty\s*\{([^}]*)\}/)
    expect(scale).not.toBeNull()
    expect(scale?.[1]).toContain('padding: 10px')
    // T116: элемент <output> тянет за собой ЗЕЛЁНУЮ плашку auth-лексики
    // глобуса (display/margin/padding/radius/color/background) — класс
    // бьёт element-селектор ТОЛЬКО по объявленным свойствам, поэтому
    // правило сайдбар-масштаба закрывает каждое box-свойство явно:
    // тихая подпись, НЕ плашка (паттерн T051/T102, эталон §5).
    expect(scale?.[1]).toContain('display: block')
    expect(scale?.[1]).toContain('border-radius: 0')
    expect(scale?.[1]).toContain('background: none')
    // Строка синхронизации входит в существующую семью — собственных
    // селекторов в states.css не появляется.
    expect(statesCss.includes('sync-indicator')).toBe(false)
    // Шапка семьи фиксирует нового члена (truthful-докблок).
    expect(statesCss).toMatch(/«Синхронизация сообщений…»/)
  })
})
