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
