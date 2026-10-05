import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider, useToast } from '../Toast'

/**
 * Тост-примитив «Aethergram» (feature 008, T011; FR-025, data-model 1.4/3.2,
 * ui-behavior §5): ToastProvider + useToast — единый механизм уведомлений.
 * Одиночный слот z-99 (класс .toast из прототипа design/chats.html),
 * авто-скрытие 3000 мс, новый тост мгновенно заменяет предыдущий
 * с перезапуском таймера; закрывается только таймером (pointer-events: none).
 */

/** Потребитель-проба: кнопка вызывает show(text) через useToast. */
function ToastButton({ text }: { text: string }) {
  const show = useToast()
  return (
    <button type="button" onClick={() => show(text)}>
      операция
    </button>
  )
}

function renderWithButton(text: string) {
  return render(
    <ToastProvider>
      <ToastButton text={text} />
    </ToastProvider>,
  )
}

function toastSlot(): HTMLElement {
  const slot = document.querySelector<HTMLElement>('.toast')
  expect(slot).not.toBeNull()
  return slot as HTMLElement
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('Toast — ToastProvider/useToast: единый механизм (FR-025)', () => {
  it('useToast вне провайдера падает с понятной ошибкой', () => {
    // Ожидание консоль-шума React от необработанной ошибки рендера
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<ToastButton text="упс" />)).toThrow(/ToastProvider/u)
    spy.mockRestore()
  })

  it('слот всегда один в DOM, даже до первого тоста (скрыт, без show)', () => {
    render(
      <ToastProvider>
        <p>поверхность</p>
      </ToastProvider>,
    )
    const slots = document.querySelectorAll('.toast')
    expect(slots).toHaveLength(1)
    const slot = toastSlot()
    expect(slot.className).toBe('toast')
    expect(slot.textContent).toBe('')
  })

  it('show(text) выводит тост с текстом и классом show', () => {
    renderWithButton('Контакт заблокирован — Григорий')
    fireEvent.click(screen.getByRole('button', { name: 'операция' }))
    const slot = toastSlot()
    expect(slot.className).toBe('toast show')
    expect(slot.textContent).toBe('Контакт заблокирован — Григорий')
  })

  it('тост — status-регион для скринридеров (aria-live=polite)', () => {
    renderWithButton('Профиль обновлён')
    fireEvent.click(screen.getByRole('button', { name: 'операция' }))
    expect(screen.getByRole('status')).toHaveTextContent('Профиль обновлён')
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  })
})

describe('Toast — авто-скрытие 3000 мс (FR-025, data-model 1.4)', () => {
  it('скрывается ровно по таймеру: 2999 мс — ещё виден, 3000 мс — скрыт', () => {
    renderWithButton('Групповой чат создан — Проект Альфа')
    fireEvent.click(screen.getByRole('button', { name: 'операция' }))
    expect(toastSlot().className).toBe('toast show')

    act(() => {
      vi.advanceTimersByTime(2999)
    })
    expect(toastSlot().className).toBe('toast show')

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(toastSlot().className).toBe('toast')
  })

  it('скрытие — только таймером: клики по слоту не закрывают раньше', () => {
    renderWithButton('Контакт удалён — чат сохранён')
    fireEvent.click(screen.getByRole('button', { name: 'операция' }))
    fireEvent.click(toastSlot())
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(toastSlot().className).toBe('toast show')
  })

  it('таймер очищается при размонтировании провайдера', () => {
    const { unmount } = renderWithButton('Чат удалён — контакт сохранён')
    fireEvent.click(screen.getByRole('button', { name: 'операция' }))
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
    expect(() => vi.advanceTimersByTime(5000)).not.toThrow()
  })
})

describe('Toast — одиночный слот: новый тост заменяет предыдущий (FR-025, data-model 3.2)', () => {
  it('пока первый виден, второй мгновенно заменяет текст, слот остаётся один', () => {
    const Probe = () => {
      const show = useToast()
      return (
        <>
          <button type="button" onClick={() => show('Первый')}>
            один
          </button>
          <button type="button" onClick={() => show('Второй')}>
            два
          </button>
        </>
      )
    }
    render(
      <ToastProvider>
        <Probe />
      </ToastProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'один' }))
    expect(toastSlot().textContent).toBe('Первый')

    fireEvent.click(screen.getByRole('button', { name: 'два' }))
    expect(document.querySelectorAll('.toast')).toHaveLength(1)
    expect(toastSlot().className).toBe('toast show')
    expect(toastSlot().textContent).toBe('Второй')
  })

  it('замена перезапускает таймер: отсчёт 3000 мс от последнего тоста', () => {
    const Probe = () => {
      const show = useToast()
      return (
        <>
          <button type="button" onClick={() => show('Первый')}>
            один
          </button>
          <button type="button" onClick={() => show('Второй')}>
            два
          </button>
        </>
      )
    }
    render(
      <ToastProvider>
        <Probe />
      </ToastProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'один' }))
    act(() => {
      vi.advanceTimersByTime(2500)
    })
    fireEvent.click(screen.getByRole('button', { name: 'два' }))

    act(() => {
      vi.advanceTimersByTime(2999)
    })
    expect(toastSlot().className).toBe('toast show')
    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(toastSlot().className).toBe('toast')
  })

  it('после скрытия следующий тост показывается снова', () => {
    renderWithButton('Профиль обновлён')
    fireEvent.click(screen.getByRole('button', { name: 'операция' }))
    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(toastSlot().className).toBe('toast')

    fireEvent.click(screen.getByRole('button', { name: 'операция' }))
    expect(toastSlot().className).toBe('toast show')
    expect(toastSlot().textContent).toBe('Профиль обновлён')
  })
})
