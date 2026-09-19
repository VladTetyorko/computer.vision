import type { GeofenceZone } from '../../api/models';

/**
 * The `geofence` slice's entire state (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N6), replacing
 * `GeofenceStore`. No `@ngrx/entity` here — §3 rule 4 mandates it only for marks/layers/drawings/
 * tracks; a zone's own `id` is already a plain string and this list has no fold-history-dependent
 * ordering to preserve (unlike `tracks`/`marks`), so a bare array round-trips through
 * `create()`/`replace()`/`remove()`/the live fold exactly as `GeofenceStore.zonesSignal` did.
 */
export interface GeofenceState {
  readonly zones: readonly GeofenceZone[];
  /** `true` once the first `refresh()` (activation or poll) has settled — distinguishes "loading" from "genuinely empty". */
  readonly loaded: boolean;
  /** Ref-count of demand (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3) — see `geofence.effects.ts`'s own doc comment. */
  readonly activeConsumers: number;
}

export const initialGeofenceState: GeofenceState = { zones: [], loaded: false, activeConsumers: 0 };

/** Safety-net cadence while active and live is unavailable — `GeofenceStore`'s original `ZONES_POLL_INTERVAL_MS`. */
export const ZONES_POLL_INTERVAL_MS = 30_000;
