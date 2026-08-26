import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { SystemEventRow as SystemEventRowModel } from '../../core/system-events/system-events-logic';

/**
 * `vision-system-event-row` — one generic `LiveEvent`-derived row (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2-
 * §3.3), the notification bell's own system-events section. A **sibling** of `shared/ui/event-row.ts`
 * (`<vision-event-row>`), not a widened version of it: `EventRow` is typed to `DetectionEvent`
 * (`OPEN`/`CLOSED` state, `peakConfidence`) and every one of its call sites (`events-rail.ts`,
 * `features/alerts/**`, the asset detail Events section) stays byte-for-byte unchanged by this wave —
 * widening `EventRow.event` to a union would have forced every one of those templates to keep
 * narrowing a shape they never asked for, for a row whose fields (`severity`/`title`/`detail`, no
 * open/closed state or confidence at all) barely overlap `DetectionEvent`'s own. A dedicated sibling
 * with its own small template was the lower-risk, more honest option.
 *
 * **Informational, not a navigation target** — mirrors `features/asset-detail/**`'s own Events
 * section, which reuses this markup minus the click affordance, rather than `vision-event-row`'s
 * clickable rail/bell rows. A generic system event (`STREAM_STARTED`, `DEVICE_OFFLINE`, …) has no
 * single canonical destination the way a detection event's asset/live-cockpit target does, and the
 * scope (`SYSTEM-STATUS-PLAN.md` S1) is "give these events a durable, readable home", not "wire a
 * second navigation graph" — a later wave can add a click target once there's a real page
 * (`/manage/system`, S3) for one to resolve to.
 */
@Component({
  selector: 'vision-system-event-row',
  templateUrl: './system-event-row.html',
  styleUrl: './system-event-row.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SystemEventRow {
  readonly event = input.required<SystemEventRowModel>();
  /** `core/system-events/system-events-logic.ts#describeSystemEventSource`'s own result — resolved by
   *  the host, which already holds `FleetStore`'s devices/streams snapshots (this component stays
   *  dumb, mirroring `vision-event-row`'s identical `sourceLabel` input). */
  readonly sourceLabel = input.required<string>();
  readonly relativeTime = input.required<string>();
}
