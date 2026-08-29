import type { ActiveStream, AssetUsage, DetectionEvent, Device } from '../api/models';
import { humanAge } from '../telemetry/telemetry-logic';

/**
 * Pure derivations behind `core/events-store.ts` and every page that reads it (docs/plans/done/MVP2-PLAN.md
 * §E, E-b: the Wall rail, the asset detail Events section, the fleet map's event markers, and the
 * notification-decision gate) — split out so the merge/cursor/filter/marker-selection/
 * notification-gating logic is unit-testable without HTTP, timers, or the `Notification` API,
 * mirroring `core/telemetry/telemetry-logic.ts`'s split of pure derivation from the injectable that drives
 * it.
 */

// --- Merge, dedupe, cursor advance (sinceMs handling) -----------------------------------------

/**
 * How many events `EventsStore` keeps in memory across polls (docs/plans/done/MVP2-PLAN.md §E, E-b's own
 * "small shared events-store singleton" cost note) — generous for a rail/marker feed, bounded so a
 * long-running session doesn't grow the in-memory list forever.
 */
export const MAX_RETAINED_EVENTS = 200;

/**
 * Merges a fresh poll batch into the already-held event list, upserting by id — `incoming` always
 * wins over `existing` for a shared id, since `EventController#recent`'s `sinceMs` cursor means a
 * repeated id is always a fresher read of the same event (an OPEN event's `lastSeen`/
 * `peakConfidence` advancing, or an OPEN→CLOSED transition), never a stale duplicate.
 *
 * Re-sorts the merged set newest-first by `lastSeen` (matching `EventController`'s own ordering)
 * rather than trusting concatenation order, since `existing` and `incoming` are each individually
 * sorted but not merged — and trims to `maxRetained`, dropping the *oldest* entries first.
 */
export function mergeEvents(
  existing: readonly DetectionEvent[],
  incoming: readonly DetectionEvent[],
  maxRetained: number = MAX_RETAINED_EVENTS,
): readonly DetectionEvent[] {
  const byId = new Map<string, DetectionEvent>();
  for (const event of existing) {
    byId.set(event.id, event);
  }
  for (const event of incoming) {
    byId.set(event.id, event);
  }
  return [...byId.values()]
    .sort((a, b) => Date.parse(b.lastSeen) - Date.parse(a.lastSeen))
    .slice(0, maxRetained);
}

/**
 * The next polling cursor: the newest `lastSeen` seen so far, across every poll — `sinceInclusive`
 * on the wire (`DetectionEventRepositoryPort#findRecent`'s own doc comment) is genuinely
 * *inclusive*, so re-requesting the exact instant of the last-seen event on the next poll is
 * correct, not an off-by-one risk — an event whose `lastSeen` hasn't advanced past it simply won't
 * reappear (nothing new to say), and one that has (still-OPEN, still qualifying) will, which is
 * exactly the "steady-state poll only ever receives genuinely new activity" contract
 * `EventController`'s own doc comment describes.
 *
 * `incoming` is assumed newest-first (`EventController`'s contract), so its first element already
 * carries the batch's own newest `lastSeen` — no need to scan the whole batch.
 */
export function advanceCursor(
  currentSinceMs: number | undefined,
  incoming: readonly DetectionEvent[],
): number | undefined {
  if (incoming.length === 0) {
    return currentSinceMs;
  }
  const newestMs = Date.parse(incoming[0].lastSeen);
  return currentSinceMs === undefined ? newestMs : Math.max(currentSinceMs, newestMs);
}

// --- Filtering ----------------------------------------------------------------------------------

export interface EventFilter {
  readonly label?: string;
  readonly assetId?: string;
}

/** Applies an optional label/asset filter — an absent field in `filter` matches everything. */
export function filterEvents(
  events: readonly DetectionEvent[],
  filter: EventFilter,
): readonly DetectionEvent[] {
  return events.filter(
    (event) =>
      (filter.label === undefined || event.label === filter.label) &&
      (filter.assetId === undefined || event.assetId === filter.assetId),
  );
}

/** The distinct labels present in `events`, alphabetical — backs the rail's label filter `<select>`. */
export function distinctLabels(events: readonly DetectionEvent[]): readonly string[] {
  return [...new Set(events.map((event) => event.label))].sort((a, b) => a.localeCompare(b));
}

// --- Human-facing derivations --------------------------------------------------------------------

/** Capitalizes a raw label (`"person"` → `"Person"`) for display — the wire value stays lowercase. */
export function capitalizeLabel(label: string): string {
  return label.length === 0 ? label : label[0].toUpperCase() + label.slice(1);
}

/** `peakConfidence` (0..1) as a whole-percent string, e.g. `"87%"`. */
export function formatConfidence(peakConfidence: number): string {
  return `${Math.round(peakConfidence * 100)}%`;
}

