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
 * «демонстрирует экран» (латунная курсивная лексика, 008).
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
 * выводится вовсе — правило удалено из источника целиком.
 *
 * T105 — полный демонтаж строки отказа .conference-controls-notice:
 * все уведомления панели живут в tooltip'ах кнопок (отказ переключения
 * «Не удалось включить/выключить …» — в tooltip СВОЕЙ кнопки, приоритет
 * pending > failure > точечное ограничение > имя) — правило удалено
 * из источника целиком (реинверсия сохранения из T097).
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
 *
 * T099 — задний фон сетки тайлов: та же отделка корпуса machine, что у
 * conference-controls T098, но БЕЗ decor-слоя шестерёнок. Константы
 * корпуса (пластина/окантовка/вкладыш/теневое кольцо) с T098 не
 * дублируются захардкоженными значениями (FR-002): заданы ОДИН раз
 * CSS-переменными на корневых секциях обеих точек входа и наследуются
 * поверхностям — панель управления (T098) и сетка тайлов (T099)
 * потребляют общий --conference-machine-*-лексикон. Машиной красится
 * только стандартная сетка: компактная полоса пина
 * (.conference-tiles-compact) живёт своей геометрией (:not-гвард),
 * grid-механика T084/T075 не меняется (auto-fit/minmax/gap).
 *
 * T101 — задний фон форм: pre-join (.conference-prejoin, обе точки
 * входа — член и гость) и шаг имени гостя (.guest-join-name) получают
 * ту же отделку корпуса machine, что панель управления T098 и сетка
 * тайлов T099, но БЕЗ decor-слоя шестерёнок: константы корпуса
 * потребляются из общего --conference-machine-*-лексикона (без дублей,
 * FR-002); центрирование T096 (margin: auto), заголовки T091 и
 * геометрия prejoin-тумблеров T098 не регрессируют; обвязка
 * .guest-join-prejoin сама не красится — двойной рамки у pre-join
 * гостя не возникает.
 *
 * T108 — индикаторы микрофона/камеры/экрана в строке участника
 * (прототипы tmp/conference-page-{moderator,participant}.html):
 * латунные мини-бейджи .cp-sig 22px между именем и кебабом, состояние
 * — data-state on/off/blocked; on = золото + on-части иконки, off =
 * приглушение + диагональный штрих --err, blocked = приглушение
 * saturate + замок-бейдж (только в виду модератора). Индикаторы НЕ
 * кликабельны (pointer-events: none) и БЕЗ tooltip.
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
    expect(section?.[1]).toContain('height: 100dvh')
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
    // скрытие остального: панель участников (объединённая T107 —
    // инструменты модератора живут в ней), компактная полоса остальных
    // тайлов; шапка и identity-строка удалены целиком (T087),
    // output-подпись joined-формы и живой счётчик T080 — тоже (T092):
    // их селекторов в источнике больше нет
    const hidden = conferencesCss.match(
      /body:has\(\.conference-room-pinned\) \.conference-participants[\s\S]{0,600}?\{([^}]*)\}/,
    )
    expect(hidden).not.toBeNull()
    expect(hidden?.[1]).toContain('display: none')
    for (const selector of [
      'body:has(.conference-room-pinned) .conference-participants',
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
    // visible (tooltip'ы выезжают за верхнюю границу); константы корпуса
    // выражены общим --conference-machine-*-лексиконом (единственный
    // источник — корневые секции конференции, пин в блоке T099 ниже)
    const shell = conferencesCss.match(/^\.conference-controls\s*\{([^}]*)\}/m)
    expect(shell).not.toBeNull()
    expect(shell?.[1]).toContain('border: var(--conference-machine-border)')
    expect(shell?.[1]).toContain('border-radius: var(--conference-machine-radius)')
    expect(shell?.[1]).toContain('background: var(--conference-machine-bg)')
    expect(shell?.[1]).toContain('backdrop-filter: var(--conference-machine-blur)')
    expect(shell?.[1]).toContain('padding: 18px 24px')
    // равные интервалы кнопок прототипа — gap 22px
    expect(shell?.[1]).toContain('gap: 22px')
    // двойная рамка: внутренняя рамка-вкладыш ::before inset 4px —
    // правило общее с сеткой тайлов (T099), пин в блоке T099 ниже
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

describe('задний фон сетки тайлов — корпус machine без шестерёнок (T099): статический пин CSS', () => {
  it('константы корпуса заданы ОДИН раз на корневых секциях обеих точек входа (общая лексика T098/T099)', () => {
    // FR-002: пластина/окантовка/вкладыш/теневое кольцо не дублируются
    // захардкоженными значениями в правилах-потребителях — единственный
    // источник: CSS-переменные на корневых секциях конференции (member
    // section[data-conference-id] и гость section[data-guest-join]);
    // значения — норматы прототипа .machine (tmp/conference-controls.html)
    const tokens = conferencesCss.match(
      /^section\[data-conference-id\],\nsection\[data-guest-join\]\s*\{([^}]*)\}/m,
    )
    expect(tokens).not.toBeNull()
    expect(tokens?.[1]).toContain('--conference-machine-bg: rgba(20, 17, 12, 0.72)')
    expect(tokens?.[1]).toContain('--conference-machine-blur: blur(2px)')
    expect(tokens?.[1]).toContain('--conference-machine-border: 1px solid var(--gold-dark)')
    expect(tokens?.[1]).toContain('--conference-machine-radius: 18px')
    expect(tokens?.[1]).toContain('--conference-machine-inner: rgba(201, 162, 74, 0.35)')
    expect(tokens?.[1]).toContain('--conference-machine-inner-radius: 14px')
    // теневое кольцо прототипа: 0 0 0 4px rgba(138,106,44,.16) + глубокая тень
    expect(tokens?.[1]).toContain('rgba(138, 106, 44, 0.16)')
    expect(tokens?.[1]).toContain('0 24px 60px rgba(0, 0, 0, 0.6)')
  })

  it('сетка .conference-tiles несёт пластину машины: заливка + backdrop-blur + латунная окантовка + радиус машины', () => {
    // фон навешивается на существующий элемент сетки (DOM/TSX не меняются);
    // :not-гвард — машиной красится только стандартная сетка, компактная
    // полоса пина живёт своей геометрией (пин ниже); position: relative —
    // опора ::before-вкладыша
    const grid = conferencesCss.match(
      /^\.conference-tiles:not\(\.conference-tiles-compact\)\s*\{([^}]*)\}/m,
    )
    expect(grid).not.toBeNull()
    expect(grid?.[1]).toContain('position: relative')
    expect(grid?.[1]).toContain('background: var(--conference-machine-bg)')
    expect(grid?.[1]).toContain('backdrop-filter: var(--conference-machine-blur)')
    expect(grid?.[1]).toContain('border: var(--conference-machine-border)')
    expect(grid?.[1]).toContain('border-radius: var(--conference-machine-radius)')
    expect(grid?.[1]).toContain('box-shadow: var(--conference-machine-shadow)')
    // внутренние отступы сетки — тайлы не подпирают рамку-вкладыш
    expect(grid?.[1]).toContain('padding:')
  })

  it('рамка-вкладыш ::before — общая с controls, не перехватывает события', () => {
    // прототип .machine::before: inset 4px, латунь 0.35, радиус 14px;
    // правило сгруппировано с панелью управления (константа общая)
    const inner = conferencesCss.match(
      /\.conference-controls::before,\s*\.conference-tiles:not\(\.conference-tiles-compact\)::before\s*\{([^}]*)\}/,
    )
    expect(inner).not.toBeNull()
    expect(inner?.[1]).toContain("content: ''")
    expect(inner?.[1]).toContain('position: absolute')
    expect(inner?.[1]).toContain('inset: 4px')
    expect(inner?.[1]).toContain('border: 1px solid var(--conference-machine-inner)')
    expect(inner?.[1]).toContain('border-radius: var(--conference-machine-inner-radius)')
    // вкладыш не перехватывает события тайлов (гвард задачи)
    expect(inner?.[1]).toContain('pointer-events: none')
  })

  it('«без svg шестерёнок»: в скоупе сетки нет decor-слоя и svg-правил — шестерни только у controls (T098)', () => {
    expect(conferencesCss).not.toMatch(/\.conference-tiles[^{}]*decor/)
    expect(conferencesCss).not.toMatch(/\.conference-tiles[^{}]*svg/)
  })

  it('регресс grid-механики T084/T075: auto-fit/minmax/gap не меняются, компактная полоса — своя геометрия', () => {
    const grid = conferencesCss.match(
      /^\.conference-tiles:not\(\.conference-tiles-compact\)\s*\{([^}]*)\}/m,
    )
    expect(grid?.[1]).toContain('display: grid')
    expect(grid?.[1]).toContain('grid-template-columns: repeat(auto-fit, minmax(220px, 1fr))')
    expect(grid?.[1]).toContain('gap: 8px')
    // компактная полоса пина T074 — горизонтальный flex-скролл без машины
    const compact = conferencesCss.match(/^\.conference-tiles-compact\s*\{([^}]*)\}/m)
    expect(compact).not.toBeNull()
    expect(compact?.[1]).toContain('display: flex')
    expect(compact?.[1]).toContain('overflow-x: auto')
  })

  it('докблок блока сетки truthfully описывает корпус machine без шестерёнок', () => {
    const at = conferencesCss.indexOf('.conference-tiles:not(.conference-tiles-compact)')
    expect(at).toBeGreaterThan(0)
    const doc = conferencesCss.slice(Math.max(0, at - 900), at)
    expect(doc).toContain('machine')
    expect(doc).toContain('шестерёнок')
  })
})

