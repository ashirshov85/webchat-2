import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 009, T118 — статический пин замыкания плашек глобальных
 * element-правил index.css за уведомлениями мессенджера (chats-часть
 * задачи; конференц-поверхности — conferences-css.test.ts). Vitest
 * CSS-импорты — no-op, jsdom не каскадирует: источники читаются через
 * node:fs (паттерн status-banners-css.test.ts).
 *
 * Суть T118: за статусами/ошибками, чьи классы перекрывали глобальные
 * правила `output, [role='status']` и `[role='alert']` (index.css —
 * светлые плашки auth-лексики success #e8f5ee/#17301f и danger
 * #fceceb/#3a1d1b) только по типографике/цвету текста, «просвечивала»
 * чужая заливка — подпись стояла «в другом элементе с границами на
 * белом фоне» (то же семейство, что и баг T116 за SyncIndicator).
 * Договорка по паттерну T051/T102: правило компонента обязано
 * объявить КАЖДОЕ box-свойство глобуса —
 * display/margin/padding/border-radius/background.
 *
 * Специфичность (докблок status-banners.css): index.css идёт ПОСЛЕ
 * компонентных CSS в сборке (main.tsx импортирует App раньше
 * './index.css'), поэтому селекторы-«ties» (0,1,0) с `[role='alert']`
 * ПРОИГРЫВАЮТ и требуют запаса класс+элемент (0,1,1)+;
 * element-селектор `output` (0,0,1) класс (0,1,0) бьёт по
 * объявленным свойствам — договорка добирает необъявленные.
 *
 * Сами глобальные правила index.css НЕ правятся — ими живут auth
 * (SsoCallbackPage/ConfirmRegistrationPage/SetPasswordForm) и
 * настройки (паттерн T102).
 */
const messageListCss = readFileSync(join(import.meta.dirname, '../message-list.css'), 'utf8')
const contactsModalCss = readFileSync(join(import.meta.dirname, '../contacts-modal.css'), 'utf8')

describe('замыкание плашек глобуса за уведомлениями мессенджера (T118): статический пин CSS', () => {
  it('карточка-приглашение: нейтральная подпись .sys-status — латунная плашка машины, замыкает ВСЕ box-свойства глобуса', () => {
    // <output class="sys-status"> (ConferenceInviteCard): селектор
    // (0,2,0) перекрывает ОБЕ глобальные по каждому свойству —
    // латунная плашка машины (лексика hover-строки списка чатов
    // rgba(201,162,74,.08) и бейджа «Звонок идёт»), НЕ success-auth
    const rule = messageListCss.match(/^\.message-list \.sys-status\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('display: block')
    expect(rule?.[1]).toContain('margin: 0')
    expect(rule?.[1]).toContain('padding: 6px 12px')
    expect(rule?.[1]).toContain('border: 1px solid rgba(201, 162, 74, 0.35)')
    expect(rule?.[1]).toContain('border-radius: var(--r-field)')
    expect(rule?.[1]).toContain('background: rgba(201, 162, 74, 0.08)')
    // типографика гравировки прежняя (10.5px heading/трекинг/muted)
    expect(rule?.[1]).toContain('font: 10.5px var(--font-heading)')
    expect(rule?.[1]).toContain('letter-spacing: 1px')
    expect(rule?.[1]).toContain('color: var(--muted)')
    // зелёная лексика auth-поверхностей исключена
    expect(rule?.[1]).not.toContain('var(--success)')
    expect(rule?.[1]).not.toContain('var(--success-bg)')
  })

  it('карточка-приглашение: транспортный сбой .sys-status-error — danger-смывка машины (span[role=alert])', () => {
    // <span class="sys-status sys-status-error" role="alert">: (0,2,0)
    // бьёт глобус — danger-смывка машины (лексика .error-banner /
    // output.queue-overflow-banner), НЕ светлая danger-auth
    const rule = messageListCss.match(/^\.message-list \.sys-status-error\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('display: inline-block')
    expect(rule?.[1]).toContain('margin: 0')
    expect(rule?.[1]).toContain('padding: 6px 12px')
    expect(rule?.[1]).toContain('border: 1px solid rgba(255, 122, 102, 0.4)')
    expect(rule?.[1]).toContain('border-radius: var(--r-field)')
    expect(rule?.[1]).toContain('background: rgba(126, 51, 32, 0.35)')
    expect(rule?.[1]).toContain('color: var(--err)')
    // светлая danger-плашка auth исключена
    expect(rule?.[1]).not.toContain('var(--danger-bg)')
  })

  it('пикеры: .pick-empty замыкает output-глобус — тихая подпись без плашки', () => {
    // output-варианты («Загрузка…» ProfileModal/CreateGroupDialog,
    // «Загрузка контактов…» GroupEditModal) тянули success-плашку
    // глобуса; div-вариантам («Нет контактов»/«Ничего не найдено»/
    // «Нет участников») договорка безвредна — element-правило их
    // не затрагивает
    const rule = contactsModalCss.match(/^\.pick-empty\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('display: block')
    expect(rule?.[1]).toContain('margin: 0')
    expect(rule?.[1]).toContain('border-radius: 0')
    expect(rule?.[1]).toContain('background: none')
    // прежняя лексика тихой подписи
    expect(rule?.[1]).toContain('padding: 8px')
    expect(rule?.[1]).toContain('font: italic 12.5px var(--font-body)')
    expect(rule?.[1]).toContain('color: var(--muted-3)')
  })

  it('инлайн-ошибки форм: селектор div.modal-err (0,1,1) бьёт tie-[role=alert] — тихая курсив-опасность без плашки', () => {
    // девять <div class="modal-err" role="alert"> (ContactsModal,
    // GroupEditModal, GroupMembersModal, ProfileModal,
    // CreateGroupDialog): класс-селектор (0,1,0) с [role='alert']
    // давал tie — глобус позже в сборке и побеждал; запас класс+
    // элемент закрывает договорку каждое box-свойство глобуса
    const rule = contactsModalCss.match(/^div\.modal-err\s*\{([^}]*)\}/m)
    expect(rule).not.toBeNull()
    expect(rule?.[1]).toContain('margin: 0')
    expect(rule?.[1]).toContain('padding: 0')
    expect(rule?.[1]).toContain('border-radius: 0')
    expect(rule?.[1]).toContain('background: none')
    // прежняя лексика тихой опасности
    expect(rule?.[1]).toContain('min-height: 17px')
    expect(rule?.[1]).toContain('font: italic 12.5px var(--font-body)')
    expect(rule?.[1]).toContain('color: var(--err)')
    // старый tie-селектор без элемента больше не живёт
    expect(contactsModalCss).not.toMatch(/^\.modal-err\s*\{/m)
  })

  it('докблоки правил truthfully описывают договорки глобуса (T118)', () => {
    const sysAt = messageListCss.indexOf('.message-list .sys-status {')
    expect(sysAt).toBeGreaterThan(0)
    const sysDoc = messageListCss.slice(Math.max(0, sysAt - 1200), sysAt)
    expect(sysDoc).toContain('T118')
    expect(sysDoc).toContain('index.css')

    const errAt = contactsModalCss.indexOf('div.modal-err {')
    expect(errAt).toBeGreaterThan(0)
    const errDoc = contactsModalCss.slice(Math.max(0, errAt - 900), errAt)
    expect(errDoc).toContain('T118')
    expect(errDoc).toContain('index.css')
  })
})
