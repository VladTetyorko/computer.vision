import type { DetectionEvent, LiveEvent } from '../../core/api/models';

/**
 * Pure derivations behind `shared/ui/notification-bell.ts` (docs/plans/done/UX-REWORK-PLAN.md §U-c's user
 * amendments: "Events become notifications" — the header bell's unread count and its toast-worthy
 * subset of a fresh `EventsStore.events()` read), split out so both rules are unit-testable without
 * a component/effect/timer, mirroring every other consumer of `EventsStore`'s own
 * `core/events/events-logic.ts` split.
 */

/**
 * Events not yet acknowledged — the bell's own unread badge count is `unreadEvents(...).length`.
 * `readIds` is the bell's own dropdown-open dedupe set (every event id present the moment the
 * dropdown was last opened gets marked read; see that component's own doc comment) — deliberately
 * **not** `EventsStore`'s own `seenIds` (a private, poll-arrival dedupe with a different job: "has
 * this id ever been fetched", not "has the operator looked at it").
 */
export function unreadEvents(events: readonly DetectionEvent[], readIds: ReadonlySet<string>): readonly DetectionEvent[] {
  return events.filter((event) => !readIds.has(event.id));
}

/**
 * The toast-worthy subset of a fresh `EventsStore.events()` read: genuinely new ids (not in
 * `knownIds`) that are currently `OPEN` — mirrors `core/events/events-logic.ts#shouldNotify`'s own
 * `!alreadySeen && event.state === 'OPEN'` pair, but for this app's always-on in-app toast rather
 * than the opt-in native browser `Notification`. Deliberately **no** permission/hidden gating the
 * way `shouldNotify` has: those two gates exist only because a native OS notification is for when
 * the user *isn't looking at the tab at all*; a toast is this app's own chrome, inert unless the
 * tab itself is open, so there is no "user isn't looking" case left to gate on.
 */
export function newlyOpenedEvents(
  events: readonly DetectionEvent[],
  knownIds: ReadonlySet<string>,
): readonly DetectionEvent[] {
  return events.filter((event) => !knownIds.has(event.id) && event.state === 'OPEN');
}

/**
 * Whether a `LiveEvent` (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U4, §2 U4 — today the
 * geofence-breach toast effect's own input) is actually news, not history replaying late.
 *
 * **Root cause this fixes.** The breach effect's old "seed silently on the first tick, toast
 * everything after" idiom (mirroring `toastedIds`/`seededToasts` above) assumed
 * `LiveStore.liveEvents()` was already fully populated the instant that first effect run happened.
 * It isn't, reliably: the SSE connection's own snapshot/backlog can arrive on a *later* tick than
 * the bell's first render, so a historic breach — an `enter` event for an asset that has been
 * offline for days — lands in the "not yet seeded" set and fires as if it had just happened live.
 * Reproduced live (`http://localhost:4200`, any page load): a `KEEP-IN breach — Demo operating
 * area` toast for an asset that hasn't reported in days.
 *
 * **The fix is two independent, honest gates, both required — a construction fix, not a better
 * dedup set**: the event's own `at` must be no older than the instant this bell mounted (a plain
 * timestamp comparison, immune to *when* the event happens to arrive over the wire — unlike the
 * old seed, it can't be fooled by an SSE replay landing on a later tick), and the asset the event
 * concerns must be currently streaming (an offline asset cannot be having something happen to it
 * *right now*, whatever the event's own timestamp claims — the second, independent honesty check
 * this finding's own design section names).
 */
export function shouldToast(event: LiveEvent, mountedAtMs: number, assetStreaming: boolean): boolean {
  return assetStreaming && Date.parse(event.at) >= mountedAtMs;
}

// --- Read-ids persistence (docs/plans/active/OPERATOR-UX-7-PLAN.md finding B1) --------------------