describe('задний фон форм pre-join и шага имени — корпус machine без шестерёнок (T101): статический пин CSS', () => {
  const panelRule = (selector: string) =>
    conferencesCss.match(new RegExp(`^\\.${selector}\\s*\\{([^}]*)\\}`, 'm'))

  it('обе панели несут пластину машины: заливка + backdrop-blur + латунная окантовка + радиус машины + теневое кольцо', () => {
    // фон навешивается на существующие панели (DOM/TSX не меняются);
    // константы корпуса — из общего --conference-machine-*-лексикона
    // (единственный источник — корневые секции конференции, T099)
    for (const selector of ['conference-prejoin', 'guest-join-name']) {
      const rule = panelRule(selector)
      expect(rule, `правило .${selector}`).not.toBeNull()
      // position: relative — опора ::before-вкладыша
      expect(rule?.[1], `.${selector}`).toContain('position: relative')
      expect(rule?.[1], `.${selector}`).toContain('background: var(--conference-machine-bg)')
      expect(rule?.[1], `.${selector}`).toContain('backdrop-filter: var(--conference-machine-blur)')
      expect(rule?.[1], `.${selector}`).toContain('border: var(--conference-machine-border)')
      expect(rule?.[1], `.${selector}`).toContain('border-radius: var(--conference-machine-radius)')
      expect(rule?.[1], `.${selector}`).toContain('box-shadow: var(--conference-machine-shadow)')
      // отступы — вкладыш inset 4px не подрезает контент (промер)
      expect(rule?.[1], `.${selector}`).toContain('padding:')
    }
  })

  it('рамка-вкладыш ::before у обеих панелей — правило общее с controls/сеткой, не перехватывает события', () => {
    const inner = conferencesCss.match(
      /\.conference-prejoin::before,\s*\.guest-join-name::before,\s*\.conference-controls::before,\s*\.conference-tiles:not\(\.conference-tiles-compact\)::before\s*\{([^}]*)\}/,
    )
    expect(inner).not.toBeNull()
    expect(inner?.[1]).toContain("content: ''")
    expect(inner?.[1]).toContain('position: absolute')
    expect(inner?.[1]).toContain('inset: 4px')
    expect(inner?.[1]).toContain('border: 1px solid var(--conference-machine-inner)')
    expect(inner?.[1]).toContain('border-radius: var(--conference-machine-inner-radius)')
    // вкладыш не участвует в таб-порядоке/кликах (гвард задачи:
    // dialog-семантика pre-join и фокус-порядок не трогаются)
    expect(inner?.[1]).toContain('pointer-events: none')
  })

  it('«без svg шестерёнок»: в скоупе форм нет decor-слоя и svg-правил — шестерни только у controls (T098)', () => {
    // пин по правилам: докблоки цитируют запрос «без svg шестерёнок»,
    // поэтому comments вырезаются — матчится только живой селектор
    const rulesOnly = conferencesCss.replace(/\/\*[\s\S]*?\*\//g, '')
    for (const selector of ['conference-prejoin', 'guest-join-name']) {
      expect(rulesOnly, `decor в скоупе .${selector}`).not.toMatch(
        new RegExp(`\\.${selector}[^{}]*decor`),
      )
      expect(rulesOnly, `svg в скоупе .${selector}`).not.toMatch(
        new RegExp(`\\.${selector}[^{}]*svg`),
      )
    }
  })

  it('регресс-пины T096/T098: margin: auto и ширина сохранены, prejoin-тумблеры на месте', () => {
    for (const selector of ['conference-prejoin', 'guest-join-name']) {
      const rule = panelRule(selector)
      expect(rule?.[1], `.${selector}: центрирование T096`).toContain('margin: auto')
      expect(rule?.[1], `.${selector}: ширина`).toContain('width: min(560px, 100%)')
    }
    const toggle = conferencesCss.match(/^\.conference-prejoin-toggle\s*\{([^}]*)\}/m)
    expect(toggle?.[1]).toContain('border: 1px solid var(--line)')
    expect(toggle?.[1]).toContain('background: var(--input-bg)')
  })

  it('лексика узкой панели 008 у форм ушла: старый бордер --line / радиус --r-frame / --panel-bg отсутствуют', () => {
    for (const selector of ['conference-prejoin', 'guest-join-name']) {
      const rule = panelRule(selector)
      expect(rule?.[1], `.${selector}`).not.toContain('var(--line)')
      expect(rule?.[1], `.${selector}`).not.toContain('var(--r-frame)')
      expect(rule?.[1], `.${selector}`).not.toContain('var(--panel-bg)')
    }
    // симметрия точек входа: обвязка .guest-join-prejoin НЕ красится
    // (гость получает pre-join той же пластиной, двойной рамки нет)
    const wrap = panelRule('guest-join-prejoin')
    expect(wrap?.[1]).not.toContain('border')
    expect(wrap?.[1]).not.toContain('--conference-machine')
  })

  it('докблоки блоков обеих панелей truthfully описывают корпус machine без шестерёнок', () => {
    for (const selector of ['.conference-prejoin {', '.guest-join-name {']) {
      const at = conferencesCss.indexOf(selector)
      expect(at, `селектор ${selector}`).toBeGreaterThan(0)
      const doc = conferencesCss.slice(Math.max(0, at - 900), at)
      expect(doc, `докблок ${selector}`).toContain('machine')
      expect(doc, `докблок ${selector}`).toContain('шестерёнок')
    }
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
    // T107: панель модерации объединена в панель участников —
    // отдельного селектора скрытия больше нет
    for (const selector of [
      'body:has(.conference-room-pinned) .conference-participants',
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

  it('правило .conference-controls-notice удалено из источника (демонтаж строки отказа T105)', () => {
    // все уведомления панели живут в tooltip'ах кнопок: отказ
    // переключения «Не удалось включить/выключить …» — в tooltip СВОЕЙ
    // кнопки, <output>-строки под кнопками больше нет — мёртвое
    // правило не остаётся в CSS (реинверсия по образцу T097)
    expect(conferencesCss).not.toContain('.conference-controls-notice')
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

  it('бейдж «демонстрирует экран» — латунная курсивная лексика (margin-left 6px, gold-light)', () => {
    const badge = conferencesCss.match(/\.conference-tile-sharing\s*\{([^}]*)\}/)
    expect(badge).not.toBeNull()
    expect(badge?.[1]).toContain('margin-left: 6px')
    expect(badge?.[1]).toContain('font-style: italic')
    expect(badge?.[1]).toContain('color: var(--gold-light)')
  })
})

describe('индикатор микрофона и пометки ролей тайла (T100): статический пин CSS', () => {
  it('бейдж .conference-tile-mic — позиция правый нижний угол (right/bottom 8px), круг 30px, латунная рамка, не перехватывает клики', () => {
    // прототип tmp/conference-tile-mic.html: бейдж — ИНДИКАТОР, не
    // кнопка → pointer-events: none (двойной клик тайла T074 жив)
    const badge = conferencesCss.match(/^\.conference-tile-mic\s*\{([^}]*)\}/m)
    expect(badge).not.toBeNull()
    expect(badge?.[1]).toContain('position: absolute')
    expect(badge?.[1]).toContain('right: 8px')
    expect(badge?.[1]).toContain('bottom: 8px')
    expect(badge?.[1]).toContain('width: 30px')
    expect(badge?.[1]).toContain('height: 30px')
    expect(badge?.[1]).toContain('border-radius: 50%')
    expect(badge?.[1]).toContain('pointer-events: none')
  })

  it('РОВНО ДВА состояния: вкл — золото по умолчанию; выкл — приглушение --muted + диагональный штрих --err', () => {
    // выкл (второе и ПОСЛЕДНЕЕ состояние): тёмная латунь + muted-иконка
    const off = conferencesCss.match(/\.conference-tile-mic\[data-mic='off'\]\s*\{([^}]*)\}/)
    expect(off).not.toBeNull()
    expect(off?.[1]).toContain('color: var(--muted)')
    const slash = conferencesCss.match(/^\.conference-tile-mic-slash\s*\{([^}]*)\}/m)
    expect(slash).not.toBeNull()
    expect(slash?.[1]).toContain('var(--err)')
    const slashShown = conferencesCss.match(
      /\.conference-tile-mic\[data-mic='off'\] \.conference-tile-mic-slash\s*\{([^}]*)\}/,
    )
    expect(slashShown?.[1]).toContain('opacity: 0.95')
    // третьего состояния (замок/«отключён модератором») и tooltip нет
    expect(conferencesCss).not.toMatch(/\.conference-tile-mic[^{}]*(blocked|lock|tooltip)/)
  })

  it('пульс при речи — ТОЛЬКО у бейджа: mic-speak 1.6s infinite, кадры золотого свечения', () => {
    // speaking + mic on (класс вешает TSX) — пульсирует бейдж, не рамка
    const pulse = conferencesCss.match(
      /\.conference-tile-mic\[data-mic='on'\]\.conference-tile-mic-pulse\s*\{([^}]*)\}/,
    )
    expect(pulse).not.toBeNull()
    expect(pulse?.[1]).toContain('animation: conference-tile-mic-speak 1.6s ease-in-out infinite')
    expect(pulse?.[1]).toContain('border-color: var(--gold-light)')
    const frames = conferencesCss.match(/@keyframes conference-tile-mic-speak\s*\{([\s\S]*?)\n\}/)
    expect(frames).not.toBeNull()
    expect(frames?.[1]).toContain('rgba(232, 200, 119, 0.35)')
    expect(frames?.[1]).toContain('rgba(232, 200, 119, 0.8)')
  })

  it('демонтаж текстового индикатора речи: селектора .conference-tile-speaking в источнике больше нет (реинверсия)', () => {
    expect(conferencesCss).not.toContain('.conference-tile-speaking')
  })

  it('пометки ролей в строке имени — приглушённый курсив --muted, без пластин/бейджей', () => {
    // «гость»/«модератор» рядом с именем (формат прототипа «Имя · гость»)
    const mark = conferencesCss.match(/^\.conference-tile-mark\s*\{([^}]*)\}/m)
    expect(mark).not.toBeNull()
    expect(mark?.[1]).toContain('font-style: italic')
    expect(mark?.[1]).toContain('color: var(--muted)')
    expect(mark?.[1]).not.toContain('border')
    expect(mark?.[1]).not.toContain('background')
  })

  it('idle-имя — plain-текст прототипа (cream 12.5px); бейдж масштабируется в развёрнутом тайле', () => {
    // имя под аватаром без дополнительной стилизации: размер прототипа
    const idleName = conferencesCss.match(
      /\.conference-tile-idle \.conference-tile-name\s*\{([^}]*)\}/,
    )
    expect(idleName?.[1]).toContain('font-size: 12.5px')
    // пин-оверлей T079: бейдж виден и в fullscreen-тайле (промер)
    const pinnedBadge = conferencesCss.match(
      /\.conference-tile-pinned \.conference-tile-mic\s*\{([^}]*)\}/,
    )
    expect(pinnedBadge).not.toBeNull()
    expect(pinnedBadge?.[1]).toContain('width: 40px')
    // регресс-пины соседней механики: бейдж демонстрации T084 на месте
    const sharing = conferencesCss.match(/\.conference-tile-sharing\s*\{([^}]*)\}/)
    expect(sharing?.[1]).toContain('color: var(--gold-light)')
  })

  it('докблок блока бейджа truthfully описывает индикатор (два состояния, пульс, прототип)', () => {
    const at = conferencesCss.indexOf('.conference-tile-mic {')
    expect(at).toBeGreaterThan(0)
    const doc = conferencesCss.slice(Math.max(0, at - 1400), at)
    expect(doc).toContain('T100')
    expect(doc).toContain('прототип')
    expect(doc).toContain('РОВНО ДВА состояния')
  })
})

