/**
 * Экран конференции (009, US1): цель ленивого чанка маршрута
 * `/conference/:id` (T025) — тяжёлый `livekit-client`/
 * `@livekit/components-react` попадает ТОЛЬКО сюда и не раздувает
 * основной бандл (research §10, plan.md Structure Decision). Страница
 * достраивается по задачам: T026 (useConference: №45/№46, join №47 →
 * JoinTicket, «одна конференция на вкладку», `conference_ended`),
 * T027/T028 (ConferenceRoom/MediaControls), T034 (интеграция pre-join
 * для всех точек входа — mode из query). До T026 рендерит каркас с
 * идентификатором конференции — маршрут и ленивая загрузка уже
 * зафиксированы и покрыты тестами (Vitest маршрута + e2e T029).
 */

/** Props экрана конференции: id из маршрута /conference/:id. */
export interface ConferencePageProps {
  readonly conferenceId: string
}

export default function ConferencePage({ conferenceId }: ConferencePageProps) {
  return (
    <section aria-label="Конференция" data-conference-id={conferenceId}>
      <p>Конференция {conferenceId}</p>
    </section>
  )
}
