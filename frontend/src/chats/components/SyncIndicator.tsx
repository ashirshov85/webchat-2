/**
 * Catch-up progress indicator (feature 005, T020; sync-protocol §3.1):
 * lights up while the A/B cycles of `useSync` (T019) are pulling the
 * messages missed during a disconnect and goes dark the moment the
 * cycle completes — the «индикатор дозагрузки мелькнул и погас» of
 * quickstart §3.1.1. Purely presentational: `syncing` is the
 * single-flight signal of useSync, so triggers arriving mid-cycle
 * keep one steady indication instead of blinking per request. A
 * polite live region: screen readers announce the catch-up without
 * interrupting the realtime rendering that continues in parallel.
 */
export interface SyncIndicatorProps {
  /** A §3.1 catch-up cycle is running (useSync `syncing`, T019). */
  readonly syncing: boolean
}

export function SyncIndicator({ syncing }: SyncIndicatorProps) {
  if (!syncing) {
    return null
  }
  return (
    <div className="sync-indicator" role="status">
      <span className="sync-indicator-dot" aria-hidden="true" />
      Синхронизация сообщений…
    </div>
  )
}
