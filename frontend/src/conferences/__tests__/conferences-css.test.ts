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
 *
 * T079 — полноэкранный пин-оверлей: маркер conference-room-pinned на
 * корневом div комнаты (живой пин T074/T078) → position: fixed на весь
 * вьюпорт без скроллбаров, служебные панели страницы скрыты
 * body:has(…), controls absolute снизу по центру поверх тайла,
 * статус-строки/тост — absolute-оверлеи сверху; обычная геометрия
 * тайлов T075 не регрессирует.
 *
 * T084 — слитый тайл участника: широкая секция демонстраций
 * (.conference-screens) и 21:9-тайл экрана (.conference-tile-screen)
 * удалены — единая сетка тайлов 16:9, экран вписывается object-fit
 * contain; живая демонстрация маркируется текстовым бейджем
 * «демонстрирует экран» (лексика conference-tile-speaking, 008).
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

describe('полноэкранный пин-оверлей (T079): статический пин CSS', () => {
  it('маркер комнаты: fixed+inset — оверлей на весь вьюпорт, страховка overflow у body', () => {
    const overlay = conferencesCss.match(/\.conference-room-pinned\s*\{([^}]*)\}/)
    expect(overlay).not.toBeNull()
    // комната вынимается из потока документа — контента выше вьюпорта
    // не остаётся → скроллбаров нет; overflow — страховка границ
    expect(overlay?.[1]).toContain('position: fixed')
    expect(overlay?.[1]).toContain('inset: 0')
    expect(overlay?.[1]).toContain('z-index')
    expect(overlay?.[1]).toContain('overflow: hidden')
    const body = conferencesCss.match(/body:has\(\.conference-room-pinned\)\s*\{([^}]*)\}/)
    expect(body).not.toBeNull()
    expect(body?.[1]).toContain('overflow: hidden')
  })

  it('пин-тайл внутри маркера — на весь экран: height 100%, потолки сняты, flex-рост', () => {
    const tile = conferencesCss.match(
      /\.conference-room-pinned \.conference-tile-pinned\s*\{([^}]*)\}/,
    )
    expect(tile).not.toBeNull()
    expect(tile?.[1]).toContain('height: 100%')
    expect(tile?.[1]).toContain('max-height: none')
    expect(tile?.[1]).toContain('flex: 1')
    expect(tile?.[1]).toContain('min-height: 0')
  })

  it('controls — absolute снизу по центру поверх тайла (DOM не покидает, хуки livekit живы)', () => {
    const controls = conferencesCss.match(
      /\.conference-room-pinned \.conference-controls\s*\{([^}]*)\}/,
    )
    expect(controls).not.toBeNull()
    expect(controls?.[1]).toContain('position: absolute')
    expect(controls?.[1]).toContain('bottom:')
    expect(controls?.[1]).toContain('left: 50%')
    expect(controls?.[1]).toContain('transform: translateX(-50%)')
    expect(controls?.[1]).toContain('z-index')
  })

  it('служебные панели страницы скрыты по body:has; статус-строки и тост — absolute-оверлеи сверху', () => {
    // скрытие остального: строка «Вы в конференции» (output прямой
    // ребёнок .conference-joined), панели участников/модерации (у гостя
    // её нет — правило не матчится), живой счётчик (T080), компактная
    // полоса остальных тайлов; шапка и identity-строка удалены целиком
    // (T087) — их селекторов в источнике больше нет
    const hidden = conferencesCss.match(
      /body:has\(\.conference-room-pinned\) \.conference-participants[\s\S]{0,600}?\{([^}]*)\}/,
    )
    expect(hidden).not.toBeNull()
    expect(hidden?.[1]).toContain('display: none')
    for (const selector of [
      'body:has(.conference-room-pinned) .conference-participants',
      'body:has(.conference-room-pinned) .conference-moderation',
      'body:has(.conference-room-pinned) .conference-live-count',
      'body:has(.conference-room-pinned) .conference-joined > output',
      'body:has(.conference-room-pinned) .conference-tiles-compact',
    ]) {
      expect(conferencesCss, `селектор скрытия ${selector}`).toContain(selector)
    }
    // статус-строка реконнекта и тост conference.warning — в потоке
    // отожмут высоту тайла: absolute-оверлеем сверху
    const statusOverlay = conferencesCss.match(
      /\.conference-room-pinned \.conference-room-status,\s*\.conference-room-pinned \.conference-room-media,\s*\.conference-room-pinned \.conference-warning-toast\s*\{([^}]*)\}/,
    )
    expect(statusOverlay).not.toBeNull()
    expect(statusOverlay?.[1]).toContain('position: absolute')
    expect(statusOverlay?.[1]).toContain('top:')
    expect(statusOverlay?.[1]).toContain('transform: translateX(-50%)')
  })

  it('регресс T075/T074: обычный тайл хранит потолок 62dvh, пин-тайл вне оверлея — clamp', () => {
    const tile = conferencesCss.match(/\.conference-tile\s*\{([^}]*)\}/)
    expect(tile?.[1]).toContain('max-height: 62dvh')
    const pinned = conferencesCss.match(/\.conference-tile-pinned\s*\{([^}]*)\}/)
    expect(pinned?.[1]).toContain('height: clamp(260px, 62dvh, 1080px)')
  })

  it('T080: живой счётчик подключённых — строка комнаты, скрытая полноэкранным пином вместе с панелями', () => {
    // базовая строка живого счёта (children ConferenceRoom — над панелями)
    const line = conferencesCss.match(/\.conference-live-count\s*\{([^}]*)\}/)
    expect(line).not.toBeNull()
    expect(line?.[1]).toContain('margin: 0')
    // полноэкранный пин T079 накрывает счётчик CSS-скрытием — иначе
    // строка висит поверх оверлея (добавлена к панелям body:has(…))
    expect(conferencesCss).toContain('body:has(.conference-room-pinned) .conference-live-count')
  })
})

