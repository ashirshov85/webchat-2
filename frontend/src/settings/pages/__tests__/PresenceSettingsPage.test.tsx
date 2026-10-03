import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PresenceSettingsPage } from '../PresenceSettingsPage'

/**
 * PresenceSettingsPage (feature 007, T034; quickstart QS-4): the №38
 * «incognito» profile toggle. GET on mount reflects the persisted mode
 * (survives relogin — PG V15), PUT flips it; failures surface via
 * problemMessage and the checkbox reverts to the server value.
 */

const { mockFetchPresenceSettings, mockUpdatePresenceSettings } = vi.hoisted(() => ({
  mockFetchPresenceSettings: vi.fn(),
  mockUpdatePresenceSettings: vi.fn(),
}))

vi.mock('../../../presence/presenceApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../presence/presenceApi')>()
  return {
    ...actual,
    fetchPresenceSettings: mockFetchPresenceSettings,
    updatePresenceSettings: mockUpdatePresenceSettings,
  }
})

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('PresenceSettingsPage', () => {
  it('loads the persisted incognito mode on mount (survives relogin)', async () => {
    mockFetchPresenceSettings.mockResolvedValueOnce({ incognito: true })
    render(<PresenceSettingsPage />)

    const toggle = await screen.findByRole('checkbox', { name: 'Incognito mode' })
    expect(toggle).toBeChecked()
    expect(mockFetchPresenceSettings).toHaveBeenCalledTimes(1)
  })

  it('PUTs the new mode on toggle and keeps it checked on the saved answer', async () => {
    mockFetchPresenceSettings.mockResolvedValueOnce({ incognito: false })
    mockUpdatePresenceSettings.mockResolvedValueOnce({ incognito: true })
    render(<PresenceSettingsPage />)

    const toggle = await screen.findByRole('checkbox', { name: 'Incognito mode' })
    fireEvent.click(toggle)

    await waitFor(() => expect(mockUpdatePresenceSettings).toHaveBeenCalledTimes(1))
    expect(mockUpdatePresenceSettings.mock.calls[0]?.[0]).toBe(true)
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Incognito mode' })).toBeChecked(),
    )
  })

  it('shows the problem message and reverts the checkbox when the PUT fails', async () => {
    mockFetchPresenceSettings.mockResolvedValueOnce({ incognito: false })
    mockUpdatePresenceSettings.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      errors: { incognito: ['flood_limit'] },
      retryAfterSec: 30,
    })
    render(<PresenceSettingsPage />)

    const toggle = await screen.findByRole('checkbox', { name: 'Incognito mode' })
    fireEvent.click(toggle)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('incognito: flood_limit')
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Incognito mode' })).not.toBeChecked(),
    )
  })

  it('blocks a second toggle while the PUT is in flight', async () => {
    mockFetchPresenceSettings.mockResolvedValueOnce({ incognito: false })
    let resolvePut: (value: { incognito: boolean }) => void = () => {}
    mockUpdatePresenceSettings.mockReturnValueOnce(
      new Promise((resolve) => {
        resolvePut = resolve
      }),
    )
    render(<PresenceSettingsPage />)

    const toggle = await screen.findByRole('checkbox', { name: 'Incognito mode' })
    fireEvent.click(toggle)
    expect(toggle).toBeDisabled()

    resolvePut({ incognito: true })
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Incognito mode' })).toBeEnabled(),
    )
  })

  it('shows an error state when the initial load fails', async () => {
    mockFetchPresenceSettings.mockRejectedValueOnce({ title: 'Unauthorized', status: 401 })
    render(<PresenceSettingsPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'Could not load your presence settings. Please refresh the page.',
    )
    expect(screen.queryByRole('checkbox', { name: 'Incognito mode' })).not.toBeInTheDocument()
  })
})
