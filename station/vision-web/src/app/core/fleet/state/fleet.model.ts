import type { EntityState } from '@ngrx/entity';
import { createEntityAdapter } from '@ngrx/entity';
import type { ActiveStream, CvModel, CvTracker, Device } from '../../api/models';

/**
 * `@ngrx/entity` for `Device`/`ActiveStream` (docs/plans/active/NGRX-MIGRATION-PLAN.md §3 rule 4 —
 * both named explicitly there). **No `sortComparer` on either**: `FleetStore` never sorted its two
 * lists — a poll/live snapshot lands in whatever order the server returned, and `setAll` preserves
 * that order in `selectAll` exactly like a plain signal assignment did, same "no comparator, no
 * hidden reordering" precedent as `marks.model.ts`/`layers.model.ts`.
 */
export const devicesAdapter = createEntityAdapter<Device>({ selectId: (device) => device.id });
export const streamsAdapter = createEntityAdapter<ActiveStream>({ selectId: (stream) => stream.streamId });

/**
 * Replaces `FleetStore`'s six private signals. **Not** `stopPollingFn`/`liveGated` — those are pure
 * poll-vs-live bookkeeping local to `fleet.effects.ts#gate$`'s own closure, never state a selector
 * needs to read (same "keep impure/ephemeral bookkeeping out of the store" precedent as
 * `events.model.ts`'s `seenIds` doc comment).
 */
export interface FleetState {
  readonly devices: EntityState<Device>;
  readonly streams: EntityState<ActiveStream>;
  /** `true` only while the *explicit* `refresh()` call (the Devices page's own "Refresh" button) is
   *  in flight — the background poll/live paths never touch this, see `fleet.effects.ts`'s own doc
   *  comment for why that split is a deliberate simplification, not an oversight. */
  readonly loading: boolean;
  /** `null` until the first request ever settles — see `FleetStore`'s own doc comment. */
  readonly reachable: boolean | null;
  /** The CV model roster — fetched once at boot, permanently empty on failure. See `fleet.effects.ts#loadRosters$`. */
  readonly models: readonly CvModel[];
  /** The tracker-engine roster — same one-shot, permanently-empty-on-failure shape as {@link models}. */
  readonly trackers: readonly CvTracker[];
}

export const initialFleetState: FleetState = {
  devices: devicesAdapter.getInitialState(),
  streams: streamsAdapter.getInitialState(),
  loading: false,
  reachable: null,
  models: [],
  trackers: [],
};

/** How often devices/streams are re-read while not live — `FleetStore`'s own `POLL_INTERVAL_MS`. */
export const FLEET_POLL_INTERVAL_MS = 5_000;
