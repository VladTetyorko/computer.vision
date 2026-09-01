import { describeEventSource } from '../events/events-logic';
import type { ActiveStream, Device, LiveEvent } from '../api/models';

/**
 * Pure, Angular-free logic behind `core/system-events/system-events-store.ts` and the notification
 * bell's own system-events section (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2-§3.4, wave S1) — maps the
 * generic `LiveEvent` feed (`core/live/live-store.ts#liveEvents`, the `event` SSE topic) into display
 * rows and derives the one cross-cutting reading `core/fleet/attention-logic.ts` needs from it (an
 * active, undecayed `PIPELINE_ERROR` per stream).
 *
 * **Why this exists as its own module, not folded into `core/events/events-logic.ts`**: that file is
 * `DetectionEvent`-only by design (its own doc comment: "this feed is detection events only" —
 * `docs/plans/done/MVP2-PLAN.md §E-a's own scoping note) and stays that way — `LiveEvent` is the *other*,
 * older, generic domain `Event` (`STREAM_STARTED`/`DEVICE_ONLINE`/`PIPELINE_ERROR`/…, see `LiveEvent`'s
 * own doc comment in `core/api/models.ts`), arriving on a different SSE topic with no relation to
 * `DetectionEvent`'s `OPEN`/`CLOSED`/`peakConfidence` shape. Mixing the two into one module would
 * blur exactly the distinction `SYSTEM-STATUS-PLAN.md §1.1` is about: detections already have a
 * surface (`EventsStore` → bell/rail/alerts); this module is for the other event kinds (nine as of
 * S4's `LINK_LOST`/`BATTERY_LOW`, docs/plans/active/ASSET-FLOWS-PLAN.md §2), minus `DETECTION`
 * itself, which is deliberately excluded here too (see {@link toSystemEventRow}).
 */

/** The three severities a system event row can carry — same three-tier vocabulary as `<vision-notice>`. */
export type SystemEventSeverity = 'danger' | 'warn' | 'neutral';

/**
 * `EventType`'s own severity, per docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2's frozen table: `PIPELINE_ERROR`/
 * `GEOFENCE_BREACH` are `danger` (a broken perception pipeline or a physical boundary crossing, both
 * "this needs a person right now"); `DEVICE_OFFLINE` is `warn` (a piece of hardware went quiet, worth
 * knowing, not yet a crisis); `DEVICE_ONLINE`/`STREAM_STARTED`/`STREAM_STOPPED`/`TRAINING` are
 * `neutral` (routine lifecycle noise, still worth a durable record, never colored as a problem).
 * `DETECTION` is intentionally absent — see {@link toSystemEventRow}.
 *
 * **`LINK_LOST`/`BATTERY_LOW` (S4, docs/plans/active/ASSET-FLOWS-PLAN.md §2)** are both `danger` —
 * `LINK_LOST` is a genuinely failed telemetry source (`LinkLossNotifier`, never an ordinary heartbeat
 * miss or an intentional stream stop — see `platform.EventType#LINK_LOST`'s own javadoc), and
 * `BATTERY_LOW` is the rising edge of a battery crossing the served critical threshold
 * (`BatteryMonitor`) — both are "this asset needs a person right now" facts, the same tier as
 * `GEOFENCE_BREACH`.
 */
const SEVERITY_BY_TYPE: Readonly<Record<string, SystemEventSeverity>> = {
  PIPELINE_ERROR: 'danger',
  GEOFENCE_BREACH: 'danger',
  LINK_LOST: 'danger',
  BATTERY_LOW: 'danger',
  DEVICE_OFFLINE: 'warn',
  DEVICE_ONLINE: 'neutral',
  STREAM_STARTED: 'neutral',
  STREAM_STOPPED: 'neutral',
  TRAINING: 'neutral',
};

/** Sentence-case display titles for the same nine types — `event.message` (the backend's own
 *  human sentence, e.g. `"RTSP source unreachable"`) is the row's `detail`, this is only the row's
 *  short, scannable heading. */