/**
 * A short "n ago" label for `atIso` relative to `nowMs`, e.g. `"12s ago"`, `"4m ago"`, `"4h 2m ago"` —
 * reuses `core/telemetry/telemetry-logic.ts#humanAge`, this app's one age vocabulary
 * (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N4), rather than `stream-info-logic.ts#formatDuration`'s
 * zero-padded, hour-capped rendering — an event can legitimately be days old (a removed device's
 * last-seen event), and a raw `323353s ago` is a number nobody parses. `formatDuration` stays this
 * app's one *duration* renderer (a ticking session length); this is an *age*, and every age reader
 * in this app now agrees on formatting.
 */
export function relativeTimeLabel(atIso: string, nowMs: number): string {
  const seconds = Math.max(0, (nowMs - Date.parse(atIso)) / 1000);
  return `${humanAge(seconds)} ago`;
}

/**
 * The minimal shape {@link describeEventSource} needs — satisfied structurally by both
 * `DetectionEvent` (`streamId` always present) and `LiveEvent` (`streamId` optional, no `assetId`
 * at all — docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2's system-event feed reuses this rather than
 * duplicating the identical device-name resolution as a second copy in `core/system-events/**`).
 */
export interface EventSourceIdentifiers {
  readonly streamId?: string;
  readonly assetId?: string;
}

/**
 * A friendly name for the source behind an event — the device streaming it, when resolvable via
 * the fleet's currently-active streams, falling back to a short id fragment. Neither `DetectionEvent`
 * nor `LiveEvent` carries a display name of its own, so this always needs `FleetStore`'s own
 * `devices`/`streams` snapshots to do better than a raw id.
 *
 * When neither the device nor its stream can be resolved (the event references a device or asset
 * that no longer exists in the fleet — every row in `/monitor/alerts` after a device is archived,
 * docs/plans/active/OPERATOR-UX-4-PLAN.md finding N5), the fallback reads `'Removed device · 7fd88790'`,
 * never a bare, unlabeled hash — a raw id fragment with no word next to it reads as a name, not as
 * "this device is gone". A device-level event with neither a `streamId` nor an `assetId` (e.g.
 * `LiveEvent` for `DEVICE_ONLINE`/`DEVICE_OFFLINE` — see `Event.java`'s own "`streamId` nullable …
 * device-level events" doc comment) still degrades to `'—'` — there is no id at all to name as
 * removed.
 */
export function describeEventSource(
  event: EventSourceIdentifiers,
  devices: readonly Device[],
  streams: readonly ActiveStream[],
): string {
  const stream = event.streamId ? streams.find((candidate) => candidate.streamId === event.streamId) : undefined;
  const device = stream ? devices.find((candidate) => candidate.id === stream.deviceId) : undefined;
  if (device) {
    return device.name;
  }
  const fallback = event.assetId ?? event.streamId;
  return fallback ? `Removed device · ${fallback.slice(0, 8)}` : '—';
}

// --- Navigation target (Wall rail / map marker popup "open" action) ---------------------------

export interface EventNavTarget {
  /** `'asset'` navigates to the asset detail page; `'live'` to the single-device cockpit. */
  readonly kind: 'asset' | 'live';
  readonly id: string;
}

/**
 * Where clicking an event should navigate: the owning asset's detail page when `assetId` resolved
 * (E-a's own doc comment: absent "if unresolvable"), else the device's live cockpit when the
 * event's stream is still active in `streams` (a device with no asset yet can still be watched
 * live), else `undefined` — nothing to navigate to, the caller renders the event as unclickable
 * rather than guessing.
 */
export function resolveEventTarget(
  event: DetectionEvent,
  streams: readonly ActiveStream[],
): EventNavTarget | undefined {
  if (event.assetId) {
    return { kind: 'asset', id: event.assetId };
  }
  const stream = streams.find((candidate) => candidate.streamId === event.streamId);
  return stream ? { kind: 'live', id: stream.deviceId } : undefined;
}

// --- Event → replay deep link (docs/plans/done/OPS-CORE-PLAN.md §Q1) --------------------------------------

/**
 * The usage from `usages` whose own window covers `atIso` — an open usage's window runs to `now`
 * (mirrors `features/replay/replay.ts`'s own "open one = watch live instead" framing: a usage with
 * no `endedAt` yet is still "covering" the present moment). `undefined` when nothing covers it
 * (a gap between usages, or an event that predates every retained usage).
 */
export function findCoveringUsage(usages: readonly AssetUsage[], atIso: string): AssetUsage | undefined {
  const atMs = Date.parse(atIso);
  return usages.find((usage) => {
    const startMs = Date.parse(usage.startedAt);
    const endMs = usage.endedAt !== undefined ? Date.parse(usage.endedAt) : Date.now();
    return atMs >= startMs && atMs <= endMs;
  });
}

