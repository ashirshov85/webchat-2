/**
 * Тост-примитив «Aethergram» (feature 008, T011; FR-025, data-model 1.4/3.2,
 * ui-behavior §5): ToastProvider + useToast — единый механизм уведомлений
 * «одно на экране». DOM и классы — дословно из прототипа
 * specs/008-chat-window-styling/design/chats.html (`<div class="toast">`,
 * показ классом .show); позиция/тема — toast.css на токенах --z-toast/--r-toast
 * (поверх любых слоёв, z-99 над модалью). Жизненный цикл: show → авто-скрытие
 * через 3000 мс (TOAST_AUTO_HIDE_MS;data-model 1.4); новое событие в visible →
 * мгновенная замена текста + перезапуск таймера (одиночный слот, id-счётчик).
 * Закрывается только таймером: pointer-events none, клики молча проходят мимо.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import './toast.css'

/** Авто-скрытие видимого тоста (FR-025: ~3 секунды; data-model 1.4 — 3000 мс). */
const TOAST_AUTO_HIDE_MS = 3000

/** Показать тост с результатом завершившейся операции (ровно один на операцию). */
export type ShowToast = (text: string) => void

const ToastContext = createContext<ShowToast | null>(null)

/** Тост-слот: id — монотонный счётчик для замены предыдущего (data-model 1.4). */
interface ToastState {
  readonly id: number
  readonly text: string
  readonly visible: boolean
}

const HIDDEN_TOAST: ToastState = { id: 0, text: '', visible: false }

/**
 * Хук доступа к тостам: `const show = useToast(); show('Контакт добавлен')`.
 * Требует <ToastProvider> выше по дереву (один на приложение — одиночный слот).
 */
// eslint-disable-next-line react-refresh/only-export-components -- хук неотделим от контекста провайдера (plan.md: Toast.tsx = ToastProvider + useToast)
export function useToast(): ShowToast {
  const show = useContext(ToastContext)
  if (show === null) {
    throw new Error('useToast должен вызываться внутри <ToastProvider>')
  }
  return show
}

export function ToastProvider({ children }: { readonly children: ReactNode }) {
  const [toast, setToast] = useState<ToastState>(HIDDEN_TOAST)
  const counter = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const show = useCallback<ShowToast>((text: string) => {
    if (timer.current !== null) {
      clearTimeout(timer.current)
    }
    counter.current += 1
    setToast({ id: counter.current, text, visible: true })
    timer.current = setTimeout(() => {
      timer.current = null
      setToast((current) => ({ ...current, visible: false }))
    }, TOAST_AUTO_HIDE_MS)
  }, [])

  useEffect(
    () => () => {
      if (timer.current !== null) {
        clearTimeout(timer.current)
      }
    },
    [],
  )

  const value = useMemo(() => show, [show])

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className={toast.visible ? 'toast show' : 'toast'} role="status" aria-live="polite">
        {toast.text}
      </div>
    </ToastContext.Provider>
  )
}