describe('тематическая live-плашка шага имени гостя (T102): статический пин CSS', () => {
  it('плашка закрывает ВСЕ box-свойства глобального output-стиля: margin 0, ширина поля имени, тёмная пластина, латунная рамка, паддинг input', () => {
    // глобальное element-правило `output, [role='status']` (index.css)
    // навешивает каждому <output> светлую зелёную плашку auth-лексики
    // (margin 0 0 14px / padding 10px 12px / background
    // var(--success-bg)); класс (0,1,0) обязан закрыть каждое из них
    const rule = conferencesCss.match(/^\.guest-join-live\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('margin: 0')
    // во всю ширину поля имени: оба — flex-дети колонки .guest-join-name
    expect(rule?.[1]).toContain('width: 100%')
    // явная тёмная пластина прототипа — зелёная лексика исключена
    expect(rule?.[1]).toContain(
      'linear-gradient(180deg, rgba(34, 28, 18, 0.9), rgba(20, 16, 10, 0.92))',
    )
    expect(rule?.[1]).toContain('border: 1px solid var(--gold-dark)')
    expect(rule?.[1]).not.toContain('var(--success-bg)')
    expect(rule?.[1]).not.toContain('var(--success)')
    // паддинг равен .guest-join-name-input — пластина геометрически
    // совпадает с полем имени (7px 10px)
    const input = conferencesCss.match(/^\.guest-join-name-input\s*\{([^}]*)\}/m)
    expect(input?.[1]).toContain('padding: 7px 10px')
    expect(rule?.[1]).toContain('padding: 7px 10px')
    expect(rule?.[1]).toContain('overflow-wrap: break-word')
  })

  it('контент по центру + лампа-точка ::before с пульсом live-flick (паритет лампы тумблеров T098)', () => {
    const rule = conferencesCss.match(/^\.guest-join-live\s*\{([^}]*)\}/m)
    expect(rule?.[1]).toContain('display: flex')
    expect(rule?.[1]).toContain('align-items: center')
    expect(rule?.[1]).toContain('justify-content: center')
    expect(rule?.[1]).toContain('column-gap: 7px')
    expect(rule?.[1]).toContain('text-align: center')
    // лампа-точка 6px: radial-градиент прототипа + свечение + флик
    const lamp = conferencesCss.match(/^\.guest-join-live::before\s*\{([^}]*)\}/m)
    expect(lamp).not.toBeNull()
    expect(lamp?.[1]).toContain("content: ''")
    expect(lamp?.[1]).toContain('flex: none')
    expect(lamp?.[1]).toContain('width: 6px')
    expect(lamp?.[1]).toContain('height: 6px')
    expect(lamp?.[1]).toContain(
      'radial-gradient(circle at 35% 30%, #fff6d8, var(--gold-light) 45%, var(--gold-dark))',
    )
    expect(lamp?.[1]).toContain('box-shadow: 0 0 6px rgba(232, 200, 119, 0.8)')
    expect(lamp?.[1]).toContain('animation: live-flick 4s ease-in-out infinite')
    // кадры мерцания прототипа tmp/guest-join-title.html
    const frames = conferencesCss.match(/@keyframes live-flick\s*\{([\s\S]*?)\n\}/)
    expect(frames).not.toBeNull()
    expect(frames?.[1]).toContain('opacity: 0.6')
  })

  it('регресс-пины формы (T091/T096/T101) не меняются; глобальный output-стиль в index.css не правится', () => {
    // корпус machine, центрирование по обеим осям и ширина формы — на месте
    const form = conferencesCss.match(/^\.guest-join-name\s*\{([^}]*)\}/m)
    expect(form?.[1]).toContain('margin: auto')
    expect(form?.[1]).toContain('width: min(560px, 100%)')
    expect(form?.[1]).toContain('background: var(--conference-machine-bg)')
    const title = conferencesCss.match(/^\.guest-join-title\s*\{([^}]*)\}/m)
    expect(title?.[1]).toContain('text-align: center')
    // глобальное правило живёт без изменений — им пользуются
    // auth/настройки/статус-баннеры (status-banners.css перекрывает
    // его своими классами — тот же паттерн переопределения)
    const output = indexCss.match(/output,\s*\[role='status'\]\s*\{([^}]*)\}/)
    expect(output).not.toBeNull()
    expect(output?.[1]).toContain('var(--success-bg)')
  })

  it('докблок правила truthfully описывает перекрытие глобального output-стиля', () => {
    const at = conferencesCss.indexOf('.guest-join-live {')
    expect(at).toBeGreaterThan(0)
    const doc = conferencesCss.slice(Math.max(0, at - 1400), at)
    expect(doc).toContain('output')
    expect(doc).toContain('глобальн')
    expect(doc).toContain('T102')
  })
})

