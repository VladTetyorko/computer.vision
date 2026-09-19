import { createEntityAdapter, type EntityState } from '@ngrx/entity';
import type { ProjectedTrackResponse } from '../../api/models';
import { trackKey } from '../../camera-geo/camera-geo-logic';

/**
 * `@ngrx/entity` for `ProjectedTrackResponse` (docs/plans/active/NGRX-MIGRATION-PLAN.md §3 rule 4 —
 * mandatory for this slice). `selectId` reuses `trackKey`, the same `(assetId, trackId)` composite
 * identity `camera-geo-logic.ts#applyTrackEvent`/`trackChipLabel` already key by — never the bare
 * `trackId`, which is only unique per asset, not across the whole fleet.
 *
 * **No `sortComparer`**: unlike a field you could sort by, this list's order is fold-history
 * dependent — `applyTrackEvent`'s own `[...tracks.filter(...), merged]` deliberately moves a
 * just-updated track to the end, which a comparator over the data alone could never reproduce. The
 * reducer instead re-derives the *whole* array through the existing pure `applyTrackEvent` fold and
 * hands the result to `setAll`, which keeps exactly the order it was given — see `tracks.reducer.ts`.
 */
export const tracksAdapter = createEntityAdapter<ProjectedTrackResponse>({
  selectId: (track) => trackKey(track.assetId, track.trackId),
});

export interface TracksState extends EntityState<ProjectedTrackResponse> {
  /** `true` once the first `GET /api/map/tracks` has settled (success or failure) — `TracksStore`'s own `loaded`. */
  readonly loaded: boolean;
  /** Ref-count of demand (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3) — see `tracks.effects.ts`'s own doc comment for how this drives the poll/live gate. */
  readonly activeConsumers: number;
}

export const initialTracksState: TracksState = tracksAdapter.getInitialState({
  loaded: false,
  activeConsumers: 0,
});

/** Safety-net cadence while active and live is unavailable — see `TracksStore`'s original class doc. */
export const TRACKS_POLL_INTERVAL_MS = 30_000;
