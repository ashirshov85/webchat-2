import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 009, T075 — статический пин CSS-источника каркаса конференц-страницы.
 * Vitest CSS-импорты — no-op, jsdom не каскадирует: источник читается
 * через node:fs (паттерн T072/T083-008, MessageList.test.tsx).
 *
 * Суть T075: обе конференц-точки входа (член /conference/:id —
 * data-conference-id, гость /join/:inviteToken — data-guest-join)
 * растягиваются на всё окно браузера — раскрытие #root по образцу
 * мессенджера (body:has(.machine) #root, messenger.css), нейтрализация
 * auth-наследия <section> (index.css), вертикаль 100dvh. Правила живут
 * в conferences.css и скоуплены ТОЛЬКО конференц-маркерами: index.css
 * и messenger.css не правятся (регресс-пины источников ниже).
 */

const conferencesCss = readFileSync(join(import.meta.dirname, '../conferences.css'), 'utf8')
const indexCss = readFileSync(join(import.meta.dirname, '../../index.css'), 'utf8')
const messengerCss = readFileSync(
  join(import.meta.dirname, '../../chats/pages/messenger.css'),
  'utf8',
)

describe('каркас конференц-страницы (T075): статический пин CSS', () => {
  it('conferences.css: раскрытие #root скоуплено body:has с ОБЕИМИ маркерами конференций', () => {
    const expand = conferencesCss.match(
      /body:has\(\[data-conference-id\], \[data-guest-join\]\) #root\s*\{([^}]*)\}/,
    )
    expect(expand).not.toBeNull()
    expect(expand?.[1]).toContain('max-width: none')
    expect(expand?.[1]).toContain('margin: 0')
    expect(expand?.[1]).toContain('padding: 0')
  })

  it('conferences.css: нейтрализация auth-наследия section для обеих корневых секций + вертикаль 100dvh', () => {
    const section = conferencesCss.match(
      /body:has\(\[data-conference-id\], \[data-guest-join\]\) section\[data-conference-id\],\s*body:has\(\[data-conference-id\], \[data-guest-join\]\) section\[data-guest-join\]\s*\{([^}]*)\}/,
    )
    expect(section).not.toBeNull()
    // auth-наследие index.css (подложка/рамка/радиус/паддинги/margin-top) снято
    expect(section?.[1]).toContain('background: none')
    expect(section?.[1]).toContain('border: none')
    expect(section?.[1]).toContain('border-radius: 0')
    expect(section?.[1]).toContain('margin-top: 0')
    // каркас — flex-колонка на высоту окна браузера (фазы loading/pre-join/
    // joined/ended/error занимают всё окно)
    expect(section?.[1]).toContain('display: flex')
    expect(section?.[1]).toContain('flex-direction: column')
    expect(section?.[1]).toContain('min-height: 100dvh')
  })

  it('conferences.css: вертикальная цепочка роста (join → joined → room) и доли окна у тайлов', () => {
    const join = conferencesCss.match(/\.conference-join\s*\{([^}]*)\}/)
    expect(join?.[1]).toContain('flex: 1')
    const joined = conferencesCss.match(/\.conference-joined\s*\{([^}]*)\}/)
    expect(joined?.[1]).toContain('flex: 1')
    const room = conferencesCss.match(/\.conference-room\s*\{([^}]*)\}/)
    expect(room?.[1]).toContain('flex: 1')
    expect(room?.[1]).toContain('min-height: 0')
    // сеточные тайлы: aspect-ratio 16/9 + потолок доли окна — solo-тайл
    // auto-fit-коллапсом получает всю ширину окна и без потолка выталкивает
    // страницу за окно (промер T075)
    const tile = conferencesCss.match(/\.conference-tile\s*\{([^}]*)\}/)
    expect(tile?.[1]).toContain('max-height: 62dvh')
    expect(tile?.[1]).toContain('min-height: 0')
    // видео — абсолютная видеоповерхность: intrinsic-пропорции LiveKit-трека
    // не участвуют в определении высоты тайла/сетки/комнаты
    const video = conferencesCss.match(/\.conference-tile video\s*\{([^}]*)\}/)
    expect(video?.[1]).toContain('position: absolute')
    // пин-тайл T074: границы пересмотрены промером T075 — доля окна 62dvh,
    // потолок поднят 780px → 1080px (растянутая страница), пол 260px
    const pinned = conferencesCss.match(/\.conference-tile-pinned\s*\{([^}]*)\}/)
    expect(pinned?.[1]).toContain('height: clamp(260px, 62dvh, 1080px)')
  })

  it('регресс-пины источников: index.css и messenger.css не правятся', () => {
    // служебная колонка auth-страниц на месте
    const root = indexCss.match(/#root\s*\{([^}]*)\}/)
    expect(root?.[1]).toContain('max-width: 480px')
    expect(root?.[1]).toContain('margin: 0 auto')
    // раскрытие мессенджера — прежний паттерн body:has(.machine)
    const machine = messengerCss.match(/body:has\(\.machine\) #root\s*\{([^}]*)\}/)
    expect(machine?.[1]).toContain('max-width: none')
    expect(machine?.[1]).toContain('padding: 0')
  })
})