describe('состояние «в полёте» тумблеров — переливающийся контур (T103): статический пин CSS', () => {
  it("селектор [data-state='pending']: золотой бордер, штрих скрыт, on-части приглушены до 0.45", () => {
    // кнопка читается «нажатой, источник прогревается»: тёмная лампа/штрих
    // выкл уходит, но полного «вкл» нет — on-части иконки на 0.45
    const pending = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='pending'\]\s*\{([^}]*)\}/,
    )
    expect(pending).not.toBeNull()
    expect(pending?.[1]).toContain('border-color: var(--gold)')
    const slash = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='pending'\] \.conference-controls-off-slash\s*\{([^}]*)\}/,
    )
    expect(slash).not.toBeNull()
    expect(slash?.[1]).toContain('opacity: 0')
    const onPart = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='pending'\] \.conference-controls-on-part\s*\{([^}]*)\}/,
    )
    expect(onPart).not.toBeNull()
    expect(onPart?.[1]).toContain('opacity: 0.45')
  })

  it('кольцо ::after — вращающийся conic-gradient золотых тонов, замаскированный в тонкое кольцо поверх бордера', () => {
    const ring = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='pending'\]::after\s*\{([^}]*)\}/,
    )
    expect(ring).not.toBeNull()
    expect(ring?.[1]).toContain('position: absolute')
    expect(ring?.[1]).toContain('inset: -5px')
    expect(ring?.[1]).toContain('border-radius: 50%')
    // перелив: конус от прозрачного через золотые тона прототипа
    expect(ring?.[1]).toContain('conic-gradient')
    expect(ring?.[1]).toContain('var(--gold-light)')
    expect(ring?.[1]).toContain('#fff6d8')
    expect(ring?.[1]).toContain('var(--gold)')
    // маска оставляет тонкое кольцо ~1px (farthest-side, calc-ступени)
    expect(ring?.[1]).toContain('farthest-side')
    expect(ring?.[1]).toContain('calc(100% - 4px)')
    expect(ring?.[1]).toContain('calc(100% - 3px)')
    expect(ring?.[1]).toContain('mask:')
    expect(ring?.[1]).toContain('animation: conference-controls-ring-crawl 1.4s linear infinite')
    expect(ring?.[1]).toContain('pointer-events: none')
  })

  it('лампа «дышит» в полёте: горящий градиент + lamp-breathe 1.4s (пульс 0.35↔1)', () => {
    const lamp = conferencesCss.match(
      /\.conference-controls-toggle\[data-state='pending'\] \.conference-controls-lamp\s*\{([^}]*)\}/,
    )
    expect(lamp).not.toBeNull()
    expect(lamp?.[1]).toContain(
      'radial-gradient(circle at 35% 30%, #fff6d8, var(--gold-light) 45%, var(--gold-dark))',
    )
    expect(lamp?.[1]).toContain(
      'animation: conference-controls-lamp-breathe 1.4s ease-in-out infinite',
    )
    // кадры прототипа: горящая лампа пульсирует 0.35↔1
    const breathe = conferencesCss.match(
      /@keyframes conference-controls-lamp-breathe\s*\{([\s\S]*?)\n\}/,
    )
    expect(breathe).not.toBeNull()
    expect(breathe?.[1]).toContain('opacity: 0.35')
    expect(breathe?.[1]).toContain('opacity: 1')
    const crawl = conferencesCss.match(
      /@keyframes conference-controls-ring-crawl\s*\{([\s\S]*?)\n\}/,
    )
    expect(crawl).not.toBeNull()
    expect(crawl?.[1]).toContain('rotate(360deg)')
  })

  it('disabled-затемнение несёт карве-аут :not([data-state=pending]) — кнопка в полёте НЕ гаснет', () => {
    // прототип .ctl:disabled:not([data-state='pending']): две соседние
    // кнопки в полёте затемняются как раньше, летящая остаётся полной
    const rule = conferencesCss.match(
      /\.conference-controls button\[disabled\]:not\(\[data-state='blocked'\]\):not\(\[data-state='pending'\]\)\s*\{([^}]*)\}/,
    )
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('opacity: 0.45')
  })
})