const TITLE_BY_TYPE: Readonly<Record<string, string>> = {
  PIPELINE_ERROR: 'Pipeline error',
  GEOFENCE_BREACH: 'Geofence breach',
  LINK_LOST: 'Link lost',
  BATTERY_LOW: 'Battery low',
  DEVICE_OFFLINE: 'Device offline',
  DEVICE_ONLINE: 'Device online',
  STREAM_STARTED: 'Stream started',
  STREAM_STOPPED: 'Stream stopped',
  TRAINING: 'Training',
};

/**
 * `SCREAMING_SNAKE_CASE` → `"Screaming snake case"` — the forward-compat fallback for a `type` this
 * module doesn't recognize yet (`LiveEvent.type` is a plain string, not a closed union — see that
 * field's own doc comment for why: it mirrors the Java `EventType` enum's `name()` without tracking
 * it by hand). Never reached by any `EventType` value that exists today; exists so a future ninth
 * type degrades to a readable label instead of a raw enum constant, the same "tolerate an
 * unrecognized value" rule `core/org/org-logic.ts#formatActivity` already follows for `AuditEntry`.
 */
function humanizeEventType(type: string): string {
  const lower = type.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

function severityForEventType(type: string): SystemEventSeverity {
  return SEVERITY_BY_TYPE[type] ?? 'neutral';
}

function titleForEventType(type: string): string {
  return TITLE_BY_TYPE[type] ?? humanizeEventType(type);
}

/** One `LiveEvent`, mapped to what the bell/log actually render. */
export interface SystemEventRow {
  readonly id: string;
  readonly type: string;
  readonly severity: SystemEventSeverity;
  readonly title: string;
  /** The backend's own human sentence (`LiveEvent.message`) — never re-worded here. */
  readonly detail: string;
  readonly streamId?: string;
  readonly atIso: string;
}

/**
 * Maps one `LiveEvent` to a {@link SystemEventRow}, or `undefined` for `DETECTION` — **deliberately
 * excluded** (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2): detections already have their own surface
 * (`core/events/events-store.ts#EventsStore` → the bell's existing dropdown, the Wall rail, the fleet
 * map's markers, `/monitor/alerts`), and duplicating every detection into this generic log too would
 * be exactly the alert-noise failure mode UX-DESIGN names as fatal — a second, competing feed for the
 * same information, not a second source of truth.
 */
export function toSystemEventRow(event: LiveEvent): SystemEventRow | undefined {
  if (event.type === 'DETECTION') {
    return undefined;
  }
  return {
    id: event.id,
    type: event.type,
    severity: severityForEventType(event.type),
    title: titleForEventType(event.type),
    detail: event.message,
    streamId: event.streamId,
    atIso: event.at,
  };
}

/** How many rows `SystemEventsStore`/the bell's system-events section keep on screen at once —
 *  mirrors `shared/ui/events-rail.ts#EVENTS_DISPLAY_LIMIT`'s identical "recent activity, not the
 *  full retained history" cap. */
export const SYSTEM_EVENTS_DISPLAY_LIMIT = 20;

/**
 * `events` → display rows, `DETECTION` dropped, capped to `max`. Assumes `events` is already
 * newest-first — `LiveStore.liveEvents()`'s own contract (it prepends on arrival, see that signal's
 * own doc comment), so no re-sort is needed here.
 */
export function systemEventRows(
  events: readonly LiveEvent[],
  max: number = SYSTEM_EVENTS_DISPLAY_LIMIT,
): readonly SystemEventRow[] {
  const rows: SystemEventRow[] = [];
  for (const event of events) {
    const row = toSystemEventRow(event);
    if (row) {
      rows.push(row);
      if (rows.length >= max) {
        break;
      }
    }
  }
  return rows;
}

/**
 * A friendly source name for a {@link SystemEventRow} — a thin pass-through to
 * `core/events/events-logic.ts#describeEventSource` (generalized this same wave to accept either
 * `DetectionEvent` or `LiveEvent`'s shape) rather than a second copy of the identical
 * stream→device-name resolution.
 */
export function describeSystemEventSource(
  row: Pick<SystemEventRow, 'streamId'>,
  devices: readonly Device[],
  streams: readonly ActiveStream[],
): string {
  return describeEventSource(row, devices, streams);
}

// --- `pipeline-error` attention reason (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.4) ----------------------

/**
 * How long an undecayed `PIPELINE_ERROR` stays an active attention reason once nothing else has
 * cleared it (see {@link activePipelineErrorMessagesByStreamId}'s own doc comment for the full decay
 * rule). 15 minutes: long enough that a genuinely-still-broken pipeline stays flagged through a
 * normal "glance at Command every few minutes" operator workflow, short enough that a stale error
 * from earlier in a long session doesn't glow forever once nothing else ever arrives to clear it.
 * This is a documented heuristic, not real per-stream health — `SYSTEM-STATUS-PLAN.md §2`'s own
 * non-goal defers the structured per-stream health field (whether the pipeline is *actually* still
 * broken right now) to S2b, after `StreamPipeline`'s own K3 decomposition; until then, an event log
 * is the honest ceiling on what this app can say.
 */
export const PIPELINE_ERROR_ATTENTION_WINDOW_MS = 15 * 60 * 1000;

/**
 * `streamId → the active PIPELINE_ERROR's own message`, for `core/fleet/attention-logic.ts`'s
 * `pipeline-error` reason — exactly the `activeGeofenceBreaches` pattern
 * (`core/geofence/geofence-logic.ts`) applied to a different enter/exit-shaped pair of event types:
 *
 * - A `PIPELINE_ERROR` for a stream opens the concern; a later `STREAM_STARTED` for the *same*
 *   `streamId` clears it (an explicit "this stream came back up" signal — mirrors a geofence
 *   breach's own `exit` clearing an `enter`). `STREAM_STOPPED` does **not** clear it by itself
 *   (a stream that stopped *because* it was erroring should still read as flagged until it's
 *   genuinely restarted) — but in practice this rarely matters, since `AssetAttention.streamId` is
 *   `undefined` the moment `streaming` goes `false` (see that field's own Java doc comment), so the
 *   reason stops rendering for that asset regardless the instant it stops streaming; it only still
 *   matters if a *different* asset's device somehow reused the same `streamId` before a
 *   `STREAM_STARTED` for it arrived, which the backend's own id generation makes practically moot.
 * - Absent either signal, a `PIPELINE_ERROR` older than {@link PIPELINE_ERROR_ATTENTION_WINDOW_MS}
 *   decays on its own — a point-in-time event is not a permanent level, and a reason that never
 *   clears is worse than no reason at all (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.4's own framing).
 *
 * `events` is assumed newest-first (see {@link systemEventRows}) — a single forward scan finds each
 * `streamId`'s most recent `STREAM_STARTED`-or-`PIPELINE_ERROR` in one pass, exactly like
 * `activeGeofenceBreaches`'s own "first-seen-per-key-wins while scanning newest-first" idiom.
 */
export function activePipelineErrorMessagesByStreamId(
  events: readonly LiveEvent[],
  nowMs: number,
): ReadonlyMap<string, string> {
  const seenStreamIds = new Set<string>();
  const active = new Map<string, string>();
  for (const event of events) {
    if (event.streamId === undefined) {
      continue;
    }
    if (event.type !== 'STREAM_STARTED' && event.type !== 'PIPELINE_ERROR') {
      continue;
    }
    if (seenStreamIds.has(event.streamId)) {
      continue;
    }
    seenStreamIds.add(event.streamId);
    if (event.type !== 'PIPELINE_ERROR') {
      continue; // STREAM_STARTED is the clearing signal — nothing to record for this stream.
    }
    if (nowMs - Date.parse(event.at) > PIPELINE_ERROR_ATTENTION_WINDOW_MS) {
      continue; // decayed — see this function's own doc comment.
    }
    active.set(event.streamId, event.message);
  }
  return active;
}