describe('санитаризация шапки/identity (T087): статический пин CSS', () => {
  it('шапка .conference-header и строка .conference-identity удалены из источника целиком', () => {
    // шапка ушла из всех фаз страницы (ConferencePage/GuestJoinPage),
    // identity-строка — из joined-форм: мёртвые правила (включая
    // селекторы скрытия пин-оверлея T079) не остаются в CSS
    expect(conferencesCss).not.toContain('.conference-header')
    expect(conferencesCss).not.toContain('.conference-identity')
  })

  it('селекторы пин-оверлея T079 для оставшихся поверхностей сохранены', () => {
    for (const selector of [
      'body:has(.conference-room-pinned) .conference-participants',
      'body:has(.conference-room-pinned) .conference-moderation',
      'body:has(.conference-room-pinned) .conference-live-count',
      'body:has(.conference-room-pinned) .conference-joined > output',
      'body:has(.conference-room-pinned) .conference-tiles-compact',
    ]) {
      expect(conferencesCss, `селектор скрытия ${selector}`).toContain(selector)
    }
  })
})

describe('слитый тайл участника (T084): статический пин CSS', () => {
  it('широкая секция демонстраций и 21:9-тайл экрана удалены — единая сетка тайлов 16:9', () => {
    expect(conferencesCss).not.toContain('.conference-screens')
    expect(conferencesCss).not.toContain('.conference-tile-screen')
    // 21:9-геометрия упразднена: экран вписывается в 16:9 (object-fit contain)
    expect(conferencesCss).not.toContain('21 / 9')
    const tile = conferencesCss.match(/\.conference-tile\s*\{([^}]*)\}/)
    expect(tile?.[1]).toContain('aspect-ratio: 16 / 9')
  })

  it('бейдж «демонстрирует экран» — правило в лексике conference-tile-speaking', () => {
    const badge = conferencesCss.match(/\.conference-tile-sharing\s*\{([^}]*)\}/)
    expect(badge).not.toBeNull()
    expect(badge?.[1]).toContain('margin-left: 6px')
    expect(badge?.[1]).toContain('font-style: italic')
    expect(badge?.[1]).toContain('color: var(--gold-light)')
  })
})