/**
 * `localStorage` key for the bell's own persisted "already read" event-id set
 * (`notification-bell.ts`'s `readIds` signal). `core/panel-state.ts` has no JSON-array-shaped
 * helper of its own — only a boolean flag (`readPersistedFlag`/`writePersistedFlag`) and a plain
 * string (`readPersistedString`/`writePersistedString`) — so the bell reads/writes its own
 * JSON-encoded array *through* those two string primitives directly, guarded with try/catch in
 * `notification-bell.ts` (see that file's own `loadPersistedReadIds`/`persistReadIds`): the same
 * storage access pattern every other per-component `localStorage` preference in this app already
 * uses, just carrying a richer payload than a flag.
 */
export const BELL_READ_IDS_KEY = 'vision.bell.readIds';

/** How many read ids `notification-bell.ts` persists at most — generous for a real session's worth
 *  of activity, bounded so a browser profile left open for months doesn't grow this key forever. */
export const BELL_READ_IDS_CAP = 500;

/**
 * The bell's own persisted "already read" set (finding B1: an in-memory-only `readIds` marked every
 * historic event unread again on every reload — `9+` on a station where nothing has happened for
 * days is a badge nobody reads).
 *
 * `persisted === null` means genuinely nothing was ever saved for this browser profile — a cold
 * start, not "the operator has read zero events" (an explicit, previously-persisted empty array is a
 * real, distinct state — everything currently known really is unread, and that's honest, not a bug
 * to paper over). Only the cold-start case seeds from `events`, mirroring `notification-bell.ts`'s
 * own pre-existing `toastedIds`/`seededToasts` idiom for toast eligibility: whatever is already
 * present the very first time this runs is history, not news, so it starts "read" rather than
 * lighting up the badge with a burst of pre-existing activity the instant the app loads (the same
 * "history is not news" rule `shouldToast` above enforces for the breach toast, finding U4). Once a
 * persisted set exists — any later boot — it is trusted as-is; a genuinely new event id arriving
 * after that point is correctly excluded from it, which is the entire point.
 */
export function seedReadIds(
  events: readonly DetectionEvent[],
  persisted: readonly string[] | null,
): ReadonlySet<string> {
  if (persisted !== null) {
    return new Set(persisted);
  }
  return new Set(events.map((event) => event.id));
}

/**
 * Caps a persisted read-id list to the newest `cap` entries. `ids` is assumed ordered newest-first —
 * `notification-bell.ts` always builds it from `EventsStore.events()`, itself newest-first per
 * `core/events/events-logic.ts#mergeEvents`'s own sort — so "newest" is simply "keep the front, drop
 * the tail", the same direction that ordering already runs in.
 */
export function pruneReadIds(ids: readonly string[], cap: number = BELL_READ_IDS_CAP): readonly string[] {
  return ids.length <= cap ? ids : ids.slice(0, cap);
}

// --- Removed-device source (docs/plans/active/OPERATOR-UX-7-PLAN.md finding W1) --------------------

/** The literal prefix `core/events/events-logic.ts#describeEventSource` renders once neither an
 *  event's stream nor its device resolves in the fleet's current snapshot — i.e. the device behind
 *  it has since been removed/archived. */
export const REMOVED_DEVICE_SOURCE_PREFIX = 'Removed device';

/**
 * Whether a resolved event source name (`describeEventSource`'s own return value) names a removed
 * device — `shared/ui/events-rail.ts`'s own default filter (finding W1: a live page's rail showing
 * 100% history from devices that no longer exist, the newest row nine days old). Deliberately reuses
 * `describeEventSource`'s already-computed string rather than re-running its device/stream lookup a
 * second time — that function's own fallback already *is* the fact this needs, so there is nothing
 * left to resolve, only to name. Lives here rather than `core/events/events-logic.ts` per this wave's
 * own file scope (`shared/ui/**` only) — `events-rail.ts` has no paired `*-logic.ts`/`.spec.ts` of
 * its own to hold a pure helper, and this file already is one.
 */
export function isRemovedDeviceSource(sourceLabel: string): boolean {
  return sourceLabel.startsWith(REMOVED_DEVICE_SOURCE_PREFIX);
}
