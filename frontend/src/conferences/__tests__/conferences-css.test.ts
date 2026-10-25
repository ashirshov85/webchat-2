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
 *
 * T092 — демонтаж живого счётчика T080 и output-подписи joined-формы:
 * правило .conference-live-count и селекторы скрытия пин-оверлея для
 * него и «.conference-joined > output» удалены из источника целиком;
 * панельные селекторы оверлея (участники/модерация/компактная полоса)
 * сохранены.
 *
 * T096 — вертикальное центрирование pre-join-панели члена
 * (.conference-prejoin) и гостевых форм (.guest-join-name,
 * .guest-join-prejoin): auto-маржины flex-элемента поглощают свободное
 * место по ОБЕИМ осям (margin: 0 auto → margin: auto) — формы встают
 * в центр фазовой колонки по горизонтали И вертикали, симметрично для
 * обеих точек входа (PreJoinScreen переиспользуется, паритет Q1=C);
 * прочие фазы (joined/error/терминал) не центрируются.
 *
 * T097 — демонтаж стека пояснений .conference-controls-restrictions
 * (T043): уведомления о точечных ограничениях всплывают нативным
 * title тумблера, состояние «все источники отключены модератором»
 * (доменное speechRestricted, T094) в conference-controls не
 * выводится вовсе — правило удалено из источника целиком; нейтральная
 * строка отказа переключения .conference-controls-notice остаётся.
 *
 * T098 — полная замена conference-controls на стимпанк-прототип кнопок-
 * иконок tmp/conference-controls.html: оболочка machine на fieldset
 * .conference-controls (двойная латунная рамка + фон-пластина +
 * backdrop-blur + decor-layer с двумя вращающимися шестерёнками),
 * круглые кнопки-иконки 78px/gap 22px (состояние — data-state on/off/
 * blocked: лампа + on-части иконки / диагональный штрих / замок-бейдж),
 * кастомные латунные tooltip на :hover/:focus-visible. ПАРИТЕТ двух
 * режимов (пересмотр T089): правило скрытия «Покинуть» в пин-оверлее
 * демонтировано — панель одна для стандартного режима и оверлея.
 * Геометрия текстовых тумблеров T028 переименована в prejoin-скоуп
 * (.conference-prejoin-toggle/-state — её единственный потребитель
 * PreJoinScreen), .conference-controls-state из источника удалён.
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
    // скрытие остального: панели участников/модерации (у гостя её
    // нет — правило не матчится), компактная полоса остальных тайлов;
    // шапка и identity-строка удалены целиком (T087), output-подпись
    // joined-формы и живой счётчик T080 — тоже (T092): их селекторов
    // в источнике больше нет
    const hidden = conferencesCss.match(
      /body:has\(\.conference-room-pinned\) \.conference-participants[\s\S]{0,600}?\{([^}]*)\}/,
    )
    expect(hidden).not.toBeNull()
    expect(hidden?.[1]).toContain('display: none')
    for (const selector of [
      'body:has(.conference-room-pinned) .conference-participants',
      'body:has(.conference-room-pinned) .conference-moderation',
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

  it('T092: живой счётчик T080 и output-подпись joined-формы удалены из источника целиком', () => {
    // демонтаж ConferenceLiveCount: ни базового правила строки
    // «Участники: N / M», ни её селектора в списке скрытия пин-оверлея;
    // output-подпись «Вы в конференции» (прямой ребёнок
    // .conference-joined) тоже не существует — селектора скрытия нет
    expect(conferencesCss).not.toContain('.conference-live-count')
    expect(conferencesCss).not.toContain(
      'body:has(.conference-room-pinned) .conference-joined > output',
    )
  })
})