export interface ReplayDeepLink {
  readonly usageId: string;
  /** Milliseconds from the covering usage's own `startedAt` to the event's `firstSeen` — never negative. */
  readonly offsetMs: number;
}

/**
 * Resolves the `/replay?asset=…&usage=…&t=…` deep link for `event` (docs/plans/done/OPS-CORE-PLAN.md §Q1),
 * given the owning asset's already-fetched `recentUsages` (a lazy, click-time-only lookup — see
 * `shared/ui/notification-bell.ts`'s own doc comment for why this is never done per-row on render).
 * `undefined` when no usage covers the event's own `firstSeen`, **or** when the covering usage is
 * still open — an open usage's replay page redirects to "Watch live" instead of rendering
 * (`features/replay/replay.ts`'s own R-b rule), so linking there would just be an extra hop; the
 * caller's existing `resolveEventTarget` fallback (which already offers "Watch live" for a live
 * stream) is the more direct answer in that case.
 */
export function resolveReplayDeepLink(event: DetectionEvent, recentUsages: readonly AssetUsage[]): ReplayDeepLink | undefined {
  const usage = findCoveringUsage(recentUsages, event.firstSeen);
  if (!usage || usage.endedAt === undefined) {
    return undefined;
  }
  const offsetMs = Math.max(0, Date.parse(event.firstSeen) - Date.parse(usage.startedAt));
  return { usageId: usage.usageId, offsetMs };
}

// --- Map markers ----------------------------------------------------------------------------

/** How many position-carrying events the fleet map plots at once — "most recent N", not a full history. */
export const MAX_EVENT_MARKERS = 30;

/**
 * The events worth plotting on the fleet map: only ones carrying a `position` (E-a's own "best
 * effort, absent if unavailable" — most events never get one, e.g. an asset with no telemetry
 * device), capped to the `max` most recent. `events` is assumed already newest-first (every reader
 * of `EventsStore.events()` gets that for free from `mergeEvents`), so this is a filter + slice,
 * not a re-sort.
 */
export function selectEventMarkers(
  events: readonly DetectionEvent[],
  max: number = MAX_EVENT_MARKERS,
): readonly DetectionEvent[] {
  const withPosition = events.filter((event) => event.position !== undefined);
  return withPosition.slice(0, max);
}

// --- Notification decision (new-open-event × permission × hidden) -----------------------------

export interface NotificationDecisionInput {
  readonly event: DetectionEvent;
  /**
   * Whether this event's id has already been observed in a previous poll (regardless of whether a
   * notification fired for it then) — the dedupe key. An event already seen while the tab was
   * visible (and therefore already shown in the rail) must never notify later just because the tab
   * happened to go background afterward; this flag is what prevents that, not a separate "already
   * notified" set.
   */
  readonly alreadySeen: boolean;
  /** `SettingsStore.eventNotifications()` — the user's own opt-in toggle. */
  readonly notificationsEnabled: boolean;
  /** The browser's own grant — flipping `notificationsEnabled` alone can never bypass this. */
  readonly permission: NotificationPermission;
  /**
   * `document.hidden` at decision time. The visible app already shows the newly-seen event in the
   * rail — a notification is only for when the user isn't looking (docs/plans/done/MVP2-PLAN.md §E, E-b:
   * "only when document.hidden").
   */
  readonly documentHidden: boolean;
}

/**
 * Whether a genuinely new, currently-OPEN event should fire a browser `Notification` — every gate
 * from docs/plans/done/MVP2-PLAN.md §E, E-b's own bullet 4 ("new-open-event × permission × hidden"), each
 * independently necessary:
 *
 * - `!alreadySeen` — dedupe by event id (a still-OPEN event reappears on every poll while its
 *   `lastSeen` keeps advancing; only its first-ever appearance counts as "new").
 * - `event.state === 'OPEN'` — a CLOSED event arriving for the first time (e.g. right after a
 *   session begins, or a stream whose event closed between two polls) is history, not news.
 * - `notificationsEnabled` — the user's own opt-in.
 * - `permission === 'granted'` — the browser's own opt-in; `'default'`/`'denied'` both refuse.
 * - `documentHidden` — the visible app already shows it in the rail; see `documentHidden`'s own doc
 *   comment above for why this is the deciding "the user isn't looking" signal.
 */
export function shouldNotify(input: NotificationDecisionInput): boolean {
  return (
    !input.alreadySeen &&
    input.event.state === 'OPEN' &&
    input.notificationsEnabled &&
    input.permission === 'granted' &&
    input.documentHidden
  );
}

export interface EventNotificationText {
  readonly title: string;
  readonly body: string;
}

/** The native `Notification`'s title/body for `event` — plain, no markup (the OS renders it, not us). */
export function eventNotificationText(event: DetectionEvent): EventNotificationText {
  return {
    title: `${capitalizeLabel(event.label)} detected`,
    body: `${formatConfidence(event.peakConfidence)} confidence`,
  };
}
