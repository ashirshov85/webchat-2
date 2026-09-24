/**
 * Queue-overflow notification banner (feature 005, T028; FR-005,
 * data-model entity 4, quickstart §3.3.4): when the offline queue
 * passes its fixed storage limit, `outbox.ts` (T026) evicts the
 * oldest `sending` records to terminal `failed`/`queue_overflow` and
 * fires `onOutboxOverflow` — this banner is that listener and the
 * explicit user notification the spec demands. It aggregates the
 * evictions of the signed-in account until dismissed (an offline
 * burst can evict several records in a row) and ignores other
 * accounts' events — the outbox storage is per-user, like the sync
 * cursors of T017. The evicted messages themselves stay visible in
 * their dialogs as «не отправлено (переполнение очереди)» with the
 * manual retry action (MessageList, T028); the banner explains why
 * they appeared and that auto-retries have stopped.
 */
import { useEffect, useState } from 'react'
import { onOutboxOverflow } from '../outbox'

export interface QueueOverflowBannerProps {
  /** Signed-in account: only its evictions are announced. */
  readonly userId: string | null
}

export function QueueOverflowBanner({ userId }: QueueOverflowBannerProps) {
  const [evictedCount, setEvictedCount] = useState(0)

  useEffect(() => {
    if (userId === null) {
      return
    }
    return onOutboxOverflow((event) => {
      if (event.userId !== userId) {
        return
      }
      setEvictedCount((previous) => previous + event.evicted.length)
    })
  }, [userId])

  if (evictedCount === 0) {
    return null
  }
  return (
    <div className="queue-overflow-banner" role="status">
      <p className="queue-overflow-banner-text">
        Оффлайн-очередь переполнена: вытеснено сообщений — {evictedCount}. Вытесненные отмечены «не
        отправлено (переполнение очереди)» и не отправляются автоматически — повторите отправку
        вручную.
      </p>
      <button
        type="button"
        className="queue-overflow-banner-dismiss"
        aria-label="Закрыть уведомление"
        onClick={() => {
          setEvictedCount(0)
        }}
      >
        ×
      </button>
    </div>
  )
}