describe('каркас экрана — сайдбар участников справа (T106): статический пин CSS', () => {
  it('.conference-main — flex-ROW: тайлы слева растягиваются, высота гасится внутри (flex:1 + min-height:0)', () => {
    // прототип tmp/conference-page-{moderator,participant}-{expanded,collapsed}.html:
    // рабочая область — строка «сетка тайлов + панель участников», flex:1
    // колонки комнаты, переполнение гасится ВНУТРИ (скроллбаров страницы нет)
    const rule = conferencesCss.match(/^\.conference-main\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('display: flex')
    expect(rule?.[1]).toContain('flex-direction: row')
    expect(rule?.[1]).toContain('flex: 1')
    expect(rule?.[1]).toContain('min-height: 0')
    expect(rule?.[1]).toContain('gap: var(--gap-frame)')
  })

  it('сетка тайлов внутри .conference-main — flex:1/min-width:0 + ВНУТРЕННЯЯ прокрутка с тонким латунным скроллбаром', () => {
    // скроллбар появляется, только когда тайлы не помещаются при
    // минимальном 220px (auto-fit); align-content:start — строки не
    // растягиваются по свободной высоте; компактная полоса пина T074
    // живёт своей геометрией (:not-гвард, паритет машины T099)
    const rule = conferencesCss.match(
      /^\.conference-main \.conference-tiles:not\(\.conference-tiles-compact\)\s*\{([^}]*)\}/m,
    )
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('flex: 1')
    expect(rule?.[1]).toContain('min-width: 0')
    expect(rule?.[1]).toContain('align-content: start')
    expect(rule?.[1]).toContain('overflow-y: auto')
    expect(rule?.[1]).toContain('scrollbar-width: thin')
    expect(rule?.[1]).toContain('scrollbar-color: #55452c transparent')
  })

  it('панель участников — фиксированная ширина 340px, заполняет колонку (flex:1) + [hidden]-гвард свёрнутого состояния', () => {
    // правая колонка фиксированной ширины; T107: панель — единственный
    // ребёнок колонки, растягивается на её высоту (flex:1) и сжимается
    // по min-height:0 — прокрутка живёт у СПИСКА, футер вне прокрутки;
    // свёрнутое состояние — нативный [hidden] (гвард обязателен:
    // авторский display:flex сильнее UA-стиля [hidden])
    const rule = conferencesCss.match(/^\.conference-participants\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('width: 340px')
    expect(rule?.[1]).toContain('flex: 1')
    expect(rule?.[1]).toContain('min-height: 0')
    const guard = conferencesCss.match(
      /^\.conference-side\[hidden\],\s*\.conference-participants\[hidden\]\s*\{([^}]*)\}/m,
    )
    expect(guard).not.toBeNull()
    expect(guard?.[1]).toContain('display: none')
  })

  it('правая колонка .conference-side — контейнер слота сайдбара (width 340, flex-колонка), на узких окнах сужается media-запросом', () => {
    // T107: колонка держит ОБЪЕДИНЁННУЮ панель участников; сама колонка
    // не прокручивается — скроллится только список панели
    const rule = conferencesCss.match(/^\.conference-side\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('display: flex')
    expect(rule?.[1]).toContain('flex-direction: column')
    expect(rule?.[1]).toContain('width: 340px')
    expect(rule?.[1]).toContain('flex: none')
    expect(rule?.[1]).toContain('min-height: 0')
    // media-запрос прототипа: на узких окнах панель сужается
    const media = conferencesCss.match(
      /@media \(max-width: ?960px\)\s*\{[^@]*?\.conference-side,\s*\.conference-participants\s*\{([^}]*)\}/,
    )
    expect(media).not.toBeNull()
    expect(media?.[1]).toContain('width: min(300px, 46vw)')
  })

  it('список участников — ВНУТРЕННЯЯ прокрутка (flex:1/min-height:0), тонкий латунный скроллбар', () => {
    // скроллбар может быть у списка участников (уточнение пользователя):
    // сама панель не скроллится — заголовок сверху остаётся видимым
    const rule = conferencesCss.match(/^\.conference-participants-list\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('flex: 1')
    expect(rule?.[1]).toContain('min-height: 0')
    expect(rule?.[1]).toContain('overflow-y: auto')
    expect(rule?.[1]).toContain('scrollbar-width: thin')
    expect(rule?.[1]).toContain('scrollbar-color: #55452c transparent')
  })

  it('полоса .conference-toggle-row над рабочей областью: кнопка справа, прижата к ней компенсацией gap', () => {
    const rule = conferencesCss.match(/^\.conference-toggle-row\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('display: flex')
    expect(rule?.[1]).toContain('justify-content: flex-end')
    expect(rule?.[1]).toContain('flex: none')
    expect(rule?.[1]).toContain('margin-bottom: calc(2px - var(--gap-frame))')
  })

  it('кнопка .conference-panel-toggle — ТОЛЬКО svg-иконка 17px: без текста, рамки, фона и теней; шеврон различает состояния', () => {
    // уточнения пользователя: «убрать текст и границы кнопки» — прототип:
    // border:none, background:transparent, теней нет; hover золотом,
    // focus-visible (008), шеврон ‹ развёрнута / › свёрнута (rotate 180)
    const rule = conferencesCss.match(/^\.conference-panel-toggle\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('border: none')
    expect(rule?.[1]).toContain('background: transparent')
    expect(rule?.[1]).not.toContain('box-shadow')
    const svg = conferencesCss.match(/^\.conference-panel-toggle svg\s*\{([^}]*)\}/m)
    expect(svg).not.toBeNull()
    expect(svg?.[1]).toContain('width: 17px')
    expect(svg?.[1]).toContain('height: 17px')
    const hover = conferencesCss.match(/^\.conference-panel-toggle:hover\s*\{([^}]*)\}/m)
    expect(hover?.[1]).toContain('color: var(--gold-light)')
    const focus = conferencesCss.match(/^\.conference-panel-toggle:focus-visible\s*\{([^}]*)\}/m)
    expect(focus?.[1]).toContain('outline: 2px solid var(--gold-light)')
    const chevron = conferencesCss.match(
      /^\.conference-panel-toggle\[aria-expanded='false'\] \.conference-panel-chevron\s*\{([^}]*)\}/m,
    )
    expect(chevron).not.toBeNull()
    expect(chevron?.[1]).toContain('transform: rotate(180deg)')
  })

  it('полноэкранный пин T079: полоса с кнопкой и колонка сайдбара скрыты существующим механизмом body:has', () => {
    // в пин-оверлее полоса не нужна; колонка сайдбара скрывается вместе с
    // панелью участников и компактной полосой тайлов (T107: отдельной
    // панели модерации больше нет — объединена в список участников)
    expect(conferencesCss).toContain('body:has(.conference-room-pinned) .conference-toggle-row')
    const toggleHidden = conferencesCss.match(
      /body:has\(\.conference-room-pinned\) \.conference-toggle-row\s*\{([^}]*)\}/,
    )
    expect(toggleHidden?.[1]).toContain('display: none')
    expect(conferencesCss).toContain('body:has(.conference-room-pinned) .conference-side')
    for (const selector of [
      'body:has(.conference-room-pinned) .conference-participants',
      'body:has(.conference-room-pinned) .conference-tiles-compact',
    ]) {
      expect(conferencesCss, `селектор скрытия ${selector}`).toContain(selector)
    }
  })

  it('controls всегда внизу по центру: align-self:center колонки комнаты (регресс базового правила)', () => {
    // «ребёнок колонки комнаты ПОД .conference-main» — базовое правило
    // оболочки уже центрирует (T098); в пин-оверлее — absolute T079
    const shell = conferencesCss.match(/^\.conference-controls\s*\{([^}]*)\}/m)
    expect(shell?.[1]).toContain('align-self: center')
  })

  it('докблок блока truthfully описывает каркас сайдбара (T106, прототип)', () => {
    const at = conferencesCss.indexOf('.conference-toggle-row {')
    expect(at).toBeGreaterThan(0)
    const doc = conferencesCss.slice(Math.max(0, at - 1800), at)
    expect(doc).toContain('T106')
    expect(doc).toContain('сайдбар')
  })
})

