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
 *
 * 009 (T110): строка переведена из лексики полос доставки (008
 * US4/T052 — латунная плашка + мерцающая точка, status-banners.css)
 * в семью тихих подписей `.messenger-empty` (states.css, FR-032:
 * курсив Old Standard TT 12.5px по центру на --muted-3) — та же
 * лексика, что у «Загрузка чатов…»; сайдбар-паддинг 10px даёт
 * масштаб `.chat-panel .messenger-empty` без собственных правил.
 * Замки a11y сохранены дословно: role=status + текст live-region.
 */
import './states.css'

export interface SyncIndicatorProps {
  /** A §3.1 catch-up cycle is running (useSync `syncing`, T019). */
  readonly syncing: boolean
}

export function SyncIndicator({ syncing }: SyncIndicatorProps) {
  if (!syncing) {
    return null
  }
  return (
    <output role="status" className="messenger-empty">
      Синхронизация сообщений…
    </output>
  )
}
