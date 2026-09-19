import type { DetectionEvent } from '../../api/models';

/**
 * Replaces `EventsStore`'s three private fields (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N7) —
 * `eventsSignal`/`sinceMs`/`activeConsumers`. **Not** `seenIds`: that bookkeeping only ever gates the
 * (impure — reads `Notification.permission`/`document.hidden`) notify decision, never the merged
 * list itself, so it is kept out of serializable state entirely and lives instead as a closure-local
 * `Set` inside `events.effects.ts#notify$`, the one place that already needs to be impure. See that
 * effect's own doc comment for the full reasoning.
 */
export interface EventsState {
  /** Newest-first, capped to `MAX_RETAINED_EVENTS` — `events-logic.ts#mergeEvents`'s own contract. */
  readonly events: readonly DetectionEvent[];
  /** The next poll's `sinceMs` cursor — `undefined` until the first successful poll or live arrival. */
  readonly sinceMs: number | undefined;
  /** Ref-count of mounted consumers (`WallPage`/`AssetDetailPage`/the always-on `NotificationBell`,
   *  plus a handful of read-only ones — `CommandFacade`/`AlertsFacade`/`CockpitFacade` — that never
   *  call `activate()` at all, per each one's own doc comment, relying on some *other* consumer
   *  already having done so). See `events.effects.ts#poll$`'s own doc comment for how this crosses
   *  with `core/live`'s connection state to decide poll-vs-live-vs-idle. */
  readonly activeConsumers: number;
}

export const initialEventsState: EventsState = {
  events: [],
  sinceMs: undefined,
  activeConsumers: 0,
};
