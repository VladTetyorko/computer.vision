import type { DetectionEvent } from '../../core/api/models';

/**
 * Pure derivations behind `shared/ui/notification-bell.ts` (docs/UX-REWORK-PLAN.md §U-c's user
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