describe('объединённая панель участников (T107): статический пин CSS', () => {
  it('прокрутка ТОЛЬКО у списка: колонка .conference-side собственной прокрутки не имеет (после объединения панелей)', () => {
    // T106 держал overflow-y у колонки, пока панелей две; T107 — единая
    // панель: список flex:1/min-height:0/overflow-y:auto (пин T106 выше),
    // футер вне прокрутки, колонка сама не скроллится
    const rule = conferencesCss.match(/^\.conference-side\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).not.toContain('overflow-y')
    expect(rule?.[1]).not.toContain('scrollbar-width')
  })

  it('футер вне прокрутки: разделитель и инструменты панели — flex:none (не сжимаются списком)', () => {
    const divider = conferencesCss.match(/^\.conference-participants-divider\s*\{([^}]*)\}/m)
    expect(divider).not.toBeNull()
    expect(divider?.[1]).toContain('flex: none')
    expect(divider?.[1]).toContain(
      'linear-gradient(90deg, transparent, var(--gold-dark), transparent)',
    )
    const end = conferencesCss.match(/^\.conference-moderation-end\s*\{([^}]*)\}/m)
    expect(end).not.toBeNull()
    expect(end?.[1]).toContain('flex: none')
    const invite = conferencesCss.match(/^\.conference-guest-invite\s*\{([^}]*)\}/m)
    expect(invite).not.toBeNull()
    expect(invite?.[1]).toContain('flex: none')
  })

  it('кебаб строки .c-menu — паттерн GroupMembersModal/contacts-modal: 22px, прозрачная рамка/фон, hover золотом, svg 14px fill currentColor', () => {
    const rule = conferencesCss.match(/^\.conference-participants \.c-menu\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('width: 22px')
    expect(rule?.[1]).toContain('height: 22px')
    expect(rule?.[1]).toContain('border: 1px solid transparent')
    expect(rule?.[1]).toContain('background: transparent')
    const hover = conferencesCss.match(/^\.conference-participants \.c-menu:hover\s*\{([^}]*)\}/m)
    expect(hover?.[1]).toContain('color: var(--gold-light)')
    const focus = conferencesCss.match(
      /^\.conference-participants \.c-menu:focus-visible\s*\{([^}]*)\}/m,
    )
    expect(focus?.[1]).toContain('outline: 2px solid var(--gold-light)')
    const svg = conferencesCss.match(/^\.conference-participants \.c-menu svg\s*\{([^}]*)\}/m)
    expect(svg).not.toBeNull()
    expect(svg?.[1]).toContain('width: 14px')
    expect(svg?.[1]).toContain('height: 14px')
    expect(svg?.[1]).toContain('fill: currentColor')
  })

  it('строка: центр имени .cp-main (flex:1/min-width:0) и пометки роли «·»-разделителем (лексика тайлов T100)', () => {
    const main = conferencesCss.match(/^\.cp-main\s*\{([^}]*)\}/m)
    expect(main).not.toBeNull()
    expect(main?.[1]).toContain('flex: 1')
    expect(main?.[1]).toContain('min-width: 0')
    const mark = conferencesCss.match(/^\.conference-participants-mark\s*\{([^}]*)\}/m)
    expect(mark).not.toBeNull()
    expect(mark?.[1]).toContain('font: italic 11px')
    const sep = conferencesCss.match(/^\.conference-participants-mark::before\s*\{([^}]*)\}/m)
    expect(sep?.[1]).toContain("content: '·'")
  })

  it('санация conference-moderation-*: контейнер/список/тумблеры панели модерации удалены, переиспользуемая лексика футера сохранена', () => {
    for (const gone of [
      '.conference-moderation {',
      '.conference-moderation-title',
      '.conference-moderation-list',
      '.conference-moderation-item',
      '.conference-moderation-actions',
      '.conference-moderation-toggle',
      '.conference-moderation-button-restricted',
      'body:has(.conference-room-pinned) .conference-moderation',
    ]) {
      expect(conferencesCss, `демонтированный селектор ${gone}`).not.toContain(gone)
    }
    // переиспользовано футером/подтверждением (T109 перенесёт в модаль)
    for (const kept of [
      '.conference-moderation-button',
      '.conference-moderation-button-danger',
      '.conference-moderation-end',
      '.conference-moderation-confirm',
      '.conference-moderation-notice',
      '.conference-guest-invite',
    ]) {
      expect(conferencesCss, `переиспользуемый селектор ${kept}`).toContain(kept)
    }
  })

  it('демонтированные строки-заметки T041: identity/online/ограничения из строки убраны (индикаторы — T108)', () => {
    for (const gone of [
      '.conference-participants-identity',
      '.conference-participants-online',
      '.conference-participants-restricted',
      '.conference-participants-role',
    ]) {
      expect(conferencesCss, `демонтированный селектор ${gone}`).not.toContain(gone)
    }
  })

  it('докблок блока truthfully описывает объединённую панель (T107, прототип)', () => {
    const at = conferencesCss.indexOf('.conference-participants-divider {')
    expect(at).toBeGreaterThan(0)
    const doc = conferencesCss.slice(Math.max(0, at - 2600), at)
    expect(doc).toContain('T107')
    expect(doc).toContain('кебаб')
  })
})