describe('паритет панели управления в двух режимах — пересмотр T089 (T098): статический пин CSS', () => {
  it('T098: правила скрытия «Покинуть» и тумблеров в пин-оверлее демонтированы — панель одна для обоих режимов', () => {
    // реинверсия пинов T089: «Покинуть» видна и кликабельна прямо из
    // развёрнутого тайла (выход одношаговый); blanket-скрытия controls
    // и точечных кнопок в оверлее тоже нет — состав кнопок идентичен
    expect(conferencesCss).not.toContain('.conference-room-pinned .conference-controls-leave')
    expect(conferencesCss).not.toMatch(
      /\.conference-room-pinned \.conference-controls\s*\{[^}]*display:\s*none/,
    )
    expect(conferencesCss).not.toContain('.conference-room-pinned .conference-controls-toggle')
  })

  it('позиционирование панели в оверлее остаётся T079: absolute снизу по центру — отличается размещение, не вид', () => {
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
})

describe('стимпанк-панель кнопок-иконок прототипа (T098): статический пин CSS', () => {
  it('оболочка machine на fieldset .conference-controls: латунная рамка + внутренняя рамка-вкладыш + фон-пластина', () => {
    // корпус машины из прототипа .machine: у самой оболочки overflow
    // visible (tooltip'ы выезжают за верхнюю границу); радиус машины 18px
    const shell = conferencesCss.match(/^\.conference-controls\s*\{([^}]*)\}/m)
    expect(shell).not.toBeNull()
    expect(shell?.[1]).toContain('border: 1px solid var(--gold-dark)')
    expect(shell?.[1]).toContain('border-radius: 18px')
    expect(shell?.[1]).toContain('background: rgba(20, 17, 12, 0.72)')
    expect(shell?.[1]).toContain('backdrop-filter: blur(2px)')
    expect(shell?.[1]).toContain('padding: 18px 24px')
    // равные интервалы кнопок прототипа — gap 22px
    expect(shell?.[1]).toContain('gap: 22px')
    // двойная рамка: внутренняя рамка-вкладыш ::before inset 4px
    const inner = conferencesCss.match(/\.conference-controls::before\s*\{([^}]*)\}/)
    expect(inner).not.toBeNull()
    expect(inner?.[1]).toContain('inset: 4px')
    expect(inner?.[1]).toContain('rgba(201, 162, 74, 0.35)')
  })

  it('decor-layer с ДВУМЯ вращающимися шестерёнками, обрезка слоем — оболочка не клиппит tooltip', () => {
    const layer = conferencesCss.match(/\.conference-controls-decor\s*\{([^}]*)\}/)
    expect(layer).not.toBeNull()
    expect(layer?.[1]).toContain('overflow: hidden')
    expect(layer?.[1]).toContain('pointer-events: none')
    // шестерёнки «выглядывают» из углов (tr/bl) и крутятся 90s linear infinite
    const tr = conferencesCss.match(/\.conference-controls-decor-tr\s*\{([^}]*)\}/)
    const bl = conferencesCss.match(/\.conference-controls-decor-bl\s*\{([^}]*)\}/)
    expect(tr).not.toBeNull()
    expect(bl).not.toBeNull()
    const spin = conferencesCss.match(
      /\.conference-controls-decor-tr,\s*\.conference-controls-decor-bl\s*\{([^}]*)\}/,
    )
    expect(spin?.[1]).toContain('animation: conference-controls-spin 90s linear infinite')
  })

  it('кнопки-иконки 78px: круглые, состояние — data-state (on/off/blocked) вместо текста «вкл/выкл»', () => {
    const button = conferencesCss.match(
      /^\.conference-controls-toggle,\s*\.conference-controls-leave\s*\{([^}]*)\}/m,
    )
    expect(button).not.toBeNull()
    expect(button?.[1]).toContain('width: 78px')
    expect(button?.[1]).toContain('height: 78px')
    expect(button?.[1]).toContain('border-radius: 50%')
    // on = золотая рамка + свечение
    const on = conferencesCss.match(/\.conference-controls-toggle\[data-state='on'\]\s*\{([^}]*)\}/)
    expect(on).not.toBeNull()
    expect(on?.[1]).toContain('border-color: var(--gold)')
    // лампа-индикатор горит в on (flick 4s)
    const lamp = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='on'\] \.conference-controls-lamp\s*\{([^}]*)\}/,
    )
    expect(lamp).not.toBeNull()
    expect(lamp?.[1]).toContain('animation: conference-controls-flick 4s ease-in-out infinite')
    // off = диагональный штрих --err
    const slashBase = conferencesCss.match(/\.conference-controls-off-slash\s*\{([^}]*)\}/)
    expect(slashBase?.[1]).toContain('var(--err)')
    const slash = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='off'\] \.conference-controls-off-slash\s*\{([^}]*)\}/,
    )
    expect(slash?.[1]).toContain('opacity: 0.95')
    // blocked = приглушение + замок-бейдж
    const blocked = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='blocked'\]\s*\{([^}]*)\}/,
    )
    expect(blocked?.[1]).toContain('saturate(0.55)')
    const lock = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='blocked'\] \.conference-controls-lock\s*\{([^}]*)\}/,
    )
    expect(lock?.[1]).toContain('opacity: 1')
    // on-части иконки (волны/ирис/луч) и шестерня проектора (6s)
    const onPart = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='on'\] \.conference-controls-on-part\s*\{([^}]*)\}/,
    )
    expect(onPart?.[1]).toContain('opacity: 1')
    const gear = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='on'\] \.conference-controls-gear\s*\{([^}]*)\}/,
    )
    expect(gear?.[1]).toContain('animation: conference-controls-spin 6s linear infinite')
  })

  it('латунный tooltip на кнопках: показ :hover/:focus-visible (замена нативного title T097)', () => {
    const tip = conferencesCss.match(/\.conference-controls-tooltip\s*\{([^}]*)\}/)
    expect(tip).not.toBeNull()
    expect(tip?.[1]).toContain('bottom: calc(100% + 12px)')
    expect(tip?.[1]).toContain('white-space: nowrap')
    expect(tip?.[1]).toContain('color: var(--cream)')
    const shown = conferencesCss.match(
      /\.conference-controls button:hover \.conference-controls-tooltip,\s*\.conference-controls button:focus-visible \.conference-controls-tooltip\s*\{([^}]*)\}/,
    )
    expect(shown).not.toBeNull()
    expect(shown?.[1]).toContain('opacity: 1')
  })

  it('«Покинуть» — круглая кнопка-иконка в акценте --err (геометрия прототипа)', () => {
    // собственное правило акции (после базовой геометрии .ctl): дверь
    // со стрелкой в err-латуни; hover-свечение — в err-спектре
    const leaveRules = [...conferencesCss.matchAll(/^\.conference-controls-leave\s*\{([^}]*)\}/gm)]
    expect(
      leaveRules.some(([, body]) => body?.includes('color: var(--err)') === true),
      'правило .conference-controls-leave с color: var(--err)',
    ).toBe(true)
  })

  it('переименование геометрии текстовых тумблеров в prejoin-скоуп (единственный потребитель — PreJoinScreen)', () => {
    // правила T028 НЕ удаляются бездумно: их геометрию переиспользует
    // PreJoinScreen — переименованы в .conference-prejoin-toggle/-state
    const toggleRule = conferencesCss.match(/^\.conference-prejoin-toggle\s*\{([^}]*)\}/m)
    expect(toggleRule).not.toBeNull()
    expect(toggleRule?.[1]).toContain('border: 1px solid var(--line)')
    expect(toggleRule?.[1]).toContain('background: var(--input-bg)')
    const pressed = conferencesCss.match(
      /\.conference-prejoin-toggle\[aria-pressed='true'\]\s*\{([^}]*)\}/,
    )
    expect(pressed?.[1]).toContain('border-color: var(--gold)')
    const state = conferencesCss.match(/^\.conference-prejoin-state\s*\{([^}]*)\}/m)
    expect(state?.[1]).toContain('color: var(--muted)')
    // старый текстовый индикатор controls удалён: в панели состояния
    // дублируются визуалом data-state, текстовых узлов нет
    expect(conferencesCss).not.toContain('.conference-controls-state')
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
      'body:has(.conference-room-pinned) .conference-tiles-compact',
    ]) {
      expect(conferencesCss, `селектор скрытия ${selector}`).toContain(selector)
    }
  })
})