describe('индикаторы источников строки участника (T108): статический пин CSS', () => {
  it('мини-бейдж .cp-sig — круг 22px с латунной рамкой; контейнер .cp-sigs не сжимает строку (flex:none)', () => {
    const box = conferencesCss.match(/^\.cp-sigs\s*\{([^}]*)\}/m)
    expect(box).not.toBeNull()
    expect(box?.[1]).toContain('display: inline-flex')
    expect(box?.[1]).toContain('flex: none')
    const sig = conferencesCss.match(/^\.cp-sig\s*\{([^}]*)\}/m)
    expect(sig).not.toBeNull()
    expect(sig?.[1]).toContain('width: 22px')
    expect(sig?.[1]).toContain('height: 22px')
    expect(sig?.[1]).toContain('border-radius: 50%')
    expect(sig?.[1]).toContain('border: 1.5px solid var(--gold)')
    // svg приборов 12px, инлайн-штриховка currentColor
    const svg = conferencesCss.match(/^\.cp-sig svg\s*\{([^}]*)\}/m)
    expect(svg).not.toBeNull()
    expect(svg?.[1]).toContain('width: 12px')
    expect(svg?.[1]).toContain('height: 12px')
  })

  it('НЕ кликабельны: pointer-events:none + user-select:none — индикатор span, не кнопка', () => {
    const sig = conferencesCss.match(/^\.cp-sig\s*\{([^}]*)\}/m)
    expect(sig?.[1]).toContain('pointer-events: none')
    expect(sig?.[1]).toContain('user-select: none')
  })

  it('ТРИ состояния [data-state]: on — золото + on-части; off — приглушение + штрих --err; blocked — saturate + замок', () => {
    const on = conferencesCss.match(/\.cp-sig\[data-state='on'\]\s*\{([^}]*)\}/)
    expect(on).not.toBeNull()
    expect(on?.[1]).toContain('border-color: var(--gold)')
    expect(on?.[1]).toContain('color: var(--gold-light)')
    const onPart = conferencesCss.match(/\.cp-sig\[data-state='on'\] \.cp-sig-on\s*\{([^}]*)\}/)
    expect(onPart?.[1]).toContain('opacity: 1')
    const off = conferencesCss.match(/\.cp-sig\[data-state='off'\]\s*\{([^}]*)\}/)
    expect(off).not.toBeNull()
    expect(off?.[1]).toContain('border-color: #55452c')
    expect(off?.[1]).toContain('color: var(--muted)')
    const slash = conferencesCss.match(/^\.cp-sig-slash\s*\{([^}]*)\}/m)
    expect(slash?.[1]).toContain('var(--err)')
    const slashShown = conferencesCss.match(
      /\.cp-sig\[data-state='off'\] \.cp-sig-slash\s*\{([^}]*)\}/,
    )
    expect(slashShown?.[1]).toContain('opacity: 0.95')
    // третье состояние «отключено модератором» — лексика blocked тумблеров T098
    const blocked = conferencesCss.match(/\.cp-sig\[data-state='blocked'\]\s*\{([^}]*)\}/)
    expect(blocked).not.toBeNull()
    expect(blocked?.[1]).toContain('saturate(0.55)')
    const lock = conferencesCss.match(
      /\.cp-sig\[data-state='blocked'\] \.cp-sig-lock\s*\{([^}]*)\}/,
    )
    expect(lock?.[1]).toContain('opacity: 1')
  })

  it('БЕЗ tooltip: у индикаторов нет tooltip-классов/правил (запрос T108)', () => {
    expect(conferencesCss).not.toMatch(/\.cp-sig[^{}]*tooltip/)
  })

  it('замок-бейдж — 12px в правом нижнем углу (right/bottom -3px), латунный круг', () => {
    const lock = conferencesCss.match(/^\.cp-sig-lock\s*\{([^}]*)\}/m)
    expect(lock).not.toBeNull()
    expect(lock?.[1]).toContain('position: absolute')
    expect(lock?.[1]).toContain('right: -3px')
    expect(lock?.[1]).toContain('bottom: -3px')
    expect(lock?.[1]).toContain('width: 12px')
    expect(lock?.[1]).toContain('height: 12px')
    const lockSvg = conferencesCss.match(/^\.cp-sig-lock svg\s*\{([^}]*)\}/m)
    expect(lockSvg?.[1]).toContain('width: 7px')
    expect(lockSvg?.[1]).toContain('height: 7px')
  })

  it('докблок блока truthfully описывает индикаторы (T108, прототип, три состояния)', () => {
    const at = conferencesCss.indexOf('.cp-sigs {')
    expect(at).toBeGreaterThan(0)
    const doc = conferencesCss.slice(Math.max(0, at - 1600), at)
    expect(doc).toContain('T108')
    expect(doc).toContain('прототип')
  })
})