describe('центрирование заголовков pre-join и гостевого шага имени (T091): статический пин CSS', () => {
  it('заголовок .conference-prejoin-title центрирован по панели', () => {
    // h2 — первый ребёнок flex-колонки .conference-prejoin без text-align
    // наследует left от панели, тогда как плейсхолдер превью центрирован;
    // центрируется только текст заголовка — flex-геометрия панели не трогается
    const title = conferencesCss.match(/^\.conference-prejoin-title\s*\{([^}]*)\}/m)
    expect(title).not.toBeNull()
    expect(title?.[1]).toContain('text-align: center')
  })

  it('заголовок .guest-join-title центрирован внутри формы .guest-join-name', () => {
    // симметрия гостевой точки входа (паритет Q1=C): тот же первый ребёнок
    // flex-колонки .guest-join-name — центрируется тем же правилом
    const title = conferencesCss.match(/^\.guest-join-title\s*\{([^}]*)\}/m)
    expect(title).not.toBeNull()
    expect(title?.[1]).toContain('text-align: center')
  })

  it('регресс-пин: типографика обоих заголовков сохранена', () => {
    for (const selector of ['.conference-prejoin-title', '.guest-join-title']) {
      const rule = conferencesCss.match(
        new RegExp(`^\\.${selector.slice(1)}\\s*\\{([^}]*)\\}`, 'm'),
      )
      expect(rule, `правило ${selector}`).not.toBeNull()
      expect(rule?.[1], `правило ${selector}`).toContain('margin: 0')
      expect(rule?.[1], `правило ${selector}`).toContain('font: 17px var(--font-heading)')
      expect(rule?.[1], `правило ${selector}`).toContain('letter-spacing: 0.04em')
      expect(rule?.[1], `правило ${selector}`).toContain('color: var(--cream-bright)')
    }
  })
})

describe('центрирование pre-join-форм по обеим осям (T096): статический пин CSS', () => {
  it('панель .conference-prejoin — margin: auto: центр фазовой колонки по горизонтали и вертикали', () => {
    // член чата: <dialog> — ребёнок flex-колонки .conference-join (растянута
    // на окно); auto-маржины flex-элемента поглощают свободное место по
    // обеим осям одной декларацией — «Настройка перед входом» в центре окна
    // вместо прижатости к верху длинной колонки
    const rule = conferencesCss.match(/^\.conference-prejoin\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('margin: auto')
  })

  it('форма шага имени .guest-join-name — margin: auto (тот же приём у гостя)', () => {
    // гость /join/:inviteToken: форма — прямой ребёнок корневой секции
    // (flex-колонка на 100dvh); центрирование симметрично pre-join члена
    const rule = conferencesCss.match(/^\.guest-join-name\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('margin: auto')
  })

  it('обвязка .guest-join-prejoin — margin: auto (переиспользуемый PreJoinScreen центрирован у обеих точек входа)', () => {
    // иначе один и тот же pre-join-экран центрирован у члена и прижат
    // к верху у гостя (паритет Q1=C); обвязка центрируется вместе со
    // своей ошибкой №55 как единый блок
    const rule = conferencesCss.match(/^\.guest-join-prejoin\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('margin: auto')
  })

  it('регресс-пин: ширина всех трёх правил сохранена — min(560px, 100%)', () => {
    // центрирование не меняет геометрию панелей: ширина, рамки и
    // типографика остаются как до правки
    for (const selector of ['.conference-prejoin', '.guest-join-name', '.guest-join-prejoin']) {
      const rule = conferencesCss.match(
        new RegExp(`^\\.${selector.slice(1)}\\s*\\{([^}]*)\\}`, 'm'),
      )
      expect(rule, `правило ${selector}`).not.toBeNull()
      expect(rule?.[1], `правило ${selector}`).toContain('width: min(560px, 100%)')
    }
  })
})

describe('демонтаж стека пояснений conference-controls (T097): статический пин CSS', () => {
  it('правило .conference-controls-restrictions удалено из источника целиком', () => {
    // T043 рендерил стек <output>-строк под тумблерами; T097: точечные
    // ограничения поясняются нативным title тумблера, «Все источники»
    // не поясняются вовсе — мёртвое правило не остаётся в CSS
    expect(conferencesCss).not.toContain('.conference-controls-restrictions')
  })

  it('нейтральная строка отказа .conference-controls-notice сохранена (регресс)', () => {
    // «Не удалось включить/выключить …» — единственный потребитель
    // класса после T097; геометрия не регрессирует (^ и m — не цеплять
    // хвосты составных селекторов)
    const rule = conferencesCss.match(/^\.conference-controls-notice\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('flex-basis: 100%')
    expect(rule?.[1]).toContain('font: italic 12px var(--font-body)')
    expect(rule?.[1]).toContain('color: var(--muted)')
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
